package com.eggman.backstage.companion

import com.eggman.backstage.a11y.companion.CallerPolicy
import com.eggman.backstage.a11y.companion.NodeHandles
import com.eggman.backstage.a11y.companion.GesturePoints
import org.junit.Assert.*
import org.junit.Test

class CompanionCoreTest {
    private val cert = "ab".repeat(32)

    @Test fun onlyAllowedPackagesWithExactlyTheCurrentSignerSetAreAccepted() {
        assertTrue(CallerPolicy.allowed(mapOf("com.eggman.backstage.play" to setOf(cert)), setOf(cert)))
        assertFalse(CallerPolicy.allowed(mapOf("com.eggman.backstage" to setOf(cert)), setOf(cert)))
        assertFalse(CallerPolicy.allowed(mapOf("evil" to setOf(cert)), setOf(cert)))
        assertFalse(CallerPolicy.allowed(mapOf("com.eggman.backstage.play" to setOf("cd".repeat(32))), setOf(cert)))
        assertFalse(CallerPolicy.allowed(emptyMap(), setOf(cert)))
        assertFalse(CallerPolicy.allowed(mapOf("com.eggman.backstage.play" to emptySet()), emptySet()))
        // Shared UIDs and multi-signer packages must not turn a permitted name into a confused deputy.
        assertFalse(CallerPolicy.allowed(mapOf("com.eggman.backstage.play" to setOf(cert), "evil" to setOf(cert)), setOf(cert)))
        assertFalse(CallerPolicy.allowed(mapOf("com.eggman.backstage.play" to setOf(cert, "cd".repeat(32))), setOf(cert)))
        assertFalse(CallerPolicy.sameSigners(setOf("not-a-sha256"), setOf("not-a-sha256")))
    }

    @Test fun aNewCaptureInvalidatesEveryOldHandleAndReleasesItsNodes() {
        val released = mutableListOf<String>()
        val handles = NodeHandles<String>(seed = 17, release = { released += it })
        var a = 0L
        var b = 0L
        handles.replace { register -> a = register("first"); b = register("second") }
        assertNotEquals(a, b)
        assertEquals("first", handles.withNode(a) { it })
        var fresh = 0L
        handles.replace { register -> fresh = register("fresh") }
        assertNull(handles.withNode(a) { it })
        assertNull(handles.withNode(b) { it })
        assertNotEquals(a, fresh)
        assertEquals(listOf("first", "second"), released)
        handles.clear()
        assertNull(handles.withNode(fresh) { it })
        assertNull(handles.withNode(0) { it })
    }

    @Test fun anotherSessionCannotResolveAHandleAndAFailedCapturePublishesNothing() {
        val a = NodeHandles<String>(seed = 17)
        val b = NodeHandles<String>(seed = 29)
        val id = a.replace { it("a") }
        b.replace { it("b") }
        assertNull(b.withNode(id) { it })
        var partial = 0L
        runCatching { a.replace { partial = it("partial"); error("capture failed") } }
        assertNull(a.withNode(id) { it })
        assertNull(a.withNode(partial) { it })
    }

    @Test fun revokeDoesNotWaitForAnAccessibilityAction() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        val cleared = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CopyOnWriteArrayList<String>()
        val handles = NodeHandles<String>(seed = 17, release = { released += it })
        val id = handles.replace { it("node") }
        val action = Thread { handles.withNode(id) { entered.countDown(); finish.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
        action.start()
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val revoke = Thread { handles.clear(); cleared.countDown() }
        revoke.start()
        try {
            assertTrue("clear must not wait for app IPC", cleared.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(handles.withNode(id) { it })
            assertTrue("in-flight node must not be recycled", released.isEmpty())
        } finally { finish.countDown(); action.join(2000); revoke.join(2000) }
        assertEquals(listOf("node"), released)
    }

    @Test fun invalidGestureCoordinatesAreRejectedBeforeTheyReachTheAndroidStrokeBuilder() {
        GesturePoints.validate(floatArrayOf(0f, 5f, 6f, 1f, 7f, 8f))
        assertTrue(runCatching { GesturePoints.validate(floatArrayOf(0f, -1f, 6f)) }.isFailure)
        assertTrue(runCatching { GesturePoints.validate(floatArrayOf(0f, 5f, Float.NaN)) }.isFailure)
        assertTrue(runCatching { GesturePoints.validate(floatArrayOf(0f, 5f)) }.isFailure)
    }
}
