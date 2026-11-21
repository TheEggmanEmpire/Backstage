package com.eggman.backstage.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** the wake word's Vosk model is closed even when the service dies while the model is loading. */
class OwnedResourceTest {

    private class Native(val name: String) {
        var closed = 0
    }

    @Test fun keptValueIsClosedOnceByClose() {
        val r = OwnedResource<Native> { it.closed++ }
        val a = Native("a")
        assertTrue(r.adopt(a))
        assertSame(a, r.get())
        r.close()
        r.close()
        assertEquals(1, a.closed)
        assertNull(r.get())
    }

    @Test fun adoptAfterCloseClosesTheLateValue() {
        val r = OwnedResource<Native> { it.closed++ }
        r.close()
        val late = Native("late")
        assertFalse(r.adopt(late))
        assertEquals(1, late.closed)
        assertNull(r.get())
    }

    @Test fun replacingAValueClosesTheOldOne() {
        val r = OwnedResource<Native> { it.closed++ }
        val a = Native("a")
        val b = Native("b")
        r.adopt(a)
        r.adopt(b)
        r.adopt(b)
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        r.close()
        assertEquals(1, b.closed)
    }

    /**
     * The service's load path: the model is built on IO while the service is destroyed (scope cancelled, then
     * close). Adoption on the loading thread owns the result even if a cancelled scope never
     * resumes on Main. The model must be closed exactly once however the race goes.
   */
    @Test fun serviceDestroyedDuringLoadDoesNotLeakTheModel() = runBlocking {
        val r = OwnedResource<Native> { it.closed++ }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val constructing = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val built = mutableListOf<Native>()
        var resumed = false
        val job = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    constructing.countDown()
                    finishConstruction.await(5, TimeUnit.SECONDS) // the uninterruptible native constructor
                    Native("model").also { synchronized(built) { built += it } }
                }.map { r.adopt(it) }
            }
            resumed = true
            loaded.getOrThrow()
        }
        assertTrue(constructing.await(5, TimeUnit.SECONDS))
        // onDestroy: close the holder and cancel the scope while the constructor is still running.
        r.close()
        scope.cancel()
        finishConstruction.countDown()
        withTimeout(5_000) { job.join() }
        assertFalse("the cancelled coroutine never resumes after withContext", resumed)
        assertEquals(1, built.size)
        assertEquals("model built after onDestroy is closed", 1, built.single().closed)
        assertNull(r.get())
    }
}
