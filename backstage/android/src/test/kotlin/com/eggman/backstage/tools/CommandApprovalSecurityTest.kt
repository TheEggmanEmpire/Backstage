package com.eggman.backstage.tools

import com.eggman.backstage.privileged.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandApprovalSecurityTest {
    private fun args(command: String) = Args(JSONObject().put("command", command))
    @Test fun fullAdbCommandIsShownAndLongerInputIsRefused(): Unit = runBlocking {
        val command = "echo " + "x".repeat(990) + " tail"
        val hooks = RecordingHooks(approve = false)
        val tool = AdbCommandTool(exec = { _, _ -> error("must not execute") }, autoApprove = { false }, available = { true })
        assertTrue(tool.run(TestToolContext(hooks = hooks), args(command)).isError)
        assertTrue(hooks.asked.single().contains(command))
        try { tool.run(TestToolContext(hooks = hooks), args("x".repeat(1_001))); fail("long command accepted") }
        catch (_: ToolArgumentException) { }
    }
    @Test fun approvalLimitDoesNotBreakTheSharedOmpShellNormalizer() {
        // OMP's script launcher is generated shell and has its own approval. Only adb_command has the 1000 cap.
        val shell = "echo " + "x".repeat(1_500)
        assertEquals(shell, AdbCommand.normalize(shell))
    }
    @Test fun fullOmpTaskIsShownAndLongerTaskIsRefusedEvenWithAuto() = runBlocking {
        val task = "x".repeat(1_990) + " finaltext"
        val hooks = RecordingHooks(approve = false)
        val tool = OmpSubagentTool(autoApprove = { false }, unavailable = { null },
            start = { _, _, _, _ -> error("must not start") }, defaultModel = { "model" })
        assertTrue(tool.run(TestToolContext(hooks = hooks), Args(JSONObject().put("task", task))).isError)
        assertTrue(hooks.asked.single().contains(task))
        try {
            OmpSubagentTool(autoApprove = { true }, unavailable = { null }, defaultModel = { "model" })
                .run(TestToolContext(), Args(JSONObject().put("task", "x".repeat(2_001))))
            fail("long task accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun autoAdbStillChecksAndRestoresExpandedAccessibilityMutation() = runBlocking {
        val before = linkedMapOf("enabled_accessibility_services" to "good/.Reader", "accessibility_enabled" to "1")
        val values = before.toMutableMap()
        val commands = mutableListOf<String>()
        val command = "k=enabled_accessibility_serv; settings put secure \${k}ices evil/.Service"
        val tool = AdbCommandTool(exec = { cmd, _ ->
            commands += cmd
            when {
                cmd.startsWith("settings get secure ") -> ShellResult(0, values[cmd.substringAfterLast(' ')]+"\n", "")
                cmd == command -> { values["enabled_accessibility_services"] = "evil/.Service"; ShellResult(0,"","") }
                cmd.startsWith("settings put secure ") -> {
                    val parts = cmd.split(' ',limit = 5)
                    values[parts[3]] = parts[4].trim('\'')
                    ShellResult(0,"","")
                }
                else -> error(cmd)
            }
        }, autoApprove = { true }, available = { true })
        val result = tool.run(TestToolContext(), args(command))
        assertTrue(result.isError)
        assertTrue(result.text.contains("previous values were restored"))
        assertEquals(before, values)
        assertEquals(command, commands[2])
        assertTrue(commands.any { it.startsWith("settings put secure enabled_accessibility_services") })
    }
    @Test fun cancellationAfterMutationStillRestoresAccessibility() = runBlocking {
        var value = "good/.Reader"
        val tool = AdbCommandTool(exec = { cmd, _ ->
            when {
                cmd == "id" -> { value = "evil/.Service"; throw CancellationException("cancel") }
                cmd == "settings get secure enabled_accessibility_services" -> ShellResult(0,value,"")
                cmd == "settings get secure accessibility_enabled" -> ShellResult(0,"1","")
                cmd.startsWith("settings put secure enabled_accessibility_services") -> { value = "good/.Reader"; ShellResult(0,"","") }
                else -> error(cmd)
            }
        }, autoApprove = { true }, available = { true })
        try { tool.run(TestToolContext(), args("id")); fail("cancellation swallowed") }
        catch (_: CancellationException) { }
        assertEquals("good/.Reader", value)
    }
    @Test fun unreadableAccessibilityBaselineRefusesCommand() = runBlocking {
        val commands = mutableListOf<String>()
        val tool = AdbCommandTool(exec = { cmd, _ -> commands += cmd; ShellResult(1,"","unavailable") },
            autoApprove = { true }, available = { true })
        assertTrue(tool.run(TestToolContext(), args("id")).isError)
        assertFalse("id" in commands)
    }
    @Test fun adbAndOmpAutoSettingsRemainIndependent() = runBlocking {
        val hooks = RecordingHooks(approve = false)
        var ran = false
        val adb = AdbCommandTool(exec = { _, _ -> ran = true; ShellResult(0,"null\n","") },
            autoApprove = { true }, available = { true })
        assertFalse(adb.run(TestToolContext(hooks = hooks), args("id")).isError)
        assertTrue(ran)
        assertTrue(hooks.asked.isEmpty())
        val omp = OmpSubagentTool(autoApprove = { false }, unavailable = { null },
            start = { _, _, _, _ -> error("must not run") }, defaultModel = { "model" })
        assertTrue(omp.run(TestToolContext(hooks = hooks), Args(JSONObject().put("task","work"))).isError)
        assertEquals(1,hooks.asked.size)
    }
    @Test fun scheduleInstructionsAreBoundedAndApprovalContainsFullStoredInstruction() = runBlocking {
        val old = "o".repeat(2_000)
        val replacement = "n".repeat(1_990) + " finaltext"
        val hooks = RecordingHooks(approve = true)
        var writes = 0
        val ctx = TestToolContext(hooks = hooks)
        ctx.services.schedulerImpl = object : ScheduledTaskApi {
            override fun list() = listOf(ScheduledTaskInfo("s1",old,"0 7 * * *",true,null))
            override fun create(description: String, cron: String, source: String): ScheduledTaskInfo { writes++; return list().single() }
            override fun update(id: String, description: String?, cron: String?, enabled: Boolean?): ScheduledTaskInfo { writes++; return list().single() }
            override fun delete(id: String) = false
        }
        assertFalse(CreateScheduledTaskTool().run(ctx, Args(JSONObject().put("description",replacement).put("cron_expression","0 7 * * *"))).isError)
        assertTrue(hooks.asked.last().contains(replacement))
        assertFalse(UpdateScheduledTaskTool().run(ctx, Args(JSONObject().put("id","s1").put("description",replacement))).isError)
        assertTrue(hooks.asked.last().contains(replacement))
        assertTrue("2000-char update must fit approval display", hooks.asked.last().length <= 4_000)
        val before = writes
        assertTrue(CreateScheduledTaskTool().run(ctx, Args(JSONObject().put("description","x".repeat(2_001)).put("cron_expression","0 7 * * *"))).isError)
        assertEquals(before,writes)
    }
}
