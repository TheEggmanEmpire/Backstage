package com.eggman.backstage.tools

import com.eggman.backstage.exec.TargetKind
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NO_TARGET = emptySet<TargetKind>()

// ---- Alarm module (system clock app via AlarmClock intents) ---------------------------------

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description = "Creates an alarm in the system clock app. hour is on the 24-hour clock; message is an optional label."
    override val schema = Schema.obj("hour" to Schema.int("0-23"), "minute" to Schema.int("0-59"), "message" to Schema.str("Optional label"),
        required = listOf("hour", "minute"))
    override val targets = NO_TARGET
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val alarms = ctx.services.alarms ?: return ToolResult.error("No clock app on this phone handles alarms.")
        val h = args.int("hour"); val m = args.int("minute")
        if (h !in 0..23) throw ToolArgumentException("hour must be 0-23")
        if (m !in 0..59) throw ToolArgumentException("minute must be 0-59")
        val cfg = ctx.services.modules()
        val label = args.optString("message")
        return if (alarms.setAlarm(h, m, label, cfg.alarmVibrate, cfg.alarmSkipUi)) ToolResult("Alarm set for %02d:%02d".format(h, m) + (label?.let { " – $it" } ?: "") + ".")
        else ToolResult.error("No clock app accepted the alarm.")
    }
}

class SetTimerTool : Tool {
    override val name = "set_timer"
    override val description = "Start a countdown timer in the clock app."
    override val schema = Schema.obj("seconds" to Schema.int("Length in seconds (1-86400)"), "message" to Schema.str("Optional label"), required = listOf("seconds"))
    override val targets = NO_TARGET
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val alarms = ctx.services.alarms ?: return ToolResult.error("No clock app on this phone handles timers.")
        val s = args.int("seconds")
        if (s !in 1..86_400) throw ToolArgumentException("seconds must be 1-86400")
        return if (alarms.setTimer(s, args.optString("message"), ctx.services.modules().alarmSkipUi)) ToolResult("Timer started for ${s / 60} min ${s % 60} s.")
        else ToolResult.error("No clock app accepted the timer.")
    }
}

class GetAlarmsTool : Tool {
    override val name = "get_alarms"
    override val description = "Alarm queries: action=next (default) tells the next alarm; show opens the alarm list; dismiss or snooze act on a ringing alarm."
    override val schema = Schema.obj("action" to Schema.enum("What to do", "next", "list", "show", "open", "dismiss", "snooze"))
    override val targets = NO_TARGET
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val alarms = ctx.services.alarms ?: return ToolResult.error("No clock app on this phone handles alarms.")
        return when (args.optString("action")?.lowercase() ?: "next") {
            "next", "list" -> alarms.nextAlarmMs()?.let {
                val z = ZoneId.of(ctx.services.timeZoneId())
                ToolResult("Next alarm: " + Instant.ofEpochMilli(it).atZone(z).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)) + ".")
            } ?: ToolResult("No upcoming alarms.")
            "show", "open" -> if (alarms.showAlarms()) ToolResult("Opened the alarm list.") else ToolResult.error("Could not open the clock app.")
            "dismiss" -> if (alarms.dismissAlarm()) ToolResult("Dismissed the alarm.") else ToolResult.error("Could not dismiss the alarm.")
            "snooze" -> if (alarms.snoozeAlarm()) ToolResult("Snoozed the alarm.") else ToolResult.error("Could not snooze the alarm.")
            else -> throw ToolArgumentException("action must be next, show, dismiss or snooze")
        }
    }
}

class CancelAlarmTool : Tool {
    override val name = "cancel_alarm"
    override val description = "Android does not let apps delete alarms: this opens the alarm list so the alarm can be switched off there (on screen) or by the user."
    override val schema = Schema.obj()
    override val targets = NO_TARGET
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val alarms = ctx.services.alarms ?: return ToolResult.error("No clock app on this phone handles alarms.")
        return if (alarms.showAlarms()) ToolResult("Opened the alarm list: switch the alarm off there.") else ToolResult.error("Could not open the clock app.")
    }
}

// ---- Weather module (OpenWeatherMap with the user's key) --------------------------------------

object Weather {
    fun units(u: String) = if (u.equals("imperial", true) || u.equals("fahrenheit", true)) "imperial" else "metric"

    fun current(json: JSONObject, units: String): JSONObject {
        val main = json.optJSONObject("main") ?: JSONObject()
        val w = json.optJSONArray("weather")?.optJSONObject(0)
        val wind = json.optJSONObject("wind")?.optDouble("speed")
        val sym = if (units == "imperial") "°F" else "°C"
        return JSONObject().put("success", true)
            .put("location", json.optString("name") + (json.optJSONObject("sys")?.optString("country")?.let { if (it.isNotBlank()) ", $it" else "" } ?: ""))
            .put("temperature", "%.0f%s".format(main.optDouble("temp"), sym))
            .put("condition", w?.optString("main").orEmpty())
            .put("description", w?.optString("description").orEmpty())
            .put("humidity", "${main.optInt("humidity")}%")
            .put("wind", wind?.let { if (units == "imperial") "%.0f mph".format(it) else "%.0f m/s".format(it) } ?: "")
    }

    fun forecast(json: JSONObject, units: String, days: Int, zone: ZoneId, nowMs: Long): JSONObject {
        val sym = if (units == "imperial") "°F" else "°C"
        val list = json.optJSONArray("list") ?: JSONArray()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        data class Day(var hi: Double = -1e9, var lo: Double = 1e9, val conditions: MutableMap<String, Int> = LinkedHashMap())
        val byDay = LinkedHashMap<java.time.LocalDate, Day>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val date = Instant.ofEpochSecond(item.optLong("dt")).atZone(zone).toLocalDate()
            val d = byDay.getOrPut(date) { Day() }
            val m = item.optJSONObject("main") ?: continue
            d.hi = maxOf(d.hi, m.optDouble("temp_max", m.optDouble("temp")))
            d.lo = minOf(d.lo, m.optDouble("temp_min", m.optDouble("temp")))
            item.optJSONArray("weather")?.optJSONObject(0)?.optString("main")?.let { c -> d.conditions[c] = (d.conditions[c] ?: 0) + 1 }
        }
        val fmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
        val arr = JSONArray()
        byDay.entries.take(days).forEach { (date, d) ->
            arr.put(JSONObject().put("date", if (date == today) "Today" else date.format(fmt))
                .put("high", "%.0f%s".format(d.hi, sym)).put("low", "%.0f%s".format(d.lo, sym))
                .put("condition", d.conditions.maxByOrNull { it.value }?.key.orEmpty()))
        }
        val city = json.optJSONObject("city")
        return JSONObject().put("success", true).put("location", city?.optString("name").orEmpty()).put("forecast", arr)
    }
}

private fun weatherParams(ctx: ToolContext, args: Args): Triple<String, String, String>? {
    val cfg = ctx.services.modules()
    if (cfg.weatherApiKey.isBlank()) return null
    val loc = args.optString("location") ?: cfg.weatherDefaultLocation.takeIf { it.isNotBlank() }
        ?: throw ToolArgumentException("location is required: the weather module has no home location set")
    return Triple(cfg.weatherApiKey, Weather.units(cfg.weatherUnits), loc)
}

class GetWeatherTool : Tool {
    override val name = "get_weather"
    override val description = "Current weather for a location (OpenWeatherMap). Without a configured key use web_search instead."
    override val schema = Schema.obj("location" to Schema.str("City, e.g. \"Berlin\" or \"Paris,FR\" (optional with a default location)"))
    override val targets = NO_TARGET
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val (key, units, loc) = weatherParams(ctx, args) ?: return ToolResult.error("The weather module is not configured (OpenWeatherMap API key). Use web_search.")
        val r = ctx.services.httpGet("https://api.openweathermap.org/data/2.5/weather?q=${WebText.encode(loc)}&appid=${WebText.encode(key)}&units=$units")
        if (r.code != 200) return ToolResult.error("Weather service error (HTTP ${r.code}).")
        val o = Weather.current(JSONObject(r.body), units)
        return ToolResult(o.toString(), data = o)
    }
}

class GetWeatherForecastTool : Tool {
    override val name = "get_weather_forecast"
    override val description = "Daily forecast (high/low/condition) for 1-7 days (OpenWeatherMap)."
    override val schema = Schema.obj("location" to Schema.str("City (optional with a default location)"), "days" to Schema.int("1-7, default 5"))
    override val targets = NO_TARGET
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val (key, units, loc) = weatherParams(ctx, args) ?: return ToolResult.error("The weather module is not configured (OpenWeatherMap API key). Use web_search.")
        val days = (args.optInt("days") ?: 5).coerceIn(1, 7)
        val r = ctx.services.httpGet("https://api.openweathermap.org/data/2.5/forecast?q=${WebText.encode(loc)}&appid=${WebText.encode(key)}&units=$units&cnt=${days * 8}")
        if (r.code != 200) return ToolResult.error("Weather service error (HTTP ${r.code}).")
        val o = Weather.forecast(JSONObject(r.body), units, days, ZoneId.of(ctx.services.timeZoneId()), ctx.services.nowMs())
        return ToolResult(o.toString(), data = o)
    }
}

// ---- Memory ------------------------------------------------------------------------------------

class MemorySaveTool : Tool {
    override val name = "memory_save"
    override val description = "Remember a lasting fact or preference about the user for future conversations (e.g. \"My wife is Anna, her number is in contacts as 'Anna ❤'\"). Not for temporary task details."
    override val schema = Schema.obj("content" to Schema.str("The fact to remember"), "reason" to Schema.str("Why it is worth remembering"), required = listOf("content"))
    override val targets = NO_TARGET
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val mem = ctx.services.memory ?: return ToolResult.error("Memory is turned off.")
        val item = mem.save(args.string("content").take(2_000), "agent")
        return ToolResult("Saved to memory (id ${item.id}).")
    }
}

class MemorySearchTool : Tool {
    override val name = "memory_search"
    override val description = "Search what you remembered about the user (names, preferences, accounts, habits)."
    override val schema = Schema.obj("query" to Schema.str("What to look for"), "maxResults" to Schema.int("Default 5"), required = listOf("query"))
    override val targets = NO_TARGET
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val mem = ctx.services.memory ?: return ToolResult.error("Memory is turned off.")
        val hits = mem.search(args.string("query"), (args.optInt("maxResults") ?: 5).coerceIn(1, 20))
        if (hits.isEmpty()) return ToolResult("No memories found.")
        return ToolResult(hits.joinToString("\n") { "- (%.2f) %s".format(it.score, it.content) })
    }
}

// ---- Scheduled tasks (implementation registered by the surfaces package) ----------------------

private fun scheduler(ctx: ToolContext): ScheduledTaskApi =
    ctx.services.scheduler ?: throw ToolFailure("This build has no scheduler attached.")

private fun ScheduledTaskInfo.describe(zone: ZoneId) = "[$id] \"$description\" — $schedule" + (if (!enabled) " (disabled)" else "") +
    (nextRunMs?.let { " — next: " + Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)) } ?: "")

private suspend fun schedGuard(block: suspend () -> ToolResult): ToolResult = try { block() } catch (e: ToolFailure) {
    ToolResult.error(e.message ?: "failed")
} catch (e: IllegalArgumentException) {
    ToolResult.error(e.message ?: "invalid")
}

/**
 * A scheduled task outlives the request and later runs unattended, so creating or changing one needs a person's
 * approval that nothing waives ([AgentHooks.confirmAlways]). The question shows exactly what will be stored, worded
 * by us rather than by the model. Returns a refusal, or null when approved.
 */
private suspend fun approveScheduleChange(ctx: ToolContext, action: String): ToolResult? {
    val hooks = ctx.hooks ?: return ToolResult.error("Not done: changing scheduled tasks needs the user's approval, and nobody can approve here.")
    return if (hooks.confirmAlways(action)) null
    else ToolResult.error("Not done: the user did not approve this change to scheduled tasks. Do not retry it unless the user asks again.")
}

private const val MAX_SCHEDULE_INSTRUCTION_CHARS = 2_000
private fun checkedScheduleInstruction(text: String): String {
    require(text.length <= MAX_SCHEDULE_INSTRUCTION_CHARS) { "Scheduled task instruction must be at most 2000 characters" }
    return text
}
private fun quoted(text: String) = "\u201C" + text + "\u201D"

class CreateScheduledTaskTool : Tool {
    override val name = "create_scheduled_task"
    override val description = "Schedule a task to run automatically. cron_expression is 5-field Unix cron (minute hour day month weekday): " +
        "\"0 8 * * *\" daily 08:00, \"30 7 * * 1-5\" weekdays 07:30, \"*/30 * * * *\" every 30 min, \"0 * * * *\" hourly."
    override val schema = Schema.obj("description" to Schema.str("What the agent should do on each run, worded as the user would ask it (maximum 2000 characters)").put("maxLength", MAX_SCHEDULE_INSTRUCTION_CHARS),
        "cron_expression" to Schema.str("5-field cron"), required = listOf("description", "cron_expression"))
    override val targets = NO_TARGET
    override val agentOnly = true
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args) = schedGuard {
        val api = scheduler(ctx)
        val description = checkedScheduleInstruction(args.string("description"))
        val cron = args.string("cron_expression").trim()
        approveScheduleChange(ctx, "Create a scheduled task (cron \"$cron\") that will run on its own, without you, with this instruction: ${quoted(description)}")
            ?.let { return@schedGuard it }
        val t = api.create(description, cron, "CHAT")
        ToolResult("Scheduled: " + t.describe(ZoneId.of(ctx.services.timeZoneId())))
    }
}

class ListScheduledTasksTool : Tool {
    override val name = "list_scheduled_tasks"
    override val description = "List scheduled tasks (id, description, schedule, enabled, next run)."
    override val schema = Schema.obj()
    override val targets = NO_TARGET
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args) = schedGuard {
        val list = scheduler(ctx).list()
        val z = ZoneId.of(ctx.services.timeZoneId())
        if (list.isEmpty()) ToolResult("No scheduled tasks.") else ToolResult(list.joinToString("\n") { it.describe(z) })
    }
}

class UpdateScheduledTaskTool : Tool {
    override val name = "update_scheduled_task"
    override val description = "Change a scheduled task: description, cron_expression or enabled (omitted fields stay)."
    override val schema = Schema.obj("id" to Schema.str("Task id"), "description" to Schema.str("New description (maximum 2000 characters)").put("maxLength", MAX_SCHEDULE_INSTRUCTION_CHARS),
        "cron_expression" to Schema.str("New 5-field cron"), "enabled" to Schema.bool("Enable or disable"), required = listOf("id"))
    override val targets = NO_TARGET
    override val agentOnly = true
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args) = schedGuard {
        val api = scheduler(ctx)
        val id = args.string("id")
        val description = args.optString("description")?.let(::checkedScheduleInstruction)
        val cron = args.optString("cron_expression")?.trim()?.takeIf { it.isNotEmpty() }
        val enabled = args.optBool("enabled")
        // Only switching a task off needs no approval; anything that makes it run (differently) does.
        if (description != null || cron != null || enabled == true) {
            val old = api.list().firstOrNull { it.id == id } ?: throw ToolFailure("No scheduled task with id $id.")
            // Show the complete instruction that will run, once, including when only re-enabling an old task.
            val nextInstruction = checkedScheduleInstruction(description ?: old.description)
            val action = "Change scheduled task ${quoted(id)} to run on its own, without you " +
                "(cron ${quoted(cron ?: old.cron)}, enabled ${enabled ?: old.enabled}) with this instruction: " +
                quoted(nextInstruction)
            val previous = if (description != null) " Previous instruction: ${quoted(old.description)}" else ""
            // Optional context may be omitted, but the instruction being approved is always shown in full.
            val question = if (action.length + previous.length <= 4_000) action + previous else action
            approveScheduleChange(ctx, question)?.let { return@schedGuard it }
        }
        val t = api.update(id, description, cron, enabled)
        ToolResult("Task updated: " + t.describe(ZoneId.of(ctx.services.timeZoneId())))
    }
}

class DeleteScheduledTaskTool : Tool {
    override val name = "delete_scheduled_task"
    override val description = "Delete a scheduled task by id."
    override val schema = Schema.obj("id" to Schema.str("Task id"), required = listOf("id"))
    override val targets = NO_TARGET
    override val agentOnly = true
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args) = schedGuard {
        if (scheduler(ctx).delete(args.string("id"))) ToolResult("Deleted scheduled task ${args.string("id")}.")
        else ToolResult.error("No scheduled task with id ${args.string("id")}.")
    }
}
