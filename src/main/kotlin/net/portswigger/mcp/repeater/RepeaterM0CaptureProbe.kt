package net.portswigger.mcp.repeater

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

/** Opt-in, bounded M0 metadata only. No callback/message/document references are retained. */
internal class RepeaterM0CaptureProbe {
    private val started = System.nanoTime()
    private var requestCallbacks = 0
    private var responseCallbacks = 0
    private var failures = 0
    private val requests = mutableListOf<M0RequestComparison>()
    private val responses = mutableListOf<M0ResponseObservation>()

    @Synchronized
    fun request(editor: String, hook: String, messageId: Int, sameService: Boolean,
                hookBody: ByteArray? = null, montoyaHttpVersion: String? = null) {
        if (requestCallbacks < Int.MAX_VALUE) requestCallbacks++
        if (!sameService || requests.size >= 8) return
        val expected = parts(editor)
        val observed = parts(hook)
        requests += M0RequestComparison(
            messageId, canonicalRequest(editor) == canonicalRequest(hook),
            expected.first == observed.first, expected.second == observed.second,
            expected.third == observed.third,
            expected.second.filterNot(::isContentLength) == observed.second.filterNot(::isContentLength),
            expected.second.size, observed.second.size,
            contentLengths(expected.second), contentLengths(observed.second),
            expected.third.toByteArray(Charsets.UTF_8).size, observed.third.toByteArray(Charsets.UTF_8).size,
            sha256(expected.third), sha256(observed.third),
            httpVersion(expected.first), httpVersion(observed.first),
            expected.third.length, observed.third.length, hookBody?.size, hookBody?.let(::sha256),
            elapsedMs(),
            (0 until minOf(maxOf(expected.second.size, observed.second.size), 8)).map { position ->
                val editorLine = expected.second.getOrNull(position)
                val hookLine = observed.second.getOrNull(position)
                M0HeaderComparison(position, headerCategory(editorLine), headerCategory(hookLine),
                    editorLine != null && hookLine != null && editorLine.substringBefore(':').equals(hookLine.substringBefore(':'), true),
                    editorLine != null && hookLine != null && editorLine.substringAfter(':', "") == hookLine.substringAfter(':', ""),
                    editorLine == hookLine)
            }, maxOf(expected.second.size, observed.second.size) > 8,
            contentTypeCharsets(expected.second), contentTypeCharsets(observed.second),
            montoyaHttpVersion?.takeIf { it in setOf("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/2.0", "1.0", "1.1", "2", "2.0") } ?: "unknown"
        )
    }

    @Synchronized
    fun response(messageId: Int) {
        if (responseCallbacks < Int.MAX_VALUE) responseCallbacks++
        if (responses.size < 8) responses += M0ResponseObservation(messageId, elapsedMs())
    }

    @Synchronized
    fun failure() { if (failures < Int.MAX_VALUE) failures++ }

    @Synchronized
    fun snapshot() = M0CaptureDiagnostics(
        "Montoya HttpHandler request/response callbacks; not final wire capture or tab attribution",
        "editor_text", "HttpRequestToBeSent.toString()", "HttpRequestToBeSent.body().getBytes(); not TLS/frame bytes",
        requestCallbacks, responseCallbacks, requests.toList(), responses.toList(), failures
    )

    private fun parts(text: String): Triple<String, List<String>, String> {
        val canonical = canonicalRequest(text)
        val separator = canonical.indexOf("\n\n")
        val head = if (separator < 0) canonical else canonical.substring(0, separator)
        val lines = head.split('\n')
        return Triple(lines.first(), lines.drop(1), if (separator < 0) "" else canonical.substring(separator + 2))
    }

    private fun isContentLength(line: String) = line.substringBefore(':').equals("content-length", ignoreCase = true)

    private fun contentLengths(headers: List<String>): List<String> = headers.filter(::isContentLength).take(4).map {
        val value = it.substringAfter(':', "").trim()
        if (value.length in 1..10 && value.all { char -> char in '0'..'9' }) value else "non_numeric_or_oversized"
    }

    private fun contentTypeCharsets(headers: List<String>): List<String> = headers.filter {
        it.substringBefore(':').equals("content-type", ignoreCase = true)
    }.take(4).map { line ->
        val charset = Regex("(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE)
            .find(line.substringAfter(':', ""))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        when (charset) {
            null -> "not_declared"
            "utf-8", "utf8" -> "utf-8"
            "iso-8859-1", "latin1", "latin-1" -> "iso-8859-1"
            "us-ascii", "ascii" -> "us-ascii"
            else -> "other"
        }
    }

    private fun httpVersion(line: String) = line.substringAfterLast(' ').takeIf {
        it in setOf("HTTP/1.0", "HTTP/1.1", "HTTP/2", "HTTP/2.0")
    } ?: "unknown"

    private fun headerCategory(line: String?): String? = line?.substringBefore(':')?.lowercase(Locale.ROOT)?.let {
        if (it in setOf("host", "content-length", "content-type", "connection", "accept", "accept-encoding",
                "authorization", "cookie", "user-agent")) it else "other"
    }

    private fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
    private fun sha256(text: String) = sha256(text.toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

@Serializable
internal data class M0CaptureDiagnostics(
    val captureStage: String, val expectedRepresentation: String, val observedRepresentation: String,
    val montoyaBodyRepresentation: String,
    val repeaterRequestCallbacks: Int, val repeaterResponseCallbacks: Int,
    val sameServiceRequestSamples: List<M0RequestComparison>, val responseSamples: List<M0ResponseObservation>,
    val diagnosticFailures: Int
)

@Serializable
internal data class M0RequestComparison(
    val messageId: Int, val canonicalRequestEqual: Boolean,
    val requestLineEqual: Boolean, val headerBlockEqual: Boolean, val bodyEqual: Boolean,
    val headersEqualExceptContentLength: Boolean,
    val editorHeaderCount: Int, val hookHeaderCount: Int,
    val editorContentLengthValues: List<String>, val hookContentLengthValues: List<String>,
    val editorBodyUtf8Length: Int, val hookBodyUtf8Length: Int,
    val editorBodySha256: String, val hookBodySha256: String,
    val editorRequestLineHttpVersion: String, val hookRequestLineHttpVersion: String,
    val editorBodyUtf16Length: Int, val hookBodyUtf16Length: Int,
    val montoyaBodyByteLength: Int?, val montoyaBodyByteSha256: String?,
    val elapsedMsSinceObserverCreation: Long,
    val headerSamples: List<M0HeaderComparison>, val headerSamplesTruncated: Boolean,
    val editorContentTypeCharsets: List<String>, val hookContentTypeCharsets: List<String>,
    val montoyaMessageHttpVersion: String
)

@Serializable
internal data class M0HeaderComparison(
    val position: Int, val editorHeaderCategory: String?, val hookHeaderCategory: String?,
    val nameEqualIgnoringCase: Boolean, val valueEqual: Boolean, val lineEqual: Boolean
)

@Serializable
internal data class M0ResponseObservation(val messageId: Int, val elapsedMsSinceObserverCreation: Long)
