package com.eggman.backstage.surfaces

import android.app.NotificationManager
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.overlay.BubbleGeometry
import com.eggman.backstage.overlay.BubbleModel
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.ui.minichat.MiniChatActivity
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import org.junit.Assert.assertNull
import android.content.Context
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.overlay.BubbleService
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings

/** The bubble's intent protocol (window attach included; drawing is not checked). */
@RunWith(RobolectricTestRunner::class)
class BubbleServiceRobolectricTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var agent: FakeAgent

    @Before fun setUp() {
        SettingsStores.set(memorySettings())
        agent = FakeAgent()
        AgentAccess.override = agent
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun tearDown() {
        Overlay.setDismissed(ctx, false)
        AgentAccess.override = null
        SettingsStores.set(null)
    }

    @Test fun startTaskRunsOnlyNonBlankTasksWithTheirSourceAndTarget() {
        val blank = BubbleService.startTaskIntent(ctx, "   ", Source.WIDGET, autoHide = false)
        val service = Robolectric.buildService(BubbleService::class.java, blank).create().startCommand(0, 1)
        assertTrue(agent.submitted.isEmpty())
        service.withIntent(BubbleService.startTaskIntent(ctx, "Open maps", Source.WIDGET, autoHide = false, target = Target.DESKTOP))
            .startCommand(0, 2)
        assertEquals(listOf("Open maps"), agent.submitted.map { it.prompt })
        assertEquals(Source.WIDGET, agent.submitted.single().source)
        assertEquals(Target.DESKTOP, agent.submitted.single().target)
        assertTrue(BubbleService.running)
        service.destroy()
        assertFalse(BubbleService.running)
    }

    @Test fun hideTurnsTheBubbleSettingOff() {
        SettingsStores.get(ctx).bubbleEnabled = true
        val show = android.content.Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW)
        val service = Robolectric.buildService(BubbleService::class.java, show).create().startCommand(0, 1)
        assertTrue(SettingsStores.get(ctx).bubbleEnabled)
        service.withIntent(android.content.Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_HIDE)).startCommand(0, 2)
        assertFalse(SettingsStores.get(ctx).bubbleEnabled)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
    }

    private fun show(): org.robolectric.android.controller.ServiceController<BubbleService> {
        val show = android.content.Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW)
        return Robolectric.buildService(BubbleService::class.java, show).create().startCommand(0, 1)
    }

    private fun touch(service: BubbleService, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, SystemClock.uptimeMillis(), action, x, y, 0)
        service.onTouch(e)
        e.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun tapOpensTheSameChatAsThePopupLabelledBubble() {
        val service = show()
        val bubble = service.get()
        val o = bubble.origin()
        touch(bubble, MotionEvent.ACTION_DOWN, o.x + 20f, o.y + 20f)
        touch(bubble, MotionEvent.ACTION_UP, o.x + 22f, o.y + 21f)
        val opened = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(MiniChatActivity::class.java.name, opened.component!!.className)
        assertEquals(MiniChatActivity.ORIGIN_BUBBLE, opened.getStringExtra(MiniChatActivity.EXTRA_ORIGIN))
        assertNull(opened.getStringExtra(MiniChatActivity.EXTRA_VOICE))
        assertFalse(opened.getBooleanExtra(MiniChatActivity.EXTRA_PEEK, true))
        // The bubble stays (it launches the chat; it does not turn into it).
        assertFalse(shadowOf(bubble).isStoppedBySelf)
        service.destroy()
    }

    @Test fun longPressSpeaksATask() {
        val service = show()
        val bubble = service.get()
        val o = bubble.origin()
        touch(bubble, MotionEvent.ACTION_DOWN, o.x + 20f, o.y + 20f)
        idle(ViewConfiguration.getLongPressTimeout() + 100L)
        touch(bubble, MotionEvent.ACTION_UP, o.x + 20f, o.y + 20f)
        val opened = shadowOf(ctx as android.app.Application).nextStartedActivity
        assertEquals(MiniChatActivity.VOICE_SEND, opened.getStringExtra(MiniChatActivity.EXTRA_VOICE))
        // The release after a long-press is not also a tap.
        assertNull(shadowOf(ctx as android.app.Application).nextStartedActivity)
        service.destroy()
    }

    @Test fun droppingOnTheRemoveTargetHidesTheBubbleButKeepsTheSetting() {
        SettingsStores.get(ctx).bubbleEnabled = true
        val service = show()
        val bubble = service.get()
        val size = bubble.bubbleSizePx()
        val o = bubble.origin()
        val t = bubble.removeTarget()
        val startX = o.x + size / 2f
        val startY = o.y + size / 2f
        touch(bubble, MotionEvent.ACTION_DOWN, startX, startY)
        // Halfway: follows the finger, not on the target.
        touch(bubble, MotionEvent.ACTION_MOVE, (startX + t.cx) / 2, (startY + t.cy) / 2)
        assertFalse(bubble.controller.overTarget.value)
        // Near the target (within the pull, not exactly on it): pulled onto its centre.
        touch(bubble, MotionEvent.ACTION_MOVE, t.cx + 30f, t.cy - 30f)
        assertTrue(bubble.controller.overTarget.value)
        assertEquals(BubbleGeometry.onTarget(t, size), bubble.origin())
        touch(bubble, MotionEvent.ACTION_UP, t.cx + 30f, t.cy - 30f)
        assertTrue(shadowOf(bubble).isStoppedBySelf)
        // The setting stays on; the bubble stays away (also when the app refreshes it) until shown again.
        assertTrue(SettingsStores.get(ctx).bubbleEnabled)
        assertTrue(Overlay.dismissed(ctx))
        assertEquals(BubbleModel.HIDDEN_TOAST, ShadowToast.getTextOfLatestToast())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val note = shadowOf(nm).getNotification(Notifications.ID_BUBBLE)
        assertEquals(BubbleModel.HIDDEN_TITLE, note.extras.getString(android.app.Notification.EXTRA_TITLE))
        service.destroy()
        val app = shadowOf(ctx as android.app.Application)
        while (app.nextStartedService != null) Unit
        Overlay.refresh(ctx)
        assertNull(app.nextStartedService)
        // The notification's "show again" (ACTION_SHOW) brings it back and forgets the drag.
        val again = show()
        assertFalse(Overlay.dismissed(ctx))
        assertTrue(BubbleService.running)
        again.destroy()
        // So does Settings' "Show it again".
        Overlay.setDismissed(ctx, true)
        Overlay.show(ctx)
        assertFalse(Overlay.dismissed(ctx))
        assertEquals(BubbleService::class.java.name, app.nextStartedService.component!!.className)
    }

    @Test fun releasedElsewhereItSlidesToTheNearerSide() {
        val service = show()
        val bubble = service.get()
        val size = bubble.bubbleSizePx()
        val o = bubble.origin()
        val width = ctx.resources.displayMetrics.widthPixels
        touch(bubble, MotionEvent.ACTION_DOWN, o.x + 10f, o.y + 10f)
        // To a bit left of the middle, well above the target.
        val x = width / 2f - size
        touch(bubble, MotionEvent.ACTION_MOVE, x, o.y + 10f)
        touch(bubble, MotionEvent.ACTION_UP, x, o.y + 10f)
        assertFalse(shadowOf(bubble).isStoppedBySelf)
        idle(1_000)
        // Left edge (4 dp margin), same height.
        val margin = (4 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(bubble.origin().toString(), bubble.origin().x <= margin + 1)
        assertEquals(o.y, bubble.origin().y)
        service.destroy()
    }
}
