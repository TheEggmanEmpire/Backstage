package com.eggman.backstage.schedule

import com.eggman.backstage.automation.QuickAction
import com.eggman.backstage.automation.QuickActionStore
import com.eggman.backstage.agent.api.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset

class ScheduleSpecTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 2, 10, 0).toInstant(zone).toEpochMilli()

    private fun p(s: String) = ScheduleSpec.parse(s, now, zone)

    @Test fun simpleForms() {
        assertEquals(ScheduleSpec.Parsed(Repeat.Daily, 8 * 60 + 30), p("daily 08:30"))
        assertEquals(ScheduleSpec.Parsed(Repeat.Daily, 21 * 60), p("Daily 9pm"))
        assertEquals(ScheduleSpec.Parsed(Repeat.Daily, 0), p("daily 12am"))
        assertEquals(DayOfWeek.entries.take(5).toSet(), (p("weekdays 7:45").repeat as Repeat.Weekly).days)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), (p("weekends 10:00").repeat as Repeat.Weekly).days)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), (p("weekly mon,wed,fri 09:00").repeat as Repeat.Weekly).days)
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
            (p("weekly fri-mon 09:00").repeat as Repeat.Weekly).days)
        assertEquals(Repeat.Interval(120), p("every 2h").repeat)
        assertEquals(Repeat.Interval(45), p("every 45m").repeat)
        assertEquals(Repeat.Interval(1440), p("every 1d").repeat)
    }

    @Test fun onceForms() {
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 30).toInstant(zone).toEpochMilli(), p("once 2026-11-03 08:30").onceAtMs)
        assertEquals(LocalDateTime.of(2026, 11, 3, 8, 30).toInstant(zone).toEpochMilli(), p("once 2026-11-03T08:30").onceAtMs)
        assertEquals(now + 45 * 60_000L, p("in 45m").onceAtMs)
        assertEquals(now + 2 * 3_600_000L, p("in 2h").onceAtMs)
    }

    @Test fun cronSubset() {
        assertEquals(ScheduleSpec.Parsed(Repeat.Daily, 8 * 60 + 30), p("30 8 * * *"))
        assertEquals(DayOfWeek.entries.take(5).toSet(), (p("0 7 * * 1-5").repeat as Repeat.Weekly).days)
        assertEquals(setOf(DayOfWeek.SUNDAY), (p("0 7 * * 0").repeat as Repeat.Weekly).days)
        assertEquals(Repeat.Interval(15), p("*/15 * * * *").repeat)
        assertEquals(Repeat.Interval(180), p("0 */3 * * *").repeat)
    }

    @Test fun errors() {
        listOf("", "daily", "daily 25:00", "weekly xyz 10:00", "every 0m", "once tomorrow", "0 7 1 * *", "sometimes").forEach {
            try {
                p(it); fail("expected failure for '$it'")
            } catch (_: ScheduleSpec.SpecException) {
            }
        }
    }

    @Test fun formatRoundTrips() {
        listOf("daily 08:30", "weekdays 07:45", "weekends 10:00", "weekly mon,wed 09:00", "every 2h", "every 45m", "every 1d",
            "once 2026-11-03T08:30").forEach { spec ->
            val parsed = p(spec)
            val task = ScheduledTask("x", "p", repeat = parsed.repeat, minuteOfDay = parsed.minuteOfDay, onceAtMs = parsed.onceAtMs)
            assertEquals(spec, ScheduleSpec.format(task, zone))
            assertEquals(parsed, p(ScheduleSpec.format(task, zone)))
        }
    }

    // ---- quick actions (same storage seam) --------------------------------------------------
    private class Mem : ScheduleStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    @Test fun quickActionsCrudAndOrder() {
        val store = QuickActionStore(Mem())
        val a = store.upsert(QuickAction("", "", "  Turn on DND  "))
        assertTrue(a.id.isNotEmpty())
        assertEquals("Turn on DND", a.name)
        val b = store.upsert(QuickAction("", "News", "Read me the news", Target.DESKTOP))
        store.upsert(b.copy(name = "Morning news"))
        assertEquals(listOf("Turn on DND", "Morning news"), store.list().map { it.name })
        store.move(b.id, -1)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })
        store.move(b.id, -1) // no-op at the top
        assertEquals(b.id, store.list().first().id)
        assertEquals(Target.DESKTOP, store.get(b.id)!!.target)
        store.delete(a.id)
        assertEquals(1, store.list().size)
        try {
            store.upsert(QuickAction("", "x", " ")); fail()
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(QuickActionStore.decode("garbage").isEmpty())
    }
}
