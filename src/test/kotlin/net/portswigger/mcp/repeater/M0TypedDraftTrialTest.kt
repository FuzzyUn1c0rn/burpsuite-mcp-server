package net.portswigger.mcp.repeater

import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class M0TypedDraftTrialTest {
    @Test
    fun `typed draft requires exact model protocol service repeat order and UTF8 body`() {
        val text = " café \r\nline\n\t"
        val body = text.toByteArray(Charsets.UTF_8)
        val head = "POST /m0-typed-draft HTTP/2\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val full = head + body

        fun header(name: String, value: String) = mockk<HttpHeader> {
            every { name() } returns name
            every { value() } returns value
        }

        val first = header("x-m0-variant", "first")
        val second = header("x-m0-variant", "second")
        val length = header("content-length", body.size.toString())
        val request = mockk<HttpRequest>()
        every { request.httpService().host() } returns "127.0.0.1"
        every { request.httpService().port() } returns 18443
        every { request.httpService().secure() } returns true
        every { request.httpVersion() } returns "HTTP/2"
        every { request.method() } returns "POST"
        every { request.path() } returns "/m0-typed-draft"
        every { request.headers() } returns listOf(first, second, length)
        every { request.toByteArray().bytes } returns full
        every { request.toByteArray().length() } returns full.size
        every { request.bodyOffset() } returns head.size
        every { request.body().length() } returns body.size
        every { request.body().bytes } returns body
        fun compare() = M0TypedDraftTrialExtension.checkTypedDraftModel(request)

        assertArrayEquals(full, compare())
        every { request.httpVersion() } returns "HTTP/1.1"
        assertNull(compare())
        every { request.httpVersion() } returns "HTTP/2"
        every { request.httpService().port() } returns 443
        assertNull(compare())
        every { request.httpService().port() } returns 18443
        every { request.headers() } returns listOf(second, first, length)
        assertNull(compare())
        every { request.headers() } returns listOf(first, second, length)
        every { request.body().bytes } returns text.toByteArray(Charsets.ISO_8859_1)
        assertNull(compare())
        every { request.body().bytes } returns body
        every { request.toByteArray().bytes } returns head + body.copyOf().apply { this[lastIndex] = 32 }
        assertNull(compare())
    }
}
