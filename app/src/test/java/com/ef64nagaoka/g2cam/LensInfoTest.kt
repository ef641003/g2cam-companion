package com.ef64nagaoka.g2cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** レンズの倍率と、光学かデジタルかの見分け（0.1.20） */
class LensInfoTest {

    // 35mm 判換算で、超広角 15mm・標準 23mm・望遠 70mm になる 3 本（センサーの大きさは別々）
    private val uw = LensInfo.Physical("2", 2.2f, 5.08f, 3.81f)     // 対角 6.35mm → 約 15mm
    private val main = LensInfo.Physical("0", 6.9f, 10.4f, 7.8f)    // 対角 13.0mm → 約 23mm
    private val tele = LensInfo.Physical("3", 14.0f, 6.92f, 5.19f)  // 対角 8.65mm → 約 70mm

    @Test
    fun equivalentFocalLength() {
        assertEquals(15.0, uw.eq35, 0.3)
        assertEquals(23.0, main.eq35, 0.3)
        assertEquals(70.0, tele.eq35, 0.5)
    }

    @Test
    fun threeLensesWithUltraWide() {
        val l = LensInfo.lenses(listOf(tele, main, uw), zoomMin = 0.6, zoomMax = 10.0)
        assertEquals(3, l.size)
        // 一番広いレンズはズームの下端、標準は 1 倍、望遠は標準との比（70/23 ≒ 3.04 → きりのよい 3 に寄せる）
        assertEquals(listOf("2", "0", "3"), l.map { it.id })
        assertEquals(0.6, l[0].ratio, 1e-9)
        assertEquals(1.0, l[1].ratio, 1e-9)
        assertEquals(3.0, l[2].ratio, 1e-9)
        assertEquals(listOf("超広角", "標準", "望遠"), l.map { it.name })
        assertEquals("超広角 x0.6・標準 x1・望遠 x3", LensInfo.describe(l))
    }

    @Test
    fun twoLensesWithoutUltraWide() {
        // 超広角なし（ズームの下端が 1）：一番広いレンズが標準
        val l = LensInfo.lenses(listOf(main, tele), zoomMin = 1.0, zoomMax = 8.0)
        assertEquals(listOf("0", "3"), l.map { it.id })
        assertEquals(1.0, l[0].ratio, 1e-9)
        assertEquals(3.0, l[1].ratio, 1e-9)
    }

    @Test
    fun unknownWhenTheDeviceDoesNotExposeLenses() {
        // 1 本しか見えない：裏で望遠に切り替えているかもしれないので「分からない」
        assertTrue(LensInfo.lenses(listOf(main), 0.6, 10.0).isEmpty())
        assertTrue(LensInfo.lenses(emptyList(), 1.0, 4.0).isEmpty())
        // 壊れた値は捨てる
        assertTrue(LensInfo.lenses(listOf(main, LensInfo.Physical("9", 0f, 5f, 4f)), 1.0, 4.0).isEmpty())
        // 標準レンズの見積もりが合わない（超広角 15mm・下端 0.6 なら標準は 25mm 前後のはずが、70mm しか無い）
        assertTrue(LensInfo.lenses(listOf(uw, tele), 0.6, 10.0).isEmpty())
    }

    @Test
    fun lensesOutOfTheZoomRangeAreDropped() {
        // 望遠（3 倍）がズームの上端（2 倍）を超えている：その 1 本は数えない
        val l = LensInfo.lenses(listOf(uw, main, tele), zoomMin = 0.6, zoomMax = 2.0)
        assertEquals(listOf("2", "0"), l.map { it.id })
    }

    @Test
    fun nearDuplicatesAreMerged() {
        // 標準とほぼ同じ画角の補助のカメラ（id=5）：標準の席は標準レンズのまま
        val aux = LensInfo.Physical("5", 6.7f, 10.4f, 7.8f)
        val l = LensInfo.lenses(listOf(uw, aux, main, tele), 0.6, 10.0)
        assertEquals(listOf("2", "0", "3"), l.map { it.id })
    }

    @Test
    fun telephotoRatioIsSnappedOnlyWhenClose() {
        // 101mm 相当（101/23 ≒ 4.4）：きりのよい値（4・5）から遠いので、そのまま
        val tele101 = LensInfo.Physical("4", 20.2f, 6.92f, 5.19f)
        val l = LensInfo.lenses(listOf(main, tele101), 1.0, 10.0)
        assertEquals(4.4, l[1].ratio, 0.05)
        assertEquals("標準 x1・望遠 x${LensInfo.fmt(l[1].ratio)}", LensInfo.describe(l))
        // 85mm 相当（85/23 ≒ 3.7）：3.5 に近い（6% 以内）ので寄せる
        val tele85 = LensInfo.Physical("4", 17.0f, 6.92f, 5.19f)
        assertEquals(3.5, LensInfo.lenses(listOf(main, tele85), 1.0, 10.0)[1].ratio, 1e-9)
    }

    @Test
    fun zoomStateWithPredictedLens() {
        val l = LensInfo.lenses(listOf(uw, main, tele), 0.6, 10.0)
        // 端末が使っているレンズを教えてくれないとき：ズーム以下で一番大きい倍率のレンズとみなす
        var s = LensInfo.zoomState(l, 0.6, null)
        assertEquals("超広角", s.lens!!.name)
        assertEquals(true, s.optical)
        s = LensInfo.zoomState(l, 1.0, null)
        assertEquals("標準", s.lens!!.name)
        assertEquals(true, s.optical)
        s = LensInfo.zoomState(l, 2.0, null)
        assertEquals("標準", s.lens!!.name)
        assertEquals(false, s.optical)
        assertEquals(2.0, s.digital!!, 1e-9)
        s = LensInfo.zoomState(l, 3.0, null)
        assertEquals("望遠", s.lens!!.name)
        assertEquals(true, s.optical)
        s = LensInfo.zoomState(l, 5.0, null)
        assertEquals("望遠", s.lens!!.name)
        assertEquals(false, s.optical)
        assertEquals(1.7, s.digital!!, 1e-9)
        assertEquals("望遠・デジタル 1.7 倍", LensInfo.describe(s))
        assertEquals("望遠・光学", LensInfo.describe(LensInfo.zoomState(l, 3.0, null)))
    }

    @Test
    fun zoomStateWithActiveLens() {
        val l = LensInfo.lenses(listOf(uw, main, tele), 0.6, 10.0)
        // 暗くて望遠に切り替わらず、標準レンズのまま 3 倍：デジタル 3 倍
        var s = LensInfo.zoomState(l, 3.0, "0")
        assertEquals("標準", s.lens!!.name)
        assertEquals(false, s.optical)
        assertEquals(3.0, s.digital!!, 1e-9)
        // 見積もり（3 倍）より手前の 2.8 倍で、もう望遠を使っている：拡大なしとみなす
        s = LensInfo.zoomState(l, 2.8, "3")
        assertEquals("望遠", s.lens!!.name)
        assertEquals(true, s.optical)
        assertEquals(1.0, s.digital!!, 1e-9)
        // 一覧に無いレンズの番号は無視して、見積もりを使う
        s = LensInfo.zoomState(l, 5.0, "99")
        assertEquals("望遠", s.lens!!.name)
        assertFalse(s.optical!!)
    }

    @Test
    fun zoomStateWhenLensesAreUnknown() {
        // レンズが分からなくても、1 倍は標準レンズそのまま。ほかは分からない
        assertEquals(true, LensInfo.zoomState(emptyList(), 1.0, null).optical)
        assertNull(LensInfo.zoomState(emptyList(), 2.0, null).optical)
        assertNull(LensInfo.zoomState(emptyList(), 0.6, null).optical)
        assertNull(LensInfo.zoomState(emptyList(), 0.0, null).optical)
        assertEquals("標準・光学", LensInfo.describe(LensInfo.zoomState(emptyList(), 1.0, null)))
        assertEquals("不明", LensInfo.describe(LensInfo.zoomState(emptyList(), 2.0, null)))
    }

    @Test
    fun ratioFormatting() {
        assertEquals("0.6", LensInfo.fmt(0.6))
        assertEquals("1", LensInfo.fmt(1.0))
        assertEquals("3.5", LensInfo.fmt(3.5))
        assertEquals("10", LensInfo.fmt(10.0))
        assertEquals("3.72", LensInfo.fmt(3.72))
    }
}
