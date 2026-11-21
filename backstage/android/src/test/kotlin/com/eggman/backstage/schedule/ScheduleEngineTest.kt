package com.eggman.backstage.schedule

import com.eggman.backstage.agent.api.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneOffset

class ScheduleEngineTest {
    private class MemStorage : ScheduleStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class FakeAlarms(var exact: Boolean = true) : AlarmPort {
        val armed = mutableMapOf<String, Pair<Long, Boolean>>()
        override fun canScheduleExact() = exact
        override fun arm(taskId: String, atMs: Long, exact: Boolean) { armed[taskId] = atMs to exact }
        override fun disarm(taskId: String) { armed.remove(taskId) }
    }

    private var now = 1_000_000_000L
    private val storage = MemStorage()
    private val alarms = FakeAlarms()
    private val engine = ScheduleEngine(storage, alarms, clock = { now }, zone = { ZoneOffset.UTC })

    @Test fun upsertArmsExactAlarmAndPersists() {
        val t = engine.upsert(ScheduledTask("a", "Check mail", Target.DESKTOP, repeat = Repeat.Interval(60)))
        assertEquals(now, t.anchorMs)
        assertEquals(now + 3_600_000L, t.nextAtMs)
        assertEquals(now + 3_600_000L to true, alarms.armed["a"])
        assertEquals(listOf(t), ScheduleEngine(storage, alarms, clock = { now }).list())
    }

    @Test fun inexactFallbackWhenExactNotAllowed() {
        alarms.exact = false
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Daily))
        assertFalse(alarms.armed.getValue("a").second)
    }

    @Test fun grantingExactAlarmsRearmsTheExistingInexactSchedule() {
        alarms.exact = false
        engine.upsert(ScheduledTask("play", "Check mail", repeat = Repeat.Interval(60)))
        val at = alarms.armed.getValue("play").first
        assertFalse(alarms.armed.getValue("play").second)
        alarms.exact = true
        assertTrue(engine.rescheduleAll().isEmpty()) // permission changes must not execute a task early
        assertEquals(at to true, alarms.armed.getValue("play"))
        alarms.exact = false
        engine.rescheduleAll()
        assertEquals(at to false, alarms.armed.getValue("play"))
    }

    @Test fun disableAndDelete() {
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Daily))
        engine.setEnabled("a", false)
        assertNull(alarms.armed["a"])
        assertNull(engine.get("a")!!.nextAtMs)
        engine.delete("a")
        assertTrue(engine.list().isEmpty())
    }

    @Test fun onAlarmRecordsRunAndRearmsRecurring() {
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Weekly(DayOfWeek.entries.toSet()), minuteOfDay = 0))
        val firstFire = alarms.armed.getValue("a").first
        now = firstFire
        val ran = engine.onAlarm("a")
        assertNotNull(ran)
        val stored = engine.get("a")!!
        assertEquals(now, stored.lastRunAtMs)
        assertEquals("RUNNING", stored.lastStatus)
        assertEquals(firstFire + 86_400_000L, alarms.armed.getValue("a").first)
    }

    @Test fun onceDisablesItselfAfterRunning() {
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Once, onceAtMs = now + 1000))
        now += 1000
        assertNotNull(engine.onAlarm("a"))
        val stored = engine.get("a")!!
        assertFalse(stored.enabled)
        assertNull(alarms.armed["a"])
    }

    @Test fun duplicateDeliveryRunsOnce() {
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Interval(15)))
        now += 15 * 60_000L
        assertNotNull(engine.onAlarm("a"))
        now += 5_000
        assertNull(engine.onAlarm("a"))
    }

    @Test fun unknownAlarmIsDisarmed() {
        alarms.armed["ghost"] = 1L to true
        assertNull(engine.onAlarm("ghost"))
        assertNull(alarms.armed["ghost"])
    }

    @Test fun rescheduleCatchesUpRecentMissAndMarksOldOnceMissed() {
        engine.upsert(ScheduledTask("recent", "x", repeat = Repeat.Daily, minuteOfDay = 0))
        engine.upsert(ScheduledTask("old", "y", repeat = Repeat.Once, onceAtMs = now + 1000))
        val recentAt = engine.get("recent")!!.nextAtMs!!
        // Phone is off: we are 10 minutes after "recent" but days after "old".
        now = recentAt + 10 * 60_000L
        val due = engine.rescheduleAll()
        assertEquals(listOf("recent"), due.map { it.id })
        val old = engine.get("old")!!
        assertFalse(old.enabled)
        assertEquals("MISSED", old.lastStatus)
        assertTrue(engine.get("recent")!!.nextAtMs!! > now)
    }

    @Test fun manualRunIgnoresEnabledFlagAndAlarm() {
        engine.upsert(ScheduledTask("a", "x", repeat = Repeat.Once, onceAtMs = now + 1000, enabled = false))
        assertNotNull(engine.onManualRun("a"))
        assertEquals("RUNNING", engine.get("a")!!.lastStatus)
        assertNull(engine.onManualRun("a")) // still running within the dedupe window
        engine.recordResult("a", "FAILED", "locked")
        assertNotNull(engine.onManualRun("a")) // run again after a failure
        assertNull(alarms.armed["a"])
        assertNull(engine.onManualRun("missing"))
    }

    @Test fun recordResultTruncates() {
        engine.upsert(ScheduledTask("a", "x"))
        engine.recordResult("a", "SUCCEEDED", "r".repeat(5000))
        val t = engine.get("a")!!
        assertEquals("SUCCEEDED", t.lastStatus)
        assertEquals(2000, t.lastResult!!.length)
        engine.recordResult("missing", "FAILED", null) // no crash
    }

    @Test fun codecRoundTripAllRepeatKinds() {
        val tasks = listOf(
            ScheduledTask("1", "a", Target.PHONE, repeat = Repeat.Once, onceAtMs = 5, nextAtMs = 5, lastStatus = "X", lastResult = "y"),
            ScheduledTask("2", "b", repeat = Repeat.Daily, minuteOfDay = 61),
            ScheduledTask("3", "c", repeat = Repeat.Weekly(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)), enabled = false),
            ScheduledTask("4", "d\nmultiline \"quoted\"", repeat = Repeat.Interval(90), anchorMs = 77, lastRunAtMs = 9),
        )
        assertEquals(tasks, ScheduleCodec.decode(ScheduleCodec.encode(tasks)))
        assertTrue(ScheduleCodec.decode("not json").isEmpty())
        assertTrue(ScheduleCodec.decode(null).isEmpty())
    }
}
