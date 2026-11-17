package net.portswigger.mcp.repeater

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Probe-only immutable byte input; never a final editor_text encoding contract. */
internal class M0ByteWritePlan(
    val expectedHash: String, val expectedHost: String, val expectedPort: Int,
    val expectedTls: Boolean, val expectedVersion: String, replacement: ByteArray
) {
    private val bytes = replacement.copyOf()
    val replacementLength get() = bytes.size
    val replacementHash = m0ByteHash(bytes)
    fun replacementCopy() = bytes.copyOf()

    fun matches(bytes: ByteArray, host: String, port: Int, tls: Boolean, version: String) =
        m0ByteHash(bytes).equals(expectedHash, ignoreCase = true) &&
            host.equals(expectedHost, ignoreCase = true) && port == expectedPort && tls == expectedTls && version == expectedVersion

    companion object {
        fun parse(hash: String, host: String, port: Int, tls: Boolean, version: String, encoded: String): M0ByteWritePlan {
            require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "M0_INVALID_EXPECTED_MODEL_HASH" }
            require(host.length in 1..253 && host.none { it.isWhitespace() || it.isISOControl() }) { "M0_INVALID_EXPECTED_SERVICE" }
            require(port in 1..65535 && version in setOf("HTTP/1.1", "HTTP/2")) { "M0_UNSUPPORTED_EXPECTED_PROTOCOL_OR_PORT" }
            require(encoded.length <= 349_528) { "M0_REQUEST_TOO_LARGE" }
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { error("M0_INVALID_BASE64") }
            require(bytes.size <= 262_144) { "M0_REQUEST_TOO_LARGE" }
            require(Base64.getEncoder().encodeToString(bytes) == encoded) { "M0_NONCANONICAL_BASE64" }
            val separator = (0..bytes.size - 4).firstOrNull { offset ->
                bytes[offset] == 13.toByte() && bytes[offset + 1] == 10.toByte() &&
                    bytes[offset + 2] == 13.toByte() && bytes[offset + 3] == 10.toByte()
            } ?: error("M0_CRLF_REQUEST_FRAMING_REQUIRED")
            val headBytes = bytes.copyOfRange(0, separator)
            require(headBytes.all { it.toInt() in 32..126 || it.toInt() in setOf(9, 10, 13) }) { "M0_ASCII_HEADERS_REQUIRED" }
            val lines = headBytes.toString(Charsets.US_ASCII).split("\r\n")
            require(lines.all { '\r' !in it && '\n' !in it }) { "M0_CRLF_REQUEST_FRAMING_REQUIRED" }
            val requestLine = lines.first().split(' ')
            val token = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
            require(requestLine.size == 3 && token.matches(requestLine[0]) &&
                (requestLine[1].startsWith('/') || requestLine[1] == "*") &&
                requestLine[1].all { it.code in 33..126 } && requestLine[2] == version) { "M0_UNSUPPORTED_REQUEST_LINE_OR_PROTOCOL_CHANGE" }
            val lengths = mutableListOf<Int>()
            for (line in lines.drop(1)) {
                require(':' in line && token.matches(line.substringBefore(':'))) { "M0_UNSUPPORTED_HEADER_FORMAT" }
                val name = line.substringBefore(':')
                require(!name.equals("transfer-encoding", true)) { "M0_TRANSFER_ENCODING_UNSUPPORTED" }
                if (name.equals("content-length", true)) {
                    val value = line.substringAfter(':').trim()
                    require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "M0_INVALID_CONTENT_LENGTH" }
                    lengths += value.toIntOrNull() ?: error("M0_INVALID_CONTENT_LENGTH")
                }
            }
            val body = bytes.copyOfRange(separator + 4, bytes.size)
            require(lengths.size <= 1 && lengths.all { it == body.size }) { "M0_CONFLICTING_CONTENT_LENGTH" }
            require(body.none { it.toInt() in 0..31 && it.toInt() !in setOf(9, 10, 13) || it == 127.toByte() }) { "M0_BINARY_BODY_UNSUPPORTED" }
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body))
            } catch (_: Exception) { error("M0_UTF8_TEXT_BODY_REQUIRED") }
            return M0ByteWritePlan(hash, host, port, tls, version, bytes)
        }
    }
}

internal fun m0ByteHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal fun constructM0ByteRequest(service: HttpService, bytes: ByteArray): HttpRequest =
    HttpRequest.httpRequest(service, burp.api.montoya.core.ByteArray.byteArray(*bytes))
