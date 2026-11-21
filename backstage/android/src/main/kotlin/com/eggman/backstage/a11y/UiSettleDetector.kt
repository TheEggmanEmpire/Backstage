package com.eggman.backstage.a11y

import kotlinx.coroutines.delay

/**
 * Decides when the UI has "settled" after an action: no window/content change events for
 * [quietMs], bounded by a hard cap so animated or live-updating apps (video, timers) cannot
 * stall the agent. Fed by the accessibility event stream; the clock is injectable for tests.
 */
class UiSettleDetector(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile var lastEventAtMs: Long = Long.MIN_VALUE
        private set
    @Volatile var eventCount: Long = 0
        private set

    private data class Event(val number: Long, val at: Long, val window: Boolean, val source: String?)
    private val recent = ArrayDeque<Event>()

    @Synchronized fun onEvent(windowChange: Boolean = true, source: String? = null) {
        lastEventAtMs = clock()
        eventCount++
        recent.addLast(Event(eventCount, lastEventAtMs, windowChange, source))
        if (recent.size > 512) recent.removeFirst()
    }

    @Synchronized private fun mark(): Pair<Long, Long> = clock() to eventCount

    @Synchronized private fun eventsAfter(number: Long): List<Event> = recent.filter { it.number > number }

    data class Outcome(val changed: Boolean, val settled: Boolean, val waitedMs: Long)

    /**
     * First wait for a reaction, then for quiet. All state and time are local to this wait;
     * old events cannot manufacture a reaction. A continuous single-source content ticker gets
     * 1500 ms to postpone quiet; window changes and a different source always restart it.
   */
    suspend fun awaitOutcome(quietMs: Long = 400, maxMs: Long = 3_000, expectChangeMs: Long = 500, pollMs: Long = 10): Outcome {
        val (start, startCount) = mark()
        var seen = startCount
        val cap = maxMs.coerceAtLeast(0)
        val expect = expectChangeMs.coerceIn(0, cap)
        val quiet = quietMs.coerceAtLeast(0)
        var changed = false
        var quietAt = start
        var contentStart: Long? = null
        var contentLast = Long.MIN_VALUE
        var contentSource: String? = null
        while (true) {
            val now = clock()
            for (event in eventsAfter(seen)) {
                seen = event.number
                if (!changed && event.at - start > expect) continue
                changed = true
                if (event.window) {
                    quietAt = event.at
                    contentStart = null
                } else {
                    if (contentStart == null || contentSource != event.source || event.at - contentLast >= quiet) {
                        contentStart = event.at
                    }
                    contentSource = event.source
                    contentLast = event.at
                    if (event.at - contentStart!! <= 1_500) quietAt = event.at
                }
            }
            val elapsed = (now - start).coerceAtLeast(0)
            if (!changed && elapsed >= expect) return Outcome(false, true, elapsed)
            if (changed && now - quietAt >= quiet) return Outcome(true, true, elapsed)
            if (elapsed >= cap) return Outcome(changed, false, elapsed)
            val next = if (!changed) expect - elapsed else quiet - (now - quietAt)
            delay(minOf(pollMs.coerceAtLeast(1), cap - elapsed, next.coerceAtLeast(1)))
        }
    }

    data class Result(val settled: Boolean, val waitedMs: Long, val events: Long)

    /**
     * Waits until no event arrived for [quietMs] (measured from the later of "currently" and the last
     * event), but at most [maxMs]. [minMs] gives the app time to start reacting at all.
   */
    suspend fun awaitSettled(quietMs: Long = 400, maxMs: Long = 3_000, minMs: Long = 150, pollMs: Long = 50): Result {
        val start = clock()
        val startCount = eventCount
        if (minMs > 0) delay(minMs)
        while (true) {
            val now = clock()
            val last = lastEventAtMs
            val quietSince = if (last == Long.MIN_VALUE || last < start) start else last
            if (now - quietSince >= quietMs) return Result(true, now - start, eventCount - startCount)
            if (now - start >= maxMs) return Result(false, now - start, eventCount - startCount)
            delay(pollMs.coerceAtMost(maxOf(1, maxMs - (now - start))))
        }
    }
}

/**
 * One [UiSettleDetector] per display: a video playing or the user typing on the phone must not keep
 * resetting the quiet window while the agent waits for the desktop (and vice versa). Events without a
 * display (negative id) count for every display.
 */
class PerDisplaySettle(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val detectors = java.util.concurrent.ConcurrentHashMap<Int, UiSettleDetector>()

    fun forDisplay(displayId: Int): UiSettleDetector = detectors.getOrPut(displayId) { UiSettleDetector(clock) }

    fun onEvent(displayId: Int, windowChange: Boolean = true, source: String? = null) {
        if (displayId < 0) detectors.values.forEach { it.onEvent(windowChange, source) }
        else forDisplay(displayId).onEvent(windowChange, source)
    }
}
