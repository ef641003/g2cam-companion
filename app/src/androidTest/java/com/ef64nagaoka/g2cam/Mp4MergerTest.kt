package com.ef64nagaoka.g2cam

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 遡り録画のつなぎ合わせ（Mp4Merger）を、端末（エミュレーター）の符号化器で作った本物の MP4 で確かめる。
 * 映像は H.264（1 秒ごとにキーフレーム・30fps）、音声は AAC。CameraX の録画と同じ形。
 */
@RunWith(AndroidJUnit4::class)
class Mp4MergerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "mergetest").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ── 材料づくり ──
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class Encoded(val format: MediaFormat, val samples: List<Sample>)

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, out: ArrayList<Sample>, onFormat: (MediaFormat) -> Unit): Boolean {
        val o = codec.dequeueOutputBuffer(info, 10_000)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            onFormat(codec.outputFormat)
        } else if (o >= 0) {
            val buf = codec.getOutputBuffer(o)!!
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                out.add(Sample(bytes, info.presentationTimeUs, info.flags))
            }
            codec.releaseOutputBuffer(o, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
        return false
    }

    private fun fillYuv(img: Image, n: Int) {
        val w = img.width
        val h = img.height
        for (p in 0 until 3) {
            val plane = img.planes[p]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val pw = if (p == 0) w else w / 2
            val ph = if (p == 0) h else h / 2
            for (y in 0 until ph) {
                for (x in 0 until pw) {
                    val v = if (p == 0) ((x + y + n * 4) and 0xFF) else 128
                    buf.put(y * rs + x * ps, v.toByte())
                }
            }
        }
    }

    private fun encodeVideo(width: Int, height: Int, seconds: Int): Encoded {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val fps = 30
        val frames = seconds * fps
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var n = 0
        var inputDone = false
        var done = false
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val pts = n * 1_000_000L / fps
                    if (n >= frames) {
                        codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val cap = codec.getInputBuffer(i)!!.capacity()
                        fillYuv(codec.getInputImage(i)!!, n)
                        codec.queueInputBuffer(i, 0, min(width * height * 3 / 2, cap), pts, 0)
                        n++
                    }
                }
            }
            done = drain(codec, info, out) { outFormat = it }
        }
        codec.stop()
        codec.release()
        return Encoded(outFormat ?: error("映像の形式が出てきません"), out)
    }

    private fun encodeAudio(seconds: Int): Encoded {
        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val rate = 44100
        val format = MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val total = seconds.toLong() * rate
        var fed = 0L
        val out = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var done = false
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val pts = fed * 1_000_000L / rate
                    if (fed >= total) {
                        codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val count = min(1024L, total - fed).toInt()
                        for (k in 0 until count) {
                            val v = (sin(2 * PI * 440.0 * (fed + k) / rate) * 8000).toInt()
                            buf.put((v and 0xFF).toByte())
                            buf.put(((v shr 8) and 0xFF).toByte())
                        }
                        codec.queueInputBuffer(i, 0, count * 2, pts, 0)
                        fed += count
                    }
                }
            }
            done = drain(codec, info, out) { outFormat = it }
        }
        codec.stop()
        codec.release()
        return Encoded(outFormat ?: error("音声の形式が出てきません"), out)
    }

    /** 映像と音声を時刻の順に交互に書いて MP4 にする（映像はそれぞれの順番を崩さない） */
    private fun writeMp4(file: File, video: Encoded, audio: Encoded?) {
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vt = muxer.addTrack(video.format)
        val at = if (audio != null) muxer.addTrack(audio.format) else -1
        muxer.start()
        val info = MediaCodec.BufferInfo()
        var vi = 0
        var ai = 0
        val vs = video.samples
        val aSamples = audio?.samples ?: emptyList()
        while (vi < vs.size || ai < aSamples.size) {
            val useVideo = ai >= aSamples.size || (vi < vs.size && vs[vi].ptsUs <= aSamples[ai].ptsUs)
            val s = if (useVideo) vs[vi++] else aSamples[ai++]
            val flags = if ((s.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, s.data.size, s.ptsUs, flags)
            muxer.writeSampleData(if (useVideo) vt else at, ByteBuffer.wrap(s.data), info)
        }
        muxer.stop()
        muxer.release()
    }

    /**
     * 切れ端に見立てた MP4 を作る。1 本ごとに符号化器を作り直す（CameraX も録画ごとに符号化器を構成し直すので、
     * 同じ設定なら同じ SPS/PPS になり、つなげることも確かめる）
     */
    private fun make(name: String, seconds: Int, width: Int = 320, height: Int = 240, audio: Boolean = true): File {
        val v = encodeVideo(width, height, seconds)
        val a = if (audio) encodeAudio(seconds) else null
        val f = File(dir, name)
        writeMp4(f, v, a)
        return f
    }

    // ── できた動画を調べる ──
    private class Stats(
        val videoSamples: Int,
        val videoDurationUs: Long,
        val videoMonotonic: Boolean,
        val firstVideoSync: Boolean,
        val audioSamples: Int,
        val audioMonotonic: Boolean,
    )

    private fun stats(file: File): Stats {
        fun scan(prefix: String): Triple<Int, Long, Pair<Boolean, Boolean>>? {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.path)
                val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
                    ?: return null
                ex.selectTrack(ti)
                var count = 0
                var first = -1L
                var last = -1L
                var monotonic = true
                var firstSync = false
                while (ex.sampleTime >= 0) {
                    val t = ex.sampleTime
                    if (count == 0) {
                        first = t
                        firstSync = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    } else if (t <= last) {
                        monotonic = false
                    }
                    last = t
                    count++
                    ex.advance()
                }
                return Triple(count, if (count > 0) last - first else 0L, monotonic to firstSync)
            } finally {
                ex.release()
            }
        }
        val v = scan("video/") ?: error("映像がありません")
        val a = scan("audio/")
        return Stats(v.first, v.second, v.third.first, v.third.second, a?.first ?: 0, a?.third?.first ?: true)
    }

    /** 映像を最後まで復号して、出てきたコマ数を返す（つなぎ目で壊れていないかの確認） */
    private fun decodeCount(file: File): Int {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var frames = 0
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!outDone && SystemClock.elapsedRealtime() < deadline) {
            if (!inDone) {
                val i = dec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = dec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = dec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                if (info.size > 0) frames++
                dec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
            }
        }
        dec.stop()
        dec.release()
        ex.release()
        return frames
    }

    private fun merge(out: File, vararg parts: Mp4Merger.Part): Mp4Merger.Result =
        RandomAccessFile(out, "rw").use { raf -> Mp4Merger.merge(parts.toList(), raf.fd) }

    // ── 試験 ──
    @Test
    fun joinsPreviousTailAndCurrentChunk() {
        val prev = make("prev.mp4", 6)
        val cur = make("cur.mp4", 6)
        val out = File(dir, "out.mp4")
        // 前の切れ端の 3 秒目から（キーフレームが 1 秒ごとなので、ちょうど 3 秒目から）＋ 今の切れ端の全部
        val r = merge(out, Mp4Merger.Part(prev, 3_000_000L), Mp4Merger.Part(cur, 0L))
        assertTrue(r.message, r.ok)
        val s = stats(out)
        // キーフレームが 3 秒目ちょうどなら 9 秒・270 コマ。符号化器によって 1 つ前（2 秒目）になっても可
        assertTrue("映像の長さ ${s.videoDurationUs}", s.videoDurationUs in 8_500_000L..10_100_000L)
        assertTrue("映像のコマ数 ${s.videoSamples}", s.videoSamples in 260..305)
        assertTrue("映像の時刻が前に進んでいない", s.videoMonotonic)
        assertTrue("頭がキーフレームでない", s.firstVideoSync)
        assertTrue("音声のサンプル数 ${s.audioSamples}", s.audioSamples > 300)
        assertTrue("音声の時刻が前に進んでいない", s.audioMonotonic)
        val decoded = decodeCount(out)
        assertTrue("復号できたコマ数 $decoded / ${s.videoSamples}", decoded >= s.videoSamples - 2)
        assertTrue(Mp4Merger.validate(ctx, Uri.fromFile(out), 9_000_000L))
    }

    @Test
    fun cutsTheHeadOfASingleChunk() {
        val cur = make("cur.mp4", 6)
        val out = File(dir, "out.mp4")
        // 2.5 秒目から → 直前のキーフレーム（2 秒目）から使う
        val r = merge(out, Mp4Merger.Part(cur, 2_500_000L))
        assertTrue(r.message, r.ok)
        val s = stats(out)
        // 2 秒目のキーフレームからなら 4 秒弱。少なくとも頭は切れていること
        assertTrue("映像の長さ ${s.videoDurationUs}", s.videoDurationUs in 3_400_000L..5_500_000L)
        assertTrue(s.firstVideoSync)
        assertTrue(s.audioMonotonic)
        assertTrue(decodeCount(out) >= s.videoSamples - 2)
    }

    @Test
    fun keepsVideoOnlyFiles() {
        val prev = make("prev.mp4", 6, audio = false)
        val cur = make("cur.mp4", 6, audio = false)
        val out = File(dir, "out.mp4")
        val r = merge(out, Mp4Merger.Part(prev, 4_000_000L), Mp4Merger.Part(cur, 0L))
        assertTrue(r.message, r.ok)
        val s = stats(out)
        assertEquals(0, s.audioSamples)
        assertTrue("映像の長さ ${s.videoDurationUs}", s.videoDurationUs in 7_500_000L..9_100_000L)
    }

    @Test
    fun refusesDifferentSizes() {
        val a = make("a.mp4", 2)
        val b = make("b.mp4", 2, width = 192, height = 144)
        val r = merge(File(dir, "out.mp4"), Mp4Merger.Part(a, 0L), Mp4Merger.Part(b, 0L))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("大きさ"))
    }

    @Test
    fun refusesAudioMismatch() {
        val a = make("a.mp4", 2)
        val b = make("b.mp4", 2, audio = false)
        val r = merge(File(dir, "out.mp4"), Mp4Merger.Part(a, 0L), Mp4Merger.Part(b, 0L))
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("音声"))
    }

    @Test
    fun refusesUnreadableParts() {
        val bad = File(dir, "bad.mp4").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val r = merge(File(dir, "out.mp4"), Mp4Merger.Part(bad, 0L))
        assertFalse(r.ok)
        assertFalse(Mp4Merger.validate(ctx, Uri.fromFile(bad), 1_000_000L))
    }

    @Test
    fun validateRejectsTooShort() {
        val cur = make("cur.mp4", 2)
        assertTrue(Mp4Merger.validate(ctx, Uri.fromFile(cur), 2_000_000L))
        // 期待の半分にも満たない
        assertFalse(Mp4Merger.validate(ctx, Uri.fromFile(cur), 10_000_000L))
    }
}
