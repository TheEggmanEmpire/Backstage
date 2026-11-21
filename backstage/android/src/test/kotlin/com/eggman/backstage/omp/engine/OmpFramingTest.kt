package com.eggman.backstage.omp.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class OmpFramingTest {
    @Test fun skipsMalformedLinesButKeepsTheNextFrame() {
        var malformed = 0
        val frames = OmpJsonl(ByteArrayInputStream("garbage\n[]\n\n{\"type\":\"ready\"}\r\n".toByteArray()), maxFrameBytes = 128, onMalformed = { malformed++ })
        assertEquals("ready", frames.read()!!.getString("type"))
        assertEquals(2, malformed)
        assertNull(frames.read())
    }
    @Test fun oversizedAndTruncatedLinesFailClosed() {
        for (input in listOf("x".repeat(32) + "\n", "{\"type\":\"ready\"}")) {
            assertThrows(OmpProtocolException::class.java) { OmpJsonl(ByteArrayInputStream(input.toByteArray()), maxFrameBytes = 32).read() }
        }
    }
    @Test fun strictUtf8AndTrailingGarbageAreNotAccepted() {
        var malformed = 0
        val bytes = byteArrayOf(0xc3.toByte(), 0x28, 10) + "{\"type\":\"bad\"} trailing\n{\"type\":\"ok\"}\n".toByteArray()
        assertEquals("ok", OmpJsonl(ByteArrayInputStream(bytes), onMalformed = { malformed++ }).read()!!.getString("type"))
        assertEquals(2, malformed)
    }
    @Test fun v2ReassemblesAndRejectsBrokenSequences() {
        val data = "{\"type\":\"message_end\",\"text\":\"日本語\"}".toByteArray()
        fun chunk(i: Int, bytes: ByteArray) = JSONObject().put("type", "rpc_chunk").put("chunkId", "a").put("index", i)
            .put("count", 2).put("byteLength", data.size).put("data", Base64.getEncoder().encodeToString(bytes))
        val a = chunk(0, data.copyOfRange(0, 10)); val b = chunk(1, data.copyOfRange(10, data.size))
        assertThrows(OmpProtocolException::class.java) { OmpFrameDecoder(128).accept(JSONObject(a.toString()).put("index", 0.5)) }
        val decoder = OmpFrameDecoder(128)
        assertNull(decoder.accept(a))
        assertEquals("日本語", decoder.accept(b)!!.getString("text"))
        assertThrows(OmpProtocolException::class.java) { OmpFrameDecoder(128).accept(b) }
        val interrupted = OmpFrameDecoder(128)
        interrupted.accept(a)
        assertThrows(OmpProtocolException::class.java) { interrupted.accept(JSONObject().put("type", "ready")) }
        assertThrows(OmpProtocolException::class.java) { OmpFrameDecoder(10).accept(a) }
    }
    @Test fun claimsExpireAndCanOnlyBeUsedOnce() {
        var now = 0L
        val claim = OmpBridgeClaim { now }
        assertFalse(claim.consume("wrong"))
        assertTrue(claim.consume(claim.code))
        assertFalse(claim.consume(claim.code))
        val expired = OmpBridgeClaim { now }
        now = OmpBridgeClaim.TTL_MS
        assertFalse(expired.consume(expired.code))
    }
}
