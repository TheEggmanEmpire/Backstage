package com.eggman.backstage.tools

import com.eggman.backstage.init.Backstage
import com.eggman.backstage.omp.OmpHarness
import com.eggman.backstage.omp.OmpKeyBroker
import com.eggman.backstage.omp.OmpShellRelay
import com.eggman.backstage.omp.OmpSkillScripts
import com.eggman.backstage.omp.OmpSkills
import com.eggman.backstage.privileged.ShellResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

class RunSkillScriptTool(
    private val enabled: () -> Boolean = { Backstage.environment.settings.skillScriptsEnabled },
    private val autoApprove: () -> Boolean = { Backstage.environment.settings.skillScriptsAutoApprove },
    private val unavailable: (Boolean) -> String? = { shellUser ->
        OmpHarness.startUnavailableReason() ?: if (!shellUser)
            "App-UID skill execution is unavailable: the installed OMP runtime is shell-owned. Explicitly select shell_user=true to run as uid 2000 with shell approval." else null
    },
    private val execute: suspend (String, String, List<String>, Boolean, Int, ToolContext) -> ShellResult = { skill, file, argv, shellUser, seconds, ctx ->
        val runtime = checkNotNull(OmpHarness.runtime) { "OMP runtime unavailable" }
        val settings = Backstage.environment.settings
        val relay = OmpShellRelay(
            enabled = { settings.termuxShellEnabled && settings.ompTermuxEnabled && settings.skillScriptsEnabled },
            autoApprove = { settings.termuxShellAutoApprove },
            // External confirmations queue behind the active task. Use its own hooks to avoid deadlocking it.
            confirm = { ctx.hooks?.confirmAlways(it) == true },
            generation = { settings.ompTermuxToken.hashCode() },
        )
        OmpSkillScripts(runtime::run, relay::run, runtime.libDir).execute(skill, file, argv, shellUser, seconds)
    },
) : Tool {
    override val name = "run_skill_script"
    override val description = "Execute an enabled skill's bundled .sh/.bash (sh), .js/.mjs/.ts (bun), or .py (python3 if installed) on this phone in OMP's POSIX runtime. Runs real code with the selected UID's powers. Disabled until Run skill scripts is enabled in Settings; Ask requires per-call approval. The installed runtime is shell-only: select shell_user=true, with additional Termux shell enablement/approval and its 300s cap."
    override val schema = Schema.obj(
        "skill" to Schema.str("Enabled skill name"),
        "file" to Schema.str("Script path inside that skill"),
        "args" to Schema.arr("Optional literal script arguments (never shell syntax)", Schema.str("Argument")),
        "shell_user" to Schema.bool("Run as uid 2000 through the shell relay (default false)"),
        "timeout_seconds" to Schema.int("Timeout, default 120, clamp 5..600; shell relay maximum 300").put("default", 120),
        required = listOf("skill", "file"),
    )
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        if (!enabled()) return ToolResult.error("Skill script execution is disabled. Enable Run skill scripts in Settings → Skills.")
        val shellUser = args.bool("shell_user", false)
        unavailable(shellUser)?.let { return ToolResult.error(it) }
        val store = ctx.services.skills ?: return ToolResult.error("No skills are available.")
        val name = args.string("skill")
        val path = try { store.resolveFile(name, args.string("file")).first }
            catch (e: IllegalArgumentException) { return ToolResult.error(e.message.orEmpty()) }
        val skill = store.get(name)?.takeIf { it.enabled } ?: return ToolResult.error("No enabled skill named \"$name\".")
        if (OmpSkills.safePath(path) != path) return ToolResult.error("Script path is too deep for OMP skill sync.")
        val interpreter = OmpSkillScripts.interpreter(path)
        val argv = if (!args.has("args")) emptyList() else {
            val array = args.optArray("args") ?: throw ToolArgumentException("args must be a string array")
            (0 until array.length()).map { array.get(it) as? String ?: throw ToolArgumentException("args must contain strings only") }
        }
        require(argv.size <= 100 && argv.sumOf { it.length } <= 1_500 && argv.none { '\u0000' in it }) { "Script arguments exceed 100 items / 1500 characters or contain NUL" }
        val seconds = (args.optInt("timeout_seconds") ?: 120).coerceIn(5, 600)
        val effective = if (shellUser) seconds.coerceAtMost(300) else seconds
        // What the user approves is this exact content: a GitHub sync or edit while the prompt waits must not swap it.
        val askUser = !autoApprove()
        val approvedVersion = if (askUser) skillVersion(store, name, path) ?: return ToolResult.error("The skill's files could not be read.") else null
        if (askUser) {
            val hooks = ctx.hooks ?: return ToolResult.error("Refused: skill scripts need per-task approval. Use execute_task so the phone can ask you, or enable skill scripts Auto in Settings → Skills.")
            val question = "Run skill script $name/$path with $interpreter as ${if (shellUser) "shell (uid 2000)" else "app user"}, timeout ${effective}s? Arguments: ${org.json.JSONArray(argv)}. This runs real code on the phone."
            if (!hooks.confirmAlways(question)) return ToolResult.error("Not done: the user did not approve the skill script. Do not retry unless the user asks again.")
        }
        // Approval may have waited; current enablement/availability and file containment still apply.
        currentCoroutineContext().ensureActive()
        if (!enabled() || store.get(name)?.enabled != true) return ToolResult.error("Skill script execution or the skill was disabled before execution.")
        unavailable(shellUser)?.let { return ToolResult.error(it) }
        store.resolveFile(name, path)
        if (askUser && skillVersion(store, name, path) != approvedVersion)
            return ToolResult.error("Not done: the skill changed while waiting for approval (for example a GitHub sync). Ask again to approve the new version.")
        val directory = OmpSkills.directoryNames(store.enabled().map { it.name }).getValue(skill.name)
        val result = execute(directory, path, argv, shellUser, seconds, ctx)
        val data = JSONObject().put("exit_code", result.code).put("stdout", OmpKeyBroker.boundedOutput(result.out))
            .put("stderr", OmpKeyBroker.boundedOutput(result.err)).put("shell_user", shellUser).put("effective_timeout_seconds", effective)
        return ToolResult(data.toString(), isError = !result.ok, data = data)
    }

    /** Every file's path, size and modification time plus the script's SHA-256: changes when anything is replaced. */
    private fun skillVersion(store: com.eggman.backstage.skills.SkillStore, name: String, path: String): String? = runCatching {
        val skill = store.get(name) ?: return null
        val listing = skill.files.joinToString("\n") { f ->
            val file = store.resolveFile(name, f.path).second
            "${f.path}\u0000${file.length()}\u0000${file.lastModified()}"
        }
        val script = store.resolveFile(name, path).second
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        script.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
        listing + "\n" + digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
