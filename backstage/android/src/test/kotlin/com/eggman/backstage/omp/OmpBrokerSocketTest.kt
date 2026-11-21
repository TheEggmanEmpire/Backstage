package com.eggman.backstage.omp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class OmpBrokerSocketTest {
    @Test fun globalInstructionsUseBothTokensAndStandardBrowserChecks() {
        val port = ServerSocket(0).use { it.localPort }
        val settings = Settings(MemoryStore())
        val calls = AtomicInteger()
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { calls.incrementAndGet(); OmpConfig.from(settings) },
            { error("No key read") }, OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets))
        fun request(token: String? = "harness", extra: String = "", host: String = "127.0.0.1:$port"): String = Socket("127.0.0.1", port).use {
            it.soTimeout = 5_000
            val auth = token?.let { t -> "Authorization: Bearer $t\r\n" }.orEmpty()
            it.getOutputStream().write("GET /omp/v1/config/instructions.txt HTTP/1.1\r\nHost: $host\r\n${auth}${extra}\r\n".toByteArray())
            it.getInputStream().bufferedReader().readText()
        }
        broker.start()
        try {
            assertTrue(request(null).startsWith("HTTP/1.1 401"))
            assertTrue(request("wrong").startsWith("HTTP/1.1 401"))
            assertTrue(request(extra = "Origin: null\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(host = "evil.test:$port").startsWith("HTTP/1.1 403"))
            assertEquals(0, calls.get())
            for (token in listOf("harness", "termux")) {
                val r = request(token)
                assertTrue(r.startsWith("HTTP/1.1 200"))
                assertTrue(r.contains("Content-Type: text/plain"))
                assertEquals(settings.effectiveGlobalInstructions(), r.substringAfter("\r\n\r\n"))
            }
            settings.globalInstructions = "Changed live."
            assertEquals("Changed live.", request().substringAfter("\r\n\r\n"))
        } finally { broker.stop() }
    }
    @Test fun mcpConfigAndCredentialsUseExistingAuthAndReadLiveSettings() {
        val port = ServerSocket(0).use { it.localPort }
        val settings = Settings(MemoryStore())
        val remote = RemoteMcpServer("A_B", "Remote", "https://remote.test/mcp", "synthetic-mcp-secret")
        settings.remoteMcpServers = listOf(remote)
        val credentialPath = "/omp/v1/key/${OmpConfig.mcpSlug(remote)}"
        val configs = AtomicInteger()
        val keys = AtomicInteger()
        val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" },
            { configs.incrementAndGet(); OmpConfig.from(settings) },
            { id -> keys.incrementAndGet(); OmpHarness.mcpKey(id, settings.remoteMcpServers) }, payload)
        fun request(path: String, token: String? = "harness", host: String = "127.0.0.1:$port", extra: String = ""): String = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val bearer = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n${bearer}${extra}Connection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        broker.start()
        try {
            val path = "/omp/v1/config/mcp.json"
            for (endpoint in listOf(path, credentialPath)) {
                assertTrue(request(endpoint, null).startsWith("HTTP/1.1 401"))
                assertTrue(request(endpoint, "wrong").startsWith("HTTP/1.1 401"))
                assertTrue(request(endpoint, extra = "Origin: null\r\n").startsWith("HTTP/1.1 403"))
                assertTrue(request(endpoint, host = "evil.test:$port").startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, configs.get())
            assertEquals(0, keys.get())
            for (token in listOf("harness", "termux")) {
                val config = request(path, token)
                assertTrue(config.startsWith("HTTP/1.1 200"))
                assertTrue(config.contains("Content-Type: application/json"))
                assertTrue(config.contains("Cache-Control: no-store"))
                assertEquals(OmpConfig.from(settings).mcp, config.substringAfter("\r\n\r\n"))
                assertFalse(config.contains(remote.token))
                val credential = request(credentialPath, token)
                assertTrue(credential.startsWith("HTTP/1.1 200"))
                assertEquals("Bearer ${remote.token}", credential.substringAfter("\r\n\r\n"))
                assertTrue(credential.contains("Cache-Control: no-store"))
            }
            assertTrue(request("/omp/v1/key/mcp-unknown").startsWith("HTTP/1.1 404"))
            settings.remoteMcpServers = listOf(remote.copy(token = "rotated-mcp-secret"))
            assertEquals("Bearer rotated-mcp-secret", request(credentialPath).substringAfter("\r\n\r\n"))
            assertFalse(request(path).contains("rotated-mcp-secret"))
            settings.remoteMcpServers = emptyList()
            assertEquals("{\"mcpServers\":{}}", request(path).substringAfter("\r\n\r\n"))
            assertTrue(request(credentialPath).startsWith("HTTP/1.1 404"))
        } finally { broker.stop() }
    }
    @Test fun claimEndpointAcceptsOnlyAnUnusedUnexpiredClaimAndKeepsBrowserChecks() {
        val port = ServerSocket(0).use { it.localPort }
        var now = 0L
        val claims = OmpClaims { now }
        val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("Config must not be resolved") },
            { error("Keys must not be resolved") }, payload, claims)
        fun request(code: String? = null, host: String = "127.0.0.1:$port", extra: String = ""): String = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val claim = code?.let { "X-Backstage-Claim: $it\r\n" }.orEmpty()
            socket.getOutputStream().write("GET /omp/v1/claim HTTP/1.1\r\nHost: $host\r\n${claim}${extra}Connection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        broker.start()
        try {
            val code = claims.issue("port=$port\ntoken=harness\n")
            assertTrue(request().startsWith("HTTP/1.1 401"))
            assertTrue(request("wrong").startsWith("HTTP/1.1 401"))
            for (token in listOf("harness", "termux"))
                assertTrue(request(extra = "Authorization: Bearer $token\r\n").startsWith("HTTP/1.1 401"))
            assertTrue(request(code, extra = "Origin: null\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(code, host = "evil.test:$port").startsWith("HTTP/1.1 403"))
            val good = request(code)
            assertTrue(good.startsWith("HTTP/1.1 200"))
            assertTrue(good.endsWith("port=$port\ntoken=harness\n"))
            assertTrue(good.contains("Cache-Control: no-store"))
            assertTrue(request(code).startsWith("HTTP/1.1 401"))
            val expired = claims.issue("expired-secret")
            now = 30_000
            assertTrue(request(expired).startsWith("HTTP/1.1 401"))
        } finally { broker.stop() }
    }
    @Test fun actualListenerRefusesUnauthenticatedRequestsBeforeReadingKeys() {
        val port = ServerSocket(0).use { it.localPort }
        val calls = AtomicInteger()
        var harness = "harness"
        val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
        val broker = OmpKeyBroker(port, { harness }, { "termux" }, { OmpConfig.Files("providers: {}", "modelRoles: {}") },
            { calls.incrementAndGet(); "private-test-key" }, payload)
        fun request(path: String, token: String? = "harness", extra: String = ""): String = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val bearer = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n${bearer}${extra}Connection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
        broker.start()
        try {
            assertTrue(request("/omp/v1/key/openai", null).startsWith("HTTP/1.1 401"))
            assertTrue(request("/omp/v1/key/openai", "wrong").startsWith("HTTP/1.1 401"))
            assertTrue(request("/omp/v1/key/openai", extra = "Origin: null\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request("/omp/v1/payload/manifest.json").startsWith("HTTP/1.1 403"))
            assertEquals(0, calls.get())
            val good = request("/omp/v1/key/openai")
            assertTrue(good.startsWith("HTTP/1.1 200"))
            assertTrue(good.endsWith("private-test-key"))
            assertTrue(good.contains("Cache-Control: no-store"))
            harness = "rotated"
            assertTrue(request("/omp/v1/key/openai").startsWith("HTTP/1.1 401"))
            assertTrue(request("/omp/v1/key/openai", "rotated").startsWith("HTTP/1.1 200"))
            assertEquals(2, calls.get())
        } finally { broker.stop() }
        assertFalse(broker.running)
    }
}
