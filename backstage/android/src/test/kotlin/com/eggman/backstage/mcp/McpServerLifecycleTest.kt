package com.eggman.backstage.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Start/stop/restart of the listener itself (mcp-hardening): same-port restarts, no stray accepts, bind backoff. */
class McpServerLifecycleTest {
    private val tools = FakeTools()
    private val loopback = InetAddress.getLoopbackAddress()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun server(port: Int, retries: List<Long> = emptyList()) =
        McpHttpServer(port, lan = false, token = { "secret" }, protocol = McpProtocol(tools), bindRetryDelaysMs = retries)

    /** Something accepts on loopback [port] (a kernel self-connect does not count). */
    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(loopback, port), 300)
            s.localPort != s.port
        }
    }.getOrDefault(false)

    @Test fun stopReleasesTheListenerBeforeReturningSoTheSamePortRebindsAtOnce() {
        val port = freePort()
        // No bind retries: every restart must succeed on its first attempt, which needs stop to have waited for the
        // accept loop (before, ~1 in 10 immediate rebinds failed with "address in use" on JDK 21).
        repeat(40) { round ->
            val s = server(port)
            s.start()
            assertTrue("round $round: listening", s.running)
            // A connection in flight while stopping must not keep the listener alive.
            Socket(loopback, port).use { c ->
                c.getOutputStream().write("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                s.stop()
            }
            assertFalse("round $round: still running after stop", s.running)
            // Checked at once, without waiting: a stopped server accepts nothing.
            assertFalse("round $round: a stopped server still accepted a connection", listening(port))
        }
    }

    @Test fun stopIsIdempotentAndAnInstanceIsSingleUse() {
        val s = server(0)
        s.start()
        s.stop()
        s.stop()
        assertFalse(s.running)
        val e = runCatching { s.start() }.exceptionOrNull()
        assertTrue("restarting a stopped instance must be refused, got $e", e is IllegalStateException)
        server(0).stop() // never started: nothing to wait for
    }

    @Test fun bindRetriesWhileThePortIsBrieflyHeld() {
        val holder = ServerSocket()
        holder.reuseAddress = true
        holder.bind(InetSocketAddress(loopback, 0))
        val port = holder.localPort
        val releaser = Thread { Thread.sleep(120); holder.close() }.apply { start() }
        val s = server(port, retries = listOf(50, 100, 200, 400))
        try {
            s.start()
            assertTrue(s.running)
            assertTrue(listening(port))
        } finally {
            s.stop()
            releaser.join()
        }
    }

    @Test fun aPortAnotherAppHoldsFailsWithBindExceptionAndStaysTheirs() {
        ServerSocket(0, 1, loopback).use { taken ->
            val s = server(taken.localPort, retries = listOf(10, 10))
            val e = runCatching { s.start() }.exceptionOrNull()
            assertTrue("expected BindException, got $e", e is BindException)
            assertFalse(s.running)
            assertFalse(taken.isClosed)
            assertTrue(listening(taken.localPort))
            s.stop()
            assertTrue(McpServerStatus.startError(taken.localPort, e!!).contains("listens on port ${taken.localPort}."))
        }
    }
}
