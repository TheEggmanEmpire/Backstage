package com.eggman.backstage.schedule

import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.schedule.ScheduleRunPolicy.Decision
import com.eggman.backstage.schedule.ScheduleRunPolicy.Inputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Cron alignment, the 5-minute conflict rule, the due-run decision table and cron text for the agent's tools. */
class ScheduleBehaviorTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 2, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(13, 37)

    private fun task(spec: String, id: String = "t") = ScheduleSpec.parse(spec, now, zone).let { p ->
        ScheduledTask(id, "x", repeat = p.repeat, minuteOfDay = p.minuteOfDay, onceAtMs = p.onceAtMs, anchorMs = p.anchorMs)
    }

    private fun next(spec: String) = ScheduleMath.nextFire(task(spec), now, zone)

    @Test fun cronStepsFollowTheClockNotTheCreationTime() {
        assertEquals(at(14, 0), next("*/30 * * * *"))  // :00 and :30, not 14:07
        assertEquals(at(13, 45), next("*/15 * * * *"))
        assertEquals(at(14, 0), next("0 */2 * * *"))    // even hours from midnight, not 15:37
        assertEquals(at(15, 30), next("30 */3 * * *"))  // 00:30, 03:30, ..., 12:30, 15:30
        assertEquals(at(14, 5), next("5 * * * *"))      // hourly at :05
        assertEquals(at(14, 0), next("0 * * * *"))
        // Plain "every" text keeps counting from the moment it is saved.
        assertEquals(0L, task("every 30m").anchorMs)
    }

    @Test fun cronTextForTheAgentTools() {
        assertEquals("30 8 * * *", ScheduleCron.of(task("30 8 * * *"), zone))
        assertEquals("0 7 * * 1,2,3,4,5", ScheduleCron.of(task("0 7 * * 1-5"), zone))
        assertEquals("0 7 * * 0", ScheduleCron.of(task("0 7 * * 0"), zone))
        assertEquals("*/30 * * * *", ScheduleCron.of(task("*/30 * * * *"), zone))
        assertEquals("5 * * * *", ScheduleCron.of(task("5 * * * *"), zone))
        assertEquals("0 */2 * * *", ScheduleCron.of(task("0 */2 * * *"), zone))
        assertEquals(ScheduleCron.ONCE, ScheduleCron.of(task("in 2h"), zone))
        // Round trip through the parser gives the same next run.
        listOf("30 8 * * *", "0 7 * * 1-5", "*/30 * * * *", "5 * * * *", "0 */2 * * *").forEach { c ->
            assertEquals(c, next(c), next(ScheduleCron.of(task(c), zone)!!))
        }
        // An interval saved at an odd minute has no cron form (the tools then show the text form).
        assertNull(ScheduleCron.of(ScheduledTask("i", "x", repeat = Repeat.Interval(45), anchorMs = at(13, 37)), zone))
    }

    @Test fun invalidCronIsAnExplicitError() {
        listOf("* * * * *", "0 7 1 * *", "61 * * * *", "0 25 * * *", "0 7 * * 9").forEach {
            try {
                ScheduleSpec.parse(it, now, zone); fail("expected failure for '$it'")
            } catch (_: ScheduleSpec.SpecException) {
            }
        }
    }

    private class MemStorage : ScheduleStorage {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class NoAlarms : AlarmPort {
        override fun canScheduleExact() = true
        override fun arm(taskId: String, atMs: Long, exact: Boolean) = Unit
        override fun disarm(taskId: String) = Unit
    }

    @Test fun tasksWithinFiveMinutesOfEachOtherConflict() {
        val engine = ScheduleEngine(MemStorage(), NoAlarms(), clock = { now }, zone = { zone })
        engine.upsert(task("0 8 * * *", "a"), checkConflicts = true)
        try {
            engine.upsert(task("4 8 * * *", "b"), checkConflicts = true)
            fail("08:04 is within 5 minutes of 08:00")
        } catch (e: ScheduleEngine.ConflictException) {
            assertEquals("a", e.other.id)
        }
        assertNotNull(engine.upsert(task("5 8 * * *", "c"), checkConflicts = true)) // exactly 5 minutes apart is fine
        // Editing a task never conflicts with itself; a disabled task is ignored.
        engine.upsert(task("0 8 * * *", "a").copy(prompt = "edited"), checkConflicts = true)
        engine.upsert(task("2 8 * * *", "d").copy(enabled = false), checkConflicts = true)
        try {
            engine.setEnabled("d", true)
            fail("enabling d collides with a")
        } catch (_: ScheduleEngine.ConflictException) {
        }
        // Internal re-arming (boot, alarms) never throws.
        engine.upsert(task("2 8 * * *", "e"))
        assertEquals(setOf("a", "c", "d", "e"), engine.list().map { it.id }.toSet())
    }

    @Test fun countdownText() {
        assertEquals("now", ScheduleMath.countdown(now + 20_000, now))
        assertEquals("in 45 min", ScheduleMath.countdown(now + 45 * 60_000L, now))
        assertEquals("in 2 h 5 min", ScheduleMath.countdown(now + 125 * 60_000L, now))
        assertEquals("in 3 h", ScheduleMath.countdown(now + 180 * 60_000L, now))
        assertEquals("in 1 d 2 h", ScheduleMath.countdown(now + 26 * 3_600_000L, now))
    }

    private fun decide(
        available: Boolean = true, locked: Boolean = false, busy: Boolean = false, desktop: Boolean = false,
        target: Target = Target.AUTO, manual: Boolean = false,
    ) = ScheduleRunPolicy.decide(Inputs(available, locked, busy, desktop, target, manual))

    @Test fun dueRunDecisionTable() {
        assertEquals(Decision.UNAVAILABLE, decide(available = false))
        assertEquals(Decision.UNAVAILABLE, decide(available = false, manual = true))
        assertEquals(Decision.RUN, decide())
        assertEquals(Decision.WAIT_FOR_AGENT, decide(busy = true))
        assertEquals(Decision.WAIT_FOR_UNLOCK, decide(locked = true))
        assertEquals(Decision.WAIT_FOR_UNLOCK, decide(locked = true, busy = true))
        // Locked phone + a desktop that keeps running while locked (opt-in display flags): no unlocked screen needed.
        // A desktop with the default flags sleeps with the phone and is keyguarded: the caller passes desktop = false.
        assertEquals(Decision.RUN_ON_DESKTOP, decide(locked = true, desktop = true))
        assertEquals(Decision.RUN_ON_DESKTOP, decide(locked = true, desktop = true, target = Target.DESKTOP))
        assertEquals(Decision.WAIT_FOR_UNLOCK, decide(locked = true, desktop = true, target = Target.PHONE))
        assertEquals(Decision.WAIT_FOR_AGENT, decide(locked = true, desktop = true, busy = true))
        // The user's own tap (Start / Run currently) always runs.
        assertEquals(Decision.RUN, decide(locked = true, busy = true, manual = true))
    }

    @Test fun sourceIsStored() {
        val t = ScheduledTask("s", "x", source = ScheduledTask.SOURCE_CHAT)
        assertEquals(ScheduledTask.SOURCE_CHAT, ScheduleCodec.decode(ScheduleCodec.encode(listOf(t))).single().source)
        // Older stores without the field read as UI.
        val legacy = org.json.JSONObject(ScheduleCodec.encode(listOf(t)))
        legacy.getJSONArray("tasks").getJSONObject(0).remove("source")
        assertTrue(!legacy.toString().contains("source"))
        assertEquals(ScheduledTask.SOURCE_UI, ScheduleCodec.decode(legacy.toString()).single().source)
    }
}
