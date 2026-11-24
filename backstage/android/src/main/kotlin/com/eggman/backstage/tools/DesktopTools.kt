package com.eggman.backstage.tools

import com.eggman.backstage.exec.DesktopCloseScope
import com.eggman.backstage.exec.DesktopShortcutEnv
import com.eggman.backstage.exec.DesktopShortcuts
import com.eggman.backstage.exec.ShortcutRoute
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import org.json.JSONObject

private val DESKTOP = setOf(TargetKind.DESKTOP)

internal fun parsePresentation(v: String?): DesktopHost.Presentation? = when (v?.trim()?.lowercase()?.replace('-', '_')) {
    "full", "fullscreen", "full_screen" -> DesktopHost.Presentation.FULL
    "mini", "window", "pip", "small", "popup", "pop_up" -> DesktopHost.Presentation.MINI
    "hidden", "hide", "background" -> DesktopHost.Presentation.HIDDEN
    "in_app", "app", "player" -> DesktopHost.Presentation.IN_APP
    else -> null
}

/**
 * Whether the user explicitly asked for the DESKTOP ITSELF to be shown full screen. Making an app full screen is window_control
 * maximize; only words that put the desktop / DeX / the popup itself into full screen count. "full screen on DeX" or
 * "in DeX full screen" name a place (an app full screen there) and do not count. A short answer to the agent's
 * question ("the desktop", "DeX itself") counts. Pure.
 */
object PresentationRequest {
    private const val DESK = "(?:desktop|dex|dextop|popup|pop-up|pop up|mini window|floating window|desktop window)"
    private const val FULL = "(?:full[ -]?screen|vollbild|whole screen|entire screen|ganzen bildschirm|bigger|larger|expanded?|maximi[sz]ed?)"
    /** "the desktop (in) full screen", "make DeX bigger" — not preceded by a place preposition ("in DeX", "on the desktop"). */
    private val deskThenFull = Regex(
        "(?<!\\b(?:in|on|im|auf|at|into)\\s)(?<!\\b(?:in|on|im|auf|at|into)\\s(?:the|my|den|dem)\\s)\\b$DESK\\s+(?:(?:in|to|im|into|as|zu|mode|view|go|goes|auf|den|the)\\s+){0,3}$FULL",
        RegexOption.IGNORE_CASE,
    )
    /** "full screen the desktop", "expand the popup", "maximize DeX". */
    private val fullThenDesk = Regex(
        "\\b(?:full[ -]?screen|vollbild|expand|maximi[sz]e|enlarge|vergr\u00f6\u00dfer\\w*)\\s+(?:(?:the|my|whole|entire|with|den|die|das|mode|for)\\s+){0,3}$DESK\\b",
        RegexOption.IGNORE_CASE,
    )
    private val deskWord = Regex("\\b$DESK\\b", RegexOption.IGNORE_CASE)

    fun explicitFullDesktop(words: List<String>?): Boolean {
        if (words == null) return true // a direct MCP call: the caller asked for exactly this
        return words.withIndex().any { (i, w) ->
            deskThenFull.containsMatchIn(w) || fullThenDesk.containsMatchIn(w) ||
                // A short later answer naming the desktop ("the desktop", "DeX itself"); never the request itself.
                (i > 0 && w.trim().split(Regex("\\s+")).size <= 4 && deskWord.containsMatchIn(w))
        }
    }

    const val REFUSED = "Not changed: the user did not ask to show the desktop itself full screen. To make an APP full " +
        "screen, use window_control with action maximize (its window fills the desktop; the desktop stays a popup or in the " +
        "background). If the user really meant the whole desktop, ask them (ask_user) first."
}

/**
 * The desktop's state as data: whether it runs, its display id, size and density (the coordinate space of
 * desktop tools: pixels, origin top-left), how it is shown on the phone, the display backend, its focus mode and the
 * focused window when known. Never starts anything. Shared by desktop_status and MCP get_device_status.
 */
object DesktopStatusReport {
    fun json(host: DesktopHost?, focusedWindow: com.eggman.backstage.exec.WindowInfo? = null): JSONObject {
        val o = JSONObject().put("available", host != null)
        if (host == null) return o.put("active", false)
        val active = runCatching { host.sessionActive() }.getOrDefault(false)
        o.put("active", active)
        val geo = if (active) runCatching { host.geometry() }.getOrNull() else null
        o.put("display_id", geo?.displayId ?: JSONObject.NULL)
            .put("width", geo?.width ?: JSONObject.NULL)
            .put("height", geo?.height ?: JSONObject.NULL)
            .put("density_dpi", geo?.densityDpi ?: JSONObject.NULL)
            .put("presentation", runCatching { host.presentation().name.lowercase() }.getOrDefault("none"))
            .put("direct_display", runCatching { host.directDisplay() }.getOrDefault(false))
        val extras = host as? com.eggman.backstage.host.DesktopHostExtras
        if (extras != null) {
            o.put("focus_mode", runCatching { extras.desktopFocusMode() }.getOrNull() ?: JSONObject.NULL)
            o.put("runs_while_locked", active && runCatching { extras.desktopRunsWhileLocked() }.getOrDefault(false))
        }
        o.put("focused_window", focusedWindow?.let { w ->
            JSONObject().put("title", w.title).put("package", w.packageName).put("bounds", w.bounds.toString())
        } ?: JSONObject.NULL)
        return o
    }

    fun text(o: JSONObject): String {
        if (!o.optBoolean("available")) return "No Dextop desktop in this build."
        if (!o.optBoolean("active")) return "The desktop is not running (desktop_start starts it)."
        return buildString {
            append("Desktop running on display ").append(o.opt("display_id"))
            append(", ").append(o.opt("width")).append('x').append(o.opt("height")).append(" px (origin top-left)")
            append(", shown ").append(o.optString("presentation"))
            if (!o.optBoolean("direct_display")) append(" (overlay display backend: cannot be minimized, hidden or read)")
            o.optString("focus_mode").takeIf { it.isNotEmpty() && it != "null" }?.let { append(", focus ").append(it) }
            o.optJSONObject("focused_window")?.let { w -> append(". Focused window: ").append(w.optString("title").ifBlank { w.optString("package") }) }
            append('.')
        }
    }
}

class DesktopStatusTool : Tool {
    override val name = "desktop_status"
    override val description = "Report the desktop's state without changing anything: running or not, display id, size in pixels " +
        "(the coordinate space of desktop actions), how it is shown on the phone (full, mini or hidden), the display backend, " +
        "focus mode and the focused window."
    override val schema = Schema.obj()
    override val targets = DESKTOP
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val focused = (ctx as? com.eggman.backstage.agent.PeekableContext)?.peek(TargetKind.DESKTOP)?.windows()?.firstOrNull { it.focused }
        val json = DesktopStatusReport.json(ctx.services.desktop, focused)
        return ToolResult(DesktopStatusReport.text(json), data = json)
    }
}

class DesktopStartTool : Tool {
    override val name = "desktop_start"
    override val description = "Start the DeX desktop (if not running) and make it the working target. presentation: popup (small " +
        "floating window, default), background (nothing shown) or fullscreen (the desktop covers the phone screen: only if " +
        "the user explicitly wants the desktop itself full screen)."
    override val schema = Schema.obj("presentation" to Schema.enum("How the desktop is shown on the phone", "popup", "background", "fullscreen"))
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val host = ctx.services.desktop ?: return ToolResult.error("No Dextop desktop in this build.")
        val wanted = parsePresentation(args.optString("presentation"))
        if (args.has("presentation") && (wanted == null || wanted == DesktopHost.Presentation.IN_APP)) {
            throw ToolArgumentException("presentation must be popup, background or fullscreen")
        }
        if (wanted == DesktopHost.Presentation.FULL && !PresentationRequest.explicitFullDesktop(ctx.userWords)) {
            return ToolResult.error(PresentationRequest.REFUSED)
        }
        val exec = ctx.executor(TargetKind.DESKTOP) // starts the session when needed
        ctx.switchTarget(TargetKind.DESKTOP)
        if (wanted != null && host.presentation() != wanted) host.setPresentation(wanted)
        return ToolResult("Desktop ready: ${exec.description}.\n\n" + Observe.fullListing(exec, exec.screen(), ctx))
    }
}

/** How the WHOLE desktop is shown on the phone; never for one app's window (that is [WindowControlTool]). */
class DesktopPresentationTool : Tool {
    override val name = "desktop_presentation"
    override val description = "Change how the WHOLE DeX desktop is shown on the phone, without stopping its apps: popup (small " +
        "floating window over the phone), background (not shown, keeps running), fullscreen (the desktop covers the phone " +
        "screen) or in_app (inside the Backstage app). Only for explicit requests about the desktop itself, e.g. \"show the " +
        "desktop full screen\" or \"hide the desktop\". NOT for apps: \"make YouTube full screen\" is window_control " +
        "action maximize."
    override val schema = Schema.obj(
        "mode" to Schema.enum("How the desktop is shown", "popup", "background", "fullscreen", "in_app"),
        required = listOf("mode"),
    )
    override val targets = DESKTOP
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val host = ctx.services.desktop ?: return ToolResult.error("No Dextop desktop in this build.")
        if (!host.sessionActive()) return ToolResult.error("The desktop is not running; use desktop_start.")
        val raw = args.optString("mode") ?: args.optString("presentation") ?: throw ToolArgumentException("mode is required")
        val p = parsePresentation(raw) ?: throw ToolArgumentException("mode must be popup, background, fullscreen or in_app")
        if (p == DesktopHost.Presentation.FULL && !PresentationRequest.explicitFullDesktop(ctx.userWords)) {
            return ToolResult.error(PresentationRequest.REFUSED)
        }
        if (p != DesktopHost.Presentation.FULL && !host.directDisplay()) {
            return ToolResult.error("This desktop session uses the overlay display backend, which cannot be minimized or hidden. Restart it from Backstage to get the direct display.")
        }
        val words = com.eggman.backstage.exec.ScreenStateLine.presentationWords(p.name)
        return if (host.setPresentation(p)) ToolResult("The desktop is now shown as $words.")
        else ToolResult.error("Could not show the desktop as $words.")
    }
}

/** Every app window on both screens: display, window mode, bounds, focus (read-only). */
class WindowStateTool : Tool {
    override val name = "window_state"
    override val description = "List the app windows on both screens without changing anything: the DeX desktop (display id, " +
        "how it is shown) and the phone (display 0), each window with its mode (fullscreen, freeform = floating window, " +
        "minimized), bounds and focus. Use it to see where an app really is before fixing a window."
    override val schema = Schema.obj()
    override val targets = emptySet<TargetKind>()
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val host = ctx.services.desktop
        val extras = host as? DesktopHostExtras
        val geo = host?.let { h -> runCatching { if (h.sessionActive()) h.geometry() else null }.getOrNull() }
        val phoneExec = if (ctx.phoneAllowed) runCatching { ctx.executorOrNull(TargetKind.PHONE) }.getOrNull() else null
        val windows = com.eggman.backstage.exec.WindowController(
            extras, geo?.let { Triple(it.displayId, it.width, it.height) }, phoneExec?.safeArea(), ownPackages(ctx), ctx.phoneAllowed,
        )
        val desktop = windows.desktopTasks()
        val phone = windows.phoneTasks()
        if (extras == null || (desktop == null && phone == null)) {
            return ToolResult.error(com.eggman.backstage.exec.WindowController.UNAVAILABLE + " desktop_window_list shows the visible desktop windows.")
        }
        val json = org.json.JSONObject()
        val text = buildString {
            if (geo == null) append("DeX desktop: not running.\n") else {
                val shown = com.eggman.backstage.exec.ScreenStateLine.presentationWords(runCatching { host.presentation().name }.getOrDefault("NONE"))
                append("DeX desktop: display ${geo.displayId}, ${geo.width}x${geo.height}, shown on the phone as $shown.\n")
                append(list(desktop))
            }
            append("Phone screen (display 0)").append(if (ctx.phoneAllowed) ":\n" else ", not yours to act on in this task:\n")
            append(list(phone?.take(6)))
            phone?.filter { com.eggman.backstage.exec.PhoneWindows.misplaced(it, phoneExec?.safeArea()) }?.forEach {
                append("WARNING: ${it.name} on the phone is a floating window outside the safe area; window_control action fullscreen fixes it.\n")
            }
        }
        json.put("desktop_display", geo?.displayId ?: org.json.JSONObject.NULL)
        json.put("desktop", tasksJson(desktop)).put("phone", tasksJson(phone))
        return ToolResult(text.trimEnd(), data = json)
    }

    private fun list(tasks: List<com.eggman.backstage.host.DesktopTask>?): String = when {
        tasks == null -> "  (unknown)\n"
        tasks.isEmpty() -> "  no app windows\n"
        else -> tasks.joinToString("") { "  - ${com.eggman.backstage.exec.ScreenStateLine.task(it)} (${it.packageName})\n" }
    }

    private fun tasksJson(tasks: List<com.eggman.backstage.host.DesktopTask>?) = org.json.JSONArray().apply {
        tasks?.forEach { t ->
            put(org.json.JSONObject().put("app", t.name).put("package", t.packageName).put("display", t.displayId)
                .put("mode", com.eggman.backstage.host.WindowModes.label(t.windowingMode))
                .put("bounds", t.bounds?.toString() ?: org.json.JSONObject.NULL).put("focused", t.focused).put("minimized", !t.visible))
        }
    }
}

internal fun ownPackages(ctx: ToolContext): Set<String> =
    setOfNotNull(runCatching { com.eggman.backstage.a11y.AgentAccessibility.instance?.service?.packageName }.getOrNull(), "com.eggman.backstage")

/**
 * One app window on either screen: maximize/restore/minimize/close inside the desktop, fullscreen or pop-up on the
 * phone, or moving a window between the screens. Never changes how the desktop itself is shown.
 */
class WindowControlTool : Tool {
    override val name = "window_control"
    override val description = "Change ONE app's window (default: the focused one) without touching the desktop's own " +
        "presentation. action: maximize (the window fills the desktop: what \"make the app full screen\" means on DeX), " +
        "restore (back to a normal window), minimize, close, fullscreen (phone: a normal full-screen app again, e.g. when it " +
        "is stuck as a floating window under the status bar), popup (a small floating window; phone: only when the user asks, " +
        "kept inside the status and navigation bars), move_to_desktop (an app that landed on the phone goes to the desktop), " +
        "move_to_phone (only when the user asks). Examples: {\"action\":\"maximize\",\"app\":\"YouTube\"}, " +
        "{\"action\":\"fullscreen\",\"screen\":\"phone\",\"app\":\"Settings\"}. Phone changes need a task that may use the phone."
    override val schema = Schema.obj(
        "action" to Schema.enum("What to do with the window", *com.eggman.backstage.exec.WindowAction.values().map { it.wire }.toTypedArray()),
        "app" to Schema.str("App name or package (default: the focused window)"),
        "screen" to Schema.enum("Where the window is: desktop or phone (default: where the app is found, desktop first)", "desktop", "phone"),
        "bounds" to Schema.arr("Window bounds [left, top, right, bottom] for popup/restore (px of that screen)", Schema.int("px")),
        "position" to Schema.enum("Phone pop-up position", "center", "top_left", "top_right", "bottom_left", "bottom_right"),
        required = listOf("action"),
    )
    override val targets = emptySet<TargetKind>()
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val action = com.eggman.backstage.exec.WindowAction.parse(args.string("action"))
            ?: throw ToolArgumentException("action must be one of ${com.eggman.backstage.exec.WindowAction.values().joinToString { it.wire }}")
        val screenRaw = args.optString("screen")
        val screen = parseTarget(screenRaw) ?: if (screenRaw == null && ctx.currentTarget == TargetKind.PHONE) TargetKind.PHONE else null
        if (screenRaw != null && parseTarget(screenRaw) == null) throw ToolArgumentException("screen must be desktop or phone")
        val position = com.eggman.backstage.exec.PopupPosition.parse(args.optString("position"))
            ?: throw ToolArgumentException("position must be center, top_left, top_right, bottom_left or bottom_right")
        val bounds = parseBounds(args)?.let { com.eggman.backstage.host.TaskBounds(it.left, it.top, it.right, it.bottom) }
        val host = ctx.services.desktop
        val geo = host?.let { h -> runCatching { if (h.sessionActive()) h.geometry() else null }.getOrNull() }
        val phoneExec = if (ctx.phoneAllowed) runCatching { ctx.executorOrNull(TargetKind.PHONE) }.getOrNull() else null
        val windows = com.eggman.backstage.exec.WindowController(
            host as? DesktopHostExtras, geo?.let { Triple(it.displayId, it.width, it.height) }, phoneExec?.safeArea(), ownPackages(ctx), ctx.phoneAllowed,
            ctx.desktopAllowed,
        )
        val r = windows.control(action, screen, args.optString("app"), bounds, position, ctx.services.installedApps())
        // Show the screen the window is on (the desktop by default; the phone only when this task may use it).
        val showPhone = ctx.phoneAllowed && (screen == TargetKind.PHONE || action == com.eggman.backstage.exec.WindowAction.MOVE_TO_PHONE)
        val exec = if (showPhone) phoneExec else if (geo != null && ctx.desktopAllowed) runCatching { ctx.executor(TargetKind.DESKTOP) }.getOrNull() else null
        if (exec == null) return if (r.ok) ToolResult(r.message) else ToolResult.error(r.message)
        return Observe.after(ctx, exec, r, settleMs = 1_500)
    }
}

/** The executor for [kind] without switching the task's screen; null when unavailable. */
internal suspend fun ToolContext.executorOrNull(kind: TargetKind): com.eggman.backstage.exec.Executor? {
    (this as? com.eggman.backstage.agent.PeekableContext)?.peek(kind)?.let { return it }
    val before = currentTarget
    return runCatching { executor(kind) }.getOrNull().also { if (before != null && before != kind) switchTarget(before) }
}

class DesktopRightClickTool : Tool {
    override val name = "desktop_right_click"
    override val description = "Mouse right-click on the desktop (context menus) at an element index or x,y."
    override val schema = Schema.obj("index" to Schema.int("Element index"), "x" to Schema.num("x"), "y" to Schema.num("y"),
        "space" to Schema.enum("screen (default) or image", "screen", "image"))
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.executor(TargetKind.DESKTOP)
        val (x, y) = args.optInt("index")?.let { Observe.element(exec, it).let { e -> e.centerX.toFloat() to e.centerY.toFloat() } }
            ?: Observe.point(exec, args)
        return Observe.after(ctx, exec, exec.rightClick(x, y))
    }
}

class DesktopScrollWheelTool : Tool {
    override val name = "desktop_scroll_wheel"
    override val description = "Mouse-wheel scroll on the desktop at an element or point. amount > 0 scrolls down (content moves up), < 0 up; horizontal likewise."
    override val schema = Schema.obj("index" to Schema.int("Element index"), "x" to Schema.num("x"), "y" to Schema.num("y"),
        "amount" to Schema.num("Wheel notches, default 3"), "horizontal" to Schema.num("Horizontal notches, default 0"))
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.executor(TargetKind.DESKTOP)
        val (x, y) = args.optInt("index")?.let { Observe.element(exec, it).let { e -> e.centerX.toFloat() to e.centerY.toFloat() } }
            ?: if (args.has("x")) Observe.point(exec, args) else (exec.width / 2f to exec.height / 2f)
        val v = (args.optDouble("amount") ?: 3.0).toFloat().coerceIn(-30f, 30f)
        val h = (args.optDouble("horizontal") ?: 0.0).toFloat().coerceIn(-30f, 30f)
        return Observe.after(ctx, exec, exec.scrollWheel(x, y, v, h), settleMs = 1_500)
    }
}

class DesktopWindowListTool : Tool {
    override val name = "desktop_window_list"
    override val description = "List the app windows currently open on the desktop (title, package, bounds, focus), including minimized ones when the task list is available."
    override val schema = Schema.obj()
    override val targets = DESKTOP
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val exec = ctx.executor(TargetKind.DESKTOP)
        exec.screen()
        val windows = exec.windows()
        val visible = if (windows.isEmpty()) "No app windows are visible on the desktop." else windows.joinToString("\n") { w ->
            "- ${w.title.ifBlank { w.packageName }} (${w.packageName}) ${w.bounds}${if (w.focused) " focused" else ""}"
        }
        // The task list also has minimized windows; it is the desktop display's apps only (never the phone's).
        val tasks = exec.desktopTasks()
        if (tasks.isNullOrEmpty()) return ToolResult(visible)
        return ToolResult(visible + "\nApps open on the desktop (task list, top-most first): " +
            tasks.joinToString { t -> t.name + if (t.visible) "" else " (minimized)" } + ".")
    }
}

/**
 * Closes app windows on the desktop display only. See [com.eggman.backstage.exec.DesktopWindowCloser].
 */
class DesktopCloseWindowsTool : Tool {
    override val name = "desktop_close_windows"
    override val description = "Close app windows on the desktop, and only there: scope \"all\" closes every app window on the " +
        "desktop, \"app\" the windows of one app (give app: its name or package), \"focused\" the window in front. Apps on the " +
        "phone are never touched. This is THE way to close desktop apps: never use Close all / Clear all or swipe cards away in " +
        "the recent-apps view (that list is shared with the phone and closes the phone's apps too)."
    override val schema = Schema.obj(
        "scope" to Schema.enum("all = every desktop app window, app = one app's windows, focused = the window in front", "all", "app", "focused"),
        "app" to Schema.str("App name or package (scope app)"),
        required = listOf("scope"),
    )
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val scope = when (args.string("scope").trim().lowercase()) {
            "all" -> DesktopCloseScope.All
            "focused", "current", "front" -> DesktopCloseScope.Focused
            "app" -> DesktopCloseScope.App(
                args.optString("app")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolArgumentException("scope app needs app (its name or package)"),
            )
            else -> throw ToolArgumentException("scope must be all, app or focused")
        }
        val exec = ctx.executor(TargetKind.DESKTOP)
        val r = exec.closeDesktopWindows(scope, ctx.services.installedApps())
        return Observe.after(ctx, exec, r, settleMs = 1_500)
    }
}

/** The desktop's shortcut environment ([com.eggman.backstage.exec.DesktopShortcutEnv]) from the host, else the maker. */
internal fun desktopShortcutEnv(host: DesktopHost?): DesktopShortcutEnv =
    DesktopShortcutEnv.of(
        (host as? DesktopHostExtras)?.let { runCatching { it.desktopEnvironment() }.getOrNull() },
        runCatching { android.os.Build.MANUFACTURER }.getOrNull(),
    )

/** Named desktop keyboard shortcuts ([com.eggman.backstage.exec.DesktopShortcuts]), environment-aware. */
class DesktopShortcutTool : Tool {
    private val usable = DesktopShortcuts.all
        .filter { it.route != ShortcutRoute.REFUSED }

    override val name = "desktop_shortcut"
    override val description = "Use a desktop keyboard shortcut by name — faster than searching menus: home, back, close_window " +
        "(closes the window in front, desktop only), select_all, undo, redo, copy, cut, paste; on Android desktop windowing also " +
        "snap_left, snap_right, toggle_maximize, minimize. Shortcuts that would act on the phone (recent apps, app switcher, " +
        "notifications, lock) are not available. Returns the updated screen: check that it worked."
    override val schema = Schema.obj("name" to Schema.enum("Shortcut name", *usable.map { it.name }.toTypedArray()), required = listOf("name"))
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val requested = args.string("name")
        val env = desktopShortcutEnv(ctx.services.desktop)
        val available = DesktopShortcuts.available(env)
        val shortcut = DesktopShortcuts.named(requested)
            ?: throw ToolArgumentException("Unknown shortcut '$requested'. On ${env.label}: ${available.joinToString { it.name }}")
        if (shortcut.route == ShortcutRoute.REFUSED) return ToolResult.error("Not sent: ${shortcut.note}")
        if (!shortcut.availableIn(env)) {
            return ToolResult.error("${shortcut.name} has no documented shortcut on ${env.label}. To place a window use desktop_launch_app " +
                "with slot (left, right, maximized ...); available here: ${available.joinToString { it.name }}.")
        }
        val exec = ctx.executor(TargetKind.DESKTOP)
        val r = when (shortcut.route) {
            ShortcutRoute.DESKTOP_HOME -> exec.key("home")
            ShortcutRoute.DESKTOP_BACK -> exec.key("back")
            ShortcutRoute.CLOSE_WINDOW ->
                exec.closeDesktopWindows(DesktopCloseScope.Focused, ctx.services.installedApps())
            else -> {
                val keys = shortcut.keysIn(env).first()
                val chord = DesktopShortcuts.parse(keys)
                exec.keyChord(chord.keyCode, chord.metaState, "${shortcut.name} (${keys.joinToString("+")})")
            }
        }
        val note = shortcut.note.takeIf { it.isNotBlank() && shortcut.route == ShortcutRoute.KEYS }
        return Observe.after(ctx, exec, if (note != null && r.ok) r.copy(message = "${r.message}. $note") else r, settleMs = 1_500)
    }
}

/** Any key combination on the desktop, sent as a real keyboard would ([com.eggman.backstage.host.DesktopInputMath.chordStrokes]). */
class DesktopKeyComboTool : Tool {
    override val name = "desktop_key_combo"
    override val description = "Press a key combination on the desktop like a real keyboard (modifiers held, key pressed, modifiers " +
        "released), e.g. [\"ctrl\", \"l\"] or [\"ctrl\", \"shift\", \"t\"] in a browser. Modifiers: ctrl, shift, alt, meta (the " +
        "Windows/Command key); then one key (letter, digit, f1-f12, enter, tab, escape, backspace, arrows, [ ] = - / ...). Prefer " +
        "desktop_shortcut for named shortcuts. Combinations that act on the phone are refused."
    override val schema = Schema.obj(
        "keys" to Schema.arr("Modifiers and one key, e.g. [\"meta\", \"d\"]", Schema.str("Key name")),
        required = listOf("keys"),
    )
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val keys = args.stringList("keys").ifEmpty { args.optString("keys")?.split('+').orEmpty() }
        val chord = try {
            DesktopShortcuts.parse(keys)
        } catch (e: IllegalArgumentException) {
            throw ToolArgumentException(e.message ?: "invalid keys")
        }
        val exec = ctx.executor(TargetKind.DESKTOP)
        return Observe.after(ctx, exec, exec.keyChord(chord.keyCode, chord.metaState, keys.joinToString("+")), settleMs = 1_500)
    }
}

class DesktopStopTool : Tool {
    override val name = "desktop_stop"
    override val description = "Stop the Dextop desktop session (closes its display). Use only on the user's explicit request."
    override val schema = Schema.obj()
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val host = ctx.services.desktop ?: return ToolResult.error("No Dextop desktop in this build.")
        if (!host.sessionActive()) return ToolResult("The desktop is not running.")
        host.stopDesktop()
        return ToolResult("Desktop session stopped.")
    }
}

/** Stop and start the desktop: a clean screen with no windows or dialogs left from earlier work. */
class DesktopRestartTool : Tool {
    override val name = "desktop_restart"
    override val description = "Restart the DeX desktop (stop it, start it again in the same presentation) for a clean " +
        "screen: all its windows close. Use when the desktop is cluttered, stuck or misbehaving, when its screenshot is " +
        "black or blank while it reports running (call it at once; waking or relaunching apps does not fix that), or the user asks. " +
        "Starts it when it is not running. Unsaved work in desktop apps is lost."
    override val schema = Schema.obj()
    override val targets = DESKTOP
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        if (!ctx.desktopAllowed) return ToolResult.error(com.eggman.backstage.agent.TaskTools.DESKTOP_GATED)
        val host = ctx.services.desktop ?: return ToolResult.error("No Dextop desktop in this build.")
        if (host.sessionActive() && ctx.services.restartDesktop() == null) {
            return ToolResult.error("The desktop did not come back after the restart. Try desktop_start.")
        }
        val exec = ctx.executor(TargetKind.DESKTOP) // starts the desktop when inactive
        ctx.switchTarget(TargetKind.DESKTOP)
        return ToolResult("Desktop restarted: ${exec.description}.\n\n" + Observe.fullListing(exec, exec.screen(), ctx))
    }
}

/** Move the task between the DeX desktop and the phone's own screen, optionally to that screen's home. */
class SwitchScreenTool : Tool {
    override val name = "switch_screen"
    override val description = "Switch which screen you work on: desktop (DeX, started when needed) or phone (the phone's " +
        "own screen). home=true also goes to that screen's home screen. Following tools act on the chosen screen."
    override val schema = Schema.obj(
        "screen" to Schema.enum("The screen to work on", "desktop", "phone"),
        "home" to Schema.bool("Also go to that screen's home screen (default false)"),
        required = listOf("screen"),
    )
    override val targets = setOf(TargetKind.DESKTOP, TargetKind.PHONE)
    override val mutating = true
    override suspend fun run(ctx: ToolContext, args: Args): ToolResult {
        val kind = when (args.string("screen").trim().lowercase()) {
            "desktop", "dex", "dextop" -> TargetKind.DESKTOP
            "phone" -> TargetKind.PHONE
            else -> throw ToolArgumentException("screen must be desktop or phone")
        }
        val exec = try { ctx.executor(kind) } catch (e: IllegalStateException) { return ToolResult.error(e.message ?: "That screen is not available.") }
        ctx.switchTarget(kind)
        if (args.optBool("home") == true) exec.key("home")
        val words = if (kind == TargetKind.DESKTOP) "the desktop" else "the phone screen"
        return ToolResult("Now working on $words: ${exec.description}.\n\n" + Observe.fullListing(exec, exec.screen(), ctx))
    }
}
