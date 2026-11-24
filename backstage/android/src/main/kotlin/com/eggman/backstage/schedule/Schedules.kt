package com.eggman.backstage.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.ui.StatusText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Android wiring for [ScheduleEngine]: SharedPreferences storage + AlarmManager alarms. */
object Schedules {
    const val ACTION_FIRE = "com.eggman.backstage.schedule.FIRE"
    const val EXTRA_ID = "schedule_id"
    /** Scheduled runs are non-interactive; this caps one run. */
    const val RUN_TIMEOUT_MS = 15 * 60 * 1000L

    @Volatile
    private var engine: ScheduleEngine? = null

    fun engine(context: Context): ScheduleEngine = engine ?: synchronized(this) {
        engine ?: ScheduleEngine(PrefsStorage(context.applicationContext), AndroidAlarms(context.applicationContext))
            .also { engine = it }
    }

    fun canScheduleExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** Arm all alarms and run tasks missed while the phone is off/asleep. */
    fun reschedule(context: Context) {
        engine(context).rescheduleAll().forEach { runNow(context, it.id) }
    }

    /** Fire [id] (alarm or catch-up); [manual] = the user tapped the manual run or start action. */
    /** Returns a short line for the user saying what happened (shown as a toast by the manual run action). */
    fun runNow(context: Context, id: String, manual: Boolean = false): String {
        val app = context.applicationContext
        val task = (if (manual) engine(app).onManualRun(id) else engine(app).onAlarm(id))
            ?: return "This task is already running (started less than a minute ago)."
        val state = AgentAccess.state().value
        val busyNow = StatusText.isActive(state.current) || state.queueSize > 0
        val decision = ScheduleRunPolicy.decide(
            ScheduleRunPolicy.Inputs(
                agentAvailable = AgentAccess.controller() != null,
                locked = app.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true,
                busy = StatusText.isActive(state.current) || state.queueSize > 0,
                // A running desktop alone is not enough: with the default display flags it sleeps with the phone and the
                // keyguard covers it. Only a desktop that keeps running while locked can take the run: one created that
                // way, or (with the opt-in) one the task starts, or restarts when it predates the opt-in.
                desktopRunsWhileLocked = runCatching {
                    com.eggman.backstage.agent.LockedDesktop.worksWhileLocked(
                        AgentAccess.host(),
                        autoStart = com.eggman.backstage.data.Settings(app).mayAutoStartDesktop,
                    )
                }.getOrDefault(false),
                target = task.target,
                manual = manual,
            ),
        )
        when (decision) {
            ScheduleRunPolicy.Decision.UNAVAILABLE -> {
                engine(app).recordResult(id, "FAILED", "Agent not available")
                Notifications.scheduleResult(app, task, TaskStatus.FAILED, "The agent is not ready (open Backstage to finish setup).")
                return "Not started: the agent is not ready (finish setup in Backstage)."
            }
            ScheduleRunPolicy.Decision.WAIT_FOR_UNLOCK -> {
                engine(app).recordDeferred(id, "The phone was locked when this was due")
                wakeScreenBriefly(app)
                Notifications.scheduleWaiting(app, task, "Waiting for you: the phone was locked when this was due. Unlock, then press Start.")
                return "Waiting: the phone is locked."
            }
            ScheduleRunPolicy.Decision.WAIT_FOR_AGENT -> {
                engine(app).recordDeferred(id, "The agent was on another task when this was due")
                Notifications.scheduleWaiting(app, task, "Waiting for you: the agent was on another task when this was due. Press Start when ready.")
                return "Waiting: the agent is on another task."
            }
            ScheduleRunPolicy.Decision.RUN, ScheduleRunPolicy.Decision.RUN_ON_DESKTOP -> {
                if (!submit(app, task, if (decision == ScheduleRunPolicy.Decision.RUN_ON_DESKTOP) Target.DESKTOP else task.target))
                    return "Not started: the agent is not ready (finish setup in Backstage)."
                if (busyNow) {
                    engine(app).recordResult(id, "QUEUED", "Queued behind the agent's current task; it starts when that one ends")
                    return "Queued: it starts when the agent's current task ends (stop that task to start it now)."
                }
                return "Started — follow it in the chat's task view."
            }
        }
    }

    private fun submit(app: Context, task: ScheduledTask, target: Target): Boolean {
        val id = task.id
        Notifications.cancelScheduleResult(app, id)
        val handle = AgentAccess.submit(
            TaskRequest(
                prompt = task.prompt,
                source = Source.SCHEDULE,
                target = target,
                interactive = false,
                timeoutMs = RUN_TIMEOUT_MS,
                attachments = mapOf("schedule_id" to task.id),
                // Only an instruction the user saved in the Automate screen may waive confirmations, never one the agent wrote.
                waiverText = task.prompt.takeIf { task.source == ScheduledTask.SOURCE_UI },
            ),
        )
        if (handle == null) {
            engine(app).recordResult(id, "FAILED", "Agent not available")
            Notifications.scheduleResult(app, task, TaskStatus.FAILED, "The agent is not ready (open Backstage to finish setup).")
            return false
        }
        SurfaceScope.launch {
            val snap = withTimeoutOrNull(RUN_TIMEOUT_MS + 60_000L) { handle.await() }
            val status = snap?.status ?: TaskStatus.FAILED
            val result = snap?.result ?: if (snap == null) "Timed out" else null
            engine(app).recordResult(id, status.name, result)
            Notifications.scheduleResult(app, task, status, result,
                snap?.request?.attachments?.get(com.eggman.backstage.agent.AgentControllerImpl.THREAD_KEY))
        }
        return true
    }

    /** About five seconds of screen so the user sees the "unlock and start" notification. */
    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly(context: Context) {
        runCatching {
            val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return
            pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "backstage:schedule",
            ).acquire(5_000L)
        }
    }

    private class PrefsStorage(context: Context) : ScheduleStorage {
        private val prefs = context.getSharedPreferences("backstage_schedules", Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString("tasks", null)
        override fun save(json: String) {
            // apply: the in-memory copy updates at once (later reads see it); the disk write leaves the main thread.
            prefs.edit().putString("tasks", json).apply()
        }
    }

    private class AndroidAlarms(private val context: Context) : AlarmPort {
        private val am get() = context.getSystemService(AlarmManager::class.java)

        override fun canScheduleExact(): Boolean = am?.canScheduleExactAlarms() == true

        override fun arm(taskId: String, atMs: Long, exact: Boolean) {
            val pi = pending(taskId, create = true) ?: return
            val manager = am ?: return
            try {
                if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            } catch (_: SecurityException) {
                // Exact-alarm permission revoked between the check and the call: degrade to inexact.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            }
        }

        override fun disarm(taskId: String) {
            pending(taskId, create = false)?.let { am?.cancel(it); it.cancel() }
        }

        private fun pending(taskId: String, create: Boolean): PendingIntent? {
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION_FIRE)
                // Data URI makes each task's PendingIntent distinct regardless of request code collisions.
                .setData(android.net.Uri.parse("backstage-schedule://task/$taskId"))
                .putExtra(EXTRA_ID, taskId)
            val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
            return PendingIntent.getBroadcast(context, taskId.hashCode(), intent, flags)
        }
    }
}

/** AlarmManager target (not exported). */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Schedules.ACTION_FIRE) return
        val id = intent.getStringExtra(Schedules.EXTRA_ID) ?: return
        val pending = goAsync()
        SurfaceScope.launch {
            try {
                Schedules.runNow(context, id)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Re-arms alarms after boot, app update, clock/zone changes and exact-alarm permission changes. */
class ScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Unit
            else -> return
        }
        val pending = goAsync()
        SurfaceScope.launch {
            try {
                Schedules.reschedule(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) com.eggman.backstage.voice.WakeWord.onBoot(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * What to do when a scheduled run is due (pure). Order: agent missing → fail; a user tap always runs; phone locked →
 * run on a desktop that keeps running while the phone is locked (own display group + always unlocked, an opt-in)
 * unless the agent is busy, else wait for unlock; agent busy → wait for a tap (no silent pile-up of runs); otherwise run.
 */
object ScheduleRunPolicy {
    enum class Decision { UNAVAILABLE, WAIT_FOR_UNLOCK, WAIT_FOR_AGENT, RUN, RUN_ON_DESKTOP }

    data class Inputs(
        val agentAvailable: Boolean,
        val locked: Boolean,
        val busy: Boolean,
        /** A desktop session runs that keeps running and stays unlocked while the phone is locked. */
        val desktopRunsWhileLocked: Boolean,
        val target: Target,
        val manual: Boolean,
    )

    fun decide(i: Inputs): Decision = when {
        !i.agentAvailable -> Decision.UNAVAILABLE
        i.manual -> Decision.RUN
        i.locked && i.target != Target.PHONE && i.desktopRunsWhileLocked -> if (i.busy) Decision.WAIT_FOR_AGENT else Decision.RUN_ON_DESKTOP
        i.locked -> Decision.WAIT_FOR_UNLOCK
        i.busy -> Decision.WAIT_FOR_AGENT
        else -> Decision.RUN
    }
}
