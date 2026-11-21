package com.eggman.backstage.tools

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.ScreenImage
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.llm.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/** What a tool returns to the model (and to MCP clients). */
data class ToolResult(
    val text: String,
    val image: ScreenImage? = null,
    val isError: Boolean = false,
    /** finish: ends the task. */
    val terminal: Boolean = false,
    /** Optional structured payload (MCP structuredContent). */
    val data: JSONObject? = null,
    /** Time spent waiting for the screen to settle after the action (timing; part of the tool's run time). */
    val settleMs: Long = 0,
) {
    companion object {
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** Typed, validating view over the model's JSON arguments. */
class Args(val json: JSONObject) {
    fun has(name: String) = json.has(name) && !json.isNull(name)

    fun string(name: String): String = optString(name) ?: throw ToolArgumentException("Missing required argument '$name'")
    fun optString(name: String): String? = if (has(name)) json.get(name).toString().takeIf { it.isNotEmpty() } else null

    fun int(name: String): Int = optInt(name) ?: throw ToolArgumentException("Missing required integer '$name'")
    fun optInt(name: String): Int? = if (!has(name)) null else when (val v = json.get(name)) {
        is Number -> v.toDouble().let { if (it.isNaN()) null else it.toInt() }
        is String -> v.trim().removePrefix("[").removeSuffix("]").toDoubleOrNull()?.toInt()
            ?: throw ToolArgumentException("'$name' must be a number, got \"$v\"")
        else -> throw ToolArgumentException("'$name' must be a number")
    }

    fun optDouble(name: String): Double? = if (!has(name)) null else when (val v = json.get(name)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: throw ToolArgumentException("'$name' must be a number")
        else -> throw ToolArgumentException("'$name' must be a number")
    }

    fun optBool(name: String): Boolean? = if (!has(name)) null else when (val v = json.get(name)) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) { "true", "yes", "1" -> true; "false", "no", "0" -> false; else -> null }
        is Number -> v.toInt() != 0
        else -> null
    }

    fun bool(name: String, def: Boolean) = optBool(name) ?: def

    fun optArray(name: String): JSONArray? = if (has(name)) json.optJSONArray(name) else null

    fun stringList(name: String): List<String> = optArray(name)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it)?.toString() } } ?: emptyList()
}

/** Callbacks only the in-app agent loop provides (MCP clients plan and talk to their own user). */
interface AgentHooks {
    fun onPlan(steps: List<PlanStep>)
    /** Returns the user's answer, or null when nobody answered (non-interactive timeout). */
    suspend fun ask(question: String, choices: List<String>): String?
    /** Returns true when the action may proceed. */
    suspend fun confirm(action: String): Boolean
    /**
     * Like [confirm], but nothing skips the question: no "without asking" in the request, no confirmation setting and
     * no auto-allow policy for unattended tasks. Only a person's answer approves. For changes that outlive the task.
   */
    suspend fun confirmAlways(action: String): Boolean = confirm(action)
    /**
     * Approval for a [Tool.irreversible] tool, asked by [ToolRegistry.execute] before every run . Like
     * [confirmAlways] (the confirmation setting and the auto-allow policy for unattended tasks never skip it), except
     * that an explicit trusted waiver for this task approves: the user's own "…without asking", or the
     * `allow_irreversible` flag of an authenticated caller.
   */
    suspend fun confirmIrreversible(action: String): Boolean = confirmAlways(action)
    fun onFinish(summary: String, success: Boolean)
}

data class HttpResult(val code: Int, val body: String, val contentType: String? = null, val finalUrl: String = "")

data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean, val primary: Boolean)
data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
)

/** Calendar access via CalendarContract (Android adapter) or a fake in tests. */
interface CalendarService {
    fun hasPermission(write: Boolean): Boolean
    fun calendars(): List<CalendarInfo>
    fun events(fromMs: Long, toMs: Long, query: String?): List<CalendarEvent>
    fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, location: String?, description: String?, timeZone: String): Long
    fun event(id: Long): CalendarEvent? = null
    /** Partial update: null fields keep their value. */
    fun update(id: Long, title: String?, startMs: Long?, endMs: Long?, location: String?, description: String?): Boolean = false
    fun delete(id: Long): Boolean = false
    /** Minutes before start for each reminder of an event. */
    fun reminders(eventId: Long): List<Int> = emptyList()
    /** Replaces the event's reminders (empty list removes them). */
    fun setReminders(eventId: Long, minutes: List<Int>) = Unit
}

/** Alarms live in the system clock app (AlarmClock intents); apps cannot read or delete them. */
interface AlarmService {
    fun setAlarm(hour: Int, minute: Int, message: String?, vibrate: Boolean, skipUi: Boolean): Boolean
    fun nextAlarmMs(): Long?
    fun showAlarms(): Boolean
    fun dismissAlarm(): Boolean
    fun snoozeAlarm(): Boolean
    fun setTimer(seconds: Int, message: String?, skipUi: Boolean): Boolean = false
}

data class MemoryItem(val id: String, val content: String, val source: String, val createdMs: Long, val score: Double = 0.0)

/** Long-term memory the agent can write to and search (local only). */
interface MemoryService {
    fun save(content: String, source: String): MemoryItem
    fun search(query: String, max: Int): List<MemoryItem>
    fun all(): List<MemoryItem>
    fun delete(id: String): Boolean
}

data class ScheduledTaskInfo(
    val id: String,
    val description: String,
    val cron: String,
    val enabled: Boolean,
    val nextRunMs: Long?,
    val schedule: String = cron,
)

/**
 * Scheduled tasks (owned by the surfaces package: storage, alarms, notifications). The agent's
 * create/list/update/delete_scheduled_task tools appear only when an implementation is registered
 * in [AgentExtensions.scheduler]. Methods throw IllegalArgumentException with a readable message
 * for invalid cron expressions, unknown ids or the 5-minute conflict rule.
 */
interface ScheduledTaskApi {
    fun list(): List<ScheduledTaskInfo>
    fun create(description: String, cron: String, source: String): ScheduledTaskInfo
    fun update(id: String, description: String?, cron: String?, enabled: Boolean?): ScheduledTaskInfo
    fun delete(id: String): Boolean
}

/** Optional capabilities other packages plug in at runtime. */
object AgentExtensions {
    @Volatile var scheduler: ScheduledTaskApi? = null
}

/** User configuration of the tool modules (weather, search, alarm, calendar). */
data class ModuleConfig(
    val weatherApiKey: String = "",
    val weatherUnits: String = "metric",
    val weatherDefaultLocation: String = "",
    val searchProvider: String = "duckduckgo",
    val searchApiKey: String = "",
    val searxngUrl: String = "",
    /** Custom search engine: URL template with {query} (and optional {count}), optional header. */
    val customSearchUrl: String = "",
    val customSearchHeaderName: String = "",
    val customSearchHeaderValue: String = "",
    /** browser_search: URL template with {query}; empty = the default browser's own search engine. */
    val browserSearchUrl: String = "",
    val alarmVibrate: Boolean = true,
    val alarmSkipUi: Boolean = true,
    val calendarDefaultId: Long? = null,
    val calendarEnabledIds: Set<Long> = emptySet(),
    val calendarMaxEvents: Int = 20,
    val calendarDefaultReminder: Int? = null,
    val memoryEnabled: Boolean = true,
)

data class NotificationEntry(val packageName: String, val appLabel: String, val text: String, val timeMs: Long)

/**
 * Device capabilities tools need beyond the screen. The Android implementation lives in
 * [AndroidDeviceServices]; tests pass a fake.
 */
interface DeviceServices {
    fun installedApps(): List<AppEntry>
    /**
     * The user's default app per role (browser, camera, email, maps,...: [com.eggman.backstage.exec.DefaultAppsCache]),
     * used by the prompt, default_apps and launch_app's categories. Empty when unknown.
   */
    fun defaultApps(): List<com.eggman.backstage.exec.DefaultApp> = emptyList()
    suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): HttpResult
    val calendar: CalendarService?
    fun clipboardGet(): String?
    fun clipboardSet(text: String): Boolean
    fun recentNotifications(): List<NotificationEntry>
    val desktop: DesktopHost?
    /** Stop and start the desktop for a clean screen (see [com.eggman.backstage.agent.DesktopRefresh]); null on failure. */
    suspend fun restartDesktop(): DesktopHost.Geometry? =
        desktop?.let { com.eggman.backstage.agent.DesktopRefresh.restart(it, DesktopHost.Presentation.HIDDEN) }
    fun nowMs(): Long = System.currentTimeMillis()
    fun timeZoneId(): String = java.util.TimeZone.getDefault().id
    /** Search engine: "duckduckgo" or a SearXNG base URL (legacy; see [modules]). */
    fun searchEngine(): String = "duckduckgo"
    suspend fun httpPost(url: String, headers: Map<String, String>, jsonBody: String): HttpResult = HttpResult(501, "POST not supported")
    val alarms: AlarmService? get() = null
    val memory: MemoryService? get() = null
    val scheduler: ScheduledTaskApi? get() = AgentExtensions.scheduler
    /** Installed skills (SKILL.md folders, ); null where skills are not available. */
    val skills: com.eggman.backstage.skills.SkillStore? get() = null
    fun modules(): ModuleConfig = ModuleConfig(searchProvider = searchEngine().let { if (it.startsWith("http")) "searxng" else it },
        searxngUrl = searchEngine().takeIf { it.startsWith("http") }.orEmpty())
}

/** Per-call environment for tools. */
interface ToolContext {
    /** Target the task currently works on (null = not chosen yet / no device needed). */
    val currentTarget: TargetKind?
    /** Executor for [kind] (null = current/default), starting the desktop if needed. Throws with a readable message when unavailable. */
    suspend fun executor(kind: TargetKind? = null): Executor
    /** Switch the task's working target (desktop_start, explicit target arguments). */
    fun switchTarget(kind: TargetKind)
    val services: DeviceServices
    val hooks: AgentHooks?
    val visionEnabled: Boolean
    val autoScreenshot: Boolean
    /** How long the screen must stay unchanged after an action before it is read (0 = no polling). */
    val stabilityMs: Long get() = com.eggman.backstage.exec.AgentPace.stabilityMs()
    /**
     * Whether this task may act on the phone's own screen ([com.eggman.backstage.exec.TargetResolver.phoneAllowed]).
     * False for a task gated to the desktop: phone executors and phone window changes are refused.
   */
    val phoneAllowed: Boolean get() = true
    /** False on a task the user put on the Phone: desktop tools and desktop window changes are refused. */
    val desktopAllowed: Boolean get() = true
    /**
     * What the user said for this task (request, later instructions, answers to ask_user), newest last; null for a
     * direct MCP call, whose caller is explicit. Tools that change something only on the user's explicit wish
     * (desktop_presentation full) check it ([PresentationRequest]).
   */
    val userWords: List<String>? get() = null
}

/**
 * A capability the model (agent loop) or an MCP client can invoke. [targets] lists where it
 * works (empty = target-independent, e.g. web search). [agentOnly] tools (plan/ask/confirm/finish)
 * are not exported over MCP.
 */
interface Tool {
    val name: String
    val description: String
    val schema: JSONObject
    val targets: Set<TargetKind>
    val agentOnly: Boolean get() = false
    /** Changes device state (used by stuck detection and the irreversible-action guard). */
    val mutating: Boolean get() = false
    /**
     * Acts beyond undo (sends an SMS, starts a call, deletes calendar data). [ToolRegistry.execute] asks a person
     * before every run, independent of the model and of the confirmation setting ([AgentHooks.confirmIrreversible]);
     * with nobody to ask (a direct MCP call) the call is refused.
   */
    val irreversible: Boolean get() = false
    /**
     * The approval question for an [irreversible] call, worded by the tool from its validated arguments (never by the
     * model). Throws [ToolArgumentException] for bad arguments and [ToolFailure] when the call cannot work anyway, so
     * nobody is asked to approve something that would fail.
   */
    suspend fun approvalQuestion(ctx: ToolContext, args: Args): String = "Run $name"
    suspend fun run(ctx: ToolContext, args: Args): ToolResult

    /** Model-facing spec; acting tools get an optional `reason` shown to the user as progress. */
    fun spec(): ToolSpec {
        if (!mutating || targets.isEmpty()) return ToolSpec(name, description, schema)
        val s = JSONObject(schema.toString())
        s.optJSONObject("properties")?.put("reason", Schema.str("Why this step, in a few words (shown to the user)"))
        return ToolSpec(name, description, s)
    }
}

/** JSON-schema helpers. */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))

    fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    fun num(desc: String) = JSONObject().put("type", "number").put("description", desc)
    fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    fun enum(desc: String, vararg values: String) = str(desc).put("enum", JSONArray(values.toList()))
    fun arr(desc: String, items: JSONObject) = JSONObject().put("type", "array").put("description", desc).put("items", items)

    val target = "target" to enum("Where to act: desktop (Dextop desktop display) or phone (the phone's own screen). Default: the task's current target.", "desktop", "phone")
}

fun parseTarget(value: String?): TargetKind? = when (value?.trim()?.lowercase()) {
    "desktop", "dex" -> TargetKind.DESKTOP
    "phone", "screen", "main" -> TargetKind.PHONE
    else -> null
}
