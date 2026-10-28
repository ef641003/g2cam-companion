package com.ef64nagaoka.g2cam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * カメラを保持するフォアグラウンドサービス（種別 camera。録画の音声が許可されていれば microphone も）。
 * 画面が消えても、映像の配信・撮影・録画が止まらない。
 * 開始は「アプリが画面に出ている間」に行うこと（Android 11 以降の制約）。
 */
class CameraService : LifecycleService(), CameraController {

    companion object {
        const val PORT = 8787
        const val ACTION_STOP = "com.ef64nagaoka.g2cam.STOP"
        const val ACTION_STOP_RECORDING = "com.ef64nagaoka.g2cam.STOP_RECORDING"
        /** G2 カメラからの要求が途絶えてから自動で止めるまで（ミリ秒） */
        private const val IDLE_STOP_MS = 120_000L
        private const val CHANNEL_ID = "g2cam"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "G2Cam"
        private const val RELATIVE_DIR = "DCIM/G2Camera"

        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var framesServed = 0L
        @Volatile var photosTaken = 0
        /** 画面表示用：録画中なら「0:12」のような経過時間、録画していなければ null */
        @Volatile var recordingLabel: String? = null
    }

    // ── CameraController ──
    @Volatile private var facingValue = "environment"
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    override val facing: String get() = facingValue
    override val frameWidth: Int get() = frameW
    override val frameHeight: Int get() = frameH

    @Volatile private var zoomNow = 1f
    override val zoom: Float get() = zoomNow
    override val zoomMin: Float get() = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    override val zoomMax: Float get() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /** サービス開始時に microphone 種別を付けられたか。付いていないと背景でマイクが使えない（Android 11 以降） */
    @Volatile private var micServiceType = false
    override val audioPermitted: Boolean
        get() = micServiceType && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val captureLock = Any()
    private val recordLock = Any()

    private var server: CompanionServer? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var rotation = Surface.ROTATION_0

    // 最新フレーム。seq は増えていく通し番号（サービスを再起動しても戻らないよう、時刻から始める）
    private val frameLock = ReentrantLock()
    private val frameArrived = frameLock.newCondition()
    @Volatile private var latestFrame: Frame? = null
    private var frameSeq = System.currentTimeMillis()
    override val hasFrame: Boolean get() = latestFrame != null
    private var lastFrameAt = 0L

    // 録画
    @Volatile private var recordingState: RecordingState? = null
    override val recording: RecordingState? get() = recordingState
    private var activeRecording: Recording? = null
    private var recordingThumb: ByteArray? = null
    private var finalizeLatch: CountDownLatch? = null
    @Volatile private var finalizeEvent: VideoRecordEvent.Finalize? = null
    private var recordLimitTimer: Runnable? = null
    /** このサービスで録画したファイル（名前 → URI）。/file で返すときに使う */
    private val recentFiles = HashMap<String, Uri>()
    /** G2 カメラ側がまだ受け取っていない録画結果（最長時間や通知で止めたとき） */
    @Volatile private var pendingResult: RecordResult? = null

    // G2 カメラ側とのつながり（終了に合わせた自動停止）
    @Volatile private var lastClientAt = 0L
    @Volatile private var clientSeen = false
    private val idleWatchdog = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastClientAt
            if (clientSeen && idle > IDLE_STOP_MS && Prefs.autoStop(this@CameraService)) {
                Log.i(TAG, "G2 カメラからの応答が ${idle / 1000} 秒途絶えたので停止します")
                shutdownGracefully()
                return
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                shutdownGracefully()
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> {
                ioExecutor.execute { stopRecordingInternally("通知") }
                return START_NOT_STICKY
            }
        }
        if (!running) {
            if (!startInForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            lastError = null
            framesServed = 0
            photosTaken = 0
            acquireWakeLock()
            startServer()
            startOrientationListener()
            bindCamera(null, null)
            clientSeen = false
            mainHandler.postDelayed(idleWatchdog, 5000)
        }
        // システムが勝手に再起動すると背景からのカメラ開始になり失敗するので、再起動はしない
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        recordingLabel = null
        mainHandler.removeCallbacks(idleWatchdog)
        recordLimitTimer?.let { mainHandler.removeCallbacks(it) }
        orientationListener?.disable()
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy() // カメラの unbind を先に済ませてから実行系を止める
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    /** 録画中なら保存してから止める。G2 カメラの終了・通知の「停止」・応答の途絶えから呼ばれる */
    private fun shutdownGracefully() {
        if (activeRecording == null) {
            stopSelf()
            return
        }
        ioExecutor.execute {
            try {
                stopRecordingInternally("サービス停止")
            } finally {
                mainHandler.post { stopSelf() }
            }
        }
    }

    // ── フォアグラウンド化 ──
    private fun buildNotification(recordingText: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("G2 カメラ コンパニオン")
            .setContentText(recordingText ?: "カメラを保持しています（127.0.0.1:$PORT）")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recordingText != null) {
            val stopRec = PendingIntent.getService(
                this, 2, Intent(this, CameraService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "録画停止", stopRec).build())
        }
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "停止", stop).build())
        return builder.build()
    }

    private fun updateNotification(recordingText: String?) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(recordingText))
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 カメラ", NotificationManager.IMPORTANCE_LOW))
        // 録画の音声を使うには、サービスの種別に microphone も要る（Android 14 以降。権限が無いと指定できない）
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(null), types)
            micServiceType = micGranted
            true
        } catch (e: Exception) {
            // 背景からの開始、カメラ権限なし、など
            lastError = "フォアグラウンド開始に失敗: ${e.message}"
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "g2cam:service").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startServer() {
        try {
            val s = CompanionServer(this, PORT)
            s.start(5000, true)
            server = s
        } catch (e: Exception) {
            lastError = "サーバーを開始できません（ポート $PORT）: ${e.message}"
            Log.e(TAG, "server start failed", e)
        }
    }

    /** 端末の向きに合わせて撮影・解析・録画の回転を更新する（画面が消えていても向きが取れれば追従） */
    private fun startOrientationListener() {
        val l = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val r = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                rotation = r
                imageCapture?.targetRotation = r
                imageAnalysis?.targetRotation = r
                // 録画中に向きを変えても、動画の向きは録画開始時のまま（途中で変えると壊れる端末がある）
                if (activeRecording == null) videoCapture?.targetRotation = r
            }
        }
        if (l.canDetectOrientation()) l.enable()
        orientationListener = l
    }

    // ── カメラ ──
    /**
     * ユースケースを束ねる。recorder が null なら「配信＋撮影」、あれば「配信＋録画＋撮影」。
     * 端末が同時に使えない組み合わせのときは、撮影 → 配信の順に外して試す。
     */
    private fun bindCamera(recorder: Recorder?, onDone: Runnable?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                imageAnalysis = null
                imageCapture = null
                videoCapture = null

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(rotation)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> handleFrame(image) }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .setTargetRotation(rotation)
                    .build()

                val video = if (recorder != null) {
                    VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
                } else {
                    null
                }

                val selector = if (facingValue == "user") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val attempts: List<List<UseCase>> = if (video != null) {
                    listOf(
                        listOf(analysis, video, capture),
                        listOf(analysis, video),
                        listOf(video, capture),
                        listOf(video),
                    )
                } else {
                    listOf(listOf(analysis, capture), listOf(analysis))
                }
                var bound: Camera? = null
                var used: List<UseCase> = emptyList()
                var lastFailure: Exception? = null
                for (set in attempts) {
                    try {
                        bound = provider.bindToLifecycle(this, selector, *set.toTypedArray())
                        used = set
                        break
                    } catch (e: Exception) {
                        lastFailure = e
                        Log.w(TAG, "bind failed for ${set.size} use cases: ${e.message}")
                        provider.unbindAll()
                    }
                }
                if (bound == null) throw lastFailure ?: IllegalStateException("bind failed")
                camera = bound
                imageAnalysis = if (used.contains(analysis)) analysis else null
                imageCapture = if (used.contains(capture)) capture else null
                videoCapture = if (video != null && used.contains(video)) video else null
                if (imageAnalysis == null) frameLock.withLock { latestFrame = null }
                lastError = null
                // ズームは束ね直すと 1 に戻るので、直前の倍率を掛け直す
                if (zoomNow != 1f) {
                    try {
                        bound.cameraControl.setZoomRatio(zoomNow)
                    } catch (e: Exception) {
                        Log.w(TAG, "zoom restore failed", e)
                    }
                }
            } catch (e: Exception) {
                lastError = "カメラを開始できません: ${e.message}"
                Log.e(TAG, "bindCamera failed", e)
            } finally {
                onDone?.run()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** メインスレッドで束ね直し、終わるまで待つ（HTTP のスレッドから呼ぶ） */
    private fun rebindAndWait(recorder: Recorder?) {
        val latch = CountDownLatch(1)
        mainHandler.post { bindCamera(recorder, Runnable { latch.countDown() }) }
        if (!latch.await(6, TimeUnit.SECONDS)) throw IllegalStateException("カメラの切り替えがタイムアウトしました")
    }

    /** 解析フレーム → 長辺 320px の JPEG にして最新として保持する */
    private fun handleFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 30) return // 30fps まで通す（カメラ側が 30fps なので実質すべて）
            lastFrameAt = now

            val src = image.toBitmap()
            val scale = 320f / max(image.width, image.height)
            val m = Matrix()
            m.postRotate(image.imageInfo.rotationDegrees.toFloat())
            m.postScale(scale, scale)
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val stream = ByteArrayOutputStream(24 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            frameW = out.width
            frameH = out.height
            val bytes = stream.toByteArray()
            frameLock.withLock {
                frameSeq += 1
                latestFrame = Frame(frameSeq, bytes)
                frameArrived.signalAll()
            }
            if (out !== src) out.recycle()
            src.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            image.close()
        }
    }

    override fun nextFrame(afterSeq: Long, waitMs: Long): Frame? {
        var remainNs = TimeUnit.MILLISECONDS.toNanos(waitMs)
        frameLock.withLock {
            while (true) {
                val f = latestFrame
                if (f != null && f.seq > afterSeq) {
                    framesServed++
                    return f
                }
                if (remainNs <= 0L) return null
                remainNs = frameArrived.awaitNanos(remainNs)
            }
        }
    }

    override fun touch() {
        lastClientAt = SystemClock.elapsedRealtime()
        clientSeen = true
    }

    override fun requestStop(): Boolean {
        if (!Prefs.autoStop(this)) return false
        // 返事を返し終えてから止める（録画中なら保存してから）
        mainHandler.postDelayed({ shutdownGracefully() }, 300)
        return true
    }

    override fun setZoom(zoom: Double): Float {
        val cam = camera ?: return 1f
        val state = cam.cameraInfo.zoomState.value
        val target = if (state != null) zoom.toFloat().coerceIn(state.minZoomRatio, state.maxZoomRatio) else 1f
        // 結果は待たない（ズーム中も映像は流れ続ける）。1 未満なら端末が超広角に、大きければ望遠に切り替える
        cam.cameraControl.setZoomRatio(target)
        zoomNow = target
        return target
    }

    override fun setFacing(facing: String) {
        val next = if (facing == "user") "user" else "environment"
        if (next == facingValue) return
        if (activeRecording != null) throw IllegalStateException("録画中はカメラを切り替えられません")
        facingValue = next
        zoomNow = 1f
        frameLock.withLock { latestFrame = null }
        // 切り替えが済んでから返す（直後にズーム範囲を読むため）
        rebindAndWait(null)
    }

    override fun capture(): ByteArray = synchronized(captureLock) {
        val cap = imageCapture
            ?: throw IllegalStateException(if (activeRecording != null) "この端末では録画中に撮影できません" else "カメラが起動していません")

        val name = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()

        val latch = CountDownLatch(1)
        var savedUri: Uri? = null
        var failure: Throwable? = null
        cap.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                savedUri = output.savedUri
                latch.countDown()
            }

            override fun onError(exception: ImageCaptureException) {
                failure = exception
                latch.countDown()
            }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("撮影がタイムアウトしました")
        failure?.let { throw it }
        val uri = savedUri ?: throw IllegalStateException("保存先が取得できません")
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("保存した写真を読めません")
        photosTaken++
        bytes
    }

    // ── 録画 ──
    private fun qualityOf(name: String): Quality = when (name.lowercase(Locale.US)) {
        "uhd", "4k" -> Quality.UHD
        "hd", "720p" -> Quality.HD
        "sd", "480p" -> Quality.SD
        else -> Quality.FHD
    }

    override fun startRecording(audio: Boolean, quality: String, maxMs: Long): RecordingState = synchronized(recordLock) {
        recordingState?.let { return it }
        if (camera == null) throw IllegalStateException("カメラが起動していません")

        // 録画の間は VideoCapture を加えて束ね直す（サムネイルは束ね直す前の最新フレーム）
        recordingThumb = latestFrame?.bytes
        val recorder = Recorder.Builder()
            .setExecutor(ioExecutor)
            .setQualitySelector(QualitySelector.from(qualityOf(quality), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        rebindAndWait(recorder)
        val vc = videoCapture
        if (vc == null) {
            // 録画できる組み合わせが無かった。元の配信＋撮影に戻す
            rebindAndWait(null)
            throw IllegalStateException(lastError ?: "この端末では録画を開始できません")
        }

        val name = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
        }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        val withAudio = audio && audioPermitted
        var pending = vc.output.prepareRecording(this, options)
        if (withAudio) pending = pending.withAudioEnabled()

        val state = RecordingState(
            name = name,
            startedAt = SystemClock.elapsedRealtime(),
            audio = withAudio,
            live = imageAnalysis != null,
            capture = imageCapture != null,
        )
        val started = CountDownLatch(1)
        finalizeEvent = null
        val fin = CountDownLatch(1)
        finalizeLatch = fin
        var startFailure: String? = null
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> started.countDown()
                is VideoRecordEvent.Status -> {
                    state.durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    state.bytes = event.recordingStats.numBytesRecorded
                    recordingLabel = formatDuration(state.durationMs)
                }
                is VideoRecordEvent.Finalize -> {
                    state.durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    state.bytes = event.recordingStats.numBytesRecorded
                    if (event.hasError() && event.outputResults.outputUri == Uri.EMPTY) {
                        startFailure = "録画に失敗（コード ${event.error}）: ${event.cause?.message ?: ""}"
                    }
                    finalizeEvent = event
                    fin.countDown()
                    started.countDown()
                }
                else -> {}
            }
        }
        if (!started.await(8, TimeUnit.SECONDS)) {
            activeRecording?.close()
            activeRecording = null
            rebindAndWait(null)
            throw IllegalStateException("録画が始まりません（タイムアウト）")
        }
        startFailure?.let {
            activeRecording = null
            rebindAndWait(null)
            throw IllegalStateException(it)
        }
        recordingState = state
        recordingLabel = "0:00"
        updateNotification("録画中（${if (withAudio) "音声あり" else "無音"}）")
        if (maxMs > 0) {
            val timer = Runnable { ioExecutor.execute { stopRecordingInternally("最長録画時間") } }
            recordLimitTimer = timer
            mainHandler.postDelayed(timer, maxMs)
        }
        Log.i(TAG, "録画開始 $name（音声 $withAudio・配信 ${state.live}・撮影 ${state.capture}）")
        return state
    }

    override fun takePendingResult(): RecordResult? = synchronized(recordLock) {
        val r = pendingResult
        pendingResult = null
        r
    }

    /** 自分の都合で止めたときは、結果を G2 カメラ側が取りに来るまで持っておく */
    private fun stopRecordingInternally(reason: String) {
        try {
            Log.i(TAG, "録画を止めます（$reason）")
            val r = stopRecording()
            if (r != null) pendingResult = r
        } catch (e: Exception) {
            Log.w(TAG, "stop recording ($reason) failed", e)
        }
    }

    override fun stopRecording(): RecordResult? = synchronized(recordLock) {
        val rec = activeRecording ?: return null
        val state = recordingState
        recordLimitTimer?.let { mainHandler.removeCallbacks(it) }
        recordLimitTimer = null
        rec.stop()
        val fin = finalizeLatch
        if (fin != null && !fin.await(10, TimeUnit.SECONDS)) {
            Log.w(TAG, "録画の保存待ちがタイムアウトしました")
        }
        activeRecording = null
        recordingState = null
        recordingLabel = null
        val ev = finalizeEvent
        val uri = ev?.outputResults?.outputUri?.takeIf { it != Uri.EMPTY }
        val name = state?.name ?: "recording.mp4"
        if (uri != null) recentFiles[name] = uri
        val error = if (ev == null) {
            "保存結果を受け取れませんでした"
        } else if (ev.hasError()) {
            "保存時のエラー（コード ${ev.error}）: ${ev.cause?.message ?: ""}"
        } else {
            null
        }
        val result = RecordResult(
            name = name,
            uri = uri,
            durationMs = state?.durationMs ?: (ev?.recordingStats?.recordedDurationNanos?.div(1_000_000L) ?: 0L),
            bytes = state?.bytes ?: (ev?.recordingStats?.numBytesRecorded ?: 0L),
            audio = state?.audio ?: false,
            thumbnail = recordingThumb,
            error = error,
        )
        recordingThumb = null
        updateNotification(null)
        Log.i(TAG, "録画終了 $name ${result.durationMs}ms ${result.bytes}B uri=$uri error=$error")
        // 配信＋撮影の構成に戻す（戻せなくても録画結果は返す）
        try {
            if (running) rebindAndWait(null)
        } catch (e: Exception) {
            Log.w(TAG, "rebind after recording failed", e)
        }
        return result
    }

    override fun openRecording(name: String): Pair<InputStream, Long>? {
        var uri = recentFiles[name]
        var size = -1L
        if (uri == null) {
            val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
            val sel = "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(name, "$RELATIVE_DIR%"), null)?.use { c ->
                if (c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    size = c.getLong(1)
                }
            }
        }
        val u = uri ?: return null
        if (size < 0) {
            contentResolver.query(u, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) size = c.getLong(0)
            }
        }
        val stream = contentResolver.openInputStream(u) ?: return null
        if (size < 0) {
            // 大きさが分からなければ読み切る（短い動画向けの保険）
            val bytes = stream.use { it.readBytes() }
            return Pair(bytes.inputStream(), bytes.size.toLong())
        }
        return Pair(stream, size)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}"
    }
}
