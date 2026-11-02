package com.ef64nagaoka.g2cam

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本体の画面で読める小さなログ（adb が使えない端末向け）。
 * 起動・停止・起動要求・カメラの開閉・負荷の異常・発熱の段階など、要点だけを残す。
 * メモリに直近 400 行、ファイル（filesDir/companion.log）に 256KB まで。
 */
object AppLog {
    private const val TAG = "G2Cam"
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 256 * 1024
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "companion.log")
        file = f
        try {
            if (f.exists()) {
                val all = f.readLines()
                for (l in all.takeLast(MAX_LINES)) lines.addLast(l)
            }
        } catch (e: Exception) {
            Log.w(TAG, "log load failed", e)
        }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        add(msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        add("⚠ $msg")
    }

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) {
                f.writeText(lines.joinToString("\n") + "\n")
            } else {
                f.appendText(line + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }

    /** 新しい順に n 行 */
    @Synchronized
    fun recent(n: Int): String = lines.toList().takeLast(n).reversed().joinToString("\n")

    @Synchronized
    fun all(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }
}
