package com.eggman.backstage.schedule

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Pure scheduling math (no Android). All times are epoch milliseconds. */
object ScheduleMath {

    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    /** A run missed while the phone is off is still executed if it is at most this late. */
    const val DEFAULT_CATCH_UP_GRACE_MS = 2 * 60 * 60 * 1000L

    /** Next fire strictly after [nowMs], or null if the task will never fire again. */
    fun nextFire(task: ScheduledTask, nowMs: Long, zone: ZoneId): Long? {
        if (!task.enabled) return null
        return when (val r = task.repeat) {
            Repeat.Once -> task.onceAtMs.takeIf { it > nowMs }
            Repeat.Daily -> nextWallClock(task.minuteOfDay, nowMs, zone) { true }
            is Repeat.Weekly -> if (r.days.isEmpty()) null else nextWallClock(task.minuteOfDay, nowMs, zone) { it.dayOfWeek in r.days }
            is Repeat.Interval -> nextInterval(task.anchorMs, r.minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES), nowMs)
        }
    }

    private inline fun nextWallClock(minuteOfDay: Int, nowMs: Long, zone: ZoneId, dayOk: (LocalDate) -> Boolean): Long? {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        val time = LocalTime.of(m / 60, m % 60)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (!dayOk(date)) continue
            // ZonedDateTime.of resolves DST gaps by moving forward and overlaps to the earlier offset.
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > nowMs) return candidate
        }
        return null
    }

    private fun nextInterval(anchorMs: Long, minutes: Int, nowMs: Long): Long {
        val period = minutes * 60_000L
        if (anchorMs > nowMs) return anchorMs
        val k = (nowMs - anchorMs) / period + 1
        return anchorMs + k * period
    }

    /**
     * After a reboot/time change: is the previously armed fire time missed recently enough that
     * it should still run?
   */
    fun shouldCatchUp(task: ScheduledTask, nowMs: Long, graceMs: Long = DEFAULT_CATCH_UP_GRACE_MS): Boolean {
        val armed = task.nextAtMs ?: return false
        if (!task.enabled || armed > nowMs) return false
        val alreadyRan = task.lastRunAtMs != null && task.lastRunAtMs >= armed
        return !alreadyRan && nowMs - armed <= graceMs
    }

    /** Two tasks whose next runs are closer than this conflict (one agent, one screen). */
    const val CONFLICT_WINDOW_MS = 5 * 60 * 1000L

    /**
     * The first other enabled task whose next run is within [CONFLICT_WINDOW_MS] of [task]'s next run, if any.
     * [task] must carry its final anchor (intervals anchored at "currently" when new).
   */
    fun conflict(task: ScheduledTask, others: List<ScheduledTask>, nowMs: Long, zone: ZoneId): ScheduledTask? {
        val mine = nextFire(task, nowMs, zone) ?: return null
        return others.firstOrNull { o ->
            if (o.id == task.id || !o.enabled) return@firstOrNull false
            val theirs = nextFire(o, nowMs, zone) ?: return@firstOrNull false
            kotlin.math.abs(theirs - mine) < CONFLICT_WINDOW_MS
        }
    }

    /** "in 2 h 5 min", "in 45 min", "in 3 d 4 h", "currently" — the live countdown on schedule cards. */
    fun countdown(atMs: Long, nowMs: Long): String {
        val min = ((atMs - nowMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "now"
            min < 60 -> "in $min min"
            min < 24 * 60 -> "in ${min / 60} h" + (if (min % 60 != 0L) " ${min % 60} min" else "")
            else -> "in ${min / (24 * 60)} d" + (if ((min / 60) % 24 != 0L) " ${(min / 60) % 24} h" else "")
        }
    }

    fun validate(task: ScheduledTask, nowMs: Long): List<String> = buildList {
        if (task.prompt.isBlank()) add("Describe what the agent should do.")
        when (val r = task.repeat) {
            Repeat.Once -> if (task.onceAtMs <= nowMs) add("Pick a time in the future.")
            is Repeat.Weekly -> if (r.days.isEmpty()) add("Pick at least one weekday.")
            is Repeat.Interval -> if (r.minutes < MIN_INTERVAL_MINUTES) add("Repeat at most every $MIN_INTERVAL_MINUTES minutes.")
            Repeat.Daily -> Unit
        }
    }

    /** Short human description, e.g. "Mon, Wed at 08:30" or "Every 2 h". */
    fun describe(task: ScheduledTask, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val hhmm = "%02d:%02d".format(task.minuteOfDay / 60, task.minuteOfDay % 60)
        return when (val r = task.repeat) {
            Repeat.Once -> "Once, " + DateTimeFormatter.ofPattern("EEE d MMM HH:mm", locale)
                .format(Instant.ofEpochMilli(task.onceAtMs).atZone(zone))
            Repeat.Daily -> "Daily at $hhmm"
            is Repeat.Weekly -> if (r.days.size == 7) "Daily at $hhmm" else
                r.days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) } + " at $hhmm"
            is Repeat.Interval -> when {
                r.minutes % (24 * 60) == 0 -> "Every ${r.minutes / (24 * 60)} d"
                r.minutes % 60 == 0 -> "Every ${r.minutes / 60} h"
                else -> "Every ${r.minutes} min"
            }
        }
    }
}
