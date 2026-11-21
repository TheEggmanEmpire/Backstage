package com.eggman.backstage.tools

import com.eggman.backstage.privileged.ShellResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdbCommandTest {
    @Test fun shellPrefixesAreStripped() {
        assertEquals("pm list packages -3", AdbCommand.normalize("  adb shell pm list packages -3  "))
        assertEquals("settings get global adb_enabled", AdbCommand.normalize("adb -d shell settings get global adb_enabled"))
        assertEquals("id", AdbCommand.normalize("adb\tshell\tid"))
    }
    @Test fun installPreservesFlagsAndQuotedPaths() {
        assertEquals("pm install -r -g '/sdcard/My app.apk'", AdbCommand.normalize("adb install -r -g '/sdcard/My app.apk'"))
        assertEquals("pm install /sdcard/app.apk", AdbCommand.normalize("adb install /sdcard/app.apk"))
        assertEquals("pm install /sdcard/app.apk", AdbCommand.normalize("install /sdcard/app.apk"))
    }
    @Test fun uninstallPreservesKeepDataFlag() {
        assertEquals("pm uninstall -k com.example.app", AdbCommand.normalize("adb uninstall -k com.example.app"))
        assertEquals("pm uninstall com.example.app", AdbCommand.normalize("uninstall com.example.app"))
    }
    @Test fun rebootIsMapped() {
        assertEquals("reboot", AdbCommand.normalize("adb reboot"))
        assertEquals("reboot bootloader", AdbCommand.normalize("adb reboot bootloader"))
        assertEquals("reboot bootloader", AdbCommand.normalize("adb reboot-bootloader"))
    }
    @Test fun hostOnlyAndUnknownVerbsAreRejected() {
        listOf("push", "pull", "connect", "disconnect", "pair", "forward", "reverse", "tcpip", "root", "remount", "sideload", "devices", "unknown").forEach {
            val error = assertThrows(ToolArgumentException::class.java) { AdbCommand.normalize("adb $it example") }
            assertTrue(error.message.orEmpty().contains("host-side"))
        }
    }
    @Test fun logcatGetsFiniteDumpUnlessAlreadyBounded() {
        assertEquals("logcat -d", AdbCommand.normalize("logcat"))
        assertEquals("logcat -v time -d", AdbCommand.normalize("adb logcat -v time"))
        assertEquals("logcat -d", AdbCommand.normalize("adb shell logcat -d"))
        assertEquals("logcat -t 100", AdbCommand.normalize("logcat -t 100"))
        assertEquals("logcat -t100", AdbCommand.normalize("logcat -t100"))
    }
    @Test fun shellCommandsArePreserved() {
        val command = "pm list packages -3 | head -5"
        assertEquals(command, AdbCommand.normalize(command))
        assertEquals("echo 'quoted -d'", AdbCommand.normalize("echo 'quoted -d'"))
    }
    @Test fun invalidCommandsAreRejected() {
        listOf("", "   ", "adb", "adb shell", "adb -d shell", "a".repeat(4001), "id\u0000whoami").forEach {
            assertThrows(ToolArgumentException::class.java) { AdbCommand.normalize(it) }
        }
    }
    private var calls = mutableListOf<Pair<String, Long>>()
    private var auto = false
    @Test fun accessibilitySettingsCannotBeWrittenEvenThroughShellWrappers() {
        for (command in listOf(
            "settings put secure enabled_accessibility_services evil/.Service",
            "settings reset secure",
            "content update --uri content://settings/secure --where \"name LIKE '%accessibility%'\" --bind value:s:1",
            "content call --uri content://settings/secure --method PUT_secure --arg accessibility_enabled --extra value:s:1",
            "adb shell cmd settings --user 0 put secure accessibility_enabled 1",
            "settings --user current delete secure accessibility_enabled",
            "sh -c \"settings put secure 'enabled_accessibility_services' evil/.Service\"",
            "content update --uri content://settings/secure --bind value:s:1 --where \"name='accessibility_enabled'\"",
            "content insert --uri content://settings/secure --bind name:s:enabled_accessibility_services --bind value:s:evil/.Service",
            "settings put secure enabled_''accessibility_services evil/.Service",
            "cmd settings put secure accessibility_\\enabled 1",
        )) {
            val e = assertThrows(command, ToolArgumentException::class.java) { AdbCommand.normalize(command) }
            assertTrue(e.message.orEmpty().contains("Accessibility"))
        }
        assertEquals("settings get secure accessibility_enabled", AdbCommand.normalize("settings get secure accessibility_enabled"))
        assertEquals("settings put global adb_enabled 1", AdbCommand.normalize("settings put global adb_enabled 1"))
    }

    private fun tool(available: Boolean = true, result: ShellResult = ShellResult(0, "ok", "")) = AdbCommandTool(
        exec = { command, timeout ->
            calls += command to timeout
            when (command) {
                "settings get secure enabled_accessibility_services" -> ShellResult(0, "good/.Reader\n", "")
                "settings get secure accessibility_enabled" -> ShellResult(0, "1\n", "")
                else -> result
            }
        },
        autoApprove = { auto }, available = { available },
    )
    private fun guardedCalls(command: String, timeoutMs: Long): List<Pair<String, Long>> {
        val reads = listOf("settings get secure enabled_accessibility_services" to 10000L,
            "settings get secure accessibility_enabled" to 10000L)
        return reads + (command to timeoutMs) + reads
    }
    private fun run(tool: Tool, hooks: AgentHooks? = null, command: String = "adb shell id", timeout: Int? = null) = runBlocking {
        tool.run(TestToolContext(hooks = hooks), Args(JSONObject().put("command", command).apply {
            if (timeout != null) put("timeout_seconds", timeout)
        }))
    }
    @Test fun declineDoesNotExecuteOrInviteRetry() {
        val hooks = RecordingHooks(approve = false)
        val result = run(tool(), hooks)
        assertTrue(result.isError)
        assertTrue(result.text.contains("Do not retry"))
        assertEquals("confirm:Run ADB command as shell: `id`?", hooks.asked.single())
        assertTrue(calls.isEmpty())
    }
    @Test fun directMcpWithoutAutoRefuses() {
        val result = run(tool())
        assertTrue(result.isError)
        assertTrue(result.text.contains("execute_task"))
        assertTrue(calls.isEmpty())
    }
    @Test fun approvalExecutesNormalizedCommandWithDefaultTimeout() {
        assertFalse(run(tool(), RecordingHooks()).isError)
        assertEquals(guardedCalls("id", 60000L), calls)
    }
    @Test fun confirmAlwaysIsUsedEvenIfOrdinaryConfirmWouldAllow() {
        val hooks = object : AgentHooks by RecordingHooks() {
            override suspend fun confirmAlways(action: String) = false
        }
        assertTrue(run(tool(), hooks).isError)
        assertTrue(calls.isEmpty())
    }
    @Test fun adbAutoAndPerCommandApprovalCannotEnableAccessibility() {
        for (autoEnabled in listOf(false, true)) {
            auto = autoEnabled
            val hooks = RecordingHooks(approve = true)
            assertThrows(ToolArgumentException::class.java) {
                run(tool(), hooks, "cmd settings put secure accessibility_enabled 1")
            }
            assertTrue(calls.isEmpty())
            assertTrue(hooks.asked.isEmpty())
        }
    }

    @Test fun autoRunsWithoutHooksAndIsReadFresh() {
        val tool = tool()
        assertTrue(run(tool).isError)
        auto = true
        assertFalse(run(tool).isError)
        auto = false
        assertTrue(run(tool).isError)
        assertEquals(guardedCalls("id", 60000L), calls)
    }
    @Test fun autoSkipsHooksAndTimeoutIsClamped() {
        auto = true
        val hooks = RecordingHooks(approve = false)
        assertFalse(run(tool(), hooks, timeout = 1).isError)
        assertFalse(run(tool(), hooks, timeout = 999).isError)
        assertEquals(guardedCalls("id", 5000L) + guardedCalls("id", 300000L), calls)
        assertTrue(hooks.asked.isEmpty())
    }
    @Test fun missingPrivilegeDoesNotAskOrExecute() {
        val hooks = RecordingHooks()
        val result = run(tool(available = false), hooks)
        assertTrue(result.isError)
        assertTrue(result.text.contains("Shizuku"))
        assertTrue(hooks.asked.isEmpty())
        assertTrue(calls.isEmpty())
    }
    @Test fun outputIsStructuredBoundedAndKeepsTail() {
        auto = true
        val result = run(tool(result = ShellResult(7, "x".repeat(14000) + "OUT TAIL", "y".repeat(14000) + "ERR TAIL")))
        assertTrue(result.isError)
        val data = JSONObject(result.text)
        assertEquals(7, data.getInt("exit_code"))
        assertTrue(data.getString("stdout").endsWith("OUT TAIL"))
        assertTrue(data.getString("stderr").endsWith("ERR TAIL"))
        assertTrue(data.getString("stdout").length + data.getString("stderr").length <= 12000)
    }
    @Test fun questionUsesTheFullNormalizedCommand() {
        val hooks = RecordingHooks(approve = false)
        run(tool(), hooks, command = "adb shell echo " + "x".repeat(350))
        assertEquals("confirm:Run ADB command as shell: `" + ("echo " + "x".repeat(350)) + "`?", hooks.asked.single())
    }
    @Test fun registryAvailabilityFiltersAgentAndMcp() {
        val registry = ToolRegistry.default()
        val unavailable = ToolRegistry.Availability(desktop = false, overlay = false, adb = false)
        val available = unavailable.copy(adb = true)
        assertFalse(registry.forAgent(unavailable).any { it.name == "adb_command" })
        assertFalse(registry.forMcp(unavailable).any { it.name == "adb_command" })
        assertTrue(registry.forAgent(available).any { it.name == "adb_command" })
        assertTrue(registry.forMcp(available).any { it.name == "adb_command" })
        assertTrue(registry["adb_command"]!!.mutating)
        assertFalse(registry["adb_command"]!!.irreversible)
        assertTrue(registry["adb_command"]!!.targets.isEmpty())
    }
}
