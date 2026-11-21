package com.eggman.backstage.ui.chat

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.robolectric.RoboSupport
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ApprovalQuestionRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent
    private var activity: ActivityScenario<AgentScreenActivity>? = null
    @Before fun setUp() {
        RoboSupport.resetProcess()
        agent = FakeAgent()
        AgentAccess.override = agent
        SettingsStores.set(memorySettings().apply { onboardingDone = true })
    }
    @After fun tearDown() { activity?.close(); RoboSupport.resetProcess() }
    @Test fun longApprovalQuestionIsCompleteScrollableAndCanBeApprovedInApp() {
        val question = (1..80).joinToString("\n") { "Command line $it" } + "\nFINAL ACTION: delete the originals"
        assertTrue(question.length in 401..4_000)
        agent.state.value = AgentState(current = snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion(question, true)))
        activity = ActivityScenario.launch(Intent(app, AgentScreenActivity::class.java))
        compose.waitForIdle()
        // The question shows in the chat transcript and in the approval card; the card is the scrollable one.
        val text = compose.onNode(androidx.compose.ui.test.hasText(question) and androidx.compose.ui.test.hasScrollAction())
        assertTrue(text.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
        text.performTouchInput { swipeUp() }
        compose.onNodeWithText("Approve").performClick()
        assertEquals(listOf(Answers.APPROVE), agent.answers)
    }
}
