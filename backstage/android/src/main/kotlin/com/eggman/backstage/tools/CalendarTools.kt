package com.eggman.backstage.tools

import com.eggman.backstage.exec.TargetKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Calendar module (CalendarContract): list calendars, read, create, modify and delete events,
 * with reminders. Argument names and JSON results follow the calendar tools; ISO `start`/`end`
 * strings are accepted too.
 */
internal object Cal {
    private val NONE = emptySet<TargetKind>()
    val targets get() = NONE
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)", Locale.ENGLISH)

    fun service(ctx: ToolContext, write: Boolean): CalendarService {
        val c = ctx.services.calendar ?: throw ToolFailure("This phone has no calendar provider Backstage can use.")
        if (!c.hasPermission(write)) throw ToolFailure("Calendar permission not granted. Allow calendar access for Backstage in Settings (calendar module), or use the calendar app on screen.")
        return c
    }

    fun zone(ctx: ToolContext): ZoneId = ZoneId.of(ctx.services.timeZoneId())

    fun date(v: String, name: String): LocalDate = try { LocalDate.parse(v.trim()) } catch (e: DateTimeParseException) {
        throw ToolArgumentException("$name must be YYYY-MM-DD, got '$v'")
    }

    fun time(v: String, name: String): LocalTime = try { LocalTime.parse(v.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it }) } catch (e: DateTimeParseException) {
        throw ToolArgumentException("$name must be HH:MM (24 h), got '$v'")
    }

    fun format(ms: Long, allDay: Boolean, zone: ZoneId): String =
        if (allDay) Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).format(dayFmt) else Instant.ofEpochMilli(ms).atZone(zone).format(timeFmt)

    fun eventJson(e: CalendarEvent, zone: ZoneId, reminders: List<Int>) = JSONObject()
        .put("id", e.id).put("title", e.title).put("description", e.description)
        .put("start", format(e.startMs, e.allDay, zone)).put("end", format(e.endMs, e.allDay, zone))
        .put("location", e.location).put("isAllDay", e.allDay).put("calendarId", e.calendarId)
        .put("reminders", JSONArray(reminders))

    /** Start/end from either ISO (`start`, `end`) or split (`start_date`, `start_time`, `end_date`, `end_time`, `duration_minutes`). */
    data class Span(val startMs: Long, val endMs: Long, val allDay: Boolean)

    fun span(args: Args, zone: ZoneId): Span {
        if (args.has("start")) {
            val s = DateParsing.parse(args.string("start"), zone)
            val end = args.optString("end")?.let { DateParsing.parse(it, zone).epochMs }
            if (s.dateOnly) {
                val d = LocalDate.parse(args.string("start").trim().take(10))
                val utc = d.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
                val days = end?.let {
                    val endDate = Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
                    maxOf(1L, java.time.temporal.ChronoUnit.DAYS.between(d, endDate))
                } ?: 1L
                return Span(utc, utc + days * 86_400_000L, true)
            }
            val e = end ?: (s.epochMs + (args.optInt("duration_minutes") ?: 60) * 60_000L)
            return Span(s.epochMs, e, false)
        }
        val startDate = date(args.string("start_date"), "start_date")
        val endDate = args.optString("end_date")?.let { date(it, "end_date") } ?: startDate
        val startTime = args.optString("start_time")?.let { time(it, "start_time") }
        if (startTime == null) {
            val s = startDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            val e = endDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
            return Span(s, maxOf(e, s + 86_400_000L), true)
        }
        val s = startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val e = args.optString("end_time")?.let { endDate.atTime(time(it, "end_time")).atZone(zone).toInstant().toEpochMilli() }
            ?: (s + (args.optInt("duration_minutes") ?: 60).coerceIn(1, 7 * 24 * 60) * 60_000L)
        return Span(s, e, false)
    }

    fun result(o: JSONObject) = ToolResult(o.toString(), data = o)
    fun error(msg: String) = JSONObject().put("success", false).put("error", msg).let { ToolResult(it.toString(), isError = true, data = it) }
}

/** A tool-level failure reported as a normal error result (not an argument error). */
class ToolFailure(message: String) : RuntimeException(message)

private suspend fun calendarGuard(block: suspend () -> ToolResult): ToolResult = try { block() } catch (e: ToolFailure) { Cal.error(e.message ?: "failed") }

class GetCalendarsTool : Tool {
    override val name = "get_calendars"
    override val description = "List the device calendars (id, name, account) the agent may use."
    override val schema = Schema.obj()
    override val targets = Cal.targets
    override suspend fun run(ctx: ToolContext, args: Args) = calendarGuard {
        val cal = Cal.service(ctx, write = false)
        val enabled = ctx.services.modules().calendarEnabledIds
        val list = cal.calendars().filter { enabled.isEmpty() || it.id in enabled }
        Cal.result(JSONObject().put("success", true).put("count", list.size).put("calendars", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("account", it.account).put("writable", it.writable)) }
        }))
    }
}

class GetCalendarEventsTool : Tool {
    override val name = "get_calendar_events"
    override val description = "Read calendar events: the next `days` days (default 7, max 30) or only today. Faster and more reliable than opening the calendar app."
    override val schema = Schema.obj(
        "days" to Schema.int("1-30, default 7"),
        "today_only" to Schema.bool("Only today's events (overrides days)"),
        "from" to Schema.str("Optional ISO start instead of now"),
        "query" to Schema.str("Optional text filter"),
    )
    override val targets = Cal.targets
    override suspend fun run(ctx: ToolContext, args: Args) = calendarGuard {
        val cal = Cal.service(ctx, write = false)
        val zone = Cal.zone(ctx)
        val cfg = ctx.services.modules()
        val now = ctx.services.nowMs()
        val (from, to) = if (args.bool("today_only", false)) {
            val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            d.atStartOfDay(zone).toInstant().toEpochMilli() to d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            val f = args.optString("from")?.let { DateParsing.parse(it, zone).epochMs } ?: now
            f to f + (args.optInt("days") ?: 7).coerceIn(1, 30) * 86_400_000L
        }
        val events = cal.events(from, to, args.optString("query"))
            .filter { cfg.calendarEnabledIds.isEmpty() || it.calendarId in cfg.calendarEnabledIds }
            .sortedBy { it.startMs }
            .take(cfg.calendarMaxEvents.coerceIn(1, 100))
        Cal.result(JSONObject().put("success", true).put("count", events.size).put("timeZone", zone.id).put("events", JSONArray().apply {
            events.forEach { put(Cal.eventJson(it, zone, cal.reminders(it.id))) }
        }))
    }
}

class CreateCalendarEventTool : Tool {
    override val name = "create_calendar_event"
    override val description = "Create a calendar event directly (no UI). Use the user's literal date and time. Leave out start_time to make it an all-day event. Default duration 60 minutes."
    override val schema = Schema.obj(
        "title" to Schema.str("Event title"),
        "start_date" to Schema.str("YYYY-MM-DD"),
        "start_time" to Schema.str("HH:MM, 24-hour; leave empty for all-day"),
        "end_date" to Schema.str("YYYY-MM-DD (default start_date)"),
        "end_time" to Schema.str("HH:MM (default start + duration)"),
        "duration_minutes" to Schema.int("Default 60"),
        "description" to Schema.str("Optional"),
        "location" to Schema.str("Optional"),
        "calendar_id" to Schema.int("Optional; default calendar otherwise"),
        "reminder_minutes" to Schema.int("Minutes before start; -1 = no reminder; default from settings"),
        "start" to Schema.str("Alternative: ISO start like 2026-03-14T15:30"),
        "end" to Schema.str("Alternative: ISO end"),
        required = listOf("title"),
    )
    override val targets = Cal.targets
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args) = calendarGuard {
        if (!args.has("start") && !args.has("start_date")) throw ToolArgumentException("start_date (YYYY-MM-DD) is required")
        val cal = Cal.service(ctx, write = true)
        val zone = Cal.zone(ctx)
        val cfg = ctx.services.modules()
        val span = Cal.span(args, zone)
        if (span.endMs <= span.startMs) throw ToolArgumentException("the end must be after the start")
        val writable = cal.calendars().filter { it.writable && (cfg.calendarEnabledIds.isEmpty() || it.id in cfg.calendarEnabledIds) }
        val target = args.optInt("calendar_id")?.let { id -> writable.firstOrNull { it.id == id.toLong() } ?: throw ToolFailure("No writable calendar with id $id") }
            ?: cfg.calendarDefaultId?.let { d -> writable.firstOrNull { it.id == d } }
            ?: writable.firstOrNull { it.primary } ?: writable.firstOrNull()
            ?: throw ToolFailure("No writable calendar found on this device.")
        val title = args.string("title")
        val id = cal.insert(target.id, title, span.startMs, span.endMs, span.allDay, args.optString("location"), args.optString("description"),
            if (span.allDay) "UTC" else zone.id)
        val reminder = args.optInt("reminder_minutes") ?: cfg.calendarDefaultReminder
        if (reminder != null && reminder >= 0) cal.setReminders(id, listOf(reminder))
        Cal.result(JSONObject().put("success", true).put("eventId", id)
            .put("message", "Created \"$title\" on ${Cal.format(span.startMs, span.allDay, zone)} in \"${target.name}\"."))
    }
}

class ModifyCalendarEventTool : Tool {
    override val name = "modify_calendar_event"
    override val description = "Change an existing event (get its id from get_calendar_events). Only the given fields change; missing date/time parts keep their current value. reminder_minutes=-1 removes reminders."
    override val schema = Schema.obj(
        "event_id" to Schema.int("Event id"),
        "title" to Schema.str("New title"),
        "start_date" to Schema.str("YYYY-MM-DD"),
        "start_time" to Schema.str("HH:MM"),
        "end_date" to Schema.str("YYYY-MM-DD"),
        "end_time" to Schema.str("HH:MM"),
        "description" to Schema.str("New description"),
        "location" to Schema.str("New location"),
        "reminder_minutes" to Schema.int("-1 removes reminders"),
        required = listOf("event_id"),
    )
    override val targets = Cal.targets
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args) = calendarGuard {
        val cal = Cal.service(ctx, write = true)
        val zone = Cal.zone(ctx)
        val id = args.int("event_id").toLong()
        val ev = cal.event(id) ?: throw ToolFailure("No event with id $id")
        val evZone = if (ev.allDay) ZoneId.of("UTC") else zone
        val oldStart = Instant.ofEpochMilli(ev.startMs).atZone(evZone)
        val oldEnd = Instant.ofEpochMilli(ev.endMs).atZone(evZone)
        val timeChange = listOf("start_date", "start_time", "end_date", "end_time").any { args.has(it) }
        var start: Long? = null
        var end: Long? = null
        if (timeChange) {
            val sd = args.optString("start_date")?.let { Cal.date(it, "start_date") } ?: oldStart.toLocalDate()
            val st = args.optString("start_time")?.let { Cal.time(it, "start_time") } ?: oldStart.toLocalTime()
            val newStart = sd.atTime(st).atZone(evZone)
            val duration = ev.endMs - ev.startMs
            val ed = args.optString("end_date")?.let { Cal.date(it, "end_date") }
            val et = args.optString("end_time")?.let { Cal.time(it, "end_time") }
            val newEnd = if (ed == null && et == null) newStart.plusNanos(duration * 1_000_000)
            else (ed ?: if (args.has("start_date")) sd else oldEnd.toLocalDate()).atTime(et ?: oldEnd.toLocalTime()).atZone(evZone)
            start = newStart.toInstant().toEpochMilli()
            end = newEnd.toInstant().toEpochMilli()
            if (end <= start) throw ToolArgumentException("the end must be after the start")
        }
        val title = args.optString("title")
        val desc = if (args.has("description")) args.json.optString("description") else null
        val loc = if (args.has("location")) args.json.optString("location") else null
        val reminder = args.optInt("reminder_minutes")
        if (!timeChange && title == null && desc == null && loc == null && reminder == null) throw ToolFailure("Nothing to change: give at least one field.")
        if (title != null || desc != null || loc != null || timeChange) {
            if (!cal.update(id, title, start, end, loc, desc)) throw ToolFailure("The calendar did not accept the change.")
        }
        reminder?.let { cal.setReminders(id, if (it < 0) emptyList() else listOf(it)) }
        Cal.result(JSONObject().put("success", true).put("message", "Updated event $id."))
    }
}

class DeleteCalendarEventTool : Tool {
    override val name = "delete_calendar_event"
    override val description = "Remove one event, given the event_id that get_calendar_events reported. The phone asks its user to " +
        "approve the deletion of that exact event first, so do not call confirm_action before it."
    override val schema = Schema.obj("event_id" to Schema.int("Event id"), required = listOf("event_id"))
    override val targets = Cal.targets
    override val mutating = true
    override val irreversible = true
    override suspend fun approvalQuestion(ctx: ToolContext, args: Args): String {
        val cal = Cal.service(ctx, write = true)
        val id = args.int("event_id").toLong()
        val ev = cal.event(id) ?: throw ToolFailure("No event with id $id")
        val zone = runCatching { java.time.ZoneId.of(ctx.services.timeZoneId()) }.getOrDefault(java.time.ZoneId.systemDefault())
        val start = java.time.Instant.ofEpochMilli(ev.startMs).atZone(zone)
        val pattern = if (ev.allDay) "EEE d MMM yyyy" else "EEE d MMM yyyy, HH:mm"
        val whenText = start.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ROOT))
        return "Delete the calendar event “${ev.title.take(200)}” ($whenText)"
    }
    override suspend fun run(ctx: ToolContext, args: Args) = calendarGuard {
        val cal = Cal.service(ctx, write = true)
        val id = args.int("event_id").toLong()
        val ev = cal.event(id) ?: throw ToolFailure("No event with id $id")
        if (!cal.delete(id)) throw ToolFailure("The calendar did not delete the event.")
        Cal.result(JSONObject().put("success", true).put("message", "Deleted \"${ev.title}\"."))
    }
}
