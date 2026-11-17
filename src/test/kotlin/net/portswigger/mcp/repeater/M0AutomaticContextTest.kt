package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.*
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
import javax.swing.*

class M0AutomaticContextTest {
    @AfterEach fun cleanup() = unmockkAll()
    private class UiFixture {
        val table = JTable(2, 2).apply { setSize(200, 60) }
        var owned = true
        var permitted = true
        var showing = true
        var path = emptyArray<MenuElement>()
        var dispatches = 0
        var hides = 0
        var clears = 0
        var pathReads = 0
        var onPathRead: (Int) -> Unit = {}
        var onHide: () -> Unit = {}
        var onDispatch: (MouseEvent) -> Unit = {}
        val ui = M0AutomaticContextUi({ showing }, { source, event ->
            assertSame(table, source); dispatches++; onDispatch(event)
        }, { onPathRead(++pathReads); path }, { popup -> hides++; popup.isVisible = false; onHide() },
            { clears++; path = emptyArray() })
        fun lease() = M0AutomaticContext(table, { owned && it === table }, { permitted }, ui)
        fun popup(invoker: java.awt.Component = table) = JPopupMenu().apply { this.invoker = invoker }
    }

    @Test fun `one exact issued token rejects registration manual cloned duplicate and late events`() {
        SwingUtilities.invokeAndWait {
            val f = UiFixture()
            val lease = f.lease()
            val manual = MouseEvent(f.table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 1, 1, 1, true, 3)
            assertFalse(lease.accept(manual))
            f.onDispatch = { issued ->
                val clone = MouseEvent(f.table, issued.id, issued.`when`, issued.modifiersEx, issued.x, issued.y, 1, true, 3)
                assertFalse(lease.accept(clone))
                assertTrue(lease.accept(issued))
                assertFalse(lease.accept(issued))
                assertFalse(lease.dispatchOnce())
                f.path = arrayOf(f.popup())
            }
            assertTrue(lease.dispatchOnce())
            assertEquals(1, f.dispatches)
            assertTrue(lease.diagnostics().exactIssuedEventObserved)
            lease.close()
            assertFalse(lease.accept(manual))
            assertFalse(lease.dispatchOnce())
            assertEquals(1, f.dispatches)
        }
    }

    @Test fun `public component dispatch delivers single release to exact headless component`() {
        SwingUtilities.invokeAndWait {
            val f = UiFixture()
            var path = emptyArray<MenuElement>()
            val lease = M0AutomaticContext(f.table, { it === f.table }, { true },
                M0AutomaticContextUi(showing = { true }, menuPath = { path }, clearPath = { path = emptyArray() }))
            var calls = 0
            f.table.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    calls++; assertTrue(lease.accept(event)); path = arrayOf(f.popup())
                }
            })
            assertTrue(lease.dispatchOnce())
            assertEquals(1, calls)
            lease.close()
        }
    }

    @Test fun `retargeted or translated same event object does not authorize callback`() {
        SwingUtilities.invokeAndWait {
            for (scenario in listOf("source", "coordinates")) {
                val f = UiFixture()
                val lease = f.lease()
                f.onDispatch = { event ->
                    if (scenario == "source") event.source = JTable() else event.translatePoint(1, 0)
                    assertFalse(lease.accept(event), scenario)
                }
                assertFalse(lease.dispatchOnce())
                assertFalse(lease.diagnostics().exactIssuedEventObserved)
                assertEquals(1, f.dispatches)
                lease.close()
            }
        }
    }

    @Test fun `unsafe preconditions refuse before dispatch without cell focus or menu mutation`() {
        SwingUtilities.invokeAndWait {
            for (scenario in listOf("ownership", "permission", "showing", "bounds", "cell_edit", "menu", "closed")) {
                val f = UiFixture()
                val lease = f.lease()
                when (scenario) {
                    "ownership" -> f.owned = false
                    "permission" -> f.permitted = false
                    "showing" -> f.showing = false
                    "bounds" -> f.table.setSize(0, 0)
                    "cell_edit" -> assertTrue(f.table.editCellAt(0, 0))
                    "menu" -> f.path = arrayOf(f.popup())
                    "closed" -> lease.close()
                }
                assertFalse(lease.dispatchOnce(), scenario)
                assertEquals(0, f.dispatches, scenario)
                assertEquals(0, f.hides + f.clears, scenario)
                if (scenario == "cell_edit") assertTrue(f.table.isEditing)
                lease.close()
            }
        }
    }

    @Test fun `only unchanged owned popup is hidden and cleared without selecting action`() {
        SwingUtilities.invokeAndWait {
            val f = UiFixture()
            val popup = f.popup()
            val item = JMenuItem("PRIVATE_MENU_TEXT").apply { addActionListener { fail<Unit>("No menu action") } }
            popup.add(item)
            val lease = f.lease()
            f.onDispatch = { assertTrue(lease.accept(it)); f.path = arrayOf(popup, item) }
            assertTrue(lease.dispatchOnce())
            assertEquals(1, f.hides)
            assertEquals(1, f.clears)
            assertTrue(lease.diagnostics().popupCleanupConfirmed)
            assertFalse(Json.encodeToString(lease.diagnostics()).contains("PRIVATE_MENU_TEXT"))
            lease.close()
        }
    }

    @Test fun `popup ownership loss or takeover never clears borrowed path`() {
        SwingUtilities.invokeAndWait {
            for (scenario in listOf("invoker", "foreign_element", "changed_path", "identity_loss", "hide_takeover", "invoker_change", "hide_owner_loss", "no_matching_callback")) {
                val f = UiFixture()
                val popup = f.popup()
                val other = f.popup(JTable())
                val lease = f.lease()
                f.onDispatch = {
                    if (scenario != "no_matching_callback") lease.accept(it)
                    f.path = when (scenario) {
                        "invoker" -> arrayOf(other)
                        "foreign_element" -> arrayOf(popup, other)
                        else -> arrayOf(popup)
                    }
                    if (scenario == "identity_loss") f.owned = false
                }
                if (scenario == "changed_path") f.onPathRead = { if (it == 3) f.path = arrayOf(other) }
                if (scenario == "hide_takeover") f.onHide = { f.path = arrayOf(other) }
                if (scenario == "invoker_change") f.onHide = { popup.invoker = JTable() }
                if (scenario == "hide_owner_loss") f.onHide = { f.owned = false }
                assertFalse(lease.dispatchOnce(), scenario)
                assertEquals(0, f.clears, scenario)
                if (scenario !in listOf("hide_takeover", "invoker_change", "hide_owner_loss")) assertEquals(0, f.hides, scenario)
                lease.close()
            }
        }
    }

    private class ModelFixture {
        val f = UiFixture()
        val native = mockk<NativeRepeater>()
        val api = mockk<MontoyaApi>(relaxed = true)
        val registration = mockk<Registration>(relaxed = true)
        val target = M0ContextTarget("tab", "Hex", automaticSource = f.table,
            describeMutationReadiness = { error("PRIVATE_DIAGNOSTIC") }) { f.owned && it === f.table }
        val event = mockk<ContextMenuEvent>(relaxed = true)
        val editor = mockk<MessageEditorHttpRequestResponse>(relaxed = true)
        val request = mockk<HttpRequest>(relaxed = true)
        lateinit var provider: ContextMenuItemsProvider
        val bytes = "GET /PRIVATE_PATH HTTP/2\r\nHost: private.test\r\n\r\n".toByteArray()
        init {
            coEvery { native.modelContextTarget("tab") } returns target
            every { api.userInterface().registerContextMenuItemsProvider(any()) } answers { provider = firstArg(); registration }
            every { event.isFromTool(ToolType.REPEATER) } returns true
            every { event.invocationType() } returns InvocationType.MESSAGE_EDITOR_REQUEST
            every { event.messageEditorRequestResponse() } returns Optional.of(editor)
            every { editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.REQUEST
            every { editor.requestResponse().request() } returns request
            every { request.toByteArray().bytes } returns bytes
            every { request.toByteArray().length() } returns bytes.size
            every { request.body().bytes } returns byteArrayOf()
            every { request.body().length() } returns 0
            every { request.bodyOffset() } returns bytes.size
            every { request.httpService().host() } returns "private.test"
            every { request.httpService().port() } returns 443
            every { request.httpService().secure() } returns true
            every { request.httpVersion() } returns "HTTP/2"
            f.onDispatch = { issued ->
                every { event.inputEvent() } returns issued
                assertTrue(provider.provideMenuItems(event).isEmpty())
                assertTrue(provider.provideMenuItems(event).isEmpty())
                f.path = arrayOf(f.popup())
            }
        }
        suspend fun run(deadline: Long = 1000) = observeM0ContextModel(api, native, "tab",
            permissionStillValid = { f.permitted }, automaticRead = true, automaticUi = f.ui, deadlineMillis = deadline)
    }

    @Test fun `automatic model read uses exact callback only once releases provider and emits bounded metadata`() = runBlocking {
        val f = ModelFixture()
        // An unrelated manual callback during registration must not grant access.
        every { f.api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            f.provider = firstArg()
            every { f.event.inputEvent() } returns MouseEvent(f.f.table, MouseEvent.MOUSE_RELEASED, 1, 0, 1, 1, 1, true, 3)
            SwingUtilities.invokeAndWait { f.provider.provideMenuItems(f.event) }
            verify(exactly = 0) { f.editor.requestResponse() }
            f.registration
        }
        val observed = f.run()
        assertEquals("observed", observed.status)
        assertEquals(m0ByteHash(f.bytes), observed.modelRequestSha256)
        assertTrue(checkNotNull(observed.automaticContext).exactIssuedEventObserved)
        assertEquals(1, observed.automaticContext.dispatchCount)
        assertNull(observed.nativeReadiness) // A failed diagnostic cannot grant/deny model access.
        val serialized = Json.encodeToString(observed)
        assertFalse(serialized.contains("PRIVATE_"))
        assertFalse(serialized.contains("GET /"))
        assertFalse(serialized.contains("Host: private.test")) // Service metadata is intentionally present; raw headers are absent.
        verify(exactly = 1) { f.editor.requestResponse() }
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 0) { f.editor.setResponse(any()) }
        verify(exactly = 1) { f.registration.deregister() }
        SwingUtilities.invokeAndWait { f.provider.provideMenuItems(f.event) }
        verify(exactly = 1) { f.editor.requestResponse() }
    }

    @Test fun `cloned event wrong source context or revoked permission never read or mutate model`() = runBlocking {
        for (scenario in listOf("clone", "other_source", "response", "viewer", "ownership", "permission")) {
            val f = ModelFixture()
            val originalDispatch = f.f.onDispatch
            f.f.onDispatch = { issued ->
                when (scenario) {
                    "clone", "other_source" -> {
                        val source = if (scenario == "clone") f.f.table else JTable()
                        every { f.event.inputEvent() } returns MouseEvent(source, issued.id, issued.`when`, 0, issued.x, issued.y, 1, true, 3)
                        f.provider.provideMenuItems(f.event)
                    }
                    else -> {
                        when (scenario) {
                            "response" -> every { f.editor.selectionContext() } returns MessageEditorHttpRequestResponse.SelectionContext.RESPONSE
                            "viewer" -> every { f.event.invocationType() } returns InvocationType.MESSAGE_VIEWER_RESPONSE
                            "ownership" -> f.f.owned = false
                            "permission" -> f.f.permitted = false
                        }
                        originalDispatch(issued)
                    }
                }
            }
            val observed = f.run(100)
            assertTrue(observed.status in setOf("not_observed", "rejected"), scenario)
            assertFalse(observed.modelWriteAttempted, scenario)
            assertEquals(1, f.f.dispatches, scenario)
            verify(exactly = 0) { f.editor.requestResponse() }
            verify(exactly = 0) { f.editor.setRequest(any()) }
            verify(exactly = 1) { f.registration.deregister() }
        }
    }

    @Test fun `cancel while registration blocked closes automatic issuance and cleans returned registration`() = runBlocking {
        val f = ModelFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { f.api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            f.provider = firstArg(); entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)); f.registration
        }
        val call = async(Dispatchers.Default) { f.run() }
        try {
            entered.await(); call.cancel()
        } finally { release.countDown() }
        call.join()
        assertEquals(0, f.f.dispatches)
        verify(exactly = 0) { f.editor.requestResponse() }
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 1) { f.registration.deregister() }
    }

    @Test fun `registration failure before issue cannot dispatch and popup cleanup failure cannot claim success`() = runBlocking {
        val failed = ModelFixture()
        every { failed.api.userInterface().registerContextMenuItemsProvider(any()) } throws IllegalStateException("PRIVATE_REGISTRATION")
        val registrationResult = failed.run()
        assertEquals("rejected", registrationResult.status)
        assertEquals(0, failed.f.dispatches)
        assertFalse(Json.encodeToString(registrationResult).contains("PRIVATE_REGISTRATION"))
        val popup = ModelFixture()
        val dispatch = popup.f.onDispatch
        popup.f.onDispatch = { event -> dispatch(event); popup.f.path = arrayOf(popup.f.popup(JTable())) }
        val observed = popup.run()
        assertEquals("rejected", observed.status)
        assertFalse(checkNotNull(observed.automaticContext).popupCleanupConfirmed)
        assertNull(observed.modelRequestSha256)
        assertEquals(0, popup.f.hides + popup.f.clears)
        verify(exactly = 0) { popup.editor.setRequest(any()) }
    }

    @Test fun `cancel during public dispatch closes issued token before a delayed callback`() = runBlocking {
        val f = ModelFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val dispatch = f.f.onDispatch
        f.f.onDispatch = { event -> entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); dispatch(event) }
        val call = async(Dispatchers.Default) { f.run() }
        try { entered.await(); call.cancel() } finally { release.countDown() }
        call.join()
        assertEquals(1, f.f.dispatches)
        verify(exactly = 0) { f.editor.requestResponse() }
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 1) { f.registration.deregister() }
    }

    @Test fun `cancel after observed callback preserves owned popup cleanup for entered dispatch only`() = runBlocking {
        val f = ModelFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val dispatch = f.f.onDispatch
        f.f.onDispatch = { event ->
            dispatch(event)
            f.f.path = arrayOf(f.f.popup())
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        }
        val call = async(Dispatchers.Default) { f.run() }
        try { entered.await(); call.cancel() } finally { release.countDown() }
        call.join()
        assertEquals(1, f.f.dispatches)
        assertEquals(1, f.f.hides)
        assertEquals(1, f.f.clears)
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 1) { f.registration.deregister() }
    }

    @Test fun `whole observation deadline closes issuance while registration is blocked`() = runBlocking {
        val f = ModelFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { f.api.userInterface().registerContextMenuItemsProvider(any()) } answers {
            f.provider = firstArg(); entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)); f.registration
        }
        val call = async(Dispatchers.Default) { f.run(100) }
        try { entered.await(); delay(200) } finally { release.countDown() }
        val observed = call.await()
        assertEquals("not_observed", observed.status)
        assertEquals(0, f.f.dispatches)
        assertFalse(checkNotNull(observed.automaticContext).eventIssued)
        verify(exactly = 0) { f.editor.requestResponse() }
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 1) { f.registration.deregister() }
    }

    @Test fun `permission lost during getter prevents metadata publication and any later model access`() = runBlocking {
        val f = ModelFixture()
        every { f.request.body().bytes } answers { f.f.permitted = false; byteArrayOf() }
        val observed = f.run()
        assertEquals("rejected", observed.status)
        assertEquals("PERMISSION_CHANGED_DURING_MODEL_READ", observed.reason)
        assertNull(observed.modelRequestSha256)
        verify(exactly = 0) { f.editor.setRequest(any()) }
        verify(exactly = 0) { f.request.bodyOffset() }
        verify(exactly = 0) { f.request.httpService() }
    }

    @Test fun `R9 exact issued token is revoked when public dispatch exits without callback`() {
        SwingUtilities.invokeAndWait {
            val f = UiFixture()
            val lease = f.lease()
            var issued: MouseEvent? = null
            f.onDispatch = { issued = it }
            assertFalse(lease.dispatchOnce())
            assertTrue(lease.diagnostics().dispatchExited)
            assertFalse(lease.diagnostics().popupCleanupConfirmed)
            assertFalse(lease.accept(checkNotNull(issued)))
            assertFalse(lease.diagnostics().exactIssuedEventObserved)
            assertEquals(1, f.dispatches)
            assertEquals(0, f.hides + f.clears)
            lease.close()
        }
    }

    @Test fun `R9 deferred exact native callback cannot read model or claim popup cleanup`() = runBlocking {
        val f = ModelFixture()
        f.f.onDispatch = { issued -> SwingUtilities.invokeLater {
            every { f.event.inputEvent() } returns issued
            f.provider.provideMenuItems(f.event)
            f.f.path = arrayOf(f.f.popup()) // Simulated native deferred popup.
        } }
        val observed = f.run()
        assertEquals("rejected", observed.status)
        assertNull(observed.modelRequestSha256)
        assertFalse(checkNotNull(observed.automaticContext).popupCleanupConfirmed)
        assertTrue(observed.automaticContext.dispatchExited)
        SwingUtilities.invokeAndWait { } // Drain the deliberately queued callback.
        verify(exactly = 0) { f.editor.requestResponse() }
        verify(exactly = 0) { f.editor.setRequest(any()) }
        assertEquals(1, f.f.dispatches)
        assertEquals(0, f.f.hides + f.f.clears)
        assertTrue(f.f.path.isNotEmpty())
    }

    @Test fun `R9 synchronous callback with absent or deferred popup cannot report cleanup confirmed`() = runBlocking {
        for (deferredPopup in listOf(false, true)) {
            val f = ModelFixture()
            val callback = f.f.onDispatch
            f.f.onDispatch = { event ->
                callback(event); f.f.path = emptyArray()
                if (deferredPopup) SwingUtilities.invokeLater { f.f.path = arrayOf(f.f.popup()) }
            }
            val observed = f.run()
            assertEquals("rejected", observed.status)
            assertNull(observed.modelRequestSha256)
            val auto = checkNotNull(observed.automaticContext)
            assertTrue(auto.exactIssuedEventObserved)
            assertTrue(auto.dispatchExited)
            assertFalse(auto.popupObserved || auto.popupCleanupConfirmed)
            assertEquals("NO_SYNCHRONOUS_OWNED_POPUP", auto.reason)
            SwingUtilities.invokeAndWait { }
            if (deferredPopup) assertTrue(f.f.path.isNotEmpty())
            assertEquals(0, f.f.hides + f.f.clears)
            verify(exactly = 0) { f.editor.setRequest(any()) }
            assertEquals(1, f.f.dispatches)
        }
    }
}
