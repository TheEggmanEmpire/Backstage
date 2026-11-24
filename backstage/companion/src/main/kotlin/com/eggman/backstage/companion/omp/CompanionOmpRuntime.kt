package com.eggman.backstage.companion.omp

import android.content.Context
import android.os.Bundle
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.privilege.CompanionPrivilege
import com.eggman.backstage.omp.OmpPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Companion-owned OMP runtime. Play sends only typed requests over the authenticated Binder session. */
object CompanionOmpRuntime {
    const val ROOT = "/data/local/tmp/backstage-agent-omp"
    private const val MAX_BROKER_BODY = 8 * 1024
    private const val MAX_BROKER_HEADER = 8 * 1024
    private const val OP_TIMEOUT_MS = 20 * 60_000L
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "Backstage-OMP").apply { isDaemon = true } }
    private val operations = ConcurrentHashMap<String, Operation>()
    private val sessions = ConcurrentHashMap<Any, Session>()
    private val lock = Any()

    private data class Operation(val owner: Any, @Volatile var state: String = "pending", @Volatile var message: String = "Queued", @Volatile var result: Bundle? = null, @Volatile var future: Future<*>? = null)
    private data class Session(
        @Volatile var port: Int = 47821,
        @Volatile var harnessToken: String = "",
        @Volatile var termuxToken: String = "",
        @Volatile var enabled: Boolean = false,
        @Volatile var termuxEnabled: Boolean = false,
        @Volatile var shellEnabled: Boolean = false,
        @Volatile var shellAuto: Boolean = false,
        @Volatile var models: String = "",
        @Volatile var config: String = "",
        @Volatile var mcp: String = "{}",
        @Volatile var instructions: String = "",
        @Volatile var keys: JSONObject = JSONObject(),
        @Volatile var broker: Broker? = null,
    )

    @JvmStatic fun capabilities(context: Context): Int =
        if (CompanionPrivilege.canUseShell() && OmpPayload(context.assets).available &&
            context.applicationInfo.nativeLibraryDir.isNotBlank()) CompanionProtocol.CAP_OMP else 0

    @JvmStatic fun request(context: Context, owner: Any, request: Bundle): Bundle {
        if (capabilities(context) == 0) return failure("OMP runtime payload or shell access is unavailable")
        return try {
            when (request.getString("action").orEmpty()) {
                "configure" -> configure(context.applicationContext, owner, request)
                "status" -> status(context.applicationContext, owner)
                "install", "repair", "update" -> begin(owner, request.getString("requestId"), request.getString("action").orEmpty()) { runLong(context.applicationContext, owner, it) }
                "poll" -> poll(owner, request.getString("requestId").orEmpty())
                "cancel" -> cancel(owner, request.getString("requestId").orEmpty())
                "subagent_start" -> startSubagent(context.applicationContext, owner, request)
                "subagent_status" -> subagentStatus(context.applicationContext, owner, request)
                "subagent_cancel" -> subagentCancel(context.applicationContext, owner, request)
                else -> failure("Unsupported OMP action")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e.message ?: "OMP request failed") }
    }

    @JvmStatic fun cancelOwner(owner: Any) {
        operations.entries.removeIf { (_, operation) ->
            if (operation.owner !== owner) false else { operation.future?.cancel(true); true }
        }
        sessions.remove(owner)?.broker?.stop()
        CompanionPrivilege.cancelOwner(owner)
    }

    /** Play extensions invoke only interpreters inside the installed companion userland. */
    @JvmStatic fun skillArgv(context: Context, argv: List<String>): List<String>? {
        if (argv.isEmpty() || argv.size > 64 || argv.any { it.length > 4096 || '\u0000' in it }) return null
        val interpreter = argv.firstOrNull()?.substringAfterLast('/')
        if (interpreter !in setOf("bun", "python", "python3", "node", "sh", "bash")) return null
        val state = sessions.values.firstOrNull { it.enabled } ?: return null
        return listOf("sh", "$ROOT/run.sh", ROOT, context.applicationInfo.nativeLibraryDir, "--") + argv
    }

    private fun configure(context: Context, owner: Any, request: Bundle): Bundle {
        val port = request.getInt("brokerPort", 47821).coerceIn(1024, 65535)
        val harness = request.getString("harnessToken").orEmpty().take(128)
        val termux = request.getString("termuxToken").orEmpty().take(128)
        val enabled = request.getBoolean("ompEnabled")
        val termuxEnabled = request.getBoolean("termuxEnabled")
        val session = sessions.computeIfAbsent(owner) { Session() }
        synchronized(session) {
            val unchanged = session.port == port && session.harnessToken == harness && session.termuxToken == termux &&
                session.enabled == enabled && session.termuxEnabled == termuxEnabled &&
                session.shellEnabled == request.getBoolean("shellEnabled") && session.shellAuto == request.getBoolean("shellAutoApprove")
            session.models = request.getString("models").orEmpty().take(256_000)
            session.config = request.getString("config").orEmpty().take(256_000)
            session.mcp = request.getString("mcp").orEmpty().take(256_000)
            session.instructions = request.getString("instructions").orEmpty().take(128_000)
            session.keys = runCatching { JSONObject(request.getString("keys").orEmpty()) }.getOrDefault(JSONObject())
            session.port = port; session.harnessToken = harness; session.termuxToken = termux
            session.enabled = enabled; session.termuxEnabled = termuxEnabled
            session.shellEnabled = request.getBoolean("shellEnabled"); session.shellAuto = request.getBoolean("shellAutoApprove")
            if (!unchanged || (session.broker?.running != true && (enabled || termuxEnabled))) {
                session.broker?.stop(); session.broker = null
                if ((enabled || termuxEnabled) && (harness.isNotBlank() || termux.isNotBlank())) {
                    session.broker = Broker(context, session).also { it.start() }
                }
            }
        }
        val install = installed(context)
        if (install.first) writeBroker(context, session, owner)
        return Bundle().apply {
            putBoolean("ok", true); putString("state", "finished")
            putString("message", if (install.first) "OMP installed (${install.second})" else "OMP not installed")
            putBoolean("installed", install.first); putInt("capabilities", CompanionProtocol.CAP_OMP)
        }
    }

    private fun status(context: Context, owner: Any): Bundle {
        val session = sessions[owner] ?: return Bundle().apply {
            putBoolean("ok", true); putString("state", "finished"); putBoolean("installed", false); putString("message", "Waiting for Play app configuration")
        }
        val installed = installed(context)
        if (installed.first && session.enabled) writeBroker(context, session, owner)
        return Bundle().apply {
            putBoolean("ok", true); putString("state", "finished"); putBoolean("installed", installed.first)
            putString("message", if (installed.first) "OMP installed (${installed.second})" else "OMP not installed")
            putInt("capabilities", CompanionProtocol.CAP_OMP)
        }
    }

    private fun begin(owner: Any, suppliedId: String?, verb: String, work: (Operation) -> Bundle): Bundle {
        val id = suppliedId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) } ?: java.util.UUID.randomUUID().toString()
        val operation = Operation(owner)
        synchronized(lock) {
            if (operations.putIfAbsent(id, operation) != null) return failure("Request ID is already in use")
            while (operations.size > 64) operations.entries.firstOrNull()?.let { operations.remove(it.key) } ?: break
        }
        operation.future = executor.submit {
            operation.state = "running"; operation.message = "${verb.replaceFirstChar(Char::uppercase)} started"
            try {
                operation.result = work(operation)
                operation.state = if (operation.result?.getBoolean("ok", false) == true) "finished" else "failed"
                operation.message = operation.result?.getString("message") ?: operation.result?.getString("error").orEmpty()
            } catch (e: Exception) {
                operation.result = failure(e.message ?: "OMP operation failed"); operation.state = "failed"; operation.message = operation.result?.getString("error").orEmpty()
            }
        }
        return Bundle().apply { putBoolean("ok", true); putString("state", "pending"); putString("requestId", id); putString("message", operation.message) }
    }

    private fun runLong(context: Context, owner: Any, operation: Operation): Bundle {
        val session = sessions[owner] ?: return failure("Configure OMP first")
        val action = when (operation.message.substringBefore(' ').lowercase()) { "repair" -> "repair"; "update" -> "update"; else -> "install" }
        if (action == "update") {
            val (code, out, err) = runRoot(context, listOf("omp-termux", "install", "latest"), 10 * 60_000L)
            if (code != 0) return result(code, out, err, "OMP update failed")
        } else {
            val apk = context.applicationInfo.sourceDir
            val stage = "$ROOT.installer"
            val script = "set -eu; mkdir -p ${q(stage)}; /system/bin/unzip -p ${q(apk)} assets/omp/device/install.sh > ${q("$stage/install.sh")}; chmod 700 ${q("$stage/install.sh")}; BACKSTAGE_OMP_REPAIR=${if (action == "repair") 1 else 0} /system/bin/sh ${q("$stage/install.sh")} ${q(ROOT)} ${q(apk)} ${q(context.applicationInfo.nativeLibraryDir)}"
            val (code, out, err) = CompanionPrivilege.runSystemCommand(context, listOf("sh", "-c", script), OP_TIMEOUT_MS)
            if (code != 0) return result(code, out, err, "OMP $action failed")
        }
        operation.message = "Writing provider broker pairing"
        writeBroker(context, session, owner)
        val (ok, version) = installed(context)
        return Bundle().apply { putBoolean("ok", ok); putString("state", if (ok) "finished" else "failed"); putBoolean("installed", ok); putString("message", if (ok) "OMP installed ($version)" else "OMP install verification failed") }
    }

    private fun poll(owner: Any, id: String): Bundle {
        val op = operations[id]?.takeIf { it.owner === owner } ?: return failure("Unknown OMP request")
        return Bundle().apply {
            putBoolean("ok", true); putString("state", op.state); putString("requestId", id); putString("message", op.message)
            op.result?.let { putBundle("resultBundle", it) }
        }
    }

    private fun cancel(owner: Any, id: String): Bundle {
        val operation = operations[id]?.takeIf { it.owner === owner } ?: return failure("Unknown OMP request")
        operation.future?.cancel(true)
        operation.state = "cancelled"; operation.message = "OMP request cancelled"
        return Bundle().apply { putBoolean("ok", true); putString("state", "cancelled"); putString("requestId", id) }
    }

    private fun startSubagent(context: Context, owner: Any, request: Bundle): Bundle {
        val task = request.getString("task").orEmpty()
        require(task.isNotBlank() && task.length <= 2000 && '\u0000' !in task) { "Task must contain 1..2000 characters" }
        val model = request.getString("model").orEmpty().take(4000)
        val cwd = request.getString("cwd").orEmpty().take(4000)
        require(cwd.isEmpty() || cwd.startsWith('/')) { "Working directory must be absolute" }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val session = sessions[owner] ?: return failure("Configure OMP first")
        if (!session.enabled) return failure("OMP is disabled in Backstage settings")
        val script = "set -eu; d=${q("$ROOT/jobs/$id")}; mkdir -p \"\$d\"; printf %s ${q(task)} > \"\$d/task\"; printf %s ${q(model)} > \"\$d/model\"; printf %s ${q(cwd)} > \"\$d/cwd\"; sh ${q("$ROOT/job.sh")} ${q(ROOT)} ${q(context.applicationInfo.nativeLibraryDir)} start ${q(id)}"
        val (code, out, err) = CompanionPrivilege.runSystemCommand(context, listOf("sh", "-c", script), 15_000)
        if (code != 0) return result(code, out, err, "Could not start OMP subagent")
        val wait = request.getInt("waitSeconds", 120).coerceIn(0, 600)
        return if (wait == 0) jobStatus(context, id, 0) else begin(owner, null, "subagent status", waitWork(context, id, wait))
            .also { it.putString("jobId", id) }
    }

    private fun waitWork(context: Context, id: String, seconds: Int): (Operation) -> Bundle = { _ -> jobStatus(context, id, seconds) }

    private fun subagentStatus(context: Context, owner: Any, request: Bundle): Bundle {
        if (sessions[owner] == null) return failure("Configure OMP first")
        val id = validJobId(request.getString("jobId").orEmpty())
        val wait = request.getInt("waitSeconds", 60).coerceIn(0, 600)
        return if (wait == 0) jobStatus(context, id, 0) else begin(owner, request.getString("requestId"), "subagent status", waitWork(context, id, wait))
    }

    private fun jobStatus(context: Context, id: String, seconds: Int): Bundle {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var data: JSONObject
        do {
            val (code, out, err) = runRoot(context, listOf("sh", "$ROOT/job.sh", ROOT, context.applicationInfo.nativeLibraryDir, "status", id), 15_000)
            if (code != 0) return result(code, out, err, "Unknown or unreadable OMP job")
            data = JSONObject(out).put("job_id", id)
            if (data.optString("state") !in setOf("starting", "running") || System.currentTimeMillis() >= deadline) break
            Thread.sleep(500)
        } while (true)
        if (data.optString("state") !in setOf("starting", "running")) {
            val out = runRoot(context, listOf("sh", "-c", "tail -c 16384 ${q("$ROOT/jobs/$id/out")} 2>/dev/null || :"), 5_000).second
            val err = runRoot(context, listOf("sh", "-c", "tail -c 16384 ${q("$ROOT/jobs/$id/err")} 2>/dev/null || :"), 5_000).second
            data.put("answer", out).put("stderr", err)
        }
        return Bundle().apply { putBoolean("ok", true); putString("state", "finished"); putString("result", data.toString()) }
    }

    private fun subagentCancel(context: Context, owner: Any, request: Bundle): Bundle {
        if (sessions[owner] == null) return failure("Configure OMP first")
        val id = validJobId(request.getString("jobId").orEmpty())
        val (code, out, err) = runRoot(context, listOf("sh", "$ROOT/job.sh", ROOT, context.applicationInfo.nativeLibraryDir, "cancel", id), 15_000)
        return if (code == 0) Bundle().apply { putBoolean("ok", true); putString("state", "finished"); putString("result", JSONObject(out).put("job_id", id).toString()) }
        else result(code, out, err, "Could not cancel OMP job")
    }

    private fun installed(context: Context): Pair<Boolean, String> {
        val (code, out, _) = CompanionPrivilege.runSystemCommand(context, listOf("sh", "-c", "test -x ${q("$ROOT/home/.bun/bin/bun")} && test -f ${q("$ROOT/run.sh")} && cat ${q("$ROOT/installed.json")}"), 5_000)
        if (code != 0) return false to "Not installed"
        return runCatching { JSONObject(out).let { (it.optInt("payload") == 1) to it.optString("ompVersion") } }.getOrDefault(false to "Invalid payload")
    }

    private fun writeBroker(context: Context, session: Session, owner: Any) {
        if (!session.enabled || session.harnessToken.isBlank() || session.broker?.running != true) return
        val spec = "port=${session.port}\ntoken=${session.harnessToken}\n"
        val code = java.util.UUID.randomUUID().toString().replace("-", "")
        val broker = session.broker ?: return
        if (!broker.issueClaim(code, spec)) return
        val script = "set -eu; printf 'header = \\\"X-Backstage-Claim: %s\\\"\\n' ${q(code)} | curl -q --noproxy '*' --config - --fail --silent --show-error --connect-timeout 1 --max-time 5 http://127.0.0.1:${session.port}/omp/v1/claim > ${q("$ROOT/.broker.new")}; test -s ${q("$ROOT/.broker.new")}; chmod 600 ${q("$ROOT/.broker.new")}; mkdir -p ${q("$ROOT/home/.config/backstage-omp")}; mv ${q("$ROOT/.broker.new")} ${q("$ROOT/home/.config/backstage-omp/broker")}"
        CompanionPrivilege.runSystemCommand(context, listOf("sh", "$ROOT/run.sh", ROOT, context.applicationInfo.nativeLibraryDir, "--", "bash", "-c", script), 15_000)
        broker.revokeClaim(code)
    }

    private fun runRoot(context: Context, argv: List<String>, timeoutMs: Long): Triple<Int, String, String> =
        CompanionPrivilege.runSystemCommand(context, listOf("sh", "$ROOT/run.sh", ROOT, context.applicationInfo.nativeLibraryDir, "--") + argv, timeoutMs)

    private fun result(code: Int, stdout: String, stderr: String, message: String) = Bundle().apply {
        putBoolean("ok", code == 0); putString("state", if (code == 0) "finished" else "failed"); putInt("exit", code)
        putString("stdout", stdout.takeLast(64 * 1024)); putString("stderr", stderr.takeLast(64 * 1024)); putString("message", message)
        if (code != 0) putString("error", "$message (${code}): ${stderr.ifBlank { stdout }.takeLast(512)}")
    }

    private fun failure(message: String) = Bundle().apply { putBoolean("ok", false); putString("state", "failed"); putString("error", message) }
    private fun validJobId(id: String): String { require(id.matches(Regex("[a-z0-9]{8,32}"))) { "Invalid OMP job id" }; return id }
    private fun q(value: String): String { require('\u0000' !in value); return "'" + value.replace("'", "'\\''") + "'" }

    private class Broker(private val context: Context, private val session: Session) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val claims = ConcurrentHashMap<String, String>()
        @Volatile private var server: ServerSocket? = null
        val running get() = server?.isClosed == false
        fun start() {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), session.port), 16)
            server = socket
            scope.launch {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    launch { handle(client) }
                }
            }
        }
        fun stop() { runCatching { server?.close() }; claims.clear(); scope.cancel() }
        fun issueClaim(code: String, content: String): Boolean { claims[code] = content; return true }
        fun revokeClaim(code: String) { claims.remove(code) }

        private fun handle(socket: Socket) {
            socket.use { client ->
                client.soTimeout = 5_000
                val input = BufferedInputStream(client.getInputStream())
                val output = BufferedOutputStream(client.getOutputStream())
                val headerBytes = java.io.ByteArrayOutputStream()
                var state = 0
                while (headerBytes.size() < MAX_BROKER_HEADER) {
                    val byte = input.read(); if (byte < 0) return
                    headerBytes.write(byte)
                    state = when { state == 0 && byte == 13 -> 1; state == 1 && byte == 10 -> 2; state == 2 && byte == 13 -> 3; state == 3 && byte == 10 -> 4; byte == 13 -> 1; else -> 0 }
                    if (state == 4) break
                }
                val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
                val parts = lines.firstOrNull()?.split(' ') ?: return
                if (parts.size != 3) return response(output, 400, "Invalid request")
                val method = parts[0]; val path = parts[1].substringBefore('?')
                val headers = lines.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
                if (headers.containsKey("origin") || headers["host"] !in setOf("127.0.0.1:${session.port}", "localhost:${session.port}")) return response(output, 403, "Foreign host refused")
                if (path == "/omp/v1/claim") {
                    if (method != "GET") return response(output, 405, "GET required")
                    val claim = claims.remove(headers["x-backstage-claim"]) ?: return response(output, 401, "Claim invalid")
                    return response(output, 200, claim)
                }
                val token = headers["authorization"]?.removePrefix("Bearer ")
                val isHarness = session.enabled && token == session.harnessToken && token?.isNotBlank() == true
                val isTermux = session.termuxEnabled && token == session.termuxToken && token?.isNotBlank() == true
                if (!isHarness && !isTermux) return response(output, 401, "Open Backstage and pair again")
                when {
                    method == "GET" && path == "/omp/v1/config/models.yml" && isHarness -> response(output, 200, session.models)
                    method == "GET" && path == "/omp/v1/config/config.yml" && isHarness -> response(output, 200, session.config)
                    method == "GET" && path == "/omp/v1/config/mcp.json" && isHarness -> response(output, 200, session.mcp, "application/json")
                    method == "GET" && path == "/omp/v1/config/instructions.txt" && isHarness -> response(output, 200, session.instructions)
                    method == "GET" && path.startsWith("/omp/v1/key/") && isHarness -> keyReply(output, path.removePrefix("/omp/v1/key/"))
                    method == "GET" && path == "/omp/v1/termux/setup" && isTermux -> payloadText(output, "termux-setup.sh")
                    method == "GET" && path.startsWith("/omp/v1/payload/") && isTermux -> payloadStream(output, path.removePrefix("/omp/v1/payload/"))
                    method == "POST" && path == "/omp/v1/shell" && isTermux -> shellRequest(input, output, headers["content-length"]?.toIntOrNull() ?: -1)
                    else -> response(output, 404, "Not found")
                }
            }
        }
        private fun keyReply(output: BufferedOutputStream, id: String) {
            if (!id.matches(Regex("[a-z0-9-]{1,64}"))) return response(output, 404, "Unknown provider")
            val key = session.keys.optString(id)
            if (key.isBlank()) response(output, 404, "Provider has no credential") else response(output, 200, key)
        }
        private fun payloadText(output: BufferedOutputStream, name: String) {
            val path = "omp/device/$name"
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                .onSuccess { response(output, 200, it.replace("@BROKER_PORT@", session.port.toString())) }
                .onFailure { response(output, 404, "Unknown payload file") }
        }
        private fun payloadStream(output: BufferedOutputStream, name: String) {
            val payload = OmpPayload(context.assets)
            if (name != "manifest.json" && payload.manifest?.files?.containsKey(name) != true) return response(output, 404, "Unknown payload file")
            runCatching { payload.open(name).use { stream ->
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${payload.size(name)}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                stream.copyTo(output, 32 * 1024); output.flush()
            } }.onFailure { response(output, 503, "Payload unavailable") }
        }
        private fun shellRequest(input: BufferedInputStream, output: BufferedOutputStream, length: Int) {
            if (!session.shellEnabled || length !in 2..MAX_BROKER_BODY) return response(output, 403, "Termux shell is disabled")
            val body = ByteArray(length); var offset = 0
            while (offset < body.size) { val n = input.read(body, offset, body.size - offset); if (n < 0) return response(output, 400, "Incomplete request"); offset += n }
            val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrElse { return response(output, 400, "Invalid JSON") }
            val command = json.optString("command")
            if (command.isBlank() || command.length > 1000 || '\u0000' in command) return response(output, 400, "Invalid shell command")
            val req = Bundle().apply { putString("action", "start"); putString("operation", "shell"); putString("argument", command); putInt("timeoutSeconds", json.optInt("timeout", 60).coerceIn(5, 300)) }
            val ack = CompanionPrivilege.request(context, session, req)
            val requestId = ack.getString("requestId").orEmpty()
            if (ack.getString("state") == "pending") {
                var reply = ack
                val deadline = System.currentTimeMillis() + 310_000
                while (reply.getString("state") in setOf("pending", "approved", "running") && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500); reply = CompanionPrivilege.request(context, session, Bundle().apply { putString("action", "poll"); putString("requestId", requestId) })
                }
                return response(output, if (reply.getBoolean("ok", false)) 200 else 403, JSONObject().put("exit_code", reply.getInt("exitCode", -1)).put("stdout", reply.getString("stdout").orEmpty()).put("stderr", reply.getString("stderr").orEmpty()).put("error", reply.getString("message").orEmpty()).toString(), "application/json")
            }
            response(output, 403, ack.getString("message") ?: ack.getString("error") ?: "Shell request refused", "application/json")
        }
        private fun response(out: BufferedOutputStream, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 503 -> "Service Unavailable"; else -> "Error" }
            out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); out.write(bytes); out.flush()
        }
    }
}
