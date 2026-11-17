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
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.*

class RepeaterM0AutomaticWriteTest {
    @AfterEach fun cleanup() = unmockkAll()

    private class Fixture(val fault: String = "", val immediateFresh: Boolean = false) {
        val api = mockk<MontoyaApi>(relaxed = true)
        val native = mockk<NativeRepeater>()
        val source = JTable(2, 2).apply { setSize(200, 60) }
        val oldBytes = "GET /old HTTP/2\r\nHost: example.test\r\nContent-Length: 5\r\n\r\nhello".toByteArray()
        val newBytes = "POST /PRIVATE_PATH HTTP/2\r\nHost: example.test\r\nX-Variant: one\r\nX-Variant: two\r\nContent-Length: 5\r\n\r\ncafé".toByteArray(Charsets.UTF_8)
        val old = model(oldBytes)
        val replacement = model(newBytes)
        var actual = old
        var owned = true
        var permitted = true
        var idle = true
        var showing = true
        var registrations = 0
        var dispatches = 0
        var setters = 0
        var callbackActive = false
        var markerRan = false
        var menu = emptyArray<MenuElement>()
        val providers = mutableListOf<ContextMenuItemsProvider>()
        val events = mutableListOf<java.awt.event.MouseEvent>()
        val log = mutableListOf<String>()
        var afterFirstCallback: () -> Unit = {}
        var beforeRegister: (Int) -> Unit = {}
        var afterDeregister: (Int) -> Unit = {}
        val target = M0ContextTarget("tab", "Hex", automaticSource = source, canMutateNow = { idle },
            matchesNativeService = { h, p, tls -> h == "example.test" && p == 443 && tls }) { owned && it === source }
        val ui = M0AutomaticContextUi({ showing }, { table, input ->
            assertSame(source, table)
            val phase = ++dispatches
            assertEquals(phase, registrations)
            if (phase == 2) {
                assertTrue(markerRan)
                assertTrue(log.indexOf("deregister1") < log.size)
                assertNotSame(events.first(), input)
            }
            events += input
            log += "dispatch$phase-enter"
            val event = mockk<ContextMenuEvent>(relaxed = true)
            val editor = mockk<MessageEditorHttpRequestResponse>(relaxed = true)
            every { event.isFromTool(ToolType.REPEATER) } returns true
            every { event.inputEvent() } returns input
            every { event.invocationType() } returns InvocationType.MESSAGE_EDITOR_REQUEST
            every { event.messageEditorRequestResponse() } answers { check(callbackActive); Optional.of(editor) }
            every { editor.selectionContext() } answers { check(callbackActive); MessageEditorHttpRequestResponse.SelectionContext.REQUEST }
            every { editor.requestResponse().request() } answers {
                check(callbackActive)
                if (phase == 2 && fault == "read2") error("PRIVATE_GETTER")
                if (phase == 1 && !immediateFresh) old else actual
            }
            every { editor.setRequest(any()) } answers {
                check(callbackActive && phase == 1)
                setters++; log += "setter"
                if (fault == "setter_before") error("PRIVATE_SETTER")
                actual = firstArg()
                if (fault == "setter_after") error("PRIVATE_SETTER_AFTER")
            }
            callbackActive = true
            log += "callback$phase-enter"
            try {
                providers[phase - 1].provideMenuItems(event)
                providers[phase - 1].provideMenuItems(event) // Duplicate cannot set/read twice.
            } finally { callbackActive = false; log += "callback$phase-exit" }
            menu = arrayOf(JPopupMenu().apply { invoker = table })
            if (phase == 1) {
                SwingUtilities.invokeLater { markerRan = true; log += "later-marker" }
                afterFirstCallback()
            }
            if (fault == "dispatch1" && phase == 1) error("PRIVATE_DISPATCH")
            log += "dispatch$phase-exit"
        }, { menu }, { popup ->
            log += "hide$dispatches"
            if (fault == "hide$dispatches") error("PRIVATE_HIDE")
            popup.isVisible = false
        }, { log += "clear$dispatches"; menu = emptyArray() })

        init {
            coEvery { native.modelContextTarget("tab") } returns target
            every { api.userInterface().registerContextMenuItemsProvider(any()) } answers {
                val phase = ++registrations
                log += "register$phase"
                beforeRegister(phase)
                if (fault == "register$phase") error("PRIVATE_REGISTRATION")
                providers += firstArg<ContextMenuItemsProvider>()
                mockk<Registration>().also { registration -> every { registration.deregister() } answers {
                    log += "deregister$phase"
                    if (fault == "deregister$phase") error("PRIVATE_DEREGISTRATION")
                    if (phase == 1) when (fault) {
                        "owner_between" -> owned = false
                        "permission_between" -> permitted = false
                        "busy_between" -> idle = false
                        "bytes2" -> actual = model(newBytes + 'x'.code.toByte())
                        "service2" -> actual = model(newBytes, "other.test")
                        "version2" -> actual = model(newBytes, version = "HTTP/1.1")
                    }
                    if (phase == 2 && fault == "owner_after2") owned = false
                    if (phase == 2 && fault == "editing_after2") source.editCellAt(0, 0)
                    if (phase == 2 && fault == "menu_after2") menu = arrayOf(JPopupMenu().apply { invoker = source })
                    if (phase == 2 && fault == "hidden_after2") showing = false
                    afterDeregister(phase)
                } }
            }
        }
        fun plan(hash: String = m0ByteHash(oldBytes)) = M0ByteWritePlan.parse(hash, "example.test", 443, true,
            "HTTP/2", Base64.getEncoder().encodeToString(newBytes))
        suspend fun run(deadline: Long = 2000, evidence: M0MutationAttempt = M0MutationAttempt(), hash: String = m0ByteHash(oldBytes)) =
            observeM0AutomaticWrite(api, native, "tab", plan(hash), { permitted }, { _, bytes ->
                assertArrayEquals(newBytes, bytes); replacement
            }, ui, deadline, evidence)
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

    @Test fun `one setter stale callback then new facade exact confirmation after exit cleanup dereg and later turn`() = runBlocking {
        for (immediateFresh in listOf(false, true)) {
            val f = Fixture(immediateFresh = immediateFresh)
            val result = f.run()
            assertEquals("mutation_confirmed_fresh_context", result.status)
            assertTrue(result.modelWriteAttempted && result.setterReturnedNormally && result.freshContextConfirmed)
            assertEquals(if (immediateFresh) "mutation_confirmed" else "mutation_unconfirmed", result.writeStage?.status)
            if (!immediateFresh) assertEquals("MODEL_READBACK_UNCONFIRMED", result.writeStage?.reason)
            assertEquals(m0ByteHash(f.newBytes), result.freshReadStage?.modelRequestSha256)
            assertTrue(checkNotNull(result.freshReadStage?.freshReadback).completeBytesEqual)
            assertFalse(checkNotNull(result.freshReadStage).modelWriteAttempted)
            assertEquals(1, f.setters)
            assertEquals(2, f.dispatches)
            assertTrue(f.log.indexOf("callback1-exit") < f.log.indexOf("dispatch1-exit"))
            assertTrue(f.log.indexOf("dispatch1-exit") < f.log.indexOf("hide1"))
            assertTrue(f.log.indexOf("clear1") < f.log.indexOf("deregister1"))
            assertTrue(f.log.indexOf("deregister1") < f.log.indexOf("register2"))
            coVerify(exactly = 1) { f.native.modelContextTarget("tab") }
            val json = Json.encodeToString(result)
            assertFalse(json.contains("PRIVATE_PATH") || json.contains("X-Variant") || json.contains("café"))
        }
    }

    @Test fun `pre-admission failure rejects with zero setters and no fresh phase`() = runBlocking {
        val f = Fixture()
        val result = f.run(hash = "0".repeat(64))
        assertEquals("rejected", result.status)
        assertFalse(result.modelWriteAttempted || result.mutationMayHaveOccurred)
        assertEquals(0, f.setters)
        assertEquals(1, f.dispatches)
        assertNull(result.freshReadStage)
    }

    @Test fun `setter first dispatch or first cleanup faults never attempt second setter or fresh phase`() = runBlocking {
        for (fault in listOf("setter_before", "setter_after", "dispatch1", "hide1", "deregister1")) {
            val f = Fixture(fault)
            val evidence = M0MutationAttempt()
            val result = f.run(evidence = evidence)
            assertEquals("mutation_unconfirmed", result.status, fault)
            assertTrue(result.modelWriteAttempted && result.mutationMayHaveOccurred && evidence.attempted, fault)
            assertFalse(result.freshContextConfirmed, fault)
            assertEquals(1, f.setters, fault)
            assertEquals(1, f.registrations, fault)
            assertNull(result.freshReadStage, fault)
            assertFalse(Json.encodeToString(result).contains("PRIVATE_"), fault)
        }
    }

    @Test fun `phase two setup exact mismatch cleanup and final owner failures all retain first attempt`() = runBlocking {
        for (fault in listOf("register2", "read2", "bytes2", "service2", "version2", "hide2", "deregister2", "owner_after2",
            "editing_after2", "menu_after2", "hidden_after2")) {
            val f = Fixture(fault)
            val result = f.run()
            assertEquals("mutation_unconfirmed", result.status, fault)
            assertTrue(result.modelWriteAttempted && result.setterReturnedNormally && result.mutationMayHaveOccurred, fault)
            assertFalse(result.freshContextConfirmed, fault)
            assertEquals(1, f.setters, fault)
            assertEquals(2, f.registrations, fault)
            if (fault in listOf("hide2", "deregister2", "owner_after2")) {
                // Even an exact new model cannot erase later phase cleanup/identity failure.
                if (fault != "hide2") assertTrue(checkNotNull(result.freshReadStage?.freshReadback).completeBytesEqual, fault)
            }
        }
    }

    @Test fun `frozen owner permission or busy change between phases prevents fresh registration`() = runBlocking {
        for (fault in listOf("owner_between", "permission_between", "busy_between")) {
            val f = Fixture(fault)
            val result = f.run()
            assertEquals("mutation_unconfirmed", result.status, fault)
            assertTrue(result.modelWriteAttempted, fault)
            assertEquals(1, f.registrations, fault)
            assertEquals(1, f.setters, fault)
            assertNull(result.freshReadStage, fault)
        }
    }

    @Test fun `shared deadline after admitted callback preserves attempt when phase result is missing`() = runBlocking {
        val f = Fixture()
        val evidence = M0MutationAttempt()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.afterFirstCallback = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val call = async(Dispatchers.Default) { f.run(100, evidence) }
        try { entered.await(); delay(200) } finally { release.countDown() }
        val result = call.await()
        assertEquals("mutation_unconfirmed", result.status)
        assertTrue(result.modelWriteAttempted && result.setterReturnedNormally && evidence.attempted)
        assertFalse(result.freshContextConfirmed)
        assertEquals(1, f.setters)
        assertEquals(1, f.registrations)
    }

    @Test fun `cancellation after setter revokes later phases without erasing metadata evidence`() = runBlocking {
        val f = Fixture()
        val evidence = M0MutationAttempt()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.afterFirstCallback = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val call = async(Dispatchers.Default) { f.run(evidence = evidence) }
        try { entered.await(); call.cancel() } finally { release.countDown() }
        call.join()
        assertTrue(evidence.attempted && evidence.returnedNormally)
        assertEquals(1, f.setters)
        assertEquals(1, f.registrations)
        assertEquals(1, f.dispatches)
    }

    @Test fun `cancellation while explicit later turn queued never dispatches phase two`() = runBlocking {
        val f = Fixture()
        val evidence = M0MutationAttempt()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.afterDeregister = { phase -> if (phase == 1) SwingUtilities.invokeLater {
            entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS))
        } }
        val call = async(Dispatchers.Default) { f.run(evidence = evidence) }
        try { entered.await(); call.cancel() } finally { release.countDown() }
        call.join()
        SwingUtilities.invokeAndWait { }
        assertTrue(evidence.attempted && evidence.returnedNormally)
        assertEquals(1, f.setters)
        assertEquals(1, f.registrations)
        assertEquals(1, f.dispatches)
    }

    @Test fun `shared deadline during blocked fresh registration preserves missing fresh phase evidence`() = runBlocking {
        val f = Fixture()
        val evidence = M0MutationAttempt()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        f.beforeRegister = { phase -> if (phase == 2) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) } }
        val call = async(Dispatchers.Default) { f.run(300, evidence) }
        try { entered.await(); delay(400) } finally { release.countDown() }
        val result = call.await()
        assertEquals("mutation_unconfirmed", result.status)
        assertTrue(result.modelWriteAttempted && result.setterReturnedNormally)
        assertTrue(evidence.attempted)
        assertEquals(1, f.setters)
        assertEquals(2, f.registrations)
        assertEquals(1, f.dispatches)
        assertTrue(f.log.contains("deregister2"))
    }
}
