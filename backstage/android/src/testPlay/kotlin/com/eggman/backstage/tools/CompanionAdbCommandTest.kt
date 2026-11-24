package com.eggman.backstage.tools

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompanionAdbCommandTest {
    @Test fun denialPreventsTheCompanionRequest() = runBlocking {
        val hooks = RecordingHooks(approve = false)
        val tool = CompanionPrivilegedTool.adbCommand({ false }) { error("Command reached the companion") }
        val result = tool.run(TestToolContext(hooks = hooks), Args(JSONObject().put("command", "adb shell id")))

        assertTrue(result.isError)
        assertTrue(hooks.asked.single().contains("id"))
    }

    @Test fun autoSendsNormalizedCommandAndPreservesTheExitCode() = runBlocking {
        val tool = CompanionPrivilegedTool.adbCommand({ true }) {
            { request ->
                assertEquals("start", request.getString("action"))
                assertEquals("id", request.getString("argument"))
                assertEquals(5, request.getInt("timeoutSeconds"))
                Bundle().apply {
                    putString("state", "finished")
                    putInt("exitCode", 7)
                    putString("stdout", "output")
                    putString("stderr", "diagnostic")
                }
            }
        }
        val args = Args(JSONObject().put("command", "adb shell id").put("timeout_seconds", 2))
        val result = tool.run(TestToolContext(), args)

        assertTrue(result.isError)
        assertEquals(7, result.data?.getInt("exit_code"))
        assertEquals("output", result.data?.getString("stdout"))
        assertEquals("diagnostic", result.data?.getString("stderr"))
    }
}
