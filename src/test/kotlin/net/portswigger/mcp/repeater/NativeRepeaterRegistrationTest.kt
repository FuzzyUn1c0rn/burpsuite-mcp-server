package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeRepeaterRegistrationTest {
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
