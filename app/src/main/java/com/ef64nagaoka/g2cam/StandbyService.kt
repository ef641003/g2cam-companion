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
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 「待機」サービス。カメラは持たず、127.0.0.1:8788 で G2 カメラからの「起動して」を待つだけ。
 *
 * G2 カメラが POST /launch を送ると、透明な LaunchActivity を前面に出し、そこからカメラのサービスを始める
 * （カメラ付きサービスは画面が出ている間にしか始められないため）。
 * 裏から画面を出すには「他のアプリの上に表示」の許可が要るので、許可があるときは 1px の透明な窓を出しておく
 * （Android 15 以降は、許可だけでなく窓が出ていることが条件）。
 */
class StandbyService : Service() {

    companion object {
        const val PORT = 8788
        private const val CHANNEL_ID = "g2cam_standby"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "G2Cam"
        const val ACTION_STOP = "com.ef64nagaoka.g2cam.STANDBY_STOP"

        @Volatile var running = false
        @Volatile var lastLaunchAt = 0L
        @Volatile var launchCount = 0

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StandbyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StandbyService::class.java))
        }
    }

    private var server: LauncherServer? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setStandby(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G2 カメラの起動待ち", NotificationManager.IMPORTANCE_MIN))
            try {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e: Exception) {
                CameraService.lastError = "待機を開始できません: ${e.message}"
                Log.e(TAG, "standby startForeground failed", e)
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            try {
                server = LauncherServer().also { it.start(5000, true) }
            } catch (e: Exception) {
                CameraService.lastError = "待機用のサーバーを開始できません（ポート $PORT）: ${e.message}"
                Log.e(TAG, "launcher server failed", e)
            }
            showOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        server?.stop()
        server = null
        hideOverlay()
        super.onDestroy()
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

    private fun showOverlay() {
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
        } catch (e: Exception) {
            Log.w(TAG, "overlay failed", e)
        }
    }

    private fun hideOverlay() {
        overlay?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    /** 起動待ちの小さな HTTP サーバー。GET /ping、POST /launch */
    private inner class LauncherServer : NanoHTTPD("127.0.0.1", PORT) {
        override fun serve(session: IHTTPSession): Response {
            val res = try {
                when {
                    session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                    session.uri == "/ping" -> json(JSONObject().put("ok", true).put("standby", true).put("cameraRunning", CameraService.running))
                    session.uri == "/launch" && session.method == Method.POST -> launch()
                    else -> json(JSONObject().put("ok", false).put("error", "not found"))
                }
            } catch (e: Exception) {
                json(JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
            }
            res.addHeader("Access-Control-Allow-Origin", "*")
            res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            res.addHeader("Access-Control-Allow-Headers", "*")
            res.addHeader("Access-Control-Allow-Private-Network", "true")
            res.addHeader("Cache-Control", "no-store")
            return res
        }

        private fun launch(): Response {
            if (CameraService.running) {
                return json(JSONObject().put("ok", true).put("already", true))
            }
            lastLaunchAt = System.currentTimeMillis()
            launchCount++
            val overlayOk = Settings.canDrawOverlays(this@StandbyService)
            val intent = Intent(this@StandbyService, LaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            startActivity(intent)
            return json(JSONObject().put("ok", true).put("overlayPermission", overlayOk).put("overlayShown", overlay != null))
        }

        private fun json(body: JSONObject): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body.toString())
    }
}
