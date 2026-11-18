package net.portswigger.mcp.repeater

import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.showingOptionOwner
import java.awt.Component
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicReference

internal data class RepeaterApprovalSnapshot(
    val serverInstanceId: String,
    val operationId: String,
    val nativeTabIdentity: String,
    val fingerprint: String,
    val request: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val configuredProtocol: String,
    val verifiedProfile: String
) {
    // Keep accidental logging of this value free of request data.
    override fun toString() = "RepeaterApprovalSnapshot"
}

internal class RepeaterApprovalDisplay(val destination: String, val protocol: String, val profile: String, val request: String)

internal fun interface RepeaterApprovalDialog {
    suspend fun approveOnce(display: RepeaterApprovalDisplay): Boolean
}

/** Local dialog only. The parent is supplied by the owning Burp application. */
internal class BurpRepeaterApprovalDialog(private val parent: Component) : RepeaterApprovalDialog {
    override suspend fun approveOnce(display: RepeaterApprovalDisplay): Boolean {
        val owner = withContext(Dispatchers.Swing) { showingOptionOwner(parent) } ?: return false
        return Dialogs.requestOption(
            parent,
            "Actual connection destination: ${display.destination}\n" +
                "Configured protocol: ${display.protocol}\nExecution profile: ${display.profile}\n\n" +
                "Review the complete request. Backslash escapes show line endings, trailing spaces, control characters and Unicode.",
            arrayOf("Deny", "Approve once"),
            requestContent = display.request,
            requiredOwner = owner
        ) == 1
    }
}

internal data class RepeaterApprovalState(
    val snapshot: RepeaterApprovalSnapshot?,
    val repeaterAccess: Boolean,
    val operationEligible: Boolean
)

internal enum class RepeaterApprovalResult {
    ATTEMPT_ADMITTED, PERMISSION_DENIED, STALE_FINGERPRINT, UNVERIFIED_SETTINGS,
    OPERATION_NOT_ELIGIBLE, EXPIRED, CLOSED
}

/**
 * Instantiate once per registered operation. The adapter supplies a fresh verified
 * state and holds its own state boundary across validation, admission and attempt.
 * This component records admission only and has no executor or network access.
 */
internal class RepeaterSendApproval(
    private val lifetimeMs: Long = 30_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(lifetimeMs in 1..300_000) }

    private enum class State { NEW, PENDING, APPROVED, CLOSED, ATTEMPTED }
    private val state = AtomicReference(State.NEW)
    val attemptRecorded get() = state.get() == State.ATTEMPTED

    suspend fun review(
        proposed: RepeaterApprovalState,
        dialog: RepeaterApprovalDialog,
        atProtectedBoundary: ((RepeaterApprovalState) -> RepeaterApprovalResult) -> RepeaterApprovalResult
    ): RepeaterApprovalResult {
        if (!state.compareAndSet(State.NEW, State.PENDING)) return RepeaterApprovalResult.CLOSED
        val context = currentCoroutineContext()
        val cancellationLease = Job(context[Job])
        fun close() {
            state.compareAndSet(State.PENDING, State.CLOSED)
            state.compareAndSet(State.APPROVED, State.CLOSED)
        }
        val onCancel = cancellationLease.invokeOnCompletion { if (it != null) close() }
        try {
            context.ensureActive()
            if (!proposed.repeaterAccess) return RepeaterApprovalResult.PERMISSION_DENIED
            if (!proposed.operationEligible) return RepeaterApprovalResult.OPERATION_NOT_ELIGIBLE
            val snapshot = proposed.snapshot ?: return RepeaterApprovalResult.UNVERIFIED_SETTINGS
            val display = snapshot.display() ?: return RepeaterApprovalResult.UNVERIFIED_SETTINGS
            val start = nowMillis()
            fun expired() = nowMillis() - start >= lifetimeMs
            val approved = withTimeoutOrNull(lifetimeMs) { dialog.approveOnce(display) }
            context.ensureActive()
            if (approved == null || expired()) return RepeaterApprovalResult.EXPIRED
            if (!approved) return RepeaterApprovalResult.PERMISSION_DENIED
            if (!state.compareAndSet(State.PENDING, State.APPROVED)) return RepeaterApprovalResult.CLOSED
            val boundaryThread = Thread.currentThread()
            var boundSnapshot: RepeaterApprovalSnapshot? = snapshot
            var decision = RepeaterApprovalResult.CLOSED
            var checked = false
            try {
                atProtectedBoundary { current ->
                    // A queued or borrowed callback cannot authorize an attempt.
                    if (Thread.currentThread() !== boundaryThread || checked || state.get() != State.APPROVED)
                        return@atProtectedBoundary RepeaterApprovalResult.CLOSED
                    checked = true
                    context.ensureActive()
                    val rejection = when {
                        !current.repeaterAccess -> RepeaterApprovalResult.PERMISSION_DENIED
                        !current.operationEligible -> RepeaterApprovalResult.OPERATION_NOT_ELIGIBLE
                        current.snapshot == null -> RepeaterApprovalResult.UNVERIFIED_SETTINGS
                        current.snapshot != boundSnapshot -> RepeaterApprovalResult.STALE_FINGERPRINT
                        else -> null
                    }
                    // Compare first, then sample the deadline immediately before the
                    // atomic transition. Cancellation and admission compete for it.
                    context.ensureActive()
                    decision = when {
                        rejection != null -> rejection
                        expired() -> RepeaterApprovalResult.EXPIRED
                        state.compareAndSet(State.APPROVED, State.ATTEMPTED) -> RepeaterApprovalResult.ATTEMPT_ADMITTED
                        else -> RepeaterApprovalResult.CLOSED
                    }
                    if (!attemptRecorded) close()
                    decision
                }
                // Only the synchronous check can report admission.
                return decision
            } finally {
                // Even if the adapter retained the callback, it keeps no request.
                boundSnapshot = null
            }
        } finally {
            close()
            onCancel.dispose()
            cancellationLease.complete()
        }
    }
}

private fun RepeaterApprovalSnapshot.display(): RepeaterApprovalDisplay? {
    if (listOf(serverInstanceId, operationId, nativeTabIdentity, fingerprint).any { it.isBlank() } ||
        configuredProtocol !in setOf("HTTP/1.1", "HTTP/2") ||
        verifiedProfile.length !in 1..512 || verifiedProfile.any { it !in ' '..'~' } ||
        request.isBlank() || request.length > 262_144 || request.toByteArray(Charsets.UTF_8).size > 262_144) return null
    if (!Charsets.UTF_8.newEncoder().canEncode(request)) return null
    val destination = approvalEndpoint(host, port, tls)?.toASCIIString() ?: return null
    return RepeaterApprovalDisplay(destination, configuredProtocol, verifiedProfile, renderRequest(request))
}

private fun approvalEndpoint(host: String, port: Int, tls: Boolean): URI? {
    if (port !in 1..65535 || host.length !in 1..253 ||
        host.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in ".:[]-" }) return null
    return try {
        URI(if (tls) "https" else "http", null, host, port, null, null, null).takeIf { it.host != null }
    } catch (_: URISyntaxException) {
        null
    }
}

/** Reversible display of the complete approved representation, without Swing HTML interpretation. */
private fun renderRequest(request: String) = buildString {
    for (index in request.indices) when (val char = request[index]) {
        '\\' -> append("\\\\")
        '\r' -> append("\\r")
        '\n' -> append("\\n\n")
        '\t' -> append("\\t")
        ' ' -> when (request.getOrNull(index + 1)) {
            null, '\r', '\n' -> append("\\x20")
            else -> append(' ')
        }
        in '!'..'~' -> append(char)
        else -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
    }
}
