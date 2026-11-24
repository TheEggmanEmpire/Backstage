package com.eggman.backstage.robolectric

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.test.core.app.ActivityScenario
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Smoke render of the agent app's screens under Robolectric: each tab composes without crashing and shows its
 * heading. Layout and drawing are not checked (no real rendering on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeSmokeRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent
    private var scenario: ActivityScenario<AgentScreenActivity>? = null

    @Before fun setUp() {
        RoboSupport.resetProcess()
        agent = FakeAgent()
        AgentAccess.override = agent
    }

    @After fun tearDown() {
        scenario?.close()
        RoboSupport.resetProcess()
    }

    private fun start(onboarded: Boolean = true, intent: Intent = Intent(app, AgentScreenActivity::class.java)) {
        SettingsStores.set(memorySettings().apply { onboardingDone = onboarded })
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    private fun tab(label: String) = compose.onAllNodesWithText(label)[0].performClick().also { compose.waitForIdle() }

    @Test fun everyTabRenders() {
        start()
        compose.onNodeWithText("Backstage").assertExists()
        tab("History")
        compose.onNodeWithText("Task history").assertExists()
        // "Home" is not a screen here: it goes back to Backstage's Home (DesktopSettingsRobolectricTest).
        compose.onNodeWithText("Home").assertExists()
        tab("Automate")
        compose.onNodeWithText("Scheduled tasks").assertExists()
        tab("Agent settings")
        compose.onAllNodesWithText("Agent settings").fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        // Setting cards start collapsed: open "Backup & about" first.
        compose.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Backup & about"))).performScrollToNode(hasText("Backup & about"))
        compose.onNodeWithText("Backup & about").performClick()
        compose.onNode(hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(hasText("Open-source licenses"))).performScrollToNode(hasText("Open-source licenses"))
        compose.onNodeWithText("Open-source licenses").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Read the GPL-3.0").assertExists()
    }

    @Test fun firstRunOpensTheSetupWizard() {
        start(onboarded = false)
        compose.onNodeWithText("Welcome to Backstage").assertExists()
    }

    /** : every permission-gated device tool has a request path in the checklist. */
    @Test fun deviceActionItemsRequestTheirAccess() {
        val pm = org.robolectric.Shadows.shadowOf(app.packageManager)
        pm.setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        pm.setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_CALLING, true)
        // Robolectric allows "modify system settings" by default; a fresh install does not have it.
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AppOpsManager::class.java)).setMode(
            android.app.AppOpsManager.OPSTR_WRITE_SETTINGS, android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_ERRORED)
        start()
        tab("Agent settings")
        val about = hasText("Backup & about")
        compose.onNode(hasScrollAction() and hasAnyDescendant(about)).performScrollToNode(about)
        compose.onNodeWithText("Backup & about").performClick()
        val checklist = hasText("Setup checklist")
        compose.onNode(hasScrollAction() and hasAnyDescendant(checklist)).performScrollToNode(checklist)
        compose.onNode(checklist).performClick()
        compose.waitForIdle()
        fun allow(title: String) {
            val button = androidx.compose.ui.test.hasText("Allow") and androidx.compose.ui.test.hasAnySibling(hasText("$title · optional"))
            compose.onNode(hasScrollAction()).performScrollToNode(button)
            compose.onNode(button).performClick()
            compose.waitForIdle()
        }
        fun requested(): List<String> {
            var perms = emptyList<String>()
            scenario!!.onActivity { a -> perms = org.robolectric.Shadows.shadowOf(a).lastRequestedPermission?.requestedPermissions?.toList().orEmpty() }
            return perms
        }
        fun startedAction(): String? {
            var action: String? = null
            scenario!!.onActivity { a -> action = org.robolectric.Shadows.shadowOf(a).nextStartedActivity?.action }
            return action
        }
        allow("Contacts"); assertEquals(listOf(android.Manifest.permission.READ_CONTACTS), requested())
        allow("Send SMS"); assertEquals(listOf(android.Manifest.permission.SEND_SMS), requested())
        allow("Phone calls"); assertEquals(listOf(android.Manifest.permission.CALL_PHONE), requested())
        allow("Location")
        assertEquals(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), requested())
        allow("Modify system settings"); assertEquals(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, startedAction())
        allow("Do Not Disturb access"); assertEquals(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, startedAction())
    }

    @Test fun waitingQuestionShowsApproveAndDeny() {
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Send the message to Anna?", confirm = true)))
        start()
        // Shown in the transcript and in the question bar.
        assertTrue(compose.onAllNodesWithText("Send the message to Anna?", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertEquals(1, agent.answers.size)
        assertTrue(agent.submitted.isEmpty())
    }

    @Test fun deepLinkPrefillIsVisibleButNotSent() {
        start(intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("backstage://agent?prompt=Turn%20on%20do%20not%20disturb"))
            .setClass(app, AgentScreenActivity::class.java))
        compose.onNodeWithText("Turn on do not disturb").assertExists()
        assertTrue(agent.submitted.isEmpty())
    }
}
