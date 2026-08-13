package com.charifmahmoudi.chatgptremote

import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

interface McpTransport {
    suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>): McpResult
    suspend fun terminate(headers: Map<String, List<String>>): McpResult
}

/**
 * Embedded, stateless MCP server backed directly by Android's paired wireless adbd.
 *
 * A fresh ADB connection is opened per tool call. This avoids sharing a potentially stale Kadb
 * socket across Android network changes and concurrent tunnel commands.
 */
class AdbMcpTransport(private val host: String, private val port: Int) : McpTransport {
    override suspend fun jsonRpc(
        payload: JsonElement,
        headers: Map<String, List<String>>,
    ): McpResult = withContext(Dispatchers.IO) {
        val request = payload.jsonObject
        val id = request["id"] ?: JsonNull
        val response = when (request["method"]?.jsonPrimitive?.contentOrNull) {
            "initialize" -> result(
                id,
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "android-adb-mcp")
                        put("version", SERVER_VERSION)
                    }
                },
            )
            "notifications/initialized" -> null
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, toolList())
            "tools/call" -> callTool(id, request["params"]?.jsonObject)
            else -> error(id, -32601, "Method not found")
        }
        if (response == null) {
            McpResult(204, null, emptyMap())
        } else {
            McpResult(200, response, mapOf("Content-Type" to listOf("application/json")))
        }
    }

    override suspend fun terminate(headers: Map<String, List<String>>) = McpResult(204, null, emptyMap())

    private fun callTool(id: JsonElement, params: JsonObject?): JsonObject {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "Missing tool name")
        val args = params["arguments"]?.jsonObject ?: buildJsonObject {}
        return try {
            val text = Kadb.create(host, port).use { adb ->
                when (name) {
                    "adb_status" -> adb.shell("id; getprop ro.product.model").output
                    "adb_shell" -> {
                        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "Missing command")
                        require(command.length in 1..MAX_COMMAND_LENGTH) {
                            "Command must contain 1–$MAX_COMMAND_LENGTH characters"
                        }
                        adb.shell(command).let { "exit_code=${it.exitCode}\n${it.output}" }
                    }
                    "adb_packages" -> adb.shell(if (args["include_system"]?.jsonPrimitive?.booleanOrNull == true) "pm list packages" else "pm list packages -3").output
                    "adb_properties" -> adb.shell("getprop").output
                    else -> return error(id, -32602, "Unknown tool")
                }
            }
            toolResult(id, text.take(MAX_OUTPUT_LENGTH), isError = false)
        } catch (e: Exception) {
            // Return only the exception class: messages may contain device or command details.
            toolResult(id, "ADB error: ${e.javaClass.simpleName}", isError = true)
        }
    }

    private fun toolList() = buildJsonObject {
        putJsonArray("tools") {
            add(tool("adb_status", "Check the paired ADB connection", emptyMap()))
            add(tool("adb_shell", "Run one command as Android's shell user", mapOf("command" to "string")))
            add(tool("adb_packages", "List installed package names", mapOf("include_system" to "boolean")))
            add(tool("adb_properties", "Read Android system properties", emptyMap()))
        }
    }

    private fun toolResult(id: JsonElement, text: String, isError: Boolean) = result(
        id,
        buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", text) })
            }
            put("isError", isError)
        },
    )

    private fun tool(name: String, description: String, properties: Map<String, String>) = buildJsonObject {
        put("name", name)
        put("description", description)
        putJsonObject("inputSchema") {
            put("type", "object")
            putJsonObject("properties") {
                properties.forEach { (key, type) -> putJsonObject(key) { put("type", type) } }
            }
        }
    }
    private fun result(id: JsonElement, value: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) } }

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_VERSION = "0.3.1"
        private const val MAX_COMMAND_LENGTH = 8_192
        private const val MAX_OUTPUT_LENGTH = 1_000_000

        suspend fun pair(host: String, port: Int, pin: String) = withContext(Dispatchers.IO) {
            require(pin.matches(Regex("^\\d{6}$"))) { "Pairing PIN must contain six digits" }
            Kadb.pair(host, port, pin)
        }
    }
}
