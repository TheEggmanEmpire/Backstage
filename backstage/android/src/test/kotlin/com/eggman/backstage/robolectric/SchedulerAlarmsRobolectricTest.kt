package com.eggman.backstage.robolectric

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.schedule.AgentScheduledTasks
import com.eggman.backstage.schedule.Repeat
import com.eggman.backstage.schedule.ScheduleAlarmReceiver
import com.eggman.backstage.schedule.ScheduleBootReceiver
import com.eggman.backstage.schedule.ScheduleCodec
import com.eggman.backstage.schedule.ScheduledTask
import com.eggman.backstage.schedule.Schedules
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Scheduled tasks against the real AlarmManager contract (shadowed): arming, firing, cancelling, boot re-arm. */
@RunWith(RobolectricTestRunner::class)
class SchedulerAlarmsRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent

    @Before fun setUp() {
        RoboSupport.resetProcess()
        SettingsStores.set(memorySettings())
        agent = FakeAgent()
        AgentAccess.override = agent
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun tearDown() = RoboSupport.resetProcess()

    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun alarmFor(id: String) = alarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.getStringExtra(Schedules.EXTRA_ID) == id }

    @Test fun creatingAScheduleArmsOneExactWakeupAlarm() {
        val t = AgentScheduledTasks(app).create("Check the weather", "0 6 * * *", "CHAT")
        val armed = alarmFor(t.id).single()
        assertEquals(AlarmManager.RTC_WAKEUP, armed.type)
        assertEquals(t.nextRunMs, armed.triggerAtTime)
        assertTrue(armed.allowWhileIdle)
        assertTrue(armed.triggerAtTime > System.currentTimeMillis())
        val pi = shadowOf(armed.operation)
        assertTrue(pi.isBroadcast)
        assertTrue(pi.isImmutable)
        val fire = pi.savedIntent
        assertEquals(Schedules.ACTION_FIRE, fire.action)
        assertEquals(ComponentName(app, ScheduleAlarmReceiver::class.java), fire.component)
        assertEquals("backstage-schedule://task/${t.id}", fire.dataString)
        // The alarm target is private: only AlarmManager (through our PendingIntent) can reach it.
        assertFalse(app.packageManager.getReceiverInfo(ComponentName(app, ScheduleAlarmReceiver::class.java), 0).exported)
    }

    @Test fun withoutExactAlarmPermissionItFallsBackToAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(Schedules.canScheduleExact(app))
        val t = AgentScheduledTasks(app).create("Water the plants", "30 18 * * *", "CHAT")
        val armed = alarmFor(t.id).single()
        assertEquals(t.nextRunMs, armed.triggerAtTime)
        assertTrue(armed.allowWhileIdle)
    }

    @Test fun disablingOrDeletingCancelsTheAlarm() {
        val api = AgentScheduledTasks(app)
        val a = api.create("Morning summary", "0 7 * * *", "CHAT")
        val b = api.create("Evening summary", "0 21 * * *", "CHAT")
        assertEquals(1, alarmFor(a.id).size)
        api.update(a.id, null, null, false)
        assertTrue(alarmFor(a.id).isEmpty())
        api.update(a.id, null, null, true)
        assertEquals(1, alarmFor(a.id).size)
        assertTrue(api.delete(a.id))
        assertTrue(alarmFor(a.id).isEmpty())
        assertEquals("the other schedule keeps its alarm", 1, alarmFor(b.id).size)
    }

    @Test fun firedAlarmRunsTheTaskAndArmsTheNextOccurrence() {
        val t = AgentScheduledTasks(app).create("Summarise my unread email", "15 8 * * *", "CHAT")
        val first = alarmFor(t.id).single()
        alarms.fireAlarm(first)
        assertTrue(RoboSupport.waitFor { agent.submitted.isNotEmpty() })
        val r = agent.submitted.single()
        assertEquals("Summarise my unread email", r.prompt)
        assertEquals(Source.SCHEDULE, r.source)
        assertFalse("scheduled runs never wait for a user", r.interactive)
        assertEquals(t.id, r.attachments["schedule_id"])
        // The next occurrence is armed again (the test fires early, so it is the same wall-clock slot), and the
        // result is recorded once the task ends.
        val next = alarmFor(t.id).single()
        assertTrue(next.triggerAtTime >= first.triggerAtTime)
        assertTrue(RoboSupport.waitFor { Schedules.engine(app).get(t.id)?.lastStatus == TaskStatus.SUCCEEDED.name })
        assertEquals("done", Schedules.engine(app).get(t.id)?.lastResult)
    }

    @Test fun duplicateDeliveryRunsOnce() {
        val t = AgentScheduledTasks(app).create("Back up photos", "0 2 * * *", "CHAT")
        val fire = shadowOf(alarmFor(t.id).single().operation).savedIntent
        app.sendBroadcast(Intent(fire))
        app.sendBroadcast(Intent(fire))
        RoboSupport.waitFor(timeoutMs = 1_000) { false }
        assertEquals(1, agent.submitted.size)
    }

    @Test fun staleAlarmForADeletedScheduleDoesNothing() {
        val api = AgentScheduledTasks(app)
        val t = api.create("Old task", "0 9 * * *", "CHAT")
        val fire = shadowOf(alarmFor(t.id).single().operation).savedIntent
        api.delete(t.id)
        app.sendBroadcast(Intent(fire))
        RoboSupport.waitFor(timeoutMs = 500) { false }
        assertTrue(agent.submitted.isEmpty())
    }

    @Test fun busyAgentDefersTheRunToAWaitingNotification() {
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "busy", status = TaskStatus.RUNNING))
        val t = AgentScheduledTasks(app).create("Check the stock price", "0 12 * * *", "CHAT")
        alarms.fireAlarm(alarmFor(t.id).single())
        assertTrue(RoboSupport.waitFor { Schedules.engine(app).get(t.id)?.lastStatus == "WAITING" })
        assertTrue(agent.submitted.isEmpty())
    }

    private fun storeRaw(vararg tasks: ScheduledTask) {
        app.getSharedPreferences("backstage_schedules", Context.MODE_PRIVATE).edit().putString("tasks", ScheduleCodec.encode(tasks.toList())).commit()
    }

    @Test fun bootReArmsAlarmsAndCatchesUpARecentlyMissedRun() {
        val now = System.currentTimeMillis()
        storeRaw(
            // Due ten minutes ago while the phone is off: runs once at boot.
            ScheduledTask("missed", "Read me the news", repeat = Repeat.Daily, minuteOfDay = 6 * 60, nextAtMs = now - 10 * 60_000, createdAtMs = 1),
            // Not due yet: only re-armed.
            ScheduledTask("later", "Plan my evening", repeat = Repeat.Daily, minuteOfDay = 20 * 60, nextAtMs = now + 3_600_000, createdAtMs = 2),
            // Disabled: nothing.
            ScheduledTask("off", "Never", enabled = false, nextAtMs = now - 60_000, createdAtMs = 3),
        )
        assertTrue(alarms.scheduledAlarms.isEmpty())
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        assertTrue(RoboSupport.waitFor { alarmFor("missed").isNotEmpty() && alarmFor("later").isNotEmpty() })
        assertTrue(alarmFor("off").isEmpty())
        assertTrue(RoboSupport.waitFor { agent.submitted.isNotEmpty() })
        assertEquals(listOf("Read me the news"), agent.submitted.map { it.prompt })
        assertNotNull(Schedules.engine(app).get("missed")?.lastRunAtMs)
    }

    @Test fun clockAndTimezoneChangesReArm() {
        storeRaw(ScheduledTask("t", "Stretch", repeat = Repeat.Daily, minuteOfDay = 10 * 60, nextAtMs = System.currentTimeMillis() + 60_000, createdAtMs = 1))
        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).setPackage(app.packageName))
        assertTrue(RoboSupport.waitFor { alarmFor("t").isNotEmpty() })
    }

    @Test fun bootReceiverIgnoresForeignActions() {
        storeRaw(ScheduledTask("t", "Stretch", nextAtMs = System.currentTimeMillis() - 60_000, createdAtMs = 1))
        // Exported for the system broadcasts; an app sending it something else gets no work done.
        app.sendBroadcast(Intent("com.evil.RESCHEDULE").setComponent(ComponentName(app, ScheduleBootReceiver::class.java)))
        RoboSupport.waitFor(timeoutMs = 500) { false }
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(agent.submitted.isEmpty())
        val info = app.packageManager.getReceiverInfo(ComponentName(app, ScheduleBootReceiver::class.java), 0)
        assertTrue(info.exported)
    }
}
