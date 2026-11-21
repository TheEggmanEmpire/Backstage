package com.eggman.backstage.exec

import android.view.KeyEvent
import java.util.Locale

/** The desktop that runs on Dextop's display; its system keyboard shortcuts differ. */
enum class DesktopShortcutEnv(val label: String) {
    /** Dextop's "samsung_dex" environment: Samsung's own DeX desktop (One UI) runs on the display. */
    SAMSUNG_DEX("Samsung DeX"),
    /** Android's own desktop windowing (AOSP, Pixel and most other vendors: "android_freeform", "pixel_aosp_compat"). */
    ANDROID_DESKTOP("Android desktop windowing"),
    /** One of Dextop's own window managers (Plasma) owns the display: no system shortcut list applies. */
    DEXTOP_SHELL("Dextop's window manager");

    companion object {
        const val DEXTOP_SHELL_ID = "dextop_plasma"

        /** From the host's environment id ([com.eggman.backstage.host.DesktopHostExtras.desktopEnvironment]), else the maker. */
        fun of(environmentId: String?, manufacturer: String?): DesktopShortcutEnv {
            val id = environmentId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            return when {
                id == DEXTOP_SHELL_ID -> DEXTOP_SHELL
                id != null && id.startsWith("samsung") -> SAMSUNG_DEX
                id != null -> ANDROID_DESKTOP
                manufacturer.equals("samsung", ignoreCase = true) -> SAMSUNG_DEX
                else -> ANDROID_DESKTOP
            }
        }
    }
}

/** How Backstage carries out a named shortcut on the desktop. */
enum class ShortcutRoute {
    /** The key combination is sent to the desktop display as real key events (modifiers down, key, modifiers up). */
    KEYS,
    /** The desktop's launcher on the desktop display: a HOME key is handled for the whole phone and is never sent. */
    DESKTOP_HOME,
    /** BACK as a key event with the desktop's display id (the system's Meta+Backspace back is injected on the phone's focus). */
    DESKTOP_BACK,
    /** The focused desktop window is closed through the task manager on the desktop display only (never Alt+F4 in the background). */
    CLOSE_WINDOW,
    /** Not sent: it acts on the phone (or on the app list the phone shares) instead of the desktop. */
    REFUSED,
}

/** One documented key combination of a shortcut in one desktop environment, with where it is documented. */
data class ShortcutBinding(val env: DesktopShortcutEnv, val keys: List<String>, val source: String)

/** A named desktop shortcut: what it does, how Backstage carries it out, and its documented keys per environment. */
data class DesktopShortcut(
    val name: String,
    val action: String,
    val route: ShortcutRoute,
    val bindings: List<ShortcutBinding>,
    /** Why it is refused, or what to know when using it. */
    val note: String = "",
) {
    fun keysIn(env: DesktopShortcutEnv): List<List<String>> = bindings.filter { it.env == env }.map { it.keys }

    /**
     * Usable in [env]: key shortcuts only where they are documented for that desktop; routed actions (home, back,
     * close window) everywhere, since Backstage carries them out itself. Refused ones never.
   */
    fun availableIn(env: DesktopShortcutEnv): Boolean = when (route) {
        ShortcutRoute.REFUSED -> false
        ShortcutRoute.KEYS -> bindings.any { it.env == env }
        else -> true
    }
}

/**
 * Keyboard shortcuts of the desktop, so the agent operates it quickly instead of searching through menus (user
 * request: "the agent should know the keyboard shortcuts to quick operate on dex desktop"). ONLY documented
 * entries, each with its source:
 *
 * - Samsung DeX: Samsung's support article "Use a keyboard and mouse with Samsung DeX", section "Keyboard shortcuts in
 *   DeX" ([SAMSUNG_DEX_HELP]).
 * - Android desktop windowing: the system shortcut table of AOSP's InputGestureManager ([AOSP_SYSTEM_SHORTCUTS];
 *   snap/maximize/minimize exist where the platform enables task-resizing shortcuts) and Google's help article on
 *   desktop windowing ([ANDROID_DESKTOP_HELP], Alt+Tab).
 * - Text editing in any app: AOSP TextView.onKeyShortcut ([AOSP_TEXT_SHORTCUTS]).
 *
 * Shortcuts that would act on the phone the user is holding (lock, notification shade, recents, app switcher,
 * assistant, screenshot, system settings) are listed as [ShortcutRoute.REFUSED] so a raw key combination for them is
 * refused too ([match]). Snapping/maximizing on Samsung DeX is not in Samsung's list: desktop_launch_app's window slots
 * do it instead.
 */
object DesktopShortcuts {
    /** https://www.samsung.com/us/support/answer/ANS10003477/ ("Keyboard shortcuts in DeX"). */
    const val SAMSUNG_DEX_HELP = "https://www.samsung.com/us/support/answer/ANS10003477/"
    /** AOSP frameworks/base InputGestureManager.initSystemShortcuts (main). */
    const val AOSP_SYSTEM_SHORTCUTS =
        "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputGestureManager.java"
    /** Google: "Multitask with desktop windowing & connected displays on Android". */
    const val ANDROID_DESKTOP_HELP = "https://support.google.com/android/answer/16928875"
    /** AOSP frameworks/base TextView.onKeyShortcut (main). */
    const val AOSP_TEXT_SHORTCUTS =
        "https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/TextView.java"

    private val DEX = DesktopShortcutEnv.SAMSUNG_DEX
    private val AOSP = DesktopShortcutEnv.ANDROID_DESKTOP
    private val SHELL = DesktopShortcutEnv.DEXTOP_SHELL

    private fun dex(vararg keys: String) = ShortcutBinding(DEX, keys.toList(), SAMSUNG_DEX_HELP)
    private fun aosp(vararg keys: String) = ShortcutBinding(AOSP, keys.toList(), AOSP_SYSTEM_SHORTCUTS)
    private fun text(vararg keys: String) =
        DesktopShortcutEnv.entries.map { ShortcutBinding(it, keys.toList(), AOSP_TEXT_SHORTCUTS) }

    private const val PHONE_SHARED = "It acts on the phone the user is holding (or on the app list the phone shares with the desktop), so it is not sent."

    val all: List<DesktopShortcut> = listOf(
        // ---- navigation (carried out on the desktop display by Backstage) ----
        DesktopShortcut(
            "home", "Show the desktop's home screen", ShortcutRoute.DESKTOP_HOME,
            // Samsung: "WIN + Enter: Home". AOSP: META+H and META+ENTER -> KEY_GESTURE_TYPE_HOME.
            listOf(dex("meta", "enter"), aosp("meta", "h"), aosp("meta", "enter")),
            "Backstage starts the desktop's launcher on the desktop display; a HOME key would act on the whole phone.",
        ),
        DesktopShortcut(
            "back", "Go back in the focused desktop window", ShortcutRoute.DESKTOP_BACK,
            // Samsung: "WIN + Backspace: Back". AOSP: META+DEL, META+ESCAPE, META+DPAD_LEFT -> KEY_GESTURE_TYPE_BACK.
            listOf(dex("meta", "backspace"), aosp("meta", "backspace"), aosp("meta", "escape"), aosp("meta", "left")),
            "Sent as a BACK key on the desktop display (the system's own back shortcut goes to the phone's focused app).",
        ),
        DesktopShortcut(
            "close_window", "Close the focused desktop window (app)", ShortcutRoute.CLOSE_WINDOW,
            // Samsung: "ALT + F4: Close current app".
            listOf(dex("alt", "f4")),
            "Backstage closes the focused desktop window through the task manager, on the desktop display only.",
        ),
        // ---- window management (Android desktop windowing; flag-gated: enableTaskResizingKeyboardShortcuts) ----
        DesktopShortcut("snap_left", "Snap the focused window to the left half", ShortcutRoute.KEYS, listOf(aosp("meta", "left_bracket"))),
        DesktopShortcut("snap_right", "Snap the focused window to the right half", ShortcutRoute.KEYS, listOf(aosp("meta", "right_bracket"))),
        DesktopShortcut("toggle_maximize", "Maximize or restore the focused window", ShortcutRoute.KEYS, listOf(aosp("meta", "equals"))),
        DesktopShortcut("minimize", "Minimize the focused window", ShortcutRoute.KEYS, listOf(aosp("meta", "minus"))),
        // ---- text editing in the focused field (any desktop) ----
        DesktopShortcut("select_all", "Select all text in the focused field", ShortcutRoute.KEYS, text("ctrl", "a")),
        DesktopShortcut("undo", "Undo the last edit", ShortcutRoute.KEYS, text("ctrl", "z")),
        DesktopShortcut("redo", "Redo", ShortcutRoute.KEYS, text("ctrl", "y") + text("ctrl", "shift", "z")),
        DesktopShortcut("copy", "Copy the selection", ShortcutRoute.KEYS, text("ctrl", "c"),
            "Uses the phone's clipboard (shared with the desktop): only when the user asked to copy."),
        DesktopShortcut("cut", "Cut the selection", ShortcutRoute.KEYS, text("ctrl", "x"),
            "Uses the phone's clipboard (shared with the desktop): only when the user asked for it."),
        DesktopShortcut("paste", "Paste", ShortcutRoute.KEYS, text("ctrl", "v"),
            "Pastes the phone's clipboard: only when the user asked to paste."),
        // ---- refused: they act on the phone ----
        DesktopShortcut(
            "recent_apps", "Recent apps", ShortcutRoute.REFUSED,
            // Samsung: "WIN + TAB: Recent apps". AOSP: META+TAB -> KEY_GESTURE_TYPE_RECENT_APPS.
            listOf(dex("meta", "tab"), aosp("meta", "tab")),
            "The recent-apps list is shared with the phone: closing apps there closes the phone's apps too. Use desktop_window_list and desktop_close_windows.",
        ),
        DesktopShortcut(
            "switch_apps", "Switch between running apps", ShortcutRoute.REFUSED,
            // Samsung: "ALT + TAB: Switch between running apps". Google desktop windowing help: Alt + Tab.
            listOf(dex("alt", "tab"), ShortcutBinding(AOSP, listOf("alt", "tab"), ANDROID_DESKTOP_HELP)),
            "The app switcher may open on the phone. Bring a window to the front with desktop_launch_app (its name) instead.",
        ),
        DesktopShortcut(
            "notifications", "Open notifications", ShortcutRoute.REFUSED,
            // Samsung: "WIN + N: Open notifications". AOSP: META+N -> KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL.
            listOf(dex("meta", "n"), aosp("meta", "n")),
            "It may open the phone's notification shade. Use get_notifications.",
        ),
        DesktopShortcut(
            "lock", "Lock the screen", ShortcutRoute.REFUSED,
            // Samsung: "WIN + L: Lock DeX". AOSP: META+L -> KEY_GESTURE_TYPE_LOCK_SCREEN.
            listOf(dex("meta", "l"), aosp("meta", "l")), "It locks the user's phone.",
        ),
        DesktopShortcut(
            "camera_access", "Enable or disable camera access", ShortcutRoute.REFUSED,
            // Samsung: "WIN + SHIFT + V: Enable or disable camera access".
            listOf(dex("meta", "shift", "v")), "It changes the phone's camera access.",
        ),
        DesktopShortcut(
            "assistant", "Open the assistant", ShortcutRoute.REFUSED,
            listOf(aosp("meta", "a")), PHONE_SHARED, // AOSP: META+A -> KEY_GESTURE_TYPE_LAUNCH_ASSISTANT.
        ),
        DesktopShortcut(
            "system_settings", "Open system settings", ShortcutRoute.REFUSED,
            listOf(aosp("meta", "i")), // AOSP: META+I -> KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS.
            "It may open Settings on the phone. Open Settings with desktop_launch_app instead.",
        ),
        DesktopShortcut(
            "system_screenshot", "Take a screenshot", ShortcutRoute.REFUSED,
            listOf(aosp("meta", "ctrl", "s")), // AOSP: META+CTRL+S -> KEY_GESTURE_TYPE_TAKE_SCREENSHOT.
            "It saves a screenshot to the phone's gallery. Use the screenshot tool to look at the desktop.",
        ),
    )

    private val byName: Map<String, DesktopShortcut> = all.associateBy { it.name }

    fun named(name: String): DesktopShortcut? = byName[normalizeName(name)]

    /** Names the agent can use in [env]. */
    fun available(env: DesktopShortcutEnv): List<DesktopShortcut> = all.filter { it.availableIn(env) }

    fun normalizeName(name: String): String = name.trim().lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_')

    /** One line per usable shortcut for the system prompt: `name (keys)`. */
    fun promptLine(env: DesktopShortcutEnv): String = available(env).joinToString(", ") { s ->
        val keys = s.keysIn(env).firstOrNull()
        if (keys == null) s.name else "${s.name} (${keys.joinToString("+") { label(it) }})"
    }

    private fun label(key: String) = when (key) {
        "meta" -> "Meta"
        "ctrl" -> "Ctrl"
        "alt" -> "Alt"
        "shift" -> "Shift"
        "left_bracket" -> "["
        "right_bracket" -> "]"
        "equals" -> "="
        "minus" -> "-"
        else -> key.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    // ---- key parsing ------------------------------------------------------------------------------------------

    /** A parsed key combination: the main key and the modifier meta state (KeyEvent META_*_ON bits). */
    data class Chord(val keyCode: Int, val metaState: Int) {
        /** Modifier bits only, left/right merged, for comparing chords. */
        val modifiers: Int get() = metaState and MODIFIER_ON
    }

    private const val MODIFIER_ON = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON

    private val modifierNames: Map<String, Int> = mapOf(
        "ctrl" to KeyEvent.META_CTRL_ON, "control" to KeyEvent.META_CTRL_ON,
        "shift" to KeyEvent.META_SHIFT_ON,
        "alt" to KeyEvent.META_ALT_ON, "option" to KeyEvent.META_ALT_ON,
        "meta" to KeyEvent.META_META_ON, "win" to KeyEvent.META_META_ON, "windows" to KeyEvent.META_META_ON,
        "cmd" to KeyEvent.META_META_ON, "command" to KeyEvent.META_META_ON, "super" to KeyEvent.META_META_ON,
    )

    /** Key names beyond [KeyNames.codes]: symbols and aliases a key combination may use. */
    private val extraKeys: Map<String, Int> = mapOf(
        "[" to KeyEvent.KEYCODE_LEFT_BRACKET, "left_bracket" to KeyEvent.KEYCODE_LEFT_BRACKET,
        "]" to KeyEvent.KEYCODE_RIGHT_BRACKET, "right_bracket" to KeyEvent.KEYCODE_RIGHT_BRACKET,
        "=" to KeyEvent.KEYCODE_EQUALS, "equals" to KeyEvent.KEYCODE_EQUALS,
        "-" to KeyEvent.KEYCODE_MINUS, "minus" to KeyEvent.KEYCODE_MINUS,
        "/" to KeyEvent.KEYCODE_SLASH, "slash" to KeyEvent.KEYCODE_SLASH,
        "\\" to KeyEvent.KEYCODE_BACKSLASH, "backslash" to KeyEvent.KEYCODE_BACKSLASH,
        ";" to KeyEvent.KEYCODE_SEMICOLON, "semicolon" to KeyEvent.KEYCODE_SEMICOLON,
        "'" to KeyEvent.KEYCODE_APOSTROPHE, "apostrophe" to KeyEvent.KEYCODE_APOSTROPHE,
        "," to KeyEvent.KEYCODE_COMMA, "comma" to KeyEvent.KEYCODE_COMMA,
        "." to KeyEvent.KEYCODE_PERIOD, "period" to KeyEvent.KEYCODE_PERIOD,
        "`" to KeyEvent.KEYCODE_GRAVE, "grave" to KeyEvent.KEYCODE_GRAVE,
        "esc" to KeyEvent.KEYCODE_ESCAPE, "return" to KeyEvent.KEYCODE_ENTER, "insert" to KeyEvent.KEYCODE_INSERT,
        "arrow_up" to KeyEvent.KEYCODE_DPAD_UP, "arrow_down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "arrow_left" to KeyEvent.KEYCODE_DPAD_LEFT, "arrow_right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "end" to KeyEvent.KEYCODE_MOVE_END, "pos1" to KeyEvent.KEYCODE_MOVE_HOME,
    )

    /** The key code of a single modifier pressed on its own (e.g. ["meta"]). */
    private val loneModifier: Map<Int, Int> = mapOf(
        KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.META_SHIFT_ON to KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.META_ALT_ON to KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_META_ON to KeyEvent.KEYCODE_META_LEFT,
    )

    /** Key code of one non-modifier key name ("d", "f4", "enter", "["...), or null. */
    fun keyCode(name: String): Int? {
        val raw = name.trim().lowercase(Locale.ROOT)
        extraKeys[raw]?.let { return it }
        val n = KeyNames.normalize(raw)
        extraKeys[n]?.let { return it }
        KeyNames.codes[n]?.let { return it }
        return when {
            n.length == 1 && n[0] in 'a'..'z' -> KeyEvent.KEYCODE_A + (n[0] - 'a')
            n.length == 1 && n[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (n[0] - '0')
            n.matches(Regex("f([1-9]|1[0-2])")) -> KeyEvent.KEYCODE_F1 + n.drop(1).toInt() - 1
            else -> null
        }
    }

    /**
     * Parses a key combination such as ["meta", "d"] or ["ctrl", "shift", "t"]: any modifiers and exactly one other
     * key (or a single modifier on its own). Throws [IllegalArgumentException] with a readable reason.
   */
    fun parse(keys: List<String>): Chord {
        val names = keys.flatMap { it.split('+') }.map { it.trim() }.filter { it.isNotEmpty() }
        require(names.isNotEmpty()) { "keys is empty: give modifiers and one key, e.g. [\"ctrl\", \"a\"]" }
        require(names.size <= 5) { "at most four modifiers and one key" }
        var meta = 0
        val others = mutableListOf<Int>()
        for (n in names) {
            val modifier = modifierNames[n.lowercase(Locale.ROOT)]
            if (modifier != null) {
                meta = meta or modifier
                continue
            }
            others += keyCode(n) ?: throw IllegalArgumentException("unknown key '$n'")
        }
        require(others.size <= 1) { "only one key besides the modifiers (ctrl, shift, alt, meta) can be pressed" }
        val key = others.singleOrNull()
        if (key != null) return Chord(key, meta)
        // Only modifiers: one of them is the pressed key, the others are held.
        val lone = loneModifier.keys.last { (meta and it) != 0 }
        return Chord(loneModifier.getValue(lone), meta and lone.inv())
    }

    /** The catalog entry whose documented keys (in any environment) are exactly [chord], or null. */
    fun match(chord: Chord): DesktopShortcut? = all.firstOrNull { s ->
        s.bindings.any { b -> runCatching { parse(b.keys) }.getOrNull()?.let { it.keyCode == chord.keyCode && it.modifiers == chord.modifiers } == true }
    }
}
