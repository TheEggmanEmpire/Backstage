package com.eggman.backstage.omp.engine

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class OmpGenerationRegressionTest {
    private fun send(socket: Socket, frame: JSONObject) {
        socket.getOutputStream().apply { write((frame.toString() + "\n").toByteArray()); flush() }
    }

    @Test fun queuedHostCallRetainsItsOriginalGenerationAndIsRejectedAfterRollover() = runBlocking {
        ServerSocket(0).use { listener -> Socket("127.0.0.1", listener.localPort).use { peer ->
            peer.soTimeout = 2000
            OmpRpcClient(listener.accept()).use { client ->
                val first = client.beginTask("first")
                send(peer, JSONObject("""{"type":"host_tool_call","id":"old","toolCallId":"old","toolName":"backstage_probe","arguments":{}}"""))
                // An awaited response is a deterministic reader barrier: the host call is received.
                val barrier = async { client.command("get_state") }
                withContext(Dispatchers.IO) {
                    val request = OmpJsonl(peer.getInputStream()).read()!!
                    send(peer, JSONObject().put("type", "response").put("id", request.getString("id")).put("success", true))
                }
                withTimeout(2000) { barrier.await() }
                client.endTask(first)
                val second = client.beginTask("second")
                val event = withTimeout(2000) { client.nextEvent() }
                var executed = false
                val failure = runCatching { client.requireHostTask(event, second); executed = true }.exceptionOrNull()
                assertFalse(executed)
                assertTrue(failure is OmpProtocolException)
                val result = withContext(Dispatchers.IO) { OmpJsonl(peer.getInputStream()).read()!! }
                assertEquals("host_tool_result", result.getString("type"))
                assertEquals("old", result.getString("id"))
                assertTrue(result.getBoolean("isError"))
                assertFalse(client.alive)
                assertEquals(-1, withContext(Dispatchers.IO) { peer.getInputStream().read() })
            }
        } }
    }

    @Test fun eventByteBudgetFailsCleanlyBeforeTheFrameCountLimit() = runBlocking {
        ServerSocket(0).use { listener -> Socket("127.0.0.1", listener.localPort).use { peer ->
            peer.soTimeout = 2000
            OmpRpcClient(listener.accept()).use { client ->
                val command = async { runCatching { client.command("steer") } }
                withContext(Dispatchers.IO) {
                    OmpJsonl(peer.getInputStream()).read()!!
                    val frame = JSONObject().put("type", "message_update").put("data", "x".repeat(600_000))
                    runCatching { repeat(16) { send(peer, frame) } }
                }
                val failure = withTimeout(2000) { command.await() }.exceptionOrNull()
                assertTrue(failure is OmpProtocolException)
                assertTrue(failure!!.message!!.contains("8 MiB"))
                assertFalse(client.alive)
            }
        } }
    }
}
