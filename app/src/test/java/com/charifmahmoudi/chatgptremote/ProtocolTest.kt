package com.charifmahmoudi.chatgptremote

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ProtocolTest {
    @Test fun `decodes documented command and ignores additions`() {
        val body = """{"commands":[{"request_id":"req_1","shard_token":"opaque","command_type":"jsonrpc","channel":"main","response_timeout":"30s","headers":{"Mcp-Session-Id":["s1"]},"jsonrpc":{"jsonrpc":"2.0","id":"rpc_1","method":"tools/list","params":{}},"future":true}]}"""
        val command = TunnelJson.json.decodeFromString<PollEnvelope>(body).commands.single()
        assertEquals("req_1", command.requestId); assertEquals(30_000L, ResponseTimeoutParser.toMillis(command.responseTimeout)); assertEquals(listOf("s1"), command.headers["Mcp-Session-Id"])
    }
    @Test fun `timeout parser follows wire grammar and fails open`() {
        assertEquals(0L, ResponseTimeoutParser.toMillis(JsonPrimitive("0s"))); assertEquals(4_500L, ResponseTimeoutParser.toMillis(JsonPrimitive("4500ms")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("4.5s"))); assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("1m30s")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive(-1))); assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("999999999999999999999999h")))
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects malformed tunnel id`() {
        TunnelClient("https://api.openai.com", "bad", "key", object : McpTransport {
            override suspend fun jsonRpc(payload: JsonElement, headers: Map<String, List<String>>) = error("unused")
            override suspend fun terminate(headers: Map<String, List<String>>) = error("unused")
        })
    }
    @Test fun `embedded MCP advertises ADB tools`() = runBlocking {
        val request = TunnelJson.json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        val response = AdbMcpTransport("127.0.0.1", 5555).jsonRpc(request, emptyMap()).body.toString()
        assertTrue(response.contains("adb_shell")); assertTrue(response.contains("adb_packages")); assertTrue(response.contains("adb_status"))
    }
}
