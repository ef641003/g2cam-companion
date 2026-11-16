package com.ef64nagaoka.g2cam

import kotlin.math.ln
import kotlin.math.pow

/**
 * 低照度（0.1.19）：暗さの見積もりと、暗いときの露光の決め方。Android に頼らない計算だけ（単体試験で確かめる）。
 *
 * 暗さはカメラの露出の値から見積もる：EV100 = log2(F値² / 露光時間[秒]) − log2(ISO / 100)。
 * カメラの自動露出は画面をほぼ中間の明るさに合わせるので、この値がその場の明るさの目安になる。
 * 照度（lux）はおおよそ 2.5 × 2^EV100（入射光式露出計の換算。あくまで目安）。
 */
object LowLight {
    /** 判定の段階。enterLux 以下が続いたら暗い、exitLux 以上が続いたら明るい（間を空けて行ったり来たりを防ぐ） */
    data class Level(val enterLux: Double, val exitLux: Double, val label: String)

    /** 0 = 早め、1 = ふつう（既定）、2 = 遅め */
    val LEVELS = listOf(
        Level(40.0, 100.0, "早め"),
        Level(20.0, 50.0, "ふつう"),
        Level(8.0, 20.0, "遅め"),
    )

    fun level(i: Int): Level = LEVELS[i.coerceIn(0, LEVELS.size - 1)]

    /** 露出の値から EV100 を出す。読めない値なら null。boostPct は RAW の後で掛ける感度（100 = 1 倍） */
    fun ev100(exposureNs: Long, iso: Int, boostPct: Int?, aperture: Float): Double? {
        if (exposureNs <= 0L || iso <= 0 || !(aperture > 0f)) return null
        val t = exposureNs / 1e9
        val s = iso * ((boostPct?.takeIf { it > 0 } ?: 100) / 100.0)
        val n = aperture.toDouble()
        val ev = log2(n * n / t) - log2(s / 100.0)
        return if (ev.isNaN() || ev.isInfinite()) null else ev
    }

    fun luxOf(ev: Double): Double = 2.5 * 2.0.pow(ev)

    fun evOf(lux: Double): Double = log2(lux / 2.5)

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    /** AE の fps 範囲（android.util.Range は単体試験で使えないので自前で持つ） */
    data class FpsRange(val lower: Int, val upper: Int) {
        override fun toString(): String = "$lower〜$upper"
    }

    /** 録画の下限（これより遅くするとカクつきすぎる）と、写真・メガネの映像の下限 */
    const val RECORDING_MIN_FPS = 7
    const val STILL_MIN_FPS = 5

    /**
     * 暗いときの AE の fps 範囲を選ぶ。下限が低いほど 1 コマの露光を長くできる（明るく、ノイズが減る）。
     * recording（録画中・録画の準備中）：上限は端末の 30 以下の一番上（ふつう 30）を保ち、下限は 7 まで。
     * それ以外（写真・メガネの映像）：上限 30 以下で下限が一番低いもの（下限は 5 まで。同じなら上限の低い方 = 負荷が軽い）。
     * 使えるものが無ければ null（fps は変えない）
     */
    fun pickRange(ranges: List<FpsRange>, recording: Boolean): FpsRange? {
        val usable = ranges.filter { it.upper <= 30 && it.lower in 1 until it.upper }
        if (recording) {
            val top = usable.maxOfOrNull { it.upper } ?: return null
            return usable.filter { it.upper == top && it.lower >= RECORDING_MIN_FPS }.minByOrNull { it.lower }
                ?: usable.filter { it.lower >= RECORDING_MIN_FPS }
                    .minWithOrNull(compareBy<FpsRange> { it.lower }.thenByDescending { it.upper })
        }
        return usable.filter { it.lower >= STILL_MIN_FPS }
            .minWithOrNull(compareBy<FpsRange> { it.lower }.thenBy { it.upper })
    }
}

/**
 * 暗いかどうかの判定。露出の値は揺れるので、ならしてから、入る・出るの基準を分け、しばらく続いたら切り替える。
 * update は露出の値が届くたびに呼ぶ（間引きは呼ぶ側）。状態が変わったときだけ新しい状態を返す（変わらなければ null）。
 * スレッドをまたいで使うときは、呼ぶ側でまとめて排他する。
 */
class LowLightDetector(private val level: () -> Int) {
    /** いま暗いとみなしているか */
    var active = false
        private set

    /** ならした EV100（まだ値が無ければ NaN） */
    var smoothedEv = Double.NaN
        private set

    private var belowSince = -1L
    private var aboveSince = -1L

    /** カメラを開き直した・切り替えたとき：ならしをやり直す（暗いかどうかの判定はそのまま） */
    fun reset() {
        smoothedEv = Double.NaN
        belowSince = -1L
        aboveSince = -1L
    }

    fun update(ev: Double, now: Long): Boolean? {
        if (ev.isNaN() || ev.isInfinite()) return null
        smoothedEv = if (smoothedEv.isNaN()) ev else smoothedEv + (ev - smoothedEv) * ALPHA
        val lv = LowLight.level(level())
        if (!active) {
            aboveSince = -1L
            if (smoothedEv <= LowLight.evOf(lv.enterLux)) {
                if (belowSince < 0L) belowSince = now
                if (now - belowSince >= ENTER_MS) {
                    active = true
                    belowSince = -1L
                    return true
                }
            } else {
                belowSince = -1L
            }
        } else {
            belowSince = -1L
            if (smoothedEv >= LowLight.evOf(lv.exitLux)) {
                if (aboveSince < 0L) aboveSince = now
                if (now - aboveSince >= EXIT_MS) {
                    active = false
                    aboveSince = -1L
                    return false
                }
            } else {
                aboveSince = -1L
            }
        }
        return null
    }

    companion object {
        /** ならしの強さ（1 回の値でどれだけ動くか） */
        const val ALPHA = 0.3
        /** 暗いが続いたら切り替えるまで */
        const val ENTER_MS = 1500L
        /** 明るいが続いたら戻すまで（明かりを一瞬向けただけで戻らないよう長め） */
        const val EXIT_MS = 3000L
    }
}
