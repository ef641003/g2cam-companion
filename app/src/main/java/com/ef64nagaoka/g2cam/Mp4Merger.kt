package com.ef64nagaoka.g2cam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * 遡り録画の「切れ端」と本番の録画を、再エンコードせずに 1 本の MP4 につなぐ。
 * 同じ録画器（同じ画質・同じ設定）で録ったファイル同士だけを対象にする。
 * 形式が違う・読めないときは何もせず失敗を返す（呼び出し側が材料を別ファイルのまま残す）。
 */
object Mp4Merger {
    private const val TAG = "G2Cam"
    /** つなぎ目に空ける間（30fps の 1 コマぶん） */
    private const val GAP_US = 33_333L
    /** 1 サンプルの最大の大きさ（4K のキーフレームでも収まるように） */
    private const val BUFFER_BYTES = 16 * 1024 * 1024

    /** つなぐ材料。startUs はそのファイルの頭からどこを使い始めるか（実際にはその直前のキーフレームから使う） */
    class Part(val file: File, val startUs: Long)

    class Result(val ok: Boolean, val durationUs: Long, val message: String)

    private class TrackInfo(
        val videoIndex: Int,
        val videoFormat: MediaFormat,
        val audioIndex: Int,
        val audioFormat: MediaFormat?,
        val rotation: Int,
    )

    private fun probe(file: File): TrackInfo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            var vi = -1
            var vf: MediaFormat? = null
            var ai = -1
            var af: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (vi < 0 && mime.startsWith("video/")) {
                    vi = i
                    vf = f
                } else if (ai < 0 && mime.startsWith("audio/")) {
                    ai = i
                    af = f
                }
            }
            val video = vf ?: return null
            val rot = if (video.containsKey(MediaFormat.KEY_ROTATION)) video.getInteger(MediaFormat.KEY_ROTATION) else 0
            return TrackInfo(vi, video, ai, af, rot)
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${file.name}", e)
            return null
        } finally {
            ex.release()
        }
    }

    private fun bytesOf(f: MediaFormat, key: String): ByteArray? {
        if (!f.containsKey(key)) return null
        val b = f.getByteBuffer(key) ?: return null
        val d = b.duplicate()
        d.position(0)
        val out = ByteArray(d.remaining())
        d.get(out)
        return out
    }

    private fun intOf(f: MediaFormat, key: String): Int = if (f.containsKey(key)) f.getInteger(key) else -1

    /** つなげる組み合わせか。だめなら理由、よければ null */
    private fun incompatibility(a: TrackInfo, b: TrackInfo): String? {
        val av = a.videoFormat
        val bv = b.videoFormat
        if (av.getString(MediaFormat.KEY_MIME) != bv.getString(MediaFormat.KEY_MIME)) return "映像の形式が違います"
        if (intOf(av, MediaFormat.KEY_WIDTH) != intOf(bv, MediaFormat.KEY_WIDTH) ||
            intOf(av, MediaFormat.KEY_HEIGHT) != intOf(bv, MediaFormat.KEY_HEIGHT)
        ) return "映像の大きさが違います"
        if (a.rotation != b.rotation) return "映像の向きが違います"
        for (k in listOf("csd-0", "csd-1", "csd-2")) {
            if (!(bytesOf(av, k) contentEquals bytesOf(bv, k))) return "映像の符号化の設定が違います（$k）"
        }
        val aa = a.audioFormat
        val ba = b.audioFormat
        if ((aa == null) != (ba == null)) return "音声の有無が違います"
        if (aa != null && ba != null) {
            if (aa.getString(MediaFormat.KEY_MIME) != ba.getString(MediaFormat.KEY_MIME) ||
                intOf(aa, MediaFormat.KEY_SAMPLE_RATE) != intOf(ba, MediaFormat.KEY_SAMPLE_RATE) ||
                intOf(aa, MediaFormat.KEY_CHANNEL_COUNT) != intOf(ba, MediaFormat.KEY_CHANNEL_COUNT) ||
                !(bytesOf(aa, "csd-0") contentEquals bytesOf(ba, "csd-0"))
            ) return "音声の形式が違います"
        }
        return null
    }

    /**
     * parts を順につないで out（MP4）に書く。out は書き込み・シークできること（"rw" で開く）。
     * 映像と音声は時刻の順に交互に書く。2 本目以降は、前の材料の最後の直後から始まるよう時刻をずらす。
     */
    fun merge(parts: List<Part>, out: FileDescriptor): Result {
        if (parts.isEmpty()) return Result(false, 0, "材料がありません")
        val infos = ArrayList<TrackInfo>()
        for (p in parts) infos.add(probe(p.file) ?: return Result(false, 0, "読めない材料があります（${p.file.name}）"))
        for (i in 1 until infos.size) incompatibility(infos[0], infos[i])?.let { return Result(false, 0, it) }
        val first = infos[0]
        val muxer = try {
            MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            return Result(false, 0, "書き出し先を開けません: ${e.message}")
        }
        try {
            if (first.rotation != 0) muxer.setOrientationHint(first.rotation)
            val vOut = muxer.addTrack(first.videoFormat)
            val aOut = first.audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            val buf = ByteBuffer.allocateDirect(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L
            var endUs = 0L
            var videoSamples = 0
            var lastAudioUs = -1L
            for ((idx, part) in parts.withIndex()) {
                val ti = infos[idx]
                val vEx = MediaExtractor()
                var aEx: MediaExtractor? = null
                try {
                    vEx.setDataSource(part.file.path)
                    vEx.selectTrack(ti.videoIndex)
                    if (aOut >= 0 && ti.audioIndex >= 0) {
                        aEx = MediaExtractor().apply {
                            setDataSource(part.file.path)
                            selectTrack(ti.audioIndex)
                        }
                    }
                    val firstUs = vEx.sampleTime
                    if (firstUs < 0) continue
                    if (part.startUs > 0) vEx.seekTo(firstUs + part.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val baseUs = vEx.sampleTime
                    if (baseUs < 0) continue
                    aEx?.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var partEndUs = -1L
                    while (true) {
                        var at = aEx?.sampleTime ?: -1L
                        // 映像の始まりより前の音声は捨てる
                        while (aEx != null && at >= 0 && at < baseUs) {
                            aEx.advance()
                            at = aEx.sampleTime
                        }
                        val vt = vEx.sampleTime
                        if (vt < 0 && at < 0) break
                        val useVideo = at < 0 || (vt in 0..at)
                        val ex = if (useVideo) vEx else aEx!!
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            ex.advance()
                            continue
                        }
                        val t = ex.sampleTime - baseUs + offsetUs
                        if (!useVideo && t <= lastAudioUs) {
                            // 音声は時刻が前に進まないサンプルを書けない（つなぎ目の重なり）
                            ex.advance()
                            continue
                        }
                        val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, t, flags)
                        muxer.writeSampleData(if (useVideo) vOut else aOut, buf, info)
                        if (useVideo) videoSamples++ else lastAudioUs = t
                        if (t > partEndUs) partEndUs = t
                        ex.advance()
                    }
                    if (partEndUs >= 0) {
                        endUs = partEndUs
                        offsetUs = partEndUs + GAP_US
                    }
                } finally {
                    vEx.release()
                    aEx?.release()
                }
            }
            muxer.stop()
            return if (videoSamples > 0) Result(true, endUs, "") else Result(false, 0, "映像のサンプルがありません")
        } catch (e: Exception) {
            Log.w(TAG, "merge failed", e)
            return Result(false, 0, "つなぎ合わせに失敗: ${e.message}")
        } finally {
            try {
                muxer.release()
            } catch (e: Exception) {
                Log.w(TAG, "muxer release failed", e)
            }
        }
    }

    /** できた動画を読み直して、映像があり、長さが期待の半分以上あるかを確かめる */
    fun validate(context: Context, uri: Uri, expectUs: Long): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var ok = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") != true) continue
                val d = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else -1L
                ex.selectTrack(i)
                ok = (d < 0 || d >= expectUs / 2) && ex.sampleTime >= 0
                break
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "validate failed", e)
            false
        } finally {
            ex.release()
        }
    }
}

/**
 * 遡り録画で、どの材料のどこから使うかを決める（純粋な計算。試験しやすいよう分けてある）。
 * 時刻はどれも SystemClock.elapsedRealtime() のミリ秒。
 */
object PreRollPlan {
    class Prev(val durationMs: Long, val endedAt: Long)

    /** prevUse=true なら前の切れ端の prevFromMs から、続けて今の切れ端を curFromMs から使う */
    class Plan(val prevUse: Boolean, val prevFromMs: Long, val curFromMs: Long, val preRollMs: Long)

    /** 前の切れ端と今の切れ端の間がこれより空いていたら、つながっていないとみなす */
    const val MAX_GAP_MS = 3_000L

    /**
     * @param tapAt 録画を始めた（タップした）時刻
     * @param curStartedAt 今の切れ端が録り始めた時刻
     * @param wantMs 欲しい遡りの長さ
     * @param prev 一つ前の切れ端（無ければ null）
     */
    fun plan(tapAt: Long, curStartedAt: Long, wantMs: Long, prev: Prev?): Plan {
        val inCur = (tapAt - curStartedAt).coerceAtLeast(0)
        val usablePrev = prev != null && curStartedAt - prev.endedAt in 0..MAX_GAP_MS && prev.durationMs > 0
        if (inCur >= wantMs || !usablePrev) {
            val from = (inCur - wantMs).coerceAtLeast(0)
            return Plan(false, 0, from, inCur - from)
        }
        val need = wantMs - inCur
        val from = (prev!!.durationMs - need).coerceAtLeast(0)
        return Plan(true, from, 0, inCur + (prev.durationMs - from))
    }
}
