package com.eggman.backstage.exec

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

/**
 * grouped control of the phone's own screen (display 0) through accessibility: node
 * actions and dispatchGesture, global actions for navigation, SET_TEXT for typing, and privileged
 * key injection when shell access exists. Our floating overlay is hidden while capturing and when a
 * gesture would land on it.
 */
class PhoneExecutor(
    private val context: Context,
    ownPackages: Set<String>,
) : A11yExecutorBase(ownPackages) {
    override val kind = TargetKind.PHONE
    override val displayId: Int = Display.DEFAULT_DISPLAY
    private val injector = if (PLAY_DISTRIBUTION) null else InputInjector(displayId)

    /** Re-read on every use: the phone rotates, and foldables change size when unfolded. */
    private val size = LiveDisplaySize {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        if (display != null) {
            val p = Point()
            @Suppress("DEPRECATION") display.getRealSize(p)
            p.x to p.y
        } else {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.let { it.width() to it.height() }
        }
    }
    override val width: Int get() = size.get().first
    override val height: Int get() = size.get().second

    override val description: String get() = "the phone's own screen (${width}x$height)"

    override fun preferNodeClick() = true

    /** The desktop's MINI tile, when the Dextop host is present. */
    private val miniTile: OverlayController = MiniTileOverlay({ com.eggman.backstage.host.DesktopHosts.current }, ::liveTileBounds)

    private fun liveTileBounds(): Box? {
        val s = a11y() ?: return null
        val windows = s.windowsOn(displayId).filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.map { w ->
            val r = android.graphics.Rect().also(w::getBoundsInScreen)
            UiWindow(
                id = w.id, kind = WindowKind.ACCESSIBILITY_OVERLAY, title = w.title?.toString().orEmpty(),
                packageName = runCatching { w.root?.packageName?.toString() }.getOrNull().orEmpty(),
                bounds = Box(r.left, r.top, r.right, r.bottom),
            )
        }
        return MiniTileOverlay.tileBounds(windows, ownPackageSet, width, height)
    }

    override suspend fun grabScreenshot(): ScreenImage? {
        val s = a11y() ?: return null
        val bmp = OverlayHooks.hiddenDuring(miniTile) {
            com.eggman.backstage.cursor.AgentCursor.hiddenDuring(displayId) { s.screenshot(displayId) }
        } ?: return null
        return encode(bmp, width, height, screenshotQuality, screenshotScale).also { bmp.recycle() }
    }

    private suspend fun gesture(x: Float, y: Float, block: suspend () -> Boolean): Boolean =
        OverlayHooks.hiddenAt(x, y, miniTile) { block() }

    override suspend fun tap(x: Float, y: Float): ActionResult {
        val s = requireA11y()
        cursor(com.eggman.backstage.cursor.CursorEvent.Tap(displayId, x, y))
        return if (gesture(x, y) { s.tap(displayId, x, y) }) ActionResult.ok("Tapped (${x.toInt()},${y.toInt()})")
        else ActionResult.fail("Tap gesture was cancelled")
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): ActionResult {
        val s = requireA11y()
        cursor(com.eggman.backstage.cursor.CursorEvent.LongPress(displayId, x, y, durationMs))
        return if (gesture(x, y) { s.longPress(displayId, x, y, durationMs) }) ActionResult.ok("Long-pressed (${x.toInt()},${y.toInt()})")
        else ActionResult.fail("Long-press gesture was cancelled")
    }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): ActionResult {
        val s = requireA11y()
        cursor(com.eggman.backstage.cursor.CursorEvent.Swipe(displayId, x1, y1, x2, y2, durationMs))
        return if (gesture(x1, y1) { s.swipe(displayId, x1, y1, x2, y2, durationMs) }) ActionResult.ok("Swiped")
        else ActionResult.fail("Swipe gesture was cancelled")
    }

    override suspend fun setText(element: UiElement?, text: String): ActionResult = com.eggman.backstage.a11y.TextInputSession.withSession { session ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val s = requireA11y()
            cursor(com.eggman.backstage.cursor.CursorEvent.Type(displayId, element?.centerX?.toFloat(), element?.centerY?.toFloat()))
            if (s.setText(element?.handle, displayId, text)) return@withContext ActionResult.ok("Entered text (verified)")
            session.failureHint?.let { return@withContext ActionResult.fail(it) }
            var status = s.textVerification(element?.handle, displayId, text)
            if (status in setOf(com.eggman.backstage.a11y.TextVerification.LENGTH_LIMIT, com.eggman.backstage.a11y.TextVerification.FILTERED)) {
                return@withContext ActionResult.fail("Text entry failed: ${status!!.message}")
            }
            if (element != null) {
                clickElement(element)
                awaitSettle(800)
                if (s.setText(null, displayId, text)) return@withContext ActionResult.ok("Entered text (verified)")
                session.failureHint?.let { return@withContext ActionResult.fail(it) }
                status = s.textVerification(null, displayId, text)
                if (status in setOf(com.eggman.backstage.a11y.TextVerification.LENGTH_LIMIT, com.eggman.backstage.a11y.TextVerification.FILTERED)) {
                    return@withContext ActionResult.fail("Text entry failed: ${status!!.message}")
                }
            }
            // Last resort only. Select-all is essential: key injection otherwise appends to an old value.
            if (injector != null && injector.available && injector.key(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON) && injector.typeViaKeys(text)) {
                val verified = s.textVerification(null, displayId, text)
                return@withContext when (verified) {
                    com.eggman.backstage.a11y.TextVerification.VERIFIED -> ActionResult.ok("Typed text via key events (verified)")
                    null -> ActionResult.ok("Typed text via key events; verification unavailable")
                    else -> ActionResult.fail("Text entry failed: ${verified.message}")
                }
            }
            ActionResult.fail("Text entry failed: ${status?.message ?: "nothing taken; tap the input field first"}")
        }.let { result ->
            session.clipboardNotice?.let { result.copy(message = "${result.message}. $it") } ?: result
        }
    }

    override suspend fun key(name: String): ActionResult {
        val n = KeyNames.normalize(name)
        val global = when (n) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "dismiss_notifications" -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            "media_play_pause" -> AccessibilityService.GLOBAL_ACTION_MEDIA_PLAY_PAUSE
            "all_apps" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS
            "split_screen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> null
        }
        val s = requireA11y()
        if (global != null && s.global(global)) return ActionResult.ok("Pressed $n")
        if (n == "enter" && s.imeEnter(displayId)) return ActionResult.ok("Pressed enter (keyboard action)")
        if (PLAY_DISTRIBUTION) return ActionResult.fail("Backstage Agent could not send '$name'. Use an on-screen control instead.")
        val chord = KeyNames.chord(name) ?: return ActionResult.fail("Unknown key '$name'")
        if (injector != null && injector.available && injector.key(chord.first, chord.second)) return ActionResult.ok("Pressed $name")
        return ActionResult.fail("Key '$name' needs privileged access (Shizuku / wireless debugging); on the phone only back, home, recents, notifications, quick_settings and enter work without it")
    }

    private fun launchContext(): Context = a11y()?.service ?: context

    private fun extras() = com.eggman.backstage.host.DesktopHosts.current as? com.eggman.backstage.host.DesktopHostExtras

    /** The phone's app tasks (display 0) from the host, when it can list them. */
    private fun phoneTasks(): List<com.eggman.backstage.host.DesktopTask>? =
        extras()?.let { runCatching { it.phoneTasks() }.getOrNull() }
            ?.filter { it.displayId == displayId && it.packageName.isNotBlank() && it.packageName !in ownPackageSet }

    /**
     * Below the status bar and above the navigation bar (and outside a display cutout), in display pixels. From the
     * window metrics' insets; the system bar dimensions are the fallback.
   */
    override fun safeArea(): com.eggman.backstage.host.TaskBounds? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        val metrics = runCatching { launchContext().getSystemService(WindowManager::class.java)?.maximumWindowMetrics }.getOrNull()
        val insets = metrics?.let {
            runCatching {
                it.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            }.getOrNull()
        }
        if (insets != null) return com.eggman.backstage.host.TaskBounds(insets.left, insets.top, w - insets.right, h - insets.bottom)
        val res = android.content.res.Resources.getSystem()
        fun dimen(name: String) = runCatching {
            res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize)
        }.getOrNull() ?: 0
        return com.eggman.backstage.host.TaskBounds(0, dimen("status_bar_height"), w, h - dimen("navigation_bar_height"))
    }

    override fun stateLine(): String? = ScreenStateLine.phone(
        width, height, phoneTasks(), safeArea(),
        desktopRunning = runCatching { com.eggman.backstage.host.DesktopHosts.current?.sessionActive() == true }.getOrDefault(false),
    )

    /**
     * Phone launches open NORMALLY: fullscreen windowing mode on display 0 and no launch bounds.
     * Dextop turns on freeform support for the whole system, so without an explicit mode a reused task that last ran
     * as a freeform window (e.g. on the desktop) came back to the phone floating, with its caption under the status
     * bar. A task that still ends up freeform is switched back to fullscreen through the host. [LaunchWindow.POPUP]
     * (only when the user asked for a window) opens a pop-up inside the safe area instead.
   */
    override suspend fun launchApp(packageName: String, slot: String?, bounds: Box?, window: LaunchWindow, position: PopupPosition): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.fail("$packageName has no launchable activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val popup = window == LaunchWindow.POPUP
        val safe = safeArea()
        val popupBounds = if (popup) safe?.let { PhoneWindows.popupBounds(it, position) } else null
        val options = PhoneLaunch.options(displayId, popupBounds)
        runCatching { launchContext().startActivity(intent, options.toBundle()) }
            .onFailure { return ActionResult.fail("Launch failed: ${it.message}") }
        return ActionResult.ok("Launched $packageName" + fixWindow(packageName, popup, popupBounds, safe))
    }

    /** After a launch: the app's phone task in the window mode that is asked for (fullscreen unless [popup]). */
    private suspend fun fixWindow(packageName: String, popup: Boolean, popupBounds: com.eggman.backstage.host.TaskBounds?, safe: com.eggman.backstage.host.TaskBounds?): String {
        val extras = extras() ?: return ""
        var waited = 0L
        var task: com.eggman.backstage.host.DesktopTask? = null
        while (waited <= 2_000L) {
            val tasks = phoneTasks() ?: return ""
            task = tasks.firstOrNull { it.packageName == packageName && it.visible }
            if (task != null) break
            val poll = AgentPace.launchPollMs()
            kotlinx.coroutines.delay(poll)
            waited += poll
        }
        val t = task ?: return ""
        return when (PhoneLaunch.correction(t, popup, safe)) {
            com.eggman.backstage.host.TaskOp.FULLSCREEN ->
                if (runCatching { extras.controlTask(t.taskId, displayId, com.eggman.backstage.host.TaskOp.FULLSCREEN) }.getOrDefault(false))
                    " (it came up as a floating window and was switched to fullscreen)"
                else " (it came up as a floating window; window_control action fullscreen fixes that)"
            com.eggman.backstage.host.TaskOp.FREEFORM -> {
                val b = popupBounds ?: return ""
                if (runCatching { extras.controlTask(t.taskId, displayId, com.eggman.backstage.host.TaskOp.FREEFORM, b) }.getOrDefault(false))
                    " as a pop-up window at $b" else " (it could not be made a pop-up window)"
            }
            else -> if (popup) " as a pop-up window" else ""
        }
    }

    override suspend fun openUri(uri: String): ActionResult = runCatching {
        val intent = SafeIntents.forUri(uri, ownPackageSet, { i -> context.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName }) { i ->
            DefaultAppsCache.pinForLink(context, i, ownPackageSet)
        }
        launchContext().startActivity(intent, PhoneLaunch.options(displayId, null).toBundle())
        ActionResult.ok("Opened $uri" + DefaultAppsCache.pinnedNote(context, intent, uri))
    }.getOrElse { ActionResult.fail("Could not open $uri: ${it.message}") }
}
