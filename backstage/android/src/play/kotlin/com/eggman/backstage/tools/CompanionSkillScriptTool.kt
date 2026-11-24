package com.eggman.backstage.tools

import android.os.Bundle
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.omp.OmpKeyBroker
import com.eggman.backstage.omp.OmpSkillScripts
import com.eggman.backstage.omp.OmpSkills
import com.eggman.backstage.skills.SkillStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class CompanionSkillScriptTool(
    private val enabled: () -> Boolean = { Backstage.environment.settings.skillScriptsEnabled },
    private val autoApprove: () -> Boolean = { Backstage.environment.settings.skillScriptsAutoApprove },
    private val request: () -> ((Bundle) -> Bundle)? = { companionPrivilegeRequest(capability) },
) : Tool {
    override val name = "run_skill_script"
    override val description = "Run an enabled skill's shell, JavaScript, TypeScript or Python script as the shell user through Backstage-play-agent. Backstage's Run skill scripts approval setting applies first. The companion asks its owner to approve the script. JavaScript, TypeScript and Python require the companion's OMP runtime and their interpreters."
    override val schema = Schema.obj(
        "skill" to Schema.str("Enabled skill name"),
        "file" to Schema.str("Script path inside the skill"),
        "args" to Schema.arr("Literal script arguments", Schema.str("Argument")),
        "shell_user" to Schema.bool("Run as shell UID 2000").put("default", true),
        "timeout_seconds" to Schema.int("Timeout in seconds (5–300)").put("default", 120),
        required = listOf("skill", "file"),
    )
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val agentOnly = true
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        if (!enabled()) return ToolResult.error("Enable Run skill scripts in Backstage settings.")
        if (!args.bool("shell_user", true)) return ToolResult.error("Backstage-play-agent runs skill scripts as shell (uid 2000).")
        val store = ctx.services.skills ?: return ToolResult.error("No skills are available.")
        val name = args.string("skill")
        val rawPath = args.string("file")
        val path = withContext(Dispatchers.IO) { store.resolveFile(name, rawPath).first }
        val interpreter = OmpSkillScripts.interpreter(path)
        val argv = if (!args.has("args")) emptyList() else {
            val array = args.optArray("args") ?: throw ToolArgumentException("args must be a string array")
            (0 until array.length()).map { array.get(it) as? String ?: throw ToolArgumentException("args must contain strings only") }
        }
        require(argv.size <= 100 && argv.sumOf { it.length } <= 1_500 && argv.none { '\u0000' in it }) {
            "Script arguments exceed 100 items / 1500 characters or contain NUL"
        }
        val seconds = (args.optInt("timeout_seconds") ?: 120).coerceIn(5, 300)
        val archive = withContext(Dispatchers.IO) { snapshot(store, name, path) }
        if (!autoApprove()) {
            if (ctx.hooks?.confirmAlways("Run $name/$path with $interpreter as shell (uid 2000), timeout ${seconds}s? Arguments: ${JSONArray(argv)}.") != true) {
                return ToolResult.error("Skill script was not approved in Backstage.")
            }
            if (!archive.contentEquals(withContext(Dispatchers.IO) { snapshot(store, name, path) })) {
                return ToolResult.error("The skill changed while waiting for approval. Approve the new version before running it.")
            }
        }
        currentCoroutineContext().ensureActive()
        if (!enabled()) return ToolResult.error("Skill script execution was disabled before the request.")
        val result = runCompanionPrivilegeRequest(request, seconds) {
            putString("operation", "skill_script")
            putString("argument", name)
            putString("path", path)
            putStringArrayList("args", ArrayList(argv))
            putInt("timeoutSeconds", seconds)
            putByteArray("archive", archive)
        }
        val code = result.exitCode ?: return ToolResult.error(result.message)
        val data = JSONObject().put("exit_code", code)
            .put("stdout", OmpKeyBroker.boundedOutput(result.stdout))
            .put("stderr", OmpKeyBroker.boundedOutput(result.stderr))
            .put("shell_user", true).put("effective_timeout_seconds", seconds)
        return ToolResult(data.toString(), isError = code != 0, data = data)
    }

    private fun snapshot(store: SkillStore, name: String, entrypoint: String): ByteArray = SkillStore.withFilesLock(store.root) {
        val skill = store.get(name)?.takeIf { it.enabled } ?: throw ToolArgumentException("No enabled skill named \"$name\".")
        require(skill.files.any { it.path == entrypoint } && skill.files.size <= 256) { "Skill has too many files or is missing the script." }
        val output = ByteArrayOutputStream()
        var remaining = CompanionProtocol.MAX_SKILL_UNPACKED_BYTES
        ZipOutputStream(output).use { archive ->
            for (entry in skill.files) {
                require(OmpSkills.safePath(entry.path) == entry.path) { "Invalid path inside skill." }
                val file = store.resolveFile(name, entry.path).second
                val bytes = file.inputStream().use { it.readNBytes(remaining + 1) }
                require(bytes.size <= remaining) { "Skill files exceed the companion size limit." }
                remaining -= bytes.size
                archive.putNextEntry(ZipEntry(entry.path).apply { time = 0 })
                archive.write(bytes)
                archive.closeEntry()
                require(output.size() <= CompanionProtocol.MAX_SKILL_ARCHIVE_BYTES) { "Skill archive exceeds the companion size limit." }
            }
        }
        require(output.size() <= CompanionProtocol.MAX_SKILL_ARCHIVE_BYTES) { "Skill archive exceeds the companion size limit." }
        output.toByteArray()
    }

    companion object {
        const val capability = CompanionProtocol.CAP_SHELL or CompanionProtocol.CAP_SKILL_SCRIPTS
    }
}
