package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus

/** Pure description of the notification for a task snapshot (rendered by [Notifications]). */
data class TaskNotificationModel(
    val channel: String,
    val title: String,
    val text: String,
    val ongoing: Boolean,
    val progress: Pair<Int, Int>?,
    val actions: List<Action>,
    val alert: Boolean,
) {
    sealed interface Action {
        /**
         * The button answers the agent (approve a payment, pick a choice, type a reply), so it works only after the
         * phone is unlocked: from the lock screen anyone holding the phone could press it. Stop stays usable.
       */
        val requiresUnlock: Boolean get() = false

        data object Stop : Action
        data object Reply : Action { override val requiresUnlock get() = true }
        data object Approve : Action { override val requiresUnlock get() = true }
        data object Deny : Action { override val requiresUnlock get() = true }
        data class Choice(val text: String) : Action { override val requiresUnlock get() = true }
        data object Open : Action
    }

    /** Title shown on the lock screen instead of [title]: no task text, question, amount or result. */
    val publicTitle: String
        get() = when (channel) {
            NotifyChannels.QUESTIONS -> "Backstage needs your answer"
            NotifyChannels.RESULTS -> "Backstage task finished"
            else -> "Backstage is working on a task"
        }

    /** Text shown on the lock screen instead of [text]. */
    val publicText: String
        get() = if (ongoing && channel != NotifyChannels.QUESTIONS) "Unlock to see the details." else "Unlock to see it."

    companion object {
        const val MAX_TEXT = 400
        private const val REVIEW_IN_APP = "Open Backstage to review the complete question."

        internal fun reviewText(text: String, maxChars: Int = MAX_TEXT): String =
            text.take((maxChars - REVIEW_IN_APP.length - 2).coerceAtLeast(0)) + "\n\n" + REVIEW_IN_APP

        /**
         * Results of these sources do not ring: the user did not just ask for them (automations, the MCP client that
         * gets the result itself, schedules with their own notification) or watched them in the app (chat).
       */
        val SILENT_RESULT_SOURCES = setOf(Source.CHAT, Source.TASKER, Source.BROADCAST, Source.SCHEDULE, Source.MCP)

        /** Identifies the question a notification shows (null when the task waits for nothing). */
        fun questionKey(s: TaskSnapshot): String? =
            s.question?.takeIf { s.status == TaskStatus.WAITING_FOR_USER }?.let { "${s.id}:${it.text.hashCode()}" }

        /**
         * An answer button applies only to the task and question it is posted for, while that question is open. Every
         * button names its question; an intent without one (not built by [Notifications]) never answers.
       */
        fun answerApplies(current: TaskSnapshot?, taskId: String?, questionKey: String?): Boolean {
            if (current == null || current.status != TaskStatus.WAITING_FOR_USER) return false
            if (taskId == null || current.id != taskId) return false
            return questionKey != null && questionKey(current) == questionKey
        }

        fun from(s: TaskSnapshot): TaskNotificationModel {
            val prompt = s.request.prompt.lineSequence().firstOrNull().orEmpty().take(80)
            return when (s.status) {
                TaskStatus.QUEUED -> TaskNotificationModel(
                    NotifyChannels.STATUS, "Queued", prompt, ongoing = true, progress = null,
                    actions = listOf(Action.Stop), alert = false,
                )
                TaskStatus.RUNNING -> {
                    val current = s.plan.firstOrNull { it.status == "in_progress" }?.title
                    val done = s.plan.count { it.status == "done" || it.status == "skipped" }
                    val detail = buildString {
                        if (s.plan.isNotEmpty()) append("Step ${minOf(done + 1, s.plan.size)}/${s.plan.size}: ${current ?: "working"}")
                        else append(s.lastAction ?: "Working…")
                    }
                    TaskNotificationModel(
                        NotifyChannels.STATUS, "Working: $prompt", detail.take(MAX_TEXT), ongoing = true,
                        progress = if (s.maxSteps > 0) s.step.coerceAtMost(s.maxSteps) to s.maxSteps else null,
                        actions = listOf(Action.Stop), alert = false,
                    )
                }
                TaskStatus.WAITING_FOR_USER -> {
                    val q = s.question
                    val needsReview = q?.confirm == true && q.text.length > MAX_TEXT
                    val actions = buildList {
                        if (needsReview) {
                            add(Action.Open)
                        } else if (q?.confirm == true) {
                            add(Action.Approve); add(Action.Deny)
                        } else if (q != null && q.choices.isNotEmpty()) {
                            // Notifications show at most three actions; keep Reply for free text.
                            q.choices.take(2).forEach { add(Action.Choice(it)) }
                            add(Action.Reply)
                        } else add(Action.Reply)
                        if (size < 3) add(Action.Stop)
                    }
                    TaskNotificationModel(
                        NotifyChannels.QUESTIONS,
                        if (q?.confirm == true) "Approve this action?" else "The agent needs your input",
                        if (needsReview) reviewText(q!!.text) else (q?.text ?: "Open Backstage to answer.").take(MAX_TEXT),
                        ongoing = true, progress = null,
                        actions = actions, alert = true,
                    )
                }
                TaskStatus.SUCCEEDED -> TaskNotificationModel(
                    NotifyChannels.RESULTS, "Done: $prompt", (s.result ?: "Finished.").take(MAX_TEXT),
                    ongoing = false, progress = null, actions = listOf(Action.Open), alert = s.request.source !in SILENT_RESULT_SOURCES,
                )
                TaskStatus.FAILED -> TaskNotificationModel(
                    NotifyChannels.RESULTS, "Failed: $prompt", (s.result ?: "The task could not be completed.").take(MAX_TEXT),
                    ongoing = false, progress = null, actions = listOf(Action.Open), alert = s.request.source !in SILENT_RESULT_SOURCES,
                )
                TaskStatus.CANCELLED -> TaskNotificationModel(
                    NotifyChannels.RESULTS, "Stopped: $prompt", s.result?.take(MAX_TEXT) ?: "Cancelled.",
                    ongoing = false, progress = null, actions = listOf(Action.Open), alert = false,
                )
            }
        }

        val TaskStatus.terminal: Boolean
            get() = this == TaskStatus.SUCCEEDED || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED
    }
}

object NotifyChannels {
    const val STATUS = "bs_agent_status"
    const val QUESTIONS = "bs_agent_questions"
    const val RESULTS = "bs_agent_results"
    const val WAKE_WORD = "bs_wake_word"
    const val WAKE_WORD_ALERTS = "bs_wake_word_alerts"
    const val SCHEDULE = "bs_schedule"
    const val BUBBLE = "bs_bubble"
    const val AUTOMATION = "bs_automation"
}
