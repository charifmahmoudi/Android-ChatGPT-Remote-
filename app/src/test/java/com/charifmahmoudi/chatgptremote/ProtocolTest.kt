package com.charifmahmoudi.chatgptremote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun `diagnostic log exports version and bounded events`() {
        DiagnosticLog.clear()
        repeat(300) { DiagnosticLog.record("test", "event=$it") }

        val report = DiagnosticLog.export("0.3.2", 5)

        assertTrue(report.contains("Version: 0.3.2 (5)"))
        assertTrue(report.contains("event=299"))
        assertTrue(!report.contains("event=0\n"))
        assertEquals(250, report.lineSequence().count { "[test]" in it })
    }

    @Test
    fun `decodes documented command and ignores additions`() {
        val body = """
            {
              "commands": [{
                "request_id": "req_1",
                "shard_token": "opaque",
                "command_type": "jsonrpc",
                "channel": "main",
                "response_timeout": "30s",
                "headers": {"Mcp-Session-Id": ["s1"]},
                "jsonrpc": {"jsonrpc": "2.0", "id": "rpc_1", "method": "tools/list"},
                "future": true
              }]
            }
        """.trimIndent()

        val command = TunnelJson.json.decodeFromString<PollEnvelope>(body).commands.single()

        assertEquals("req_1", command.requestId)
        assertEquals("main", command.channel)
        assertEquals(30_000L, ResponseTimeoutParser.toMillis(command.responseTimeout))
        assertEquals(listOf("s1"), command.headers["Mcp-Session-Id"])
    }

    @Test
    fun `missing channel uses protocol default`() {
        val body = """{"commands":[{"request_id":"r","shard_token":"s","command_type":"session_termination"}]}"""
        val command = TunnelJson.json.decodeFromString<PollEnvelope>(body).commands.single()
        assertEquals("main", command.channel)
    }

    @Test
    fun `timeout parser follows wire grammar and fails open`() {
        assertEquals(0L, ResponseTimeoutParser.toMillis(JsonPrimitive("0s")))
        assertEquals(4_500L, ResponseTimeoutParser.toMillis(JsonPrimitive("4500ms")))
        assertEquals(0L, ResponseTimeoutParser.toMillis(JsonPrimitive("999ns")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("4.5s")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("1m30s")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive(-1)))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive(" 1s")))
        assertNull(ResponseTimeoutParser.toMillis(JsonPrimitive("999999999999999999999999h")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects malformed tunnel id`() {
        TunnelClient("https://api.openai.com", "bad", "key", UnusedTransport)
    }

    @Test
    fun `embedded MCP advertises all ADB tools`() = runBlocking {
        val request = TunnelJson.json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""",
        )
        val response = AdbMcpTransport("127.0.0.1", 5555)
            .jsonRpc(request, emptyMap())
            .body
            .toString()

        listOf("adb_status", "adb_shell", "adb_packages", "adb_properties").forEach {
            assertTrue(response.contains(it))
        }
    }

    private object UnusedTransport : McpTransport {
        override suspend fun jsonRpc(
            payload: JsonElement,
            headers: Map<String, List<String>>,
        ): McpResult = error("unused")

        override suspend fun terminate(headers: Map<String, List<String>>): McpResult =
            error("unused")
    }
}
