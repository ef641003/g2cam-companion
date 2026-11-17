package com.ef64nagaoka.g2cam

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * クイック設定のタイル「G2 録画」（0.1.21）：コンパニオン単体で録画を始める／止める。
 * カメラのサービスが動いていればそのまま頼み、動いていなければ透明な画面（LaunchActivity）を経由して始める
 * （カメラ付きのフォアグラウンドサービスは、アプリが前面にある間にしか始められないため）。
 */
class RecordTileService : TileService() {

    companion object {
        private const val TAG = "G2CamTile"

        /** 録画の状態が変わったらタイルの表示を直してもらう */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, RecordTileService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "requestListeningState failed", e)
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        if (CameraService.running) {
            startService(
                Intent(this, CameraService::class.java)
                    .setAction(CameraService.ACTION_TOGGLE_RECORDING)
                    .putExtra(CameraService.EXTRA_TRIGGER, "タイル"),
            )
        } else {
            val intent = Intent(this, LaunchActivity::class.java)
                .putExtra(LaunchActivity.EXTRA_RECORD, "タイル")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        // 実際の状態はサービスが refresh で知らせてくる。押した手応えのため、先に表示だけ変える
        qsTile?.let {
            it.state = if (CameraService.recordingNow) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val rec = CameraService.recordingNow
        tile.state = if (rec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "G2 録画"
        tile.subtitle = when {
            rec && CameraService.recordingOrigin == ORIGIN_COMPANION -> "録画中（タップで停止）"
            rec -> "録画中（G2 アプリ）"
            else -> "タップで録画開始"
        }
        tile.updateTile()
    }
}
