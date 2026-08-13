package com.charifmahmoudi.chatgptremote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private val onConnected: () -> Unit = {},
    private val onConnectionLost: () -> Unit = {},
    private val onDiagnostic: (String) -> Unit = {},
) {
    init {
        require(TUNNEL_ID.matches(tunnelId)) { "Invalid tunnel ID" }
        require(apiKey.isNotBlank()) { "Runtime key is required" }
    }

    private val instanceId = UUID.randomUUID().toString()
    private var ownerJob: Job? = null

    suspend fun run() = supervisorScope {
        ownerJob = currentCoroutineContext().job
        val queue = Channel<ReceivedCommand>(capacity = COMMAND_QUEUE_CAPACITY)
        val workers = List(MAX_CONCURRENT_COMMANDS) { workerIndex ->
            launch(Dispatchers.IO) {
                for (received in queue) {
                    try {
                        process(received)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        onDiagnostic(
                            "command failed worker=$workerIndex ${failureFields(error)}",
                        )
                    }
                }
            }
        }
        var failures = 0
        var connected = false

        try {
            while (isActive) {
                try {
                    // OkHttp's synchronous execute() must never run on the service's main dispatcher.
                    val commands = withContext(Dispatchers.IO) { pollOnce() }
                    if (!connected) {
                        connected = true
                        onDiagnostic("poll connected")
                        onConnected()
                    }
                    if (commands.isNotEmpty()) {
                        onDiagnostic("poll commands=${commands.size}")
                    }
                    commands.forEach { queue.send(it) }
                    failures = 0
                } catch (error: IOException) {
                    if (connected) {
                        connected = false
                        onConnectionLost()
                    }
                    failures += 1
                    val delayMs = backoff(failures)
                    onDiagnostic("poll retry=$failures delay_ms=$delayMs ${failureFields(error)}")
                    delay(delayMs)
                }
            }
        } finally {
            queue.close()
            workers.forEach { it.cancelAndJoin() }
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
                    throw RetryableHttpException("poll", response.code)
                }
                onDiagnostic("poll rejected status=${response.code}")
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
        if (remainingMs != null && remainingMs <= 0) {
            onDiagnostic("command expired before dispatch")
            return
        }

        val work: suspend () -> Unit = {
            when (command.commandType) {
                COMMAND_JSON_RPC -> processJsonRpc(command)
                COMMAND_SESSION_TERMINATION -> processTermination(command)
                else -> onDiagnostic("unsupported command type")
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
        val responseType = if (result.body != null) {
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
                    if (httpResponse.isSuccessful) {
                        onDiagnostic("response delivered status=${httpResponse.code} attempts=${attempt + 1}")
                        return
                    }
                    if (httpResponse.code == 404) {
                        onDiagnostic("response terminal status=404")
                        return
                    }
                    if (httpResponse.code !in RETRYABLE_RESPONSE_CODES) {
                        onDiagnostic("response rejected status=${httpResponse.code}")
                        throw NonRetryableResponseException(httpResponse.code)
                    }
                    throw RetryableHttpException("response", httpResponse.code)
                }
            } catch (error: NonRetryableResponseException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (attempt >= MAX_RESPONSE_RETRIES) {
                    onDiagnostic("response abandoned attempts=${attempt + 1} ${failureFields(error)}")
                    throw error
                }
                val delayMs = backoff(attempt + 1)
                onDiagnostic("response retry=${attempt + 1} delay_ms=$delayMs ${failureFields(error)}")
                delay(delayMs)
            }
            attempt += 1
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

    private class RetryableHttpException(val operation: String, val status: Int) :
        IOException("Retryable HTTP failure")

    private fun failureFields(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .take(4)
            .joinToString(">") { it.javaClass.simpleName.ifBlank { "Throwable" } }
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val kind = when {
            error is RetryableHttpException -> "http"
            causes.any { it is UnknownHostException } -> "dns"
            causes.any { it is SSLException } -> "tls"
            causes.any { it is ConnectException } -> "connect"
            causes.any { it is SocketTimeoutException } -> "timeout"
            else -> "io"
        }
        val status = (error as? RetryableHttpException)?.status?.let { " status=$it" }.orEmpty()
        return "kind=$kind$status chain=$chain"
    }

    private companion object {
        const val CLIENT_NAME = "android-kotlin-tunnel-client"
        const val CLIENT_VERSION = "0.4.1"
        const val POLL_LIMIT = 25
        const val POLL_TIMEOUT_MS = 15_000
        const val MAX_CONCURRENT_COMMANDS = 4
        const val COMMAND_QUEUE_CAPACITY = 100
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
