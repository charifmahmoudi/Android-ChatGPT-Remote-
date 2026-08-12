package com.charifmahmoudi.chatgptremote

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object TunnelJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

/**
 * Outbound-only Secure MCP Tunnel client.
 *
 * One long-poll loop feeds a bounded worker set. Each response retains the command's opaque
 * correlation values, and deadlines use the monotonic timestamp captured at poll receipt.
 */
class TunnelClient(
    private val baseUrl: String,
    private val tunnelId: String,
    private val apiKey: String,
    private val transport: McpTransport,
    private val http: OkHttpClient = defaultHttpClient(),
    private val nowNanos: () -> Long = System::nanoTime,
) {
    init {
        require(TUNNEL_ID.matches(tunnelId)) { "Invalid tunnel ID" }
        require(apiKey.isNotBlank()) { "Runtime key is required" }
    }

    private val instanceId = UUID.randomUUID().toString()
    private val workerPermits = Semaphore(MAX_CONCURRENT_COMMANDS)
    private var ownerJob: Job? = null

    suspend fun run() = coroutineScope {
        ownerJob = currentCoroutineContext().job
        var failures = 0

        while (isActive) {
            try {
                pollOnce().forEach { received ->
                    launch(Dispatchers.IO) {
                        workerPermits.withPermit { process(received) }
                    }
                }
                failures = 0
            } catch (error: IOException) {
                delay(backoff(++failures))
            }
        }
    }

    fun stop() {
        ownerJob?.cancel()
    }

    internal fun pollOnce(): List<ReceivedCommand> {
        val request = commonHeaders(
            Request.Builder().url(
                "$baseUrl/v1/tunnels/$tunnelId/poll?limit=$POLL_LIMIT&timeout_ms=$POLL_TIMEOUT_MS",
            ),
        ).get().build()

        http.newCall(request).execute().use { response ->
            // The protocol anchors every command deadline in this batch to one monotonic instant.
            val receivedAt = nowNanos()
            when (response.code) {
                204 -> return emptyList()
                401, 403 -> throw SecurityException("Tunnel authorization failed")
            }
            if (!response.isSuccessful) {
                if (response.code == 429 || response.code >= 500) {
                    throw IOException("Transient tunnel poll failure: HTTP ${response.code}")
                }
                throw NonRetryableControlException(response.code)
            }

            val body = response.body?.string() ?: throw IOException("Missing poll body")
            return TunnelJson.json.decodeFromString<PollEnvelope>(body).commands.map {
                ReceivedCommand(it, receivedAt)
            }
        }
    }

    private suspend fun process(received: ReceivedCommand) {
        val command = received.command
        val timeoutMs = ResponseTimeoutParser.toMillis(command.responseTimeout)
        val remainingMs = timeoutMs?.minus(
            TimeUnit.NANOSECONDS.toMillis(nowNanos() - received.receivedAtNanos),
        )

        // A valid zero or a deadline spent while waiting for a worker is dropped without response.
        if (remainingMs != null && remainingMs <= 0) return

        val work: suspend () -> Unit = {
            when (command.commandType) {
                COMMAND_JSON_RPC -> processJsonRpc(command)
                COMMAND_SESSION_TERMINATION -> processTermination(command)
                else -> Unit // Future command types must never be guessed from payload shape.
            }
        }

        if (remainingMs == null) {
            work()
        } else {
            withTimeoutOrNull(remainingMs) { work() }
        }
    }

    private suspend fun processJsonRpc(command: TunnelCommand) {
        val payload = command.jsonrpc ?: return
        val result = transport.jsonRpc(payload, command.headers)
        val responseType = if (payload.jsonObject["id"] != null) {
            "jsonrpc_response"
        } else {
            "notify_ack"
        }
        post(
            command,
            TunnelResponse(
                requestId = command.requestId,
                channel = command.channel,
                respJson = result.body,
                respHeaders = result.headers,
                respCode = result.code,
                respType = responseType,
            ),
        )
    }

    private suspend fun processTermination(command: TunnelCommand) {
        val result = transport.terminate(command.headers)
        post(
            command,
            TunnelResponse(
                requestId = command.requestId,
                channel = command.channel,
                respHeaders = result.headers,
                respCode = result.code,
                respType = "session_termination_response",
            ),
        )
    }

    private suspend fun post(command: TunnelCommand, response: TunnelResponse) {
        val body = TunnelJson.json.encodeToString(response)
            .toRequestBody(JSON_MEDIA_TYPE)
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            val request = commonHeaders(
                Request.Builder().url("$baseUrl/v1/tunnels/$tunnelId/response"),
            ).header(HEADER_SHARD_TOKEN, command.shardToken).post(body).build()

            try {
                http.newCall(request).execute().use { httpResponse ->
                    if (httpResponse.isSuccessful || httpResponse.code == 404) return
                    if (httpResponse.code !in RETRYABLE_RESPONSE_CODES) {
                        throw NonRetryableResponseException(httpResponse.code)
                    }
                }
            } catch (error: NonRetryableResponseException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= MAX_RESPONSE_RETRIES) throw error
            }
            delay(backoff(++attempt))
        }
    }

    private fun commonHeaders(builder: Request.Builder) = builder
        .header("Authorization", "Bearer $apiKey")
        .header("User-Agent", "$CLIENT_NAME/$CLIENT_VERSION")
        .header("X-Tunnel-Client-Name", CLIENT_NAME)
        .header("X-Tunnel-Client-Version", CLIENT_VERSION)
        .header("X-Tunnel-Client-Instance-Id", instanceId)
        .header("X-Tunnel-MCP-Server-Info", MCP_SERVER_INFO)

    private fun backoff(attempt: Int): Long =
        min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl min(attempt, 6)) +
            Random.nextLong(0, BACKOFF_JITTER_MS)

    private class NonRetryableResponseException(code: Int) :
        IOException("Tunnel response rejected: HTTP $code")

    private class NonRetryableControlException(code: Int) :
        IllegalStateException("Tunnel poll rejected: HTTP $code")

    private companion object {
        const val CLIENT_NAME = "android-kotlin-tunnel-client"
        const val CLIENT_VERSION = "0.2.0"
        const val POLL_LIMIT = 25
        const val POLL_TIMEOUT_MS = 15_000
        const val MAX_CONCURRENT_COMMANDS = 4
        const val MAX_RESPONSE_RETRIES = 4
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 30_000L
        const val BACKOFF_JITTER_MS = 250L
        const val COMMAND_JSON_RPC = "jsonrpc"
        const val COMMAND_SESSION_TERMINATION = "session_termination"
        const val HEADER_SHARD_TOKEN = "X-Tunnel-Shard-Token"
        const val MCP_SERVER_INFO = "{\"version\":1,\"channels\":[{\"name\":\"main\"}]}"
        val TUNNEL_ID = Regex("^tunnel_[0-9a-f]{32}$")
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val RETRYABLE_RESPONSE_CODES = setOf(408, 429, 502, 503, 504)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }
}
