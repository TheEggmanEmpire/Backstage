package com.eggman.backstage.ui

import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/**
 * What the text field of the chat and of the bubble does with the text (pure, unit-tested): answer the
 * agent's question, resume a paused task with an added instruction (AgentController.resume(taskId,
 * instruction)), or start a new task.
 */
object Composer {
    enum class Mode { ANSWER, RESUME, NEW_TASK }

    fun mode(current: TaskSnapshot?): Mode = when {
        current?.status == TaskStatus.WAITING_FOR_USER -> Mode.ANSWER
        current?.status == TaskStatus.RUNNING && current.paused -> Mode.RESUME
        else -> Mode.NEW_TASK
    }

    fun placeholder(mode: Mode, listening: Boolean, bubble: Boolean = false): String = when {
        listening -> "Listening…"
        mode == Mode.ANSWER -> if (bubble) "Answer…" else "Answer the agent…"
        mode == Mode.RESUME -> "Add an instruction, then send to resume…"
        else -> if (bubble) "Tell the agent…" else "Ask or command…"
    }
}

/** Choices of the per-task time limit in Settings (minutes; 0 = no limit). */
object TimeLimitChoices {
    val options = listOf(0, 5, 10, 15, 30, 60, 120)

    fun label(min: Int): String = when {
        min <= 0 -> "No limit"
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "$min min"
    }

    /** One line saying what the setting does. */
    fun summary(min: Int): String =
        if (min <= 0) "Tasks you start run until they finish, fail or you stop them."
        else "A task you start stops after ${label(min)} of work; pauses and questions to you are not counted."
}
