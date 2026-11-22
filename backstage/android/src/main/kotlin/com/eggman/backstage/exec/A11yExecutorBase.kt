package com.eggman.backstage.exec

import android.graphics.Bitmap
import android.util.Base64
import com.eggman.backstage.a11y.AgentAccessibility
import java.io.ByteArrayOutputStream

/** Shared plumbing for executors that read the screen through the app's accessibility service ([AgentAccessibility]). */
abstract class A11yExecutorBase(ownPackages: Set<String>) : Executor {
    protected val serializer = ScreenSerializer(ownPackages)
    /** Our own package(s): never a target for model-supplied intents ([SafeIntents]). */
    protected val ownPackageSet: Set<String> = ownPackages

    @Volatile final override var lastScreen: ScreenState? = null
        private set
    @Volatile final override var lastScreenshot: ScreenImage? = null
        private set
    @Volatile final override var lastCapture: ScreenCapture? = null
        private set

    /** JPEG quality and scale (percent) for screenshots (settings). */
    @Volatile var screenshotQuality: Int = 72
    @Volatile var screenshotScale: Int = 100

    protected fun a11y(): AgentAccessibility? = AgentAccessibility.instance

    protected fun requireA11y(): AgentAccessibility =
        a11y() ?: throw IllegalStateException(
            if (com.eggman.backstage.a11y.AccessibilityBridge.hostComponent() == null) com.eggman.backstage.a11y.DistributionAccessibility.status ?: com.eggman.backstage.a11y.AccessibilityEnabler.NO_HOST
            else "Backstage cannot see or act: its accessibility service \"Backstage\" is switched off (Settings → Accessibility).",
        )

    private fun read(): Pair<ScreenState, ScreenCapture> {
        val service = a11y()
        val w = width
        val h = height
        val capture = service?.capture(displayId, w, h) ?: ScreenCapture(displayId, w, h, emptyList())
        val state = serializer.serialize(capture, kind.label, previous = lastScreen).let {
            if (service == null) it.copy(text = it.text + "(accessibility service disabled: only screenshots and coordinates are available)\n") else it
        }.let { withStateLine(it) }
        return state to capture
    }

    /**
     * One line of explicit state ([ScreenStateLine]) put right under the listing's header: which screen the agent acts
     * on, how the desktop is shown, each screen's front app and window modes. Null when there is nothing to add.
   */
    protected open fun stateLine(): String? = null

    /** Shows [event] with the agent cursor and lets it lead the action slightly ([com.eggman.backstage.cursor.AgentCursor]). */
    protected suspend fun cursor(event: com.eggman.backstage.cursor.CursorEvent) = com.eggman.backstage.cursor.AgentCursor.before(event)

    private fun withStateLine(state: ScreenState): ScreenState {
        val device = runCatching { com.eggman.backstage.agent.DeviceStateDiff.line() }.getOrNull()
        val line = listOfNotNull(runCatching { stateLine() }.getOrNull()?.takeIf { it.isNotBlank() }, device)
            .joinToString("\n").takeIf { it.isNotBlank() } ?: return state
        val cut = state.text.indexOf('\n')
        val text = if (cut < 0) state.text + "\n" + line + "\n" else state.text.substring(0, cut + 1) + line + "\n" + state.text.substring(cut + 1)
        return state.copy(text = text)
    }

    @Volatile private var screenEventMark: Long? = null

    override fun uiEventsSinceScreen(): Long? {
        val mark = screenEventMark ?: return null
        val now = a11y()?.settleFor(displayId)?.eventCount ?: return null
        return (now - mark).coerceAtLeast(0)
    }

    override suspend fun screen(): ScreenState {
        // The mark is taken before the capture: an event during the capture counts as a change (safe side).
        screenEventMark = a11y()?.settleFor(displayId)?.eventCount
        val (state, capture) = read()
        lastCapture = capture
        lastScreen = state
        return state
    }

    override suspend fun observe(): Pair<ScreenState, ScreenCapture?> = read()

    /** Takes a screenshot of this executor's display (encoded for the model); null when none is available. */
    protected abstract suspend fun grabScreenshot(): ScreenImage?

    final override suspend fun screenshot(): ScreenImage? = grabScreenshot()?.also { lastScreenshot = it }

    override suspend fun observeScreenshot(): ScreenImage? = grabScreenshot()

    override suspend fun awaitSettleOutcome(maxMs: Long, expectChangeMs: Long): SettleOutcome {
        val detector = a11y()?.settleFor(displayId) ?: return super<Executor>.awaitSettleOutcome(maxMs, expectChangeMs)
        val outcome = detector.awaitOutcome(quietMs = AgentPace.settleQuietMs(), maxMs = maxMs, expectChangeMs = expectChangeMs)
        return SettleOutcome(outcome.changed, outcome.settled, outcome.waitedMs)
    }

    override suspend fun awaitSettle(maxMs: Long) {
        val s = a11y() ?: run { kotlinx.coroutines.delay(minOf(maxMs, 600)); return }
        s.settleFor(displayId).awaitSettled(quietMs = AgentPace.settleQuietMs(), maxMs = maxMs)
    }

    override suspend fun clickElement(element: UiElement, long: Boolean): ActionResult {
        val cx = element.centerX.toFloat()
        val cy = element.centerY.toFloat()
        cursor(if (long) com.eggman.backstage.cursor.CursorEvent.LongPress(displayId, cx, cy, 650) else com.eggman.backstage.cursor.CursorEvent.Tap(displayId, cx, cy))
        val s = a11y()
        if (preferNodeClick() && s != null && s.click(element.handle, long)) return ActionResult.ok(clickMsg(element, long, "accessibility"))
        val r = if (long) longPress(element.centerX.toFloat(), element.centerY.toFloat()) else tap(element.centerX.toFloat(), element.centerY.toFloat())
        if (r.ok) return ActionResult.ok(clickMsg(element, long, "input"))
        if (!preferNodeClick() && s != null && s.click(element.handle, long)) return ActionResult.ok(clickMsg(element, long, "accessibility"))
        return r
    }

    /** Phone: node actions first (immune to our own overlay); desktop: real input first. */
    protected abstract fun preferNodeClick(): Boolean

    private fun clickMsg(e: UiElement, long: Boolean, via: String) =
        "${if (long) "Long-pressed" else "Tapped"} [${e.index}] ${e.className}${if (e.label.isNotEmpty()) " \"${e.label.take(60)}\"" else ""}"

    override suspend fun scrollElement(element: UiElement?, direction: String): ActionResult {
        val at = element?.bounds ?: Box(0, 0, width, height)
        cursor(com.eggman.backstage.cursor.CursorEvent.Scroll(displayId, at.centerX.toFloat(), at.centerY.toFloat(), direction))
        val d = direction.lowercase()
        val forward = d == "down" || d == "right"
        val s = a11y()
        if (element != null && s != null && (d == "down" || d == "up") && s.scroll(element.handle, forward)) {
            return ActionResult.ok("Scrolled $d in [${element.index}]")
        }
        // Gesture fallback: swipe inside the element (or the middle of the screen). Content moves opposite to the finger.
        val area = element?.bounds?.takeIf { it.width > 40 && it.height > 40 } ?: Box(0, height / 6, width, height * 5 / 6)
        val cx = area.centerX.toFloat(); val cy = area.centerY.toFloat()
        val dy = area.height * 0.35f; val dx = area.width * 0.35f
        val r = when (d) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy, 400)
            "up" -> swipe(cx, cy - dy, cx, cy + dy, 400)
            "right" -> swipe(cx + dx, cy, cx - dx, cy, 400)
            "left" -> swipe(cx - dx, cy, cx + dx, cy, 400)
            else -> return ActionResult.fail("direction must be up, down, left or right")
        }
        return if (r.ok) ActionResult.ok("Scrolled $d") else r
    }

    companion object {
        const val MAX_IMAGE_SIDE = 1280

        fun encode(bitmap: Bitmap, displayWidth: Int, displayHeight: Int, quality: Int = 72, scalePercent: Int = 100): ScreenImage {
            val scale = minOf(MAX_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height), scalePercent.coerceIn(10, 100) / 100f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), out)
            val img = ScreenImage(
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg", scaled.width, scaled.height,
                if (displayWidth > 0) displayWidth else bitmap.width, if (displayHeight > 0) displayHeight else bitmap.height,
            )
            if (scaled !== bitmap) scaled.recycle()
            return img
        }
    }
}
