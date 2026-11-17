package net.portswigger.mcp.repeater

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.*
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RepeaterExchangeCaptureTest {
    private val raw = "GET /one HTTP/1.1\r\nHost: example.test\r\n\r\n"
    private val expected = RepeaterSnapshot("tab", "name", emptyList(), "example.test", 443, true, raw, null, "hash")

    @BeforeEach
    fun setup() {
        mockkStatic(RequestToBeSentAction::class)
        mockkStatic(ResponseReceivedAction::class)
        every { RequestToBeSentAction.continueWith(any(), any()) } returns mockk()
        every { ResponseReceivedAction.continueWith(any(), any()) } returns mockk()
    }

    @AfterEach
    fun cleanup() { unmockkAll() }

    private fun request(id: Int, isRepeater: Boolean = true, host: String = "example.test", text: String = raw) =
        mockk<HttpRequestToBeSent>(relaxed = true).also { request ->
            every { request.messageId() } returns id
            every { request.toolSource().isFromTool(ToolType.REPEATER) } returns isRepeater
            every { request.httpService().host() } returns host
            every { request.httpService().port() } returns 443
            every { request.httpService().secure() } returns true
            every { request.toString() } returns text
        }

    private fun response(id: Int) = mockk<HttpResponseReceived>(relaxed = true).also { response ->
        every { response.messageId() } returns id
        every { response.toolSource().isFromTool(ToolType.REPEATER) } returns true
        every { response.initiatingRequest().toString() } returns raw
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\nfresh"
    }

    @Test
    fun `captures fresh response by id and ignores old unrelated traffic`() {
        val capture = RepeaterExchangeCapture(expected)
        capture.handleHttpRequestToBeSent(request(10)) // Before the native click is armed.
        capture.arm()
        capture.handleHttpResponseReceived(response(10))
        capture.handleHttpRequestToBeSent(request(900, isRepeater = false))
        capture.handleHttpRequestToBeSent(request(901, host = "different.test"))
        capture.handleHttpRequestToBeSent(request(902, text = raw.replace("/one", "/other")))
        assertFalse(capture.result.isCompleted)
        capture.handleHttpRequestToBeSent(request(1000))
        capture.handleHttpResponseReceived(response(999))
        assertFalse(capture.result.isCompleted)
        capture.handleHttpResponseReceived(response(1000))
        assertTrue(capture.result.isCompleted)
    }
}
