package com.ef64nagaoka.g2cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 解析フレーム（RGBA）→ メガネ用の小さな JPEG（長辺 maxLong px）。
 * Bitmap と出力の入れ物を使い回して、フレームごとの割り当てを無くす
 * （以前は 1 フレームごとに Bitmap を 2 枚作っていて、30fps だと毎秒 40MB ほどのごみが出て GC が走り続けていた）。
 * 1 つのスレッドからだけ使うこと。
 */
class FrameEncoder(private val maxLong: Int = 320, private val quality: Int = 70) {
    private var src: Bitmap? = null
    private var out: Bitmap? = null
    private var tight: ByteBuffer? = null
    private val stream = ByteArrayOutputStream(32 * 1024)
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 直近の出力の大きさ */
    var outWidth = 0
        private set
    var outHeight = 0
        private set

    /**
     * @param rgba 画素（R,G,B,A の順で 1 画素 4 バイト。CameraX の OUTPUT_IMAGE_FORMAT_RGBA_8888）
     * @param rowStride 1 行のバイト数（行の間に余白がある端末では width*4 より大きい）
     * @param rotationDegrees 端末の向きに合わせて回す角度（0/90/180/270）
     */
    fun encode(width: Int, height: Int, rgba: ByteBuffer, rowStride: Int, pixelStride: Int, rotationDegrees: Int): ByteArray {
        val s = src?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                src?.recycle()
                src = it
            }
        rgba.rewind()
        if (pixelStride == 4 && rowStride == width * 4) {
            s.copyPixelsFromBuffer(rgba)
        } else {
            // 行ごとに詰め直してから写す
            val need = width * height * 4
            val t = tight?.takeIf { it.capacity() == need } ?: ByteBuffer.allocateDirect(need).also { tight = it }
            t.clear()
            val row = ByteArray(width * 4)
            for (y in 0 until height) {
                rgba.position(y * rowStride)
                if (pixelStride == 4) {
                    rgba.get(row, 0, row.size)
                } else {
                    for (x in 0 until width) {
                        val p = y * rowStride + x * pixelStride
                        row[x * 4] = rgba.get(p)
                        row[x * 4 + 1] = rgba.get(p + 1)
                        row[x * 4 + 2] = rgba.get(p + 2)
                        row[x * 4 + 3] = rgba.get(p + 3)
                    }
                }
                t.put(row)
            }
            t.rewind()
            s.copyPixelsFromBuffer(t)
        }

        val rot = ((rotationDegrees % 360) + 360) % 360
        val swap = rot == 90 || rot == 270
        val scale = maxLong.toFloat() / max(width, height)
        val ow = max(1, ((if (swap) height else width) * scale).roundToInt())
        val oh = max(1, ((if (swap) width else height) * scale).roundToInt())
        val o = out?.takeIf { it.width == ow && it.height == oh }
            ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also {
                out?.recycle()
                out = it
            }
        matrix.reset()
        matrix.postTranslate(-width / 2f, -height / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(ow / 2f, oh / 2f)
        Canvas(o).drawBitmap(s, matrix, paint)

        stream.reset()
        o.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        outWidth = ow
        outHeight = oh
        return stream.toByteArray()
    }

    fun release() {
        src?.recycle()
        out?.recycle()
        src = null
        out = null
        tight = null
    }
}
