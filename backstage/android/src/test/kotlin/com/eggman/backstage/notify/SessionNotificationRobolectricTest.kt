package com.eggman.backstage.notify

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.robolectric.RoboSupport
import com.eggman.backstage.surfaces.FakeAgent
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.ui.Fixtures.snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The one session notification: shown while the desktop or a task runs, inline reply, popup toggle, opens the app. */
@RunWith(RobolectricTestRunner::class)
class SessionNotificationRobolectricTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private lateinit var host: FakeDesktopHost
    private lateinit var agent: FakeAgent

    @Before fun setUp() {
        host = FakeDesktopHost(active = false)
        agent = FakeAgent()
        AgentAccess.hostOverride = host
        AgentAccess.override = agent
        nm.cancelAll()
    }

    @After fun tearDown() {
        AgentAccess.hostOverride = null
        AgentAccess.override = null
        SessionNotification.mcpLine = null
        nm.cancelAll()
    }

    private fun posted(): Notification? {
        SessionNotification.renderNow(ctx)
        return shadowOf(nm).getNotification(SessionNotification.ID)
    }

    @Test fun shownExactlyWhileTheDesktopOrATaskRuns() {
        assertNull(posted())
        host.active = true
        host.presentation = Presentation.HIDDEN
        val n = posted()!!
        assertEquals(SessionNotification.CHANNEL, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        host.active = false
        assertNull(posted())
        agent.state.value = AgentState(current = snapshot())
        assertEquals("Backstage is working", posted()!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test fun replyIsTheOnlyMutableActionAndEveryActingActionNeedsAnUnlock() {
        host.active = true
        host.presentation = Presentation.MINI
        val n = posted()!!
        val labels = n.actions.map { it.title.toString() }
        assertEquals(listOf("Message the agent", "Stop desktop", "Background"), labels)
        n.actions.forEach { action ->
            assertTrue(action.title.toString(), action.isAuthenticationRequired)
            val mutable = shadowOf(action.actionIntent).flags and PendingIntent.FLAG_MUTABLE != 0
            assertEquals(action.title.toString(), action.remoteInputs?.isNotEmpty() == true, mutable)
            assertEquals(ctx.packageName, shadowOf(action.actionIntent).savedIntent.`package`)
            assertNotNull(shadowOf(action.actionIntent).savedIntent.component)
        }
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
    }

    private fun reply(text: String, model: SessionNotificationModel = SessionNotification.model()) {
        val intent = shadowOf(SessionNotification.messageIntent(ctx, model)).savedIntent
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(SessionNotification.REMOTE_INPUT_KEY).build()), intent,
            Bundle().apply { putCharSequence(SessionNotification.REMOTE_INPUT_KEY, text) },
        )
        NotificationActionReceiver().onReceive(ctx, intent)
    }

    @Test fun replyStartsATaskWhenIdleAndInstructsARunningOne() {
        host.active = true
        reply("open the calendar")
        assertEquals(1, agent.submitted.size)
        assertEquals(Source.NOTIFICATION, agent.submitted[0].source)
        assertEquals("open the calendar", agent.submitted[0].waiverText)
        agent.state.value = AgentState(current = snapshot(id = "t9"))
        reply("use Firefox")
        assertEquals(listOf<Pair<String?, String>>("t9" to "use Firefox"), agent.instructed)
        // Re-posted, never cancelled: the reply spinner ends and the card stays.
        assertNotNull(shadowOf(nm).getNotification(SessionNotification.ID))
    }

    @Test fun replyIsIgnoredWhileLocked() {
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        reply("open the calendar")
        assertTrue(agent.submitted.isEmpty())
    }

    @Test fun popupButtonBringsTheDesktopBack() {
        host.active = true
        host.presentation = Presentation.HIDDEN
        val popup = posted()!!.actions.first { it.title.toString() == "Popup" }
        NotificationActionReceiver().onReceive(ctx, shadowOf(popup.actionIntent).savedIntent)
        assertTrue(RoboSupport.waitFor { "present MINI" in host.calls })
        assertFalse("present HIDDEN" in host.calls)
    }
}
