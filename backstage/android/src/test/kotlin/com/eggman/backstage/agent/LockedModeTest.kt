package com.eggman.backstage.agent

import com.eggman.backstage.agent.DesktopLockGate.Step
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.host.LockedModeStatus
import com.eggman.backstage.notify.SessionMessage
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.voice.VoiceCommand
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locked mode ("Keep the desktop running while locked"): the gate, the awake policy, the report, the entry points. */
class LockedModeTest {
    private val g = LockedModeStatus.OWN_DISPLAY_GROUP
    private val u = LockedModeStatus.ALWAYS_UNLOCKED
    private val f = LockedModeStatus.OWN_FOCUS

    private fun status(
        active: Boolean = true,
        created: Boolean? = true,
        requested: Int = f or g or u,
        accepted: Int = f or g or u,
        effective: Int = accepted,
        awake: Boolean? = true,
        wake: Boolean? = true,
        fallback: String? = null,
        fallbackAt: Long = 0L,
    ) = LockedModeStatus(
        sessionActive = active, directDisplay = active, createdWithOptIn = if (active) created else null,
        requestedFlags = requested, acceptedFlags = accepted, effectiveFlags = effective,
        runsWhileLocked = active && effective and (g or u) == (g or u), desktopAwake = if (active) awake else null,
        wakeAllowed = wake, hostFallback = fallback, hostFallbackAtMs = fallbackAt,
    )

    @After fun tearDown() = LockedModeLog.reset()

    @Test fun unlockedOrRunningWhileLockedProceeds() {
        assertEquals(Step.Proceed, DesktopLockGate.decide(phoneLocked = false, sessionActive = true, runsWhileLocked = false, optedIn = false))
        assertEquals(Step.Proceed, DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = true, optedIn = true))
        // Not running: a desktop started gets the flags.
        assertEquals(Step.Proceed, DesktopLockGate.decide(phoneLocked = true, sessionActive = false, runsWhileLocked = false, optedIn = true))
    }

    @Test fun aDesktopStartedBeforeTheOptInIsRestartedOnceWhileLocked() {
        val restart = DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = true,
            createdWithOptIn = false)
        assertTrue(restart is Step.Restart)
        // Already restarted (still no flags, e.g. overlay fallback): no loop, a reason instead.
        val again = DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = true,
            createdWithOptIn = false, restartTriedRecently = true, hostFallback = "overlay")
        assertTrue(again is Step.Refuse && "overlay" in again.reason)
        // Cannot start one (auto-start off or no shell): no restart either.
        assertTrue(DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = true,
            createdWithOptIn = false, startable = false) is Step.Refuse)
    }

    @Test fun aRefusedFlagSetIsReportedWithThePlatformsReason() {
        val step = DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = true,
            createdWithOptIn = true, hostFallback = "the platform refused an always-unlocked desktop: Requires ADD_ALWAYS_UNLOCKED_DISPLAY")
        assertTrue(step is Step.Refuse)
        assertTrue("ADD_ALWAYS_UNLOCKED_DISPLAY" in (step as Step.Refuse).reason)
        assertEquals(Step.Refuse(DesktopLockGate.LOCKED),
            DesktopLockGate.decide(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = false))
        assertTrue(DesktopLockGate.decide(phoneLocked = true, sessionActive = false, runsWhileLocked = false, optedIn = true,
            startable = false) is Step.Refuse)
    }

    @Test fun aDesktopStartedWhileLockedStartsHidden() {
        assertEquals(Presentation.HIDDEN, DesktopLockGate.startPresentation(phoneLocked = true, configured = Presentation.FULL))
        assertEquals(Presentation.HIDDEN, DesktopLockGate.startPresentation(phoneLocked = true, configured = Presentation.MINI))
        assertEquals(Presentation.MINI, DesktopLockGate.startPresentation(phoneLocked = false, configured = Presentation.MINI))
    }

    @Test fun restartCooldown() {
        assertFalse(LockedModeLog.restartTriedRecently(1_000L))
        LockedModeLog.markRestart(1_000L)
        assertTrue(LockedModeLog.restartTriedRecently(1_000L + 60_000L))
        assertFalse(LockedModeLog.restartTriedRecently(1_000L + DesktopLockGate.RESTART_COOLDOWN_MS))
    }

    @Test fun onlyADesktopInItsOwnGroupIsHeldAwakeAndOnlyWhenNeeded() {
        assertTrue(DesktopAwakePolicy.hold(status(), taskRunning = true, phoneScreenOn = false))
        assertTrue(DesktopAwakePolicy.hold(status(), taskRunning = false, phoneScreenOn = true))
        // Locked phone, no task: the desktop may sleep (battery).
        assertFalse(DesktopAwakePolicy.hold(status(), taskRunning = false, phoneScreenOn = false))
        // In the phone's group a screen wake lock would keep the phone's own screen on: never.
        assertFalse(DesktopAwakePolicy.hold(status(accepted = f, effective = f), taskRunning = true, phoneScreenOn = false))
        assertFalse(DesktopAwakePolicy.hold(status(active = false), taskRunning = true, phoneScreenOn = true))
        assertFalse(DesktopAwakePolicy.hold(null, taskRunning = true, phoneScreenOn = true))
    }

    @Test fun reportSaysWhatToDo() {
        val now = 10_000_000L
        val off = LockedModeReport.build(settingOn = false, status = status(active = false), libraryFallback = null, nowMs = now)
        assertTrue(off.summary.startsWith("Off"))
        assertEquals("Off", off.lines.first { it.label == "Setting" }.value)

        val ready = LockedModeReport.build(true, status(), null, now)
        assertTrue(ready.summary.startsWith("Ready"))
        assertEquals("Yes", ready.lines.first { it.label == "Runs while locked now" }.value)
        assertEquals("own display group, always unlocked, own focus", ready.lines.first { it.label == "Flags applied" }.value)
        assertEquals("None", ready.lines.first { it.label == "Last fallback" }.value)
        assertEquals(LockedModeReport.Action.NONE, ready.action)

        val stale = LockedModeReport.build(true, status(created = false, requested = f, accepted = f), null, now)
        assertEquals(LockedModeReport.Action.RESTART_DESKTOP, stale.action)
        assertEquals("No", stale.lines.first { it.label == "Runs while locked now" }.value)

        val refused = LockedModeReport.build(true, status(accepted = f, fallback = "the platform refused an always-unlocked desktop",
            fallbackAt = now - 120_000L), null, now)
        assertTrue(refused.summary.startsWith("Not available") && "refused" in refused.summary)
        assertEquals("own display group, always unlocked, own focus", refused.lines.first { it.label == "Flags requested" }.value)
        assertEquals("own focus", refused.lines.first { it.label == "Flags accepted" }.value)
        assertEquals("the platform refused an always-unlocked desktop (2 min ago)", refused.lines.first { it.label == "Last fallback" }.value)

        val noWake = LockedModeReport.build(true, status(wake = false), null, now)
        assertEquals(LockedModeReport.Action.GRANT_WAKE, noWake.action)

        // The newer of host and agent fallbacks is shown.
        val newer = LockedModeReport.build(true, status(fallback = "old", fallbackAt = now - 3_600_000L),
            LockedModeLog.Entry("restarted the desktop while locked", now - 5_000L), now)
        assertEquals("restarted the desktop while locked (just now)", newer.lines.first { it.label == "Last fallback" }.value)

        val notRunning = LockedModeReport.build(true, status(active = false), null, now)
        assertTrue(notRunning.summary.contains("next desktop start"))
        assertEquals("Not running", notRunning.lines.first { it.label == "Desktop" }.value)
        assertEquals("none", LockedModeReport.flagNames(0))
    }

    @Test fun lockedDesktopAvailabilityFollowsTheGate() {
        class Host(val runs: Boolean, val optIn: Boolean, val created: Boolean?) :
            com.eggman.backstage.host.DesktopHost by FakeDesktopHost(),
            com.eggman.backstage.host.DesktopHostExtras {
            override fun setMiniStatus(text: String?) = Unit
            override fun setMiniShape(shape: String) = true
            override fun miniShape() = com.eggman.backstage.host.DesktopHostExtras.SHAPE_ROUNDED
            override fun usePrivilegeProvider(provider: String) = false
            override fun desktopAccessibilityComponent(): String? = null
            override fun desktopRunsWhileLocked() = runs
            override fun runWhileLocked() = optIn
            override fun lockedModeStatus() = created?.let { status(created = it) }
        }
        assertTrue(LockedDesktop.worksWhileLocked(Host(runs = true, optIn = true, created = true), autoStart = true))
        assertFalse(LockedDesktop.worksWhileLocked(Host(runs = false, optIn = false, created = null), autoStart = true))
        // Started before the opt-in: a task restarts it.
        assertTrue(LockedDesktop.worksWhileLocked(Host(runs = false, optIn = true, created = false), autoStart = true))
        assertFalse(LockedDesktop.worksWhileLocked(Host(runs = false, optIn = true, created = false), autoStart = false))
        assertFalse(LockedDesktop.worksWhileLocked(null, autoStart = true))
    }

    @Test fun voiceTasksWhileLockedOnlyWithTheOptIns() {
        val task = VoiceCommand.NewTask("open the mail")
        assertFalse(VoiceCommand.allowedWhileLocked(task, desktopWhileLocked = false))
        assertTrue(VoiceCommand.allowedWhileLocked(task, desktopWhileLocked = true))
        // Answers ("yes" to a payment) always need an unlock; stop always works.
        assertFalse(VoiceCommand.allowedWhileLocked(VoiceCommand.Answer("yes"), desktopWhileLocked = true))
        assertTrue(VoiceCommand.allowedWhileLocked(VoiceCommand.Stop, desktopWhileLocked = false))
    }

    @Test fun aReplyDeliveredWhileTheKeyguardGoesAwayWaitsForTheUnlock() = runTest {
        var reads = 0
        assertTrue(SessionMessage.awaitUnlock { ++reads < 4 })
        assertFalse(SessionMessage.awaitUnlock(graceMs = 1_000L) { true })
        assertTrue(SessionMessage.awaitUnlock { false })
    }

    @Test fun phoneScreenIsTheDefaultDisplayState() {
        assertTrue(PhoneScreen.stateOn(android.view.Display.STATE_ON))
        assertFalse(PhoneScreen.stateOn(android.view.Display.STATE_OFF))
        assertFalse(PhoneScreen.stateOn(android.view.Display.STATE_DOZE))
    }
}
