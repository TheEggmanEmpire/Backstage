package com.eggman.backstage.skills

import java.text.Normalizer

/**
 * The SKILL.md format (the open Agent Skills layout): a folder whose SKILL.md starts with a YAML frontmatter block
 * (`name`, `description`, optionally `license`, `compatibility`, `metadata`, `allowed-tools`) followed by Markdown
 * instructions. Parsing is our own: [MiniYaml] reads the small YAML subset frontmatter uses, [SkillParser] checks the
 * fields. Errors make a skill unusable; warnings are shown in Settings and do not block an import.
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val license: String = "",
    val compatibility: String = "",
    /** `allowed-tools` as written (space separated or a list). Shown to the user; Backstage does not grant tools by it. */
    val allowedTools: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

data class SkillParse(
    val manifest: SkillManifest?,
    val body: String,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val ok: Boolean get() = manifest != null && errors.isEmpty()
}

class SkillFormatException(message: String) : IllegalArgumentException(message)

object SkillRules {
    const val NAME_MAX = 64
    const val DESCRIPTION_MAX = 1_024
    const val COMPATIBILITY_MAX = 500
    /** The spec recommends keeping SKILL.md under this many lines. */
    const val BODY_LINES_HINT = 500
    val KNOWN_KEYS = setOf("name", "description", "license", "compatibility", "metadata", "allowed-tools")

    /**
     * Problems with [name] as a skill name, or empty when it is valid: 1–64 lowercase letters or digits (Unicode
     * allowed) in hyphen-separated groups: no leading, trailing or doubled hyphen.
   */
    fun nameProblems(name: String): List<String> {
        val out = mutableListOf<String>()
        if (name.isEmpty()) return listOf("name is empty")
        if (name.length > NAME_MAX) out += "name is longer than $NAME_MAX characters"
        if (name != Normalizer.normalize(name, Normalizer.Form.NFKC)) out += "name is not in normalized (NFKC) form"
        val bad = name.filter { c -> c != '-' && !c.isDigit() && !(Character.isLetter(c) && c.lowercaseChar() == c) }
        if (bad.isNotEmpty()) out += "name may only use lowercase letters, digits and hyphens (found \"${bad.toSet().joinToString("")}\")"
        if (name.startsWith('-') || name.endsWith('-')) out += "name must not start or end with a hyphen"
        if ("--" in name) out += "name must not contain two hyphens in a row"
        return out
    }

    fun validName(name: String) = nameProblems(name).isEmpty()

    /** A best-effort valid name made from free text ("My Skill!" → "my-skill"); empty when nothing usable is left. */
    fun suggestName(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        .map { if (Character.isLetterOrDigit(it)) it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(NAME_MAX).trimEnd('-')
}

/** Splits SKILL.md into frontmatter and body and validates the fields. */
object SkillParser {
    /** (frontmatter text, body) or a [SkillFormatException] when there is no frontmatter block. */
    fun split(text: String): Pair<String, String> {
        val t = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = t.split('\n')
        if (lines.isEmpty() || lines[0].trimEnd() != "---") {
            throw SkillFormatException("SKILL.md must start with a frontmatter block: a line \"---\", the fields, and another \"---\"")
        }
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
            ?: throw SkillFormatException("the frontmatter block is not closed with a \"---\" line")
        val front = lines.subList(1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim('\n').trimEnd()
        return front to body
    }

    /**
     * Parses a SKILL.md. [folderName] is the folder it came from (the spec wants it to equal the name; Backstage
     * stores skills under their name, so a mismatch is only a warning).
   */
    fun parse(text: String, folderName: String? = null): SkillParse {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val (front, body) = try { split(text) } catch (e: SkillFormatException) {
            return SkillParse(null, "", listOf(e.message ?: "invalid SKILL.md"), emptyList())
        }
        val map = try { MiniYaml.parse(front, warnings) } catch (e: SkillFormatException) {
            return SkillParse(null, body, listOf("frontmatter: ${e.message}"), warnings)
        }
        fun scalar(key: String): String? = when (val v = map[key]) {
            null -> null
            is String -> v
            is List<*> -> { errors += "$key must be text, not a list"; null }
            else -> { errors += "$key must be text, not a mapping"; null }
        }
        val name = scalar("name")?.trim().orEmpty()
        val description = scalar("description")?.trim().orEmpty()
        if (!map.containsKey("name") || name.isEmpty()) errors += "the required field \"name\" is missing"
        else errors += SkillRules.nameProblems(name)
        if (!map.containsKey("description") || description.isEmpty()) errors += "the required field \"description\" is missing"
        else if (description.length > SkillRules.DESCRIPTION_MAX) errors += "description is longer than ${SkillRules.DESCRIPTION_MAX} characters"
        val license = scalar("license")?.trim().orEmpty()
        val compatibility = scalar("compatibility")?.trim().orEmpty()
        if (compatibility.length > SkillRules.COMPATIBILITY_MAX) warnings += "compatibility is longer than ${SkillRules.COMPATIBILITY_MAX} characters"
        val allowed = when (val v = map["allowed-tools"]) {
            null -> emptyList()
            is String -> v.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            is List<*> -> v.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            else -> { warnings += "allowed-tools is not a list; ignored"; emptyList() }
        }
        if (allowed.isNotEmpty()) warnings += "allowed-tools is informational here: Backstage's own tools and approvals apply"
        val metadata = when (val v = map["metadata"]) {
            null -> emptyMap()
            is Map<*, *> -> v.entries.associate { (k, value) ->
                if (value !is String) warnings += "metadata.$k is not plain text; kept as text"
                k.toString() to (value?.toString() ?: "")
            }
            is String -> { if (v.isNotBlank()) warnings += "metadata should be a mapping; ignored"; emptyMap() }
            else -> { warnings += "metadata should be a mapping; ignored"; emptyMap() }
        }
        (map.keys - SkillRules.KNOWN_KEYS).sorted().forEach { warnings += "unknown field \"$it\" is ignored" }
        if (folderName != null && name.isNotEmpty() && folderName != name) warnings += "folder \"$folderName\" differs from the name; stored as \"$name\""
        if (body.isBlank()) warnings += "SKILL.md has no instructions after the frontmatter"
        val lines = body.count { it == '\n' } + 1
        if (lines > SkillRules.BODY_LINES_HINT) warnings += "SKILL.md has $lines lines (more than ${SkillRules.BODY_LINES_HINT} costs context when it is used)"
        val manifest = if (errors.isEmpty()) SkillManifest(name, description, license, compatibility, allowed, metadata) else null
        return SkillParse(manifest, body, errors, warnings)
    }

    /** SKILL.md text for a manifest and body (in-app editor, examples). Values are quoted when YAML needs it. */
    fun render(m: SkillManifest, body: String): String = buildString {
        append("---\n")
        append("name: ").append(MiniYaml.quote(m.name)).append('\n')
        append("description: ").append(MiniYaml.quote(m.description)).append('\n')
        if (m.license.isNotBlank()) append("license: ").append(MiniYaml.quote(m.license)).append('\n')
        if (m.compatibility.isNotBlank()) append("compatibility: ").append(MiniYaml.quote(m.compatibility)).append('\n')
        if (m.allowedTools.isNotEmpty()) append("allowed-tools: ").append(MiniYaml.quote(m.allowedTools.joinToString(" "))).append('\n')
        if (m.metadata.isNotEmpty()) {
            append("metadata:\n")
            m.metadata.forEach { (k, v) -> append("  ").append(MiniYaml.quote(k)).append(": ").append(MiniYaml.quote(v)).append('\n') }
        }
        append("---\n\n")
        append(body.trim()).append('\n')
    }
}

/**
 * A small YAML reader for frontmatter: block mappings (nested by indentation), block and flow sequences, flow
 * mappings, plain scalars (also continued on more-indented lines), single- and double-quoted scalars, literal (`|`) and
 * folded (`>`) block scalars, and comments. Values stay text: no number, boolean or date conversion. Anchors, tags
 * and multi-document streams are not supported (they do not occur in skill frontmatter).
 */
object MiniYaml {
    private class Line(val no: Int, val indent: Int, val text: String)

    fun parse(text: String, warnings: MutableList<String> = mutableListOf()): Map<String, Any?> {
        val raw = text.replace("\r\n", "\n").split('\n')
        val lines = raw.mapIndexed { i, l ->
            val lead = l.takeWhile { it == ' ' || it == '\t' }
            if ('\t' in lead && l.isNotBlank()) throw SkillFormatException("line ${i + 1}: tabs cannot be used for indentation")
            Line(i + 1, lead.length, l)
        }
        val r = Reader(lines, warnings)
        val out = r.mapping(0)
        r.skipBlank()
        if (r.i < lines.size) throw SkillFormatException("line ${lines[r.i].no}: unexpected indentation")
        return out
    }

    /** [s] as a YAML scalar: plain when that reads back unchanged, else double-quoted. */
    fun quote(s: String): String {
        val plainOk = s.isNotEmpty() && s.trim() == s && '\n' !in s && ": " !in s && " #" !in s && !s.endsWith(':') &&
            s[0] !in "!&*{}[]|>'\"%@`#,?:-" && s.lowercase() !in setOf("null", "~", "true", "false", "yes", "no")
        if (plainOk) return s
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
    }

    private class Reader(val lines: List<Line>, val warnings: MutableList<String>) {
        var i = 0

        fun blank(l: Line) = l.text.isBlank() || l.text.trimStart().startsWith("#")
        fun skipBlank() { while (i < lines.size && blank(lines[i])) i++ }
        fun peek(): Line? { skipBlank(); return lines.getOrNull(i) }

        fun mapping(indent: Int): MutableMap<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            while (true) {
                val l = peek() ?: break
                if (l.indent < indent) break
                if (l.indent > indent) throw SkillFormatException("line ${l.no}: unexpected indentation")
                val content = l.text.substring(l.indent)
                if (content.startsWith("- ") || content == "-") throw SkillFormatException("line ${l.no}: a list item where a field was expected")
                val colon = keyColon(content) ?: throw SkillFormatException("line ${l.no}: expected \"key: value\"")
                val key = unquoteKey(content.substring(0, colon).trim(), l.no)
                val rest = content.substring(colon + 1)
                i++
                if (out.containsKey(key)) warnings += "field \"$key\" appears twice; the last one counts"
                out[key] = value(rest, indent, l.no)
            }
            return out
        }

        /** Index of the colon that ends the key (followed by a space or the line end), outside quotes. */
        fun keyColon(s: String): Int? {
            var q: Char? = null
            var k = 0
            while (k < s.length) {
                val c = s[k]
                if (q != null) { if (c == q) q = null }
                else if ((c == '"' || c == '\'') && k == 0) q = c
                else if (c == ':' && (k + 1 == s.length || s[k + 1] == ' ')) return k
                else if (c == '#' && k > 0 && s[k - 1] == ' ') return null
                k++
            }
            return null
        }

        fun unquoteKey(k: String, no: Int): String = when {
            k.length >= 2 && k.startsWith('"') && k.endsWith('"') -> doubleQuoted(k.substring(1, k.length - 1), no)
            k.length >= 2 && k.startsWith('\'') && k.endsWith('\'') -> k.substring(1, k.length - 1).replace("''", "'")
            k.isEmpty() -> throw SkillFormatException("line $no: empty key")
            else -> k
        }

        /** The value after "key:" on a line at [indent]. */
        fun value(rest: String, indent: Int, no: Int): Any? {
            val v = rest.trim()
            if (v.isEmpty() || v.startsWith("#")) {
                val next = peek() ?: return null
                if (next.indent <= indent) {
                    // "key:" followed by "- item" at the same indent is still that key's list.
                    val c = next.text.substring(next.indent)
                    return if (next.indent == indent && (c.startsWith("- ") || c == "-")) sequence(indent) else null
                }
                val c = next.text.substring(next.indent)
                return if (c.startsWith("- ") || c == "-") sequence(next.indent) else mapping(next.indent)
            }
            return when (v[0]) {
                '|', '>' -> block(v, indent)
                '"', '\'' -> quoted(v, indent, no)
                '[', '{' -> flow(v, indent, no)
                '&', '*', '!' -> throw SkillFormatException("line $no: anchors, aliases and tags are not supported")
                else -> plain(v, indent)
            }
        }

        fun sequence(indent: Int): List<Any?> {
            val out = mutableListOf<Any?>()
            while (true) {
                val l = peek() ?: break
                if (l.indent != indent) { if (l.indent > indent) throw SkillFormatException("line ${l.no}: unexpected indentation"); break }
                val c = l.text.substring(l.indent)
                if (!(c.startsWith("- ") || c == "-")) break
                i++
                val item = c.removePrefix("-").trim()
                out += when {
                    item.isEmpty() -> null
                    keyColon(item) != null && item[0] !in "\"'[{" -> {
                        // "- key: value" item: a small mapping; kept as text "key: value".
                        warnings += "line ${l.no}: a mapping inside a list is kept as text"
                        item
                    }
                    else -> value(item, indent, l.no)
                }
            }
            return out
        }

        fun stripComment(s: String): String {
            val k = s.indexOf(" #")
            return (if (k >= 0) s.substring(0, k) else s).trimEnd()
        }

        fun plain(first: String, indent: Int): String? {
            val parts = mutableListOf(stripComment(first))
            while (true) {
                val l = lines.getOrNull(i) ?: break
                if (l.text.isBlank()) {
                    // A blank line inside a continued plain scalar is a line break; stop if nothing more-indented follows.
                    val save = i
                    skipBlank()
                    val n = lines.getOrNull(i)
                    if (n == null || n.indent <= indent || n.text.trimStart().startsWith("#")) { i = save; break }
                    parts += "\n"
                    continue
                }
                if (l.indent <= indent || l.text.trimStart().startsWith("#")) break
                parts += stripComment(l.text.trim())
                i++
            }
            val s = parts.joinToString(" ").replace(" \n ", "\n").trim()
            return if (s == "~" || s == "null") null else s
        }

        fun quoted(first: String, indent: Int, no: Int): String {
            val q = first[0]
            val sb = StringBuilder(first.substring(1))
            fun closeAt(s: CharSequence): Int {
                var k = 0
                while (k < s.length) {
                    val c = s[k]
                    if (q == '"' && c == '\\') { k += 2; continue }
                    if (c == q) {
                        if (q == '\'' && k + 1 < s.length && s[k + 1] == '\'') { k += 2; continue }
                        return k
                    }
                    k++
                }
                return -1
            }
            var end = closeAt(sb)
            while (end < 0) {
                val l = lines.getOrNull(i) ?: throw SkillFormatException("line $no: the quoted value is not closed")
                i++
                if (l.text.isBlank()) sb.append("\n") else {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
                    sb.append(l.text.trim())
                }
                end = closeAt(sb)
            }
            val after = sb.substring(end + 1).trim()
            if (after.isNotEmpty() && !after.startsWith("#")) throw SkillFormatException("line $no: text after the closing quote")
            val inner = sb.substring(0, end)
            return if (q == '"') doubleQuoted(inner, no) else inner.replace("''", "'")
        }

        fun doubleQuoted(s: String, no: Int): String {
            val out = StringBuilder()
            var k = 0
            while (k < s.length) {
                val c = s[k]
                if (c != '\\') { out.append(c); k++; continue }
                val n = s.getOrNull(k + 1) ?: throw SkillFormatException("line $no: a backslash ends the quoted value")
                k += 2
                when (n) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); '0' -> out.append('\u0000')
                    '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/'); ' ' -> out.append(' ')
                    'u', 'x', 'U' -> {
                        val len = when (n) { 'x' -> 2; 'u' -> 4; else -> 8 }
                        val hex = s.substring(k, minOf(s.length, k + len))
                        val cp = hex.takeIf { it.length == len }?.toIntOrNull(16) ?: throw SkillFormatException("line $no: bad \\$n escape")
                        out.appendCodePoint(cp); k += len
                    }
                    else -> { out.append('\\').append(n) }
                }
            }
            return out.toString()
        }

        fun block(header: String, indent: Int): String {
            val h = stripComment(header)
            val folded = h[0] == '>'
            val keep = '+' in h
            val strip = '-' in h
            val explicit = h.drop(1).firstOrNull { it.isDigit() }?.digitToInt()
            val collected = mutableListOf<String>()
            var blockIndent = explicit?.let { indent + it }
            while (i < lines.size) {
                val l = lines[i]
                if (l.text.isBlank()) { collected += ""; i++; continue }
                if (l.indent <= indent) break
                if (blockIndent == null) blockIndent = l.indent
                if (l.indent < blockIndent) break
                collected += l.text.substring(blockIndent)
                i++
            }
            // Trailing blank lines belong to chomping, not to the content.
            var trailing = 0
            while (collected.isNotEmpty() && collected.last().isEmpty()) { collected.removeAt(collected.size - 1); trailing++ }
            val text = if (!folded) collected.joinToString("\n") else buildString {
                var prevBlank = true
                collected.forEach { line ->
                    when {
                        line.isEmpty() -> { append('\n'); prevBlank = true }
                        line.startsWith(" ") -> { if (!prevBlank) append('\n'); append(line); append('\n'); prevBlank = true }
                        else -> { if (!prevBlank) append(' '); append(line); prevBlank = false }
                    }
                }
            }.trimEnd('\n')
            return when {
                strip -> text
                keep -> text + "\n".repeat(trailing + 1)
                else -> if (text.isEmpty()) "" else text + "\n"
            }
        }

        fun flow(first: String, indent: Int, no: Int): Any {
            val open = first[0]
            val close = if (open == '[') ']' else '}'
            val sb = StringBuilder(first)
            fun balanced(s: CharSequence): Int {
                var depth = 0; var q: Char? = null
                s.forEachIndexed { k, c ->
                    if (q != null) { if (c == q) q = null; return@forEachIndexed }
                    when (c) {
                        '"', '\'' -> q = c
                        '[', '{' -> depth++
                        ']', '}' -> { depth--; if (depth == 0) return k }
                    }
                }
                return -1
            }
            var end = balanced(sb)
            while (end < 0) {
                val l = lines.getOrNull(i) ?: throw SkillFormatException("line $no: \"$open\" is not closed")
                if (l.indent <= indent && l.text.isNotBlank()) throw SkillFormatException("line $no: \"$open\" is not closed")
                i++
                sb.append(' ').append(l.text.trim())
                end = balanced(sb)
            }
            if (sb[end] != close) throw SkillFormatException("line $no: mismatched brackets")
            val after = sb.substring(end + 1).trim()
            if (after.isNotEmpty() && !after.startsWith("#")) throw SkillFormatException("line $no: text after \"$close\"")
            val items = splitFlow(sb.substring(1, end)).filter { it.isNotBlank() }
            return if (open == '[') items.map { scalarItem(it.trim(), no) } else {
                val m = LinkedHashMap<String, Any?>()
                items.forEach { item ->
                    val c = keyColon(item.trim()) ?: throw SkillFormatException("line $no: expected \"key: value\" inside {}")
                    val t = item.trim()
                    m[unquoteKey(t.substring(0, c).trim(), no)] = scalarItem(t.substring(c + 1).trim(), no)
                }
                m
            }
        }

        fun scalarItem(s: String, no: Int): Any? = when {
            s.isEmpty() -> null
            s.startsWith('"') && s.endsWith('"') && s.length >= 2 -> doubleQuoted(s.substring(1, s.length - 1), no)
            s.startsWith('\'') && s.endsWith('\'') && s.length >= 2 -> s.substring(1, s.length - 1).replace("''", "'")
            s.startsWith('[') || s.startsWith('{') -> flow(s, Int.MAX_VALUE, no)
            s == "~" || s == "null" -> null
            else -> s
        }

        fun splitFlow(s: String): List<String> {
            val out = mutableListOf<String>()
            var depth = 0; var q: Char? = null; val cur = StringBuilder()
            for (c in s) {
                if (q != null) { cur.append(c); if (c == q) q = null; continue }
                when (c) {
                    '"', '\'' -> { q = c; cur.append(c) }
                    '[', '{' -> { depth++; cur.append(c) }
                    ']', '}' -> { depth--; cur.append(c) }
                    ',' -> if (depth == 0) { out += cur.toString(); cur.clear() } else cur.append(c)
                    else -> cur.append(c)
                }
            }
            out += cur.toString()
            return out
        }
    }
}
