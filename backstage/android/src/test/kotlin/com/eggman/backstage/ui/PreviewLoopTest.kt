package com.eggman.backstage.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.eggman.backstage.ui.desktop.runPreviewLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** the Desktop tab's live preview captures only while the activity is started. */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewLoopTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun capturesStopWhileStoppedAndResumeWhenStartedAgain() = runTest(dispatcher) {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        var captures = 0
        val frames = mutableListOf<Int>()
        val job = launch { runPreviewLoop(owner.lifecycle, 500, capture = { ++captures }) { frames += it } }
        runCurrent()
        advanceTimeBy(2_100)
        runCurrent()
        val whileVisible = captures
        assertEquals(5, whileVisible) // t = 0, 500, 1000, 1500, 2000

        // Home pressed: the activity is stopped but not destroyed (the process lives on).
        owner.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no capture while nobody can see the preview", whileVisible, captures)

        // Back to the app: the preview refreshes again.
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(whileVisible + 3, captures)
        assertEquals(captures, frames.size)
        job.cancel()
    }

    @Test fun nullFramesAreSkipped() = runTest(dispatcher) {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.STARTED
        val frames = mutableListOf<String>()
        var n = 0
        val job = launch { runPreviewLoop(owner.lifecycle, 100, capture = { if (n++ % 2 == 0) null else "f$n" }) { frames += it } }
        advanceTimeBy(350)
        runCurrent()
        assertEquals(listOf("f2", "f4"), frames)
        job.cancel()
    }
}
