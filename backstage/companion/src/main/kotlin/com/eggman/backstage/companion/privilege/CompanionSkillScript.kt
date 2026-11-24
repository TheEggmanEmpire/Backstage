package com.eggman.backstage.companion.privilege

import android.os.Bundle
import android.util.Base64
import com.eggman.backstage.a11y.companion.CompanionProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class CompanionSkillScript private constructor(
    private val entrypoint: String,
    val interpreter: String,
    private val args: List<String>,
    private val archive: ByteArray,
    private val preview: String,
) {
    fun approvalText(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        val input = if (args.isEmpty()) "" else "\nArguments: ${org.json.JSONArray(args)}"
        return "$entrypoint ($interpreter)$input\nSkill archive SHA-256: $hash\n\n$preview"
    }

    fun argv(stage: String): List<String> = listOf(
        "sh", "-c", stageScript, "backstage-skill", Base64.encodeToString(archive, Base64.NO_WRAP), stage, entrypoint, interpreter,
    ) + args

    companion object {
        private const val MAX_ENTRIES = 256
        private const val MAX_PREVIEW = 4000

        fun from(request: Bundle): CompanionSkillScript {
            val file = requireNotNull(request.getString("path")).also { require(safePath(it)) }
            val interpreter = when (file.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
                "sh", "bash" -> "sh"
                "js", "mjs", "ts" -> "bun"
                "py" -> "python3"
                else -> throw IllegalArgumentException("Unsupported skill interpreter.")
            }
            val argv = request.getStringArrayList("args").orEmpty().toList()
            require(argv.size <= 100 && argv.sumOf { it.length } <= 1500 && argv.none { '\u0000' in it })
            val source = requireNotNull(request.getByteArray("archive"))
            require(source.size in 1..CompanionProtocol.MAX_SKILL_ARCHIVE_BYTES)
            val safeArchive = ByteArrayOutputStream()
            val seen = mutableSetOf<String>()
            var preview: String? = null
            var remaining = CompanionProtocol.MAX_SKILL_UNPACKED_BYTES
            ZipInputStream(ByteArrayInputStream(source)).use { input ->
                ZipOutputStream(safeArchive).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        require(!entry.isDirectory && safePath(entry.name) && seen.add(entry.name) && seen.size <= MAX_ENTRIES)
                        val bytes = input.readNBytes(remaining + 1)
                        require(bytes.size <= remaining)
                        remaining -= bytes.size
                        if (entry.name == file) {
                            val script = bytes.decodeToString(throwOnInvalidSequence = true)
                            require('\u0000' !in script)
                            preview = script.take(MAX_PREVIEW).map { c ->
                                if (c == '\n' || c == '\t' || c == '\r' || (!c.isISOControl() && Character.getType(c) != Character.FORMAT.toInt())) c else '\ufffd'
                            }.joinToString("") + if (script.length > MAX_PREVIEW) "\n[Preview truncated]" else ""
                        }
                        output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                        output.write(bytes)
                        output.closeEntry()
                    }
                }
            }
            require(safeArchive.size() <= CompanionProtocol.MAX_SKILL_ARCHIVE_BYTES)
            return CompanionSkillScript(file, interpreter, argv, safeArchive.toByteArray(), requireNotNull(preview))
        }

        private fun safePath(path: String): Boolean =
            path.length in 1..480 && '\\' !in path && path.split('/').let { segments ->
                segments.size <= 12 && segments.all { part ->
                    part.isNotEmpty() && part != "." && part != ".." &&
                        part.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                }
            }

        private val stageScript = """
            set -eu
            umask 077
            stage=${'$'}2
            mkdir "${'$'}stage"
            trap 'rm -rf "${'$'}stage"' EXIT
            printf '%s' "${'$'}1" | base64 -d > "${'$'}stage/input.zip"
            file=${'$'}3
            interpreter=${'$'}4
            shift 4
            mkdir "${'$'}stage/files"
            unzip -q "${'$'}stage/input.zip" -d "${'$'}stage/files"
            cd "${'$'}stage/files"
            "${'$'}interpreter" "./${'$'}file" "${'$'}@" </dev/null
        """.trimIndent()
    }
}
