package com.eggman.backstage.exec

import com.eggman.backstage.testutil.Screens
import org.junit.Assert.*
import org.junit.Test

class IndexHighWaterTest {
    @Test fun disappearingUnseenElementNeverReleasesItsIndexInTheSameGeneration() {
        val serializer = ScreenSerializer()
        fun capture(extra: String? = null): ScreenCapture = Screens.app(*((0 until 20).map { i ->
            Screens.node("Row $i", b = Box(0, i * 50, 300, i * 50 + 30))
        } + listOfNotNull(extra?.let { Screens.node(it, b = Box(0, 1500, 300, 1530)) })).toTypedArray())
        val shown = serializer.serialize(capture(), "phone")
        val unseen = serializer.serialize(capture("Temporary"), "phone", shown)
        val gone = serializer.serialize(capture(), "phone", unseen)
        val new = serializer.serialize(capture("Different"), "phone", gone)
        assertEquals(shown.indexGeneration, new.indexGeneration)
        assertEquals(20, unseen.elements.last().index)
        assertEquals(21, new.elements.last().index)
    }

    @Test fun nonCommittingObserveBranchStillReservesItsAllocatedIndices() {
        val serializer = ScreenSerializer()
        fun capture(extra: String? = null) = Screens.app(*((0 until 20).map { i ->
            Screens.node("Row $i", b = Box(0, i * 50, 300, i * 50 + 30))
        } + listOfNotNull(extra?.let { Screens.node(it, b = Box(0, 1500, 300, 1530)) })).toTypedArray())
        val shown = serializer.serialize(capture(), "phone")
        val observedOnly = serializer.serialize(capture("Temporary"), "phone", shown)
        val actual = serializer.serialize(capture("Different"), "phone", shown)
        assertEquals(shown.indexGeneration, actual.indexGeneration)
        assertEquals(20, observedOnly.elements.last().index)
        assertEquals(21, actual.elements.last().index)
    }
}
