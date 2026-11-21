package com.eggman.backstage.omp

import com.eggman.backstage.mcp.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import com.eggman.backstage.tools.ToolArgumentException
import com.eggman.backstage.agent.api.chatTail
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** Header-only checks; rejected requests never read a body or resolve credentials. */
class OmpBrokerAuth(
    private val port: Int,
    private val harness: () -> String,
    private val termux: () -> String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val requests = mutableMapOf<String, Pair<String, java.util.ArrayDeque<Long>>>()
    @Synchronized fun problem(r: HttpRequest): HttpResponse? {
        if (r.header("origin") != null || r.header("host") !in setOf("127.0.0.1:$port", "localhost:$port"))
            return HttpResponse.text(403, "Browser origins and foreign hosts are refused")
        // Claim codes are the sole credential for this endpoint. Count failed/missing codes too;
        // actual consumption happens in the route, after HTTP framing has been validated.
        if (r.path == "/omp/v1/claim") return limit("claim", "")
            ?: if (r.method != "GET") HttpResponse.text(405, "GET required") else null
        val harnessValue = harness()
        val termuxValue = termux()
        val h = TokenAuth.check(r.header("authorization"), harnessValue)
        val t = TokenAuth.check(r.header("authorization"), termuxValue)
        if (!h && !t) return HttpResponse.text(401, "Open Backstage; pairing revoked: run the setup line again")
        if (!t && (r.path.startsWith("/omp/v1/payload/") || r.path == "/omp/v1/termux/setup" || r.path == "/omp/v1/shell"))
            return HttpResponse.text(403, "Termux pairing token required")
        val role = if (t) "termux" else "harness"
        val current = if (t) termuxValue else harnessValue
        limit(role, current)?.let { return it }
        val method = if (r.path == "/omp/v1/shell") "POST" else "GET"
        if (r.method != method) return HttpResponse.text(405, "$method required")
        return null
    }
    private fun limit(role: String, current: String): HttpResponse? {
        val window = requests[role]?.takeIf { it.first == current }
            ?: (current to java.util.ArrayDeque<Long>()).also { requests[role] = it }
        val queue = window.second
        val now = clock()
        while (queue.isNotEmpty() && now - queue.first >= 10_000) queue.removeFirst()
        if (queue.size >= 64) return HttpResponse.text(429, "Rate limit; retry in 10 seconds", mapOf("Retry-After" to "10"))
        queue.addLast(now)
        return null
    }
}

/** Plain sockets and HttpCore framing, IPv4 loopback only; streams large assets with a bounded buffer. */
class OmpKeyBroker(
    val port: Int,
    harnessToken: () -> String,
    termuxToken: () -> String,
    private val config: () -> OmpConfig.Files,
    private val key: suspend (String) -> String?,
    private val payload: OmpPayload,
    private val claims: OmpClaims = OmpClaims(),
    private val shell: suspend (String, Long) -> ShellResultDTO? = { _, _ -> null },
    private val skills: () -> OmpSkills.Archive = OmpSkills.SnapshotCache()::open,
    private val writeDeadlineMs: Long = WRITE_DEADLINE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val auth = OmpBrokerAuth(port, harnessToken, termuxToken)
    @Volatile private var listener: ServerSocket? = null
    val running get() = listener?.isClosed == false

    @Synchronized fun start() {
        check(listener == null)
        val server = ServerSocket()
        try { server.reuseAddress = true; server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16) }
        catch (e: Exception) { server.close(); throw e }
        listener = server
        scope.launch {
            while (isActive && !server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (server.isClosed || clients.size >= 8) { socket.close(); continue }
                clients += socket
                val job = launch {
                    try { serve(socket) } catch (_: Exception) { /* Never log request headers or keys. */ }
                    finally { clients -= socket; runCatching { socket.close() } }
                }
                // Cancellation can occur before a launch body enters its try/finally.
                job.invokeOnCompletion { clients -= socket; runCatching { socket.close() } }
            }
        }
    }
    fun stop() {
        claims.clear()
        runCatching { listener?.close() }
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
        scope.cancel()
    }
    private suspend fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        // Absolute deadline: a client dripping bytes slower than soTimeout must still not hold a slot forever.
        val watchdog = scope.launch { kotlinx.coroutines.delay(READ_DEADLINE_MS); runCatching { socket.close() } }
        var writeWatchdog: Job? = null
        val out = BufferedOutputStream(socket.getOutputStream())
        try {
        val request = try {
            HttpReader(BufferedInputStream(socket.getInputStream()), out, maxBody = SHELL_MAX_BODY).read {
                HttpReader.Gate(reject = auth.problem(it), maxBody = if (it.path == "/omp/v1/shell") SHELL_MAX_BODY else 0)
            } ?: return
        } catch (e: HttpError) { HttpWriter.write(out, HttpResponse.text(e.status, "Invalid request"), false); return }
        // The full request (line, headers, bounded body) is read; long work (shell exec, asset download) follows.
        watchdog.cancel()
        request.rejected?.let { HttpWriter.write(out, it, false); return }
        val path = request.path
        // Socket.soTimeout covers reads only. Bound blocked downloads, including readers that stop consuming.
        if (path == "/omp/v1/config/skills.zip" || path.startsWith("/omp/v1/payload/") || path.startsWith("/omp/v1/config/")) {
            writeWatchdog = scope.launch { delay(writeDeadlineMs); runCatching { socket.close() } }
        }
        val response = when {
            path == "/omp/v1/shell" -> runShell(request)
            path == "/omp/v1/claim" -> claims.consume(request.header("x-backstage-claim"))
                ?.let { HttpResponse.text(200, it) } ?: HttpResponse.text(401, "Claim invalid, expired, or already used")
            path == "/omp/v1/config/models.yml" -> HttpResponse.text(200, config().models)
            path == "/omp/v1/config/config.yml" -> HttpResponse.text(200, config().config)
            path == "/omp/v1/config/mcp.json" -> HttpResponse.json(200, config().mcp)
            path == "/omp/v1/config/instructions.txt" -> HttpResponse.text(200, config().instructions)
            path == "/omp/v1/config/skills.zip" -> {
                val archive = try {
                    skills().also {
                        if (it.size > OmpSkills.MAX_ZIP_BYTES) { it.close(); error("Skills archive too large") }
                    }
                } catch (_: Exception) {
                    HttpWriter.write(out, HttpResponse.text(503, "Skills unavailable; keeping cached skills"), false)
                    return
                }
                archive.use { streamAsset(out, it.input, it.size, "application/zip") }
                return
            }
            path.startsWith("/omp/v1/key/") -> {
                val id = path.removePrefix("/omp/v1/key/")
                if (!id.matches(Regex("[a-z0-9-]{1,64}"))) HttpResponse.text(404, "Unknown provider")
                else try { key(id)?.takeIf { it.isNotBlank() }?.let { HttpResponse.text(200, it) } ?: HttpResponse.text(404, "Provider has no credential") }
                catch (_: Exception) { HttpResponse.text(503, "Open Backstage and check the provider sign-in") }
            }
            path == "/omp/v1/termux/setup" -> HttpResponse.text(200, payload.open("device/termux-setup.sh").bufferedReader().use { it.readText() }.replace("@BROKER_PORT@", port.toString()))
            path.startsWith("/omp/v1/payload/") -> {
                val name = path.removePrefix("/omp/v1/payload/")
                if (name != "manifest.json" && payload.manifest?.files?.containsKey(name) != true) HttpResponse.text(404, "Unknown payload file")
                else {
                    payload.open(name).use { stream -> streamAsset(out, stream, payload.size(name)) }
                    return
                }
            }
            else -> HttpResponse.text(404, "Not found")
        }
        HttpWriter.write(out, response, false)
        } finally { watchdog.cancel(); writeWatchdog?.cancel() }
    }

    private suspend fun runShell(request: HttpRequest): HttpResponse {
        val input = try {
            val tokener = JSONTokener(request.bodyText)
            val json = tokener.nextValue() as? JSONObject ?: return shellError(400, "JSON object required")
            if (tokener.nextClean() != '\u0000') return shellError(400, "Unexpected data after JSON")
            val command = json.opt("command") as? String ?: return shellError(400, "command must be a string")
            val timeout = if (json.has("timeout")) json.opt("timeout") else 60L
            if (timeout !is Int && timeout !is Long) return shellError(400, "timeout must be an integer number of seconds")
            command to (timeout as Number).toLong()
        } catch (_: Exception) { return shellError(400, "Invalid shell request JSON") }
        return try {
            val result = shell(input.first, input.second) ?: return shellError(403, "Termux shell access is disabled or unavailable")
            HttpResponse.json(200, JSONObject().put("exit_code", result.exitCode)
                .put("stdout", boundedOutput(result.stdout)).put("stderr", boundedOutput(result.stderr)).toString())
        } catch (e: OmpShellRefused) { shellError(e.status, e.message.orEmpty()) }
        catch (e: ToolArgumentException) { shellError(400, e.message.orEmpty()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { shellError(503, "Shell command failed; open Backstage and check privileged access") }
    }

    private fun shellError(status: Int, message: String) = HttpResponse.json(status, JSONObject().put("error", message).toString())

    companion object {
        const val SHELL_MAX_BODY = 8 * 1024
        /** A full request must arrive within this long; it does not bound the response (shell exec, downloads). */
        const val READ_DEADLINE_MS = 15_000L
        const val WRITE_DEADLINE_MS = 60_000L
        const val SHELL_MAX_OUTPUT = 256 * 1024
        internal fun boundedOutput(value: String): String {
            if (value.toByteArray(Charsets.UTF_8).size <= SHELL_MAX_OUTPUT) return value
            val marker = "[truncated]\n"
            return marker + chatTail(value, SHELL_MAX_OUTPUT - marker.length)
        }
    }
    private fun streamAsset(out: java.io.OutputStream, input: InputStream, size: Long, contentType: String = "application/octet-stream") {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: $size\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        input.copyTo(out, 32 * 1024)
        out.flush()
    }
}
