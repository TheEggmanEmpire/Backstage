package com.eggman.backstage.voice

import com.eggman.backstage.agent.Approval
import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/** What a spoken command after the wake word means, given the agent's current task (pure). */
sealed interface VoiceCommand {
    /** Reply to the pending question; confirm questions get the canonical [Answers] word. */
    data class Answer(val text: String) : VoiceCommand
    data object Stop : VoiceCommand
    data class NewTask(val text: String) : VoiceCommand

    companion object {
        /**
         * While the phone is locked only [Stop] is obeyed: anyone holding a locked phone can speak, so a new task or an
         * answer ("yes" to a payment) waits for the owner to unlock it.
       */
        fun allowedWhileLocked(c: VoiceCommand): Boolean = c == Stop

        /**
         * With [desktopWhileLocked] (the user turned on "Keep the desktop running while locked" AND turned off "Pause
         * while the phone is locked" for the wake word) a new task is accepted too; it runs on the desktop only, never on
         * the phone, and its result is not read aloud while locked. Answers still need an unlock.
       */
        fun allowedWhileLocked(c: VoiceCommand, desktopWhileLocked: Boolean): Boolean =
            allowedWhileLocked(c) || (desktopWhileLocked && c is NewTask)

        private val stopWords = Regex("^(stop|cancel|abort|halt|stopp|abbrechen|arrête|annule)( (it|the task|that|now|please))?[.!]*$", RegexOption.IGNORE_CASE)

        fun classify(spoken: String, current: TaskSnapshot?): VoiceCommand {
            val text = spoken.trim()
            val active = current != null && current.status in setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_USER, TaskStatus.QUEUED)
            if (active && stopWords.matches(text)) return Stop
            val question = current?.question
            if (current?.status == TaskStatus.WAITING_FOR_USER && question != null) {
                return Answer(if (question.confirm) (if (Approval.parse(text)) Answers.APPROVE else Answers.DENY) else text)
            }
            return NewTask(text)
        }
    }
}
