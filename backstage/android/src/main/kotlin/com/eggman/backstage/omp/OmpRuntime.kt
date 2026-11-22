package com.eggman.backstage.omp

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.content.Context
import com.eggman.backstage.data.Settings
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShellResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal fun shellQuote(value: String): String {
    require('\u0000' !in value)
    return "'" + value.replace("'", "'\\''") + "'"
}

class OmpRuntime(private val context: Context, val payload: OmpPayload, private val settings: Settings) {
    companion object { const val ROOT = "/data/local/tmp/backstage-omp"; const val INSTALLER = "/data/local/tmp/backstage-omp-installer" }
    private val mutation = Mutex()
    private val brokerWrite = Mutex()
    private val brokerInstaller = OmpBrokerInstaller(OmpHarness::issueBrokerClaim, OmpHarness::revokeBrokerClaim)
    private var brokerWritten: String? = null
    val libDir: String get() = context.applicationInfo.nativeLibraryDir
    @Volatile var installed = false; private set
    @Volatile var version = ""; private set
    @Volatile var message = "Runtime not checked"; private set
    fun unavailableReason(): String? = when {
        !payload.available -> "OMP not available in this build."
        !settings.ompEnabled -> "Enable OMP harness in Backstage settings."
        !Privileged.available() -> "OMP needs privileged access: start Shizuku/Stellar or Dextop wireless debugging."
        !installed -> "Install or repair OMP harness in Backstage settings."
        else -> null
    }
    suspend fun status() {
        if (!payload.available) { installed = false; message = "OMP runtime payload is missing"; return }
        if (!Privileged.available()) { installed = false; message = "Needs Shizuku/Stellar or Dextop wireless debugging"; return }
        val r = Privileged.exec("sh", "-c", "test -x ${shellQuote("$ROOT/home/.bun/bin/bun")} && test -f ${shellQuote("$ROOT/run.sh")} && cat ${shellQuote("$ROOT/installed.json")}")
        installed = r.ok && runCatching { JSONObject(r.out).getInt("payload") == 1 }.getOrDefault(false)
        if (installed) {
            val v = run(listOf("omp", "--version"), 30_000)
            version = if (v.ok) v.out.trim().take(150) else "Runtime check failed"
            message = if (v.ok) "Installed" else "Installed; omp does not start (${v.code}): ${v.err.ifBlank { v.out }.trim().takeLast(200)}"
        } else { version = ""; message = "Not installed" }
    }
    /**
     * Starts install.sh detached (stdio to files) and polls its log: the privileged transports wait for a command's
     * output to close and have no timeout, so a multi-minute install must never hold their pipes.
   */
    suspend fun install(onProgress: (String) -> Unit = {}): ShellResult = mutation.withLock {
        if (PLAY_DISTRIBUTION) return@withLock ShellResult(126, "", "OMP is available in the GitHub build.")
        check(payload.available) { "OMP not available in this build" }
        check(Privileged.available()) { "OMP needs privileged access" }
        val apk = shellQuote(context.applicationInfo.sourceDir)
        val work = shellQuote(INSTALLER)
        val launch = "set -e; umask 077; w=$work; rm -rf \"\$w\"; mkdir -p \"\$w\"; " +
            "/system/bin/unzip -p $apk assets/omp/device/install.sh > \"\$w/install.sh\"; test -s \"\$w/install.sh\"; " +
            "if command -v setsid >/dev/null 2>&1; then s=setsid; else s=; fi; " +
            "\$s /system/bin/sh -c 'BACKSTAGE_OMP_REPAIR=1 /system/bin/sh \"\$0/install.sh\" \"\$1\" \"\$2\" \"\$3\" > \"\$0/log\" 2>&1; " +
            "echo \$? > \"\$0/exit.tmp\"; mv \"\$0/exit.tmp\" \"\$0/exit\"' \"\$w\" ${shellQuote(ROOT)} $apk ${shellQuote(libDir)} " +
            "</dev/null >/dev/null 2>&1 &"
        message = "Installing: starting"; onProgress(message)
        val started = Privileged.exec("sh", "-c", launch, timeoutMs = 60_000)
        if (!started.ok) {
            message = "Installation could not start (${started.code}): ${started.err.ifBlank { started.out }.takeLast(300)}"
            return@withLock started
        }
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        var misses = 0
        while (true) {
            kotlinx.coroutines.delay(1_500)
            val poll = Privileged.exec("sh", "-c", "w=$work; if [ -f \"\$w/exit\" ]; then printf 'exit:'; cat \"\$w/exit\"; fi; tail -n 30 \"\$w/log\" 2>/dev/null; :")
            if (!poll.ok) {
                if (++misses >= 20) { message = "Installation status unavailable: privileged access lost"; return@withLock ShellResult(125, "", message) }
                continue
            }
            misses = 0
            val lines = poll.out.lines()
            val exit = lines.firstOrNull { it.startsWith("exit:") }?.removePrefix("exit:")?.trim()?.toIntOrNull()
            val log = lines.filterNot { it.startsWith("exit:") }
            log.lastOrNull { it.startsWith("step: ") }?.let { message = "Installing: ${it.removePrefix("step: ")}"; onProgress(message) }
            if (exit != null) {
                val tail = log.filterNot { it.startsWith("step: ") }.joinToString("\n").trim()
                return@withLock if (exit == 0) {
                    installed = true; brokerWritten = null
                    runCatching { writeBroker() }
                    status(); onProgress(message)
                    ShellResult(0, tail, "")
                } else {
                    message = "Installation failed ($exit): ${tail.takeLast(300).ifBlank { "no output" }}"
                    onProgress(message)
                    ShellResult(exit, "", tail)
                }
            }
            if (System.currentTimeMillis() > deadline) {
                message = "Installation timed out after 20 minutes"; onProgress(message)
                return@withLock ShellResult(124, "", message)
            }
        }
        @Suppress("UNREACHABLE_CODE") ShellResult(1, "", "")
    }
    suspend fun writeBroker() = brokerWrite.withLock {
        if (!installed || !settings.ompEnabled || !Privileged.available()) return@withLock
        val token = settings.ompHarnessToken
        val spec = "port=${settings.ompBrokerPort}\ntoken=$token\n"
        if (brokerWritten == spec) return@withLock
        val r = brokerInstaller.write(ROOT, libDir, settings.ompBrokerPort, spec)
        check(r.ok) { "Could not write harness broker pairing" }
        brokerWritten = spec
    }
    suspend fun run(argv: List<String>, timeoutMs: Long = 60_000): ShellResult =
        Privileged.exec("sh", "$ROOT/run.sh", ROOT, libDir, "--", *argv.toTypedArray(), timeoutMs = timeoutMs)
    suspend fun update(): ShellResult = mutation.withLock {
        if (PLAY_DISTRIBUTION) return@withLock ShellResult(126, "", "OMP is available in the GitHub build.")
        check(unavailableReason() == null) { unavailableReason().orEmpty() }
        val r = run(listOf("omp-termux", "install", "latest"), 600_000)
        // bun global install may replace bin/omp with its own symlink. Remove it before restoring
        // our wrapper, otherwise redirection would overwrite the package's CLI through that symlink.
        val wrapper = shellQuote("$ROOT/home/.bun/bin/omp")
        val restore = Privileged.exec("sh", "-c", "set -e; umask 077; " +
            "/system/bin/unzip -p ${shellQuote(context.applicationInfo.sourceDir)} assets/omp/device/omp > $wrapper.new; " +
            "test -s $wrapper.new; chmod 700 $wrapper.new; rm -f $wrapper; mv $wrapper.new $wrapper")
        check(restore.ok) { "OMP update ended, but wrapper repair failed; use Repair" }
        status()
        r
    }
}
