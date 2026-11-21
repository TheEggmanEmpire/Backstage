package com.eggman.backstage.tools

import com.eggman.backstage.exec.ActionResult
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.ScreenDiff
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.exec.ScreenState
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.UiElement
import kotlinx.coroutines.delay

internal val BOTH = setOf(TargetKind.DESKTOP, TargetKind.PHONE)

/** Direct clients without an agent conversation receive self-contained observations on every call. */
interface FullScreenListingContext

/** Shared observe step: every acting tool returns the fresh screen so the model verifies its effect. */
internal object Observe {
    /** Fewer elements than this usually means a canvas/game/web content: add a screenshot. */
    const val SPARSE = 2

    /** Unchanged screens are not re-sent more than this many times in a row (the last listing stays current). */
    const val MAX_SUPPRESSED = 2
    private val unchangedStreak = java.util.WeakHashMap<Executor, Int>()
    private val diffStreak = java.util.WeakHashMap<Executor, Int>()
    private data class Shown(val state: ScreenState, val task: java.lang.ref.WeakReference<AgentHooks>?)
    private val lastShown = java.util.WeakHashMap<Executor, Shown>()
    private data class Secrets(val task: java.lang.ref.WeakReference<AgentHooks>?, val values: MutableSet<String> = LinkedHashSet())
    private val secrets = java.util.WeakHashMap<Executor, Secrets>()
    private val hidden = java.util.WeakHashMap<Executor, Int>()
    const val FULL_EVERY = 6

    /** Invalidate observation history without altering the executor's capture or element handles. */
    fun reset(exec: Executor? = null) {
        invalidate(exec)
        synchronized(secrets) { if (exec == null) secrets.clear() else secrets.remove(exec) }
    }

    /** A compressed observation chain needs resynchronising; task secrets still need masking afterwards. */
    fun invalidate(exec: Executor? = null) {
        synchronized(lastShown) { if (exec == null) lastShown.clear() else lastShown.remove(exec) }
        synchronized(unchangedStreak) { if (exec == null) unchangedStreak.clear() else unchangedStreak.remove(exec) }
        synchronized(diffStreak) { if (exec == null) diffStreak.clear() else diffStreak.remove(exec) }
    }

    private fun shown(ctx: ToolContext, exec: Executor): ScreenState? {
        val previous = synchronized(lastShown) { lastShown[exec] }
        val registered = synchronized(secrets) { secrets[exec] }
        // Hooks belong to one task. A new task must not inherit a previous task's model-visible baseline.
        if (ctx is FullScreenListingContext) {
            invalidate(exec)
            return null
        }
        val owner = if (previous != null) previous.task?.get() else registered?.task?.get()
        if ((previous != null || registered != null) && owner !== ctx.hooks) {
            reset(exec)
            return null
        }
        return previous?.state
    }

    private fun remember(ctx: ToolContext?, exec: Executor, state: ScreenState) {
        if (ctx is FullScreenListingContext) { invalidate(exec); return }
        if (synchronized(hidden) { (hidden[exec] ?: 0) > 0 }) return
        synchronized(lastShown) { lastShown[exec] = Shown(state, ctx?.hooks?.let { java.lang.ref.WeakReference(it) }) }
    }

    fun registerSecret(ctx: ToolContext, exec: Executor, text: String) {
        if (text.length < 2) return
        shown(ctx, exec) // Discard a previous task's registrations before creating this task's set.
        synchronized(secrets) {
            val existing = secrets[exec]?.takeIf { it.task?.get() === ctx.hooks }
            val registered = existing ?: Secrets(ctx.hooks?.let { java.lang.ref.WeakReference(it) }).also { secrets[exec] = it }
            registered.values += text
        }
    }

    /** Text-only masking; snapshots and element handles retain their original values for matching/actions. */
    fun redactText(exec: Executor, text: String, ctx: ToolContext? = null): String {
        val shownTask = synchronized(lastShown) { lastShown[exec]?.task?.get() }
        val values = synchronized(secrets) {
            val task = ctx?.hooks ?: shownTask ?: secrets[exec]?.task?.get()
            if (task != null) secrets.values.filter { it.task?.get() === task }.flatMap { it.values }.distinct()
            else secrets[exec]?.values?.toList().orEmpty()
        }
        return values.sortedByDescending { it.length }.fold(text) { result, secret -> result.replace(secret, "••••") }
    }

    /** MCP structured state carries strings as JSON values; replacing serialized numbers would corrupt JSON. */
    fun redactJson(exec: Executor, value: org.json.JSONObject): org.json.JSONObject {
        fun redact(value: Any?): Any? = when (value) {
            is String -> redactText(exec, value)
            is org.json.JSONObject -> org.json.JSONObject().apply { value.keys().forEach { key -> put(key, redact(value.get(key))) } }
            is org.json.JSONArray -> org.json.JSONArray().apply { for (i in 0 until value.length()) put(redact(value.get(i))) }
            else -> value
        }
        return redact(value) as org.json.JSONObject
    }

    /** Sequence steps are executed normally, but their intermediate captures are never sent to the model. */
    suspend fun <T> withoutShowing(exec: Executor?, block: suspend () -> T): T {
        if (exec == null) return block()
        synchronized(hidden) { hidden[exec] = (hidden[exec] ?: 0) + 1 }
        try { return block() } finally {
            synchronized(hidden) {
                val depth = (hidden[exec] ?: 1) - 1
                if (depth == 0) hidden.remove(exec) else hidden[exec] = depth
            }
        }
    }

    suspend fun after(ctx: ToolContext, exec: Executor, action: ActionResult, settleMs: Long = 3_000, showScreen: Boolean = true): ToolResult {
        val before = shown(ctx, exec)
        val visible = showScreen && synchronized(hidden) { (hidden[exec] ?: 0) == 0 }
        if (!action.ok) {
            return ToolResult.error(redactText(exec, action.message + if (visible) "\n\n" + fullListing(exec, safeScreen(exec), ctx) else "", ctx))
        }
        val settleStart = System.nanoTime()
        val outcome = exec.awaitSettleOutcome(settleMs)
        val (screen, changing) = settledScreen(exec, ctx.stabilityMs)
        val settled = (System.nanoTime() - settleStart) / 1_000_000
        if (!visible) return ToolResult(redactText(exec, action.message, ctx), settleMs = settled)
        val continuous = before != null && before.displayId == screen.displayId &&
            before.foregroundPackage == screen.foregroundPackage && screen.indexGeneration > 0 &&
            before.indexGeneration == screen.indexGeneration
        val changes = before?.let { ScreenDiff.lines(it, screen) }.orEmpty()
        val unchanged = before != null && screen.fingerprint == before.fingerprint
        // A reset invalidates old targets even if the visible content happens to hash identically.
        val sameIndices = before?.elements?.map { it.index } == screen.elements.map { it.index }
        val canSuppress = ctx !is FullScreenListingContext && unchanged && !outcome.changed && sameIndices
        val streak = synchronized(unchangedStreak) {
            val n = if (canSuppress) (unchangedStreak[exec] ?: 0) + 1 else 0
            unchangedStreak[exec] = if (n > MAX_SUPPRESSED) 0 else n
            n
        }
        val image = if (ctx.visionEnabled && ctx.autoScreenshot && screen.elements.size < SPARSE) runCatching { exec.screenshot() }.getOrNull() else null
        val note = (if (image != null) "\n(Few accessible elements: a screenshot is attached.)" else "") +
            if (changing || !outcome.settled) "\n(The screen was still changing; if something is loading, await_screen_change before acting on it.)" else ""
        if (canSuppress && streak in 1..MAX_SUPPRESSED) {
            return ToolResult(redactText(exec, action.message + "\n\n(No visible change on screen yet; if something is loading use await_screen_change — keep using the previous listing.)" + note, ctx),
                image = image, settleMs = settled)
        }
        val diff = changes.joinToString("\n")
        val count = synchronized(diffStreak) { diffStreak[exec] ?: 0 }
        val listing = if (continuous && before!!.omittedElements == 0 && screen.omittedElements == 0 &&
            changes.isNotEmpty() && changes.size <= 40 && diff.length * 2 < screen.text.length && count < FULL_EVERY - 1) {
            synchronized(diffStreak) { diffStreak[exec] = count + 1 }
            remember(ctx, exec, screen)
            ScreenSerializer.withChanges(screen, diff)
        } else fullListing(exec, screen, ctx)
        return ToolResult(redactText(exec, action.message + "\n\n" + listing + note, ctx), image = image, settleMs = settled)
    }

    /** Explicit observations/waits always resynchronise the full listing and its suppression counters. */
    fun fullListing(exec: Executor, screen: ScreenState, ctx: ToolContext? = null): String {
        synchronized(unchangedStreak) { unchangedStreak.remove(exec) }
        synchronized(diffStreak) { diffStreak.remove(exec) }
        remember(ctx, exec, screen)
        return redactText(exec, screen.text, ctx)
    }

    /** A complete sequence result uses the same sparse-screen screenshot rule as an explicit get_screen. */
    suspend fun fullObservation(ctx: ToolContext, exec: Executor): ToolResult {
        val screen = exec.screen()
        val image = if (ctx.visionEnabled && ctx.autoScreenshot && screen.elements.size < SPARSE) runCatching { exec.screenshot() }.getOrNull() else null
        return ToolResult(fullListing(exec, screen, ctx), image = image)
    }

    /**
     * Polls the screen until its content (ignoring clocks and other volatile text) stays the same for
     * [stableMs], at most [maxMs]; a moving screen restarts the window. Returns the latest capture.
   */
    suspend fun stableScreen(exec: Executor, stableMs: Long, pollMs: Long = 300, maxMs: Long = 5_000): ScreenState {
        var current = exec.screen()
        if (stableMs <= 0) return current
        var stableFor = 0L
        var waited = 0L
        while (stableFor < stableMs && waited < maxMs) {
            delay(pollMs)
            waited += pollMs
            val next = exec.screen()
            if (next.fingerprint == current.fingerprint) stableFor += pollMs else stableFor = 0
            current = next
        }
        return current
    }

    suspend fun safeScreen(exec: Executor): ScreenState = exec.screen()

    /** Loading indicators: a progress bar or a short "Loading…"-style label. */
    private val loadingLabel = Regex("^(loading|please wait|wird geladen|chargement|cargando|caricamento)\\b", RegexOption.IGNORE_CASE)

    fun looksLoading(screen: ScreenState, capture: com.eggman.backstage.exec.ScreenCapture? = null): Boolean =
        screen.elements.any {
            if (it.className.contains("ProgressBar", ignoreCase = true)) isSpinner(it.className, it.range)
            else it.label.length <= 40 && loadingLabel.containsMatchIn(it.label.trim())
        } || capture?.windows?.any { w -> w.kind == com.eggman.backstage.exec.WindowKind.APPLICATION && w.root?.let(::hasSpinner) == true } == true

    /** An unlabelled spinner is no listed element: look for it in the raw tree (application windows only). */
    private fun hasSpinner(n: com.eggman.backstage.exec.UiNode, depth: Int = 0): Boolean =
        depth < 60 && n.visible && (isSpinner(n.className, n.range) || n.children.any { hasSpinner(it, depth + 1) })

    private fun isSpinner(className: String, range: Triple<Float, Float, Float>?): Boolean =
        className.contains("ProgressBar", ignoreCase = true) &&
            (range == null || !range.first.isFinite() || !range.second.isFinite() || !range.third.isFinite() || range.second <= range.first)

    /**
     * The screen after an action's event-quiet settle (fast mode): one capture, confirmed by polling only while
     * a loading indicator shows, for at most [CONFIRM_CAP_MS]. Returns the screen and whether it is still changing.
     * Without fast mode the stability polling runs unchanged.
   */
    suspend fun settledScreen(exec: Executor, stabilityMs: Long): Pair<ScreenState, Boolean> {
        if (!com.eggman.backstage.exec.AgentPace.isFast()) return stableScreen(exec, stabilityMs, pollMs = com.eggman.backstage.exec.AgentPace.stablePollMs()) to false
        var current = exec.screen()
        if (stabilityMs <= 0 || !looksLoading(current, exec.lastCapture)) return current to false
        var waited = 0L
        val poll = com.eggman.backstage.exec.AgentPace.stablePollMs()
        while (waited < CONFIRM_CAP_MS) {
            delay(poll)
            waited += poll
            val next = exec.screen()
            if (next.fingerprint == current.fingerprint && !looksLoading(next, exec.lastCapture)) return next to false
            current = next
        }
        return current to true
    }

    const val CONFIRM_CAP_MS = 1_500L

    /**
     * The element an action targets: `index` from the latest listing or a stable `nid` (from the
     * markup format / MCP). Null when neither is given (coordinates are used instead).
   */
    suspend fun target(exec: Executor, args: Args): UiElement? {
        args.optInt("index")?.let { return element(exec, it) }
        val nid = args.optString("nid") ?: return null
        val screen = exec.lastScreen ?: exec.screen()
        screen.elements.firstOrNull { it.nid == nid }?.let { return it }
        val cap = exec.lastCapture
        val found = cap?.let { com.eggman.backstage.exec.ScreenMarkup.findByNid(it, nid) }
            ?: throw ToolArgumentException("NODE_NOT_FOUND: no element with nid $nid on the current screen; read the screen again (nids change when elements move or change text)")
        val (node, windowId) = found
        return UiElement(-1, node.shortClass, node.text.ifBlank { node.contentDescription }, node.text, node.contentDescription, node.hint,
            node.resourceId.substringAfter(":id/"), node.bounds, emptyList(), windowId, node.packageName, node.handle, nid)
    }

    /** Resolves an element index against the last screen the model saw (capturing one if none). */
    suspend fun element(exec: Executor, index: Int): UiElement {
        val screen = exec.lastScreen ?: exec.screen()
        return screen.element(index)
            ?: throw ToolArgumentException("No element [$index] on the current screen (valid: ${screen.elements.joinToString(",") { it.index.toString() }}). Call get_screen to refresh indices.")
    }

    /** x/y from args, in display pixels; `space:"image"` converts from the last screenshot's pixels. */
    fun point(exec: Executor, args: Args, xName: String = "x", yName: String = "y"): Pair<Float, Float> {
        val x = args.optDouble(xName) ?: throw ToolArgumentException("Give an element index or both $xName and $yName")
        val y = args.optDouble(yName) ?: throw ToolArgumentException("Give an element index or both $xName and $yName")
        if (args.optString("space") == "image") {
            val shot = exec.lastScreenshot ?: throw ToolArgumentException("space=image needs a previous screenshot")
            return shot.toDisplay(x, y)
        }
        if (x < 0 || y < 0 || x > exec.width || y > exec.height) {
            throw ToolArgumentException("($x,$y) is outside the ${exec.width}x${exec.height} screen")
        }
        return x.toFloat() to y.toFloat()
    }
}

internal suspend fun ToolContext.exec(args: Args): Executor = executor(parseTarget(args.optString("target")))

class GetScreenTool : Tool {
    override val name = "get_screen"
    override val description = "List what is on screen now as numbered UI elements (type, label, resource id, centre coordinates, traits such as click/edit/scroll). Use the [index] numbers with tap, type_text, scroll. After actions, + / - / ~ lines show appeared / gone / changed elements; unchanged elements keep their index."
    override val schema = Schema.obj(Schema.target)
    override val targets = BOTH
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        return Observe.fullObservation(ctx, exec)
    }
}

class ScreenshotTool : Tool {
    override val name = "screenshot"
    override val description = "Capture an image of the screen. Use it when the element list is empty or ambiguous (images, maps, games, canvas or web content). Prefer element indices for acting; image coordinates can be passed to tap with space=\"image\"."
    override val schema = Schema.obj(Schema.target)
    override val targets = BOTH
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        if (!ctx.visionEnabled) return ToolResult.error("Screenshots are disabled (vision is off or the model does not accept images). Use get_screen.")
        val exec = ctx.exec(args)
        val img = exec.screenshot() ?: return ToolResult.error("Screenshot unavailable right now (display not drawn yet, or capture blocked). Use get_screen.")
        return ToolResult(
            "Screenshot ${img.width}x${img.height} of the ${exec.width}x${exec.height} ${exec.kind.label} screen" +
                (if (img.width != exec.width) " (scaled: multiply image x by %.3f and y by %.3f for screen pixels, or pass space=\"image\")".format(img.scaleX, img.scaleY) else "") + ".",
            image = img,
        )
    }
}

class AwaitScreenChangeTool : Tool {
    override val name = "await_screen_change"
    override val description = "Wait until the screen content changes (a page loads, a reply arrives), up to timeout_ms. Clocks, battery level and typing/online indicators do not count as changes."
    override val schema = Schema.obj(
        "reason" to Schema.str("The change you expect, e.g. \"search results appear\""),
        "timeout_ms" to Schema.int("Maximum wait in milliseconds, default 3000 (1-600 s)"),
        "timeout_seconds" to Schema.int("Alternative to timeout_ms, in seconds"),
        Schema.target,
    )
    override val targets = BOTH

    /** Last wait reason per executor: repeated waits for the same thing within a minute escalate. */
    private class WaitMemo(val reason: String, val atMs: Long, val count: Int)
    private val memo = java.util.WeakHashMap<Executor, WaitMemo>()

    private fun repeats(exec: Executor, reason: String?, now: Long): Int {
        val key = reason?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()
        return synchronized(memo) {
            val prev = memo[exec]
            val n = if (key.isNotEmpty() && prev != null && prev.reason == key && now - prev.atMs <= REPEAT_WINDOW_MS) prev.count + 1 else 1
            memo[exec] = WaitMemo(key, now, n)
            n
        }
    }

    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.exec(args)
        val repeat = repeats(exec, args.optString("reason"), ctx.services.nowMs())
        val requestedMs = args.optInt("timeout_ms") ?: args.optInt("timeout_seconds")?.let { it * 1000 } ?: 3_000
        val totalSec = (requestedMs / 1000).coerceIn(1, 600)
        val total = totalSec * 1000L
        val interval = (total / 3).coerceIn(500, 2_000)
        val baseline = (exec.lastScreen ?: exec.screen()).fingerprint
        var waited = 0L
        // with an event stream, look again as soon as something changed (at most every 300 ms), and at the
        // usual interval regardless; without one, the fixed interval as before.
        val eventDriven = exec.uiEventsSinceScreen() != null
        val tick = if (eventDriven) 100L else interval
        var sinceCapture = 0L
        while (waited < total) {
            delay(tick)
            waited += tick
            sinceCapture += tick
            val events = exec.uiEventsSinceScreen()
            if (eventDriven && sinceCapture < interval && (events == 0L || sinceCapture < 300)) continue
            sinceCapture = 0
            val now = exec.screen()
            if (now.fingerprint != baseline) return ToolResult(Observe.redactText(exec,
                "Screen changed after ${waited / 1000.0}s.\n\n" + Observe.fullListing(exec, now, ctx), ctx))
        }
        val hint = when { totalSec < 5 -> 6; totalSec < 9 -> 10; else -> 30 }
        val advice = if (repeat >= 2) {
            "This is wait number $repeat for the same thing within a minute; more waiting is unlikely to help. " +
                "Look for an error or a prompt, scroll, go back or reopen the app, or finish with success=false."
        } else {
            "If content is still loading, call again with a longer wait (e.g. timeout_ms=${hint * 1000})."
        }
        return ToolResult(Observe.redactText(exec, "TIMEOUT: nothing on screen changed during ${totalSec}s. $advice\n\n" +
            Observe.fullListing(exec, exec.lastScreen ?: exec.screen(), ctx), ctx), isError = true)
    }

    private companion object { const val REPEAT_WINDOW_MS = 60_000L }
}

class MoveFloatingWidgetTool : Tool {
    override val name = "move_floating_widget"
    override val description = "Move Backstage's floating bubble to another screen corner when it might cover something you need to tap or read (phone screen only)."
    override val schema = Schema.obj("position" to Schema.enum("Corner", "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"), required = listOf("position"))
    override val targets = setOf(TargetKind.PHONE)
    override val agentOnly = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val pos = args.string("position").uppercase()
        if (pos !in setOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT")) throw ToolArgumentException("position must be a corner")
        val c = com.eggman.backstage.exec.OverlayHooks.controller ?: return ToolResult("No floating bubble is shown; nothing to move.")
        return if (c.moveTo(pos)) ToolResult("Moved the bubble to $pos.") else ToolResult.error("The bubble could not be moved.")
    }
}

class WaitTool : Tool {
    override val name = "wait"
    override val description = "Wait for content to load or an animation to finish, then return the screen. Use instead of repeating the same action."
    override val schema = Schema.obj("seconds" to Schema.num("1-15, default 2"), Schema.target)
    override val targets = BOTH
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val ms = ((args.optDouble("seconds") ?: 2.0).coerceIn(0.5, 15.0) * 1000).toLong()
        val exec = ctx.exec(args)
        delay(ms)
        exec.awaitSettle(1_500)
        return ToolResult(Observe.redactText(exec, "Waited ${ms / 1000.0}s.\n\n" + Observe.fullListing(exec, exec.screen(), ctx), ctx))
    }
}
