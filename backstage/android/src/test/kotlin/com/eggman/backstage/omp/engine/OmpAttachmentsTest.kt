package com.eggman.backstage.omp.engine

import org.junit.Assert.*
import org.junit.Test

class OmpAttachmentsTest {
    @Test fun inlineImagesBecomePartsAndOtherContextIsKept() {
        val parsed = OmpAttachments.parse(mapOf("photo" to "data:image/png;base64,SU1H", "note" to "context"))
        assertEquals("[image attached]", parsed.context["photo"])
        assertEquals("context", parsed.context["note"])
        assertEquals("SU1H", parsed.images.single().base64)
        assertEquals("image/png", parsed.images.single().mediaType)
    }
    @Test fun malformedImagesAreRejected() {
        assertThrows(OmpProtocolException::class.java) { OmpAttachments.parse(mapOf("photo" to "data:image/png;base64,???")) }
    }
}
