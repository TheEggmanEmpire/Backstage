package com.eggman.backstage.omp

import com.eggman.backstage.skills.SkillStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OmpSkillsCacheTest {
    private fun OmpSkills.SnapshotCache.file() = open().use { it.file }
    @get:Rule val tmp = TemporaryFolder()

    @Test fun replacingTheSharedStoresFilesInvalidatesItsSnapshot() {
        val store = SkillStore.shared(tmp.newFolder())
        store.saveSimple("restored-skill", "Restored skill", "restored steps v1")
        store.setEnabled("restored-skill", true)
        val cache = OmpSkills.SnapshotCache(store)
        val before = cache.file()
        val beforeBytes = before.readBytes()
        SkillStore.notifyFilesReplaced(tmp.newFolder())
        assertEquals(before, cache.file())
        val instructions = java.io.File(store.root, "restored-skill/SKILL.md")
        instructions.writeText(instructions.readText().replace("restored steps v1", "restored steps v2"))
        SkillStore.notifyFilesReplaced(store.root)
        val after = cache.file()
        assertNotEquals(before, after)
        assertFalse(beforeBytes.contentEquals(after.readBytes()))
    }

    @Test fun concurrentReadersShareOneFileUntilInvalidated() {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = OmpSkills.SnapshotCache(generate = {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            it.writeBytes(ByteArray(128))
        })
        val pool = Executors.newFixedThreadPool(8)
        try {
            val readers = (1..8).map { pool.submit<File> { cache.file() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            val first = readers.first().get(5, TimeUnit.SECONDS)
            readers.forEach { assertEquals(first, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(first, cache.file())
            assertEquals(1, calls.get())
            cache.invalidate()
            assertNotEquals(first, cache.file())
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun storeChangesInvalidateSnapshotsAndFailuresAreCachedUntilAChange() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("one", "One", "Steps")
        val calls = AtomicInteger()
        val cache = OmpSkills.SnapshotCache(store) { calls.incrementAndGet(); OmpSkills.zip(store, it) }
        val first = cache.file()
        assertEquals(first, cache.file())
        store.setEnabled("one", true)
        val enabled = cache.file()
        assertNotEquals(first, enabled)
        assertTrue(enabled.length() > 22)
        assertEquals(enabled, cache.file())
        assertEquals(2, calls.get())
        var fail = true
        val failureCalls = AtomicInteger()
        val broken = OmpSkills.SnapshotCache(store) {
            failureCalls.incrementAndGet()
            check(!fail)
            OmpSkills.zip(store, it)
        }
        repeat(2) { assertThrows(IllegalStateException::class.java) { broken.file() } }
        assertEquals(1, failureCalls.get())
        fail = false
        store.setEnabled("one", false)
        assertArrayEquals(OmpSkills.zip(null, tmp.newFile()).readBytes(), broken.file().readBytes())
        assertEquals(2, failureCalls.get())
    }

    @Test(timeout = 5000) fun invalidationDuringGenerationRetriesWithoutReversingTheStoreLockOrder() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("one", "One", "Steps")
        val calls = AtomicInteger()
        val cache = OmpSkills.SnapshotCache(store) {
            if (calls.incrementAndGet() == 1) store.setEnabled("one", true)
            OmpSkills.zip(store, it)
        }
        assertTrue(cache.file().length() > 22)
        assertEquals(2, calls.get())
    }
    @Test fun openReaderSurvivesReplacementAndOldFileIsUnlinked() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("one", "One", "Steps")
        store.setEnabled("one", true)
        val cache = OmpSkills.SnapshotCache(store)
        cache.open().use { old ->
            val before = old.file.readBytes()
            store.setEnabled("one", false)
            cache.open().use { newer ->
                assertFalse(old.file.exists())
                assertEquals(22L, newer.size)
                assertArrayEquals(before, old.input.readBytes())
            }
        }
    }

    @Test fun largeSnapshotsUseDiskAndReuseTheSameFile() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("large", "Large", "Steps")
        store.setEnabled("large", true)
        java.io.RandomAccessFile(File(store.root, "large/data.bin"), "rw").use { it.setLength(31_000_000) }
        val cache = OmpSkills.SnapshotCache(store)
        val first = cache.file()
        assertTrue(first.length() > 30_000_000)
        assertEquals(first, cache.file())
        assertEquals(1, first.parentFile!!.listFiles()!!.count { it.extension == "zip" })
    }

}
