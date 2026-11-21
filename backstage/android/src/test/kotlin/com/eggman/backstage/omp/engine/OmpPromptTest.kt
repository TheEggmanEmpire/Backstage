package com.eggman.backstage.omp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmpPromptTest {
    @Test fun requestLeadsAndIsRepeatedAfterThePreamble() {
        val p = OmpPrompt.first("PREAMBLE ".repeat(5000), listOf("USER" to "earlier"), "open the camera")
        assertTrue(p.startsWith("USER REQUEST (this is the task; answer or carry it out):\nopen the camera"))
        assertTrue(p.endsWith("USER REQUEST (repeated):\nopen the camera"))
        assertTrue(p.indexOf("PREAMBLE") > p.indexOf("open the camera"))
        assertTrue(p.contains("USER: earlier"))
    }

    @Test fun longRequestRepeatIsCapped() {
        val req = "x".repeat(OmpPrompt.REPEAT_CHARS + 10)
        val tail = OmpPrompt.first("p", emptyList(), req).substringAfter("USER REQUEST (repeated):\n")
        assertEquals(OmpPrompt.REPEAT_CHARS + 1, tail.length)
    }
}
