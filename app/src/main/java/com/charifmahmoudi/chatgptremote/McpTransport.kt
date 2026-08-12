package com.charifmahmoudi.chatgptremote

import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

interface McpTransport {
    suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>): McpResult
    suspend fun terminate(headers: Map<String, List<String>>): McpResult
}

/** Embedded MCP server backed directly by Android's paired wireless adbd. */
class AdbMcpTransport(private val host: String, private val port: Int) : McpTransport {
    override suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>): McpResult = withContext(Dispatchers.IO) {
        val request = payload.jsonObject
        val id = request["id"] ?: JsonNull
        val response = when (request["method"]?.jsonPrimitive?.contentOrNull) {
            "initialize" -> result(id, buildJsonObject {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") { putJsonObject("tools") {} }
                putJsonObject("serverInfo") { put("name", "android-adb-mcp"); put("version", "0.2.0") }
            })
            "notifications/initialized" -> null
            "ping" -> result(id, buildJsonObject {})
            "tools/list" -> result(id, buildJsonObject { putJsonArray("tools") {
                add(tool("adb_status", "Check the paired ADB connection", emptyMap()))
                add(tool("adb_shell", "Run one command with Android shell-user privileges", mapOf("command" to "string")))
                add(tool("adb_packages", "List installed package names", mapOf("include_system" to "boolean")))
                add(tool("adb_properties", "Read Android system properties", emptyMap()))
            } })
            "tools/call" -> callTool(id, request["params"]?.jsonObject)
            else -> error(id, -32601, "Method not found")
        }
        if (response == null) McpResult(204, null, emptyMap()) else McpResult(200, response, mapOf("Content-Type" to listOf("application/json")))
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
                        require(command.length in 1..8192) { "Command must contain 1–8192 characters" }
                        adb.shell(command).let { "exit_code=${it.exitCode}\n${it.output}" }
                    }
                    "adb_packages" -> adb.shell(if (args["include_system"]?.jsonPrimitive?.booleanOrNull == true) "pm list packages" else "pm list packages -3").output
                    "adb_properties" -> adb.shell("getprop").output
                    else -> return error(id, -32602, "Unknown tool")
                }
            }
            result(id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text.take(1_000_000)) }) }; put("isError", false) })
        } catch (e: Exception) {
            result(id, buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "ADB error: ${e.javaClass.simpleName}") }) }; put("isError", true) })
        }
    }

    private fun tool(name: String, description: String, properties: Map<String, String>) = buildJsonObject {
        put("name", name); put("description", description)
        putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") { properties.forEach { (key, type) -> putJsonObject(key) { put("type", type) } } } }
    }
    private fun result(id: JsonElement, value: JsonElement) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) } }

    companion object {
        suspend fun pair(host: String, port: Int, pin: String) = withContext(Dispatchers.IO) {
            require(pin.matches(Regex("^\\d{6}$"))) { "Pairing PIN must contain six digits" }
            Kadb.pair(host, port, pin)
        }
    }
}
