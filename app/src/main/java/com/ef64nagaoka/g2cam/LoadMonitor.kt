package com.ef64nagaoka.g2cam

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * このアプリ自身の CPU 使用の見張り（adb が使えない端末で、何が忙しいかを画面に出すため）。
 * 5 秒ごとに tick() を呼ぶと、前回からの CPU 時間の増え方を「1 コアの何％」で出す。
 * スレッドごとの CPU 時間（/proc/self/task/<tid>/stat）も読んで、いちばん忙しいスレッドの名前を出す
 * （たとえば HTTP サーバーの accept が空回りしていれば、そのスレッドが 100% 近くになる）。
 * カメラ HAL・ISP・GPU・Even アプリ側の負荷は含まない（それは端末の電池画面で見る）。
 */
object LoadMonitor {
    class ThreadLoad(val name: String, val pct: Int)

    class Sample(val at: Long, val cpuPct: Int, val threads: List<ThreadLoad>)

    private var lastWall = 0L
    private var lastCpu = 0L
    private val lastThreadTicks = HashMap<Int, Long>()
    private val history = ArrayDeque<Int>()
    @Volatile var latest: Sample? = null
        private set
    /** 直近 5 分の平均（1 コアの %） */
    @Volatile var avgPct = 0
        private set
    /** 直近 5 分で最も高かった値 */
    @Volatile var peakPct = 0
        private set

    private val ticksPerSec: Long by lazy {
        try {
            Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
        } catch (e: Exception) {
            100L
        }
    }

    /** 前回の tick からの CPU 使用を出す。2 秒以内に呼び直されたときは前回の値を返す */
    @Synchronized
    fun tick(): Sample? {
        val wall = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        if (lastWall == 0L) {
            lastWall = wall
            lastCpu = cpu
            lastThreadTicks.putAll(readThreadTicks())
            return null
        }
        val dWall = wall - lastWall
        if (dWall < 2000) return latest
        val pct = ((cpu - lastCpu) * 100 / dWall).toInt().coerceAtLeast(0)
        val now = readThreadTicks()
        val list = ArrayList<ThreadLoad>()
        for ((tid, ticks) in now) {
            val prev = lastThreadTicks[tid] ?: continue
            val d = ticks - prev
            if (d <= 0) continue
            val ms = d * 1000 / ticksPerSec
            val tp = (ms * 100 / dWall).toInt()
            if (tp >= 1) list.add(ThreadLoad(threadName(tid), tp))
        }
        list.sortByDescending { it.pct }
        lastThreadTicks.clear()
        lastThreadTicks.putAll(now)
        lastWall = wall
        lastCpu = cpu
        history.addLast(pct)
        while (history.size > 60) history.removeFirst()
        avgPct = if (history.isEmpty()) 0 else history.sum() / history.size
        peakPct = history.maxOrNull() ?: 0
        val s = Sample(wall, pct, list.take(3))
        latest = s
        return s
    }

    /** 画面用：「CPU 3%（5 分平均 2%・最大 12%）　忙しいスレッド: …」 */
    fun describe(): String {
        val s = latest ?: return "CPU 計測中…"
        val threads = if (s.threads.isEmpty()) "" else "　忙しいスレッド: " + s.threads.joinToString("・") { "${it.name} ${it.pct}%" }
        return "CPU ${s.cpuPct}%（5 分平均 $avgPct%・最大 $peakPct%）$threads"
    }

    /** スレッドごとの CPU 時間（クロックティック） */
    private fun readThreadTicks(): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        val dirs = File("/proc/self/task").listFiles() ?: return out
        for (d in dirs) {
            val tid = d.name.toIntOrNull() ?: continue
            try {
                val stat = File(d, "stat").readText()
                // "123 (name with spaces) S 1 2 ... utime stime ..." → 名前の閉じ括弧の後ろを分ける
                val close = stat.lastIndexOf(')')
                if (close < 0) continue
                val fields = stat.substring(close + 1).trim().split(' ')
                // 閉じ括弧の後ろは state(3) から数えて、utime が 14 番目・stime が 15 番目 → 添字 11 と 12
                if (fields.size < 13) continue
                val utime = fields[11].toLongOrNull() ?: continue
                val stime = fields[12].toLongOrNull() ?: continue
                out[tid] = utime + stime
            } catch (e: Exception) {
                // スレッドが消えた
            }
        }
        return out
    }

    private fun threadName(tid: Int): String = try {
        File("/proc/self/task/$tid/comm").readText().trim().ifEmpty { "tid $tid" }
    } catch (e: Exception) {
        "tid $tid"
    }
}
