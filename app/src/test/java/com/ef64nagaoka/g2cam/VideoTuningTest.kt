package com.ef64nagaoka.g2cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 録画の細かさ（ビットレート）と fps（0.1.20） */
class VideoTuningTest {

    @Test
    fun levelNames() {
        assertEquals("std", VideoTuning.levelOf(null))
        assertEquals("std", VideoTuning.levelOf(""))
        assertEquals("std", VideoTuning.levelOf("ultra"))
        assertEquals("high", VideoTuning.levelOf("HIGH"))
        assertEquals("max", VideoTuning.levelOf("max"))
        assertEquals(1.0, VideoTuning.multiplier("std"), 1e-9)
        assertEquals(1.5, VideoTuning.multiplier("high"), 1e-9)
        assertEquals(2.0, VideoTuning.multiplier("max"), 1e-9)
    }

    @Test
    fun fpsNames() {
        assertEquals(30, VideoTuning.fpsOf(0))
        assertEquals(30, VideoTuning.fpsOf(30))
        assertEquals(30, VideoTuning.fpsOf(49))
        assertEquals(60, VideoTuning.fpsOf(50))
        assertEquals(60, VideoTuning.fpsOf(60))
        assertEquals(60, VideoTuning.fpsOf(120))
    }

    @Test
    fun targetBitrateScalesTheDeviceDefault() {
        // 標準は指定しない
        assertNull(VideoTuning.targetBitrate("std", 17_000_000, 30, 30))
        assertNull(VideoTuning.targetBitrate("std", 17_000_000, 30, 60))
        // 高 = 1.5 倍、最高 = 2 倍
        assertEquals(25_500_000, VideoTuning.targetBitrate("high", 17_000_000, 30, 30))
        assertEquals(34_000_000, VideoTuning.targetBitrate("max", 17_000_000, 30, 30))
        // 60fps は fps の分も比例させる（CameraX が標準で行うのと同じ）
        assertEquals(51_000_000, VideoTuning.targetBitrate("high", 17_000_000, 30, 60))
        // 端末の標準が 60fps のときの値なら、30fps では半分から
        assertEquals(30_000_000, VideoTuning.targetBitrate("high", 40_000_000, 60, 30))
        // 上限・下限
        assertEquals(VideoTuning.MAX_BPS, VideoTuning.targetBitrate("max", 80_000_000, 30, 60))
        assertEquals(VideoTuning.MIN_BPS, VideoTuning.targetBitrate("high", 100_000, 30, 30))
        // 標準が読めない（0）なら指定しない
        assertNull(VideoTuning.targetBitrate("max", 0, 30, 30))
        // fps が読めないときは比例させない
        assertEquals(25_500_000, VideoTuning.targetBitrate("high", 17_000_000, 0, 30))
    }

    @Test
    fun fallbackBitrateByResolution() {
        assertTrue(VideoTuning.fallbackBitrate(2160) > VideoTuning.fallbackBitrate(1080))
        assertTrue(VideoTuning.fallbackBitrate(1080) > VideoTuning.fallbackBitrate(720))
        assertTrue(VideoTuning.fallbackBitrate(720) > VideoTuning.fallbackBitrate(480))
    }

    private fun r(lo: Int, hi: Int) = LowLight.FpsRange(lo, hi)

    @Test
    fun supports60NeedsARangeUpToSixty() {
        assertTrue(VideoTuning.supports60(listOf(r(15, 30), r(30, 30), r(60, 60))))
        assertTrue(VideoTuning.supports60(listOf(r(30, 60))))
        assertFalse(VideoTuning.supports60(listOf(r(15, 30), r(30, 30))))
        assertFalse(VideoTuning.supports60(emptyList()))
    }

    @Test
    fun plans60KeepStabilization() {
        // 補正オン：電子式の補正つきだけ（強い順）。補正なしは試さない（利用者の選択：手ブレ補正を優先）
        assertEquals(listOf(2, 1), VideoTuning.plans60(stabOn = true, strongest = 2, hasNormal = true))
        assertEquals(listOf(2), VideoTuning.plans60(stabOn = true, strongest = 2, hasNormal = false))
        assertEquals(listOf(1), VideoTuning.plans60(stabOn = true, strongest = 1, hasNormal = true))
        // 端末に電子式の補正が無い：補正なしで試す（失うものが無い）
        assertEquals(listOf(0), VideoTuning.plans60(stabOn = true, strongest = null, hasNormal = false))
        // 補正オフの設定：補正なしだけ
        assertEquals(listOf(0), VideoTuning.plans60(stabOn = false, strongest = 2, hasNormal = true))
    }

    @Test
    fun fpsOkAllowsALittleSlack() {
        assertTrue(VideoTuning.fpsOk(59.8, 60))
        assertTrue(VideoTuning.fpsOk(51.0, 60))
        assertFalse(VideoTuning.fpsOk(50.9, 60))
        assertFalse(VideoTuning.fpsOk(30.0, 60))
        assertFalse(VideoTuning.fpsOk(0.0, 60))
    }

    @Test
    fun mbps() {
        // 60 秒で 127.5MB → 17Mbps
        assertEquals(17.0, VideoTuning.mbpsOf(127_500_000L, 60_000L), 1e-9)
        assertEquals(0.0, VideoTuning.mbpsOf(0L, 60_000L), 1e-9)
        assertEquals(0.0, VideoTuning.mbpsOf(1000L, 0L), 1e-9)
        assertEquals("48Mbps", VideoTuning.mbpsLabel(48_200_000L))
        assertEquals("8.5Mbps", VideoTuning.mbpsLabel(8_500_000L))
    }
}
