package com.eggman.backstage.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleRegressionTest {
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    private class Storage : ScheduleStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }
    private class Alarms : AlarmPort {
        val armed = mutableMapOf<String, Long>()
        var arms = 0
        override fun canScheduleExact() = true
        override fun arm(taskId: String, atMs: Long, exact: Boolean) { armed[taskId] = atMs; arms++ }
        override fun disarm(taskId: String) { armed.remove(taskId) }
    }

    @Test fun manualRunTwentySecondsBeforeAlarmStillRearmsDailyTask() {
        var now = at("2026-11-14T09:29:00Z")
        val alarms = Alarms()
        val engine = ScheduleEngine(Storage(), alarms, { now }, { ZoneOffset.UTC })
        engine.upsert(ScheduledTask("daily", "Check mail", repeat = Repeat.Daily, minuteOfDay = 570))
        now = at("2026-11-14T09:29:40Z")
        assertNotNull(engine.onManualRun("daily"))
        now = at("2026-11-14T09:30:00Z")
        alarms.armed.remove("daily") // AlarmManager consumes the one-shot delivery.
        assertNull(engine.onAlarm("daily"))
        assertEquals(at("2026-11-15T09:30:00Z"), alarms.armed.getValue("daily"))
        assertEquals(alarms.armed["daily"], engine.get("daily")!!.nextAtMs)
        now = alarms.armed.getValue("daily")
        assertNotNull(engine.onAlarm("daily"))
        assertEquals(at("2026-11-16T09:30:00Z"), alarms.armed.getValue("daily"))
    }

    @Test fun duplicateAlarmDeliveryRearmsEvenWhenSuppressed() {
        var now = at("2026-11-14T09:00:00Z")
        val alarms = Alarms()
        val engine = ScheduleEngine(Storage(), alarms, { now }, { ZoneOffset.UTC })
        engine.upsert(ScheduledTask("repeat", "Check", repeat = Repeat.Interval(30)))
        now += 30 * 60000
        assertNotNull(engine.onAlarm("repeat"))
        val next = alarms.armed.getValue("repeat")
        val count = alarms.arms
        now += 5000
        alarms.armed.remove("repeat")
        assertNull(engine.onAlarm("repeat"))
        assertEquals(next, alarms.armed.getValue("repeat"))
        assertEquals(count + 1, alarms.arms)
    }

    @Test fun rejectsCronStepsThatWouldDriftAcrossHourOrDayBoundaries() {
        val now = at("2026-11-14T09:17:42Z")
        for (spec in listOf("*/45 * * * *", "*/7 * * * *", "*/0 * * * *", "*/61 * * * *")) {
            val error = assertThrows(ScheduleSpec.SpecException::class.java) { ScheduleSpec.parse(spec, now, ZoneOffset.UTC) }
            assertTrue(error.message.orEmpty().contains("divide 60"))
        }
        for (spec in listOf("0 */5 * * *", "0 */0 * * *", "0 */25 * * *")) {
            val error = assertThrows(ScheduleSpec.SpecException::class.java) { ScheduleSpec.parse(spec, now, ZoneOffset.UTC) }
            assertTrue(error.message.orEmpty().contains("divide 24"))
        }
    }

    @Test fun validMinuteStepStartsAtTheLocalHour() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = at("2026-11-14T00:17:42Z")
        val p = ScheduleSpec.parse("*/30 * * * *", now, zone)
        assertEquals(at("2026-11-14T00:00:00Z"), p.anchorMs)
        val task = ScheduledTask("cron", "Check", repeat = p.repeat, anchorMs = p.anchorMs)
        val first = ScheduleMath.nextFire(task, now, zone)!!
        assertEquals(at("2026-11-14T00:30:00Z"), first)
        assertEquals(at("2026-11-14T01:00:00Z"), ScheduleMath.nextFire(task, first, zone))
    }

    @Test fun validHourStepStartsAtLocalMidnight() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = at("2026-11-14T00:17:42Z")
        val p = ScheduleSpec.parse("0 */6 * * *", now, zone)
        assertEquals(at("2026-11-13T15:00:00Z"), p.anchorMs)
        val task = ScheduledTask("cron", "Check", repeat = p.repeat, anchorMs = p.anchorMs)
        assertEquals(at("2026-11-14T03:00:00Z"), ScheduleMath.nextFire(task, now, zone))
    }
}
