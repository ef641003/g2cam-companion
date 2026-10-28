package com.ef64nagaoka.g2cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
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
 * カメラを保持するフォアグラウンドサービス（種別 camera）。
 * 画面が消えても、映像の配信と撮影が止まらない。
 * 開始は「アプリが画面に出ている間」に行うこと（Android 11 以降の制約）。
 */
class CameraService : LifecycleService(), CameraController {

    companion object {
        const val PORT = 8787
        const val ACTION_STOP = "com.ef64nagaoka.g2cam.STOP"
        /** G2 カメラからの要求が途絶えてから自動で止めるまで（ミリ秒） */
        private const val IDLE_STOP_MS = 120_000L
        private const val CHANNEL_ID = "g2cam"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "G2Cam"

        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var framesServed = 0L
        @Volatile var photosTaken = 0
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val captureLock = Any()

    private var server: CompanionServer? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var orientationListener: OrientationEventListener? = null

    // 最新フレーム。seq は増えていく通し番号（サービスを再起動しても戻らないよう、時刻から始める）
    private val frameLock = ReentrantLock()
    private val frameArrived = frameLock.newCondition()
    @Volatile private var latestFrame: Frame? = null
    private var frameSeq = System.currentTimeMillis()
    override val hasFrame: Boolean get() = latestFrame != null
    private var lastFrameAt = 0L

    // G2 カメラ側とのつながり（終了に合わせた自動停止）
    @Volatile private var lastClientAt = 0L
    @Volatile private var clientSeen = false
    private val idleWatchdog = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastClientAt
            if (clientSeen && idle > IDLE_STOP_MS && Prefs.autoStop(this@CameraService)) {
                Log.i(TAG, "G2 カメラからの応答が ${idle / 1000} 秒途絶えたので停止します")
                stopSelf()
                return
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
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
            bindCamera(null)
            clientSeen = false
            mainHandler.postDelayed(idleWatchdog, 5000)
        }
        // システムが勝手に再起動すると背景からのカメラ開始になり失敗するので、再起動はしない
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(idleWatchdog)
        orientationListener?.disable()
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy() // カメラの unbind を先に済ませてから実行系を止める
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
    }

    // ── フォアグラウンド化 ──
    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 カメラ", NotificationManager.IMPORTANCE_LOW))

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("G2 カメラ コンパニオン")
            .setContentText("カメラを保持しています（127.0.0.1:$PORT）")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "停止", stop).build())
            .setOngoing(true)
            .build()

        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
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

    /** 端末の向きに合わせて撮影・解析の回転を更新する（画面が消えていても向きが取れれば追従） */
    private fun startOrientationListener() {
        val l = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                imageAnalysis?.targetRotation = rotation
            }
        }
        if (l.canDetectOrientation()) l.enable()
        orientationListener = l
    }

    // ── カメラ ──
    private fun bindCamera(onDone: Runnable?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()

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
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> handleFrame(image) }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build()

                val selector = if (facingValue == "user") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                camera = provider.bindToLifecycle(this, selector, analysis, capture)
                imageAnalysis = analysis
                imageCapture = capture
                zoomNow = 1f
                lastError = null
            } catch (e: Exception) {
                lastError = "カメラを開始できません: ${e.message}"
                Log.e(TAG, "bindCamera failed", e)
            } finally {
                onDone?.run()
            }
        }, ContextCompat.getMainExecutor(this))
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
        // 返事を返し終えてから止める
        mainHandler.postDelayed({ stopSelf() }, 300)
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
        facingValue = next
        frameLock.withLock { latestFrame = null }
        // 切り替えが済んでから返す（直後にズーム範囲を読むため）
        val latch = CountDownLatch(1)
        mainHandler.post { bindCamera(Runnable { latch.countDown() }) }
        latch.await(3, TimeUnit.SECONDS)
    }

    override fun capture(): ByteArray = synchronized(captureLock) {
        val cap = imageCapture ?: throw IllegalStateException("カメラが起動していません")

        val name = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/G2Camera")
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
}
