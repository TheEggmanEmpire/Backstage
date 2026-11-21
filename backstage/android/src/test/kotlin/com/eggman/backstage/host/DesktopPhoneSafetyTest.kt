package com.eggman.backstage.host

import com.eggman.backstage.host.DesktopHost.Presentation.FULL
import com.eggman.backstage.host.DesktopHost.Presentation.HIDDEN
import com.eggman.backstage.host.DesktopHost.Presentation.MINI
import com.eggman.backstage.host.DesktopHost.Presentation.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "The agent controls the desktop while I keep using my phone normally": the rules the Dextop glue applies. */
class DesktopPhoneSafetyTest {

    @Test
    fun aRunningButUngrantedShizukuIsNoPrivilege() {
        // Shizuku hands its binder to every app; without the permission every shell call fails.
        assertFalse(PrivilegeStatus.usable(embeddedRuntime = false, shizukuBinderAlive = true, shizukuGranted = false))
        assertTrue(PrivilegeStatus.usable(embeddedRuntime = false, shizukuBinderAlive = true, shizukuGranted = true))
        assertTrue(PrivilegeStatus.usable(embeddedRuntime = true, shizukuBinderAlive = true, shizukuGranted = false))
        assertTrue(PrivilegeStatus.usable(embeddedRuntime = true, shizukuBinderAlive = false, shizukuGranted = false))
        // A stale grant answer without a live binder is no privilege either.
        assertFalse(PrivilegeStatus.usable(embeddedRuntime = false, shizukuBinderAlive = false, shizukuGranted = true))
    }

    @Test
    fun theFocusGoesBackToThePhoneUnlessTheDesktopIsQuiet() {
        for (presentation in listOf(MINI, HIDDEN)) {
            assertTrue(DesktopFocusPolicy.returnFocusToPhone(DesktopFocusPolicy.OWN, presentation))
            assertTrue(DesktopFocusPolicy.returnFocusToPhone(DesktopFocusPolicy.SHARED, presentation))
            assertTrue(DesktopFocusPolicy.returnFocusToPhone(null, presentation))
            assertFalse(DesktopFocusPolicy.returnFocusToPhone(DesktopFocusPolicy.QUIET, presentation))
        }
        // FULL: the user looks at the desktop, it should have the focus.
        assertFalse(DesktopFocusPolicy.returnFocusToPhone(DesktopFocusPolicy.OWN, FULL))
        assertFalse(DesktopFocusPolicy.returnFocusToPhone(DesktopFocusPolicy.SHARED, NONE))
    }

    @Test
    fun keysReachASharedFocusDesktopOnlyAfterFocusingIt() {
        assertTrue(DesktopFocusPolicy.focusDesktopForKeys(DesktopFocusPolicy.SHARED))
        assertFalse(DesktopFocusPolicy.focusDesktopForKeys(DesktopFocusPolicy.OWN))
        assertFalse(DesktopFocusPolicy.focusDesktopForKeys(DesktopFocusPolicy.QUIET))
        assertFalse(DesktopFocusPolicy.focusDesktopForKeys(null))
        assertTrue(DesktopFocusPolicy.focusSafe(DesktopFocusPolicy.QUIET))
        assertFalse(DesktopFocusPolicy.focusSafe(DesktopFocusPolicy.OWN))
    }

    @Test
    fun anAppOpenOnThePhoneIsNeverPulledOntoTheBackgroundDesktop() {
        val phone = listOf(
            DesktopLaunchPolicy.PhoneTask("com.whatsapp", visible = true),
            DesktopLaunchPolicy.PhoneTask("com.android.chrome", visible = false),
        )
        for (presentation in listOf(MINI, HIDDEN)) {
            val refusal = DesktopLaunchPolicy.phoneConflict("com.whatsapp", phone, presentation, label = "WhatsApp")
            assertNotNull(refusal)
            assertTrue(refusal!!.startsWith("WhatsApp is open on the phone"))
            // A task the user is not looking at may move; other apps are fine.
            assertNull(DesktopLaunchPolicy.phoneConflict("com.android.chrome", phone, presentation))
            assertNull(DesktopLaunchPolicy.phoneConflict("org.mozilla.firefox", phone, presentation))
        }
        // FULL covers the phone: nothing in use there.
        assertNull(DesktopLaunchPolicy.phoneConflict("com.whatsapp", phone, FULL))
        assertNull(DesktopLaunchPolicy.phoneConflict(null, phone, MINI))
        assertTrue(DesktopLaunchPolicy.phoneConflict("com.whatsapp", phone, MINI)!!.startsWith("com.whatsapp is open"))
    }

    @Test
    fun typingNeverFallsBackToTheClipboard() {
        assertTrue(DesktopTyping.keyTypeable("Hello, world! ~{}[]\n\t"))
        assertNull(DesktopTyping.refusal("Hello"))
        for (text in listOf("Müller", "日本語", "emoji 😀")) {
            assertFalse(DesktopTyping.keyTypeable(text))
            val refusal = DesktopTyping.refusal(text)!!
            assertTrue(refusal, refusal.contains("never uses the phone's clipboard"))
        }
        assertTrue(DesktopTyping.refusal("Müller")!!.contains("\"ü\""))
        assertTrue(DesktopTyping.refusal("emoji 😀")!!.contains("\"😀\""))
        // The default host extra reports the same reason.
        val extras = object : DesktopHostExtras {
            override fun setMiniStatus(text: String?) = Unit
            override fun setMiniShape(shape: String) = false
            override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
            override fun usePrivilegeProvider(provider: String) = false
            override fun desktopAccessibilityComponent(): String? = null
        }
        assertEquals(DesktopTyping.refusal("Grüße"), extras.typingRefusal("Grüße"))
        assertNull(extras.launchRefusal("com.whatsapp"))
        assertNull(extras.desktopFocusMode())
    }

    @Test
    fun homeIsALaunchOnTheDesktopNeverTheGlobalHomeKey() {
        assertEquals(DesktopGlobalActions.Route.HOME_ACTIVITY_ON_DESKTOP, DesktopGlobalActions.route("home"))
        assertEquals(DesktopGlobalActions.Route.HOME_ACTIVITY_ON_DESKTOP, DesktopGlobalActions.route(" HOME "))
        assertEquals(DesktopGlobalActions.Route.BACK_KEY_ON_DESKTOP, DesktopGlobalActions.route("back"))
        assertEquals(DesktopGlobalActions.Route.DESKTOP_RECENTS, DesktopGlobalActions.route("recents"))
        assertEquals(DesktopGlobalActions.Route.UNSUPPORTED, DesktopGlobalActions.route("power"))
        // The key fallback for "home" is routed the same way, never injected.
        assertTrue(DesktopGlobalActions.keyIsDesktopHome(android.view.KeyEvent.KEYCODE_HOME))
        assertFalse(DesktopGlobalActions.keyIsDesktopHome(android.view.KeyEvent.KEYCODE_BACK))
        assertFalse(DesktopGlobalActions.keyIsDesktopHome(android.view.KeyEvent.KEYCODE_H))
    }

    /** Stand-in for a binder proxy bound to one runtime instance. */
    private class Channel(val generation: Int, private val alive: () -> Int) {
        fun send(): Pair<Boolean, Throwable?> =
            if (alive() == generation) true to null else false to IllegalStateException("DeadObjectException")
    }

    @Test
    fun aDeadRuntimeBinderIsReplacedByAFreshProxy() {
        var runtime = 1
        var connects = 0
        val channels = Reconnecting { connects++; Channel(runtime) { runtime } }
        val broken = { result: Pair<Boolean, Throwable?> -> !result.first && result.second != null }
        assertTrue(channels.call(broken) { it.send() }.first)
        assertTrue(channels.call(broken) { it.send() }.first)
        assertEquals(1, connects)
        // The embedded runtime reconnects (Wi-Fi change, adbd restart): the cached proxy is invalid.
        runtime = 2
        assertTrue(channels.call(broken) { it.send() }.first)
        assertEquals(2, connects)
        assertEquals(2, channels.get().generation)
        // A plain refusal (no failure) is not a broken connection: no reconnect, no second send.
        var sends = 0
        val refused = channels.call(broken) { sends++; false to null }
        assertFalse(refused.first)
        assertEquals(1, sends)
        assertEquals(2, connects)
    }

    @Test
    fun invalidatingAnOlderConnectionKeepsTheCurrentOne() {
        var connects = 0
        val channels = Reconnecting { ++connects }
        val first = channels.get()
        channels.invalidate(first)
        val second = channels.get()
        assertEquals(2, second)
        channels.invalidate(first) // stale: ignored
        assertEquals(2, channels.get())
        assertEquals(2, connects)
    }
}
