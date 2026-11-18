package net.portswigger.mcp.repeater

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RepeaterSendApprovalTest {
    private val snapshot = RepeaterApprovalSnapshot("instance", "operation", "native-tab", "fingerprint",
        "POST / HTTP/2\r\nHost: display.test\r\nX-Key: a\r\nX-Key: b\r\n\r\ncafé \t\u202e\\",
        "connection.test", 8443, true, "HTTP/2", "Single request, verified settings")
    private val verified = RepeaterApprovalState(snapshot, repeaterAccess = true, operationEligible = true)

    private class Boundary(var current: RepeaterApprovalState) {
        var attempts = 0
        var failAfterAttempt = false
        fun execute(check: (RepeaterApprovalState) -> RepeaterApprovalResult): RepeaterApprovalResult = synchronized(this) {
            val result = check(current)
            if (result == RepeaterApprovalResult.ATTEMPT_ADMITTED) {
                attempts++
                if (failAfterAttempt) error("mock attempt failed")
            }
            result
        }
    }

    @Test
    fun `one fresh verified proposal displays the complete request and actual destination and admits once`() = runBlocking {
        val gate = RepeaterSendApproval()
        val boundary = Boundary(verified)
        var prompts = 0
        val dialog = RepeaterApprovalDialog { display ->
            prompts++
            assertEquals("https://connection.test:8443", display.destination)
            assertEquals("HTTP/2", display.protocol)
            assertEquals(snapshot.verifiedProfile, display.profile)
            assertEquals(
                "POST\\x20/\\x20HTTP/2\\r\\n\nHost:\\x20display.test\\r\\n\n" +
                    "X-Key:\\x20a\\r\\n\nX-Key:\\x20b\\r\\n\n\\r\\n\n" +
                    "caf\\u00e9\\x20\\t\\u202e\\\\", display.request
            )
            assertEquals(0, boundary.attempts)
            true
        }
        assertEquals(RepeaterApprovalResult.ATTEMPT_ADMITTED, gate.review(verified, dialog, boundary::execute))
        assertEquals(RepeaterApprovalResult.CLOSED, gate.review(verified, dialog, boundary::execute))
        assertTrue(gate.attemptRecorded)
        assertEquals(1, prompts)
        assertEquals(1, boundary.attempts)
        assertFalse(snapshot.toString().contains("display.test"))
    }

    @Test
    fun `changed exact request service state or operation identity cannot use an approval`() = runBlocking {
        val changed = listOf(
            snapshot.copy(request = snapshot.request.replace("café", "cafe")),
            snapshot.copy(request = snapshot.request.replace("X-Key: a\r\nX-Key: b", "X-Key: b\r\nX-Key: a")),
            snapshot.copy(host = "other.test"), snapshot.copy(port = 443), snapshot.copy(tls = false),
            snapshot.copy(configuredProtocol = "HTTP/1.1"), snapshot.copy(verifiedProfile = "Changed"),
            snapshot.copy(operationId = "other"), snapshot.copy(serverInstanceId = "other"),
            snapshot.copy(nativeTabIdentity = "other"), snapshot.copy(fingerprint = "other")
        )
        for (current in changed) {
            val gate = RepeaterSendApproval()
            val boundary = Boundary(verified.copy(snapshot = current))
            assertEquals(RepeaterApprovalResult.STALE_FINGERPRINT,
                gate.review(verified, { true }, boundary::execute))
            assertEquals(0, boundary.attempts)
            assertFalse(gate.attemptRecorded)
        }
    }

    @Test
    fun `denial expiry profile uncertainty and access loss all prevent admission`() = runBlocking {
        val boundary = Boundary(verified)
        var now = 0L
        assertEquals(RepeaterApprovalResult.PERMISSION_DENIED,
            RepeaterSendApproval().review(verified, { false }, boundary::execute))
        assertEquals(RepeaterApprovalResult.EXPIRED,
            RepeaterSendApproval(1000) { now }.review(verified, { now = 1000; true }, boundary::execute))
        assertEquals(RepeaterApprovalResult.EXPIRED,
            RepeaterSendApproval(10).review(verified, { awaitCancellation() }, boundary::execute))
        boundary.current = verified.copy(repeaterAccess = false)
        assertEquals(RepeaterApprovalResult.PERMISSION_DENIED,
            RepeaterSendApproval().review(verified, { true }, boundary::execute))
        boundary.current = verified.copy(snapshot = null)
        assertEquals(RepeaterApprovalResult.UNVERIFIED_SETTINGS,
            RepeaterSendApproval().review(verified, { true }, boundary::execute))
        boundary.current = verified.copy(operationEligible = false)
        assertEquals(RepeaterApprovalResult.OPERATION_NOT_ELIGIBLE,
            RepeaterSendApproval().review(verified, { true }, boundary::execute))
        assertEquals(0, boundary.attempts)
    }

    @Test
    fun `unknown initial state or unrenderable request fails before prompting`() = runBlocking {
        val boundary = Boundary(verified)
        val dialog = RepeaterApprovalDialog { fail<Boolean>("Unexpected dialog") }
        assertEquals(RepeaterApprovalResult.UNVERIFIED_SETTINGS,
            RepeaterSendApproval().review(verified.copy(snapshot = null), dialog, boundary::execute))
        assertEquals(RepeaterApprovalResult.UNVERIFIED_SETTINGS,
            RepeaterSendApproval().review(verified.copy(snapshot = snapshot.copy(request = "\uD800")), dialog, boundary::execute))
        assertEquals(RepeaterApprovalResult.PERMISSION_DENIED,
            RepeaterSendApproval().review(verified.copy(repeaterAccess = false), dialog, boundary::execute))
        assertEquals(0, boundary.attempts)
    }

    @Test
    fun `a late approval after cancellation cannot admit and a pending proposal cannot replay`() = runBlocking {
        val boundary = Boundary(verified)
        val gate = RepeaterSendApproval()
        val entered = CompletableDeferred<Unit>()
        val lateAllow = CompletableDeferred<Unit>()
        val review = launch {
            gate.review(verified, {
                entered.complete(Unit)
                withContext(NonCancellable) { lateAllow.await() }
                true
            }, boundary::execute)
        }
        entered.await()
        assertEquals(RepeaterApprovalResult.CLOSED, gate.review(verified, { fail("Duplicate prompt") }, boundary::execute))
        review.cancel()
        lateAllow.complete(Unit)
        review.join()
        assertFalse(gate.attemptRecorded)
        assertEquals(0, boundary.attempts)
    }

    @Test
    fun `attempt evidence survives an exception and a saved boundary callback cannot replay`() = runBlocking {
        val boundary = Boundary(verified).apply { failAfterAttempt = true }
        val gate = RepeaterSendApproval()
        lateinit var saved: (RepeaterApprovalState) -> RepeaterApprovalResult
        val failure = runCatching {
            gate.review(verified, { true }) { check ->
                saved = check
                boundary.execute(check)
            }
        }.exceptionOrNull()
        assertEquals("mock attempt failed", failure?.message)
        assertTrue(gate.attemptRecorded)
        assertEquals(RepeaterApprovalResult.CLOSED, saved(verified))
        assertEquals(1, boundary.attempts)
    }

    @Test
    fun `expiry and cancellation at the final boundary still prevent an attempt`() = runBlocking {
        val boundary = Boundary(verified)
        var now = 0L
        val expiring = RepeaterSendApproval(1000) { now }
        assertEquals(RepeaterApprovalResult.EXPIRED, expiring.review(verified, { true }) { check ->
            now = 1000
            boundary.execute(check)
        })
        val canceled = RepeaterSendApproval()
        val review = launch {
            canceled.review(verified, { true }) { check ->
                cancel()
                boundary.execute(check)
            }
        }
        review.join()
        assertFalse(expiring.attemptRecorded)
        assertFalse(canceled.attemptRecorded)
        assertEquals(0, boundary.attempts)
    }
}
