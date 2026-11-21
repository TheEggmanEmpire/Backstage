package com.eggman.backstage.schedule

import com.eggman.backstage.agent.api.Target
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** How a scheduled task repeats. */
sealed interface Repeat {
    /** Runs once at [ScheduledTask.onceAtMs]. */
    data object Once : Repeat
    /** Every day at [ScheduledTask.minuteOfDay]. */
    data object Daily : Repeat
    /** On the given weekdays at [ScheduledTask.minuteOfDay]. */
    data class Weekly(val days: Set<DayOfWeek>) : Repeat
    /** Every [minutes] minutes, aligned to [ScheduledTask.anchorMs]. */
    data class Interval(val minutes: Int) : Repeat
}

data class ScheduledTask(
    val id: String,
    val prompt: String,
    val target: Target = Target.AUTO,
    val enabled: Boolean = true,
    val repeat: Repeat = Repeat.Daily,
    /** Local wall-clock time for Daily/Weekly (0..1439). */
    val minuteOfDay: Int = 9 * 60,
    /** Absolute time for Once. */
    val onceAtMs: Long = 0L,
    /** Alignment origin for Interval. */
    val anchorMs: Long = 0L,
    /** Next armed fire time (persisted so missed runs can be detected after reboot). */
    val nextAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastStatus: String? = null,
    val lastResult: String? = null,
    val createdAtMs: Long = 0L,
    /** Who created it: [SOURCE_UI] (the Automate screen) or [SOURCE_CHAT] (the agent's schedule tools). */
    val source: String = SOURCE_UI,
) {
    val label: String get() = prompt.lineSequence().firstOrNull().orEmpty().take(60)

    companion object {
        const val SOURCE_UI = "UI"
        const val SOURCE_CHAT = "CHAT"
    }
}

/** JSON persistence format (version 1). */
object ScheduleCodec {
    private const val VERSION = 1

    fun encode(tasks: List<ScheduledTask>): String = JSONObject()
        .put("v", VERSION)
        .put("tasks", JSONArray().apply { tasks.forEach { put(encodeTask(it)) } })
        .toString()

    fun decode(json: String?): List<ScheduledTask> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("tasks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { decodeTask(it) }.getOrNull() } }
    }

    fun encodeTask(t: ScheduledTask): JSONObject = JSONObject()
        .put("id", t.id)
        .put("prompt", t.prompt)
        .put("target", t.target.name)
        .put("enabled", t.enabled)
        .put("repeat", when (val r = t.repeat) {
            Repeat.Once -> JSONObject().put("type", "once")
            Repeat.Daily -> JSONObject().put("type", "daily")
            is Repeat.Weekly -> JSONObject().put("type", "weekly")
                .put("days", JSONArray().apply { r.days.sorted().forEach { put(it.value) } })
            is Repeat.Interval -> JSONObject().put("type", "interval").put("minutes", r.minutes)
        })
        .put("minuteOfDay", t.minuteOfDay)
        .put("onceAtMs", t.onceAtMs)
        .put("anchorMs", t.anchorMs)
        .putOpt("nextAtMs", t.nextAtMs)
        .putOpt("lastRunAtMs", t.lastRunAtMs)
        .putOpt("lastStatus", t.lastStatus)
        .putOpt("lastResult", t.lastResult)
        .put("createdAtMs", t.createdAtMs)
        .put("source", t.source)

    fun decodeTask(o: JSONObject): ScheduledTask {
        val r = o.getJSONObject("repeat")
        val repeat = when (r.getString("type")) {
            "once" -> Repeat.Once
            "daily" -> Repeat.Daily
            "weekly" -> {
                val days = r.optJSONArray("days") ?: JSONArray()
                Repeat.Weekly((0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet())
            }
            "interval" -> Repeat.Interval(r.getInt("minutes"))
            else -> error("unknown repeat")
        }
        return ScheduledTask(
            id = o.getString("id"),
            prompt = o.getString("prompt"),
            target = runCatching { Target.valueOf(o.optString("target")) }.getOrDefault(Target.AUTO),
            enabled = o.optBoolean("enabled", true),
            repeat = repeat,
            minuteOfDay = o.optInt("minuteOfDay", 540),
            onceAtMs = o.optLong("onceAtMs", 0L),
            anchorMs = o.optLong("anchorMs", 0L),
            nextAtMs = if (o.has("nextAtMs")) o.getLong("nextAtMs") else null,
            lastRunAtMs = if (o.has("lastRunAtMs")) o.getLong("lastRunAtMs") else null,
            lastStatus = o.optString("lastStatus").takeIf { o.has("lastStatus") },
            lastResult = o.optString("lastResult").takeIf { o.has("lastResult") },
            createdAtMs = o.optLong("createdAtMs", 0L),
            source = o.optString("source", ScheduledTask.SOURCE_UI).ifEmpty { ScheduledTask.SOURCE_UI },
        )
    }
}
