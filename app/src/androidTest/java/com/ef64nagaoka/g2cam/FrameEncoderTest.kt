package com.ef64nagaoka.g2cam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * メガネ用 JPEG の変換（FrameEncoder）の確認と、以前の作り（毎フレーム Bitmap を 2 枚作る）との速さ比べ。
 * 結果は logcat の G2CamBench に出す（CI では test.log に写す）。エミュレーターなので絶対値ではなく比率を見る。
 */
@RunWith(AndroidJUnit4::class)
class FrameEncoderTest {

    private fun fakeFrame(width: Int, height: Int, n: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buf.put(((x + n) and 0xFF).toByte())
                buf.put(((y + n) and 0xFF).toByte())
                buf.put(((x xor y) and 0xFF).toByte())
                buf.put(0xFF.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    /** 以前の作り：toBitmap（新しい Bitmap）→ createBitmap（回転・縮小でもう 1 枚）→ JPEG */
    private fun legacyEncode(width: Int, height: Int, rgba: ByteBuffer, rotation: Int): ByteArray {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rgba.rewind()
        src.copyPixelsFromBuffer(rgba)
        val scale = 320f / maxOf(width, height)
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        m.postScale(scale, scale)
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val stream = ByteArrayOutputStream(24 * 1024)
        out.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        if (out !== src) out.recycle()
        src.recycle()
        return stream.toByteArray()
    }

    @Test
    fun producesScaledRotatedJpeg() {
        val enc = FrameEncoder()
        val frame = fakeFrame(640, 480, 0)
        val jpeg = enc.encode(640, 480, frame, 640 * 4, 4, 0)
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull(bmp)
        assertEquals(320, bmp.width)
        assertEquals(240, bmp.height)
        assertEquals(320, enc.outWidth)

        val rotated = enc.encode(640, 480, fakeFrame(640, 480, 7), 640 * 4, 4, 90)
        val b2 = BitmapFactory.decodeByteArray(rotated, 0, rotated.size)
        assertEquals(240, b2.width)
        assertEquals(320, b2.height)
        // 回転で左上に来るはずの画素（元の左下 = 赤 0, 緑 479+7）を確かめる：赤がほぼ 0、緑が大きい
        val px = b2.getPixel(2, 2)
        assertTrue("rotated pixel $px", ((px shr 16) and 0xFF) < 40 && ((px shr 8) and 0xFF) > 150)
        enc.release()
    }

    @Test
    fun cropsCenterForDualView() {
        // 2 画面：同じフレームから全体（左）と中央 1/4 の切り出し（右）を作る。どちらも長辺 320
        val enc = FrameEncoder()
        val w = 1280
        val h = 960
        enc.load(w, h, fakeFrame(w, h, 0), w * 4, 4)
        val fullBytes = enc.render(0, 1f, 320)
        val full = BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size)
        val cropBytes = enc.render(0, 4f, 320)
        val crop = BitmapFactory.decodeByteArray(cropBytes, 0, cropBytes.size)
        assertEquals(320, full.width)
        assertEquals(240, full.height)
        assertEquals(320, crop.width)
        assertEquals(240, crop.height)
        // 切り出しの左上は元の (480, 360) あたり：赤 = 480 and 255 = 224、緑 = 360 and 255 = 104
        val px = crop.getPixel(1, 1)
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        assertTrue("crop pixel r=$r g=$g", kotlin.math.abs(r - 224) < 30 && kotlin.math.abs(g - 104) < 30)
        // 回転つきの切り出しも縦長になる
        val rotBytes = enc.render(90, 2f, 320)
        val rb = BitmapFactory.decodeByteArray(rotBytes, 0, rotBytes.size)
        assertEquals(240, rb.width)
        assertEquals(320, rb.height)
        enc.release()
    }

    @Test
    fun handlesRowPadding() {
        // 行の間に 64 バイトの余白がある画像でも同じ絵になる
        val w = 320
        val h = 240
        val stride = w * 4 + 64
        val padded = ByteBuffer.allocateDirect(stride * h)
        val tight = fakeFrame(w, h, 3)
        for (y in 0 until h) {
            tight.position(y * w * 4)
            val row = ByteArray(w * 4)
            tight.get(row)
            padded.position(y * stride)
            padded.put(row)
        }
        padded.rewind()
        val enc = FrameEncoder()
        val a = enc.encode(w, h, padded, stride, 4, 0)
        val b = enc.encode(w, h, tight, w * 4, 4, 0)
        val ba = BitmapFactory.decodeByteArray(a, 0, a.size)
        val bb = BitmapFactory.decodeByteArray(b, 0, b.size)
        var diff = 0L
        for (y in 0 until bb.height step 7) for (x in 0 until bb.width step 7) {
            val p = ba.getPixel(x, y)
            val q = bb.getPixel(x, y)
            diff += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) + Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
        }
        assertTrue("padding diff $diff", diff < 2000)
        enc.release()
    }

    @Test
    fun benchmarkAgainstLegacy() {
        val w = 640
        val h = 480
        val frames = (0 until 8).map { fakeFrame(w, h, it * 13) }
        val enc = FrameEncoder()
        // 温める
        repeat(10) { enc.encode(w, h, frames[it % frames.size], w * 4, 4, 90) }
        repeat(10) { legacyEncode(w, h, frames[it % frames.size], 90) }
        val n = 120
        val t0 = SystemClock.elapsedRealtimeNanos()
        var bytesNew = 0L
        for (i in 0 until n) bytesNew += enc.encode(w, h, frames[i % frames.size], w * 4, 4, 90).size
        val t1 = SystemClock.elapsedRealtimeNanos()
        var bytesOld = 0L
        for (i in 0 until n) bytesOld += legacyEncode(w, h, frames[i % frames.size], 90).size
        val t2 = SystemClock.elapsedRealtimeNanos()
        val msNew = (t1 - t0) / 1e6 / n
        val msOld = (t2 - t1) / 1e6 / n
        Log.i("G2CamBench", String.format("frame encode 640x480->320 rot90: new %.2f ms/frame (%d B avg), legacy %.2f ms/frame (%d B avg)", msNew, bytesNew / n, msOld, bytesOld / n))
        // 速さが同じか速いこと（割り当てが無い分、GC の手間は別途減る）
        assertTrue("new $msNew ms vs legacy $msOld ms", msNew <= msOld * 1.3)
        enc.release()
    }
}
