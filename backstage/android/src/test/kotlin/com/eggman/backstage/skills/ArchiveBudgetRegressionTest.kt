package com.eggman.backstage.skills

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveBudgetRegressionTest {
    private fun scan(entries: List<Pair<String, ByteArray>>, budget: Long): ArchiveScan {
        val file = Files.createTempFile("backstage-budget-", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            }
            return SkillArchive.scanZip(file, limits = SkillLimits(maxExpandedBytes = budget))
        } finally { file.delete() }
    }
    private fun md(name: String) = "---\nname: $name\ndescription: Test archive budget.\n---\nSteps.\n".toByteArray()

    @Test fun manyCompressibleSkillsShareOneExpandedBudget() {
        val entries = listOf("one/SKILL.md" to md("one"), "one/data.txt" to ByteArray(100000) { 65 },
            "two/SKILL.md" to md("two"), "two/data.txt" to ByteArray(100000) { 65 })
        val result = scan(entries, 150000)
        assertTrue("Must reject before returning retained candidate payloads", result.candidates.isEmpty())
        assertTrue(result.problems.toString(), result.problems.any { "expanded content exceeds" in it })
    }

    @Test fun exactAggregateBoundaryIsAcceptedAndOneByteLessIsRejected() {
        val entries = listOf("one/SKILL.md" to md("one"), "two/SKILL.md" to md("two"))
        val total = entries.sumOf { it.second.size.toLong() }
        val accepted = scan(entries, total)
        assertEquals(2, accepted.candidates.size)
        assertTrue(accepted.candidates.all { it.ok })
        assertEquals(total, accepted.candidates.sumOf { it.totalBytes })
        assertTrue(scan(entries, total - 1).candidates.isEmpty())
    }

    @Test fun unrelatedRepositoryFilesDoNotConsumeRetainedCandidateBudget() {
        val marker = md("one")
        val result = scan(listOf("repo/one/SKILL.md" to marker, "repo/README.md" to ByteArray(100000)), marker.size.toLong())
        assertEquals(1, result.candidates.size)
        assertTrue(result.candidates.single().ok)
    }
}
