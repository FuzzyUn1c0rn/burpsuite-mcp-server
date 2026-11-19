package net.portswigger.mcp.repeater

import burp.api.montoya.core.ToolType
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.InvocationType
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JTable
import javax.swing.SwingUtilities

class RepeaterM0ByteWriteTest {
    @AfterEach fun cleanup() = unmockkAll()

    private class Fixture {
        val oldBytes = "GET /old HTTP/2\r\nHost: example.test\r\nContent-Length: 5\r\n\r\nhello".toByteArray()
        val newBytes = "POST /PRIVATE_PATH HTTP/2\r\nHost: other-authority.test\r\nX-Test: one\r\nX-Test: two\r\nContent-Length: 5\r\n\r\ncafé".toByteArray(Charsets.UTF_8)
        val old = model(oldBytes)
        val replacement = model(newBytes)
        var current = old
        val source = JTable()
        var owned = true
        var idle = true
        var nativeServiceValid = true
        var permitted = true
        var setters = 0
        var setterHook: () -> Unit = {}
        var builder: (HttpService, ByteArray) -> HttpRequest = { service, bytes ->
            assertSame(old.httpService(), service)
            assertArrayEquals(newBytes, bytes)
            replacement
        }
        val editor = mockk<MessageEditorHttpRequestResponse>(relaxed = true)
        val event = mockk<ContextMenuEvent>(relaxed = true)
        val target = M0ContextTarget("tab", "Hex", canMutateNow = { idle }, matchesNativeService = { host, port, tls ->
            nativeServiceValid && host == "example.test" && port == 443 && tls
        }) { owned && it === source }
        init {
            every { event.isFromTool(ToolType.REPEATER) } returns true
            every { event.invocationType() } returns InvocationType.MESSAGE_EDITOR_REQUEST
            every { event.inputEvent() } returns MouseEvent(source, MouseEvent.MOUSE_RELEASED, 2000, 0, 1, 1, 1, true, MouseEvent.BUTTON3)
            every { event.messageEditorRequestResponse() } returns Optional.of(editor)
            every { editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.REQUEST
            every { editor.requestResponse().request() } answers { current }
            every { editor.setRequest(any()) } answers { setters++; setterHook(); current = firstArg() }
        }
        fun plan(hash: String = m0ByteHash(oldBytes)) = M0ByteWritePlan.parse(hash, "example.test", 443, true,
            "HTTP/2", Base64.getEncoder().encodeToString(newBytes))
        fun probe(plan: M0ByteWritePlan = plan()) = RepeaterM0ContextProbe(target, 1000, plan,
            { permitted }, { service, bytes -> builder(service, bytes) })
        fun invoke(probe: RepeaterM0ContextProbe) = SwingUtilities.invokeAndWait { assertTrue(probe.provideMenuItems(event).isEmpty()) }
        fun model(bytes: ByteArray, host: String = "example.test", version: String = "HTTP/2"): HttpRequest {
            val request = mockk<HttpRequest>(relaxed = true)
            val offset = bytes.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
            val body = bytes.copyOfRange(offset, bytes.size)
            every { request.toByteArray().bytes } returns bytes
            every { request.toByteArray().length() } returns bytes.size
            every { request.body().bytes } returns body
            every { request.body().length() } returns body.size
            every { request.bodyOffset() } returns offset
            every { request.httpService().host() } returns host
            every { request.httpService().port() } returns 443
            every { request.httpService().secure() } returns true
            every { request.httpVersion() } returns version
            return request
        }
    }

    @Test fun `exact byte setter changes whole request once and returns metadata with UTF8 bytes preserved`() = runBlocking {
        val f = Fixture()
        val probe = f.probe()
        f.invoke(probe)
        val observed = probe.result.await()
        assertEquals("mutation_confirmed", observed.status)
        assertTrue(observed.modelWriteAttempted && observed.modelWriteConfirmed && observed.mutationMayHaveOccurred)
        assertEquals(m0ByteHash(f.newBytes), observed.modelRequestSha256)
        assertEquals("850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e", observed.modelBodySha256)
        assertFalse(observed.configuredProtocolVerified)
        assertEquals("unverified", observed.configuredProtocol)
        assertTrue(checkNotNull(observed.constructorDiagnostics).exactBytesEqual)
        val readback = checkNotNull(observed.readbackDiagnostics)
        assertEquals(m0ByteHash(f.newBytes), readback.requestSha256)
        assertTrue(readback.equalsReplacementBytes && readback.serviceEqual && readback.messageVersionEqual)
        assertFalse(readback.equalsOriginalBytes)
        assertFalse(Json.encodeToString(observed).contains("PRIVATE_PATH"))
        f.invoke(probe)
        assertEquals(1, f.setters)
        verify(exactly = 0) { f.editor.setResponse(any()) }
        verify(exactly = 0) { f.current.toString() }
    }

    @Test fun `stale full bytes service or model version refuse before setter`() = runBlocking {
        for (scenario in listOf("hash", "bytes", "host", "port", "tls", "version", "owned", "busy", "native_service", "permission", "response", "viewer")) {
            val f = Fixture()
            val plan = f.plan(if (scenario == "hash") "0".repeat(64) else m0ByteHash(f.oldBytes))
            when (scenario) {
                "bytes" -> f.current = f.model(f.oldBytes.toString(Charsets.UTF_8).replace("/old", "/changed").toByteArray())
                "host" -> every { f.old.httpService().host() } returns "other.test"
                "port" -> every { f.old.httpService().port() } returns 8443
                "tls" -> every { f.old.httpService().secure() } returns false
                "version" -> every { f.old.httpVersion() } returns "HTTP/1.1"
                "owned" -> f.owned = false
                "busy" -> f.idle = false
                "native_service" -> f.nativeServiceValid = false
                "permission" -> f.permitted = false
                "response" -> every { f.editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.RESPONSE
                "viewer" -> every { f.event.invocationType() } returns InvocationType.MESSAGE_VIEWER_REQUEST
            }
            val probe = f.probe(plan)
            f.invoke(probe)
            assertEquals("rejected", probe.result.await().status, scenario)
            assertFalse(probe.result.await().mutationMayHaveOccurred, scenario)
            assertEquals(0, f.setters, scenario)
            if (scenario == "permission") verify(exactly = 0) { f.editor.requestResponse() }
        }
    }

    @Test fun `constructor discrepancies and changes at final boundary refuse before setter`() = runBlocking {
        for (scenario in listOf("bytes", "service", "version", "old_bytes_changed", "permission", "identity", "busy", "native_service")) {
            val f = Fixture()
            f.builder = { _, _ ->
                when (scenario) {
                    "old_bytes_changed" -> f.current = f.model(f.oldBytes + 'x'.code.toByte())
                    "permission" -> f.permitted = false
                    "identity" -> f.owned = false
                    "busy" -> f.idle = false
                    "native_service" -> f.nativeServiceValid = false
                }
                when (scenario) {
                    "bytes" -> f.model(f.newBytes + 'x'.code.toByte())
                    "service" -> f.model(f.newBytes, host = "other.test")
                    "version" -> f.model(f.newBytes, version = "HTTP/1.1")
                    else -> f.replacement
                }
            }
            val probe = f.probe()
            f.invoke(probe)
            assertEquals("rejected", probe.result.await().status, scenario)
            assertEquals(0, f.setters, scenario)
        }
    }

    @Test fun `setter faults stale readback and post-write identity permission changes report possible mutation`() = runBlocking {
        for (scenario in listOf("setter", "stale", "readback", "identity", "permission")) {
            val f = Fixture()
            when (scenario) {
                "setter" -> f.setterHook = { error("PRIVATE_SETTER_FAILURE") }
                "stale" -> every { f.editor.requestResponse().request() } returns f.old
                "readback" -> every { f.editor.requestResponse().request() } answers {
                    if (f.setters > 0) error("PRIVATE_READBACK_FAILURE") else f.current
                }
                "identity" -> f.setterHook = { f.owned = false }
                "permission" -> f.setterHook = { f.permitted = false }
            }
            val probe = f.probe()
            f.invoke(probe)
            val observed = probe.result.await()
            assertEquals("mutation_unconfirmed", observed.status, scenario)
            assertTrue(observed.mutationMayHaveOccurred && observed.modelWriteAttempted, scenario)
            assertFalse(observed.modelWriteConfirmed, scenario)
            assertNull(observed.modelRequestSha256, scenario)
            assertFalse(Json.encodeToString(observed).contains("PRIVATE_"), scenario)
            assertEquals(1, f.setters, scenario)
            if (scenario == "stale") {
                val readback = checkNotNull(observed.readbackDiagnostics)
                assertEquals(m0ByteHash(f.oldBytes), readback.requestSha256)
                assertTrue(readback.equalsOriginalBytes && readback.serviceEqual && readback.messageVersionEqual)
                assertFalse(readback.equalsReplacementBytes)
            }
        }
    }

    @Test fun `reentrant second context cannot invoke second setter`() = runBlocking {
        val f = Fixture()
        val probe = f.probe()
        f.setterHook = { assertTrue(probe.provideMenuItems(f.event).isEmpty()) }
        f.invoke(probe)
        assertTrue(probe.result.await().modelWriteConfirmed)
        assertEquals(1, f.setters)
    }

    @Test fun `close before admission and late callback never mutate`() {
        val f = Fixture()
        val probe = f.probe()
        f.builder = { _, _ -> probe.close(); f.replacement }
        f.invoke(probe)
        assertTrue(probe.result.isCancelled)
        assertEquals(0, f.setters)
        val second = f.probe()
        val deadline = second.closeForTimeout("tab", "Hex")
        assertFalse(deadline.modelWriteAttempted || deadline.mutationMayHaveOccurred)
        f.invoke(second)
        assertEquals(0, f.setters)
    }

    @Test fun `timeout while setter is entered preserves attempt without blocking or repeating`() {
        val f = Fixture()
        val probe = f.probe()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.setterHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val worker = Thread { f.invoke(probe) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deadline = probe.closeForTimeout("tab", "Hex")
            assertEquals("mutation_unconfirmed", deadline.status)
            assertTrue(deadline.modelWriteAttempted && deadline.mutationMayHaveOccurred)
            assertFalse(deadline.modelWriteConfirmed)
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertTrue(probe.result.isCancelled)
        f.invoke(probe)
        assertEquals(1, f.setters)
    }

    @Test fun `byte plan rejects malformed ambiguous binary and noncanonical input without echoing it`() {
        val f = Fixture()
        val invalid = listOf(
            f.newBytes.toString(Charsets.UTF_8).replace("HTTP/2", "HTTP/1.1").toByteArray(),
            f.newBytes.toString(Charsets.UTF_8).replace("Content-Length: 5", "Content-Length: 4").toByteArray(),
            f.newBytes.toString(Charsets.UTF_8).replace("Content-Length: 5", "Content-Length: 5\r\nContent-Length: 5").toByteArray(),
            f.newBytes.toString(Charsets.UTF_8).replace("Content-Length: 5", "Transfer-Encoding: chunked").toByteArray(),
            f.oldBytes + byteArrayOf(0), f.oldBytes + byteArrayOf(0xff.toByte())
        )
        for (bytes in invalid) {
            val failure = assertThrows(RuntimeException::class.java) {
                M0ByteWritePlan.parse(m0ByteHash(f.oldBytes), "example.test", 443, true, "HTTP/2", Base64.getEncoder().encodeToString(bytes))
            }
            assertTrue(failure.message.orEmpty().startsWith("M0_"))
            assertFalse(failure.message.orEmpty().contains("PRIVATE_PATH"))
        }
        for (encoded in listOf("!!!", "QQ", "A".repeat(349529), Base64.getEncoder().encodeToString(ByteArray(262145)))) {
            assertThrows(RuntimeException::class.java) {
                M0ByteWritePlan.parse(m0ByteHash(f.oldBytes), "example.test", 443, true, "HTTP/2", encoded)
            }
        }
    }

    @Test fun `request byte limit and immutable plan preserve exact replacement bytes`() {
        var bodyLength = 262000
        fun header() = "POST / HTTP/2\r\nHost: example.test\r\nContent-Length: $bodyLength\r\n\r\n".toByteArray()
        repeat(3) { bodyLength = 262144 - header().size }
        val exact = header() + ByteArray(bodyLength) { 'a'.code.toByte() }
        assertEquals(262144, exact.size)
        val plan = M0ByteWritePlan.parse("0".repeat(64), "example.test", 443, true, "HTTP/2", Base64.getEncoder().encodeToString(exact))
        val expectedHash = m0ByteHash(exact)
        exact[exact.lastIndex] = 'b'.code.toByte()
        assertEquals(expectedHash, plan.replacementHash)
        assertEquals(expectedHash, m0ByteHash(plan.replacementCopy()))
        val copy = plan.replacementCopy()
        copy[copy.lastIndex] = 'c'.code.toByte()
        assertEquals(expectedHash, m0ByteHash(plan.replacementCopy()))
    }

    @Test fun `provider cleanup fault retains attempted mutation metadata and closes late callbacks`() = runBlocking {
        val f = Fixture()
        every { f.event.inputEvent() } returns MouseEvent(f.source, MouseEvent.MOUSE_RELEASED, Long.MAX_VALUE,
            0, 1, 1, 1, true, MouseEvent.BUTTON3)
        val api = mockk<MontoyaApi>(relaxed = true)
        val native = mockk<NativeRepeater>()
        val registration = mockk<Registration>()
        val registered = CompletableDeferred<ContextMenuItemsProvider>()
        coEvery { native.modelContextTarget("tab", any(), false) } returns f.target
        every { api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            registered.complete(firstArg()); registration
        }
        every { registration.deregister() } throws IllegalStateException("PRIVATE_CLEANUP_FAILURE")
        val call = async { observeM0ContextModel(api, native, "tab", f.plan(), { f.permitted }, f.builder) }
        val provider = registered.await()
        SwingUtilities.invokeAndWait { assertTrue(provider.provideMenuItems(f.event).isEmpty()) }
        val outcome = call.await()
        assertEquals("mutation_unconfirmed", outcome.status)
        assertTrue(outcome.modelWriteAttempted && outcome.mutationMayHaveOccurred)
        assertFalse(outcome.modelWriteConfirmed)
        assertEquals("M0_CONTEXT_DEREGISTRATION_UNCONFIRMED", outcome.reason)
        assertFalse(Json.encodeToString(outcome).contains("PRIVATE_CLEANUP_FAILURE"))
        SwingUtilities.invokeAndWait { provider.provideMenuItems(f.event) }
        assertEquals(1, f.setters)
    }

    @Test fun `registration failure after admitted callback preserves possible mutation and closes provider`() = runBlocking {
        val f = Fixture()
        every { f.event.inputEvent() } returns MouseEvent(f.source, MouseEvent.MOUSE_RELEASED, Long.MAX_VALUE,
            0, 1, 1, 1, true, MouseEvent.BUTTON3)
        val api = mockk<MontoyaApi>(relaxed = true)
        val native = mockk<NativeRepeater>()
        var provider: ContextMenuItemsProvider? = null
        coEvery { native.modelContextTarget("tab", any(), false) } returns f.target
        every { api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            provider = firstArg()
            SwingUtilities.invokeAndWait { checkNotNull(provider).provideMenuItems(f.event) }
            error("PRIVATE_REGISTRATION_FAILURE")
        }
        val outcome = observeM0ContextModel(api, native, "tab", f.plan(), { f.permitted }, f.builder)
        assertEquals("mutation_unconfirmed", outcome.status)
        assertTrue(outcome.modelWriteAttempted && outcome.mutationMayHaveOccurred)
        assertFalse(outcome.modelWriteConfirmed)
        assertEquals("M0_CONTEXT_REGISTRATION_UNCONFIRMED", outcome.reason)
        assertFalse(Json.encodeToString(outcome).contains("PRIVATE_REGISTRATION_FAILURE"))
        SwingUtilities.invokeAndWait { checkNotNull(provider).provideMenuItems(f.event) }
        assertEquals(1, f.setters)
    }

    @Test fun `Raw and Pretty writer targets are rejected before reading or setting model`() = runBlocking {
        for (view in listOf("Raw", "Pretty")) {
            val f = Fixture()
            val target = M0ContextTarget("tab", view, canMutateNow = { true }) { it === f.source }
            val probe = RepeaterM0ContextProbe(target, 1000, f.plan(), { true }, f.builder)
            f.invoke(probe)
            val outcome = probe.result.await()
            assertEquals("M0_BYTE_WRITER_REQUIRES_HEX", outcome.reason)
            assertFalse(outcome.modelWriteAttempted)
            verify(exactly = 0) { f.editor.requestResponse() }
            assertEquals(0, f.setters)
        }
    }

    @Test fun `cancellation during blocked registration closes admission before a late callback`() = runBlocking {
        val f = Fixture()
        every { f.event.inputEvent() } returns MouseEvent(f.source, MouseEvent.MOUSE_RELEASED, Long.MAX_VALUE,
            0, 1, 1, 1, true, MouseEvent.BUTTON3)
        val api = mockk<MontoyaApi>(relaxed = true)
        val native = mockk<NativeRepeater>()
        val registration = mockk<Registration>(relaxed = true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var provider: ContextMenuItemsProvider? = null
        coEvery { native.modelContextTarget("tab", any(), false) } returns f.target
        every { api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            provider = firstArg()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            registration
        }
        val call = async(Dispatchers.Default) {
            observeM0ContextModel(api, native, "tab", f.plan(), { f.permitted }, f.builder)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            call.cancel()
            SwingUtilities.invokeAndWait { checkNotNull(provider).provideMenuItems(f.event) }
            assertEquals(0, f.setters)
            verify(exactly = 0) { f.editor.requestResponse() }
        } finally { release.countDown() }
        call.join()
        assertTrue(call.isCancelled)
        verify(exactly = 1) { registration.deregister() }
    }
}
