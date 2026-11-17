package net.portswigger.mcp.repeater

import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.*
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RepeaterExchangeCaptureTest {
    @Test
    fun `M0 body diagnostic failure does not interrupt exact capture or request pass through`() {
        val probe = RepeaterM0CaptureProbe()
        val capture = RepeaterExchangeCapture(expected, probe)
        val outgoing = request(5)
        every { outgoing.body() } throws IllegalStateException("must not expose diagnostic exception detail")
        capture.arm()
        capture.handleHttpRequestToBeSent(outgoing)
        capture.handleHttpResponseReceived(response(5))
        assertTrue(capture.result.isCompleted)
        assertEquals(1, probe.snapshot().diagnosticFailures)
        assertEquals(1, probe.snapshot().repeaterRequestCallbacks)
        assertNull(probe.snapshot().sameServiceRequestSamples.single().montoyaBodyByteLength)
        verify(exactly = 1) { RequestToBeSentAction.continueWith(outgoing, outgoing.annotations()) }
    }

    @Test
    fun `M0 probe observes mismatched hook and response without attributing or changing traffic`() {
        val probe = RepeaterM0CaptureProbe()
        val capture = RepeaterExchangeCapture(expected, probe)
        val changed = request(4, text = raw.replace("\r\n\r\n", "\r\nContent-Length: 0\r\n\r\n"))
        every { changed.body().bytes } returns byteArrayOf()
        val received = response(4)
        capture.arm()
        capture.handleHttpRequestToBeSent(changed)
        capture.handleHttpResponseReceived(received)
        assertFalse(capture.result.isCompleted)
        assertFalse(probe.snapshot().sameServiceRequestSamples.single().canonicalRequestEqual)
        assertEquals(4, probe.snapshot().responseSamples.single().messageId)
        verify(exactly = 1) { RequestToBeSentAction.continueWith(changed, changed.annotations()) }
        verify(exactly = 1) { ResponseReceivedAction.continueWith(received, received.annotations()) }
    }

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
