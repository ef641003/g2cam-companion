package com.ef64nagaoka.g2cam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末の起動後、待機モードがオンなら待機サービスを再開する */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.standby(context)) {
            try {
                StandbyService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
