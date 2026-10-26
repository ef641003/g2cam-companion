package com.ef64nagaoka.g2cam

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** サーバーがカメラ側に求める操作。CameraService が実装する。 */
interface CameraController {
    val facing: String
    val frameWidth: Int
    val frameHeight: Int

    /** 最新のプレビュー用 JPEG。まだ無ければ null */
    fun latestFrame(): ByteArray?

    /** 撮影して DCIM に保存し、JPEG のバイト列を返す（ブロッキング）。zoom はレンズのズーム倍率 */
    fun capture(zoom: Double): ByteArray

    fun setFacing(facing: String)
}

/**
 * G2 アプリ（Even アプリ内 WebView）に映像を渡すローカル HTTP サーバー。
 * 仕様は docs/companion-protocol.md。127.0.0.1 だけで待ち受ける。
 */
class CompanionServer(private val camera: CameraController, port: Int) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val res = try {
            route(session)
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
        }
        // ブラウザ（WebView）から別オリジンで呼ばれるので CORS を全部に付ける
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "*")
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
            "/status" -> json(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("app", "g2cam-companion")
                    .put("version", "1.0")
                    .put("facing", camera.facing)
                    .put("width", camera.frameWidth)
                    .put("height", camera.frameHeight),
            )

            "/frame" -> {
                val bytes = camera.latestFrame()
                    ?: return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("ok", false).put("error", "no frame"))
                NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            }

            "/capture" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val zoom = readBody(session).optDouble("zoom", 1.0)
                val bytes = camera.capture(if (zoom.isNaN()) 1.0 else zoom)
                NanoHTTPD.newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                )
            }

            "/facing" -> {
                if (session.method != Method.POST) return methodNotAllowed()
                val want = readBody(session).optString("facing", "environment")
                camera.setFacing(want)
                json(Response.Status.OK, JSONObject().put("ok", true).put("facing", camera.facing))
            }

            else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not found"))
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
