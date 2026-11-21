package com.eggman.backstage.notify

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eggman.backstage.R
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.host.PresentationNames
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Posts the one session notification ([SessionNotificationModel]). It replaces the old "Desktop running in background"
 * card and the task status card, and it is also the agent service's foreground notification (same id), so a running
 * task never shows two cards. Questions that need an approval still ring separately (TaskNotifier).
 *
 * Updates are throttled to one per [MIN_INTERVAL_MS] (NotificationManager drops bursts silently); the last state is
 * always posted.
 */
object SessionNotification {
    const val ID = 0xBA57
    const val CHANNEL = "bs_session"
    const val ACTION_MESSAGE = "com.eggman.backstage.notify.SESSION_MESSAGE"
    const val ACTION_PRESENT = "com.eggman.backstage.notify.DESKTOP_PRESENT"
    const val ACTION_STOP_DESKTOP = "com.eggman.backstage.notify.STOP_DESKTOP"
    const val EXTRA_PRESENTATION = "presentation"
    const val REMOTE_INPUT_KEY = "bs_message"
    const val MIN_INTERVAL_MS = 1_000L
    /** Dextop's Home screen: the app's main screen (the launcher's own target, without the trampoline). */
    const val HOME_ACTIVITY = "moe.n4tsu.dextop.MainActivity"
    /** Old channels this card replaces. */
    private val RETIRED_CHANNELS = listOf("bs_desktop_background", "backstage_attention")
    private const val OLD_DESKTOP_ID = 0x4253_0006

    /** Set by the agent service while the MCP server runs (one line in the card, plus "Stop MCP server"). */
    @Volatile var mcpLine: String? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var lastPostedAt = 0L
    private var pending = false
    private var registeredHost: DesktopHost? = null
    @Volatile private var started = false

    private val hostListener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: Presentation) {
            appContext?.let { render(it) }
        }
    }
    @Volatile private var appContext: Context? = null

    /** Process start: follows the agent state and the desktop host. Idempotent. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        val nm = app.getSystemService(NotificationManager::class.java)
        runCatching {
            ensureChannel(nm)
            RETIRED_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            nm.cancel(OLD_DESKTOP_ID)
        }
        AgentAccess.host()?.takeIf { it !== registeredHost }?.let { host ->
            registeredHost = host
            host.addListener(hostListener)
        }
        if (!started) {
            started = true
            SurfaceScope.launch {
                while (AgentAccess.controller() == null) delay(500)
                AgentAccess.state().collect { render(app) }
            }
        }
        render(app)
    }

    fun ensureChannel(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Backstage session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What the agent is doing while the desktop or a task runs, with a reply field and Stop"
                setShowBadge(false)
            },
        )
    }

    private fun presentation(): Presentation {
        val host = AgentAccess.host() ?: return Presentation.NONE
        return runCatching { if (host.sessionActive()) host.presentation() else Presentation.NONE }.getOrDefault(Presentation.NONE)
    }

    fun model(state: AgentState = AgentAccess.state().value): SessionNotificationModel =
        SessionNotificationModel.from(
            state, presentation(), mcpLine,
            target = runCatching { com.eggman.backstage.ui.settings.SettingsStores.peek()?.defaultTarget }.getOrNull(),
            phoneMode = com.eggman.backstage.shell.ShellLifecycle.phoneMode.value,
        )

    /** Posts (or removes) the card; throttled. Any thread. */
    fun render(context: Context) {
        val app = context.applicationContext ?: context
        main.post {
            if (pending) return@post
            val wait = lastPostedAt + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) {
                pending = true
                main.postDelayed({ pending = false; renderNow(app) }, wait)
            } else renderNow(app)
        }
    }

    /** Posts or cancels at once (the receiver after a reply, so the reply spinner ends). */
    fun renderNow(context: Context) {
        lastPostedAt = SystemClock.uptimeMillis()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val model = model()
        if (!model.show) {
            runCatching { nm.cancel(ID) }
            return
        }
        runCatching {
            ensureChannel(nm)
            nm.notify(ID, build(context, model))
        }
    }

    private fun icon(context: Context) = Icon.createWithResource(context, R.drawable.bs_ic_agent)

    fun build(context: Context, model: SessionNotificationModel = model()): Notification {
        val actions = model.actions.map { action(context, model, it) }
        // Lock screen: nothing about the task, only Stop (a running task can always be stopped).
        val redacted = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle(SessionNotificationModel.PUBLIC_TITLE)
            .setContentText(SessionNotificationModel.PUBLIC_TEXT)
            .setOngoing(true)
            .apply {
                model.actions.zip(actions).filter { it.first == SessionNotificationModel.Action.StopTask }
                    .forEach { addAction(it.second) }
            }
            .build()
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle(model.title)
            .setContentText(model.text.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(model.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openThreadIntent(context, null))
            .apply {
                model.progress?.let { (cur, max) -> setProgress(max, cur, false) }
                actions.forEach { addAction(it) }
            }
            .build()
    }

    private fun action(context: Context, model: SessionNotificationModel, action: SessionNotificationModel.Action): Notification.Action {
        val builder = when (action) {
            SessionNotificationModel.Action.Message -> Notification.Action.Builder(
                icon(context), SessionNotificationModel.MESSAGE_LABEL, messageIntent(context, model),
            ).addRemoteInput(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(SessionNotificationModel.MESSAGE_LABEL).build())
                .setAllowGeneratedReplies(false)
            SessionNotificationModel.Action.StopTask -> Notification.Action.Builder(
                icon(context), SessionNotificationModel.STOP_LABEL,
                broadcast(context, Intent(Notifications.ACTION_STOP).putExtra(Notifications.EXTRA_TASK_ID, model.taskId), "stop" + model.taskId),
            )
            SessionNotificationModel.Action.StopDesktop -> Notification.Action.Builder(
                icon(context), SessionNotificationModel.STOP_DESKTOP_LABEL, broadcast(context, Intent(ACTION_STOP_DESKTOP), ACTION_STOP_DESKTOP),
            )
            is SessionNotificationModel.Action.Present -> Notification.Action.Builder(
                icon(context),
                if (action.presentation == Presentation.MINI) SessionNotificationModel.POPUP_LABEL else SessionNotificationModel.BACKGROUND_LABEL,
                broadcast(
                    context,
                    Intent(ACTION_PRESENT).putExtra(EXTRA_PRESENTATION, PresentationNames.wireName(action.presentation)),
                    ACTION_PRESENT + action.presentation.name,
                ),
            )
            SessionNotificationModel.Action.StopMcp -> Notification.Action.Builder(
                icon(context), SessionNotificationModel.STOP_MCP_LABEL,
                PendingIntent.getService(
                    context, 4,
                    Intent(context, com.eggman.backstage.agent.AgentService::class.java)
                        .setAction(com.eggman.backstage.agent.AgentService.ACTION_STOP_MCP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.setAuthenticationRequired(action.requiresUnlock).build()
    }

    /** Explicit, immutable broadcast to the non-exported receiver. */
    private fun broadcast(context: Context, intent: Intent, key: String): PendingIntent = PendingIntent.getBroadcast(
        context, key.hashCode(),
        intent.setClass(context, NotificationActionReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The only mutable PendingIntent: the system adds the typed text. Explicit, to the non-exported receiver. */
    fun messageIntent(context: Context, model: SessionNotificationModel): PendingIntent = PendingIntent.getBroadcast(
        context, (ACTION_MESSAGE + model.taskId + model.questionKey).hashCode(),
        Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_MESSAGE).setPackage(context.packageName)
            .putExtra(Notifications.EXTRA_TASK_ID, model.taskId)
            .putExtra(Notifications.EXTRA_QUESTION, model.questionKey)
            .putExtra(Notifications.EXTRA_DISPLAYED_QUESTION, model.displayedQuestion)
            .setData(android.net.Uri.Builder().scheme("backstage-notification").authority("session-reply")
                .appendQueryParameter("task", model.taskId).appendQueryParameter("question", model.questionKey)
                .appendQueryParameter("shown", model.displayedQuestion).build()),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Intent that opens the app: Dextop's Home (with the live desktop), or the agent in builds without it. */
    fun openAppIntent(context: Context): Intent {
        val home = Intent().setComponent(ComponentName(context.packageName, HOME_ACTIVITY))
        val target = if (context.packageManager.resolveActivity(home, 0) != null) home else Intent(context, AgentScreenActivity::class.java)
        return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, ID, openAppIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Receiver side (NotificationActionReceiver). True when the intent is one of this card's. */
    fun onAction(context: Context, intent: Intent): Boolean {
        when (intent.action) {
            ACTION_MESSAGE -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
                val taskId = intent.getStringExtra(Notifications.EXTRA_TASK_ID)
                val question = intent.getStringExtra(Notifications.EXTRA_QUESTION)
                val displayedQuestion = intent.getStringExtra(Notifications.EXTRA_DISPLAYED_QUESTION)
                if (deviceLocked(context) && !text.isNullOrBlank()) {
                    // The reply needs an unlock (setAuthenticationRequired); some lock screens deliver it while the
                    // keyguard is still going away. Wait briefly for the unlock instead of dropping it silently.
                    SurfaceScope.launch {
                        val unlocked = SessionMessage.awaitUnlock { deviceLocked(context) }
                        if (unlocked) deliverMessage(context, taskId, question, text, displayedQuestion)
                        else Notifications.automationNotice(context, "Reply not sent", "Unlock the phone, then send it again.")
                        renderNow(context)
                    }
                    renderNow(context)
                    return true
                }
                deliverMessage(context, taskId, question, text, displayedQuestion)
                // Re-post (never cancel: it may be the foreground notification) so the reply spinner ends.
                renderNow(context)
            }
            ACTION_PRESENT -> {
                val target = PresentationNames.parse(intent.getStringExtra(EXTRA_PRESENTATION))
                    .takeIf { it == Presentation.MINI || it == Presentation.HIDDEN } ?: return true
                val host = AgentAccess.host() ?: return true
                // The host waits for MirrorService on the main thread: never block the receiver's (main) thread.
                SurfaceScope.launch {
                    val ok = runCatching { host.sessionActive() && host.setPresentation(target) }.getOrDefault(false)
                    if (ok && target == Presentation.MINI) {
                        // The popup would stay invisible under the shade.
                        runCatching {
                            AgentAccessibility.instance?.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                        }
                    }
                    render(context)
                }
            }
            ACTION_STOP_DESKTOP -> {
                if (deviceLocked(context)) return true
                // Agent first: a running task would otherwise start the desktop again.
                com.eggman.backstage.agent.SessionStop.stopAgentAndDesktop(AgentAccess.controller(), AgentAccess.host())
                render(context)
            }
            else -> return false
        }
        return true
    }

    /**
     * Routes a reply typed into the card ([SessionMessage.route]); the phone is unlocked or nothing counts. Also the
     * popup's chat panel (MiniChatActivity): same routing, desktop gating (default target) and source.
   */
    fun deliverMessage(context: Context, taskId: String?, question: String?, text: String?, displayedQuestion: String? = null): SessionMessage.Route {
        val route = SessionMessage.route(
            AgentAccess.state().value.current, taskId, question, locked = deviceLocked(context), text = text,
        )
        when (route) {
            SessionMessage.Route.Drop -> Unit
            is SessionMessage.Route.Answer -> {
                if (!Notifications.answerFromNotification(taskId, question, displayedQuestion, route.text)) return SessionMessage.Route.Drop
            }
            is SessionMessage.Route.Instruct -> AgentAccess.instruct(route.taskId, route.text)
            is SessionMessage.Route.Resume -> AgentAccess.resume(route.taskId, route.text)
            is SessionMessage.Route.NewTask -> AgentAccess.submit(
                TaskRequest(
                    prompt = route.text,
                    source = Source.NOTIFICATION,
                    // The chat's current target (the same chips everywhere), unless the text names the other screen.
                    target = com.eggman.backstage.entry.CurrentTarget.forMessage(context, route.text),
                    // Typed by the user on an unlocked phone: their own words, like the chat.
                    waiverText = route.text,
                ),
            )
        }
        return route
    }

    fun deviceLocked(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true
}
