package com.ef64nagaoka.g2cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 遡り録画で、どの切れ端のどこから使うかの計算 */
class PreRollPlanTest {

    @Test
    fun currentChunkAloneIsEnough() {
        // 今の切れ端はタップの 15 秒前から。10 秒遡る → 今の切れ端の 5 秒目から。前の切れ端は使わない
        val p = PreRollPlan.plan(100_000L, 85_000L, 10_000L, PreRollPlan.Prev(12_000L, 84_500L))
        assertFalse(p.prevUse)
        assertEquals(5_000L, p.curFromMs)
        assertEquals(10_000L, p.preRollMs)
    }

    @Test
    fun takesTheRestFromThePreviousChunk() {
        // 今の切れ端はタップの 4 秒前から → 足りない 6 秒を前の切れ端（12 秒）の 6 秒目から
        val p = PreRollPlan.plan(100_000L, 96_000L, 10_000L, PreRollPlan.Prev(12_000L, 95_500L))
        assertTrue(p.prevUse)
        assertEquals(6_000L, p.prevFromMs)
        assertEquals(0L, p.curFromMs)
        assertEquals(10_000L, p.preRollMs)
    }

    @Test
    fun previousChunkShorterThanNeeded() {
        // 前の切れ端が 3 秒しかない → 全部使って、遡りは 2 + 3 = 5 秒
        val p = PreRollPlan.plan(100_000L, 98_000L, 10_000L, PreRollPlan.Prev(3_000L, 97_500L))
        assertTrue(p.prevUse)
        assertEquals(0L, p.prevFromMs)
        assertEquals(0L, p.curFromMs)
        assertEquals(5_000L, p.preRollMs)
    }

    @Test
    fun previousChunkTooFarApartIsNotUsed() {
        // 前の切れ端の終わりから今の切れ端の始まりまで 6 秒空いている → つながっていないので使わない
        val p = PreRollPlan.plan(100_000L, 96_000L, 10_000L, PreRollPlan.Prev(12_000L, 90_000L))
        assertFalse(p.prevUse)
        assertEquals(0L, p.curFromMs)
        assertEquals(4_000L, p.preRollMs)
    }

    @Test
    fun noPreviousChunk() {
        val p = PreRollPlan.plan(100_000L, 97_000L, 10_000L, null)
        assertFalse(p.prevUse)
        assertEquals(0L, p.curFromMs)
        assertEquals(3_000L, p.preRollMs)
    }

    @Test
    fun tapRightAfterTheChunkStarted() {
        // 時計の揺れでタップが始まりより前に見えても、負にならない
        val p = PreRollPlan.plan(100_000L, 100_200L, 5_000L, PreRollPlan.Prev(12_000L, 99_700L))
        assertTrue(p.prevUse)
        assertEquals(7_000L, p.prevFromMs)
        assertEquals(0L, p.curFromMs)
        assertEquals(5_000L, p.preRollMs)
    }

    @Test
    fun previousEndingAfterCurrentStartIsNotUsed() {
        // 前の切れ端の「止めた時刻」が今の切れ端の始まりより後（あり得ないが）なら、つながっていないとみなす
        val p = PreRollPlan.plan(100_000L, 96_000L, 10_000L, PreRollPlan.Prev(12_000L, 96_500L))
        assertFalse(p.prevUse)
        assertEquals(4_000L, p.preRollMs)
    }
}
