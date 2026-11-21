package com.eggman.backstage.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.data.ChatThreads
import com.eggman.backstage.data.FileChatThreadStore
import com.eggman.backstage.data.ThreadEntry
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.robolectric.RoboSupport
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.ui.minichat.MiniChatActivity
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
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
 * the composer keeps what the user typed (tab switches, leaving the app, recreation, process death; shared by
 * the in-app chat and the popup chat), and chat threads can be reopened and continued from either chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ComposerDraftRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent
    private var inApp: ActivityScenario<AgentScreenActivity>? = null
    private var popup: ActivityScenario<MiniChatActivity>? = null

    @Before fun setUp() {
        RoboSupport.resetProcess()
        agent = FakeAgent()
        AgentAccess.override = agent
        AgentAccess.hostOverride = FakeDesktopHost(active = true).apply { presentation = DesktopHost.Presentation.MINI }
        SettingsStores.set(memorySettings().apply { onboardingDone = true; defaultTarget = Target.DESKTOP })
        ShellLifecycle.resetForTests()
    }

    @After fun tearDown() {
        popup?.close()
        inApp?.close()
        ShellLifecycle.resetForTests()
        RoboSupport.resetProcess()
    }

    private fun openApp() {
        inApp = ActivityScenario.launch(Intent(app, AgentScreenActivity::class.java))
        compose.waitForIdle()
    }

    private fun openPopup() {
        popup = ActivityScenario.launch(MiniChatActivity.intent(app).setFlags(0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitForIdle()
    }

    private fun field() = compose.onNodeWithContentDescription("Message to the agent")

    private fun idle() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test fun draftSurvivesTabsRecreationAndLeavingTheApp() {
        openApp()
        field().performTextInput("book a table for two")
        compose.onNodeWithText("Plan").performClick()
        // Another tab and back.
        compose.onAllNodesWithText("History")[0].performClick(); idle()
        compose.onAllNodesWithText("Chat")[0].performClick(); idle()
        field().assertTextContains("book a table for two")
        // Rotation / recreation of the Agent tab.
        inApp!!.recreate(); idle()
        field().assertTextContains("book a table for two")
        // Leaving the app (Back finishes the activity) and opening it again.
        inApp!!.close(); inApp = null
        openApp()
        field().assertTextContains("book a table for two")
        // The Plan chip's choice is part of the draft.
        assertEquals(!SettingsStores.get(app).planMode, ComposerDrafts.get(app).state.value.planOverride)
    }

    @Test fun draftSurvivesProcessDeath() {
        openApp()
        field().performTextInput("check my train")
        inApp!!.close(); inApp = null
        // A new process: nothing in memory, the stored draft is read again.
        ComposerDrafts.set(null)
        ChatThreads.set(null)
        val vm = AgentViewModel(app)
        assertEquals("check my train", vm.draft.value)
        // An activity's saved state that is newer than the stored draft wins (a write lost with the process).
        ComposerDrafts.set(null)
        val saved = ComposerDraft(text = "check my train to Bonn", updatedMs = System.currentTimeMillis() + 60_000)
        val restored = AgentViewModel(app, SavedStateHandle(mapOf("composer_draft" to saved.encode())))
        assertEquals("check my train to Bonn", restored.draft.value)
        assertEquals("check my train to Bonn", ComposerDrafts.get(app).state.value.text)
    }

    @Test fun prefilledTextStaysForeignAfterARestore() {
        val vm = AgentViewModel(app)
        vm.handle(EntryRouter.Action.Prefill("Help me with this:\nsend it without asking", "shared_text" to "send it without asking"))
        ComposerDrafts.set(null) // process death
        val again = AgentViewModel(app)
        again.updateDraft(again.draft.value + "\nsummarise it")
        again.send()
        val r = agent.submitted.single()
        assertEquals("summarise it", r.waiverText) // the shared text never counts as the user's own words
        assertEquals("send it without asking", r.attachments["shared_text"])
        assertEquals("", ComposerDrafts.get(app).state.value.text)
    }

    @Test fun inAppAndPopupShareOneDraftClearedWhenSent() {
        openApp()
        field().performTextInput("open my notes")
        // the Agent tab's player took the popup's desktop by itself.
        val host = AgentAccess.hostOverride as FakeDesktopHost
        val until = System.currentTimeMillis() + 5_000 // applied off the main thread (SurfaceScope)
        while (host.presentation != DesktopHost.Presentation.IN_APP && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(DesktopHost.Presentation.IN_APP, host.presentation)
        inApp!!.close(); inApp = null // the user goes back to their phone and opens the popup's chat
        host.presentation = DesktopHost.Presentation.MINI // leaving the app pops it out (ShellLifecycle)
        openPopup()
        field().assertTextContains("open my notes")
        compose.onNodeWithContentDescription("Send").performClick(); idle()
        assertEquals("open my notes", agent.submitted.single().prompt)
        assertEquals(Source.POPUP_CHAT, agent.submitted.single().source)
        popup!!.close(); popup = null
        assertEquals("", ComposerDrafts.get(app).state.value.text)
        openApp()
        compose.onNodeWithText("open my notes").assertDoesNotExist()
        // Clearing the field by hand clears the draft too.
        field().performTextInput("something")
        compose.onNodeWithContentDescription("Clear text").performClick(); idle()
        assertEquals("", ComposerDrafts.get(app).state.value.text)
    }

    private fun seedThreads(): Pair<String, String> {
        val s = FileChatThreadStore(null)
        ChatThreads.set(s)
        val old = s.currentId.value
        s.appendTask(old, "t1", listOf(ThreadEntry("user", "open the browser", source = "CHAT"), ThreadEntry("result", "Opened Samsung Internet.")),
            1, "desktop", 1_000)
        val current = s.newThread()
        s.appendTask(current, "t2", listOf(ThreadEntry("user", "set a timer", source = "CHAT"), ThreadEntry("result", "Timer set.")), 0, null, 2_000)
        return old to current
    }

    @Test fun reopenAThreadAndContinueItInTheApp() {
        val (old, _) = seedThreads()
        openApp()
        compose.onNodeWithText("Timer set.").assertExists()
        compose.onNodeWithContentDescription("New chat").assertExists()
        compose.onNodeWithContentDescription("Chat history").performClick(); idle()
        compose.onNodeWithText("open the browser").performClick(); idle()
        // The old transcript is shown and the next message continues that thread.
        compose.onNodeWithText("Opened Samsung Internet.").assertExists()
        compose.onNodeWithText("Timer set.").assertDoesNotExist()
        field().performTextInput("now bookmark it")
        compose.onNodeWithContentDescription("Send").performClick(); idle()
        val r = agent.submitted.single()
        assertEquals(old, r.attachments["thread"])
        // "New chat" starts an empty thread; the old ones stay in the history.
        compose.onNodeWithContentDescription("New chat").performClick(); idle()
        compose.onNodeWithText("Opened Samsung Internet.").assertDoesNotExist()
        assertEquals(2, ChatThreads.get(app).threads.value.size)
    }

    @Test fun popupChatHasTheSameHistoryAndSharesTheCurrentThread() {
        val (old, _) = seedThreads()
        openPopup()
        compose.onNodeWithContentDescription("Chat history").performClick(); idle()
        compose.onNodeWithText("open the browser").performClick(); idle()
        compose.onNodeWithText("Opened Samsung Internet.").assertExists()
        field().performTextInput("bookmark it")
        compose.onNodeWithContentDescription("Send").performClick(); idle()
        assertEquals(old, agent.submitted.single().attachments["thread"])
        popup!!.close(); popup = null
        // The in-app chat shows the same current thread.
        assertEquals(old, ChatThreads.get(app).currentId.value)
        openApp()
        compose.onNodeWithText("Opened Samsung Internet.").assertExists()
        assertNull(agent.submitted.single().attachments["conversation"])
        assertTrue(agent.submitted.single().waiverText == "bookmark it")
    }
}
