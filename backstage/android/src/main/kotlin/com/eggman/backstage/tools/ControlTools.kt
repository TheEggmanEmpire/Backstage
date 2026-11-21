package com.eggman.backstage.tools

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.exec.TargetKind

private val NONE = emptySet<TargetKind>()
val PLAN_STATUSES = setOf("pending", "in_progress", "done", "skipped")

class UpdatePlanTool : Tool {
    override val name = "update_plan"
    override val description = "Plan mode checklist: publish the full list of steps with their status. Call it first for multi-step tasks and again whenever a step changes status. Every step must be done or skipped before finish."
    override val schema = Schema.obj(
        "steps" to Schema.arr(
            "All steps in order",
            Schema.obj("title" to Schema.str("Short step"), "status" to Schema.enum("Status", *PLAN_STATUSES.toTypedArray()), required = listOf("title", "status")),
        ),
        required = listOf("steps"),
    )
    override val targets = NONE
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val hooks = ctx.hooks ?: return ToolResult.error("update_plan is only available inside an agent task")
        val arr = args.optArray("steps") ?: throw ToolArgumentException("steps must be an array")
        val steps = PlanArgs.parse(arr)
        hooks.onPlan(steps)
        val done = steps.count { it.status == "done" || it.status == "skipped" }
        val next = steps.firstOrNull { it.status == "in_progress" } ?: steps.firstOrNull { it.status == "pending" }
        return ToolResult("Plan saved ($done/${steps.size} complete)." + (next?.let { " Next: ${it.title}" } ?: " All steps complete — verify and call finish."))
    }
}

class AskUserTool : Tool {
    override val name = "ask_user"
    override val description = "Ask the user for missing information or a choice and wait for the answer. Do not ask for things you can find out yourself."
    override val schema = Schema.obj(
        "question" to Schema.str("The question"),
        "choices" to Schema.arr("Optional short answer options", Schema.str("option")),
        required = listOf("question"),
    )
    override val targets = NONE
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val hooks = ctx.hooks ?: return ToolResult.error("ask_user is only available inside an agent task")
        val answer = hooks.ask(args.string("question"), args.stringList("choices"))
            ?: return ToolResult("No answer: the user is not available for this task. Use a safe default if one is obvious; otherwise finish and explain what information is missing.", isError = true)
        return ToolResult("User answered: $answer")
    }
}

class PendingUserActionTool : Tool {
    override val name = "pending_user_action"
    override val description = "Ask the user to do something only they can do on the device (enter a password or 2FA code, solve a captcha, grant a permission, unlock), then continue when they confirm."
    override val schema = Schema.obj("instruction" to Schema.str("Exactly what the user should do"), required = listOf("instruction"))
    override val targets = NONE
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val hooks = ctx.hooks ?: return ToolResult.error("pending_user_action is only available inside an agent task")
        val a = hooks.ask(args.string("instruction") + "\nTap Continue when you are done.", listOf("Continue", "Cancel"))
            ?: return ToolResult("The user did not respond. Stop and explain in finish what they need to do.", isError = true)
        return if (a.trim().lowercase().let { it.startsWith("cancel") || it.startsWith("no") || it == "stop" })
            ToolResult("The user cancelled. Do not continue this step.", isError = true)
        else ToolResult("The user has done it. Read the screen again and continue.")
    }
}

class ConfirmActionTool : Tool {
    override val name = "confirm_action"
    override val description = "Ask the user to approve an irreversible or outward-facing step (sending a message or email, posting, paying, buying, deleting, changing account/security settings) BEFORE doing it. Describe exactly what will happen. Returns approved or denied."
    override val schema = Schema.obj("action" to Schema.str("Exactly what will happen, e.g. Send \"On my way\" to Tom in WhatsApp"), required = listOf("action"))
    override val targets = NONE
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val hooks = ctx.hooks ?: return ToolResult.error("confirm_action is only available inside an agent task")
        return if (hooks.confirm(args.string("action"))) ToolResult("approved — go ahead with exactly this action.")
        else ToolResult("denied — do NOT perform this action. The task ends here; the user can ask for something else.")
    }
}

class FinishTool : Tool {
    override val name = "finish"
    override val description = "End the task. Call exactly once when the request is fully done (verified on screen) or impossible. The summary is shown/spoken to the user: state the result or answer plainly."
    override val schema = Schema.obj(
        "summary" to Schema.str("Result for the user (the answer, or what was done / why it failed)"),
        "success" to Schema.bool("Whether the goal was achieved"),
        required = listOf("summary"),
    )
    override val targets = NONE
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val hooks = ctx.hooks ?: return ToolResult.error("finish is only available inside an agent task")
        hooks.onFinish(args.optString("summary") ?: "", args.bool("success", true))
        return ToolResult("Task finished.", terminal = true)
    }
}
