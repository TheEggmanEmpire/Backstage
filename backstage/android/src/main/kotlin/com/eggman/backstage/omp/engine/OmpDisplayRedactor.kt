package com.eggman.backstage.omp.engine

/** Display-only sanitization. Never changes tool inputs, provider requests, or the executed command. */
class OmpDisplayRedactor(secrets: Collection<String> = emptyList()) {
    private val exact = secrets.filter(String::isNotEmpty).distinct().sortedByDescending(String::length)

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var safe = text
        exact.forEach { safe = safe.replace(it, MASK) }
        safe = headers.replace(safe) { it.groupValues[1] + MASK }
        safe = bearer.replace(safe) { it.groupValues[1] + MASK }
        safe = assignments.replace(safe) { it.groupValues[1] + MASK }
        safe = basicAuth.replace(safe) { it.groupValues[1] + " " + MASK }
        safe = urlUserInfo.replace(safe) { it.groupValues[1] + MASK + "@" }
        safe = keyShapes.replace(safe, MASK)
        return keywordRuns.replace(safe) { it.groupValues[1] + MASK }
    }

    companion object {
        const val MASK = "•••"
        val DEFAULT = OmpDisplayRedactor()
        private val headers = Regex("""(?i)(\b(?:proxy-)?authorization\b["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\\r\n])*"|'[^'\r\n]*'|[^\r\n'";,}]+)""")
        private val bearer = Regex("""(?i)(\bBearer\s+)[^\s"'`,;}\]]+""")
        private val assignments = Regex("""(?i)(\b(?:[a-z0-9]+[_-])*(?:api[_-]?key|key|(?:access|refresh|auth|broker)?[_-]?token|password|passwd|secret|client[_-]?secret)\b["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'[^']*'|[^\s"'`,;&}\]]+)""")
        private val basicAuth = Regex("""(?i)(?<!\S)(-u|--user)(?:\s+|=)(?:"[^"]*"|'[^']*'|[^\s]+)""")
        private val urlUserInfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/\s@]+@""")
        private val keyShapes = Regex("""\b(?:sk-(?:ant-)?[A-Za-z0-9_-]{4,}|gh[pousr]_[A-Za-z0-9_]{4,}|github_pat_[A-Za-z0-9_]{4,}|AIza[A-Za-z0-9_-]{4,}|xox[bp]-[A-Za-z0-9_-]{4,})\b""")
        private val keywordRuns = Regex("""(?i)(\b(?:api[\s_-]?key|token|password|secret|credential|authorization)\b\s+(?:(?:is|was)\s+)?)[A-Za-z0-9_+/=-]{32,}""")
    }
}
