package com.eggman.backstage.surfaces

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.automation.BroadcastApi
import com.eggman.backstage.automation.BroadcastApiReceiver
import com.eggman.backstage.automation.LocaleFireReceiver
import com.eggman.backstage.automation.PluginBundleCodec
import com.eggman.backstage.automation.PluginConfig
import com.eggman.backstage.automation.PluginProtocol
import com.eggman.backstage.automation.toBundle
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.ui.Fixtures
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Serializable that fails while being read back: what a hostile app can put into any extra. */
class Boom : java.io.Serializable {
    @Suppress("unused")
    private fun readObject(input: java.io.ObjectInputStream): Unit = throw java.io.IOException("boom")

    companion object {
        private const val serialVersionUID = 1L
    }
}

@RunWith(RobolectricTestRunner::class)
class AutomationReceiversRobolectricTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val settings = memorySettings()
    private lateinit var agent: FakeAgent

    @Before fun setUp() {
        SettingsStores.set(settings)
        agent = FakeAgent()
        AgentAccess.override = agent
    }

    @After fun tearDown() {
        AgentAccess.override = null
        SettingsStores.set(null)
    }

    /** A bundle as it arrives from another process: parcelled, values unparcelled lazily on first access. */
    private fun fromOtherProcess(b: Bundle): Bundle {
        val p = Parcel.obtain()
        try {
            b.writeToParcel(p, 0)
            p.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(p)
        } finally {
            p.recycle()
        }
    }

    @Test fun hostileExtrasNeverCrashTheProcess() {
        val r = BroadcastApiReceiver()
        // Hostile value next to a valid request: ignored, the task runs.
        val ok = fromOtherProcess(Bundle().apply {
            putString(BroadcastApi.EXTRA_TOKEN, settings.mcpToken)
            putString(BroadcastApi.EXTRA_TASK, "Mute the phone")
            putSerializable("evil", Boom())
        })
        r.onReceive(ctx, Intent(BroadcastApi.ACTION_RUN).putExtras(ok))
        assertEquals(listOf("Mute the phone"), agent.submitted.map { it.prompt })
        assertEquals(Source.BROADCAST, agent.submitted.single().source)

        // Hostile value under a key we do read: rejected, no exception escapes onReceive.
        val bad = fromOtherProcess(Bundle().apply {
            putString(BroadcastApi.EXTRA_TOKEN, settings.mcpToken)
            putSerializable(BroadcastApi.EXTRA_TASK, Boom())
        })
        r.onReceive(ctx, Intent(BroadcastApi.ACTION_RUN).putExtras(bad))
        val noToken = fromOtherProcess(Bundle().apply { putSerializable(BroadcastApi.EXTRA_TOKEN, Boom()) })
        r.onReceive(ctx, Intent(BroadcastApi.ACTION_RUN).putExtras(noToken))
        assertEquals(1, agent.submitted.size)

        // Same for the plugin receiver (hostile plugin bundle).
        val plugin = Intent(PluginProtocol.ACTION_FIRE_SETTING)
            .putExtra(PluginProtocol.EXTRA_BUNDLE, fromOtherProcess(Bundle().apply { putSerializable(PluginBundleCodec.K_VERSION, Boom()) }))
        LocaleFireReceiver().onReceive(ctx, plugin)
        assertEquals(1, agent.submitted.size)
    }

    @Test fun answersOnlyReachTheNamedWaitingTask() {
        val r = BroadcastApiReceiver()
        fun answer(taskId: String?) = r.onReceive(ctx, Intent(BroadcastApi.ACTION_ANSWER).putExtras(Bundle().apply {
            putString(BroadcastApi.EXTRA_TOKEN, settings.mcpToken)
            putString(BroadcastApi.EXTRA_ANSWER, "approve")
            if (taskId != null) putString(BroadcastApi.EXTRA_TASK_ID, taskId)
        }))
        answer("t1") // nothing waits
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Send?", true)))
        answer("other")
        assertTrue(agent.answers.isEmpty())
        answer("t1")
        answer(null)
        // a broadcast may answer questions but never approve a confirmation.
        assertTrue(agent.answers.isEmpty())
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "t1", status = TaskStatus.WAITING_FOR_USER,
            question = PendingQuestion("Which one?", false)))
        answer("t1")
        answer(null)
        assertEquals(listOf("approve", "approve"), agent.answers)
    }

    private fun signedPluginIntent(config: PluginConfig, completion: String? = null) =
        Intent(PluginProtocol.ACTION_FIRE_SETTING)
            .putExtra(PluginProtocol.EXTRA_BUNDLE, PluginBundleCodec.encode(config, settings.pluginSigningSecret).toBundle())
            .apply { if (completion != null) putExtra(PluginProtocol.EXTRA_COMPLETION_INTENT, completion) }

    /** Sends [intent] as an ordered broadcast (delivered to the manifest-declared [LocaleFireReceiver]); returns (code, extras). */
    private fun ordered(intent: Intent): Pair<Int, Bundle?> {
        var code = Int.MIN_VALUE
        var extras: Bundle? = null
        val result = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                code = resultCode
                extras = getResultExtras(false)
            }
        }
        ctx.sendOrderedBroadcast(intent, null, result, null, Activity.RESULT_CANCELED, null, null)
        shadowOf(Looper.getMainLooper()).idle()
        return code to extras
    }

    @Test fun pluginWithoutWaitingReturnsTheTaskIdVariableWithPercent() {
        val (code, extras) = ordered(signedPluginIntent(PluginConfig("Mute", waitForResult = false)))
        assertEquals(PluginProtocol.RESULT_CODE_OK, code)
        val vars = extras!!.getBundle(PluginProtocol.EXTRA_VARIABLES_BUNDLE)!!
        // Tasker only sets variables whose names keep the leading %.
        assertEquals("task1", vars.getString("%bs_task_id"))
        assertEquals(null, vars.getString("bs_task_id"))
        assertEquals(Source.TASKER, agent.submitted.single().source)
        assertEquals(5 * 60_000L, agent.submitted.single().timeoutMs)
    }

    @Test fun pluginWaitingDeliversVariablesThroughTheCompletionIntent() {
        // Tasker's completion intent names its own receiver (explicit); delivery is pinned to that package.
        val completion = "intent:#Intent;action=net.dinglisch.android.tasker.COMPLETED;component=net.dinglisch.android.taskerm/.PluginResultReceiver;end"
        val (code, _) = ordered(signedPluginIntent(PluginConfig("Mute", timeoutMin = 2), completion))
        assertEquals(PluginProtocol.RESULT_CODE_PENDING, code)
        assertEquals(2 * 60_000L, agent.submitted.single().timeoutMs)
        // The result is delivered from a background coroutine.
        val app = shadowOf(RuntimeEnvironment.getApplication())
        var done: Intent? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (done == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            done = app.broadcastIntents.firstOrNull { it.action == "net.dinglisch.android.tasker.COMPLETED" }
            if (done == null) Thread.sleep(20)
        }
        assertNotNull("completion intent sent", done)
        assertEquals("net.dinglisch.android.taskerm", done!!.`package`)
        assertEquals(PluginProtocol.RESULT_CODE_OK, done.getIntExtra(PluginProtocol.EXTRA_RESULT_CODE, 0))
        val vars = done.getBundleExtra(PluginProtocol.EXTRA_VARIABLES_BUNDLE)!!
        assertEquals("true", vars.getString("%success"))
        assertEquals("done", vars.getString("%result"))
    }

    /** a completion aimed at Backstage's own receivers (answers, schedule alarms) is never sent. */
    @Test fun completionIntentIntoThisAppIsNeverSent() {
        val app = shadowOf(RuntimeEnvironment.getApplication())
        val own = ctx.packageName
        for (completion in listOf(
            "intent:#Intent;action=com.eggman.backstage.notify.ANSWER;component=$own/com.eggman.backstage.notify.NotificationActionReceiver;S.task_id=t1;S.answer=approve;end",
            "intent:#Intent;action=com.eggman.backstage.schedule.FIRE;package=$own;end",
            // Implicit, and the sender is unknown: nobody to pin it to.
            "intent:#Intent;action=com.eggman.backstage.notify.ANSWER;end",
        )) {
            agent.submitted.clear()
            val (code, extras) = ordered(signedPluginIntent(PluginConfig("Mute", timeoutMin = 1), completion))
            // The task still runs; the caller gets its id at once instead of a pending result.
            assertEquals(completion, PluginProtocol.RESULT_CODE_OK, code)
            assertEquals("task1", extras!!.getBundle(PluginProtocol.EXTRA_VARIABLES_BUNDLE)!!.getString("%bs_task_id"))
        }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.broadcastIntents.none { it.action?.startsWith("com.eggman.backstage") == true })
    }

    /** a start while a task runs is refused at once (never queued behind it). */
    @Test fun startsWhileBusyAreRefusedImmediately() {
        agent.state.value = AgentState(current = Fixtures.snapshot(id = "chat1", status = TaskStatus.RUNNING))
        val (code, extras) = ordered(signedPluginIntent(PluginConfig("Mute", timeoutMin = 5)))
        assertEquals(PluginProtocol.RESULT_CODE_OK, code)
        val vars = extras!!.getBundle(PluginProtocol.EXTRA_VARIABLES_BUNDLE)!!
        assertEquals("false", vars.getString("%success"))
        assertEquals("busy", vars.getString("%error"))
        assertTrue(agent.submitted.isEmpty())

        BroadcastApiReceiver().onReceive(ctx, Intent(BroadcastApi.ACTION_RUN).putExtras(Bundle().apply {
            putString(BroadcastApi.EXTRA_TOKEN, settings.mcpToken)
            putString(BroadcastApi.EXTRA_TASK, "Mute the phone")
            putString(BroadcastApi.EXTRA_REQUEST_ID, "r9")
        }))
        assertTrue(agent.submitted.isEmpty())
        val result = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == BroadcastApi.ACTION_RESULT }
        assertEquals(BroadcastApi.STATUS_BUSY, result.getStringExtra(BroadcastApi.EXTRA_STATUS))
        assertEquals("busy", result.getStringExtra(BroadcastApi.EXTRA_ERROR))
        assertEquals("r9", result.getStringExtra(BroadcastApi.EXTRA_REQUEST_ID))
    }

    @Test fun forgedPluginBundleFailsAndRunsNothing() {
        val forged = PluginBundleCodec.encode(PluginConfig("Mute"), "not-this-phones-secret").toBundle()
        val (code, extras) = ordered(Intent(PluginProtocol.ACTION_FIRE_SETTING).putExtra(PluginProtocol.EXTRA_BUNDLE, forged))
        assertEquals(PluginProtocol.RESULT_CODE_FAILED, code)
        assertEquals("false", extras!!.getBundle(PluginProtocol.EXTRA_VARIABLES_BUNDLE)!!.getString("%success"))
        assertTrue(agent.submitted.isEmpty())
    }
}
