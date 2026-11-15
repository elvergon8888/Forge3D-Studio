package com.forge3d.studio.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Servidor MCP Streamable HTTP (JSON-RPC 2.0) propio, sin dependencias externas.
 * Escucha 127.0.0.1:8080 (o 0.0.0.0 si se activa LAN), ruta /mcp.
 * Seguridad: Bearer obligatorio, validación de Origin/Host, rate-limit 100 ops/s.
 */
class McpServer(private val bindAny: Boolean, private val port: Int = 8080) {

    @Volatile var running = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ExecutorService = Executors.newFixedThreadPool(4)
    private val requestsServed = AtomicLong(0)

    private val rateLock = Any()
    private var windowStart = 0L
    private var windowCount = 0L

    @Volatile private var sessionId: String = ""

    fun start(): Boolean {
        if (running) return true
        return try {
            val addr = if (bindAny) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
            val ss = ServerSocket(port, 32, addr)
            serverSocket = ss
            running = true
            acceptThread = Thread {
                while (running) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock) }
                    } catch (e: Exception) {
                        if (running) {
                            try { Thread.sleep(50) } catch (ie: InterruptedException) { break }
                        }
                    }
                }
            }.also { it.isDaemon = true; it.start() }
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        pool.shutdownNow()
    }

    fun info(): String =
        "http://${if (bindAny) "0.0.0.0" else "127.0.0.1"}:$port/mcp · peticiones=${requestsServed.get()}"

    private fun rateLimited(): Boolean {
        synchronized(rateLock) {
            val now = System.currentTimeMillis()
            if (now - windowStart > 1000) {
                windowStart = now
                windowCount = 0
            }
            windowCount++
            return windowCount > 100
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val input = DataInputStream(sock.getInputStream().buffered())
            val out = sock.getOutputStream()

            // leer cabeceras byte a byte hasta CRLFCRLF
            val head = ByteArrayOutputStream()
            val last4 = IntArray(4)
            var found = false
            while (head.size() < 65536) {
                val b = input.read()
                if (b == -1) break
                head.write(b)
                last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b
                if (last4[0] == 13 && last4[1] == 10 && last4[2] == 13 && last4[3] == 10) {
                    found = true
                    break
                }
            }
            if (!found) {
                respond(out, 400, "Bad Request", JSONObject().put("error", "cabecera incompleta"))
                return
            }
            val lines = head.toString("ISO-8859-1").split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) {
                respond(out, 400, "Bad Request", JSONObject().put("error", "línea de petición inválida"))
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1]
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                }
            }

            val body: ByteArray
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                if (contentLength > 10_000_000) {
                    respond(out, 413, "Payload Too Large", JSONObject().put("error", "cuerpo demasiado grande"))
                    return
                }
                body = ByteArray(contentLength)
                input.readFully(body)
            } else {
                body = ByteArray(0)
            }

            requestsServed.incrementAndGet()

            if (path != "/mcp") {
                respond(out, 404, "Not Found", JSONObject().put("error", "ruta inválida; usa /mcp"))
                return
            }

            // Validación de Host (protección rebinding DNS)
            val host = headers["host"] ?: ""
            val hostOk = if (bindAny) host.isNotBlank()
            else host.startsWith("127.0.0.1") || host.startsWith("localhost") || host.startsWith("[::1]")
            if (!hostOk) {
                respond(out, 403, "Forbidden", JSONObject().put("error", "Host no permitido"))
                return
            }

            // Validación de Origin (navegadores)
            val origin = headers["origin"]
            if (origin != null && origin.isNotBlank() &&
                !origin.contains("127.0.0.1") && !origin.contains("localhost") && origin != "null"
            ) {
                respond(out, 403, "Forbidden", JSONObject().put("error", "Origin no permitido"))
                return
            }

            if (method == "OPTIONS") {
                respondRaw(out, 204, "No Content", mapOf(
                    "Access-Control-Allow-Methods" to "POST, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers" to "Authorization, Content-Type, Accept, Mcp-Session-Id",
                    "Access-Control-Max-Age" to "86400"
                ), null)
                return
            }
            if (method == "DELETE") {
                respondRaw(out, 204, "No Content", emptyMap(), null)
                return
            }
            if (method != "POST") {
                respond(out, 405, "Method Not Allowed", JSONObject().put("error", "usa POST con JSON-RPC 2.0"))
                return
            }

            // Rate limit
            if (rateLimited()) {
                respond(out, 429, "Too Many Requests", JSONObject().put("error", "rate limit 100 ops/s"))
                return
            }

            // Autenticación Bearer
            val ctx = AppModel.appContext
            val token = if (ctx != null) AuthManager.token(ctx) else null
            val auth = headers["authorization"] ?: ""
            if (token == null || auth != "Bearer $token") {
                respondRaw(out, 401, "Unauthorized", mapOf(
                    "WWW-Authenticate" to "Bearer"
                ), JSONObject().put("error", "token Bearer ausente o inválido; genera uno en la UI").toString().toByteArray())
                return
            }

            // Parsear JSON-RPC
            val msg = try {
                JSONObject(String(body, Charsets.UTF_8))
            } catch (e: Exception) {
                respond(out, 400, "Bad Request", rpcError(null, -32700, "Error de parseo JSON"))
                return
            }

            val rpcId = msg.opt("id")
            val rpcMethod = msg.optString("method", "")

            if (rpcId == null || msg.isNull("id")) {
                // notificación
                respondRaw(out, 202, "Accepted", emptyMap(), null)
                return
            }

            val result: JSONObject = when (rpcMethod) {
                "initialize" -> {
                    sessionId = UUID.randomUUID().toString()
                    val clientV = msg.optJSONObject("params")?.optString("protocolVersion", "") ?: ""
                    JSONObject()
                        .put("protocolVersion", if (clientV.isNotBlank()) clientV else "2025-11-25")
                        .put("capabilities", JSONObject()
                            .put("tools", JSONObject())
                            .put("resources", JSONObject())
                            .put("prompts", JSONObject())
                            .put("logging", JSONObject()))
                        .put("serverInfo", JSONObject()
                            .put("name", "Forge3D Studio MCP")
                            .put("version", "1.0.0"))
                        .put("instructions", "Servidor MCP del editor 3D Forge3D Studio. Usa tools/list para ver las 27 tools disponibles (escena, primitivas, transformaciones, materiales, cámara, proyectos, import/export glTF).")
                }
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", McpTools.listJson())
                "tools/call" -> {
                    val params = msg.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name", "")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val (isErr, text) = McpTools.call(name, args)
                    JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                        .put("isError", isErr)
                }
                "resources/list" -> JSONObject().put("resources", JSONArray())
                "prompts/list" -> JSONObject().put("prompts", JSONArray())
                else -> {
                    respond(out, 200, "OK", rpcError(rpcId, -32601, "Método desconocido: $rpcMethod"))
                    return
                }
            }

            val resp = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", rpcId)
                .put("result", result)
            val extra = if (sessionId.isNotBlank()) mapOf("Mcp-Session-Id" to sessionId) else emptyMap()
            respondRaw(out, 200, "OK", extra, resp.toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            try { sock.close() } catch (ie: Exception) {}
        } finally {
            try { sock.close() } catch (e: Exception) {}
        }
    }

    private fun rpcError(id: Any?, code: Int, msg: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun respond(out: OutputStream, code: Int, reason: String, body: JSONObject) {
        respondRaw(out, code, reason, emptyMap(), body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun respondRaw(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, body: ByteArray?) {
        try {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            sb.append("Content-Type: application/json\r\n")
            sb.append("Connection: close\r\n")
            val len = body?.size ?: 0
            sb.append("Content-Length: ").append(len).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null && body.isNotEmpty()) out.write(body)
            out.flush()
        } catch (e: Exception) {
        }
    }
}
