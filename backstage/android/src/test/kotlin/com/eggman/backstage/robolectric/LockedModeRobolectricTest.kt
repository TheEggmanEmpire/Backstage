package com.eggman.backstage.robolectric

import android.app.Application
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import com.eggman.backstage.agent.AndroidAgentEnvironment
import com.eggman.backstage.agent.LockedModeLog
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.LockedModeStatus
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The desktop executor on a locked phone with "Keep the desktop running while locked". */
@RunWith(RobolectricTestRunner::class)
class LockedModeRobolectricTest {
    private lateinit var ctx: Application

    /** A desktop host whose locked-mode facts the test sets; a restart creates the desktop with the opt-in. */
    private class LockedHost(
        private val fake: FakeDesktopHost = FakeDesktopHost(),
        var optIn: Boolean = true,
        var created: Boolean = false,
        var runs: Boolean = false,
        var fallback: String? = null,
        var flagsApplyOnRestart: Boolean = true,
    ) : DesktopHost by fake, DesktopHostExtras {
        val calls get() = fake.calls
        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = true
        override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun runWhileLocked() = optIn
        override fun desktopRunsWhileLocked() = fake.sessionActive() && runs
        override fun lockedModeStatus() = LockedModeStatus(
            sessionActive = fake.sessionActive(), directDisplay = true,
            createdWithOptIn = if (fake.sessionActive()) created else null,
            requestedFlags = 0, acceptedFlags = 0, effectiveFlags = 0, runsWhileLocked = desktopRunsWhileLocked(),
            desktopAwake = true, wakeAllowed = true, hostFallback = fallback,
        )
        override suspend fun startDesktop(presentation: DesktopHost.Presentation, timeoutMs: Long): DesktopHost.Geometry? {
            created = optIn
            runs = optIn && flagsApplyOnRestart
            return fake.startDesktop(presentation, timeoutMs)
        }
        override suspend fun restartDesktop(presentation: DesktopHost.Presentation): DesktopHost.Geometry? {
            fake.calls += "restart $presentation"
            fake.stopDesktop()
            return startDesktop(presentation, 1_000L)
        }
    }

    @Before fun setUp() {
        RoboSupport.resetProcess()
        LockedModeLog.reset()
        ctx = ApplicationProvider.getApplicationContext()
        com.eggman.backstage.data.Settings(ctx).onboardingDone = true
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
    }

    @After fun tearDown() {
        DesktopHosts.current = null
        LockedModeLog.reset()
        RoboSupport.resetProcess()
    }

    @Test fun aTaskCannotStartTheDesktopBeforeOnboardingFinishes() = runBlocking {
        com.eggman.backstage.data.Settings(ctx).onboardingDone = false
        val host = LockedHost(fake = FakeDesktopHost(active = false))
        DesktopHosts.current = host
        val failure = runCatching { AndroidAgentEnvironment(ctx).executor(TargetKind.DESKTOP) }.exceptionOrNull()
        assertEquals("Finish Backstage setup before starting the desktop.", failure?.message)
        assertFalse(host.calls.any { it.startsWith("start ") })
    }

    @Test fun aDesktopStartedBeforeTheSettingIsRestartedHiddenWhenATaskNeedsItLocked() = runBlocking {
        val host = LockedHost()
        DesktopHosts.current = host
        val env = AndroidAgentEnvironment(ctx)
        // A task started while locked does not wake the phone for it: the desktop will work locked.
        assertFalse(com.eggman.backstage.agent.ReadinessCheck.needsPhoneScreen(env.readinessInputs(), TargetKind.DESKTOP))
        env.executor(TargetKind.DESKTOP)
        assertTrue(host.calls.toString(), "restart HIDDEN" in host.calls)
        assertTrue(LockedModeLog.last!!.reason.startsWith("restarted the desktop while locked"))
    }

    @Test fun noDesktopYetStartsHiddenWhileLocked() = runBlocking {
        val host = LockedHost()
        host.calls.clear()
        host.stopDesktop()
        DesktopHosts.current = host
        AndroidAgentEnvironment(ctx).executor(TargetKind.DESKTOP)
        assertTrue(host.calls.toString(), "start HIDDEN" in host.calls)
    }

    @Test fun aDesktopThePlatformRefusedTheFlagsForFailsWithTheReason() = runBlocking {
        val host = LockedHost(created = true, runs = false, fallback = "the platform refused an always-unlocked desktop: Requires X")
        DesktopHosts.current = host
        try {
            AndroidAgentEnvironment(ctx).executor(TargetKind.DESKTOP)
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, "Requires X" in e.message!!)
        }
        assertFalse("restart HIDDEN" in host.calls)
        assertEquals(true, LockedModeLog.last?.reason?.contains("Requires X"))
    }

    @Test fun withoutTheSettingALockedPhoneRefusesTheDesktop() = runBlocking {
        DesktopHosts.current = LockedHost(optIn = false)
        try {
            AndroidAgentEnvironment(ctx).executor(TargetKind.DESKTOP)
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertEquals(com.eggman.backstage.agent.DesktopLockGate.LOCKED, e.message)
        }
    }
}
