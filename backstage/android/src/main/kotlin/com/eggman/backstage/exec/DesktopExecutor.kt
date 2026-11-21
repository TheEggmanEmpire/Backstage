package com.eggman.backstage.exec

import android.content.Intent
import android.graphics.Rect
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopTask
import com.eggman.backstage.host.DesktopTaskPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Operates apps on the Dextop desktop display (FULL, MINI or HIDDEN presentation). Input, frames
 * and launches go through [DesktopHost]; the UI tree comes from our accessibility service via
 * getWindowsOnAllDisplays (works for Dextop's direct owned VirtualDisplay, which is public).
 */
class DesktopExecutor(
    private val host: DesktopHost,
    geometry: DesktopHost.Geometry,
    ownPackages: Set<String>,
) : A11yExecutorBase(ownPackages) {
    override val kind = TargetKind.DESKTOP
    override val displayId: Int = geometry.displayId
    override val width: Int = geometry.width
    override val height: Int = geometry.height
    override val description: String
        get() = "the Dextop desktop (display $displayId, ${width}x$height, presentation ${host.presentation().name.lowercase()}" +
            (if (!host.directDisplay()) ", accessibility tree unavailable in this display mode" else "") + ")"

    /**
     * FULL: real input first (desktop apps react to it best). MINI/HIDDEN: accessibility node actions first, so
     * the background desktop never takes the phone's top focus or IME (injected touches may, on SDK 33 or when
     * the display is created without STEAL_TOP_FOCUS_DISABLED); injection stays the fallback.
   */
    override fun preferNodeClick() = runCatching { host.presentation() != DesktopHost.Presentation.FULL }.getOrDefault(false)

    override suspend fun grabScreenshot(): ScreenImage? {
        // The agent never sees its own cursor.
        val frame = com.eggman.backstage.cursor.AgentCursor.hiddenDuring(displayId) {
            runCatching { host.captureFrame() }.getOrNull() ?: a11y()?.screenshot(displayId)
        } ?: return null
        return encode(frame, width, height, screenshotQuality, screenshotScale).also { if (!frame.isRecycled) frame.recycle() }
    }

    private fun result(ok: Boolean, what: String) = if (ok) ActionResult.ok(what) else ActionResult.fail("$what was rejected by the system")

    override suspend fun clickElement(element: UiElement, long: Boolean): ActionResult {
        sharedControlRefusal(element)?.let { return ActionResult.fail(it) }
        return super.clickElement(element, long)
    }

    override suspend fun tap(x: Float, y: Float): ActionResult {
        cursor(com.eggman.backstage.cursor.CursorEvent.Tap(displayId, x, y))
        elementAt(x, y)?.let { e -> sharedControlRefusal(e)?.let { return ActionResult.fail(it) } }
        if (host.tap(x, y)) return ActionResult.ok("Tapped (${x.toInt()},${y.toInt()})")
        val s = a11y()
        if (s != null && s.tap(displayId, x, y)) return ActionResult.ok("Tapped (${x.toInt()},${y.toInt()}) via accessibility gesture")
        return ActionResult.fail("Tap at (${x.toInt()},${y.toInt()}) was rejected by the system")
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long) =
        cursor(com.eggman.backstage.cursor.CursorEvent.LongPress(displayId, x, y, durationMs)).let { result(host.longPress(x, y, durationMs) || a11y()?.longPress(displayId, x, y, durationMs) == true, "Long press at (${x.toInt()},${y.toInt()})") }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): ActionResult {
        cursor(com.eggman.backstage.cursor.CursorEvent.Swipe(displayId, x1, y1, x2, y2, durationMs))
        windowAt(x1, y1)?.let { w -> SharedRecentsGuard.swipeRefusal(w.title, w.packageName, homePackages())?.let { return ActionResult.fail(it) } }
        return result(host.swipe(x1, y1, x2, y2, durationMs) || a11y()?.swipe(displayId, x1, y1, x2, y2, durationMs) == true, "Swipe")
    }

    override suspend fun rightClick(x: Float, y: Float) = cursor(com.eggman.backstage.cursor.CursorEvent.Tap(displayId, x, y)).let { result(host.rightClick(x, y), "Right-click at (${x.toInt()},${y.toInt()})") }

    override suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float) =
        cursor(com.eggman.backstage.cursor.CursorEvent.Scroll(displayId, x, y, wheelDirection(vertical, horizontal))).let { result(host.scrollWheel(x, y, vertical, horizontal), "Mouse wheel scroll") }

    private fun wheelDirection(v: Float, h: Float) = when {
        kotlin.math.abs(h) > kotlin.math.abs(v) -> if (h > 0) "right" else "left"
        else -> if (v < 0) "up" else "down"
    }

    override suspend fun scrollElement(element: UiElement?, direction: String): ActionResult {
        // Desktop apps respond to the mouse wheel better than to touch flings; try it first.
        val area = element?.bounds ?: Box(0, 0, width, height)
        cursor(com.eggman.backstage.cursor.CursorEvent.Scroll(displayId, area.centerX.toFloat(), area.centerY.toFloat(), direction))
        val amount = when (direction.lowercase()) { "down" -> 5f to 0f; "up" -> -5f to 0f; "right" -> 0f to 5f; "left" -> 0f to -5f; else -> null }
        if (amount != null && host.scrollWheel(area.centerX.toFloat(), area.centerY.toFloat(), amount.first, amount.second)) {
            return ActionResult.ok("Scrolled ${direction.lowercase()}")
        }
        return super.scrollElement(element, direction)
    }

    override suspend fun setText(element: UiElement?, text: String): ActionResult = com.eggman.backstage.a11y.TextInputSession.withSession { session ->
        withContext(Dispatchers.IO) {
            cursor(com.eggman.backstage.cursor.CursorEvent.Type(displayId, element?.centerX?.toFloat(), element?.centerY?.toFloat()))
            val s = a11y()
            if (s != null && s.setText(element?.handle, displayId, text)) return@withContext ActionResult.ok("Entered text (verified)")
            var status = s?.textVerification(element?.handle, displayId, text)
            if (status in setOf(com.eggman.backstage.a11y.TextVerification.LENGTH_LIMIT, com.eggman.backstage.a11y.TextVerification.FILTERED)) {
                return@withContext ActionResult.fail("Text entry failed: ${status!!.message}")
            }
            if (element != null) {
                tap(element.centerX.toFloat(), element.centerY.toFloat())
                if (s != null && s.setText(null, displayId, text)) return@withContext ActionResult.ok("Entered text (verified)")
                status = s?.textVerification(null, displayId, text)
            }
            if (status in setOf(com.eggman.backstage.a11y.TextVerification.LENGTH_LIMIT, com.eggman.backstage.a11y.TextVerification.FILTERED)) {
                return@withContext ActionResult.fail("Text entry failed: ${status!!.message}")
            }
            if (!key("ctrl+a").ok) return@withContext ActionResult.fail("Text entry failed: could not select the field contents")
            if (!host.typeText(text)) return@withContext ActionResult.fail("Text entry failed: ${status?.message ?: "nothing taken; tap the input field first"}")
            when (val verified = s?.textVerification(null, displayId, text)) {
                com.eggman.backstage.a11y.TextVerification.VERIFIED -> ActionResult.ok("Typed text via keyboard input (verified)")
                null -> ActionResult.ok("Typed text via keyboard input; verification unavailable")
                else -> ActionResult.fail("Text entry failed: ${verified.message}")
            }
        }.let { result ->
            session.clipboardNotice?.let { result.copy(message = "${result.message}. $it") } ?: result
        }
    }

    override suspend fun key(name: String): ActionResult {
        val n = KeyNames.normalize(name)
        if (n in setOf("back", "home", "recents")) {
            if (host.globalAction(n)) {
                // The desktop's recent-apps view lists the phone's apps too: "Close all" there closed them (user report).
                return ActionResult.ok(if (n == "recents") "Pressed recents. ${SharedRecentsGuard.RECENTS_NOTE}" else "Pressed $n")
            }
            // KEYCODE_APP_SWITCH is handled for the whole phone: it would open the phone's Overview.
            if (n == "recents") return ActionResult.fail(DesktopKeyPolicy.RECENTS_UNAVAILABLE)
        }
        val chord = KeyNames.chord(name) ?: return ActionResult.fail("Unknown key '$name'")
        return keyChord(chord.first, chord.second, name)
    }

    /**
     * Phone-global keys are refused ([DesktopKeyPolicy]); a combination the catalog knows ([DesktopShortcuts.match]) is
     * refused when it acts on the phone, and home/back/close-window ones are carried out on the desktop display.
   */
    override suspend fun keyChord(keyCode: Int, metaState: Int, label: String): ActionResult {
        DesktopKeyPolicy.refusal(keyCode)?.let { return ActionResult.fail(it) }
        val shortcut = DesktopShortcuts.match(DesktopShortcuts.Chord(keyCode, metaState))
        when (shortcut?.route) {
            ShortcutRoute.REFUSED -> return ActionResult.fail("Not sent: ${shortcut.action} ($label). ${shortcut.note}")
            ShortcutRoute.DESKTOP_HOME -> return key("home")
            ShortcutRoute.DESKTOP_BACK -> return key("back")
            ShortcutRoute.CLOSE_WINDOW -> return closeDesktopWindows(DesktopCloseScope.Focused)
            ShortcutRoute.KEYS, null -> Unit
        }
        return result(host.key(keyCode, metaState), "Key $label")
    }

    /** The app windows of this desktop display only ([DesktopTaskPolicy]: no phone task, launcher or Backstage itself). */
    override fun desktopTasks(): List<DesktopTask>? =
        (host as? DesktopHostExtras)?.let { runCatching { it.desktopTasks() }.getOrNull() }
            ?.let { DesktopTaskPolicy.select(it, displayId, ownPackageSet) }

    override suspend fun closeDesktopWindows(scope: DesktopCloseScope, installed: List<AppEntry>): ActionResult =
        withContext(Dispatchers.IO) {
            DesktopWindowCloser.close(host as? DesktopHostExtras, displayId, ownPackageSet, scope, installed)
        }

    // ---- shared system controls (the recent-apps view lists the phone's apps too) -------------------------------

    /** The smallest listed element under (x, y) in the last listing. */
    private fun elementAt(x: Float, y: Float): UiElement? =
        lastScreen?.elements?.filter { it.bounds.contains(x.toInt(), y.toInt()) }?.minByOrNull { it.bounds.area }

    /** The listed window under (x, y), top-most first. */
    private fun windowAt(x: Float, y: Float): WindowSummary? =
        lastScreen?.windows?.filter { it.bounds.contains(x.toInt(), y.toInt()) }?.firstOrNull()

    private fun sharedControlRefusal(element: UiElement): String? {
        if (!SharedRecentsGuard.closeAllControl(element)) return null
        val window = lastScreen?.windows?.firstOrNull { it.id == element.windowId }
        return SharedRecentsGuard.tapRefusal(element, window?.title.orEmpty(), window?.packageName.orEmpty(), homePackages())
    }

    /** The device's home apps (they host the recent-apps view); asked only when a guard needs them. */
    private fun homePackages(): Set<String> = runCatching {
        a11y()?.service?.packageManager?.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.mapNotNull { it.activityInfo?.packageName }?.toSet()
    }.getOrNull().orEmpty()

    override fun stateLine(): String? {
        val extras = host as? DesktopHostExtras
        val tasks = desktopTasks()
        val phoneFront = extras?.let { runCatching { it.phoneTasks() }.getOrNull() }
            ?.firstOrNull { it.displayId == LaunchCheck.PHONE_DISPLAY && it.visible && it.packageName !in ownPackageSet }
        val presentation = runCatching { host.presentation().name }.getOrDefault("NONE")
        return ScreenStateLine.desktop(displayId, width, height, presentation, tasks, phoneFront)
    }

    /** Window control on this desktop (and, when allowed, the phone) for window_control and launch placement. */
    fun windowController(phoneAllowed: Boolean, phoneSafe: com.eggman.backstage.host.TaskBounds? = null) =
        WindowController(host as? DesktopHostExtras, Triple(displayId, width, height), phoneSafe, ownPackageSet, phoneAllowed)

    /**
     * Launches on the desktop display, then checks where the window really is: on the desktop -> done (and maximized when asked); on the phone -> moved to the desktop, and if that
     * fails the launch is reported as failed so the agent never continues on the phone.
   */
    override suspend fun launchApp(packageName: String, slot: String?, bounds: Box?, window: LaunchWindow, position: PopupPosition): ActionResult {
        val placeSlot = slot ?: when (window) {
            LaunchWindow.POPUP -> "center"
            else -> null
        }
        val ok = when {
            placeSlot != null -> host.launchPackageAt(packageName, placeSlot)
            bounds != null -> host.launchPackage(packageName, Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
            else -> host.launchPackage(packageName, null)
        }
        if (!ok) return ActionResult.fail((host as? DesktopHostExtras)?.launchRefusal(packageName) ?: "Could not launch $packageName on the desktop")
        val launched = "Launched $packageName on the desktop${placeSlot?.let { " ($it)" } ?: ""}"
        val extras = host as? DesktopHostExtras ?: return ActionResult.ok(launched)
        val windows = windowController(phoneAllowed = false)
        var place = awaitPlace(packageName, windows)
        var note = ""
        if (place is LaunchPlace.Phone) {
            val task = (place as LaunchPlace.Phone).task
            val moved = runCatching {
                extras.controlTask(task.taskId, LaunchCheck.PHONE_DISPLAY, com.eggman.backstage.host.TaskOp.MOVE_TO_DESKTOP,
                    DesktopWindowRules.defaultWindow(width, height))
            }.getOrDefault(false)
            place = if (moved) awaitPlace(packageName, windows, onlyDesktop = true) else place
            if (place !is LaunchPlace.Desktop) {
                return ActionResult.fail("$packageName opened on the PHONE screen instead of the desktop and could not be moved " +
                    "to the desktop. Do not operate it on the phone. Try window_control action move_to_desktop once; if that " +
                    "fails, tell the user (the app may be open on their phone).")
            }
            note = " It had opened on the phone and was moved to the desktop."
        }
        return when (val p = place) {
            is LaunchPlace.Desktop -> {
                if (window == LaunchWindow.MAXIMIZED || window == LaunchWindow.FULLSCREEN) {
                    val r = windows.control(WindowAction.MAXIMIZE, TargetKind.DESKTOP, p.task.packageName, null, PopupPosition.CENTER, emptyList())
                    note += if (r.ok) " Its window is maximized (fills the desktop)." else " Maximizing failed: ${r.message}"
                }
                ActionResult.ok("$launched; verified: its window is on the desktop (display $displayId).$note")
            }
            LaunchPlace.Unknown -> ActionResult.ok("$launched (not verified: the task list is unavailable).")
            else -> ActionResult.ok("$launched; its window is not in the desktop's task list yet: check the screen.")
        }
    }

    private suspend fun awaitPlace(packageName: String, windows: WindowController, onlyDesktop: Boolean = false, timeoutMs: Long = 3_000): LaunchPlace {
        var waited = 0L
        var last: LaunchPlace = LaunchPlace.NotFound
        while (true) {
            val desktop = windows.desktopTasks()
            val phone = if (onlyDesktop) emptyList() else windows.phoneTasks()
            last = LaunchCheck.where(packageName, displayId, desktop, phone)
            if (last is LaunchPlace.Desktop || last is LaunchPlace.Unknown) return last
            // A window on the phone may still move to the desktop while the launch settles: give it a moment.
            if (waited >= timeoutMs || (last is LaunchPlace.Phone && waited >= 1_000)) return last
            val poll = AgentPace.launchPollMs()
            kotlinx.coroutines.delay(poll)
            waited += poll
        }
    }

    override suspend fun openUri(uri: String): ActionResult {
        val service = a11y()?.service
        val pm = service?.packageManager
        val intent = runCatching {
            SafeIntents.forUri(uri, ownPackageSet, { i -> pm?.resolveActivity(i, 0)?.activityInfo?.packageName }) { i ->
                // No default for this link: the desktop would show a chooser; open it in the default app's place instead.
                service?.let { DefaultAppsCache.pinForLink(it, i, ownPackageSet) }
            }
        }.getOrElse { return ActionResult.fail("Cannot open $uri: ${it.message}") }
        val note = service?.let { DefaultAppsCache.pinnedNote(it, intent, uri) }.orEmpty()
        return if (host.launchIntent(intent, null)) ActionResult.ok("Opened $uri on the desktop$note") else ActionResult.fail("No app on the desktop could open $uri")
    }
}
