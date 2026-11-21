package com.eggman.backstage.exec

/** Concrete place the agent acts on (the API-level [com.eggman.backstage.agent.api.Target] adds AUTO). */
enum class TargetKind(val label: String) {
    DESKTOP("desktop"),
    PHONE("phone");
}

/** A JPEG/PNG screenshot ready for a vision model, with the scale relative to display pixels. */
data class ScreenImage(
    val base64: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val scaleX: Double get() = if (width == 0) 1.0 else displayWidth.toDouble() / width
    val scaleY: Double get() = if (height == 0) 1.0 else displayHeight.toDouble() / height

    fun toDisplay(x: Double, y: Double): Pair<Float, Float> = (x * scaleX).toFloat() to (y * scaleY).toFloat()
}

/** Outcome of a device action, phrased for the model. */
/** Result of [Executor.awaitSettleOutcome]: [changed] false = no UI reaction in the first-change window. */
data class SettleOutcome(val changed: Boolean, val settled: Boolean, val waitedMs: Long)

data class ActionResult(val ok: Boolean, val message: String) {
    companion object {
        fun ok(msg: String) = ActionResult(true, msg)
        fun fail(msg: String) = ActionResult(false, msg)
    }
}

data class WindowInfo(val title: String, val packageName: String, val bounds: Box, val focused: Boolean)

/**
 * What tools drive. Two implementations: [DesktopExecutor] (Dextop desktop display through
 * [com.eggman.backstage.host.DesktopHost]) and [PhoneExecutor] (display 0 through accessibility).
 * Tests use a fake. Coordinates are display pixels of [displayId].
 */
interface Executor {
    val kind: TargetKind
    val displayId: Int
    val width: Int
    val height: Int
    val description: String

    /** Captures and serializes the current screen; remembered as [lastScreen] for index lookups. */
    suspend fun screen(): ScreenState
    val lastScreen: ScreenState?
    /** Raw tree behind [lastScreen] (markup rendering, nid lookups). */
    val lastCapture: ScreenCapture? get() = null
    suspend fun screenshot(): ScreenImage?
    val lastScreenshot: ScreenImage?

    /**
     * Reads the screen without touching [lastScreen]/[lastCapture]. For observers (MCP, UI) that must not
     * replace the listing a running task resolves indices and the fresh-screen guard against.
   */
    suspend fun observe(): Pair<ScreenState, ScreenCapture?> = screen() to lastCapture
    /** A screenshot that does not replace [lastScreenshot] (the image `space=image` coordinates refer to). */
    suspend fun observeScreenshot(): ScreenImage? = screenshot()

    suspend fun tap(x: Float, y: Float): ActionResult
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 650): ActionResult
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): ActionResult
    suspend fun clickElement(element: UiElement, long: Boolean = false): ActionResult
    /** Replace the text of [element] (or the focused field when null). */
    suspend fun setText(element: UiElement?, text: String): ActionResult
    suspend fun scrollElement(element: UiElement?, direction: String): ActionResult
    suspend fun key(name: String): ActionResult
    /**
     * Opens [packageName]. Desktop: a window on the desktop display ([slot]/[bounds] place it), verified there. Phone:
     * fullscreen on display 0 unless [window] is [LaunchWindow.POPUP] (a pop-up inside the safe area).
   */
    suspend fun launchApp(packageName: String, slot: String? = null, bounds: Box? = null, window: LaunchWindow = LaunchWindow.DEFAULT, position: PopupPosition = PopupPosition.CENTER): ActionResult
    suspend fun openUri(uri: String): ActionResult
    /** Waits for the UI to stop changing after an action (bounded). */
    suspend fun awaitSettle(maxMs: Long = 3_000)

    /**
     * Two-phase settle: first waits up to [expectChangeMs] for the UI to react at all, then for quiet, at
     * most [maxMs] in total. [SettleOutcome.changed] is false when nothing reacted in the first window. Executors
     * without an event stream fall back to [awaitSettle] and report a change.
   */
    suspend fun awaitSettleOutcome(maxMs: Long = 3_000, expectChangeMs: Long = 500): SettleOutcome {
        val start = System.nanoTime()
        awaitSettle(maxMs)
        return SettleOutcome(changed = true, settled = true, waitedMs = (System.nanoTime() - start) / 1_000_000)
    }

    /**
     * Accessibility change events on this display since [lastScreen] is captured: 0 means nothing changed,
     * so a re-capture can be skipped; null when unknown (no event stream).
   */
    fun uiEventsSinceScreen(): Long? = null

    // Desktop-only extras; phone returns a polite failure.
    suspend fun rightClick(x: Float, y: Float): ActionResult = ActionResult.fail("right_click is only available on the desktop")
    suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float): ActionResult =
        ActionResult.fail("scroll_wheel is only available on the desktop")
    fun windows(): List<WindowInfo> = lastScreen?.windows?.filter { it.kind == WindowKind.APPLICATION }
        ?.map { WindowInfo(it.title, it.packageName, it.bounds, it.focused) }.orEmpty()

    /** Phone only: the area below the status bar and above the navigation bar (display pixels); null when unknown. */
    fun safeArea(): com.eggman.backstage.host.TaskBounds? = null

    /** Desktop only: the app windows (tasks) open on the desktop display, top-most first; null when unknown. */
    fun desktopTasks(): List<com.eggman.backstage.host.DesktopTask>? = null

    /** Desktop only: closes app windows on the desktop display ([DesktopWindowCloser]); the phone's apps are never touched. */
    suspend fun closeDesktopWindows(scope: DesktopCloseScope, installed: List<AppEntry> = emptyList()): ActionResult =
        ActionResult.fail("Closing windows this way only works on the desktop")

    /**
     * Desktop only: a key combination as real key events on the desktop display (modifiers down, key down/up,
     * modifiers up). Combinations that act on the phone are refused; home/back/close-window ones are carried out on
     * the desktop ([DesktopShortcuts]). [label] names it in the result.
   */
    suspend fun keyChord(keyCode: Int, metaState: Int, label: String): ActionResult =
        ActionResult.fail("Key combinations are only sent to the desktop")
}

/**
 * Keys the system handles for the whole phone whatever display they carry: on the desktop target
 * they would act on the phone the user is holding (screen off, camera, assistant, the phone's Overview, the
 * phone's music). They are refused for the desktop; the model uses the app's own controls instead.
 */
object DesktopKeyPolicy {
    // KeyEvent values: POWER 26, CAMERA 27, HEADSETHOOK 79, NOTIFICATION 83, APP_SWITCH 187, ASSIST 219,
    // BRIGHTNESS_DOWN/UP 220/221, SLEEP 223, WAKEUP 224, VOICE_ASSIST 231, SOFT_SLEEP 276, ALL_APPS 284.
    private val PHONE_GLOBAL = setOf(26, 27, 79, 83, 187, 219, 220, 221, 223, 224, 231, 276, 284)
    // MEDIA_PLAY_PAUSE 85, STOP 86, NEXT 87, PREVIOUS 88, REWIND 89, FAST_FORWARD 90, PLAY 126, PAUSE 127, RECORD 130.
    private val MEDIA = setOf(85, 86, 87, 88, 89, 90, 126, 127, 130)

    const val RECENTS_UNAVAILABLE = "The desktop's recent-apps view is not available right now (the recents key would open the " +
        "phone's own Overview). Use desktop_window_list to see the open windows, or launch_app to switch apps."

    /** Why [keyCode] may not be sent to the desktop, or null when it may. */
    fun refusal(keyCode: Int): String? = when (keyCode) {
        in PHONE_GLOBAL -> "That key acts on the whole phone (screen, camera, assistant, notifications or the phone's " +
            "Overview), not on the desktop, so it is not sent there. Use the desktop app's own controls."
        in MEDIA -> "Media keys control whatever is playing on the phone, not the desktop app. Use the desktop app's " +
            "on-screen play/pause controls instead."
        else -> null
    }
}

/** Normalized key names accepted by press_key, mapped to Android key codes (values from KeyEvent). */
object KeyNames {
    val codes: Map<String, Int> = linkedMapOf(
        "back" to 4, "home" to 3, "recents" to 187, "enter" to 66, "delete" to 67, "backspace" to 67,
        "forward_delete" to 112, "tab" to 61, "escape" to 111, "space" to 62, "search" to 84, "menu" to 82,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "page_up" to 92, "page_down" to 93,
        "move_home" to 122, "move_end" to 123, "volume_up" to 24, "volume_down" to 25, "volume_mute" to 164,
        "media_play_pause" to 85, "media_next" to 87, "media_previous" to 88, "power" to 26, "camera" to 27,
        "copy" to 278, "paste" to 279, "cut" to 277,
    )

    /** Accepts "Enter", "KEYCODE_ENTER", "ctrl+c" style is handled by [chord]. */
    fun normalize(name: String): String = name.trim().lowercase().removePrefix("keycode_").replace(' ', '_').replace('-', '_')

    fun code(name: String): Int? = codes[normalize(name)]

    /** Meta-state bits from KeyEvent: CTRL_ON|CTRL_LEFT_ON, SHIFT_ON|SHIFT_LEFT_ON, ALT_ON|ALT_LEFT_ON, META_ON|META_LEFT_ON. */
    private val modifiers = mapOf("ctrl" to (0x1000 or 0x2000), "control" to (0x1000 or 0x2000), "shift" to (0x1 or 0x40),
        "alt" to (0x2 or 0x10), "meta" to (0x10000 or 0x20000), "win" to (0x10000 or 0x20000), "cmd" to (0x10000 or 0x20000))

    /** Parses "ctrl+shift+t" into (keyCode, metaState); letters/digits map to KEYCODE_A.. / KEYCODE_0.. */
    fun chord(spec: String): Pair<Int, Int>? {
        val parts = spec.split('+').map { normalize(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var meta = 0
        for (m in parts.dropLast(1)) meta = meta or (modifiers[m] ?: return null)
        val last = parts.last()
        val code = codes[last] ?: when {
            last.length == 1 && last[0] in 'a'..'z' -> 29 + (last[0] - 'a')
            last.length == 1 && last[0] in '0'..'9' -> 7 + (last[0] - '0')
            last.matches(Regex("f([1-9]|1[0-2])")) -> 131 + last.drop(1).toInt() - 1
            else -> return null
        }
        return code to meta
    }
}

/**
 * Lets the  floating bubble/overlay get out of the way: hidden while the phone screen is
 * captured (so the model never sees or taps our own UI) and while gestures are dispatched.
 */
interface OverlayController {
    suspend fun hide()
    fun restore()
    /** Current on-screen bounds of the overlay (display 0 pixels) so gestures there can hide it first. */
    fun bounds(): Box? = null
    /** Move the bubble to a corner (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT). */
    fun moveTo(corner: String): Boolean = false
}

object OverlayHooks {
    /** Registered by the floating bubble (overlay.BubbleService) while it is shown; null otherwise. */
    @Volatile var controller: OverlayController? = null

    /** True when (x,y) lies on our own overlay, i.e. a gesture there would hit the bubble. */
    fun covers(x: Float, y: Float): Boolean = covering(x, y).isNotEmpty()

    /** The overlays ([controller] and [extra], e.g. the desktop's MINI tile) whose bounds contain (x,y). */
    fun covering(x: Float, y: Float, vararg extra: OverlayController?): List<OverlayController> =
        (listOf(controller) + extra).filterNotNull().filter { c ->
            runCatching { c.bounds()?.contains(x.toInt(), y.toInt()) == true }.getOrDefault(false)
        }

    /** Runs [block] with the bubble and every [extra] overlay hidden (phone screenshots). */
    suspend fun <T> hiddenDuring(vararg extra: OverlayController?, block: suspend () -> T): T =
        hiding((listOf(controller) + extra).filterNotNull(), block)

    /** Runs a gesture at (x,y) with only the overlays that would catch it hidden. */
    suspend fun <T> hiddenAt(x: Float, y: Float, vararg extra: OverlayController?, block: suspend () -> T): T =
        hiding(covering(x, y, *extra), block)

    private suspend fun <T> hiding(overlays: List<OverlayController>, block: suspend () -> T): T {
        if (overlays.isEmpty()) return block()
        overlays.forEach { runCatching { it.hide() } }
        try {
            return block()
        } finally {
            overlays.forEach { runCatching { it.restore() } }
        }
    }
}

/**
 * The Dextop desktop's MINI tile, an accessibility overlay of this app on the phone screen. While the
 * agent works on the phone, a coordinate gesture that lands on the tile would go into the desktop (or drag the tile),
 * and phone screenshots would show it over the app. Hidden through the host's public presentation switch (MINI to
 * HIDDEN and back; the desktop keeps running), only while it is MINI and only for the gesture or screenshot.
 */
class MiniTileOverlay(
    private val host: () -> com.eggman.backstage.host.DesktopHost?,
    private val tileBounds: () -> Box?,
    private val settleMs: Long = 120,
) : OverlayController {
    @Volatile private var hiddenByUs = false

    private fun mini(h: com.eggman.backstage.host.DesktopHost) =
        runCatching { h.sessionActive() && h.presentation() == com.eggman.backstage.host.DesktopHost.Presentation.MINI }.getOrDefault(false)

    override suspend fun hide() {
        val h = host() ?: return
        if (!mini(h)) return
        if (runCatching { h.setPresentation(com.eggman.backstage.host.DesktopHost.Presentation.HIDDEN) }.getOrDefault(false)) {
            hiddenByUs = true
            kotlinx.coroutines.delay(settleMs)
        }
    }

    override fun restore() {
        if (!hiddenByUs) return
        hiddenByUs = false
        val h = host() ?: return
        // Only undo our own switch: if the user changed the presentation meanwhile, theirs stands.
        if (runCatching { h.presentation() }.getOrNull() == com.eggman.backstage.host.DesktopHost.Presentation.HIDDEN) {
            runCatching { h.setPresentation(com.eggman.backstage.host.DesktopHost.Presentation.MINI) }
        }
    }

    override fun bounds(): Box? {
        val h = host() ?: return null
        return if (mini(h)) tileBounds() else null
    }

    companion object {
        const val TILE_TITLE = "Backstage mini desktop"

        /**
         * The tile among the phone's windows: an accessibility overlay of one of [ownPackages] that is not full-screen
         * (the host's pointer layer is), preferring the one titled [TILE_TITLE].
       */
        fun tileBounds(windows: List<UiWindow>, ownPackages: Set<String>, screenW: Int, screenH: Int): Box? {
            val screenArea = screenW.toLong() * screenH
            val candidates = windows.filter { w ->
                w.kind == WindowKind.ACCESSIBILITY_OVERLAY && w.packageName in ownPackages && !w.bounds.isEmpty &&
                    w.bounds.area * 10 < screenArea * 6
            }
            return (candidates.firstOrNull { it.title == TILE_TITLE } ?: candidates.singleOrNull())?.bounds
        }
    }
}
