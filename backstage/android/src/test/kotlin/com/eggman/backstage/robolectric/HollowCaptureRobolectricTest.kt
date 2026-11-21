package com.eggman.backstage.robolectric

import android.graphics.Rect
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class HollowCaptureRobolectricTest {
    private lateinit var service: ServiceController<HostAccessibilityService>
    private lateinit var backend: LocalAccessibilityBackend
    @Before fun setup() { service = A11yTrees.connectService(); backend = LocalAccessibilityBackend(service.get()) }
    @After fun teardown() { service.destroy() }
    private suspend fun captureTime(display: Int = 0): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        backend.capture(display, 1080, 1920)
        (System.nanoTime() - start) / 1_000_000
    }
    @Test fun initiallyHollowScreensReturnWithoutRetry() = runBlocking {
        shadowOf(service.get()).setWindows(emptyList())
        assertTrue(captureTime() < 200)
        val game = A11yTrees.node(bounds = Rect(0, 0, 1080, 1920))
        shadowOf(service.get()).setWindows(listOf(A11yTrees.window(5, game, Rect(0, 0, 1080, 1920))))
        assertTrue(captureTime() < 200)
    }
    @Test fun transitionRetriesOnceForAtMostThreeHundredMilliseconds() = runBlocking {
        shadowOf(service.get()).setWindows(listOf(A11yTrees.MailScreen().window))
        captureTime()
        shadowOf(service.get()).setWindows(emptyList())
        val transition = captureTime()
        assertTrue("transition=$transition", transition in 280..600)
        assertTrue(captureTime() < 200)
    }
    @Test fun captureHistoryIsPerDisplay() = runBlocking {
        shadowOf(service.get()).setWindows(listOf(A11yTrees.MailScreen().window))
        captureTime()
        assertTrue(captureTime(7) < 200)
    }
    @Test fun otpTextStaysVisibleWhilePasswordTextIsMasked() {
        val otp = A11yTrees.node(text = "123456", hint = "Verification code", editable = true, bounds = Rect(0, 100, 500, 200))
        val password = A11yTrees.node(text = "private", editable = true, bounds = Rect(0, 200, 500, 300)).apply { isPassword = true }
        val root = A11yTrees.node(bounds = Rect(0, 0, 1080, 1920), children = listOf(otp, password))
        shadowOf(service.get()).setWindows(listOf(A11yTrees.window(7, root, Rect(0, 0, 1080, 1920))))
        val fields = backend.capture(0, 1080, 1920).windows.single().root!!.children
        assertEquals("123456", fields[0].text)
        assertEquals("••••", fields[1].text)
    }
}
