package com.eggman.backstage.omp

import com.eggman.backstage.privileged.ShellResult
import com.eggman.backstage.tools.ToolArgumentException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OmpShellRelayTest {
    private var enabled = true
    private var auto = false
    private var available = true
    private var approved = true
    private val questions = mutableListOf<String>()
    private val commands = mutableListOf<Pair<String, Long>>()
    private val relay = OmpShellRelay({ enabled }, { auto }, { available }, { question ->
        questions += question
        approved
    }, exec = { command, timeout ->
        commands += command to timeout
        ShellResult(7, "output\n", "error\n")
    })

    @Test fun disabledAndUnavailableRefuseWithoutAskingOrExecuting() = runTest {
        enabled = false; auto = true
        assertTrue(runCatching { relay.run("id", 60) }.exceptionOrNull()!!.message!!.contains("disabled"))
        enabled = true; available = false
        assertTrue(runCatching { relay.run("id", 60) }.exceptionOrNull()!!.message!!.contains("privileged"))
        assertTrue(questions.isEmpty()); assertTrue(commands.isEmpty())
    }
    @Test fun asksForExactNormalizedCommandAndDenialNeverExecutes() = runTest {
        approved = false
        assertTrue(runCatching { relay.run("adb shell pm list packages", 60) }.exceptionOrNull() is OmpShellRefused)
        assertTrue(questions.single().contains("pm list packages"))
        assertTrue(commands.isEmpty())
        approved = true
        val result = relay.run("adb shell pm list packages", 60)
        assertEquals(listOf("pm list packages" to 60_000L), commands)
        assertEquals(7, result.exitCode)
        assertEquals("output\n", result.stdout)
        assertEquals("error\n", result.stderr)
    }
    @Test fun autoRunsWithoutAskingAndClampsTimeoutWithoutOverflow() = runTest {
        auto = true
        relay.run("adb install /sdcard/app.apk", Long.MAX_VALUE)
        relay.run("id", Long.MIN_VALUE)
        assertEquals(listOf("pm install /sdcard/app.apk" to 300_000L, "id" to 5_000L), commands)
        assertTrue(questions.isEmpty())
    }
    @Test fun accessRevokedWhileApprovalIsPendingCannotExecute() = runTest {
        val r = OmpShellRelay({ enabled }, { false }, { available }, { enabled = false; true },
            exec = { _, _ -> error("must not execute") })
        assertTrue(runCatching { r.run("id", 60) }.exceptionOrNull() is OmpShellRefused)
    }
    @Test fun pairingRotationDuringApprovalRevokesTheRequest() = runTest {
        var gen = 1
        val r = OmpShellRelay({ true }, { false }, { true }, { gen = 2; true }, { gen },
            { _, _ -> error("must not execute after rotation") })
        val e = runCatching { r.run("id", 60) }.exceptionOrNull()
        assertTrue(e is OmpShellRefused && e.message!!.contains("pairing changed"))
    }
    @Test fun wrapperArgvEnforcesTimeoutAndCapWithoutRequotingTheCommand() {
        val argv = OmpShellRelay.wrapperArgv("echo \"a b\"; rm -rf /", 5, 1024)
        assertEquals(listOf("sh", "-c"), argv.subList(0, 2))
        assertEquals("bsh", argv[3])
        assertEquals("echo \"a b\"; rm -rf /", argv[4]) // the command is a single argv entry, never spliced into the script
        assertEquals("5", argv[5]); assertEquals("1024", argv[6])
        assertTrue(argv[2].contains("timeout \"\$2\"") && argv[2].contains("head -c \"\$3\""))
    }
    @Test fun normalizationAndAccessibilityRefusalApplyEvenInAuto() = runTest {
        auto = true
        for (command in listOf("adb pull /sdcard/file", "", "a".repeat(4001), "id\u0000whoami",
            "adb shell settings put secure accessibility_enabled 1",
            "sh -c 'settings put secure enabled_\"accessibility\"_services other/service'",
            "cmd settings reset secure", "content update --uri content://settings/secure --bind value:s:1")) {
            assertTrue(command, runCatching { relay.run(command, 60) }.exceptionOrNull() is ToolArgumentException)
        }
        assertTrue(commands.isEmpty()); assertTrue(questions.isEmpty())
        relay.run("adb shell settings get secure accessibility_enabled", 60)
        assertEquals("settings get secure accessibility_enabled", commands.single().first)
    }
}
