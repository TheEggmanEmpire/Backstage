package com.eggman.backstage.robolectric

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.notify.NotificationActionReceiver
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.notify.SessionNotification
import com.eggman.backstage.mcp.McpServerStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** The agent's foreground service: declaration, notification per state, Stop, MCP lifetime, self-shutdown. */
@RunWith(RobolectricTestRunner::class)
class AgentServiceRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<AgentService>? = null
    private var llm: LlmBlackHole? = null

    @Before fun setUp() {
        com.eggman.backstage.agent.AgentService.laneOverride = java.util.concurrent.Executor { it.run() }
        RoboSupport.resetProcess()
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        Backstage.init(app)
    }

    @After fun tearDown() {
        com.eggman.backstage.agent.AgentService.laneOverride = null
        runCatching { Backstage.controller.cancelAll() }
        service?.let { runCatching { it.destroy() } }
        llm?.close()
        RoboSupport.resetProcess()
    }

    private val settings get() = Backstage.environment.settings

    private fun start(action: String? = null): ServiceController<AgentService> {
        val intent = Intent(app, AgentService::class.java).apply { action?.let(::setAction) }
        val c = service?.withIntent(intent) ?: Robolectric.buildService(AgentService::class.java, intent).create().also { service = it }
        c.startCommand(0, 1)
        return c
    }

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** The service's notification as NotificationManager holds it once the coalesced state update has run. */
    private fun posted(): Notification? {
        RoboSupport.advanceMain(300)
        return shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.NOTIFICATION_ID)
    }

    private fun portOpen(port: Int) = RoboSupport.listening(port)

    @Test fun declaredAsPrivateSpecialUseForegroundService() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, AgentService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        listOf(Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { assertTrue(it, it in requested) }
    }

    @Test fun idleStartPostsTheRequiredNotificationThenStops() {
        val c = start()
        val sh = shadowOf(c.get())
        // startForegroundService obliges startForeground, even when there is nothing to do; with nothing to do the
        // service then leaves the foreground and stops; with no desktop and no task the session card goes away.
        assertEquals(AgentService.NOTIFICATION_ID, sh.lastForegroundNotificationId)
        assertTrue(sh.isForegroundStopped)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.NOTIFICATION_ID))
        assertTrue(sh.isStoppedBySelf)
    }

    @Test fun mcpServerLivesExactlyAsLongAsTheSetting() {
        val port = RoboSupport.freePort()
        settings.mcpPort = port
        settings.mcpEnabled = true
        val c = start()
        val sh = shadowOf(c.get())
        assertFalse(sh.isStoppedBySelf)
        assertTrue(portOpen(port))
        assertEquals(AgentService.NOTIFICATION_ID, sh.lastForegroundNotificationId)
        val n = posted()!!
        assertEquals(SessionNotification.CHANNEL, n.channelId)
        assertEquals("Backstage", n.title)
        assertTrue(n.text, n.text!!.contains("MCP server: http://127.0.0.1:$port/mcp (this phone only"))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)

        // Restart (e.g. after a port change in Settings): the server comes back on the new port.
        val newPort = RoboSupport.freePort()
        settings.mcpPort = newPort
        start(AgentService.ACTION_RESTART_MCP)
        assertTrue(portOpen(newPort))
        assertTrue(RoboSupport.portReleased(port))
        assertFalse(sh.isStoppedBySelf)

        // Restarts on the SAME port (token rotation, Retry): every one comes back at once. Before the hardening about
        // 1 in 10 failed with "address in use" because stop returned while the accept thread still held the listener.
        repeat(15) { round ->
            start(AgentService.ACTION_RESTART_MCP)
            assertTrue("round $round", portOpen(newPort))
            assertEquals("round $round", null, McpServerStatus.state.value.error)
            assertTrue("round $round", McpServerStatus.state.value.running)
        }
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.MCP_ERROR_NOTIFICATION_ID))

        // Switched off: the next refresh closes the port and the idle service goes away.
        settings.mcpEnabled = false
        start()
        assertTrue(RoboSupport.portReleased(newPort))
        assertTrue(sh.isStoppedBySelf)
        assertFalse(McpServerStatus.state.value.running)
    }

    /** What the settings write turned into: the service start it requested (null = none). */
    private fun requestedStart(): Intent? = shadowOf(app).nextStartedService?.also {
        assertEquals(AgentService::class.java.name, it.component?.className)
    }

    private fun deliver(intent: Intent): ServiceController<AgentService> {
        val c = service?.withIntent(intent) ?: Robolectric.buildService(AgentService::class.java, intent).create().also { service = it }
        c.startCommand(0, 1)
        return c
    }

    @Test fun settingChangesStartRebindAndStopTheServerAtOnce() {
        shadowOf(app).clearStartedServices()
        // Off and not failing: changing the port alone needs no service.
        val port = RoboSupport.freePort()
        settings.mcpPort = port
        assertNull(requestedStart())

        // Enabling starts the server right away (before: only at the next task or process start).
        settings.mcpEnabled = true
        val sh = shadowOf(deliver(requestedStart() ?: throw AssertionError("enabling requested no service start")).get())
        assertTrue(portOpen(port))
        assertFalse(sh.isStoppedBySelf)

        // A new port rebinds: the old one is released, the new one listens.
        val newPort = RoboSupport.freePort()
        settings.mcpPort = newPort
        deliver(requestedStart() ?: throw AssertionError("port change requested no service start"))
        assertTrue(portOpen(newPort))
        assertTrue(RoboSupport.portReleased(port))
        assertEquals(newPort, McpServerStatus.state.value.port)

        // The LAN choice rebinds too (all interfaces <-> loopback only).
        settings.mcpLan = true
        deliver(requestedStart() ?: throw AssertionError("LAN change requested no service start"))
        assertTrue(McpServerStatus.state.value.lan)
        assertTrue(portOpen(newPort))
        settings.mcpLan = false
        deliver(requestedStart() ?: throw AssertionError("LAN change requested no service start"))
        assertFalse(McpServerStatus.state.value.lan)
        assertTrue(portOpen(newPort))

        // Disabling stops it and the idle service goes away (before: the server kept listening and accepting the token).
        settings.mcpEnabled = false
        deliver(requestedStart() ?: throw AssertionError("disabling requested no service start"))
        assertTrue(RoboSupport.portReleased(newPort))
        assertTrue(sh.isStoppedBySelf)
        assertFalse(McpServerStatus.state.value.running)
    }

    @Test fun portInUseLeavesAVisibleErrorAndRetryRecovers() {
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val taken = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = taken.localPort
        try {
            settings.mcpPort = port
            settings.mcpEnabled = true
            val c = start()
            val sh = shadowOf(c.get())
            // Nothing to serve: the idle service still leaves the foreground and stops (no crash, no retry loop)...
            assertTrue(sh.isStoppedBySelf)
            assertTrue(sh.isForegroundStopped)
            assertFalse(taken.isClosed)
            assertTrue(portOpen(port))
            //...but the failure stays visible: its own notification (own id and channel) outlives the service.
            val err = nm.getNotification(AgentService.MCP_ERROR_NOTIFICATION_ID) ?: throw AssertionError("no error notification")
            assertEquals(Backstage.CHANNEL_MCP_ERROR, err.channelId)
            assertEquals(AgentService.MCP_ERROR_TITLE, err.title)
            assertTrue(err.text, err.text!!.contains("listens on port $port."))
            val retry = err.actions?.singleOrNull { it.title == "Retry" } ?: throw AssertionError("no Retry action")
            assertEquals(AgentService.ACTION_RESTART_MCP, shadowOf(retry.actionIntent).savedIntent.action)
            assertTrue(shadowOf(retry.actionIntent).isForegroundService)
            assertTrue(shadowOf(retry.actionIntent).isImmutable)
            //...and the settings page can show it.
            assertFalse(McpServerStatus.state.value.running)
            assertTrue(McpServerStatus.state.value.error!!.contains("listens on port $port."))
        } finally {
            taken.close()
        }
        // The other app let go of the port; the user taps Retry: the server binds and the error goes away.
        start(AgentService.ACTION_RESTART_MCP)
        assertTrue(portOpen(port))
        assertNull(nm.getNotification(AgentService.MCP_ERROR_NOTIFICATION_ID))
        assertNull(McpServerStatus.state.value.error)
        assertTrue(McpServerStatus.state.value.running)
    }

    @Test fun disablingClearsAPendingBindError() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { taken ->
            settings.mcpPort = taken.localPort
            settings.mcpEnabled = true
            start()
            assertTrue(McpServerStatus.state.value.error != null)
            shadowOf(app).clearStartedServices()
            // Off while failing: the service is asked once more, so the error notification and status are cleared.
            settings.mcpEnabled = false
            deliver(requestedStart() ?: throw AssertionError("disabling a failing server requested no service start"))
            assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.MCP_ERROR_NOTIFICATION_ID))
            assertNull(McpServerStatus.state.value.error)
            assertFalse(taken.isClosed)
        }
    }

    @Test fun runningTaskShowsProgressAndStopCancelsIt() {
        llm = LlmBlackHole().also { it.install(settings) }
        val handle = Backstage.controller.submit(TaskRequest("Water the plants reminder", Source.CHAT))
        assertTrue("task reached the model", RoboSupport.waitFor(10_000) { llm!!.requests > 0 })
        assertEquals(TaskStatus.RUNNING, Backstage.controller.state.value.current?.status)
        // The controller asked for the service itself (busy -> refresh).
        assertEquals(AgentService::class.java.name, shadowOf(app).nextStartedService?.component?.className)

        val c = start()
        val sh = shadowOf(c.get())
        val n = sh.lastForegroundNotification
        assertEquals(SessionNotification.CHANNEL, n.channelId)
        assertEquals(n.title, posted()?.title)
        assertEquals("Backstage is working", n.title)
        assertTrue(n.text, n.text!!.contains("Water the plants"))
        val stop = n.actions?.singleOrNull { it.title == "Stop" } ?: throw AssertionError("no Stop action")
        val stopIntent = shadowOf(stop.actionIntent).savedIntent
        assertEquals(Notifications.ACTION_STOP, stopIntent.action)
        assertEquals(NotificationActionReceiver::class.java.name, stopIntent.component?.className)
        assertTrue(shadowOf(stop.actionIntent).isImmutable)
        assertFalse(sh.isStoppedBySelf)

        // Tapping Stop: the task is cancelled, the notification settles, the service stops itself.
        start(AgentService.ACTION_STOP)
        assertTrue(RoboSupport.waitFor(10_000) { Backstage.controller.state.value.current?.status == TaskStatus.CANCELLED })
        assertEquals(handle.id, Backstage.controller.state.value.current?.id)
        val deadline = System.currentTimeMillis() + 5_000
        while (!sh.isStoppedBySelf && System.currentTimeMillis() < deadline) {
            RoboSupport.advanceMain(300)
            Thread.sleep(20)
        }
        assertTrue(sh.isStoppedBySelf)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.NOTIFICATION_ID))
    }
}
