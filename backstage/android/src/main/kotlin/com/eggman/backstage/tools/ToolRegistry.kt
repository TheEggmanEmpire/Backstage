package com.eggman.backstage.tools

import com.eggman.backstage.exec.TargetKind
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * The single tool registry shared by the agent loop and the MCP server. Selection depends on what
 * is available (desktop host, vision, plan mode) so the model never sees tools it cannot use.
 */
class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names" }
    }

    operator fun get(name: String): Tool? = byName[name]

    /** This registry plus [extra] tools (e.g. a remote MCP server's), skipping name clashes. */
    fun plus(extra: List<Tool>): ToolRegistry =
        if (extra.isEmpty()) this else ToolRegistry(tools + extra.filter { it.name !in byName }.distinctBy { it.name })

    data class Availability(
        val desktop: Boolean,
        val phone: Boolean = true,
        val vision: Boolean = true,
        val planMode: Boolean = false,
        val calendar: Boolean = true,
        /** A floating bubble is registered ( overlay). */
        val overlay: Boolean = com.eggman.backstage.exec.OverlayHooks.controller != null,
        val alarms: Boolean = true,
        /** OpenWeatherMap key configured. */
        val weather: Boolean = false,
        val memory: Boolean = true,
        val scheduler: Boolean = AgentExtensions.scheduler != null,
        /** Device-control capabilities that are actually usable (permissions granted). */
        val deviceCapabilities: Set<String> = emptySet(),
        /** At least one skill is enabled (list_skills, use_skill, read_skill_file). */
        val skills: Boolean = false,
        /** Shell identity through Shizuku/Stellar or Dextop wireless debugging. */
        val adb: Boolean = false,
        val omp: Boolean = false,
    ) {
        companion object {
            /** What the given device services can actually do. */
            fun of(s: DeviceServices, desktop: Boolean, vision: Boolean, planMode: Boolean) = Availability(
                desktop = desktop, vision = vision, planMode = planMode,
                omp = com.eggman.backstage.omp.OmpHarness.unavailableReason() == null,
                adb = com.eggman.backstage.privileged.Privileged.available(),
                calendar = s.calendar != null, alarms = s.alarms != null,
                weather = s.modules().weatherApiKey.isNotBlank(),
                memory = s.memory != null && s.modules().memoryEnabled,
                scheduler = s.scheduler != null,
                deviceCapabilities = (s as? DeviceControlProvider)?.deviceControl?.let { d ->
                    DeviceControlTools.capabilityOf.values.toSet().filter { runCatching { d.has(it) }.getOrDefault(false) }.toSet()
                } ?: emptySet(),
                skills = runCatching { s.skills?.enabled()?.isNotEmpty() }.getOrNull() == true,
            )
        }
    }

    private fun usable(t: Tool, a: Availability): Boolean {
        if (t.targets.isNotEmpty()) {
            val ok = (TargetKind.DESKTOP in t.targets && a.desktop) || (TargetKind.PHONE in t.targets && a.phone)
            if (!ok) return false
        }
        if ((t.name.startsWith("omp_subagent") || t.name == "run_skill_script") && (!a.omp || !a.adb)) return false
        if (t.name == "adb_command" && !a.adb) return false
        if (t.name == "screenshot" && !a.vision) return false
        if (t.name == "update_plan" && !a.planMode) return false
        if (t.name.contains("calendar") && !a.calendar) return false
        if ((t.name.contains("alarm") || t.name == "set_timer") && !a.alarms) return false
        if (t.name.startsWith("get_weather") && !a.weather) return false
        if (t.name.startsWith("memory_") && !a.memory) return false
        if (t.name.endsWith("scheduled_task") || t.name == "list_scheduled_tasks") { if (!a.scheduler) return false }
        DeviceControlTools.capabilityOf[t.name]?.let { cap -> if (cap !in a.deviceCapabilities) return false }
        if (t.name == "move_floating_widget" && !a.overlay) return false
        if (t.name in SKILL_TOOLS && !a.skills) return false
        return true
    }

    fun forAgent(a: Availability): List<Tool> = tools.filter { usable(it, a) }

    /**
     * Tools a direct MCP call may use. [Tool.irreversible] ones are left out: a direct call has nobody on the phone
     * to approve them (they stay reachable through execute_task, where the phone asks its user).
   */
    fun forMcp(a: Availability): List<Tool> = tools.filter { !it.agentOnly && !it.irreversible && usable(it, a.copy(planMode = false)) }

    /**
     * Runs a tool; argument and runtime errors become error results the model can react to. A [Tool.irreversible]
     * tool runs only after a person approved the exact action ([AgentHooks.confirmIrreversible]); without hooks
     * (a direct MCP call) it is refused.
   */
    suspend fun execute(ctx: ToolContext, name: String, args: JSONObject): ToolResult {
        val tool = byName[name] ?: return ToolResult.error("Unknown tool '$name'. Available: ${tools.joinToString { it.name }}")
        if (args.has("_raw")) return ToolResult.error("Arguments for $name were not valid JSON: ${args.optString("_raw").take(200)}")
        return try {
            val a = Args(args)
            if (tool.irreversible) approve(ctx, tool, a)?.let { return it }
            tool.run(ctx, a)
        } catch (c: CancellationException) {
            throw c
        } catch (e: ToolArgumentException) {
            ToolResult.error("Invalid arguments for $name: ${e.message}")
        } catch (e: ToolFailure) {
            ToolResult.error(e.message ?: "$name failed")
        } catch (t: Throwable) {
            ToolResult.error("$name failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Null when a person approved [tool]'s call, else the refusal. */
    private suspend fun approve(ctx: ToolContext, tool: Tool, args: Args): ToolResult? {
        val question = tool.approvalQuestion(ctx, args)
        val hooks = ctx.hooks ?: return ToolResult.error(noApprover(tool.name))
        return if (hooks.confirmIrreversible(question)) null
        else ToolResult.error("Not done: the user did not approve “$question”. Do not retry it unless the user asks again.")
    }

    companion object {
        val SKILL_TOOLS = setOf("list_skills", "use_skill", "read_skill_file", "run_skill_script")

        fun noApprover(name: String) = "Refused: $name cannot be undone and needs the phone user's approval, which a direct MCP call " +
            "cannot get. Use execute_task instead: the phone asks its user (or the caller passes allow_irreversible)."

        fun default(): ToolRegistry = ToolRegistry(
            listOf(
                GetScreenTool(), ScreenshotTool(), TapTool(), LongPressTool(), SwipeTool(), ScrollTool(), TypeTextTool(),
                PressKeyTool(), LaunchAppTool(), ListAppsTool(), DefaultAppsTool(), OpenUrlTool(), WaitTool(), AwaitScreenChangeTool(),
                WebSearchTool(), FetchUrlTool(), BrowserSearchTool(),
                GetCalendarsTool(), GetCalendarEventsTool(), CreateCalendarEventTool(), ModifyCalendarEventTool(), DeleteCalendarEventTool(),
                SetAlarmTool(), SetTimerTool(), GetAlarmsTool(), CancelAlarmTool(), GetWeatherTool(), GetWeatherForecastTool(),
                MemorySaveTool(), MemorySearchTool(),
                CreateScheduledTaskTool(), ListScheduledTasksTool(), UpdateScheduledTaskTool(), DeleteScheduledTaskTool(),
                ClipboardGetTool(), ClipboardSetTool(), NotificationsTool(), AdbCommandTool(), OmpSubagentTool(), OmpSubagentStatusTool(), OmpSubagentCancelTool(),
                com.eggman.backstage.skills.ListSkillsTool(), com.eggman.backstage.skills.UseSkillTool(), com.eggman.backstage.skills.ReadSkillFileTool(), RunSkillScriptTool(),
            ) + DeviceControlTools.all() + listOf(
                DesktopStartTool(), DesktopStatusTool(), DesktopPresentationTool(), LaunchAppTool("desktop_launch_app", forceDesktop = true),
                DesktopRightClickTool(), DesktopScrollWheelTool(), DesktopWindowListTool(), DesktopCloseWindowsTool(),
                DesktopShortcutTool(), DesktopKeyComboTool(), DesktopStopTool(), DesktopRestartTool(), SwitchScreenTool(), WindowStateTool(), WindowControlTool(),
                UpdatePlanTool(), ActSequenceTool(), AskUserTool(), PendingUserActionTool(), ConfirmActionTool(), MoveFloatingWidgetTool(), FinishTool(),
            ),
        )
    }
}
