package com.eggman.backstage.robolectric

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.shell.ShellPage
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.desktop.DesktopSettings
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One app, one launcher icon: Dextop's Home is the app's main screen and the agent is its Agent tab. The agent screen's
 * "Home" tab goes back to it (Dextop's activities are not part of the library, so they are registered here like the
 * merged app declares them).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DesktopSettingsRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val dextopUi get() = ComponentName(app.packageName, DesktopSettings.ACTIVITY)
    private var scenario: ActivityScenario<AgentScreenActivity>? = null

    @Before fun setUp() {
        RoboSupport.resetProcess()
        AgentAccess.override = FakeAgent()
    }

    @After fun tearDown() {
        scenario?.close()
        RoboSupport.resetProcess()
    }

    private val button = hasText(DesktopSettings.LABEL) and hasClickAction()

    private fun withDextopUi() = shadowOf(app.packageManager).addActivityIfNotPresent(dextopUi)

    private fun start() {
        SettingsStores.set(memorySettings().apply { onboardingDone = true })
        scenario = ActivityScenario.launch(Intent(app, AgentScreenActivity::class.java))
        compose.waitForIdle()
    }

    private fun tab(label: String) = compose.onAllNodesWithText(label)[0].performClick().also { compose.waitForIdle() }

    private fun startedFromActivity(): Intent? {
        var started: Intent? = null
        scenario!!.onActivity { a -> started = shadowOf(a).nextStartedActivity }
        return started
    }

    @Test fun theLibraryDeclaresNoLauncher() {
        // The one launcher icon is Dextop's (Home, with the Agent tab); scripts/lib/verify_apk.py checks the merged APK.
        val launchers = app.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0,
        ).map { it.activityInfo.name }
        assertEquals(emptyList<String>(), launchers)
    }

    @Test fun opensDextopsUiExplicitlyAndOnlyWhenItIsPartOfTheApp() {
        assertFalse(DesktopSettings.available(app))
        assertFalse(DesktopSettings.open(app))
        assertNull(shadowOf(app).nextStartedActivity)

        withDextopUi()
        assertTrue(DesktopSettings.available(app))
        assertTrue(DesktopSettings.open(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(dextopUi, started.component)
        assertNull("explicit, never an implicit MAIN/LAUNCHER intent", started.action)
        // Outside an activity (application context) the trampoline needs a task of its own.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun homeTabReturnsToHomeWithoutClosingTheAiTab() {
        val home = ComponentName(app.packageName, com.eggman.backstage.notify.SessionNotification.HOME_ACTIVITY)
        shadowOf(app.packageManager).addActivityIfNotPresent(home)
        AgentAccess.hostOverride = FakeDesktopHost(active = false)
        start()
        tab("Home")
        val started = startedFromActivity()
        assertEquals(home, started?.component)
        // Same task, brought forward as it is (the chat stays alive behind it), no transition.
        assertTrue(started!!.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertFalse(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("home", started.getStringExtra(ShellPage.EXTRA))
    }
    @Test fun linuxAndSettingsReturnToTheirAppPageAndAiTabsStayLocal() {
        val home = ComponentName(app.packageName, com.eggman.backstage.notify.SessionNotification.HOME_ACTIVITY)
        shadowOf(app.packageManager).addActivityIfNotPresent(home)
        start()
        tab("History")
        compose.onNodeWithText("Task history").assertExists()
        assertNull(startedFromActivity())
        for ((label, page) in listOf("Linux" to "linux", "Settings" to "settings")) {
            tab(label)
            val started = startedFromActivity()!!
            assertEquals(home, started.component)
            assertEquals(page, started.getStringExtra(ShellPage.EXTRA))
            assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
            assertFalse(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

}
