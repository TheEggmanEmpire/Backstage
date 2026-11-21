package com.eggman.backstage.notify

import com.eggman.backstage.agent.api.Answers
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.eggman.backstage.R
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.schedule.ScheduledTask

/** Channels + builders shared by every  surface. */
object Notifications {
    const val ID_TASK_STATUS = 0x4253_0001
    const val ID_WAKE_WORD = 0x4253_0002
    const val ID_BUBBLE = 0x4253_0003
    const val ID_AUTOMATION = 0x4253_0004
    const val ID_WAKE_WORD_ALERT = 0x4253_0005
    private const val ID_RESULT_BASE = 0x4253_1000
    private const val ID_SCHEDULE_BASE = 0x4253_2000

    const val ACTION_STOP = "com.eggman.backstage.notify.STOP"
    const val ACTION_REPLY = "com.eggman.backstage.notify.REPLY"
    const val ACTION_ANSWER = "com.eggman.backstage.notify.ANSWER"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_ANSWER = "answer"
    /** Which question an answer button belongs to (hash of its text); a stale button never answers a newer one. */
    const val EXTRA_QUESTION = "question_key"
    /** The exact question text included in this notification, never reconstructed from a later snapshot. */
    const val EXTRA_DISPLAYED_QUESTION = "displayed_question"
    const val REMOTE_INPUT_KEY = "bs_reply"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(NotifyChannels.STATUS, "Agent activity", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of the running task, with a stop button" },
                NotificationChannel(NotifyChannels.QUESTIONS, "Agent questions", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "The agent asks for missing information or approval" },
                NotificationChannel(NotifyChannels.RESULTS, "Task results", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Outcome of tasks started by voice, automation or schedules" },
                NotificationChannel(NotifyChannels.WAKE_WORD, "Wake word listening", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while Backstage listens for the wake phrase" },
                NotificationChannel(NotifyChannels.WAKE_WORD_ALERTS, "Wake word stopped", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "The wake word stopped (after a restart or a busy microphone) and needs a tap to resume" },
                NotificationChannel(NotifyChannels.BUBBLE, "Floating bubble", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while the floating agent bubble is on screen" },
                NotificationChannel(NotifyChannels.SCHEDULE, "Scheduled tasks", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "A scheduled task was missed, or waits for you to unlock the phone or for the agent to be free" },
                NotificationChannel(NotifyChannels.AUTOMATION, "Automation", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Tasker, MacroDroid and broadcast API events" },
            ),
        )
    }

    /** Opens the app (Dextop's Home with the desktop; the Agent tab is one tap away). */
    fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode, SessionNotification.openAppIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Opens the AI chat on [threadId] (null: the live task's thread, else the chat as it is). */
    fun openThreadIntent(context: Context, threadId: String?): PendingIntent = PendingIntent.getActivity(
        context, ("thread" + threadId).hashCode(),
        Intent(context, OpenThreadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { threadId?.let { putExtra(OpenThreadActivity.EXTRA_THREAD, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(
        context: Context, action: String, taskId: String?, answer: String? = null, mutable: Boolean = false,
        question: String? = null, displayedQuestion: String? = null,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_ANSWER, answer)
            .putExtra(EXTRA_QUESTION, question)
            .putExtra(EXTRA_DISPLAYED_QUESTION, displayedQuestion)
            // Extras are not part of PendingIntent identity. Bind the displayed text even across hash collisions.
            .setData(android.net.Uri.Builder().scheme("backstage-notification").authority("answer")
                .appendQueryParameter("task", taskId).appendQueryParameter("question", question)
                .appendQueryParameter("shown", displayedQuestion).build())
        val code = (action + taskId + answer + question).hashCode()
        // RemoteInput needs a mutable PendingIntent so the system can attach the typed reply.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, code, intent, flags)
    }

    private fun icon(context: Context) = Icon.createWithResource(context, R.drawable.bs_ic_agent)

    fun build(context: Context, snapshot: TaskSnapshot): Notification {
        val model = TaskNotificationModel.from(snapshot)
        val stop = Notification.Action.Builder(icon(context), "Stop", actionIntent(context, ACTION_STOP, snapshot.id)).build()
        // The lock screen gets a redacted copy: no task text, question (amounts, message content) or result, and of the
        // buttons only Stop. Answer buttons in the full version also demand an unlock (setAuthenticationRequired).
        val redacted = Notification.Builder(context, model.channel)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle(model.publicTitle)
            .setContentText(model.publicText)
            .setOngoing(model.ongoing)
            .apply { if (TaskNotificationModel.Action.Stop in model.actions) addAction(stop) }
            .build()
        val b = Notification.Builder(context, model.channel)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setStyle(Notification.BigTextStyle().bigText(model.text))
            .setOngoing(model.ongoing)
            .setOnlyAlertOnce(!model.alert)
            .setAutoCancel(!model.ongoing)
            .setContentIntent(openThreadIntent(context, snapshot.request.attachments[com.eggman.backstage.agent.AgentControllerImpl.THREAD_KEY]))
            .setCategory(if (model.channel == NotifyChannels.QUESTIONS) Notification.CATEGORY_MESSAGE else Notification.CATEGORY_PROGRESS)
        model.progress?.let { (cur, max) -> b.setProgress(max, cur, false) }
        val q = TaskNotificationModel.questionKey(snapshot)
        model.actions.forEach { a ->
            val builder = when (a) {
                TaskNotificationModel.Action.Stop -> { b.addAction(stop); null }
                TaskNotificationModel.Action.Approve ->
                    Notification.Action.Builder(icon(context), "Approve", actionIntent(context, ACTION_ANSWER, snapshot.id,
                        Answers.APPROVE, question = q, displayedQuestion = model.text))
                TaskNotificationModel.Action.Deny ->
                    Notification.Action.Builder(icon(context), "Deny", actionIntent(context, ACTION_ANSWER, snapshot.id,
                        Answers.DENY, question = q, displayedQuestion = model.text))
                is TaskNotificationModel.Action.Choice ->
                    Notification.Action.Builder(icon(context), a.text.take(24), actionIntent(context, ACTION_ANSWER, snapshot.id,
                        a.text, question = q, displayedQuestion = model.text))
                TaskNotificationModel.Action.Reply ->
                    Notification.Action.Builder(icon(context), "Reply", actionIntent(context, ACTION_REPLY, snapshot.id,
                        mutable = true, question = q, displayedQuestion = model.text))
                        .addRemoteInput(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel("Your answer").build())
                        .setAllowGeneratedReplies(false)
                TaskNotificationModel.Action.Open -> null // content intent already opens the app
            } ?: return@forEach
            b.addAction(builder.setAuthenticationRequired(a.requiresUnlock).build())
        }
        return b.build()
    }

    /** Receiver callers check unlock first. A confirmation is bound to the text this notification actually showed. */
    internal fun answerFromNotification(taskId: String?, questionKey: String?, displayedQuestion: String?, text: String): Boolean {
        val current = AgentAccess.state().value.current ?: return false
        if (!TaskNotificationModel.answerApplies(current, taskId, questionKey)) return false
        if (current.question?.confirm == true) {
            val shown = displayedQuestion?.takeIf { it.length <= TaskNotificationModel.MAX_TEXT } ?: return false
            return AgentAccess.answerFromUi(text, current.id, shown)
        }
        // Ordinary questions may be answered programmatically too, including when their notification is shortened.
        AgentAccess.answer(text)
        return true
    }

    fun notifyTask(context: Context, snapshot: TaskSnapshot) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (TaskNotificationModel.from(snapshot).ongoing) {
            nm.notify(ID_TASK_STATUS, build(context, snapshot))
        } else {
            nm.cancel(ID_TASK_STATUS)
            nm.notify(ID_RESULT_BASE + (snapshot.id.hashCode() and 0xFFF), build(context, snapshot))
        }
    }

    fun clearTaskStatus(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_TASK_STATUS)
    }

    fun scheduleResult(context: Context, task: ScheduledTask, status: TaskStatus, result: String?, threadId: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val title = when (status) {
            TaskStatus.SUCCEEDED -> "Scheduled task done"
            TaskStatus.CANCELLED -> "Scheduled task stopped"
            else -> "Scheduled task failed"
        }
        val text = (result ?: task.label).take(TaskNotificationModel.MAX_TEXT)
        nm.notify(
            ID_SCHEDULE_BASE + (task.id.hashCode() and 0xFFF),
            Notification.Builder(context, NotifyChannels.RESULTS)
                .setSmallIcon(R.drawable.bs_ic_agent)
                .setContentTitle("$title: ${task.label}")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(if (threadId != null) openThreadIntent(context, threadId) else openAppIntent(context))
                .apply {
                    if (status != TaskStatus.SUCCEEDED) addAction(
                        Notification.Action.Builder(
                            icon(context), "Run again", startScheduleIntent(context, task),
                        ).build(),
                    )
                }
                .build(),
        )
    }

    private fun startScheduleIntent(context: Context, task: ScheduledTask): PendingIntent = PendingIntent.getActivity(
        context, ("run" + task.id).hashCode(),
        Intent(context, com.eggman.backstage.schedule.ScheduleRunActivity::class.java)
            .putExtra(com.eggman.backstage.schedule.Schedules.EXTRA_ID, task.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A due run that did not start (locked phone, busy agent): one tap on Start runs it (unlocking first). */
    fun scheduleWaiting(context: Context, task: ScheduledTask, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val start = startScheduleIntent(context, task)
        nm.notify(
            ID_SCHEDULE_BASE + (task.id.hashCode() and 0xFFF),
            Notification.Builder(context, NotifyChannels.SCHEDULE)
                .setSmallIcon(R.drawable.bs_ic_agent)
                .setContentTitle("Scheduled task ready: ${task.label}")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(start)
                .addAction(Notification.Action.Builder(icon(context), "Start", start).build())
                .build(),
        )
    }

    fun cancelScheduleResult(context: Context, scheduleId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_SCHEDULE_BASE + (scheduleId.hashCode() and 0xFFF))
    }

    fun automationNotice(context: Context, title: String, text: String) {
        context.getSystemService(NotificationManager::class.java)?.notify(
            ID_AUTOMATION,
            Notification.Builder(context, NotifyChannels.AUTOMATION)
                .setSmallIcon(R.drawable.bs_ic_agent)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build(),
        )
    }

    /** Persistent notification for a foreground service (wake word / bubble). */
    fun foreground(
        context: Context,
        channel: String,
        title: String,
        text: String,
        actions: List<Pair<String, PendingIntent>> = emptyList(),
    ): Notification = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.bs_ic_agent)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openAppIntent(context))
        .apply { actions.forEach { (label, pi) -> addAction(Notification.Action.Builder(icon(context), label, pi).build()) } }
        .build()
}

/**
 * Handles notification buttons: stop, approve/deny/choice and RemoteInput replies, and the session notification's
 * "Message the agent", Popup/Background and Stop desktop (not exported).
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (SessionNotification.onAction(context, intent)) return
        val taskId = intent.getStringExtra(Notifications.EXTRA_TASK_ID)
        val question = intent.getStringExtra(Notifications.EXTRA_QUESTION)
        val displayedQuestion = intent.getStringExtra(Notifications.EXTRA_DISPLAYED_QUESTION)
        when (intent.action) {
            Notifications.ACTION_STOP -> {
                AgentAccess.cancel(taskId)
                SessionNotification.render(context)
            }
            Notifications.ACTION_ANSWER -> {
                val answer = intent.getStringExtra(Notifications.EXTRA_ANSWER) ?: return
                // Backstop for setAuthenticationRequired: answers never count while the phone is locked.
                if (deviceLocked(context)) return
                // A stale Approve (older question, other task) must never answer the question shown.
                if (!Notifications.answerFromNotification(taskId, question, displayedQuestion, answer)) return
            }
            Notifications.ACTION_REPLY -> {
                if (deviceLocked(context)) return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.REMOTE_INPUT_KEY)?.toString()
                if (text.isNullOrBlank() || !Notifications.answerFromNotification(taskId, question, displayedQuestion, text.trim())) return
            }
            else -> return
        }
        // Remove the question immediately; the notifier re-posts the live status on the next state change.
        Notifications.clearTaskStatus(context)
    }

    private fun deviceLocked(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true
}
