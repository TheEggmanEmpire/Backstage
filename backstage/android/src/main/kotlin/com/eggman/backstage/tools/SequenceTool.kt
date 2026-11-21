package com.eggman.backstage.tools

import com.eggman.backstage.agent.api.PlanStep
import org.json.JSONArray

/**
 * act_sequence: several predictable, low-risk UI actions in one model turn (typing a number on a keypad,
 * filling a form, a known menu path). The agent loop runs the steps itself (each through the same guards as a single
 * call, one action of the budget each) and returns one observation at the end; [run] only answers calls from outside
 * a task.
 */
class ActSequenceTool : Tool {
    override val name = NAME
    override val description = "Run up to $MAX_STEPS predictable UI actions in order and see the screen once at the end: typing a number on a keypad, " +
        "filling several fields, a menu path you know. Allowed: ${ALLOWED.joinToString()}. Indices refer to the current listing; the " +
        "sequence stops early when a step fails, an element no longer matches the listing, or (stop_on_change, default true) another app or " +
        "window comes up. Never use it for steps whose outcome you need to see first."
    override val schema = Schema.obj(
        "steps" to Schema.arr("The actions in order", Schema.obj(
            "tool" to Schema.enum("Tool to run", *ALLOWED.toTypedArray()),
            "args" to org.json.JSONObject().put("type", "object").put("description", "That tool's arguments, e.g. {\"index\":4}"),
            required = listOf("tool"),
        )),
        "stop_on_change" to Schema.bool("Stop when another app or window comes to the front (default true)"),
        Schema.target,
        required = listOf("steps"),
    )
    override val targets = BOTH
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult =
        ToolResult.error("act_sequence runs only inside an agent task; call the steps one by one.")

    companion object {
        const val NAME = "act_sequence"
        const val MAX_STEPS = 8
        /** Low-risk input only: no launches, no confirmations, nothing irreversible. */
        val ALLOWED = listOf("tap", "type_text", "press_key", "swipe", "scroll", "desktop_shortcut", "desktop_key_combo", "desktop_scroll_wheel")
    }
}

/** Plan step lists (update_plan and the optional `plan` argument of acting tools in plan mode). */
object PlanArgs {
    fun parse(arr: JSONArray): List<PlanStep> {
        val steps = (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw ToolArgumentException("step $i must be an object")
            val status = o.optString("status", "pending").lowercase().replace(' ', '_').let { if (it == "completed") "done" else it }
            if (status !in PLAN_STATUSES) throw ToolArgumentException("step $i: status must be one of $PLAN_STATUSES")
            PlanStep(o.optString("title").ifBlank { throw ToolArgumentException("step $i has no title") }, status)
        }
        if (steps.isEmpty()) throw ToolArgumentException("steps is empty")
        return steps
    }

    val schema = Schema.arr(
        "Plan mode: the full checklist with updated statuses, applied before this action (saves a separate update_plan call)",
        Schema.obj("title" to Schema.str("Short step"), "status" to Schema.enum("Status", *PLAN_STATUSES.toTypedArray()), required = listOf("title", "status")),
    )
}
