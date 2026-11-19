package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.Server
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NativeRepeaterRegistrationTest {
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
