package com.eggman.backstage.integration

import com.eggman.backstage.a11y.AccessibilityEnabler
import com.eggman.backstage.agent.Approval
import com.eggman.backstage.agent.MiniStatus
import com.eggman.backstage.agent.MiniStatusBridge
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Answers
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.host.extras
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.privileged.PrivilegedSetup
import com.eggman.backstage.voice.VoiceCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seams between the work packages that the merge reconciled (library side, no Dextop glue present). */
class IntegrationSeamsTest {

    private fun snap(
        status: TaskStatus,
        step: Int = 0,
        lastAction: String? = null,
        question: PendingQuestion? = null,
        result: String? = null,
        paused: Boolean = false,
        plan: List<PlanStep> = emptyList(),
    ) = TaskSnapshot(
        "t1", TaskRequest("Order the usual pizza on the desktop", Source.CHAT), status, step, 40, plan,
        lastAction, question, result, 0L, null, paused,
    )

    // ---- mini window caption ----------------------------------------------------------------
    @Test fun miniStatusLines() {
        assertNull(MiniStatus.format(AgentState()).text)
        assertEquals("Step 3/40 · tap Order", MiniStatus.format(AgentState(snap(TaskStatus.RUNNING, 3, "tap Order"))).text)
        assertEquals("Paused: Order the usual pizza on the desktop", MiniStatus.format(AgentState(snap(TaskStatus.RUNNING, 3, "x", paused = true))).text)
        assertEquals("Needs approval: Pay 23 EUR?", MiniStatus.format(AgentState(snap(TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Pay 23 EUR?", true)))).text)
        assertEquals("Question: Which size?", MiniStatus.format(AgentState(snap(TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which size?", false)))).text)
        val done = MiniStatus.format(AgentState(snap(TaskStatus.SUCCEEDED, result = "Ordered.\nDetails")))
        assertEquals("Done: Ordered.", done.text)
        assertTrue(done.terminal)
        assertEquals("Stopped", MiniStatus.format(AgentState(snap(TaskStatus.CANCELLED))).text)
        val long = MiniStatus.format(AgentState(snap(TaskStatus.RUNNING, 1, "x".repeat(500)))).text!!
        assertTrue(long.length <= MiniStatus.MAX_LENGTH)
        assertEquals("Step 1/40 · Open the app", MiniStatus.format(AgentState(snap(TaskStatus.RUNNING, 1, plan = listOf(PlanStep("Open the app", "in_progress"))))).text)
    }

    @Test fun miniStatusBridgePushesAndClearsTerminalLines() {
        val state = MutableStateFlow(AgentState())
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val job = MiniStatusBridge.start(state, clearAfterMs = 50L) { seen += it }
        try {
            waitFor { seen.isNotEmpty() }
            state.value = AgentState(snap(TaskStatus.RUNNING, 1, "tap Login"))
            waitFor { "Step 1/40 · tap Login" in seen }
            state.value = AgentState(snap(TaskStatus.SUCCEEDED, result = "Logged in"))
            waitFor { seen.lastOrNull() == null && "Done: Logged in" in seen }
            assertEquals(null, seen.last())
        } finally {
            job.cancel()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "condition not met" }
            Thread.sleep(10)
        }
    }

    // ---- Shizuku-first setup --------------------------------------------------------------------
    @Test fun privilegedSetupEnablesTheOneServiceDropsTheRetiredOneAndKeepsOthers() = runBlocking {
        val dextop = "com.eggman.backstage/moe.n4tsu.dextop.MirrorService"
        val retired = "com.eggman.backstage/com.eggman.backstage.a11y.AgentAccessibilityService"
        // An update from the retired agent service is still listed next to other apps' services.
        val secure = mutableMapOf(AccessibilityEnabler.KEY_ENABLED_SERVICES to "com.pw/.Fill:$retired:com.talkback/.TB")
        val calls = mutableListOf<List<String>>()
        val shell = AccessibilityEnabler.Shell { argv ->
            calls += argv.toList()
            when {
                argv[0] == "settings" && argv[1] == "get" -> 0 to (secure[argv[3]] ?: "null")
                argv[0] == "settings" && argv[1] == "put" -> { secure[argv[3]] = argv[4]; 0 to "" }
                argv[0] == "pm" && argv[3] == "android.permission.POST_NOTIFICATIONS" -> 1 to "denied" // e.g. not declared
                else -> 0 to ""
            }
        }
        val report = PrivilegedSetup.run(shell, "com.eggman.backstage", listOf(dextop), retired = listOf(retired))
        assertEquals("com.pw/.Fill:com.talkback/.TB:$dextop", secure[AccessibilityEnabler.KEY_ENABLED_SERVICES])
        assertEquals("1", secure[AccessibilityEnabler.KEY_ACCESSIBILITY_ENABLED])
        assertTrue(PrivilegedSetup.ACCESSIBILITY in report.done)
        assertTrue(report.accessibilityDone)
        assertEquals(listOf("Notifications"), report.failed)
        assertTrue(listOf("pm", "grant", "com.eggman.backstage", "android.permission.WRITE_SECURE_SETTINGS") in calls)
        assertTrue(listOf("appops", "set", "com.eggman.backstage", "SYSTEM_ALERT_WINDOW", "allow") in calls)
        // Personal-data permissions are never granted silently.
        assertFalse(calls.any { it.any { a -> a.contains("RECORD_AUDIO") || a.contains("CALENDAR") || a.contains("SMS") } })
        // Idempotent: a second run does not rewrite the list.
        calls.clear()
        PrivilegedSetup.run(shell, "com.eggman.backstage", listOf(dextop), retired = listOf(retired))
        assertFalse(calls.any { it.take(4) == listOf("settings", "put", "secure", AccessibilityEnabler.KEY_ENABLED_SERVICES) })
        assertEquals("a:b", PrivilegedSetup.accessibilityValue("null", listOf("a", "b", "a")))
        assertEquals("x/.Y:a", PrivilegedSetup.accessibilityValue("old/.Agent:x/.Y", listOf("a"), retired = listOf("old/.Agent")))
    }

    @Test fun privilegedSetupWithoutAnAccessibilityServiceReportsItNotDone() = runBlocking {
        val shell = AccessibilityEnabler.Shell { argv -> if (argv[0] == "settings" && argv[1] == "get") 0 to "com.pw/.Fill" else 0 to "" }
        val report = PrivilegedSetup.run(shell, "com.eggman.backstage", emptyList())
        assertFalse(report.accessibilityDone)
        assertTrue(PrivilegedSetup.ACCESSIBILITY in report.failed)
    }

    // ---- answer vocabulary ----------------------------------------------------------------------
    @Test fun approvalIsStrict() {
        assertTrue(Approval.parse(Answers.APPROVE))
        assertFalse(Approval.parse(Answers.DENY))
        assertTrue(Approval.parse("Yes, send it"))
        assertTrue(Approval.parse("ok"))
        assertTrue(Approval.parse("go ahead"))
        listOf("go away", "ok no", "sure, but don't send it", "yes but wait", "no", "maybe later", "", "yes ".repeat(10))
            .forEach { assertFalse(it, Approval.parse(it)) }
    }

    @Test fun voiceCommandsAnswerOrStopTheCurrentTask() {
        val confirm = snap(TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Send?", true))
        assertEquals(VoiceCommand.Answer(Answers.APPROVE), VoiceCommand.classify("yes send it", confirm))
        assertEquals(VoiceCommand.Answer(Answers.DENY), VoiceCommand.classify("no don't", confirm))
        val ask = snap(TaskStatus.WAITING_FOR_USER, question = PendingQuestion("Which Anna?", false))
        assertEquals(VoiceCommand.Answer("Anna K"), VoiceCommand.classify("Anna K", ask))
        assertEquals(VoiceCommand.Stop, VoiceCommand.classify("stop", snap(TaskStatus.RUNNING)))
        assertEquals(VoiceCommand.NewTask("stop"), VoiceCommand.classify("stop", null))
        assertEquals(VoiceCommand.NewTask("open maps"), VoiceCommand.classify(" open maps ", snap(TaskStatus.SUCCEEDED)))
    }

    // ---- init without / with the Dextop glue ------------------------------------------------------
    @Test fun bridgeInstallIsReflectiveAndOptional() {
        DesktopHosts.current = null
        assertEquals(Backstage.Bridge.ABSENT, Backstage.installDesktopBridge(null, "moe.n4tsu.dextop.DoesNotExist"))
        assertNull(DesktopHosts.current)
        assertNull(DesktopHosts.extras())
        assertEquals(Backstage.Bridge.FAILED, Backstage.installDesktopBridge(null, ThrowingBridge::class.java.name))
        assertEquals(Backstage.Bridge.INSTALLED, Backstage.installDesktopBridge(null, FakeBridge::class.java.name))
        assertTrue(FakeBridge.installed)
        assertEquals(Backstage.Bridge.INSTALLED, Backstage.installDesktopBridge(null, StaticBridge::class.java.name))
        assertTrue(StaticBridge.installed)
    }
}

/** Kotlin object with a member install(Context) (the INSTANCE path). */
object FakeBridge {
    @Volatile var installed = false
    fun install(@Suppress("UNUSED_PARAMETER") context: android.content.Context?) { installed = true }
}

/** Glue shape: @JvmStatic install(Context) (the static path BackstageDesktopBridge uses). */
object StaticBridge {
    @Volatile var installed = false
    @JvmStatic fun install(@Suppress("UNUSED_PARAMETER") context: android.content.Context?) { installed = true }
}

object ThrowingBridge {
    @JvmStatic fun install(@Suppress("UNUSED_PARAMETER") context: android.content.Context?): Unit = error("boom")
}
