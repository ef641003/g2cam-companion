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
import android.provider.DocumentsContract
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
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * カメラを保持するフォアグラウンドサービス（種別 camera。録画の音声が許可されていれば microphone も）。
 * 画面が消えても、映像の配信・撮影・録画が止まらない。
 * 開始は「アプリが画面に出ている間」に行うこと（Android 11 以降の制約）。
 *
 * 録画まわり（0.1.11）
 * - 準備（/record/prepare。G2 録画が開いたとき）：待機中から録画できる組み方に束ねておき、タップですぐ録画が始まる
 * - 区切り録画：最長時間に達したら止めずに次のファイルへ続ける（/record/segments で書き終えた分を渡す）
 * - 見張り：電池の温度・発熱の段階・空き容量を 5 秒ごとに読み、危ないときは保存して止める
 * - 遡り録画（本体の画面でオンにしたときだけ）：準備中は短い切れ端を録り続け、タップしたら今の切れ端をそのまま本番にする。
 *   止めたあとに直前の数秒ぶんとつないで 1 本にする（再エンコードなし）
 */
class CameraService : LifecycleService(), CameraController {

    companion object {
        const val PORT = 8787
        const val ACTION_STOP = "com.ef64nagaoka.g2cam.STOP"
        const val ACTION_STOP_RECORDING = "com.ef64nagaoka.g2cam.STOP_RECORDING"
        /** 本体の画面で遡り録画の設定を変えた */
        const val ACTION_PREROLL_CHANGED = "com.ef64nagaoka.g2cam.PREROLL_CHANGED"
        /** G2 カメラからの要求が途絶えてから自動で止めるまで（ミリ秒） */
        private const val IDLE_STOP_MS = 120_000L
        /** G2 録画からの要求が途絶えてから、録画の準備（と遡り録画の録りため）を解くまで */
        private const val DISARM_IDLE_MS = 60_000L
        private const val CHANNEL_ID = "g2cam"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "G2Cam"
        private const val RELATIVE_DIR = Storage.RELATIVE_DIR
        private const val MB = 1024L * 1024L
        /** 遡り録画の切れ端を置く領域の空きがこれを切ったら録りためを休み、これを超えたら戻す */
        private const val BUFFER_PAUSE_FREE = 1024L * MB
        private const val BUFFER_RESUME_FREE = 1536L * MB
        /** 区切り録画で書き終えたファイルを、G2 録画が取りに来るまで持っておく最大の本数 */
        private const val MAX_SEGMENT_QUEUE = 50

        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var framesServed = 0L
        @Volatile var photosTaken = 0
        /** 画面表示用：録画中なら「0:12」のような経過時間、録画していなければ null */
        @Volatile var recordingLabel: String? = null
        /** 画面表示用：カメラ構成などの診断 */
        @Volatile var diagText: String? = null
        /** 画面表示用：電池・温度・空き容量（5 秒ごとに更新） */
        @Volatile var monitorLabel: String? = null
        @Volatile private var instance: CameraService? = null

        /** 画面表示用：遡り録画の状態（サービスが動いていなければ null） */
        fun preRollText(): String? = instance?.describePreRoll()
    }

    // ── CameraController ──
    @Volatile private var facingValue = "environment"
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0
    override val facing: String get() = facingValue
    override val frameWidth: Int get() = frameW
    override val frameHeight: Int get() = frameH
    override val appVersion: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

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
    /** 区切り・遡り録画の録りため・見張り（録画の始まりや保存を待つことが多いので専用） */
    private val workExecutor = Executors.newSingleThreadExecutor()
    /** 遡り録画のつなぎ合わせ（時間がかかる） */
    private val mergeExecutor = Executors.newSingleThreadExecutor()
    private val captureLock = Any()
    /** 録画・準備・録りためを動かすときに持つ。メインスレッドでは持たないこと（束ね直しをメインで待つため） */
    private val recordLock = Any()
    private val segmentLock = Any()

    private var server: CompanionServer? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var videoCapture: VideoCapture<Recorder>? = null
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

    // ── 録画の部品 ──

    /** 1 本の録画（遡り録画の切れ端、または保存先へ直接書く 1 ファイル） */
    private class Rec(
        /** 保存先での名前（切れ端はファイル名） */
        val name: String,
        val audio: Boolean,
        /** アプリ内に録るとき（遡り録画の切れ端）のファイル */
        val file: File?,
        /** 選んだフォルダに録るときの書き込み先（null で写真アプリ） */
        val target: Storage.VideoTarget?,
    ) {
        @Volatile var recording: Recording? = null
        val started = CountDownLatch(1)
        val finalized = CountDownLatch(1)
        /** 録り始めた時刻（Start を受け取ったとき。elapsedRealtime） */
        @Volatile var startedAt = 0L
        /** 止めた時刻（こちらが stop を呼んだとき、または向こうで止まったとき） */
        @Volatile var stopAt = 0L
        @Volatile var durationMs = 0L
        @Volatile var bytes = 0L
        @Volatile var finalizeEvent: VideoRecordEvent.Finalize? = null
        @Volatile var startFailure: String? = null
        /** 本番の録画に使っているとき、そのタップの時刻（経過表示用。0 なら録りためのまま） */
        @Volatile var sessionTapAt = 0L
        val isFinalized: Boolean get() = finalized.count == 0L
    }

    /** G2 録画が準備を頼んだときの組み方 */
    private class ArmedConfig(
        val quality: String,
        val audio: Boolean,
        val still: Boolean,
        val facing: String,
        val stopTempC: Double,
        val resolution: String,
    )

    private class ArmRequest(val quality: String, val audio: Boolean, val still: Boolean, val stopTempC: Double)

    /** 1 本目につなぐ遡りの材料 */
    private class PreRollUse(val prev: Rec?, val plan: PreRollPlan.Plan)

    /** タップしてから止めるまでの一続きの録画（区切り録画なら何本かのファイルになる） */
    private class Session(
        val opts: StartOptions,
        val tapAt: Long,
        val audio: Boolean,
        val resolution: String,
        val live: Boolean,
        val capture: Boolean,
        current: Rec,
        /** 1 本目に遡りをつなぐとき（current は切れ端のまま録っている） */
        val preRoll: PreRollUse?,
        /** 1 本目の保存名 */
        val firstName: String,
    ) {
        @Volatile var current: Rec = current
        @Volatile var segment = 1
        @Volatile var thumb: ByteArray? = null
        @Volatile var finishedBytes = 0L
        var timer: Runnable? = null
    }

    /** 束ね方：recorder があれば録画つき（静止画も束ねるか・電子式手ブレ補正のモード 0=なし） */
    private class BindSpec(val recorder: Recorder, val still: Boolean, val eis: Int)

    // ── 録画の状態（recordLock の中で変える。読むだけなら外からでもよい） ──
    @Volatile private var session: Session? = null
    @Volatile private var armed: ArmedConfig? = null
    @Volatile private var pendingArm: ArmRequest? = null
    @Volatile private var bufCur: Rec? = null
    @Volatile private var bufPrev: Rec? = null
    private var bufTimer: Runnable? = null
    /** 録りためを休んでいる理由（heat / space）。休んでいなければ null */
    @Volatile private var bufPaused: String? = null
    @Volatile private var bufError: String? = null
    private var bufRetryAt = 0L
    private var chunkSeq = System.currentTimeMillis()
    @Volatile private var disarmPosted = false

    override val recording: RecordingState? get() = session?.let { snapshot(it) }
    override val armedQuality: String? get() = armed?.quality
    override val armedResolution: String get() = armed?.resolution ?: ""

    /** このサービスで録画したファイル（名前 → URI）。/file で返すときに使う */
    private val recentFiles = ConcurrentHashMap<String, Uri>()
    /** 書き出し中（つなぎ合わせ中）の名前 → 済んだら開く門 */
    private val merging = ConcurrentHashMap<String, CountDownLatch>()
    override val mergingCount: Int get() = merging.size
    @Volatile private var mergeNote: String? = null
    override val lastMergeNote: String? get() = mergeNote
    /** G2 カメラ側がまだ受け取っていない録画結果（最長時間・通知・高温などで止めたとき） */
    @Volatile private var pendingResult: RecordResult? = null
    /** 区切り録画で書き終えて、まだ渡していない分 */
    private val completedSegments = ArrayDeque<RecordResult>()
    override val pendingSegments: Int get() = synchronized(segmentLock) { completedSegments.size }

    @Volatile private var lastMonitor: DeviceMonitor.Snapshot? = null
    override val monitor: DeviceMonitor.Snapshot?
        get() = lastMonitor ?: try {
            DeviceMonitor.read(this).also { lastMonitor = it }
        } catch (e: Exception) {
            null
        }

    // 診断（/status の diag と本体画面に出す）
    @Volatile private var boundLabel = "未起動"
    @Volatile private var extensionLabel = "なし"
    @Volatile private var bindNotes = ""
    @Volatile private var stabLabel = "なし"
    @Volatile private var recordResLabel = "－"
    override val diag: String
        get() = buildDiag()

    private fun buildDiag(): String {
        val a = armed
        val ready = if (a != null) "準備済み（${a.quality} ${a.resolution.ifEmpty { "解像度不明" }}${if (a.still) "・撮影あり" else ""}）" else "なし"
        return "構成=$boundLabel・静止画の拡張=$extensionLabel・手ブレ補正=$stabLabel・録画の解像度=$recordResLabel・録画の準備=$ready・保存先=${Storage.describe(this)}" +
            (if (bindNotes.isNotEmpty()) "・束ね直しの失敗=$bindNotes" else "")
    }

    // G2 カメラ側とのつながり（終了に合わせた自動停止）
    /** 録画中に G2 カメラが終了した。録画が終わったらサービスを止める */
    @Volatile private var stopWhenRecordingEnds = false
    @Volatile private var lastClientAt = 0L
    @Volatile private var clientSeen = false
    private val idleWatchdog = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastClientAt
            // 録画中・書き出し中は、メガネ・G2 カメラとの接続が切れても止めない（済んでから判断する）
            if (session == null && merging.isEmpty() && clientSeen && idle > IDLE_STOP_MS && Prefs.autoStop(this@CameraService)) {
                Log.i(TAG, "G2 カメラからの応答が ${idle / 1000} 秒途絶えたので停止します")
                shutdownGracefully()
                return
            }
            // G2 録画が閉じられたまま：準備（遡り録画の録りため）を解いて電池を守る
            if (armed != null && session == null && clientSeen && idle > DISARM_IDLE_MS && !disarmPosted) {
                disarmPosted = true
                postWork {
                    try {
                        synchronized(recordLock) {
                            if (session == null && SystemClock.elapsedRealtime() - lastClientAt > DISARM_IDLE_MS) {
                                Log.i(TAG, "G2 録画からの応答が途絶えたので、録画の準備を解きます")
                                disarmLocked()
                            }
                        }
                    } finally {
                        disarmPosted = false
                    }
                }
            }
            mainHandler.postDelayed(this, 5000)
        }
    }

    /** 5 秒ごとの見張り（電池・温度・空き容量）。録画中は危ないときに止め、録りためは休ませる */
    private val healthTick = object : Runnable {
        override fun run() {
            postWork { checkHealth() }
            mainHandler.postDelayed(this, 5000)
        }
    }

    private fun postWork(block: () -> Unit) {
        try {
            workExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "work failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "work rejected (service stopping)")
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
                postWork { stopRecordingInternally("通知") }
                return START_NOT_STICKY
            }
            ACTION_PREROLL_CHANGED -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                postWork {
                    synchronized(recordLock) {
                        if (Prefs.preRoll(this)) {
                            bufRetryAt = 0
                            bufError = null
                            maybeStartBufferLocked()
                        } else {
                            stopBufferLocked()
                            bufPaused = null
                        }
                        refreshNotification()
                    }
                }
                return START_NOT_STICKY
            }
        }
        if (!running) {
            if (!startInForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            instance = this
            lastError = null
            framesServed = 0
            photosTaken = 0
            acquireWakeLock()
            startServer()
            startOrientationListener()
            bindCamera(null, null)
            clientSeen = false
            mainHandler.postDelayed(idleWatchdog, 5000)
            mainHandler.post(healthTick)
            // 前回つなぎ終えられなかった遡り録画があれば、ここで書き出し直す。録りためのかけらは片付ける
            postWork { recoverMerges() }
        }
        // システムが勝手に再起動すると背景からのカメラ開始になり失敗するので、再起動はしない
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        recordingLabel = null
        diagText = null
        monitorLabel = null
        mainHandler.removeCallbacks(idleWatchdog)
        mainHandler.removeCallbacks(healthTick)
        bufTimer?.let { mainHandler.removeCallbacks(it) }
        session?.timer?.let { mainHandler.removeCallbacks(it) }
        orientationListener?.disable()
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy() // カメラの unbind を先に済ませてから実行系を止める
        analysisExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        recorderExecutor.shutdownNow()
        workExecutor.shutdownNow()
        // 書き出しは途中で切らない（プロセスが残っている間は続ける。消えたら次回の開始時にやり直す）
        mergeExecutor.shutdown()
    }

    /** 録画中なら保存してから止める。G2 カメラの終了・通知の「停止」・応答の途絶えから呼ばれる */
    private fun shutdownGracefully() {
        if (session == null && bufCur == null && merging.isEmpty()) {
            stopSelf()
            return
        }
        postWork {
            try {
                synchronized(recordLock) {
                    stopWhenRecordingEnds = false
                    armed = null
                    pendingArm = null
                    stopSessionLocked(null, rebind = false)?.let { pendingResult = it }
                    stopBufferLocked()
                }
                if (merging.isNotEmpty()) {
                    Log.i(TAG, "書き出しが済むのを待ってから止めます（${merging.size} 本）")
                    refreshNotification()
                    waitMerges(10 * 60_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "shutdown failed", e)
            } finally {
                mainHandler.post { stopSelf() }
            }
        }
    }

    // ── フォアグラウンド化 ──
    private fun buildNotification(text: String?, recordingNow: Boolean): Notification {
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
            .setContentText(text ?: "カメラを保持しています（127.0.0.1:$PORT）")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recordingNow) {
            val stopRec = PendingIntent.getService(
                this, 2, Intent(this, CameraService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "録画停止", stopRec).build())
        }
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "停止", stop).build())
        return builder.build()
    }

    @Volatile private var lastNotificationText: String? = "\u0000"

    /** 通知と本体画面の表示を、いまの状態に合わせる */
    private fun refreshNotification() {
        val s = session
        val main = when {
            s != null -> "録画中（${if (s.audio) "音声あり" else "無音"}${if (s.segment > 1) "・${s.segment} 本目" else ""}）"
            bufCur != null -> "遡り録画の録りため中（直前 ${Prefs.preRollSeconds(this)} 秒）"
            armed != null -> "録画の準備ができています"
            else -> null
        }
        val merge = if (merging.isNotEmpty()) "動画を書き出し中（${merging.size} 本）" else null
        val text = listOfNotNull(main, merge).joinToString("・").ifEmpty { null }
        val key = "$text|${s != null}"
        if (key == lastNotificationText) return
        lastNotificationText = key
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, s != null))
        } catch (e: Exception) {
            Log.w(TAG, "notify failed", e)
        }
        diagText = buildDiag()
    }

    private fun startInForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 カメラ", NotificationManager.IMPORTANCE_LOW))
        // 録画の音声を使うには、サービスの種別に microphone も要る（Android 14 以降。権限が無いと指定できない）
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(null, false), types)
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
                // 録画中（録りため中も）は動画の向きを変えない（途中で変えると壊れる端末がある）。次の 1 本を始めるときに合わせる
                if (session == null && bufCur == null) videoCapture?.let { if (it.targetRotation != r) it.targetRotation = r }
            }
        }
        if (l.canDetectOrientation()) l.enable()
        orientationListener = l
    }

    /** メインスレッドで実行して終わるまで待つ（メイン以外から呼ぶ） */
    private fun onMainAndWait(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "main task failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
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

    /** 使っているカメラの映像の手ブレ補正モード（0=なし 1=通常 2=プレビュー安定化）のうち、端末が対応しているもの */
    private fun availableStabModes(): IntArray = try {
        val cm = getSystemService(CameraManager::class.java)
        val want = if (facingValue == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList
            .map { cm.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == want }
            ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
    } catch (e: Exception) {
        intArrayOf()
    }

    /** 使う電子式手ブレ補正のモード。強い方（プレビュー安定化 = 2。Android 13 以降）を優先、無ければ通常（1）、無ければ null */
    private fun chooseEisMode(): Int? {
        val modes = availableStabModes()
        return when {
            android.os.Build.VERSION.SDK_INT >= 33 && modes.contains(2) -> 2
            modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> null
        }
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
     * ユースケースを束ねる。spec が null なら「配信＋撮影」、あれば「配信＋録画（＋撮影）」。
     * 端末が同時に使えない組み合わせのときは、撮影 → 配信の順に外して試す。
     * 高画質モードでは、先に端末のカメラ拡張つき（HDR 等）で試し、だめなら通常に戻す。
     */
    private fun bindCamera(spec: BindSpec?, onDone: Runnable?) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (spec == null && Prefs.highQuality(this)) {
                    val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
                    extFuture.addListener({
                        val ext = try {
                            extFuture.get()
                        } catch (e: Exception) {
                            Log.w(TAG, "extensions unavailable", e)
                            null
                        }
                        finishBind(provider, ext, null, onDone)
                    }, ContextCompat.getMainExecutor(this))
                } else {
                    finishBind(provider, null, spec, onDone)
                }
            } catch (e: Exception) {
                lastError = "カメラを開始できません: ${e.message}"
                Log.e(TAG, "bindCamera failed", e)
                onDone?.run()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun finishBind(provider: ProcessCameraProvider, ext: ExtensionsManager?, spec: BindSpec?, onDone: Runnable?) {
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
            // 録画つきなら、配信も録画と同じモードで安定化する（束ねたカメラ全体で 1 つのモードに揃える）
            val eisMode = spec?.eis?.takeIf { it > 0 }
            if (eisMode != null) {
                try {
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
                } catch (e: Exception) {
                    Log.w(TAG, "eis (analysis) failed", e)
                }
            }
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(analysisExecutor) { image -> handleFrame(image) }

            var eisOn = false
            val video = if (spec != null) {
                val vb = VideoCapture.Builder(spec.recorder).setTargetRotation(rotation)
                if (eisMode != null) {
                    try {
                        Camera2Interop.Extender(vb)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
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
            if (video != null && spec?.still == true) {
                val cap = buildCapture(recording = true, withInterop = false)
                attempts.add(Attempt(base, listOf(analysis, video, cap), "配信+録画+撮影", "なし"))
                attempts.add(Attempt(base, listOf(analysis, video), "配信+録画", "なし"))
                attempts.add(Attempt(base, listOf(video, cap), "録画+撮影", "なし"))
                attempts.add(Attempt(base, listOf(video), "録画のみ", "なし"))
            } else if (video != null) {
                // 静止画を束ねない（G2 録画）。カメラの同時出力が 2 本で済むので、高い解像度が通りやすい
                attempts.add(Attempt(base, listOf(analysis, video), "配信+録画", "なし"))
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
            stabLabel = "光学" + (if (ois) "あり" else "なし") + "・動画の電子式" +
                (if (!withVideo) "（録画の組み方ではない）" else if (eisOn) (if (eisMode == 2) "オン（プレビュー安定化）" else "オン（通常）") else "オフ") +
                "・端末の対応モード=" + availableStabModes().joinToString(",")
            extensionLabel = used.extension
            imageAnalysis = used.cases.filterIsInstance<ImageAnalysis>().firstOrNull()
            imageCapture = used.cases.filterIsInstance<ImageCapture>().firstOrNull()
            videoCapture = if (withVideo) video else null
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

    /** メインスレッドで束ね直し、終わるまで待つ（メイン以外から呼ぶ） */
    private fun rebindAndWait(spec: BindSpec?) {
        val latch = CountDownLatch(1)
        mainHandler.post { bindCamera(spec, Runnable { latch.countDown() }) }
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
                    // 映像を取りに来ている = G2 カメラがまた動いている。録画後の自動停止は取り消す
                    stopWhenRecordingEnds = false
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
        // 録画中なら、録画をそのまま続ける。録画が終わったあとに止める
        if (session != null) {
            Log.i(TAG, "G2 カメラが終了しましたが、録画中なので続けます（録画が終わったら止めます）")
            stopWhenRecordingEnds = true
            return true
        }
        // 返事を返し終えてから止める（書き出し中なら済んでから）
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
        synchronized(recordLock) {
            if (session != null) throw IllegalStateException("録画中はカメラを切り替えられません")
            stopBufferLocked()
            facingValue = next
            zoomNow = 1f
            frameLock.withLock { latestFrame = null }
            // 切り替えが済んでから返す（直後にズーム範囲を読むため）。録画の準備をしていたなら、新しいカメラで準備し直す
            val a = armed
            if (a != null) {
                try {
                    val res = bindForRecordingLocked(a.quality, a.still)
                    armed = ArmedConfig(a.quality, a.audio, a.still, next, a.stopTempC, res)
                    bufRetryAt = 0
                    requestBufferStart()
                } catch (e: Exception) {
                    Log.w(TAG, "arm after facing change failed", e)
                    armed = null
                }
            } else {
                rebindAndWait(null)
            }
            refreshNotification()
        }
    }

    override fun capture(): ByteArray = synchronized(captureLock) {
        if (imageCapture == null && session == null && armed != null) {
            // G2 録画の準備（静止画を束ねない組み方）のまま撮影を頼まれた：準備を解いて撮影できる組み方に戻す
            synchronized(recordLock) {
                if (session == null && armed != null) disarmLocked()
            }
        }
        val cap = imageCapture
            ?: throw IllegalStateException(if (session != null) "この端末では録画中に撮影できません" else "カメラが起動していません")

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

    // ── 録画：組み方 ──
    private fun qualityOf(name: String): Quality = when (name.lowercase(Locale.US)) {
        "uhd", "4k" -> Quality.UHD
        "hd", "720p" -> Quality.HD
        "sd", "480p" -> Quality.SD
        else -> Quality.FHD
    }

    /** 画質の名前 → 短辺の画素数（頼んだ画質が通ったかの判定用） */
    private fun shortSideOf(q: Quality): Int = when (q) {
        Quality.UHD -> 2160
        Quality.FHD -> 1080
        Quality.HD -> 720
        else -> 480
    }

    /**
     * 束ねた録画の実際の解像度（分からなければ null）。
     * CameraX 1.3 の VideoCapture には resolutionInfo が無いので、束ねた面の解像度を読む（ライブラリ内向けの API）
     */
    @android.annotation.SuppressLint("RestrictedApi")
    private fun videoSize(vc: VideoCapture<Recorder>): Size? = try {
        vc.attachedSurfaceResolution
    } catch (e: Exception) {
        null
    }

    private fun sizeLabel(s: Size): String = "${maxOf(s.width, s.height)}x${minOf(s.width, s.height)}"

    private fun newRecorder(q: Quality): Recorder = Recorder.Builder()
        .setExecutor(recorderExecutor)
        .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()

    /**
     * 録画できる組み方に束ねる（recordLock の中で、メイン以外から呼ぶ）。実際の解像度（分からなければ空）を返す。
     * 前に頼んだ画質が通った組み方（向き・画質・静止画の有無ごとに覚える）があれば、それで 1 回だけ束ねる。
     * 無ければ手ブレ補正のモードを強い順に試し、頼んだ画質が通ったもの（だめなら一番高かったもの）を覚える。
     * 録画できる組み方が無ければ、配信＋撮影に戻して例外を投げる。
     */
    private fun bindForRecordingLocked(quality: String, still: Boolean): String {
        val want = qualityOf(quality)
        val wantShort = shortSideOf(want)
        val stabOn = Prefs.stabilization(this)
        val tried = ArrayList<String>()
        fun tag(e: Int) = "${if (still) "撮影あり" else "撮影なし"}・補正$e"
        // 手ブレ補正がオフなら補正なしの 1 通りだけ
        val cached = if (stabOn) Prefs.planEis(this, facingValue, quality, still) else 0
        var done = false
        if (cached != null) {
            rebindAndWait(BindSpec(newRecorder(want), still, cached))
            val v = videoCapture
            if (v != null) {
                tried.add("${tag(cached)}=${videoSize(v)?.let { sizeLabel(it) } ?: "不明"}${if (stabOn) "（覚えていた組み方）" else ""}")
                done = true
            } else {
                tried.add("${tag(cached)}=束ねられない")
            }
        }
        if (!done && stabOn) {
            val modes = availableStabModes()
            val eis0 = chooseEisMode() ?: 0
            val plans = ArrayList<Int>()
            fun add(e: Int) {
                if (!plans.contains(e) && e != cached) plans.add(e)
            }
            add(eis0)
            if (eis0 == 2 && modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) add(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            add(0)
            var bestEis = -1
            var bestShort = -1
            var lastBound = -1
            for (e in plans) {
                rebindAndWait(BindSpec(newRecorder(want), still, e))
                val v = videoCapture
                if (v == null) {
                    tried.add("${tag(e)}=束ねられない")
                    continue
                }
                lastBound = e
                val size = videoSize(v)
                val short = size?.let { minOf(it.width, it.height) } ?: 0
                tried.add("${tag(e)}=${size?.let { sizeLabel(it) } ?: "不明"}")
                if (short > bestShort) {
                    bestShort = short
                    bestEis = e
                }
                if (short >= wantShort || size == null) break
            }
            if (bestEis >= 0) {
                if (bestEis != lastBound) rebindAndWait(BindSpec(newRecorder(want), still, bestEis))
                Prefs.setPlanEis(this, facingValue, quality, still, bestEis)
            }
        }
        Log.i(TAG, "録画の組み方（頼んだ短辺 $wantShort）: ${tried.joinToString(" / ")}")
        val vc = videoCapture
        val resolution = vc?.let { v -> videoSize(v)?.let { sizeLabel(it) } } ?: ""
        recordResLabel = if (vc != null) {
            "${resolution.ifEmpty { "不明" }}（頼んだ画質 $quality。試した組み方: ${tried.joinToString(" / ")}）"
        } else {
            "録画できません（${tried.joinToString(" / ")}）"
        }
        diagText = buildDiag()
        if (vc == null) {
            // 録画できる組み合わせが無かった。理由を控えてから、元の配信＋撮影に戻す
            val reason = lastError ?: "この端末では録画を開始できません"
            val notes = bindNotes
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind failed", e)
            }
            throw IllegalStateException("$reason${if (notes.isNotEmpty()) "（$notes）" else ""}")
        }
        return resolution
    }

    // ── 録画：1 本ずつの録り始め・保存 ──
    private fun newVideoName(): String = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

    /** 保存先：選んだフォルダがあればそこへ直接、無ければ写真アプリ（DCIM/G2Camera） */
    private fun newDestinationRec(name: String, audio: Boolean): Rec = Rec(name, audio, null, Storage.openVideoInTree(this, name))

    private fun preRollDir(): File = File(noBackupFilesDir, "preroll").also { it.mkdirs() }
    private fun mergeDir(): File = File(noBackupFilesDir, "merge").also { it.mkdirs() }

    private fun newChunkRec(audio: Boolean): Rec {
        chunkSeq += 1
        val name = "chunk_$chunkSeq.mp4"
        return Rec(name, audio, File(preRollDir(), name), null)
    }

    /**
     * rec を録り始め、始まるまで待つ（recordLock の中で、メイン以外から呼ぶ）。
     * 前の 1 本を stop した直後に呼んでもよい（前の保存が済みしだい始まる）。失敗したら作りかけを消して例外を投げる
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecLocked(vc: VideoCapture<Recorder>, rec: Rec) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "main thread" }
        // 動画の向きは 1 本ごとに、始める時点の端末の向きに合わせる
        val r = rotation
        if (vc.targetRotation != r) onMainAndWait { vc.targetRotation = r }
        val pending: PendingRecording = try {
            when {
                rec.file != null -> vc.output.prepareRecording(this, FileOutputOptions.Builder(rec.file).build())
                rec.target != null -> vc.output.prepareRecording(this, FileDescriptorOutputOptions.Builder(rec.target.pfd).build())
                else -> {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, rec.name)
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
            }
        } catch (e: Exception) {
            cleanupRec(rec)
            throw IllegalStateException("録画の準備に失敗: ${e.message}")
        }
        val p = if (rec.audio) {
            try {
                pending.withAudioEnabled()
            } catch (e: Exception) {
                Log.w(TAG, "audio enable failed", e)
                pending
            }
        } else {
            pending
        }
        try {
            rec.recording = p.start(ContextCompat.getMainExecutor(this)) { event -> onRecEvent(rec, event) }
        } catch (e: Exception) {
            cleanupRec(rec)
            throw IllegalStateException("録画を始められません: ${e.message}")
        }
        if (!rec.started.await(8, TimeUnit.SECONDS)) {
            rec.stopAt = SystemClock.elapsedRealtime()
            try {
                rec.recording?.close()
            } catch (_: Exception) {
            }
            cleanupRec(rec)
            throw IllegalStateException("録画が始まりません（タイムアウト。構成=$boundLabel）")
        }
        rec.startFailure?.let {
            cleanupRec(rec)
            throw IllegalStateException(it)
        }
    }

    /** 録画の知らせ（メインスレッド）。ここでは recordLock を持たない */
    private fun onRecEvent(rec: Rec, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                rec.startedAt = SystemClock.elapsedRealtime()
                rec.started.countDown()
            }
            is VideoRecordEvent.Status -> {
                rec.durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                rec.bytes = event.recordingStats.numBytesRecorded
                if (rec.sessionTapAt > 0) recordingLabel = formatDuration(SystemClock.elapsedRealtime() - rec.sessionTapAt)
            }
            is VideoRecordEvent.Finalize -> {
                rec.durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                rec.bytes = event.recordingStats.numBytesRecorded
                if (event.hasError() && event.recordingStats.numBytesRecorded == 0L) {
                    rec.startFailure = "録画に失敗（${errorText(event.error)}）: ${event.cause?.message ?: ""}"
                }
                rec.target?.let { closeQuietly(it) }
                rec.finalizeEvent = event
                val unexpected = rec.stopAt == 0L && rec.startedAt > 0L
                if (rec.stopAt == 0L) rec.stopAt = SystemClock.elapsedRealtime()
                rec.finalized.countDown()
                rec.started.countDown()
                // こちらが止めていないのに終わった（カメラを取られた・容量不足など）
                if (unexpected && running) postWork { onUnexpectedEnd(rec, event) }
            }
            else -> {}
        }
    }

    private fun errorText(code: Int): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "空き容量が足りません"
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "カメラが止まりました"
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "符号化に失敗"
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "映像がありません"
        else -> "コード $code"
    }

    private fun onUnexpectedEnd(rec: Rec, ev: VideoRecordEvent.Finalize) {
        val why = "録画が途中で止まりました（${errorText(ev.error)}）"
        synchronized(recordLock) {
            val s = session
            if (s != null && s.current === rec) {
                Log.w(TAG, "$why ${ev.cause?.message ?: ""}")
                stopSessionLocked(why, rebind = true)?.let { pendingResult = it }
                return
            }
            if (bufCur === rec) {
                Log.w(TAG, "遡り録画の録りためが止まりました（${errorText(ev.error)}）")
                bufTimer?.let { mainHandler.removeCallbacks(it) }
                bufTimer = null
                bufCur = null
                rec.file?.delete()
                bufError = why
                bufRetryAt = SystemClock.elapsedRealtime() + 10_000
                refreshNotification()
            }
        }
    }

    private fun cleanupRec(rec: Rec) {
        rec.file?.delete()
        rec.target?.let {
            closeQuietly(it)
            Storage.deleteQuietly(this, it.uri)
        }
    }

    /** rec を止めて保存が済むのを待つ */
    private fun stopRecWait(rec: Rec, timeoutSec: Long) {
        if (rec.stopAt == 0L) rec.stopAt = SystemClock.elapsedRealtime()
        try {
            rec.recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        if (!rec.finalized.await(timeoutSec, TimeUnit.SECONDS)) Log.w(TAG, "録画の保存待ちがタイムアウトしました（${rec.name}）")
    }

    // ── 録画：準備 ──
    override fun prepare(quality: String, audio: Boolean, still: Boolean, stopTempC: Double, off: Boolean) {
        synchronized(recordLock) {
            if (off) {
                pendingArm = null
                disarmLocked()
                return
            }
            if (!running) throw IllegalStateException("カメラが起動していません")
            val req = ArmRequest(quality, audio, still, stopTempC)
            if (session != null) {
                // 録画中：録画が終わってから準備し直す
                pendingArm = req
                return
            }
            armLocked(req)
        }
    }

    private fun armLocked(req: ArmRequest) {
        val a = armed
        if (a != null && a.quality == req.quality && a.still == req.still && a.facing == facingValue && videoCapture != null) {
            // 組み方は同じ。音声の有無が変わったなら録りためだけ録り直す
            if (a.audio != req.audio) stopBufferLocked()
            armed = ArmedConfig(req.quality, req.audio, req.still, facingValue, req.stopTempC, a.resolution)
            requestBufferStart()
            return
        }
        stopBufferLocked()
        armed = null
        val res = bindForRecordingLocked(req.quality, req.still)
        armed = ArmedConfig(req.quality, req.audio, req.still, facingValue, req.stopTempC, res)
        Log.i(TAG, "録画の準備ができました（${req.quality} $res・音声 ${req.audio}・撮影 ${req.still}）")
        bufRetryAt = 0
        bufError = null
        requestBufferStart()
        refreshNotification()
    }

    /** 準備を解いて、配信＋撮影の組み方に戻す */
    private fun disarmLocked() {
        val wasArmed = armed != null
        armed = null
        stopBufferLocked()
        bufPaused = null
        if (wasArmed && session == null && running && videoCapture != null) {
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind (disarm) failed", e)
            }
        }
        refreshNotification()
    }

    // ── 録画：遡り録画の録りため ──
    /** 切れ端 1 本の長さ。遡る秒数より少し長く（短すぎると切り替えが多くなるので最低 10 秒） */
    private fun chunkMs(): Long = (max(Prefs.preRollSeconds(this), 10) + 2) * 1000L

    /** 録りためを（始められるなら）別のスレッドで始める。録画の停止・準備の返事を待たせないため */
    private fun requestBufferStart() {
        postWork { synchronized(recordLock) { maybeStartBufferLocked() } }
    }

    /** 遡り録画がオンで、準備ができていて、録画していなければ録りためを始める（recordLock の中で） */
    private fun maybeStartBufferLocked() {
        val a = armed ?: return
        if (!running || !Prefs.preRoll(this) || session != null || bufCur != null || bufPaused != null || stopWhenRecordingEnds) return
        if (a.facing != facingValue) return
        val vc = videoCapture ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < bufRetryAt) return
        val rec = newChunkRec(a.audio && audioPermitted)
        try {
            startRecLocked(vc, rec)
        } catch (e: Exception) {
            bufError = e.message ?: e.javaClass.simpleName
            bufRetryAt = now + 30_000
            Log.w(TAG, "遡り録画の録りためを始められません: $bufError")
            refreshNotification()
            return
        }
        bufError = null
        bufCur = rec
        scheduleBufferRollLocked()
        refreshNotification()
    }

    private fun scheduleBufferRollLocked() {
        bufTimer?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { postWork { synchronized(recordLock) { rollBufferLocked() } } }
        bufTimer = r
        mainHandler.postDelayed(r, chunkMs())
    }

    /** 切れ端を次へ回す：今のを止め、すぐ次を始め、止めた方を「一つ前」として残す（その前のは消す） */
    private fun rollBufferLocked() {
        val old = bufCur ?: return
        if (session != null) return
        val vc = videoCapture ?: return
        bufTimer = null
        val next = newChunkRec(old.audio)
        old.stopAt = SystemClock.elapsedRealtime()
        try {
            old.recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        var startedNext = true
        try {
            startRecLocked(vc, next)
        } catch (e: Exception) {
            startedNext = false
            bufError = e.message ?: e.javaClass.simpleName
            bufRetryAt = SystemClock.elapsedRealtime() + 30_000
            Log.w(TAG, "次の切れ端を始められません: $bufError")
        }
        old.finalized.await(10, TimeUnit.SECONDS)
        val keep = old.isFinalized && old.startFailure == null && old.durationMs > 0 && (old.file?.length() ?: 0L) > 0L
        bufPrev?.file?.delete()
        bufPrev = if (keep) old else {
            old.file?.delete()
            null
        }
        bufCur = if (startedNext) next else null
        if (startedNext) scheduleBufferRollLocked() else refreshNotification()
    }

    /** 録りためをやめて切れ端を消す（recordLock の中で） */
    private fun stopBufferLocked() {
        bufTimer?.let { mainHandler.removeCallbacks(it) }
        bufTimer = null
        val cur = bufCur
        bufCur = null
        if (cur != null) stopRecWait(cur, 10)
        cur?.file?.delete()
        bufPrev?.file?.delete()
        bufPrev = null
        if (cur != null) refreshNotification()
    }

    /** いま録画を始めたら含められる遡りの長さ */
    private fun readyMsNow(wantMs: Long): Long {
        val cur = bufCur ?: return 0L
        if (cur.startedAt == 0L) return 0L
        val prev = bufPrev?.takeIf { it.isFinalized && it.startFailure == null }
        return PreRollPlan.plan(SystemClock.elapsedRealtime(), cur.startedAt, wantMs, prev?.let { PreRollPlan.Prev(it.durationMs, it.stopAt) }).preRollMs
    }

    override val preRollInfo: PreRollInfo
        get() {
            val enabled = Prefs.preRoll(this)
            val sec = Prefs.preRollSeconds(this)
            val state = when {
                !enabled -> "off"
                session != null -> "recording"
                armed == null -> "idle"
                bufPaused == "heat" -> "paused-heat"
                bufPaused == "space" -> "paused-space"
                bufCur != null -> "buffering"
                bufError != null -> "error"
                else -> "idle"
            }
            return PreRollInfo(enabled, sec, state, if (state == "buffering") readyMsNow(sec * 1000L) else 0L)
        }

    fun describePreRoll(): String {
        val p = preRollInfo
        return when (p.state) {
            "off" -> "オフ"
            "idle" -> "オン（${p.seconds} 秒）・G2 録画を開くと録りためを始めます"
            "buffering" -> "● 録りため中（直前 ${p.readyMs / 1000} / ${p.seconds} 秒ぶん用意できています）"
            "recording" -> "録画中（直前 ${p.seconds} 秒ぶんをつなぎます）"
            "paused-heat" -> "スマホが熱いので休止中（冷えたら再開）"
            "paused-space" -> "空き容量が少ないので休止中"
            "error" -> "始められません：${bufError ?: "不明"}"
            else -> p.state
        }
    }

    // ── 録画：開始・区切り・停止 ──
    private fun snapshot(s: Session): RecordingState {
        val cur = s.current
        val rate = if (cur.durationMs > 1000) cur.bytes * 1000 / cur.durationMs else 0L
        return RecordingState(
            name = if (s.segment == 1) s.firstName else cur.name,
            elapsedMs = SystemClock.elapsedRealtime() - s.tapAt,
            bytes = s.finishedBytes + cur.bytes,
            audio = s.audio,
            live = s.live,
            capture = s.capture,
            resolution = s.resolution,
            segment = s.segment,
            preRollMs = s.preRoll?.plan?.preRollMs ?: 0L,
            bytesPerSec = rate,
        )
    }

    override fun startRecording(o: StartOptions): RecordingState {
        val tapAt = SystemClock.elapsedRealtime()
        synchronized(recordLock) {
            session?.let { return snapshot(it) }
            if (camera == null) throw IllegalStateException("カメラが起動していません")
            val thumb = latestFrame?.bytes
            val withAudio = o.audio && audioPermitted
            var a = armed
            if (a != null && !(a.quality == o.quality && a.still == o.still && a.facing == facingValue && videoCapture != null)) {
                // 準備と違う頼み方：静止画なし（G2 録画）なら頼まれた組み方で準備し直し、静止画あり（G2 カメラ）なら準備を解く
                stopBufferLocked()
                armed = null
                a = null
                if (!o.still) {
                    val res = bindForRecordingLocked(o.quality, o.still)
                    val na = ArmedConfig(o.quality, o.audio, o.still, facingValue, o.stopTempC, res)
                    armed = na
                    a = na
                }
            }
            val armedNow = a
            val name = newVideoName()
            val cur = bufCur
            val s: Session
            if (armedNow != null && cur != null && cur.audio == withAudio && cur.startedAt > 0L && !cur.isFinalized) {
                // 遡り録画：いま録っている切れ端をそのまま本番にする（つなぎ目なし）。足りない分は一つ前の切れ端から
                bufTimer?.let { mainHandler.removeCallbacks(it) }
                bufTimer = null
                val prev = bufPrev?.takeIf { it.isFinalized && it.startFailure == null && it.file?.exists() == true }
                val plan = PreRollPlan.plan(
                    tapAt, cur.startedAt, Prefs.preRollSeconds(this) * 1000L,
                    prev?.let { PreRollPlan.Prev(it.durationMs, it.stopAt) },
                )
                if (!plan.prevUse) bufPrev?.file?.delete()
                bufCur = null
                bufPrev = null
                cur.sessionTapAt = tapAt
                s = Session(o, tapAt, withAudio, armedNow.resolution, imageAnalysis != null, imageCapture != null, cur, PreRollUse(if (plan.prevUse) prev else null, plan), name)
                Log.i(TAG, "録画開始 $name（遡り ${plan.preRollMs}ms：前の切れ端 ${if (plan.prevUse) "${plan.prevFromMs}ms から" else "使わない"}・今の切れ端 ${plan.curFromMs}ms から）")
            } else {
                stopBufferLocked()
                val resolution = armedNow?.resolution ?: bindForRecordingLocked(o.quality, o.still)
                val vc = videoCapture ?: throw IllegalStateException("録画を開始できません（構成=$boundLabel）")
                val rec = newDestinationRec(name, withAudio)
                try {
                    startRecLocked(vc, rec)
                } catch (e: Exception) {
                    if (armedNow == null) {
                        try {
                            rebindAndWait(null)
                        } catch (_: Exception) {
                        }
                    }
                    throw e
                }
                rec.sessionTapAt = tapAt
                s = Session(o, tapAt, withAudio, resolution, imageAnalysis != null, imageCapture != null, rec, null, name)
                Log.i(TAG, "録画開始 $name（音声 $withAudio・配信 ${s.live}・撮影 ${s.capture}・解像度 $resolution・準備 ${armedNow != null}）")
            }
            s.thumb = thumb
            session = s
            recordingLabel = "0:00"
            scheduleSessionTimerLocked(s)
            refreshNotification()
            return snapshot(s)
        }
    }

    /** 最長時間（区切り録画なら次の区切り）のタイマー。区切りはタップから maxMs ごと */
    private fun scheduleSessionTimerLocked(s: Session) {
        s.timer?.let { mainHandler.removeCallbacks(it) }
        s.timer = null
        if (s.opts.maxMs <= 0) return
        val due = s.tapAt + s.opts.maxMs * s.segment
        val r = Runnable { postWork { synchronized(recordLock) { onSegmentDueLocked(s) } } }
        s.timer = r
        mainHandler.postDelayed(r, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun onSegmentDueLocked(s: Session) {
        if (session !== s) return
        if (s.opts.continuous) {
            rollSegmentLocked(s, null)
        } else {
            Log.i(TAG, "録画を止めます（最長録画時間）")
            stopSessionLocked(null, rebind = true)?.let { pendingResult = it }
        }
    }

    /** 区切り録画：今のファイルを閉じ、すぐ次のファイルへ録り続ける */
    private fun rollSegmentLocked(s: Session, note: String?) {
        if (session !== s) return
        val vc = videoCapture
        if (vc == null) {
            stopSessionLocked(note, rebind = true)?.let { pendingResult = it }
            return
        }
        val old = s.current
        old.stopAt = SystemClock.elapsedRealtime()
        try {
            old.recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        val next = newDestinationRec(newVideoName(), s.audio)
        var startError: String? = null
        try {
            startRecLocked(vc, next)
        } catch (e: Exception) {
            startError = e.message ?: e.javaClass.simpleName
        }
        if (!old.finalized.await(30, TimeUnit.SECONDS)) Log.w(TAG, "区切りの保存待ちがタイムアウトしました（${old.name}）")
        val r = finishSegmentLocked(s, old, note)
        if (startError != null) {
            // 続きを始められなかった：ここで録画を終える（書き終えた分は結果として渡す）
            Log.w(TAG, "区切りの続きを始められません: $startError")
            s.timer?.let { mainHandler.removeCallbacks(it) }
            s.timer = null
            session = null
            recordingLabel = null
            pendingResult = r.copy(error = listOfNotNull(r.error, "区切りの続きを始められませんでした（$startError）").joinToString("。"))
            refreshNotification()
            afterSessionLocked(rebind = true)
            return
        }
        next.sessionTapAt = s.tapAt
        synchronized(segmentLock) {
            completedSegments.addLast(r)
            while (completedSegments.size > MAX_SEGMENT_QUEUE) completedSegments.removeFirst()
        }
        s.finishedBytes += r.bytes
        s.current = next
        s.segment += 1
        s.thumb = latestFrame?.bytes
        scheduleSessionTimerLocked(s)
        refreshNotification()
        Log.i(TAG, "区切りました：${r.name}（${r.durationMs}ms）→ ${next.name}（${s.segment} 本目）")
    }

    /** 書き終えた 1 本の結果を作る。1 本目に遡りをつなぐなら、つなぎ合わせを予約して「書き出し中」で返す */
    private fun finishSegmentLocked(s: Session, rec: Rec, note: String?): RecordResult {
        val first = s.segment == 1
        val pre = if (first) s.preRoll else null
        val name = if (first) s.firstName else rec.name
        val file = rec.file
        if (pre != null && file != null) {
            val tail = (rec.stopAt - s.tapAt).coerceAtLeast(0L)
            val dur = pre.plan.preRollMs + tail
            val rate = if (rec.durationMs > 0) rec.bytes.toDouble() / rec.durationMs else 0.0
            val parts = ArrayList<Mp4Merger.Part>()
            pre.prev?.file?.let { parts.add(Mp4Merger.Part(it, pre.plan.prevFromMs * 1000L)) }
            parts.add(Mp4Merger.Part(file, pre.plan.curFromMs * 1000L))
            if (rec.finalizeEvent != null && rec.bytes > 0L && file.length() > 0L) {
                enqueueMerge(name, parts, dur * 1000L)
                return RecordResult(
                    name = name, uri = null, durationMs = dur, bytes = (rate * dur).toLong(), audio = s.audio,
                    thumbnail = s.thumb, error = note, resolution = s.resolution, preRollMs = pre.plan.preRollMs,
                    pending = true, segment = s.segment,
                )
            }
            parts.forEach { it.file.delete() }
            return RecordResult(
                name = name, uri = null, durationMs = dur, bytes = 0L, audio = s.audio, thumbnail = s.thumb,
                error = listOfNotNull(note, "録画を保存できませんでした").joinToString("。"), resolution = s.resolution,
                segment = s.segment,
            )
        }
        val ev = rec.finalizeEvent
        val uri = ev?.outputResults?.outputUri?.takeIf { it != Uri.EMPTY } ?: rec.target?.uri
        if (uri != null) recentFiles[name] = uri
        val error = when {
            ev == null -> "保存結果を受け取れませんでした"
            ev.hasError() -> "保存時のエラー（${errorText(ev.error)}）${ev.cause?.message?.let { ": $it" } ?: ""}"
            else -> null
        }
        return RecordResult(
            name = name, uri = uri, durationMs = rec.durationMs, bytes = rec.bytes, audio = s.audio,
            thumbnail = s.thumb, error = listOfNotNull(note, error).joinToString("。").ifEmpty { null },
            resolution = s.resolution, preRollMs = 0L, pending = false, segment = s.segment,
        )
    }

    /** 録画を止めて最後の 1 本の結果を返す（recordLock の中で）。録画していなければ null */
    private fun stopSessionLocked(note: String?, rebind: Boolean): RecordResult? {
        val s = session ?: return null
        s.timer?.let { mainHandler.removeCallbacks(it) }
        s.timer = null
        val rec = s.current
        stopRecWait(rec, 30)
        session = null
        recordingLabel = null
        val r = finishSegmentLocked(s, rec, note)
        Log.i(TAG, "録画終了 ${r.name} ${r.durationMs}ms ${r.bytes}B 遡り ${r.preRollMs}ms 書き出し中 ${r.pending} uri=${r.uri} error=${r.error}")
        refreshNotification()
        afterSessionLocked(rebind)
        return r
    }

    /** 録画が終わったあと：準備していたならそのまま（録りためを再開）、していなければ配信＋撮影に戻す */
    private fun afterSessionLocked(rebind: Boolean) {
        // 録画中に G2 カメラが終了していたなら、録画が済んだのでサービスも止める
        if (stopWhenRecordingEnds && Prefs.autoStop(this)) {
            stopWhenRecordingEnds = false
            armed = null
            pendingArm = null
            mainHandler.post { shutdownGracefully() }
            return
        }
        val req = pendingArm
        pendingArm = null
        if (req != null && running) {
            try {
                armLocked(req)
                return
            } catch (e: Exception) {
                Log.w(TAG, "arm after recording failed", e)
                armed = null
            }
        }
        val a = armed
        if (a != null && videoCapture != null && a.facing == facingValue) {
            requestBufferStart()
        } else if (rebind && running) {
            armed = null
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind after recording failed", e)
            }
        }
        refreshNotification()
    }

    override fun stopRecording(): RecordResult? = synchronized(recordLock) { stopSessionLocked(null, rebind = true) }

    override fun takePendingResult(): RecordResult? {
        val r = pendingResult
        pendingResult = null
        return r
    }

    override fun takeSegments(): List<RecordResult> = synchronized(segmentLock) {
        val list = ArrayList(completedSegments)
        completedSegments.clear()
        list
    }

    /** 自分の都合で止めたときは、結果を G2 カメラ側が取りに来るまで持っておく */
    private fun stopRecordingInternally(reason: String, note: String? = null) {
        try {
            synchronized(recordLock) {
                if (session == null) return
                Log.i(TAG, "録画を止めます（$reason）")
                stopSessionLocked(note, rebind = true)?.let { pendingResult = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop recording ($reason) failed", e)
        }
    }

    // ── 見張り ──
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun describeMonitor(m: DeviceMonitor.Snapshot): String {
        val parts = ArrayList<String>()
        if (m.batteryPct >= 0) parts.add("電池 ${m.batteryPct}%${if (m.charging) "（充電中）" else ""}")
        if (!m.tempC.isNaN()) parts.add("温度 ${fmt1(m.tempC)}度")
        if (m.thermal >= PowerManager.THERMAL_STATUS_MODERATE) parts.add("発熱 段階${m.thermal}")
        if (m.freeBytes >= 0) parts.add("空き ${fmt1(m.freeBytes / 1024.0 / 1024.0 / 1024.0)}GB")
        return parts.joinToString("・")
    }

    private fun checkHealth() {
        val m = DeviceMonitor.read(this)
        lastMonitor = m
        monitorLabel = describeMonitor(m)
        val s = session
        if (s != null) {
            val hot = (s.opts.stopTempC > 0 && !m.tempC.isNaN() && m.tempC >= s.opts.stopTempC) ||
                m.thermal >= PowerManager.THERMAL_STATUS_CRITICAL
            if (hot) {
                stopRecordingInternally("高温", "スマホが熱くなったので録画を止めて保存しました（${if (m.tempC.isNaN()) "発熱 段階${m.thermal}" else "${fmt1(m.tempC)}度"}）")
                return
            }
            if (m.freeBytes >= 0) {
                // 1 本目を切れ端のまま録っているときは、つなぎ合わせで同じだけの空きが要る
                val cacheSeg = s.preRoll != null && s.segment == 1
                val curBytes = s.current.bytes
                val need = s.opts.minFreeBytes + (if (cacheSeg) curBytes + 200 * MB else 0L)
                if (m.freeBytes < need) {
                    synchronized(recordLock) {
                        if (session !== s) return
                        if (cacheSeg && s.opts.continuous && m.freeBytes >= s.opts.minFreeBytes + curBytes) {
                            Log.i(TAG, "空き容量の都合で 1 本目を早めに区切ります")
                            rollSegmentLocked(s, "空き容量の都合で早めに区切りました")
                        } else {
                            Log.i(TAG, "録画を止めます（空き容量）")
                            stopSessionLocked("空き容量が少なくなったので録画を止めて保存しました", rebind = true)?.let { pendingResult = it }
                        }
                    }
                }
            }
            return
        }
        synchronized(recordLock) {
            if (session != null) return
            val a = armed
            if (a == null || !Prefs.preRoll(this)) {
                bufPaused = null
                return
            }
            val pauseAt = if (a.stopTempC > 0) a.stopTempC - 3 else 45.0
            val paused = bufPaused
            val resume: Boolean
            if (paused == null) {
                val hot = m.thermal >= PowerManager.THERMAL_STATUS_SEVERE || (!m.tempC.isNaN() && m.tempC >= pauseAt)
                val full = m.cacheFreeBytes in 0L until BUFFER_PAUSE_FREE
                if (hot || full) {
                    Log.i(TAG, "遡り録画の録りためを休みます（${if (hot) "高温" else "空き容量"}）")
                    stopBufferLocked()
                    bufPaused = if (hot) "heat" else "space"
                    refreshNotification()
                    return
                }
                // 録りためが止まっていたら（始められなかった・途中で止まった）やり直す
                resume = bufCur == null
            } else if (paused == "heat") {
                resume = m.thermal < PowerManager.THERMAL_STATUS_MODERATE && (m.tempC.isNaN() || m.tempC < pauseAt - 2)
            } else {
                resume = m.cacheFreeBytes < 0 || m.cacheFreeBytes > BUFFER_RESUME_FREE
            }
            if (resume) {
                if (paused != null) Log.i(TAG, "遡り録画の録りためを再開します")
                bufPaused = null
                maybeStartBufferLocked()
            }
        }
    }

    // ── 遡り録画のつなぎ合わせ ──
    private fun manifestFor(name: String) = File(mergeDir(), "$name.json")

    /** つなぎ合わせの段取りを書き残す（途中でアプリが止まっても、次回の開始時にやり直せるように） */
    private fun writeManifest(name: String, parts: List<Mp4Merger.Part>, expectUs: Long, outUri: Uri?) {
        try {
            val arr = JSONArray()
            for (p in parts) arr.put(JSONObject().put("path", p.file.path).put("startUs", p.startUs))
            val j = JSONObject().put("name", name).put("expectUs", expectUs).put("parts", arr)
            if (outUri != null) j.put("out", outUri.toString())
            manifestFor(name).writeText(j.toString())
        } catch (e: Exception) {
            Log.w(TAG, "manifest write failed", e)
        }
    }

    private fun enqueueMerge(name: String, parts: List<Mp4Merger.Part>, expectUs: Long) {
        writeManifest(name, parts, expectUs, null)
        val latch = CountDownLatch(1)
        merging[name] = latch
        refreshNotification()
        try {
            mergeExecutor.execute {
                try {
                    runMerge(name, parts, expectUs)
                } catch (e: Throwable) {
                    Log.e(TAG, "merge crashed: $name", e)
                    mergeNote = "$name の書き出しに失敗しました（${e.message}）"
                } finally {
                    merging.remove(name)
                    latch.countDown()
                    mainHandler.post { refreshNotification() }
                }
            }
        } catch (e: RejectedExecutionException) {
            merging.remove(name)
            latch.countDown()
        }
    }

    private fun runMerge(name: String, parts: List<Mp4Merger.Part>, expectUs: Long) {
        val t0 = SystemClock.elapsedRealtime()
        var uri: Uri? = null
        var why = ""
        val out = Storage.openVideoOutput(this, name)
        if (out == null) {
            why = "書き出し先を作れません"
        } else {
            writeManifest(name, parts, expectUs, out.uri)
            val r = Mp4Merger.merge(parts, out.pfd.fileDescriptor)
            if (r.ok) {
                out.publish(this)
                if (Mp4Merger.validate(this, out.uri, expectUs)) {
                    uri = out.uri
                } else {
                    why = "つないだ動画を読み直せません"
                    out.discard(this)
                }
            } else {
                why = r.message
                out.discard(this)
            }
        }
        var note: String? = null
        if (uri == null) {
            // 保険：材料をそのまま保存先へ写す（今の切れ端は本番の録画を含む。前の切れ端は別名で）
            Log.w(TAG, "つなぎ合わせに失敗（$why）。材料をそのまま保存します")
            uri = Storage.copyVideo(this, parts.last().file, name)
            val preName = name.removeSuffix(".mp4") + "_pre.mp4"
            if (uri != null && parts.size > 1) Storage.copyVideo(this, parts.first().file, preName)
            note = if (uri != null) {
                "$name：遡りをつなげませんでした（$why）" + (if (parts.size > 1) "。直前の分は $preName に保存しました" else "")
            } else {
                "$name：書き出せませんでした（$why）。空き容量を確保してからコンパニオンを開き直すと、もう一度書き出します"
            }
        }
        if (uri != null) {
            recentFiles[name] = uri
            parts.forEach { it.file.delete() }
            manifestFor(name).delete()
        } else {
            writeManifest(name, parts, expectUs, null)
        }
        if (note != null) mergeNote = note
        Log.i(TAG, "書き出し $name：${if (uri != null) "済み" else "失敗"}（${SystemClock.elapsedRealtime() - t0}ms）$why")
    }

    /** 前回つなぎ終えられなかった分を書き出し直し、使っていない切れ端を消す（開始時に一度） */
    private fun recoverMerges() {
        val referenced = HashSet<String>()
        val files = mergeDir().listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
        for (m in files) {
            try {
                val j = JSONObject(m.readText())
                val name = j.getString("name")
                val arr = j.getJSONArray("parts")
                val parts = (0 until arr.length()).map {
                    val p = arr.getJSONObject(it)
                    Mp4Merger.Part(File(p.getString("path")), p.optLong("startUs", 0L))
                }
                if (parts.isEmpty() || parts.any { !it.file.exists() }) {
                    m.delete()
                    continue
                }
                // 前回の作りかけがあれば消してから
                j.optString("out", "").takeIf { it.isNotEmpty() }?.let { deleteUriQuietly(Uri.parse(it)) }
                parts.forEach { referenced.add(it.file.path) }
                Log.i(TAG, "前回の遡り録画を書き出し直します: $name")
                enqueueMerge(name, parts, j.optLong("expectUs", 0L))
            } catch (e: Exception) {
                Log.w(TAG, "manifest broken: ${m.name}", e)
                m.delete()
            }
        }
        synchronized(recordLock) {
            val live = setOfNotNull(bufCur?.file?.path, bufPrev?.file?.path, session?.current?.file?.path, session?.preRoll?.prev?.file?.path)
            preRollDir().listFiles()?.forEach { if (it.path !in referenced && it.path !in live) it.delete() }
        }
    }

    private fun deleteUriQuietly(uri: Uri) {
        try {
            if (contentResolver.delete(uri, null, null) > 0) return
        } catch (_: Exception) {
        }
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
        }
    }

    private fun waitMerges(timeoutMs: Long) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (merging.isNotEmpty() && SystemClock.elapsedRealtime() < until) {
            merging.values.firstOrNull()?.await(1, TimeUnit.SECONDS)
        }
    }

    // ── 保存した動画を渡す ──
    override fun openRecording(name: String): Pair<InputStream, Long>? {
        // 書き出し中なら済むまで待つ
        merging[name]?.await(180, TimeUnit.SECONDS)
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
            try {
                contentResolver.query(u, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) size = c.getLong(0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "size query failed", e)
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
