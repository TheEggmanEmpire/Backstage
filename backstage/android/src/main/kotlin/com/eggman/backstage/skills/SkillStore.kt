package com.eggman.backstage.skills

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SkillFileInfo(val path: String, val size: Long, val text: Boolean, val script: Boolean)

/** An installed skill as Settings and the tools see it. */
data class InstalledSkill(
    val manifest: SkillManifest,
    val enabled: Boolean,
    /** Where it came from: "file: name.zip", an URL, "created in Backstage" or "built-in example". */
    val source: String,
    val installedMs: Long,
    val builtin: Boolean,
    val files: List<SkillFileInfo>,
    val warnings: List<String>,
    val syncSource: String? = null,
) {
    val name: String get() = manifest.name
    val description: String get() = manifest.description
    val hasScripts: Boolean get() = files.any { it.script }
}

/** Result of installing one candidate. */
sealed class InstallOutcome(val name: String) {
    class Installed(name: String, val replaced: Boolean) : InstallOutcome(name)
    class Duplicate(name: String) : InstallOutcome(name)
    class Failed(name: String, val reason: String) : InstallOutcome(name)
}

/**
 * Skills on this phone: one folder per skill under [root] (`<files>/skills/<name>/SKILL.md` + its files), named after
 * the skill, plus `state.json` (enabled, source, install time, built-in flag). New skills start switched off. Nothing
 * is updated automatically unless it belongs to a configured GitHub sync source.
 *
 * Thread-safe (one lock); every read goes to disk, which is small and keeps Settings and the agent in step.
 */
class SkillStore(val root: File, private val clock: () -> Long = System::currentTimeMillis, val limits: SkillLimits = SkillLimits()) {
    private val lock = filesLock(root)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val stateFile get() = File(root, STATE)

    /** Disk-backed skills do not change Settings preferences. Notify process consumers after every state write. */
    fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    companion object {
        const val STATE = "state.json"
        const val SOURCE_CREATED = "created in Backstage"
        const val SOURCE_EXAMPLE = "built-in example"

        @Volatile private var shared: SkillStore? = null
        private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private fun filesLock(root: File): Any = fileLocks.getOrPut(root.canonicalPath) { Any() }

        /** Settings restore must use this gate while replacing the tree, so a mirror cannot commit into it. */
        fun <T> withFilesLock(root: File, block: () -> T): T = synchronized(filesLock(root), block)

        private const val INSTALLED = "INSTALLED"
        private const val COMMITTED = "COMMITTED"
        internal const val PREFS_COMMITTED = "PREFS_COMMITTED"

        /** The app's store (`<filesDir>/skills`), with the examples added once. */
        fun shared(filesDir: File): SkillStore = shared ?: synchronized(this) {
            shared ?: run {
                val root = File(filesDir, "skills")
                withFilesLock(root) { runCatching { recoverInterrupted(filesDir, root) } }
                SkillStore(root).also { it.seedExamples(force = false); shared = it }
            }
        }

        /**
         * Process death can interrupt two multi-step file operations; finish or undo them before anything reads skills.
         *
         * 1. A settings restore (`<files>/.settings-restore-*`). `PREFS_COMMITTED` in the workspace says the restored
         *    preferences are live (written after they are committed, removed again by a rollback). With it the staged
         *    `incoming` tree is the right one, without it the user's `previous` tree is: a tree in its place (a failed
         *    swap, or a folder a sync recreated meanwhile) is set aside so the right tree can take its place.
         * 2. A GitHub mirror (`skills/.sync-*`). The commit point is the state.json write, which names the transaction
         *    (`"txn"`). Committed: only its leftovers go. Not committed: the folders it moved into place (listed in
         *    `INSTALLED` before each move) are removed and the folders it moved aside (`old/`) come back.
         * Leftovers are first renamed to `.trash-*` (one atomic step) and only then deleted, so a deletion cut short by
         * process death can never be mistaken for an unfinished transaction.
       */
        internal fun recoverInterrupted(filesDir: File, root: File) {
            fun retire(dir: File) {
                // Never delete in place: a partial deletion could strip the markers that make it safe. Retry next start.
                val trash = File(dir.parentFile, ".trash-${java.util.UUID.randomUUID()}")
                if (dir.renameTo(trash)) trash.deleteRecursively()
            }
            filesDir.listFiles { f -> f.isDirectory && f.name.startsWith(".trash-") }.orEmpty().forEach { it.deleteRecursively() }
            root.listFiles { f -> f.isDirectory && f.name.startsWith(".trash-") }.orEmpty().forEach { it.deleteRecursively() }
            filesDir.listFiles { f -> f.isDirectory && f.name.startsWith(".settings-restore-") }.orEmpty().forEach { ws ->
                val incoming = File(ws, "incoming"); val previous = File(ws, "previous")
                // Marker: the restored tree belongs (still staged in `incoming`, or already live at root once it is gone).
                // No marker: the user's tree belongs (back from `previous` if it is moved aside, else root is it).
                val wanted = if (File(ws, PREFS_COMMITTED).exists()) incoming.takeIf { it.isDirectory } else previous.takeIf { it.isDirectory }
                if (wanted != null) {
                    val displaced = File(ws, "displaced")
                    if (root.exists() && !root.renameTo(displaced)) return@forEach // keep everything for a later try
                    if (!wanted.renameTo(root)) return@forEach
                    // Bringing back the user's own tree: skills made meanwhile in a recreated folder are kept, not lost
                    // (they return switched off). The restored tree replacing an old one keeps nothing of the old one.
                    if (wanted.name == "previous") displaced.listFiles { f -> f.isDirectory && SkillRules.validName(f.name) }.orEmpty().forEach { d ->
                        val target = File(root, d.name)
                        if (!target.exists()) d.renameTo(target)
                    }
                }
                if (root.exists()) retire(ws)
            }
            val committedTxn = runCatching { JSONObject(File(root, STATE).readText()).optString("txn") }.getOrDefault("")
            root.listFiles { f -> f.isDirectory && f.name.startsWith(".sync-") }.orEmpty().forEach { txn ->
                // Committed: named in state.json, or marked (an older commit whose retirement failed before a newer one).
                if (txn.name == committedTxn || File(txn, COMMITTED).exists()) { retire(txn); return@forEach }
                val installedLog = File(txn, INSTALLED)
                val ours = installedLog.takeIf { it.isFile }?.readLines().orEmpty().filter { SkillRules.validName(it) }
                val notRemoved = ours.filterNot { name -> File(root, name).let { !it.exists() || it.deleteRecursively() } }
                // Forget them before moving backups back, so a second run can never delete a restored folder.
                if (notRemoved.isEmpty()) installedLog.delete() else { installedLog.writeText(notRemoved.joinToString("\n", postfix = "\n")); return@forEach }
                var conflict = false
                File(txn, "old").listFiles().orEmpty().forEach { backup ->
                    val target = File(root, backup.name)
                    // Something new took the name meanwhile: keep both rather than overwrite either.
                    if (target.exists() || !backup.renameTo(target)) conflict = true
                }
                if (!conflict) retire(txn) else File(txn, INSTALLED).delete()
            }
        }

        /** Settings restore swaps the whole tree outside this store's normal mutation methods. */
        internal fun notifyFilesReplaced(root: File) {
            shared?.takeIf { it.root.absoluteFile == root.absoluteFile }?.notifyChanged()
        }
    }

    // ------------------------------------------------------------------------------------------- state

    private data class Meta(val enabled: Boolean, val source: String, val installedMs: Long, val builtin: Boolean, val syncSource: String? = null)

    private fun readState(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    private fun writeState(s: JSONObject) {
        root.mkdirs()
        val tmp = File(root, "$STATE.tmp")
        tmp.writeText(s.toString(2))
        check(tmp.renameTo(stateFile)) { "Could not save skill state" }
        notifyChanged()
    }

    private fun meta(state: JSONObject, name: String): Meta {
        val o = state.optJSONObject("skills")?.optJSONObject(name)
        return Meta(o?.optBoolean("enabled", false) ?: false, o?.optString("source").orEmpty(), o?.optLong("installedMs") ?: 0L, o?.optBoolean("builtin") ?: false,
            o?.optString("syncSource")?.takeIf { it.isNotEmpty() })
    }

    private fun putMeta(state: JSONObject, name: String, m: Meta?) {
        val skills = state.optJSONObject("skills") ?: JSONObject().also { state.put("skills", it) }
        if (m == null) skills.remove(name)
        else skills.put(name, JSONObject().put("enabled", m.enabled).put("source", m.source).put("installedMs", m.installedMs).put("builtin", m.builtin).put("syncSource", m.syncSource))
    }

    // ------------------------------------------------------------------------------------------- reading

    private fun dirOf(name: String): File? = if (SkillRules.validName(name)) File(root, name) else null

    /** Every installed skill whose SKILL.md parses, sorted by name. */
    fun list(): List<InstalledSkill> = synchronized(lock) {
        val state = readState()
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && SkillRules.validName(it.name) }.orEmpty()
        dirs.mapNotNull { load(it, state) }.sortedBy { it.name }
    }

    fun enabled(): List<InstalledSkill> = list().filter { it.enabled }

    fun get(name: String): InstalledSkill? = synchronized(lock) { dirOf(name)?.takeIf { it.isDirectory }?.let { load(it, readState()) } }

    private fun load(dir: File, state: JSONObject): InstalledSkill? {
        val md = File(dir, SkillArchive.SKILL_FILE).takeIf { it.isFile } ?: return null
        val parse = SkillParser.parse(md.readText(), dir.name)
        val manifest = parse.manifest ?: return null
        if (manifest.name != dir.name) return null
        val m = meta(state, dir.name)
        val files = walk(dir).map { (rel, f) ->
            // Listing looks at the first 8 KB only; readFile checks the whole file.
            val head = f.inputStream().use { s -> ByteArray(8 * 1024).let { b -> val n = s.read(b); b.copyOf(maxOf(n, 0)) } }
            SkillFileInfo(rel, f.length(), f.length() <= limits.maxFileBytes && SkillFileKinds.isTextName(rel) && head.none { it == 0.toByte() },
                SkillFileKinds.isScript(rel))
        }
        return InstalledSkill(manifest, m.enabled, m.source, m.installedMs, m.builtin, files, parse.warnings, m.syncSource)
    }

    private fun walk(dir: File): List<Pair<String, File>> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath to it }.sortedBy { it.first }.toList()

    /** The SKILL.md instructions (frontmatter removed). */
    fun body(name: String): String? = synchronized(lock) {
        val dir = dirOf(name) ?: return null
        val md = File(dir, SkillArchive.SKILL_FILE).takeIf { it.isFile } ?: return null
        SkillParser.parse(md.readText(), name).takeIf { it.ok }?.body
    }

    sealed class FileRead {
        class Text(val path: String, val text: String, val truncated: Boolean, val size: Long) : FileRead()
        class Refused(val reason: String) : FileRead()
    }

    /**
     * A text file of skill [name]. The path is normalized and must stay inside the skill's folder (checked again on
     * the canonical file, so a symbolic link cannot lead out); binary files are refused.
   */
    fun readFile(name: String, path: String, maxChars: Int = limits.maxReadChars): FileRead = synchronized(lock) {
        val resolved = try { resolveFile(name, path) } catch (e: IllegalArgumentException) {
            return FileRead.Refused(e.message.orEmpty())
        }
        val rel = resolved.first
        val canon = resolved.second
        if (canon.length() > limits.maxFileBytes) return FileRead.Refused("\"$rel\" is too large to read.")
        val bytes = canon.readBytes()
        if (!SkillFileKinds.isText(rel, bytes)) return FileRead.Refused("\"$rel\" is not a text file; only text files can be read.")
        val text = bytes.decodeToString()
        FileRead.Text(rel, text.take(maxChars), text.length > maxChars, canon.length())
    }

    /** Shared path validation for reads and execution; resolving a script never loads its bytes into memory. */
    fun resolveFile(name: String, path: String): Pair<String, File> = synchronized(lock) {
        val dir = dirOf(name)?.takeIf { it.isDirectory } ?: throw IllegalArgumentException("No skill named \"$name\".")
        val rel = SkillPaths.normalize(path) ?: throw IllegalArgumentException("\"$path\" is not a valid path inside the skill.")
        val base = dir.canonicalFile
        val canon = File(dir, rel).canonicalFile
        require(base == File(root.canonicalFile, name) && canon.path.startsWith(base.path + File.separator)) {
            "\"$path\" is outside the skill."
        }
        require(canon.isFile) { "The skill has no file \"$rel\". Files: ${walk(dir).joinToString(", ") { it.first }.take(600)}" }
        require(canon.length() <= limits.maxFileBytes) { "\"$rel\" is too large to read or execute." }
        rel to canon
    }

    // ------------------------------------------------------------------------------------------- changing

    fun setEnabled(name: String, on: Boolean): Boolean = synchronized(lock) {
        if (get(name) == null) return false
        val state = readState()
        putMeta(state, name, meta(state, name).copy(enabled = on))
        writeState(state)  // writeState notifies observers → the omp skills snapshot + warm engine refresh.
        true
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        if (meta(readState(), name).syncSource != null) return false
        val dir = dirOf(name)?.takeIf { it.isDirectory } ?: return false
        val ok = dir.deleteRecursively()
        val state = readState()
        putMeta(state, name, null)
        writeState(state)
        ok
    }

    fun exists(name: String): Boolean = synchronized(lock) { dirOf(name)?.isDirectory == true }

    fun managedMessage(name: String): String? = synchronized(lock) {
        meta(readState(), name).syncSource?.let { "Managed by GitHub source $it; remove the source to delete it" }
    }

    /**
     * Installs [c] as an (initially disabled) skill. A skill of the same name is replaced only with [replace]
     * (its on/off state is kept); otherwise the outcome is [InstallOutcome.Duplicate]. Files are written to a
     * temporary folder first and moved into place, so a failure never leaves half a skill.
   */
    fun install(c: SkillCandidate, source: String, replace: Boolean = false, builtin: Boolean = false, syncSource: String? = null): InstallOutcome = synchronized(lock) {
        val name = c.name
        if (!c.ok || name == null) return InstallOutcome.Failed(c.label, c.allErrors.firstOrNull() ?: "invalid skill")
        if (c.totalBytes > limits.maxSkillBytes) return InstallOutcome.Failed(name, "larger than ${SkillArchive.mb(limits.maxSkillBytes)}")
        val target = File(root, name)
        val prev = meta(readState(), name)
        if (prev.syncSource != null && prev.syncSource != syncSource) return InstallOutcome.Failed(name, managedMessage(name)!!)
        if (syncSource != null && target.exists() && prev.syncSource != syncSource) return InstallOutcome.Failed(name, "This skill belongs to a manual import or another source")
        val existed = target.exists()
        if (existed && !replace) return InstallOutcome.Duplicate(name)
        root.mkdirs()
        val tmp = File(root, ".tmp-$name-${clock()}")
        try {
            tmp.deleteRecursively()
            for ((rel, bytes) in c.files) {
                val safe = SkillPaths.normalize(rel) ?: continue
                val f = File(tmp, safe)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            val old = File(root, ".old-$name-${clock()}")
            if (existed && !target.renameTo(old)) return InstallOutcome.Failed(name, "could not replace the installed skill")
            if (!tmp.renameTo(target)) {
                if (existed) old.renameTo(target)
                return InstallOutcome.Failed(name, "could not store the skill")
            }
            old.deleteRecursively()
        } finally {
            tmp.deleteRecursively()
        }
        val state = readState()
        putMeta(state, name, Meta(enabled = (existed || syncSource != null && prev.syncSource == syncSource) && prev.enabled,
            source = source, installedMs = clock(), builtin = builtin, syncSource = syncSource))
        writeState(state)
        InstallOutcome.Installed(name, existed)
    }

    /**
     * Creates or edits a simple skill (name, description, instructions) from the in-app editor. Editing keeps the
     * skill's other files and frontmatter fields; renaming moves the folder. Returns the errors, empty on success.
   */
    fun saveSimple(name: String, description: String, instructions: String, editing: String? = null): List<String> = synchronized(lock) {
        (editing ?: name).let { managedMessage(it) }?.let { return listOf(it) }
        val errors = SkillRules.nameProblems(name).toMutableList()
        val d = description.trim().replace(Regex("\\s*\n\\s*"), " ")
        if (d.isEmpty()) errors += "the description is empty"
        if (d.length > SkillRules.DESCRIPTION_MAX) errors += "the description is longer than ${SkillRules.DESCRIPTION_MAX} characters"
        if (instructions.isBlank()) errors += "the instructions are empty"
        if (instructions.toByteArray().size > limits.maxFileBytes) errors += "the instructions are larger than ${SkillArchive.mb(limits.maxFileBytes)}"
        if (name != editing && exists(name)) errors += "a skill named \"$name\" already exists"
        if (errors.isNotEmpty()) return errors
        val old = editing?.let { get(it) }
        val manifest = (old?.manifest ?: SkillManifest(name, d)).copy(name = name, description = d)
        val files = LinkedHashMap<String, ByteArray>()
        if (old != null) {
            val dir = File(root, old.name)
            walk(dir).forEach { (rel, f) -> if (rel != SkillArchive.SKILL_FILE) files[rel] = f.readBytes() }
        }
        files[SkillArchive.SKILL_FILE] = SkillParser.render(manifest, instructions).toByteArray()
        val parse = SkillParser.parse(files.getValue(SkillArchive.SKILL_FILE).decodeToString(), name)
        val candidate = SkillCandidate("", parse, files)
        val state = readState()
        val prevMeta = old?.let { meta(state, it.name) }
        val outcome = install(candidate, prevMeta?.source?.ifEmpty { null } ?: SOURCE_CREATED, replace = name == editing, builtin = prevMeta?.builtin ?: false)
        if (outcome is InstallOutcome.Failed) return listOf(outcome.reason)
        if (old != null && editing != name) {
            delete(editing)
            if (prevMeta?.enabled == true) setEnabled(name, true)
        }
        emptyList()
    }

    /** Includes metadata without a folder, as retained by settings restore. */
    fun mirroredNames(sourceId: String): Set<String> = synchronized(lock) {
        val state = readState()
        state.optJSONObject("skills")?.keys()?.asSequence()?.filter { SkillRules.validName(it) && meta(state, it).syncSource == sourceId }?.toSet().orEmpty()
    }

    fun hasMissingMirrors(sourceId: String): Boolean = synchronized(lock) {
        mirroredNames(sourceId).any { !File(root, "$it/${SkillArchive.SKILL_FILE}").isFile }
    }

    /**
     * Validates/stages a complete source before replacing any folders. On IO failure every touched folder is
     * restored and state.json remains unchanged. Store readers and manual mutations share the transaction lock.
     * An empty candidate list is an intentional upstream deletion of all mirrors for this source.
   */
    fun mirror(sourceId: String, candidates: List<SkillCandidate>, source: String): SkillSyncCounts = synchronized(lock) {
        val state = readState()
        val owned = mirroredNames(sourceId)
        val unique = LinkedHashMap<String, SkillCandidate>()
        var skipped = 0
        val notes = mutableListOf<String>()
        // One broken skill must not block the rest of the repository; a mirror it would replace is kept, not deleted.
        val protected = HashSet<String>()
        candidates.forEach { c ->
            val problem = when {
                !c.ok || c.name == null -> c.allErrors.firstOrNull() ?: "invalid manifest"
                !(c.totalBytes <= limits.maxSkillBytes && c.files.size <= limits.maxFiles && c.files.values.all { it.size <= limits.maxFileBytes }) -> "exceeds the size limits"
                !c.files.keys.all { SkillPaths.normalize(it) == it } -> "unsafe file path"
                else -> null
            }
            if (problem != null) {
                // Unparsable manifests have no name: the folder name (the format's convention) protects that mirror too.
                skipped++; notes += "${c.name ?: c.label}: $problem"; c.name?.let(protected::add)
                c.root.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let(protected::add); return@forEach
            }
            val name = c.name!!
            if (unique.containsKey(name)) { skipped++; notes += "$name: the repository holds this name twice; the first one was used" } else unique[name] = c
        }
        val changes = LinkedHashMap<String, SkillCandidate>()
        var added = 0; var updated = 0
        for ((name, c) in unique) {
            val target = File(root, name)
            val owner = meta(state, name).syncSource
            // Two sources claiming one name would overwrite each other on every sync: the first owner keeps it.
            if (owner != null && owner != sourceId) {
                skipped++; notes += "$name: already synced from source $owner"; continue
            }
            // The repository is authoritative: a local skill (created, imported or example) with this name is replaced.
            val takeover = owner == null && target.exists()
            val same = !takeover && target.isDirectory && walk(target).map { it.first }.toSet() == c.files.keys &&
                c.files.all { (rel, bytes) -> File(target, rel).readBytes().contentEquals(bytes) }
            if (same) continue
            changes[name] = c
            if (name in owned || takeover) updated++ else added++
        }
        // An invalid skill without a readable name could be any of our mirrors: delete nothing until it is fixed.
        val unnamedInvalid = candidates.any { !it.ok && it.name == null }
        if (unnamedInvalid) notes += "Removals paused: a skill in the repository could not be read, so none of this source's skills were deleted."
        val removed = if (unnamedInvalid) emptySet() else owned - unique.keys - protected
        if (changes.isEmpty() && removed.isEmpty()) return SkillSyncCounts(skipped = skipped, notes = notes)
        check(root.isDirectory || root.mkdirs()) { "Could not create skills folder" }
        val txn = File(root, ".sync-${java.util.UUID.randomUUID()}")
        val saved = LinkedHashSet<String>()
        val installed = LinkedHashSet<String>()
        var committed = false
        var rollbackIncomplete = false
        try {
            changes.forEach { (name, c) -> c.files.forEach { (rel, bytes) ->
                val f = File(txn, "new/$name/$rel")
                check(f.parentFile!!.isDirectory || f.parentFile!!.mkdirs()) { "Could not stage skills" }
                f.writeBytes(bytes)
            } }
            check(txn.isDirectory || txn.mkdirs()) { "Could not stage skills" }
            val installedLog = File(txn, INSTALLED)
            for (name in changes.keys + removed) {
                val target = File(root, name)
                if (target.exists()) {
                    val backup = File(txn, "old/$name")
                    check(backup.parentFile!!.isDirectory || backup.parentFile!!.mkdirs()) { "Could not stage old skills" }
                    check(target.renameTo(backup)) { "Could not replace skill $name" }
                    saved += name
                }
                if (name in changes) {
                    // Logged before the move: if the process dies right after it, recovery knows this folder is ours.
                    installedLog.appendText("$name\n")
                    check(File(txn, "new/$name").renameTo(target)) { "Could not save skill $name" }
                    installed += name
                    val previous = meta(state, name)
                    // A replaced local skill keeps its on/off switch; brand-new mirrors start off.
                    putMeta(state, name, Meta((previous.syncSource == sourceId || previous.syncSource == null) && previous.enabled, source, clock(), false, sourceId))
                } else putMeta(state, name, null)
            }
            state.put("txn", txn.name) // the commit point: recovery treats this transaction as done from here on
            writeState(state)
            committed = true
            runCatching { File(txn, COMMITTED).createNewFile() } // stays valid once a later sync names another txn
        } catch (e: Exception) {
            val stuck = installed.filterNot { File(root, it).deleteRecursively() }
            // Our removed installs: recovery must never delete those names again (the user may reuse them). Any that
            // could not be removed stay listed, and the transaction is kept for start-up recovery.
            runCatching { if (stuck.isEmpty()) File(txn, INSTALLED).delete() else File(txn, INSTALLED).writeText(stuck.joinToString("\n", postfix = "\n")) }
            rollbackIncomplete = stuck.isNotEmpty()
            // Try every folder: one failed rename must not leave the others hidden in the transaction folder.
            val failed = saved.filterNot { name -> File(txn, "old/$name").renameTo(File(root, name)) }
            if (failed.isNotEmpty()) e.addSuppressed(IllegalStateException("Could not restore ${failed.joinToString()}; recovery files remain at ${txn.name}"))
            throw e
        } finally {
            // If restoring a folder failed, retain its backup for recovery; otherwise retire the folder atomically.
            if (committed || !rollbackIncomplete && (saved.all { File(root, it).exists() } || !File(txn, "old").exists())) {
                // Retire atomically; if the rename fails the folder stays for start-up recovery instead of a partial delete.
                val trash = File(root, ".trash-${java.util.UUID.randomUUID()}")
                if (txn.renameTo(trash)) trash.deleteRecursively()
            }
        }
        SkillSyncCounts(added, updated, removed.size, skipped, notes)
    }

    /** Only the source-removal confirmation path calls this; manual delete refuses mirrors. */
    fun removeSource(sourceId: String): SkillSyncCounts = mirror(sourceId, emptyList(), "")

    /** Writes skill [name] as a zip with one top folder named after the skill (the usual way skills are shared). */
    fun exportZip(name: String, out: OutputStream): Boolean = synchronized(lock) {
        val dir = dirOf(name)?.takeIf { it.isDirectory } ?: return false
        ZipOutputStream(out).use { z ->
            walk(dir).forEach { (rel, f) ->
                z.putNextEntry(ZipEntry("$name/$rel"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        true
    }

    /**
     * Adds the built-in example skills (switched off). Without [force] only once per store (a deleted example stays
     * deleted); with [force] ("Restore examples") the missing ones come back. Existing skills are never overwritten.
   */
    fun seedExamples(force: Boolean): Int = synchronized(lock) {
        val state = readState()
        if (!force && state.optBoolean("examplesSeeded")) return 0
        var added = 0
        BuiltinSkills.all.forEach { (name, files) ->
            if (exists(name)) return@forEach
            val md = files.getValue(SkillArchive.SKILL_FILE)
            val c = SkillCandidate("", SkillParser.parse(md, name), files.mapValues { it.value.toByteArray() })
            if (install(c, SOURCE_EXAMPLE, builtin = true) is InstallOutcome.Installed) added++
        }
        val s = readState().put("examplesSeeded", true)
        writeState(s)
        added
    }
}
