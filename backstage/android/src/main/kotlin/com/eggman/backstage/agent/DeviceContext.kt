package com.eggman.backstage.agent

import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the agent knows about the device: a compact "This phone" / "The DeX desktop" block in the system prompt
 * (read once per task) and a "[device]" line in screen listings that names only what changed since the last step.
 * Written for Backstage. No personal data: no network names, accounts or location.
 */
enum class FoldState(val text: String) {
    FOLDED("folded: the cover screen"),
    UNFOLDED("unfolded: the main screen"),
    HALF_OPEN("half open (flex mode)"),
}

/** Book-style foldables (Galaxy Z Fold) versus clamshells (Galaxy Z Flip): their screens have opposite shapes. */
enum class FoldKind { BOOK, FLIP }

object FoldStates {
    /**
     * The fold state from display 0's current size and, when the phone has a hinge sensor, its angle (degrees,
     * 180 = flat open). Book style: the near-square main screen is unfolded, the tall narrow cover screen folded.
     * Clamshell: the tall screen is unfolded, the small near-square cover screen folded. Pure.
   */
    fun from(kind: FoldKind?, widthPx: Int, heightPx: Int, hingeAngle: Float? = null): FoldState? {
        if (kind == null || widthPx <= 0 || heightPx <= 0) return null
        if (hingeAngle != null && hingeAngle in 25f..155f) return FoldState.HALF_OPEN
        val aspect = max(widthPx, heightPx).toDouble() / min(widthPx, heightPx)
        val squareish = aspect < 1.5
        return when (kind) {
            FoldKind.BOOK -> if (squareish) FoldState.UNFOLDED else FoldState.FOLDED
            FoldKind.FLIP -> if (squareish) FoldState.FOLDED else FoldState.UNFOLDED
        }
    }

    /** Samsung's foldables by model number (SM-F9xx Fold, SM-F7xx Flip), else what the hinge sensor says. */
    fun kindOf(model: String, hasHinge: Boolean): FoldKind? {
        val m = model.uppercase()
        return when {
            m.startsWith("SM-F9") || m.startsWith("SM-W9") -> FoldKind.BOOK
            m.startsWith("SM-F7") || m.startsWith("SM-W7") -> FoldKind.FLIP
            hasHinge -> FoldKind.BOOK
            else -> null
        }
    }
}

data class DeviceFacts(
    val maker: String,
    val model: String,
    val android: String,
    val sdk: Int,
    /** One UI version ("7.0") on Samsung phones when readable. */
    val oneUi: String? = null,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    /** "portrait" / "landscape" */
    val orientation: String,
    /** Safe-area insets of display 0 in px (status bar, navigation bar, cutout). */
    val insetTop: Int = 0,
    val insetBottom: Int = 0,
    val insetLeft: Int = 0,
    val insetRight: Int = 0,
    /** "gestures" / "3-button" / "2-button", or null when unknown. */
    val navigation: String? = null,
    val darkMode: Boolean? = null,
    val fontScale: Float = 1f,
    /** The display size setting relative to the default (1.0 = default). */
    val displayScale: Float = 1f,
    val languages: List<String> = emptyList(),
    val keyboardLanguages: List<String> = emptyList(),
    val foldKind: FoldKind? = null,
    val fold: FoldState? = null,
)

data class DesktopFacts(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val environment: String?,
    val presentation: String,
    /** "yes" / "no" / null (unknown). */
    val runsWhileLocked: Boolean?,
)

object DeviceContext {
    private fun dp(px: Int, dpi: Int) = if (dpi <= 0) px else (px * 160.0 / dpi).roundToInt()
    private fun density(dpi: Int) = (dpi / 160.0).let { if (it == it.roundToInt().toDouble()) it.roundToInt().toString() else "%.3f".format(java.util.Locale.ROOT, it).trimEnd('0') }

    fun deviceBlock(f: DeviceFacts?): String {
        if (f == null) return ""
        return buildString {
            appendLine("## This phone")
            append("- ${f.maker} ${f.model}")
            if (f.foldKind != null) append(" (foldable)")
            append(", Android ${f.android} (API ${f.sdk})")
            f.oneUi?.let { append(", One UI $it") }
            appendLine(".")
            append("- Phone screen (display 0): ${f.widthPx}x${f.heightPx} px = ${dp(f.widthPx, f.densityDpi)}x${dp(f.heightPx, f.densityDpi)} dp, " +
                "${f.densityDpi} dpi (x${density(f.densityDpi)}), ${f.orientation}")
            f.fold?.let { append("; ${it.text}") }
            appendLine(".")
            appendLine("- Coordinates are pixels with the origin at the top left of the screen named in [state]. Phone safe area: " +
                "top ${f.insetTop}, bottom ${f.insetBottom}, left ${f.insetLeft}, right ${f.insetRight} px (status bar, navigation bar, cutout).")
            val ui = buildList {
                f.navigation?.let { add("navigation: $it") }
                f.darkMode?.let { add(if (it) "dark mode" else "light mode") }
                if (f.fontScale != 1f) add("font scale ${"%.2f".format(java.util.Locale.ROOT, f.fontScale).trimEnd('0').trimEnd('.')}")
                if (f.displayScale != 1f) add("display size ${"%.2f".format(java.util.Locale.ROOT, f.displayScale).trimEnd('0').trimEnd('.')}x")
                if (f.languages.isNotEmpty()) add("languages: ${f.languages.take(3).joinToString()}")
                if (f.keyboardLanguages.isNotEmpty()) add("keyboard: ${f.keyboardLanguages.take(3).joinToString()}")
            }
            if (ui.isNotEmpty()) appendLine("- " + ui.joinToString("; ") + ".")
        }.trimEnd()
    }

    fun desktopBlock(d: DesktopFacts?): String {
        if (d == null) return ""
        val orientation = if (d.widthPx >= d.heightPx) "landscape" else "portrait"
        return buildString {
            appendLine("## The DeX desktop")
            append("- Display ${d.displayId}: ${d.widthPx}x${d.heightPx} px = ${dp(d.widthPx, d.densityDpi)}x${dp(d.heightPx, d.densityDpi)} dp, " +
                "${d.densityDpi} dpi, $orientation")
            d.environment?.let { append("; environment: $it") }
            append("; shown: ${d.presentation}")
            d.runsWhileLocked?.let { append("; keeps running while the phone is locked: ${if (it) "yes" else "no"}") }
            append(".")
        }
    }

    /** The desktop's facts from the host (null without a running desktop). */
    fun desktopFacts(host: DesktopHost?, environment: String?): DesktopFacts? {
        val h = host ?: return null
        val g = runCatching { h.geometry() }.getOrNull() ?: return null
        val presentation = when (runCatching { h.presentation() }.getOrNull()) {
            DesktopHost.Presentation.FULL -> "full screen on the phone"
            DesktopHost.Presentation.MINI -> "in the popup"
            DesktopHost.Presentation.HIDDEN -> "in the background"
            DesktopHost.Presentation.IN_APP -> "inside the Backstage app"
            else -> "not shown"
        }
        val locked = (h as? DesktopHostExtras)?.let { runCatching { it.lockedModeStatus()?.runsWhileLocked }.getOrNull() }
        return DesktopFacts(g.displayId, g.width, g.height, g.densityDpi, environment, presentation, locked)
    }
}

/**
 * The "[device]" line of screen listings: only what changed since the previous listing of the task (the first listing
 * of a task gives everything once). One task runs at a time, so one tracker serves the process; the controller resets
 * it when a task starts. [source] is installed by the Android environment.
 */
object DeviceStateDiff {
    @Volatile var source: (() -> Map<String, String>)? = null
    /** What the model has been shown. */
    private var shown: Map<String, String> = emptyMap()
    /** The newest state rendered into a listing (shown once [commit] says that listing reached the model). */
    private var latest: Map<String, String>? = null

    @Synchronized fun reset() { shown = emptyMap(); latest = null }

    /**
     * The changes since the last listing the model received, as "[device] key: value; …", or null when nothing
     * changed. Rendering does not consume them: settle polls and guard captures that never reach the model
     * preserve pending changes; [commit] marks them delivered.
   */
    @Synchronized fun line(): String? {
        val now = runCatching { source?.invoke() }.getOrNull() ?: return null
        latest = now
        return diff(shown, now)?.let { "[device] $it" }
    }

    /** The newest rendered state reached the model (the agent loop calls this when a listing is sent). */
    @Synchronized fun commit() { latest?.let { shown = it } }

    fun diff(previous: Map<String, String>, now: Map<String, String>): String? =
        now.entries.filter { previous[it.key] != it.value }.joinToString("; ") { "${it.key}: ${it.value}" }.ifEmpty { null }

    /** Battery in 5 % steps, so the line is not repeated for every percent. */
    fun battery(percent: Int, charging: Boolean): String = "${(percent / 5) * 5}%" + if (charging) ", charging" else ""
}
