package com.eggman.backstage.notify

import android.app.Application
import android.app.KeyguardManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import com.eggman.backstage.agent.api.*
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.robolectric.RoboSupport
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.AgentViewModel
import com.eggman.backstage.ui.Fixtures.snapshot
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ApprovalUiRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent
    @Before fun setUp() {
        RoboSupport.resetProcess()
        agent = FakeAgent()
        AgentAccess.override = agent
        SettingsStores.set(memorySettings().apply { onboardingDone = true })
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
    }
    @After fun tearDown() { RoboSupport.resetProcess() }

    @Test fun taskNotificationActionCarriesItsCompleteDisplayedQuestionAndNeedsUnlock() {
        val current = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send this exact message?", true))
        agent.state.value = AgentState(current = current)
        val notification = Notifications.build(app, current)
        val action = notification.actions.first { it.title.toString() == "Approve" }
        assertTrue(action.isAuthenticationRequired)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(current.question!!.text, intent.getStringExtra(Notifications.EXTRA_DISPLAYED_QUESTION))
        val receiver = NotificationActionReceiver()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        receiver.onReceive(app, intent)
        assertTrue(agent.answers.isEmpty())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        receiver.onReceive(app, intent)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
    }
    @Test fun oversizedTaskNotificationOffersNoApprovalOrReplyAction() {
        val current = snapshot(status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("x".repeat(900) + " protected ending", true))
        agent.state.value = AgentState(current = current)
        val notification = Notifications.build(app, current)
        assertFalse(notification.actions.any { it.title.toString() in listOf("Approve", "Reply") })
        val text = notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text.contains("Open Backstage to review"))
    }
    private fun replyToSession(current: TaskSnapshot, text: String) {
        val model = SessionNotificationModel.from(AgentState(current = current), Presentation.HIDDEN)
        val intent = shadowOf(SessionNotification.messageIntent(app, model)).savedIntent
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(SessionNotification.REMOTE_INPUT_KEY).build()),
            intent, Bundle().apply { putCharSequence(SessionNotification.REMOTE_INPUT_KEY, text) })
        NotificationActionReceiver().onReceive(app, intent)
    }
    @Test fun sessionInlineReplyApprovesAShortShownQuestionAndRejectsATruncatedOne() {
        val short = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Approve command?", true))
        agent.state.value = AgentState(current = short)
        replyToSession(short, Answers.APPROVE)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
        agent.answers.clear()
        val long = short.copy(question = PendingQuestion("x".repeat(900) + " protected ending", true))
        agent.state.value = AgentState(current = long)
        replyToSession(long, Answers.APPROVE)
        assertTrue(agent.answers.isEmpty())
    }
    @Test fun viewModelApprovalChecksTheDisplayedSnapshotAndPopupGuard() {
        val question = "x".repeat(1_950) + " exact ending"
        val current = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion(question, true))
        agent.state.value = AgentState(current = current)
        val vm = AgentViewModel(app)
        vm.answer(Answers.APPROVE, current.id, question.take(400))
        assertTrue(agent.answers.isEmpty())
        vm.sendGuard = { "Phone is locked" }
        vm.answer(Answers.APPROVE, current.id, question)
        assertTrue(agent.answers.isEmpty())
        assertEquals("Phone is locked", vm.lastSent.value)
        vm.sendGuard = { null }
        vm.answer(Answers.APPROVE, current.id, question)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
        agent.answers.clear()
        agent.state.value = AgentState(current = current.copy(question = PendingQuestion("Different question", true)))
        vm.answer(Answers.APPROVE, current.id, question)
        assertTrue(agent.answers.isEmpty())
    }
    @Test fun collidingQuestionHashesCannotReplaceAnOldButtonsDisplayedText() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Aa", true))
        val second = first.copy(question = PendingQuestion("BB", true))
        val old = Notifications.build(app, first).actions.first { it.title.toString() == "Approve" }.actionIntent
        val next = Notifications.build(app, second).actions.first { it.title.toString() == "Approve" }.actionIntent
        val oldIntent = shadowOf(old).savedIntent
        assertFalse(oldIntent.filterEquals(shadowOf(next).savedIntent))
        agent.state.value = AgentState(current = second)
        NotificationActionReceiver().onReceive(app, oldIntent)
        assertTrue(agent.answers.isEmpty())
    }
    @Test fun typedApprovalsNeedTheQuestionSnapshotRenderedByTheComposer() {
        val first = snapshot(status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("First action?", true))
        agent.state.value = AgentState(current = first)
        val vm = AgentViewModel(app)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        vm.updateDraft(Answers.APPROVE)
        vm.send()
        vm.answer(Answers.APPROVE)
        vm.send(fromVoice = false, shownTask = first.copy(question = PendingQuestion("First action?", false)))
        assertTrue(agent.answers.isEmpty())
        vm.send(fromVoice = false, shownTask = first)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
        agent.answers.clear()
        val next = first.copy(question = PendingQuestion("Different action?", true))
        agent.state.value = AgentState(current = next)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        vm.updateDraft(Answers.APPROVE)
        vm.send(fromVoice = false, shownTask = first)
        assertTrue(agent.answers.isEmpty())
        assertEquals(Answers.APPROVE, vm.draft.value)
        vm.send(fromVoice = false, shownTask = next)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
    }
}
