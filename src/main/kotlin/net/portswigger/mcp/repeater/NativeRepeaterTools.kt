package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.schema.asInputSchema
import kotlin.reflect.KClass
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.tools.mcpTool

internal fun Server.registerNativeRepeaterTools(api: MontoyaApi, config: McpConfig,
    draftService: RepeaterDraftService? = null): AutoCloseable? {
    val investigation = java.lang.Boolean.getBoolean("net.portswigger.mcp.repeater.m0")
    if (!investigation && !java.lang.Boolean.getBoolean("net.portswigger.mcp.repeater.drafts")) return null
    val service = draftService ?: newRepeaterDraftService(api, config)

    val probeJson = Json { encodeDefaults = true }
    if (investigation) mcpTool<RepeaterM0Probe>("M0 INVESTIGATION ONLY. inspect/edit/model_read require M0_UNVERIFIED_NATIVE_MODEL acknowledgement. model_read_auto requires M0_AUTO_CONTEXT_READ and one exact synchronous public event/owned popup on the frozen Hex table, read-only. model_write_bytes requires M0_BYTE_MODEL_WRITE and manual context. model_write_bytes_auto requires M0_AUTO_BYTE_MODEL_WRITE: one guarded setter then a new read-only context after callback/dispatch exit, owned cleanup, deregistration and later EDT turn. Writers require expected hash/service/version/replacementRequestBase64, shared automatic-write deadline30s, uncertainty never retry. profile_menu_inspect requires M0_PROFILE_MENU_DISCOVERY: read an already manually opened gear menu, fixed semantic tokens/public state only, no open/select/close/reset or effective-profile claim. Byte semantics probe-only, not final editor_text. Exact tool/tab already selected. No Send, view/focus/settings change, rollback or retained facade. Synthetic disposable M0 project only.") {
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
        service.investigate(tabId, mayMutate = action == "edit" || writing) { native, nativeId, allowed ->
            if (action == "edit") {
                val snapshot = native.investigateEdit(nativeId, checkNotNull(expectedFingerprint),
                    checkNotNull(requestContent), allowed)
                probeJson.encodeToString(RepeaterM0EditResult(tabId, snapshot.fingerprint,
                    snapshot.targetHostname, snapshot.targetPort, snapshot.usesHttps))
            } else if (profileDiscovery) {
                probeJson.encodeToString(inspectM0ProfileMenu(native, nativeId, allowed))
            } else if (automaticWriting) {
                probeJson.encodeToString(observeM0AutomaticWrite(api, native, nativeId, checkNotNull(writePlan), allowed))
            } else if (action == "model_read" || automaticRead || writing) {
                val observation = observeM0ContextModel(api, native, nativeId, writePlan, allowed,
                    automaticRead = automaticRead)
                probeJson.encodeToString(observation)
            } else {
                probeJson.encodeToString(RepeaterM0Inspection(native.investigateControls(nativeId, allowed)))
            }
        }
    }

    addDraftReadTool("get_repeater_tabs",
        "List a page of native Repeater drafts and groups. May select the main Repeater tool.",
        GetRepeaterTabs::class, GetRepeaterTabs.serializer(), DraftTabList.serializer(), service, service::list)
    addDraftReadTool("get_repeater_tab",
        "Read one native draft by ID. Select Hex in that draft for model-backed reads. A displayed response may belong to an earlier draft.",
        GetRepeaterTab::class, GetRepeaterTab.serializer(), DraftTabRead.serializer(), service, service::read)
    return service
}

private val draftJson = Json { encodeDefaults = true }

private fun <I : Any, O> Server.addDraftReadTool(name: String, description: String, input: KClass<I>,
    serializer: KSerializer<I>, output: KSerializer<O>, service: RepeaterDraftService,
    execute: suspend (I) -> O) {
    val reflected = input.asInputSchema()
    val properties = reflected.properties.orEmpty().mapValues { (key, value) ->
        val options = when (key) {
            "offset" -> mapOf("minimum" to JsonPrimitive(0), "default" to JsonPrimitive(0))
            "count" -> mapOf("minimum" to JsonPrimitive(1), "maximum" to JsonPrimitive(DRAFT_MAX_PAGE),
                "default" to JsonPrimitive(DRAFT_DEFAULT_PAGE))
            "maxResponseLength" -> mapOf("minimum" to JsonPrimitive(0), "maximum" to JsonPrimitive(DRAFT_MAX_PREVIEW),
                "default" to JsonPrimitive(DRAFT_DEFAULT_PREVIEW))
            "includeRequest", "includeResponse" -> mapOf("default" to JsonPrimitive(true))
            else -> emptyMap()
        }
        JsonObject(value.jsonObject + options)
    }
    val schema = ToolSchema(properties = JsonObject(properties), required = reflected.required)
    addTool(name = name, description = description, inputSchema = schema, handler = { request ->
        val arguments = request.params.arguments ?: JsonObject(emptyMap())
        val suppliedTabId = arguments["tabId"] as? JsonPrimitive
        val tabId = suppliedTabId?.takeIf { "tabId" in properties && it.isString &&
            draftTabIdPattern.matches(it.content) }?.content
        fun error(code: String, message: String): CallToolResult = CallToolResult(
            content = listOf(TextContent(draftJson.encodeToString(DraftError(code, message, service.instanceId, tabId)))),
            isError = true
        )
        try {
            // The serialization decoder also accepts some quoted numbers and booleans.
            // Reject values that do not have the JSON type advertised for this argument.
            if (arguments.any { (key, value) ->
                    val primitive = value as? JsonPrimitive ?: return@any true
                    when (properties[key]?.jsonObject?.get("type")?.jsonPrimitive?.content) {
                        "string" -> !primitive.isString
                        "integer" -> primitive.isString || primitive.intOrNull == null
                        "boolean" -> primitive.isString || primitive.booleanOrNull == null
                        else -> true
                    }
                }) return@addTool error("INVALID_ARGUMENT", "Check Repeater tool arguments and types.")
            val args = draftJson.decodeFromJsonElement(serializer, arguments)
            CallToolResult(content = listOf(TextContent(draftJson.encodeToString(output, execute(args)))), isError = false)
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: DraftFailure) {
            error(failure.code, failure.message.orEmpty())
        } catch (_: SerializationException) {
            error("INVALID_ARGUMENT", "Check Repeater tool arguments and types.")
        } catch (_: Exception) {
            error("UNSUPPORTED_LAYOUT", "Cannot safely read the native Repeater state.")
        }
    })
}

/** Recheck a single approval after waiting for another draft operation and again before returning data. */
internal suspend fun <T> withM0RepeaterAccess(config: McpConfig, operations: Mutex, mayMutate: Boolean = false,
    operation: suspend (() -> Boolean) -> T): T {
    val allowed = approveM0RepeaterAccess(config)
    return operations.withLock {
        checkM0RepeaterAccess(allowed)
        val result = operation(allowed)
        if (!allowed()) throw DraftFailure("PERMISSION_DENIED",
            if (mayMutate) "Repeater data access changed; draft mutation may have occurred."
            else "Repeater data access changed.")
        result
    }
}

internal fun checkM0RepeaterAccess(allowed: () -> Boolean) {
    if (!allowed()) throw DraftFailure("PERMISSION_DENIED", "Repeater data access changed.")
}

internal suspend fun approveM0RepeaterAccess(config: McpConfig): () -> Boolean {
    fun policy() = config.requireDataAccessApproval to config.alwaysAllowRepeater
    val before = policy()
    if (!DataAccessSecurity.checkDataAccessPermission(DataAccessType.REPEATER, config))
        throw DraftFailure("PERMISSION_DENIED", "Repeater data access denied by Burp Suite.")
    val approved = policy()
    // Allow Once leaves both flags unchanged. Always Allow may grant persistent access during the prompt.
    val granted = approved == before || before == (true to false) && approved == (true to true)
    return { granted && policy() == approved }
}

@Serializable
internal data class GetRepeaterTabs(val offset: Int? = null, val count: Int? = null)

@Serializable
internal data class GetRepeaterTab(val tabId: String, val includeRequest: Boolean? = null,
    val includeResponse: Boolean? = null, val maxResponseLength: Int? = null)

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
