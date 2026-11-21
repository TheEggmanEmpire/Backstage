package com.eggman.backstage.omp.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class OmpBridgeTest {
    @Test fun listenerRejectsBadClaimThenAcceptsOnePeerAndClosesItsPort() = runBlocking {
        OmpRpcBridgeServer().use { server ->
            val port = server.port
            val accepted = async(Dispatchers.IO) { server.awaitConnection() }
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { bad ->
                    bad.soTimeout = 2000
                    bad.getOutputStream().write("{\"type\":\"claim\",\"code\":\"wrong\"}\n".toByteArray())
                    assertEquals(-1, bad.getInputStream().read())
                }
                Socket("127.0.0.1", port).use { good ->
                    good.soTimeout = 2000
                    good.getOutputStream().write("{\"type\":\"claim\",\"code\":\"${server.claim.code}\"}\n".toByteArray())
                    assertEquals("claimed", OmpJsonl(good.getInputStream()).read()!!.getString("type"))
                    accepted.await().use { socket -> assertTrue(socket.localAddress.isLoopbackAddress) }
                    assertThrows(java.net.ConnectException::class.java) { Socket("127.0.0.1", port).close() }
                    assertFalse(server.claim.consume(server.claim.code))
                }
            }
        }
    }
    @Test fun expiredListenerDoesNotAdmitAConnection() = runBlocking {
        var now = 0L
        val claim = OmpBridgeClaim { now }
        now = OmpBridgeClaim.TTL_MS
        OmpRpcBridgeServer(claim).use { server ->
            var failed = false
            try { server.awaitConnection() } catch (_: OmpProtocolException) { failed = true }
            assertTrue(failed)
        }
    }
}
