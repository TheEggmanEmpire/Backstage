package com.eggman.backstage.exec

import java.text.Normalizer
import java.util.Locale

/**
 * Root cause of "close all foreground apps closed the phone's apps too": the agent opened the recent-apps view on the
 * desktop (DesktopExecutor.key("recents") -> Dextop's display-targeted APP_SWITCH key) and tapped its "Close all".
 * That view lists the phone's tasks as well (One UI shares one recents list between the phone and DeX, like every
 * Android launcher's Overview), so "Close all" removed every task on both screens.
 *
 * While the agent works on the desktop, a tap/click on a "Close all" / "Clear all" control of the system (launcher,
 * System UI, a recents or task-switcher window) is refused with the way to do it right ([CLOSE_ALL_REFUSAL]), and so is
 * swiping cards away in a window that is recognisably the recent-apps view. Matching is by package/window and by
 * label or view id, multilingual (en, de and a few more). Apps' own "Close all tabs" buttons are not affected.
 */
object SharedRecentsGuard {
    /** Packages that host the recent-apps view, the task switcher or the notification shade (system controls). */
    val SYSTEM_HOSTS: Set<String> = setOf(
        "com.android.systemui",
        "com.sec.android.app.launcher", // Samsung One UI Home (recents, DeX taskbar)
        "com.samsung.desktopsystemui", // Samsung DeX system UI
        "com.sec.android.app.desktoplauncher", // Samsung DeX launcher (older One UI)
        "com.google.android.apps.nexuslauncher", // Pixel Launcher (Quickstep Overview)
        "com.android.launcher3", // AOSP Launcher3 / Quickstep
        "com.miui.home",
        "com.huawei.android.launcher",
        "net.oneplus.launcher",
        "com.oppo.launcher",
        "com.motorola.launcher3",
    )

    /** "Close all"-style labels, already normalised ([normalize]): en, de, fr, es, it, nl. */
    private val CLOSE_ALL_PHRASES = listOf(
        "close all", "clear all", "dismiss all", "remove all", "end all", "kill all", "close all apps", "clear all apps",
        "alle schliessen", "alles schliessen", "alle apps schliessen", "alle loschen", "alles loschen", "alle beenden",
        "alle apps beenden", "alle entfernen", "alle verwerfen", "alle apps loschen",
        "tout fermer", "fermer tout", "tout effacer", "effacer tout",
        "cerrar todo", "cerrar todas", "borrar todo",
        "chiudi tutto", "chiudi tutte", "cancella tutto",
        "alles sluiten", "alles wissen", "alle sluiten",
    )

    /** View-id fragments of such controls (language independent). */
    private val CLOSE_ALL_IDS = listOf("clear_all", "close_all", "dismiss_all", "clearall", "closeall", "kill_all", "remove_all")

    /** Window titles of the recent-apps view / task switcher (normalised, substring match). */
    private val RECENTS_TITLES = listOf(
        "recent", "overview", "task switcher", "app switcher", "ubersicht", "zuletzt", "letzte apps", "aktuelle apps",
        "recents", "app recenti", "recientes", "recentes",
    )

    const val CLOSE_ALL_REFUSAL = "Refused: this \"Close all\" belongs to the system's recent-apps view (or notification " +
        "shade), which the phone shares with the desktop: it would also close the user's phone apps. To close desktop " +
        "apps use desktop_close_windows (scope all, or one app): it closes windows on the desktop display only."

    const val CARD_SWIPE_REFUSAL = "Refused: swiping cards away in the recent-apps view closes apps on the phone too " +
        "(the list is shared). Use desktop_close_windows to close desktop windows."

    /** Appended when the agent opened the recent-apps view on the desktop. */
    const val RECENTS_NOTE = "Note: this recent-apps view also lists the phone's apps. Use it only to switch to a desktop " +
        "app; never tap Close all and never swipe cards away here (that closes the phone's apps). To close desktop " +
        "windows use desktop_close_windows."

    /** Lower case, accents and ß folded (ö->o, ß->ss), punctuation and digits dropped, single spaces. */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT).replace("ß", "ss")
        val folded = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return folded.replace(Regex("[^\\p{L}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** True when any of [texts] reads as "close all" / "clear all" (the whole label, or the label starting with it). */
    fun closeAllLabel(vararg texts: String): Boolean = texts.any { raw ->
        val t = normalize(raw)
        t.isNotEmpty() && CLOSE_ALL_PHRASES.any { p -> t == p || t.startsWith("$p ") }
    }

    fun closeAllId(resourceId: String): Boolean {
        val id = resourceId.substringAfter(":id/").lowercase(Locale.ROOT)
        return id.isNotEmpty() && CLOSE_ALL_IDS.any { id.contains(it) }
    }

    fun closeAllControl(element: UiElement): Boolean =
        closeAllLabel(element.label, element.text, element.description) || closeAllId(element.resourceId)

    fun recentsTitle(title: String): Boolean {
        val t = normalize(title)
        return t.isNotEmpty() && RECENTS_TITLES.any { t.contains(it) }
    }

    /** A package that hosts system controls: the known launchers/System UI or a home app of this device ([homePackages]). */
    fun systemHost(packageName: String, homePackages: Set<String> = emptySet()): Boolean =
        packageName.isNotBlank() && (packageName in SYSTEM_HOSTS || packageName in homePackages)

    /**
     * Why tapping [element] on the desktop is refused, or null. [windowTitle]/[windowPackage] describe the window the
     * element is in; [homePackages] are the device's home apps (launchers host the recent-apps view).
   */
    fun tapRefusal(element: UiElement, windowTitle: String, windowPackage: String, homePackages: Set<String> = emptySet()): String? {
        if (!closeAllControl(element)) return null
        val system = systemHost(element.packageName, homePackages) || systemHost(windowPackage, homePackages) || recentsTitle(windowTitle)
        return if (system) CLOSE_ALL_REFUSAL else null
    }

    /** Why a swipe starting in a window titled [windowTitle] of [windowPackage] is refused (recent-apps cards), or null. */
    fun swipeRefusal(windowTitle: String, windowPackage: String, homePackages: Set<String> = emptySet()): String? =
        if (recentsTitle(windowTitle) && systemHost(windowPackage, homePackages)) CARD_SWIPE_REFUSAL else null
}
