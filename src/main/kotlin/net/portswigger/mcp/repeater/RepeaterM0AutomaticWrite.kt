package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.awt.EventQueue
import kotlin.coroutines.resume

/** Two synchronous event phases, one irreversible setter, no facade between phases. */
internal suspend fun observeM0AutomaticWrite(api: MontoyaApi, native: NativeRepeater, tabId: String,
    plan: M0ByteWritePlan, permissionStillValid: () -> Boolean = { true },
    constructRequest: (HttpService, ByteArray) -> HttpRequest = ::constructM0ByteRequest,
    automaticUi: M0AutomaticContextUi = M0AutomaticContextUi(), deadlineMillis: Long = 30_000,
    attempt: M0MutationAttempt = M0MutationAttempt()): M0AutomaticWriteObservation {
    var write: M0ContextModelObservation? = null
    var fresh: M0ContextModelObservation? = null
    var laterTurnPassed = false
    var stage = "CAPTURE"
    fun outcome(confirmed: Boolean, reason: String) = M0AutomaticWriteObservation(
        if (confirmed) "mutation_confirmed_fresh_context" else if (attempt.attempted) "mutation_unconfirmed" else "rejected",
        reason, tabId, attempt.attempted, attempt.returnedNormally, attempt.attempted, confirmed,
        laterTurnPassed, write, fresh)
    try {
        val finished = withTimeoutOrNull(deadlineMillis) {
            val target = native.modelContextTarget(tabId, permissionStillValid)
            val source = target.automaticSource
            if (target.view != "Hex" || source == null) return@withTimeoutOrNull outcome(false, "CAPTURED_HEX_TABLE_REQUIRED")
            stage = "WRITE_EVENT"
            write = observeM0ContextOnTarget(api, target, plan, permissionStillValid, constructRequest,
                automaticInvocation = true, automaticUi = automaticUi, deadlineMillis = deadlineMillis,
                attempt = attempt, allowAutomaticWrite = true)
            currentCoroutineContext().ensureActive()
            val first = checkNotNull(write)
            val auto = first.automaticContext
            val healthyWrite = attempt.attempted && attempt.returnedNormally && first.callbackExited &&
                first.providerCleanupConfirmed && auto?.dispatchExited == true && auto.exactIssuedEventObserved &&
                auto.popupObserved && auto.popupInvokerOwned && auto.popupCleanupConfirmed &&
                (first.status == "mutation_confirmed" || first.status == "mutation_unconfirmed" && first.reason == "MODEL_READBACK_UNCONFIRMED")
            if (!healthyWrite) return@withTimeoutOrNull outcome(false, "WRITE_STAGE_UNCONFIRMED")

            stage = "LATER_EDT_TURN"
            // Always posts, including when invoked from EDT. No facade/token is captured.
            awaitM0LaterEdtTurn()
            currentCoroutineContext().ensureActive()
            laterTurnPassed = true
            // All Swing source/profile checks execute on the posted EDT turn below.
            val ready = checkM0AutomaticTargetOnLaterTurn(target, plan, permissionStillValid, automaticUi)
            currentCoroutineContext().ensureActive()
            if (!ready) return@withTimeoutOrNull outcome(false, "FROZEN_TARGET_OR_PERMISSION_CHANGED_BETWEEN_PHASES")

            stage = "FRESH_READ_EVENT"
            // This provider receives read-only comparison material, never the write plan.
            fresh = observeM0ContextOnTarget(api, target, permissionStillValid = permissionStillValid,
                automaticInvocation = true, automaticUi = automaticUi, deadlineMillis = deadlineMillis,
                readbackExpectation = M0ReadbackExpectation(plan.replacementCopy(), plan.expectedHost,
                    plan.expectedPort, plan.expectedTls, plan.expectedVersion))
            currentCoroutineContext().ensureActive()
            val read = checkNotNull(fresh)
            val readAuto = read.automaticContext
            val match = read.freshReadback
            val confirmed = read.status == "observed" && !read.modelWriteAttempted && read.callbackExited &&
                read.providerCleanupConfirmed && readAuto?.dispatchExited == true && readAuto.exactIssuedEventObserved &&
                readAuto.popupObserved && readAuto.popupInvokerOwned && readAuto.popupCleanupConfirmed &&
                match?.completeBytesEqual == true && match.serviceEqual && match.messageVersionEqual
            stage = "FINAL_TARGET_CHECK"
            val finalReady = checkM0AutomaticTargetOnLaterTurn(target, plan, permissionStillValid, automaticUi)
            currentCoroutineContext().ensureActive()
            outcome(confirmed && finalReady, if (confirmed && finalReady) "NEW_CONTEXT_EXACT_READBACK" else "FRESH_READ_OR_FINAL_TARGET_UNCONFIRMED")
        }
        return finished ?: outcome(false, "SHARED_DEADLINE_AT_$stage")
    } catch (cancel: CancellationException) {
        // Transport cancellation may prevent any reply. Monotonic evidence remains
        // independent of missing phase results; cancellation never permits phase2.
        throw cancel
    } catch (_: Exception) {
        return outcome(false, "STAGE_FAILURE_AT_$stage")
    } finally {
        attempt.close() // Cannot erase STARTED/normal-return evidence.
    }
}

private suspend fun awaitM0LaterEdtTurn() = suspendCancellableCoroutine<Unit> { continuation ->
    EventQueue.invokeLater { if (continuation.isActive) continuation.resume(Unit) }
}

private suspend fun checkM0AutomaticTargetOnLaterTurn(target: M0ContextTarget, plan: M0ByteWritePlan,
    permission: () -> Boolean, ui: M0AutomaticContextUi): Boolean = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (continuation.isActive) {
            val ready = try {
                val source = target.automaticSource
                source != null && permission() && ui.showing(source) && source.width > 0 && source.height > 0 &&
                    !source.isEditing && ui.menuPath().isEmpty() && target.ownsSource(source) && target.canMutateNow() &&
                    target.matchesNativeService(plan.expectedHost, plan.expectedPort, plan.expectedTls)
            } catch (_: Exception) { false }
            if (continuation.isActive) continuation.resume(ready)
        }
    }
}

@Serializable
internal data class M0AutomaticWriteObservation(
    val status: String, val reason: String, val tabId: String,
    val modelWriteAttempted: Boolean, val setterReturnedNormally: Boolean,
    val mutationMayHaveOccurred: Boolean, val freshContextConfirmed: Boolean,
    val laterEdtTurnPassed: Boolean, val writeStage: M0ContextModelObservation?,
    val freshReadStage: M0ContextModelObservation?,
    val configuredProtocolVerified: Boolean = false,
    val configuredProtocol: String = "unverified",
    val confirmationBasis: String = "current bytes/service/model-version in a new exact generated context; not wire/configured mode/history"
)
