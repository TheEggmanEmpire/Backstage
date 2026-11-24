package com.eggman.backstage.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.eggman.backstage.host.DesktopHost.Presentation

class DesktopHostSupportTest {

    @Test
    fun presentationNamesRoundTrip() {
        DesktopHost.Presentation.values().forEach { presentation ->
            assertEquals(presentation, PresentationNames.parse(PresentationNames.wireName(presentation)))
        }
    }

    @Test
    fun presentationParsingIsLenient() {
        assertEquals(DesktopHost.Presentation.MINI, PresentationNames.parse(" MINI "))
        assertEquals(DesktopHost.Presentation.NONE, PresentationNames.parse(null))
        assertEquals(DesktopHost.Presentation.NONE, PresentationNames.parse("pip"))
    }

    @Test
    fun backgroundStartCompletesWhenAPreviewAlreadyTookTheDesktop() {
        assertTrue(desktopStartPresentationReached(Presentation.HIDDEN, Presentation.HIDDEN))
        assertTrue(desktopStartPresentationReached(Presentation.HIDDEN, Presentation.IN_APP))
        assertFalse(desktopStartPresentationReached(Presentation.HIDDEN, Presentation.FULL))
        assertFalse(desktopStartPresentationReached(Presentation.MINI, Presentation.IN_APP))
    }

    @Test
    fun base64PayloadIgnoresLineWrapping() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val wrapped = encoded.chunked(4).joinToString("\n") + "\n"
        val decoded = ShellPayloads.decodeBase64(wrapped)
        assertArrayEquals(bytes, decoded)
        assertTrue(ShellPayloads.isPng(decoded))
    }

    @Test
    fun base64PayloadRejectsGarbage() {
        assertNull(ShellPayloads.decodeBase64(""))
        assertNull(ShellPayloads.decodeBase64("   \n"))
        assertNull(ShellPayloads.decodeBase64("screencap: error!!"))
        assertFalse(ShellPayloads.isPng(byteArrayOf(1, 2, 3)))
        assertFalse(ShellPayloads.isPng(null))
    }

    @Test
    fun screencapCommandTargetsTheDisplay() {
        assertArrayEquals(
            arrayOf("sh", "-c", "screencap -d 7 -p | base64"),
            ShellPayloads.screencapCommand(7),
        )
    }

    // ---- FrameHandoff (the one-shot desktop mirror never closes its ImageReader during a copy) ----

    @Test
    fun aFrameOfferedInTimeIsTakenOnce() {
        val handoff = FrameHandoff<String>()
        assertTrue(handoff.offer { "frame" })
        assertFalse(handoff.offer { error("only the first frame is copied") })
        assertEquals("frame", handoff.take(1_000))
        var released = false
        handoff.close { released = true }
        assertTrue(released)
    }

    @Test
    fun aFrameArrivingAfterTheConsumerGaveUpIsNeitherCopiedNorLeaked() {
        val discarded = mutableListOf<String>()
        val handoff = FrameHandoff<String> { discarded += it }
        assertNull(handoff.take(10))
        var produced = false
        assertFalse(handoff.offer { produced = true; "late" })
        assertFalse(produced)
        handoff.close()
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun anUntakenFrameIsDiscardedOnCloseAndNothingIsProducedAfterwards() {
        val discarded = mutableListOf<String>()
        val handoff = FrameHandoff<String> { discarded += it }
        assertTrue(handoff.offer { "frame" })
        handoff.close()   // e.g. attachSurface threw: nobody takes the frame
        assertEquals(listOf("frame"), discarded)
        var produced = false
        assertFalse(handoff.offer { produced = true; "after close" })
        assertFalse(produced)
        assertNull(handoff.take(0))
        var releasedAgain = false
        handoff.close { releasedAgain = true }  // idempotent
        assertFalse(releasedAgain)
    }

    @Test
    fun aNullProductionLeavesTheHandoffOpen() {
        val handoff = FrameHandoff<String>()
        assertFalse(handoff.offer { null })  // acquireLatestImage returned null
        assertTrue(handoff.offer { "frame" })
        assertEquals("frame", handoff.take(0))
    }

    @Test
    fun closeWaitsForACopyInProgress() {
        val handoff = FrameHandoff<String>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val copying = java.util.concurrent.CountDownLatch(1)
        val producer = Thread {
            handoff.offer {
                copying.countDown()
                Thread.sleep(200)          // copyPixelsFromBuffer in progress
                events += "copy done"
                "frame"
            }
        }
        producer.start()
        assertTrue(copying.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The consumer timed out just as the frame arrived and releases the reader.
        handoff.close { events += "reader closed" }
        producer.join(5_000)
        assertEquals(listOf("copy done", "reader closed"), events.toList())
    }

    @Test
    fun aProducerThatThrowsLeavesNoHalfState() {
        val handoff = FrameHandoff<String>()
        val failure = runCatching { handoff.offer { throw IllegalStateException("buffer is inaccessible") } }
        assertTrue(failure.isFailure)
        assertTrue(handoff.offer { "frame" })
        assertEquals("frame", handoff.take(0))
    }
}
