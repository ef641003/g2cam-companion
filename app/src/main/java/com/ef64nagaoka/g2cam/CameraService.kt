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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
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
        private const val RELATIVE_DIR = Storage.RELATIVE_DIR

        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var framesServed = 0L
        @Volatile var photosTaken = 0
        /** 画面表示用：録画中なら「0:12」のような経過時間、録画していなければ null */
        @Volatile var recordingLabel: String? = null
        /** 画面表示用：カメラ構成などの診断 */
        @Volatile var diagText: String? = null
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
    /** Recorder 専用。ioExecutor と共用すると、停止待ちで ioExecutor が塞がったときに保存処理が進まなくなる */
    private val recorderExecutor = Executors.newSingleThreadExecutor()
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
    /** 選んだフォルダに録画しているときの保存先（MediaStore の場合は null） */
    private var customVideoUri: Uri? = null

    // 診断（/status の diag と本体画面に出す）
    @Volatile private var boundLabel = "未起動"
    @Volatile private var extensionLabel = "なし"
    @Volatile private var bindNotes = ""
    @Volatile private var stabLabel = "なし"
    override val diag: String
        get() = buildDiag()

    private fun buildDiag(): String = "構成=$boundLabel・静止画の拡張=$extensionLabel・手ブレ補正=$stabLabel・保存先=${Storage.describe(this)}" +
            (if (bindNotes.isNotEmpty()) "・束ね直しの失敗=$bindNotes" else "")
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
        diagText = null
        mainHandler.removeCallbacks(idleWatchdog)
        recordLimitTimer?.let { mainHandler.removeCallbacks(it) }
        orientationListener?.disable()
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy() // カメラの unbind を先に済ませてから実行系を止める
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        recorderExecutor.shutdownNow()
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
    /** 束ね方の候補ひとつ */
    private class Attempt(val selector: CameraSelector, val cases: List<UseCase>, val label: String, val extension: String)

    /** 光学式手ブレ補正（OIS）が使える端末か */
    private fun oisSupported(): Boolean = try {
        val cm = getSystemService(CameraManager::class.java)
        val want = if (facingValue == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.any { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == want &&
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        }
    } catch (e: Exception) {
        false
    }

    /** 動画の電子式手ブレ補正（EIS）が使える端末か */
    private fun eisSupported(): Boolean = try {
        val cm = getSystemService(CameraManager::class.java)
        val want = if (facingValue == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.any { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == want &&
                c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        }
    } catch (e: Exception) {
        false
    }

    /** 静止画のユースケース。高画質モードなら最高画質・最大解像度・ノイズ低減と輪郭を高品質に */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun buildCapture(recording: Boolean, withInterop: Boolean): ImageCapture {
        val hq = Prefs.highQuality(this) && !recording
        val b = ImageCapture.Builder()
            .setCaptureMode(if (hq) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(if (hq) 100 else 95)
            .setTargetRotation(rotation)
        if (hq) {
            b.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
            if (withInterop) {
                try {
                    val x = Camera2Interop.Extender(b)
                    x.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    x.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    x.setCaptureRequestOption(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "camera2 interop failed", e)
                }
            }
        }
        if (withInterop && Prefs.stabilization(this) && oisSupported()) {
            try {
                Camera2Interop.Extender(b)
                    .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } catch (e: Exception) {
                Log.w(TAG, "ois failed", e)
            }
        }
        return b.build()
    }

    /** 端末のカメラ拡張（オート → HDR）が使えるなら、拡張つきのセレクターを返す */
    private fun extensionSelector(ext: ExtensionsManager?, base: CameraSelector): Pair<CameraSelector, String>? {
        if (ext == null) return null
        for ((mode, label) in listOf(ExtensionMode.AUTO to "オート", ExtensionMode.HDR to "HDR")) {
            try {
                if (ext.isExtensionAvailable(base, mode)) {
                    return ext.getExtensionEnabledCameraSelector(base, mode) to label
                }
            } catch (e: Exception) {
                Log.w(TAG, "extension check failed: $label", e)
            }
        }
        return null
    }

    /**
     * ユースケースを束ねる。recorder が null なら「配信＋撮影」、あれば「配信＋録画＋撮影」。
     * 端末が同時に使えない組み合わせのときは、撮影 → 配信の順に外して試す。
     * 高画質モードでは、先に端末のカメラ拡張つき（HDR 等）で試し、だめなら通常に戻す。
     */
    private fun bindCamera(recorder: Recorder?, onDone: Runnable?, stabilize: Boolean = false) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (recorder == null && Prefs.highQuality(this)) {
                    val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
                    extFuture.addListener({
                        val ext = try {
                            extFuture.get()
                        } catch (e: Exception) {
                            Log.w(TAG, "extensions unavailable", e)
                            null
                        }
                        finishBind(provider, ext, recorder, onDone, stabilize)
                    }, ContextCompat.getMainExecutor(this))
                } else {
                    finishBind(provider, null, recorder, onDone, stabilize)
                }
            } catch (e: Exception) {
                lastError = "カメラを開始できません: ${e.message}"
                Log.e(TAG, "bindCamera failed", e)
                onDone?.run()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun finishBind(provider: ProcessCameraProvider, ext: ExtensionsManager?, recorder: Recorder?, onDone: Runnable?, stabilize: Boolean) {
        try {
            provider.unbindAll()
            imageAnalysis = null
            imageCapture = null
            videoCapture = null

            val ois = Prefs.stabilization(this) && oisSupported()
            val analysisBuilder = ImageAnalysis.Builder()
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
            if (ois) {
                try {
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                } catch (e: Exception) {
                    Log.w(TAG, "ois (analysis) failed", e)
                }
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(analysisExecutor) { image -> handleFrame(image) }

            var eisOn = false
            val video = if (recorder != null) {
                val vb = VideoCapture.Builder(recorder).setTargetRotation(rotation)
                if (stabilize && eisSupported()) {
                    try {
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        eisOn = true
                    } catch (e: Exception) {
                        Log.w(TAG, "eis failed", e)
                    }
                }
                vb.build()
            } else {
                null
            }

            val base = if (facingValue == "user") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val attempts = ArrayList<Attempt>()
            if (video != null) {
                val cap = buildCapture(recording = true, withInterop = false)
                attempts.add(Attempt(base, listOf(analysis, video, cap), "配信+録画+撮影", "なし"))
                attempts.add(Attempt(base, listOf(analysis, video), "配信+録画", "なし"))
                attempts.add(Attempt(base, listOf(video, cap), "録画+撮影", "なし"))
                attempts.add(Attempt(base, listOf(video), "録画のみ", "なし"))
            } else {
                extensionSelector(ext, base)?.let { (sel, label) ->
                    attempts.add(Attempt(sel, listOf(analysis, buildCapture(false, withInterop = false)), "配信+撮影", label))
                }
                val cap = buildCapture(recording = false, withInterop = true)
                attempts.add(Attempt(base, listOf(analysis, cap), "配信+撮影", "なし"))
                attempts.add(Attempt(base, listOf(analysis), "配信のみ", "なし"))
            }

            var bound: Camera? = null
            var used: Attempt? = null
            var lastFailure: Exception? = null
            val notes = ArrayList<String>()
            for (a in attempts) {
                try {
                    bound = provider.bindToLifecycle(this, a.selector, *a.cases.toTypedArray())
                    used = a
                    break
                } catch (e: Exception) {
                    lastFailure = e
                    notes.add("${a.label}${if (a.extension != "なし") "(拡張${a.extension})" else ""}: ${e.message?.take(80)}")
                    Log.w(TAG, "bind failed (${a.label}/${a.extension}): ${e.message}")
                    provider.unbindAll()
                }
            }
            bindNotes = notes.joinToString(" / ")
            if (bound == null || used == null) {
                boundLabel = "失敗"
                throw lastFailure ?: IllegalStateException("bind failed")
            }
            camera = bound
            boundLabel = used.label
            val withVideo = video != null && used.cases.contains(video)
            stabLabel = "光学" + (if (ois) "あり" else "なし") + "・動画の電子式" + (if (!withVideo) "（録画していない）" else if (eisOn) "オン" else "オフ")
            extensionLabel = used.extension
            imageAnalysis = used.cases.filterIsInstance<ImageAnalysis>().firstOrNull()
            imageCapture = used.cases.filterIsInstance<ImageCapture>().firstOrNull()
            videoCapture = if (video != null && used.cases.contains(video)) video else null
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
            diagText = buildDiag()
            onDone?.run()
        }
    }

    /** メインスレッドで束ね直し、終わるまで待つ（HTTP のスレッドから呼ぶ） */
    private fun rebindAndWait(recorder: Recorder?, stabilize: Boolean = false) {
        val latch = CountDownLatch(1)
        mainHandler.post { bindCamera(recorder, Runnable { latch.countDown() }, stabilize) }
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

        // いったんメモリに受けて、すぐ保存先（選んだフォルダ or 写真アプリ）へ書く。読み戻しはしない
        val buffer = ByteArrayOutputStream(4 * 1024 * 1024)
        val options = ImageCapture.OutputFileOptions.Builder(buffer).build()

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        cap.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                latch.countDown()
            }

            override fun onError(exception: ImageCaptureException) {
                failure = exception
                latch.countDown()
            }
        })
        // 拡張（HDR・夜景）の撮影は数秒かかることがある
        if (!latch.await(20, TimeUnit.SECONDS)) throw IllegalStateException("撮影がタイムアウトしました")
        failure?.let { throw it }
        val bytes = buffer.toByteArray()
        val name = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val uri = Storage.saveImage(this, bytes, name)
        if (uri == null) {
            lastError = "写真を保存できません（保存先を確認してください）"
        }
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
        fun newRecorder() = Recorder.Builder()
            .setExecutor(recorderExecutor)
            .setQualitySelector(QualitySelector.from(qualityOf(quality), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        // 手ブレ補正つきで束ねてみて、端末が受け付けなければ補正なしでやり直す
        val wantStab = Prefs.stabilization(this)
        rebindAndWait(newRecorder(), wantStab)
        var vc = videoCapture
        if (vc == null && wantStab) {
            val why = lastError ?: bindNotes
            Log.w(TAG, "動画の手ブレ補正つきでは束ねられません。補正なしでやり直します: $why")
            rebindAndWait(newRecorder(), false)
            vc = videoCapture
        }
        if (vc == null) {
            // 録画できる組み合わせが無かった。理由を控えてから、元の配信＋撮影に戻す
            val reason = lastError ?: "この端末では録画を開始できません"
            val notes = bindNotes
            rebindAndWait(null)
            throw IllegalStateException("$reason${if (notes.isNotEmpty()) "（$notes）" else ""}")
        }

        val name = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        // 保存先：選んだフォルダがあればそこへ直接、無ければ写真アプリ（DCIM/G2Camera）
        val target = Storage.openVideoInTree(this, name)
        customVideoUri = target?.uri
        val withAudio = audio && audioPermitted
        var pending = try {
            if (target != null) {
                vc.output.prepareRecording(this, FileDescriptorOutputOptions.Builder(target.pfd).build())
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
                }
                vc.output.prepareRecording(
                    this,
                    MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .setContentValues(values)
                        .build(),
                )
            }
        } catch (e: Exception) {
            target?.let { closeQuietly(it); Storage.deleteQuietly(this, it.uri) }
            customVideoUri = null
            rebindAndWait(null)
            throw IllegalStateException("録画の準備に失敗: ${e.message}")
        }
        if (withAudio) {
            try {
                pending = pending.withAudioEnabled()
            } catch (e: Exception) {
                Log.w(TAG, "audio enable failed", e)
            }
        }

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
                    if (event.hasError() && event.recordingStats.numBytesRecorded == 0L) {
                        startFailure = "録画に失敗（コード ${event.error}）: ${event.cause?.message ?: ""}"
                    }
                    target?.let { closeQuietly(it) }
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
            target?.let { closeQuietly(it); Storage.deleteQuietly(this, it.uri) }
            customVideoUri = null
            rebindAndWait(null)
            throw IllegalStateException("録画が始まりません（タイムアウト。構成=$boundLabel）")
        }
        startFailure?.let {
            activeRecording = null
            target?.let { t -> Storage.deleteQuietly(this, t.uri) }
            customVideoUri = null
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
        val uri = ev?.outputResults?.outputUri?.takeIf { it != Uri.EMPTY } ?: customVideoUri
        customVideoUri = null
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
        var uri = recentFiles[name] ?: Storage.findInTree(this, name)
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

    private fun closeQuietly(t: Storage.VideoTarget) {
        try {
            t.pfd.close()
        } catch (e: Exception) {
            Log.w(TAG, "pfd close failed", e)
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${String.format(Locale.US, "%02d", s % 60)}"
    }
}
