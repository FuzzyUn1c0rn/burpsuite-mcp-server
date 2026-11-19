package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.Version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.McpConfig
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.swing.text.Document

internal const val DRAFT_REQUEST_LIMIT = 262_144
internal const val DRAFT_DEFAULT_PAGE = 50
internal const val DRAFT_MAX_PAGE = 200
internal const val DRAFT_DEFAULT_PREVIEW = 4_000
internal const val DRAFT_MAX_PREVIEW = 20_000
// Montoya's buildNumber() for 2026.11.2-55152, including the release components.
private const val SUPPORTED_REPEATER_MONTOYA_BUILD = 20261102000055152L

internal class DraftFailure(val code: String, message: String) : IllegalStateException(message)

@Serializable
internal data class DraftError(val code: String, val message: String, val serverInstanceId: String?, val tabId: String? = null)

@Serializable
internal data class DraftCapabilities(
    val burpVersion: String?, val burpEdition: String?, val adapterSupported: Boolean,
    val editorModes: List<String> = if (adapterSupported) listOf("Hex") else emptyList(),
    val modelProtocols: List<String> = if (adapterSupported) listOf("HTTP/1.1", "HTTP/2") else emptyList(),
    val unsupportedReason: String? = if (adapterSupported) null else "UNSUPPORTED_BURP",
    val defaultPageSize: Int = DRAFT_DEFAULT_PAGE, val maxPageSize: Int = DRAFT_MAX_PAGE,
    val maxRequestBytes: Int = DRAFT_REQUEST_LIMIT, val defaultResponsePreview: Int = DRAFT_DEFAULT_PREVIEW,
    val maxResponsePreview: Int = DRAFT_MAX_PREVIEW, val creationConfirmationMillis: Int = 5_000
)

@Serializable
internal data class DraftTabList(
    val serverInstanceId: String, val tabs: List<RepeaterTab>, val nextOffset: Int?,
    val totalCount: Int, val capabilities: DraftCapabilities, val schemaVersion: Int = 1
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DraftDisplayedResponse(
    val availability: String, val truncated: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val preview: String? = null,
    val provenance: String = "displayed_ui_snapshot"
)

internal data class DraftUiState(
    val tab: RepeaterTab, val hostname: String, val port: Int, val tls: Boolean,
    val busyState: String, val response: DraftDisplayedResponse
)

internal data class ObservedDraft(val ui: DraftUiState, val bytes: ByteArray, val protocol: String, val bodyLength: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DraftTabRead(
    val serverInstanceId: String, val tabId: String, val name: String, val kind: String, val groupPath: List<String>?,
    val targetHostname: String, val targetPort: Int, val usesHttps: Boolean, val modelProtocol: String,
    val fingerprint: String, val requestIncluded: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val request: String? = null,
    val busyState: String, val displayedResponse: DraftDisplayedResponse,
    val requestRepresentation: String = "editor_text", val configuredProtocol: String = "unverified",
    val negotiatedProtocol: String? = null, val negotiatedProtocolReason: String = "No negotiation observed for this draft.",
    val schemaVersion: Int = 1
)

internal class RepeaterDraftService(
    private val config: McpConfig,
    private val projectId: () -> String,
    private val capabilities: () -> DraftCapabilities,
    private val newNative: () -> NativeRepeater,
    private val readModel: suspend (NativeRepeater, String, Int?, () -> Boolean) -> ObservedDraft
) : AutoCloseable {
    private data class Instance(val project: String, val id: String, val native: NativeRepeater)
    private val operations = Mutex()
    private val lifetime = Job()
    @Volatile private var instance: Instance? = null
    val instanceId: String? get() = instance?.id

    suspend fun list(args: GetRepeaterTabs): DraftTabList {
        val offset = args.offset ?: 0
        val count = args.count ?: DRAFT_DEFAULT_PAGE
        if (offset < 0 || count !in 1..DRAFT_MAX_PAGE)
            throw DraftFailure("INVALID_ARGUMENT", "Use a nonnegative offset and a count from 1 to 200.")
        return execute { current, permission ->
            val detected = capabilities()
            if (!detected.adapterSupported) return@execute DraftTabList(current.id, emptyList(), null, 0, detected)
            val entries = try { current.native.tabs(permission) }
            catch (denied: DraftFailure) { throw denied }
            catch (canceled: CancellationException) { throw canceled }
            catch (_: Exception) {
                throw DraftFailure("UNSUPPORTED_LAYOUT", "Cannot identify the native Repeater pane.")
            }
            val page = entries.drop(offset).take(count).map { it.copy(tabId = "${current.id}:${it.tabId}") }
            val next = (offset.toLong() + page.size).takeIf { it < entries.size }?.toInt()
            DraftTabList(current.id, page, next, entries.size, detected)
        }
    }

    suspend fun read(args: GetRepeaterTab): DraftTabRead {
        val limit = args.maxResponseLength ?: DRAFT_DEFAULT_PREVIEW
        if (limit !in 0..DRAFT_MAX_PREVIEW) throw DraftFailure("INVALID_ARGUMENT", "Use a preview length from 0 to 20000.")
        val tabId = args.tabId
        if (!draftTabIdPattern.matches(tabId)) throw DraftFailure("INVALID_ARGUMENT", "Use a tabId returned by get_repeater_tabs.")
        val includeRequest = args.includeRequest ?: true
        val includeResponse = args.includeResponse ?: true
        return execute { current, permission ->
            val rawId = rawTabId(current, tabId)
            if (!capabilities().adapterSupported) throw DraftFailure("UNSUPPORTED_BURP", "This Burp environment is outside the verified draft adapter profile.")
            val observed = readModel(current.native, rawId, limit.takeIf { includeResponse }, permission)
            val ui = observed.ui
            if (ui.tab.tabId != rawId) throw DraftFailure("TAB_NOT_FOUND", "Native Repeater tab identity could not be confirmed.")
            if (observed.bytes.size > DRAFT_REQUEST_LIMIT) throw DraftFailure("REQUEST_TOO_LARGE", "The draft exceeds 256 KiB.")
            if (observed.protocol !in setOf("HTTP/1.1", "HTTP/2") || observed.protocol == "HTTP/2" && !ui.tls)
                throw DraftFailure("UNSUPPORTED_MODE", "The native model is outside the supported HTTP draft modes.")
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(observed.bytes)).toString() }
                catch (_: CharacterCodingException) {
                    throw DraftFailure("UNSUPPORTED_MODE", "The native request is not valid UTF-8 text.")
                }
            val boundary = draftHeaderSeparator.find(text)
            if (boundary == null ||
                text.substring(boundary.range.last + 1).toByteArray(Charsets.UTF_8).size != observed.bodyLength ||
                !draftRequestLine.matches(text.lineSequence().first()) ||
                text.any { it.isISOControl() && it != '\r' && it != '\n' && it != '\t' }) {
                throw DraftFailure("UNSUPPORTED_MODE", "The native request is outside the supported text draft format.")
            }
            DraftTabRead(current.id, tabId, ui.tab.name, ui.tab.kind, ui.tab.groupPath,
                ui.hostname, ui.port, ui.tls, observed.protocol,
                draftModelFingerprint(ui.hostname, ui.port, ui.tls, observed.protocol, text),
                includeRequest, text.takeIf { includeRequest }, ui.busyState, ui.response)
        }
    }

    suspend fun <T> investigate(tabId: String, mayMutate: Boolean,
        operation: suspend (NativeRepeater, String, () -> Boolean) -> T): T =
        execute(mayMutate) { current, permission -> operation(current.native, rawTabId(current, tabId), permission) }

    private fun rawTabId(current: Instance, tabId: String): String {
        if (!tabId.startsWith("${current.id}:"))
            throw DraftFailure("INSTANCE_MISMATCH", "The tab ID belongs to another Repeater service instance. List tabs again.")
        return tabId.substringAfter(':')
    }

    private suspend fun <T> execute(mayMutate: Boolean = false,
        operation: suspend (Instance, () -> Boolean) -> T): T = coroutineScope {
        val closeHandler = lifetime.invokeOnCompletion { cancel("Repeater service closed") }
        try {
            val project = projectId()
            fun checkProject() {
                if (projectId() != project)
                    throw DraftFailure("INSTANCE_MISMATCH", "The Burp project changed. List tabs again.")
            }
            withM0RepeaterAccess(config, operations, mayMutate) { permission ->
                checkProject()
                val current = instance?.takeIf { it.project == project }
                    ?: Instance(project, UUID.randomUUID().toString(), newNative()).also { instance = it }
                val result = try {
                    operation(current) { permission() && lifetime.isActive && projectId() == project }
                } catch (failure: DraftFailure) {
                    checkProject()
                    throw failure
                }
                checkProject()
                result
            }
        } finally {
            closeHandler.dispose()
        }
    }

    override fun close() = lifetime.cancel()
}

private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
internal val draftTabIdPattern = Regex("$UUID_PATTERN:$UUID_PATTERN")
private val draftRequestLine = Regex("[^\\s]+ [^\\r\\n]+ HTTP/(?:1\\.1|2(?:\\.0)?)")
private val draftHeaderSeparator = Regex("\\r?\\n\\r?\\n")

internal fun draftModelFingerprint(host: String, port: Int, tls: Boolean, protocol: String, text: String): String {
    val scope = "${host.lowercase()}\n$port\n$tls\n$protocol\n"
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest((scope + canonicalRequest(text)).toByteArray(Charsets.UTF_8)))
}

internal fun displayedDraftResponse(document: Document?, limit: Int?): DraftDisplayedResponse {
    if (document == null) return DraftDisplayedResponse("unavailable")
    val length = document.length
    if (length == 0) return DraftDisplayedResponse("empty_or_not_rendered")
    if (limit == null || limit == 0) return DraftDisplayedResponse("available")
    val sample = document.getText(0, minOf(length, limit * 2 + 1))
    val count = sample.codePointCount(0, sample.length)
    val end = sample.offsetByCodePoints(0, minOf(count, limit))
    return DraftDisplayedResponse("available", end < length, sample.substring(0, end))
}

internal fun newRepeaterDraftService(api: MontoyaApi, config: McpConfig): RepeaterDraftService =
    RepeaterDraftService(config, projectId = { api.project().id() }, capabilities = {
        draftCapabilities(api.burpSuite().version(), System.getProperty("os.name"))
    }, newNative = { NativeRepeater { api.userInterface().swingUtils().suiteFrame() } },
        readModel = { native, id, limit, allowed -> readOwnedDraft(api, native, id, limit, allowed) })

internal fun draftCapabilities(version: Version, osName: String): DraftCapabilities {
    val edition = version.edition()
    return DraftCapabilities(version.toString(), edition.name, osName.startsWith("Windows") &&
        edition == BurpSuiteEdition.COMMUNITY_EDITION && version.buildNumber() == SUPPORTED_REPEATER_MONTOYA_BUILD)
}

private suspend fun readOwnedDraft(api: MontoyaApi, native: NativeRepeater, id: String, limit: Int?,
    allowed: () -> Boolean): ObservedDraft {
    val target = try { native.modelContextTarget(id, allowed, activate = true) }
    catch (failure: DraftFailure) { throw failure }
    catch (canceled: CancellationException) { throw canceled }
    catch (_: Exception) { throw DraftFailure("UNSUPPORTED_LAYOUT", "Cannot identify an owned Repeater request and response view.") }
    if (target.view != "Hex" || target.automaticSource == null)
        throw DraftFailure("UNSUPPORTED_MODE", "Select Hex in the identified Repeater tab to read the native model.")
    val sampleUi = target.readDraftUi ?: throw DraftFailure("UNSUPPORTED_LAYOUT", "Cannot identify the displayed response.")
    suspend fun sample(): DraftUiState = withContext(Dispatchers.Swing) { sampleUi(limit) }
    var ui = sample()
    if (limit != null && limit > 0) {
        repeat(8) {
            if (ui.response.availability == "empty_or_not_rendered") {
                delay(25)
                ui = sample()
            }
        }
    }
    var request: ByteArray? = null
    val model = observeM0ContextOnTarget(api, target, permissionStillValid = allowed, automaticInvocation = true,
        deadlineMillis = 2_000, receiveRequestBytes = { request = it })
    checkM0RepeaterAccess(allowed)
    if (model.reason == "MODEL_REQUEST_TOO_LARGE") throw DraftFailure("REQUEST_TOO_LARGE", "The draft exceeds 256 KiB.")
    if (model.status != "observed" || !model.providerCleanupConfirmed || !model.callbackExited)
        throw DraftFailure("UNVERIFIED_SETTINGS", "Cannot read a confirmed native request model for this draft.")
    val finalUi = sample()
    if (!finalUi.hostname.equals(model.targetHostname, true) || finalUi.port != model.targetPort ||
        finalUi.tls != model.usesHttps || !finalUi.hostname.equals(ui.hostname, true) ||
        finalUi.port != ui.port || finalUi.tls != ui.tls)
        throw DraftFailure("UNVERIFIED_SETTINGS", "The native model and visible destination cannot be reconciled.")
    val bytes = request ?: throw DraftFailure("UNVERIFIED_SETTINGS", "The native model was unavailable.")
    val bodyLength = model.modelBodyByteLength ?: throw DraftFailure("UNVERIFIED_SETTINGS", "The native model body was unavailable.")
    return ObservedDraft(finalUi, bytes, model.montoyaMessageHttpVersion.orEmpty(), bodyLength)
}
