package com.eggman.backstage.omp

import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShellResult
import com.eggman.backstage.tools.AdbCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Refusals are HTTP errors, never successful command results with a fabricated exit code. */
class OmpShellRefused(message: String, val status: Int = 403) : IllegalStateException(message)

data class ShellResultDTO(val exitCode: Int, val stdout: String, val stderr: String)

/** No dependency on omp installation, engine selection, provider configuration or agent Auto settings. */
class OmpShellRelay(
    private val enabled: () -> Boolean,
    private val autoApprove: () -> Boolean,
    private val available: () -> Boolean = { Privileged.available() },
    private val confirm: suspend (String) -> Boolean,
    /** Pairing identity at the time of the request; a change (token rotation) before execution revokes it. */
    private val generation: () -> Int = { 0 },
    /** Runs the already-normalized command as the shell user. The real path wraps it to bound time and output. */
    private val exec: suspend (String, Long) -> ShellResult = { command, timeoutMs ->
        val seconds = (timeoutMs / 1000).coerceAtLeast(1)
        Privileged.exec(*wrapperArgv(command, seconds).toTypedArray(), timeoutMs = timeoutMs + 10_000)
    },
) {
    suspend fun run(raw: String, timeoutSeconds: Long): ShellResultDTO {
        // normalize also enforces the exact adb_command accessibility-settings write refusal.
        val command = AdbCommand.normalize(raw)
        checkAccess()
        val gen = generation()
        if (!autoApprove() && !confirm("Run Termux command as shell (uid 2000):\n$command"))
            throw OmpShellRefused("Not done: the user did not approve the Termux shell command. Do not retry unless the user asks again.")
        // Approval may have waited in the controller queue; enablement, privilege or the pairing can change meanwhile.
        checkAccess()
        if (generation() != gen) throw OmpShellRefused("Termux pairing changed before the command ran; run the setup line again.")
        currentCoroutineContext().ensureActive()
        val result = exec(command, timeoutSeconds.coerceIn(5L, 300L) * 1_000L)
        return ShellResultDTO(result.code, result.out, result.err)
    }

    private fun checkAccess() {
        if (!enabled()) throw OmpShellRefused("Termux shell access is disabled. Enable Shell access (uid 2000) in Backstage's Termux settings.")
        if (!available()) throw OmpShellRefused("Termux shell commands need privileged access: start Shizuku/Stellar or Dextop wireless debugging.", 503)
    }

    companion object {
        const val CAP_BYTES = 256 * 1024
        /** $1 = command, $2 = timeout seconds, $3 = max bytes per stream. Output goes to files (reads never
         *  deadlock on a stderr-heavy command), the wall-clock limit kills the process, each stream is capped. */
        private const val WRAPPER = """
d=${'$'}(mktemp -d "${'$'}{TMPDIR:-/data/local/tmp}/bsh.XXXXXX") || exit 127
trap 'rm -rf "${'$'}d"' EXIT
if command -v timeout >/dev/null 2>&1; then
  timeout "${'$'}2" sh -c "${'$'}1" >"${'$'}d/o" 2>"${'$'}d/e"; rc=${'$'}?
else
  sh -c "${'$'}1" >"${'$'}d/o" 2>"${'$'}d/e" & p=${'$'}!
  ( sleep "${'$'}2"; kill "${'$'}p" 2>/dev/null ) & k=${'$'}!
  wait "${'$'}p"; rc=${'$'}?
  kill "${'$'}k" 2>/dev/null
fi
head -c "${'$'}3" "${'$'}d/o"
head -c "${'$'}3" "${'$'}d/e" >&2
exit ${'$'}rc
"""
        /** sh -c WRAPPER with $0=bsh so the arbitrary command is $1 and never re-quoted into a script. */
        fun wrapperArgv(command: String, timeoutSeconds: Long, capBytes: Int = CAP_BYTES): List<String> =
            listOf("sh", "-c", WRAPPER, "bsh", command, timeoutSeconds.toString(), capBytes.toString())
    }
}
