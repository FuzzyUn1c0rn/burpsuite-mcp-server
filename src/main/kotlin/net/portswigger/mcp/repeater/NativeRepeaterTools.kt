package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.tools.mcpTool

internal fun Server.registerNativeRepeaterTools(api: MontoyaApi, config: McpConfig) {
    if (!java.lang.Boolean.getBoolean("net.portswigger.mcp.repeater.m0")) return
    val native = NativeRepeater { api.userInterface().swingUtils().suiteFrame() }
    val operations = Mutex()
    suspend fun checkReadPermission() {
        check(DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config)) {
            "Repeater data access denied by Burp Suite."
        }
    }

    val probeJson = Json { encodeDefaults = true }
    mcpTool<RepeaterM0Probe>("M0 INVESTIGATION ONLY. inspect/edit/model_read require M0_UNVERIFIED_NATIVE_MODEL acknowledgement. model_read_auto requires M0_AUTO_CONTEXT_READ and one exact synchronous public event/owned popup on the frozen Hex table, read-only. model_write_bytes requires M0_BYTE_MODEL_WRITE and manual context. model_write_bytes_auto requires M0_AUTO_BYTE_MODEL_WRITE: one guarded setter then a new read-only context after callback/dispatch exit, owned cleanup, deregistration and later EDT turn. Writers require expected hash/service/version/replacementRequestBase64, shared automatic-write deadline30s, uncertainty never retry. profile_menu_inspect requires M0_PROFILE_MENU_DISCOVERY: read an already manually opened gear menu, fixed semantic tokens/public state only, no open/select/close/reset or effective-profile claim. Byte semantics probe-only, not final editor_text. Exact tool/tab already selected. No Send, view/focus/settings change, rollback or retained facade. Synthetic disposable M0 project only.") {
        require(action in setOf("inspect", "edit", "model_read", "model_read_auto", "model_write_bytes", "model_write_bytes_auto", "profile_menu_inspect")) { "Unsupported M0 action" }
        val profileDiscovery = action == "profile_menu_inspect"
        val automaticWriting = action == "model_write_bytes_auto"
        val writing = action == "model_write_bytes" || automaticWriting
        val automaticRead = action == "model_read_auto"
        require(acknowledgement == when { profileDiscovery -> "M0_PROFILE_MENU_DISCOVERY";
            automaticWriting -> "M0_AUTO_BYTE_MODEL_WRITE";
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
        val profilePermission = if (profileDiscovery) approveM0ProfileMenuRead(config) else null
        if (!profileDiscovery) checkReadPermission()
        val approvedRequire = config.requireDataAccessApproval
        val approvedAlways = config.alwaysAllowRepeater
        fun permissionStillValid() = !config.requireDataAccessApproval || config.alwaysAllowRepeater ||
            (config.requireDataAccessApproval == approvedRequire && config.alwaysAllowRepeater == approvedAlways)
        operations.withLock {
            if (action == "edit") {
                val snapshot = native.investigateEdit(tabId, checkNotNull(expectedFingerprint), checkNotNull(requestContent))
                probeJson.encodeToString(RepeaterM0EditResult(snapshot.tabId, snapshot.fingerprint,
                    snapshot.targetHostname, snapshot.targetPort, snapshot.usesHttps))
            } else if (profileDiscovery) {
                val allowed = checkNotNull(profilePermission)
                val observation = inspectM0ProfileMenu(native, tabId, allowed)
                check(allowed()) { "Repeater data access changed during inspection." }
                probeJson.encodeToString(observation)
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

    mcpTool<GetRepeaterTabs>("Experimental native Swing Repeater control. Lists native tab IDs and names, including collapsed groups' request tabs. kind=group identifies headers that are not request drafts. groupPath=null means membership is unavailable in Burp's flat group layout. Selects Repeater. IDs last until server restart or tab closure; duplicate names are allowed.") {
        checkReadPermission()
        operations.withLock { Json.encodeToString(native.tabs()) }
    }

    mcpTool<GetRepeaterTab>("Experimental: selects the exact native Repeater tab ID and reads its current request, displayed response, target and fingerprint. Use get_repeater_tabs first. Only HTTP tabs in Pretty/Raw views are supported.") {
        checkReadPermission()
        operations.withLock { Json.encodeToString(native.read(tabId)) }
    }
}

@Serializable
internal class GetRepeaterTabs

@Serializable
internal data class GetRepeaterTab(val tabId: String)

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
