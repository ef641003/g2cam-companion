package com.ef64nagaoka.g2cam

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream

/** プレビュー用の 1 フレーム。seq は増えていく通し番号 */
class Frame(val seq: Long, val bytes: ByteArray)

/** いまは応じられない（書き出し中など）。503 で返し、G2 アプリにあとでやり直してもらう */
class BusyException(message: String) : Exception(message)

/** 録画中の状態（/status 用の写し） */
class RecordingState(
    /** いま書いているファイルの名前 */
    val name: String,
    /** 録画を始めて（タップして）からの経過。遡りの分は含まない */
    val elapsedMs: Long,
    /** これまでに書いた量（区切ったファイルの分も含む） */
    val bytes: Long,
    val audio: Boolean,
    /** 録画中もメガネへの配信（ImageAnalysis）が続いているか */
    val live: Boolean,
    /** 録画中も静止画を撮れるか */
    val capture: Boolean,
    /** 実際に録っている解像度（例 3840x2160。分からなければ空） */
    val resolution: String,
    /** 区切り録画の何本目か（1 から） */
    val segment: Int,
    /** 1 本目に含める遡りの長さ */
    val preRollMs: Long,
    /** いまの書き込みの速さ（バイト/秒。分からなければ 0） */
    val bytesPerSec: Long,
)

/** 録画の結果 */
data class RecordResult(
    val name: String,
    val uri: Uri?,
    val durationMs: Long,
    val bytes: Long,
    val audio: Boolean,
    /** 録画開始時の最新フレーム（JPEG）。サムネイル用 */
    val thumbnail: ByteArray?,
    /** 保存できたが警告があるとき・保存できなかったときの説明 */
    val error: String?,
    /** 録った解像度（例 3840x2160。分からなければ空） */
    val resolution: String = "",
    /** 含めた遡りの長さ */
    val preRollMs: Long = 0,
    /** 書き出し中（遡り録画をつないでいる最中）。名前は決まっていて、済めば /file で取れる */
    val pending: Boolean = false,
    /** 区切り録画の何本目か */
    val segment: Int = 1,
)

/** /record/start の頼み方 */
class StartOptions(
    val audio: Boolean,
    val quality: String,
    /** 1 本の最長（ミリ秒）。0 で無制限 */
    val maxMs: Long,
    /** 録画中も静止画を撮れるようにするか（G2 カメラは true、G2 録画は false） */
    val still: Boolean,
    /** 最長に達したら止めずに次のファイルへ続ける */
    val continuous: Boolean,
    /** この温度（℃）以上になったら保存して止める。0 で止めない */
    val stopTempC: Double,
    /** 空き容量がこれを切ったら保存して止める（バイト） */
    val minFreeBytes: Long,
)

/** このアプリ自身の負荷と配信の様子（/status 用。adb が使えない端末で原因を見るため） */
class LoadInfo(
    /** このアプリの CPU（1 コアの %。計測前は -1） */
    val cpuPct: Int,
    /** 直近 5 分の平均 */
    val cpuAvgPct: Int,
    /** カメラから届いているフレーム数（fps。実測） */
    val cameraFps: Int,
    /** メガネ用に変換した枚数（fps） */
    val producedFps: Int,
    /** G2 アプリに渡した枚数（fps） */
    val servedFps: Int,
    /** 配信の上限 fps（設定。熱いときは半分） */
    val streamFpsCap: Int,
    /** いま束ねている静止画の拡張（なし / オート / HDR） */
    val extension: String,
)

/** 遡り録画の状態（/status 用） */
class PreRollInfo(
    /** コンパニオンの画面でオンにしているか */
    val enabled: Boolean,
    val seconds: Int,
    /** off / idle（G2 録画の準備待ち）/ buffering / paused-heat / paused-space / error */
    val state: String,
    /** いま録画を始めたら含められる遡りの長さ */
    val readyMs: Long,
)

/** サーバーがカメラ側に求める操作。CameraService が実装する。 */
interface CameraController {
    val facing: String
    val frameWidth: Int
    val frameHeight: Int
    /** このアプリの版（G2 カメラと同じ番号） */
    val appVersion: String

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
    /**
     * 録画の準備（G2 録画が開いたとき・設定を変えたときに呼ぶ）。待機中から録画できる組み方にしておき、
     * 遡り録画がオンなら切れ端を録り始める。off なら準備をやめて通常（配信＋撮影）に戻す
     */
    fun prepare(quality: String, audio: Boolean, still: Boolean, stopTempC: Double, off: Boolean)
    /** 準備した録画の画質（uhd など。準備していなければ null） */
    val armedQuality: String?
    /** 準備した録画の解像度（例 3840x2160。準備していない・分からなければ空） */
    val armedResolution: String
    /** 録画の準備を休んでいる理由（"heat"）。休んでいなければ null。G2 録画はこの間、準備を頼み直さない */
    val armPausedReason: String?
    /** カメラを開いているか（要求が無くて閉じていれば false。次の要求で開く） */
    val cameraOpen: Boolean
    /** 負荷と配信の実測 */
    val loadInfo: LoadInfo
    /** 録画を始める（ブロッキング。始まるまで待つ）。すでに録画中ならその状態を返す */
    fun startRecording(o: StartOptions): RecordingState
    /** 録画を止めて保存する（ブロッキング）。録画していなければ null */
    fun stopRecording(): RecordResult?
    /** 向こう側の都合（最長時間・通知・高温など）で止まった録画の結果。一度返したら消える */
    fun takePendingResult(): RecordResult?
    /** 区切り録画で書き終えたファイルの結果（まだ渡していない分）。渡したら消える */
    fun takeSegments(): List<RecordResult>
    /** 区切り録画で書き終えて、まだ渡していない本数 */
    val pendingSegments: Int
    /** 保存した動画を名前で開く（ストリームと大きさ）。書き出し中なら済むまで待ち、待ちきれなければ BusyException。無ければ null */
    fun openRecording(name: String): Pair<InputStream, Long>?
    /** 遡り録画の状態 */
    val preRollInfo: PreRollInfo
    /** 書き出し中（遡り録画のつなぎ合わせ）の本数 */
    val mergingCount: Int
    /** 最後の書き出しで起きたこと（つなげなかった等。何もなければ null） */
    val lastMergeNote: String?
    /** スマホの電池・温度・空き容量（直近の読み取り） */
    val monitor: DeviceMonitor.Snapshot?
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
        } catch (e: BusyException) {
            json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("ok", false).put("busy", true).put("error", e.message ?: "busy"))
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

            "/record/prepare" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val body = readBody(session)
                camera.prepare(
                    quality = body.optString("quality", "fhd"),
                    audio = body.optBoolean("audio", true),
                    still = body.optBoolean("still", false),
                    stopTempC = body.optDouble("stopTempC", 0.0).let { if (it.isNaN()) 0.0 else it },
                    off = body.optBoolean("off", false),
                )
                json(Response.Status.OK, statusJson())
            }

            "/record/start" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val body = readBody(session)
                camera.startRecording(
                    StartOptions(
                        audio = body.optBoolean("audio", true),
                        quality = body.optString("quality", "fhd"),
                        maxMs = body.optLong("maxMs", 0L),
                        still = body.optBoolean("still", true),
                        continuous = body.optBoolean("continuous", false),
                        stopTempC = body.optDouble("stopTempC", 0.0).let { if (it.isNaN()) 0.0 else it },
                        minFreeBytes = body.optLong("minFreeMb", 300L).coerceAtLeast(0L) * 1024L * 1024L,
                    ),
                )
                json(Response.Status.OK, statusJson())
            }

            "/record/stop" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val r = camera.stopRecording() ?: camera.takePendingResult()
                    ?: return json(Response.Status.OK, JSONObject().put("ok", true).put("recorded", false))
                json(Response.Status.OK, resultJson(r).put("ok", true))
            }

            "/record/segments" -> {
                // 区切り録画で書き終えたファイル（まだ渡していない分）を渡して消す
                val list = JSONArray()
                for (r in camera.takeSegments()) list.put(resultJson(r))
                json(Response.Status.OK, JSONObject().put("ok", true).put("segments", list))
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

    private fun resultJson(r: RecordResult): JSONObject {
        val j = JSONObject()
            .put("recorded", r.uri != null || r.pending)
            .put("name", r.name)
            .put("durationMs", r.durationMs)
            .put("bytes", r.bytes)
            .put("audio", r.audio)
            .put("uri", r.uri?.toString() ?: JSONObject.NULL)
            .put("error", r.error ?: JSONObject.NULL)
            .put("resolution", r.resolution)
            .put("preRollMs", r.preRollMs)
            .put("pending", r.pending)
            .put("segment", r.segment)
        r.thumbnail?.let { j.put("thumbnailBase64", Base64.encodeToString(it, Base64.NO_WRAP)) }
        return j
    }

    private fun statusJson(): JSONObject {
        val j = JSONObject()
            .put("ok", true)
            .put("app", "g2cam-companion")
            .put("version", camera.appVersion)
            .put("diag", camera.diag)
            .put("facing", camera.facing)
            .put("width", camera.frameWidth)
            .put("height", camera.frameHeight)
            .put("zoom", camera.zoom.toDouble())
            .put("zoomMin", camera.zoomMin.toDouble())
            .put("zoomMax", camera.zoomMax.toDouble())
            .put("canRecord", true)
            .put("canPrepare", true)
            .put("audioPermitted", camera.audioPermitted)
            .put("armed", camera.armedQuality != null)
            .put("armedQuality", camera.armedQuality ?: JSONObject.NULL)
            .put("armedResolution", camera.armedResolution)
            .put("armPaused", camera.armPausedReason ?: JSONObject.NULL)
            .put("cameraOpen", camera.cameraOpen)
            .put("recording", camera.recording != null)
            .put("pendingSegments", camera.pendingSegments)
            .put("merging", camera.mergingCount)
            .put("mergeNote", camera.lastMergeNote ?: JSONObject.NULL)
        camera.recording?.let { r ->
            j.put("recordName", r.name)
                .put("recordMs", r.elapsedMs)
                .put("recordBytes", r.bytes)
                .put("recordAudio", r.audio)
                .put("recordLive", r.live)
                .put("recordCapture", r.capture)
                .put("recordResolution", r.resolution)
                .put("recordSegment", r.segment)
                .put("recordPreRollMs", r.preRollMs)
                .put("recordBytesPerSec", r.bytesPerSec)
        }
        val li = camera.loadInfo
        j.put("cpuPct", li.cpuPct)
            .put("cpuAvgPct", li.cpuAvgPct)
            .put("cameraFps", li.cameraFps)
            .put("producedFps", li.producedFps)
            .put("servedFps", li.servedFps)
            .put("streamFpsCap", li.streamFpsCap)
            .put("extension", li.extension)
        val p = camera.preRollInfo
        j.put("preRoll", p.enabled)
            .put("preRollSec", p.seconds)
            .put("preRollState", p.state)
            .put("preRollReadyMs", p.readyMs)
        camera.monitor?.let { m ->
            j.put("batteryPct", m.batteryPct)
                .put("batteryTempC", if (m.tempC.isNaN()) JSONObject.NULL else Math.round(m.tempC * 10) / 10.0)
                .put("charging", m.charging)
                .put("thermal", m.thermal)
                .put("freeBytes", m.freeBytes)
        }
        return j
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
