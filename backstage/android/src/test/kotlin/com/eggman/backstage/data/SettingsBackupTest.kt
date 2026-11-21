package com.eggman.backstage.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SettingsBackupTest {
    private class Cipher(private val phone: String) : SecretCipher {
        override fun encrypt(plain: String) = "enc1:$phone:$plain"
        override fun decrypt(stored: String) = stored.takeIf { it.startsWith("enc1:$phone:") }
            ?.removePrefix("enc1:$phone:")
    }

    private class Store : SettingsBackup.Store {
        var writes = 0
        var snapshot = SettingsBackup.Snapshot(mapOf("settings" to mapOf("stale" to true)))
        override fun replace(snapshot: SettingsBackup.Snapshot) {
            writes++
            this.snapshot = this.snapshot.copy(prefs = this.snapshot.prefs + snapshot.prefs, skills = snapshot.skills)
        }
    }

    private fun backup(prefs: Map<String, Map<String, Any?>>, skills: Map<String, ByteArray> = emptyMap()): Pair<ByteArray, SettingsBackup.Result> {
        val out = ByteArrayOutputStream()
        val result = SettingsBackup.export(SettingsBackup.Snapshot(prefs, skills), Cipher("old"), out, "test", Instant.EPOCH)
        return out.toByteArray() to result
    }

    private fun archive(json: JSONObject, vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            (listOf(SettingsBackup.MANIFEST to json.toString().toByteArray()) + files).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun manifest(bytes: ByteArray): JSONObject = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: error("Missing manifest")
            if (entry.name == SettingsBackup.MANIFEST) return@use JSONObject(String(zip.readBytes(), Charsets.UTF_8))
        }
        @Suppress("UNREACHABLE_CODE") error("Missing manifest")
    }

    private fun root() = JSONObject().put("format", 1).put("app", "com.eggman.backstage")
        .put("versionName", "test").put("exportedAt", Instant.EPOCH.toString()).put("prefs", JSONObject())

    private fun rejected(bytes: ByteArray) {
        val store = Store()
        assertThrows(Exception::class.java) { SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store) }
        assertEquals(0, store.writes)
        assertEquals(mapOf("stale" to true), store.snapshot.prefs["settings"])
    }

    @Test fun secretsAndSkillsRoundTripAcrossPhones() {
        val remote = JSONArray().put(JSONObject().put("id", "server").put("token", "enc1:old:remote-key").put("url", "https://example.com")).toString()
        val (bytes, result) = backup(mapOf("settings" to mapOf("key_OPENAI" to "enc1:old:api-key", "remote_mcp" to remote)),
            mapOf("my-skill/SKILL.md" to "# Skill".toByteArray(), "my-skill/icon.bin" to byteArrayOf(0, 1, -1)))
        val values = manifest(bytes).getJSONObject("prefs").getJSONObject("settings")
        assertEquals("api-key", values.getJSONObject("key_OPENAI").getString("v"))
        assertTrue(values.getJSONObject("key_OPENAI").getBoolean("secret"))
        assertFalse(values.getJSONObject("remote_mcp").getString("v").contains("enc1:"))
        assertEquals(2, result.skillFiles)
        val store = Store()
        val restored = SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals("enc1:new:api-key", store.snapshot.prefs["settings"]!!["key_OPENAI"])
        val servers = JSONArray(store.snapshot.prefs["settings"]!!["remote_mcp"] as String)
        assertEquals("enc1:new:remote-key", servers.getJSONObject(0).getString("token"))
        assertArrayEquals(byteArrayOf(0, 1, -1), store.snapshot.skills["my-skill/icon.bin"])
        assertEquals(2, restored.settings)
        assertEquals(1, store.writes)
    }

    @Test fun undecryptableSecretsAreAbsentAndReportedWithoutCiphertext() {
        val remote = "[{\"token\":\"enc1:lost:remote-key\",\"id\":\"server\"}]"
        val (bytes, result) = backup(mapOf("settings" to mapOf("key_OPENAI" to "enc1:lost:api-key", "remote_mcp" to remote)))
        val values = manifest(bytes).getJSONObject("prefs").getJSONObject("settings")
        assertFalse(values.has("key_OPENAI"))
        assertFalse(values.toString().contains("enc1:"))
        assertEquals(listOf("settings/key_OPENAI", "settings/remote_mcp[0].token"), result.unrecoverableSecrets)
        assertTrue(result.warnings.isNotEmpty())
        val store = Store()
        val imported = SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(result.unrecoverableSecrets, imported.unrecoverableSecrets)
        assertFalse(JSONArray(store.snapshot.prefs["settings"]!!["remote_mcp"] as String).getJSONObject(0).has("token"))
    }

    @Test fun wrongFormatOrAppIsRejectedWithoutWriting() {
        rejected(archive(root().put("format", 2)))
        rejected(archive(root().put("format", "1")))
        rejected(archive(root().put("app", "another.app")))
    }

    @Test fun zipSlipAndAmbiguousPathsAreRejectedWithoutWriting() {
        listOf("skills/../../escape", "../escape", "/skills/file", "skills/..\\escape", "skills/a//file", "skills/C:/file").forEach {
            rejected(archive(root(), it to byteArrayOf(1)))
        }
    }

    @Test fun allPreferenceTypesArePreservedAndStaleKeysAreCleared() {
        val values = mapOf("s" to "日本語", "i" to Int.MIN_VALUE, "l" to Long.MAX_VALUE, "b" to true,
            "f" to 0.125f, "ss" to setOf("one", "two"), "emptySet" to emptySet<String>())
        val (bytes, _) = backup(mapOf("settings" to values, "backstage_bubble" to emptyMap()))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(values, store.snapshot.prefs["settings"])
        assertFalse(store.snapshot.prefs["settings"]!!.containsKey("stale"))
        assertEquals(emptyMap<String, Any?>(), store.snapshot.prefs["backstage_bubble"])
    }

    @Test fun malformedLatePreferenceAndUnknownFilesAreRejectedBeforeWriting() {
        val prefs = JSONObject().put("settings", JSONObject()
            .put("valid", JSONObject().put("t", "b").put("v", true))
            .put("invalid", JSONObject().put("t", "i").put("v", 2147483648L)))
        rejected(archive(root().put("prefs", prefs)))
        rejected(archive(root().put("prefs", JSONObject().put("chat_threads", JSONObject()))))
        rejected(archive(root(), "chat-history.json" to byteArrayOf(1)))
        rejected(archive(root(), "skills/a" to byteArrayOf(1), "skills/a/file" to byteArrayOf(2)))
    }

    @Test fun malformedValueTypesAndSecretFlagsAreRejected() {
        listOf(
            JSONObject().put("t", "b").put("v", "true"),
            JSONObject().put("t", "ss").put("v", JSONArray().put(1)),
            JSONObject().put("t", "s").put("v", "key").put("secret", "true"),
            JSONObject().put("t", "i").put("v", 1).put("secret", true),
            JSONObject().put("t", "s").put("v", "enc1:old:ciphertext"),
        ).forEach { value ->
            rejected(archive(root().put("prefs", JSONObject().put("settings", JSONObject().put("bad", value)))))
        }
    }

    @Test fun encryptionFailureDoesNotWriteAnything() {
        val bytes = backup(mapOf("settings" to mapOf("key_OPENAI" to "enc1:old:api-key"))).first
        val store = Store()
        assertThrows(Exception::class.java) { SettingsBackup.import(ByteArrayInputStream(bytes), SecretCipher.Plain, store) }
        assertEquals(0, store.writes)
    }

    @Test fun legacyPlaintextSecretKeysAreReencryptedToo() {
        val (bytes, _) = backup(mapOf("settings" to mapOf("key_OPENAI" to "legacy-key", "mcp_token" to "token", "search_custom_header_value" to "header")))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals("enc1:new:legacy-key", store.snapshot.prefs["settings"]!!["key_OPENAI"])
        assertEquals("enc1:new:token", store.snapshot.prefs["settings"]!!["mcp_token"])
        assertEquals("enc1:new:header", store.snapshot.prefs["settings"]!!["search_custom_header_value"])
    }

    @Test fun newPreferenceFilesRoundTripWithFlutterEncodingsAndSyncSecrets() {
        val flutter = mapOf(
            "flutter.display_scale" to "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu1.25",
            "flutter.apps" to "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!['a','b']",
            "flutter.legacy_list" to "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3QurO0ABXcEAAAAAA==",
            "flutter.width" to 1920L, "flutter.enabled" to true,
            "flutter.setup_completed" to true,
        )
        val (bytes, _) = backup(mapOf(
            "FlutterSharedPreferences" to flutter,
            "dextop_privilege_provider" to mapOf("selected" to "stellar", "last_mode" to "embedded"),
            "backstage_skill_sync" to mapOf("config" to "{\"sources\":[]}", "token_private" to "enc1:old:github-token", "token_legacy" to "legacy-token", "status" to "busy"),
        ))
        val sync = manifest(bytes).getJSONObject("prefsExtra").getJSONObject("backstage_skill_sync")
        assertEquals("github-token", sync.getJSONObject("token_private").getString("v"))
        assertTrue(sync.getJSONObject("token_private").getBoolean("secret"))
        assertTrue(sync.getJSONObject("token_legacy").getBoolean("secret"))
        assertFalse(sync.has("status"))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(flutter - "flutter.setup_completed", store.snapshot.prefs["FlutterSharedPreferences"])
        assertEquals(mapOf("selected" to "stellar"), store.snapshot.prefs["dextop_privilege_provider"])
        assertEquals("enc1:new:github-token", store.snapshot.prefs["backstage_skill_sync"]!!["token_private"])
        assertEquals("enc1:new:legacy-token", store.snapshot.prefs["backstage_skill_sync"]!!["token_legacy"])
        assertEquals("{\"sources\":[]}", store.snapshot.prefs["backstage_skill_sync"]!!["config"])
    }

    @Test fun entryLimitOmitsWholeLargestSkillAndPreservesSettings() {
        val crowded = (0 until 10_000).associate { "crowded/ref-$it.txt" to byteArrayOf(1) } +
            mapOf("crowded/SKILL.md" to byteArrayOf(1), "small/SKILL.md" to byteArrayOf(1))
        val (bytes, exported) = backup(mapOf("settings" to mapOf("model_OPENAI" to "saved-model")), crowded)
        assertEquals(listOf("Not backed up (too large): crowded"), exported.warnings)
        val store = Store()
        val restored = SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(setOf("small/SKILL.md"), store.snapshot.skills.keys)
        assertEquals("saved-model", store.snapshot.prefs["settings"]!!["model_OPENAI"])
        assertEquals(exported.warnings, restored.warnings.filter { it.startsWith("Not backed up (too large): ") })
    }

    @Test fun malformedOptionalConfigurationStillRejectsBeforeAnyWrite() {
        rejected(archive(root().put("reportDestinations", JSONObject().put("maintainer", "bad\naddress").put("provider", ""))))
        rejected(archive(root().put("warnings", JSONArray().put(42))))
        val (bytes, _) = backup(mapOf("backstage_skill_sync" to mapOf("token_repo" to "github-token")))
        val store = Store()
        assertThrows(Exception::class.java) { SettingsBackup.import(ByteArrayInputStream(bytes), SecretCipher.Plain, store) }
        assertEquals(0, store.writes)
    }

    @Test fun nativeDesktopChoicesRoundTripWithoutSessionRecoveryData() {
        val (bytes, _) = backup(mapOf(
            "freedextop_input" to mapOf("direct_touch" to true, "overlay_button_order" to "home,back"),
            "dextop_display_environment" to mapOf("software_cursor_fallback" to true, "legacy_topology_migrated" to true),
            "dextop_topology_layout" to mapOf("saved_arrangement_v1" to "{}"),
            "dextop_plasma" to mapOf("favorites" to "pkg.one", "pinned" to "pkg.two", "recent" to "pkg.private"),
            "dextop_auto_ui" to mapOf("theme" to "dark", "rail_width" to 72),
            "dextop_auto_runtime" to mapOf("favorites" to "pkg.one", "auto_session" to "running"),
            "dextop_linux_controllers" to mapOf("android" to setOf("controller")),
            "linux-store-sources" to mapOf("apt" to true, "flathub" to false),
            "linux-execution" to mapOf("mode" to "android"),
            "linux_android_settings" to mapOf("theme" to "dark", "clipboard_sync" to false),
            "linux_shortcut_settings" to mapOf("install_mode" to "ask", "launch_key" to "device-bound-key"),
            "linux-usb" to mapOf("policy" to "each", "enabled" to setOf("0001:0002:serial")),
        ))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(true, store.snapshot.prefs["freedextop_input"]!!["direct_touch"])
        assertEquals(mapOf("software_cursor_fallback" to true), store.snapshot.prefs["dextop_display_environment"])
        assertEquals(mapOf("favorites" to "pkg.one", "pinned" to "pkg.two"), store.snapshot.prefs["dextop_plasma"])
        assertEquals(mapOf("favorites" to "pkg.one"), store.snapshot.prefs["dextop_auto_runtime"])
        assertEquals(mapOf("android" to setOf("controller")), store.snapshot.prefs["dextop_linux_controllers"])
        assertEquals(mapOf("apt" to true, "flathub" to false), store.snapshot.prefs["linux-store-sources"])
        assertEquals(mapOf("mode" to "android"), store.snapshot.prefs["linux-execution"])
        assertEquals(mapOf("theme" to "dark", "clipboard_sync" to false), store.snapshot.prefs["linux_android_settings"])
        assertEquals(mapOf("install_mode" to "ask"), store.snapshot.prefs["linux_shortcut_settings"])
        assertEquals(mapOf("policy" to "each", "enabled" to setOf("0001:0002:serial")), store.snapshot.prefs["linux-usb"])
    }

    @Test fun oldFormatOneWithoutNewOptionalFilesStillImports() {
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(archive(root().put("prefs", JSONObject().put("settings",
            JSONObject().put("model_OPENAI", JSONObject().put("t", "s").put("v", "saved-model")))))), Cipher("new"), store)
        assertEquals("saved-model", store.snapshot.prefs["settings"]!!["model_OPENAI"])
        assertFalse(store.snapshot.prefs.containsKey("backstage_skill_sync"))
        assertEquals(1, store.writes)
    }

    @Test fun aiCredentialsAndOmpPairingTokensMigrateReencrypted() {
        val (bytes, _) = backup(mapOf("settings" to mapOf(
            "key_CHATGPT" to "enc1:old:oauth-tokens", "omp_harness_token" to "enc1:old:local",
            "omp_termux_token" to "enc1:old:local-termux", "omp_enabled" to true,
            "voice_tts" to true, "app_rules" to "{}", "memory" to true,
        )))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        val settings = store.snapshot.prefs["settings"]!!
        assertEquals("enc1:new:oauth-tokens", settings["key_CHATGPT"])
        assertEquals("enc1:new:local", settings["omp_harness_token"])
        assertEquals("enc1:new:local-termux", settings["omp_termux_token"])
        assertEquals(true, settings["omp_enabled"])
        assertEquals(true, settings["voice_tts"])
    }

    @Test fun runtimeHistoryIsRemovedButUserChoicesAndScheduleDedupeAreKept() {
        val tasks = "{\"v\":1,\"tasks\":[{\"id\":\"task\",\"lastStatus\":\"RUNNING\",\"lastResult\":\"private result\",\"lastRunAtMs\":123,\"nextAtMs\":456}]}"
        val (bytes, _) = backup(mapOf(
            "backstage_a11y" to mapOf("user_turned_off" to true, "last_self_write_ms" to 99L),
            "backstage_schedules" to mapOf("tasks" to tasks),
            "backstage_bubble" to mapOf("x" to 42, "y" to 84, "dismissed" to true),
            "backstage_widgets" to mapOf("w12" to "quick-action"),
            "backstage_composer" to mapOf("composer_draft" to "unsent text"),
        ))
        val store = Store()
        SettingsBackup.import(ByteArrayInputStream(bytes), Cipher("new"), store)
        assertEquals(mapOf("user_turned_off" to true), store.snapshot.prefs["backstage_a11y"])
        val task = JSONObject(store.snapshot.prefs["backstage_schedules"]!!["tasks"] as String).getJSONArray("tasks").getJSONObject(0)
        assertFalse(task.has("lastStatus")); assertFalse(task.has("lastResult"))
        assertEquals(123L, task.getLong("lastRunAtMs")); assertEquals(456L, task.getLong("nextAtMs"))
        assertEquals(42, store.snapshot.prefs["backstage_bubble"]!!["x"])
        assertEquals("quick-action", store.snapshot.prefs["backstage_widgets"]!!["w12"])
        assertEquals("unsent text", store.snapshot.prefs["backstage_composer"]!!["composer_draft"])
    }

    @Test fun unparsableSkillSyncConfigIsRejectedBeforeWriting() {
        val bad = JSONObject().put("config", JSONObject().put("t", "s").put("v", "{\"intervalMinutes\":5,\"sources\":[]}"))
        rejected(archive(root().put("prefsExtra", JSONObject().put("backstage_skill_sync", bad))))
        val garbage = JSONObject().put("config", JSONObject().put("t", "s").put("v", "not json"))
        rejected(archive(root().put("prefsExtra", JSONObject().put("backstage_skill_sync", garbage))))
    }
}

@RunWith(RobolectricTestRunner::class)
class SettingsBackupAndroidTest {
    @Test fun androidStoreClearsPreferenceFilesAndReplacesSkills() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("stale", "old").commit()
        val root = File(context.filesDir, "skills").apply { mkdirs() }
        File(root, "stale.md").writeText("old")
        SettingsBackup.AndroidStore(context).replace(SettingsBackup.Snapshot(
            mapOf("settings" to mapOf("new" to 7, "float" to 0.25f, "set" to setOf("a", "b"))),
            mapOf("my-skill/SKILL.md" to "# New".toByteArray()),
        ))
        assertFalse(prefs.contains("stale"))
        assertEquals(7, prefs.getInt("new", 0))
        assertEquals(0.25f, prefs.getFloat("float", 0f))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
        assertFalse(File(root, "stale.md").exists())
        assertEquals("# New", File(root, "my-skill/SKILL.md").readText())
    }

    private fun skill(root: File, name: String): File = File(root, name).apply {
        mkdirs()
        File(this, "SKILL.md").writeText("---\nname: $name\ndescription: Test\n---\nInstructions")
    }

    @Test fun snapshotSkipsInstallLeftoversAndMirrorsButKeepsStateAndManualSkills() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        skill(root, "manual")
        skill(root, "mirror")
        skill(root, ".tmp-manual-1")
        skill(root, ".old-manual-2")
        skill(root, "Invalid_folder")
        File(skill(root, "invalid-manifest"), "SKILL.md").writeText("not a skill manifest")
        File(skill(root, "wrong-name"), "SKILL.md").writeText("---\nname: another-name\ndescription: Test\n---\nText")
        File(root, "missing-manifest").mkdirs()
        File(root, "orphan.txt").writeText("orphan")
        File(root, "state.json.tmp").writeText("leftover")
        val state = "{\"skills\":{\"mirror\":{\"enabled\":true,\"syncSource\":\"repo-1\"},\"manual\":{\"enabled\":false}}}"
        File(root, "state.json").writeText(state)
        val snapshot = SettingsBackup.AndroidStore(context).snapshot()
        assertTrue(snapshot.skills.isEmpty()) // File content stays on disk until the zip writer opens it.
        assertEquals(setOf("state.json", "manual/SKILL.md"), snapshot.skillFiles.keys)
        val out = ByteArrayOutputStream()
        val exported = SettingsBackup.export(snapshot, SecretCipher.Plain, out, "test")
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain) { restored = it }
        assertEquals(setOf("state.json", "manual/SKILL.md"), restored!!.skills.keys)
        assertEquals(state, restored!!.skills["state.json"]!!.toString(Charsets.UTF_8))
        assertEquals(2, exported.skillFiles)
    }

    @Test fun oversizedManualSkillIsOmittedWholeAndSettingsStillExport() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        val large = skill(root, "large")
        java.io.RandomAccessFile(File(large, "huge.bin"), "rw").use { it.setLength(64L * 1024 * 1024) }
        skill(root, "small")
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().putBoolean("voice_tts", true).commit()
        val snapshot = SettingsBackup.AndroidStore(context).snapshot()
        val out = ByteArrayOutputStream()
        val result = SettingsBackup.export(snapshot, SecretCipher.Plain, out, "test")
        assertTrue(result.warnings.contains("Not backed up (too large): large"))
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain) { restored = it }
        assertEquals(setOf("small/SKILL.md"), restored!!.skills.keys)
        assertEquals(true, restored!!.prefs["settings"]!!["voice_tts"])
    }

    @Test fun sameDeviceRestoreKeepsSkillsTheExportLeftOutAsTooLarge() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        val large = skill(root, "large")
        java.io.RandomAccessFile(File(large, "huge.bin"), "rw").use { it.setLength(64L * 1024 * 1024) }
        skill(root, "small")
        val out = ByteArrayOutputStream()
        SettingsBackup.export(SettingsBackup.AndroidStore(context).snapshot(), SecretCipher.Plain, out, "test")
        val json = ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == SettingsBackup.MANIFEST }; JSONObject(String(zip.readBytes(), Charsets.UTF_8))
        }
        assertEquals(setOf("large"), json.getJSONObject("omittedSkills").keys().asSequence().toSet())
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain, SettingsBackup.AndroidStore(context))
        assertEquals(64L * 1024 * 1024, File(root, "large/huge.bin").length())
        assertTrue(File(root, "small/SKILL.md").isFile)
    }

    @Test fun anotherPhonesSameNamedSkillIsNotKeptByARestore() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        val large = skill(root, "large")
        java.io.RandomAccessFile(File(large, "huge.bin"), "rw").use { it.setLength(64L * 1024 * 1024) }
        val out = ByteArrayOutputStream()
        SettingsBackup.export(SettingsBackup.AndroidStore(context).snapshot(), SecretCipher.Plain, out, "test")
        // The importing phone has a different skill that happens to share the name.
        root.deleteRecursively(); root.mkdirs()
        File(skill(root, "large"), "SKILL.md").writeText("---\nname: large\ndescription: Someone else's\n---\nOther")
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain, SettingsBackup.AndroidStore(context))
        assertFalse(File(root, "large").exists())
    }

    @Test fun aggregateByteBudgetDropsLargestFirstWithoutPartialSkillFiles() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        val large = skill(root, "large")
        val medium = skill(root, "medium")
        java.io.RandomAccessFile(File(large, "ref.bin"), "rw").use { it.setLength(40L * 1024 * 1024) }
        java.io.RandomAccessFile(File(medium, "ref.bin"), "rw").use { it.setLength(30L * 1024 * 1024) }
        File(root, "state.json").writeText("{\"skills\":{\"large\":{\"enabled\":false}}}")
        val out = ByteArrayOutputStream()
        val result = SettingsBackup.export(SettingsBackup.AndroidStore(context).snapshot(), SecretCipher.Plain, out, "test")
        assertEquals(listOf("Not backed up (too large): large"), result.warnings)
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain) { restored = it }
        assertEquals(setOf("medium/SKILL.md", "medium/ref.bin", "state.json"), restored!!.skills.keys)
        assertEquals(30 * 1024 * 1024, restored!!.skills["medium/ref.bin"]!!.size)
    }

    @Test fun symlinkFilesAndDirectoriesAreSkippedWithWarnings() {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.filesDir, "skills").apply { deleteRecursively(); mkdirs() }
        val dir = skill(root, "manual")
        val outside = File(context.cacheDir, "private.txt").apply { writeText("must not export") }
        java.nio.file.Files.createSymbolicLink(File(dir, "link.txt").toPath(), outside.toPath())
        java.nio.file.Files.createSymbolicLink(File(dir, "linked-dir").toPath(), context.cacheDir.toPath())
        val out = ByteArrayOutputStream()
        val result = SettingsBackup.export(SettingsBackup.AndroidStore(context).snapshot(), SecretCipher.Plain, out, "test")
        assertTrue(result.warnings.any { it.contains("symlink") && it.contains("manual/link.txt") })
        assertTrue(result.warnings.any { it.contains("symlink") && it.contains("manual/linked-dir") })
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(out.toByteArray()), SecretCipher.Plain) { restored = it }
        assertEquals(setOf("manual/SKILL.md"), restored!!.skills.keys)
    }

    @Test fun androidPreferencesAndReportDestinationsRoundTripWithoutRuntimeOrReportCopies() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val double = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu0.75"
        val list = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu![\"one\",\"two\"]"
        prefs.edit().clear().putString("flutter.scale", double).putString("flutter.list", list)
            .putLong("flutter.width", Long.MAX_VALUE).putBoolean("flutter.enabled", true)
            .putBoolean("flutter.setup_completed", true).commit()
        context.getSharedPreferences("backstage_skill_sync", Context.MODE_PRIVATE).edit().clear()
            .putString("config", "{}").putString("status", "running").commit()
        val reports = File(context.noBackupFilesDir, "ai-reports").apply { mkdirs() }
        File(reports, "destinations.json").writeText("{\"maintainer\":\"user@example.com\",\"provider\":\"\"}")
        File(reports, "report.json").writeText("private response")
        val backup = ByteArrayOutputStream()
        SettingsBackup.export(SettingsBackup.AndroidStore(context).snapshot(), SecretCipher.Plain, backup, "test")
        prefs.edit().clear().putBoolean("flutter.setup_completed", false).commit()
        File(reports, "destinations.json").delete()
        SettingsBackup.import(ByteArrayInputStream(backup.toByteArray()), SecretCipher.Plain, SettingsBackup.AndroidStore(context))
        assertEquals(double, prefs.getString("flutter.scale", null))
        assertEquals(list, prefs.getString("flutter.list", null))
        assertEquals(Long.MAX_VALUE, prefs.getLong("flutter.width", 0))
        assertEquals(false, prefs.getBoolean("flutter.setup_completed", true))
        assertEquals("user@example.com", JSONObject(File(reports, "destinations.json").readText()).getString("maintainer"))
        assertEquals("private response", File(reports, "report.json").readText())
        assertEquals("running", context.getSharedPreferences("backstage_skill_sync", Context.MODE_PRIVATE).getString("status", null))
    }

    @Test fun failedCommitRollsBackAllTouchedFilesAndKeepsSkills() {
        val context = RuntimeEnvironment.getApplication()
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val bubble = context.getSharedPreferences("backstage_bubble", Context.MODE_PRIVATE)
        settings.edit().clear().putString("old", "keep").commit()
        bubble.edit().clear().putInt("x", 12).commit()
        val root = File(context.filesDir, "skills").apply { mkdirs() }
        File(root, "keep.md").writeText("keep")
        var failNextCommit = true
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "backstage_bubble") return delegate
                return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    if (method.name != "edit") method.invoke(delegate, *(args ?: emptyArray()))
                    else {
                        val editor = delegate.edit()
                        Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, operation, params ->
                            val result = operation.invoke(editor, *(params ?: emptyArray()))
                            if (operation.name == "commit" && failNextCommit) { failNextCommit = false; false }
                            else if (result === editor) proxy else result
                        }
                    }
                } as SharedPreferences
            }
        }
        assertThrows(java.io.IOException::class.java) {
            SettingsBackup.AndroidStore(failing).replace(SettingsBackup.Snapshot(
                linkedMapOf("settings" to mapOf("new" to true), "backstage_bubble" to mapOf("x" to 99)),
                mapOf("new.md" to byteArrayOf(1)),
            ))
        }
        assertEquals(mapOf("old" to "keep"), settings.all)
        assertEquals(mapOf("x" to 12), bubble.all)
        assertEquals("keep", File(root, "keep.md").readText())
        assertFalse(File(root, "new.md").exists())
    }
}
