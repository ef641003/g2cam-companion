package com.ef64nagaoka.g2cam

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.min

/**
 * 待機モード用の小さな HTTP サーバー（127.0.0.1 だけ。GET / POST の 1 行 JSON 応答）。
 * ライブラリ（NanoHTTPD）の accept ループは、ソケットが壊れたまま accept が失敗し続けると
 * 休まず回り続けて CPU を 1 コアぶん使い切る作りなので、待機モードでは使わない。
 * ここでは失敗したら少し待ち、続くならソケットを作り直す。
 */
class LoopbackServer(
    private val port: Int,
    private val name: String,
    /** (method, path) → (HTTP ステータス, JSON 本文) */
    private val handler: (String, String) -> Pair<Int, String>,
) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    /** accept や bind の失敗（累計。診断用） */
    @Volatile var errors = 0
        private set
    /** 受けた要求（累計） */
    @Volatile var requests = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, name).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        closeQuietly(socket)
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var consecutive = 0
        while (running) {
            val s = socket ?: try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("127.0.0.1", port), 8)
                }.also {
                    socket = it
                    consecutive = 0
                }
            } catch (e: IOException) {
                errors++
                lastError = "bind: ${e.message}"
                AppLog.w("待機サーバーを開けません（ポート $port）: ${e.message}")
                sleep(min(30_000L, 1000L shl min(consecutive++, 5)))
                continue
            }
            val client = try {
                s.accept()
            } catch (e: IOException) {
                if (!running) break
                errors++
                consecutive++
                lastError = "accept: ${e.message}"
                if (consecutive >= 3) {
                    // 壊れたソケットを作り直す
                    AppLog.w("待機サーバーの accept が失敗し続けるので作り直します: ${e.message}")
                    closeQuietly(s)
                    socket = null
                }
                sleep(500)
                continue
            }
            consecutive = 0
            Thread({ serve(client) }, "$name-client").apply {
                isDaemon = true
                start()
            }
        }
        closeQuietly(socket)
        socket = null
    }

    private fun serve(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 5000
                val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val reqLine = input.readLine() ?: return
                val parts = reqLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                var contentLength = 0
                while (true) {
                    val h = input.readLine() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                // 本文は読み捨てる（{} だけ）
                var remain = contentLength.coerceAtMost(64 * 1024)
                val buf = CharArray(1024)
                while (remain > 0) {
                    val n = input.read(buf, 0, min(buf.size, remain))
                    if (n < 0) break
                    remain -= n
                }
                requests++
                val (code, body) = if (method == "OPTIONS") 204 to "" else handler(method, path)
                val bytes = body.toByteArray(Charsets.UTF_8)
                val head = StringBuilder()
                    .append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                    .append("Content-Type: application/json; charset=utf-8\r\n")
                    .append("Content-Length: ").append(bytes.size).append("\r\n")
                    .append("Access-Control-Allow-Origin: *\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                    .append("Access-Control-Allow-Headers: *\r\n")
                    .append("Access-Control-Allow-Private-Network: true\r\n")
                    .append("Cache-Control: no-store\r\n")
                    .append("Connection: close\r\n\r\n")
                    .toString()
                val out = c.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            }
        } catch (e: Exception) {
            // 相手が途中で切った等。何もしない
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun closeQuietly(s: ServerSocket?) {
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }
}
