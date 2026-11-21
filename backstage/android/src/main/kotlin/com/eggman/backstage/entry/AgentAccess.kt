package com.eggman.backstage.entry

import com.eggman.backstage.agent.api.AgentController
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Agents
import com.eggman.backstage.agent.api.TaskHandle
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only way  surfaces reach the agent core and the desktop host.
 *
 * `Agents.controller` is a lateinit installed by the core's init provider; surfaces can be
 * created before it (or in tests without it), so every access goes through [controller] which
 * returns null instead of throwing.
 */
object AgentAccess {

    /** Test seam: when set, used instead of [Agents.controller]. */
    @Volatile
    internal var override: AgentController? = null

    /** Test seam for the desktop host. */
    @Volatile
    internal var hostOverride: DesktopHost? = null

    fun controller(): AgentController? =
        override ?: try {
            Agents.controller
        } catch (_: UninitializedPropertyAccessException) {
            null
        }

    fun host(): DesktopHost? = hostOverride ?: DesktopHosts.current

    private val emptyState = MutableStateFlow(AgentState())

    /** Agent state flow, or a constant empty state while no controller is installed. */
    fun state(): StateFlow<AgentState> = controller()?.state ?: emptyState

    /** Submit a task; null when the agent core is not installed (caller reports the problem). */
    fun submit(request: TaskRequest): TaskHandle? {
        val prompt = request.prompt.trim()
        if (prompt.isEmpty()) return null
        return controller()?.submit(request.copy(prompt = prompt))
    }

    fun cancel(taskId: String? = null) {
        controller()?.cancel(taskId)
    }

    fun answer(text: String) {
        controller()?.answer(text)
    }

    /** Trusted UI only: bind the answer to the task and the complete question that this surface displayed. */
    fun answerFromUi(text: String, taskId: String, completeQuestionText: String): Boolean {
        val controller = controller() ?: return false
        if (controller is com.eggman.backstage.agent.AgentControllerImpl) {
            return controller.answerFromUi(text, taskId, completeQuestionText)
        }
        // Embedders may supply another controller implementation of the public API.
        val current = controller.state.value.current ?: return false
        if (current.id != taskId || current.status != com.eggman.backstage.agent.api.TaskStatus.WAITING_FOR_USER ||
            current.question?.text != completeQuestionText) return false
        controller.answer(text)
        return true
    }

    /** Hold the running task before its next step (the user wants to look or add something). */
    fun pause(taskId: String? = null): Boolean = controller()?.pause(taskId) == true

    fun resume(taskId: String? = null): Boolean = controller()?.resume(taskId) == true

    /** Add an instruction to the running task; it reads it before its next step. */
    fun instruct(taskId: String?, instruction: String): Boolean = controller()?.instruct(taskId, instruction) == true

    /** Let a paused task continue with an extra instruction from the user (blank = plain resume). */
    fun resume(taskId: String?, instruction: String?): Boolean =
        controller()?.resume(taskId, instruction?.trim()?.takeIf { it.isNotEmpty() }) == true
}

/** Process-wide scope for surface work that must outlive a receiver/activity (awaiting results). */
object SurfaceScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
