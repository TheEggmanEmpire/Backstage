package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.notify.TaskNotificationModel.Companion.terminal

/**
 * The ONE persistent Backstage notification (requirement E), as pure data: shown while the desktop runs, a task is
 * active or the MCP server serves. It says what the agent is doing, offers "Message the agent" (inline reply), Stop and
 * Popup/Background, and a tap opens the app. Rendered by [SessionNotification]; unit-tested in SessionNotificationModelTest.
 */
data class SessionNotificationModel(
    val show: Boolean,
    val title: String,
    val text: String,
    val progress: Pair<Int, Int>?,
    val actions: List<Action>,
    /** The task and question an inline reply is written for (see [SessionMessage.route]). */
    val taskId: String?,
    val questionKey: String?,
    /** The part of the question actually visible in [text]; shortened text cannot approve the full question. */
    val displayedQuestion: String? = null,
) {
    sealed interface Action {
        /** Acting on the agent or drawing over the screen needs an unlocked phone; stopping a task does not. */
        val requiresUnlock: Boolean get() = true

        data object Message : Action
        data object StopTask : Action { override val requiresUnlock get() = false }
        data object StopDesktop : Action
        data class Present(val presentation: Presentation) : Action
        data object StopMcp : Action { override val requiresUnlock get() = false }
    }

    companion object {
        const val MAX_TEXT = 400
        const val PUBLIC_TITLE = "Backstage is running"
        const val PUBLIC_TEXT = "Unlock to see what the agent is doing."
        const val MESSAGE_LABEL = "Message the agent"
        const val STOP_LABEL = "Stop"
        const val STOP_DESKTOP_LABEL = "Stop desktop"
        const val POPUP_LABEL = "Popup"
        const val BACKGROUND_LABEL = "Background"
        const val STOP_MCP_LABEL = "Stop MCP server"

        val HIDDEN_MODEL = SessionNotificationModel(false, "", "", null, emptyList(), null, null)

        /** Phone mode: the app does not use DeX; the desktop keeps running in the background. */
        const val PHONE_MODE_LINE = "DeX: in the background while the agent works on the phone"

        fun desktopLine(p: Presentation, phoneMode: Boolean = false): String? = if (phoneMode && p == Presentation.HIDDEN) PHONE_MODE_LINE else when (p) {
            Presentation.FULL -> "Desktop: full screen"
            Presentation.MINI -> "Desktop: popup window"
            Presentation.HIDDEN -> "Desktop: running in the background"
            Presentation.IN_APP -> "Desktop: playing in the app"
            Presentation.NONE -> null
        }

        /** What the popup button does: the popup goes to the background, anything else comes back as the popup. */
        fun toggleTarget(p: Presentation): Presentation? = when (p) {
            Presentation.MINI, Presentation.FULL -> Presentation.HIDDEN
            Presentation.HIDDEN, Presentation.IN_APP -> Presentation.MINI
            Presentation.NONE -> null
        }

        private fun detail(s: TaskSnapshot): String {
            val prompt = s.request.prompt.lineSequence().firstOrNull().orEmpty().take(80)
            val status = when {
                s.status == TaskStatus.WAITING_FOR_USER -> s.question?.text ?: "Waiting for you"
                s.status == TaskStatus.QUEUED -> "Queued"
                s.paused -> "Paused"
                s.plan.isNotEmpty() -> {
                    val done = s.plan.count { it.status == "done" || it.status == "skipped" }
                    val current = s.plan.firstOrNull { it.status == "in_progress" }?.title ?: "working"
                    "Step ${minOf(done + 1, s.plan.size)}/${s.plan.size}: $current"
                }
                else -> s.lastAction ?: "Working…"
            }
            return if (prompt.isEmpty()) status else "$prompt — $status"
        }

        /** The current target (the chat's chips), shown so a reply's destination is never a surprise. */
        fun targetLine(t: com.eggman.backstage.agent.api.Target?): String? = when (t) {
            com.eggman.backstage.agent.api.Target.DESKTOP -> "Messages go to: DeX desktop"
            com.eggman.backstage.agent.api.Target.PHONE -> "Messages go to: Phone"
            com.eggman.backstage.agent.api.Target.AUTO -> "Messages go to: Auto"
            null -> null
        }

        fun from(
            state: AgentState,
            presentation: Presentation,
            mcpLine: String? = null,
            target: com.eggman.backstage.agent.api.Target? = null,
            phoneMode: Boolean = false,
        ): SessionNotificationModel {
            val task = state.current?.takeUnless { it.status.terminal }
            val desktop = presentation != Presentation.NONE
            if (task == null && !desktop && mcpLine == null) return HIDDEN_MODEL
            val title = when {
                task?.status == TaskStatus.WAITING_FOR_USER -> "Backstage needs your answer"
                task?.paused == true -> "Backstage is paused"
                task != null -> "Backstage is working"
                desktop -> "Backstage desktop is running"
                else -> "Backstage"
            }
            val rawText = buildList {
                if (task != null) add(detail(task)) else if (desktop) add("Message the agent to give it a task.")
                if (state.queueSize > 0) add("${state.queueSize} queued")
                desktopLine(presentation, phoneMode)?.let(::add)
                targetLine(target)?.let(::add)
                mcpLine?.let(::add)
            }.joinToString("\n")
            val question = task?.question?.takeIf { task.status == TaskStatus.WAITING_FOR_USER }
            val questionOffset = if (question != null) detail(task).length - question.text.length else 0
            val needsReview = question?.confirm == true && rawText.take(MAX_TEXT).drop(questionOffset).length < question.text.length
            val text = if (needsReview) TaskNotificationModel.reviewText(rawText, MAX_TEXT) else rawText.take(MAX_TEXT)
            val displayedQuestion = question?.let { text.drop(questionOffset).take(it.text.length) }
            val actions = buildList {
                add(Action.Message)
                when {
                    task != null -> add(Action.StopTask)
                    desktop -> add(Action.StopDesktop)
                }
                toggleTarget(presentation)?.let { add(Action.Present(it)) }
                if (mcpLine != null && size < 3) add(Action.StopMcp)
            }
            val progress = task?.takeIf { it.status == TaskStatus.RUNNING && it.maxSteps > 0 }
                ?.let { it.step.coerceAtMost(it.maxSteps) to it.maxSteps }
            return SessionNotificationModel(
                show = true, title = title, text = text, progress = progress, actions = actions,
                taskId = task?.id, questionKey = task?.let { TaskNotificationModel.questionKey(it) },
                displayedQuestion = displayedQuestion,
            )
        }
    }
}

/** Where an inline reply typed into the session notification goes (pure; SessionNotificationModelTest). */
object SessionMessage {
    sealed interface Route {
        data object Drop : Route
        data class Answer(val text: String) : Route
        data class Instruct(val taskId: String, val text: String) : Route
        data class Resume(val taskId: String, val text: String) : Route
        data class NewTask(val text: String) : Route
    }

    /**
     * [postedTaskId]/[postedQuestion]: what the notification showed when the user typed. Nothing counts while the phone
     * is locked (backstop for setAuthenticationRequired). Idle: a new task. Waiting on the question the user saw: the
     * answer. Running (or waiting on a question the user did not see yet): an extra instruction. Paused: resumes with it.
   */
    /** How long a reply delivered while the keyguard is still going away waits for the unlock. */
    const val UNLOCK_GRACE_MS = 3_000L

    /** Polls [locked] until it reads false (true) or [UNLOCK_GRACE_MS] passed (false). */
    suspend fun awaitUnlock(stepMs: Long = 150L, graceMs: Long = UNLOCK_GRACE_MS, locked: () -> Boolean): Boolean {
        var waited = 0L
        while (locked()) {
            if (waited >= graceMs) return false
            kotlinx.coroutines.delay(stepMs)
            waited += stepMs
        }
        return true
    }

    fun route(current: TaskSnapshot?, postedTaskId: String?, postedQuestion: String?, locked: Boolean, text: String?): Route {
        val message = text?.trim().orEmpty()
        if (locked || message.isEmpty()) return Route.Drop
        if (current == null || current.status.terminal) return Route.NewTask(message)
        if (TaskNotificationModel.answerApplies(current, postedTaskId, postedQuestion)) return Route.Answer(message)
        if (current.paused) return Route.Resume(current.id, message)
        return Route.Instruct(current.id, message)
    }
}
