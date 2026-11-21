package com.eggman.backstage.ui.minichat

import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.robolectric.RoboSupport
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.ui.Fixtures.snapshot
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

/** The popup's chat panel: sends like a notification reply, never moves the desktop, keeps the popup clear of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MiniChatRobolectricTest {
    @get:Rule val compose = createEmptyComposeRule()

    /** The desktop host with the extras the panel uses: records the panel's top edge and its listeners. */
    private class PanelHost(val fake: FakeDesktopHost) : DesktopHost by fake, DesktopHostExtras {
        val tops = mutableListOf<Int?>()
        val listeners = mutableListOf<DesktopHost.Listener>()
        override fun setMiniStatus(text: String?) = Unit
        override fun setMiniShape(shape: String) = true
        override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
        override fun usePrivilegeProvider(provider: String) = false
        override fun desktopAccessibilityComponent(): String? = null
        override fun setMiniPanelTop(top: Int?) { tops += top }
        override fun addListener(listener: DesktopHost.Listener) { listeners += listener }
        override fun removeListener(listener: DesktopHost.Listener) { listeners -= listener }
    }

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent
    private lateinit var host: PanelHost
    private var scenario: ActivityScenario<MiniChatActivity>? = null

    @Before fun setUp() {
        RoboSupport.resetProcess()
        agent = FakeAgent()
        host = PanelHost(FakeDesktopHost(active = true).apply { presentation = DesktopHost.Presentation.MINI })
        AgentAccess.override = agent
        AgentAccess.hostOverride = host
        SettingsStores.set(memorySettings().apply { onboardingDone = true; defaultTarget = Target.DESKTOP })
        ShellLifecycle.resetForTests()
        ShellLifecycle.start(app)
    }

    @After fun tearDown() {
        scenario?.close()
        ShellLifecycle.resetForTests()
        RoboSupport.resetProcess()
    }

    private fun open() {
        scenario = ActivityScenario.launch(MiniChatActivity.intent(app).setFlags(0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitForIdle()
    }

    private fun send(text: String) {
        compose.onNodeWithContentDescription("Message to the agent").performTextInput(text)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    @Test fun idleStartsATaskLabelledPopupChat() {
        open()
        send("open the calendar")
        assertEquals(1, agent.submitted.size)
        val request = agent.submitted[0]
        assertEquals("open the calendar", request.prompt)
        // Its own source label ("Popup chat"), the current target (the shared chips) and the user's own words as waiver.
        assertEquals(Source.POPUP_CHAT, request.source)
        assertEquals(Target.DESKTOP, request.target)
        assertEquals("open the calendar", request.waiverText)
        compose.onNodeWithText("Task started.").assertExists()
    }

    @Test fun runningTaskGetsAnInstructionAndAWaitingOneItsAnswer() {
        agent.state.value = AgentState(current = snapshot(id = "t9", step = 13, maxSteps = 80, lastAction = "Tap Compose"))
        open()
        compose.onNodeWithText("Step 13/80 · Tap Compose").assertExists()
        send("use Firefox")
        assertEquals(listOf<Pair<String?, String>>("t9" to "use Firefox"), agent.instructed)
        assertTrue(agent.submitted.isEmpty())
        agent.state.value = AgentState(
            current = snapshot(id = "t9", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which account?", confirm = false)),
        )
        compose.waitForIdle()
        // The status line says what the agent waits for; the conversation shows the question.
        compose.onNodeWithText("Question: Which account?").assertExists()
        // In the conversation and in the answer bar, like the in-app chat.
        compose.onAllNodesWithText("Which account?").assertCountEquals(2)
        send("the work one")
        assertEquals(listOf("the work one"), agent.answers)
    }

    @Test fun lockedPhoneSendsNothingAndSaysUnlockToSend() {
        open()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        send("open the calendar")
        assertTrue(agent.submitted.isEmpty())
        assertTrue(agent.instructed.isEmpty())
        compose.onNodeWithText(MiniChatModel.LOCKED_NOTICE).assertExists()
        // The text stays in the field for after the unlock.
        compose.onNodeWithText("open the calendar").assertExists()
    }

    @Test fun openingThePanelIsNotOpeningTheApp() {
        open()
        scenario!!.onActivity { activity -> assertFalse(ShellLifecycle.countsAsApp(activity)) }
        RoboSupport.waitFor(1_000) { false }
        // The desktop stays the popup: no presentation change, start or stop, and the shell never saw the app.
        assertFalse(ShellLifecycle.visible.value)
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it.startsWith("present") || it.startsWith("start") || it == "stop" })
        // The popup is told where the panel starts, and that it is gone after closing.
        assertTrue(host.tops.toString(), host.tops.any { it != null && it > 0 })
        scenario!!.close()
        scenario = null
        assertNull(host.tops.last())
        RoboSupport.waitFor(1_000) { false }
        assertFalse(ShellLifecycle.visible.value)
        assertTrue(host.fake.calls.none { it.startsWith("present") })
    }

    @Test fun goingFullScreenClosesThePanel() {
        open()
        var panel: MiniChatActivity? = null
        scenario!!.onActivity { panel = it }
        val listener = host.listeners.last() // ShellLifecycle registered first, the panel last
        // The popup moving to the background keeps the panel; full screen (it would sit under it) closes it.
        listener.onDesktopChanged(host.geometry(), DesktopHost.Presentation.HIDDEN)
        RoboSupport.waitFor(300) { false }
        assertFalse(panel!!.isFinishing)
        host.fake.presentation = DesktopHost.Presentation.FULL
        listener.onDesktopChanged(host.geometry(), DesktopHost.Presentation.FULL)
        assertTrue(RoboSupport.waitFor { panel!!.isFinishing || panel!!.isDestroyed })
    }

    @Test fun stopButtonStopsTheAgentButNotTheDesktop() {
        // Idle: no Stop button next to Send.
        open()
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertDoesNotExist()
        // A running task (or a queue): Stop appears, stops the running task and the queue, and leaves the desktop.
        agent.state.value = AgentState(current = snapshot(id = "t3", step = 2, maxSteps = 80), queueSize = 1)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertExists().performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), agent.cancelled)
        compose.onNodeWithText(MiniChatModel.STOPPED_NOTICE).assertExists()
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it == "stop" || it.startsWith("present") })
        assertTrue(host.fake.active)
        // A paused task counts too; a finished one does not.
        agent.state.value = AgentState(current = snapshot(id = "t3", status = TaskStatus.RUNNING).copy(paused = true))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertExists()
        agent.state.value = AgentState(current = snapshot(id = "t3", status = TaskStatus.SUCCEEDED))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertDoesNotExist()
    }

    @Test fun stopNeedsAnUnlockedPhone() {
        open()
        agent.state.value = AgentState(current = snapshot(id = "t4", step = 1, maxSteps = 80))
        compose.waitForIdle()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertExists().performClick()
        compose.waitForIdle()
        assertTrue(agent.cancelled.isEmpty())
        compose.onNodeWithText(MiniChatModel.LOCKED_STOP_NOTICE).assertExists()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), agent.cancelled)
    }

    @Test fun thePanelHasTheInAppChatControlsAndSendsWithTheSameTargetAndPlan() {
        open()
        // The same controls as the in-app chat (shared composables), plus the agent Stop while a task runs.
        for (label in listOf("DeX desktop", "Phone", "Auto", "Plan")) compose.onNodeWithText(label).assertExists()
        for (d in listOf("Voice input", "Send", "Chat history", "New chat", "Message to the agent")) compose.onNodeWithContentDescription(d).assertExists()
        // Phone + Plan: the message goes with exactly these flags.
        val plan = !SettingsStores.get(app).planMode // the Plan chip flips the setting for this message
        compose.onNodeWithText("Phone").performClick()
        compose.onNodeWithText("Plan").performClick()
        send("open my gallery")
        val r = agent.submitted.single()
        assertEquals(Target.PHONE, r.target)
        assertEquals(plan, r.planMode)
        assertEquals(Source.POPUP_CHAT, r.source)
        // One shared target: the chip is persisted, so the notification reply goes to the phone too.
        assertEquals(Target.PHONE, SettingsStores.get(app).defaultTarget)
        com.eggman.backstage.notify.SessionNotification.deliverMessage(app, null, null, "check the weather app")
        assertEquals(Target.PHONE, agent.submitted.last().target)
        assertEquals(Source.NOTIFICATION, agent.submitted.last().source)
        //...unless the message names the other screen.
        send("open youtube on DeX")
        assertEquals(Target.DESKTOP, agent.submitted.last().target)
        // The in-app chat's path gives identical flags for the same choices.
        val vm = com.eggman.backstage.ui.AgentViewModel(app)
        vm.setTarget(Target.PHONE)
        vm.setPlanOverride(plan)
        vm.updateDraft("open my gallery")
        vm.send()
        val inApp = agent.submitted.last()
        assertEquals(r.target, inApp.target)
        assertEquals(r.planMode, inApp.planMode)
        assertEquals(Source.CHAT, inApp.source)
    }

    // ---- the floating bubble opens the same sheet --------------------------------------------

    private fun openFromBubble(peek: Boolean = false, autoClose: Boolean = false) {
        scenario = ActivityScenario.launch(
            MiniChatActivity.bubbleIntent(app, peek = peek, autoClose = autoClose).setFlags(0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitForIdle()
    }

    @Test fun theBubbleOpensTheSameChatWithTheSameControlsLabelledBubble() {
        openFromBubble()
        // Everything the popup chat has (shared composables and view-model)...
        for (label in listOf("DeX desktop", "Phone", "Auto", "Plan")) compose.onNodeWithText(label).assertExists()
        for (d in listOf("Voice input", "Send", "Chat history", "New chat", "Message to the agent", "Close chat")) {
            compose.onNodeWithContentDescription(d).assertExists()
        }
        //...plus the bubble's own "Open Backstage".
        compose.onNodeWithContentDescription(com.eggman.backstage.overlay.OPEN_APP_LABEL).assertExists()
        // The bubble steps aside while a sheet shows.
        assertTrue(MiniChatActivity.showing.value)
        compose.onNodeWithText("Phone").performClick()
        send("open my gallery")
        val r = agent.submitted.single()
        assertEquals(Source.OVERLAY, r.source)
        assertEquals("Bubble", com.eggman.backstage.ui.chat.sourceLabel(r.source))
        assertEquals(Target.PHONE, r.target)
        assertEquals("open my gallery", r.waiverText)
        // The same thread as the in-app chat and the popup chat.
        assertEquals(com.eggman.backstage.data.ChatThreads.get(app).currentId.value, r.attachments[com.eggman.backstage.agent.AgentControllerImpl.THREAD_KEY])
        // Approvals and questions are answered here too; a running task gets the Stop and instructions.
        agent.state.value = AgentState(current = snapshot(id = "t5", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send it?", confirm = true)))
        compose.waitForIdle()
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertEquals(listOf(com.eggman.backstage.agent.api.Answers.APPROVE), agent.answers)
        agent.state.value = AgentState(current = snapshot(id = "t6", step = 2, maxSteps = 80))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(MiniChatModel.STOP_LABEL).assertExists()
        send("use the work account")
        assertEquals(listOf<Pair<String?, String>>("t6" to "use the work account"), agent.instructed)
        scenario!!.close()
        scenario = null
        assertFalse(MiniChatActivity.showing.value)
    }

    @Test fun theBubblesChatIsNotOpeningTheAppAndSharesTheDraft() {
        com.eggman.backstage.ui.ComposerDrafts.get(app).update { it.copy(text = "half-typed task") }
        openFromBubble()
        compose.onNodeWithText("half-typed task").assertExists()
        scenario!!.onActivity { activity -> assertFalse(ShellLifecycle.countsAsApp(activity)) }
        RoboSupport.waitFor(1_000) { false }
        assertFalse(ShellLifecycle.visible.value)
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it.startsWith("present") || it.startsWith("start") || it == "stop" })
    }

    @Test fun theBubblesChatStaysWhenTheDesktopGoesIntoTheAppButNotFullScreen() {
        val before = host.listeners.size // ShellLifecycle's
        openFromBubble()
        var panel: MiniChatActivity? = null
        scenario!!.onActivity { panel = it }
        // The sheet's own listeners (the panel and its desktop switches).
        val sheet = host.listeners.drop(before)
        host.fake.presentation = DesktopHost.Presentation.IN_APP
        sheet.forEach { it.onDesktopChanged(host.geometry(), DesktopHost.Presentation.IN_APP) }
        RoboSupport.waitFor(300) { false }
        assertFalse(panel!!.isFinishing)
        host.fake.presentation = DesktopHost.Presentation.FULL
        sheet.forEach { it.onDesktopChanged(host.geometry(), DesktopHost.Presentation.FULL) }
        assertTrue(RoboSupport.waitFor { panel!!.isFinishing || panel!!.isDestroyed })
    }

    @Test fun aResultTheBubbleOpensClosesItselfUnlessTouched() {
        agent.state.value = AgentState(current = snapshot(id = "t7", status = TaskStatus.SUCCEEDED, result = "Done: 3 unread"))
        openFromBubble(peek = true, autoClose = true)
        var panel: MiniChatActivity? = null
        scenario!!.onActivity { panel = it }
        assertFalse(panel!!.isFinishing)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MiniChatModel.PEEK_CLOSE_MS + 500))
        assertTrue(panel!!.isFinishing || panel!!.isDestroyed)
        // Typed into: it stays.
        scenario!!.close()
        openFromBubble(peek = true, autoClose = true)
        scenario!!.onActivity { panel = it }
        compose.onNodeWithContentDescription("Message to the agent").performTextInput("and then")
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(MiniChatModel.PEEK_CLOSE_MS + 500))
        assertFalse(panel!!.isFinishing)
    }

    // ---- the bubble's chat shows the DeX popup with a DeX/Auto target, never with the Phone target -------

    @Test fun theBubblesChatBringsABackgroundDesktopUpAsThePopup() {
        host.fake.presentation = DesktopHost.Presentation.HIDDEN
        openFromBubble()
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { "present MINI" in host.fake.calls })
        // The sheet tells the popup where it starts, so the popup sits above it (and above the keyboard).
        assertTrue(host.tops.toString(), host.tops.any { it != null && it > 0 })
    }

    @Test fun theBubblesChatStartsAMissingDesktopAsThePopupWithAuto() {
        SettingsStores.get(app).defaultTarget = Target.AUTO
        host.fake.active = false
        openFromBubble()
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { "start MINI" in host.fake.calls })
    }

    @Test fun withoutShellAccessTheSheetSaysWhyAndStillChats() {
        host.fake.active = false
        host.fake.privileged = false
        openFromBubble()
        compose.onNodeWithText(com.eggman.backstage.shell.ShellPresence.SHEET_NO_ACCESS).assertExists()
        RoboSupport.waitFor(500) { false }
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it.startsWith("start") || it.startsWith("present") })
        send("open the calendar")
        assertEquals(1, agent.submitted.size)
    }

    @Test fun withThePhoneTargetTheBubblesChatNeverShowsOrStartsDex() {
        SettingsStores.get(app).defaultTarget = Target.PHONE
        ShellLifecycle.onTargetChosen(Target.PHONE)
        // Phone mode sent the popup to the background already ; opening the bubble's chat adds nothing.
        assertTrue(RoboSupport.waitFor { "present HIDDEN" in host.fake.calls })
        host.fake.calls.clear()
        openFromBubble()
        RoboSupport.waitFor(500) { false }
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it.startsWith("start") || it.startsWith("present") })
        scenario!!.close()
        host.fake.active = false
        openFromBubble()
        RoboSupport.waitFor(500) { false }
        assertTrue(host.fake.calls.toString(), host.fake.calls.none { it.startsWith("start") || it.startsWith("present") })
    }

    @Test fun whileTheSheetShowsThePhoneChipHidesThePopupAndDexShowsItAgain() {
        // Also with "Pop out when leaving" off: the sheet is the surface on screen.
        SettingsStores.get(app).popOutOnLeave = false
        openFromBubble()
        compose.onNodeWithText("Phone").performClick()
        compose.waitForIdle()
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { "present HIDDEN" in host.fake.calls })
        assertTrue(host.fake.active) // the session is kept
        compose.onNodeWithText("DeX desktop").performClick()
        compose.waitForIdle()
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { host.fake.calls.lastOrNull() == "present MINI" })
    }

    @Test fun whileThePopupsSheetShowsTheAgentSwitchingScreensMovesThePopup() {
        SettingsStores.get(app).popOutOnLeave = false
        open()
        agent.state.value = AgentState(current = snapshot(id = "t8", resolvedTarget = Target.PHONE))
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { "present HIDDEN" in host.fake.calls })
        agent.state.value = AgentState(current = snapshot(id = "t8", resolvedTarget = Target.DESKTOP))
        assertTrue(host.fake.calls.toString(), RoboSupport.waitFor { host.fake.calls.lastOrNull() == "present MINI" })
    }
}
