package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.AgentController
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.host.DesktopHost

/**
 * The two "stop" controls:
 *
 * - [stopAgent]: the chat panel's Stop and the notification's Stop — cancels the running task and clears the queue;
 *   the desktop keeps running.
 * - [stopAgentAndDesktop]: the popup's X (after its confirmation) and the notification's Stop desktop — cancels the
 *   agent FIRST, blocks the agent from starting a desktop again for [RESTART_BLOCK_MS] (a task that is already in
 *   flight cannot bring it back), then ends the desktop session (display, wake locks and the session notification
 *   follow from there).
 */
object SessionStop {
    const val RESTART_BLOCK_MS = 8_000L
    const val BLOCKED = "The user just stopped the desktop; it is not started again for this task."

    @Volatile private var blockedUntilMs = 0L

    /** Whether there is anything for [stopAgent] to stop: a task runs, waits, is paused or queued. */
    fun agentActive(state: AgentState): Boolean = state.busy || state.current?.paused == true

    fun stopAgent(controller: AgentController?) {
        runCatching { controller?.cancelAll() }
    }

    fun stopAgentAndDesktop(controller: AgentController?, host: DesktopHost?, nowMs: Long = System.currentTimeMillis()) {
        stopAgent(controller)
        blockedUntilMs = nowMs + RESTART_BLOCK_MS
        runCatching { host?.stopDesktop() }
        AgentKeepAwake.reevaluate()
    }

    /** True for a short while after [stopAgentAndDesktop]: nothing may start the desktop again on its own. */
    fun autoStartBlocked(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < blockedUntilMs

    /** Tests. */
    internal fun reset() { blockedUntilMs = 0L }
}
