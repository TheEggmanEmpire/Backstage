package com.eggman.backstage.mcp

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Streamable-HTTP MCP server on plain sockets (no extra dependencies): POST/DELETE on [path],
 * GET answered with 405 (no server-initiated SSE stream), bearer-token auth on every request,
 * Origin check against DNS rebinding, keep-alive, size limits.
 *
 * Binds to all interfaces when [lan] is true, else to loopback (use `adb forward`).
 */
class McpHttpServer(
    val port: Int,
    /** True: listens on all interfaces; false: loopback only. */
    val lan: Boolean,
    private val token: () -> String,
    private val protocol: McpProtocol,
    private val path: String = "/mcp",
    private val idleTimeoutMs: Int = 60_000,
    private val maxConnections: Int = 16,
    /** Waits between bind attempts when the port is still held (a restart on the same port). */
    private val bindRetryDelaysMs: List<Long> = listOf(50, 100, 200, 400),
) {
    // Nothing a peer sends may reach the process's uncaught-exception handler: in the merged app that would
    // kill Dextop and its running desktop session.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false
    /** Counted down when the accept loop has left accept for good (see [stop]). */
    private val acceptDone = CountDownLatch(1)
    private val clients = Collections.synchronizedSet(HashSet<Socket>())

    val running: Boolean get() = !stopped && server?.isClosed == false
    val boundPort: Int get() = server?.localPort ?: port

    /**
     * Binds and starts accepting. A port that is still held for a moment (the previous server of a restart, a socket
     * in TIME_WAIT) is retried with a short backoff ([bindRetryDelaysMs]); a port another app listens on stays theirs:
     * the last [java.net.BindException] is thrown. One instance serves once: after [stop] create a new one.
   */
    @Throws(IOException::class)
    fun start() {
        check(!stopped && server == null) { "McpHttpServer instances are single-use" }
        val s = bindWithRetry()
        server = s
        scope.launch {
            try {
                while (!s.isClosed && !stopped) {
                    val client = try {
                        s.accept()
                    } catch (_: IOException) {
                        break
                    } catch (_: Throwable) {
                        continue
                    }
                    if (stopped) {
                        // Accepted in the instant between stop and the listener going away: never serve it.
                        runCatching { client.close() }
                        break
                    }
                    if (clients.size >= maxConnections) {
                        runCatching { HttpWriter.write(client.getOutputStream(), HttpResponse.text(503, "too many connections"), false); client.close() }
                        continue
                    }
                    clients += client
                    // Each connection is a sibling under the supervisor, not a child of this loop: a failing
                    // connection (even an Error such as OOM) can neither cancel the accept loop nor escape.
                    scope.launch {
                        try { serve(client) } catch (_: Throwable) { } finally { clients -= client; runCatching { client.close() } }
                    }
                }
            } finally {
                runCatching { s.close() }
                // A connection accepted while stop is closing the others is closed here, before stop returns.
                if (stopped) synchronized(clients) { clients.forEach { runCatching { it.close() } } }
                acceptDone.countDown()
            }
        }
    }

    private fun bindWithRetry(): ServerSocket {
        var last: IOException? = null
        for (delayMs in listOf(0L) + bindRetryDelaysMs) {
            if (delayMs > 0) Thread.sleep(delayMs)
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(if (lan) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32)
                return s
            } catch (e: BindException) {
                runCatching { s.close() }
                last = e
            } catch (e: IOException) {
                runCatching { s.close() }
                throw e
            }
        }
        throw last ?: BindException("could not bind port $port")
    }

    /**
     * Closes the listener and every connection, and returns only once the accept loop has let go of the listening
     * socket (bounded by [STOP_JOIN_MS]). Without the wait, ServerSocket.close can return while another thread is
     * still inside accept and the kernel listener lingers: a restart on the same port then fails with "address in
     * use", and the closed server may still accept a connection. Idempotent.
   */
    fun stop() {
        stopped = true
        val s = server
        runCatching { s?.close() }
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
        if (s != null) runCatching { acceptDone.await(STOP_JOIN_MS, TimeUnit.MILLISECONDS) }
        scope.cancel()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = idleTimeoutMs
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val reader = HttpReader(input, output)
        while (!socket.isClosed) {
            val request = try {
                reader.read(::admit) ?: return
            } catch (e: HttpError) {
                runCatching { HttpWriter.write(output, HttpResponse.text(e.status, e.message ?: "bad request"), false) }
                lingeringClose(socket, input)
                return
            } catch (_: SocketTimeoutException) {
                return
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                return
            }
            request.rejected?.let { early ->
                // Refused from the headers alone: the body is unread, so the connection cannot be reused.
                runCatching { HttpWriter.write(output, early, false) }
                lingeringClose(socket, input)
                return
            }
            val response = try {
                route(request)
            } catch (t: Throwable) {
                HttpResponse.text(500, "internal error")
            }
            val keep = request.keepAlive && response.status < 500
            try {
                HttpWriter.write(output, response, keep)
            } catch (_: IOException) {
                return
            }
            if (!keep) return
        }
    }

    /**
     * After answering a request whose body is not consumed: half-close, then discard a bounded amount of
     * what the peer is still sending, so closing does not reset the connection before it reads the answer.
   */
    private fun lingeringClose(socket: Socket, input: java.io.InputStream) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = LINGER_MS
            val sink = ByteArray(8 * 1024)
            var left = LINGER_BYTES
            while (left > 0) {
                val r = input.read(sink, 0, minOf(sink.size, left))
                if (r < 0) break
                left -= r
            }
        }
    }

    /**
     * Header-only screening, run before any body byte is read: wrong path, foreign origin or host and a
     * missing bearer token are refused at once; a request with a wrong token may send only a small body
     * (enough for the JSON-RPC id echoed by [McpProtocol.invalidToken]).
   */
    internal fun admit(head: HttpRequest): HttpReader.Gate {
        preAuthProblem(head)?.let { return HttpReader.Gate(reject = it) }
        if (head.method == "OPTIONS") return HttpReader.Gate()
        return if (TokenAuth.check(head.header("authorization"), token())) HttpReader.Gate() else HttpReader.Gate(maxBody = UNAUTHENTICATED_BODY)
    }

    private fun preAuthProblem(request: HttpRequest): HttpResponse? {
        val p = request.path.substringBefore('?')
        if (p != path && p != "$path/") return HttpResponse.text(404, "not found")
        originProblem(request)?.let { return HttpResponse.text(403, it) }
        if (request.method == "OPTIONS") return null
        val auth = request.header("authorization")
        if (auth == null || !auth.trim().startsWith("Bearer ", ignoreCase = true)) {
            return HttpResponse.json(401, McpProtocol.error(null, McpProtocol.UNAUTHORIZED, "Unauthorized: send Authorization: Bearer <token>").toString(),
                mapOf("WWW-Authenticate" to "Bearer realm=\"backstage\""))
        }
        return null
    }

    internal fun route(request: HttpRequest): HttpResponse {
        preAuthProblem(request)?.let { return it }
        if (request.method == "OPTIONS") return HttpResponse.empty(204, mapOf("Allow" to ALLOW))
        val auth = request.header("authorization")
        if (!TokenAuth.check(auth, token())) {
            // A token that is regenerated on the phone: answer with a visible tool-style result so chat
            // clients show the user what to fix instead of hiding a transport error.
            return protocol.invalidToken(request)
        }
        return when (request.method) {
            "POST" -> runBlocking { protocol.handlePost(request) }
            "DELETE" -> protocol.handleDelete(request)
            "GET" -> HttpResponse.text(405, "This server does not open an SSE stream; use POST.", mapOf("Allow" to ALLOW))
            else -> HttpResponse.text(405, "method not allowed", mapOf("Allow" to ALLOW))
        }
    }

    internal companion object {
        const val ALLOW = "POST, GET, DELETE, OPTIONS"
        /** Body cap for requests whose bearer token is wrong (they only get the "update your token" reply). */
        const val UNAUTHENTICATED_BODY = 64 * 1024
        private const val LINGER_MS = 1_000
        private const val LINGER_BYTES = 256 * 1024
        /** Upper bound for [stop] to wait until the accept loop has released the listening socket. */
        const val STOP_JOIN_MS = 2_000L

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV6 = Regex("^[0-9a-f:.]+(%[0-9a-z._-]+)?$")

        /**
         * DNS-rebinding defence: a rebinding page reaches us under a hostname its author controls, so the
         * Host header must be an IP literal, localhost or an mDNS (.local) name. A missing Host header
         * (HTTP/1.0 tools) is accepted; the bearer token still applies.
       */
        fun hostAllowed(hostHeader: String?): Boolean {
            val h = hostHeader?.trim()?.lowercase() ?: return true
            val host = when {
                h.startsWith("[") -> h.substringAfter('[').substringBefore(']')
                h.count { it == ':' } == 1 -> h.substringBefore(':')
                else -> h
            }
            if (host.isEmpty()) return false
            return host == "localhost" || host.endsWith(".local") || IPV4.matches(host) || (':' in host && IPV6.matches(host))
        }
    }

    /** Host must be an address (see [hostAllowed]); a browser Origin must be that same host or loopback. */
    private fun originProblem(request: HttpRequest): String? {
        if (!hostAllowed(request.header("host"))) return "host not allowed"
        val origin = request.header("origin") ?: return null
        if (origin == "null") return "origin not allowed"
        val originHost = runCatching { URI(origin).host }.getOrNull()?.lowercase() ?: return "bad origin"
        val host = request.header("host")?.substringBeforeLast(':')?.trim('[', ']')?.lowercase()
        if (originHost == host || originHost == "localhost" || originHost == "127.0.0.1" || originHost == "::1") return null
        return "origin not allowed"
    }
}
