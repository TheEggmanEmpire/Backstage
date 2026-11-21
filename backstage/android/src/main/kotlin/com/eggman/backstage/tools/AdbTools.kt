package com.eggman.backstage.tools

import com.eggman.backstage.init.Backstage
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.privileged.ShellResult
import org.json.JSONObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Device-side command normalization; the remainder is deliberately preserved as shell syntax. */
internal object AdbCommand {
    fun normalize(raw: String): String {
        if (raw.length > 4_000) throw ToolArgumentException("Command must be at most 4000 characters")
        if ('\u0000' in raw) throw ToolArgumentException("Command must not contain NUL")
        var command = raw.trim()
        val adb = Regex("^adb(?:\\s+-d)?(?:\\s+|$)").find(command)
        if (adb != null) {
            command = command.substring(adb.range.last + 1).trimStart()
            val verb = command.takeWhile { !it.isWhitespace() }
            val rest = command.drop(verb.length).trimStart()
            command = when (verb) {
                "shell" -> rest
                "install", "uninstall" -> "pm $verb" + if (rest.isEmpty()) "" else " $rest"
                "reboot", "logcat" -> command
                "reboot-bootloader" -> "reboot bootloader" + if (rest.isEmpty()) "" else " $rest"
                else -> throw ToolArgumentException("'adb $verb' is a host-side adb verb that cannot run on the device. Use a device shell command; host file transfers and connections need a computer.")
            }
        }
        if (command.isBlank()) throw ToolArgumentException("Command must not be empty")
        // Accept the short install/uninstall forms used in user requests as well.
        if (Regex("^(install|uninstall)(?:\\s|$)").containsMatchIn(command)) command = "pm $command"
        if (Regex("^logcat(?:\\s|$)").containsMatchIn(command) &&
            !Regex("(?:^|\\s)-(?:d(?:\\s|$)|t(?:\\s|[0-9]))").containsMatchIn(command)) {
            command += " -d"
        }
        refuseAccessibilitySettingsWrite(command)
        if (command.length > 4_000) throw ToolArgumentException("Normalized command must be at most 4000 characters")
        return command
    }

    /** This tool never toggles accessibility, even with per-command approval or ADB Auto. */
    private fun refuseAccessibilitySettingsWrite(command: String) {
        // Strip shell quoting/escapes so quoted keys, quote concatenation, cmd settings, shell wrappers and
        // content-provider bindings/where clauses receive the same policy. Inspect the whole compound command.
        val plain = command.filterNot { it.code in setOf(34, 39, 92) }.lowercase(java.util.Locale.ROOT)
        val settings = Regex("(?:^|[^a-z0-9_])settings(?:[^a-z0-9_]|$)").containsMatchIn(plain)
        val key = Regex("enabled_accessibility_services|accessibility_enabled").containsMatchIn(plain)
        val write = Regex("(?:^|[^a-z0-9_])(put|delete|reset|insert|update|call)(?:[^a-z0-9_]|$)").containsMatchIn(plain)
        val resetsSecure = Regex("""\breset\s+secure\b""").containsMatchIn(plain)
        // SQL predicates and provider call arguments can affect either protected key without spelling its name.
        // Reject secure-table mutations conservatively; reads and ordinary settings writes remain available.
        val secureContentWrite = Regex("""\bcontent\b""").containsMatchIn(plain) &&
            "content://settings/secure" in plain && write
        if (settings && (key && write || resetsSecure || secureContentWrite)) {
            throw ToolArgumentException("Accessibility settings cannot be changed through ADB commands. Enable or disable accessibility services yourself in Android Settings; Backstage never enables the companion.")
        }
    }

}

/** Raw shell is exposed only here, behind the user's per-command approval or explicit Auto setting. */
class AdbCommandTool(
    private val exec: suspend (String, Long) -> ShellResult = { command, timeout ->
        Privileged.exec("sh", "-c", command, timeoutMs = timeout)
    },
    private val autoApprove: () -> Boolean = { Backstage.environment.settings.adbAutoApprove },
    private val available: () -> Boolean = { Privileged.available() },
) : Tool {
    override val name = "adb_command"
    override val description = "Run an ADB-style shell command on this device as the shell user (uid 2000), through Shizuku, Stellar or Dextop wireless debugging. Use when the user requests an ADB/shell action or it is clearly the right means, such as installing or uninstalling an APK; never bypass UI tools for ordinary app navigation. Accepts device shell commands and adb shell/install/uninstall/reboot/logcat forms; APK paths must already be on the device. Each command needs user approval unless ADB Auto is enabled in Backstage settings."
    override val schema = Schema.obj(
        "command" to Schema.str("Device shell command, e.g. pm list packages -3, adb install /sdcard/Download/app.apk, or adb shell settings get global adb_enabled (maximum 1000 characters)").put("maxLength", 1000),
        "timeout_seconds" to Schema.int("Command timeout in seconds (default 60; clamped to 5..300)").put("default", 60),
        required = listOf("command"),
    )
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        // The shared normalizer also serves generated OMP shell scripts; this tool has a smaller approval limit.
        val raw = args.string("command")
        if (raw.length > 1_000) throw ToolArgumentException("ADB command must be at most 1000 characters so approval can show it in full")
        val command = AdbCommand.normalize(raw)
        if (command.length > 1_000) throw ToolArgumentException("Normalized ADB command must be at most 1000 characters")
        val timeoutMs = (args.optInt("timeout_seconds") ?: 60).coerceIn(5, 300) * 1_000L
        if (!available()) return ToolResult.error("ADB commands need privileged access: start Shizuku/Stellar or Dextop wireless debugging.")
        if (!autoApprove()) {
            val question = "Run ADB command as shell: `${command}`?"
            val hooks = ctx.hooks ?: return ToolResult.error("Refused: ADB commands need per-command approval. Enable ADB Auto in Backstage settings or use execute_task so the phone can ask you.")
            if (!hooks.confirmAlways(question)) return ToolResult.error("Not done: the user did not approve $question Do not retry it unless the user asks again.")
        }
        // A shell expansion can hide protected key names from the literal check above. Observe the actual state.
        val before = accessibilitySettings() ?: return ToolResult.error("Refused: could not read accessibility settings before the command.")
        var changed = false
        var verified = true
        val restoreFailures = mutableListOf<String>()
        val result = try {
            exec(command, timeoutMs)
        } finally {
            withContext(NonCancellable) {
                val after = accessibilitySettings()
                verified = after != null
                for ((key, value) in before) {
                    if (after == null || after[key] != value) {
                        if (after != null) changed = true
                        val restore = if (value == null) "settings delete secure $key"
                            else "settings put secure $key ${shellQuote(value)}"
                        if (runCatching { exec(restore, 10_000L).ok }.getOrDefault(false).not()) restoreFailures += key
                    }
                }
                if (changed || !verified) {
                    val restored = accessibilitySettings()
                    for ((key, value) in before) {
                        if (restored == null || restored[key] != value) {
                            if (key !in restoreFailures) restoreFailures += key
                        }
                    }
                }
            }
        }
        if (restoreFailures.isNotEmpty()) return ToolResult.error("The ADB command changed accessibility settings or could not be checked; restoring them failed for ${restoreFailures.joinToString()}. Check Android Accessibility Settings.")
        if (changed) return ToolResult.error("Blocked: the command tried to change accessibility settings. The previous values were restored. Backstage never enables or disables accessibility through ADB commands.")
        if (!verified) return ToolResult.error("The command ran, but its accessibility changes could not be verified. The previous values were restored; do not retry it.")
        // Share the budget fairly, leaving unused room to the other stream; keep the diagnostic tail.
        val stdoutBudget = (12_000 - result.err.length.coerceAtMost(6_000)).coerceAtMost(result.out.length)
        val stderrBudget = 12_000 - stdoutBudget
        val data = JSONObject().put("exit_code", result.code)
            .put("stdout", result.out.takeLast(stdoutBudget))
            .put("stderr", result.err.takeLast(stderrBudget))
        return ToolResult(data.toString(), isError = !result.ok, data = data)
    }

    private suspend fun accessibilitySettings(): Map<String, String?>? {
        val values = linkedMapOf<String, String?>()
        for (key in listOf("enabled_accessibility_services", "accessibility_enabled")) {
            val result = runCatching { exec("settings get secure $key", 10_000L) }.getOrNull() ?: return null
            if (!result.ok) return null
            val value = result.out.trimEnd('\r', '\n')
            values[key] = value.takeUnless { it == "null" }
        }
        return values
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

}
