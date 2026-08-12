package com.charifmahmoudi.chatgptremote

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

object TunnelJson { val json = Json { ignoreUnknownKeys = true; explicitNulls = false } }

class TunnelClient(
    private val baseUrl: String, private val tunnelId: String, private val apiKey: String,
    private val transport: McpTransport,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(25, TimeUnit.SECONDS).build(),
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init { require(Regex("^tunnel_[0-9a-f]{32}$").matches(tunnelId)) { "Invalid tunnel ID" }; require(apiKey.isNotBlank()) }
    private val instanceId = UUID.randomUUID().toString()
    private var job: Job? = null

    suspend fun run() = coroutineScope {
        job = coroutineContext.job
        var failures = 0
        while (isActive) {
            try { pollOnce().forEach { command -> launch(Dispatchers.IO) { process(command) } }; failures = 0 }
            catch (_: IOException) { delay(backoff(++failures)) }
        }
    }
    fun stop() = job?.cancel()

    internal fun pollOnce(): List<TunnelCommand> {
        val request = common(Request.Builder().url("$baseUrl/v1/tunnels/$tunnelId/poll?limit=25&timeout_ms=15000")).get().build()
        http.newCall(request).execute().use {
            if (it.code == 204) return emptyList()
            if (it.code == 401 || it.code == 403) throw SecurityException("Tunnel authorization failed")
            if (!it.isSuccessful) throw IOException("Tunnel poll failed: HTTP ${it.code}")
            return TunnelJson.json.decodeFromString<PollEnvelope>(it.body?.string() ?: throw IOException("Missing poll body")).commands
        }
    }

    private suspend fun process(command: TunnelCommand) {
        val received = nowNanos()
        val timeoutMs = ResponseTimeoutParser.toMillis(command.responseTimeout)
        if (timeoutMs == 0L) return
        val work: suspend () -> Unit = {
            when (command.commandType) {
                "jsonrpc" -> command.jsonrpc?.let { payload ->
                    val result = transport.jsonRpc(payload, command.headers)
                    val type = if (payload.jsonObject["id"] != null) "jsonrpc_response" else "notify_ack"
                    post(command, TunnelResponse(command.requestId, command.channel, result.body, result.headers, result.code, type))
                }
                "session_termination" -> {
                    val result = transport.terminate(command.headers)
                    post(command, TunnelResponse(command.requestId, command.channel, null, result.headers, result.code, "session_termination_response"))
                }
            }
        }
        if (timeoutMs == null) work() else {
            val remaining = timeoutMs - ((nowNanos() - received) / 1_000_000)
            if (remaining > 0) withTimeoutOrNull(remaining) { work() }
        }
    }

    private suspend fun post(command: TunnelCommand, response: TunnelResponse) {
        val body = TunnelJson.json.encodeToString(response).toRequestBody("application/json".toMediaType())
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val request = common(Request.Builder().url("$baseUrl/v1/tunnels/$tunnelId/response"))
                .header("X-Tunnel-Shard-Token", command.shardToken).post(body).build()
            try {
                http.newCall(request).execute().use {
                    if (it.isSuccessful || it.code == 404) return
                    if (it.code !in setOf(408, 429, 502, 503, 504)) throw IOException("Response rejected: HTTP ${it.code}")
                }
            } catch (e: IOException) { if (attempt >= 4) throw e }
            delay(backoff(++attempt))
        }
    }

    private fun common(builder: Request.Builder) = builder
        .header("Authorization", "Bearer $apiKey")
        .header("User-Agent", "android-kotlin-tunnel-client/0.1.0")
        .header("X-Tunnel-Client-Name", "android-kotlin-tunnel-client")
        .header("X-Tunnel-Client-Version", "0.1.0")
        .header("X-Tunnel-Client-Instance-Id", instanceId)
        .header("X-Tunnel-MCP-Server-Info", "{\"version\":1,\"channels\":[{\"name\":\"main\"}]}")
    private fun backoff(attempt: Int): Long = min(30_000L, 500L shl min(attempt, 6)) + Random.nextLong(0, 250)
}
