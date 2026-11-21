package com.eggman.backstage.voice

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoskModelInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(bytes: ByteArray, sha: String = sha(bytes), size: Long = bytes.size.toLong()) =
        VoskModel("vosk-model-small-xx-0.1", "Test", server.url("/m.zip").toString(), size, sha, 10_000)

    private val goodZip get() = zip(
        "vosk-model-small-xx-0.1/" to "",
        "vosk-model-small-xx-0.1/am/final.mdl" to "acoustic",
        "vosk-model-small-xx-0.1/conf/model.conf" to "--min-active=200",
        "vosk-model-small-xx-0.1/graph/words.txt" to "hey 1\n",
    )

    @Test fun installsVerifiedModelAndStripsTopFolder() = runTest {
        val bytes = goodZip
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val installer = VoskModelInstaller(tmp.root)
        val m = model(bytes)
        var lastProgress = 0L
        val dir = installer.install(m) { done, _ -> lastProgress = done }
        assertEquals(bytes.size.toLong(), lastProgress)
        assertEquals("acoustic", File(dir, "am/final.mdl").readText())
        assertTrue(installer.isInstalled(m))
        assertFalse(File(tmp.root, "${m.id}.zip.part").exists())
        // Second install is a no-op (no request made).
        installer.install(m)
        assertEquals(1, server.requestCount)
        installer.delete(m)
        assertFalse(installer.isInstalled(m))
    }

    @Test fun checksumMismatchLeavesNothingBehind() = runTest {
        val bytes = goodZip
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val installer = VoskModelInstaller(tmp.root)
        val m = model(bytes, sha = "0".repeat(64))
        try {
            installer.install(m); fail("expected failure")
        } catch (e: VoskModelInstaller.VerificationException) {
            assertTrue(e.message!!.contains("Checksum"))
        }
        assertFalse(installer.isInstalled(m))
        assertTrue(tmp.root.listFiles()!!.isEmpty())
    }

    @Test fun sizeMismatchIsRejectedBeforeDownloading() = runTest {
        val bytes = goodZip
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val m = model(bytes, size = bytes.size + 10L)
        try {
            VoskModelInstaller(tmp.root).install(m); fail("expected failure")
        } catch (e: VoskModelInstaller.VerificationException) {
            assertTrue(e.message!!.contains("size"))
        }
    }

    @Test fun zipSlipIsRejected() = runTest {
        val bytes = zip("am/final.mdl" to "x", "conf/a" to "y", "../../evil.txt" to "pwned")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            VoskModelInstaller(File(tmp.root, "models")).install(model(bytes)); fail("expected failure")
        } catch (e: VoskModelInstaller.VerificationException) {
            assertTrue(e.message!!.contains("Unsafe"))
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun archiveWithoutModelLayoutIsRejected() = runTest {
        val bytes = zip("readme.txt" to "hello")
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            VoskModelInstaller(tmp.root).install(model(bytes)); fail("expected failure")
        } catch (e: VoskModelInstaller.VerificationException) {
            assertTrue(e.message!!.contains("not a Vosk model"))
        }
    }

    @Test fun httpErrorPropagates() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            VoskModelInstaller(tmp.root).install(model(ByteArray(10))); fail("expected failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("404"))
        }
    }
}
