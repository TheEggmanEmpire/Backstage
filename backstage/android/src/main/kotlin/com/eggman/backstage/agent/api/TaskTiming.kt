package com.eggman.backstage.agent.api

import org.json.JSONObject

/**
 * Where one agent step's time went (local only): waiting for the AI provider ([modelMs], retries included), the
 * tool's own work ([actionMs]) and waiting for the screen to settle afterwards ([settleMs]).
 */
data class StepTiming(val step: Int, val tool: String?, val modelMs: Long, val actionMs: Long, val settleMs: Long) {
    val totalMs: Long get() = modelMs + actionMs + settleMs
}

/** A task's step timings, summed (kept in [TaskSnapshot] and the task history; never leaves the phone). */
data class TaskTiming(
    val steps: Int = 0,
    val modelMs: Long = 0,
    val actionMs: Long = 0,
    val settleMs: Long = 0,
    /** The step that took longest in total. */
    val slowest: StepTiming? = null,
) {
    val totalMs: Long get() = modelMs + actionMs + settleMs

    operator fun plus(s: StepTiming): TaskTiming = TaskTiming(
        steps = steps + 1,
        modelMs = modelMs + s.modelMs.coerceAtLeast(0),
        actionMs = actionMs + s.actionMs.coerceAtLeast(0),
        settleMs = settleMs + s.settleMs.coerceAtLeast(0),
        slowest = if (slowest == null || s.totalMs > slowest.totalMs) s else slowest,
    )

    /** One line for the task details, e.g. "AI 12.4 s · actions 3.1 s · screen settling 2.0 s (9 steps, avg AI 1.4 s)". */
    fun summary(): String {
        if (steps == 0) return ""
        val avg = modelMs / steps
        val slow = slowest?.let { s ->
            "; slowest: step ${s.step}" + (s.tool?.let { " ($it)" } ?: "") + ", ${secs(s.totalMs)}"
        }.orEmpty()
        return "AI ${secs(modelMs)} · actions ${secs(actionMs)} · screen settling ${secs(settleMs)} " +
            "($steps step${if (steps == 1) "" else "s"}, avg AI ${secs(avg)}$slow)"
    }

    /** The largest share: what to change first (a slow model, slow apps, or long settle waits). */
    fun dominant(): String? = when {
        steps == 0 || totalMs == 0L -> null
        modelMs >= actionMs && modelMs >= settleMs -> "ai"
        actionMs >= settleMs -> "actions"
        else -> "settle"
    }

    fun toJson(): JSONObject = JSONObject().put("steps", steps).put("modelMs", modelMs).put("actionMs", actionMs).put("settleMs", settleMs)
        .putOpt("slowest", slowest?.let { JSONObject().put("step", it.step).putOpt("tool", it.tool).put("modelMs", it.modelMs).put("actionMs", it.actionMs).put("settleMs", it.settleMs) })

    companion object {
        fun secs(ms: Long): String = if (ms < 10_000) String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0) else "${(ms + 500) / 1000} s"

        fun fromJson(o: JSONObject?): TaskTiming? = o?.let {
            TaskTiming(
                steps = it.optInt("steps"), modelMs = it.optLong("modelMs"), actionMs = it.optLong("actionMs"), settleMs = it.optLong("settleMs"),
                slowest = it.optJSONObject("slowest")?.let { s ->
                    StepTiming(s.optInt("step"), s.optString("tool").takeIf { t -> s.has("tool") && t.isNotEmpty() }, s.optLong("modelMs"), s.optLong("actionMs"), s.optLong("settleMs"))
                },
            )
        }
    }
}
