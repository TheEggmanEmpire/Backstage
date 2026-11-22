package com.eggman.backstage.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class ApkDownloadTest {
    @Test fun checksumAndApkRedirectsToUntrustedHostsAreRejected() {
        MockWebServer().use { server ->
            val assets = UpdateAssets(ReleaseAsset("update.apk", server.url("/apk").toString(), 5),
                ReleaseAsset("SHA256SUMS", server.url("/sums").toString(), 100))
            val dir = Files.createTempDirectory("updater-redirect").toFile()
            val file = dir.resolve("update.apk")
            try {
                for (apk in listOf(false, true)) {
                    if (apk) server.enqueue(MockResponse().setBody("${"00".repeat(32)}  update.apk\n"))
                    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/update"))
                    val error = assertThrows(java.io.IOException::class.java) {
                        ApkDownload().fetch(assets, file, "1", { Long.MAX_VALUE }) {}
                    }
                    assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Untrusted update redirect"))
                    assertFalse(file.exists())
                }
                assertEquals(3, server.requestCount)
            } finally { file.delete(); dir.delete() }
        }
    }

    @Test fun streamsExactFileAndChecksumAndReportsProgress() {
        MockWebServer().use { server ->
            val payload = ByteArray(180_000) { (it % 253).toByte() }
            val hash = MessageDigest.getInstance("SHA-256").digest(payload).hex()
            server.enqueue(MockResponse().setBody("$hash  update.apk\n"))
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val assets = UpdateAssets(ReleaseAsset("update.apk", server.url("/apk").toString(), payload.size.toLong()),
                ReleaseAsset("SHA256SUMS", server.url("/sums").toString(), 100))
            val dir = Files.createTempDirectory("updater-test").toFile()
            val file = dir.resolve("update.apk")
            try {
                var count = 0L
                assertEquals(hash, ApkDownload().fetch(assets, file, "1.0.0", { Long.MAX_VALUE }) { count = it })
                assertArrayEquals(payload, file.readBytes())
                assertEquals(payload.size.toLong(), count)
                assertEquals("Backstage/1.0.0", server.takeRequest().getHeader("User-Agent"))
            } finally { file.delete(); dir.delete() }
        }
    }
    @Test fun wrongHashTruncationAndLowDiskNeverLeaveAnApk() {
        MockWebServer().use { server ->
            val assets = UpdateAssets(ReleaseAsset("update.apk", server.url("/apk").toString(), 5),
                ReleaseAsset("SHA256SUMS", server.url("/sums").toString(), 100))
            val dir = Files.createTempDirectory("updater-reject").toFile()
            val file = dir.resolve("update.apk")
            try {
                assertThrows(Exception::class.java) { ApkDownload().fetch(assets, file, "1", { 0 }) {} }
                assertEquals(0, server.requestCount)
                server.enqueue(MockResponse().setBody("${"00".repeat(32)}  update.apk\n"))
                server.enqueue(MockResponse().setBody("wrong"))
                assertThrows(Exception::class.java) { ApkDownload().fetch(assets, file, "1", { Long.MAX_VALUE }) {} }
                assertFalse(file.exists())
                server.enqueue(MockResponse().setBody("${"00".repeat(32)}  update.apk\n"))
                server.enqueue(MockResponse().setBody("tiny"))
                assertThrows(Exception::class.java) { ApkDownload().fetch(assets, file, "1", { Long.MAX_VALUE }) {} }
                assertFalse(file.exists())
            } finally { file.delete(); dir.delete() }
        }
    }
    @Test fun cancellationCleansPartialDownload() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("${"00".repeat(32)}  update.apk\n"))
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100_000))))
            val assets = UpdateAssets(ReleaseAsset("update.apk", server.url("/apk").toString(), 100_000),
                ReleaseAsset("SHA256SUMS", server.url("/sums").toString(), 100))
            val dir = Files.createTempDirectory("updater-cancel").toFile()
            val file = dir.resolve("update.apk")
            try {
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    ApkDownload().fetch(assets, file, "1", { Long.MAX_VALUE }) { throw java.util.concurrent.CancellationException() }
                }
                assertFalse(file.exists())
            } finally { file.delete(); dir.delete() }
        }
    }
}
