package com.eggman.backstage.omp

import org.junit.Assert.*
import org.junit.Test

class SkillScriptsRegressionTest {
    @Test fun syncCeilingMatchesTheRaisedImportCeiling() {
        assertEquals(2_000_000_000L, OmpSkills.MAX_ZIP_BYTES.toLong())
        assertTrue(OmpSkills.MAX_DIRECTORIES > 2_000)
    }
}
