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
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
        /** コンパニオン単体で録画を始める（画面・通知・タイル。0.1.21）。extra「trigger」に始めた場所 */
        const val ACTION_START_RECORDING = "com.ef64nagaoka.g2cam.START_RECORDING"
        /** 録画中なら止め、録画していなければ単体で始める（タイル。0.1.21） */
        const val ACTION_TOGGLE_RECORDING = "com.ef64nagaoka.g2cam.TOGGLE_RECORDING"
        const val EXTRA_TRIGGER = "trigger"
        /** コンパニオンの画面・通知からズームを 1 段変える（0.1.22）。extra「dir」に +1 / -1 */
        const val ACTION_ZOOM_STEP = "com.ef64nagaoka.g2cam.ZOOM_STEP"
        const val EXTRA_DIR = "dir"
        /** 本体の画面で遡り録画の設定を変えた */
        const val ACTION_PREROLL_CHANGED = "com.ef64nagaoka.g2cam.PREROLL_CHANGED"
        /** 本体の画面で配信の fps などの設定を変えた */
        const val ACTION_PREFS_CHANGED = "com.ef64nagaoka.g2cam.PREFS_CHANGED"
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
        /** 映像を取りに来なくなってからこれだけ経ったら、フレームの変換をやめる */
        private const val FRAME_DEMAND_MS = 2_000L
        /** 何の要求も無くなってからこれだけ経ったら、カメラを閉じる（サービスは残す） */
        private const val IDLE_CAMERA_MS = 20_000L
        /** 撮影のあと、静止画の拡張つきの組み方を保っておく時間 */
        private const val EXTENSION_KEEP_MS = 60_000L
        /** 露出の値（暗さの判定）を読む間隔 */
        private const val EXPOSURE_SAMPLE_MS = 250L
        /** 夜景の拡張の表示名（extensionLabel） */
        private const val NIGHT_LABEL = "夜景"

        @Volatile var running = false
        @Volatile var lastError: String? = null
        @Volatile var framesServed = 0L
        @Volatile var photosTaken = 0
        /** 画面表示用：録画中なら「0:12」のような経過時間、録画していなければ null */
        @Volatile var recordingLabel: String? = null
        /** 録画中か・誰が始めたか（タイルと画面のボタン用。0.1.21） */
        @Volatile var recordingNow = false
        @Volatile var recordingOrigin: String? = null
        /** 単体の録画を始められなかったときの理由（画面用。始められたら null） */
        @Volatile var standaloneError: String? = null
        /** 画面表示用：カメラ構成などの診断 */
        @Volatile var diagText: String? = null
        /** 画面表示用：電池・温度・空き容量（5 秒ごとに更新） */
        @Volatile var monitorLabel: String? = null
        /** 画面表示用：配信の様子（カメラの開閉・fps） */
        @Volatile var streamLabel: String? = null
        @Volatile private var instance: CameraService? = null
        /** 書き出し中の名前（プロセス全体。サービスを作り直したときの書き出し直しで重ねないため） */
        private val activeMerges: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** 画面表示用：遡り録画の状態（サービスが動いていなければ null） */
        fun preRollText(): String? = instance?.describePreRoll()

        /** 画面表示用：低照度の状態（サービスが動いていなければ null） */
        fun lowLightText(): String? = instance?.describeLowLight()

        /** 画面表示用：レンズの倍率と、いまのズームが光学かデジタルか（サービスが動いていなければ null） */
        fun lensText(): String? = instance?.describeLens()

        /** 画面の「－ x2（デジタル 2 倍） ＋」の真ん中（0.1.22）。カメラが動いていなければ null */
        fun zoomText(): String? = instance?.zoomLabel()

        /** いまの倍率で、もう上・下に段があるか（画面のボタンを押せるか） */
        fun canZoom(dir: Int): Boolean = instance?.let { it.nextZoomStep(dir) != null } ?: false

        /** 画面表示用：録画の組み方（解像度・fps・細かさ。まだ束ねていなければ null） */
        fun recordPlanText(): String? = instance?.recordResLabel?.takeIf { it != "－" }
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
    @Volatile private var zoomSeqValue = 0
    override val zoomSeq: Int get() = zoomSeqValue
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
    /** 解析スレッドだけで使う（Bitmap を使い回す） */
    private val frameEncoder = FrameEncoder()
    /** 最後に /frame を取りに来た時刻。これが古ければ変換しない */
    @Volatile private var lastFrameRequestAt = 0L
    /** 配信の上限（ms 間隔。設定の fps から。熱いときは倍） */
    @Volatile private var streamIntervalMs = 66L
    @Volatile private var hotNow = false
    // fps の実測（1 秒ごとに締める）
    private var fpsWindowStart = 0L
    private var fpsCamera = 0
    private var fpsProduced = 0
    @Volatile private var fpsServedCount = 0
    @Volatile private var measuredCameraFps = 0
    @Volatile private var measuredProducedFps = 0
    @Volatile private var measuredServedFps = 0
    /** 誰も使っていないのでカメラを閉じている（サービスは動いている） */
    @Volatile private var cameraIdle = false
    @Volatile private var reopening = false
    /** 静止画の拡張（HDR・オート）：いま束ねているか・端末で使えるか・直近の撮影 */
    @Volatile private var extensionBound = false
    @Volatile private var extensionAvailable: Boolean? = null
    @Volatile private var extensionWanted = false
    @Volatile private var lastCaptureAt = 0L

    // ── 低照度（0.1.19）：露出の値から暗さを見て、暗いときは露光を長めにする（AE の fps 範囲の下限を下げる）。写真は夜景の拡張 ──
    private val lowLightLock = Any()
    private val lowLightDetector = LowLightDetector { Prefs.lowLightLevel(this) }
    /** 直近の露出から見積もった明るさ（EV100。まだ無ければ NaN）と、その露出（表示用） */
    @Volatile private var lowLightEv = Double.NaN
    @Volatile private var exposureLabel = ""
    @Volatile private var lastExposureAt = 0L
    /** 結果に絞りが入っていない端末向けに、レンズの絞り（束ねたカメラの値） */
    @Volatile private var defaultAperture = 1.8f
    /** いま Camera2CameraControl で掛けている AE の fps 範囲（null = 掛けていない） */
    @Volatile private var appliedLowLight: LowLight.FpsRange? = null
    /** 夜景の拡張が使えるか（向きごと。無い = まだ確かめていない）。束ねられなかったときの理由 */
    private val nightAvailable = ConcurrentHashMap<String, Boolean>()
    private val nightBindFailed = ConcurrentHashMap<String, String>()
    /** 撮影の前に夜景の拡張つきで束ねてほしい（bindCamera が読む）。いま夜景の拡張で束ねているか */
    @Volatile private var nightWanted = false
    @Volatile private var nightBound = false
    @Volatile private var nightChecking = false
    /** 直近の撮影の撮り方（night / lowlight / オート / HDR / normal） */
    @Volatile private var lastCaptureMode = ""
    /** 夜景の拡張でメガネへの配信を束ねられないときの、捨てるだけのプレビューの受け口 */
    private var dummyThread: HandlerThread? = null

    // ── fps の実測とレンズ（0.1.20）：撮影結果（カメラのスレッドに届く）から読む ──
    /** カメラから届いた枚数（撮影結果の数。録画の fps の実測に使う） */
    private val sensorFrames = java.util.concurrent.atomic.AtomicLong()
    /** カメラのスレッドだけで使う：同じ結果が 2 回届く（配信と録画の両方に付けている）のを 1 回に数えるための番号と、1 秒ごとの締め */
    private var lastFrameNumber = -1L
    private var sensorWinStart = 0L
    private var sensorWinFrames = 0L
    @Volatile private var sensorFpsNow = 0.0
    @Volatile private var lastSensorFrameAt = 0L
    /** 60fps が出るかを確かめている間（暗くても fps を下げない） */
    @Volatile private var fpsProbing = false
    /** 60fps が使えなかった組み方と理由（このサービスが動いている間だけ覚える。その場かぎりの理由かもしれないため） */
    private val plan60Failed = ConcurrentHashMap<String, String>()
    /** レンズ：束ねたカメラの物理カメラ（焦点距離・センサーの大きさ）と、その説明 */
    @Volatile private var lensCameraId: String? = null
    @Volatile private var lensNote = "未確認（カメラを開くと確かめます）"
    /** レンズの一覧（倍率の小さい順。カメラごとに 1 回だけ出す。カメラを閉じている間もそのまま返すので、G2 アプリのズームの段が揺れない） */
    @Volatile private var lensList: List<LensInfo.Lens> = emptyList()
    /** いま使っているレンズ（撮影結果から。端末が教えてくれるときだけ）と、そのときのズーム */
    @Volatile private var activeLensId: String? = null
    @Volatile private var activeLensZoom = 0f
    @Volatile private var activeLensAt = 0L
    @Volatile private var zoomChangedAt = 0L

    private val exposureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            countSensorFrame(result)
            onExposure(result)
        }
    }
    /** 録画の準備を高温で休んでいる（"heat"）。冷えたら lastArm で準備し直す */
    @Volatile private var armPaused: String? = null
    @Volatile private var lastArm: ArmRequest? = null

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
        /** 録り始めたときの動画の向き（つなぐ材料の向きが揃っているかを見る） */
        @Volatile var rotation = Surface.ROTATION_0
        /** 本番の録画に使っているとき、そのタップの時刻（経過表示用。0 なら録りためのまま） */
        @Volatile var sessionTapAt = 0L
        val isFinalized: Boolean get() = finalized.count == 0L
    }

    /**
     * 録画の fps・細かさ（0.1.20）：頼まれたもの（fpsWanted・bitrateLevel）と、束ねた結果
     * （fps = 束ねた組み方の目標の fps、bitrate = 目標のビットレート。0 なら端末の標準のまま、fpsNote = 60fps が使えなかった理由）
     */
    private class Tuning(val fpsWanted: Int, val bitrateLevel: String, val fps: Int, val bitrate: Int, val fpsNote: String) {
        companion object {
            val DEFAULT = Tuning(30, VideoTuning.STD, 30, 0, "")
        }
    }

    /** 録画の組み方を束ねた結果（実際の解像度。分からなければ空） */
    private class Bound(val resolution: String, val tuning: Tuning)

    /** G2 録画が準備を頼んだときの組み方 */
    private class ArmedConfig(
        val quality: String,
        val audio: Boolean,
        val still: Boolean,
        val facing: String,
        val stopTempC: Double,
        val resolution: String,
        val tuning: Tuning = Tuning.DEFAULT,
    ) {
        /** 頼まれた組み方（画質・静止画の有無・fps・細かさ・向き）が、この準備と同じか */
        fun sameAs(quality: String, still: Boolean, fps: Int, bitrate: String, facing: String): Boolean =
            this.quality == quality && this.still == still && tuning.fpsWanted == fps && tuning.bitrateLevel == bitrate && this.facing == facing
    }

    private class ArmRequest(
        val quality: String,
        val audio: Boolean,
        val still: Boolean,
        val stopTempC: Double,
        val fps: Int = 30,
        val bitrate: String = VideoTuning.STD,
    )

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
        /** fps・細かさ（0.1.20） */
        val tuning: Tuning = Tuning.DEFAULT,
    ) {
        @Volatile var current: Rec = current
        @Volatile var segment = 1
        @Volatile var thumb: ByteArray? = null
        @Volatile var finishedBytes = 0L
        var timer: Runnable? = null
        /** 録画中に G2 アプリから要求が届いたか（単体で始めた録画を G2 アプリが引き継いだか。0.1.21） */
        @Volatile var clientSeen = false
        /** いまの 1 本を録り始めたときの、カメラから届いた枚数と時刻（実測の fps を出すため） */
        @Volatile var segFrames0 = 0L
        @Volatile var segStartAt = 0L
        /** 止める直前に出した、いまの 1 本の実測の fps（まだなら 0） */
        @Volatile var segFps = 0.0
    }

    /** 束ね方：recorder があれば録画つき（静止画も束ねるか・電子式手ブレ補正のモード 0=なし・fps。60 のときだけ目標の fps を付ける） */
    private class BindSpec(val recorder: Recorder, val still: Boolean, val eis: Int, val fps: Int = 30)

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
    override val armedFps: Int get() = armed?.tuning?.fps ?: 0
    override val armedFpsWanted: Int get() = armed?.tuning?.fpsWanted ?: 0
    override val armedFpsNote: String get() = armed?.tuning?.fpsNote ?: ""
    override val armedBitrate: String get() = armed?.tuning?.bitrateLevel ?: ""
    override val sensorFps: Double
        get() = if (camera == null || cameraIdle || SystemClock.elapsedRealtime() - lastSensorFrameAt > 1500L) 0.0 else sensorFpsNow
    override val lensStatus: LensStatus
        get() = LensStatus(lensesNow(), zoomStateNow(), lensNote)
    override val armPausedReason: String? get() = armPaused
    override val cameraOpen: Boolean get() = camera != null && !cameraIdle
    override val heatStopTemp: Int get() = Prefs.heatStopTemp(this)
    override val heatStopThermal: Int get() = Prefs.heatStopThermal(this)
    override val heatHotThermal: Int get() = Prefs.heatHotThermal(this)
    override val loadInfo: LoadInfo
        get() = LoadInfo(
            cpuPct = LoadMonitor.latest?.cpuPct ?: -1,
            cpuAvgPct = LoadMonitor.avgPct,
            cameraFps = measuredCameraFps,
            producedFps = measuredProducedFps,
            servedFps = measuredServedFps,
            streamFpsCap = (1000 / streamIntervalMs).toInt(),
            extension = if (extensionBound) extensionLabel else "なし",
        )

    override val lowLight: LowLightInfo
        get() = LowLightInfo(
            enabled = Prefs.lowLightAuto(this),
            active = lowLightActive && camera != null && !cameraIdle,
            ev = lowLightEv,
            night = nightState(),
            nightOn = Prefs.lowLightNight(this),
            video = Prefs.lowLightVideo(this),
            range = appliedLowLight?.let { "${it.lower}-${it.upper}" } ?: "",
            lastCaptureMode = lastCaptureMode,
            level = Prefs.lowLightLevel(this),
            videoMin = Prefs.lowLightVideoMin(this),
        )

    private val lowLightActive: Boolean get() = synchronized(lowLightLock) { lowLightDetector.active }

    /** 夜景の拡張（いまの向き）：available / unavailable / failed / unknown */
    private fun nightState(): String = when {
        nightBindFailed.containsKey(facingValue) -> "failed"
        nightAvailable[facingValue] == true -> "available"
        nightAvailable[facingValue] == false -> "unavailable"
        else -> "unknown"
    }

    private fun fmtLux(ev: Double): String {
        if (ev.isNaN()) return "?"
        val lux = LowLight.luxOf(ev)
        return if (lux < 10) String.format(Locale.US, "%.1f", lux) else lux.roundToInt().toString()
    }

    /**
     * 画面・診断用の低照度の説明。live = false なら、いまの明るさなど刻々と変わる値を入れない
     * （診断の文は G2 アプリが変わるたびにログへ書くので、揺れる値を入れると溢れる）
     */
    fun describeLowLight(live: Boolean = true): String {
        val on = Prefs.lowLightAuto(this)
        val lv = LowLight.level(Prefs.lowLightLevel(this))
        val open = camera != null && !cameraIdle
        val now = when {
            !open -> "カメラを閉じている"
            lowLightEv.isNaN() -> "明るさを計測中"
            else -> "${if (lowLightActive) "暗い" else "明るい"}（約 ${fmtLux(lowLightEv)} lux・EV ${String.format(Locale.US, "%.1f", lowLightEv)}${if (exposureLabel.isNotEmpty()) "・$exposureLabel" else ""}）"
        }
        val night = when (nightState()) {
            "available" -> "使える"
            "unavailable" -> "この端末には無い"
            "failed" -> "束ねられない（${nightBindFailed[facingValue]}）"
            else -> "未確認"
        }
        val applied = appliedLowLight?.let { "・露光を長めに（fps $it）" } ?: ""
        return (if (on) "オン（判定 ${lv.label}：約 ${lv.enterLux.roundToInt()} lux 以下で暗い）" else "オフ") +
            (if (live) "・いま $now$applied" else "") +
            "・夜景の拡張 $night${if (!Prefs.lowLightNight(this)) "（使わない設定）" else ""}" +
            "・録画は${LowLight.videoMinLabel(Prefs.lowLightVideoMin(this))}" +
            (if (live && lastCaptureMode.isNotEmpty()) "・直近の撮影=$lastCaptureMode" else "")
    }

    /** このサービスで録画したファイル（名前 → URI）。/file で返すときに使う */
    private val recentFiles = ConcurrentHashMap<String, Uri>()
    /** 書き出し中（つなぎ合わせ中）の名前 → 済んだら開く門 */
    private val merging = ConcurrentHashMap<String, CountDownLatch>()
    override val mergingCount: Int get() = merging.size
    @Volatile private var mergeNote: String? = null
    override val lastMergeNote: String? get() = mergeNote
    /** G2 カメラ側がまだ受け取っていない録画結果（最長時間・通知・高温などで止めたとき）。取り出しは一度だけ */
    private val pendingResult = java.util.concurrent.atomic.AtomicReference<RecordResult?>(null)
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
        val ready = if (a != null) {
            "準備済み（${a.quality} ${a.resolution.ifEmpty { "解像度不明" }}・${a.tuning.fps}fps・細かさ ${a.tuning.bitrateLevel}${if (a.still) "・撮影あり" else ""}）"
        } else if (armPaused != null) "休止中（高温）" else "なし"
        return "構成=${if (cameraIdle) "カメラを閉じている（待機）" else boundLabel}・静止画の拡張=$extensionLabel・手ブレ補正=$stabLabel・録画の解像度=$recordResLabel・録画の準備=$ready・保存先=${Storage.describe(this)}" +
            "・低照度=${describeLowLight(live = false)}" +
            "・レンズ=$lensNote" +
            (if (bindNotes.isNotEmpty()) "・束ね直しの失敗=$bindNotes" else "")
    }

    private fun buildStreamLabel(): String {
        val cap = (1000 / streamIntervalMs).toInt()
        val cam = if (cameraIdle) "カメラ: 閉じている（要求が来たら開く）" else "カメラ: 開いている（実測 ${measuredCameraFps}fps）"
        val demand = if (SystemClock.elapsedRealtime() - lastFrameRequestAt < FRAME_DEMAND_MS) "配信中 ${measuredProducedFps}fps（上限 $cap・渡した ${measuredServedFps}fps）" else "配信なし（取りに来ていないので変換していない）"
        return "$cam　$demand"
    }

    // G2 カメラ側とのつながり（終了に合わせた自動停止）
    /** 止めようとしている（遡り録画の書き出しが済むのを待っている間も） */
    @Volatile private var stopping = false
    /** G2 アプリの終了・応答の途絶えによる停止（止まるまでに G2 アプリが戻ってきたら取りやめる）。通知の「停止」は取りやめない */
    @Volatile private var stopCancellable = false
    /** 録画中に G2 カメラが終了した。録画が終わったらサービスを止める */
    @Volatile private var stopWhenRecordingEnds = false
    @Volatile private var lastClientAt = 0L
    @Volatile private var clientSeen = false
    private val idleWatchdog = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastClientAt
            // 録画中・書き出し中は、メガネ・G2 カメラとの接続が切れても止めない（済んでから判断する）
            if (session == null && merging.isEmpty() && !stopping && clientSeen && idle > IDLE_STOP_MS && Prefs.autoStop(this@CameraService)) {
                Log.i(TAG, "G2 カメラからの応答が ${idle / 1000} 秒途絶えたので停止します")
                shutdownGracefully(cancellable = true)
                return
            }
            // 誰も使っていない：カメラを閉じる（サービスは残す。次の要求で開き直す）
            if (!cameraIdle && !reopening && camera != null && session == null && armed == null && bufCur == null && !stopping &&
                clientSeen && idle > IDLE_CAMERA_MS && Prefs.idleCameraOff(this@CameraService)
            ) {
                closeCameraForIdle()
            }
            // 撮影してしばらく経った：静止画の拡張つき（夜景も）の組み方から通常に戻す（拡張は待機中も重い）
            if (extensionBound && ((extensionWanted && Prefs.extensionOnDemand(this@CameraService)) || nightWanted) &&
                session == null && armed == null && !cameraIdle && !stopping &&
                SystemClock.elapsedRealtime() - lastCaptureAt > EXTENSION_KEEP_MS
            ) {
                extensionWanted = false
                nightWanted = false
                postWork {
                    synchronized(recordLock) {
                        if (session == null && armed == null && extensionBound && !cameraIdle) {
                            AppLog.i("撮影から ${EXTENSION_KEEP_MS / 1000} 秒経ったので、静止画の拡張を外して通常の組み方に戻します")
                            try {
                                rebindAndWait(null)
                            } catch (e: Exception) {
                                Log.w(TAG, "rebind (extension off) failed", e)
                            }
                        }
                    }
                }
            }
            // G2 録画が閉じられたまま：準備（遡り録画の録りため）を解いて電池を守る
            if (armed != null && session == null && clientSeen && idle > DISARM_IDLE_MS && !disarmPosted) {
                disarmPosted = true
                postWork {
                    try {
                        synchronized(recordLock) {
                            if (session == null && SystemClock.elapsedRealtime() - lastClientAt > DISARM_IDLE_MS) {
                                AppLog.i("G2 録画からの応答が途絶えたので、録画の準備を解きます")
                                lastArm = null
                                armPaused = null
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
                shutdownGracefully(cancellable = false)
                return START_NOT_STICKY
            }
            ACTION_STOP_RECORDING -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                postWork { stopRecordingInternally(intent.getStringExtra(EXTRA_TRIGGER) ?: "通知") }
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORDING -> {
                if (running && session != null) {
                    postWork { stopRecordingInternally(intent.getStringExtra(EXTRA_TRIGGER) ?: "タイル") }
                    return START_NOT_STICKY
                }
                standaloneTrigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "タイル"
            }
            ACTION_START_RECORDING -> standaloneTrigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "通知"
            ACTION_ZOOM_STEP -> {
                if (running) {
                    val dir = intent.getIntExtra(EXTRA_DIR, 0)
                    val from = intent.getStringExtra(EXTRA_TRIGGER) ?: "通知"
                    postWork { zoomStep(dir, from) }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_PREFS_CHANGED -> {
                if (!running) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                refreshStreamInterval()
                AppLog.i("設定を反映しました（配信の上限 ${Prefs.streamFps(this)}fps・低照度 ${if (Prefs.lowLightAuto(this)) "オン" else "オフ"}）")
                // 低照度の設定（オン/オフ・録画・判定の段階）もここで反映する
                postWork { reconcileLowLight("設定を変えた") }
                diagText = buildDiag()
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
        if (running && stopping) {
            // 止めている途中（書き出し待ち）に本体の画面から開始された：停止を取りやめてカメラを戻す
            stopping = false
            Log.i(TAG, "開始を頼まれたので停止を取りやめます")
            if (camera == null) bindCamera(null, null)
            refreshNotification()
        }
        if (!running) {
            AppLog.init(this)
            if (!startInForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            stopping = false
            instance = this
            refreshStreamInterval()
            LoadMonitor.tick()
            AppLog.i("カメラのサービスを開始")
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
        // コンパニオン単体での録画開始（サービスを始めたところなら、カメラが開くのを待ってから）
        standaloneTrigger?.let { t ->
            standaloneTrigger = null
            if (running) postWork { startStandalone(t) }
        }
        // システムが勝手に再起動すると背景からのカメラ開始になり失敗するので、再起動はしない
        return START_NOT_STICKY
    }

    /** onStartCommand の中だけで使う：単体で録画を始める場所（始めないなら null） */
    private var standaloneTrigger: String? = null

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        recordingLabel = null
        diagText = null
        monitorLabel = null
        streamLabel = null
        if (recordingNow) {
            recordingNow = false
            recordingOrigin = null
        }
        RecordTileService.refresh(this)
        AppLog.i("カメラのサービスを終了")
        mainHandler.removeCallbacks(idleWatchdog)
        mainHandler.removeCallbacks(healthTick)
        bufTimer?.let { mainHandler.removeCallbacks(it) }
        session?.timer?.let { mainHandler.removeCallbacks(it) }
        orientationListener?.disable()
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy() // カメラの unbind を先に済ませてから実行系を止める
        dummyThread?.quitSafely()
        analysisExecutor.execute { frameEncoder.release() }
        analysisExecutor.shutdown()
        ioExecutor.shutdownNow()
        recorderExecutor.shutdownNow()
        workExecutor.shutdownNow()
        // 書き出しは途中で切らない（プロセスが残っている間は続ける。消えたら次回の開始時にやり直す）
        mergeExecutor.shutdown()
    }

    /**
     * 止める。録画中なら保存してから、遡り録画の書き出しが残っていれば済んでから止まる（書き出しを待つ間も、ほかの仕事は止めない）。
     * cancellable：G2 アプリの終了・応答の途絶えによる停止。止まるまでに G2 アプリが戻ってきたら（映像・準備・録画の要求）取りやめる。
     * 通知の「停止」は取りやめず、書き出しを待つ間はカメラを手放す
     */
    private fun shutdownGracefully(cancellable: Boolean) {
        if (stopping && !stopCancellable) return // 確定した停止の途中
        stopping = true
        stopCancellable = cancellable
        if (session == null && bufCur == null && merging.isEmpty()) {
            stopSelf()
            return
        }
        postWork {
            synchronized(recordLock) {
                if (!stopping) return@postWork // 取りやめになった
                stopWhenRecordingEnds = false
                armed = null
                pendingArm = null
                try {
                    stopKeepingLocked(null, rebind = false)
                } catch (e: Exception) {
                    Log.w(TAG, "stop recording (shutdown) failed", e)
                }
                stopBufferLocked()
            }
            if (merging.isEmpty()) {
                mainHandler.post { finishStop() }
                return@postWork
            }
            Log.i(TAG, "書き出しが済むのを待ってから止めます（${merging.size} 本）")
            if (!cancellable) releaseCamera()
            refreshNotification()
            // 書き出しの順番待ちの最後に置く（それより前に頼んだ書き出しが全部済んでから止まる）
            try {
                mergeExecutor.execute { mainHandler.post { finishStop() } }
            } catch (e: RejectedExecutionException) {
                mainHandler.post { finishStop() }
            }
        }
    }

    private fun finishStop() {
        if (!stopping) {
            Log.i(TAG, "停止を取りやめました")
            return
        }
        if (session != null) return // 念のため：録画が始まっていたら止めない
        stopSelf()
    }

    /** G2 アプリが戻ってきた：自動の停止なら取りやめて true。通知の「停止」の途中なら false */
    private fun cancelStop(why: String): Boolean {
        if (!stopping) return true
        if (!stopCancellable) return false
        stopping = false
        Log.i(TAG, "G2 アプリが戻ってきたので停止を取りやめます（$why）")
        if (camera == null) mainHandler.post { if (camera == null && running) bindCamera(null, null) }
        refreshNotification()
        return true
    }

    /** カメラを手放す（通知の「停止」のあと、書き出しが済むのを待つ間） */
    private fun releaseCamera() {
        mainHandler.post {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
            camera = null
            imageAnalysis = null
            imageCapture = null
            videoCapture = null
            frameLock.withLock { latestFrame = null }
            boundLabel = "停止中（書き出し待ち）"
            forgetLowLightCamera()
        }
    }

    // ── フォアグラウンド化 ──
    private fun buildNotification(text: String?, recordingNow: Boolean): Notification {
        val startRec = PendingIntent.getService(
            this, 3, Intent(this, CameraService::class.java).setAction(ACTION_START_RECORDING).putExtra(EXTRA_TRIGGER, "通知"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 0.1.22：通知のボタンは「ズーム－・ズーム＋・録画開始/停止」（通知のボタンは 3 つまで。コンパニオンを止めるのは画面から）
        fun zoomIntent(code: Int, dir: Int) = PendingIntent.getService(
            this, code,
            Intent(this, CameraService::class.java).setAction(ACTION_ZOOM_STEP).putExtra(EXTRA_DIR, dir).putExtra(EXTRA_TRIGGER, "通知"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("G2 カメラ コンパニオン")
            .setContentText(text ?: "カメラを保持しています（127.0.0.1:$PORT）")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (camera != null && !cameraIdle) {
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_zoom, "ズーム－", zoomIntent(4, -1)).build())
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_zoom, "ズーム＋", zoomIntent(5, 1)).build())
        }
        if (recordingNow) {
            val stopRec = PendingIntent.getService(
                this, 2, Intent(this, CameraService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "録画停止", stopRec).build())
        } else if (!stopping) {
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "録画開始", startRec).build())
        }
        return builder.build()
    }

    @Volatile private var lastNotificationText: String? = "\u0000"

    /** 通知と本体画面の表示を、いまの状態に合わせる */
    private fun refreshNotification() {
        if (!running) return
        val s = session
        val main = when {
            s != null -> "録画中（${if (s.opts.origin == ORIGIN_COMPANION) "コンパニオンから・" else ""}${if (s.audio) "音声あり" else "無音"}${if (s.segment > 1) "・${s.segment} 本目" else ""}）"
            bufCur != null -> "遡り録画の録りため中（直前 ${Prefs.preRollSeconds(this)} 秒）"
            armed != null -> "録画の準備ができています"
            cameraIdle -> "待機中（カメラを閉じています。G2 アプリが映像を取りに来たら開きます）"
            else -> null
        }
        val merge = if (merging.isNotEmpty()) "動画を書き出し中（${merging.size} 本${if (stopping) "。済んだら止まります" else ""}）" else null
        // 0.1.22：いまの倍率（通知のズームのボタンの手応え）
        val zoomPart = if (camera != null && !cameraIdle) zoomLabel()?.let { "ズーム $it" } else null
        val text = listOfNotNull(main, merge, zoomPart).joinToString("・").ifEmpty { null }
        // タイルと画面のボタン用（0.1.21）
        val wasRecording = recordingNow
        recordingNow = s != null
        recordingOrigin = s?.opts?.origin
        if (wasRecording != recordingNow) RecordTileService.refresh(this)
        val key = "$text|${s != null}|$cameraIdle|$stopping"
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

    /** カメラを開いている間だけ CPU を眠らせない（閉じている間は手放す） */
    private fun acquireWakeLock() {
        val w = wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "g2cam:service").apply {
            setReferenceCounted(false)
        }.also { wakeLock = it }
        if (!w.isHeld) w.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /** 設定の fps → 配信の間隔。熱いときは倍にする */
    private fun refreshStreamInterval() {
        val fps = Prefs.streamFps(this)
        val base = (1000 / fps).toLong().coerceAtLeast(33L)
        streamIntervalMs = if (hotNow) base * 2 else base
    }

    /** 誰も使っていないのでカメラを閉じる（サービス・HTTP は残す）。次の要求で wakeCamera が開き直す */
    private fun closeCameraForIdle() {
        cameraIdle = true
        AppLog.i("${IDLE_CAMERA_MS / 1000} 秒要求が無いのでカメラを閉じます（サービスは残す）")
        mainHandler.post {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (e: Exception) {
                Log.w(TAG, "unbind failed", e)
            }
            camera = null
            imageAnalysis = null
            imageCapture = null
            videoCapture = null
            extensionBound = false
            forgetLowLightCamera()
            frameLock.withLock { latestFrame = null }
            orientationListener?.disable()
            releaseWakeLock()
            refreshNotification()
            diagText = buildDiag()
            streamLabel = buildStreamLabel()
        }
    }

    /** 閉じていたカメラを開き直す（要求が来たとき。メイン以外からも呼べる） */
    private fun wakeCamera(why: String) {
        if (!cameraIdle || reopening || !running || stopping) return
        reopening = true
        AppLog.i("要求が来たのでカメラを開きます（$why）")
        mainHandler.post {
            acquireWakeLock()
            orientationListener?.enable()
            bindCamera(null, Runnable {
                cameraIdle = false
                reopening = false
                refreshNotification()
            })
        }
    }

    /** recordLock の中で：カメラが閉じていれば開き直して待つ */
    private fun ensureCameraLocked() {
        if (!cameraIdle) return
        AppLog.i("要求が来たのでカメラを開きます（同期）")
        onMainAndWait {
            acquireWakeLock()
            orientationListener?.enable()
        }
        try {
            rebindAndWait(null)
        } finally {
            cameraIdle = false
            reopening = false
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
    /** 待機中のセンサー fps。端末が持つ範囲のうち、上限 15 以下で下限が一番高いもの（[15,15] が理想）。無ければ null */
    private fun idleFpsRange(): android.util.Range<Int>? = try {
        val cm = getSystemService(CameraManager::class.java)
        val want = if (facingValue == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val ranges = cm.cameraIdList
            .map { cm.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == want }
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        ranges.filter { it.upper in 10..15 && it.lower >= 7 }.maxByOrNull { it.lower * 100 + it.upper }
    } catch (e: Exception) {
        null
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun buildCapture(recording: Boolean, withInterop: Boolean, fpsRange: android.util.Range<Int>? = null): ImageCapture {
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
        if (withInterop && fpsRange != null) {
            try {
                Camera2Interop.Extender(b).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            } catch (e: Exception) {
                Log.w(TAG, "fps range (capture) failed", e)
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


    // ── 低照度（0.1.19） ──

    // ── fps の実測とレンズ（0.1.20） ──

    /** カメラから 1 枚届いた（カメラのスレッド）。同じ結果は 1 回だけ数え、1 秒ごとに fps を締める */
    private fun countSensorFrame(r: TotalCaptureResult) {
        val n = r.frameNumber
        if (n == lastFrameNumber) return
        lastFrameNumber = n
        val total = sensorFrames.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        lastSensorFrameAt = now
        if (sensorWinStart == 0L || now - sensorWinStart > 3000L) {
            sensorWinStart = now
            sensorWinFrames = total
        } else if (now - sensorWinStart >= 1000L) {
            sensorFpsNow = (total - sensorWinFrames) * 1000.0 / (now - sensorWinStart)
            sensorWinStart = now
            sensorWinFrames = total
        }
    }

    /**
     * 束ね直した直後に、カメラから want fps が届くかを確かめる（メイン以外から）。（届いたか, 見た fps）を返す。
     * 束ね直した直後は、前の組み方の残りが届いたり、新しい組み方が流れ始めるまで間が空いたりするので、
     * 0.4 秒ずつ区切って数え、2 区切り続けて届いたら「届いた」、流れ始めてから 2 秒たっても届かなければ「届かない」とする
     */
    private fun measureSensorFps(want: Int): Pair<Boolean, Double> {
        val deadline = SystemClock.elapsedRealtime() + 5000L
        var best = 0.0
        var okInARow = 0
        var streamingSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val a = sensorFrames.get()
            val ta = SystemClock.elapsedRealtimeNanos()
            Thread.sleep(400)
            val b = sensorFrames.get()
            val tb = SystemClock.elapsedRealtimeNanos()
            val fps = if (tb > ta) (b - a) * 1e9 / (tb - ta) else 0.0
            val now = SystemClock.elapsedRealtime()
            if (VideoTuning.fpsOk(fps, want)) {
                okInARow += 1
                if (fps > best) best = fps
                if (okInARow >= 2) return Pair(true, fps)
                continue
            }
            okInARow = 0
            if (fps >= 5.0) {
                if (fps > best) best = fps
                if (streamingSince == 0L) streamingSince = now
                if (now - streamingSince >= 2000L) break
            } else {
                streamingSince = 0L
            }
        }
        // 届いたのが 1 区切りだけ（続かなかった・時間切れ）のときは、届いていないとみなす
        return Pair(false, best)
    }

    /** 束ねたカメラのレンズ（物理カメラ）を読む。同じカメラなら読み直さない（束ねた直後に呼ぶ） */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun readLenses(cam: Camera) {
        try {
            val id = Camera2CameraInfo.from(cam.cameraInfo).cameraId
            if (id == lensCameraId) return
            val cm = getSystemService(CameraManager::class.java)
            val chars = cm.getCameraCharacteristics(id)
            val ids = chars.physicalCameraIds
            val list = ArrayList<LensInfo.Physical>()
            for (pid in ids) {
                try {
                    val c = cm.getCameraCharacteristics(pid)
                    val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (f != null && s != null) list.add(LensInfo.Physical(pid, f, s.width, s.height))
                } catch (e: Exception) {
                    Log.w(TAG, "physical camera $pid unreadable", e)
                }
            }
            // ズームの範囲は、カメラの特性から読む（束ねた直後は CameraX のズームの状態がまだ入っていないことがある）
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val z = cam.cameraInfo.zoomState.value
            val zoomLo = (range?.lower ?: z?.minZoomRatio ?: 1f).toDouble()
            val zoomHi = (range?.upper ?: z?.maxZoomRatio ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).toDouble()
            val lenses = LensInfo.lenses(list, zoomLo, zoomHi)
            lensList = lenses
            lensCameraId = id
            lensNote = when {
                ids.size < 2 -> "この端末はレンズごとの情報を出していません（光学かデジタルかは分かりません）"
                lenses.isEmpty() -> "レンズの情報から倍率を出せませんでした"
                else -> LensInfo.describe(lenses)
            }
            val raw = list.joinToString(" / ") {
                "id=${it.id} 焦点 ${it.focalMm}mm・センサー ${fmt1(it.sensorW.toDouble())}x${fmt1(it.sensorH.toDouble())}mm（35mm 判換算 ${it.eq35.roundToInt()}mm）"
            }
            AppLog.i("レンズ（カメラ $id・ズーム x${LensInfo.fmt(zoomLo)}〜x${LensInfo.fmt(zoomHi)}）：$lensNote${if (raw.isNotEmpty()) "　［$raw］" else ""}")
        } catch (e: Exception) {
            Log.w(TAG, "lens read failed", e)
            lensNote = "レンズの情報を読めませんでした"
        }
    }

    /** 端末のレンズ（倍率の小さい順。分からなければ空） */
    private fun lensesNow(): List<LensInfo.Lens> = lensList

    /** いまのズームが光学かデジタルか（カメラを閉じていれば「分からない」） */
    private fun zoomStateNow(): LensInfo.ZoomState {
        if (camera == null || cameraIdle) return LensInfo.ZoomState(null, null, null)
        val now = SystemClock.elapsedRealtime()
        val z = zoomNow
        // いま使っているレンズ（撮影結果から）は、直近の結果で、ズームが落ち着いたあとのものだけ信じる
        val reported = activeLensZoom
        val settled = if (reported > 0f) abs(reported - z) <= z * 0.03f else now - zoomChangedAt > 800L
        val fresh = now - activeLensAt < 2000L && settled
        return LensInfo.zoomState(lensesNow(), z.toDouble(), if (fresh) activeLensId else null)
    }

    /** 画面・診断用：レンズと、いまのズームの説明 */
    fun describeLens(): String {
        val open = camera != null && !cameraIdle
        if (!open) return lensNote
        val st = zoomStateNow()
        val now = when {
            st.optical == null -> "いま x${LensInfo.fmt(zoomNow.toDouble())}"
            else -> "いま x${LensInfo.fmt(zoomNow.toDouble())}＝${LensInfo.describe(st)}"
        }
        return "$lensNote（$now）"
    }

    /** 露出の値が届いた（カメラのスレッド）。間引いて暗さを判定し、変わったら露光の決め方を合わせる */
    private fun onExposure(r: TotalCaptureResult) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExposureAt < EXPOSURE_SAMPLE_MS) return
        lastExposureAt = now
        try {
            // いま使っているレンズ（複数レンズを 1 つのカメラとして見せる端末が教えてくれる）と、そのときのズーム
            activeLensId = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            activeLensZoom = r.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 0f
            activeLensAt = now
        } catch (e: Exception) {
            Log.w(TAG, "active lens read failed", e)
        }
        try {
            val t = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val boost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)
            val ap = r.get(CaptureResult.LENS_APERTURE)?.takeIf { it > 0f } ?: defaultAperture
            val ev = LowLight.ev100(t, iso, boost, ap) ?: return
            lowLightEv = ev
            exposureLabel = "1/${max(1, (1e9 / t).roundToInt())} 秒・ISO ${iso * (boost ?: 100) / 100}"
            val changed = synchronized(lowLightLock) { lowLightDetector.update(ev, now) }
            if (changed != null) postWork { onLowLightChanged(changed) }
        } catch (e: Exception) {
            Log.w(TAG, "exposure read failed", e)
        }
    }

    private fun onLowLightChanged(active: Boolean) {
        val on = Prefs.lowLightAuto(this)
        val lux = fmtLux(lowLightEv)
        AppLog.i(
            if (active) "暗くなりました（約 $lux lux）。${if (on) "低照度撮影にします" else "低照度の自動切り替えはオフです"}"
            else "明るくなりました（約 $lux lux）。${if (on) "通常の撮影に戻します" else ""}",
        )
        reconcileLowLight(if (active) "暗くなった" else "明るくなった")
        diagText = buildDiag()
    }

    /**
     * 低照度の露光（AE の fps 範囲の下限を下げて、1 コマの露光を長くする）を、いまの状態に合わせる。
     * 束ね直しのあと・暗さが変わったとき・設定を変えたときに呼ぶ。
     * Camera2CameraControl の値はカメラ（向き）ごとに束ね直しても残るので、束ね直しのあとは force で必ず掛け直す
     * （録画の組み方に、写真用の範囲が残らないように）
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun reconcileLowLight(why: String, force: Boolean = false) {
        synchronized(lowLightLock) {
            val cam = camera ?: return
            val recCtx = videoCapture != null || session != null || armed != null || bufCur != null
            val want = desiredLowLightRange(cam, recCtx)
            if (!force && want == appliedLowLight) return
            try {
                val c2 = Camera2CameraControl.from(cam.cameraControl)
                if (want == null) {
                    c2.clearCaptureRequestOptions()
                } else {
                    c2.setCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(want.lower, want.upper))
                            .build(),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "low light control failed", e)
                return
            }
            val prev = appliedLowLight
            appliedLowLight = want
            if (prev != want) {
                AppLog.i(
                    if (want != null) "低照度：露光を長めにしました（fps $want${if (recCtx) "・録画は${LowLight.videoMinLabel(Prefs.lowLightVideoMin(this))}" else ""}。$why）"
                    else "低照度：露光を元に戻しました（$why）",
                )
            }
        }
    }

    /** いま掛けたい AE の fps 範囲（lowLightLock の中で）。掛けないなら null */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun desiredLowLightRange(cam: Camera, recCtx: Boolean): LowLight.FpsRange? {
        if (!Prefs.lowLightAuto(this) || !lowLightDetector.active) return null
        // 60fps が出るかを確かめている間は掛けない（暗さで fps が下がると、確かめられない）
        if (fpsProbing) return null
        // 端末の拡張（夜景・HDR など）が露出を受け持っている間は掛けない
        if (extensionBound) return null
        // 録画中・録画の準備中は、設定「暗いときの録画」が「fps を下げない」以外のときだけ（下限は設定に従う）
        val videoMin = Prefs.lowLightVideoMin(this)
        if (recCtx && videoMin == 0) return null
        val ranges = try {
            Camera2CameraInfo.from(cam.cameraInfo).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        } catch (e: Exception) {
            null
        } ?: return null
        return LowLight.pickRange(ranges.map { LowLight.FpsRange(it.lower, it.upper) }, recCtx, if (videoMin == 0) LowLight.RECORDING_MIN_FPS else videoMin)
    }

    /** カメラを閉じた・手放した：表示用の値を消し、ならしをやり直す（暗いかどうかの判定は残す） */
    private fun forgetLowLightCamera() {
        synchronized(lowLightLock) {
            lowLightDetector.reset()
            appliedLowLight = null
        }
        lowLightEv = Double.NaN
        exposureLabel = ""
        nightBound = false
        nightWanted = false
    }

    /** 撮影の前に夜景の拡張を使うか：暗い・設定オン・この向きで使える（または未確認）・束ねられなかったことがない */
    private fun wantNightCapture(): Boolean =
        Prefs.lowLightAuto(this) && Prefs.lowLightNight(this) && lowLightActive &&
            nightAvailable[facingValue] != false && !nightBindFailed.containsKey(facingValue)

    /** 夜景の拡張つきで束ねられる候補（使えなければ null） */
    private fun nightSelector(ext: ExtensionsManager?, base: CameraSelector): CameraSelector? {
        if (ext == null) return null
        return try {
            if (ext.isExtensionAvailable(base, ExtensionMode.NIGHT)) ext.getExtensionEnabledCameraSelector(base, ExtensionMode.NIGHT) else null
        } catch (e: Exception) {
            Log.w(TAG, "night extension check failed", e)
            null
        }
    }

    /** 夜景の拡張でメガネへの配信（ImageAnalysis）を一緒に束ねられない端末のための、捨てるだけのプレビュー（画面には出さない） */
    private fun discardPreview(): Preview {
        val p = Preview.Builder().setTargetRotation(rotation).build()
        val executor = ContextCompat.getMainExecutor(this)
        p.setSurfaceProvider(executor) { request ->
            val size = request.resolution
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.close()
                } catch (e: Exception) {
                    // 閉じたあとに届いた
                }
            }, discardHandler())
            request.provideSurface(reader.surface, executor) { reader.close() }
        }
        return p
    }

    private fun discardHandler(): Handler {
        val t = dummyThread ?: HandlerThread("g2cam-discard").also {
            it.start()
            dummyThread = it
        }
        return Handler(t.looper)
    }

    /** 夜景の拡張が使えるか（向きごとに 1 回）を、束ねずに確かめておく（本体の画面と /status に出すため） */
    private fun checkNightAvailability(provider: ProcessCameraProvider) {
        val facing = facingValue
        if (nightAvailable.containsKey(facing) || nightChecking) return
        nightChecking = true
        val f = ExtensionsManager.getInstanceAsync(this, provider)
        f.addListener({
            try {
                val ext = f.get()
                val base = if (facing == "user") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val ok = ext.isExtensionAvailable(base, ExtensionMode.NIGHT)
                nightAvailable[facing] = ok
                AppLog.i("夜景の拡張（${if (facing == "user") "フロント" else "リア"}）：${if (ok) "使えます" else "この端末にはありません（暗いときは露光を長めにするだけ）"}")
                diagText = buildDiag()
            } catch (e: Exception) {
                Log.w(TAG, "night check failed", e)
            } finally {
                nightChecking = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 撮影の前に、静止画の拡張を整える（recordLock の中で）。
     * 暗いとき：夜景の拡張で束ね直す（使える端末なら。1 秒ほど）。束ねられなければ、露光を長めにしたまま通常で撮る。
     *   暗いときは HDR・オートは使わない（露光を長めにする方を優先）。
     * 明るいとき：高画質（オート・HDR）を使う設定なら、それで束ね直す（これまでどおり）
     */
    private fun prepareStillExtensionLocked() {
        if (wantNightCapture()) {
            if (nightBound) return
            nightWanted = true
            AppLog.i("暗いので、夜景の拡張で撮ります（数秒かかります。スマホを動かさないでください）")
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind (night) failed", e)
            }
            if (nightBound) return
            nightWanted = false
            if (nightAvailable[facingValue] == true) {
                nightBindFailed[facingValue] = bindNotes.ifEmpty { "理由不明" }.take(120)
                AppLog.i("夜景の拡張で束ねられませんでした（${nightBindFailed[facingValue]}）。露光を長めにして撮ります")
            } else {
                AppLog.i("夜景の拡張はこの端末にありません。露光を長めにして撮ります")
            }
            return
        }
        val dark = Prefs.lowLightAuto(this) && lowLightActive
        if (nightWanted || nightBound || (dark && extensionBound)) {
            // 明るくなった・設定を切った：夜景の組み方を外す。暗いのに HDR・オートが残っている：外して露光を長めにする
            nightWanted = false
            if (dark) extensionWanted = false
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind (night off) failed", e)
            }
        }
        if (dark) return
        if (Prefs.highQuality(this) && !extensionBound && extensionAvailable != false) {
            if (Prefs.extensionOnDemand(this)) extensionWanted = true
            try {
                rebindAndWait(null)
            } catch (e: Exception) {
                Log.w(TAG, "rebind (extension on) failed", e)
            }
            if (!extensionBound) AppLog.i("静止画の拡張は使えません（この端末、またはこの組み方では）。通常の撮影にします")
        }
    }

    /** 夜景の組み方でメガネへの配信を束ねられなかった（映像が止まっている）：撮ったらすぐ通常に戻す */
    private fun releaseNightSoon() {
        postWork {
            synchronized(recordLock) {
                if (session == null && armed == null && nightBound && !cameraIdle && running) {
                    nightWanted = false
                    AppLog.i("夜景で撮り終えたので、メガネへの映像が出る組み方に戻します")
                    try {
                        rebindAndWait(null)
                    } catch (e: Exception) {
                        Log.w(TAG, "rebind (night release) failed", e)
                    }
                }
            }
        }
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
                // 静止画の拡張（HDR・オート）は、設定が「撮影の前後だけ」なら撮影の直前に求められたときだけ束ねる（待機中は通常の組み方）
                // 暗いときの撮影では夜景の拡張（nightWanted）。暗いときは HDR・オートは束ねない（露光を長めにする方を優先）
                val dark = Prefs.lowLightAuto(this) && lowLightActive
                val wantExt = spec == null && (nightWanted || (!dark && Prefs.highQuality(this) && (!Prefs.extensionOnDemand(this) || extensionWanted)))
                if (wantExt) {
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
                    // 夜景の拡張が使えるかを先に確かめておく（本体の画面と /status に出す）
                    if (spec == null && Prefs.lowLightNight(this)) checkNightAvailability(provider)
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
            // 露出の値（暗さの判定）を受け取る。録画の組み方では録画のユースケースにも付ける（配信を束ねられない端末向け）
            try {
                Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(exposureCallback)
            } catch (e: Exception) {
                Log.w(TAG, "exposure callback (analysis) failed", e)
            }
            if (ois) {
                try {
                    Camera2Interop.Extender(analysisBuilder)
                        .setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                } catch (e: Exception) {
                    Log.w(TAG, "ois (analysis) failed", e)
                }
            }
            // 配信＋撮影だけ（録画の準備でも録画中でもない）なら、センサーを 15fps 前後に落として ISP の仕事を減らす。
            // 録画の組み方（spec あり）には付けないので、録画の画質・fps は変わらない
            val idleRange = if (spec == null && ext == null && Prefs.idleSensorFps(this)) idleFpsRange() else null
            if (idleRange != null) {
                try {
                    Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, idleRange)
                } catch (e: Exception) {
                    Log.w(TAG, "fps range (analysis) failed", e)
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
                // 60fps（0.1.20）：目標の fps を付ける。30fps のときは付けない（これまでと同じ組み方のまま）
                if (spec.fps >= 50) vb.setTargetFrameRate(android.util.Range(spec.fps, spec.fps))
                try {
                    Camera2Interop.Extender(vb).setSessionCaptureCallback(exposureCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "exposure callback (video) failed", e)
                }
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
                val nightSel = if (nightWanted) nightSelector(ext, base) else null
                if (nightSel != null) {
                    // 暗いときの撮影：夜景の拡張。配信と一緒に束ねられない端末では、捨てるだけのプレビューと撮影だけにする（撮る間はメガネの映像が止まる）
                    attempts.add(Attempt(nightSel, listOf(analysis, buildCapture(false, withInterop = false)), "配信+撮影", NIGHT_LABEL))
                    attempts.add(Attempt(nightSel, listOf(discardPreview(), buildCapture(false, withInterop = false)), "撮影のみ（夜景の間はメガネの映像が止まる）", NIGHT_LABEL))
                } else if (!nightWanted) {
                    extensionSelector(ext, base)?.let { (sel, label) ->
                        attempts.add(Attempt(sel, listOf(analysis, buildCapture(false, withInterop = false)), "配信+撮影", label))
                    }
                }
                val cap = buildCapture(recording = false, withInterop = true, fpsRange = idleRange)
                attempts.add(Attempt(base, listOf(analysis, cap), "配信+撮影", "なし"))
                attempts.add(Attempt(base, listOf(analysis), "配信のみ", "なし"))
            }
            if (ext != null) {
                extensionAvailable = extensionSelector(ext, base) != null
                nightAvailable[facingValue] = nightSelector(ext, base) != null
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
            extensionBound = used.extension != "なし"
            nightBound = used.extension == NIGHT_LABEL
            imageAnalysis = used.cases.filterIsInstance<ImageAnalysis>().firstOrNull()
            imageCapture = used.cases.filterIsInstance<ImageCapture>().firstOrNull()
            videoCapture = if (withVideo) video else null
            if (imageAnalysis == null) frameLock.withLock { latestFrame = null }
            lastError = null
            // 露出から明るさを出すときの絞り（結果に絞りが入っていない端末向け）
            try {
                Camera2CameraInfo.from(bound.cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    ?.firstOrNull()?.let { if (it > 0f) defaultAperture = it }
            } catch (e: Exception) {
                Log.w(TAG, "aperture read failed", e)
            }
            // レンズ（超広角・標準・望遠）の倍率を読む（カメラが変わったときだけ）
            readLenses(bound)
            // 低照度の露光を、この組み方（写真か録画か・拡張の有無）に合わせて掛け直す
            reconcileLowLight("束ね直し", force = true)
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

    /**
     * 解析フレーム → 長辺 320px の JPEG にして最新として保持する。
     * 誰も取りに来ていない間（G2 アプリを閉じている・映像を止めている）は変換しない。
     * 配信の上限 fps（設定。既定 15）を超える分は捨てる。Bitmap は使い回す（FrameEncoder）
     */
    private fun handleFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            countFps(now, camera = true, produced = false)
            if (now - lastFrameRequestAt > FRAME_DEMAND_MS) return
            if (now - lastFrameAt < streamIntervalMs) return
            lastFrameAt = now

            val plane = image.planes[0]
            val bytes = frameEncoder.encode(image.width, image.height, plane.buffer, plane.rowStride, plane.pixelStride, image.imageInfo.rotationDegrees)
            frameW = frameEncoder.outWidth
            frameH = frameEncoder.outHeight
            countFps(now, camera = false, produced = true)
            frameLock.withLock {
                frameSeq += 1
                latestFrame = Frame(frameSeq, bytes)
                frameArrived.signalAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame failed", e)
        } finally {
            image.close()
        }
    }

    /** 1 秒ごとに、カメラから来た枚数・変換した枚数・渡した枚数を締める（解析スレッドで） */
    private fun countFps(now: Long, camera: Boolean, produced: Boolean) {
        if (camera) fpsCamera++
        if (produced) fpsProduced++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val span = now - fpsWindowStart
        if (span >= 1000) {
            measuredCameraFps = (fpsCamera * 1000 / span).toInt()
            measuredProducedFps = (fpsProduced * 1000 / span).toInt()
            measuredServedFps = (fpsServedCount * 1000 / span).toInt()
            fpsCamera = 0
            fpsProduced = 0
            fpsServedCount = 0
            fpsWindowStart = now
            streamLabel = buildStreamLabel()
        }
    }

    override fun nextFrame(afterSeq: Long, waitMs: Long): Frame? {
        lastFrameRequestAt = SystemClock.elapsedRealtime()
        if (cameraIdle) wakeCamera("映像")
        // 開き直し・起動の直後で 1 枚も無いときは、最初の 1 枚を長めに待つ（G2 アプリに 503 を返して取り直させない）
        val wait = if (latestFrame == null && (reopening || cameraIdle || camera != null)) maxOf(waitMs, 1500L) else waitMs
        var remainNs = TimeUnit.MILLISECONDS.toNanos(wait)
        frameLock.withLock {
            while (true) {
                val f = latestFrame
                if (f != null && f.seq > afterSeq) {
                    framesServed++
                    fpsServedCount++
                    // 映像を取りに来ている = G2 カメラがまた動いている。録画後の自動停止・書き出し待ちの停止は取り消す
                    stopWhenRecordingEnds = false
                    if (stopping) cancelStop("映像")
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
        session?.clientSeen = true
    }

    override fun requestStop(): Boolean {
        if (!Prefs.autoStop(this)) return false
        // 録画中なら、録画をそのまま続ける。録画が終わったあとに止める
        if (session != null) {
            Log.i(TAG, "G2 カメラが終了しましたが、録画中なので続けます（録画が終わったら止めます）")
            stopWhenRecordingEnds = true
            return true
        }
        // 返事を返し終えてから止める（書き出し中なら済んでから。それまでに G2 アプリが戻ってきたら取りやめ）
        mainHandler.postDelayed({ shutdownGracefully(cancellable = true) }, 300)
        return true
    }

    override fun setZoom(zoom: Double): Float {
        if (cameraIdle) wakeCamera("ズーム")
        val cam = camera ?: return zoomNow
        val state = cam.cameraInfo.zoomState.value
        val target = if (state != null) zoom.toFloat().coerceIn(state.minZoomRatio, state.maxZoomRatio) else 1f
        // 結果は待たない（ズーム中も映像は流れ続ける）。1 未満なら端末が超広角に、大きければ望遠に切り替える
        cam.cameraControl.setZoomRatio(target)
        if (target != zoomNow) zoomChangedAt = SystemClock.elapsedRealtime()
        zoomNow = target
        return target
    }

    /** いまの倍率から、その向きの次の段（G2 アプリと同じ段。端なら null。0.1.22） */
    fun nextZoomStep(dir: Int): Double? {
        val steps = LensInfo.zoomSteps(zoomMin.toDouble(), zoomMax.toDouble(), lensesNow())
        return LensInfo.stepFrom(steps, zoomNow.toDouble(), dir)
    }

    /** コンパニオンの画面・通知から倍率を 1 段変える（0.1.22）。G2 アプリは zoomSeq が増えたのを見て合わせる */
    private fun zoomStep(dir: Int, from: String) {
        if (camera == null) {
            AppLog.w("カメラが開いていないので倍率を変えられません（$from）")
            return
        }
        val next = nextZoomStep(dir)
        if (next == null) {
            AppLog.i("これ以上${if (dir > 0) "拡大" else "広角に"}できません（x${LensInfo.fmt(zoomNow.toDouble())}）")
            return
        }
        val z = setZoom(next)
        zoomSeqValue += 1
        AppLog.i("コンパニオン（$from）で倍率を x${LensInfo.fmt(z.toDouble())} にしました")
        refreshNotification()
        // レンズの切り替わりは少し遅れるので、光学／デジタルの表示を少しあとで直す
        mainHandler.postDelayed({ refreshNotification() }, 1200)
    }

    /** 「x2（デジタル 2 倍）」「x3（光学）」「x1」 */
    fun zoomLabel(): String? {
        if (camera == null) return null
        val st = zoomStateNow()
        val z = "x${LensInfo.fmt(zoomNow.toDouble())}"
        return when (st.optical) {
            true -> "$z（光学）"
            false -> "$z（デジタル ${LensInfo.fmt(st.digital ?: 1.0)} 倍）"
            null -> z
        }
    }

    override fun setFacing(facing: String) {
        val next = if (facing == "user") "user" else "environment"
        if (next == facingValue) return
        synchronized(recordLock) {
            if (session != null) throw IllegalStateException("録画中はカメラを切り替えられません")
            stopBufferLocked()
            cameraIdle = false // このあと束ね直すので、閉じていても開く
            onMainAndWait {
                acquireWakeLock()
                orientationListener?.enable()
            }
            facingValue = next
            zoomNow = 1f
            // 別のカメラになる：レンズの一覧は束ね直したときに読み直す
            lensList = emptyList()
            frameLock.withLock { latestFrame = null }
            // 別のカメラになる：夜景の組み方は外し、明るさのならしをやり直す
            nightWanted = false
            synchronized(lowLightLock) { lowLightDetector.reset() }
            lowLightEv = Double.NaN
            // 切り替えが済んでから返す（直後にズーム範囲を読むため）。録画の準備をしていたなら、新しいカメラで準備し直す
            val a = armed
            if (a != null) {
                try {
                    val b = bindForRecordingLocked(a.quality, a.still, a.tuning.fpsWanted, a.tuning.bitrateLevel)
                    armed = ArmedConfig(a.quality, a.audio, a.still, next, a.stopTempC, b.resolution, b.tuning)
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
        lastCaptureAt = SystemClock.elapsedRealtime()
        synchronized(recordLock) {
            if (session == null) {
                if (cameraIdle) ensureCameraLocked()
                if (imageCapture == null && (armed != null || videoCapture != null)) {
                    // G2 録画の準備（静止画を束ねない組み方）のまま、または束ね直しが遅れて録画の組み方のまま撮影を頼まれた：撮影できる組み方に戻す
                    if (armed != null) {
                        lastArm = null
                        armPaused = null
                        disarmLocked()
                    } else if (videoCapture != null && running) {
                        try {
                            rebindAndWait(null)
                        } catch (e: Exception) {
                            Log.w(TAG, "rebind for capture failed", e)
                        }
                    }
                }
                // 静止画の拡張を整える：暗ければ夜景（使える端末なら）、明るければ高画質（オート・HDR。撮影の前後だけ。1 秒ほど）
                if (running) prepareStillExtensionLocked()
            }
        }
        val cap = imageCapture
            ?: throw IllegalStateException(if (session != null) "この端末では録画中に撮影できません" else "カメラが起動していません")

        val mode = when {
            nightBound -> "night"
            extensionBound -> extensionLabel
            appliedLowLight != null -> "lowlight"
            else -> "normal"
        }
        val startedAt = SystemClock.elapsedRealtime()
        // 夜景の組み方でメガネへの配信を束ねられなかった（映像が止まっている）：撮り終えたら（失敗しても）すぐ通常に戻す
        val releaseNight = nightBound && imageAnalysis == null
        try {
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
            lastCaptureMode = mode
            if (mode == "night" || mode == "lowlight") {
                val sec = (SystemClock.elapsedRealtime() - startedAt) / 100 / 10.0
                AppLog.i("暗いところで撮りました（${if (mode == "night") "夜景の拡張" else "露光を長めに（fps ${appliedLowLight ?: "-"}）"}・$sec 秒）")
            }
            bytes
        } finally {
            if (releaseNight) releaseNightSoon()
        }
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

    private fun newRecorder(q: Quality, bitrate: Int? = null): Recorder {
        val b = Recorder.Builder()
            .setExecutor(recorderExecutor)
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        // 録画の細かさ（0.1.20）：標準なら指定しない（CameraX が端末の標準から決める）
        if (bitrate != null) b.setTargetVideoEncodingBitRate(bitrate)
        return b.build()
    }

    /** 短辺の画素数 → 画質（ビットレートの目安を引くため） */
    private fun qualityOfShort(short: Int): Quality = when {
        short >= 2160 -> Quality.UHD
        short >= 1080 -> Quality.FHD
        short >= 720 -> Quality.HD
        else -> Quality.SD
    }

    /** 端末の標準のビットレート（bps）と、そのときの fps（画質ごと。読めなければ目安） */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun baseBitrate(q: Quality): Pair<Int, Int> {
        try {
            val bound = camera?.let { Camera2CameraInfo.from(it.cameraInfo).cameraId.toIntOrNull() }
            val id = bound ?: if (facingValue == "user") 1 else 0
            val cq = when (q) {
                Quality.UHD -> CamcorderProfile.QUALITY_2160P
                Quality.FHD -> CamcorderProfile.QUALITY_1080P
                Quality.HD -> CamcorderProfile.QUALITY_720P
                else -> CamcorderProfile.QUALITY_480P
            }
            if (CamcorderProfile.hasProfile(id, cq)) {
                @Suppress("DEPRECATION")
                val prof: CamcorderProfile? = CamcorderProfile.get(id, cq)
                if (prof != null && prof.videoBitRate > 0) {
                    return Pair(prof.videoBitRate, if (prof.videoFrameRate > 0) prof.videoFrameRate else 30)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "camcorder profile unreadable", e)
        }
        return Pair(VideoTuning.fallbackBitrate(shortSideOf(q)), 30)
    }

    /** 目標のビットレート（bps。標準なら null = 指定しない） */
    private fun bitrateFor(q: Quality, level: String, fps: Int): Int? {
        if (level == VideoTuning.STD) return null
        val base = baseBitrate(q)
        return VideoTuning.targetBitrate(level, base.first, base.second, fps)
    }

    /** この端末のカメラ（いまの向き）が 60fps を出せるか（AE の fps 範囲に 60 があるか） */
    private fun camera60Supported(): Boolean = try {
        val cm = getSystemService(CameraManager::class.java)
        val want = if (facingValue == "user") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val ranges = cm.cameraIdList
            .map { cm.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == want }
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        VideoTuning.supports60(ranges.map { LowLight.FpsRange(it.lower, it.upper) })
    } catch (e: Exception) {
        false
    }

    /**
     * 60fps の組み方を確かめて束ねる（recordLock の中で、メイン以外から）。使えたら（手ブレ補正のモード, ""）、使えなければ（-1, 理由）。
     * 使えるとは：束ねられる・メガネへの配信（静止画つきの頼み方なら録画中の撮影も）を保てる・解像度が頼んだ画質に届く・
     * カメラから実際に 60fps が届く。60fps のために、メガネの映像や録画中の撮影を手放すことはしない。
     * 手ブレ補正がオンのときは、電子式の補正つきの組み方だけを使う（利用者の選択：手ブレ補正を優先。端末に電子式の補正が無ければ補正なしで使う）。
     * 通った組み方は覚えて、次からは測らずに 1 回で束ねる。通らなかった理由は、このサービスが動いている間だけ覚える
     */
    private fun try60Locked(quality: String, want: Quality, wantShort: Int, still: Boolean, stabOn: Boolean, level: String, tried: MutableList<String>): Pair<Int, String> {
        if (!camera60Supported()) {
            tried.add("60fps=端末のカメラが非対応")
            return Pair(-1, "この端末のカメラは 60fps に対応していません（30fps で録ります）")
        }
        val key = Prefs.plan60Key(facingValue, quality, still, stabOn)
        val failed = plan60Failed[key]
        if (failed != null) {
            tried.add("60fps=使えない（確かめ済み）")
            return Pair(-1, failed)
        }
        val cached = Prefs.plan60(this, key)
        val hasNormal = availableStabModes().contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        val plans = if (cached != null) listOf(cached) else VideoTuning.plans60(stabOn, chooseEisMode(), hasNormal)
        val bitrate = bitrateFor(want, level, 60)
        var sawLowRes = false
        var sawSlow = false
        var okWithoutStab = false
        // メガネへの配信・録画中の撮影を束ねられなかった（何を失うか）
        var lost = ""
        fpsProbing = true
        try {
            for (e in plans) {
                rebindAndWait(BindSpec(newRecorder(want, bitrate), still, e, 60))
                val v = videoCapture
                if (v == null) {
                    tried.add("60fps・補正$e=束ねられない")
                    continue
                }
                val size = videoSize(v)
                val short = size?.let { minOf(it.width, it.height) } ?: 0
                val label = size?.let { sizeLabel(it) } ?: "不明"
                val missing = if (imageAnalysis == null) "メガネの映像" else if (still && imageCapture == null) "録画中の撮影" else ""
                if (missing.isNotEmpty()) {
                    lost = missing
                    tried.add("60fps・補正$e=$label（${missing}を束ねられないので使わない）")
                    continue
                }
                if (size != null && short < wantShort) {
                    sawLowRes = true
                    tried.add("60fps・補正$e=$label（画質が下がるので使わない）")
                    continue
                }
                if (cached != null) {
                    // 覚えていた組み方は、測らずに使う（録画を始めるのを遅らせない。実際の fps は /status に出る）
                    tried.add("60fps・補正$e=$label（覚えていた組み方）")
                    return Pair(e, "")
                }
                val measured = measureSensorFps(60)
                tried.add("60fps・補正$e=$label・実測 ${fmt1(measured.second)}fps${if (measured.first) "" else "（届かない）"}")
                if (measured.first) {
                    Prefs.setPlan60(this, key, e)
                    return Pair(e, "")
                }
                sawSlow = true
            }
            // 補正つきでは 60fps が出なかった：補正なしなら出るかだけ確かめる（使わない。理由を正しく伝えるため）
            if (sawSlow && stabOn && !plans.contains(0)) {
                rebindAndWait(BindSpec(newRecorder(want, bitrate), still, 0, 60))
                val v = videoCapture
                if (v != null) {
                    val size = videoSize(v)
                    val short = size?.let { minOf(it.width, it.height) } ?: 0
                    val measured = measureSensorFps(60)
                    val full = imageAnalysis != null && (!still || imageCapture != null)
                    okWithoutStab = full && (size == null || short >= wantShort) && measured.first
                    tried.add("60fps・補正0=${size?.let { sizeLabel(it) } ?: "不明"}・実測 ${fmt1(measured.second)}fps（確かめただけ。補正を優先するので使わない）")
                }
            }
        } finally {
            fpsProbing = false
        }
        // 覚えていた組み方が通らなくなった：忘れて、次に確かめ直す
        if (cached != null) Prefs.clearPlan60(this, key)
        val why = when {
            okWithoutStab -> "手ブレ補正を使うと 60fps が出ません（手ブレ補正を優先して 30fps で録ります。コンパニオンの手ブレ補正を切ると 60fps で録れます）"
            lost.isNotEmpty() && !sawSlow && !sawLowRes -> "60fps では${lost}を保てません（${lost}を優先して 30fps で録ります）"
            sawLowRes && !sawSlow -> "この画質では 60fps が使えません（画質を保って 30fps で録ります。画質を下げると使えることがあります）"
            sawSlow -> "この画質の組み方では 60fps が出ません（30fps で録ります。" +
                (if (still) "録画中も写真を撮れる組み方のためかもしれません。G2 録画では使えることがあります" else "画質を下げると使えることがあります") + "）"
            else -> "60fps の組み方で束ねられません（30fps で録ります）"
        }
        if (cached == null) plan60Failed[key] = why
        return Pair(-1, why)
    }

    /**
     * 60fps の組み方で録画を始められなかった：この組み方では 60fps が使えないと覚え、30fps の組み方で束ね直す（recordLock の中で）。
     * 束ね直した結果を返す（頼まれた fps は 60 のまま、実際は 30、理由つき）
     */
    private fun downgrade60Locked(quality: String, still: Boolean, bitrate: String, why: String): Bound {
        val key = Prefs.plan60Key(facingValue, quality, still, Prefs.stabilization(this))
        plan60Failed[key] = "60fps では録画を始められませんでした（30fps で録ります）"
        Prefs.clearPlan60(this, key)
        AppLog.i("60fps の組み方で録画を始められないので、30fps に戻します（$why）")
        return bindForRecordingLocked(quality, still, 60, bitrate)
    }

    /**
     * 録画できる組み方に束ねる（recordLock の中で、メイン以外から呼ぶ）。実際の解像度と、fps・細かさの結果を返す。
     * 60fps を頼まれたら（0.1.20）、まず 60fps の組み方を確かめる（try60Locked）。使えなければ 30fps の組み方にして、理由を返す。
     * 30fps の組み方：前に頼んだ画質が通った組み方（向き・画質・静止画の有無ごとに覚える）があれば、それで 1 回だけ束ねる。
     * 無ければ手ブレ補正のモードを強い順に試し、頼んだ画質が通ったもの（だめなら一番高かったもの）を覚える。
     * 録画できる組み方が無ければ、配信＋撮影に戻して例外を投げる。
     */
    private fun bindForRecordingLocked(quality: String, still: Boolean, fps: Int = 30, bitrate: String = VideoTuning.STD): Bound {
        // 録画の組み方に移る：撮影のための夜景の組み方は求めない（録画のあと元に戻すときに、夜景で束ね直さない）
        nightWanted = false
        val want = qualityOf(quality)
        val wantShort = shortSideOf(want)
        val stabOn = Prefs.stabilization(this)
        val wantFps = VideoTuning.fpsOf(fps)
        val level = VideoTuning.levelOf(bitrate)
        val tried = ArrayList<String>()
        fun tag(e: Int) = "${if (still) "撮影あり" else "撮影なし"}・補正$e"
        var useFps = 30
        // 束ねた組み方の手ブレ補正のモード（束ねられていなければ -1）
        var useEis = -1
        var fpsNote = ""
        if (wantFps == 60) {
            val r = try60Locked(quality, want, wantShort, still, stabOn, level, tried)
            if (r.first >= 0) {
                useFps = 60
                useEis = r.first
                // 確かめている間は暗くても fps を下げていないので、ここで合わせ直す
                reconcileLowLight("60fps の組み方", force = true)
            } else {
                fpsNote = r.second
                AppLog.i("60fps は使いません：$fpsNote")
            }
        }
        if (useFps != 60) {
            val bitrate30 = bitrateFor(want, level, 30)
            // 手ブレ補正がオフなら補正なしの 1 通りだけ
            val cached = if (stabOn) Prefs.planEis(this, facingValue, quality, still) else 0
            var done = false
            if (cached != null) {
                rebindAndWait(BindSpec(newRecorder(want, bitrate30), still, cached))
                val v = videoCapture
                if (v != null) {
                    tried.add("${tag(cached)}=${videoSize(v)?.let { sizeLabel(it) } ?: "不明"}${if (stabOn) "（覚えていた組み方）" else ""}")
                    useEis = cached
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
                    rebindAndWait(BindSpec(newRecorder(want, bitrate30), still, e))
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
                    if (bestEis != lastBound) rebindAndWait(BindSpec(newRecorder(want, bitrate30), still, bestEis))
                    Prefs.setPlanEis(this, facingValue, quality, still, bestEis)
                    useEis = bestEis
                }
            }
        }
        Log.i(TAG, "録画の組み方（頼んだ短辺 $wantShort・fps $wantFps・細かさ $level）: ${tried.joinToString(" / ")}")
        var vc = videoCapture
        var size = vc?.let { videoSize(it) }
        // 目標のビットレートを出した画質（頼んだ画質より低い解像度で束ねられたら、その解像度に合わせ直す）
        var bitrateQuality = want
        if (vc != null && size != null && level != VideoTuning.STD && useEis >= 0) {
            val short = minOf(size.width, size.height)
            if (short < wantShort) {
                bitrateQuality = qualityOfShort(short)
                rebindAndWait(BindSpec(newRecorder(want, bitrateFor(bitrateQuality, level, useFps)), still, useEis, useFps))
                vc = videoCapture
                size = vc?.let { videoSize(it) }
                tried.add("細かさを実際の解像度（短辺 $short）に合わせて束ね直し")
            }
        }
        val resolution = size?.let { sizeLabel(it) } ?: ""
        val targetBps = if (vc != null) bitrateFor(bitrateQuality, level, useFps) ?: 0 else 0
        val tuning = Tuning(wantFps, level, useFps, targetBps, fpsNote)
        recordResLabel = if (vc != null) {
            "${resolution.ifEmpty { "不明" }}・${useFps}fps・細かさ ${VideoTuning.label(level)}${if (targetBps > 0) "（目標 ${VideoTuning.mbpsLabel(targetBps.toLong())}）" else ""}" +
                "（頼んだ画質 $quality・fps $wantFps${if (fpsNote.isNotEmpty()) "：$fpsNote" else ""}。試した組み方: ${tried.joinToString(" / ")}）"
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
        return Bound(resolution, tuning)
    }

    // ── 録画：1 本ずつの録り始め・保存 ──
    private var lastNameBase = ""
    private var lastNameCount = 0

    /** 保存名。同じ秒に 2 本目ができたら _2 を付け、書き出し中・保存済み・書き出しの段取りと重ならないようにする（recordLock の中で） */
    private fun newVideoName(): String {
        val base = "G2_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (base == lastNameBase) lastNameCount += 1 else {
            lastNameBase = base
            lastNameCount = 1
        }
        var name = if (lastNameCount == 1) "$base.mp4" else "${base}_$lastNameCount.mp4"
        while (merging.containsKey(name) || recentFiles.containsKey(name) || manifestFor(name).exists()) {
            lastNameCount += 1
            name = "${base}_$lastNameCount.mp4"
        }
        return name
    }

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
    private fun startRecLocked(vc: VideoCapture<Recorder>, rec: Rec, startTimeoutSec: Long = 8) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "main thread" }
        // 動画の向きは 1 本ごとに、始める時点の端末の向きに合わせる
        val r = rotation
        if (vc.targetRotation != r) onMainAndWait { vc.targetRotation = r }
        rec.rotation = r
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
        if (!rec.started.await(startTimeoutSec, TimeUnit.SECONDS)) {
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
                stopKeepingLocked(why, rebind = true)
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
    override fun prepare(quality: String, audio: Boolean, still: Boolean, stopTempC: Double, off: Boolean, fps: Int, bitrate: String) {
        synchronized(recordLock) {
            if (off) {
                pendingArm = null
                lastArm = null
                armPaused = null
                disarmLocked()
                return
            }
            if (!running) throw IllegalStateException("カメラが起動していません")
            if (!cancelStop("録画の準備")) throw IllegalStateException("コンパニオンを止めています（動画の書き出しが済むと止まります）")
            val req = ArmRequest(quality, audio, still, stopTempC, VideoTuning.fpsOf(fps), VideoTuning.levelOf(bitrate))
            lastArm = req
            if (session != null) {
                // 録画中：録画が終わってから準備し直す
                pendingArm = req
                return
            }
            if (armPaused != null) return // 高温で休んでいる：冷えたら見張りが準備し直す
            if (cameraIdle) ensureCameraLocked()
            armLocked(req)
        }
    }

    private fun armLocked(req: ArmRequest) {
        val a = armed
        if (a != null && a.sameAs(req.quality, req.still, req.fps, req.bitrate, facingValue) && videoCapture != null) {
            // 組み方は同じ。音声の有無が変わったなら録りためだけ録り直す
            if (a.audio != req.audio) stopBufferLocked()
            armed = ArmedConfig(req.quality, req.audio, req.still, facingValue, req.stopTempC, a.resolution, a.tuning)
            requestBufferStart()
            return
        }
        stopBufferLocked()
        armed = null
        val b = bindForRecordingLocked(req.quality, req.still, req.fps, req.bitrate)
        armed = ArmedConfig(req.quality, req.audio, req.still, facingValue, req.stopTempC, b.resolution, b.tuning)
        Log.i(TAG, "録画の準備ができました（${req.quality} ${b.resolution}・${b.tuning.fps}fps・細かさ ${b.tuning.bitrateLevel}・音声 ${req.audio}・撮影 ${req.still}）")
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
        if (!running || stopping || !Prefs.preRoll(this) || session != null || bufCur != null || bufPaused != null || stopWhenRecordingEnds) return
        if (a.facing != facingValue) return
        val vc = videoCapture ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < bufRetryAt) return
        val rec = newChunkRec(a.audio && audioPermitted)
        try {
            startRecLocked(vc, rec)
        } catch (e: Exception) {
            if (a.tuning.fps == 60) {
                // 60fps の組み方では録り始められない：30fps の組み方に戻して、録りためをやり直す
                try {
                    val b = downgrade60Locked(a.quality, a.still, a.tuning.bitrateLevel, e.message ?: e.javaClass.simpleName)
                    armed = ArmedConfig(a.quality, a.audio, a.still, a.facing, a.stopTempC, b.resolution, b.tuning)
                    bufRetryAt = 0
                    requestBufferStart()
                    refreshNotification()
                    return
                } catch (e2: Exception) {
                    Log.w(TAG, "30fps への戻しに失敗", e2)
                }
            }
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
        // 発火が遅れて別の切れ端の番になっていたら回さない（古いタイマーが新しい切れ端を早回しするのを防ぐ）
        val target = bufCur
        val r = Runnable { postWork { synchronized(recordLock) { if (bufCur === target) rollBufferLocked() } } }
        bufTimer = r
        mainHandler.postDelayed(r, chunkMs())
    }

    /** 切れ端を次へ回す：今のを止め、すぐ次を始め、止めた方を「一つ前」として残す（その前のは消す） */
    private fun rollBufferLocked() {
        val old = bufCur ?: return
        if (session != null) return
        val vc = videoCapture ?: return
        bufTimer?.let { mainHandler.removeCallbacks(it) }
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
            // 次は前の切れ端の保存が済んでから始まるので、長めに待つ
            startRecLocked(vc, next, 30)
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
        val prev = bufPrev?.takeIf { it.isFinalized && it.startFailure == null && it.rotation == cur.rotation }
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
            fps = s.tuning.fps,
            fpsWanted = s.tuning.fpsWanted,
            fpsNote = s.tuning.fpsNote,
            bitrate = s.tuning.bitrate,
            bitrateLevel = s.tuning.bitrateLevel,
            origin = s.opts.origin,
        )
    }

    /** いまの 1 本を録り始めた：カメラから届いた枚数と時刻を控える（実測の fps を出すため） */
    private fun beginSegFps(s: Session) {
        s.segFrames0 = sensorFrames.get()
        s.segStartAt = SystemClock.elapsedRealtime()
        s.segFps = 0.0
    }

    /** いまの 1 本を止める直前に呼ぶ：録っている間にカメラから届いた fps の平均を控える */
    private fun markSegFps(s: Session) {
        val ms = SystemClock.elapsedRealtime() - s.segStartAt
        s.segFps = if (s.segStartAt > 0L && ms >= 500L) (sensorFrames.get() - s.segFrames0) * 1000.0 / ms else 0.0
    }

    override fun startRecording(o: StartOptions): RecordingState {
        val tapAt = SystemClock.elapsedRealtime()
        synchronized(recordLock) {
            session?.let { return snapshot(it) }
            // G2 アプリの頼み方を覚えておく（コンパニオン単体で録るときに同じ設定で録る。0.1.21）
            if (o.origin == ORIGIN_G2) Prefs.setLastRecordOptions(this, o)
            if (!cancelStop("録画")) throw IllegalStateException("コンパニオンを止めています（動画の書き出しが済むと止まります）")
            if (cameraIdle) ensureCameraLocked()
            if (camera == null) throw IllegalStateException("カメラが起動していません（止める途中から戻ったところなら、少し待ってからもう一度）")
            val thumb = latestFrame?.bytes
            val withAudio = o.audio && audioPermitted
            var a = armed
            val oFps = VideoTuning.fpsOf(o.fps)
            val oBitrate = VideoTuning.levelOf(o.bitrate)
            if (a != null && !(a.sameAs(o.quality, o.still, oFps, oBitrate, facingValue) && videoCapture != null)) {
                // 準備と違う頼み方：静止画なし（G2 録画）なら頼まれた組み方で準備し直し、静止画あり（G2 カメラ）なら準備を解く
                stopBufferLocked()
                armed = null
                a = null
                if (!o.still) {
                    val b = bindForRecordingLocked(o.quality, o.still, oFps, oBitrate)
                    val na = ArmedConfig(o.quality, o.audio, o.still, facingValue, o.stopTempC, b.resolution, b.tuning)
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
                val prev = bufPrev?.takeIf { it.isFinalized && it.startFailure == null && it.file?.exists() == true && it.rotation == cur.rotation }
                val plan = PreRollPlan.plan(
                    tapAt, cur.startedAt, Prefs.preRollSeconds(this) * 1000L,
                    prev?.let { PreRollPlan.Prev(it.durationMs, it.stopAt) },
                )
                if (!plan.prevUse) bufPrev?.file?.delete()
                bufCur = null
                bufPrev = null
                cur.sessionTapAt = tapAt
                s = Session(o, tapAt, withAudio, armedNow.resolution, imageAnalysis != null, imageCapture != null, cur, PreRollUse(if (plan.prevUse) prev else null, plan), name, armedNow.tuning)
                Log.i(TAG, "録画開始 $name（遡り ${plan.preRollMs}ms：前の切れ端 ${if (plan.prevUse) "${plan.prevFromMs}ms から" else "使わない"}・今の切れ端 ${plan.curFromMs}ms から）")
            } else {
                stopBufferLocked()
                var bound = if (armedNow != null) Bound(armedNow.resolution, armedNow.tuning) else bindForRecordingLocked(o.quality, o.still, oFps, oBitrate)
                var vc = videoCapture ?: throw IllegalStateException("録画を開始できません（構成=$boundLabel）")
                var rec = newDestinationRec(name, withAudio)
                try {
                    startRecLocked(vc, rec)
                } catch (e: Exception) {
                    var retried = false
                    if (bound.tuning.fps == 60) {
                        // 60fps の組み方では録り始められない：30fps の組み方に戻して、もう一度だけ試す
                        try {
                            bound = downgrade60Locked(o.quality, o.still, oBitrate, e.message ?: e.javaClass.simpleName)
                            if (armedNow != null) {
                                armed = ArmedConfig(armedNow.quality, armedNow.audio, armedNow.still, armedNow.facing, armedNow.stopTempC, bound.resolution, bound.tuning)
                            }
                            vc = videoCapture ?: throw IllegalStateException("録画を開始できません（構成=$boundLabel）")
                            rec = newDestinationRec(name, withAudio)
                            startRecLocked(vc, rec)
                            retried = true
                        } catch (e2: Exception) {
                            Log.w(TAG, "30fps でのやり直しに失敗", e2)
                        }
                    }
                    if (!retried) {
                        if (armedNow == null) {
                            try {
                                rebindAndWait(null)
                            } catch (_: Exception) {
                            }
                        }
                        throw e
                    }
                }
                rec.sessionTapAt = tapAt
                s = Session(o, tapAt, withAudio, bound.resolution, imageAnalysis != null, imageCapture != null, rec, null, name, bound.tuning)
                Log.i(
                    TAG,
                    "録画開始 $name（音声 $withAudio・配信 ${s.live}・撮影 ${s.capture}・解像度 ${bound.resolution}・${bound.tuning.fps}fps・細かさ ${bound.tuning.bitrateLevel}・準備 ${armedNow != null}）",
                )
            }
            s.thumb = thumb
            beginSegFps(s)
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
        // 発火が遅れて、その間に（空き容量の都合などで）区切っていたら、この区切りはもう済んでいる
        val seg = s.segment
        val r = Runnable { postWork { synchronized(recordLock) { if (s.segment == seg) onSegmentDueLocked(s) } } }
        s.timer = r
        mainHandler.postDelayed(r, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun onSegmentDueLocked(s: Session) {
        if (session !== s) return
        if (s.opts.continuous) {
            rollSegmentLocked(s, null)
        } else {
            Log.i(TAG, "録画を止めます（最長録画時間）")
            stopKeepingLocked(null, rebind = true)
        }
    }

    /** 区切り録画：今のファイルを閉じ、すぐ次のファイルへ録り続ける */
    private fun rollSegmentLocked(s: Session, note: String?) {
        if (session !== s) return
        val vc = videoCapture
        if (vc == null) {
            stopKeepingLocked(note, rebind = true)
            return
        }
        val old = s.current
        // 次のファイル（選んだフォルダなら書き込み先も）を先に作っておき、止めてから始めるまでの欠けを短くする
        val next = newDestinationRec(newVideoName(), s.audio)
        markSegFps(s)
        old.stopAt = SystemClock.elapsedRealtime()
        try {
            old.recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
        var startError: String? = null
        try {
            // 次は前のファイルの保存が済んでから始まるので、長めに待つ
            startRecLocked(vc, next, 30)
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
            pendingResult.set(r.copy(error = listOfNotNull(r.error, "区切りの続きを始められませんでした（$startError）").joinToString("。")))
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
        beginSegFps(s)
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
            // 何も録れていないと分かったときだけ諦める。保存待ちが長引いているだけなら、書き出しの側で済むのを待つ
            val nothing = rec.isFinalized && (rec.bytes <= 0L || !file.exists() || file.length() == 0L)
            if (!nothing) {
                enqueueMerge(name, parts, dur * 1000L, if (rec.isFinalized) null else rec.finalized)
                val saveError = rec.finalizeEvent?.takeIf { it.hasError() }?.let { "保存時のエラー（${errorText(it.error)}）" }
                return RecordResult(
                    name = name, uri = null, durationMs = dur, bytes = (rate * dur).toLong(), audio = s.audio,
                    thumbnail = s.thumb, error = listOfNotNull(note, saveError).joinToString("。").ifEmpty { null },
                    resolution = s.resolution, preRollMs = pre.plan.preRollMs, pending = true, segment = s.segment,
                    fps = s.tuning.fps, measuredFps = s.segFps, bitrate = s.tuning.bitrate, bitrateLevel = s.tuning.bitrateLevel, fpsNote = s.tuning.fpsNote,
                )
            }
            parts.forEach { it.file.delete() }
            return RecordResult(
                name = name, uri = null, durationMs = dur, bytes = 0L, audio = s.audio, thumbnail = s.thumb,
                error = listOfNotNull(note, "録画を保存できませんでした").joinToString("。"), resolution = s.resolution,
                segment = s.segment,
                fps = s.tuning.fps, measuredFps = s.segFps, bitrate = s.tuning.bitrate, bitrateLevel = s.tuning.bitrateLevel, fpsNote = s.tuning.fpsNote,
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
            fps = s.tuning.fps, measuredFps = s.segFps, bitrate = s.tuning.bitrate, bitrateLevel = s.tuning.bitrateLevel, fpsNote = s.tuning.fpsNote,
        )
    }

    /** 録画を止めて最後の 1 本の結果を返す（recordLock の中で）。録画していなければ null */
    private fun stopSessionLocked(note: String?, rebind: Boolean): RecordResult? {
        val s = session ?: return null
        s.timer?.let { mainHandler.removeCallbacks(it) }
        s.timer = null
        val rec = s.current
        markSegFps(s)
        stopRecWait(rec, 30)
        session = null
        recordingLabel = null
        val r = finishSegmentLocked(s, rec, note)
        Log.i(
            TAG,
            "録画終了 ${r.name} ${r.durationMs}ms ${r.bytes}B（平均 ${fmt1(VideoTuning.mbpsOf(r.bytes, r.durationMs))}Mbps・実測 ${fmt1(r.measuredFps)}fps）" +
                " 遡り ${r.preRollMs}ms 書き出し中 ${r.pending} uri=${r.uri} error=${r.error}",
        )
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
            mainHandler.post { shutdownGracefully(cancellable = true) }
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

    override fun takePendingResult(): RecordResult? = pendingResult.getAndSet(null)

    override fun takeSegments(): List<RecordResult> = synchronized(segmentLock) {
        val list = ArrayList(completedSegments)
        completedSegments.clear()
        list
    }

    /**
     * 録画を止め、結果を G2 アプリが取りに来るまで持っておく。
     * ただしコンパニオン単体で始めて G2 アプリが一度も触れていない録画は、保存して終わり（G2 アプリの一覧には渡さない。0.1.21）
     */
    private fun stopKeepingLocked(note: String?, rebind: Boolean) {
        val s = session
        val r = stopSessionLocked(note, rebind) ?: return
        if (s != null && s.opts.origin == ORIGIN_COMPANION && !s.clientSeen) {
            AppLog.i("コンパニオンで録った動画を保存しました：${r.name}（${r.durationMs / 1000} 秒）")
            return
        }
        pendingResult.set(r)
    }

    /**
     * コンパニオン単体で録画を始める（0.1.21。画面のボタン・通知・クイック設定タイル）。
     * 頼み方は G2 アプリが最後に使ったもの（区切りなし・高温と空き容量では止める）。カメラが開くまで少し待つ
     */
    private fun startStandalone(trigger: String) {
        if (session != null) return
        val o = Prefs.lastRecordOptions(this)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var last: Exception? = null
        while (running && !stopping && SystemClock.elapsedRealtime() < deadline) {
            try {
                startRecording(o)
                standaloneError = null
                AppLog.i("コンパニオンから録画を始めました（$trigger・${Prefs.describeLastRecord(this)}）")
                return
            } catch (e: Exception) {
                last = e
                // カメラがまだ開いていない（起動直後）：少し待ってやり直す
                if (camera != null && !cameraIdle) break
                Thread.sleep(400)
            }
        }
        val why = last?.message ?: "カメラが動いていません"
        standaloneError = "録画を始められませんでした：$why"
        AppLog.w("コンパニオンからの録画を始められませんでした（$trigger）：$why")
        refreshNotification()
    }

    /** 自分の都合で止めたときは、結果を G2 カメラ側が取りに来るまで持っておく */
    private fun stopRecordingInternally(reason: String, note: String? = null) {
        try {
            synchronized(recordLock) {
                if (session == null) return
                Log.i(TAG, "録画を止めます（$reason）")
                stopKeepingLocked(note, rebind = true)
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

    private var lastThermal = -1

    private fun checkHealth() {
        val m = DeviceMonitor.read(this)
        lastMonitor = m
        monitorLabel = describeMonitor(m)
        LoadMonitor.tick()
        if (m.thermal != lastThermal) {
            if (lastThermal >= 0 || m.thermal > 0) AppLog.i("発熱の段階が $lastThermal → ${m.thermal} に変わりました（${if (m.tempC.isNaN()) "温度不明" else "${fmt1(m.tempC)}度"}・CPU ${LoadMonitor.latest?.cpuPct ?: -1}%）")
            lastThermal = m.thermal
        }
        // 「熱い」の段階・止める段階・止める温度はコンパニオンの画面で選べる（0.1.15）
        val hotLevel = Prefs.heatHotThermal(this)
        val stopLevel = Prefs.heatStopThermal(this)
        val hot = hotLevel > 0 && m.thermal >= hotLevel
        if (hot != hotNow) {
            hotNow = hot
            refreshStreamInterval()
            AppLog.i(if (hot) "熱いので配信の fps を半分にします" else "冷えたので配信の fps を戻します")
        }
        streamLabel = buildStreamLabel()
        val s = session
        if (s != null) {
            val stopTemp = Prefs.effectiveStopTemp(this, s.opts.stopTempC)
            val hot = (stopTemp > 0 && !m.tempC.isNaN() && m.tempC >= stopTemp) ||
                (stopLevel > 0 && m.thermal >= stopLevel)
            if (hot) {
                stopRecordingInternally("高温", "スマホが熱くなったので録画を止めて保存しました（${if (m.tempC.isNaN()) "発熱 段階${m.thermal}" else "${fmt1(m.tempC)}度"}）")
                // 録画のあとの録りため（このあとに順番待ち）も、冷えるまで休ませる
                if (armed != null && Prefs.preRoll(this)) bufPaused = "heat"
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
                            stopKeepingLocked("空き容量が少なくなったので録画を止めて保存しました", rebind = true)
                            if (armed != null && Prefs.preRoll(this)) bufPaused = "space"
                        }
                    }
                }
            }
            return
        }
        synchronized(recordLock) {
            if (session != null) return
            // 録画の準備（録画の組み方で待つ）は、熱いときは解いて冷えるまで休む（冷えたら頼まれていた通りに準備し直す）
            val a0 = armed
            val stopTemp = Prefs.effectiveStopTemp(this, a0?.stopTempC ?: lastArm?.stopTempC ?: 0.0)
            val armHot = (hotLevel > 0 && m.thermal >= hotLevel) || (stopTemp > 0 && !m.tempC.isNaN() && m.tempC >= stopTemp - 3)
            if (a0 != null && armHot) {
                AppLog.i("熱いので録画の準備を休みます（${if (m.tempC.isNaN()) "発熱 段階${m.thermal}" else "${fmt1(m.tempC)}度"}）。冷えたら準備し直します")
                armPaused = "heat"
                disarmLocked()
                return
            }
            if (a0 == null && armPaused != null) {
                val cooled = (hotLevel <= 0 || m.thermal < maxOf(1, hotLevel - 1)) && (m.tempC.isNaN() || stopTemp <= 0 || m.tempC < stopTemp - 5)
                val req = lastArm
                if (cooled && req != null && !cameraIdle && running && !stopping) {
                    AppLog.i("冷えたので録画の準備をし直します")
                    armPaused = null
                    try {
                        armLocked(req)
                    } catch (e: Exception) {
                        Log.w(TAG, "re-arm failed", e)
                    }
                }
                return
            }
            val a = armed
            if (a == null || !Prefs.preRoll(this)) {
                bufPaused = null
                return
            }
            val effStop = Prefs.effectiveStopTemp(this, a.stopTempC)
            val pauseAt = if (effStop > 0) effStop - 3 else if (Prefs.heatStopTemp(this) == -1) 999.0 else 45.0
            val paused = bufPaused
            val resume: Boolean
            if (paused == null) {
                val hot = (hotLevel > 0 && m.thermal >= hotLevel) || (!m.tempC.isNaN() && m.tempC >= pauseAt)
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
                resume = (hotLevel <= 0 || m.thermal < maxOf(1, hotLevel - 1)) && (m.tempC.isNaN() || m.tempC < pauseAt - 2)
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

    /** つなぎ合わせを頼む。ready があれば、材料の保存（CameraX の書き終わり）を待ってから始める */
    private fun enqueueMerge(name: String, parts: List<Mp4Merger.Part>, expectUs: Long, ready: CountDownLatch? = null) {
        writeManifest(name, parts, expectUs, null)
        val latch = CountDownLatch(1)
        merging[name] = latch
        activeMerges.add(name)
        refreshNotification()
        try {
            mergeExecutor.execute {
                try {
                    if (ready != null && !ready.await(5, TimeUnit.MINUTES)) Log.w(TAG, "材料の保存が済まないまま書き出します: $name")
                    runMerge(name, parts, expectUs)
                } catch (e: Throwable) {
                    Log.e(TAG, "merge crashed: $name", e)
                    mergeNote = "$name の書き出しに失敗しました（${e.message}）"
                } finally {
                    if (merging[name] === latch) merging.remove(name)
                    activeMerges.remove(name)
                    latch.countDown()
                    mainHandler.post { refreshNotification() }
                }
            }
        } catch (e: RejectedExecutionException) {
            merging.remove(name)
            activeMerges.remove(name)
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
            val preSaved = uri != null && parts.size > 1 && Storage.copyVideo(this, parts.first().file, preName) != null
            note = if (uri != null) {
                "$name：遡りをつなげませんでした（$why）" +
                    (if (parts.size > 1) (if (preSaved) "。直前の分は $preName に保存しました" else "。直前の分は保存できませんでした") else "")
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
                // まだ書き出している（同じプロセスの前のサービスが頼んだ）ものは重ねない
                if (activeMerges.contains(name)) continue
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

    // ── 保存した動画を渡す ──
    override fun openRecording(name: String): Pair<InputStream, Long>? {
        // 書き出し中なら済むまで待つ。待ちきれなければ「書き出し中」で断る（作りかけを渡さない）
        val m = merging[name]
        if (m != null && !m.await(180, TimeUnit.SECONDS)) throw BusyException("$name は書き出し中です。しばらくしてからもう一度取り寄せてください")
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
