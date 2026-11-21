package com.eggman.backstage.omp.engine

import com.eggman.backstage.agent.*
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.UiElement
import com.eggman.backstage.llm.Part
import com.eggman.backstage.tools.*
import org.json.JSONObject

/** Native guarded dispatch mirrored from AgentLoop; the native path remains unchanged. */
class OmpToolPolicy(
    private val registry: ToolRegistry,
    private val config: LoopConfig,
    private val user: UserChannel,
    private val contextFactory: (AgentHooks) -> ToolContext,
    private val listener: LoopListener,
) {
    var plan: List<PlanStep> = emptyList(); private set
    var outcome: LoopOutcome? = null; private set
    var actions = 0; private set
    private var step = 0
    private var trackedTurns = false
    private var actedInTurn = false
    private var planNags = 0
    private var finishSummary: String? = null
    private var finishSuccess = true
    private var declined: String? = null
    private val stuck = StuckDetector()
    private val freshGuard = FreshScreenGuard()
    private var rulesShownFor: String? = null
    private val allowed = registry.forAgent(config.availability).map { it.name }.toSet()
    private val hooks = object : AgentHooks {
        override fun onPlan(steps: List<PlanStep>) {
            plan = steps
            user.planChanged(steps)
        }

        override suspend fun ask(question: String, choices: List<String>): String? = user.ask(question, choices)

        override suspend fun confirm(action: String): Boolean =
            user.confirm(action).also { if (it) config.guard.noteApproval(step) else declined = action }

        // Approves only the one change it describes: it never opens the guard's window for other actions.
        override suspend fun confirmAlways(action: String): Boolean = user.confirmAlways(action).also { if (!it) declined = action }

        override suspend fun confirmIrreversible(action: String): Boolean = user.confirmIrreversible(action).also { if (!it) declined = action }

        override fun onFinish(summary: String, success: Boolean) {
            finishSummary = summary
            finishSuccess = success
        }
    }

    private val ctx: ToolContext by lazy { contextFactory(hooks) }

    private fun pendingPlanSteps() = plan.filter { it.status == "pending" || it.status == "in_progress" }


    fun beginTurn() { trackedTurns = true; actedInTurn = false; step++ }

    suspend fun execute(call: Part.ToolCall): ToolResult {
        if (call.name !in allowed) return ToolResult.error("Tool '${call.name}' is not available for this task")
        if (outcome != null) return ToolResult.error("Skipped: the task already finished.")
        listener.beforeStep()
        if (!trackedTurns) step++
        val planOnly = call.name == "update_plan" && config.planMode
        if (trackedTurns && actedInTurn && !planOnly) return ToolResult.error(AgentLoop.ONE_PER_TURN)
        if (!planOnly) actedInTurn = true
        listener.onToolCall(call)
        val tool = registry[call.name]!!
        if (tool.mutating) {
            if (actions >= config.maxActions) {
                outcome = LoopOutcome(false, "Stopped after $actions actions without finishing (safety limit).", step, EndReason.STEP_LIMIT)
                return ToolResult.error(outcome!!.summary)
            }
            actions++; listener.onAction(actions)
        }
        val result = if (call.name == ActSequenceTool.NAME) runSequence(call) else executeCall(call, false, planNags < config.maxPlanNags)
        if (result.text.startsWith(PLAN_BLOCK)) planNags++
        listener.onToolResult(call, result)
        outcome = when {
            declined != null -> LoopOutcome(false, "Not done: “${declined!!.take(300)}” was not approved.", step, EndReason.DECLINED)
            result.text.startsWith(STUCK_ABORT) -> LoopOutcome(false, "Stopped: the agent kept repeating an action that had no effect.", step, EndReason.STUCK)
            result.terminal -> LoopOutcome(finishSuccess, finishSummary?.takeIf { it.isNotBlank() } ?: "Done.", step, EndReason.FINISHED)
            else -> outcome
        }
        return result
    }
    private fun planReminder(): String = "Your plan still has unfinished steps: " + pendingPlanSteps().joinToString("; ") { "${it.title} (${it.status})" } +
        ". Continue working on them, or mark them skipped with a reason via update_plan, then call finish."
    private fun executorPeek(call: Part.ToolCall): Executor? {
        val target = parseTarget(call.args.optString("screen", "").ifEmpty { call.args.optString("target", "") }) ?: ctx.currentTarget ?: return null
        return (ctx as? PeekableContext)?.peek(target)
    }

    private fun elementFor(exec: Executor?, args: JSONObject): UiElement? {
        val screen = exec?.lastScreen ?: return null
        if (args.has("index")) return screen.element(args.optInt("index", -1))
        if (args.has("nid")) return screen.elements.firstOrNull { it.nid == args.optString("nid") }
        if (args.has("x") && args.has("y") && args.optString("space") != "image") {
            val x = args.optDouble("x").toInt(); val y = args.optDouble("y").toInt()
            return screen.elements.filter { it.bounds.contains(x, y) }.minByOrNull { it.bounds.area }
        }
        return null
    }

    private suspend fun executeCall(call: Part.ToolCall, screenChanged: Boolean, mayNagPlan: Boolean): ToolResult {
        val tool = registry[call.name]
        val args = call.args
        // Plan mode: an acting tool may carry the updated checklist, applied before the action.
        if (config.planMode && call.name != "update_plan") args.optJSONArray("plan")?.let { arr ->
            runCatching { PlanArgs.parse(arr) }.getOrNull()?.let { hooks.onPlan(it) }
        }
        // Plan-mode completion check.
        if (call.name == "finish" && config.planMode && pendingPlanSteps().isNotEmpty() && mayNagPlan &&
            args.optBoolean("success", true)
        ) {
            return ToolResult.error(PLAN_BLOCK + " " + planReminder())
        }
        // Indices from the listing the model saw are stale once an earlier call in this turn changed the screen.
        if (screenChanged && tool != null && tool.targets.isNotEmpty() && args.has("index")) {
            return ToolResult.error("Skipped: an earlier action in this turn changed the screen, so index ${args.opt("index")} may point elsewhere. Look at the new screen and act again.")
        }
        val exec = if (tool != null && tool.targets.isNotEmpty()) executorPeek(call) else null
        // Irreversible tools ask the user themselves (ToolRegistry.execute): the guard would only make it ask twice.
        if (tool?.mutating == true && !tool.irreversible) {
            val el = elementFor(exec, args)
            val hint = el?.let { listOf(it.label, it.hint, it.resourceId).joinToString(" ") }
                ?: exec?.lastScreen?.elements?.firstOrNull { it.editable && "focused" in it.traits }?.let { "${it.label} ${it.hint} ${it.resourceId}" }
            // the user is asked right here, worded from the tool and the element (no confirm_action round
            // trip through the model); a refusal ends the task as before.
            val question = config.guard.approvalQuestion(step, call.name, el?.label?.takeIf { it.isNotBlank() } ?: el?.description,
                args.optBoolean("submit"), hint, args.optString("text").takeIf { it.isNotEmpty() },
                exec?.lastScreen?.foregroundPackage)
            if (question != null && !hooks.confirm(question)) {
                return ToolResult.error("Not done: the user did not approve “$question”. Do not retry it unless the user asks again.")
            }
        }
        // Re-read the screen right before acting; withhold the action if it changed meanwhile.
        val guarded = call.name in FreshScreenGuard.GUARDED || (call.name == "press_key" && args.optString("key").equals("back", true))
        if (guarded && exec != null) {
            val seen = exec.lastScreen
            // No accessibility event on that display since the listing is captured: nothing changed.
            if (seen != null && exec.uiEventsSinceScreen() != 0L) {
                val fresh = runCatching { exec.screen() }.getOrNull()
                if (fresh != null) {
                    freshGuard.check(seen, fresh)?.let { return ToolResult.error(it) }
                    if (args.has("index")) freshGuard.indexMoved(seen, fresh, args.optInt("index", -1))?.let { return ToolResult.error(it) }
                }
            }
        } else freshGuard.clear()
        val before = exec?.lastScreen?.fingerprint
        val result = withAppRules(call, registry.execute(ctx, call.name, args))
        if (tool?.mutating == true && tool.targets.isNotEmpty()) {
            val after = executorPeek(call)?.lastScreen?.fingerprint
            when (stuck.record(call.name, args, before, after)) {
                StuckDetector.Verdict.WARN -> return result.copy(text = result.text + "\n\n" + StuckDetector.WARNING)
                StuckDetector.Verdict.ABORT -> return result.copy(text = STUCK_ABORT + "\n" + result.text, isError = true)
                StuckDetector.Verdict.OK -> Unit
            }
        }
        return result
    }

    /**
     * Appends the notes for the foreground app the first time it shows up: Backstage's built-in notes, then
     * the user's own rules for it, which win where they differ.
   */
    private fun withAppRules(call: Part.ToolCall, result: ToolResult): ToolResult {
        val pkg = executorPeek(call)?.lastScreen?.foregroundPackage?.takeIf { it.isNotBlank() } ?: return result
        if (pkg == rulesShownFor) return result
        rulesShownFor = pkg
        val builtIn = AppRuleRegistry.rulesFor(pkg)
        val user = config.appRules[pkg]?.takeIf { it.isNotBlank() }
        if (builtIn == null && user == null) return result
        val text = buildString {
            append(result.text)
            if (builtIn != null) append("\n\nNotes for this app ($pkg):\n").append(builtIn)
            if (user != null) append("\n\nThe user's rules for this app ($pkg; these win over the notes):\n").append(user.trim())
        }
        return result.copy(text = text)
    }

    private suspend fun runSequence(call: Part.ToolCall): ToolResult {
        val steps = call.args.optJSONArray("steps") ?: return ToolResult.error("steps must be an array")
        if (steps.length() == 0) return ToolResult.error("steps is empty")
        if (steps.length() > ActSequenceTool.MAX_STEPS) return ToolResult.error("At most ${ActSequenceTool.MAX_STEPS} steps per sequence")
        val parsed = (0 until steps.length()).map { i ->
            val o = steps.optJSONObject(i) ?: return ToolResult.error("step ${i + 1} must be an object")
            val name = o.optString("tool")
            val t = registry[name]
            if (name !in ActSequenceTool.ALLOWED || name !in allowed || t == null || t.irreversible) {
                return ToolResult.error("step ${i + 1}: '$name' cannot run in a sequence (allowed: ${ActSequenceTool.ALLOWED.joinToString()})")
            }
            val a = JSONObject((o.optJSONObject("args") ?: JSONObject()).toString())
            if (!a.has("target") && call.args.has("target")) a.put("target", call.args.optString("target"))
            name to a
        }
        val stopOnChange = call.args.optBoolean("stop_on_change", true)
        val seen = executorPeek(call)?.lastScreen
        val startPkg = seen?.foregroundPackage
        val lines = ArrayList<String>()
        var settle = 0L
        var stop: String? = null
        suspend fun finalObservation(): ToolResult? {
            val exec = executorPeek(call) ?: return null
            if (parsed.any { (name, args) -> name == "type_text" && (args.optBoolean("secret") || args.opt("text") is JSONObject) }) {
                Observe.invalidate(exec)
                return null
            }
            return try { Observe.fullObservation(ctx, exec) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { Observe.invalidate(exec); null }
        }
        for ((i, pair) in parsed.withIndex()) {
            val (name, a) = pair
            val sub = Part.ToolCall("${call.id}#${i + 1}", name, a)
            val exec = executorPeek(sub)
            if (a.has("index") && seen != null) {
                // Indices come from the listing the model saw: act only while that element is still the same one.
                val idx = a.optInt("index", -1)
                val was = seen.element(idx)
                val now = exec?.lastScreen?.element(idx)
                if (was == null || now == null || now.label != was.label || now.className != was.className) {
                    stop = "step ${i + 1} skipped: element [$idx] is no longer “${was?.label.orEmpty()}”"
                    break
                }
            }
            if (actions >= config.maxActions) { stop = "the safety limit of ${config.maxActions} actions is used up"; break }
            actions++
            listener.onAction(actions)
            listener.onToolCall(sub)
            val r = Observe.withoutShowing(exec) { executeCall(sub, screenChanged = false, mayNagPlan = false) }
            listener.onToolResult(sub, r)
            settle += r.settleMs
            lines += "${i + 1}. $name: ${r.text.lineSequence().firstOrNull().orEmpty().take(160)}"
            if (r.text.startsWith(STUCK_ABORT)) {
                val observation = finalObservation()
                val text = r.text + observation?.text?.let { "\n\n$it" }.orEmpty()
                return r.copy(text = executorPeek(call)?.let { Observe.redactText(it, text, ctx) } ?: text,
                    image = observation?.image, settleMs = settle)
            }
            if (r.isError) { stop = "step ${i + 1} failed"; break }
            if (declined != null) break
            if (stopOnChange && i < parsed.lastIndex) {
                val pkg = executorPeek(sub)?.lastScreen?.foregroundPackage
                if (!startPkg.isNullOrBlank() && !pkg.isNullOrBlank() && pkg != startPkg) { stop = "another app ($pkg) came to the front after step ${i + 1}"; break }
            }
        }
        val done = lines.size
        val head = "Sequence: $done of ${parsed.size} steps done" + (stop?.let { " — stopped: $it" } ?: "") + "\n" + lines.joinToString("\n")
        val observation = finalObservation()
        val text = head + observation?.text?.let { "\n\n$it" }.orEmpty()
        return ToolResult(executorPeek(call)?.let { Observe.redactText(it, text, ctx) } ?: text, image = observation?.image,
            isError = done == 0, settleMs = settle)
    }

    companion object {
        const val PLAN_BLOCK = AgentLoop.PLAN_BLOCK
        const val STUCK_ABORT = AgentLoop.STUCK_ABORT
    }
}
