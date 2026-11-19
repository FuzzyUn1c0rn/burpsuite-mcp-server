package net.portswigger.mcp.repeater

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.swing.text.PlainDocument

class RepeaterDraftServiceTest {
    private val fixture = DraftServiceFixture()

    @Test
    fun `paged listing shares identities without reading drafts and handles extreme offsets`() = runBlocking {
        fixture.create().use { service ->
            val first = service.list(GetRepeaterTabs(count = 1))
            val second = service.list(GetRepeaterTabs(offset = 1))
            assertEquals(first.serverInstanceId, second.serverInstanceId)
            assertEquals(1, first.nextOffset)
            assertEquals(2, first.totalCount)
            assertNull(second.nextOffset)
            assertNotEquals(first.tabs.single().tabId, second.tabs.single().tabId)
            assertEquals(first.tabs.single().name, second.tabs.single().name)
            assertTrue(service.list(GetRepeaterTabs(offset = Int.MAX_VALUE)).tabs.isEmpty())
            assertEquals(0, fixture.reads)
            assertEquals("INVALID_ARGUMENT", runCatching { service.list(GetRepeaterTabs(count = 201)) }
                .exceptionOrNull().let { (it as DraftFailure).code })
        }
    }

    @Test
    fun `metadata fingerprint uses the full model and excludes displayed response and visibility options`() = runBlocking {
        fixture.create().use { service ->
            val id = service.list(GetRepeaterTabs()).tabs.first().tabId
            val full = service.read(GetRepeaterTab(id))
            assertEquals(fixture.request, full.request)
            assertEquals("HTTP/2", full.modelProtocol)
            assertEquals("unverified", full.configuredProtocol)
            assertNull(full.negotiatedProtocol)
            fixture.responseText = "displayed-only change"
            val metadata = service.read(GetRepeaterTab(id, includeRequest = false, maxResponseLength = 0))
            assertEquals(full.fingerprint, metadata.fingerprint)
            assertFalse(metadata.requestIncluded)
            val serialized = Json { encodeDefaults = true }.encodeToString(metadata)
            assertFalse(serialized.contains("\"request\":"))
            assertFalse(serialized.contains("\"preview\":"))
            assertEquals("available", metadata.displayedResponse.availability)
            fixture.request = fixture.request.replace("hello", " world ")
            assertNotEquals(full.fingerprint, service.read(GetRepeaterTab(id, false)).fingerprint)
        }
    }

    @Test
    fun `model fingerprint canonicalizes headers only and binds destination TLS and model protocol`() {
        val head = "POST / HTTP/1.1\r\nX-Test: a\r\nX-Test: b"
        val body = " é\r\n\t\n"
        val text = "$head\r\n\r\n$body"
        fun hash(host: String = "example.test", port: Int = 18443, tls: Boolean = true,
            protocol: String = "HTTP/1.1", content: String = text) = draftModelFingerprint(host, port, tls, protocol, content)
        val expected = hash()
        assertEquals(expected, hash(content = head.replace("\r\n", "\n") + "\n\n" + body))
        assertNotEquals(expected, hash(content = text.replace("a\r\nX-Test: b", "b\r\nX-Test: a")))
        assertNotEquals(expected, hash(content = text.replace(body, body.trim())))
        assertNotEquals(expected, hash(content = text.replace(body, body.replace("\r\n", "\n"))))
        assertNotEquals(expected, hash(host = "other.test"))
        assertNotEquals(expected, hash(port = 443))
        assertNotEquals(expected, hash(tls = false))
        assertNotEquals(expected, hash(protocol = "HTTP/2"))
    }

    @Test
    fun `response previews count Unicode code points and distinguish absence from blank display`() {
        assertEquals("unavailable", displayedDraftResponse(null, 4000).availability)
        val document = PlainDocument()
        assertEquals("empty_or_not_rendered", displayedDraftResponse(document, 4000).availability)
        val emoji = "\uD83E\uDDEA"
        document.insertString(0, emoji.repeat(20_000) + "tail", null)
        for (limit in listOf(4000, 20000)) {
            val preview = displayedDraftResponse(document, limit)
            assertEquals(emoji.repeat(limit), preview.preview)
            assertTrue(preview.truncated)
        }
        assertNull(displayedDraftResponse(document, 0).preview)
        assertNull(displayedDraftResponse(document, null).preview)
    }

    @Test
    fun `size layout and model mode failures return the corresponding refusal without partial content`() = runBlocking {
        fixture.create().use { service ->
            val id = service.list(GetRepeaterTabs()).tabs.first().tabId
            suspend fun checkCode(expected: String) {
                assertEquals(expected, (runCatching { service.read(GetRepeaterTab(id, false)) }
                    .exceptionOrNull() as DraftFailure).code)
            }
            fixture.request += "x".repeat(DRAFT_REQUEST_LIMIT)
            checkCode("REQUEST_TOO_LARGE")
            fixture.request = "POST /model HTTP/2\r\n\r\nhello"
            fixture.tls = false
            checkCode("UNSUPPORTED_MODE")
            fixture.supported = false
            checkCode("UNSUPPORTED_BURP")
            assertTrue(service.list(GetRepeaterTabs()).tabs.isEmpty())
            assertFalse(service.list(GetRepeaterTabs()).capabilities.adapterSupported)
        }
    }

    @Test
    fun `project replacement and service close invalidate instance IDs and cancel pending reads`() = runBlocking {
        fixture.create().use { service ->
            val old = service.list(GetRepeaterTabs()).tabs.first().tabId
            fixture.project = "new project"
            val current = service.list(GetRepeaterTabs()).tabs.first().tabId
            assertNotEquals(old.substringBefore(':'), current.substringBefore(':'))
            assertEquals("INSTANCE_MISMATCH", (runCatching { service.read(GetRepeaterTab(old)) }
                .exceptionOrNull() as DraftFailure).code)
            val blocked = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            fixture.beforeRead = { entered.complete(Unit); blocked.await() }
            val pending = async { service.read(GetRepeaterTab(current)) }
            withTimeout(1000) { entered.await() }
            service.close()
            withTimeout(1000) { pending.join() }
            assertTrue(pending.isCancelled)
        }
    }
}

/** The transport verifier reuses this fixture without exposing an injection tool. */
internal class DraftServiceFixture {
    private val config = mockk<McpConfig> {
        every { requireDataAccessApproval } returns false
        every { alwaysAllowRepeater } returns false
    }
    private val entries = listOf(
        RepeaterTab(UUID.randomUUID().toString(), "Duplicate", null, "request"),
        RepeaterTab(UUID.randomUUID().toString(), "Duplicate", null, "request")
    )
    @Volatile var project = "original"
    @Volatile var supported = true
    @Volatile var request = "POST /model HTTP/2\r\nX-Test: one\r\nX-Test: two\r\n\r\nhello"
    @Volatile var protocol = "HTTP/2"
    @Volatile var tls = true
    @Volatile var responseText: String? = "\uD83E\uDDEA".repeat(20_001)
    @Volatile var reads = 0
    @Volatile var beforeRead: suspend () -> Unit = {}

    fun create(policy: McpConfig = config) = RepeaterDraftService(policy, { project },
        { DraftCapabilities("2026.11.2", "COMMUNITY_EDITION", supported) }, newNative = {
            mockk { coEvery { tabs(any()) } returns entries }
        }, readModel = { _, id, preview, _ ->
            beforeRead()
            reads++
            val tab = entries.firstOrNull { it.tabId == id }
                ?: throw DraftFailure("TAB_NOT_FOUND", "List Repeater tabs again.")
            val display = responseText?.let { text -> PlainDocument().apply { insertString(0, text, null) } }
            val text = request
            ObservedDraft(DraftUiState(tab, "127.0.0.1", 18443, tls, "idle",
                displayedDraftResponse(display, preview)), text.toByteArray(Charsets.UTF_8), protocol,
                text.substringAfter("\r\n\r\n", "").toByteArray(Charsets.UTF_8).size)
        })
}
