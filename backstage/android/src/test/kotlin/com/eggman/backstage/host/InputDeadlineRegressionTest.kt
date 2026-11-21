package com.eggman.backstage.host

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InputDeadlineRegressionTest {
    @Test fun timedOutQueuedInputNeverRunsLater() {
        val lane = Executors.newSingleThreadExecutor()
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val injected = AtomicBoolean(false)
        try {
            lane.submit { occupied.countDown(); release.await() }
            assertTrue(occupied.await(2, TimeUnit.SECONDS))
            assertFalse(DesktopHostInput.run(lane, 50) { injected.set(true); true })
            release.countDown()
            lane.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse("Abandoned Back must never inject later", injected.get())
        } finally { release.countDown(); lane.shutdownNow() }
    }

    @Test fun timedOutRunningInputReportsUnknownRatherThanFalse() {
        val lane = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            assertThrows(DesktopInputOutcomeUnknown::class.java) {
                DesktopHostInput.run(lane, 100) { started.countDown(); release.await(); true }
            }
            assertEquals(0L, started.count)
            release.countDown()
            lane.submit {}.get(2, TimeUnit.SECONDS)
        } finally { release.countDown(); lane.shutdownNow() }
    }

    @Test fun completedRefusalAndFailuresKeepTheirMeaning() {
        val lane = Executors.newSingleThreadExecutor()
        try {
            assertFalse(DesktopHostInput.run(lane, 2000) { false })
            assertTrue(DesktopHostInput.run(lane, 2000) { true })
            val failure = IllegalArgumentException("injection failed")
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
                DesktopHostInput.run(lane, 2000) { throw failure }
            })
        } finally { lane.shutdownNow() }
    }
}
