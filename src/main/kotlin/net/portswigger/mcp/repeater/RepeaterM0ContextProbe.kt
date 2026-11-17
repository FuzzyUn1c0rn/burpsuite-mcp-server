package net.portswigger.mcp.repeater

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse.SelectionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.awt.Component
import java.security.MessageDigest
import javax.swing.SwingUtilities

/** Identity validator is transient and released when the observation closes. */
internal class M0ContextTarget(
    val tabId: String, val view: String, val ownsSource: (Component) -> Boolean
)

/** One callback-scoped, read-only public-model experiment. Never adds a menu item. */
internal class RepeaterM0ContextProbe(
    target: M0ContextTarget, private val startedAtMillis: Long = System.currentTimeMillis()
) : ContextMenuItemsProvider {
    @Volatile private var target: M0ContextTarget? = target
    val result = CompletableDeferred<M0ContextModelObservation>()

    override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
        val current = target ?: return emptyList()
        if (result.isCompleted) return emptyList()
        fun reject(reason: String) {
            if (target === current) result.complete(M0ContextModelObservation("rejected", reason, current.tabId, current.view))
        }
        try {
            if (!SwingUtilities.isEventDispatchThread()) {
                reject("UNSUPPORTED_CALLBACK_THREAD")
                return emptyList()
            }
            if (!event.isFromTool(ToolType.REPEATER)) return emptyList()
            val input = event.inputEvent()
            if (input.`when` <= startedAtMillis) {
                reject("EVENT_NOT_NEWER_THAN_OBSERVATION")
                return emptyList()
            }
            val source = input.component
            if (!current.ownsSource(source)) {
                reject("OWNERSHIP_UNCONFIRMED")
                return emptyList()
            }
            val editor = event.messageEditorRequestResponse().orElse(null)
            if (editor == null) {
                reject("EDITOR_HANDLE_UNAVAILABLE")
                return emptyList()
            }
            if (editor.selectionContext() != SelectionContext.REQUEST) {
                reject("NOT_REQUEST_EDITOR_CONTEXT")
                return emptyList()
            }
            // Do not retain event/editor/requestResponse/request or decode model bytes as text.
            val request = editor.requestResponse().request()
            val message = request.toByteArray()
            val body = request.body()
            if (message.length() !in 0..262_144 || body.length() !in 0..262_144) {
                reject("MODEL_REQUEST_TOO_LARGE")
                return emptyList()
            }
            val messageBytes = message.bytes
            val bodyBytes = body.bytes
            val bodyOffset = request.bodyOffset()
            if (messageBytes.size > 262_144 || bodyBytes.size > 262_144 ||
                bodyOffset !in 0..messageBytes.size || !messageBytes.copyOfRange(bodyOffset, messageBytes.size).contentEquals(bodyBytes)) {
                reject("MODEL_BYTES_INCONSISTENT")
                return emptyList()
            }
            val service = request.httpService()
            val host = service.host()
            if (host.length !in 1..253 || service.port() !in 1..65535) {
                reject("MODEL_DESTINATION_UNSUPPORTED")
                return emptyList()
            }
            val version = request.httpVersion().takeIf {
                it in setOf("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/2.0", "1.0", "1.1", "2", "2.0")
            } ?: "unknown"
            val observation = M0ContextModelObservation("observed", "CALLBACK_MODEL_METADATA_ONLY", current.tabId,
                current.view, messageBytes.size, sha256(messageBytes), bodyBytes.size, sha256(bodyBytes),
                host, service.port(), service.secure(), version)
            if (!current.ownsSource(source)) reject("IDENTITY_CHANGED_DURING_MODEL_READ")
            else if (target === current) result.complete(observation)
        } catch (_: Exception) {
            reject("MODEL_METADATA_UNAVAILABLE")
        }
        return emptyList()
    }

    fun close() {
        target = null
        if (!result.isCompleted) result.cancel()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

internal suspend fun observeM0ContextModel(api: MontoyaApi, native: NativeRepeater, tabId: String): M0ContextModelObservation {
    val target = native.modelContextTarget(tabId)
    val probe = RepeaterM0ContextProbe(target)
    val registration = try {
        api.userInterface().registerContextMenuItemsProvider(probe)
    } catch (_: Exception) {
        probe.close()
        error("M0_CONTEXT_REGISTRATION_UNAVAILABLE")
    }
    return try {
        withTimeoutOrNull(30_000) { probe.result.await() }
            ?: M0ContextModelObservation("not_observed", "NO_CONTEXT_EVENT_WITHIN_30000MS", tabId, target.view)
    } finally {
        probe.close()
        try { registration.deregister() } catch (_: Exception) { error("M0_CONTEXT_DEREGISTRATION_UNCONFIRMED") }
    }
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
    val editorViewSwitchAttempted: Boolean = false
)
