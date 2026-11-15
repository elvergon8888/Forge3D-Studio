package com.forge3d.studio.mcp.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class McpClient private constructor(
    private val baseUrl: String,
    private val bearerToken: String?,
    private val client: HttpClient
) {
    private val json = Json { prettyPrint = true }
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)

    constructor(baseUrl: String, bearerToken: String? = null) : this(baseUrl, bearerToken, HttpClient())

    suspend fun listTools(): Map<String, Any?> {
        val res = call("tools/list", emptyMap())
        return (res["result"] as? JsonObject ?: JsonObject({}))["tools"]?.let {
            when (it) {
                is JsonArray -> it.map { (it as JsonObject)["name"]?.jsonPrimitive?.orNull ?: "" }.filterIsInstance<String>().associateWith { it -> it }
                else -> emptyMap()
            }
        } ?: emptyMap()
    }

    suspend fun callTool(name: String, arguments: Map<String, Any?> = emptyMap()): String {
        val res = call("tools/list", mapOf("name" to name, "arguments" to arguments))
        return (res["result"] as? JsonObject)?.toString() ?: res.toString()
    }

    private suspend fun call(method: String, params: Map<String, Any?>): JsonObject {
        val id = idCounter.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response: HttpResponse = client.post("${baseUrl}/mcp") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $bearerToken")
            setBody(body)
        }
        val text = response.bodyAsText()
        val parsed = json.parseToJsonElement(text) as JsonObject
        val error = parsed["error"] as? JsonObject
        if (error != null) throw RuntimeException("MCP error: ${error["message"]?.jsonPrimitive?.orNull}")
        return parsed["result"] as? JsonObject ?: JsonObject({})
    }

    fun close() { client.close() }
}
