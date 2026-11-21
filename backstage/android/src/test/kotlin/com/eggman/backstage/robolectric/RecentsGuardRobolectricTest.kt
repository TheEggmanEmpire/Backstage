package com.eggman.backstage.robolectric

import android.app.Application
import android.graphics.Rect
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.exec.DesktopExecutor
import com.eggman.backstage.exec.SharedRecentsGuard
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.robolectric.A11yTrees.node
import com.eggman.backstage.robolectric.A11yTrees.window
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The recent-apps view on the desktop lists the phone's apps too (One UI shares it): its "Close all" is refused on
 * the desktop, through real AccessibilityNodeInfo trees, by element click, by coordinates and by node action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class RecentsGuardRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var service: ServiceController<HostAccessibilityService>

    @Before fun setUp() {
        RoboSupport.resetProcess()
        service = A11yTrees.connectService()
    }

    @After fun tearDown() {
        service.destroy()
        RoboSupport.resetProcess()
    }

    private val launcher = "com.sec.android.app.launcher"
    private val closeAll = node("Close all", cls = "android.widget.Button", bounds = Rect(860, 960, 1060, 1040), pkg = launcher,
        clickable = true, id = "$launcher:id/clear_all")
    private val card = node("WhatsApp", cls = "android.widget.FrameLayout", bounds = Rect(200, 200, 700, 900), pkg = launcher, clickable = true)
    private val recents = window(30, node(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1920, 1080), pkg = launcher,
        children = listOf(card, closeAll)), Rect(0, 0, 1920, 1080), displayId = 7, title = "Recent apps")

    private fun exec(host: FakeDesktopHost) = DesktopExecutor(host, DesktopHost.Geometry(7, 1920, 1080, 160), setOf(app.packageName))

    @Test fun closeAllInTheDesktopsRecentsIsRefusedEveryWay() = runBlocking {
        shadowOf(service.get()).setWindowsOnDisplay(7, listOf(recents))
        for (presentation in listOf(DesktopHost.Presentation.HIDDEN, DesktopHost.Presentation.FULL)) {
            val host = FakeDesktopHost().apply { this.presentation = presentation }
            val exec = exec(host)
            val button = exec.screen().elements.single { it.label == "Close all" }
            val click = exec.clickElement(button, long = false)
            assertFalse(click.ok)
            assertEquals(SharedRecentsGuard.CLOSE_ALL_REFUSAL, click.message)
            val tap = exec.tap(button.centerX.toFloat(), button.centerY.toFloat())
            assertFalse(tap.ok)
            assertTrue(tap.message.contains("desktop_close_windows"))
            // Swiping a card away is refused too (the window is the recent-apps view).
            assertFalse(exec.swipe(450f, 550f, 450f, 50f, 300).ok)
            assertTrue(host.calls.none { it.startsWith("tap") })
        }
        assertTrue(A11yTrees.actions(closeAll).isEmpty())
        assertTrue(shadowOf(service.get()).gesturesDispatched.isEmpty())
    }

    @Test fun switchingToAnAppFromRecentsStillWorks() = runBlocking {
        shadowOf(service.get()).setWindowsOnDisplay(7, listOf(recents))
        val host = FakeDesktopHost().apply { presentation = DesktopHost.Presentation.FULL }
        val exec = exec(host)
        val whatsapp = exec.screen().elements.single { it.label == "WhatsApp" }
        assertTrue(exec.clickElement(whatsapp, long = false).ok)
        assertEquals("tap ${whatsapp.centerX.toFloat()},${whatsapp.centerY.toFloat()}", host.calls.last())
    }

    @Test fun anAppsOwnCloseAllTabsIsNotBlocked() = runBlocking {
        val chrome = "com.android.chrome"
        val button = node("Close all tabs", cls = "android.widget.Button", bounds = Rect(100, 100, 400, 180), pkg = chrome, clickable = true)
        shadowOf(service.get()).setWindowsOnDisplay(7, listOf(
            window(31, node(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1920, 1080), pkg = chrome, children = listOf(button)),
                Rect(0, 0, 1920, 1080), displayId = 7, title = "Chrome"),
        ))
        val host = FakeDesktopHost().apply { presentation = DesktopHost.Presentation.HIDDEN }
        val exec = exec(host)
        val element = exec.screen().elements.single { it.label == "Close all tabs" }
        assertTrue(exec.clickElement(element, long = false).ok)
        assertEquals(1, A11yTrees.actions(button).size)
        assertTrue(exec.swipe(500f, 500f, 500f, 100f, 300).ok)
    }
}
