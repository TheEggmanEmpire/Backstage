package com.eggman.backstage.cursor

import kotlinx.coroutines.delay

/** What the agent cursor shows. */
enum class CursorAction { IDLE, TAP, LONG_PRESS, SWIPE, TYPE, SCROLL, THINKING, HIDDEN }

data class CursorPoint(val x: Float, val y: Float)

/**
 * The cursor on one display: where it is, what it shows there ([action]), the swipe path, the scroll direction, a short
 * step label, and how long the effect lasts. Pure data, drawn by a [CursorSink].
 */
data class CursorState(
    val displayId: Int = -1,
    val action: CursorAction = CursorAction.HIDDEN,
    val at: CursorPoint? = null,
    val path: List<CursorPoint> = emptyList(),
    val direction: String? = null,
    val label: String? = null,
    val durationMs: Long = 0,
) {
    val visible: Boolean get() = action != CursorAction.HIDDEN && at != null
}

/** One thing the agent is about to do (or is doing) that the cursor shows. */
sealed class CursorEvent {
    abstract val displayId: Int

    data class Tap(override val displayId: Int, val x: Float, val y: Float) : CursorEvent()
    data class LongPress(override val displayId: Int, val x: Float, val y: Float, val durationMs: Long) : CursorEvent()
    data class Swipe(override val displayId: Int, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long) : CursorEvent()
    /** Typing into the field at (x, y); null = the focused field, where the cursor already is. */
    data class Type(override val displayId: Int, val x: Float?, val y: Float?) : CursorEvent()
    data class Scroll(override val displayId: Int, val x: Float, val y: Float, val direction: String) : CursorEvent()
    /** Waiting for the model: subtle dots next to the last position. */
    data class Thinking(override val displayId: Int = -1) : CursorEvent()
    data class Label(val text: String?, override val displayId: Int = -1) : CursorEvent()
    data class Hide(override val displayId: Int = -1) : CursorEvent()
}

/**
 * Cursor state transitions per action type (own design for Backstage). Pure, unit-tested: the cursor glides to the
 * target of each action; thinking keeps it where it is; a new display starts fresh; hide clears everything.
 */
object CursorModel {
    const val LABEL_MAX = 40

    fun next(s: CursorState, e: CursorEvent): CursorState {
        // A different display (desktop <-> phone): the cursor appears there, the other one is gone.
        val base = if (e.displayId >= 0 && e.displayId != s.displayId) CursorState(displayId = e.displayId, label = s.label) else s
        return when (e) {
            is CursorEvent.Tap -> base.copy(action = CursorAction.TAP, at = CursorPoint(e.x, e.y), path = emptyList(), direction = null, durationMs = 0)
            is CursorEvent.LongPress -> base.copy(action = CursorAction.LONG_PRESS, at = CursorPoint(e.x, e.y), path = emptyList(), direction = null, durationMs = e.durationMs)
            is CursorEvent.Swipe -> base.copy(
                action = CursorAction.SWIPE, at = CursorPoint(e.x1, e.y1),
                path = listOf(CursorPoint(e.x1, e.y1), CursorPoint(e.x2, e.y2)), direction = null, durationMs = e.durationMs,
            )
            is CursorEvent.Type -> base.copy(
                action = CursorAction.TYPE,
                at = if (e.x != null && e.y != null) CursorPoint(e.x, e.y) else base.at,
                path = emptyList(), direction = null, durationMs = 0,
            )
            is CursorEvent.Scroll -> base.copy(action = CursorAction.SCROLL, at = CursorPoint(e.x, e.y), path = emptyList(), direction = e.direction.lowercase(), durationMs = 0)
            is CursorEvent.Thinking -> if (s.at == null) s else s.copy(action = CursorAction.THINKING, path = emptyList(), direction = null)
            is CursorEvent.Label -> s.copy(label = e.text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let(::shorten))
            is CursorEvent.Hide -> CursorState()
        }
    }

    /** Where the cursor ends after the action (a swipe ends at its last point). */
    fun endPoint(s: CursorState): CursorPoint? = s.path.lastOrNull() ?: s.at

    /** The same action at the same place again within [windowMs] (e.g. a click that falls back to a tap): no second lead. */
    fun duplicate(prev: CursorState, next: CursorState, sinceMs: Long, windowMs: Long = 400): Boolean =
        sinceMs in 0..windowMs && prev.displayId == next.displayId && prev.action == next.action && prev.at == next.at && prev.path == next.path

    private fun shorten(t: String) = if (t.length <= LABEL_MAX) t else t.take(LABEL_MAX - 1).trimEnd() + "…"
}

/** Desktop (or phone) display pixels to the overlay view's pixels; identity when both have the same size. Pure. */
object CursorGeometry {
    fun toOverlay(p: CursorPoint, displayWidth: Int, displayHeight: Int, overlayWidth: Int, overlayHeight: Int): CursorPoint {
        if (displayWidth <= 0 || displayHeight <= 0 || overlayWidth <= 0 || overlayHeight <= 0) return p
        val sx = overlayWidth.toFloat() / displayWidth
        val sy = overlayHeight.toFloat() / displayHeight
        return CursorPoint((p.x * sx).coerceIn(0f, overlayWidth - 1f), (p.y * sy).coerceIn(0f, overlayHeight - 1f))
    }
}

/** How long the cursor leads an action and whether it animates (Android's "remove animations": static markers). */
object CursorTiming {
    const val DEFAULT_LEAD_MS = 200
    const val MAX_LEAD_MS = 600

    /** Animations are off when the animator duration scale is 0. */
    fun animate(animatorScale: Float): Boolean = animatorScale > 0f

    /** The delay before the injected action: the setting, 0 when the cursor is off or animations are removed. */
    fun leadMs(enabled: Boolean, settingMs: Int, animatorScale: Float): Long =
        if (!enabled || !animate(animatorScale)) 0L else settingMs.coerceIn(0, MAX_LEAD_MS).toLong()

    /** Glide time to the next target: 150-300 ms scaled like other animations; 0 = jump (static). */
    fun glideMs(animatorScale: Float): Long = if (!animate(animatorScale)) 0L else (220 * animatorScale).toLong().coerceIn(150L, 300L)
}

/** Draws the cursor (the Android overlay, a fake in tests). Called on any thread. */
interface CursorSink {
    fun render(state: CursorState)
    /** Hides the cursor on [displayId] before a capture, so the agent never sees it. Returns once it is off screen. */
    suspend fun hideForCapture(displayId: Int)
    fun restoreAfterCapture(displayId: Int)
    /** Current animator duration scale (Settings.Global); 1 by default. */
    fun animatorScale(): Float = 1f
}

/**
 * The agent cursor: executors report each action before carrying it out ([before]), the controller reports thinking,
 * the step label and the end of a task. Off without a sink (tests, no accessibility service) or when "Show agent cursor"
 * is off.
 */
object AgentCursor {
    @Volatile var sink: CursorSink? = null
    /** "Show agent cursor" and the lead time; set by the agent environment. */
    @Volatile var enabled: () -> Boolean = { true }
    @Volatile var leadSettingMs: () -> Int = { CursorTiming.DEFAULT_LEAD_MS }
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile var state = CursorState()
        private set
    @Volatile private var lastAt = 0L

    private fun on(): Boolean = sink != null && runCatching { enabled() }.getOrDefault(false)

    /** Shows [event] and waits the lead time, so the cursor arrives slightly before the action. */
    suspend fun before(event: CursorEvent) {
        val s = sink ?: return
        if (!on()) return
        val prev = state
        val next = CursorModel.next(prev, event)
        val now = clock()
        val dup = CursorModel.duplicate(prev, next, now - lastAt)
        state = next
        lastAt = now
        runCatching { s.render(next) }
        if (dup) return
        val lead = CursorTiming.leadMs(true, runCatching { leadSettingMs() }.getOrDefault(CursorTiming.DEFAULT_LEAD_MS), runCatching { s.animatorScale() }.getOrDefault(1f))
        if (lead > 0) delay(lead)
    }

    fun thinking() = update(CursorEvent.Thinking())
    fun label(text: String?) = update(CursorEvent.Label(text))

    fun hide() {
        state = CursorState()
        runCatching { sink?.render(state) }
    }

    private fun update(e: CursorEvent) {
        val s = sink ?: return
        if (!on()) return
        state = CursorModel.next(state, e)
        runCatching { s.render(state) }
    }

    /** Runs a capture of [displayId] with the cursor off screen (screenshots and frames the agent reads). */
    suspend fun <T> hiddenDuring(displayId: Int, block: suspend () -> T): T {
        val s = sink
        if (s == null || !state.visible || state.displayId != displayId) return block()
        runCatching { s.hideForCapture(displayId) }
        try {
            return block()
        } finally {
            runCatching { s.restoreAfterCapture(displayId) }
        }
    }

    /** Tests. */
    internal fun reset() {
        sink = null; enabled = { true }; leadSettingMs = { CursorTiming.DEFAULT_LEAD_MS }; clock = { System.currentTimeMillis() }
        state = CursorState(); lastAt = 0L
    }
}
