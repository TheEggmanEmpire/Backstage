package com.eggman.backstage.omp

import com.eggman.backstage.mcp.HttpRequest
import org.junit.Assert.*
import org.junit.Test

class OmpBrokerTest {
    @Test fun mcpConfigAndKeysKeepTokenHostOriginAndRateLimits() {
        for (path in listOf("/omp/v1/config/mcp.json", "/omp/v1/key/mcp-remote")) {
            assertEquals(401, policy().problem(request(null, path))?.status)
            assertEquals(401, policy().problem(request("wrong", path))?.status)
            assertEquals(403, policy().problem(request(path = path, extra = mapOf("origin" to "null")))?.status)
            assertEquals(403, policy().problem(request(path = path, extra = mapOf("host" to "evil.test:47821")))?.status)
            val p = policy()
            repeat(64) { assertNull(p.problem(request(path = path))) }
            assertEquals(429, p.problem(request(path = path))?.status)
            assertNull(p.problem(request("termux", path)))
        }
    }
    @Test fun shellRequiresTermuxPostAndKeepsHeaderAndRateLimits() {
        fun shell(token: String = "termux", method: String = "POST", extra: Map<String, String> = emptyMap()) =
            HttpRequest(method, "/omp/v1/shell", "HTTP/1.1", mapOf("host" to "127.0.0.1:47821", "authorization" to "Bearer $token") + extra, byteArrayOf())
        assertEquals(403, policy().problem(shell("harness"))?.status)
        assertNull(policy().problem(shell()))
        assertEquals(405, policy().problem(shell(method = "GET"))?.status)
        assertEquals(403, policy().problem(shell(extra = mapOf("origin" to "null")))?.status)
        assertEquals(403, policy().problem(shell(extra = mapOf("host" to "evil.test:47821")))?.status)
        val p = policy()
        repeat(64) { assertNull(p.problem(shell())) }
        assertEquals(429, p.problem(shell())?.status)
        assertNull(p.problem(request()))
    }
    private fun request(token: String? = "harness", path: String = "/omp/v1/key/openai", extra: Map<String,String> = emptyMap()) = HttpRequest("GET", path, "HTTP/1.1", mapOf("host" to "127.0.0.1:47821") + (token?.let { mapOf("authorization" to "Bearer $it") } ?: emptyMap()) + extra, byteArrayOf())
    private fun policy(clock: () -> Long = { 0L }) = OmpBrokerAuth(47821, { "harness" }, { "termux" }, clock)
    @Test fun tokensAndBrowserHeadersAreEnforced() {
        val p = policy()
        assertEquals(401, p.problem(request(null))?.status)
        assertEquals(401, p.problem(request("wrong"))?.status)
        assertEquals(403, p.problem(request(extra = mapOf("origin" to "null")))?.status)
        assertEquals(403, p.problem(request(extra = mapOf("host" to "evil.test:47821")))?.status)
        assertNull(p.problem(request()))
        assertNull(p.problem(request(extra = mapOf("host" to "localhost:47821"))))
    }
    @Test fun payloadAndSetupRequireEnabledPairingToken() {
        for (path in listOf("/omp/v1/payload/manifest.json", "/omp/v1/termux/setup")) {
            assertEquals(403, policy().problem(request(path = path))?.status)
            assertNull(policy().problem(request("termux", path)))
            assertEquals(401, OmpBrokerAuth(47821, { "harness" }, { "" }).problem(request("termux", path))?.status)
        }
    }
    @Test fun limitIsPerTokenAndExpires() {
        var time = 0L
        val p = policy { time }
        repeat(64) { assertNull(p.problem(request())) }
        assertEquals(429, p.problem(request())?.status)
        assertNull(p.problem(request("termux")))
        time = 10_000
        assertNull(p.problem(request()))
    }
    @Test fun rotationStartsANewTokenRateWindow() {
        var token = "harness"
        val p = OmpBrokerAuth(47821, { token }, { "termux" }, { 0L })
        repeat(64) { assertNull(p.problem(request())) }
        assertEquals(429, p.problem(request())?.status)
        token = "new-harness"
        assertEquals(401, p.problem(request())?.status)
        assertNull(p.problem(request("new-harness")))
    }
    @Test fun claimAttemptsHaveTheirOwnBoundedRateWindowAndBrowserRules() {
        var time = 0L
        val p = policy { time }
        val path = "/omp/v1/claim"
        assertEquals(403, p.problem(request(null, path, mapOf("origin" to "null")))?.status)
        assertEquals(403, p.problem(request(null, path, mapOf("host" to "evil.test:47821")))?.status)
        repeat(64) { assertNull(p.problem(request(null, path))) }
        assertEquals(429, p.problem(request("harness", path))?.status)
        assertNull(p.problem(request())) // Claim attempts do not spend the normal token budget.
        time = 10_000
        assertNull(p.problem(request(null, path)))
    }
    @Test fun jobIdsCannotEscapeJobDirectory() {
        listOf("abcd1234", "a".repeat(32)).forEach { assertEquals(it, OmpJobs.validateId(it)) }
        listOf("", "short", "a".repeat(33), "../abcd1234", "abcd123;id", "ABCD1234", "abcd1234\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { OmpJobs.validateId(it) }
        }
    }
}
