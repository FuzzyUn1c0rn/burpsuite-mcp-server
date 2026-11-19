package net.portswigger.mcp.repeater

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.contextmenu.ContextMenuEvent
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider
import java.awt.Component
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JMenuItem
import javax.swing.SwingUtilities

/** Fixed synthetic draft only. Packaged separately from the normal extension. */
class M0TypedDraftTrialExtension : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("M0 typed HTTP/2 draft trial (test only)")
        val used = AtomicBoolean()
        val registration = api.userInterface().registerContextMenuItemsProvider(object : ContextMenuItemsProvider {
            override fun provideMenuItems(event: ContextMenuEvent): List<Component> {
                if (!SwingUtilities.isEventDispatchThread() || !event.isFromTool(ToolType.REPEATER) || used.get()) return emptyList()
                return listOf(JMenuItem("Create one typed synthetic HTTP/2 draft").apply {
                    addActionListener {
                        if (SwingUtilities.isEventDispatchThread() && used.compareAndSet(false, true)) createDraft(api)
                    }
                })
            }
        })
        api.extension().registerUnloadingHandler {
            used.set(true)
            registration.deregister()
        }
    }

    private fun createDraft(api: MontoyaApi) {
        val marker = "M0-typed-h2-${UUID.randomUUID()}"
        var creationMayHaveOccurred = false
        try {
            val service = HttpService.httpService(HOST, PORT, true)
            val request = HttpRequest.http2Request(service, listOf(
                HttpHeader.httpHeader(":method", "POST"),
                HttpHeader.httpHeader(":scheme", "https"),
                HttpHeader.httpHeader(":authority", "$HOST:$PORT"),
                HttpHeader.httpHeader(":path", PATH),
                HttpHeader.httpHeader("x-m0-variant", "first"),
                HttpHeader.httpHeader("x-m0-variant", "second"),
                HttpHeader.httpHeader("content-length", body.size.toString())
            ), MontoyaByteArray.byteArray(*body))
            val prepared = checkNotNull(checkTypedDraftModel(request))
            api.logging().logToOutput("M0-typed-draft PREPARED marker=$marker fullSha256=${sha256(prepared)} " +
                "bodyBytes=${body.size} bodySha256=${sha256(body)} modelProtocol=HTTP/2 configuredProtocol=unverified")
            val repeater = api.repeater()
            creationMayHaveOccurred = true
            repeater.sendToRepeater(request, marker)
            api.logging().logToOutput("M0-typed-draft CREATE_RETURNED marker=$marker")
        } catch (_: Exception) {
            api.logging().logToOutput("M0-typed-draft UNCONFIRMED creationMayHaveOccurred=$creationMayHaveOccurred")
        }
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 18443
        private const val PATH = "/m0-typed-draft"
        private val body = " café \r\nline\n\t".toByteArray(Charsets.UTF_8)

        internal fun checkTypedDraftModel(request: HttpRequest): ByteArray? {
            val service = request.httpService()
            if (service.host() != HOST || service.port() != PORT || !service.secure() ||
                request.httpVersion() != "HTTP/2" || request.method() != "POST" || request.path() != PATH) return null

            val headers = request.headers()
            if (headers.filter { it.name().equals("x-m0-variant", true) }.map { it.value() } != listOf("first", "second") ||
                headers.filter { it.name().equals("content-length", true) }.map { it.value() } != listOf(body.size.toString())) return null

            val message = request.toByteArray()
            val messageLength = message.length()
            val offset = request.bodyOffset()
            val modelBody = request.body()
            if (messageLength !in 1..1024 || offset !in 1..messageLength ||
                modelBody.length() != body.size || !modelBody.bytes.contentEquals(body)) return null
            val bytes = message.bytes
            return bytes.takeIf { it.size == messageLength && it.copyOfRange(offset, it.size).contentEquals(body) }
        }

        private fun sha256(bytes: ByteArray) =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
