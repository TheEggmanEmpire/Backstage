package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.StepTiming
import com.eggman.backstage.tools.ActSequenceTool
import com.eggman.backstage.tools.PlanArgs
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.UiElement
import com.eggman.backstage.llm.LlmClient
import com.eggman.backstage.llm.LlmException
import com.eggman.backstage.llm.LlmRequest
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.RetryPolicy
import com.eggman.backstage.llm.Role
import com.eggman.backstage.llm.StopReason
import com.eggman.backstage.llm.ToolChoice
import com.eggman.backstage.llm.Usage
import com.eggman.backstage.tools.AgentHooks
import com.eggman.backstage.tools.SensitiveActionGuard
import com.eggman.backstage.tools.ToolContext
import com.eggman.backstage.tools.ToolRegistry
import com.eggman.backstage.tools.ToolResult
import com.eggman.backstage.tools.parseTarget
import kotlinx.coroutines.async
import org.json.JSONObject

/** Human side of a task, implemented by the controller (UI question, notification, timeout policy). */
interface UserChannel {
    suspend fun ask(question: String, choices: List<String>): String?
    suspend fun confirm(action: String): Boolean
    /** A confirmation nothing waives (see [AgentHooks.confirmAlways]); only a person's answer approves. */
    suspend fun confirmAlways(action: String): Boolean = confirm(action)
    /** Approval for an irreversible tool (see [AgentHooks.confirmIrreversible]): only a person or a trusted waiver approves. */
    suspend fun confirmIrreversible(action: String): Boolean = confirmAlways(action)
    fun planChanged(steps: List<PlanStep>)
}

interface LoopListener {
    /** Called before every step; may suspend (AgentController.pause holds the task here). */
    suspend fun beforeStep() {}
    fun onStep(step: Int) {}
    /** An action (a mutating tool call) is about to run; [count] includes it (the progress shown against the budget). */
    fun onAction(count: Int) {}
    fun onAssistantText(text: String, final: Boolean) {}
    fun onAssistantMessageStart() {}
    /** Provider-visible reasoning for the current assistant message, already bounded for display. */
    fun onThinking(text: String, streaming: Boolean) {}
    fun onToolCall(call: Part.ToolCall) {}
    /** Engine-supplied display label; native/host tools retain their usual labels. */
    fun onToolCall(call: Part.ToolCall, summary: String) { onToolCall(call) }
    fun onToolProgress(call: Part.ToolCall, status: String) {}
    fun onToolResult(call: Part.ToolCall, result: ToolResult) {}
    fun onRetry(attempt: Int, delayMs: Long, error: LlmException) {}
    fun onUsage(model: String, usage: Usage) {}
    /** A status line for the transcript (not a step): e.g. the omp engine starting. */
    fun onStatus(text: String) {}
    /** Where one step's time went (timing, local only). */
    fun onTiming(timing: StepTiming) {}
}

data class LoopConfig(
    val system: String,
    /**
     * Action budget: calls of tools that change something ([com.eggman.backstage.tools.Tool.mutating]).
     * Looking, waiting (await_screen_change, wait) and planning are free, but every model turn counts towards [maxSteps].
   */
    val maxActions: Int,
    val planMode: Boolean,
    val maxTokens: Int,
    val retry: RetryPolicy,
    val guard: SensitiveActionGuard,
    val availability: ToolRegistry.Availability,
    val maxPlanNags: Int = 2,
    val maxNudges: Int = 2,
    /** Device tasks require a tool call every turn (answers go through finish); providers that cannot honour it fall back to auto. */
    val toolChoice: ToolChoice = ToolChoice.REQUIRED,
    /** User rules per app package, shown to the model when that app comes to the foreground. */
    val appRules: Map<String, String> = emptyMap(),
    /** Model context window (tokens); history is summarized when a prompt exceeds [compressAt] of it. */
    val contextWindow: Long = 128_000,
    val compressAt: Double = 0.8,
) {
    /** Cap on model turns of any kind (actions, looks, waits, plans, nudges): twice the action budget. */
    val maxSteps: Int get() = maxActions * 2
}

/** DECLINED: the user (or the unattended-task policy) refused an approval; the task ends unsuccessful. */
enum class EndReason { FINISHED, ANSWERED, STEP_LIMIT, STUCK, LLM_ERROR, DECLINED }

data class LoopOutcome(val success: Boolean, val summary: String, val steps: Int, val reason: EndReason)

/**
 * One task's observe → act → verify loop against an LLM with tool calling.
 *
 * Responsibilities: request/response turns with retry, tool execution through the shared
 * registry, plan-mode bookkeeping and completion check, the irreversible-action guard, stale-index
 * protection within a turn, stuck detection and the step limit. Conversation pruning is delegated
 * to [ContextManager]. The caller owns [conversation] (multi-turn chats reuse it).
 */
class AgentLoop(
    private val llm: LlmClient,
    private val registry: ToolRegistry,
    private val config: LoopConfig,
    private val user: UserChannel,
    private val contextFactory: (AgentHooks) -> ToolContext,
    private val listener: LoopListener = object : LoopListener {},
    private val contextManager: ContextManager = ContextManager(),
    /** Monotonic milliseconds for the step timings. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var plan: List<PlanStep> = emptyList()
        private set
    private var finishSummary: String? = null
    private var finishSuccess = true
    /** The action whose approval is refused in the current turn; ends the task unsuccessful. */
    private var declined: String? = null
    private var step = 0
    /** Calls of mutating tools so far (the tion budget). */
    var actions = 0
        private set
    private val stuck = StuckDetector()
    private val freshGuard = FreshScreenGuard()
    private var rulesShownFor: String? = null
    private var needsCompression = false

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

    suspend fun run(conversation: MutableList<Message>): LoopOutcome {
        com.eggman.backstage.tools.Observe.reset()
        var planNags = 0
        var nudges = 0
        var lastText = ""
        var maxTokens = config.maxTokens
        // The request this run works on is the newest message when it starts (earlier chat turns precede it).
        var requestIndex = conversation.lastIndex.coerceAtLeast(0)
        while (true) {
            if (needsCompression) {
                needsCompression = false
                if (HistoryCompressor.splitPoint(conversation) != null) {
                    // Before details are condensed away, keep what is worth remembering about the user (memory on only).
                    // both model calls run side by side; the memory pass reads a snapshot of the history.
                    val snapshot = conversation.toList()
                    val compressed = kotlinx.coroutines.coroutineScope {
                        val flush = ctx.services.memory?.let { m -> async { HistoryCompressor.flushToMemory(llm, snapshot, m) } }
                        val c = HistoryCompressor.compress(llm, conversation, anchor = requestIndex)
                        flush?.await()
                        c
                    }
                    if (compressed) requestIndex = 0
                }
            }
            listener.beforeStep()
            if (step >= config.maxSteps) return limitReached("$step steps", lastText)
            step++
            listener.onStep(step)
            // The listing about to be sent carries the [device] changes: mark them delivered.
            if (conversation.lastOrNull()?.let(::hasListing) == true) DeviceStateDiff.commit()
            val tools = registry.forAgent(config.availability).map { if (config.planMode) withPlanArgument(it) else it.spec() }
            val request = LlmRequest(
                // The system prompt and tools stay byte-identical for the whole task (a stable cache prefix). 
                // the live plan goes with the newest user turn instead.
                system = config.system,
                messages = contextManager.prepare(conversation).let { if (config.planMode) withPlan(it) else it },
                tools = tools,
                maxTokens = maxTokens,
                toolChoice = config.toolChoice,
            )
            val modelStart = clock()
            val reply = try {
                config.retry.run(onRetry = { a, d, e -> listener.onRetry(a, d, e) }) { llm.complete(request) }
            } catch (e: LlmException) {
                return LoopOutcome(false, e.userMessage(), step, EndReason.LLM_ERROR)
            }
            val modelMs = clock() - modelStart
            listener.onUsage(llm.model, reply.usage)
            if (reply.usage.inputTokens > config.contextWindow * config.compressAt) needsCompression = true
            val text = reply.message.text.trim()
            val calls = reply.message.toolCalls
            // A turn with neither text nor tool calls carries nothing, and OpenAI-style APIs reject it when it is
            // sent back (content null without tool_calls), so it never enters the history.
            if (text.isNotEmpty() || calls.isNotEmpty()) conversation += reply.message

            if (calls.isEmpty()) {
                listener.onTiming(StepTiming(step, null, modelMs, 0, 0))
                if (reply.stopReason == StopReason.MAX_TOKENS && text.isEmpty() && maxTokens < MAX_TOKENS_CEILING && nudges < config.maxNudges) {
                    // Reasoning models can spend the whole output budget thinking: ask the same question again with
                    // more room instead of nudging the model about a reply it never wrote.
                    nudges++
                    maxTokens = minOf(maxTokens * 4, MAX_TOKENS_CEILING)
                    continue
                }
                if (reply.stopReason == StopReason.MAX_TOKENS && nudges < config.maxNudges) {
                    nudges++
                    if (text.isNotEmpty()) listener.onAssistantText(text, final = false)
                    conversation += Message.user("Your reply was cut off by the length limit. Continue, more briefly.")
                    continue
                }
                if (text.isEmpty()) {
                    if (nudges < config.maxNudges) {
                        nudges++
                        conversation += Message.user("Continue: call the next tool, or give your final answer.")
                        continue
                    }
                    return LoopOutcome(false, "The model returned no answer.", step, EndReason.LLM_ERROR)
                }
                if (config.planMode && pendingPlanSteps().isNotEmpty() && planNags < config.maxPlanNags) {
                    planNags++
                    listener.onAssistantText(text, final = false)
                    conversation += Message.user(planReminder())
                    continue
                }
                listener.onAssistantText(text, final = true)
                return LoopOutcome(reply.stopReason != StopReason.REFUSAL, text, step, EndReason.ANSWERED)
            }
            if (text.isNotEmpty()) {
                lastText = text
                listener.onAssistantText(text, final = false)
            }

            val results = ArrayList<Part>()
            var screenChanged = false
            var terminal = false
            var acted = false
            val actStart = clock()
            var settleMs = 0L
            var timedTool: String? = null
            for (call in calls) {
                if (terminal) {
                    results += Part.ToolResult(call.id, "Skipped: the task already finished.", isError = true, name = call.name)
                    continue
                }
                // update_plan only changes the checklist: it may come along with the turn's one action.
                val planOnly = call.name == "update_plan" && config.planMode
                if (acted && !planOnly) {
                    // One action, then observe (for every provider): later calls of the same turn
                    // are planned against a screen the first one may have changed.
                    results += Part.ToolResult(call.id, ONE_PER_TURN, isError = true, name = call.name)
                    continue
                }
                if (!planOnly) { acted = true; timedTool = call.name }
                listener.onToolCall(call)
                val tool = registry[call.name]
                // only tools that change something use up the action budget. The action after the last one
                // allowed is refused (the model may still finish after the last allowed action).
                if (tool?.mutating == true) {
                    if (actions >= config.maxActions) {
                        results += Part.ToolResult(call.id, "Not done: the safety limit of ${config.maxActions} actions is used up.", isError = true, name = call.name)
                        conversation += Message(Role.USER, results + skipRest(calls, call))
                        return limitReached("$actions actions", lastText)
                    }
                    actions++
                    listener.onAction(actions)
                }
                val result = (if (call.name == ActSequenceTool.NAME) runSequence(call) else executeCall(call, screenChanged, planNags < config.maxPlanNags)).also { r ->
                    if (r.text.startsWith(PLAN_BLOCK)) planNags++
                }
                settleMs += result.settleMs
                listener.onToolResult(call, result)
                if ((tool?.mutating == true || call.name == ActSequenceTool.NAME) && !result.isError && tool?.targets?.isNotEmpty() == true) screenChanged = true
                results += Part.ToolResult(
                    callId = call.id,
                    text = result.text,
                    image = result.image?.let { Part.Image(it.base64, it.mediaType, it.width, it.height) },
                    isError = result.isError,
                    name = call.name,
                )
                if (result.terminal) terminal = true
                if (result.text.startsWith(STUCK_ABORT)) {
                    conversation += Message(Role.USER, results + skipRest(calls, call))
                    return LoopOutcome(false, "Stopped: the agent kept repeating an action that had no effect.", step, EndReason.STUCK)
                }
            }
            conversation += Message(Role.USER, results)
            listener.onTiming(StepTiming(step, timedTool, modelMs, (clock() - actStart - settleMs).coerceAtLeast(0), settleMs))
            declined?.let { action ->
                // A refused approval ends the task as unsuccessful, whatever the model would report afterwards.
                return LoopOutcome(false, "Not done: “${action.take(300)}” was not approved.", step, EndReason.DECLINED)
            }
            if (terminal) {
                return LoopOutcome(finishSuccess, finishSummary?.takeIf { it.isNotBlank() } ?: lastText.ifBlank { "Done." }, step, EndReason.FINISHED)
            }
        }
    }

    private fun limitReached(what: String, lastText: String): LoopOutcome {
        val status = lastText.takeIf { it.isNotBlank() }?.let { " Last status: ${it.take(300)}" } ?: ""
        return LoopOutcome(false, "Stopped after $what without finishing (safety limit).$status", step, EndReason.STEP_LIMIT)
    }

    private fun skipRest(calls: List<Part.ToolCall>, after: Part.ToolCall): List<Part> =
        calls.dropWhile { it !== after }.drop(1).map { Part.ToolResult(it.id, "Skipped.", isError = true, name = it.name) }

    private fun planReminder(): String =
        "Your plan still has unfinished steps: " + pendingPlanSteps().joinToString("; ") { "\"${it.title}\" (${it.status})" } +
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

    private fun hasListing(m: Message): Boolean = m.parts.any { p ->
        (p is Part.ToolResult && ContextManager.SCREEN.containsMatchIn(p.text)) || (p is Part.Text && ContextManager.SCREEN.containsMatchIn(p.text))
    }

    /** Acting tools offer an optional `plan` argument in plan mode (the checklist rides along with the action). */
    private fun withPlanArgument(t: com.eggman.backstage.tools.Tool): com.eggman.backstage.llm.ToolSpec {
        val spec = t.spec()
        if (!t.mutating || t.targets.isEmpty()) return spec
        val schema = JSONObject(spec.schema.toString())
        schema.optJSONObject("properties")?.put("plan", PlanArgs.schema)
        return spec.copy(schema = schema)
    }

    /** The live plan, added to the newest user turn of this request only (the stored conversation is unchanged). */
    private fun withPlan(messages: List<Message>): List<Message> {
        val text = PlanSection.render(plan).trim()
        val last = messages.lastOrNull()
        return if (last != null && last.role == Role.USER) messages.dropLast(1) + last.copy(parts = last.parts + Part.Text(text))
        else messages + Message.user(text)
    }

    /**
     * act_sequence: runs each allowed step through [executeCall] (guards, approvals, stuck detection, the action
     * budget), stopping at the first failure, at an index whose element differs from the listing the model saw,
     * or (stop_on_change) when another app comes to the front. One observation for the model at the end.
   */
    private suspend fun runSequence(call: Part.ToolCall): ToolResult {
        val steps = call.args.optJSONArray("steps") ?: return ToolResult.error("steps must be an array")
        if (steps.length() == 0) return ToolResult.error("steps is empty")
        if (steps.length() > ActSequenceTool.MAX_STEPS) return ToolResult.error("At most ${ActSequenceTool.MAX_STEPS} steps per sequence")
        val parsed = (0 until steps.length()).map { i ->
            val o = steps.optJSONObject(i) ?: return ToolResult.error("step ${i + 1} must be an object")
            val name = o.optString("tool")
            val t = registry[name]
            if (name !in ActSequenceTool.ALLOWED || t == null || t.irreversible) {
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
            // Secret steps intentionally return no screen; a sequence must preserve that boundary.
            if (parsed.any { (name, args) -> name == "type_text" && (args.optBoolean("secret") || args.opt("text") is JSONObject) }) {
                com.eggman.backstage.tools.Observe.invalidate(exec)
                return null
            }
            return try { com.eggman.backstage.tools.Observe.fullObservation(ctx, exec) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { com.eggman.backstage.tools.Observe.invalidate(exec); null }
        }
        // type_text redacts a refused or secret text in its own (cloned) args; carry that back into the parent
        // sequence call so history and logs never keep the plaintext.
        fun syncRedactions() {
            for ((i, pair) in parsed.withIndex()) {
                val redacted = pair.second.opt("text") as? JSONObject ?: continue
                steps.optJSONObject(i)?.optJSONObject("args")?.apply { put("text", redacted); remove("reason") }
            }
        }
        try {
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
            val r = com.eggman.backstage.tools.Observe.withoutShowing(exec) {
                executeCall(sub, screenChanged = false, mayNagPlan = false)
            }
            listener.onToolResult(sub, r)
            settle += r.settleMs
            lines += "${i + 1}. $name: ${r.text.lineSequence().firstOrNull().orEmpty().take(160)}"
            if (r.text.startsWith(STUCK_ABORT)) {
                val observation = finalObservation()
                val text = r.text + observation?.text?.let { "\n\n$it" }.orEmpty()
                return r.copy(text = executorPeek(call)?.let { com.eggman.backstage.tools.Observe.redactText(it, text, ctx) } ?: text,
                    image = observation?.image, settleMs = settle)
            }
            if (r.isError) { stop = "step ${i + 1} failed"; break }
            if (declined != null) break
            if (stopOnChange && i < parsed.lastIndex) {
                val pkg = executorPeek(sub)?.lastScreen?.foregroundPackage
                if (!startPkg.isNullOrBlank() && !pkg.isNullOrBlank() && pkg != startPkg) { stop = "another app ($pkg) came to the front after step ${i + 1}"; break }
            }
        }
        } finally { syncRedactions() }
        val done = lines.size
        val head = "Sequence: $done of ${parsed.size} steps done" + (stop?.let { " — stopped: $it" } ?: "") + "\n" + lines.joinToString("\n")
        // Inner observations are hidden: the model needs the entire resulting screen, including earlier steps.
        val observation = finalObservation()
        val text = head + observation?.text?.let { "\n\n$it" }.orEmpty()
        return ToolResult(executorPeek(call)?.let { com.eggman.backstage.tools.Observe.redactText(it, text, ctx) } ?: text,
            image = observation?.image,
            isError = done == 0, settleMs = settle)
    }

    companion object {
        const val PLAN_BLOCK = "Not finished yet:"
        const val STUCK_ABORT = "Aborted: repeated ineffective action."
        const val ONE_PER_TURN = "Skipped: only the first tool call of a turn runs. Look at its result, then make the next call."
        /** Upper bound when the output budget is raised after an empty, length-truncated reply. */
        const val MAX_TOKENS_CEILING = 32_768
    }
}

/** Optional ToolContext capability: look at an existing executor without creating/starting anything. */
interface PeekableContext {
    fun peek(kind: TargetKind): Executor?
}
