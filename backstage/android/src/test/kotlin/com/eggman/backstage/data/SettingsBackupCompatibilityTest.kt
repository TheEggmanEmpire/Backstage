package com.eggman.backstage.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private object Backup031Fixture {
    // Intentionally independent of today's allowlist. This is the complete allowlist from HEAD.
    val files = setOf("settings", "backstage_quick_actions", "backstage_schedules", "backstage_bubble",
        "backstage_widgets", "backstage_a11y", "backstage_composer")

    // Fixed UTF-8 manifest with the exact format-1 encoding emitted by export, without new root keys.
    private val manifest = """
        {
          "format": 1,
          "app": "com.eggman.backstage",
          "versionName": "1.0.0",
          "exportedAt": "2026-10-01T00:00:00Z",
          "prefs": {
            "settings": {
              "persona": {"t": "s", "v": "Legacy persona"},
              "key_OPENAI": {"t": "s", "v": "legacy-api-key", "secret": true},
              "voice_tts": {"t": "b", "v": true}
            },
            "backstage_quick_actions": {},
            "backstage_schedules": {"tasks": {"t": "s", "v": "{\"v\":1,\"tasks\":[]}"}},
            "backstage_bubble": {"x": {"t": "i", "v": 12}},
            "backstage_widgets": {},
            "backstage_a11y": {"user_turned_off": {"t": "b", "v": false}},
            "backstage_composer": {"composer_draft": {"t": "s", "v": "Unsent legacy draft"}}
          },
          "unrecoverableSecrets": []
        }
    """.trimIndent()

    fun archive(): ByteArray = zip(linkedMapOf(
        SettingsBackup.MANIFEST to manifest.toByteArray(Charsets.UTF_8),
        "skills/.tmp-x-1/SKILL.md" to "---\nname: x\ndescription: Leftover\n---\nOld install".toByteArray(),
        "skills/manual/.references/keep.txt" to "keep nested hidden files".toByteArray(),
        "skills/manual/SKILL.md" to "---\nname: manual\ndescription: Legacy manual skill\n---\nInstructions".toByteArray(),
        "skills/state.json" to "{\"skills\":{\"manual\":{\"enabled\":false,\"source\":\"file: manual.zip\",\"installedMs\":12,\"builtin\":false}}}".toByteArray(),
    ))

    fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    fun manifest(zip: ByteArray): JSONObject = ZipInputStream(ByteArrayInputStream(zip)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: error("Missing manifest")
            if (entry.name == SettingsBackup.MANIFEST) return@use JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }
        @Suppress("UNREACHABLE_CODE") error("Missing manifest")
    }

    fun minimalRoot(): JSONObject = JSONObject().put("format", 1).put("app", "com.eggman.backstage")
        .put("versionName", "test").put("exportedAt", Instant.EPOCH.toString()).put("prefs", JSONObject())

    fun archive(root: JSONObject): ByteArray = zip(mapOf(SettingsBackup.MANIFEST to root.toString().toByteArray()))
}

class SettingsBackupCompatibilityTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc1:new:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc1:new:")
    }

    private class Store : SettingsBackup.Store {
        var writes = 0
        var snapshot: SettingsBackup.Snapshot? = null
        override fun replace(snapshot: SettingsBackup.Snapshot) { writes++; this.snapshot = snapshot }
    }

    @Test fun byteStable031FixtureImportsPersonaPlaintextCredentialsAndDropsOldLeftovers() {
        val fixture = Backup031Fixture.archive()
        assertArrayEquals(fixture, Backup031Fixture.archive())
        assertTrue(legacyImportAccepts(fixture))
        val store = Store()
        val result = SettingsBackup.import(ByteArrayInputStream(fixture), cipher, store)
        assertEquals(1, store.writes)
        assertEquals(Backup031Fixture.files, store.snapshot!!.prefs.keys)
        assertEquals("Legacy persona", store.snapshot!!.prefs["settings"]!!["persona"])
        assertEquals("enc1:new:legacy-api-key", store.snapshot!!.prefs["settings"]!!["key_OPENAI"])
        assertEquals(setOf("state.json", "manual/SKILL.md", "manual/.references/keep.txt"), store.snapshot!!.skills.keys)
        val state = JSONObject(store.snapshot!!.skills.getValue("state.json").toString(Charsets.UTF_8))
        assertFalse(state.getJSONObject("skills").getJSONObject("manual").has("syncSource"))
        assertFalse(state.getJSONObject("skills").getJSONObject("manual").getBoolean("enabled"))
        val kept = result.warnings.single { it.startsWith("Not in this backup, kept as they are: ") }
        (SettingsBackup.preferenceFiles - Backup031Fixture.files).forEach { assertTrue(kept.contains(it)) }
        assertTrue(result.summary().contains(kept))
    }

    @Test fun newExportKeepsLegacyPrefsAndMovesEveryNewFileToPrefsExtra() {
        val output = ByteArrayOutputStream()
        val snapshot = SettingsBackup.Snapshot(SettingsBackup.preferenceFiles.associateWith { emptyMap() },
            warnings = listOf("fixture warning"),
            reportDestinations = "{\"maintainer\":\"user@example.com\",\"provider\":\"\"}")
        val result = SettingsBackup.export(snapshot, cipher, output, "test", Instant.EPOCH)
        val bytes = output.toByteArray()
        val manifest = Backup031Fixture.manifest(bytes)
        assertEquals(Backup031Fixture.files, manifest.getJSONObject("prefs").keys().asSequence().toSet())
        assertEquals(SettingsBackup.preferenceFiles - Backup031Fixture.files, manifest.getJSONObject("prefsExtra").keys().asSequence().toSet())
        assertEquals(SettingsBackup.preferenceFiles.size, result.prefFiles)
        assertTrue(manifest.has("warnings")); assertTrue(manifest.has("reportDestinations"))
        assertTrue(legacyImportAccepts(bytes)) // HEAD reads only known root keys; all three additions are ignored.
        val store = Store()
        val imported = SettingsBackup.import(ByteArrayInputStream(bytes), cipher, store)
        assertEquals(SettingsBackup.preferenceFiles, store.snapshot!!.prefs.keys)
        assertEquals(listOf("fixture warning"), imported.warnings)
    }

    @Test fun legacyAcceptanceProbeRejectsNewFilesInsideLegacyPrefsAndStrayZipEntries() {
        val root = Backup031Fixture.minimalRoot().put("prefs", JSONObject().put("FlutterSharedPreferences", JSONObject()))
        assertFalse(legacyImportAccepts(Backup031Fixture.archive(root)))
        assertFalse(legacyImportAccepts(Backup031Fixture.zip(mapOf(
            SettingsBackup.MANIFEST to Backup031Fixture.minimalRoot().toString().toByteArray(), "unexpected" to byteArrayOf(1)))))
        assertFalse(legacyImportAccepts(Backup031Fixture.archive(Backup031Fixture.minimalRoot().put("format", 2))))
    }

    @Test fun knownNewPreferenceFileImportsFromEitherRootWithSecretEncryption() {
        for (group in listOf("prefs", "prefsExtra")) {
            val sync = JSONObject().put("token_repo", JSONObject().put("t", "s").put("v", "github-token"))
            val root = Backup031Fixture.minimalRoot().put(group, JSONObject().put("backstage_skill_sync", sync))
            val store = Store()
            val result = SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive(root)), cipher, store)
            assertEquals("enc1:new:github-token", store.snapshot!!.prefs["backstage_skill_sync"]!!["token_repo"])
            assertEquals(1, result.prefFiles)
        }
    }

    @Test fun newerFormatExplainsRequiredUpdateBeforeAnyWrite() {
        val store = Store()
        val error = assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive(Backup031Fixture.minimalRoot().put("format", 2))), cipher, store)
        }
        assertEquals("This backup was made by a newer Backstage version (format 2); update Backstage first.", error.message)
        assertEquals(0, store.writes)
    }

    @Test fun unknownPrefsExtraFileIsSkippedWithWarningEvenWithFutureValueEncoding() {
        val root = Backup031Fixture.minimalRoot().put("prefsExtra", JSONObject()
            .put("future_preferences", "future encoding, intentionally not a JSONObject")
            .put("FlutterSharedPreferences", JSONObject().put("flutter.scale", JSONObject().put("t", "s").put("v", "encoded-double"))))
        val store = Store()
        val result = SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive(root)), cipher, store)
        assertEquals(1, store.writes)
        assertEquals(setOf("FlutterSharedPreferences"), store.snapshot!!.prefs.keys)
        assertEquals("encoded-double", store.snapshot!!.prefs["FlutterSharedPreferences"]!!["flutter.scale"])
        assertTrue(result.warnings.any { it.contains("future_preferences") && it.contains("Skipped") })
    }

    @Test fun unknownLegacyPrefsFileStillRejectsBeforeWriting() {
        val store = Store()
        val root = Backup031Fixture.minimalRoot().put("prefs", JSONObject().put("future_preferences", JSONObject()))
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive(root)), cipher, store)
        }
        assertEquals(0, store.writes)
    }

    @Test fun duplicatePreferenceFileAcrossRootsRejectsBeforeWriting() {
        val root = Backup031Fixture.minimalRoot().put("prefs", JSONObject().put("settings", JSONObject()))
            .put("prefsExtra", JSONObject().put("settings", JSONObject()))
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive(root)), cipher, store)
        }
        assertEquals(0, store.writes)
    }

    /** Compatibility probe copied from the relevant import rules, never today's allowlist/parser. */
    private fun legacyImportAccepts(bytes: ByteArray): Boolean = runCatching {
        val files = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var total = 0L
        fun safe(path: String) {
            require(path.isNotEmpty() && path.length <= 4096 && !path.contains('\\') && !path.contains(':') &&
                path.none { it.code < 32 } && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                safe(if (entry.isDirectory) name.removeSuffix("/") else name)
                require(seen.add(name) && seen.size <= 10_000)
                require(name == SettingsBackup.MANIFEST && !entry.isDirectory || name.startsWith("skills/") || name == "skills/" && entry.isDirectory)
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 64L * 1024 * 1024)
                    data.write(buffer, 0, count)
                }
                if (entry.isDirectory) require(data.size() == 0) else files[name] = data.toByteArray()
                zip.closeEntry()
            }
        }
        val parser = JSONTokener(files.remove(SettingsBackup.MANIFEST)!!.toString(Charsets.UTF_8))
        val root = parser.nextValue() as JSONObject
        require(parser.nextClean() == '\u0000')
        require(root.get("format") is Number && root.get("format").toString().toLong() == 1L)
        require(root.get("app") == "com.eggman.backstage")
        require(root.get("versionName") is String && root.get("exportedAt") is String)
        Instant.parse(root.getString("exportedAt"))
        val prefs = root.getJSONObject("prefs")
        prefs.keys().forEach { file ->
            require(file in Backup031Fixture.files)
            val entries = prefs.getJSONObject(file)
            entries.keys().forEach { key ->
                val item = entries.getJSONObject(key)
                val value = item.get("v")
                when (item.get("t")) {
                    "s" -> require(value is String && !value.startsWith("enc1:"))
                    "i" -> require(value is Number && value.toString().toLong() in Int.MIN_VALUE..Int.MAX_VALUE)
                    "l" -> require(value is Number && value.toString().toLongOrNull() != null)
                    "b" -> require(value is Boolean)
                    "f" -> require(value is Number && value.toFloat().isFinite())
                    "ss" -> require(value is JSONArray && (0 until value.length()).all { value.get(it) is String })
                    else -> error("Unknown preference type")
                }
                require(!item.has("secret") || item.get("secret") is Boolean)
                require(!item.optBoolean("secret", false) || value is String)
            }
        }
        val paths = files.keys.map { it.removePrefix("skills/") }.toSet()
        paths.forEach { path ->
            safe(path)
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) { require(parent !in paths); parent = parent.substringBeforeLast('/', "") }
        }
        if (root.has("unrecoverableSecrets")) {
            val lost = root.getJSONArray("unrecoverableSecrets")
            require((0 until lost.length()).all { lost.get(it) is String })
        }
        // Deliberately do not enumerate root keys: HEAD ignores prefsExtra, warnings and reportDestinations.
    }.isSuccess
}

@RunWith(RobolectricTestRunner::class)
class SettingsBackupCompatibilityAndroidTest {
    @Test fun oldImportLeavesAllAbsentPreferenceFilesAndReportDestinationsUntouched() {
        val context = RuntimeEnvironment.getApplication()
        val missing = SettingsBackup.preferenceFiles - Backup031Fixture.files
        missing.forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().putString("untouched", name).commit() }
        val report = java.io.File(context.noBackupFilesDir, "ai-reports/destinations.json").apply {
            parentFile!!.mkdirs(); writeText("{\"maintainer\":\"kept@example.com\",\"provider\":\"\"}")
        }
        val imported = SettingsBackup.import(ByteArrayInputStream(Backup031Fixture.archive()),
            object : SecretCipher { override fun encrypt(plain: String) = "enc1:new:$plain"; override fun decrypt(stored: String) = stored },
            SettingsBackup.AndroidStore(context))
        missing.forEach { name -> assertEquals(mapOf("untouched" to name), context.getSharedPreferences(name, Context.MODE_PRIVATE).all) }
        assertTrue(report.readText().contains("kept@example.com"))
        assertEquals("Legacy persona", context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("persona", null))
        assertFalse(java.io.File(context.filesDir, "skills/.tmp-x-1").exists())
        assertTrue(imported.warnings.any { it.startsWith("Not in this backup, kept as they are: ") })
    }
}
