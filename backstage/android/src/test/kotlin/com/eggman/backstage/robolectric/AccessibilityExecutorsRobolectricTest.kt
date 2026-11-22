package com.eggman.backstage.robolectric

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.exec.DesktopExecutor
import com.eggman.backstage.exec.PhoneExecutor
import com.eggman.backstage.exec.ScreenMarkup
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.robolectric.A11yTrees.node
import com.eggman.backstage.robolectric.A11yTrees.window
import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Both executors reading and acting on real AccessibilityNodeInfo trees through the app's one accessibility service ([AgentAccessibility], fed by a host service like MirrorService)
 * (Robolectric shadows stand in for the system's accessibility connection).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi") // 1080 x 1920 px phone
class AccessibilityExecutorsRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var service: ServiceController<HostAccessibilityService>
    private val own get() = setOf(app.packageName)

    @Before fun setUp() {
        RoboSupport.resetProcess()
        service = A11yTrees.connectService()
    }

    @After fun tearDown() {
        service.destroy()
        RoboSupport.resetProcess()
    }

    private val shadowService get() = shadowOf(service.get())

    /** Our own overlay (bubble) on top and the keyboard: the first is never shown to the model, the second only flagged. */
    private fun overlayAndKeyboard(displayId: Int = 0): List<AccessibilityWindowInfo> = listOf(
        window(20, node("Backstage bubble", bounds = Rect(900, 900, 1060, 1060), pkg = app.packageName, clickable = true),
            Rect(900, 900, 1060, 1060), layer = 9, displayId = displayId),
        window(30, node("q w e r t y", bounds = Rect(0, 1300, 1080, 1920), pkg = "com.example.keyboard"),
            Rect(0, 1300, 1080, 1920), type = AccessibilityWindowInfo.TYPE_INPUT_METHOD, layer = 8, displayId = displayId),
    )

    @Test fun phoneScreenIsSerializedFromTheRealTree() = runBlocking {
        val mail = A11yTrees.MailScreen()
        shadowService.setWindows(listOf(mail.window) + overlayAndKeyboard())
        val exec = PhoneExecutor(app, own)
        assertEquals(1080, exec.width)
        assertEquals(1920, exec.height)
        val s = exec.screen()

        assertEquals("com.example.mail", s.foregroundPackage)
        assertTrue(s.keyboardVisible)
        val labels = s.elements.map { it.label }
        assertTrue(labels.toString(), "Inbox" in labels)
        assertTrue(labels.toString(), "Compose" in labels)
        assertTrue("clickable row takes its child's text", "Anna: Lunch tomorrow?" in labels)
        assertFalse("invisible nodes are dropped", labels.any { "Secret" in it })
        assertFalse("the keyboard hides what lies under it", "Archive" in labels)
        assertTrue(s.coveredElements >= 1)
        assertFalse("our own bubble is never offered as a target", labels.any { "Backstage bubble" in it })
        val fab = s.elements.single { it.label == "Compose" }
        assertEquals("fab", fab.resourceId)
        assertEquals(1080 - 170, fab.centerX)
        assertEquals(1920 - 690, fab.centerY)
        assertNotNull("elements keep their node for actions", fab.handle as? AccessibilityNodeInfo)
        val search = s.elements.single { it.editable }
        assertEquals("Search mail", search.hint)
        assertTrue(s.elements.any { it.scrollable && it.resourceId == "list" })
        assertTrue(s.text, s.text.contains("Button \"Compose\" id=fab"))
        assertNotNull(exec.lastCapture)
        assertSame(s, exec.lastScreen)

        // The structured form handed to MCP clients and the grouped markup come from the same capture.
        assertEquals(s.elements.size, ScreenSerializer.toJson(s).getJSONArray("elements").length())
        val markup = ScreenMarkup.render(exec.lastCapture!!, own)
        assertTrue(markup, markup.contains("<app package=\"com.example.mail\">"))
        assertTrue(markup, markup.contains("Compose"))
        assertFalse(markup, markup.contains("Backstage bubble"))
    }

    @Test fun phoneActsThroughNodeActionsFirst() = runBlocking {
        val mail = A11yTrees.MailScreen()
        shadowService.setWindows(listOf(mail.window))
        val exec = PhoneExecutor(app, own)
        val s = exec.screen()

        val row = s.elements.single { it.label == "Anna: Lunch tomorrow?" }
        assertTrue(exec.clickElement(row, long = false).ok)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), A11yTrees.actions(mail.row))

        val search = s.elements.single { it.editable }
        assertTrue(exec.setText(search, "flight booking").ok)
        val setText = A11yTrees.actionsWithArgs(mail.search).single { it.first == AccessibilityNodeInfo.ACTION_SET_TEXT }
        assertEquals("flight booking", setText.second!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString())

        val list = s.elements.single { it.scrollable }
        assertTrue(exec.scrollElement(list, "down").ok)
        assertTrue(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in A11yTrees.actions(mail.list))

        assertTrue(exec.key("back").ok)
        assertTrue(exec.key("notifications").ok)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
            shadowService.globalActionsPerformed)
    }

    @Test fun clickOnAPlainLabelClimbsToItsClickableParent() = runBlocking {
        val mail = A11yTrees.MailScreen()
        shadowService.setWindows(listOf(mail.window))
        assertTrue(A11yTrees.agent().click(mail.rowLabel))
        assertTrue(A11yTrees.actions(mail.rowLabel).isEmpty())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), A11yTrees.actions(mail.row))
        // Long click: nothing in the chain is long-clickable.
        assertFalse(A11yTrees.agent().click(mail.rowLabel, long = true))
    }

    @Test fun coordinateTapIsAGestureOnTheRightDisplay() = runBlocking {
        shadowService.setWindows(listOf(A11yTrees.MailScreen().window))
        val exec = PhoneExecutor(app, own)
        val tap = async(kotlinx.coroutines.Dispatchers.Default) { exec.tap(540f, 960f) }
        assertTrue(RoboSupport.waitFor { shadowService.gesturesDispatched.isNotEmpty() })
        val g = shadowService.gesturesDispatched.single()
        assertEquals(0, g.description().displayId)
        assertEquals(1, g.description().strokeCount)
        g.callback().onCompleted(g.description())
        assertTrue(tap.await().ok)
    }

    @Test fun withoutTheServiceThePhoneReportsItInsteadOfCrashing() = runBlocking {
        service.destroy()
        assertNull(AgentAccessibility.instance)
        val exec = PhoneExecutor(app, own)
        val s = exec.screen()
        assertTrue(s.elements.isEmpty())
        assertTrue(s.text, s.text.contains("accessibility service disabled"))
        assertFalse(runCatching { exec.tap(1f, 1f) }.isSuccess)
        service = A11yTrees.connectService() // for tearDown
    }

    @Test fun desktopReadsOnlyItsOwnDisplay() = runBlocking {
        val phone = A11yTrees.MailScreen(pkg = "com.example.phoneapp")
        val desk = A11yTrees.MailScreen(pkg = "org.example.desktopmail", displayId = 7, width = 1920, height = 1080)
        shadowService.setWindows(listOf(phone.window))
        shadowService.setWindowsOnDisplay(7, listOf(desk.window) + overlayAndKeyboard(displayId = 7).take(1))
        val host = FakeDesktopHost()
        val exec = DesktopExecutor(host, DesktopHost.Geometry(7, 1920, 1080, 160), own)
        val s = exec.screen()
        assertEquals(7, s.displayId)
        assertEquals("org.example.desktopmail", s.foregroundPackage)
        assertTrue(s.elements.isNotEmpty())
        assertTrue(s.elements.all { it.packageName == "org.example.desktopmail" })
        assertFalse(s.keyboardVisible)
        assertTrue(exec.description, exec.description.contains("presentation mini"))
        assertEquals(listOf(7), A11yTrees.agent().trackedDisplays().filter { it == 7 })
    }

    @Test fun desktopInMiniOrHiddenUsesNodeActionsInFullUsesRealInput() = runBlocking {
        val desk = A11yTrees.MailScreen(pkg = "org.example.desktopmail", displayId = 7, width = 1920, height = 1080)
        shadowService.setWindowsOnDisplay(7, listOf(desk.window))
        val host = FakeDesktopHost().apply { presentation = DesktopHost.Presentation.HIDDEN }
        val exec = DesktopExecutor(host, DesktopHost.Geometry(7, 1920, 1080, 160), own)
        val fab = exec.screen().elements.single { it.label == "Compose" }

        // Hidden: the background desktop must not take the phone's focus, so the node is clicked.
        assertTrue(exec.clickElement(fab, long = false).ok)
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_CLICK), A11yTrees.actions(desk.fab))
        assertTrue(host.calls.none { it.startsWith("tap") })

        // Full screen: injected input first, the node is left alone.
        host.presentation = DesktopHost.Presentation.FULL
        assertTrue(exec.clickElement(fab, long = false).ok)
        if (PLAY_DISTRIBUTION) {
            assertTrue(host.calls.none { it.startsWith("tap") })
            assertTrue(shadowService.gesturesDispatched.any { it.description().displayId == 7 } ||
                A11yTrees.actions(desk.fab).size == 2)
        } else {
            assertEquals("tap ${fab.centerX.toFloat()},${fab.centerY.toFloat()}", host.calls.last())
            assertEquals(1, A11yTrees.actions(desk.fab).size)
        }

        // Text goes through SET_TEXT on the desktop field; keys and launches through the host.
        val search = exec.screen().elements.single { it.editable }
        assertTrue(exec.setText(search, "quarterly report").ok)
        assertTrue(A11yTrees.actions(desk.search).contains(AccessibilityNodeInfo.ACTION_SET_TEXT))
        assertEquals(!PLAY_DISTRIBUTION, exec.key("back").ok)
        if (PLAY_DISTRIBUTION) assertTrue(host.calls.none { it.startsWith("global") })
        else assertEquals("global back", host.calls.last())
        assertTrue(exec.launchApp("org.example.desktopmail", "left", null).ok)
        assertEquals("launchAt org.example.desktopmail left", host.calls.last())
        // Gestures never went to the phone's display.
        assertTrue(shadowService.gesturesDispatched.none { it.description().displayId == 0 })
    }
}
