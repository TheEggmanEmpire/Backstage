package com.eggman.backstage.mcp

import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.FakeEnv
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.testutil.Screens
import com.eggman.backstage.testutil.ScriptedLlm
import com.eggman.backstage.testutil.ScriptedLlm.Companion.call
import com.eggman.backstage.testutil.ScriptedLlm.Companion.text
import com.eggman.backstage.testutil.script
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackstageMcpToolsTest {
    private fun TestScope.setup(llm: ScriptedLlm = ScriptedLlm(script(text("done"))), wake: (suspend () -> Boolean)? = null): Triple<BackstageMcpTools, AgentControllerImpl, FakeEnv> {
        val env = FakeEnv(this, llm)
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        return Triple(BackstageMcpTools({ c }, env, wake = wake), c, env)
    }

    @Test fun defaultToolSetAndOptInLowLevel() = runTest {
        val (tools, _, env) = setup()
        assertEquals(listOf("execute_task", "get_screen_state", "get_device_status"), tools.tools().map { it.name })
        env.settings.mcpEnabledTools = setOf("execute_task", "wake_device", "tap")
        assertEquals(listOf("execute_task", "wake_device", "tap"), tools.tools().map { it.name })
        env.settings.mcpExtendedTools = true
        val all = tools.tools().map { it.name }
        assertTrue("type_text" in all && "launch_app" in all)
        assertFalse("finish" in all || "ask_user" in all)
        assertTrue(tools.tools().first { it.name == "tap" }.destructive)
    }

    @Test fun deviceStatusJsonKeys() = runTest {
        val (tools, _, env) = setup()
        var r = tools.call("get_device_status", JSONObject())
        var j = JSONObject(r.content.single().text!!)
        assertTrue(j.getBoolean("screen_on"))
        assertFalse(j.getBoolean("locked"))
        assertTrue(j.getBoolean("accessibility_enabled"))
        assertTrue(j.getBoolean("ready"))
        assertFalse(j.has("reason"))
        env.unlocked = false
        r = tools.call("get_device_status", JSONObject())
        j = JSONObject(r.content.single().text!!)
        assertFalse(j.getBoolean("ready"))
        assertTrue(j.getBoolean("locked"))
        assertTrue(j.getString("reason").contains("locked"))
        assertEquals(j.toString(), r.structured.toString())
    }

    /** /  / a locked phone does not block a desktop that keeps running while locked. */
    @Test fun lockedPhoneWithADesktopThatRunsWhileLockedIsReadyForDesktopTasks() = runTest {
        var woke = false
        val (tools, _, env) = setup(ScriptedLlm(script(call("finish", """{"summary":"Tidied the downloads","success":true}"""))),
            wake = { woke = true; false })
        suspend fun status(target: String = "auto") = tools.call("get_device_status", JSONObject().put("target", target)).structured!!
        env.settings.defaultTarget = com.eggman.backstage.agent.api.Target.DESKTOP
        env.hostPresent = true
        env.privileged = true
        env.desktopActive = true
        env.desktopRunsWhileLocked = true
        env.unlocked = false
        env.screenOn = false // in the owner's pocket

        var j = status()
        assertTrue(j.toString(), j.getBoolean("ready"))
        assertEquals("desktop", j.getString("target"))
        assertTrue(j.getBoolean("locked"))
        assertFalse(j.has("reason"))
        assertTrue(j.getJSONObject("desktop").getBoolean("works_while_locked"))
        val r = tools.call("execute_task", JSONObject("""{"task":"tidy my downloads","target":"desktop"}"""))
        assertFalse(r.content.single().text, r.isError)
        assertEquals("Tidied the downloads", r.content.single().text)
        assertFalse("the phone is left dark", woke)

        // The phone screen on the same locked, dark phone is not ready (and remote callers may not use it anyway).
        j = status("phone")
        assertFalse(j.getBoolean("ready"))
        assertTrue(j.getString("reason"), j.getString("reason").startsWith("Display off"))

        // A desktop with the default flags sleeps with the phone: refused with the reason and the way out.
        env.desktopRunsWhileLocked = false
        env.screenOn = true
        j = status()
        assertFalse(j.getBoolean("ready"))
        assertEquals("desktop", j.getString("target"))
        assertTrue(j.getString("reason").startsWith("Phone locked"))
        val refused = tools.call("execute_task", JSONObject("""{"task":"tidy my downloads"}"""))
        assertTrue(refused.isError)
        assertTrue(refused.content.single().text!!, refused.content.single().text!!.contains("Keep the desktop running while locked"))

        // Not running yet, but it will start with the opt-in: ready as well.
        env.desktopActive = false
        env.desktopStartableWhileLocked = true
        assertTrue(status().getBoolean("ready"))
    }

    @Test fun executeTaskRunsAndReportsResult() = runTest {
        val (tools, c, _) = setup(ScriptedLlm(script(call("finish", """{"summary":"Opened Maps","success":true}"""))))
        val r = tools.call("execute_task", JSONObject("""{"task":"open maps","target":"phone"}"""))
        assertFalse(r.isError)
        assertEquals("Opened Maps", r.content.single().text)
        assertEquals("succeeded", r.structured!!.getString("status"))
        val h = c.history(1).single()
        assertEquals(Source.MCP, h.request.source)
        assertFalse(h.request.interactive)
        assertEquals(300_000L, h.request.timeoutMs)
    }

    @Test fun executeTaskPreflight() = runTest {
        val (tools, c, env) = setup(ScriptedLlm(script(call("ask_user", """{"question":"?"}"""))))
        try { tools.call("execute_task", JSONObject()); throw AssertionError("expected invalid params") } catch (_: McpInvalidParams) {}
        env.a11y = false
        assertTrue(tools.call("execute_task", JSONObject("""{"task":"x"}""")).content.single().text!!.contains("accessibility"))
        env.a11y = true
        env.screenOn = false
        assertTrue(tools.call("execute_task", JSONObject("""{"task":"x"}""")).content.single().text!!.contains("Display is dark"))
        env.screenOn = true
        env.unlocked = false
        assertTrue(tools.call("execute_task", JSONObject("""{"task":"x"}""")).content.single().text!!.contains("locked"))
        env.unlocked = true
        c.submit(TaskRequest("busy", Source.CHAT))
        runCurrent()
        val busy = tools.call("execute_task", JSONObject("""{"task":"x"}"""))
        assertTrue(busy.isError)
        assertTrue(busy.content.single().text!!.contains("Busy"))
        c.cancel(null)
    }

    @Test fun wakeDevice() = runTest {
        var woke = false
        lateinit var envRef: FakeEnv
        val (tools, _, env) = setup(wake = { woke = true; envRef.screenOn = true; true })
        envRef = env
        assertTrue(tools.call("wake_device", JSONObject()).content.single().text!!.contains("Nothing to do"))
        env.screenOn = false
        env.unlocked = false
        val r = tools.call("wake_device", JSONObject())
        assertTrue(woke)
        assertTrue(r.content.single().text!!.contains("lock screen is up"))
    }

    @Test fun screenStateMarkupAndList() = runTest {
        val (tools, _, env) = setup()
        env.phone.capture = Screens.buttons("Allow", "Deny")
        val md = tools.call("get_screen_state", JSONObject())
        assertTrue(md.content.single().text!!.startsWith("<app package=\"com.example\">"))
        assertTrue(md.content.single().text!!.contains("txt=\"Allow\""))
        assertEquals(2, md.structured!!.getJSONArray("elements").length())
        val list = tools.call("get_screen_state", JSONObject("""{"format":"list","include_screenshot":true}"""))
        assertTrue(list.content[0].text!!.contains("[0] Button \"Allow\""))
        assertEquals("image", list.content[1].type)
    }

    /** with "every low-level tool" on, a client still cannot send SMS or place calls with nobody approving. */
    @Test fun irreversibleToolsAreNeitherListedNorCallableDirectly() = runTest {
        val dc = com.eggman.backstage.tools.ToolsTest.FakeDeviceControl(setOf("sms", "call", "contacts"))
        val env = FakeEnv(this, ScriptedLlm(script(text("done"))), com.eggman.backstage.tools.ToolsTest.DcServices(dc))
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val tools = BackstageMcpTools({ c }, env)
        env.settings.mcpExtendedTools = true
        env.settings.mcpEnabledTools = env.settings.mcpEnabledTools + setOf("sms_send", "phone_call")
        val listed = tools.tools().map { it.name }
        assertTrue("contacts_search" in listed)
        assertFalse("sms_send" in listed || "phone_call" in listed)
        val r = tools.call("sms_send", JSONObject("""{"phone_number":"+49 170 1234567","message":"hi"}"""))
        assertTrue(r.isError)
        assertTrue(r.content.single().text!!, r.content.single().text!!.contains("execute_task"))
        assertTrue(tools.call("phone_call", JSONObject("""{"phone_number":"+49 170 1234567"}""")).isError)
        assertTrue(dc.calls.isEmpty())
    }

    /** "All off" empties the set: tools/list shows nothing and tools/call is refused; "All on" lists every tool. */
    @Test fun allOnAllOffSwitchesEveryTool() = runTest {
        val (tools, _, env) = setup(ScriptedLlm(script(text("done"))))
        env.settings.mcpEnabledTools = emptySet()
        env.settings.mcpExtendedTools = false
        assertTrue(tools.tools().isEmpty())
        val p = McpProtocol(tools)
        val refused = p.dispatch(JSONObject("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_device_status","arguments":{}}}"""), "s")!!
        assertTrue(refused.toString(), refused.has("error") || refused.getJSONObject("result").optBoolean("isError"))
        env.settings.mcpEnabledTools = com.eggman.backstage.ui.settings.McpToolChoices.all()
        val listed = tools.tools().map { it.name }
        assertTrue(listed.containsAll(listOf("execute_task", "get_screen_state", "get_device_status", "tap")))
    }

    @Test fun lowLevelToolsRefusedWhileAgentBusy() = runTest {
        val (tools, c, env) = setup(ScriptedLlm(script(call("ask_user", """{"question":"?"}"""))))
        env.settings.mcpExtendedTools = true
        assertFalse(tools.call("get_screen", JSONObject()).isError)
        c.submit(TaskRequest("busy", Source.CHAT))
        runCurrent()
        assertTrue(tools.call("tap", JSONObject("""{"x":1,"y":1}""")).isError)
        // read-only registry tools store what they read in the shared executor: refused too while busy
        assertTrue(tools.call("get_screen", JSONObject()).isError)
        c.cancel(null)
    }

    /** / `notifications/cancelled` for a running execute_task stops the task. */
    @Test fun cancelledNotificationStopsTheRunningTask() = runTest {
        val hanging = object : com.eggman.backstage.llm.LlmClient {
            override val provider = com.eggman.backstage.llm.ProviderId.ANTHROPIC
            override val model = "slow"
            override suspend fun complete(request: com.eggman.backstage.llm.LlmRequest): com.eggman.backstage.llm.LlmReply = kotlinx.coroutines.awaitCancellation()
        }
        val env = FakeEnv(this, hanging)
        val c = AgentControllerImpl(env, StandardTestDispatcher(testScheduler))
        val p = McpProtocol(BackstageMcpTools({ c }, env))
        val call = async {
            p.dispatch(JSONObject("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"execute_task","arguments":{"task":"x"}}}"""), "s1")
        }
        runCurrent()
        assertEquals(com.eggman.backstage.agent.api.TaskStatus.RUNNING, c.state.value.current?.status)
        // another session's request 5 is a different request
        p.dispatch(JSONObject("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":5}}"""), "other")
        runCurrent()
        assertEquals(com.eggman.backstage.agent.api.TaskStatus.RUNNING, c.state.value.current?.status)
        p.dispatch(JSONObject("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":5,"reason":"user"}}"""), "s1")
        val r = call.await()!!.getJSONObject("result")
        assertTrue(r.getBoolean("isError"))
        assertTrue(r.getJSONArray("content").getJSONObject(0).getString("text").contains("stopped"))
        assertEquals(com.eggman.backstage.agent.api.TaskStatus.CANCELLED, c.state.value.current?.status)
    }

    @Test fun screenStateDuringATaskLeavesTheTasksListingAlone() = runTest {
        val (tools, c, env) = setup(ScriptedLlm(script(call("ask_user", """{"question":"?"}"""))))
        env.phone.capture = Screens.buttons("Compose", "Send")
        c.submit(TaskRequest("busy", Source.CHAT))
        runCurrent()
        val seenByTask = env.phone.lastScreen
        val seenCapture = env.phone.lastCapture
        val shot = env.phone.lastScreenshot
        env.phone.capture = Screens.buttons("A dialog", "OK", "Cancel")
        val r = tools.call("get_screen_state", JSONObject("""{"include_screenshot":true}"""))
        assertTrue(r.content[0].text!!.contains("A dialog"))
        assertTrue(seenByTask === env.phone.lastScreen)
        assertTrue(seenCapture === env.phone.lastCapture)
        assertTrue(shot === env.phone.lastScreenshot)
        c.cancel(null)
    }
}
