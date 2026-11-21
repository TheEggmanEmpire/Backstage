package com.eggman.backstage.mcp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class HttpRequest(
    val method: String,
    val path: String,
    val version: String,
    /** Lower-cased header names; repeated headers are joined with ", ". */
    val headers: Map<String, String>,
    val body: ByteArray,
    /** Set when the request is refused from its headers alone; the body is not read. */
    val rejected: HttpResponse? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val bodyText: String get() = String(body, Charsets.UTF_8)
    val keepAlive: Boolean
        get() {
            val c = header("connection")?.lowercase().orEmpty()
            return if (version == "HTTP/1.0") "keep-alive" in c else "close" !in c
        }
}

class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun text(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, text.toByteArray(), "text/plain; charset=utf-8", headers)
        fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, json.toByteArray(), "application/json", headers)
        fun empty(status: Int, headers: Map<String, String> = emptyMap()) = HttpResponse(status, headers = headers)
    }
}

/** Protocol violation with the status to answer before closing the connection. */
class HttpError(val status: Int, message: String) : IOException(message)

/**
 * Minimal, strict HTTP/1.1 request reader: request line, headers, Content-Length or chunked body,
 * Expect: 100-continue, and hard limits against oversized input.
 */
class HttpReader(
    private val input: InputStream,
    private val out: OutputStream? = null,
    private val maxLine: Int = 8 * 1024,
    private val maxHeaders: Int = 100,
    private val maxBody: Int = 4 * 1024 * 1024,
) {
    /**
     * What to do with a request once its headers are known, before any body byte is read: refuse it
     * outright ([reject], answered with the connection closed) or cap the body it may send ([maxBody]).
   */
    class Gate(val reject: HttpResponse? = null, val maxBody: Int? = null)

    /**
     * Returns null on a clean end of stream before a request starts (keep-alive connection closed).
     * [admit] sees the request with an empty body; unauthenticated peers can thus be refused, or
     * limited to a small body, without the server buffering what they send.
   */
    fun read(admit: ((HttpRequest) -> Gate)? = null): HttpRequest? {
        var line = readLine(allowEof = true) ?: return null
        while (line.isEmpty()) line = readLine(allowEof = true) ?: return null // tolerate stray CRLF between requests
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "malformed request line")
        val (method, target, version) = parts
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(allowEof = false) ?: throw HttpError(400, "unexpected end of headers")
            if (h.isEmpty()) break
            if (++count > maxHeaders) throw HttpError(431, "too many headers")
            val idx = h.indexOf(':')
            if (idx <= 0 || h[0] == ' ' || h[0] == '\t') throw HttpError(400, "malformed header")
            val name = h.substring(0, idx).trim().lowercase()
            val value = h.substring(idx + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        val head = HttpRequest(method.uppercase(), target, version, headers, ByteArray(0))
        val gate = admit?.invoke(head)
        gate?.reject?.let { return HttpRequest(head.method, target, version, headers, ByteArray(0), rejected = it) }
        val limit = minOf(maxBody, gate?.maxBody ?: maxBody)
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out?.let { it.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); it.flush() }
        }
        val te = headers["transfer-encoding"]?.lowercase()
        val body = when {
            te != null && te.contains("chunked") -> readChunked(limit)
            te != null -> throw HttpError(501, "unsupported transfer-encoding")
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.split(',')[0].trim().toLongOrNull() ?: throw HttpError(400, "bad content-length")
                if (len < 0) throw HttpError(400, "bad content-length")
                if (len > limit) throw HttpError(413, "body too large")
                readExact(len.toInt())
            }
            else -> ByteArray(0)
        }
        return HttpRequest(method.uppercase(), target, version, headers, body)
    }

    private fun readChunked(maxBody: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(allowEof = false) ?: throw HttpError(400, "truncated chunk")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400, "bad chunk size")
            if (size < 0) throw HttpError(400, "bad chunk size")
            if (size == 0) {
                // trailers until empty line
                while (true) {
                    val t = readLine(allowEof = false) ?: throw HttpError(400, "truncated trailers")
                    if (t.isEmpty()) break
                }
                return buf.toByteArray()
            }
            // Long math: "7fffffff" must not overflow past the limit check.
            if (buf.size().toLong() + size.toLong() > maxBody.toLong()) throw HttpError(413, "body too large")
            buf.write(readExact(size))
            val crlf = readLine(allowEof = false) ?: throw HttpError(400, "truncated chunk")
            if (crlf.isNotEmpty()) throw HttpError(400, "missing chunk terminator")
        }
    }

    private fun readExact(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw HttpError(400, "truncated body")
            off += r
        }
        return b
    }

    private fun readLine(allowEof: Boolean): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) {
                if (sb.size() == 0 && allowEof) return null
                throw HttpError(400, "unexpected end of stream")
            }
            if (c == '\n'.code) {
                val bytes = sb.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.ISO_8859_1)
            }
            sb.write(c)
            if (sb.size() > maxLine) throw HttpError(431, "line too long")
        }
    }
}

object HttpWriter {
    private val reasons = mapOf(
        200 to "OK", 202 to "Accepted", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
        404 to "Not Found", 405 to "Method Not Allowed", 406 to "Not Acceptable", 411 to "Length Required", 413 to "Payload Too Large",
        415 to "Unsupported Media Type", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
        501 to "Not Implemented", 503 to "Service Unavailable",
    )

    fun write(out: OutputStream, r: HttpResponse, keepAlive: Boolean) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reasons[r.status] ?: "Status").append("\r\n")
        r.contentType?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n") }
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }
}

object TokenAuth {
    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    /** Constant-time comparison of the presented bearer token with the expected one. */
    fun check(authorization: String?, expected: String): Boolean {
        if (expected.isEmpty() || authorization == null) return false
        val prefix = "bearer "
        if (authorization.length < prefix.length || !authorization.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val presented = authorization.substring(prefix.length).trim()
        // Compare fixed-length digests so neither content nor length leaks through timing.
        return MessageDigest.isEqual(sha256(presented), sha256(expected))
    }
}
