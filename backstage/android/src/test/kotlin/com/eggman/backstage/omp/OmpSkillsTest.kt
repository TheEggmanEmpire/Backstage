package com.eggman.backstage.omp

import com.eggman.backstage.skills.SkillLimits
import com.eggman.backstage.skills.SkillStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class OmpSkillsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun enabled(store: SkillStore, name: String) {
        assertEquals(emptyList<String>(), store.saveSimple(name, "Use $name", "Instructions for $name"))
        assertTrue(store.setEnabled(name, true))
    }

    private fun zip(store: SkillStore? = null): File = tmp.newFile().also { OmpSkills.zip(store, it) }

    private fun entries(file: File): Map<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    @Test fun onlyEnabledSkillsAndAllTheirFilesHaveManagedMarkersAndStableBytes() {
        val store = SkillStore(tmp.newFolder())
        enabled(store, "zeta")
        enabled(store, "alpha")
        store.saveSimple("disabled", "Disabled", "Do not share")
        File(store.root, "alpha/references").mkdirs()
        File(store.root, "alpha/references/file with spaces.md").writeText("reference")
        File(store.root, "alpha/asset.bin").writeBytes(byteArrayOf(0, 1, -1))
        val first = zip(store)
        val files = entries(first)
        assertEquals(listOf("skills/alpha/.backstage", "skills/alpha/SKILL.md", "skills/alpha/asset.bin",
            "skills/alpha/references/file with spaces.md", "skills/zeta/.backstage", "skills/zeta/SKILL.md"), files.keys.toList())
        assertArrayEquals(byteArrayOf(), files.getValue("skills/alpha/.backstage"))
        assertArrayEquals(byteArrayOf(0, 1, -1), files.getValue("skills/alpha/asset.bin"))
        assertEquals("reference", files.getValue("skills/alpha/references/file with spaces.md").decodeToString())
        File(store.root, "alpha/SKILL.md").setLastModified(1_000L)
        assertArrayEquals(first.readBytes(), zip(store).readBytes())
        store.setEnabled("alpha", false)
        assertEquals(setOf("skills/zeta/.backstage", "skills/zeta/SKILL.md"), entries(zip(store)).keys)
        store.delete("zeta")
        assertTrue(entries(zip(store)).isEmpty())
    }

    @Test fun noStoreAndNoEnabledSkillsProduceAValidEmptyZip() {
        val empty = zip(null)
        assertTrue(empty.length() >= 22)
        assertTrue(entries(empty).isEmpty())
        assertArrayEquals(empty.readBytes(), zip(SkillStore(tmp.newFolder())).readBytes())
    }

    @Test fun directoryNamesAreSafeAndCollisionAllocationDoesNotDependOnInputOrder() {
        val names = listOf("", ".", "..", "/ABSOLUTE", "../escape", "A B", "A/B", "a-b", "x".repeat(100), "a\\b")
        val dirs = OmpSkills.directoryNames(names)
        assertEquals(dirs, OmpSkills.directoryNames(names.reversed()))
        assertEquals(names.size, dirs.values.toSet().size)
        dirs.values.forEach {
            assertTrue(it, it.matches(Regex("[a-z0-9._-]{1,64}")))
            assertFalse(it in setOf(".", ".."))
        }
        for (path in listOf("../secret", "/absolute", "a/../../secret", "a\\..\\secret", "a\nfile", "C:/secret"))
            assertNull(path, OmpSkills.safePath(path))
    }

    @Test fun symlinksCannotExportFilesOutsideTheSkillOrReplaceTheMarker() {
        val store = SkillStore(tmp.newFolder())
        enabled(store, "safe")
        File(store.root, "safe/.backstage").writeText("imported marker content")
        val secret = tmp.newFile().apply { writeText("outside") }
        Files.createSymbolicLink(File(store.root, "safe/leak.md").toPath(), secret.toPath())
        val files = entries(zip(store))
        assertFalse(files.containsKey("skills/safe/leak.md"))
        assertArrayEquals(byteArrayOf(), files.getValue("skills/safe/.backstage"))
    }

    @Test fun sizeLimitsRejectOversizedSnapshotsAndFileCountIsCapped() {
        val store = SkillStore(tmp.newFolder(), limits = SkillLimits(maxSkillBytes = 300, maxFileBytes = 250, maxFiles = 3))
        enabled(store, "large")
        File(store.root, "large/data.bin").writeBytes(ByteArray(251))
        assertThrows(IllegalArgumentException::class.java) { zip(store) }
        File(store.root, "large/data.bin").delete()
        repeat(4) { File(store.root, "large/$it.txt").writeText("x") }
        assertEquals(4, entries(zip(store)).size) // Three files plus the managed marker.
    }

    @Test fun totalArchiveAndSkillCountAreBounded() {
        val bytes = SkillStore(tmp.newFolder(), limits = SkillLimits(maxArchiveBytes = 200))
        enabled(bytes, "one")
        assertThrows(IllegalArgumentException::class.java) { zip(bytes) }
        val count = SkillStore(tmp.newFolder(), limits = SkillLimits(maxSkills = 1))
        enabled(count, "one")
        enabled(count, "two")
        assertThrows(IllegalArgumentException::class.java) { zip(count) }
    }

    @Test fun deepPathsAndExcessImpliedDirectoriesAreOmitted() {
        val store = SkillStore(tmp.newFolder())
        enabled(store, "bounded")
        val deepPath = (1..10).joinToString("/") { "deep$it" } + "/file.md"
        File(store.root, "bounded/$deepPath").apply { parentFile!!.mkdirs(); writeText("omit") }
        repeat(240) { i ->
            val path = "branch-$i/a/b/c/d/e/f/g/h/file.md"
            File(store.root, "bounded/$path").apply { parentFile!!.mkdirs(); writeText("x") }
        }
        val archive = entries(zip(store))
        assertFalse(archive.containsKey("skills/bounded/$deepPath"))
        assertTrue(archive.containsKey("skills/bounded/SKILL.md"))
        assertTrue(archive.keys.all { it.split('/').size <= 12 })
        val directories = archive.keys.flatMap { path ->
            val parts = path.split('/')
            (1 until parts.size).map { parts.take(it).joinToString("/") }
        }.toSet()
        assertTrue("${directories.size} directories", directories.size <= OmpSkills.MAX_DIRECTORIES)
        assertEquals(242, archive.size)
    }

    @Test fun excessFileEntriesAreOmittedWithinPerSkillAndTotalCaps() {
        val store = SkillStore(tmp.newFolder(), limits = SkillLimits(maxFiles = 3, maxEntries = 4))
        enabled(store, "bounded")
        repeat(10) { File(store.root, "bounded/$it.md").writeText("x") }
        val archive = entries(zip(store))
        assertEquals(4, archive.size)
        assertTrue(archive.containsKey("skills/bounded/SKILL.md"))
        assertTrue(archive.containsKey("skills/bounded/.backstage"))
    }

    @Test fun anAliasedParentDirectoryDoesNotHideLegitimateSkills() {
        val real = tmp.newFolder()
        val alias = File(tmp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val store = SkillStore(alias)
        enabled(store, "inside")
        assertEquals(setOf("skills/inside/.backstage", "skills/inside/SKILL.md"), entries(zip(store)).keys)
    }

    @Test fun storeNotifiesForInstallToggleReplaceEditAndDeleteAndCanUnsubscribe() {
        val store = SkillStore(tmp.newFolder())
        var changes = 0
        val stop = store.observe { changes++ }
        store.observe { error("a failed listener must not break skill writes") }
        store.saveSimple("live", "Use live", "v1")
        assertEquals(1, changes)
        store.setEnabled("live", true)
        assertEquals(2, changes)
        store.saveSimple("live", "Use live", "v2", editing = "live")
        assertEquals(3, changes)
        store.setEnabled("live", false)
        assertEquals(4, changes)
        store.delete("live")
        assertEquals(5, changes)
        assertFalse(store.setEnabled("missing", true))
        assertEquals(5, changes)
        stop()
        store.saveSimple("another", "Use another", "v1")
        assertEquals(5, changes)
    }
}
