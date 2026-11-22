package com.eggman.backstage.mcp

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.agent.AgentEnvironment
import com.eggman.backstage.agent.ExecutionLease
import com.eggman.backstage.agent.PeekableContext
import com.eggman.backstage.agent.ReadinessCheck
import com.eggman.backstage.agent.ReadinessInputs
import com.eggman.backstage.agent.api.AgentController
import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.ScreenMarkup
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.exec.TargetDecision
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.TargetResolver
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.DeviceServices
import com.eggman.backstage.tools.Schema
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolFailure
import com.eggman.backstage.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools Backstage offers over MCP.
 *  - High level (grouped): execute_task, get_screen_state, get_device_status, wake_device.
 *  - Low level ("experimental", opt-in per tool): the shared tool registry (tap, type_text, launch_app,
 *    desktop_* …) minus agent-only control tools.
 * A tool is listed and callable when its name is in the enabled set (default: the three standard
 * tools) or, for low-level tools, when "expose all low-level tools" is on.
 */
class BackstageMcpTools(
    private val controller: () -> AgentController,
    private val env: AgentEnvironment,
    private val extended: () -> Boolean = { env.settings.mcpExtendedTools },
    private val enabledTools: () -> Set<String> = { env.settings.mcpEnabledTools },
    /** Wakes the screen (null in tests / builds without the capability). Returns the screen-on state. */
    private val wake: (suspend () -> Boolean)? = null,
) : McpToolProvider {
    /** JSON-RPC request key → task id of a running execute_task, for `notifications/cancelled`. */
    private val runningTasks = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 300L
        val HIGH_LEVEL = listOf("execute_task", "get_screen_state", "get_device_status", "wake_device")
        val DEFAULT_ENABLED = setOf("execute_task", "get_screen_state", "get_device_status")
    }

    private val highLevel: List<McpTool> = listOf(
        McpTool(
            "execute_task",
            "Have the phone's AI agent carry out a task described in natural language (e.g. \"reply to Anna on WhatsApp that I'm running late\"). " +
                "It operates real apps — in the background on the Dextop desktop when available, otherwise on the phone screen — and returns " +
                "the agent's final result. Blocks until the task ends (default limit 300 s).",
            Schema.obj(
                "task" to Schema.str("What the agent should do, in natural language"),
                "target" to Schema.enum("Where to act: auto (default), desktop (background desktop) or phone (phone screen)", "auto", "desktop", "phone"),
                "plan_mode" to Schema.bool("Make the agent keep a step checklist (default: the user's setting)"),
                "timeout_seconds" to Schema.int("Give up after this many seconds (default 300, max 3600)"),
                "allow_irreversible" to Schema.bool("Allow sending/paying/deleting without asking the phone's user (default false: such steps are denied)"),
                required = listOf("task"),
            ),
            title = "Execute task",
        ),
        McpTool(
            "get_screen_state",
            "Snapshot of what is displayed right now. format=markup (default) groups elements per app in <app package> blocks; each element " +
                "shows txt, dsc, a stable nid, its flags (clickable, scrollable, editable, checked, focused, selected) and bounds b=\"l,t,r,b\" " +
                "(aim at the middle). format=list numbers the elements and gives their centre points. Structured JSON is attached as well.",
            Schema.obj(
                "target" to Schema.enum("auto (default: running desktop, else phone), desktop or phone", "auto", "desktop", "phone"),
                "format" to Schema.enum("markup (default) or list", "markup", "list"),
                "include_screenshot" to Schema.bool("Also attach a JPEG screenshot"),
            ),
            readOnly = true,
            title = "Get screen state",
        ),
        McpTool(
            "get_device_status",
            "Readiness report as JSON: screen_on, locked, accessibility_enabled, ready and, when not ready, reason (what needs fixing); " +
                "target (where a task would run: desktop or phone) with ready computed for it — a desktop that keeps running while " +
                "the phone is locked is ready even when the phone is locked or dark; desktop (available, active, works_while_locked); " +
                "also provider_configured, privileged_available, desktop_active and problems.",
            Schema.obj("target" to Schema.enum("Report readiness for this target: auto (default, what execute_task would use), desktop or phone", "auto", "desktop", "phone")),
            readOnly = true,
            title = "Get device status",
        ),
        McpTool(
            "wake_device",
            "Lights up a dark display. The lock screen stays in place, so the reply tells whether tasks can run now or the owner must unlock first.",
            Schema.obj(),
            title = "Wake device",
        ),
    )

    private fun lowLevelTools(): List<McpTool> {
        val a = ToolRegistry.Availability.of(env.services, desktop = env.services.desktop != null, vision = true, planMode = false)
        val own = env.registry.forMcp(a).map { t -> McpTool(t.name, t.description, t.schema, readOnly = !t.mutating, destructive = t.mutating) }
        // interoperability names, listed when every tool they forward to is offered.
        val names = own.mapTo(HashSet()) { it.name }
        val aliases = McpAliases.all.filter { names.containsAll(it.targets) }
            .map { al -> McpTool(al.name, al.description, al.schema, readOnly = al.readOnly, destructive = !al.readOnly) }
        return own + aliases
    }

    /**
     * the high-level tools in their fixed order (when enabled), then every low-level tool the user ticked
     * (or all of them with "expose all low-level tools").
   */
    override fun tools(): List<McpTool> {
        val enabled = enabledTools()
        val ext = extended()
        return HIGH_LEVEL.mapNotNull { n -> highLevel.firstOrNull { it.name == n && n in enabled } } +
            lowLevelTools().filter { ext || it.name in enabled }
    }

    override suspend fun call(name: String, arguments: JSONObject): McpCallResult = call(name, arguments, null)

    override suspend fun call(name: String, arguments: JSONObject, requestKey: String?): McpCallResult = when (name) {
        "execute_task" -> executeTask(arguments, requestKey)
        "get_screen_state" -> screenState(arguments)
        "get_device_status" -> deviceStatus(arguments)
        "wake_device" -> wakeDevice()
        else -> callRegistry(name, arguments)
    }

    private fun targetArg(a: JSONObject): Target = when (a.optString("target").lowercase()) {
        "", "auto" -> Target.AUTO
        "desktop" -> Target.DESKTOP
        "phone" -> Target.PHONE
        else -> throw McpInvalidParams("target must be auto, desktop or phone")
    }

    private fun busy(): Boolean = controller().state.value.busy

    /** Where a task with [target] would run (null kind: nowhere; the reason says why). */
    private fun decide(target: Target, task: String = ""): TargetDecision? =
        runCatching { TargetResolver.resolve(env.targetContext(TaskRequest(task, Source.MCP, target), null)) }.getOrNull()

    /**
     * Task pre-flight order: agent, screen, lock, provider, busy. Null when the task may start. Per target (
     * ): a desktop that keeps running while the phone is locked needs neither the screen nor an unlock, so the
     * phone is left dark and locked in the owner's pocket.
   */
    private suspend fun preflight(target: Target, task: String): String? {
        var r = controller().readiness()
        if (PLAY_DISTRIBUTION && env.readinessInputs().companionStatus != null) return when {
            !r.providerConfigured -> r.problems.firstOrNull { it.startsWith("AI provider") } ?: "Set up an AI provider."
            busy() -> "Busy: resubmit once the agent is idle."
            else -> null
        }
        if (!r.accessibilityEnabled) return "Backstage's accessibility service is off, so nothing can be operated. " + r.problems.firstOrNull { "ccessibility" in it }.orEmpty()
        val kind = decide(target, task)?.kind
        if (ReadinessCheck.needsPhoneScreen(env.readinessInputs(), kind)) {
            if (!r.screenOn) {
                wake?.invoke()
                r = controller().readiness()
                if (!r.screenOn) return "Display is dark and the remote wake did not work; someone at the phone must switch it on."
            }
            if (!r.unlocked) {
                return "Phone is locked; resubmit after the owner unlocks it." + if (kind == TargetKind.DESKTOP) {
                    " (The desktop pauses while the phone is locked unless \"Keep the desktop running while locked\" is on.)"
                } else ""
            }
        }
        if (!r.providerConfigured) return r.problems.firstOrNull { it.startsWith("AI provider") } ?: "No AI provider is configured on the phone."
        if (busy()) return "Busy: the phone's agent is working on something else. Resubmit once it is idle."
        return null
    }

    override fun cancel(requestKey: String) {
        runningTasks[requestKey]?.let { controller().cancel(it) }
    }

    private suspend fun executeTask(a: JSONObject, requestKey: String? = null): McpCallResult {
        val task = listOf("task", "prompt", "instruction").firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
            ?: throw McpInvalidParams("Missing required parameter: task")
        val target = targetArg(a)
        preflight(target, task)?.let { return McpCallResult.text(it, isError = true) }
        val timeoutSec = if (a.has("timeout_seconds")) a.optLong("timeout_seconds").coerceIn(10, 3600) else DEFAULT_TIMEOUT_SEC
        val attachments = buildMap { if (a.optBoolean("allow_irreversible", false)) put("allow_irreversible", "true") }
        val handle = controller().submit(
            TaskRequest(
                prompt = task,
                source = Source.MCP,
                target = target,
                planMode = if (a.has("plan_mode")) a.optBoolean("plan_mode") else null,
                attachments = attachments,
                interactive = false,
                timeoutMs = timeoutSec * 1000,
            ),
        )
        requestKey?.let { runningTasks[it] = handle.id }
        val s = try { handle.await() } finally { requestKey?.let { runningTasks.remove(it) } }
        val structured = JSONObject().put("taskId", s.id).put("status", s.status.name.lowercase()).put("result", s.result ?: "")
            .put("steps", s.step)
            .put("plan", JSONArray().apply { s.plan.forEach { put(JSONObject().put("title", it.title).put("status", it.status)) } })
        val text = when (s.status) {
            TaskStatus.SUCCEEDED -> s.result?.takeIf { it.isNotBlank() } ?: "Finished; the agent gave no summary."
            TaskStatus.CANCELLED -> "The task was stopped before it finished."
            else -> s.result?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("Timed out")) "Gave up: no result within $timeoutSec s." else "Task failed: $it" }
                ?: "Task failed."
        }
        return McpCallResult.text(text, isError = s.status != TaskStatus.SUCCEEDED, structured = structured)
    }

    private fun defaultKind(): TargetKind {
        val c = env.targetContext(TaskRequest("", Source.MCP), null)
        return if (c.hostPresent && c.desktopActive) TargetKind.DESKTOP else TargetKind.PHONE
    }

    private suspend fun resolveExecutor(a: JSONObject): Executor {
        val kind = when (targetArg(a)) {
            Target.DESKTOP -> {
                val d = TargetResolver.resolve(env.targetContext(TaskRequest("", Source.MCP, Target.DESKTOP), null))
                if (d.kind == null) throw McpInvalidParams(d.reason)
                TargetKind.DESKTOP
            }
            Target.PHONE -> TargetKind.PHONE
            Target.AUTO -> defaultKind() // observation never starts a desktop implicitly
        }
        // reading the phone screen is gated like acting on it ("Remote callers may use the phone screen").
        if (kind == TargetKind.PHONE && !env.targetContext(TaskRequest("", Source.MCP), null).externalPhoneAllowed) {
            throw McpInvalidParams(TargetResolver.PHONE_LOCKED)
        }
        return env.executor(kind)
    }

    private suspend fun screenState(a: JSONObject): McpCallResult {
        val exec = try { resolveExecutor(a) } catch (e: IllegalStateException) { return McpCallResult.text(e.message ?: "unavailable", isError = true) }
        // observe: never replaces the listing a running agent task is working from.
        val (state, capture) = exec.observe()
        val text = when (a.optString("format", "markup").lowercase()) {
            "list" -> state.text
            "markup", "" -> capture?.let { ScreenMarkup.render(it, env.ownPackages()) } ?: state.text
            else -> throw McpInvalidParams("format must be markup or list")
        }
        val content = mutableListOf(McpContent("text", text = com.eggman.backstage.tools.Observe.redactText(exec, text)))
        if (a.optBoolean("include_screenshot")) {
            exec.observeScreenshot()?.let { content += McpContent("image", data = it.base64, mimeType = it.mediaType) }
                ?: content.add(McpContent("text", text = "(screenshot unavailable)"))
        }
        return McpCallResult(content, structured = com.eggman.backstage.tools.Observe.redactJson(exec, ScreenSerializer.toJson(state)))
    }

    /**
     * keys plus Backstage's per-target fields: `target` is where a task would run, `ready` and
     * `reason` are computed for that target, and `desktop` tells whether the desktop works while the phone is locked.
   */
    internal fun statusJson(r: Readiness, i: ReadinessInputs, decision: TargetDecision?, desktopReport: JSONObject? = null): JSONObject {
        val kind = decision?.kind
        val phoneScreen = ReadinessCheck.needsPhoneScreen(i, kind)
        val screenReady = ReadinessCheck.readyFor(r, i, kind)
        val ready = if (PLAY_DISTRIBUTION && i.companionStatus != null) r.providerConfigured else screenReady
        val reason = when {
            !r.accessibilityEnabled -> if (PLAY_DISTRIBUTION) i.companionStatus ?: "Screen access unavailable."
            else "Accessibility service off: " + (r.problems.firstOrNull { "ccessibility" in it } ?: "enable it on the phone.")
            phoneScreen && !r.screenOn -> "Display off: call wake_device first."
            phoneScreen && !r.unlocked -> "Phone locked: the owner has to unlock it."
            !r.providerConfigured -> r.problems.firstOrNull { it.startsWith("AI provider") } ?: "No AI provider configured."
            else -> null
        }
        val desktop = JSONObject()
            .put("available", i.hostPresent)
            .put("active", r.desktopActive)
            .put("runs_while_locked", i.desktopRunsWhileLocked)
            .put("works_while_locked", ReadinessCheck.desktopWorksWhileLocked(i))
        // display id, size, presentation, backend, focus mode and focused window (DesktopStatusReport).
        desktopReport?.let { rep ->
            rep.keys().forEach { k -> if (k != "available" && k != "active" && k != "runs_while_locked") desktop.put(k, rep.get(k)) }
        }
        return JSONObject()
            .put("screen_on", r.screenOn)
            .put("locked", !r.unlocked)
            .put("accessibility_enabled", r.accessibilityEnabled)
            .apply { if (PLAY_DISTRIBUTION) put("screen_ready", screenReady) }
            .put("ready", ready)
            .putOpt("reason", reason)
            .put("target", kind?.label ?: JSONObject.NULL)
            .putOpt("target_reason", decision?.reason)
            .put("desktop", desktop)
            .put("provider_configured", r.providerConfigured)
            .put("privileged_available", r.privilegedAvailable)
            .put("desktop_active", r.desktopActive)
            .put("problems", JSONArray(r.problems))
    }

    private fun deviceStatus(a: JSONObject): McpCallResult {
        val focused = env.peekExecutor(TargetKind.DESKTOP)?.windows()?.firstOrNull { it.focused }
        val report = com.eggman.backstage.tools.DesktopStatusReport.json(env.services.desktop, focused)
        val json = statusJson(controller().readiness(), env.readinessInputs(), decide(targetArg(a)), report)
        return McpCallResult(listOf(McpContent("text", text = json.toString())), structured = json)
    }

    private suspend fun wakeDevice(): McpCallResult {
        val before = controller().readiness()
        if (before.screenOn) {
            return McpCallResult.text(if (before.unlocked) "Nothing to do: the display was already on and the phone is unlocked." else "The display was already on, but the lock screen is up; tasks can start after the owner unlocks.")
        }
        val w = wake ?: return McpCallResult.text("Remote wake is unavailable in this build; the display has to be switched on at the phone.", isError = true)
        w()
        val after = controller().readiness()
        return when {
            !after.screenOn -> McpCallResult.text("Wake attempt failed: the display is still dark.", isError = true)
            after.unlocked -> McpCallResult.text("Woke the display; there is no lock screen, so tasks can run.")
            else -> McpCallResult.text("Woke the display, but the lock screen is up; tasks wait until the owner unlocks.")
        }
    }

    private suspend fun callRegistry(name: String, args: JSONObject): McpCallResult {
        val alias = McpAliases.byName[name]
        val (toolName, a) = alias?.map?.invoke(args) ?: (name to McpAliases.argumentCompat(name, args))
        return callTool(toolName, a)
    }

    private suspend fun callTool(name: String, a: JSONObject): McpCallResult {
        val tool = env.registry[name] ?: throw McpInvalidParams("Unknown tool $name")
        if (tool.agentOnly) throw McpInvalidParams("$name is not available over MCP")
        // Even read-only registry tools (get_screen, screenshot, …) store what they read in the shared executor,
        // which a running task resolves indices against: all of them need exclusive executor ownership.
        // Refuse instead of queueing: a busy-state check followed by a separate lock races task startup.
        return ExecutionLease.direct {
            if (busy()) return@direct McpCallResult.text("Backstage is busy with a task; try again when it is done", isError = true)
            com.eggman.backstage.tools.Observe.invalidate()
            val result = try { env.registry.execute(McpToolContext(env), name, a) }
            finally { com.eggman.backstage.tools.Observe.invalidate() }
            val content = mutableListOf(McpContent("text", text = result.text))
            result.image?.let { content += McpContent("image", data = it.base64, mimeType = it.mediaType) }
            McpCallResult(content, isError = result.isError, structured = result.data)
        } ?: McpCallResult.text("Backstage is busy with a task or another MCP tool call; try again when it is done", isError = true)
    }
}

/** ToolContext for direct MCP calls: no hooks, target per call (default: running desktop, else phone). */
class McpToolContext(private val env: AgentEnvironment) : ToolContext, PeekableContext, com.eggman.backstage.tools.FullScreenListingContext {
    override var currentTarget: TargetKind? = null
        private set

    override suspend fun executor(kind: TargetKind?): Executor {
        val k = kind ?: currentTarget ?: run {
            val c = env.targetContext(TaskRequest("", Source.MCP), null)
            if (c.hostPresent && c.desktopActive) TargetKind.DESKTOP else TargetKind.PHONE
        }
        if (k == TargetKind.PHONE && !phoneAllowed) throw ToolFailure(TargetResolver.PHONE_LOCKED)
        currentTarget = k
        return env.executor(k)
    }

    /** A direct MCP call may change the phone's windows only with "Remote callers may use the phone screen". */
    override val phoneAllowed: Boolean
        get() = runCatching { env.targetContext(TaskRequest("", Source.MCP), null).externalPhoneAllowed }.getOrDefault(false)

    override fun switchTarget(kind: TargetKind) { currentTarget = kind }
    override fun peek(kind: TargetKind): Executor? = env.peekExecutor(kind)
    override val services: DeviceServices get() = env.services
    override val hooks: AgentHooks? = null
    override val visionEnabled: Boolean = true
    override val autoScreenshot: Boolean = false
}
