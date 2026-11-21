package com.eggman.backstage.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun skillMd(name: String, description: String = "Does $name things.", body: String = "Steps for $name.") =
    "---\nname: $name\ndescription: $description\n---\n\n$body\n"

fun zipOf(file: File, entries: List<Pair<String, ByteArray>>): File {
    ZipOutputStream(file.outputStream()).use { z ->
        entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
    }
    return file
}

fun zipOf(file: File, vararg entries: Pair<String, String>): File = zipOf(file, entries.map { it.first to it.second.toByteArray() })

class SkillArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun singleSkillZipWithFolder() {
        val z = zipOf(tmp.newFile("a.zip"), "tidy/SKILL.md" to skillMd("tidy"), "tidy/references/more.md" to "# More", "tidy/scripts/run.py" to "print(1)")
        val scan = SkillArchive.scan(z)
        val c = scan.candidates.single()
        assertTrue(c.allErrors.toString(), c.ok)
        assertEquals("tidy", c.name)
        assertEquals(setOf("SKILL.md", "references/more.md", "scripts/run.py"), c.files.keys)
        assertTrue(c.allWarnings.any { "contain real code" in it })
    }

    @Test fun skillAtArchiveRootAndDotSkillFile() {
        val z = zipOf(tmp.newFile("x.skill"), "SKILL.md" to skillMd("rooted"), "notes.txt" to "n")
        val c = SkillArchive.scan(z).candidates.single()
        assertEquals("rooted", c.name)
        assertEquals(setOf("SKILL.md", "notes.txt"), c.files.keys)
    }

    @Test fun repositoryArchiveWithSkillsFolderAndSubPath() {
        val z = zipOf(tmp.newFile("repo.zip"),
            "owner-repo-abc123/README.md" to "readme",
            "owner-repo-abc123/skills/one/SKILL.md" to skillMd("one"),
            "owner-repo-abc123/skills/one/ref.md" to "r1",
            "owner-repo-abc123/skills/two/SKILL.md" to skillMd("two"),
            "owner-repo-abc123/skills/two/nested/SKILL.md" to skillMd("nested"),
            "owner-repo-abc123/skills/two/nested/n.md" to "n",
            "owner-repo-abc123/__MACOSX/skills/one/._SKILL.md" to "junk",
        )
        val all = SkillArchive.scan(z)
        assertEquals(listOf("one", "two", "nested"), all.candidates.map { it.name })
        // Files belong to the innermost skill folder.
        assertEquals(setOf("SKILL.md"), all.candidates.first { it.name == "two" }.files.keys)
        assertEquals(setOf("SKILL.md", "n.md"), all.candidates.first { it.name == "nested" }.files.keys)
        assertEquals("skills/one", all.candidates.first().root)
        val sub = SkillArchive.scan(z, "skills/one")
        assertEquals(listOf("one"), sub.candidates.map { it.name })
        assertEquals(setOf("SKILL.md", "ref.md"), sub.candidates.single().files.keys)
        val none = SkillArchive.scan(z, "docs")
        assertTrue(none.fatal)
        assertTrue(none.problems.any { "no SKILL.md in \"docs\"" in it })
    }

    @Test fun zipSlipEntriesAreSkippedNeverWritten() {
        val z = zipOf(tmp.newFile("evil.zip"),
            "good/SKILL.md" to skillMd("good"),
            "good/../../evil.txt" to "x",
            "/etc/passwd" to "x",
            "..\\..\\win.txt" to "x",
            "C:/temp/x.txt" to "x",
            "good/./ok.md" to "fine",
        )
        val scan = SkillArchive.scan(z)
        val c = scan.candidates.single()
        assertEquals(setOf("SKILL.md", "ok.md"), c.files.keys)
        assertEquals(4, scan.problems.count { "unsafe path" in it })
        // And installing never leaves the store's folder.
        val store = SkillStore(tmp.newFolder("store"))
        assertTrue(store.install(c, "test") is InstallOutcome.Installed)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(store.root, "evil.txt").exists())
    }

    @Test fun limitsAreEnforcedWhileReading() {
        val big = ByteArray(5_000) { 'a'.code.toByte() }
        val z = zipOf(tmp.newFile("big.zip"), listOf("s/SKILL.md" to skillMd("s").toByteArray(), "s/big.txt" to big))
        val small = SkillLimits(maxFileBytes = 4_000, maxSkillBytes = 100_000)
        assertTrue(SkillArchive.scan(z, limits = small).candidates.single().allErrors.any { "big.txt is larger" in it })
        val tight = SkillLimits(maxFileBytes = 10_000, maxSkillBytes = 3_000)
        assertTrue(SkillArchive.scan(z, limits = tight).candidates.single().allErrors.any { "skill is larger" in it })
        assertTrue(SkillArchive.scan(z, limits = SkillLimits(maxArchiveBytes = 100)).problems.single().contains("larger"))
        val many = zipOf(tmp.newFile("many.zip"), (listOf("m/SKILL.md" to skillMd("m")) + (1..5).map { "m/f$it.md" to "x" }).map { it.first to it.second.toByteArray() })
        assertTrue(SkillArchive.scan(many, limits = SkillLimits(maxFiles = 3)).candidates.single().allErrors.any { "more than 3 files" in it })
        val skills = zipOf(tmp.newFile("skills.zip"), (1..4).map { "k$it/SKILL.md" to skillMd("k$it").toByteArray() })
        val capped = SkillArchive.scan(skills, limits = SkillLimits(maxSkills = 2))
        assertEquals(2, capped.candidates.size)
        assertTrue(capped.problems.any { "only the first 2" in it })
    }

    @Test fun loneSkillMdAndNonArchives() {
        val f = tmp.newFile("SKILL.md").apply { writeText(skillMd("plain")) }
        assertEquals("plain", SkillArchive.scan(f).candidates.single().name)
        val bin = tmp.newFile("x.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 4)) }
        assertTrue(SkillArchive.scan(bin).fatal)
        val empty = zipOf(tmp.newFile("empty.zip"), "readme.md" to "nothing")
        assertTrue(SkillArchive.scan(empty).problems.any { "no SKILL.md" in it })
        val broken = tmp.newFile("broken.zip").apply { writeBytes("PK\u0003\u0004garbage".toByteArray()) }
        assertTrue(SkillArchive.scan(broken).fatal)
    }

    @Test fun invalidSkillsAreReportedNotDropped() {
        val z = zipOf(tmp.newFile("bad.zip"), "Bad_Name/SKILL.md" to skillMd("Bad_Name"), "ok/SKILL.md" to skillMd("ok"))
        val scan = SkillArchive.scan(z)
        assertEquals(2, scan.candidates.size)
        val bad = scan.candidates.first { !it.ok }
        assertEquals("Bad_Name", bad.label)
        assertNotNull(bad.allErrors.firstOrNull { "lowercase" in it })
    }
}
