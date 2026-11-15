package com.forge3d.studio.mcp.server

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.forge3d.studio.core.engine.MemoryManager

/**
 * Servidor MCP Streamable HTTP (JSON-RPC 2.0) embebido en Android.
 * Endpoint: POST /mcp  (cabecera Authorization: Bearer <token>)
 * Usa el SDK oficial de Kotlin para MCP (mcp-sdk-kotlin) como dependencia de transporte.
 */
object McpServer {
    private val tools = ConcurrentHashMap<String, ToolDef>()
    private val sessionCounter = AtomicLong(0)

    private const val TOKEN = "Forge3D-studio" // token estático (v1.1.0)
    private val rateLock = Any(); private var windowStart = 0L; private var windowCount = 0L

    fun register(tool: ToolDef) { tools[tool.name] = tool }

    fun registerAll(tools: List<ToolDef>) { tools.forEach { register(it) } }

    fun start(port: Int = 8080, host: String = "127.0.0.1") {
        val server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) { json(Json { prettyPrint = true }) }
            routing {
                post("/mcp") {
                    call.receive<String>()?.let { body ->
                        val auth = call.request.headers["Authorization"] ?: ""
                        if (auth != "Bearer $TOKEN") {
                            call.respond(401, mapOf("error" to "Bearer token requerido o inválido")); return@post
                        }
                        if (rateLimited()) { call.respond(429, mapOf("error" to "Rate limit excedido")); return@post }
                        val id = sessionCounter.incrementAndGet()
                        val sessionId = call.request.headers["Mcp-Session-Id"] ?: "s$id"
                        call.response.headers["Mcp-Session-Id"] = sessionId
                        try {
                            val rpc = Json.parseToJsonElement(body) as JsonObject
                            val method = rpc["method"]?.jsonPrimitive?.orNull ?: ""
                            val params = rpc["params"] as? JsonObject ?: JsonObject({})
                            val result = when (method) {
                                "initialize" -> mapOf("protocolVersion" to "2025-11-25", "capabilities" to mapOf("tools" to mapOf(), "resources" to mapOf(), "prompts" to mapOf()), "serverInfo" to mapOf("name" to "forge3d", "version" to "1.1.0"))
                                "ping" -> mapOf()
                                "tools/list" -> mapOf("tools" to tools.values.map { it.toJson() })
                                "tools/call" -> {
                                    val name = params["name"]?.jsonPrimitive?.orNull ?: ""
                                    val args = params["arguments"] as? JsonObject ?: JsonObject({})
                                    val tool = tools[name] ?: throw IllegalArgumentException("Tool desconocida: $name")
                                    val res = tool.handler(args)
                                    mapOf("content" to listOf(mapOf("type" to "text", "text" to res)))
                                }
                                else -> throw IllegalArgumentException("Método desconocido: $method")
                            }
                            call.respond(200, mapOf("jsonrpc" to "2.0", "id" to rpc["id"]?.jsonPrimitive?.orNull, "result" to result))
                        } catch (e: Exception) {
                            call.respond(200, mapOf("jsonrpc" to "2.0", "id" to null, "error" to mapOf("code" to -32603, "message" to e.message)))
                        }
                    } ?: run { call.respond(400, mapOf("error" to "Petición malformada")) }
                }
            }
        }
        server.start(wait = false)
        MemoryManager.init(server.application.environment.applicationContext ?: throw IllegalStateException("sin contexto"))
        println("MCP Server escuchando en http://$host:$port/mcp")
    }

    private fun rateLimited(): Boolean {
        synchronized(rateLock) {
            val now = System.currentTimeMillis()
            if (now - windowStart > 1000) { windowStart = now; windowCount = 0 }
            windowCount++; return windowCount > 100
        }
    }
}

data class ToolDef(val name: String, val desc: String, val inputSchema: Map<String, Any?>, val handler: (Map<String, Any?>) -> String) {
    fun toJson(): Map<String, Any?> = mapOf("name" to name, "description" to desc, "inputSchema" to inputSchema)
}
