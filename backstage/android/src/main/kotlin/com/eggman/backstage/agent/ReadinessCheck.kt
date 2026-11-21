package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.Answers

import com.eggman.backstage.agent.api.Readiness
import com.eggman.backstage.a11y.AccessibilityEnabler

/** Raw device facts; gathered by the Android environment, mapped to [Readiness] by pure code. */
data class ReadinessInputs(
    val providerProblem: String?,
    val accessibilityEnabled: Boolean,
    val privilegedAvailable: Boolean,
    val hostPresent: Boolean,
    val desktopActive: Boolean,
    val directDisplay: Boolean,
    val screenOn: Boolean,
    val unlocked: Boolean,
    val sdkInt: Int,
    val appLabel: String = "Backstage",
    /** The running desktop keeps running, and stays unlocked, while the phone is locked (DesktopHostExtras). */
    val desktopRunsWhileLocked: Boolean = false,
    /** No desktop runs, but one can be started with "keep running while locked" (opt-in, auto-start, privilege). */
    val desktopStartableWhileLocked: Boolean = false,
    /** The running desktop is created before the opt-in; a task started while locked restarts it with the flags. */
    val desktopRestartableWhileLocked: Boolean = false,
    /** This build has the app's accessibility service (Dextop's MirrorService); false for the standalone library. */
    val accessibilityHost: Boolean = true,
    /** What the connected accessibility service's configuration does not grant the agent (AccessibilityBridge). */
    val accessibilityGaps: List<String> = emptyList(),
)

object ReadinessCheck {
    fun compute(i: ReadinessInputs): Readiness {
        val problems = buildList {
            i.providerProblem?.let { add("AI provider not configured: $it") }
            if (!i.accessibilityEnabled) {
                add(
                    if (!i.accessibilityHost) AccessibilityEnabler.NO_HOST
                    else if (i.privilegedAvailable) "Accessibility service is off; ${i.appLabel} will switch it on automatically — or enable it in Settings → Accessibility."
                    else AccessibilityEnabler.manualGuidance(i.appLabel, i.sdkInt),
                )
            }
            if (i.accessibilityEnabled && i.accessibilityGaps.isNotEmpty()) {
                add("The accessibility service is missing what the agent needs (${i.accessibilityGaps.joinToString()}); switch it off and on again in Settings → Accessibility after updating ${i.appLabel}.")
            }
            if (!i.screenOn) add(SCREEN_OFF)
            else if (!i.unlocked) add(DEVICE_LOCKED)
            if (i.hostPresent && !i.privilegedAvailable && !i.desktopActive) {
                add("Background desktop unavailable: connect Dextop's wireless debugging (or Shizuku) so tasks can run on the desktop. Phone-screen control still works.")
            }
            if (i.hostPresent && i.desktopActive && !i.directDisplay) {
                add("The desktop runs in overlay-display mode, which the agent cannot read; restart the desktop from ${i.appLabel} to use the direct display.")
            }
        }
        return Readiness(
            providerConfigured = i.providerProblem == null,
            accessibilityEnabled = i.accessibilityEnabled,
            privilegedAvailable = i.privilegedAvailable,
            desktopActive = i.desktopActive,
            screenOn = i.screenOn,
            unlocked = i.unlocked,
            problems = problems,
        )
    }

    /**
     * Why an unattended task (Tasker, broadcast, schedule, MCP) cannot work at all, or null: no target it may
     * use ([decision] without a kind, with the resolver's setup hint), or Backstage's accessibility service is off, so
     * neither the phone nor the desktop can be read or operated. Checked before the first model call.
   */
    const val SCREEN_OFF = "The screen is off: wake the device. Android pauses apps and gestures while the screen is off."
    const val DEVICE_LOCKED = "The device is locked: unlock it. Apps cannot be operated on the lock screen."

    /**
     * Problems Setup can fix. Screen off and locked are momentary device states, excluded from setup guidance.
   */
    fun setupProblems(r: Readiness): List<String> = r.problems.filterNot { it == SCREEN_OFF || it == DEVICE_LOCKED }

    fun unattendedProblem(decision: com.eggman.backstage.exec.TargetDecision, i: ReadinessInputs): String? = when {
        decision.kind == null -> "Not started: ${decision.reason}"
        !i.accessibilityEnabled -> "Not started: the agent cannot see or operate any screen. " +
            (compute(i).problems.firstOrNull { "ccessibility" in it } ?: "Turn on Backstage's accessibility service.")
        else -> null
    }

    /** Can a device task run at all (MCP get_device_status "ready")? */
    fun ready(r: Readiness): Boolean = r.providerConfigured && r.accessibilityEnabled && r.screenOn && r.unlocked

    /**
     * The desktop needs neither the phone's screen nor an unlock when it keeps running while the phone
     * is locked: an active session created for that, or one that will be started that way (same rule as
     * [DesktopLockGate.problem], which the desktop executor applies). Otherwise it sleeps with the phone.
   */
    fun desktopWorksWhileLocked(i: ReadinessInputs): Boolean =
        i.hostPresent && DesktopLockGate.problem(
            phoneLocked = true,
            sessionActive = i.desktopActive,
            runsWhileLocked = i.desktopRunsWhileLocked || i.desktopRestartableWhileLocked,
            optedIn = i.desktopStartableWhileLocked,
        ) == null

    /** Whether the phone's screen state matters for a task on [target] (false: a desktop that works while locked). */
    fun needsPhoneScreen(i: ReadinessInputs, target: com.eggman.backstage.exec.TargetKind?): Boolean =
        !(target == com.eggman.backstage.exec.TargetKind.DESKTOP && desktopWorksWhileLocked(i))

    /** [ready] for a task on [target]: screen on and unlocked are required only where the phone's screen matters. */
    fun readyFor(r: Readiness, i: ReadinessInputs, target: com.eggman.backstage.exec.TargetKind?): Boolean =
        r.providerConfigured && r.accessibilityEnabled && (!needsPhoneScreen(i, target) || (r.screenOn && r.unlocked))
}

/**
 * Task time limit that does not count time spent waiting for the user (paused
 * or waiting time is excluded). Pure; the clock is passed in.
 */
class ActiveTimer(startMs: Long) {
    private var activeAccum = 0L
    private var runningSince: Long? = startMs

    fun pause(now: Long) {
        runningSince?.let { activeAccum += now - it }
        runningSince = null
    }

    fun resume(now: Long) {
        if (runningSince == null) runningSince = now
    }

    fun activeMs(now: Long): Long = activeAccum + (runningSince?.let { now - it } ?: 0)
    val paused: Boolean get() = runningSince == null
}

/**
 * Interprets a reply to a confirmation question. Surfaces send the canonical [Answers.APPROVE] /
 * [Answers.DENY]; free text (voice, chat, Tasker) is read strictly: any negation denies, and only a short
 * reply that starts with an explicit yes approves ("go away", "ok no", "sure, but don't send it" deny).
 */
object Approval {
    private val yesWords = setOf(
        "y", "yes", "yep", "yeah", "ok", "okay", "sure", "approve", "approved", "confirm", "confirmed", "allow",
        "ja", "jawohl", "klar", "bestätigen", "bestätige", "oui", "sí", "si",
    )
    private val yesPhrases = setOf("go ahead", "do it", "send it", "mach das", "mach es")
    private val negations = setOf(
        "n", "no", "nope", "not", "don't", "dont", "do not", "never", "deny", "denied", "cancel", "stop", "wait", "but",
        "nein", "nicht", "abbrechen", "non", "pas", "nada", "away",
    )

    fun parse(answer: String?): Boolean {
        val a = answer?.trim()?.lowercase() ?: return false
        if (a == Answers.APPROVE) return true
        if (a == Answers.DENY) return false
        val words = a.replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 5) return false
        val text = words.joinToString(" ")
        if (words.any { it in negations } || negations.any { it.contains(' ') && text.contains(it) }) return false
        return words.first() in yesWords || yesPhrases.any { text == it || text.startsWith("$it ") }
    }
}
