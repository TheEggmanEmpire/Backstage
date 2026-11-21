package com.eggman.backstage.omp.engine

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

class OmpProtocolException(message: String) : IOException(message)

/** Limits are bytes, including LF. Never use readLine: a peer controls its allocation. */
class OmpJsonl(
    input: InputStream,
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    private val onMalformed: (String) -> Unit = {},
) {
    private val input = input.buffered()
    fun read(): JSONObject? {
        var malformed = 0
        while (true) {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (bytes.size() != 0) throw OmpProtocolException("Truncated OMP JSONL frame")
                    return null
                }
                if (bytes.size() + 1 > maxFrameBytes) throw OmpProtocolException("OMP frame exceeds $maxFrameBytes bytes")
                if (b == 10) break
                bytes.write(b)
            }
            if (bytes.size() == 0) continue
            val frame = try { parse(bytes.toByteArray()) } catch (_: Exception) {
                onMalformed("Malformed OMP JSONL frame ignored")
                if (++malformed >= 8) throw OmpProtocolException("Too many malformed OMP frames")
                continue
            }
            return frame
        }
    }
    companion object {
        const val MAX_FRAME_BYTES = 1_048_576
        const val MAX_LOGICAL_BYTES = 67_108_864
        internal fun parse(bytes: ByteArray): JSONObject {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val tokenizer = JSONTokener(text)
            val value = tokenizer.nextValue()
            if (value !is JSONObject || tokenizer.nextClean() != '\u0000' || value.optString("type").isBlank())
                throw OmpProtocolException("Expected one OMP object")
            return value
        }
    }
}

/** Protocol v2 sends uninterrupted, ordered base64 segments, never nested chunk frames. */
class OmpFrameDecoder(private val maxBytes: Int = OmpJsonl.MAX_LOGICAL_BYTES) {
    private var chunkId: String? = null
    private var index = 0
    private var count = 0
    private var length = 0
    private var bytes = ByteArrayOutputStream()
    val assembling: Boolean get() = chunkId != null
    private fun integer(frame: JSONObject, key: String): Int {
        val value = frame.get(key)
        if (value !is Number || !value.toDouble().isFinite() || value.toDouble() != value.toLong().toDouble() || value.toLong() !in 0..Int.MAX_VALUE.toLong())
            throw OmpProtocolException("Invalid OMP chunk integer")
        return value.toInt()
    }
    fun accept(frame: JSONObject): JSONObject? {
        if (frame.optString("type") != "rpc_chunk") {
            if (assembling) throw OmpProtocolException("Interrupted OMP chunk sequence")
            return frame
        }
        try {
            val id = frame.getString("chunkId")
            val i = integer(frame, "index")
            val n = integer(frame, "count")
            val size = integer(frame, "byteLength")
            if (id.isBlank() || i < 0 || n < 1 || n > maxBytes || size < 1 || size > maxBytes || i >= n)
                throw OmpProtocolException("Invalid OMP chunk bounds")
            if (!assembling) {
                if (i != 0) throw OmpProtocolException("Missing first OMP chunk")
                chunkId = id; count = n; length = size; index = 0
            }
            if (chunkId != id || index != i || count != n || length != size)
                throw OmpProtocolException("Out-of-order or interleaved OMP chunks")
            val data = Base64.getDecoder().decode(frame.getString("data"))
            if (data.isEmpty() || bytes.size().toLong() + data.size > length) throw OmpProtocolException("OMP chunk exceeds declared length")
            bytes.write(data); index++
            if (index != count) return null
            if (bytes.size() != length) throw OmpProtocolException("OMP chunk length mismatch")
            val result = OmpJsonl.parse(bytes.toByteArray())
            if (result.optString("type") == "rpc_chunk") throw OmpProtocolException("Nested OMP chunks")
            chunkId = null; bytes = ByteArrayOutputStream()
            return result
        } catch (e: OmpProtocolException) { throw e }
        catch (_: Exception) { throw OmpProtocolException("Malformed OMP chunk") }
    }
}
