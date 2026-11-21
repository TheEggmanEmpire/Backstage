package com.eggman.backstage.host

/*
 * Pure rules that keep the phone untouched while the agent works on the Dextop desktop ("the agent controls the DeX
 * desktop while I keep using my phone normally"). The Dextop glue (backstage/dextop-glue) applies them; they live here
 * so that they are JVM-unit-tested (DesktopPhoneSafetyTest). No Android runtime calls.
 */

/** Shell identity is usable: Dextop's embedded runtime, or a running Shizuku that granted this app. */
object PrivilegeStatus {
    /** A live Shizuku binder alone is not enough: Shizuku hands it to every app, granted or not. */
    fun usable(embeddedRuntime: Boolean, shizukuBinderAlive: Boolean, shizukuGranted: Boolean): Boolean =
        embeddedRuntime || (shizukuBinderAlive && shizukuGranted)
}

/**
 * Focus around desktop input. [mode] is MirrorService.Backstage.desktopFocusMode: [QUIET] (own focus, never takes the
 * phone's top focus), [OWN] (own focus, but a touch or a launch makes the desktop the top-focused display) or [SHARED]
 * (no own focus: Android 13, or display profile "none"; key events reach the desktop only while it is top-focused).
 */
object DesktopFocusPolicy {
    const val QUIET = "quiet"
    const val OWN = "own"
    const val SHARED = "shared"

    /**
     * After a touch, a launch or HOME on the desktop: give the phone app its focus back when the desktop runs in the
     * background (MINI/HIDDEN) and the display may have taken it.
   */
    fun returnFocusToPhone(mode: String?, presentation: DesktopHost.Presentation): Boolean =
        background(presentation) && mode != QUIET

    /** Before key events: a [SHARED] desktop must be the top-focused display, or they are dropped (no focused window). */
    fun focusDesktopForKeys(mode: String?): Boolean = mode == SHARED

    /** Touches and launches on this desktop never move the phone's focus or close its keyboard. */
    fun focusSafe(mode: String?): Boolean = mode == QUIET

    fun background(presentation: DesktopHost.Presentation): Boolean =
        presentation == DesktopHost.Presentation.MINI || presentation == DesktopHost.Presentation.HIDDEN ||
            presentation == DesktopHost.Presentation.IN_APP
}

/** Launches on the desktop never take an app away from the user's phone. */
object DesktopLaunchPolicy {
    /** One task on the phone's display (package of its base activity, visible). */
    data class PhoneTask(val packageName: String, val visible: Boolean)

    /**
     * Why launching [packageName] on the desktop is refused, or null. Android reuses an app's existing task and moves it
     * to the display it is launched on, so launching an app the user has open on the phone (visible: in front, split
     * screen or picture-in-picture) would pull it off the phone in the middle of use. Only while the desktop runs in the
     * background (MINI/HIDDEN); in FULL the desktop covers the phone. Tasks the user is not looking at may move.
   */
    fun phoneConflict(
        packageName: String?,
        phoneTasks: List<PhoneTask>,
        presentation: DesktopHost.Presentation,
        label: String? = null,
    ): String? {
        if (packageName.isNullOrBlank() || !DesktopFocusPolicy.background(presentation)) return null
        if (phoneTasks.none { it.visible && it.packageName == packageName }) return null
        val name = label?.takeIf { it.isNotBlank() } ?: packageName
        return "$name is open on the phone right now, and launching it on the desktop would take it away from the user. " +
            "Ask the user to close it on the phone (or to let you work on the phone) instead of launching it here."
    }
}

/** Typing into desktop apps uses key events only: the phone's clipboard is never read, written or cleared. */
object DesktopTyping {
    /** Key events of the virtual keyboard map can produce [text] (printable ASCII, newline, tab). */
    fun keyTypeable(text: String): Boolean = !DesktopInputMath.requiresPaste(text)

    /** Why [text] cannot be typed as key presses, or null when it can. */
    fun refusal(text: String): String? {
        if (keyTypeable(text)) return null
        val samples = text.filterNot { c -> c in ' '..'~' || c == '\n' || c == '\t' }
            .codePoints().distinct().limit(5).toArray().joinToString("") { String(Character.toChars(it)) }
        return "This field does not accept text directly, and \"$samples\" cannot be typed as key presses. Backstage " +
            "never uses the phone's clipboard for typing (it belongs to the user). Use a field that accepts text, type " +
            "an ASCII-only alternative, or ask the user."
    }
}

/** Global actions on the desktop: each one is aimed at the desktop display and never reaches the phone. */
object DesktopGlobalActions {
    enum class Route {
        /** BACK as a key event carrying the desktop's display id. */
        BACK_KEY_ON_DESKTOP,
        /** HOME as the desktop launcher activity on the desktop display (KEYCODE_HOME is handled for the whole phone). */
        HOME_ACTIVITY_ON_DESKTOP,
        /** Dextop's own display-targeted recents. */
        DESKTOP_RECENTS,
        UNSUPPORTED,
    }

    fun route(action: String): Route = when (action.trim().lowercase(java.util.Locale.ROOT)) {
        "back" -> Route.BACK_KEY_ON_DESKTOP
        "home" -> Route.HOME_ACTIVITY_ON_DESKTOP
        "recents" -> Route.DESKTOP_RECENTS
        else -> Route.UNSUPPORTED
    }

    /**
     * A plain HOME key requested through `key` (e.g. the executor's fallback after a failed "home" action) is routed
     * like the "home" action, never injected: its display id does not stop PhoneWindowManager's global handling.
   */
    fun keyIsDesktopHome(keyCode: Int): Boolean = keyCode == android.view.KeyEvent.KEYCODE_HOME
}

/**
 * A cached connection (a binder proxy) that is rebuilt once when a call reports it broken, then retried. Dextop's
 * embedded runtime hands out proxies bound to one runtime binder; after the runtime reconnects (Wi-Fi change, adbd
 * restart) the old proxy fails forever, while a fresh one works.
 */
class Reconnecting<T : Any>(private val connect: () -> T) {
    @Volatile private var current: T? = null

    fun get(): T = current ?: synchronized(this) { current ?: connect().also { current = it } }

    /** Forgets [stale] (only if it is still the cached one), so the next [get] connects again. */
    fun invalidate(stale: T) {
        synchronized(this) { if (current === stale) current = null }
    }

    /** Runs [block]; when [broken] says the connection failed (not a plain refusal), reconnects and runs it once more. */
    fun <R> call(broken: (R) -> Boolean, block: (T) -> R): R {
        val first = get()
        val result = block(first)
        if (!broken(result)) return result
        invalidate(first)
        return block(get())
    }
}
