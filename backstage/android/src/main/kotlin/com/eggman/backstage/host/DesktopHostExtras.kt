package com.eggman.backstage.host

/**
 * Optional capabilities of the Dextop host beyond the fixed [DesktopHost] contract. The glue
 * (backstage/dextop-glue, compiled into Dextop's:app) implements it on the same object it registers as
 * [DesktopHosts.current]; the library checks `DesktopHosts.current as? DesktopHostExtras` and works without it
 * (standalone / phone-only builds). Kept separate so the frozen contract's signatures stay unchanged.
 */
interface DesktopHostExtras {
    /** One-line agent status shown as the mini window's caption; null hides it. Any thread. */
    fun setMiniStatus(text: String?)

    /** Mini window shape: [SHAPE_ROUNDED] (default) or [SHAPE_CIRCLE]. Persisted by the host, applied live. */
    fun setMiniShape(shape: String): Boolean
    fun miniShape(): String

    /**
     * Top edge (phone screen pixels) of the chat panel opened from the mini window's chat button, null once it closed:
     * the mini window keeps clear of it like of the phone keyboard. Any thread.
   */
    fun setMiniPanelTop(top: Int?) = Unit

    /**
     * Make [provider] (`preferred`, `stellar` or [PRIVILEGE_SHIZUKU]) Dextop's privilege provider instead of its embedded wireless-ADB
     * runtime (the embedded runtime remains the fallback). Returns false when the provider is not installed.
   */
    fun usePrivilegeProvider(provider: String): Boolean

    /**
     * Flattened component name of the app's ONE accessibility service (Dextop's MirrorService: the desktop engine, which
     * also serves the agent through [com.eggman.backstage.a11y.AccessibilityBridge]), or null.
   */
    fun desktopAccessibilityComponent(): String?

    /**
     * True only while the running desktop keeps running and stays unlocked when the phone is locked (created with its
     * own display group and always-unlocked: [setRunWhileLocked]). Otherwise the desktop sleeps with the phone and is
     * covered by the keyguard, so nothing can work on it until the phone is unlocked.
   */
    fun desktopRunsWhileLocked(): Boolean = false

    /** The user's opt-in for [setRunWhileLocked] (stored by the host). */
    fun runWhileLocked(): Boolean = false

    /**
     * Opt-in, applies to the next desktop start: desktop apps keep running and are not covered by the keyguard while
     * the phone is locked, so schedules and automations can use the desktop then. Whoever can reach the desktop
     * (agent, MCP clients) can use it without an unlock. Returns false when the host cannot do it.
   */
    fun setRunWhileLocked(enabled: Boolean): Boolean = false

    /**
     * Locked-mode facts of the running desktop (what is asked for, what the platform applied, whether it keeps running
     * while the phone is locked, whether its display is awake) and the host's last fallback reason; null when the host
     * cannot tell. For the "Locked mode" diagnostics and [com.eggman.backstage.agent.DesktopLockGate].
   */
    fun lockedModeStatus(): LockedModeStatus? = null

    /**
     * Stops the running desktop and starts a new one in [presentation] (a desktop created before "keep running while
     * locked" is turned on gets the flags only at creation). Returns the new desktop, or null when it did not start.
   */
    suspend fun restartDesktop(presentation: DesktopHost.Presentation): DesktopHost.Geometry? = null

    /**
     * Keeps the desktop display [displayId] awake (and wakes it when its display group went to sleep) while the
     * returned handle is open, at most [timeoutMs]. Only for a desktop in its own display group
     * ([LockedModeStatus.ownDisplayGroup]): on any other display it would keep the phone's own screen on. Null when
     * the host cannot. Closing twice is harmless.
   */
    fun holdDesktopAwake(displayId: Int, timeoutMs: Long): AutoCloseable? = null

    /**
     * Lets this app wake the desktop's display group (TURN_SCREEN_ON app-op, granted through the privileged shell when
     * possible). Returns whether waking is allowed.
   */
    fun ensureDesktopWakePermission(): Boolean = false

    /**
     * How the running desktop's focus relates to the phone's ([DesktopFocusPolicy.QUIET] / [DesktopFocusPolicy.OWN] /
     * [DesktopFocusPolicy.SHARED]); null without a desktop or when the host cannot tell. Only a quiet desktop is
     * guaranteed to leave the phone app focused (keyboard open) while the agent taps and launches on it; on the others
     * the host gives the focus back afterwards. A desktop the agent starts in MINI/HIDDEN is created quiet where the
     * platform allows it (Android 14+); one started FULL from Dextop is not.
   */
    fun desktopFocusMode(): String? = null

    /**
     * The focus mode chosen for subsequent desktop sessions: [DesktopFocusPolicy.OWN] (default: the desktop has a focus
     * of its own) or [DesktopFocusPolicy.SHARED] (no own focus: key events reach the desktop only while it is the
     * phone's focused display, for devices where an own focus misbehaves). null when the host cannot change it.
   */
    fun focusModeSetting(): String? = null

    /**
     * Choose [DesktopFocusPolicy.OWN] or [DesktopFocusPolicy.SHARED] for the next desktop start; false when the host cannot.
     * SHARED replaces "keep running while locked" (that needs an own focus), and OWN keeps it as chosen.
   */
    fun setFocusModeSetting(mode: String): Boolean = false

    /**
     * Why launching [packageName] on the desktop is refused, or null: the app is open on the phone and
     * launching it would pull its task off the phone ([DesktopLaunchPolicy]). For the model's error message.
   */
    fun launchRefusal(packageName: String): String? = null

    /**
     * Why [text] cannot be typed on the desktop with key events, or null ([DesktopTyping]). The host never falls back
     * to the phone's clipboard. For the model's error message.
   */
    fun typingRefusal(text: String): String? = DesktopTyping.refusal(text)

    /**
     * The app tasks (windows) on the running desktop's display only, top-most first; never a task of the phone's
     * display. Null when the host cannot list tasks (no desktop, no privilege). See [DesktopTaskPolicy].
   */
    fun desktopTasks(): List<DesktopTask>? = null

    /**
     * Closes one task of the desktop display (removeTask). The host re-checks that [taskId] is still on the desktop
     * display and closable ([DesktopTaskPolicy.closable]) right before closing it, so a task that moved to the phone
     * meanwhile is never touched. Nothing is force-stopped. False when the task is not closed.
   */
    fun closeDesktopTask(taskId: Int): Boolean = false

    /**
     * The app tasks of the phone's own display (0), top-most first, with their windowing mode and bounds; null when the
     * host cannot list them. Read-only: used to verify where a launch landed, to report the phone's state to the agent
     * and to find an app's task for [controlTask]. Works without a desktop session.
   */
    fun phoneTasks(): List<DesktopTask>? = null

    /**
     * Applies [op] to task [taskId] on display [displayId] (the desktop display or 0). [bounds] is needed for
     * [TaskOp.FREEFORM] (and optional for [TaskOp.MOVE_TO_DESKTOP]). The host re-checks right before acting that the
     * task is still on [displayId], is an ordinary app task and is not Backstage itself; nothing is force-stopped.
     * Focus is not moved to a background desktop. False when nothing is done.
   */
    fun controlTask(taskId: Int, displayId: Int, op: TaskOp, bounds: TaskBounds? = null): Boolean = false

    /**
     * Which desktop runs on the display: Dextop's environment id ("samsung_dex", "android_freeform",...) or
     * "dextop_plasma" when one of Dextop's own window managers owns it; null when unknown
     * ([com.eggman.backstage.exec.DesktopShortcutEnv]).
   */
    fun desktopEnvironment(): String? = null

    /**
     * The agent chat's live desktop player: its Surface, or null when it goes. Shown while the desktop is IN_APP;
     * Home's player gets the slot back afterwards ([PlayerSurfaceOwner]). Main thread. False when not supported.
   */
    fun setChatPlayerSurface(surface: android.view.Surface?): Boolean = false

    /**
     * One touch on the chat's player, already mapped to desktop display pixels through the fitted picture
     * ([PlayerFit]): [action] is [PlayerFit.DOWN], [PlayerFit.MOVE], [PlayerFit.UP] or [PlayerFit.CANCEL]. Forwarded
     * like Home's player touches (one ordered lane with the agent's input). False when not supported.
   */
    fun playerTouch(action: Int, x: Float, y: Float): Boolean = false

    companion object {
        const val SHAPE_ROUNDED = "rounded"
        const val SHAPE_CIRCLE = "circle"
        const val PRIVILEGE_SHIZUKU = "shizuku"
    }
}

/** The installed host's extras, or null without the Dextop glue. */
fun DesktopHosts.extras(): DesktopHostExtras? = current as? DesktopHostExtras

/**
 * Locked mode ("Keep the desktop running while locked") of the running desktop, as the host saw it. Flags use
 * [LockedModeStatus.OWN_DISPLAY_GROUP] / [ALWAYS_UNLOCKED] / [OWN_FOCUS] / [STEAL_TOP_FOCUS_DISABLED] (VirtualDisplay
 * flag values). Pure data.
 */
data class LockedModeStatus(
    val sessionActive: Boolean,
    /** The desktop is the owned direct display (an overlay display never keeps running while locked). */
    val directDisplay: Boolean,
    /** The running desktop is created with "keep running while locked" on; null without a session. */
    val createdWithOptIn: Boolean?,
    /** Flags the richest rung asked for, the rung the platform accepted, and what the display really carries. */
    val requestedFlags: Int,
    val acceptedFlags: Int,
    val effectiveFlags: Int,
    val runsWhileLocked: Boolean,
    /** The desktop display is on (its display group awake); null without a session. */
    val desktopAwake: Boolean?,
    /** This app may wake the desktop's display group when it went to sleep (TURN_SCREEN_ON); null when unknown. */
    val wakeAllowed: Boolean?,
    /** The host's last reason the desktop did not keep running while locked, and when (wall clock ms, 0 = never). */
    val hostFallback: String?,
    val hostFallbackAtMs: Long = 0L,
    /** Read-only device hints, separate from the flags the platform actually applied. */
    val compatibilityProfile: String? = null,
    /** Latest display-creation/windowing refusal, including when locked mode is not requested. */
    val displayFallback: String? = null,
) {
    val ownDisplayGroup: Boolean get() = effectiveFlags and OWN_DISPLAY_GROUP != 0

    companion object {
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val OWN_FOCUS = 1 shl 14
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
