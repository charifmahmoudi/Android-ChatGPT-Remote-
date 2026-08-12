package com.charifmahmoudi.chatgptremote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Wire envelope returned by the Secure MCP Tunnel poll endpoint. */
@Serializable
data class PollEnvelope(val commands: List<TunnelCommand> = emptyList())

/**
 * A command from the tunnel service.
 *
 * Unknown JSON properties are deliberately accepted by [TunnelJson] for forward compatibility.
 * The shard token is opaque and is only sent back in the response HTTP header.
 */
@Serializable
data class TunnelCommand(
    @SerialName("request_id") val requestId: String,
    @SerialName("shard_token") val shardToken: String,
    @SerialName("command_type") val commandType: String,
    val channel: String = "main",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("response_timeout") val responseTimeout: JsonElement? = null,
    val headers: Map<String, List<String>> = emptyMap(),
    val jsonrpc: JsonElement? = null,
)

/** Local receipt time is kept outside the wire model so it cannot be serialized accidentally. */
data class ReceivedCommand(val command: TunnelCommand, val receivedAtNanos: Long)

@Serializable
data class TunnelResponse(
    @SerialName("request_id") val requestId: String,
    val channel: String = "main",
    @SerialName("resp_json") val respJson: JsonElement? = null,
    @SerialName("resp_headers") val respHeaders: Map<String, List<String>> = emptyMap(),
    @SerialName("resp_code") val respCode: Int,
    @SerialName("resp_type") val respType: String = "jsonrpc_response",
)

data class McpResult(
    val code: Int,
    val body: JsonElement?,
    val headers: Map<String, List<String>>,
)

/** Parses the protocol's strict duration grammar and fails open for malformed values. */
object ResponseTimeoutParser {
    private val pattern = Regex("^(\\d+)(ns|us|ms|s|m|h)$")

    fun toMillis(value: JsonElement?): Long? {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val match = pattern.matchEntire(raw) ?: return null
        val number = match.groupValues[1].toLongOrNull() ?: return null

        return try {
            when (match.groupValues[2]) {
                "ns" -> number / 1_000_000
                "us" -> number / 1_000
                "ms" -> number
                "s" -> Math.multiplyExact(number, 1_000)
                "m" -> Math.multiplyExact(number, 60_000)
                "h" -> Math.multiplyExact(number, 3_600_000)
                else -> null
            }
        } catch (_: ArithmeticException) {
            null
        }
    }
}
