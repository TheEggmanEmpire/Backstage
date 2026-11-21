package com.eggman.backstage.privileged

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellTimeoutRegressionTest {
    private data class Result(val code: Int, val out: String, val err: String, val elapsedMs: Long)
    private fun run(command: List<String>, timeoutMs: Long = 200, cap: Int = 4096): Result {
        assumeTrue("Requires a POSIX JVM host", java.io.File("/bin/sh").canExecute())
        val dir = Files.createTempDirectory("backstage-shell-test-").toFile()
        val readers = Executors.newFixedThreadPool(2)
        val started = System.nanoTime()
        val process = ProcessBuilder("sh", "-c", BoundedShell.script(command, timeoutMs, cap, dir.path)).start()
        try {
            val out = readers.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
            val err = readers.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
            assertTrue("Wrapper leaked a process/pipe", process.waitFor(5, TimeUnit.SECONDS))
            return Result(process.exitValue(), out.get(2, TimeUnit.SECONDS), err.get(2, TimeUnit.SECONDS), (System.nanoTime() - started) / 1000000)
        } finally {
            process.destroyForcibly()
            readers.shutdownNow()
            dir.deleteRecursively()
        }
    }

    @Test fun sleepingProcessHonorsDeadline() {
        val r = run(listOf("sh", "-c", "sleep 10"))
        assertEquals(124, r.code)
        assertTrue(r.err, r.err.contains("timed out"))
        assertTrue("Elapsed ${r.elapsedMs} ms", r.elapsedMs < 4000)
    }

    @Test fun infiniteStdoutIsCappedAndTerminates() {
        val r = run(listOf("sh", "-c", "yes"), cap = 1024)
        assertEquals(124, r.code)
        assertTrue(r.out.contains("stdout truncated at 1024 bytes"))
        assertTrue(r.out.length < 1200)
    }

    @Test fun fullStderrWhileStdoutStaysOpenCannotDeadlock() {
        val r = run(listOf("sh", "-c", "yes error >&2"), cap = 1024)
        assertEquals(124, r.code)
        assertTrue(r.err.contains("stderr truncated at 1024 bytes"))
        assertTrue(r.err.length < 1300)
    }

    @Test fun timeoutKillsChildrenInsteadOfOnlyTheShell() {
        val marker = Files.createTempFile("backstage-child-", ".marker").toFile().apply { delete() }
        try {
            val r = run(listOf("sh", "-c", "(sleep 1; echo survived > '${marker.path}') & wait"), timeoutMs = 150)
            assertEquals(124, r.code)
            Thread.sleep(1200)
            assertFalse("Child survived process-group timeout", marker.exists())
        } finally { marker.delete() }
    }

    @Test fun argumentsAreQuotedAndExitCodeIsPreserved() {
        val payload = "x'; echo injected; #"
        val r = run(listOf("sh", "-c", "printf '%s' \"\$1\"; exit 7", "sh", payload), timeoutMs = 1000)
        assertEquals(7, r.code)
        assertEquals(payload, r.out)
    }
}
