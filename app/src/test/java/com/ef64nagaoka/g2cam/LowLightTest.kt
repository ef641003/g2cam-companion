package com.ef64nagaoka.g2cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 低照度（0.1.19）：暗さの見積もり・判定・fps 範囲の選び方 */
class LowLightTest {

    private fun ns(sec: Double) = (sec * 1e9).toLong()

    @Test
    fun ev100FromExposure() {
        // F1.8・1/30 秒・ISO 400 → log2(3.24 × 30) − log2(4) ≈ 4.60（約 61 lux）
        val ev = LowLight.ev100(ns(1.0 / 30), 400, null, 1.8f)!!
        assertEquals(4.60, ev, 0.01)
        assertEquals(60.8, LowLight.luxOf(ev), 0.5)
        // RAW の後の感度 2 倍は ISO 2 倍と同じ（1 段暗い）
        assertEquals(ev - 1.0, LowLight.ev100(ns(1.0 / 30), 400, 200, 1.8f)!!, 1e-9)
        // 100（1 倍）と未設定は同じ
        assertEquals(ev, LowLight.ev100(ns(1.0 / 30), 400, 100, 1.8f)!!, 1e-9)
    }

    @Test
    fun ev100RejectsBrokenValues() {
        assertNull(LowLight.ev100(0L, 400, null, 1.8f))
        assertNull(LowLight.ev100(ns(0.01), 0, null, 1.8f))
        assertNull(LowLight.ev100(ns(0.01), 100, null, 0f))
        assertNull(LowLight.ev100(ns(0.01), 100, null, Float.NaN))
    }

    @Test
    fun luxAndEvAreInverse() {
        for (lux in listOf(1.0, 8.0, 20.0, 50.0, 400.0)) assertEquals(lux, LowLight.luxOf(LowLight.evOf(lux)), 1e-9)
        // 段階の基準は「入る ＜ 出る」
        for (lv in LowLight.LEVELS) assertTrue(lv.enterLux < lv.exitLux)
        assertEquals("ふつう", LowLight.level(1).label)
        assertEquals("早め", LowLight.level(-3).label)
        assertEquals("遅め", LowLight.level(9).label)
    }

    @Test
    fun detectorNeedsDarknessToLast() {
        val d = LowLightDetector { 1 } // ふつう：20 lux 以下が 1.5 秒続いたら暗い
        val bright = LowLight.evOf(300.0)
        val dark = LowLight.evOf(5.0)
        assertNull(d.update(bright, 0))
        assertFalse(d.active)
        // 暗くなっても、ならしと待ちがあるのですぐには切り替えない
        var t = 0L
        var changed: Boolean? = null
        while (changed == null && t < 10_000L) {
            t += 250
            changed = d.update(dark, t)
        }
        assertEquals(true, changed)
        assertTrue(d.active)
        // 下がり始めてから 1.5 秒以上たってから切り替わる（ならしで基準を下回るまでの分も含む）
        assertTrue("切り替えが早すぎる: $t", t >= LowLightDetector.ENTER_MS)
        assertTrue("切り替えが遅すぎる: $t", t <= 3_000L)
    }

    @Test
    fun detectorIgnoresBriefLight() {
        val d = LowLightDetector { 1 }
        val dark = LowLight.evOf(5.0)
        var t = 0L
        repeat(20) { t += 250; d.update(dark, t) }
        assertTrue(d.active)
        // 明かりを一瞬向けた（0.5 秒）→ 戻らない
        val bright = LowLight.evOf(2000.0)
        assertNull(d.update(bright, t + 250))
        assertNull(d.update(bright, t + 500))
        t += 500
        repeat(8) { t += 250; assertNull(d.update(dark, t)) }
        assertTrue(d.active)
        // 明るいが 3 秒続いたら戻る
        var changed: Boolean? = null
        val start = t
        while (changed == null && t - start < 10_000L) {
            t += 250
            changed = d.update(bright, t)
        }
        assertEquals(false, changed)
        assertFalse(d.active)
        assertTrue(t - start >= LowLightDetector.EXIT_MS)
    }

    @Test
    fun detectorKeepsStateBetweenThresholds() {
        // 入る基準（20 lux）と出る基準（50 lux）の間では、どちらにも切り替えない
        val d = LowLightDetector { 1 }
        val between = LowLight.evOf(30.0)
        var t = 0L
        repeat(40) { t += 250; assertNull(d.update(between, t)) }
        assertFalse(d.active)
        repeat(20) { t += 250; d.update(LowLight.evOf(3.0), t) }
        assertTrue(d.active)
        repeat(40) { t += 250; assertNull(d.update(between, t)) }
        assertTrue(d.active)
    }

    @Test
    fun detectorLevelsDiffer() {
        // 30 lux：早め（40 以下）なら暗い、遅め（8 以下）なら暗くない
        val early = LowLightDetector { 0 }
        val late = LowLightDetector { 2 }
        val ev = LowLight.evOf(30.0)
        var t = 0L
        repeat(40) {
            t += 250
            early.update(ev, t)
            late.update(ev, t)
        }
        assertTrue(early.active)
        assertFalse(late.active)
    }

    @Test
    fun detectorResetKeepsState() {
        val d = LowLightDetector { 1 }
        var t = 0L
        repeat(20) { t += 250; d.update(LowLight.evOf(3.0), t) }
        assertTrue(d.active)
        d.reset()
        assertTrue(d.active)
        assertTrue(d.smoothedEv.isNaN())
        // 壊れた値は無視
        assertNull(d.update(Double.NaN, t + 250))
        assertNull(d.update(Double.POSITIVE_INFINITY, t + 500))
        assertTrue(d.smoothedEv.isNaN())
    }

    private fun r(lo: Int, hi: Int) = LowLight.FpsRange(lo, hi)

    @Test
    fun pickRangeForRecordingKeepsThirty() {
        val ranges = listOf(r(15, 15), r(7, 15), r(15, 30), r(7, 30), r(30, 30), r(24, 24))
        assertEquals(r(7, 30), LowLight.pickRange(ranges, recording = true))
        // 60fps の範囲は使わない。下限 7 未満も使わない（カクつきすぎる）
        assertEquals(r(10, 30), LowLight.pickRange(listOf(r(15, 60), r(60, 60), r(5, 30), r(10, 30), r(30, 30)), recording = true))
        // 30 が無ければ、下限が一番低いもの（同じなら上限の高い方）
        assertEquals(r(8, 24), LowLight.pickRange(listOf(r(8, 20), r(8, 24), r(24, 24)), recording = true))
        // 下げられる範囲が無い
        assertNull(LowLight.pickRange(listOf(r(30, 30), r(15, 15)), recording = true))
        assertNull(LowLight.pickRange(listOf(r(3, 30), r(30, 30)), recording = true))
        assertNull(LowLight.pickRange(emptyList(), recording = true))
    }

    @Test
    fun pickRangeForStillsGoesLowest() {
        val ranges = listOf(r(15, 15), r(7, 15), r(15, 30), r(7, 30), r(30, 30))
        // 同じ下限なら上限の低い方（負荷が軽い）
        assertEquals(r(7, 15), LowLight.pickRange(ranges, recording = false))
        assertEquals(r(5, 30), LowLight.pickRange(listOf(r(5, 30), r(10, 15), r(30, 30)), recording = false))
        // 下限 5 未満は使わない
        assertEquals(r(10, 15), LowLight.pickRange(listOf(r(2, 30), r(10, 15)), recording = false))
        assertNull(LowLight.pickRange(listOf(r(30, 30), r(15, 15)), recording = false))
    }
}
