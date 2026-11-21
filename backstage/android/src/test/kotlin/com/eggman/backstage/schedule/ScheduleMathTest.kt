package com.eggman.backstage.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ScheduleMathTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = berlin) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant().toEpochMilli()

    private fun task(repeat: Repeat, minute: Int = 8 * 60 + 30, once: Long = 0, anchor: Long = 0, enabled: Boolean = true) =
        ScheduledTask(id = "a", prompt = "p", repeat = repeat, minuteOfDay = minute, onceAtMs = once, anchorMs = anchor, enabled = enabled)

    @Test fun dailyLaterToday() {
        val now = at(2026, 11, 2, 7, 0)
        assertEquals(at(2026, 11, 2, 8, 30), ScheduleMath.nextFire(task(Repeat.Daily), now, berlin))
    }

    @Test fun dailyAlreadyPassedGoesToTomorrow() {
        val now = at(2026, 11, 2, 8, 30) // exactly at fire time -> strictly after
        assertEquals(at(2026, 11, 3, 8, 30), ScheduleMath.nextFire(task(Repeat.Daily), now, berlin))
    }

    @Test fun weeklyPicksNextSelectedDay() {
        // 2026-11-02 is a Monday.
        val now = at(2026, 11, 2, 9, 0)
        val t = task(Repeat.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)))
        assertEquals(at(2026, 11, 5, 8, 30), ScheduleMath.nextFire(t, now, berlin))
    }

    @Test fun weeklySameDayNextWeek() {
        val now = at(2026, 11, 2, 9, 0)
        val t = task(Repeat.Weekly(setOf(DayOfWeek.MONDAY)))
        assertEquals(at(2026, 11, 9, 8, 30), ScheduleMath.nextFire(t, now, berlin))
    }

    @Test fun weeklyWithoutDaysNeverFires() {
        assertNull(ScheduleMath.nextFire(task(Repeat.Weekly(emptySet())), at(2026, 11, 2, 9, 0), berlin))
    }

    @Test fun dstGapMovesForward() {
        // 2026-03-29 02:30 does not exist in Berlin (clocks jump 02:00 -> 03:00).
        val now = at(2026, 3, 29, 0, 0)
        val next = ScheduleMath.nextFire(task(Repeat.Daily, minute = 2 * 60 + 30), now, berlin)!!
        val local = java.time.Instant.ofEpochMilli(next).atZone(berlin)
        assertEquals(29, local.dayOfMonth)
        assertEquals(3, local.hour)
        assertEquals(30, local.minute)
    }

    @Test fun dailyKeepsWallClockAcrossDstEnd() {
        val now = at(2026, 10, 23, 9, 0)
        val next = ScheduleMath.nextFire(task(Repeat.Daily), now, berlin)!!
        val nextNext = ScheduleMath.nextFire(task(Repeat.Daily), next, berlin)!!
        assertEquals(8, java.time.Instant.ofEpochMilli(nextNext).atZone(berlin).hour)
        assertEquals(25 * 3600_000L, nextNext - next) // 24 Oct 08:30 -> 25 Oct 08:30 spans the 25-hour day
    }

    @Test fun onceInFutureAndPast() {
        val now = 1_000_000L
        assertEquals(2_000_000L, ScheduleMath.nextFire(task(Repeat.Once, once = 2_000_000L), now, berlin))
        assertNull(ScheduleMath.nextFire(task(Repeat.Once, once = 500L), now, berlin))
    }

    @Test fun intervalAlignsToAnchor() {
        val anchor = 0L
        val now = 95 * 60_000L
        assertEquals(120 * 60_000L, ScheduleMath.nextFire(task(Repeat.Interval(30), anchor = anchor), now, berlin))
        // Future anchor fires at the anchor.
        assertEquals(10 * 60 * 60_000L, ScheduleMath.nextFire(task(Repeat.Interval(30), anchor = 10 * 60 * 60_000L), now, berlin))
    }

    @Test fun intervalIsClampedToMinimum() {
        val next = ScheduleMath.nextFire(task(Repeat.Interval(1), anchor = 0), 1L, berlin)!!
        assertEquals(ScheduleMath.MIN_INTERVAL_MINUTES * 60_000L, next)
    }

    @Test fun disabledNeverFires() {
        assertNull(ScheduleMath.nextFire(task(Repeat.Daily, enabled = false), 0, berlin))
    }

    @Test fun catchUpOnlyWithinGraceAndIfNotRun() {
        val armed = 10_000_000L
        val t = task(Repeat.Daily).copy(nextAtMs = armed)
        assertTrue(ScheduleMath.shouldCatchUp(t, armed + 60_000))
        assertFalse(ScheduleMath.shouldCatchUp(t, armed + ScheduleMath.DEFAULT_CATCH_UP_GRACE_MS + 1))
        assertFalse(ScheduleMath.shouldCatchUp(t, armed - 1))
        assertFalse(ScheduleMath.shouldCatchUp(t.copy(lastRunAtMs = armed + 5), armed + 60_000))
        assertFalse(ScheduleMath.shouldCatchUp(t.copy(nextAtMs = null), armed + 60_000))
    }

    @Test fun validation() {
        assertEquals(1, ScheduleMath.validate(task(Repeat.Daily).copy(prompt = " "), 0).size)
        assertEquals(1, ScheduleMath.validate(task(Repeat.Once, once = 5), 10).size)
        assertEquals(1, ScheduleMath.validate(task(Repeat.Weekly(emptySet())), 0).size)
        assertEquals(1, ScheduleMath.validate(task(Repeat.Interval(5)), 0).size)
        assertTrue(ScheduleMath.validate(task(Repeat.Daily), 0).isEmpty())
    }

    @Test fun describe() {
        assertEquals("Daily at 08:30", ScheduleMath.describe(task(Repeat.Daily), berlin, Locale.ENGLISH))
        assertEquals("Mon, Fri at 08:30",
            ScheduleMath.describe(task(Repeat.Weekly(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY))), berlin, Locale.ENGLISH))
        assertEquals("Every 2 h", ScheduleMath.describe(task(Repeat.Interval(120)), berlin, Locale.ENGLISH))
        assertEquals("Every 45 min", ScheduleMath.describe(task(Repeat.Interval(45)), berlin, Locale.ENGLISH))
        assertEquals("Every 1 d", ScheduleMath.describe(task(Repeat.Interval(1440)), berlin, Locale.ENGLISH))
    }
}
