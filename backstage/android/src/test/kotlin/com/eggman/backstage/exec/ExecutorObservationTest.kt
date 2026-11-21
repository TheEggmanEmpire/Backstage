package com.eggman.backstage.exec

import com.eggman.backstage.testutil.FakeDesktopHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**  observers must not replace the listing a running task resolves indices against. */
class ExecutorObservationTest {
    @Test fun observeDoesNotReplaceTheRememberedListing() = runBlocking {
        val host = FakeDesktopHost()
        val exec = DesktopExecutor(host, host.geometry()!!, setOf("com.eggman.backstage"))
        val first = exec.screen()
        val firstCapture = exec.lastCapture
        val (observed, capture) = exec.observe()
        assertSame(first, exec.lastScreen)
        assertSame(firstCapture, exec.lastCapture)
        assertNotSame(first, observed)
        assertNotSame(firstCapture, capture)
        // screen itself still remembers what it read
        val second = exec.screen()
        assertSame(second, exec.lastScreen)
    }
}
