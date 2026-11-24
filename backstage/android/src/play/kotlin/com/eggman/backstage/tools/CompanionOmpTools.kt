package com.eggman.backstage.tools

import android.os.Bundle
import com.eggman.backstage.BuildConfig
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.data.AgentEngine
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.omp.CompanionOmpClient
import com.eggman.backstage.omp.OmpConfig
import com.eggman.backstage.omp.OmpJobs
import com.eggman.backstage.ui.settings.BackstageSettingsStore
import org.json.JSONObject

internal fun companionOmpTools(): List<Tool> = listOf(
    CompanionOmpSubagentTool(), CompanionOmpSubagentStatusTool(), CompanionOmpSubagentCancelTool(),
)

internal fun isCompanionOmpToolAvailable(tool: Tool, availability: ToolRegistry.Availability): Boolean? =
    if (tool is CompanionOmpTool) availability.companionCapabilities and CompanionProtocol.CAP_OMP != 0
    else null

private abstract class CompanionOmpTool : Tool {
    protected val store: BackstageSettingsStore get() = Backstage.environment.settings.let { com.eggman.backstage.ui.settings.CoreSettingsStore(it) }
    protected suspend fun call(request: Bundle): JSONObject {
        check(BuildConfig.PLAY_DISTRIBUTION) { "Companion OMP tools are available in the Play app." }
        val reply = CompanionOmpClient.requestConfigured(store, request)
        check(reply.getBoolean("ok", false)) { reply.getString("error") ?: "OMP request failed" }
        return runCatching { JSONObject(reply.getString("result").orEmpty()) }.getOrElse {
            JSONObject().put("state", reply.getString("state") ?: "finished")
                .put("exit", reply.getInt("exit", 0)).put("answer", reply.getString("stdout").orEmpty())
                .put("stderr", reply.getString("stderr").orEmpty())
        }
    }

    protected fun result(data: JSONObject): ToolResult {
        val failed = data.optString("state") in setOf("failed", "cancelled") ||
            (data.has("exit") && !data.isNull("exit") && data.optInt("exit") != 0)
        return ToolResult(data.toString(), isError = failed, data = data)
    }
}

private class CompanionOmpSubagentTool(
    private val autoApprove: () -> Boolean = { Backstage.environment.settings.ompAutoApprove },
    private val defaultModel: () -> String = {
        Backstage.environment.settings.let { "${OmpConfig.providerId(it.provider)}/${it.model()}" }
    },
) : CompanionOmpTool() {
    override val name = "omp_subagent"
    override val description = "Run an OMP coding subagent as the companion's shell user. It can execute commands and change apps or system settings. Each task needs approval unless OMP Auto is enabled. Returns a job id if still running."
    override val schema = Schema.obj(
        "task" to Schema.str("Task for the subagent (maximum 2000 characters)").put("maxLength", 2000),
        "model" to Schema.str("Optional OMP provider/model"),
        "cwd" to Schema.str("Optional absolute working directory"),
        "wait_seconds" to Schema.int("Wait 0..600 seconds (default 120)").put("default", 120),
        required = listOf("task"),
    )
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val task = args.string("task")
        require(task.length <= 2_000) { "OMP task must be at most 2000 characters so approval can show it in full" }
        OmpJobs.validateTask(task)
        val model = args.optString("model") ?: defaultModel()
        val cwd = args.optString("cwd")
        listOfNotNull(model, cwd).forEach { require(it.length <= 4_000 && '\u0000' !in it) { "Invalid model or cwd" } }
        require(cwd == null || cwd.startsWith('/')) { "cwd must be absolute" }
        val wait = (args.optInt("wait_seconds") ?: 120).coerceIn(0, 600)
        if (!autoApprove()) {
            val question = "Run OMP subagent as the companion shell user: `${task}`? Model: $model; cwd: ${cwd ?: "home"}."
            val hooks = ctx.hooks ?: return ToolResult.error("Refused: OMP subagents need per-task approval. Enable OMP Auto or use execute_task so the phone can ask you.")
            if (!hooks.confirmAlways(question)) return ToolResult.error("Not done: the user did not approve the OMP task. Do not retry it unless the user asks again.")
        }
        val request = CompanionOmpClient.action("subagent_start") {
            putString("task", task); putString("model", model); putString("cwd", cwd.orEmpty()); putInt("waitSeconds", wait)
        }
        return result(call(request))
    }
}

private class CompanionOmpSubagentStatusTool : CompanionOmpTool() {
    override val name = "omp_subagent_status"
    override val description = "Read an OMP subagent job's state, waiting up to 0..600 seconds (default 60). Finished jobs include recent output."
    override val schema = Schema.obj("job_id" to Schema.str("OMP job id"), "wait_seconds" to Schema.int("Wait seconds; default 60").put("default", 60), required = listOf("job_id"))
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val id = OmpJobs.validateId(args.string("job_id"))
        val request = CompanionOmpClient.action("subagent_status") {
            putString("jobId", id); putInt("waitSeconds", (args.optInt("wait_seconds") ?: 60).coerceIn(0, 600))
        }
        return result(call(request))
    }
}

private class CompanionOmpSubagentCancelTool : CompanionOmpTool() {
    override val name = "omp_subagent_cancel"
    override val description = "Cancel a running OMP subagent and its process group. Changes it already made are not undone."
    override val schema = Schema.obj("job_id" to Schema.str("OMP job id"), required = listOf("job_id"))
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val id = OmpJobs.validateId(args.string("job_id"))
        return result(call(CompanionOmpClient.action("subagent_cancel") { putString("jobId", id) }))
    }
}
