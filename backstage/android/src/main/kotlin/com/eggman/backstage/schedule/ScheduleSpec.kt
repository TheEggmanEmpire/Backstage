package com.eggman.backstage.schedule

import android.content.Context
import com.eggman.backstage.agent.api.Target
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Text form of a schedule, for agent tools (create/list/update/delete scheduled tasks) and
 * automation. Accepted forms (case-insensitive):
 *   once 2026-11-02 08:30 | once 2026-11-02T08:30 | in 45m | in 2h
 *   daily 08:30 | weekdays 07:45 | weekends 10:00 | weekly mon,wed,fri 09:00 | weekly mon-fri 9:00
 *   every 30m | every 2h | every 1d
 *   cron subset: "M H * * *", "M H * * 1-5", "M H * * 1,3,5", "*\/N * * * *", "0 *\/N * * *"
 */
object ScheduleSpec {

    /** [anchorMs] aligns intervals to the wall clock (cron "*\/30" runs at:00 and:30); 0 = anchored when saved. */
    data class Parsed(val repeat: Repeat, val minuteOfDay: Int = 0, val onceAtMs: Long = 0L, val anchorMs: Long = 0L)

    class SpecException(message: String) : IllegalArgumentException(message)

    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun parse(spec: String, nowMs: Long, zone: ZoneId): Parsed {
        val s = spec.trim().lowercase().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) throw SpecException("empty schedule")
        val parts = s.split(' ')
        return when (parts[0]) {
            "once" -> Parsed(Repeat.Once, onceAtMs = parseDateTime(parts.drop(1).joinToString(" "), zone))
            "in" -> Parsed(Repeat.Once, onceAtMs = nowMs + duration(parts.getOrNull(1)) * 60_000L)
            "daily", "everyday" -> Parsed(Repeat.Daily, minuteOfDay = time(parts.getOrNull(1)))
            "weekdays" -> Parsed(Repeat.Weekly(WEEKDAYS), minuteOfDay = time(parts.getOrNull(1)))
            "weekends" -> Parsed(Repeat.Weekly(WEEKENDS), minuteOfDay = time(parts.getOrNull(1)))
            "weekly" -> Parsed(Repeat.Weekly(days(parts.getOrNull(1))), minuteOfDay = time(parts.getOrNull(2)))
            "every" -> Parsed(Repeat.Interval(duration(parts.getOrNull(1)).toInt()))
            else -> if (parts.size == 5) cron(parts, nowMs, zone) else throw SpecException("unrecognised schedule \"$spec\"")
        }
    }

    /** Canonical text for a stored task (round-trips through [parse]). */
    fun format(task: ScheduledTask, zone: ZoneId): String {
        val hhmm = "%02d:%02d".format(task.minuteOfDay / 60, task.minuteOfDay % 60)
        return when (val r = task.repeat) {
            Repeat.Once -> "once " + LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(task.onceAtMs), zone).toString().take(16)
            Repeat.Daily -> "daily $hhmm"
            is Repeat.Weekly -> when (r.days) {
                WEEKDAYS -> "weekdays $hhmm"
                WEEKENDS -> "weekends $hhmm"
                else -> "weekly " + r.days.sorted().joinToString(",") { d -> DAYS.entries.first { it.value == d }.key } + " $hhmm"
            }
            is Repeat.Interval -> when {
                r.minutes % 1440 == 0 -> "every ${r.minutes / 1440}d"
                r.minutes % 60 == 0 -> "every ${r.minutes / 60}h"
                else -> "every ${r.minutes}m"
            }
        }
    }

    private fun time(t: String?): Int {
        val m = Regex("^(\\d{1,2})(?::(\\d{2}))?(am|pm)?$").find(t?.lowercase() ?: throw SpecException("missing time (HH:MM)"))
            ?: throw SpecException("bad time \"$t\"")
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) {
            "am" -> if (h == 12) h = 0
            "pm" -> if (h < 12) h += 12
        }
        if (h !in 0..23 || min !in 0..59) throw SpecException("bad time \"$t\"")
        return h * 60 + min
    }

    private fun days(list: String?): Set<DayOfWeek> {
        if (list.isNullOrBlank()) throw SpecException("missing weekdays")
        val out = mutableSetOf<DayOfWeek>()
        list.split(',').forEach { token ->
            val range = token.split('-')
            if (range.size == 2) {
                val a = day(range[0]).value
                val b = day(range[1]).value
                var d = a
                while (true) {
                    out += DayOfWeek.of(d)
                    if (d == b) break
                    d = d % 7 + 1
                }
            } else out += day(token)
        }
        return out
    }

    private fun day(t: String): DayOfWeek = DAYS[t.take(3)] ?: throw SpecException("bad weekday \"$t\"")

    /** Minutes from "45m", "2h", "1d" (bare number = minutes). */
    private fun duration(t: String?): Long {
        val m = Regex("^(\\d+)(m|min|h|d)?$").find(t ?: throw SpecException("missing duration")) ?: throw SpecException("bad duration \"$t\"")
        val n = m.groupValues[1].toLong()
        val minutes = when (m.groupValues[2]) {
            "h" -> n * 60
            "d" -> n * 1440
            else -> n
        }
        if (minutes <= 0) throw SpecException("duration must be positive")
        return minutes
    }

    private fun parseDateTime(t: String, zone: ZoneId): Long {
        val norm = t.trim().replace(Regex("[ t]"), "T")
        val ldt = runCatching { LocalDateTime.parse(norm) }.getOrNull()
            ?: runCatching { LocalDate.parse(norm.substringBefore('T')).atTime(LocalTime.ofSecondOfDay(time(norm.substringAfter('T', "")) * 60L)) }.getOrNull()
            ?: throw SpecException("bad date/time \"$t\" (use YYYY-MM-DD HH:MM)")
        return ldt.atZone(zone).toInstant().toEpochMilli()
    }

    /** Start of the current hour / day in [zone]: the origin cron step values count from. */
    private fun hourStart(nowMs: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(nowMs).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli()

    private fun dayStart(nowMs: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    private fun cron(f: List<String>, nowMs: Long, zone: ZoneId): Parsed {
        val (min, hour, dom, mon, dow) = f
        if (dom != "*" || mon != "*") throw SpecException("only minute, hour and weekday cron fields are supported")
        val step = Regex("^\\*/(\\d+)$")
        // Every N minutes, on the clock: "*/30" fires at:00 and:30 (anchored at the start of the hour).
        step.find(min)?.let {
            if (hour == "*" && dow == "*") {
                val n = it.groupValues[1].toIntOrNull()
                if (n == null || n !in 1..60 || 60 % n != 0)
                    throw SpecException("cron minute step must divide 60 (1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60)")
                return Parsed(Repeat.Interval(n), anchorMs = hourStart(nowMs, zone))
            }
        }
        // Hourly at minute M: "M * * * *".
        if (hour == "*" && dow == "*") {
            val m = min.toIntOrNull()?.takeIf { it in 0..59 } ?: throw SpecException("unsupported cron minute \"$min\"")
            return Parsed(Repeat.Interval(60), anchorMs = hourStart(nowMs, zone) + m * 60_000L)
        }
        // Every N hours at minute M, counted from midnight: "0 */2 * * *" fires at 00:00, 02:00,...
        step.find(hour)?.let {
            val m = min.toIntOrNull()?.takeIf { v -> v in 0..59 }
            if (m != null && dow == "*") {
                val n = it.groupValues[1].toIntOrNull()
                if (n == null || n !in 1..24 || 24 % n != 0)
                    throw SpecException("cron hour step must divide 24 (1, 2, 3, 4, 6, 8, 12, 24)")
                return Parsed(Repeat.Interval(n * 60), anchorMs = dayStart(nowMs, zone) + m * 60_000L)
            }
        }
        val m = min.toIntOrNull() ?: throw SpecException("unsupported cron minute \"$min\"")
        val h = hour.toIntOrNull() ?: throw SpecException("unsupported cron hour \"$hour\"")
        if (m !in 0..59 || h !in 0..23) throw SpecException("bad cron time")
        val minute = h * 60 + m
        if (dow == "*") return Parsed(Repeat.Daily, minuteOfDay = minute)
        val set = mutableSetOf<DayOfWeek>()
        dow.split(',').forEach { tok ->
            val r = tok.split('-').map { it.toIntOrNull() ?: throw SpecException("bad cron weekday \"$tok\"") }
            val range = if (r.size == 2) (r[0]..r[1]) else (r[0]..r[0])
            range.forEach { v -> set += DayOfWeek.of(if (v == 0 || v == 7) 7 else v.also { if (it !in 1..6) throw SpecException("bad cron weekday") }) }
        }
        return Parsed(Repeat.Weekly(set), minuteOfDay = minute)
    }
}

/** Cron text for a stored task (the agent's tools speak cron); "ONCE" for one-shot tasks, null if inexpressible. */
object ScheduleCron {
    const val ONCE = "ONCE"

    fun of(task: ScheduledTask, zone: ZoneId): String? {
        val m = task.minuteOfDay % 60
        val h = task.minuteOfDay / 60
        return when (val r = task.repeat) {
            Repeat.Once -> ONCE
            Repeat.Daily -> "$m $h * * *"
            is Repeat.Weekly -> "$m $h * * " + r.days.sorted().joinToString(",") { (it.value % 7).toString() }
            is Repeat.Interval -> {
                val anchor = java.time.Instant.ofEpochMilli(task.anchorMs).atZone(zone)
                when {
                    r.minutes < 60 && 60 % r.minutes == 0 && anchor.minute % r.minutes == 0 -> "*/${r.minutes} * * * *"
                    r.minutes == 60 -> "${anchor.minute} * * * *"
                    r.minutes % 60 == 0 && 24 % (r.minutes / 60) == 0 && anchor.hour % (r.minutes / 60) == 0 ->
                        "${anchor.minute} */${r.minutes / 60} * * *"
                    else -> null
                }
            }
        }
    }
}

/**
 * Small façade for the agent core's schedule tools (create/list/update/delete) so scheduled
 * tasks created by the model and by the user share one store and one set of alarms.
 */
object ScheduledTasksApi {
    fun list(context: Context): List<ScheduledTask> = Schedules.engine(context).list()

    fun describe(task: ScheduledTask, zone: ZoneId = ZoneId.systemDefault()) =
        "${task.id}: \"${task.prompt}\" — ${ScheduleSpec.format(task, zone)} (${if (task.enabled) "enabled" else "disabled"})" +
            (task.nextAtMs?.let { ", next ${java.time.Instant.ofEpochMilli(it)}" } ?: "")

    fun create(
        context: Context, prompt: String, spec: String, target: Target = Target.AUTO, source: String = ScheduledTask.SOURCE_UI,
    ): Result<ScheduledTask> = runCatching {
        val engine = Schedules.engine(context)
        val now = System.currentTimeMillis()
        val p = ScheduleSpec.parse(spec, now, ZoneId.systemDefault())
        val task = ScheduledTask(engine.newId(), prompt.trim(), target, repeat = p.repeat, minuteOfDay = p.minuteOfDay,
            onceAtMs = p.onceAtMs, anchorMs = p.anchorMs, source = source)
        ScheduleMath.validate(task, now).firstOrNull()?.let { throw ScheduleSpec.SpecException(it) }
        engine.upsert(task, checkConflicts = true)
    }

    /** [source] replaces the task's author when given (the agent's tools mark instructions they rewrite as [ScheduledTask.SOURCE_CHAT]). */
    fun update(
        context: Context, id: String, prompt: String? = null, spec: String? = null, enabled: Boolean? = null, target: Target? = null,
        source: String? = null,
    ): Result<ScheduledTask> =
        runCatching {
            val engine = Schedules.engine(context)
            val old = engine.get(id) ?: throw IllegalArgumentException("No scheduled task with id $id.")
            val now = System.currentTimeMillis()
            var t = old.copy(prompt = prompt?.trim() ?: old.prompt, enabled = enabled ?: old.enabled, target = target ?: old.target,
                source = source ?: old.source)
            if (spec != null) {
                val p = ScheduleSpec.parse(spec, now, ZoneId.systemDefault())
                t = t.copy(repeat = p.repeat, minuteOfDay = p.minuteOfDay, onceAtMs = p.onceAtMs, anchorMs = p.anchorMs)
            }
            ScheduleMath.validate(t, now).firstOrNull()?.takeIf { t.enabled }?.let { throw ScheduleSpec.SpecException(it) }
            engine.upsert(t, checkConflicts = true)
        }

    fun delete(context: Context, id: String): Boolean {
        val engine = Schedules.engine(context)
        if (engine.get(id) == null) return false
        engine.delete(id)
        return true
    }
}

/**
 * The agent core's scheduled-task port ([com.eggman.backstage.tools.ScheduledTaskApi]) on top of the same store and
 * alarms as the Automate screen, so tasks the model creates are listed, armed and run like the user's own. The
 * tools speak 5-field cron; invalid input, unknown ids and the 5-minute conflict rule throw
 * IllegalArgumentException with a readable message.
 */
class AgentScheduledTasks(context: Context, private val zone: () -> ZoneId = ZoneId::systemDefault) :
    com.eggman.backstage.tools.ScheduledTaskApi {
    private val app = context.applicationContext

    private fun info(t: ScheduledTask) = com.eggman.backstage.tools.ScheduledTaskInfo(
        id = t.id,
        description = t.prompt,
        cron = ScheduleCron.of(t, zone()) ?: ScheduleSpec.format(t, zone()),
        enabled = t.enabled,
        nextRunMs = t.nextAtMs,
        schedule = ScheduleMath.describe(t, zone(), java.util.Locale.ENGLISH),
    )

    override fun list(): List<com.eggman.backstage.tools.ScheduledTaskInfo> = ScheduledTasksApi.list(app).map(::info)

    /**
     * Everything created through this port is model-written ([ScheduledTask.SOURCE_CHAT]) whatever [source] claims:
     * its instruction never waives confirmations when it runs (see [com.eggman.backstage.agent.ConfirmationWaiver]).
   */
    override fun create(description: String, cron: String, source: String): com.eggman.backstage.tools.ScheduledTaskInfo {
        require(description.isNotBlank()) { "description is empty." }
        val spec = requireCron(cron)
        return info(ScheduledTasksApi.create(app, description, spec, source = ScheduledTask.SOURCE_CHAT).getOrElse { throw readable(it) })
    }

    /** A rewritten instruction becomes model-written ([ScheduledTask.SOURCE_CHAT]), even on a task the user created. */
    override fun update(id: String, description: String?, cron: String?, enabled: Boolean?): com.eggman.backstage.tools.ScheduledTaskInfo {
        require(description == null || description.isNotBlank()) { "description is empty." }
        val spec = cron?.let(::requireCron)
        val source = ScheduledTask.SOURCE_CHAT.takeIf { description != null }
        return info(ScheduledTasksApi.update(app, id, description, spec, enabled, source = source).getOrElse { throw readable(it) })
    }

    override fun delete(id: String): Boolean = ScheduledTasksApi.delete(app, id)

    private fun requireCron(cron: String): String {
        val c = cron.trim().replace(Regex("\\s+"), " ")
        require(c.split(' ').size == 5) { "cron_expression must have 5 fields (minute hour day month weekday), got \"$cron\"." }
        return c
    }

    private fun readable(t: Throwable): IllegalArgumentException =
        t as? IllegalArgumentException ?: IllegalArgumentException(t.message ?: "Could not save the scheduled task.")
}
