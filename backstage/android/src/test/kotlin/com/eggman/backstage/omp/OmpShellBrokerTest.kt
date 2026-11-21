package com.eggman.backstage.omp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class OmpShellBrokerTest {
    private fun withBroker(shell: suspend (String, Long) -> ShellResultDTO?, test: (Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config") },
            { error("No keys") }, payload, shell = shell)
        broker.start()
        try { test(port) } finally { broker.stop() }
    }
    private fun request(port: Int, body: String = """{"command":"id","timeout":60}""", token: String = "termux",
        host: String = "127.0.0.1:$port", extra: String = "", method: String = "POST", length: Int = body.toByteArray().size): Pair<Int, String> =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val header = "$method /omp/v1/shell HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $token\r\n${extra}Content-Length: $length\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(header.toByteArray() + body.toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            response.substringAfter(' ').substringBefore(' ').toInt() to response.substringAfter("\r\n\r\n")
        }

    @Test fun shellTokenHeadersMethodAndBodyAreGatedBeforeExecution() {
        var calls = 0
        withBroker({ _, _ -> calls++; ShellResultDTO(0, "ok", "") }) { port ->
            assertEquals(403, request(port, token = "harness").first)
            assertEquals(401, request(port, token = "wrong").first)
            assertEquals(403, request(port, host = "evil.test:$port").first)
            assertEquals(403, request(port, extra = "Origin: null\r\n").first)
            assertEquals(405, request(port, method = "GET").first)
            // Send only the headers for an oversized body: no body is read or callback invoked.
            assertEquals(413, request(port, body = "", length = 8193).first)
            assertEquals(0, calls)
            assertEquals(200, request(port).first)
            assertEquals(1, calls)
        }
    }
    @Test fun malformedInputIsRejectedAndMissingTimeoutDefaultsToSixtySeconds() {
        val seen = mutableListOf<Pair<String, Long>>()
        withBroker({ command, timeout -> seen += command to timeout; ShellResultDTO(23, "out\n", "err\n") }) { port ->
            for (body in listOf("", "[]", "{}", "null", "{", """{"command":7}""",
                """{"command":"id","timeout":"60"}""", """{"command":"id","timeout":null}""",
                """{"command":"id","timeout":1.5}""", """{"command":"id"} garbage"""))
                assertEquals(body, 400, request(port, body).first)
            assertTrue(seen.isEmpty())
            val (status, body) = request(port, """{"command":"pm list packages"}""")
            assertEquals(200, status)
            assertEquals(listOf("pm list packages" to 60L), seen)
            val result = JSONObject(body)
            assertEquals(23, result.getInt("exit_code"))
            assertEquals("out\n", result.getString("stdout"))
            assertEquals("err\n", result.getString("stderr"))
        }
    }
    @Test fun refusalHasNoExitCodeAndLargeUnicodeOutputIsBounded() {
        withBroker({ _, _ -> throw OmpShellRefused("Termux shell access is disabled") }) { port ->
            val (status, body) = request(port)
            assertEquals(403, status)
            assertTrue(JSONObject(body).getString("error").contains("disabled"))
            assertFalse(JSONObject(body).has("exit_code"))
        }
        val output = "🦊".repeat(100_000) + "tail"
        withBroker({ _, _ -> ShellResultDTO(0, output, output) }) { port ->
            val result = JSONObject(request(port).second)
            for (key in listOf("stdout", "stderr")) {
                val value = result.getString(key)
                assertTrue(value.startsWith("[truncated]\n"))
                assertTrue(value.endsWith("tail"))
                assertFalse(value.contains('\ufffd'))
                assertTrue(value.toByteArray().size <= 256 * 1024)
            }
        }
    }
    @Test fun brokerStopCancelsPendingShellCallback() {
        val port = ServerSocket(0).use { it.localPort }
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config") }, { null }, payload,
            shell = { _, _ -> entered.countDown(); try { awaitCancellation() } finally { cancelled.countDown() } })
        broker.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                val body = """{"command":"id"}"""
                socket.getOutputStream().write("POST /omp/v1/shell HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer termux\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                broker.stop()
                assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        } finally { broker.stop() }
    }
}
