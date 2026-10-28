package com.ef64nagaoka.g2cam

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/** プレビュー用の 1 フレーム。seq は増えていく通し番号 */
class Frame(val seq: Long, val bytes: ByteArray)

/** 録画中の状態 */
class RecordingState(
    val name: String,
    /** SystemClock.elapsedRealtime() */
    val startedAt: Long,
    val audio: Boolean,
    /** 録画中もメガネへの配信（ImageAnalysis）が続いているか */
    val live: Boolean,
    /** 録画中も静止画を撮れるか */
    val capture: Boolean,
) {
    @Volatile var durationMs = 0L
    @Volatile var bytes = 0L
}

/** 録画の結果 */
class RecordResult(
    val name: String,
    val uri: Uri?,
    val durationMs: Long,
    val bytes: Long,
    val audio: Boolean,
    /** 録画開始時の最新フレーム（JPEG）。サムネイル用 */
    val thumbnail: ByteArray?,
    /** 保存できたが警告があるとき・保存できなかったときの説明 */
    val error: String?,
)

/** サーバーがカメラ側に求める操作。CameraService が実装する。 */
interface CameraController {
    val facing: String
    val frameWidth: Int
    val frameHeight: Int

    /** レンズのズーム範囲と現在の倍率（1.0 = 標準レンズ。1 未満は超広角、1 超は望遠・光学ズーム） */
    val zoomMin: Float
    val zoomMax: Float
    val zoom: Float

    /** まだ 1 枚もフレームを受け取っていないとき false */
    val hasFrame: Boolean

    /** 番号 afterSeq より新しいフレームを返す。無ければ最大 waitMs 待って、それでも無ければ null */
    fun nextFrame(afterSeq: Long, waitMs: Long): Frame?

    /** 今のズームのまま撮影して DCIM に保存し、JPEG のバイト列を返す（ブロッキング） */
    fun capture(): ByteArray

    /** レンズ側のズームを設定する。端末が複数レンズなら倍率に応じて超広角・標準・望遠が自動で切り替わる。実際の倍率を返す */
    fun setZoom(zoom: Double): Float

    /** カメラを切り替える。切り替えが済むまで待つ */
    fun setFacing(facing: String)

    /** G2 カメラ側から何か要求が来た（応答が途絶えたら自動で止める判断に使う） */
    fun touch()

    /** G2 カメラの終了に合わせて、このサービスを止める。止めるなら true（自動停止がオフなら false） */
    fun requestStop(): Boolean

    // ── 録画 ──
    /** 録音の権限があるか（無ければ無音で録画する） */
    val audioPermitted: Boolean
    /** カメラ構成・静止画の拡張・保存先・束ね直しの失敗理由（診断用） */
    val diag: String
    /** 録画中の状態。録画していなければ null */
    val recording: RecordingState?
    /** 録画を始める（ブロッキング。始まるまで待つ）。すでに録画中ならその状態を返す */
    fun startRecording(audio: Boolean, quality: String, maxMs: Long): RecordingState
    /** 録画を止めて保存する（ブロッキング）。録画していなければ null */
    fun stopRecording(): RecordResult?
    /** 向こう側の都合（最長時間・通知）で止まった録画の結果。一度返したら消える */
    fun takePendingResult(): RecordResult?
    /** 保存した動画を名前で開く（ストリームと大きさ）。無ければ null */
    fun openRecording(name: String): Pair<InputStream, Long>?
}

/**
 * G2 アプリ（Even アプリ内 WebView）に映像を渡すローカル HTTP サーバー。
 * 仕様は docs/companion-protocol.md。127.0.0.1 だけで待ち受ける。
 */
class CompanionServer(private val camera: CameraController, port: Int) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        camera.touch()
        val res = try {
            route(session)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
        }
        // ブラウザ（WebView）から別オリジンで呼ばれるので CORS を全部に付ける
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "*")
        res.addHeader("Access-Control-Expose-Headers", "X-Frame-Seq")
        // https のトンネル経由で開いた G2 アプリから 127.0.0.1 を呼ぶとき（Private Network Access）
        res.addHeader("Access-Control-Allow-Private-Network", "true")
        res.addHeader("Access-Control-Max-Age", "600")
        res.addHeader("Cache-Control", "no-store")
        return res
    }

    private fun route(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        }
        return when (session.uri) {
            "/status" -> json(Response.Status.OK, statusJson())

            "/frame" -> {
                val after = session.parameters["after"]?.firstOrNull()?.toLongOrNull() ?: 0L
                val frame = camera.nextFrame(after, 200)
                if (frame == null) {
                    // まだ 1 枚も無い → 503、新しいフレームが来なかっただけ → 204（クライアントはそのまま取り直す）
                    return if (camera.hasFrame) {
                        NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
                    } else {
                        json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("ok", false).put("error", "no frame"))
                    }
                }
                val res = NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(frame.bytes),
                    frame.bytes.size.toLong(),
                )
                res.addHeader("X-Frame-Seq", frame.seq.toString())
                res
            }

            "/capture" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val bytes = camera.capture()
                NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            }

            "/zoom" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val want = readBody(session).optDouble("zoom", 1.0)
                camera.setZoom(if (want.isNaN()) 1.0 else want)
                json(Response.Status.OK, statusJson())
            }

            "/stop" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                json(Response.Status.OK, JSONObject().put("ok", true).put("stopping", camera.requestStop()))
            }

            "/record/start" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val body = readBody(session)
                val audio = body.optBoolean("audio", true)
                val quality = body.optString("quality", "fhd")
                val maxMs = body.optLong("maxMs", 0L)
                camera.startRecording(audio, quality, maxMs)
                json(Response.Status.OK, statusJson())
            }

            "/record/stop" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val r = camera.stopRecording() ?: camera.takePendingResult()
                    ?: return json(Response.Status.OK, JSONObject().put("ok", true).put("recorded", false))
                val j = JSONObject()
                    .put("ok", true)
                    .put("recorded", r.uri != null)
                    .put("name", r.name)
                    .put("durationMs", r.durationMs)
                    .put("bytes", r.bytes)
                    .put("audio", r.audio)
                    .put("uri", r.uri?.toString() ?: JSONObject.NULL)
                    .put("error", r.error ?: JSONObject.NULL)
                r.thumbnail?.let { j.put("thumbnailBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
                json(Response.Status.OK, j)
            }

            "/file" -> {
                val name = session.parameters["name"]?.firstOrNull()
                    ?: return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "name がありません"))
                if (name.contains('/') || name.contains("..")) {
                    return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "不正な名前"))
                }
                val (stream, size) = camera.openRecording(name)
                    ?: return json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "見つかりません"))
                val mime = if (name.endsWith(".mp4", true)) "video/mp4" else "application/octet-stream"
                val res = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, stream, size)
                res.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                res
            }

            "/facing" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val want = readBody(session).optString("facing", "environment")
                camera.setFacing(want)
                json(Response.Status.OK, statusJson())
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
        }
    }

    private fun statusJson(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("app", "g2cam-companion")
            .put("version", "1.4")
            .put("diag", camera.diag)
            .put("facing", camera.facing)
            .put("width", camera.frameWidth)
            .put("height", camera.frameHeight)
            .put("zoom", camera.zoom.toDouble())
            .put("zoomMin", camera.zoomMin.toDouble())
            .put("zoomMax", camera.zoomMax.toDouble())
            .put("canRecord", true)
            .put("audioPermitted", camera.audioPermitted)
            .put("recording", camera.recording != null)
            .also { j ->
                camera.recording?.let { r ->
                    j.put("recordName", r.name)
                        .put("recordMs", r.durationMs)
                        .put("recordBytes", r.bytes)
                        .put("recordAudio", r.audio)
                        .put("recordLive", r.live)
                        .put("recordCapture", r.capture)
                }
            }

    /** POST の本文（text/plain の JSON）を読む。空や不正なら空のオブジェクト */
    private fun readBody(session: IHTTPSession): JSONObject {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val text = files["postData"]
            if (text.isNullOrBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun methodNotAllowed(): Response =
        json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("ok", false).put("error", "method not allowed"))

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())
}
