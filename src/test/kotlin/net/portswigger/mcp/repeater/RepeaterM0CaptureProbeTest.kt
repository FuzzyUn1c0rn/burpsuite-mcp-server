package net.portswigger.mcp.repeater

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RepeaterM0CaptureProbeTest {
    @Test
    fun `diagnostics distinguish added length from body encoding changes without normalizing matcher`() {
        val editor = "POST /synthetic HTTP/2\r\nHost: example.test\r\nX-Repeat: one\r\nX-Repeat: two\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\n\r\ncafé"
        val hook = editor.replace("\r\n\r\n", "\r\nContent-Length: 4\r\n\r\n")
        val probe = RepeaterM0CaptureProbe()
        probe.request(editor, hook, 42, true, byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte()))
        probe.response(42)
        val result = probe.snapshot()
        val sample = result.sameServiceRequestSamples.single()
        assertFalse(sample.canonicalRequestEqual)
        assertFalse(sample.headerBlockEqual)
        assertTrue(sample.headersEqualExceptContentLength)
        assertTrue(sample.bodyEqual)
        assertEquals(5, sample.editorBodyUtf8Length)
        assertEquals(5, sample.hookBodyUtf8Length)
        assertEquals(4, sample.montoyaBodyByteLength)
        assertNotEquals(sample.editorBodySha256, sample.montoyaBodyByteSha256)
        assertEquals("HTTP/2", sample.hookRequestLineHttpVersion)
        assertEquals(listOf("4"), sample.hookContentLengthValues)
        assertEquals(listOf("utf-8"), sample.editorContentTypeCharsets)
        assertEquals(listOf("utf-8"), sample.hookContentTypeCharsets)
        assertEquals(42, result.responseSamples.single().messageId)
        assertTrue(result.responseSamples.single().elapsedMsSinceObserverCreation >= sample.elapsedMsSinceObserverCreation)
        // Observation never changes the exact matching comparison.
        assertNotEquals(canonicalRequest(editor), canonicalRequest(hook))
    }

    @Test
    fun `comparison preserves repeated header order and significant body newlines`() {
        val editor = "POST / HTTP/1.1\r\nX-Test: one\r\nX-Test: two\r\n\r\na\r\nb"
        val hook = "POST / HTTP/1.1\nX-Test: two\nX-Test: one\n\na\nb"
        val probe = RepeaterM0CaptureProbe()
        probe.request(editor, hook, 1, true)
        val sample = probe.snapshot().sameServiceRequestSamples.single()
        assertFalse(sample.headersEqualExceptContentLength)
        assertFalse(sample.bodyEqual)
        assertFalse(sample.headerSamples.first().valueEqual)
        assertEquals("other", sample.headerSamples.first().editorHeaderCategory)
        assertNotEquals(sample.editorBodySha256, sample.hookBodySha256)
    }

    @Test
    fun `diagnostics bound samples and contain no raw documents header values or body`() {
        val secret = "distinct-secret-value"
        val headers = (0..30).joinToString("\r\n") { "X-$secret-$it: $secret" }
        val text = "POST /$secret HTTP/1.1\r\n$headers\r\nContent-Length: $secret\r\n\r\n$secret"
        val probe = RepeaterM0CaptureProbe()
        repeat(100) {
            probe.request(text, text, it, true)
            probe.response(it)
        }
        val result = probe.snapshot()
        assertEquals(100, result.repeaterRequestCallbacks)
        assertEquals(100, result.repeaterResponseCallbacks)
        assertEquals(8, result.sameServiceRequestSamples.size)
        assertEquals(8, result.responseSamples.size)
        assertTrue(result.sameServiceRequestSamples.all { it.headerSamples.size == 8 && it.headerSamplesTruncated })
        val serialized = Json.encodeToString(result)
        assertFalse(serialized.contains(secret))
        assertTrue(serialized.length < 32_768)
        assertEquals(listOf("non_numeric_or_oversized"), result.sameServiceRequestSamples.first().editorContentLengthValues)
    }

    @Test
    fun `other destination callback counts are separate and old result excludes disabled diagnostics`() {
        val probe = RepeaterM0CaptureProbe()
        probe.request("", "", 1, false)
        assertEquals(1, probe.snapshot().repeaterRequestCallbacks)
        assertTrue(probe.snapshot().sameServiceRequestSamples.isEmpty())
        val oldResult = RepeaterSendResult("tab", 1, "request", "response", false)
        assertFalse(Json.encodeToString(oldResult).contains("m0CaptureDiagnostics"))
    }
}
