package com.charifmahmoudi.chatgptremote

import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface McpTransport {
    suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>): McpResult
    suspend fun terminate(headers: Map<String, List<String>>): McpResult
}

class HttpMcpTransport(private val url: String, private val client: OkHttpClient) : McpTransport {
    override suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>) = execute("POST", payload.toString(), headers)
    override suspend fun terminate(headers: Map<String, List<String>>) = execute("DELETE", null, headers)
    private fun execute(method: String, body: String?, headers: Map<String, List<String>>): McpResult {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val parsed = response.body?.string()?.takeIf(String::isNotBlank)?.let(TunnelJson.json::parseToJsonElement)
            val allowed = setOf("content-type", "mcp-session-id", "mcp-protocol-version", "last-event-id", "access-control-expose-headers", "www-authenticate")
            val resultHeaders = response.headers.names().filter { it.lowercase() in allowed }.associateWith { response.headers.values(it) }
            return McpResult(response.code, parsed, resultHeaders)
        }
    }
}
