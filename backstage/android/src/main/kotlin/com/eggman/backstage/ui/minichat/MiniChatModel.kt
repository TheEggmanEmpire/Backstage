package com.eggman.backstage.ui.minichat

import com.eggman.backstage.agent.MiniStatus
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.notify.SessionMessage
import com.eggman.backstage.notify.TaskNotificationModel
import com.eggman.backstage.ui.chat.Transcript

/**
 * The chat panel the desktop popup's chat button opens at the bottom of the phone screen, as pure rules
 * (MiniChatModelTest). A message goes exactly where an inline reply to the session notification goes
 * ([SessionMessage.route]): idle -> new task, waiting on the shown question -> its answer, paused -> resume with it,
 * running -> an extra instruction. The panel never changes where the desktop shows.
 */
object MiniChatModel {
    const val TITLE = "Chat with the agent"
    const val LOCKED_NOTICE = "Unlock the phone to send."
    const val LOCKED_STOP_NOTICE = "Unlock the phone to stop the agent."
    const val IDLE_STATUS = "Ready: type a task for the agent."
    /** TalkBack label of the Stop button next to Send. */
    const val STOP_LABEL = "Stop the agent"
    const val STOPPED_NOTICE = "Agent stopped. The desktop keeps running."

    /**
     * The Stop button shows while a task runs, waits, is paused or queued. It stops the agent only (running task and
     * queue); the popup's X is what stops the agent AND the desktop. On a locked phone it stops nothing and asks for the
     * unlock ([LOCKED_STOP_NOTICE]).
   */
    fun stopVisible(state: AgentState): Boolean = com.eggman.backstage.agent.SessionStop.agentActive(state)
    const val MAX_LINES = 12
    const val HISTORY_TASKS = 3

    /** One line of the recent conversation. */
    data class Line(val kind: Transcript.Kind, val text: String)

    /** What the user saw when sending: the reply is routed against it like a notification reply. */
    data class Posted(val taskId: String?, val questionKey: String?)

    fun posted(current: TaskSnapshot?): Posted {
        val active = current?.takeUnless { it.status.terminal() }
        return Posted(active?.id, active?.let { TaskNotificationModel.questionKey(it) })
    }

    /** Placeholder of the text field: says what sending does. */
    fun hint(current: TaskSnapshot?): String {
        val s = current?.takeUnless { it.status.terminal() } ?: return "Give the agent a task…"
        return when {
            s.status == TaskStatus.WAITING_FOR_USER -> "Answer the agent…"
            s.paused -> "Add an instruction and resume…"
            else -> "Add an instruction for the running task…"
        }
    }

    /** One status line (the popup caption's wording), or [IDLE_STATUS]. */
    fun status(state: AgentState): String = MiniStatus.format(state).text ?: IDLE_STATUS

    /**
     * Recent conversation: the last finished tasks from [history] (most recent first, as the controller returns it)
     * as prompt + result, then the live [transcript] of the tasks seen since the panel opened; at most [max] lines,
     * the newest last. A task present in both is shown once (live).
   */
    fun lines(history: List<TaskSnapshot>, transcript: Transcript, max: Int = MAX_LINES): List<Line> {
        val live = transcript.entries.mapNotNull { it.taskId }.toSet()
        val past = history.take(HISTORY_TASKS).reversed().filter { it.id !in live }.flatMap { s ->
            listOfNotNull(
                Line(Transcript.Kind.USER, s.request.prompt.trim()),
                s.result?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    Line(if (s.status == TaskStatus.FAILED) Transcript.Kind.ERROR else Transcript.Kind.RESULT, it)
                },
            )
        }
        val now = transcript.entries.map { e ->
            Line(e.kind, if (e.kind == Transcript.Kind.ACTION && e.step > 0) "Step ${e.step}: ${e.text}" else e.text)
        }
        return (past + now).filter { it.text.isNotBlank() }.takeLast(max)
    }

    /** After sending: what happened, or null when nothing is sent. */
    fun notice(route: SessionMessage.Route, locked: Boolean): String? = when (route) {
        SessionMessage.Route.Drop -> if (locked) LOCKED_NOTICE else null
        is SessionMessage.Route.Answer -> "Answer sent."
        is SessionMessage.Route.Instruct -> "Instruction added to the running task."
        is SessionMessage.Route.Resume -> "Instruction added; the task continues."
        is SessionMessage.Route.NewTask -> "Task started."
    }

    /**
     * The panel stays over the phone while the desktop is the popup (MINI), in the background or not running; it closes
     * when the desktop goes full screen (it would sit under it) or, opened from the popup, into the app (the app is open
     * then).
   */
    fun keepOpen(presentation: Presentation, fromBubble: Boolean = false): Boolean = when (presentation) {
        Presentation.MINI, Presentation.HIDDEN, Presentation.NONE -> true
        Presentation.FULL -> false
        // The bubble also shows over the app; only the popup's sheet makes way for the desktop in the app.
        Presentation.IN_APP -> fromBubble
    }

    /** A result the bubble opened by itself closes after this long unless the user touches it. */
    const val PEEK_CLOSE_MS = 5_000L

    private fun TaskStatus.terminal() = this == TaskStatus.SUCCEEDED || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED
}
