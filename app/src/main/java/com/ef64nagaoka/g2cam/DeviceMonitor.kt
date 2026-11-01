package com.ef64nagaoka.g2cam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log

/**
 * 録画中の見張りに使う、スマホの状態（電池・温度・発熱の段階・空き容量）。
 * どれも特別な権限なしで読める。
 */
object DeviceMonitor {
    private const val TAG = "G2Cam"

    class Snapshot(
        /** 電池残量（%）。分からなければ -1 */
        val batteryPct: Int,
        /** 電池の温度（℃）。端末の温度の目安。分からなければ NaN */
        val tempC: Double,
        val charging: Boolean,
        /** PowerManager の発熱の段階（0=なし 1=軽い 2=中 3=重い 4=危険 5=緊急 6=停止寸前） */
        val thermal: Int,
        /** 録画の保存先（内部ストレージ）の空き（バイト）。分からなければ -1 */
        val freeBytes: Long,
        /** 遡り録画の切れ端を置くアプリ内の領域の空き（バイト） */
        val cacheFreeBytes: Long,
    )

    fun read(context: Context): Snapshot {
        var pct = -1
        var temp = Double.NaN
        var charging = false
        try {
            // 電池の状態は「残り続ける放送」なので、受け手なしで登録すると最新の値が返る（システムの放送なので印は要らない）
            val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) pct = level * 100 / scale
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) temp = t / 10.0
                charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "battery read failed", e)
        }
        val thermal = try {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        } catch (e: Exception) {
            0
        }
        val free = try {
            StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        } catch (e: Exception) {
            -1L
        }
        val cacheFree = try {
            StatFs(context.noBackupFilesDir.path).availableBytes
        } catch (e: Exception) {
            -1L
        }
        return Snapshot(pct, temp, charging, thermal, free, cacheFree)
    }
}
