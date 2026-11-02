package com.ef64nagaoka.g2cam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONObject

/**
 * 「待機」サービス。カメラは持たず、127.0.0.1:8788 で G2 カメラからの「起動して」を待つだけ。
 *
 * G2 カメラが POST /launch を送ると、透明な LaunchActivity を前面に出し、そこからカメラのサービスを始める
 * （カメラ付きサービスは画面が出ている間にしか始められないため）。
 * 裏から画面を出すには「他のアプリの上に表示」の許可が要る。Android 15 以降は、許可だけでなく
 * 「その時点で窓が出ている」ことが条件なので、既定では 1px の透明な窓を出しておく（設定で「起動要求のときだけ」にもできる）。
 *
 * 0.1.12：HTTP サーバーをライブラリ（NanoHTTPD）から自前の小さなもの（LoopbackServer）に替えた。
 * 待機中はこのサービスしか動いていないのに端末が熱くなる報告があり、accept の空回りを疑ったため。
 * 5 秒ごとに自分の CPU 使用を測り、待機中なのに高ければログに残してサーバーを作り直す。
 */
class StandbyService : Service() {

    companion object {
        const val PORT = 8788
        private const val CHANNEL_ID = "g2cam_standby"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "G2Cam"
        const val ACTION_STOP = "com.ef64nagaoka.g2cam.STANDBY_STOP"
        /** 本体の画面で待機モードの設定（窓の出し方）を変えた */
        const val ACTION_OVERLAY_CHANGED = "com.ef64nagaoka.g2cam.STANDBY_OVERLAY_CHANGED"
        /** 待機中（カメラなし）なのに、このアプリの CPU がこれ以上なら異常とみなす（1 コアの %） */
        private const val IDLE_CPU_ALARM_PCT = 25

        @Volatile var running = false
        @Volatile var lastLaunchAt = 0L
        @Volatile var launchCount = 0
        @Volatile var startedAt = 0L
        @Volatile var overlayShown = false
        /** 画面用：待機サーバーの要求数・失敗数 */
        @Volatile var serverStats = ""
        /** 画面用：異常な負荷を見つけた回数 */
        @Volatile var cpuAlarms = 0

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StandbyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StandbyService::class.java))
        }
    }

    private var server: LoopbackServer? = null
    private var overlay: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastThermal = -1
    private var highCpuTicks = 0
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setStandby(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OVERLAY_CHANGED) {
            if (running) applyOverlayPolicy()
            return START_STICKY
        }
        if (!running) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 カメラの起動待ち", NotificationManager.IMPORTANCE_MIN))
            try {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: Exception) {
                CameraService.lastError = "待機を開始できません: ${e.message}"
                Log.e(TAG, "standby startForeground failed", e)
                AppLog.w("待機を開始できません: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            startedAt = SystemClock.elapsedRealtime()
            Prefs.noteStandbyStart(this)
            val restarts = Prefs.standbyStartsWithin(this, 60 * 60_000L)
            AppLog.i("待機を開始（intent ${if (intent == null) "なし＝システムによる再起動" else "あり"}。この 1 時間で ${restarts} 回目）")
            startServer()
            applyOverlayPolicy()
            LoadMonitor.tick()
            mainHandler.postDelayed(ticker, 5000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(ticker)
        server?.stop()
        server = null
        hideOverlay()
        AppLog.i("待機を終了")
        super.onDestroy()
    }

    private fun startServer() {
        server?.stop()
        val s = LoopbackServer(PORT, "g2cam-standby") { method, path -> route(method, path) }
        s.start()
        server = s
    }

    /** 5 秒ごと：自分の CPU と発熱の段階を見る。待機中なのに CPU が高ければ記録して立て直す */
    private fun tick() {
        val s = server
        serverStats = if (s != null) "要求 ${s.requests} 回・失敗 ${s.errors} 回${s.lastError?.let { "（最後の失敗: $it）" } ?: ""}" else "サーバーなし"
        val sample = LoadMonitor.tick() ?: return
        try {
            val t = getSystemService(PowerManager::class.java).currentThermalStatus
            if (t != lastThermal) {
                if (lastThermal >= 0 || t > 0) AppLog.i("発熱の段階が $lastThermal → $t に変わりました（待機中）")
                lastThermal = t
            }
        } catch (_: Exception) {
        }
        if (CameraService.running) {
            highCpuTicks = 0
            return
        }
        if (sample.cpuPct >= IDLE_CPU_ALARM_PCT) {
            highCpuTicks++
            if (highCpuTicks == 2) {
                cpuAlarms++
                val busy = sample.threads.joinToString("・") { "${it.name} ${it.pct}%" }
                AppLog.w("待機中なのに CPU が高い（${sample.cpuPct}%。忙しいスレッド: $busy）。待機サーバーを作り直します")
                startServer()
            }
        } else {
            highCpuTicks = 0
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, StandbyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("G2 カメラの起動を待っています")
            .setContentText("G2 カメラを開くとカメラを自動で始めます")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "待機をやめる", stop).build())
            .build()
    }

    /** 設定どおりに窓を出す／消す */
    private fun applyOverlayPolicy() {
        if (Prefs.standbyOverlayAlways(this)) showOverlay() else hideOverlay()
    }

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
            overlayShown = true
            AppLog.i("透明な窓を出しました（裏から起動するため）")
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed", e)
            AppLog.w("透明な窓を出せません: ${e.message}")
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
            AppLog.i("透明な窓を消しました")
        }
        overlay = null
        overlayShown = false
    }

    private fun route(method: String, path: String): Pair<Int, String> = try {
        when {
            path == "/ping" -> 200 to JSONObject().put("ok", true).put("standby", true).put("cameraRunning", CameraService.running).toString()
            path == "/launch" && method == "POST" -> 200 to launch().toString()
            else -> 404 to JSONObject().put("ok", false).put("error", "not found").toString()
        }
    } catch (e: Exception) {
        500 to JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
    }

    private fun launch(): JSONObject {
        if (CameraService.running) return JSONObject().put("ok", true).put("already", true)
        lastLaunchAt = System.currentTimeMillis()
        launchCount++
        val overlayOk = Settings.canDrawOverlays(this)
        AppLog.i("起動要求を受けました（${launchCount} 回目。窓 ${if (overlay != null) "あり" else "なし"}・許可 ${if (overlayOk) "あり" else "なし"}）")
        val always = Prefs.standbyOverlayAlways(this)
        mainHandler.post {
            if (!always) showOverlay() // 起動要求のときだけ窓を出す：出してから少し待って画面を出す
            mainHandler.postDelayed({
                try {
                    val intent = Intent(this, LaunchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(intent)
                } catch (e: Exception) {
                    AppLog.w("起動の画面を出せません: ${e.message}")
                }
                if (!always) mainHandler.postDelayed({ hideOverlay() }, 4000)
            }, if (always) 0L else 150L)
        }
        return JSONObject().put("ok", true).put("overlayPermission", overlayOk).put("overlayShown", overlay != null || !always)
    }
}
