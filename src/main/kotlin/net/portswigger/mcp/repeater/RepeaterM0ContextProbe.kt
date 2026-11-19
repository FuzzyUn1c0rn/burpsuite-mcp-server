package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.InvocationType
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse.SelectionContext
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.awt.Component
import java.security.MessageDigest
import javax.swing.SwingUtilities
import javax.swing.JTable
import net.portswigger.mcp.Swing
import java.util.concurrent.atomic.AtomicInteger

/** Identity validator is transient and released when the observation closes. */
internal class M0ContextTarget(
    val tabId: String, val view: String,
    val describeSource: ((Component) -> M0ContextOwnership)? = null,
    val canMutateNow: () -> Boolean = { false },
    val describeMutationReadiness: (() -> M0NativeReadiness)? = null,
    val matchesNativeService: (String, Int, Boolean) -> Boolean = { _, _, _ -> true },
    val automaticSource: JTable? = null,
    val captureProfileMenuInvoker: ((Component) -> M0ProfileMenuInvokerLease)? = null,
    val readDraftUi: ((Int?) -> DraftUiState)? = null,
    val ownsSource: (Component) -> Boolean
)

/** One callback-scoped M0 model experiment. Writes require a separate explicit byte plan. */
internal class RepeaterM0ContextProbe(
    target: M0ContextTarget, private val startedAtMillis: Long = System.currentTimeMillis(),
    writePlan: M0ByteWritePlan? = null,
    private val permissionStillValid: () -> Boolean = { true },
    private val constructRequest: (HttpService, ByteArray) -> HttpRequest = ::constructM0ByteRequest,
    private val automaticContext: M0AutomaticContext? = null,
    private val attempt: M0MutationAttempt = M0MutationAttempt(),
    private val allowAutomaticWrite: Boolean = false,
    readbackExpectation: M0ReadbackExpectation? = null,
    receiveRequestBytes: ((ByteArray) -> Unit)? = null
) : ContextMenuItemsProvider {
    init {
        require(automaticContext == null || writePlan == null || allowAutomaticWrite) { "M0_AUTOMATIC_CONTEXT_IS_READ_ONLY" }
        require(readbackExpectation == null || writePlan == null) { "M0_FRESH_READ_PHASE_CANNOT_WRITE" }
        require(receiveRequestBytes == null || writePlan == null) { "DRAFT_REQUEST_CAPTURE_IS_READ_ONLY" }
    }
    @Volatile private var target: M0ContextTarget? = target
    @Volatile private var writePlan: M0ByteWritePlan? = writePlan
    @Volatile private var readbackExpectation: M0ReadbackExpectation? = readbackExpectation
    @Volatile private var receiveRequestBytes: ((ByteArray) -> Unit)? = receiveRequestBytes
    private val callbackDepth = AtomicInteger()
    val callbackExited get() = callbackDepth.get() == 0
    val result = CompletableDeferred<M0ContextModelObservation>()

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        callbackDepth.incrementAndGet()
        try { return observeCallback(event) } finally { callbackDepth.decrementAndGet() }
    }

    private fun observeCallback(event: ContextMenuEvent): List<Component> {
        val current = target ?: return emptyList()
        if (result.isCompleted || !attempt.open) return emptyList()
        val plan = writePlan
        var phase = "read"
        var observedInvocation: String? = null
        var constructorDiagnostics: M0ConstructorObservation? = null
        var mutationGuards: M0MutationGuards? = null
        var readbackDiagnostics: M0ReadbackObservation? = null
        fun reject(reason: String, ownership: M0ContextOwnership? = null) {
            if (target === current) {
                val attempted = attempt.attempted
                result.complete(M0ContextModelObservation(if (attempted) "mutation_unconfirmed" else "rejected", reason,
                    current.tabId, current.view, modelWriteAttempted = attempted,
                    ownership = ownership, observedInvocationType = observedInvocation, mutationMayHaveOccurred = attempted,
                    constructorDiagnostics = constructorDiagnostics, mutationGuards = mutationGuards,
                    readbackDiagnostics = readbackDiagnostics))
            }
        }
        fun closed() = target !== current || result.isCompleted
        fun stopModelAccess(): Boolean {
            if (closed()) return true
            if (!permissionStillValid()) {
                reject(if (attempt.attempted) "POST_MUTATION_PERMISSION_CHANGED"
                    else "PERMISSION_CHANGED_DURING_MODEL_READ")
                return true
            }
            return false
        }
        try {
            if (!event.isFromTool(ToolType.REPEATER) || closed()) return emptyList()
            if (!SwingUtilities.isEventDispatchThread()) {
                reject("UNSUPPORTED_CALLBACK_THREAD")
                return emptyList()
            }
            val input = event.inputEvent()
            if (closed()) return emptyList()
            if (automaticContext != null && !automaticContext.accept(input)) return emptyList()
            if (automaticContext == null && input.`when` <= startedAtMillis) {
                reject("EVENT_NOT_NEWER_THAN_OBSERVATION")
                return emptyList()
            }
            val source = input.component
            val invocation = event.invocationType()
            observedInvocation = invocation?.name
            if (closed()) return emptyList()
            if (invocation != InvocationType.MESSAGE_EDITOR_REQUEST) {
                reject("NOT_REQUEST_EDITOR_INVOCATION")
                return emptyList()
            }
            if (!current.ownsSource(source)) {
                if (closed()) return emptyList()
                val ownership = try { current.describeSource?.invoke(source) } catch (_: Exception) { null }
                reject("OWNERSHIP_UNCONFIRMED", ownership)
                return emptyList()
            }
            if (closed()) return emptyList()
            val editor = event.messageEditorRequestResponse().orElse(null)
            if (closed()) return emptyList()
            if (editor == null) {
                reject("EDITOR_HANDLE_UNAVAILABLE")
                return emptyList()
            }
            if (editor.selectionContext() != SelectionContext.REQUEST) {
                reject("NOT_REQUEST_EDITOR_CONTEXT")
                return emptyList()
            }
            if (closed()) return emptyList()
            if (plan != null && current.view != "Hex") { reject("M0_BYTE_WRITER_REQUIRES_HEX"); return emptyList() }
            if (!permissionStillValid()) { reject("PERMISSION_CHANGED_BEFORE_MODEL_READ"); return emptyList() }
            var snapshot = copyM0EditorModel(editor, ::stopModelAccess) ?: return emptyList()
            fun mutationReady(stage: String): Boolean {
                val owned = current.ownsSource(source)
                val idle = if (owned) try { current.canMutateNow() &&
                    current.matchesNativeService(snapshot.host, snapshot.port, snapshot.tls) } catch (_: Exception) { null } else null
                val permitted = permissionStillValid()
                if (owned && idle == true && permitted) return true
                val detail = try { current.describeMutationReadiness?.invoke() } catch (_: Exception) { null }
                mutationGuards = M0MutationGuards(stage, owned, idle, permitted, detail)
                val ownership = if (!owned) try { current.describeSource?.invoke(source) } catch (_: Exception) { null } else null
                reject(if (stage == "before_constructor") "IDENTITY_CHANGED_OR_NATIVE_BUSY" else "FINAL_IDENTITY_BUSY_OR_PERMISSION_CHANGED", ownership)
                return false
            }
            if (plan != null) {
                if (!permissionStillValid()) { reject("PERMISSION_CHANGED_BEFORE_MUTATION"); return emptyList() }
                if (!plan.matches(snapshot.bytes, snapshot.host, snapshot.port, snapshot.tls, snapshot.version)) {
                    reject("STALE_MODEL_OR_SERVICE_PROTOCOL_CHANGED"); return emptyList()
                }
                if (!mutationReady("before_constructor")) return emptyList()
                val replacementBytes = plan.replacementCopy()
                val replacement = constructRequest(snapshot.service, replacementBytes)
                val constructed = copyM0RequestModel(replacement, ::stopModelAccess) ?: return emptyList()
                constructorDiagnostics = M0ConstructorObservation(constructed.bytes.contentEquals(replacementBytes),
                    constructed.sameService(snapshot), constructed.version == snapshot.version,
                    constructed.body.size, m0ByteHash(constructed.body), constructed.version)
                if (!constructed.bytes.contentEquals(replacementBytes) || !constructed.sameServiceProtocol(snapshot)) {
                    reject("CONSTRUCTOR_BYTES_OR_INVARIANTS_UNSUPPORTED"); return emptyList()
                }
                val currentSnapshot = copyM0EditorModel(editor, ::stopModelAccess) ?: return emptyList()
                if (!currentSnapshot.bytes.contentEquals(snapshot.bytes) || !currentSnapshot.sameServiceProtocol(snapshot)) {
                    reject("MODEL_CHANGED_BEFORE_MUTATION"); return emptyList()
                }
                if (!mutationReady("before_setter")) return emptyList()
                if (closed() || !attempt.admit()) return emptyList()
                // The attempt is recorded BEFORE the void API call. Closing cannot undo it.
                phase = "setter"
                editor.setRequest(replacement)
                attempt.recordNormalReturn()
                phase = "readback"
                if (closed()) return emptyList()
                if (!permissionStillValid() || !current.ownsSource(source)) {
                    reject("POST_MUTATION_PERMISSION_OR_IDENTITY_CHANGED"); return emptyList()
                }
                val readback = copyM0EditorModel(editor, ::stopModelAccess) ?: return emptyList()
                readbackDiagnostics = M0ReadbackObservation(readback.bytes.size, sha256(readback.bytes),
                    readback.body.size, sha256(readback.body), readback.bytes.contentEquals(snapshot.bytes),
                    readback.bytes.contentEquals(replacementBytes), readback.sameService(snapshot), readback.version == snapshot.version)
                if (!readback.bytes.contentEquals(replacementBytes) || !readback.sameServiceProtocol(snapshot)) {
                    reject("MODEL_READBACK_UNCONFIRMED"); return emptyList()
                }
                snapshot = readback
            }
            val observation = M0ContextModelObservation(if (plan == null) "observed" else "mutation_confirmed",
                if (plan == null) "CALLBACK_MODEL_METADATA_ONLY" else "CALLBACK_EXACT_READBACK; fresh later-context proof still required",
                current.tabId, current.view, snapshot.bytes.size, sha256(snapshot.bytes), snapshot.body.size, sha256(snapshot.body),
                snapshot.host, snapshot.port, snapshot.tls, snapshot.version, modelWriteAttempted = plan != null,
                observedInvocationType = observedInvocation, mutationMayHaveOccurred = plan != null, modelWriteConfirmed = plan != null,
                constructorDiagnostics = constructorDiagnostics, readbackDiagnostics = readbackDiagnostics,
                nativeReadiness = try { current.describeMutationReadiness?.invoke() } catch (_: Exception) { null },
                freshReadback = readbackExpectation?.compare(snapshot.bytes, snapshot.host, snapshot.port, snapshot.tls, snapshot.version))
            if (closed()) return emptyList()
            if (!permissionStillValid()) {
                reject(if (plan == null) "PERMISSION_CHANGED_DURING_MODEL_READ" else "POST_MUTATION_PERMISSION_CHANGED")
                return emptyList()
            }
            if (!current.ownsSource(source)) reject("IDENTITY_CHANGED_DURING_MODEL_READ")
            else if (target === current) {
                receiveRequestBytes?.invoke(snapshot.bytes)
                result.complete(observation)
            }
        } catch (failure: M0ModelFailure) {
            reject(failure.reason)
        } catch (_: Exception) {
            reject(when (phase) { "setter" -> "MODEL_SETTER_FAILED"; "readback" -> "MODEL_READBACK_UNAVAILABLE"; else -> "MODEL_METADATA_UNAVAILABLE" })
        }
        return emptyList()
    }

    fun close() {
        attempt.close()
        target = null
        writePlan = null
        readbackExpectation = null
        receiveRequestBytes = null
        if (!result.isCompleted) result.cancel()
    }

    fun closeForTimeout(tabId: String, view: String): M0ContextModelObservation {
        close()
        val attempted = attempt.attempted
        return M0ContextModelObservation(if (attempted) "mutation_unconfirmed" else "not_observed",
            if (attempted) "DEADLINE_AFTER_MUTATION_ADMISSION" else "NO_CONTEXT_EVENT_WITHIN_30000MS", tabId, view,
            modelWriteAttempted = attempted, mutationMayHaveOccurred = attempted)
    }

    fun closeForRegistrationFailure(tabId: String, view: String): M0ContextModelObservation {
        close()
        val attempted = attempt.attempted
        return M0ContextModelObservation(if (attempted) "mutation_unconfirmed" else "rejected",
            "M0_CONTEXT_REGISTRATION_UNCONFIRMED", tabId, view,
            modelWriteAttempted = attempted, mutationMayHaveOccurred = attempted)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

private class M0ModelFailure(val reason: String) : RuntimeException(reason)

private data class M0ModelBytes(
    val bytes: ByteArray, val body: ByteArray, val service: HttpService,
    val host: String, val port: Int, val tls: Boolean, val version: String
) {
    fun sameService(other: M0ModelBytes) = host.equals(other.host, true) && port == other.port && tls == other.tls
    fun sameServiceProtocol(other: M0ModelBytes) = sameService(other) && version == other.version
}

private fun copyM0EditorModel(editor: MessageEditorHttpRequestResponse, closed: () -> Boolean): M0ModelBytes? {
    if (closed()) return null
    val pair = editor.requestResponse()
    if (closed()) return null
    val request = pair.request()
    if (closed()) return null
    return copyM0RequestModel(request, closed)
}

private fun copyM0RequestModel(request: HttpRequest, closed: () -> Boolean): M0ModelBytes? {
    if (closed()) return null
    val message = request.toByteArray()
    if (closed()) return null
    val body = request.body()
    if (closed()) return null
    val messageLength = message.length()
    if (closed()) return null
    val bodyLength = body.length()
    if (closed()) return null
    if (messageLength !in 0..DRAFT_REQUEST_LIMIT || bodyLength !in 0..DRAFT_REQUEST_LIMIT)
        throw M0ModelFailure("MODEL_REQUEST_TOO_LARGE")
    if (closed()) return null
    val bytes = message.bytes
    if (closed()) return null
    val bodyBytes = body.bytes
    if (closed()) return null
    val offset = request.bodyOffset()
    if (bytes.size != messageLength || bodyBytes.size != bodyLength || offset !in 0..bytes.size ||
        !bytes.copyOfRange(offset, bytes.size).contentEquals(bodyBytes)) throw M0ModelFailure("MODEL_BYTES_INCONSISTENT")
    if (closed()) return null
    val service = request.httpService()
    if (closed()) return null
    val host = service.host()
    if (closed()) return null
    val port = service.port()
    if (closed()) return null
    val tls = service.secure()
    if (closed()) return null
    if (host.length !in 1..253 || port !in 1..65535) throw M0ModelFailure("MODEL_DESTINATION_UNSUPPORTED")
    val version = request.httpVersion().takeIf {
        it in setOf("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/2.0", "1.0", "1.1", "2", "2.0")
    } ?: "unknown"
    return if (closed()) null else M0ModelBytes(bytes, bodyBytes, service, host, port, tls, version)
}

internal suspend fun observeM0ContextModel(api: MontoyaApi, native: NativeRepeater, tabId: String,
    writePlan: M0ByteWritePlan? = null, permissionStillValid: () -> Boolean = { true },
    constructRequest: (HttpService, ByteArray) -> HttpRequest = ::constructM0ByteRequest,
    automaticRead: Boolean = false, automaticUi: M0AutomaticContextUi = M0AutomaticContextUi(),
    deadlineMillis: Long = 30_000): M0ContextModelObservation {
    val target = native.modelContextTarget(tabId, permissionStillValid)
    require(writePlan == null || target.view == "Hex") { "M0_BYTE_WRITER_REQUIRES_HEX" }
    require(!automaticRead || writePlan == null && target.view == "Hex" && target.automaticSource != null) {
        "M0_AUTOMATIC_READ_REQUIRES_CAPTURED_HEX_TABLE"
    }
    return observeM0ContextOnTarget(api, target, writePlan, permissionStillValid, constructRequest,
        automaticRead, automaticUi, deadlineMillis)
}

internal suspend fun observeM0ContextOnTarget(api: MontoyaApi, target: M0ContextTarget,
    writePlan: M0ByteWritePlan? = null, permissionStillValid: () -> Boolean = { true },
    constructRequest: (HttpService, ByteArray) -> HttpRequest = ::constructM0ByteRequest,
    automaticInvocation: Boolean = false, automaticUi: M0AutomaticContextUi = M0AutomaticContextUi(),
    deadlineMillis: Long = 30_000, attempt: M0MutationAttempt = M0MutationAttempt(),
    allowAutomaticWrite: Boolean = false, readbackExpectation: M0ReadbackExpectation? = null,
    receiveRequestBytes: ((ByteArray) -> Unit)? = null): M0ContextModelObservation {
    val tabId = target.tabId
    require(writePlan == null || target.view == "Hex") { "M0_BYTE_WRITER_REQUIRES_HEX" }
    require(!automaticInvocation || target.view == "Hex" && target.automaticSource != null) { "M0_AUTOMATIC_CONTEXT_REQUIRES_HEX" }
    val automatic = if (automaticInvocation) M0AutomaticContext(checkNotNull(target.automaticSource),
        target.ownsSource, permissionStillValid, automaticUi) else null
    val probe = RepeaterM0ContextProbe(target, writePlan = writePlan, permissionStillValid = permissionStillValid,
        constructRequest = constructRequest, automaticContext = automatic, attempt = attempt,
        allowAutomaticWrite = allowAutomaticWrite, readbackExpectation = readbackExpectation,
        receiveRequestBytes = receiveRequestBytes)
    var cleanupFailed = false
    val observation = try {
        withTimeoutOrNull(deadlineMillis) {
            val callContext = currentCoroutineContext()
            // Immediate cancellation completion also closes issuance while a public
            // registration call is blocked. No internal coroutine APIs or EDT locks.
            val cancellationLease = Job(callContext[Job])
            val cancelHandler = cancellationLease.invokeOnCompletion { cause ->
                if (cause != null) { automatic?.close(); probe.close() }
            }
            var registration: burp.api.montoya.core.Registration? = null
            try {
                callContext.ensureActive()
                try { registration = api.userInterface().registerContextMenuItemsProvider(probe) }
                catch (_: Exception) {
                    callContext.ensureActive()
                    return@withTimeoutOrNull probe.closeForRegistrationFailure(tabId, target.view)
                }
                callContext.ensureActive()
                if (automatic != null) {
                    val dispatched = try { withContext(Dispatchers.Swing) {
                        callContext.ensureActive()
                        automatic.dispatchOnce()
                    } } catch (_: Exception) {
                        callContext.ensureActive()
                        false
                    }
                    if (!dispatched) return@withTimeoutOrNull M0ContextModelObservation(
                        if (attempt.attempted) "mutation_unconfirmed" else "rejected",
                        "AUTOMATIC_CONTEXT_DISPATCH_OR_POPUP_UNCONFIRMED", tabId, target.view,
                        modelWriteAttempted = attempt.attempted, mutationMayHaveOccurred = attempt.attempted)
                }
                callContext.ensureActive()
                probe.result.await()
            } finally {
                automatic?.close()
                probe.close()
                cancelHandler.dispose()
                cancellationLease.complete()
                try { registration?.deregister() } catch (_: Exception) { cleanupFailed = true }
            }
        } ?: probe.closeForTimeout(tabId, target.view)
    } finally {
        automatic?.close()
        probe.close()
    }
    val reported = observation.copy(automaticContext = automatic?.diagnostics(),
        modelWriteAttempted = attempt.attempted, mutationMayHaveOccurred = attempt.attempted,
        setterReturnedNormally = attempt.returnedNormally, callbackExited = probe.callbackExited,
        providerCleanupConfirmed = !cleanupFailed)
    return if (cleanupFailed) reported.copy(
        status = if (reported.modelWriteAttempted) "mutation_unconfirmed" else "rejected",
        reason = "M0_CONTEXT_DEREGISTRATION_UNCONFIRMED", modelWriteConfirmed = false
    ) else reported
}

@Serializable
internal data class M0ContextModelObservation(
    val status: String, val reason: String, val tabId: String, val selectedRequestView: String,
    val modelRequestByteLength: Int? = null, val modelRequestSha256: String? = null,
    val modelBodyByteLength: Int? = null, val modelBodySha256: String? = null,
    val targetHostname: String? = null, val targetPort: Int? = null, val usesHttps: Boolean? = null,
    val montoyaMessageHttpVersion: String? = null,
    val representation: String = "Montoya HttpRequest.toByteArray()/body().getBytes(); not wire capture",
    val handleLifetime: String = "callback_only; later lifetime untested",
    val modelWriteAttempted: Boolean = false,
    val editorViewSwitchAttempted: Boolean = false,
    val ownership: M0ContextOwnership? = null,
    val observedInvocationType: String? = null,
    val mutationMayHaveOccurred: Boolean = false, val modelWriteConfirmed: Boolean = false,
    val configuredProtocolVerified: Boolean = false,
    val configuredProtocol: String = "unverified",
    val byteInputContract: String = "M0 model bytes only; final editor_text semantics unassigned",
    val constructorDiagnostics: M0ConstructorObservation? = null,
    val mutationGuards: M0MutationGuards? = null,
    val nativeReadiness: M0NativeReadiness? = null,
    val readbackDiagnostics: M0ReadbackObservation? = null,
    val automaticContext: M0AutomaticContextObservation? = null,
    val setterReturnedNormally: Boolean = false, val callbackExited: Boolean = false,
    val providerCleanupConfirmed: Boolean = false,
    val freshReadback: M0FreshReadbackObservation? = null
)

@Serializable
internal data class M0FreshReadbackObservation(val completeBytesEqual: Boolean, val serviceEqual: Boolean,
    val messageVersionEqual: Boolean)

@Serializable
internal data class M0ReadbackObservation(
    val requestByteLength: Int, val requestSha256: String, val bodyByteLength: Int, val bodySha256: String,
    val equalsOriginalBytes: Boolean, val equalsReplacementBytes: Boolean,
    val serviceEqual: Boolean, val messageVersionEqual: Boolean
)

@Serializable
internal data class M0NativeReadiness(
    val nativeTabPathIdentityValid: Boolean, val visibleCancelControlCount: Int,
    val cancelEnabledSamples: List<Boolean>, val visibleSendControlCount: Int,
    val visibleTargetLabelCount: Int, val cancelSamples: List<M0CancelControlDiagnostic> = emptyList(),
    val actionBarCandidateClass: String? = null, val actionBarCandidateDisjointFromEditors: Boolean = false,
    val actionBarCandidateCancelCount: Int? = null,
    val capturedActionBarPresent: Boolean = false, val capturedActionBarReady: Boolean = false,
    val effectiveShowingSendCount: Int = 0, val effectiveShowingTargetCount: Int = 0,
    val sendEnabledSamples: List<Boolean> = emptyList(), val sendShowingSamples: List<Boolean> = emptyList(),
    val sendPositiveSizeSamples: List<Boolean> = emptyList()
)

@Serializable
internal data class M0CancelControlDiagnostic(
    val componentClass: String, val enabled: Boolean, val showing: Boolean, val displayable: Boolean,
    val positiveSize: Boolean, val withinRequestOwner: Boolean, val withinResponseOwner: Boolean,
    val withinActionBarCandidate: Boolean?, val ancestorClasses: List<String>
)

@Serializable
internal data class M0MutationGuards(
    val stage: String, val ownershipValid: Boolean, val nativeIdleVerified: Boolean?,
    val permissionValid: Boolean, val nativeReadiness: M0NativeReadiness?
)

@Serializable
internal data class M0ConstructorObservation(
    val exactBytesEqual: Boolean, val serviceEqual: Boolean, val messageVersionEqual: Boolean,
    val bodyByteLength: Int, val bodyByteSha256: String, val messageVersion: String
)

@Serializable
internal data class M0ContextOwnership(
    val nativeTabPathIdentityValid: Boolean?, val viewIndexMatches: Boolean?,
    val viewComponentMatches: Boolean?, val viewTitleMatches: Boolean?, val viewPaneWithinTab: Boolean?,
    val eventSourceEqualsView: Boolean?, val eventSourceWithinView: Boolean?,
    val eventSourceWithinRequestPane: Boolean?, val eventSourceWithinTab: Boolean?,
    val selectedViewWithinEventSource: Boolean?,
    val eventSourceComponentClass: String, val eventSourcePublicKind: String,
    val eventSourceAncestorClasses: List<String>, val selectedViewComponentClass: String?,
    val requestPaneComponentClass: String, val diagnosticException: Boolean,
    val bindingMode: String = "selected_page",
    val requestRegionIdentityValid: Boolean? = null, val hexTableIdentityValid: Boolean? = null,
    val eventSourceWithinCapturedHexTable: Boolean? = null, val eventSourceWithinRequestRegion: Boolean? = null,
    val requestRegionVisibleTableCount: Int? = null, val requestRegionHasResponseOrInspector: Boolean? = null,
    val requestResponseBoundaryValid: Boolean? = null, val responseOwnerOutsideRequest: Boolean? = null,
    val eventSourceWithinResponseOwner: Boolean? = null
)
