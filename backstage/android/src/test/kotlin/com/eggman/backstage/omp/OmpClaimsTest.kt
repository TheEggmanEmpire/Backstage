package com.eggman.backstage.omp

import com.eggman.backstage.privileged.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OmpClaimsTest {
    @Test fun claimIsSingleUseAndExpiresAfterThirtySeconds() {
        var now = 0L
        val claims = OmpClaims { now }
        val code = claims.issue("port=47821\ntoken=private-token\n")
        assertTrue(code.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNull(claims.consume(null))
        assertNull(claims.consume("wrong"))
        now = 29_999
        assertEquals("port=47821\ntoken=private-token\n", claims.consume(code))
        assertNull(claims.consume(code))
        val expired = claims.issue("secret")
        now += 30_000
        assertNull(claims.consume(expired))
    }
    @Test fun revocationAndRestartClearOutstandingClaims() {
        val claims = OmpClaims { 0L }
        val first = claims.issue("first")
        val second = claims.issue("second")
        claims.revoke(first)
        assertNull(claims.consume(first))
        assertEquals("second", claims.consume(second))
        val third = claims.issue("third")
        claims.clear()
        assertNull(claims.consume(third))
    }
    @Test fun concurrentConsumersGetTheContentsOnlyOnce() {
        val claims = OmpClaims { 0L }
        val code = claims.issue("one")
        val answers = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val threads = List(8) { Thread { answers += claims.consume(code) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, answers.count { it == "one" })
    }
    @Test fun privilegedArgvContainsOnlyTheClaimCodeEvenDuringRotation() = runBlocking {
        val claims = OmpClaims { 0L }
        val commands = mutableListOf<List<String>>()
        val codes = mutableListOf<String>()
        val installer = OmpBrokerInstaller(
            issue = { _, contents -> claims.issue(contents).also { codes += it } },
            revoke = claims::revoke,
            exec = { argv, _ -> commands += argv; ShellResult(0, "", "") },
        )
        for (token in listOf("long-lived-harness-token-one", "rotated-harness-token-two")) {
            val contents = "port=47821\ntoken=$token\n"
            installer.write("/data/local/tmp/backstage-omp", "/apk/lib", 47821, contents)
            val argv = commands.last()
            assertFalse(argv.joinToString(" ").contains(token))
            assertFalse(argv.joinToString(" ").contains(contents))
            assertTrue(argv.contains(codes.last()))
            assertNull("Unused claims must be revoked after exec returns", claims.consume(codes.last()))
        }
        assertNotEquals(codes[0], codes[1])
    }
    @Test fun failedExecRevokesTheClaimAndNeverIncludesTokenInItsError() = runBlocking {
        val claims = OmpClaims { 0L }
        var code = ""
        val installer = OmpBrokerInstaller(
            issue = { _, contents -> claims.issue(contents).also { code = it } },
            revoke = claims::revoke,
            exec = { argv, _ -> error("privileged failure: ${argv.joinToString(" ")}") },
        )
        val failure = runCatching { installer.write("/runtime", "/lib", 47821, "token=must-not-log\n") }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure!!.message.orEmpty().contains("must-not-log"))
        assertNull(claims.consume(code))
    }
}
