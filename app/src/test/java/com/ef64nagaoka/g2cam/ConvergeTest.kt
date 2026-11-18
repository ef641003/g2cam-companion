package com.ef64nagaoka.g2cam

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvergeTest {
    @Test
    fun settledStates() {
        // AF：探している最中（1・3）だけ待つ。合った（2・4）・合わずに止まった（5・6）・何もしていない（0）・状態なしは待たない
        assertFalse(Converge.afSettled(1))
        assertFalse(Converge.afSettled(3))
        listOf(0, 2, 4, 5, 6, null).forEach { assertTrue("af=$it", Converge.afSettled(it)) }
        // AE：探している（1）・フラッシュ前の測光（5）だけ待つ
        assertFalse(Converge.aeSettled(1))
        assertFalse(Converge.aeSettled(5))
        listOf(0, 2, 3, 4, null).forEach { assertTrue("ae=$it", Converge.aeSettled(it)) }
    }

    @Test
    fun waitsUntilStableForAWhile() {
        val w = Converge.Watch(0)
        // 束ね直した直後：ピントを探している
        for (t in listOf(0L, 33L, 66L, 100L)) w.update(1, 2, t)
        assertFalse(w.ready(100))
        // 合った。200ms 続くまでは待つ
        w.update(2, 2, 133)
        assertFalse(w.ready(133))
        for (t in listOf(166L, 200L, 233L, 266L, 300L)) w.update(2, 2, t)
        assertFalse(w.ready(300))
        w.update(2, 2, 333)
        assertTrue(w.ready(333))
    }

    @Test
    fun needsSomeResultsFirst() {
        // 合っているように見えても、結果が少ないうちは決めない
        val w = Converge.Watch(0)
        w.update(0, 2, 0)
        w.update(0, 2, 300)
        assertFalse(w.ready(300))
        w.update(0, 2, 310)
        w.update(0, 2, 320)
        w.update(0, 2, 330)
        assertTrue(w.ready(330))
    }

    @Test
    fun searchingAgainResetsAndTimesOut() {
        val w = Converge.Watch(1000)
        for (t in 0L..5L) w.update(2, 2, 1000 + t * 30)
        w.update(2, 1, 1180) // 明るさが変わって探し直し
        assertFalse(w.ready(1300))
        assertFalse(w.timedOut(2400))
        assertTrue(w.timedOut(2500))
    }
}
