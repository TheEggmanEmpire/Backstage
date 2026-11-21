package com.eggman.backstage.ui

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/** Short, user-facing status lines shared by chat, bubble, tile and notifications. */
object StatusText {

    fun isActive(s: TaskSnapshot?): Boolean =
        s != null && (s.status == TaskStatus.QUEUED || s.status == TaskStatus.RUNNING || s.status == TaskStatus.WAITING_FOR_USER)

    fun currentStep(s: TaskSnapshot): String? = s.plan.firstOrNull { it.status == "in_progress" }?.title

    fun planProgress(s: TaskSnapshot): Pair<Int, Int>? =
        if (s.plan.isEmpty()) null else s.plan.count { it.status == "done" || it.status == "skipped" } to s.plan.size

    fun line(state: AgentState): String {
        val s = state.current ?: return if (com.eggman.backstage.agent.ReadinessCheck.setupProblems(state.ready).isNotEmpty()) "Setup needed" else "Ready"
        val queued = if (state.queueSize > 0) " (+${state.queueSize} queued)" else ""
        return when (s.status) {
            TaskStatus.QUEUED -> "Queued$queued"
            TaskStatus.RUNNING -> if (s.paused) "Paused$queued" else {
                val step = currentStep(s)
                val p = planProgress(s)
                when {
                    step != null && p != null -> "${p.first + 1}/${p.second} $step$queued"
                    s.lastAction != null -> "${s.lastAction}$queued"
                    else -> "Working… step ${s.step}$queued"
                }
            }
            TaskStatus.WAITING_FOR_USER -> if (s.question?.confirm == true) "Needs approval" else "Waiting for your answer"
            TaskStatus.SUCCEEDED -> "Done"
            TaskStatus.FAILED -> "Failed"
            TaskStatus.CANCELLED -> "Stopped"
        }
    }

    fun elapsed(s: TaskSnapshot, nowMs: Long): String {
        val end = s.finishedAtMs ?: nowMs
        val sec = ((end - s.startedAtMs) / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }
}
