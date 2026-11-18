package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.asInputSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RepeaterSendDisabledTest {
    @Test
    fun `registered Send rejects legacy calls before accessing Burp or permission settings`() = runBlocking {
        val api = mockk<MontoyaApi>()
        val config = mockk<McpConfig>()
        val server = mockk<Server>(relaxed = true)
        val handler = slot<suspend (ClientConnection, CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = "send_repeater_tab", description = any(),
                inputSchema = SendRepeaterTab::class.asInputSchema(), handler = capture(handler)
            )
        } answers {}
        server.registerNativeRepeaterTools(api, config)

        val cases = listOf(
            """{"tabId":"tab","expectedFingerprint":"fingerprint"}""" to "UNVERIFIED_SETTINGS",
            """{"tabId":"tab","expectedFingerprint":"fingerprint","timeoutMs":100,"maxResponseLength":7}""" to "UNVERIFIED_SETTINGS",
            """{"tabId":"tab","expectedFingerprint":"fingerprint","approved":true}""" to "INVALID_ARGUMENT",
            """{"tabId":"tab","expectedFingerprint":"fingerprint","timeoutMs":99}""" to "INVALID_ARGUMENT"
        )
        val connection = mockk<ClientConnection>()
        for ((args, code) in cases) {
            val request = mockk<CallToolRequest>(relaxed = true)
            every { request.params.arguments } returns Json.parseToJsonElement(args).jsonObject
            val result = handler.captured(connection, request)
            assertEquals(true, result.isError)
            val error = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
            assertEquals(code, error["code"]?.jsonPrimitive?.content)
            assertEquals("not_attempted", error["dispatchState"]?.jsonPrimitive?.content)
        }
        verify { listOf(api, config, connection) wasNot Called }
    }
}
