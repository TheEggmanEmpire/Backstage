package com.eggman.backstage.skills

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: SkillStore
    private lateinit var engine: SkillSyncEngine
    private val source = SkillSyncSource("acme-skills", "acme", "skills", "main")
    private val sha1 = "a".repeat(40)
    private val sha2 = "b".repeat(40)
    @Before fun setup() {
        server = MockWebServer().also { it.start() }
        store = SkillStore(tmp.newFolder("skills"))
        engine = SkillSyncEngine(store, tmp.newFolder("cache"), server.url("/").toString(), OkHttpClient())
    }
    @After fun close() { server.shutdown() }
    private fun archive(vararg skills: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> skills.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry("owner-repo-sha/skills/$name/SKILL.md")); zip.write(skillMd(name, body = body).toByteArray()); zip.closeEntry()
        } }
    }.toByteArray()
    private fun commit(sha: String = sha1) { server.enqueue(MockResponse().setBody("{\"sha\":\"$sha\"}")) }
    private fun zip(bytes: ByteArray) { server.enqueue(MockResponse().setBody(Buffer().write(bytes))) }
    private fun candidate(name: String, body: String) = SkillArchive.fromSkillMd(skillMd(name, body = body)).candidates.single()

    @Test fun unchangedSuccessfulCommitSkipsTheArchive() = runBlocking {
        commit()
        val previous = SkillSyncStatus(1, "ok", commitSha = sha1, configKey = source.configKey)
        val status = engine.sync(source, previous = previous)
        assertEquals("ok", status.result)
        assertEquals(SkillSyncCounts(), status.counts)
        assertEquals(1, server.requestCount)
        assertEquals("/repos/acme/skills/commits/main", server.takeRequest().path)
    }

    @Test fun branchWithSlashIsSentAsPlainPathSegments() = runBlocking {
        commit(); zip(archive("alpha" to "one"))
        assertEquals("ok", engine.sync(source.copy(ref = "feature/x")).result)
        assertEquals("/repos/acme/skills/commits/feature/x", server.takeRequest().path)
    }

    @Test fun dotSegmentsInRefAreRejectedWithoutRequests() = runBlocking {
        val status = engine.sync(source.copy(ref = "main/../../user"))
        assertEquals("error", status.result)
        assertEquals(0, server.requestCount)
    }

    @Test fun defaultBranchIsResolvedFromRepositoryRatherThanAssumingMain() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"default_branch\":\"trunk\"}")); commit(); zip(archive("alpha" to "one"))
        assertEquals("ok", engine.sync(source.copy(ref = "")).result)
        assertEquals("/repos/acme/skills", server.takeRequest().path)
        assertEquals("/repos/acme/skills/commits/trunk", server.takeRequest().path)
        assertEquals("/repos/acme/skills/zipball/$sha1", server.takeRequest().path)
    }

    @Test fun mirrorsAddUpdateAndDeleteWhileKeepingEnabledSwitch() = runBlocking {
        commit(); zip(archive("alpha" to "one", "gone" to "old"))
        val first = engine.sync(source)
        assertEquals(SkillSyncCounts(added = 2), first.counts)
        assertFalse(store.get("alpha")!!.enabled)
        assertEquals(source.id, store.get("alpha")!!.syncSource)
        store.setEnabled("alpha", true)
        commit(sha2); zip(archive("alpha" to "two", "new" to "new body"))
        val second = engine.sync(source, previous = first)
        assertEquals("ok", second.result)
        assertEquals(SkillSyncCounts(added = 1, updated = 1, deleted = 1), second.counts)
        assertEquals("two", store.body("alpha"))
        assertTrue(store.get("alpha")!!.enabled)
        assertNull(store.get("gone"))
        assertFalse(store.get("new")!!.enabled)
        assertTrue(store.root.listFiles()!!.none { it.name.startsWith(".sync-") })
    }

    @Test fun unchangedFilesAtNewCommitAreNotReinstalled() = runBlocking {
        commit(); zip(archive("alpha" to "one")); val first = engine.sync(source)
        val timestamp = store.get("alpha")!!.installedMs
        commit(sha2); zip(archive("alpha" to "one")); val second = engine.sync(source, previous = first)
        assertEquals(SkillSyncCounts(), second.counts)
        assertEquals(timestamp, store.get("alpha")!!.installedMs)
    }

    @Test fun missingFolderAfterRestoreKeepsEnabledAndBypassesSameCommitCache() = runBlocking {
        commit(); zip(archive("alpha" to "one")); val first = engine.sync(source)
        store.setEnabled("alpha", true)
        File(store.root, "alpha").deleteRecursively()
        commit(); zip(archive("alpha" to "one"))
        assertEquals("ok", engine.sync(source, previous = first).result)
        assertTrue(store.get("alpha")!!.enabled)
        assertEquals(source.id, store.get("alpha")!!.syncSource)
        assertEquals(4, server.requestCount)
    }

    @Test fun installWithMatchingRestoredOwnershipAlsoKeepsEnabled() {
        store.install(candidate("alpha", "one"), "GitHub", syncSource = source.id)
        store.setEnabled("alpha", true); File(store.root, "alpha").deleteRecursively()
        store.install(candidate("alpha", "two"), "GitHub", syncSource = source.id)
        assertTrue(store.get("alpha")!!.enabled)
    }

    @Test fun manualSkillsAreReplacedOtherSourceSkillsSkipped() = runBlocking {
        store.install(candidate("manual", "mine"), "manual")
        store.install(candidate("other", "theirs"), "GitHub", syncSource = "another-source")
        store.setEnabled("manual", true)
        commit(); zip(archive("manual" to "overwrite", "other" to "overwrite", "owned" to "ours"))
        val status = engine.sync(source)
        assertEquals("partial", status.result)
        assertEquals(SkillSyncCounts(added = 1, updated = 1, skipped = 1), status.counts.copy(notes = emptyList()))
        assertTrue(status.counts.notes.any { it == "other: already synced from source another-source" })
        assertEquals("overwrite", store.body("manual")); assertEquals("theirs", store.body("other"))
        assertEquals(source.id, store.get("manual")!!.syncSource); assertTrue(store.get("manual")!!.enabled)
        store.removeSource(source.id)
        assertNull(store.get("owned")); assertNull(store.get("manual")); assertNotNull(store.get("other"))
    }

    @Test fun wholeSourceRollsBackWhenSavingStateFails() {
        store.mirror(source.id, listOf(candidate("alpha", "old"), candidate("gone", "keep")), source.url)
        store.setEnabled("alpha", true)
        val stateBefore = File(store.root, SkillStore.STATE).readText()
        File(store.root, "${SkillStore.STATE}.tmp").mkdir()
        assertTrue(runCatching { store.mirror(source.id, listOf(candidate("alpha", "new"), candidate("beta", "new")), source.url) }.isFailure)
        assertEquals("old", store.body("alpha")); assertEquals("keep", store.body("gone")); assertNull(store.get("beta"))
        assertTrue(store.get("alpha")!!.enabled)
        assertEquals(stateBefore, File(store.root, SkillStore.STATE).readText())
    }

    @Test fun invalidCandidateIsSkippedWithAReasonWhileTheRestSyncs() = runBlocking {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        commit(); zip(archive("alpha" to "new", "Bad_Name" to "invalid"))
        val status = engine.sync(source)
        assertEquals("partial", status.result)
        assertEquals("new", store.body("alpha"))
        assertEquals(listOf("alpha"), store.list().map { it.name })
        assertEquals(1, status.counts.skipped)
        assertTrue(status.counts.notes.any { it.contains("Bad_Name") })
        assertTrue(status.counts.notes.any { it.startsWith("Removals paused") })
    }

    @Test fun mirrorWhoseUpstreamCopyBecameInvalidIsKeptNotDeleted() = runBlocking {
        store.mirror(source.id, listOf(candidate("alpha", "old"), candidate("beta", "old")), source.url)
        val broken = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("owner-repo-sha/skills/alpha/SKILL.md")); zip.write(skillMd("alpha", body = "new").toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("owner-repo-sha/skills/beta/SKILL.md")); zip.write("---\nname: beta\n---\n".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        commit(); zip(broken)
        val status = engine.sync(source)
        assertEquals("partial", status.result)
        assertEquals("new", store.body("alpha"))
        assertEquals("old", store.body("beta"))
        assertEquals(0, status.counts.deleted)
    }

    @Test fun lowercaseSkillFileStillSyncsWithANote() = runBlocking {
        val lower = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("owner-repo-sha/skills/alpha/skill.md")); zip.write(skillMd("alpha", body = "one").toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        commit(); zip(lower)
        val status = engine.sync(source)
        assertEquals("partial", status.result)
        assertEquals("one", store.body("alpha"))
        assertTrue(status.counts.notes.single().startsWith("the skill file is named"))
    }

    private fun txn(name: String, installed: List<String> = emptyList()): File = File(store.root, name).also { t ->
        t.mkdirs(); if (installed.isNotEmpty()) File(t, "INSTALLED").writeText(installed.joinToString("\n", postfix = "\n"))
    }
    private fun dir(name: String, body: String) { File(store.root, name).mkdirs(); File(store.root, "$name/SKILL.md").writeText(skillMd(name, body = body)) }
    private fun recover() = SkillStore.recoverInterrupted(store.root.parentFile, store.root)

    @Test fun uncommittedMirrorIsUndoneAndUntouchedNamesSurvive() {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        val t = txn(".sync-crash", installed = listOf("alpha", "beta"))
        File(store.root, "alpha").renameTo(File(t, "old/alpha").apply { parentFile.mkdirs() })
        dir("alpha", "half"); dir("beta", "half")
        dir("gamma", "untouched") // staged in the transaction but never moved: not in INSTALLED
        File(t, "new/gamma").mkdirs()
        recover()
        assertEquals("old", store.body("alpha")); assertNull(store.get("beta")); assertEquals("untouched", store.body("gamma"))
        assertFalse(t.exists())
    }

    @Test fun transactionNamedInStateIsCommittedEvenWithoutAnyOtherMarker() {
        store.mirror(source.id, listOf(candidate("alpha", "new")), source.url)
        val name = org.json.JSONObject(File(store.root, SkillStore.STATE).readText()).getString("txn")
        val t = txn(name, installed = listOf("alpha")) // a deletion cut short after the commit
        File(t, "old/alpha").mkdirs(); File(t, "old/alpha/SKILL.md").writeText(skillMd("alpha", body = "old"))
        recover()
        assertEquals("new", store.body("alpha")); assertFalse(t.exists())
    }

    @Test fun olderCommittedTransactionIsNotUndoneAfterANewerCommit() {
        store.mirror(source.id, listOf(candidate("alpha", "v1")), source.url)
        val stale = txn(".sync-older", installed = listOf("alpha")) // its retirement failed earlier
        File(stale, "COMMITTED").createNewFile(); File(stale, "old/alpha").mkdirs()
        store.mirror(source.id, listOf(candidate("alpha", "v2")), source.url) // state.json now names a newer txn
        recover()
        assertEquals("v2", store.body("alpha")); assertFalse(stale.exists())
    }

    @Test fun keptRollbackFolderNeverDeletesANameTheUserReusedLater() {
        val t = txn(".sync-kept") // in-process rollback removed INSTALLED before keeping the folder
        File(t, "old/other").mkdirs(); File(t, "old/other/SKILL.md").writeText(skillMd("other", body = "backup"))
        store.saveSimple("alpha", "Mine", "My own skill")
        recover()
        assertNotNull(store.get("alpha")); assertEquals("backup", store.body("other")); assertFalse(t.exists())
    }

    @Test fun restoreConflictKeepsBothCopiesInsteadOfOverwriting() {
        val t = txn(".sync-conflict")
        File(t, "old/alpha").mkdirs(); File(t, "old/alpha/SKILL.md").writeText(skillMd("alpha", body = "backup"))
        dir("alpha", "newer")
        recover()
        assertEquals("newer", store.body("alpha")); assertTrue(File(t, "old/alpha").exists())
    }

    @Test fun trashFoldersAreAlwaysDeleted() {
        File(store.root, ".trash-x/old/alpha").mkdirs()
        recover()
        assertFalse(File(store.root, ".trash-x").exists())
    }

    @Test fun unreadableUnnamedSkillPausesAllRemovals() {
        store.mirror(source.id, listOf(candidate("alpha", "old"), candidate("foo", "old")), source.url)
        val broken = SkillCandidate("skills/foo-skill", SkillParser.parse("not a manifest", "foo-skill"), mapOf("SKILL.md" to "x".toByteArray()))
        assertNull(broken.name)
        val counts = store.mirror(source.id, listOf(candidate("alpha", "new"), broken), source.url)
        assertEquals("new", store.body("alpha")); assertEquals("old", store.body("foo")); assertEquals(0, counts.deleted)
        assertTrue(counts.notes.any { it.startsWith("Removals paused") })
    }

    @Test fun restoreWithCommittedPreferencesFinishesWithTheStagedTree() {
        val files = tmp.newFolder("files"); val root = File(files, "skills").apply { mkdirs(); File(this, "old-tree").mkdirs() }
        val ws = File(files, ".settings-restore-1").apply { mkdirs() }
        File(ws, "PREFS_COMMITTED").createNewFile(); File(ws, "incoming/alpha").mkdirs()
        SkillStore.recoverInterrupted(files, root)
        assertTrue(File(root, "alpha").isDirectory); assertFalse(File(root, "old-tree").exists()); assertFalse(ws.exists())
    }

    @Test fun restoreThatWasSwappedAlreadyKeepsTheLiveTree() {
        val files = tmp.newFolder("files"); val root = File(files, "skills").apply { mkdirs(); File(this, "restored").mkdirs() }
        val ws = File(files, ".settings-restore-1").apply { mkdirs() }
        File(ws, "PREFS_COMMITTED").createNewFile(); File(ws, "previous/old-tree").mkdirs()
        SkillStore.recoverInterrupted(files, root)
        assertTrue(File(root, "restored").isDirectory); assertFalse(ws.exists())
    }

    @Test fun rolledBackRestoreBringsBackTheUsersTreeEvenIfSomethingRecreatedTheFolder() {
        val files = tmp.newFolder("files"); val root = File(files, "skills").apply { mkdirs(); File(this, "recreated-by-sync").mkdirs() }
        val ws = File(files, ".settings-restore-1").apply { mkdirs() }
        File(ws, "incoming/from-backup").mkdirs(); File(ws, "previous/mine").mkdirs()
        SkillStore.recoverInterrupted(files, root)
        assertTrue(File(root, "mine").isDirectory); assertFalse(File(root, "from-backup").exists()); assertFalse(ws.exists())
        assertTrue(File(root, "recreated-by-sync").isDirectory) // made meanwhile: merged back, never deleted
    }

    @Test fun recoveryRunTwiceNeverDeletesWhatTheFirstRunRestored() {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        val t = txn(".sync-twice", installed = listOf("alpha"))
        File(store.root, "alpha").renameTo(File(t, "old/alpha").apply { parentFile.mkdirs() })
        dir("alpha", "half")
        recover()
        // Simulate death after the first run restored alpha but before it retired the folder: INSTALLED is already
        // forgotten, so the leftover holds nothing that names alpha.
        File(t, "old").mkdirs()
        recover()
        assertEquals("old", store.body("alpha"))
    }

    @Test fun skipNotesSurviveTheStatusRoundTrip() {
        val status = SkillSyncStatus(1, "partial", counts = SkillSyncCounts(added = 1, skipped = 1, notes = listOf("deploy: clash")))
        assertEquals(status, SkillSyncStatus.parse(status.toJson()))
    }

    @Test fun validEmptyRepositoryRemovesAllSourceMirrors() = runBlocking {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        store.install(candidate("manual", "keep"), "manual")
        commit(); zip(archive())
        assertEquals(SkillSyncCounts(deleted = 1), engine.sync(source).counts)
        assertNull(store.get("alpha")); assertEquals("keep", store.body("manual"))
    }

    @Test fun brokenArchiveDoesNotEraseOldMirrors() = runBlocking {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        commit(); zip("not a zip".toByteArray())
        assertEquals("error", engine.sync(source).result)
        assertEquals("old", store.body("alpha"))
    }

    @Test fun configReplacedDuringDownloadCannotCommitAnObsoleteSource() = runBlocking {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        val guarded = SkillSyncEngine(store, tmp.newFolder("guarded-cache"), server.url("/").toString(), OkHttpClient(), sourceIsCurrent = { false })
        commit(); zip(archive("alpha" to "new", "beta" to "new"))
        val status = guarded.sync(source)
        assertEquals("error", status.result)
        assertTrue(status.error!!.contains("source changed"))
        assertEquals("old", store.body("alpha")); assertNull(store.get("beta"))
    }

    @Test fun configuredFolderIsRespected() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for (path in listOf("chosen/alpha", "elsewhere/beta")) {
                zip.putNextEntry(ZipEntry("repo-sha/$path/SKILL.md")); zip.write(skillMd(path.substringAfterLast('/')).toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        commit(); zip(bytes)
        assertEquals("ok", engine.sync(source.copy(paths = listOf("chosen"))).result)
        assertEquals(listOf("alpha"), store.list().map { it.name })
    }

    @Test fun sourceConfigChangeForcesDownloadAtSameCommit() = runBlocking {
        commit(); zip(archive("alpha" to "one"))
        val previous = SkillSyncStatus(1, "ok", commitSha = sha1, configKey = source.copy(paths = listOf("elsewhere")).configKey)
        assertEquals(1, engine.sync(source, previous = previous).counts.added)
        assertEquals(2, server.requestCount)
    }

    @Test fun unexpectedRedirectHostIsRejectedBeforeSendingTheToken() = runBlocking {
        val other = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal-token")))
            val status = engine.sync(source, "ghp_secret")
            assertEquals("error", status.result)
            assertTrue(status.error!!.contains("unexpected download host"))
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun multipleRepositoryFoldersAreScannedTogether() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for (path in listOf("one/alpha", "two/beta", "ignored/gamma")) {
                zip.putNextEntry(ZipEntry("repo-sha/$path/SKILL.md")); zip.write(skillMd(path.substringAfterLast('/')).toByteArray()); zip.closeEntry()
            }
        } }.toByteArray()
        commit(); zip(bytes)
        assertEquals(2, engine.sync(source.copy(paths = listOf("one", "two"))).counts.added)
        assertEquals(listOf("alpha", "beta"), store.list().map { it.name })
    }

    @Test fun tokenIsSentWithGithubHeadersAndResponseBodiesAreNeverExposed() = runBlocking {
        val token = "ghp_doNotLeakThisToken"
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret $token"))
        val status = engine.sync(source, token)
        val request = server.takeRequest()
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("error", status.result); assertFalse(status.toJson().toString().contains(token))
        assertTrue(status.error!!.contains("authentication failed"))
    }

    @Test fun tokenIsRedactedEvenFromTransportException() = runBlocking {
        val token = "ghp_verySecretValue"
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("failure with $token") }.build()
        val failing = SkillSyncEngine(store, tmp.newFolder("other-cache"), server.url("/").toString(), client)
        val status = failing.sync(source, token)
        assertEquals("error", status.result)
        assertFalse(status.error!!.contains(token)); assertTrue(status.error!!.contains("[redacted]"))
    }

    @Test fun oversizedDownloadKeepsOldMirrors() = runBlocking {
        val capped = SkillStore(tmp.newFolder("capped"), limits = SkillLimits(maxArchiveBytes = 10))
        capped.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        val small = SkillSyncEngine(capped, tmp.newFolder("small-cache"), server.url("/").toString(), OkHttpClient())
        commit(); zip(archive("alpha" to "new"))
        assertEquals("error", small.sync(source).result)
        assertEquals("old", capped.body("alpha"))
    }

    @Test fun manualImportCannotOverwriteMirroredSkill() {
        store.mirror(source.id, listOf(candidate("alpha", "old")), source.url)
        val result = store.install(candidate("alpha", "new"), "manual", replace = true)
        assertTrue(result is InstallOutcome.Failed)
        assertTrue((result as InstallOutcome.Failed).reason.contains("Managed by GitHub source"))
        assertEquals("old", store.body("alpha"))
    }
}

class SkillSyncInputsTest {
    @Test fun repositoryInputParsesUrlsFoldersAndUniqueIds() {
        assertEquals(SkillSyncSource("acme-repo", "acme", "repo"), SkillSyncInputs.parseRepository("acme/repo.git"))
        val tree = SkillSyncInputs.parseRepository("github.com/acme/repo/tree/trunk/skills/my%20folder")
        assertEquals("trunk", tree.ref); assertEquals(listOf("skills/my folder"), tree.paths)
        assertEquals("acme-repo-2", SkillSyncInputs.parseRepository("acme/repo", listOf("acme-repo")).id)
        assertEquals("", SkillSyncInputs.parseRepository("https://github.com/acme/repo").ref)
        assertEquals("repo", SkillSyncInputs.parseRepository("HTTPS://GitHub.com/acme/repo").repo)
        assertEquals("repo", SkillSyncInputs.parseRepository("https://github.com/acme/repo?tab=readme-ov-file#readme").repo)
    }
    @Test fun malformedRepositoriesAreRefused() {
        listOf("repo", "https://example.org/o/r", "http://github.com/o/r", "https://secret@github.com/o/r", "https://github.com/o/r/tree/main/../../escape", "https://github.com/o/r/releases/download/x").forEach {
            assertTrue(it, runCatching { SkillSyncInputs.parseRepository(it) }.isFailure)
        }
    }
    @Test fun intervalsRoundTripAndEnforceBounds() {
        listOf(15, 60, 90, 1440, 10_080).forEach { minutes ->
            val (value, unit) = SkillSyncInputs.splitInterval(minutes)
            assertEquals(minutes, SkillSyncInputs.joinInterval(value, unit))
        }
        assertEquals(90, SkillSyncInputs.joinInterval("1.5", "hours"))
        assertEquals(15, SkillSyncInputs.joinInterval("14.5", "minutes"))
        listOf("0", "14", "10081", "NaN", "Infinity", "no").forEach { assertTrue(runCatching { SkillSyncInputs.joinInterval(it, "minutes") }.isFailure) }
        assertTrue(runCatching { SkillSyncConfig(intervalMinutes = 14).validated() }.isFailure)
    }
    @Test fun tokenPasteChecksAndRedaction() {
        assertEquals("", SkillSyncInputs.checkToken("")); assertEquals("ghp_secret", SkillSyncInputs.checkToken("ghp_secret"))
        listOf("ghp_secret\n", "Bearer ghp_secret", "prefixghp_secret", "ghp_secretghp_secret").forEach { assertTrue(runCatching { SkillSyncInputs.checkToken(it) }.isFailure) }
        assertEquals("oops [redacted] [redacted]", SkillSyncInputs.redact("oops custom-token ghp_otherSecret", "custom-token"))
    }
    @Test fun configAndStatusRoundTripWithoutTokens() {
        val config = SkillSyncConfig(true, 120, listOf(SkillSyncInputs.parseRepository("o/r")))
        assertEquals(config, SkillSyncConfig.parse(config.toJson().toString()))
        assertFalse(config.toJson().toString().contains("token"))
        val status = SkillSyncStatus(20, "partial", commitSha = "abc", configKey = "key", counts = SkillSyncCounts(1, 2, 3, 4))
        assertEquals(status, SkillSyncStatus.parse(status.toJson()))
    }
}
