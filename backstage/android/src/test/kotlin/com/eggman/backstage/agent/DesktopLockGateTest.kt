package com.eggman.backstage.agent

import com.eggman.backstage.exec.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A locked phone keyguards and sleeps a desktop with the default display flags: tasks must not work on it then. */
class DesktopLockGateTest {
    @Test fun aKeyguardedDesktopIsRefusedWithAReason() {
        assertNull(DesktopLockGate.problem(phoneLocked = false, sessionActive = true, runsWhileLocked = false, optedIn = false))
        assertEquals(DesktopLockGate.LOCKED,
            DesktopLockGate.problem(phoneLocked = true, sessionActive = true, runsWhileLocked = false, optedIn = true))
        assertNull(DesktopLockGate.problem(phoneLocked = true, sessionActive = true, runsWhileLocked = true, optedIn = true))
        // Not running yet: starting one only helps with the opt-in (it is created always-unlocked).
        assertNull(DesktopLockGate.problem(phoneLocked = true, sessionActive = false, runsWhileLocked = false, optedIn = true))
        assertEquals(DesktopLockGate.LOCKED,
            DesktopLockGate.problem(phoneLocked = true, sessionActive = false, runsWhileLocked = false, optedIn = false))
    }

    @Test fun onlyADesktopThatRunsWhileLockedLeavesThePhoneScreenOff() {
        assertFalse(DesktopLockGate.needsPhoneScreen(TargetKind.DESKTOP, desktopRunsWhileLocked = true))
        assertTrue(DesktopLockGate.needsPhoneScreen(TargetKind.DESKTOP, desktopRunsWhileLocked = false))
        assertTrue(DesktopLockGate.needsPhoneScreen(TargetKind.PHONE, desktopRunsWhileLocked = true))
        assertTrue(DesktopLockGate.needsPhoneScreen(null, desktopRunsWhileLocked = true))
    }
}
