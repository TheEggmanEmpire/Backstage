package com.eggman.backstage.agent

import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.LockedModeStatus

/**
 * Whether the desktop can be worked on while the phone is locked (pure, unit-tested). With the default display flags
 * the desktop sleeps with the phone and the keyguard covers it, so reading or acting on it only wastes steps; only a
 * desktop created to keep running while locked (own display group + always unlocked, the opt-in "Keep the desktop
 * running while locked") works then.
 */
object DesktopLockGate {
    const val LOCKED = "The phone is locked, and the desktop pauses while it is locked: unlock the phone and try again. " +
        "To let tasks use the desktop while the phone is locked, turn on \"Keep the desktop running while locked\" in Settings."

    /** What the desktop executor does before it uses the desktop. */
    sealed interface Step {
        /** Use the running desktop, or start one (in [DesktopLockGate.startPresentation]). */
        data object Proceed : Step
        /** The running desktop is created before the opt-in: stop it and start one with the flags. */
        data class Restart(val reason: String) : Step
        data class Refuse(val reason: String) : Step
    }

    /**
     * [phoneLocked]: keyguard up or the phone's screen off. [createdWithOptIn]: the running desktop is created with the
     * opt-in (null: unknown or no session). [startable]: a desktop can be started (auto-start on, shell access).
     * [restartTriedRecently]: a restart for locked mode already happened, so a second one would only loop.
   */
    fun decide(
        phoneLocked: Boolean,
        sessionActive: Boolean,
        runsWhileLocked: Boolean,
        optedIn: Boolean,
        createdWithOptIn: Boolean? = null,
        hostFallback: String? = null,
        startable: Boolean = true,
        restartTriedRecently: Boolean = false,
    ): Step = when {
        !phoneLocked -> Step.Proceed
        sessionActive && runsWhileLocked -> Step.Proceed
        !optedIn -> Step.Refuse(LOCKED)
        sessionActive && createdWithOptIn == false && !restartTriedRecently && startable ->
            Step.Restart("the desktop was started before \"Keep the desktop running while locked\" was turned on")
        sessionActive -> Step.Refuse(cannotRunLocked(hostFallback))
        startable -> Step.Proceed
        else -> Step.Refuse(cannotRunLocked("no desktop runs and it cannot be started now (auto-start or shell access is off)"))
    }

    fun cannotRunLocked(reason: String?): String =
        "The phone is locked and the desktop cannot keep running while it is locked" +
            (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") +
            ". Unlock the phone and try again; Settings > Locked mode shows the details."

    /** Compatibility form of [decide] (creation unknown, startable): [LOCKED] when refused, else null. */
    fun problem(phoneLocked: Boolean, sessionActive: Boolean, runsWhileLocked: Boolean, optedIn: Boolean): String? =
        if (decide(phoneLocked, sessionActive, runsWhileLocked, optedIn) is Step.Refuse) LOCKED else null

    /** A desktop started while the phone is locked starts in the background: nothing may show over the keyguard. */
    fun startPresentation(phoneLocked: Boolean, configured: DesktopHost.Presentation): DesktopHost.Presentation =
        if (phoneLocked) DesktopHost.Presentation.HIDDEN else configured

    /** Wake the screen before a task, unless it targets a desktop that keeps running while locked. */
    fun needsPhoneScreen(target: TargetKind?, desktopRunsWhileLocked: Boolean): Boolean =
        !(target == TargetKind.DESKTOP && desktopRunsWhileLocked)

    /** A restart for locked mode counts as recent for this long (no second restart: it would only loop). */
    const val RESTART_COOLDOWN_MS = 10 * 60_000L
}

/** Can a task use the desktop while the phone is locked (running that way, or startable/restartable so)? */
object LockedDesktop {
    fun worksWhileLocked(host: DesktopHost?, autoStart: Boolean): Boolean {
        val extras = host as? com.eggman.backstage.host.DesktopHostExtras ?: return false
        val active = runCatching { host.sessionActive() }.getOrDefault(false)
        val startable = autoStart && runCatching { host.privilegedAvailable() }.getOrDefault(false)
        val status = runCatching { extras.lockedModeStatus() }.getOrNull()
        return DesktopLockGate.decide(
            phoneLocked = true,
            sessionActive = active,
            runsWhileLocked = runCatching { extras.desktopRunsWhileLocked() }.getOrDefault(false),
            optedIn = runCatching { extras.runWhileLocked() }.getOrDefault(false),
            createdWithOptIn = status?.createdWithOptIn,
            hostFallback = status?.hostFallback,
            startable = startable,
            restartTriedRecently = LockedModeLog.restartTriedRecently(),
        ) !is DesktopLockGate.Step.Refuse
    }
}

/** The library's own last locked-mode fallback (gate refusals, restarts, wake problems), shown in Settings. */
object LockedModeLog {
    data class Entry(val reason: String, val atMs: Long)

    @Volatile var last: Entry? = null
        private set
    @Volatile private var lastRestartMs = 0L

    fun record(reason: String, nowMs: Long = System.currentTimeMillis()) {
        last = Entry(reason, nowMs)
    }

    fun markRestart(nowMs: Long = System.currentTimeMillis()) {
        lastRestartMs = nowMs
    }

    fun restartTriedRecently(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastRestartMs != 0L && nowMs - lastRestartMs in 0 until DesktopLockGate.RESTART_COOLDOWN_MS

    /** Tests only. */
    fun reset() {
        last = null
        lastRestartMs = 0L
    }
}

/**
 * When the desktop's own display group is held awake (pure). A desktop with its own group (locked mode) sleeps on its
 * own timeout: user activity on the phone does not keep it awake, and once asleep its apps pause and injected input is
 * dropped. So it is held awake while a task runs and while the phone's screen is on (the user may be watching it).
 * Never for a desktop in the phone's group: a screen wake lock there would keep the phone's own screen on.
 */
object DesktopAwakePolicy {
    fun hold(status: LockedModeStatus?, taskRunning: Boolean, phoneScreenOn: Boolean): Boolean =
        status != null && status.sessionActive && status.ownDisplayGroup && (taskRunning || phoneScreenOn)
}

/** The "Locked mode" diagnostic row in Settings (pure; LockedModeReportTest). */
object LockedModeReport {
    data class Line(val label: String, val value: String)

    enum class Action { NONE, RESTART_DESKTOP, GRANT_WAKE }

    data class Report(val summary: String, val lines: List<Line>, val action: Action)

    fun flagNames(flags: Int): String = buildList {
        if (flags and LockedModeStatus.OWN_DISPLAY_GROUP != 0) add("own display group")
        if (flags and LockedModeStatus.ALWAYS_UNLOCKED != 0) add("always unlocked")
        if (flags and LockedModeStatus.OWN_FOCUS != 0) add("own focus")
        if (flags and LockedModeStatus.STEAL_TOP_FOCUS_DISABLED != 0) add("quiet focus")
    }.ifEmpty { listOf("none") }.joinToString(", ")

    fun ago(atMs: Long, nowMs: Long): String {
        val s = ((nowMs - atMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /**
     * [settingOn]: the user's opt-in. [status]: the host's view (null: this build has no desktop host or it cannot tell).
     * [libraryFallback]: the agent's own last fallback. The newer of the two fallbacks is shown.
   */
    fun build(settingOn: Boolean, status: LockedModeStatus?, libraryFallback: LockedModeLog.Entry?, nowMs: Long): Report {
        val lines = ArrayList<Line>()
        lines += Line("Setting", if (settingOn) "On" else "Off")
        status?.compatibilityProfile?.let { lines += Line("Compatibility profile", it) }
        status?.displayFallback?.let { lines += Line("Display compatibility", it) }
        if (status == null) {
            lines += Line("Desktop", "Unknown (no desktop engine)")
        } else if (!status.sessionActive) {
            lines += Line("Desktop", "Not running")
        } else {
            lines += Line("Desktop", if (status.directDisplay) "Running (direct display)" else "Running (overlay display)")
            lines += Line("Started with the setting", if (status.createdWithOptIn == true) "Yes" else "No")
            lines += Line("Flags requested", flagNames(status.requestedFlags))
            lines += Line("Flags accepted", flagNames(status.acceptedFlags))
            lines += Line("Flags applied", flagNames(status.effectiveFlags))
            lines += Line("Runs while locked now", if (status.runsWhileLocked) "Yes" else "No")
            status.desktopAwake?.let { lines += Line("Desktop display", if (it) "Awake" else "Asleep") }
        }
        status?.wakeAllowed?.let { lines += Line("May wake the desktop", if (it) "Yes" else "No") }
        val host = status?.hostFallback?.let { LockedModeLog.Entry(it, status.hostFallbackAtMs) }
        val fallback = listOfNotNull(host, libraryFallback).maxByOrNull { it.atMs }
        lines += Line("Last fallback", fallback?.let { "${it.reason} (${ago(it.atMs, nowMs)})" } ?: "None")

        val (summary, action) = when {
            !settingOn -> "Off: the desktop sleeps and locks with the phone." to Action.NONE
            status == null -> "On, but this build cannot report the desktop's state." to Action.NONE
            !status.sessionActive -> "On: the next desktop start tries to keep running while locked; " +
                "the platform must accept and apply an independent, unlocked display group." to Action.NONE
            status.runsWhileLocked && status.wakeAllowed == false -> "Works while locked, but Backstage may not wake " +
                "the desktop once it fell asleep: grant it (needs Shizuku or wireless debugging)." to Action.GRANT_WAKE
            status.runsWhileLocked -> "Ready: tasks can use the desktop while the phone is locked." to Action.NONE
            status.createdWithOptIn != true -> "Not active yet: this desktop was started before the setting was on. " +
                "Restart it (tasks started while locked restart it themselves)." to Action.RESTART_DESKTOP
            else -> "Not available on this desktop: " + (status.hostFallback ?: "the platform did not apply the flags") +
                "." to Action.NONE
        }
        return Report(summary, lines, action)
    }
}
