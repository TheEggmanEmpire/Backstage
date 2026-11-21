package com.eggman.backstage.schedule

import java.time.ZoneId
import java.util.UUID

/** Where the schedule list is persisted (SharedPreferences in the app, memory in tests). */
interface ScheduleStorage {
    fun load(): String?
    fun save(json: String)
}

/** Alarm side effects (AlarmManager in the app, a fake in tests). */
interface AlarmPort {
    fun canScheduleExact(): Boolean
    fun arm(taskId: String, atMs: Long, exact: Boolean)
    fun disarm(taskId: String)
}

/**
 * Pure orchestration of scheduled tasks: CRUD, arming the next alarm, catching up missed runs.
 * Thread-safe via a simple monitor; storage writes are synchronous and small.
 */
class ScheduleEngine(
    private val storage: ScheduleStorage,
    private val alarms: AlarmPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Any()

    fun list(): List<ScheduledTask> = synchronized(lock) { ScheduleCodec.decode(storage.load()) }

    fun get(id: String): ScheduledTask? = list().firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    /** Thrown by [upsert] with checkConflicts when another task runs within five minutes. */
    class ConflictException(val other: ScheduledTask, message: String) : IllegalArgumentException(message)

    /**
     * Insert or replace, then arm. Returns the stored task (with nextAtMs). With [checkConflicts] (user and agent
     * edits) a task whose next run is within five minutes of another task's is rejected with [ConflictException].
   */
    fun upsert(task: ScheduledTask, checkConflicts: Boolean = false): ScheduledTask = synchronized(lock) {
        val now = clock()
        val base = if (task.createdAtMs == 0L) task.copy(createdAtMs = now) else task
        val withAnchor = if (base.repeat is Repeat.Interval && base.anchorMs == 0L) base.copy(anchorMs = now) else base
        val existing = ScheduleCodec.decode(storage.load())
        if (checkConflicts && withAnchor.enabled) {
            ScheduleMath.conflict(withAnchor, existing, now, zone())?.let { other ->
                throw ConflictException(other, "Another scheduled task (\"${other.label}\") runs within 5 minutes of this one; pick another time.")
            }
        }
        val armed = arm(withAnchor, now)
        val all = existing.filterNot { it.id == armed.id } + armed
        storage.save(ScheduleCodec.encode(all.sortedBy { it.createdAtMs }))
        armed
    }

    fun delete(id: String) = synchronized(lock) {
        alarms.disarm(id)
        storage.save(ScheduleCodec.encode(ScheduleCodec.decode(storage.load()).filterNot { it.id == id }))
    }

    /** Enabling counts as an edit: it is rejected when it would collide with another task ([ConflictException]). */
    fun setEnabled(id: String, enabled: Boolean): ScheduledTask? = get(id)?.let { upsert(it.copy(enabled = enabled), checkConflicts = enabled) }

    /**
     * Re-arm everything (boot, time/zone change, exact-alarm permission change, app update).
     * Returns the tasks whose missed run should be executed.
   */
    fun rescheduleAll(): List<ScheduledTask> = synchronized(lock) {
        val now = clock()
        val catchUp = mutableListOf<ScheduledTask>()
        val updated = ScheduleCodec.decode(storage.load()).map { t ->
            val due = ScheduleMath.shouldCatchUp(t, now)
            if (due) catchUp += t
            val armed = arm(t, now)
            if (!due && armed.enabled && armed.repeat == Repeat.Once && armed.nextAtMs == null) {
                armed.copy(enabled = false, lastStatus = "MISSED")
            } else armed
        }
        storage.save(ScheduleCodec.encode(updated))
        catchUp
    }

    /**
     * An alarm fired for [id]. Records the run start, arms the next occurrence, and returns the
     * task to execute (null when unknown/disabled — stale alarm).
   */
    fun onAlarm(id: String): ScheduledTask? = synchronized(lock) {
        val now = clock()
        val all = ScheduleCodec.decode(storage.load())
        val task = all.firstOrNull { it.id == id } ?: return null.also { alarms.disarm(id) }
        if (!task.enabled) return null
        // Duplicate delivery (alarm + catch-up racing at process start): run once.
        if (task.lastRunAtMs != null && now - task.lastRunAtMs < dedupeWindow(task)) {
            // AlarmManager alarms are one-shot, including a delivery suppressed after a manual run.
            val next = arm(task, now)
            storage.save(ScheduleCodec.encode(all.map { if (it.id == id) next else it }))
            return null
        }
        val ran = task.copy(lastRunAtMs = now, lastStatus = "RUNNING", lastResult = null)
        val next = arm(ran, now)
        // A one-shot task disables itself after it ran.
        val stored = if (task.repeat == Repeat.Once) next.copy(enabled = false, nextAtMs = null) else next
        storage.save(ScheduleCodec.encode(all.map { if (it.id == id) stored else it }))
        task
    }

    /** Manual run from the UI or a notification: runs even if disabled, does not touch the alarm. */
    fun onManualRun(id: String): ScheduledTask? = synchronized(lock) {
        val now = clock()
        val all = ScheduleCodec.decode(storage.load())
        val task = all.firstOrNull { it.id == id } ?: return null
        if (task.lastStatus == "RUNNING" && task.lastRunAtMs != null && now - task.lastRunAtMs < dedupeWindow(task)) return null
        storage.save(ScheduleCodec.encode(all.map { if (it.id == id) it.copy(lastRunAtMs = now, lastStatus = "RUNNING", lastResult = null) else it }))
        task
    }

    /** A run that is not started (phone locked, agent busy): shown on the card until the user starts it. */
    fun recordDeferred(id: String, why: String) = recordResult(id, "WAITING", why)

    /** Store the outcome of a run (shown in the editor list). */
    fun recordResult(id: String, status: String, result: String?) = synchronized(lock) {
        val all = ScheduleCodec.decode(storage.load())
        if (all.none { it.id == id }) return
        storage.save(ScheduleCodec.encode(all.map {
            if (it.id == id) it.copy(lastStatus = status, lastResult = result?.take(2000)) else it
        }))
    }

    companion object {
        const val DEDUPE_WINDOW_MS = 60_000L

        /** at most half the repeat interval, so an every-minute schedule never loses a run to delivery jitter. */
        fun dedupeWindow(task: ScheduledTask): Long = (task.repeat as? Repeat.Interval)
            ?.let { minOf(DEDUPE_WINDOW_MS, it.minutes * 60_000L / 2) } ?: DEDUPE_WINDOW_MS
    }

    private fun arm(task: ScheduledTask, now: Long): ScheduledTask {
        val next = ScheduleMath.nextFire(task, now, zone())
        if (next == null) {
            alarms.disarm(task.id)
            return task.copy(nextAtMs = null)
        }
        alarms.arm(task.id, next, alarms.canScheduleExact())
        return task.copy(nextAtMs = next)
    }
}
