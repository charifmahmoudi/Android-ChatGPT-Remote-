package com.charifmahmoudi.chatgptremote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ProtocolTest {
    @Test
    fun `poll sends canonical path and diagnostic headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val client = TunnelClient(
                server.url("/").toString().removeSuffix("/"),
                "tunnel_0123456789abcdef0123456789abcdef",
                "runtime-key",
                UnusedTransport,
            )

            assertTrue(client.pollOnce().isEmpty())
            val request = server.takeRequest()
            assertEquals("/v1/tunnels/tunnel_0123456789abcdef0123456789abcdef/poll?limit=25&timeout_ms=15000", request.path)
            assertEquals("Bearer runtime-key", request.getHeader("Authorization"))
            assertEquals("android-kotlin-tunnel-client", request.getHeader("X-Tunnel-Client-Name"))
            assertEquals("0.4.1", request.getHeader("X-Tunnel-Client-Version"))
        }
    }

    @Test(expected = SecurityException::class)
    fun `poll exposes authorization failures for operator action`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            TunnelClient(
                server.url("/").toString().removeSuffix("/"),
                "tunnel_0123456789abcdef0123456789abcdef",
                "runtime-key",
                UnusedTransport,
            ).pollOnce()
        }
    }

    @Test
    fun `diagnostic log exports version and bounded events`() {
        DiagnosticLog.clear()
        repeat(600) { DiagnosticLog.record("test", "event=$it") }

        val report = DiagnosticLog.export("0.4.1", 7)

        assertTrue(report.contains("Version: 0.4.1 (7)"))
        assertTrue(report.contains("event=599"))
        assertTrue(!report.contains("event=0\n"))
        assertEquals(500, report.lineSequence().count { "[test]" in it })
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

    @Test
    fun `embedded MCP returns invalid request instead of throwing on non-object payload`() = runBlocking {
        val response = AdbMcpTransport("127.0.0.1", 5555)
            .jsonRpc(JsonPrimitive("invalid"), emptyMap())
            .body
            .toString()
        assertTrue(response.contains("-32600"))
    }

    @Test
    fun `unknown JSON-RPC notification has no response body`() = runBlocking {
        val request = TunnelJson.json.parseToJsonElement(
            """{"jsonrpc":"2.0","method":"future/notification"}""",
        )
        val result = AdbMcpTransport("127.0.0.1", 5555).jsonRpc(request, emptyMap())
        assertEquals(204, result.code)
        assertNull(result.body)
    }

    @Test
    fun `tool call rejects non-object arguments`() = runBlocking {
        val request = TunnelJson.json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"adb_shell","arguments":"bad"}}""",
        )
        val response = AdbMcpTransport("127.0.0.1", 5555)
            .jsonRpc(request, emptyMap())
            .body
            .toString()
        assertTrue(response.contains("-32602"))
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
