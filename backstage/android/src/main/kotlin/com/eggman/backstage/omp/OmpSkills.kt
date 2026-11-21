package com.eggman.backstage.omp

import com.eggman.backstage.skills.SkillArchive
import com.eggman.backstage.skills.SkillLimits
import com.eggman.backstage.skills.SkillPaths
import com.eggman.backstage.skills.SkillStore
import java.io.FileInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Live native omp skills. Extract `skills/<name>/...` into ~/.omp/agent, never into the skills root itself. */
object OmpSkills {
    const val MAX_ZIP_BYTES = 2_000_000_000L
    const val MARKER = ".backstage"
    const val MAX_PATH_SEGMENTS = 10
    const val MAX_DIRECTORIES = 20_000_000

    /** An open descriptor is a lease: unlinking an old generation cannot interrupt a response. */
    class Archive(val file: File) : java.io.Closeable {
        val input = FileInputStream(file)
        val size = input.channel.size()
        override fun close() = input.close()
    }

    /** One disk snapshot per store generation; failures are cached too. Invalidation never takes the cache
     * lock: SkillStore notifies while holding its own lock, while generation reads the store. */
    class SnapshotCache(
        store: SkillStore? = null,
        private val cacheDir: File = File(store?.root?.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: "."), "omp-skills-cache"),
        private val generate: (File) -> Unit = { zip(store, it) },
    ) {
        private data class Snapshot(val generation: Long, val result: Result<File>)
        private val generation = AtomicLong()
        private val lock = Any()
        private var snapshot: Snapshot? = null
        init { store?.observe(::invalidate) }

        fun invalidate() { generation.incrementAndGet() }

        fun open(): Archive = synchronized(lock) {
            while (true) {
                val current = generation.get()
                val cached = snapshot?.takeIf { it.generation == current }
                if (cached != null) {
                    val file = cached.result.getOrThrow()
                    return@synchronized Archive(file)
                }
                cacheDir.mkdirs()
                val result = runCatching {
                    val partial = File.createTempFile("skills-", ".partial", cacheDir)
                    val complete = File(cacheDir, partial.name.removeSuffix(".partial") + ".zip")
                    try {
                        generate(partial)
                        require(partial.length() <= MAX_ZIP_BYTES) { "OMP skills archive is too large" }
                        check(partial.renameTo(complete)) { "Could not publish OMP skills archive" }
                        complete
                    } finally { partial.delete() }
                }
                if (generation.get() != current) {
                    result.getOrNull()?.delete()
                    continue
                }
                val previous = snapshot?.result?.getOrNull()
                snapshot = Snapshot(current, result)
                previous?.delete()
                val file = result.getOrThrow()
                return@synchronized Archive(file)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    }

    internal fun safePath(path: String): String? =
        SkillPaths.normalize(path)?.takeIf { it == path && '\\' !in it && it.split('/').size <= MAX_PATH_SEGMENTS }

    internal fun directoryNames(names: Collection<String>): Map<String, String> {
        val used = mutableSetOf<String>()
        return names.distinct().sorted().associateWith { name ->
            val base = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(64)
                .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "skill"
            var directory = base
            var suffix = 2
            while (!used.add(directory)) {
                val tail = "-${suffix++}"
                directory = base.take(64 - tail.length) + tail
            }
            directory
        }
    }

    /** Limit both compressed and expanded size; a failed snapshot leaves the device's last good skills intact. */
    fun zip(store: SkillStore? = null, output: File = File.createTempFile("omp-skills-", ".zip")): File {
        val defaults = SkillLimits()
        val limits = store?.limits ?: defaults
        val enabled = store?.enabled().orEmpty().sortedBy { it.name }
        require(enabled.size <= minOf(limits.maxSkills, defaults.maxSkills)) { "Too many omp skills" }
        val directories = directoryNames(enabled.map { it.name })
        val directoryPrefixes = mutableSetOf("skills").apply { directories.values.forEach { add("skills/$it") } }
        val entryLimit = minOf(limits.maxEntries, defaults.maxEntries)
        var extraFiles = entryLimit - enabled.size * 2 // Reserve SKILL.md and the marker for every skill.
        require(extraFiles >= 0) { "Too many omp skills entries" }
        var budget = 22L // End-of-central-directory record, also the entire valid empty ZIP.
        var count = 0
        val maximum = minOf(limits.maxArchiveBytes, MAX_ZIP_BYTES.toLong())
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun entry(path: String, file: File?) {
                val size = file?.length() ?: 0L
                budget += size + 2L * path.toByteArray(Charsets.UTF_8).size + 128 // Headers and fixed timestamp extra fields.
                require(budget <= maximum && ++count <= minOf(limits.maxEntries, defaults.maxEntries)) { "OMP skills archive is too large" }
                // STORED also bounds extraction size independently of compressibility (no ZIP bombs).
                val checksum = CRC32()
                file?.inputStream()?.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        require(read <= size) { "Skill file grew while exporting" }
                        checksum.update(buffer, 0, n)
                    }
                    require(read == size) { "Skill file changed while exporting" }
                }
                zip.putNextEntry(ZipEntry(path).apply {
                    time = 0L
                    method = ZipEntry.STORED
                    this.size = size
                    compressedSize = size
                    crc = checksum.value
                })
                // ZipOutputStream checks both the exact size and CRC on closeEntry; a concurrent source edit
                // fails generation rather than publishing a corrupt two-pass STORED entry.
                file?.inputStream()?.use { it.copyTo(zip, 32 * 1024) }
                zip.closeEntry()
            }
            for (skill in enabled) {
                val dir = File(checkNotNull(store).root, skill.name)
                val base = dir.canonicalFile
                require(base == File(store.root.canonicalFile, skill.name)) { "Skill directory is outside the store" }
                val files = skill.files.mapNotNull { info ->
                    val path = safePath(info.path) ?: return@mapNotNull null
                    if (path == MARKER || path.startsWith("$MARKER/")) return@mapNotNull null
                    val file = File(dir, path)
                    if (!file.isFile || !file.canonicalPath.startsWith(base.path + File.separator)) return@mapNotNull null
                    path to file
                }.toMap().toSortedMap()
                require(files.containsKey(SkillArchive.SKILL_FILE)) { "Skill instructions disappeared" }
                val prefix = "skills/${directories.getValue(skill.name)}"
                val perSkillLimit = minOf(limits.maxFiles, defaults.maxFiles)
                require(perSkillLimit >= 1) { "Skill instructions exceed the entry limit" }
                val selected = sortedMapOf(SkillArchive.SKILL_FILE to files.getValue(SkillArchive.SKILL_FILE))
                for ((path, file) in files) {
                    if (path == SkillArchive.SKILL_FILE) continue
                    if (selected.size >= perSkillLimit || extraFiles == 0) break
                    val parts = "$prefix/$path".split('/')
                    val implied = (1 until parts.size).map { parts.take(it).joinToString("/") }
                    if (directoryPrefixes.size + implied.count { it !in directoryPrefixes } > MAX_DIRECTORIES) continue
                    directoryPrefixes.addAll(implied)
                    selected[path] = file
                    extraFiles--
                }
                var skillBytes = 0L
                val paths = (selected.keys + MARKER).sorted()
                for (path in paths) {
                    val file = if (path == MARKER) null else selected.getValue(path).also {
                        require(it.length() <= minOf(limits.maxFileBytes, defaults.maxFileBytes)) { "Skill file is too large" }
                    }
                    skillBytes += file?.length() ?: 0L
                    require(skillBytes <= minOf(limits.maxSkillBytes, defaults.maxSkillBytes)) { "Skill is too large" }
                    entry("$prefix/$path", file)
                }
            }
        }
        require(output.length() <= maximum) { "OMP skills archive is too large" }
        return output
    }
}
