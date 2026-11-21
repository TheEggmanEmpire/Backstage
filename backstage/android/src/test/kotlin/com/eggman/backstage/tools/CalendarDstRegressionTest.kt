package com.eggman.backstage.tools

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarDstRegressionTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun span(start: String, end: String) = Cal.span(Args(JSONObject().put("start", start).put("end", end)), berlin)

    @Test fun springDstAllDaySpanRetainsTwoCalendarDays() {
        val s = span("2026-03-28", "2026-03-30")
        assertTrue(s.allDay)
        assertEquals(Instant.parse("2026-03-28T00:00:00Z").toEpochMilli(), s.startMs)
        assertEquals(Instant.parse("2026-03-30T00:00:00Z").toEpochMilli(), s.endMs)
        assertEquals(2 * 86400000L, s.endMs - s.startMs)
    }

    @Test fun autumnDstAllDaySpanAlsoRetainsTwoDays() {
        val s = span("2026-10-24", "2026-10-26")
        assertTrue(s.allDay)
        assertEquals(2 * 86400000L, s.endMs - s.startMs)
    }

    @Test fun timedEventsKeepActualElapsedTimeAcrossDst() {
        val s = span("2026-03-28T00:00", "2026-03-30T00:00")
        assertFalse(s.allDay)
        assertEquals(47 * 3600000L, s.endMs - s.startMs)
    }
}
