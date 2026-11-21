package com.eggman.backstage.skills

/** Size and count limits for skills (per import, per skill, per file). */
data class SkillLimits(
    /** All files of one skill together. */
    val maxSkillBytes: Long = 150_000_000,
    /** One file inside a skill. */
    val maxFileBytes: Long = 50_000_000,
    /** Files in one skill. */
    val maxFiles: Int = 20_000,
    /** A downloaded or picked archive (a folder of many skills is allowed; the ceiling is anti-abuse, not a feature cap). */
    val maxArchiveBytes: Long = 300_000_000,
    /** Entries an archive may list (repository archives list many files we skip). */
    val maxEntries: Int = 2_000_000,
    /** Skills taken from one archive. */
    val maxSkills: Int = 5_000,
    /** Text read_skill_file returns at once. */
    val maxReadChars: Int = 40_000,
    /** SKILL.md text use_skill returns. */
    val maxBodyChars: Int = 60_000,
    /** Total expanded file bytes retained across all candidates in one import. */
    val maxExpandedBytes: Long = 64_000_000, // fits an ordinary app heap with copy overhead
)

/** Relative paths inside a skill: one safe form, nothing that can leave the skill's folder. */
object SkillPaths {
    /**
     * [raw] as a normalized relative path ("references/a.md"), or null when it is unsafe or empty: absolute paths,
     * drive letters, ".." segments, NUL or other control characters. Backslashes count as separators and "." segments
     * and duplicate slashes are dropped.
   */
    fun normalize(raw: String): String? {
        if (raw.isEmpty() || raw.length > 1_000) return null
        if (raw.any { it.code < 0x20 || it == '\u007f' }) return null
        val s = raw.replace('\\', '/')
        if (s.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(s)) return null
        val parts = s.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** Archive entries every import skips (OS clutter, version-control data). */
    fun isJunk(path: String): Boolean {
        val parts = path.split('/')
        return parts.any { it == "__MACOSX" || it == ".git" || it == ".DS_Store" || it == "Thumbs.db" || it == ".svn" || it == ".hg" }
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun fileName(path: String): String = path.substringAfterLast('/')
}

/** What kind of file a skill carries: readable text, a script (text the agent may read but nothing runs) or binary. */
object SkillFileKinds {
    private val SCRIPT_EXT = setOf("py", "sh", "bash", "zsh", "fish", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "ps1", "bat", "cmd")
    private val TEXT_EXT = setOf(
        "md", "markdown", "txt", "text", "rst", "adoc", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf", "csv",
        "tsv", "xml", "html", "htm", "css", "svg", "tex", "log", "properties", "env", "sql", "graphql", "proto", "mustache",
        "hbs", "j2", "jinja", "tmpl", "template", "license", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs",
        "swift", "dart", "lua", "r", "m",
    ) + SCRIPT_EXT
    private val BINARY_SCRIPT_EXT = setOf("exe", "dll", "so", "dylib", "jar", "apk", "dex", "bin", "class", "wasm", "pyc")
    private val TEXT_NAMES = setOf("license", "licence", "readme", "notice", "makefile", "dockerfile", "changelog", "authors")

    fun extension(path: String): String = SkillPaths.fileName(path).substringAfterLast('.', "").lowercase()

    /** Scripts and executables: stored and listed, never run (Android has no interpreter for them here). */
    fun isScript(path: String): Boolean {
        val ext = extension(path)
        return ext in SCRIPT_EXT || ext in BINARY_SCRIPT_EXT || path.startsWith("scripts/")
    }

    /** Text the agent may read: a known text name or extension AND content that is valid UTF-8 without NUL bytes. */
    fun isText(path: String, bytes: ByteArray): Boolean = isTextName(path) && looksLikeText(bytes)

    /** A file name that usually holds text (extension or a well-known name such as LICENSE). */
    fun isTextName(path: String): Boolean {
        val name = SkillPaths.fileName(path).lowercase()
        val ext = extension(path)
        return ext in TEXT_EXT || name.substringBefore('.') in TEXT_NAMES || (ext.isEmpty() && name.isNotEmpty())
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)); true }.getOrDefault(false)
    }
}
