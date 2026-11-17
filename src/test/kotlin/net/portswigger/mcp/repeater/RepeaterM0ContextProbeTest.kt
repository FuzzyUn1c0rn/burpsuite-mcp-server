package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
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
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class RepeaterM0ContextProbeTest {
    @AfterEach fun cleanup() = unmockkAll()

    private class Fixture {
        val source = JTextArea()
        var owns = true
        val target = M0ContextTarget("tab", "Hex") { owns && it === source }
        val editor = mockk<MessageEditorHttpRequestResponse>(relaxed = true)
        val request = mockk<HttpRequest>(relaxed = true)
        val event = mockk<ContextMenuEvent>(relaxed = true)
        val body = "café".toByteArray(Charsets.UTF_8)
        val head = "POST /PRIVATE_PATH HTTP/2\r\nHost: example.test\r\n\r\n".toByteArray()
        init {
            every { event.isFromTool(ToolType.REPEATER) } returns true
            every { event.invocationType() } returns InvocationType.MESSAGE_EDITOR_REQUEST
            every { event.inputEvent() } returns MouseEvent(source, MouseEvent.MOUSE_RELEASED, 2000, 0, 1, 1, 1, true, MouseEvent.BUTTON3)
            every { event.messageEditorRequestResponse() } returns Optional.of(editor)
            every { editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.REQUEST
            every { editor.requestResponse().request() } returns request
            every { request.toByteArray().bytes } returns head + body
            every { request.toByteArray().length() } returns head.size + body.size
            every { request.body().bytes } returns body
            every { request.body().length() } returns body.size
            every { request.bodyOffset() } returns head.size
            every { request.httpService().host() } returns "example.test"
            every { request.httpService().port() } returns 443
            every { request.httpService().secure() } returns true
            every { request.httpVersion() } returns "HTTP/2"
        }
        fun invoke(probe: RepeaterM0ContextProbe) {
            SwingUtilities.invokeAndWait { assertTrue(probe.provideMenuItems(event).isEmpty()) }
        }
    }

    @Test fun `request context exposes byte metadata only without decoding writing or menu items`() = runBlocking {
        val f = Fixture()
        val probe = RepeaterM0ContextProbe(f.target, 1000)
        f.invoke(probe)
        val observed = probe.result.await()
        assertEquals("observed", observed.status)
        assertEquals(5, observed.modelBodyByteLength)
        assertEquals("850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e", observed.modelBodySha256)
        assertEquals("Hex", observed.selectedRequestView)
        assertFalse(observed.modelWriteAttempted)
        assertFalse(observed.editorViewSwitchAttempted)
        val serialized = Json.encodeToString(observed)
        assertFalse(serialized.contains("PRIVATE_PATH"))
        assertFalse(serialized.contains("café"))
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 0) { f.editor.setResponse(any()) }
        verify(exactly = 0) { f.request.toString() }
        probe.close()
    }

    @Test fun `wrong ownership response context and stale input are refused before model access`() = runBlocking {
        for (scenario in listOf("ownership", "response", "stale", "missing", "viewer", "response_invocation", "table_invocation")) {
            val f = Fixture()
            val probe = RepeaterM0ContextProbe(f.target, if (scenario == "stale") 3000 else 1000)
            when (scenario) {
                "ownership" -> f.owns = false
                "response" -> every { f.editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.RESPONSE
                "missing" -> every { f.event.messageEditorRequestResponse() } returns Optional.empty()
                "viewer" -> every { f.event.invocationType() } returns InvocationType.MESSAGE_VIEWER_REQUEST
                "response_invocation" -> every { f.event.invocationType() } returns InvocationType.MESSAGE_EDITOR_RESPONSE
                "table_invocation" -> every { f.event.invocationType() } returns InvocationType.SITE_MAP_TABLE
            }
            f.invoke(probe)
            assertEquals("rejected", probe.result.await().status)
            verify(exactly = 0) { f.editor.requestResponse() }
            probe.close()
        }
    }

    @Test fun `ownership diagnostic collection failure does not grant model access or expose exception`() = runBlocking {
        val f = Fixture()
        val target = M0ContextTarget("tab", "Hex", describeSource = { error("PRIVATE_DIAGNOSTIC") }) { false }
        val probe = RepeaterM0ContextProbe(target, 1000)
        f.invoke(probe)
        val observed = probe.result.await()
        assertEquals("OWNERSHIP_UNCONFIRMED", observed.reason)
        assertNull(observed.ownership)
        assertNull(observed.modelRequestSha256)
        assertFalse(Json.encodeToString(observed).contains("PRIVATE_DIAGNOSTIC"))
        verify(exactly = 0) { f.event.messageEditorRequestResponse() }
        verify(exactly = 0) { f.editor.requestResponse() }
    }

    @Test fun `identity changed during model extraction or excessive size cannot publish model metadata`() = runBlocking {
        for (scenario in listOf("changed", "large", "failure", "inconsistent")) {
            val f = Fixture()
            val probe = RepeaterM0ContextProbe(f.target, 1000)
            when (scenario) {
                "changed" -> every { f.request.body().bytes } answers { f.owns = false; f.body }
                "large" -> every { f.request.toByteArray().length() } returns 262145
                "failure" -> every { f.editor.requestResponse() } throws IllegalStateException("PRIVATE_EXCEPTION")
                "inconsistent" -> every { f.request.body().bytes } returns byteArrayOf(1)
            }
            f.invoke(probe)
            val observed = probe.result.await()
            assertEquals("rejected", observed.status)
            assertNull(observed.modelBodySha256)
            assertFalse(Json.encodeToString(observed).contains("PRIVATE_EXCEPTION"))
            probe.close()
        }
    }

    @Test fun `unrelated tools ignored closed probe and unsupported callback thread never read model`() = runBlocking {
        val f = Fixture()
        val probe = RepeaterM0ContextProbe(f.target, 1000)
        every { f.event.isFromTool(ToolType.REPEATER) } returns false
        f.invoke(probe)
        assertFalse(probe.result.isCompleted)
        probe.close()
        f.invoke(probe)
        verify(exactly = 0) { f.event.messageEditorRequestResponse() }
        val unrelatedOffThread = RepeaterM0ContextProbe(f.target, 1000)
        assertTrue(unrelatedOffThread.provideMenuItems(f.event).isEmpty())
        assertFalse(unrelatedOffThread.result.isCompleted)
        unrelatedOffThread.close()
        every { f.event.isFromTool(ToolType.REPEATER) } returns true
        val offThread = RepeaterM0ContextProbe(f.target, 1000)
        assertTrue(offThread.provideMenuItems(f.event).isEmpty())
        assertEquals("UNSUPPORTED_CALLBACK_THREAD", offThread.result.await().reason)
        verify(exactly = 0) { f.editor.requestResponse() }
    }

    @Test fun `close while input accessor is blocked stops before later model access`() {
        val f = Fixture()
        val probe = RepeaterM0ContextProbe(f.target, 1000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { f.event.inputEvent() } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            MouseEvent(f.source, MouseEvent.MOUSE_RELEASED, 2000, 0, 1, 1, 1, true, MouseEvent.BUTTON3)
        }
        val worker = Thread { f.invoke(probe) }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            probe.close() // Must not wait for the EDT/accessor.
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertTrue(probe.result.isCancelled)
        verify(exactly = 0) { f.event.messageEditorRequestResponse() }
        verify(exactly = 0) { f.editor.requestResponse() }
    }

    @Test fun `canceling observation closes and deregisters provider without waiting on Swing`() = runBlocking {
        val f = Fixture()
        val api = mockk<MontoyaApi>(relaxed = true)
        val native = mockk<NativeRepeater>()
        val registration = mockk<Registration>(relaxed = true)
        val registered = CompletableDeferred<ContextMenuItemsProvider>()
        coEvery { native.modelContextTarget("tab") } returns f.target
        every { api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            registered.complete(firstArg())
            registration
        }
        val job = async { observeM0ContextModel(api, native, "tab") }
        val provider = registered.await()
        job.cancelAndJoin()
        SwingUtilities.invokeAndWait { assertTrue(provider.provideMenuItems(f.event).isEmpty()) }
        verify(exactly = 1) { registration.deregister() }
        verify(exactly = 0) { f.editor.requestResponse() }
    }
}
