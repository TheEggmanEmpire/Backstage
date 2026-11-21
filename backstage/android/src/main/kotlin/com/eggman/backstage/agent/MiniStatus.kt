package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.extras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One-line agent status for the desktop mini window caption (pure; see [MiniStatusBridge]). */
object MiniStatus {
    const val MAX_LENGTH = 90

    data class Line(val text: String?, val terminal: Boolean)

    fun format(state: AgentState): Line {
        val s = state.current ?: return Line(null, false)
        val text = when (s.status) {
            TaskStatus.QUEUED -> "Queued: ${short(s.request.prompt)}"
            TaskStatus.RUNNING -> when {
                s.paused -> "Paused: ${short(s.request.prompt)}"
                else -> buildString {
                    if (s.step > 0) append("Step ").append(s.step).append('/').append(s.maxSteps).append(" · ")
                    append(short(s.lastAction ?: currentPlanStep(s) ?: s.request.prompt))
                }
            }
            TaskStatus.WAITING_FOR_USER ->
                if (s.question?.confirm == true) "Needs approval: ${short(s.question.text)}"
                else "Question: ${short(s.question?.text ?: "the agent needs your answer")}"
            TaskStatus.SUCCEEDED -> "Done: ${short(s.result ?: s.request.prompt)}"
            TaskStatus.FAILED -> "Failed: ${short(s.result ?: s.request.prompt)}"
            TaskStatus.CANCELLED -> "Stopped"
        }
        val terminal = s.status == TaskStatus.SUCCEEDED || s.status == TaskStatus.FAILED || s.status == TaskStatus.CANCELLED
        return Line(text.take(MAX_LENGTH), terminal)
    }

    private fun currentPlanStep(s: TaskSnapshot): String? = s.plan.firstOrNull { it.status == "in_progress" }?.title

    private fun short(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= MAX_LENGTH) line else line.take(MAX_LENGTH - 1).trimEnd() + "…"
    }
}

/**
 * Pushes the agent state into the desktop mini window caption ([com.eggman.backstage.host.DesktopHostExtras]),
 * so a glance at the floating desktop shows what the agent is doing. Terminal lines clear after [clearAfterMs].
 * A no-op without the Dextop glue.
 */
object MiniStatusBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    fun start(state: StateFlow<AgentState>, clearAfterMs: Long = 10_000L, sink: (String?) -> Unit = ::push): Job {
        job?.cancel()
        return scope.launch {
            state.map { MiniStatus.format(it) }.distinctUntilChanged().collectLatest { line ->
                sink(line.text)
                if (line.terminal && line.text != null) {
                    delay(clearAfterMs)
                    sink(null)
                }
            }
        }.also { job = it }
    }

    private fun push(text: String?) {
        runCatching { DesktopHosts.extras()?.setMiniStatus(text) }
    }
}
