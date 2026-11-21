package com.eggman.backstage.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable configuration only. The archive deliberately contains plaintext credentials. */
object SettingsBackup {
    const val MANIFEST = "backstage-settings.json"
    private const val APP = "com.eggman.backstage"
    private const val PREFIX = "enc1:"
    // Import validates the whole archive in memory before writing. Keep this bounded until it spools to disk.
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000

    // Format 1's original importer rejects unknown file names inside prefs, but ignores unknown root keys.
    // Keep this seven-file boundary fixed; additive stores live in prefsExtra so can still import the zip.
    private val legacyPreferenceFiles = setOf(
        Settings.PREFS, "backstage_quick_actions", "backstage_schedules", "backstage_bubble",
        "backstage_widgets", "backstage_a11y", "backstage_composer",
    )

    // agent_engine is an ordinary Settings.PREFS string and round-trips without a migration.
    // termux_shell_enabled / termux_shell_auto_approve are ordinary booleans in Settings.PREFS; append-only, default off.
    // settings: all portable keys, including unknown future settings and legacy plaintext credentials.
    // quick_actions: all saved user actions. schedules: definitions and dedupe/fire times, but no status/results.
    // bubble: both BubbleService and Overlay use this SAME file; position and deliberate dismissal are durable.
    // widgets: retain action mappings (Android widget IDs themselves are managed by the launcher).
    // a11y: only the durable user opt-out. Self-write/desktop-change timestamps are runtime state, never restored.
    // composer: the unsent draft, its provenance and timestamp belong to the user and must survive a restore.
    // settings includes AI keys, ChatGPT OAuth (key_CHATGPT), personality/rules, remote MCP, voice,
    // memory enablement and OMP options. OMP harness/Termux pairing tokens are secrets too (user decision: all credentials migrate;
    // on the same phone the pairing keeps working, elsewhere they grant nothing). MCP/plugin credentials are portable.
    // Flutter's list/double encodings are native strings: preserve their bytes, never decode their prefixes.
    // Flutter setup/version/Linux installation markers, privilege last_mode and display migration markers
    // describe THIS install/OS and are preserved locally rather than restored. Provider selection is portable.
    // Native Dextop preferences cover display/input, saved topology, favorites/pins, Auto UI and controller choices.
    // Linux: Store sources, execution mode, theme/clipboard, shortcut install mode, USB policy/device selection.
    // linux_shortcut_settings/launch_key authenticates this install's generated launcher APKs and never migrates.
    // linux_shortcuts installed APK mappings, linux-folders SAF URI grants, linux-android-app-grants and actual
    // Android USB grants are install-bound; USB policy/selection are portable preferences, not Android grants.
    // Runtime recovery files (session_journal, cleanup, topology_session, input_routing, swipe_ime_state, home_launch_recovery,
    // pixel_mirror_fallback, samsung_desktop_settings_backup), embedded wireless-ADB pairing/keys, recent apps,
    // learned swipe history, chat threads, task history, Vosk downloads, OS permissions and Keystore are excluded.
    // backstage_memory.db holds knowledge records (separate Memory export), not settings; only its toggle is here.
    // Report email destinations are included separately; saved AI response/report contents never enter the archive.
    val preferenceFiles: Set<String> = setOf(
        Settings.PREFS, "backstage_quick_actions", "backstage_schedules", "backstage_bubble",
        "backstage_widgets", "backstage_a11y", "backstage_composer", "backstage_skill_sync",
        com.eggman.backstage.privileged.PrivilegeProviders.PREFERENCES, "FlutterSharedPreferences",
        "freedextop_input", "dextop_display_environment", "dextop_topology_layout", "dextop_plasma",
        "dextop_auto_ui", "dextop_auto_runtime", "dextop_linux_controllers", "linux-store-sources",
        "linux-execution", "linux_android_settings", "linux_shortcut_settings", "linux-usb",
    )
    private val secretKeys = setOf("weather_key", "search_key", "search_custom_header_value", "mcp_token", "plugin_secret", "omp_harness_token", "omp_termux_token")

    data class Snapshot(
        val prefs: Map<String, Map<String, Any?>>,
        /** Relative to filesDir/skills, including state.json and binary reference files. */
        val skills: Map<String, ByteArray> = emptyMap(),
        /** Export-only sources, opened one at a time. Import keeps its bounded, validated byte-array contract. */
        val skillFiles: Map<String, File> = emptyMap(),
        val warnings: List<String> = emptyList(),
        /** Optional format-1 addition; absent in old archives, so old imports leave these addresses alone. */
        val reportDestinations: String? = null,
        /**
         * Manual skills the export left out as too large, name → SHA-256 of their SKILL.md: a restore keeps the phone's
         * own copy instead of deleting it, but only when it is the same skill (same SKILL.md), never another phone's.
       */
        val keepLocalSkills: Map<String, String> = emptyMap(),
    )

    fun interface Store {
        /** Replace every listed prefs file completely and replace skills. Called only after full validation. */
        fun replace(snapshot: Snapshot)
    }

    data class Result(
        val prefFiles: Int,
        val settings: Int,
        val skillFiles: Int,
        val warnings: List<String> = emptyList(),
        val unrecoverableSecrets: List<String> = emptyList(),
    ) {
        fun summary(): String = "$settings settings in $prefFiles preference files, $skillFiles skill files." +
            warnings.joinToString(separator = " ", prefix = if (warnings.isEmpty()) "" else " ")
    }

    /** No Android dependency in the archive logic; a fake cipher and Store suffice for unit tests. */
    fun export(
        snapshot: Snapshot,
        cipher: SecretCipher,
        output: OutputStream,
        versionName: String,
        exportedAt: Instant = Instant.now(),
    ): Result {
        require(snapshot.prefs.keys.all { it in preferenceFiles }) { "Unsupported preference file" }
        validateSkillPaths(snapshot.skills.keys + snapshot.skillFiles.keys)
        val lost = mutableListOf<String>()
        val prefs = JSONObject()
        snapshot.prefs.forEach { (file, entries) ->
            val values = JSONObject()
            entries.forEach entry@{ (key, raw) ->
                if (!includeKey(file, key) || raw == null) return@entry
                var value = raw
                var secret = false
                if (value is String) {
                    if (value.startsWith(PREFIX)) {
                        value = decrypt(cipher, value) ?: run { lost += "$file/$key"; return@entry }
                        secret = true
                    } else if (isSecretKey(file, key)) secret = true
                    if (file == Settings.PREFS && key == "remote_mcp") {
                        value = remoteTokens(value as String) { index, token ->
                            if (token.startsWith(PREFIX)) decrypt(cipher, token).also {
                                if (it == null) lost += "$file/$key[$index].token"
                            } else token
                        }
                        secret = true
                    }
                    if (file == "backstage_schedules" && key == "tasks") value = withoutScheduleHistory(value as String)
                }
                values.put(key, encode(value).apply { if (secret) put("secret", true) })
            }
            prefs.put(file, values)
        }
        val legacyPrefs = JSONObject()
        val extraPrefs = JSONObject()
        prefs.keys().forEach { file ->
            (if (file in legacyPreferenceFiles) legacyPrefs else extraPrefs).put(file, prefs.getJSONObject(file))
        }
        val root = JSONObject().put("format", 1).put("app", APP).put("versionName", versionName)
            .put("exportedAt", exportedAt.toString()).put("prefs", legacyPrefs).put("prefsExtra", extraPrefs)
            .put("unrecoverableSecrets", JSONArray(lost))
        snapshot.reportDestinations?.let { root.put("reportDestinations", reportDestinations(it)) }
        val notes = snapshot.warnings.toMutableList()
        val sources = skillSources(snapshot, notes)
        val groups = sources.keys.filter { it != "state.json" }.groupBy { it.substringBefore('/') }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<String>>> { (_, paths) ->
                paths.sumOf { sources.getValue(it).size }
            }.thenBy { it.key })
        val omitted = mutableListOf<String>()
        val omittedDigests = linkedMapOf<String, String>()
        var group = 0
        var bytes = sources.values.sumOf { it.size }
        var json: ByteArray
        while (true) {
            val allNotes = notes + if (omitted.isEmpty()) emptyList() else
                listOf("Not backed up (too large): ${omitted.joinToString(", ")}")
            root.put("warnings", JSONArray(allNotes))
            if (omitted.isEmpty()) root.remove("omittedSkills") else root.put("omittedSkills", JSONObject(omittedDigests as Map<*, *>))
            json = root.toString(2).toByteArray(Charsets.UTF_8)
            if (sources.size < MAX_ENTRIES && json.size.toLong() + bytes <= MAX_BYTES) break
            // The manifest and state are reserved. Evict complete skills, never individual reference files.
            require(group < groups.size) { "Settings and skill state exceed the 64 MiB backup limit" }
            val next = groups[group++]
            omitted += next.key
            sources["${next.key}/SKILL.md"]?.let { src -> omittedDigests[next.key] = src.open().use(::sha256) }
            next.value.forEach { bytes -= sources.remove(it)!!.size }
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(json); zip.closeEntry()
            sources.toSortedMap().forEach { (path, source) ->
                zip.putNextEntry(ZipEntry("skills/$path"))
                source.open().use { input ->
                    // Detect growth/truncation since planning; never emit an archive beyond the import budget.
                    val copied = copyExactly(input, zip, source.size)
                    check(copied == source.size && input.read() == -1) { "Skill changed during backup: $path; retry export" }
                }
                zip.closeEntry()
            }
        }
        return result(prefs, sources.size, lost).copy(warnings = warnings(lost) + notes +
            if (omitted.isEmpty()) emptyList() else listOf("Not backed up (too large): ${omitted.joinToString(", ")}"))
    }

    private data class SkillSource(val size: Long, val open: () -> InputStream)

    private fun skillSources(snapshot: Snapshot, notes: MutableList<String>): MutableMap<String, SkillSource> {
        require(snapshot.skills.keys.intersect(snapshot.skillFiles.keys).isEmpty()) { "Duplicate skill sources" }
        val files = snapshot.skillFiles.filter { (path, file) ->
            val safe = !Files.isSymbolicLink(file.toPath())
            if (!safe) notes += "Skipped skill symlink: $path"
            safe
        }.mapValues { (_, file) ->
            SkillSource(file.length()) { Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS) }
        }
        val sources = (snapshot.skills.mapValues { (_, bytes) -> SkillSource(bytes.size.toLong()) { ByteArrayInputStream(bytes) } } + files).toMutableMap()
        val state = sources["state.json"]
        val mirrored = if (state != null && state.size <= MAX_BYTES) runCatching {
            state.open().use { input -> parseObject(input.bufferedReader(Charsets.UTF_8).readText()) }
                .optJSONObject("skills")?.let { skills ->
                    skills.keys().asSequence().filter { skills.optJSONObject(it)?.optString("syncSource").orEmpty().isNotEmpty() }.toSet()
                }.orEmpty()
        }.getOrElse { notes += "Could not read skill sync state; skill folders will be included."; emptySet() } else emptySet()
        val valid = sources.keys.map { it.substringBefore('/') }.filter {
            com.eggman.backstage.skills.SkillRules.validName(it) && "$it/SKILL.md" in sources && it !in mirrored
        }.toSet()
        sources.keys.removeAll { path -> path != "state.json" && ('/' !in path || path.substringBefore('/') !in valid) }
        return sources
    }

    private fun sha256(input: InputStream): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long): Long {
        val buffer = ByteArray(8192)
        var copied = 0L
        while (copied < size) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    /** Decode, validate ALL entries, and encrypt ALL secrets before invoking the writer even once. */
    fun import(input: InputStream, cipher: SecretCipher, store: Store): Result {
        val files = linkedMapOf<String, ByteArray>()
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                validatePath(if (entry.isDirectory) name.removeSuffix("/") else name)
                require(seen.add(name) && seen.size <= MAX_ENTRIES) { "Duplicate entry or too many zip entries" }
                require(name == MANIFEST && !entry.isDirectory || name.startsWith("skills/") || name == "skills/" && entry.isDirectory) {
                    "Unsupported zip entry"
                }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_BYTES) { "Backup exceeds the 64 MiB limit" }
                    bytes.write(buffer, 0, n)
                }
                if (!entry.isDirectory) files[name] = bytes.toByteArray()
                else require(bytes.size() == 0) { "Directory entry contains data" }
                zip.closeEntry()
            }
        }
        val manifest = files.remove(MANIFEST) ?: error("Missing $MANIFEST")
        val root = parseObject(String(manifest, Charsets.UTF_8))
        require(root.get("format") is Number) { "Unsupported backup format" }
        val format = integer(root.get("format"))
        require(format <= 1L) { "This backup was made by a newer Backstage version (format $format); update Backstage first." }
        require(format == 1L) { "Unsupported backup format" }
        require(root.get("app") == APP) { "Backup is for another app" }
        require(root.get("versionName") is String && root.get("exportedAt") is String) { "Invalid backup metadata" }
        Instant.parse(root.getString("exportedAt"))
        val encoded = linkedMapOf<String, JSONObject>()
        val importNotes = mutableListOf<String>()
        fun collect(group: JSONObject, extra: Boolean) {
            group.keys().forEach file@{ file ->
                if (file !in preferenceFiles) {
                    require(extra) { "Unsupported preference file" }
                    importNotes += "Skipped unknown preference file in prefsExtra: $file"
                    return@file // A future file may use an entirely different encoding; don't decode it.
                }
                require(file !in encoded) { "Duplicate preference file: $file" }
                encoded[file] = group.getJSONObject(file)
            }
        }
        collect(root.getJSONObject("prefs"), extra = false)
        if (root.has("prefsExtra")) collect(root.getJSONObject("prefsExtra"), extra = true)
        val prefs = linkedMapOf<String, Map<String, Any?>>()
        encoded.forEach { (file, entries) ->
            val values = linkedMapOf<String, Any?>()
            entries.keys().forEach entry@{ key ->
                val item = entries.getJSONObject(key)
                var value = decode(item)
                require(!item.has("secret") || item.get("secret") is Boolean) { "Invalid secret flag" }
                val secret = item.optBoolean("secret", false)
                require(!secret || value is String) { "Secret must be a string" }
                if (!includeKey(file, key)) return@entry
                if (value is String) {
                    require(!value.startsWith(PREFIX)) { "Backup contains device-bound ciphertext" }
                    value = when {
                        file == Settings.PREFS && key == "remote_mcp" -> remoteTokens(value) { _, token ->
                            require(!token.startsWith(PREFIX)) { "Backup contains a device-bound MCP token" }
                            encrypt(cipher, token)
                        }
                        secret || isSecretKey(file, key) -> encrypt(cipher, value)
                        // A config the sync screen cannot parse would lock the user out of it: reject before writing.
                        file == "backstage_skill_sync" && key == "config" -> value.also {
                            runCatching { com.eggman.backstage.skills.SkillSyncConfig.parse(it) }.getOrElse { e ->
                                throw IllegalArgumentException("Invalid GitHub sync configuration: ${e.message}")
                            }
                        }
                        file == "backstage_schedules" && key == "tasks" -> withoutScheduleHistory(value)
                        else -> value
                    }
                }
                if (includeKey(file, key)) values[key] = value
            }
            prefs[file] = values
        }
        val allSkills = files.mapKeys { (name, _) -> name.removePrefix("skills/") }
        validateSkillPaths(allSkills.keys)
        // Older exports copied install staging trees. Validate their paths, then omit only top-level dot entries.
        val skills = allSkills.filterKeys { !it.substringBefore('/').startsWith(".") }
        val dropped = allSkills.size - skills.size
        if (dropped > 0) importNotes += "Skipped $dropped old skill install leftover file(s)."
        val missing = preferenceFiles - prefs.keys
        if (missing.isNotEmpty()) importNotes += "Not in this backup, kept as they are: ${missing.joinToString(", ")}"
        val lost = if (root.has("unrecoverableSecrets")) stringSet(root.getJSONArray("unrecoverableSecrets")).toList() else emptyList()
        val notes = if (root.has("warnings")) stringSet(root.getJSONArray("warnings")).toList() else emptyList()
        val destinations = if (root.has("reportDestinations")) reportDestinations(root.getJSONObject("reportDestinations").toString()).toString() else null
        val result = Result(prefs.size, prefs.values.sumOf { it.size }, skills.size, warnings(lost) + notes + importNotes, lost)
        val keepLocal = (root.optJSONObject("omittedSkills") ?: JSONObject()).let { o ->
            o.keys().asSequence().filter { com.eggman.backstage.skills.SkillRules.validName(it) && skills.keys.none { k -> k.startsWith("$it/") } }
                .mapNotNull { name -> (o.opt(name) as? String)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { name to it } }.toMap()
        }
        store.replace(Snapshot(prefs, skills, reportDestinations = destinations, keepLocalSkills = keepLocal))
        return result
    }

    internal fun includeKey(file: String, key: String): Boolean = when (file) {
        "backstage_a11y" -> key == "user_turned_off"
        "backstage_skill_sync" -> key == "config" || key.startsWith("token_")
        "dextop_privilege_provider" -> key == "selected"
        "linux_shortcut_settings" -> key == "install_mode"
        "FlutterSharedPreferences" -> key !in setOf("flutter.setup_completed", "flutter.last_launched_app_version",
            "flutter.linux_first_run_done", "flutter.linux_first_run_prepare")
        "dextop_display_environment" -> key != "legacy_topology_migrated"
        "dextop_topology_layout" -> key == "saved_arrangement_v1"
        "dextop_plasma", "dextop_auto_runtime" -> key == "favorites" || key == "pinned"
        else -> true
    }

    private fun isSecretKey(file: String, key: String): Boolean =
        file == Settings.PREFS && (key.startsWith("key_") || key in secretKeys) ||
            file == "backstage_skill_sync" && key.startsWith("token_")

    private fun reportDestinations(json: String): JSONObject = parseObject(json).also {
        com.eggman.backstage.ui.report.ReportDestinations(it.getString("maintainer"), it.getString("provider"))
        require(it.keys().asSequence().toSet() == setOf("maintainer", "provider")) { "Invalid report destinations" }
    }

    private fun decrypt(cipher: SecretCipher, value: String): String? =
        runCatching { cipher.decrypt(value) }.getOrNull()?.takeUnless { it.startsWith(PREFIX) }

    private fun encrypt(cipher: SecretCipher, value: String): String {
        if (value.isEmpty()) return ""
        // KeystoreCipher falls back to plaintext if the Keystore fails. Import must fail closed instead.
        return cipher.encrypt(value).also {
            require(it.startsWith(PREFIX) && it != value) { "Could not encrypt imported secrets on this device" }
        }
    }

    private fun remoteTokens(json: String, transform: (Int, String) -> String?): String {
        val parsed = JSONTokener(json)
        val servers = parsed.nextValue() as? JSONArray ?: error("Invalid remote MCP configuration")
        require(parsed.nextClean() == '\u0000') { "Trailing remote MCP data" }
        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            if (server.has("token")) {
                val token = server.get("token") as? String ?: error("Invalid remote MCP token")
                val converted = transform(i, token)
                if (converted == null) server.remove("token") else server.put("token", converted)
            }
        }
        return servers.toString()
    }

    private fun withoutScheduleHistory(json: String): String = parseObject(json).also { root ->
        val tasks = root.getJSONArray("tasks")
        for (i in 0 until tasks.length()) tasks.getJSONObject(i).apply {
            // Last-run/next-fire times prevent replay and retain schedule semantics; result/status are task history.
            remove("lastResult"); remove("lastStatus")
        }
    }.toString()

    private fun parseObject(json: String): JSONObject {
        val parser = JSONTokener(json)
        val root = parser.nextValue() as? JSONObject ?: error("Expected a JSON object")
        require(parser.nextClean() == '\u0000') { "Trailing JSON data" }
        return root
    }

    private fun encode(value: Any): JSONObject {
        val (type, encoded) = when (value) {
            is String -> "s" to value
            is Int -> "i" to value
            is Long -> "l" to value
            is Boolean -> "b" to value
            is Float -> { require(value.isFinite()) { "Non-finite preference" }; "f" to value }
            is Set<*> -> { require(value.all { it is String }) { "Invalid string set" }; "ss" to JSONArray(value.sortedBy { it as String }) }
            else -> error("Unsupported preference type")
        }
        return JSONObject().put("t", type).put("v", encoded)
    }

    private fun integer(value: Any): Long = (value as? Number)?.toString()?.toLongOrNull() ?: error("Expected an integer")

    private fun decode(item: JSONObject): Any {
        val value = item.get("v")
        return when (item.get("t")) {
            "s" -> value as? String ?: error("Expected a string")
            "i" -> integer(value).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of range" } }.toInt()
            "l" -> integer(value)
            "b" -> value as? Boolean ?: error("Expected a boolean")
            "f" -> (value as? Number)?.toFloat()?.also { require(it.isFinite()) { "Float out of range" } } ?: error("Expected a float")
            "ss" -> stringSet(value as? JSONArray ?: error("Expected a string set"))
            else -> error("Unknown preference type")
        }
    }

    private fun stringSet(array: JSONArray): Set<String> = (0 until array.length()).map {
        array.get(it) as? String ?: error("Expected a string set member")
    }.toSet()

    private fun validatePath(path: String) {
        require(path.isNotEmpty() && path.length <= 4096 && !path.contains('\\') && !path.contains(':') && path.none { it.code < 32 } &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe zip path" }
    }

    private fun validateSkillPaths(paths: Set<String>) {
        paths.forEach { path ->
            validatePath(path)
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in paths) { "Conflicting skill file paths" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }

    private fun warnings(lost: List<String>) = if (lost.isEmpty()) emptyList() else
        listOf("${lost.size} unrecoverable secret(s) omitted; enter them again after import.")

    private fun result(prefs: JSONObject, skills: Int, lost: List<String>) =
        Result(prefs.length(), prefs.keys().asSequence().sumOf { prefs.getJSONObject(it).length() }, skills, warnings(lost), lost.toList())

    /** Android wrappers; callers must dispatch IO off the main thread. */
    fun export(context: Context, output: OutputStream): Result = synchronized(this) {
        val app = context.applicationContext
        com.eggman.backstage.skills.SkillStore.shared(app.filesDir) // runs start-up recovery first, once per process
        val version = com.eggman.backstage.ui.licenses.appVersionName(app).orEmpty()
        // Hold the skills gate while files are listed and streamed, so a GitHub sync cannot swap a folder mid-export.
        com.eggman.backstage.skills.SkillStore.withFilesLock(java.io.File(app.filesDir, "skills")) {
            export(AndroidStore(app).snapshot(), KeystoreCipher(), output, version)
        }
    }

    fun import(context: Context, input: InputStream): Result = synchronized(this) {
        val app = context.applicationContext
        com.eggman.backstage.skills.SkillStore.shared(app.filesDir) // runs start-up recovery first, once per process
        // The tree swap must not interleave with a GitHub mirror commit; syncSoon runs after the gate is released.
        com.eggman.backstage.skills.SkillStore.withFilesLock(java.io.File(app.filesDir, "skills")) {
            import(input, KeystoreCipher(), AndroidStore(app))
        }.let { imported ->
            // The restore is already committed; report scheduling failures without calling it a failed import.
            // The restored tree differs from the last sync status (mirrored folders are not in backups): forget it so the
            // next run downloads again instead of short-circuiting on an unchanged commit, and run once even with auto-sync off.
            runCatching {
                check(app.getSharedPreferences("backstage_skill_sync", Context.MODE_PRIVATE).edit().remove("status").commit())
                com.eggman.backstage.skills.SkillSyncScheduler.syncSoon(app, evenWhenOff = true)
            }.fold(
                onSuccess = { imported },
                onFailure = { imported.copy(warnings = imported.warnings + "GitHub skills could not be queued; run Sync now in Skills.") },
            )
        }
    }

    internal class AndroidStore(private val context: Context) : Store {
        private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        fun snapshot(): Snapshot {
            val root = File(context.filesDir, "skills")
            val skills = linkedMapOf<String, File>()
            val notes = mutableListOf<String>()
            fun symlink(file: File): Boolean = Files.isSymbolicLink(file.toPath()).also {
                if (it) notes += "Skipped skill symlink: ${if (file == root) "skills" else file.relativeTo(root).invariantSeparatorsPath}"
            }
            val rootSafe = !symlink(root)
            val state = File(root, "state.json")
            val mirrored = if (rootSafe && !Files.isSymbolicLink(state.toPath()) && state.isFile && state.length() <= MAX_BYTES) runCatching {
                val metadata = parseObject(state.readText(Charsets.UTF_8)).optJSONObject("skills")
                metadata?.keys()?.asSequence()?.filter {
                    metadata.optJSONObject(it)?.optString("syncSource").orEmpty().isNotEmpty()
                }?.toSet().orEmpty()
            }.getOrDefault(emptySet()) else emptySet()
            if (rootSafe && root.isDirectory) {
                root.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                    if (child.name.startsWith(".") || symlink(child)) return@forEach
                    if (child.name == "state.json" && child.isFile) skills[child.name] = child
                    else if (child.name !in mirrored && child.isDirectory && com.eggman.backstage.skills.SkillRules.validName(child.name)) {
                        val manifest = File(child, "SKILL.md")
                        if (!symlink(manifest) && manifest.isFile && validManifest(manifest, child.name)) {
                            child.walkTopDown().onEnter { !symlink(it) }.forEach { file ->
                                if (!file.isDirectory && !symlink(file) && file.isFile) {
                                    val path = file.relativeTo(root).invariantSeparatorsPath
                                    if (runCatching { validatePath(path) }.isSuccess) skills[path] = file
                                    else notes += "Skipped unsafe skill path: $path"
                                }
                            }
                        }
                    }
                }
            }
            val destinations = File(context.noBackupFilesDir, "ai-reports/destinations.json")
            return Snapshot(preferenceFiles.associateWith { prefs(it).all.toMap() }, skillFiles = skills, warnings = notes,
                reportDestinations = if (destinations.isFile) destinations.readText(Charsets.UTF_8) else null)
        }

        private fun validManifest(file: File, name: String): Boolean = runCatching {
            // Only the frontmatter determines validity. Bound the read so a large instruction body stays on disk.
            val header = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
                it.readNBytes(1024 * 1024).toString(Charsets.UTF_8)
            }
            com.eggman.backstage.skills.SkillParser.parse(header, name).manifest?.name == name
        }.getOrDefault(false)

        override fun replace(snapshot: Snapshot) {
            val old = snapshot.prefs.keys.associateWith { prefs(it).all.toMap() }
            val workspace = Files.createTempDirectory(context.filesDir.toPath(), ".settings-restore-").toFile()
            val incoming = File(workspace, "incoming").apply { check(mkdir()) }
            val previous = File(workspace, "previous")
            val skills = File(context.filesDir, "skills")
            val destinations = File(context.noBackupFilesDir, "ai-reports/destinations.json")
            val previousDestinations = if (snapshot.reportDestinations != null && destinations.exists()) destinations.readBytes() else null
            var wroteDestinations = false
            val attempted = mutableListOf<String>()
            var movedOld = false
            var movedNew = false
            try {
                // Stage all skill files before touching preferences. The new tree contains only regular files.
                snapshot.skills.forEach { (path, bytes) ->
                    val file = File(incoming, path)
                    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create skill directory" }
                    file.writeBytes(bytes)
                }
                require(!Files.isSymbolicLink(skills.toPath())) { "Skills directory is a symlink" }
                // Skills the export left out as too large stay as this phone has them (copied, so rollback is untouched).
                // Done before the preferences commit, so `incoming` is complete before recovery could ever pick it.
                snapshot.keepLocalSkills.forEach { (name, digest) ->
                    val local = File(skills, name)
                    val manifest = File(local, "SKILL.md")
                    if (local.isDirectory && !Files.isSymbolicLink(local.toPath()) && !File(incoming, name).exists() &&
                        manifest.isFile && manifest.inputStream().use(::sha256) == digest) {
                        check(local.copyRecursively(File(incoming, name))) { "Cannot keep the local skill $name" }
                    }
                }
                snapshot.prefs.forEach { (name, values) ->
                    attempted += name // commit(false) can still change the in-memory SharedPreferences map.
                    // Keys a backup never carries (runtime state such as a pending detector-pause recovery) stay.
                    val kept = old.getValue(name).filterKeys { !includeKey(name, it) }
                    check(write(prefs(name), kept + values)) { "Could not commit settings" }
                }
                // From here the restored preferences are live: start-up recovery must then finish with `incoming`.
                File(workspace, com.eggman.backstage.skills.SkillStore.PREFS_COMMITTED).createNewFile()
                snapshot.reportDestinations?.let { json ->
                    reportDestinations(json)
                    val staged = File(workspace, "destinations.json").apply { writeText(json, Charsets.UTF_8) }
                    check(destinations.parentFile!!.isDirectory || destinations.parentFile!!.mkdirs()) { "Cannot create report directory" }
                    wroteDestinations = true
                    Files.move(staged.toPath(), destinations.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                if (skills.exists()) {
                    check(skills.renameTo(previous)) { "Cannot preserve current skills" }
                    movedOld = true
                }
                check(incoming.renameTo(skills)) { "Cannot restore skills" }
                movedNew = true
            } catch (failure: Exception) {
                // First: the restored tree is not wanted. Remove the marker and the staged tree before anything else
                // can fail, so start-up recovery can only ever bring back the user's own tree from here on.
                val marker = File(workspace, com.eggman.backstage.skills.SkillStore.PREFS_COMMITTED)
                if (!marker.delete() && marker.exists()) incoming.deleteRecursively()
                // SharedPreferences has no cross-file transaction. Roll back synchronous IO/commit failures.
                // Process death or concurrent writes by running services cannot be made atomic by this API.
                val rollback = mutableListOf<Throwable>()
                if (wroteDestinations) runCatching {
                    if (previousDestinations != null) destinations.writeBytes(previousDestinations)
                    else check(!destinations.exists() || destinations.delete())
                }.exceptionOrNull()?.let(rollback::add)
                if (movedNew) runCatching { check(skills.deleteRecursively()) }.exceptionOrNull()?.let(rollback::add)
                if (movedOld) runCatching { check(previous.renameTo(skills)) }.exceptionOrNull()?.let(rollback::add)
                attempted.asReversed().forEach { name ->
                    runCatching { check(write(prefs(name), old.getValue(name))) }.exceptionOrNull()?.let(rollback::add)
                }
                val error = IOException(if (rollback.isEmpty()) "Import failed; current settings were restored." else
                    "Import failed and rollback was incomplete. Restart Backstage and check settings.", failure)
                rollback.forEach(error::addSuppressed)
                throw error
            } finally {
                // If rollback could not recover the previous tree, keep it for recovery instead of deleting it.
                // Retired by an atomic rename first: a deletion cut short must never leave a workspace recovery misreads.
                if (!previous.exists() || movedNew) {
                    val trash = File(context.filesDir, ".trash-${java.util.UUID.randomUUID()}")
                    if (workspace.renameTo(trash)) trash.deleteRecursively()
                }
            }
            com.eggman.backstage.skills.SkillStore.notifyFilesReplaced(skills)
        }

        // Keep Editor.commit's Boolean: the KTX edit lambda discards it, preventing rollback on disk failure.
        @SuppressLint("UseKtx")
        @Suppress("UNCHECKED_CAST")
        private fun write(prefs: SharedPreferences, values: Map<String, Any?>): Boolean {
            val editor = prefs.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    else -> error("Unsupported preference type")
                }
            }
            return editor.commit()
        }
    }
}
