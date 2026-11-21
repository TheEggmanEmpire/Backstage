package com.eggman.backstage.privileged

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** A shell wrapper also usable before transports which read stdout and stderr sequentially. */
object BoundedShell {
    const val DEFAULT_MAX_OUTPUT_BYTES = 128 * 1024
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Drain both pipes concurrently into capped files, then publish the results in sequence. The
     * deadline includes inherited pipes; kill each owned process group, never just its shell.
     * Refuse execution if setsid is unavailable instead of starting an unbounded command.
   */
    fun script(
        argv: List<String>, timeoutMs: Long, maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
        tempRoot: String = "/data/local/tmp",
    ): String {
        require(argv.isNotEmpty() && argv.none { '\u0000' in it }) { "Invalid shell arguments" }
        require(maxOutputBytes in 1..DEFAULT_MAX_OUTPUT_BYTES) { "Output limit must be 1..$DEFAULT_MAX_OUTPUT_BYTES bytes" }
        val seconds = (timeoutMs.coerceAtLeast(100) / 1000.0).toString()
        val command = argv.joinToString(" ", transform = ::quote)
        val drain = "head -c $maxOutputBytes; head -c 1 > \"\$1\"; cat > /dev/null"
        // Only a bounded result reaches the Binder reply, even for `yes` or a full stderr pipe.
        return """
            command -v setsid >/dev/null 2>&1 || { echo 'setsid unavailable: cannot bound shell process group' >&2; exit 125; }
            umask 077
            d=${'$'}(mktemp -d ${quote("$tempRoot/backstage-shell.XXXXXX")}) || exit 125
            p=; o=; e=; w=
            stop_group() { [ -z "${'$'}1" ] || { kill -KILL -"${'$'}1" 2>/dev/null || :; kill -KILL "${'$'}1" 2>/dev/null || :; }; }
            cleanup() { stop_group "${'$'}p"; stop_group "${'$'}o"; stop_group "${'$'}e"; stop_group "${'$'}w"; rm -rf "${'$'}d"; }
            trap cleanup EXIT
            trap 'exit 130' INT
            trap 'exit 143' TERM
            mkfifo "${'$'}d/out.pipe" "${'$'}d/err.pipe" || exit 125
            setsid sh -c ${quote(drain)} sh "${'$'}d/out.extra" < "${'$'}d/out.pipe" > "${'$'}d/out" & o=${'$'}!
            setsid sh -c ${quote(drain)} sh "${'$'}d/err.extra" < "${'$'}d/err.pipe" > "${'$'}d/err" & e=${'$'}!
            setsid sh -c ${quote(command)} > "${'$'}d/out.pipe" 2> "${'$'}d/err.pipe" & p=${'$'}!
            setsid sh -c 'sleep "${'$'}1"; : > "${'$'}2/timedout"; for pid in "${'$'}3" "${'$'}4" "${'$'}5"; do kill -KILL -"${'$'}pid" 2>/dev/null || :; kill -KILL "${'$'}pid" 2>/dev/null || :; done' sh $seconds "${'$'}d" "${'$'}p" "${'$'}o" "${'$'}e" >/dev/null 2>&1 & w=${'$'}!
            rc=0
            wait "${'$'}p" || rc=${'$'}?
            stop_group "${'$'}p"
            wait "${'$'}o" 2>/dev/null || :
            wait "${'$'}e" 2>/dev/null || :
            stop_group "${'$'}w"
            cat "${'$'}d/out"
            [ ! -s "${'$'}d/out.extra" ] || printf '\n[stdout truncated at $maxOutputBytes bytes]\n'
            cat "${'$'}d/err" >&2
            [ ! -s "${'$'}d/err.extra" ] || printf '\n[stderr truncated at $maxOutputBytes bytes]\n' >&2
            if [ -f "${'$'}d/timedout" ]; then printf '\n[command timed out]\n' >&2; rc=124; fi
            exit "${'$'}rc"
        """.trimIndent()
    }
}

/** Shizuku UserService body, running as shell (or root). */
class ShellUserService : IShellService.Stub() {
    override fun destroy() = exitProcess(0)

    override fun exec(argv: Array<String>, timeoutMs: Long): String = try {
        val process = ProcessBuilder("sh", "-c", BoundedShell.script(argv.toList(), timeoutMs)).start()
        // The wrapper caps the bytes too. Capture under a monitor so even exceptional cleanup
        // cannot race StringBuilder mutation with Binder serialization.
        val out = Capture(process.inputStream)
        val err = Capture(process.errorStream)
        val outThread = Thread(out, "Backstage-shell-stdout").apply { isDaemon = true; start() }
        val errThread = Thread(err, "Backstage-shell-stderr").apply { isDaemon = true; start() }
        try {
            val finished = process.waitFor(timeoutMs.coerceAtLeast(100) + 3_000, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            outThread.join(1_000); errThread.join(1_000)
            val code = if (finished) process.exitValue() else 124
            "$code\n${out.text()}\n\u0000${err.text()}"
        } finally {
            process.destroyForcibly()
        }
    } catch (t: Throwable) {
        if (t is InterruptedException) Thread.currentThread().interrupt()
        Log.w("ShellUserService", "exec failed", t)
        "127\n\n\u0000${t.message}"
    }

    private class Capture(private val input: InputStream) : Runnable {
        private val bytes = ByteArrayOutputStream()
        override fun run() {
            runCatching {
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        synchronized(bytes) {
                            val remaining = BoundedShell.DEFAULT_MAX_OUTPUT_BYTES + 256 - bytes.size()
                            if (remaining > 0) bytes.write(buffer, 0, minOf(n, remaining))
                        }
                    }
                }
            }
        }
        fun text(): String = synchronized(bytes) { bytes.toString(Charsets.UTF_8.name()) }
    }
}
