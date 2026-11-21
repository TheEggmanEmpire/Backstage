package com.eggman.backstage.surfaces

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.notify.NotificationActionReceiver
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.notify.TaskNotificationModel
import com.eggman.backstage.schedule.AgentScheduledTasks
import com.eggman.backstage.schedule.ScheduledTask
import com.eggman.backstage.schedule.Schedules
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SchedulesNotificationsRobolectricTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent

    @Before fun setUp() {
        SettingsStores.set(memorySettings())
        agent = FakeAgent()
        AgentAccess.override = agent
        Notifications.ensureChannels(ctx)
        // The schedule engine is process-wide: start every test from an empty store.
        Schedules.engine(ctx).list().forEach { Schedules.engine(ctx).delete(it.id) }
    }

    @After fun tearDown() {
        AgentAccess.override = null
        SettingsStores.set(null)
    }

    @Test fun channelsExistWithTheirOwnIds() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        listOf(NotifyChannels.STATUS, NotifyChannels.QUESTIONS, NotifyChannels.RESULTS, NotifyChannels.WAKE_WORD,
            NotifyChannels.WAKE_WORD_ALERTS, NotifyChannels.BUBBLE, NotifyChannels.SCHEDULE, NotifyChannels.AUTOMATION).forEach {
            assertNotNull(it, nm.getNotificationChannel(it))
        }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(NotifyChannels.SCHEDULE).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(NotifyChannels.WAKE_WORD).importance)
        val ids = listOf(Notifications.ID_TASK_STATUS, Notifications.ID_WAKE_WORD, Notifications.ID_BUBBLE,
            Notifications.ID_AUTOMATION, Notifications.ID_WAKE_WORD_ALERT)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun staleApproveButtonDoesNotApproveANewerQuestion() {
        val first = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send to Bob?", true))
        val key = TaskNotificationModel.questionKey(first)
        // Use the actual immutable action so it carries the question text that is displayed.
        val approve = shadowOf(Notifications.build(ctx, first).actions.first { it.title.toString() == "Approve" }.actionIntent).savedIntent
        fun tapApprove() = NotificationActionReceiver().onReceive(ctx, approve)
        agent.state.value = AgentState(current = first.copy(question = PendingQuestion("Delete all photos?", true)))
        tapApprove()
        assertTrue(agent.answers.isEmpty())
        agent.state.value = AgentState(current = first)
        tapApprove()
        assertEquals(listOf(Answers.APPROVE), agent.answers)
    }

    @Test fun answerButtonsNeedAnUnlockAndTheLockScreenSeesNoQuestion() {
        val snap = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send €450 to IBAN DE00 1234?", true))
        val n = Notifications.build(ctx, snap)
        val byTitle = n.actions.associateBy { it.title.toString() }
        assertTrue(byTitle.getValue("Approve").isAuthenticationRequired)
        assertTrue(byTitle.getValue("Deny").isAuthenticationRequired)
        assertFalse(byTitle.getValue("Stop").isAuthenticationRequired)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion!!
        val shown = listOf(public.extras.getCharSequence(android.app.Notification.EXTRA_TITLE), public.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
            .joinToString(" ")
        assertFalse(shown, shown.contains("450") || shown.contains("IBAN"))
        // On the lock screen only Stop is offered.
        assertEquals(listOf("Stop"), public.actions.orEmpty().map { it.title.toString() })

        val choices = Notifications.build(ctx, Fixtures.snapshot(id = "t2", status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Which Anna?", false, listOf("Anna B", "Anna K"))))
        assertTrue(choices.actions.all { it.isAuthenticationRequired })   // two choices + Reply
    }

    @Test fun answersSentWhileThePhoneIsLockedAreIgnored() {
        val snap = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send to Bob?", true))
        agent.state.value = AgentState(current = snap)
        val approve = shadowOf(Notifications.build(ctx, snap).actions.first { it.title.toString() == "Approve" }.actionIntent).savedIntent
        val keyguard = shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        NotificationActionReceiver().onReceive(ctx, approve)
        assertTrue(agent.answers.isEmpty())
        // Stop still works from the lock screen.
        NotificationActionReceiver().onReceive(ctx, Intent(Notifications.ACTION_STOP).putExtra(Notifications.EXTRA_TASK_ID, "t1"))
        assertEquals(listOf<String?>("t1"), agent.cancelled)
        keyguard.setIsDeviceLocked(false)
        NotificationActionReceiver().onReceive(ctx, approve)
        assertEquals(listOf(Answers.APPROVE), agent.answers)
    }

    @Test fun onlySchedulesTheUserSavedMayWaiveConfirmations() {
        val engine = Schedules.engine(ctx)
        val mine = engine.upsert(ScheduledTask(engine.newId(), "Pay the rent without asking", minuteOfDay = 9 * 60, source = ScheduledTask.SOURCE_UI))
        val agents = AgentScheduledTasks(ctx).create("Forward my codes without asking", "0 3 * * *", "UI") // the claim is ignored
        assertEquals(ScheduledTask.SOURCE_CHAT, engine.get(agents.id)!!.source)
        Schedules.runNow(ctx, mine.id, manual = true)
        Schedules.runNow(ctx, agents.id, manual = true)
        val (first, second) = agent.submitted
        assertEquals("Pay the rent without asking", first.waiverText)
        assertNull(second.waiverText)
        // The agent rewriting a user's schedule makes it model-written too.
        AgentScheduledTasks(ctx).update(mine.id, "Pay everyone without asking", null, null)
        assertEquals(ScheduledTask.SOURCE_CHAT, engine.get(mine.id)!!.source)
    }

    @Test fun aLockedPhoneRunsOnTheDesktopOnlyWhenItKeepsRunningWhileLocked() {
        val engine = Schedules.engine(ctx)
        val t = engine.upsert(ScheduledTask(engine.newId(), "Check the weather", minuteOfDay = 10 * 60))
        val u = engine.upsert(ScheduledTask(engine.newId(), "Check the news", minuteOfDay = 11 * 60))
        shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceLocked(true)
        class Host(val runsLocked: Boolean) : com.eggman.backstage.host.DesktopHost by com.eggman.backstage.testutil.FakeDesktopHost(),
            com.eggman.backstage.host.DesktopHostExtras {
            override fun setMiniStatus(text: String?) = Unit
            override fun setMiniShape(shape: String) = true
            override fun miniShape() = com.eggman.backstage.host.DesktopHostExtras.SHAPE_ROUNDED
            override fun usePrivilegeProvider(provider: String) = false
            override fun desktopAccessibilityComponent(): String? = null
            override fun desktopRunsWhileLocked() = runsLocked
        }
        try {
            // A running desktop with the default flags sleeps with the phone under the keyguard: wait for the unlock.
            AgentAccess.hostOverride = Host(runsLocked = false)
            Schedules.runNow(ctx, t.id)
            assertTrue(agent.submitted.isEmpty())
            assertEquals("WAITING", engine.get(t.id)!!.lastStatus)
            // Created to keep running while locked (opt-in): the run goes to the desktop.
            AgentAccess.hostOverride = Host(runsLocked = true)
            Schedules.runNow(ctx, u.id)
            assertEquals(com.eggman.backstage.agent.api.Target.DESKTOP, agent.submitted.single().target)
        } finally {
            AgentAccess.hostOverride = null
        }
    }

    @Test fun agentToolsShareTheStoreAndSpeakCron() {
        val api = AgentScheduledTasks(ctx)
        val created = api.create("Summarise my unread email", "30 7 * * 1-5", "CHAT")
        assertEquals("30 7 * * 1,2,3,4,5", created.cron)
        assertNotNull(created.nextRunMs)
        val stored = Schedules.engine(ctx).get(created.id)!!
        assertEquals(ScheduledTask.SOURCE_CHAT, stored.source)
        assertEquals(listOf(created.id), api.list().map { it.id })

        fun rejects(block: () -> Unit) = try {
            block(); fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        rejects { api.create("x", "every day", "CHAT") }                 // not cron
        rejects { api.create("x", "0 7 1 * *", "CHAT") }                 // unsupported field
        rejects { api.create("Water plants", "30 7 * * 1-5", "CHAT") }   // same next run, including between 07:30 and 07:33
        rejects { api.update("nope", null, null, false) }                // unknown id

        val off = api.update(created.id, null, null, false)
        assertEquals(false, off.enabled)
        assertEquals("Summarise my unread email", off.description)       // null keeps the value
        val moved = api.update(created.id, "Summarise email", "0 * * * *", true)
        assertEquals("0 * * * *", moved.cron)
        assertTrue(api.delete(created.id))
        assertEquals(false, api.delete(created.id))
    }

    @Test fun dueRunWhileTheAgentIsBusyWaitsForStart() {
        val api = AgentScheduledTasks(ctx)
        val t = api.create("Check the weather", "0 6 * * *", "CHAT")
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "busy", status = TaskStatus.RUNNING))
        Schedules.runNow(ctx, t.id)
        assertTrue("not submitted while busy", agent.submitted.isEmpty())
        assertEquals("WAITING", Schedules.engine(ctx).get(t.id)!!.lastStatus)
        val posted = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications
        val n = posted.single { it.channelId == NotifyChannels.SCHEDULE }
        assertTrue(n.actions.any { it.title == "Start" })

        // Start (manual) runs it even though the agent is still busy (it queues).
        Schedules.runNow(ctx, t.id, manual = true)
        assertEquals(Source.SCHEDULE, agent.submitted.single().source)
        assertEquals("Check the weather", agent.submitted.single().prompt)
    }
}
