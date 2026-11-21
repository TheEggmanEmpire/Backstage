package com.eggman.backstage.skills

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** One skill found in an archive (or a lone SKILL.md), ready to be installed. */
data class SkillCandidate(
    /** Its folder inside the archive ("" = the archive's top level). */
    val root: String,
    val parse: SkillParse,
    /** Relative path inside the skill → content; always holds SKILL.md when [ok]. */
    val files: Map<String, ByteArray>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val name: String? get() = parse.manifest?.name
    val ok: Boolean get() = parse.ok && errors.isEmpty()
    val allErrors: List<String> get() = parse.errors + errors
    val allWarnings: List<String> get() = parse.warnings + warnings
    val totalBytes: Long get() = files.values.sumOf { it.size.toLong() }
    /** Label for lists: the name, else the folder. */
    val label: String get() = name ?: root.ifEmpty { "(top level)" }
}

/** What an archive held: the skills in it and anything that is skipped or wrong with the archive itself. */
data class ArchiveScan(val candidates: List<SkillCandidate>, val problems: List<String>) {
    val fatal: Boolean get() = candidates.isEmpty()
}

/**
 * Reads skills out of a zip (.zip or.skill) or a single SKILL.md. Multi-skill archives and repository archives
 * (one top folder, skills in `skills/…`) work: every folder holding a SKILL.md is a skill, and each file belongs to the
 * innermost skill folder around it. Entry names are normalized by [SkillPaths.normalize]; entries that could escape
 * the skill folder ("zip slip": absolute paths, "..") are skipped and reported, never written. Sizes are counted
 * while reading, so a lying zip header cannot get past [SkillLimits].
 */
object SkillArchive {
    const val SKILL_FILE = "SKILL.md"

    fun isZip(head: ByteArray): Boolean = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
        (head[2].toInt() == 3 && head[3].toInt() == 4 || head[2].toInt() == 5 && head[3].toInt() == 6)

    /**
     * Skills in [file]: a zip, or a SKILL.md text file. [subPath] limits a repository archive to one folder (relative
     * to the repository root, i.e. below the archive's single top folder when there is one).
   */
    fun scan(file: File, subPath: String? = null, limits: SkillLimits = SkillLimits()): ArchiveScan {
        if (file.length() > limits.maxArchiveBytes) return ArchiveScan(emptyList(), listOf("the file is larger than ${mb(limits.maxArchiveBytes)}"))
        val head = file.inputStream().use { s -> ByteArray(4).let { b -> val n = s.read(b); if (n < 4) b.copyOf(maxOf(n, 0)) else b } }
        if (isZip(head)) return scanZip(file, subPath, limits)
        val bytes = file.readBytes()
        if (bytes.size > limits.maxFileBytes) return ArchiveScan(emptyList(), listOf("SKILL.md is larger than ${mb(limits.maxFileBytes)}"))
        if (!SkillFileKinds.looksLikeText(bytes)) return ArchiveScan(emptyList(), listOf("this is neither a zip archive nor a SKILL.md text file"))
        return fromSkillMd(bytes.decodeToString())
    }

    /** A lone SKILL.md (no extra files). */
    fun fromSkillMd(text: String): ArchiveScan {
        val bytes = text.toByteArray()
        val parse = SkillParser.parse(text)
        return ArchiveScan(listOf(SkillCandidate("", parse, mapOf(SKILL_FILE to bytes))), emptyList())
    }

    fun scanZip(file: File, subPath: String? = null, limits: SkillLimits = SkillLimits(), paths: List<String>? = null): ArchiveScan {
        val problems = mutableListOf<String>()
        val zip = try { ZipFile(file) } catch (e: Exception) {
            return ArchiveScan(emptyList(), listOf("not a readable zip archive (${e.message ?: e.javaClass.simpleName})"))
        }
        zip.use { zf ->
            val entries = try {
                val all = ArrayList<java.util.zip.ZipEntry>()
                val en = zf.entries()
                while (en.hasMoreElements()) {
                    all += en.nextElement()
                    if (all.size > limits.maxEntries) return ArchiveScan(emptyList(), listOf("the archive lists more than ${limits.maxEntries} entries"))
                }
                all
            } catch (e: Exception) {
                // Android 14+ refuses entries with ".." or a leading "/" itself (ZipPathValidator).
                return ArchiveScan(emptyList(), listOf("the archive contains an unsafe or invalid entry name (${e.message ?: "zip error"})"))
            }
            val byPath = LinkedHashMap<String, java.util.zip.ZipEntry>()
            for (e in entries) {
                if (e.isDirectory) continue
                val p = SkillPaths.normalize(e.name)
                if (p == null) { problems += "skipped an unsafe path: ${e.name.take(120)}"; continue }
                if (SkillPaths.isJunk(p)) continue
                if (byPath.putIfAbsent(p, e) != null) problems += "skipped a duplicate entry: $p"
            }
            // Repository archives wrap everything in one folder ("repo-main/"); paths below are what subPath means.
            val top = byPath.keys.map { it.substringBefore('/', "") }.distinct().singleOrNull()?.takeIf { t -> t.isNotEmpty() && byPath.keys.all { it.startsWith("$t/") } }
            fun logical(p: String) = if (top == null) p else p.removePrefix("$top/")
            val sub = subPath?.let { SkillPaths.normalize(it) }
            val selected = paths?.takeUnless { "." in it } ?: listOfNotNull(sub).takeIf { paths == null }.orEmpty()
            require(selected.all { SkillPaths.normalize(it) == it }) { "Invalid repository folder" }
            val paths = byPath.keys.filter { p ->
                val l = logical(p)
                selected.isEmpty() || selected.any { l == it || l.startsWith("$it/") }
            }
            val skillFiles = paths.filter { SkillPaths.fileName(it) == SKILL_FILE }
            val lowerCase = paths.filter { SkillPaths.fileName(it) != SKILL_FILE && SkillPaths.fileName(it).equals(SKILL_FILE, ignoreCase = true) }
            val markers = if (skillFiles.isNotEmpty()) skillFiles else lowerCase.also {
                if (it.isNotEmpty()) problems += "the skill file is named \"${SkillPaths.fileName(it[0])}\"; the format names it SKILL.md"
            }
            if (markers.isEmpty()) {
                return ArchiveScan(emptyList(), problems + (if (sub != null) "no SKILL.md in \"$sub\"" else "no SKILL.md in the archive"))
            }
            var roots = markers.map { SkillPaths.parent(it) }.distinct().sortedBy { it }
            if (roots.size > limits.maxSkills) {
                problems += "the archive holds ${roots.size} skills; only the first ${limits.maxSkills} are offered"
                roots = roots.take(limits.maxSkills)
            }
            fun ownerOf(p: String): String? = roots.filter { r -> r.isEmpty() || p.startsWith("$r/") }.maxByOrNull { it.length }
            val grouped = paths.groupBy { ownerOf(it) }
            // Preflight central-directory sizes before allocating candidate payloads. Also count
            // actual streamed bytes below: entry sizes are not a trusted allocation budget.
            val retainedPaths = roots.flatMap { grouped[it].orEmpty().sorted().take(limits.maxFiles) }
            var declaredBytes = 0L
            for (p in retainedPaths) {
                val size = byPath.getValue(p).size.coerceAtLeast(0)
                if (size > limits.maxExpandedBytes - declaredBytes)
                    return ArchiveScan(emptyList(), problems + "archive expanded content exceeds ${mb(limits.maxExpandedBytes)}")
                declaredBytes += size
            }
            var remainingBytes = limits.maxExpandedBytes
            val candidates = try {
                roots.map { root ->
                    val marker = markers.first { SkillPaths.parent(it) == root }
                    val members = grouped[root].orEmpty().sorted()
                    readCandidate(zf, byPath, root, marker, members, logical(root).ifEmpty { top.orEmpty() }, limits, remainingBytes)
                        .also { remainingBytes -= it.totalBytes }
                }
            } catch (_: ExpandedLimitException) {
                return ArchiveScan(emptyList(), problems + "archive expanded content exceeds ${mb(limits.maxExpandedBytes)}")
            }
            return ArchiveScan(candidates, problems)
        }
    }

    private fun readCandidate(
        zf: ZipFile,
        byPath: Map<String, java.util.zip.ZipEntry>,
        root: String,
        marker: String,
        members: List<String>,
        displayRoot: String,
        limits: SkillLimits,
        expandedBytesAvailable: Long,
    ): SkillCandidate {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        if (members.size > limits.maxFiles) errors += "more than ${limits.maxFiles} files"
        for (p in members.take(limits.maxFiles)) {
            val rel = if (root.isEmpty()) p else p.removePrefix("$root/")
            val entry = byPath.getValue(p)
            if (entry.size > limits.maxFileBytes) { errors += "$rel is larger than ${mb(limits.maxFileBytes)}"; break }
            val remaining = expandedBytesAvailable - total
            if (entry.size > remaining) throw ExpandedLimitException()
            val perSkillRemaining = (limits.maxSkillBytes - total).coerceAtLeast(0)
            val max = minOf(limits.maxFileBytes, perSkillRemaining, remaining)
            val bytes = zf.getInputStream(entry).use { readBounded(it, max) }
            if (bytes == null) {
                if (remaining <= minOf(limits.maxFileBytes, perSkillRemaining)) throw ExpandedLimitException()
                errors += if (perSkillRemaining < limits.maxFileBytes) "the skill is larger than ${mb(limits.maxSkillBytes)}"
                    else "$rel is larger than ${mb(limits.maxFileBytes)}"
                break
            }
            total += bytes.size
            if (total > limits.maxSkillBytes) { errors += "the skill is larger than ${mb(limits.maxSkillBytes)}"; break }
            files[if (p == marker) SKILL_FILE else rel] = bytes
        }
        val md = files[SKILL_FILE]
        val folder = SkillPaths.fileName(displayRoot).ifEmpty { null }
        val parse = when {
            md == null -> SkillParse(null, "", listOf("SKILL.md could not be read"), emptyList())
            !SkillFileKinds.looksLikeText(md) -> SkillParse(null, "", listOf("SKILL.md is not UTF-8 text"), emptyList())
            else -> SkillParser.parse(md.decodeToString(), folder)
        }
        val scripts = files.keys.filter { SkillFileKinds.isScript(it) }
        if (scripts.isNotEmpty()) warnings += "${scripts.size} script file(s) contain real code: native execution requires Run skill scripts to be enabled; omp can run them directly"
        return SkillCandidate(displayRoot, parse, files, errors, warnings)
    }

    private class ExpandedLimitException : RuntimeException()

    /** Up to [max] bytes of [input], or null when there is more. */
    fun readBounded(input: InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun mb(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes bytes"
    }
}
