package com.eggman.backstage.omp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Bounded, process-only handoffs. Only a digest of the single-use code is retained. */
class OmpClaims(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Claim(val digest: ByteArray, val contents: String, val expires: Long)
    private val pending = mutableListOf<Claim>()
    private val random = SecureRandom()
    private fun digest(code: String) = MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8))

    @Synchronized fun issue(contents: String): String {
        val now = clock()
        pending.removeAll { now >= it.expires }
        if (pending.size >= 8) pending.removeAt(0)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        pending += Claim(digest(code), contents, now + 30_000)
        return code
    }
    @Synchronized fun consume(code: String?): String? {
        val now = clock()
        pending.removeAll { now >= it.expires }
        val candidate = digest(code.orEmpty())
        var match: Claim? = null
        // Compare every outstanding fixed-size digest, without a prefix or early-match shortcut.
        pending.forEach { if (MessageDigest.isEqual(candidate, it.digest)) match = it }
        return match?.let { pending.remove(it); it.contents }
    }
    @Synchronized fun revoke(code: String) {
        val candidate = digest(code)
        pending.removeAll { MessageDigest.isEqual(candidate, it.digest) }
    }
    @Synchronized fun clear() = pending.clear()
}
