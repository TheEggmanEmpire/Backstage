package com.eggman.backstage.tools

import com.eggman.backstage.init.Backstage
import com.eggman.backstage.omp.OmpConfig
import com.eggman.backstage.omp.OmpHarness
import com.eggman.backstage.omp.OmpJobs
import org.json.JSONObject

class OmpSubagentTool(
    private val autoApprove: () -> Boolean = { Backstage.environment.settings.ompAutoApprove },
    private val unavailable: () -> String? = { OmpHarness.startUnavailableReason() },
    private val start: suspend (String, String?, String?, Int) -> JSONObject = { task, model, cwd, wait ->
        val jobs = OmpHarness.jobs
        jobs.status(jobs.create(task, model, cwd), wait)
    },
    private val defaultModel: () -> String = {
        OmpConfig.mainModelId(Backstage.environment.settings)
    },
) : Tool {
    override val name = "omp_subagent"
    override val description = "Run an omp coding subagent as this device's shell user (uid 2000), in a Termux userland under proot. It can execute arbitrary shell commands and change system settings or apps. Each task needs user approval unless OMP Auto is enabled. Use only for requested shell/coding work; its own subagents share the same provider config. Returns a job id if still running."
    override val schema = Schema.obj(
        "task" to Schema.str("Task for the subagent (maximum 2000 characters)").put("maxLength", 2000), "model" to Schema.str("Optional omp provider/model"),
        "cwd" to Schema.str("Optional absolute working directory inside proot"),
        "wait_seconds" to Schema.int("Wait 0..600 seconds (default 120)").put("default", 120), required = listOf("task"))
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val task = args.string("task")
        require(task.length <= 2_000) { "OMP task must be at most 2000 characters so the approval can show it in full" }
        OmpJobs.validateTask(task)
        val model = args.optString("model") ?: defaultModel()
        val cwd = args.optString("cwd")
        listOfNotNull(model, cwd).forEach { require(it.length <= 4_000 && '\u0000' !in it) { "Invalid model or cwd" } }
        require(cwd == null || cwd.startsWith('/')) { "cwd must be absolute" }
        val wait = (args.optInt("wait_seconds") ?: 120).coerceIn(0, 600)
        unavailable()?.let { return ToolResult.error(it) }
        if (!autoApprove()) {
            val question = "Run OMP subagent as shell: `${task}`? Model: $model; cwd: ${cwd ?: "home"}."
            val hooks = ctx.hooks ?: return ToolResult.error("Refused: OMP subagents need per-task approval. Enable OMP Auto in Backstage settings or use execute_task so the phone can ask you.")
            if (!hooks.confirmAlways(question)) return ToolResult.error("Not done: the user did not approve $question Do not retry it unless the user asks again.")
        }
        val result = start(task, model, cwd, wait)
        return ompResult(result)
    }
}

private fun ompResult(result: JSONObject): ToolResult {
    val failed = result.optString("state") in setOf("failed", "cancelled") || (result.has("exit") && !result.isNull("exit") && result.optInt("exit") != 0)
    return ToolResult(result.toString(), isError = failed, data = result)
}
class OmpSubagentStatusTool : Tool {
    override val name = "omp_subagent_status"
    override val description = "Read an omp subagent job's state, waiting up to 0..600 seconds (default 60). Finished jobs include the last 16 KB of output."
    override val schema = Schema.obj("job_id" to Schema.str("OMP job id"), "wait_seconds" to Schema.int("Wait seconds; default 60").put("default", 60), required = listOf("job_id"))
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val id = OmpJobs.validateId(args.string("job_id"))
        OmpHarness.unavailableReason()?.let { return ToolResult.error(it) }
        return ompResult(OmpHarness.jobs.status(id, args.optInt("wait_seconds") ?: 60))
    }
}
class OmpSubagentCancelTool : Tool {
    override val name = "omp_subagent_cancel"
    override val description = "Cancel a running omp subagent job and its process group. Changes it already made are not undone."
    override val schema = Schema.obj("job_id" to Schema.str("OMP job id"), required = listOf("job_id"))
    override val targets = emptySet<com.eggman.backstage.exec.TargetKind>()
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val id = OmpJobs.validateId(args.string("job_id"))
        OmpHarness.unavailableReason()?.let { return ToolResult.error(it) }
        val result = OmpHarness.jobs.cancel(id)
        return ToolResult(result.toString(), data = result)
    }
}
