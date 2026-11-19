package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.KtorServerManager
import net.portswigger.mcp.STREAMABLE_HTTP_PATH
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.TestSseMcpClient
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Independent-verifier suite. Starts a real loopback MCP server with an injected synthetic draft model. */
class RepeaterDraftTransportTest {
    @Test
    fun `both transports share the draft service and serialize synthetic response snapshots`() = runBlocking {
        val mode = "net.portswigger.mcp.repeater.drafts"
        val previous = System.getProperty(mode)
        val api = mockk<MontoyaApi>(relaxed = true)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val config = mockk<McpConfig>(relaxed = true) {
            every { host } returns "127.0.0.1"
            every { this@mockk.port } returns port
            every { requireDataAccessApproval } returns false
            every { alwaysAllowRepeater } returns false
        }
        val fixture = DraftServiceFixture()
        val manager = KtorServerManager(api, fixture::create)
        val url = "http://127.0.0.1:$port"
        val sse = TestSseMcpClient()
        val streamable = TestSseMcpClient()
        val reconnect = TestSseMcpClient()
        val restarted = TestSseMcpClient()
        val clients = listOf(sse, streamable, reconnect, restarted)

        suspend fun start() {
            val ready = CompletableDeferred<Unit>()
            manager.start(config) { state ->
                when (state) {
                    ServerState.Running -> ready.complete(Unit)
                    is ServerState.Failed -> ready.completeExceptionally(state.exception)
                    else -> Unit
                }
            }
            withTimeout(10_000) { ready.await() }
        }

        suspend fun TestSseMcpClient.list() =
            Json.decodeFromString<DraftTabList>(checkNotNull(callTool("get_repeater_tabs", emptyMap())).text())
        suspend fun TestSseMcpClient.read(id: String, options: Map<String, Any> = emptyMap()) =
            Json.decodeFromString<DraftTabRead>(checkNotNull(callTool("get_repeater_tab", options + ("tabId" to id))).text())

        System.setProperty(mode, "true")
        try {
            start()
            sse.connectToServer(url)
            streamable.connectToStreamableHttpServer(url + STREAMABLE_HTTP_PATH)
            for (client in listOf(sse, streamable)) {
                val tools = client.listTools()
                val native = tools.filter { it.name.startsWith("get_repeater_") }
                assertEquals(setOf("get_repeater_tabs", "get_repeater_tab"), native.map { it.name }.toSet())
                assertTrue(tools.none { it.name in setOf("send_repeater_tab", "get_repeater_send_status") })
                assertTrue(native.single { it.name == "get_repeater_tabs" }.inputSchema.required.orEmpty().isEmpty())
                assertEquals(listOf("tabId"), native.single { it.name == "get_repeater_tab" }.inputSchema.required)
            }

            val listed = sse.list()
            assertEquals(listed, streamable.list())
            val id = listed.tabs.first().tabId
            for (client in listOf(sse, streamable)) {
                val wrongCount = checkNotNull(client.callTool("get_repeater_tabs", mapOf("count" to "1")))
                val wrongFlag = checkNotNull(client.callTool("get_repeater_tab",
                    mapOf("tabId" to id, "includeRequest" to "false")))
                for (invalid in listOf(wrongCount, wrongFlag)) {
                    assertEquals(true, invalid.isError)
                    assertEquals("INVALID_ARGUMENT", Json.decodeFromString<DraftError>(invalid.text()).code)
                }
            }
            val full = streamable.read(id)
            assertEquals("available", full.displayedResponse.availability)
            assertEquals("\uD83E\uDDEA".repeat(4_000), full.displayedResponse.preview)
            val max = sse.read(id, mapOf("includeRequest" to false, "maxResponseLength" to 20_000))
            assertEquals("\uD83E\uDDEA".repeat(20_000), max.displayedResponse.preview)
            assertTrue(max.displayedResponse.truncated)
            assertEquals(full.fingerprint, max.fingerprint)
            assertNull(max.request)
            assertNull(sse.read(id, mapOf("maxResponseLength" to 0)).displayedResponse.preview)
            fixture.responseText = ""
            assertEquals("empty_or_not_rendered", streamable.read(id).displayedResponse.availability)
            fixture.responseText = null
            val unavailable = sse.read(id)
            assertEquals("unavailable", unavailable.displayedResponse.availability)
            assertEquals(full.fingerprint, unavailable.fingerprint)
            val invalid = checkNotNull(streamable.callTool("get_repeater_tabs", mapOf("count" to 201)))
            assertEquals(true, invalid.isError)
            assertEquals("INVALID_ARGUMENT", Json.parseToJsonElement(invalid.text()).jsonObject["code"]?.jsonPrimitive?.content)

            sse.close()
            reconnect.connectToServer(url)
            assertEquals(listed, reconnect.list())
            reconnect.close()
            streamable.close()
            start()
            restarted.connectToStreamableHttpServer(url + STREAMABLE_HTTP_PATH)
            assertNotEquals(listed.serverInstanceId, restarted.list().serverInstanceId)
            val stale = checkNotNull(restarted.callTool("get_repeater_tab", mapOf("tabId" to id)))
            assertEquals(true, stale.isError)
            val error = Json.decodeFromString<DraftError>(stale.text())
            assertEquals("INSTANCE_MISMATCH", error.code)
            assertEquals(id, error.tabId)
            verify(exactly = 0) { api.http() }
        } finally {
            try { clients.filter { it.isConnected() }.forEach { it.close() } }
            finally {
                manager.shutdown()
                if (previous == null) System.clearProperty(mode) else System.setProperty(mode, previous)
            }
        }
    }

    private fun CallToolResult.text() = (content.single() as TextContent).text
}
