package com.eggman.backstage.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/** Checks optional wording fingerprints and verifies that fingerprint data stays out of distributed sources. */
class PublicationWordingTest {

    private val fingerprintFile: File? by lazy {
        (System.getenv("BACKSTAGE_WORDING_FINGERPRINT") ?: System.getProperty("backstage.wording.fingerprint"))
            ?.takeIf { it.isNotBlank() }?.let(::File)
    }

    private val upstream: LongArray by lazy {
        val file = fingerprintFile ?: error("no fingerprint configured")
        DataInputStream(file.inputStream().buffered()).use { input ->
            val out = ArrayList<Long>((file.length() / 4 + 1).toInt())
            while (true) {
                val v = try { input.readInt() } catch (_: java.io.EOFException) { break }
                out += v.toLong() and 0xFFFF_FFFFL
            }
            out.toLongArray()
        }
    }

    private fun assumeFingerprint() {
        val file = fingerprintFile
        assumeTrue("BACKSTAGE_WORDING_FINGERPRINT not set: no optional reference fingerprint configured",
            file != null)
        assertTrue("BACKSTAGE_WORDING_FINGERPRINT=$file does not exist", file!!.isFile)
        val repo = locateRepo().canonicalFile
        assertTrue("the fingerprint must live outside the repository ($repo), not at $file",
            !file.canonicalPath.startsWith(repo.path + File.separator))
    }

    @Test fun noProseLiteralSharesSixWordsWithUpstream() {
        assumeFingerprint()
        val repo = locateRepo()
        val hits = ArrayList<String>()
        val kotlinRoots = listOf("backstage/android/src/main/kotlin", "backstage/android/src/play/kotlin",
            "backstage/companion/src/main/kotlin", "backstage/companion-protocol/kotlin", "backstage/dextop-glue")
        kotlinRoots.map { repo.resolve(it) }.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                for (lit in Literals.kotlin(f.readText())) {
                    Literals.matches(lit.value, upstream).forEach { hits += "${f.relativeTo(repo)}:${lit.line}: \"$it\"" }
                }
            }
        }
        val resRoots = listOf("backstage/android/src/main/res", "backstage/overlay-play/res", "backstage/companion/src/main/res")
        resRoots.map { repo.resolve(it) }.forEach { res ->
            res.listFiles { d -> d.isDirectory && d.name.startsWith("values") }.orEmpty().forEach { dir ->
                val f = File(dir, "strings.xml")
                if (f.isFile) for (lit in Literals.xmlStrings(f.readText())) {
                    Literals.matches(lit.value, upstream).forEach { hits += "${f.relativeTo(repo)}:${lit.line}: \"$it\"" }
                }
            }
        }
        assertTrue("Literals share wording with upstream; rewrite them:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun fingerprintSetIsSortedAndSubstantial() {
        assumeFingerprint()
        assertTrue(upstream.size > 10_000)
        for (i in 1 until upstream.size) assertTrue(upstream[i - 1] < upstream[i])
    }

    /** The fingerprint (or anything like it) must never ship: not as a test resource, not anywhere in our tree. */
    @Test fun repositoryShipsNoFingerprintData() {
        assertNull(javaClass.getResource("/wording/reference-6gram.bin"))
        val repo = locateRepo()
        val skip = setOf(".git", ".internal", "build", ".gradle", ".cache", ".kotlin", ".cxx", "upstream", "dist")
        val dataName = Regex("(?i).*(\\d+gram|ngram|fingerprint|shingle).*\\.(bin|dat|gz|zip|json)")
        val found = repo.walkTopDown().onEnter { it.name !in skip }.filter { f ->
            f.isFile && (f.parentFile?.name.equals("wording", ignoreCase = true) || dataName.matches(f.name))
        }.map { it.relativeTo(repo).path }.toList()
        assertTrue("wording fingerprint data in the repository: $found", found.isEmpty())
    }

    @Test fun detectorFindsRunsButNotAcrossTemplatesOrInIdentifiers() {
        val set = longArrayOf(Literals.fnv("alpha beta gamma delta epsilon zeta"))
        val src = """
            val a = "x Alpha beta, gamma " +
                "delta epsilon zeta y"
            val b = "alpha beta gamma ${'$'}{v} delta epsilon zeta"
            val c = "alpha.beta.gamma.delta.epsilon.zeta"
            // "alpha beta gamma delta epsilon zeta" in a comment
            val d = '"'
        """.trimIndent()
        val lits = Literals.kotlin(src)
        assertEquals(listOf("alpha beta gamma delta epsilon zeta"), Literals.matches(lits[0].value, set))
        assertEquals(1, lits[0].line)
        assertTrue(Literals.matches(lits[1].value, set).isEmpty())
        assertTrue(Literals.matches(lits[2].value, set).isEmpty())
        assertEquals(3, lits.size)
    }

    /** The repository root, from the library's test working directory (backstage/android) or the repository itself. */
    private fun locateRepo(): File {
        val candidates = listOf("../..", ".", "..", "../../..")
        return candidates.map { File(it).absoluteFile.normalize() }
            .firstOrNull { File(it, "backstage/android/src/main/kotlin").isDirectory }
            ?: error("cannot find the repository root from ${File(".").absolutePath}")
    }
}

/** Minimal Kotlin string-literal lexer for the wording scan. Templates become breaks, `"a" + "b"` is merged. */
internal object Literals {
    data class Lit(val line: Int, val value: String)

    private const val BREAK = "\u0000"
    private const val N = 6
    private val word = Regex("[a-z0-9']+")
    private val concat = Regex("\\s*\\+\\s*")

    fun kotlin(text: String): List<Lit> {
        data class Raw(val start: Int, val end: Int, val value: String)
        val raw = ArrayList<Raw>()
        var i = 0
        val n = text.length
        while (i < n) {
            when {
                text.startsWith("//", i) -> { val j = text.indexOf('\n', i); i = if (j < 0) n else j }
                text.startsWith("/*", i) -> { val j = text.indexOf("*/", i + 2); i = if (j < 0) n else j + 2 }
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != '\'') j += if (text[j] == '\\') 2 else 1
                    i = j + 1
                }
                text[i] == '"' -> {
                    val rawStr = text.startsWith("\"\"\"", i)
                    val q = if (rawStr) "\"\"\"" else "\""
                    var j = i + q.length
                    val sb = StringBuilder()
                    while (j < n) {
                        if (text.startsWith(q, j) && !(rawStr && text.startsWith("\"\"\"\"", j))) { j += q.length; break }
                        val ch = text[j]
                        if (!rawStr && ch == '\\' && j + 1 < n) {
                            val esc = text[j + 1]
                            when (esc) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'u' -> { j += 6; continue }
                                else -> sb.append(esc)
                            }
                            j += 2
                            continue
                        }
                        if (ch == '$' && j + 1 < n && text[j + 1] == '{') {
                            var depth = 1
                            var k = j + 2
                            while (k < n && depth > 0) {
                                if (text[k] == '{') depth++ else if (text[k] == '}') depth--
                                k++
                            }
                            sb.append(' ').append(BREAK).append(' ')
                            j = k
                            continue
                        }
                        if (ch == '$' && j + 1 < n && (text[j + 1].isLetter() || text[j + 1] == '_')) {
                            var k = j + 1
                            while (k < n && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                            sb.append(' ').append(BREAK).append(' ')
                            j = k
                            continue
                        }
                        sb.append(ch)
                        j++
                    }
                    raw += Raw(i, j, sb.toString())
                    i = j
                }
                else -> i++
            }
        }
        val merged = ArrayList<Raw>()
        for (r in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && concat.matches(text.substring(prev.end, r.start))) {
                merged[merged.size - 1] = Raw(prev.start, r.end, prev.value + r.value)
            } else {
                merged += r
            }
        }
        return merged.map { r -> Lit(text.substring(0, r.start).count { it == '\n' } + 1, r.value) }
    }

    fun xmlStrings(text: String): List<Lit> =
        Regex("<string[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .map { m -> Lit(text.substring(0, m.range.first).count { it == '\n' } + 1, m.groupValues[2]) }.toList()

    /** Prose only: identifiers, URLs, regexes, SQL and format strings are interoperability material. */
    private fun isProse(v: String): Boolean {
        if (' ' !in v || "://" in v) return false
        val t = v.trimStart()
        if (listOf("^", "CREATE ", "SELECT ", "INSERT ", "UPDATE ", "DELETE FROM").any { t.startsWith(it) }) return false
        val letters = v.count { it.isLetter() || it == ' ' || it == '\u0000' }
        return letters >= 0.6 * maxOf(1, v.length)
    }

    fun matches(value: String, set: LongArray): List<String> {
        if (!isProse(value)) return emptyList()
        val out = ArrayList<String>()
        for (segment in value.split(BREAK)) {
            val ws = word.findAll(segment.lowercase()).map { it.value }.toList()
            for (k in 0..ws.size - N) {
                val g = ws.subList(k, k + N).joinToString(" ")
                if (set.binarySearch(fnv(g)) >= 0) out += g
            }
        }
        return out
    }

    fun fnv(s: String): Long {
        var h = 0x811C9DC5L
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFF_FFFFL
        }
        return h
    }
}
