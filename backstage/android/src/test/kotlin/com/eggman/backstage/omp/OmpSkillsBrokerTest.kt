package com.eggman.backstage.omp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eggman.backstage.mcp.HttpRequest
import com.eggman.backstage.skills.SkillStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
class OmpSkillsBrokerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val path = "/omp/v1/config/skills.zip"

    @Test(timeout = 10000) fun stalledSkillsReaderIsClosedByTheResponseDeadline() {
        val port = ServerSocket(0).use { it.localPort }
        val requested = java.util.concurrent.CountDownLatch(1)
        val file = tmp.newFile().also { f -> RandomAccessFile(f, "rw").use { it.setLength(8 * 1024 * 1024) } }
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config") }, { null },
            OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets),
            skills = { requested.countDown(); OmpSkills.Archive(file) }, writeDeadlineMs = 200L)
        broker.start()
        try {
            Socket().use { socket ->
                socket.receiveBufferSize = 1024
                socket.soTimeout = 3000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port))
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer harness\r\n\r\n".toByteArray())
                assertTrue(requested.await(5, java.util.concurrent.TimeUnit.SECONDS))
                Thread.sleep(400) // Keep the receive window closed beyond the short test deadline.
                assertTrue(socket.getInputStream().readBytes().size < file.length())
            }
        } finally { broker.stop() }
    }

    private fun request(port: Int, token: String? = "harness", host: String = "127.0.0.1:$port", extra: String = ""): Pair<String, ByteArray> =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n${auth}${extra}Connection: close\r\n\r\n".toByteArray())
            val bytes = socket.getInputStream().readBytes()
            val header = bytes.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
            header to bytes.copyOfRange(header.length + 4, bytes.size)
        }

    private fun names(zip: ByteArray) = mutableListOf<String>().apply {
        ZipInputStream(zip.inputStream()).use { input -> while (true) add((input.nextEntry ?: break).name) }
    }

    @Test fun authenticatedEndpointStreamsCurrentEnabledSkillsWithBrowserChecks() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("live", "Use live", "steps")
        store.setEnabled("live", true)
        val calls = AtomicInteger()
        val cache = OmpSkills.SnapshotCache(store)
        val port = ServerSocket(0).use { it.localPort }
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config read") }, { error("No credential read") },
            OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets), skills = { calls.incrementAndGet(); cache.open() })
        broker.start()
        try {
            assertTrue(request(port, null).first.startsWith("HTTP/1.1 401"))
            assertTrue(request(port, "wrong").first.startsWith("HTTP/1.1 401"))
            assertTrue(request(port, extra = "Origin: null\r\n").first.startsWith("HTTP/1.1 403"))
            assertTrue(request(port, host = "evil.test:$port").first.startsWith("HTTP/1.1 403"))
            assertEquals(0, calls.get())
            for (token in listOf("harness", "termux")) {
                val (header, zip) = request(port, token)
                assertTrue(header.startsWith("HTTP/1.1 200"))
                assertTrue(header.contains("Content-Type: application/zip"))
                assertTrue(header.contains("Cache-Control: no-store"))
                assertTrue(header.contains("Content-Length: ${zip.size}"))
                assertEquals(listOf("skills/live/.backstage", "skills/live/SKILL.md"), names(zip))
            }
            store.setEnabled("live", false)
            assertTrue(names(request(port).second).isEmpty())
        } finally { broker.stop() }
    }

    @Test fun absentStoreServesAnEmptyZipAndFailedOrOversizedSnapshotsAreRefused() {
        for (provider in listOf<(() -> OmpSkills.Archive)?>(null, { error("Unreadable skill") }, {
            val file = tmp.newFile()
            RandomAccessFile(file, "rw").use { it.setLength(OmpSkills.MAX_ZIP_BYTES + 1) }
            OmpSkills.Archive(file)
        })) {
            val port = ServerSocket(0).use { it.localPort }
            val payload = OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets)
            val broker = if (provider == null) OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config read") }, { null }, payload)
                else OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config read") }, { null }, payload, skills = provider)
            broker.start()
            try {
                val (header, body) = request(port)
                if (provider == null) {
                    assertTrue(header.startsWith("HTTP/1.1 200"))
                    assertTrue(body.size >= 22)
                    assertTrue(names(body).isEmpty())
                } else assertTrue(header, header.startsWith("HTTP/1.1 503"))
            } finally { broker.stop() }
        }
    }

    @Test fun archiveAboveThirtyMbStreamsToDisk() {
        val store = SkillStore(tmp.newFolder())
        store.saveSimple("large", "Large", "Steps")
        store.setEnabled("large", true)
        RandomAccessFile(java.io.File(store.root, "large/data.bin"), "rw").use { it.setLength(31_000_000) }
        val cache = OmpSkills.SnapshotCache(store)
        val port = ServerSocket(0).use { it.localPort }
        val broker = OmpKeyBroker(port, { "harness" }, { "termux" }, { error("No config") }, { null },
            OmpPayload(ApplicationProvider.getApplicationContext<Context>().assets), skills = cache::open)
        broker.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer harness\r\n\r\n".toByteArray())
                val input = socket.getInputStream().buffered()
                fun line(): String = buildString { while (true) { val c = input.read(); if (c < 0 || c == 10) break; if (c != 13) append(c.toChar()) } }
                assertTrue(line().contains("200 OK"))
                var length = 0L
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:")) length = header.substringAfter(':').trim().toLong()
                }
                assertTrue(length > 30_000_000)
                val received = tmp.newFile()
                received.outputStream().use { assertEquals(length, input.copyTo(it, 32 * 1024)) }
                java.util.zip.ZipFile(received).use { assertEquals(31_000_000L, it.getEntry("skills/large/data.bin").size) }
            }
        } finally { broker.stop() }
    }

    @Test fun skillsEndpointUsesTheExistingPerTokenRateLimit() {
        val policy = OmpBrokerAuth(47821, { "harness" }, { "termux" }, { 0L })
        fun req(token: String) = HttpRequest("GET", path, "HTTP/1.1",
            mapOf("host" to "127.0.0.1:47821", "authorization" to "Bearer $token"), byteArrayOf())
        repeat(64) { assertNull(policy.problem(req("harness"))) }
        assertEquals(429, policy.problem(req("harness"))?.status)
        assertNull(policy.problem(req("termux")))
    }
}
