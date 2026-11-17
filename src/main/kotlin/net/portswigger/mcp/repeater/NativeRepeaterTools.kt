package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.tools.mcpTool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun Server.registerNativeRepeaterTools(api: MontoyaApi, config: McpConfig) {
    val native = NativeRepeater { api.userInterface().swingUtils().suiteFrame() }
    val operations = Mutex()
    val investigationMode = java.lang.Boolean.getBoolean("net.portswigger.mcp.repeater.m0")
    suspend fun checkReadPermission() {
        check(DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config)) {
            "Repeater data access denied by Burp Suite."
        }
    }

    if (investigationMode) {
    val probeJson = Json { encodeDefaults = true }
    mcpTool<RepeaterM0Probe>("M0 INVESTIGATION ONLY. inspect/edit/model_read require M0_UNVERIFIED_NATIVE_MODEL acknowledgement. model_read_auto requires M0_AUTO_CONTEXT_READ and one exact synchronous public event/owned popup on the frozen Hex table, read-only. model_write_bytes requires M0_BYTE_MODEL_WRITE and manual context. model_write_bytes_auto requires M0_AUTO_BYTE_MODEL_WRITE: one guarded setter, then callback/dispatch exit, popup cleanup and deregistration, later EDT turn and a new read-only context for complete-byte/service/model-version confirmation. Writers require expected model hash/service/version and replacementRequestBase64. Shared automatic-write deadline30s; missing confirmation is possible mutation, never retry. Byte semantics are probe-only, not final editor_text. Exact tool/tab already selected. No Send, view/focus/settings change, rollback or retained facade. Synthetic disposable M0 project only.") {
        require(action in setOf("inspect", "edit", "model_read", "model_read_auto", "model_write_bytes", "model_write_bytes_auto")) { "Unsupported M0 action" }
        val automaticWriting = action == "model_write_bytes_auto"
        val writing = action == "model_write_bytes" || automaticWriting
        val automaticRead = action == "model_read_auto"
        require(acknowledgement == when { automaticWriting -> "M0_AUTO_BYTE_MODEL_WRITE";
            writing -> "M0_BYTE_MODEL_WRITE"; automaticRead -> "M0_AUTO_CONTEXT_READ";
            else -> "M0_UNVERIFIED_NATIVE_MODEL" }) { "Explicit M0 action acknowledgement required" }
        require((action == "edit") == (requestContent != null && expectedFingerprint != null)) {
            "edit requires requestContent and expectedFingerprint; inspect accepts neither"
        }
        if (action != "edit") require(requestContent == null && expectedFingerprint == null)
        val writeFields = listOf(expectedModelRequestSha256, expectedTargetHostname, expectedTargetPort,
            expectedUsesHttps, expectedModelHttpVersion, replacementRequestBase64)
        require(if (writing) writeFields.all { it != null } else writeFields.all { it == null }) { "M0 byte-write fields must be complete and exclusive" }
        val writePlan = if (writing) M0ByteWritePlan.parse(checkNotNull(expectedModelRequestSha256), checkNotNull(expectedTargetHostname),
            checkNotNull(expectedTargetPort), checkNotNull(expectedUsesHttps), checkNotNull(expectedModelHttpVersion),
            checkNotNull(replacementRequestBase64)) else null
        checkReadPermission()
        val approvedRequire = config.requireDataAccessApproval
        val approvedAlways = config.alwaysAllowRepeater
        fun permissionStillValid() = !config.requireDataAccessApproval || config.alwaysAllowRepeater ||
            (config.requireDataAccessApproval == approvedRequire && config.alwaysAllowRepeater == approvedAlways)
        operations.withLock {
            if (action == "edit") {
                val snapshot = native.investigateEdit(tabId, checkNotNull(expectedFingerprint), checkNotNull(requestContent))
                probeJson.encodeToString(RepeaterM0EditResult(snapshot.tabId, snapshot.fingerprint,
                    snapshot.targetHostname, snapshot.targetPort, snapshot.usesHttps))
            } else if (automaticWriting) {
                probeJson.encodeToString(observeM0AutomaticWrite(api, native, tabId, checkNotNull(writePlan), ::permissionStillValid))
            } else if (action == "model_read" || automaticRead || writing) {
                val observation = observeM0ContextModel(api, native, tabId, writePlan, ::permissionStillValid,
                    automaticRead = automaticRead)
                if (!writing) checkReadPermission()
                probeJson.encodeToString(observation)
            } else {
                probeJson.encodeToString(RepeaterM0Inspection(native.investigateControls(tabId)))
            }
        }
    }
    }

    mcpTool<GetRepeaterTabs>("Experimental native Swing Repeater control. Lists native tab IDs and names, including collapsed groups' request tabs. kind=group identifies headers that cannot be sent. groupPath=null means membership is unavailable in Burp's flat group layout. Selects Repeater. IDs last until server restart or tab closure; duplicate names are allowed.") {
        checkReadPermission()
        operations.withLock { Json.encodeToString(native.tabs()) }
    }

    mcpTool<GetRepeaterTab>("Experimental: selects the exact native Repeater tab ID and reads its current request, displayed response, target and fingerprint. Use get_repeater_tabs first. Only HTTP tabs in Pretty/Raw views are supported.") {
        checkReadPermission()
        operations.withLock { Json.encodeToString(native.read(tabId)) }
    }

    mcpTool<SendRepeaterTab>("Experimental: clicks Send in the exact native Repeater tab and returns the first matching HTTP exchange. Requires the fingerprint from get_repeater_tab; rejects changed or closed tabs. Do not manually send identical requests concurrently. A timeout or cancellation after the click does not cancel Burp's request: inspect Burp before retrying. Single-request mode only; redirects and session rules can prevent exact capture.") {
        val timeout = timeoutMs ?: 10_000
        val responseLimit = maxResponseLength ?: 20_000
        require(timeout in 100..60_000) { "timeoutMs must be between 100 and 60000." }
        require(responseLimit in 1..200_000) { "maxResponseLength must be between 1 and 200000." }
        checkReadPermission()
        operations.withLock {
            val expected = native.read(tabId)
            require(expected.fingerprint == expectedFingerprint) {
                "Repeater request or target changed. Read the tab again before sending."
            }
            check(HttpRequestSecurity.checkHttpRequestPermission(
                expected.targetHostname, expected.targetPort, config, expected.request, api
            )) { "Repeater send denied by Burp Suite." }
            currentCoroutineContext().ensureActive()
            val captureProbe = if (investigationMode) RepeaterM0CaptureProbe() else null
            val capture = RepeaterExchangeCapture(expected, captureProbe)
            val registration = api.http().registerHttpHandler(capture)
            try {
                native.send(tabId, expectedFingerprint) { capture.arm() }
                api.logging().logToOutput("MCP native Repeater Send: $tabId ${expected.targetHostname}:${expected.targetPort}")
                val exchange = withTimeoutOrNull(timeout.toLong()) {
                    val received = capture.result.await()
                    // The response handler runs before Burp updates its native completion controls.
                    try {
                        native.awaitIdle(tabId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error("The request was sent and a response captured, but the native tab changed before completion. Inspect Burp before retrying.")
                    }
                    received
                }
                    ?: error("No matching Repeater response captured within ${timeout}ms. The native request may still be running or may have been modified by session rules. Inspect Burp before retrying; no retry was made." +
                        (captureProbe?.let { " M0_CAPTURE_DIAGNOSTICS=" + Json.encodeToString(it.snapshot()) } ?: ""))
                Json.encodeToString(RepeaterSendResult(
                    tabId, exchange.messageId, exchange.request, exchange.response.take(responseLimit),
                    exchange.response.length > responseLimit, captureProbe?.snapshot()
                ))
            } finally {
                registration.deregister()
            }
        }
    }
}

/** Observe one exact Repeater exchange. Pass all traffic through unchanged. */
internal class RepeaterExchangeCapture(
    private val expected: RepeaterSnapshot, private val probe: RepeaterM0CaptureProbe? = null
) : HttpHandler {
    private val armed = AtomicBoolean(false)
    private val requestId = AtomicInteger(-1)
    val result = CompletableDeferred<CapturedRepeaterExchange>()

    fun arm() { armed.set(true) }

    override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
        if (armed.get() && request.toolSource().isFromTool(ToolType.REPEATER)) {
            val service = request.httpService()
            val sameService = service.host().equals(expected.targetHostname, ignoreCase = true) &&
                service.port() == expected.targetPort && service.secure() == expected.usesHttps
            val hookText = if (sameService) request.toString() else null
            if (probe != null) {
                var bodyBytes: ByteArray? = null
                var messageVersion: String? = null
                if (sameService) try {
                    bodyBytes = request.body().bytes
                    messageVersion = request.httpVersion()
                } catch (_: Exception) {
                    probe.failure()
                }
                try {
                    probe.request(expected.request, hookText.orEmpty(), request.messageId(), sameService,
                        bodyBytes, messageVersion)
                } catch (_: Exception) {
                    // Diagnostic failure must not change traffic or relax the exact matcher.
                    probe.failure()
                }
            }
            if (hookText != null && canonicalRequest(hookText) == canonicalRequest(expected.request)) {
                requestId.compareAndSet(-1, request.messageId())
            }
        }
        return RequestToBeSentAction.continueWith(request, request.annotations())
    }

    override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
        if (armed.get() && probe != null) {
            try {
                if (response.toolSource().isFromTool(ToolType.REPEATER)) probe.response(response.messageId())
            } catch (_: Exception) {
                probe.failure()
            }
        }
        if (requestId.get() >= 0 && response.toolSource().isFromTool(ToolType.REPEATER) && response.messageId() == requestId.get()) {
            result.complete(CapturedRepeaterExchange(
                response.messageId(), response.initiatingRequest().toString(), response.toString()
            ))
        }
        return ResponseReceivedAction.continueWith(response, response.annotations())
    }
}

internal data class CapturedRepeaterExchange(val messageId: Int, val request: String, val response: String)

@Serializable
internal class GetRepeaterTabs

@Serializable
internal data class GetRepeaterTab(val tabId: String)

@Serializable
internal data class SendRepeaterTab(
    val tabId: String, val expectedFingerprint: String,
    val timeoutMs: Int? = null, val maxResponseLength: Int? = null
)

@Serializable
internal data class RepeaterSendResult(
    val tabId: String, val messageId: Int, val request: String,
    val response: String, val responseTruncated: Boolean,
    val m0CaptureDiagnostics: M0CaptureDiagnostics? = null
)

@Serializable
internal data class RepeaterM0Probe(
    val tabId: String, val action: String, val acknowledgement: String,
    val expectedFingerprint: String? = null, val requestContent: String? = null,
    val expectedModelRequestSha256: String? = null, val expectedTargetHostname: String? = null,
    val expectedTargetPort: Int? = null, val expectedUsesHttps: Boolean? = null,
    val expectedModelHttpVersion: String? = null, val replacementRequestBase64: String? = null
)

@Serializable
internal data class RepeaterM0Inspection(
    val controls: List<RepeaterControlProbe>,
    val configuredProtocol: String = "unverified",
    val redirectPolicy: String = "unverified",
    val groupSendMode: String = "unverified",
    val sessionRulesAndMacros: String = "unverified",
    val destinationOverrides: String = "unverified",
    val profileVerified: Boolean = false,
    val purpose: String = "M0 characterization; control labels are not effective-state proof"
)

@Serializable
internal data class RepeaterM0EditResult(
    val tabId: String, val fingerprint: String, val targetHostname: String,
    val targetPort: Int, val usesHttps: Boolean,
    val mutationMayHaveOccurred: Boolean = true,
    val nativeModelVerified: Boolean = false
)
