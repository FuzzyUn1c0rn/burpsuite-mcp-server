package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeRepeaterRegistrationTest {
    @Test
    fun `draft handlers reject coercible strings and preserve omitted optional arguments`() = runBlocking {
        val property = "net.portswigger.mcp.repeater.drafts"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            val fixture = DraftServiceFixture()
            val service = fixture.create()
            val server = mockk<Server>(relaxed = true)
            val list = slot<suspend (ClientConnection, CallToolRequest) -> CallToolResult>()
            val read = slot<suspend (ClientConnection, CallToolRequest) -> CallToolResult>()
            every { server.addTool(name = "get_repeater_tabs", description = any(), inputSchema = any(), handler = capture(list)) } answers {}
            every { server.addTool(name = "get_repeater_tab", description = any(), inputSchema = any(), handler = capture(read)) } answers {}
            server.registerNativeRepeaterTools(mockk(), mockk(), service).use {
                val connection = mockk<ClientConnection>()
                fun request(args: String?) = mockk<CallToolRequest>(relaxed = true).also { request ->
                    every { request.params.arguments } returns args?.let { Json.parseToJsonElement(it).jsonObject }
                }
                fun body(result: CallToolResult) = (result.content.single() as TextContent).text
                fun invalid(result: CallToolResult) {
                    assertEquals(true, result.isError)
                    assertEquals("INVALID_ARGUMENT", Json.decodeFromString<DraftError>(body(result)).code)
                }

                for (args in listOf("""{"count":"1"}""", """{"offset":"0"}""", """{"count":true}""",
                    """{"count":null}""", """{"count":[]}""", """{"count":1.5}""")) {
                    invalid(list.captured(connection, request(args)))
                }
                assertEquals(null, service.instanceId)
                val listed = Json.decodeFromString<DraftTabList>(body(list.captured(connection, request(null))))
                val id = listed.tabs.first().tabId
                for (fragment in listOf(""""includeRequest":"false"""", """"includeResponse":0""",
                    """"maxResponseLength":"0"""", """"maxResponseLength":null""")) {
                    invalid(read.captured(connection, request("""{"tabId":"$id",$fragment}""")))
                }
                invalid(read.captured(connection, request("""{"tabId":42}""")))
                assertEquals(0, fixture.reads)
                val accepted = Json.decodeFromString<DraftTabRead>(body(read.captured(connection,
                    request("""{"tabId":"$id","includeRequest":false,"maxResponseLength":0}"""))))
                assertEquals(false, accepted.requestIncluded)
                assertEquals(null, accepted.displayedResponse.preview)
            }
        } finally {
            if (previous == null) System.clearProperty(property)
            else System.setProperty(property, previous)
        }
    }

    @Test
    fun `draft opt-in publishes only list and read with optional ranges`() {
        val property = "net.portswigger.mcp.repeater.drafts"
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            val server = mockk<Server>(relaxed = true)
            val names = mutableListOf<String>()
            val schemas = mutableListOf<ToolSchema>()
            every {
                server.addTool(name = capture(names), description = any(), inputSchema = capture(schemas), handler = any())
            } answers {}
            server.registerNativeRepeaterTools(mockk(), mockk())?.close()

            assertEquals(listOf("get_repeater_tabs", "get_repeater_tab"), names)
            assertEquals(emptyList<String>(), schemas[0].required.orEmpty())
            assertEquals(listOf("tabId"), schemas[1].required)
            assertEquals("200", schemas[0].properties?.get("count")?.jsonObject?.get("maximum")?.jsonPrimitive?.content)
            assertEquals("0", schemas[1].properties?.get("maxResponseLength")?.jsonObject?.get("minimum")?.jsonPrimitive?.content)
            assertEquals("20000", schemas[1].properties?.get("maxResponseLength")?.jsonObject?.get("maximum")?.jsonPrimitive?.content)
        } finally {
            if (previous == null) System.clearProperty(property)
            else System.setProperty(property, previous)
        }
    }

    @Test
    fun `native Repeater is opt-in and registers only draft investigation tools`() {
        val probeProperty = "net.portswigger.mcp.repeater.m0"
        val obsoleteLoopbackProperty = "m0.project.fixed.loopback"
        val previousProbe = System.getProperty(probeProperty)
        val previousLoopback = System.getProperty(obsoleteLoopbackProperty)
        try {
            System.setProperty(obsoleteLoopbackProperty, "true")
            for (probe in listOf(false, true)) {
                System.setProperty(probeProperty, probe.toString())
                val api = mockk<MontoyaApi>()
                val config = mockk<McpConfig>()
                val server = mockk<Server>(relaxed = true)
                val names = mutableListOf<String>()
                every {
                    server.addTool(name = capture(names), description = any(), inputSchema = any(), handler = any())
                } answers {}

                server.registerNativeRepeaterTools(api, config)

                val investigation = listOf("repeater_m0_probe", "get_repeater_tabs", "get_repeater_tab")
                assertEquals(if (probe) investigation else emptyList(), names)
                verify { listOf(api, config) wasNot Called }
            }
        } finally {
            if (previousProbe == null) System.clearProperty(probeProperty)
            else System.setProperty(probeProperty, previousProbe)
            if (previousLoopback == null) System.clearProperty(obsoleteLoopbackProperty)
            else System.setProperty(obsoleteLoopbackProperty, previousLoopback)
        }
    }
}
