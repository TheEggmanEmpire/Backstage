package com.eggman.backstage.robolectric

import android.os.Looper
import com.eggman.backstage.agent.api.Agents
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.automation.AutomationRunner
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.mcp.McpServerState
import com.eggman.backstage.mcp.McpServerStatus
import com.eggman.backstage.schedule.Schedules
import com.eggman.backstage.ui.settings.SettingsStores
import org.robolectric.Shadows.shadowOf
import java.net.ServerSocket
import java.time.Duration

/**
 * Shared plumbing for the Robolectric suite. Robolectric keeps one sandbox class loader per SDK, so process-wide
 * singletons ([Backstage], [Agents], [DesktopHosts], [AgentAccess]) survive from one test (and test class) to the
 * next; every test that touches them starts and ends with [resetProcess].
 */
object RoboSupport {
    private const val BRIDGE = "moe.n4tsu.dextop.BackstageDesktopBridge"

    fun resetProcess() {
        if (Backstage.isInitialized) runCatching {
            val c = Backstage.controller
            c.cancelAll()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && c.state.value.let { s ->
                    s.queueSize > 0 || s.current?.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING_FOR_USER)
                }) Thread.sleep(20)
            Thread.sleep(50) // the worker reports idle (AgentService.refresh) right after the last task ends
        }
        // Backstage.init runs once per process; tests re-run it against a fresh Application.
        Backstage::class.java.getDeclaredField("initialized").apply { isAccessible = true }.setBoolean(null, false)
        Backstage.bridgeClassName = BRIDGE
        // lateinit: null = "not installed", which AgentAccess.controller reports as null (no core).
        Agents::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(null, null)
        DesktopHosts.current = null
        AgentAccess.override = null
        AgentAccess.hostOverride = null
        SettingsStores.set(null)
        // the composer draft and the chat threads are process-wide too.
        com.eggman.backstage.ui.ComposerDrafts.set(null)
        com.eggman.backstage.data.ChatThreads.set(null)
        // Rejected-automation notices are rate-limited per minute of uptime; Robolectric's clock restarts every test.
        AutomationRunner::class.java.getDeclaredField("lastAuthNoticeAt").apply { isAccessible = true }.setLong(AutomationRunner, -3_600_000L)
        // The schedule engine caches the first Application's preferences and AlarmManager.
        Schedules::class.java.getDeclaredField("engine").apply { isAccessible = true }.set(null, null)
        // Whatever the last test's MCP server reported (running, or a bind error) belongs to that test.
        McpServerStatus.publish(McpServerState())
    }

    /** A loopback port that is free (the MCP port setting must be 1024..65535, so 0 is not an option). */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Whether something listens on loopback [port]. A connection that the kernel made to itself (TCP self-connect,
     * possible when the probed port lies in the ephemeral range and nothing listens) does not count.
   */
    fun listening(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
            s.localPort != s.port
        }
    }.getOrDefault(false)

    /**
     * Waits until nothing listens on [port]. ServerSocket.close returns before the kernel listener is gone while
     * another thread is still blocked in accept (JDK NIO socket impl), so a closed server can answer for a moment.
   */
    fun portReleased(port: Int, timeoutMs: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!listening(port)) return true
            Thread.sleep(25)
        }
        return !listening(port)
    }

    /**
     * Polls [condition], running the main looper in between (Robolectric's main looper is paused: posted work and
     * Dispatchers.Main only run when idled). Background dispatchers run on real threads.
   */
    fun waitFor(timeoutMs: Long = 5_000, stepMs: Long = 20, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(stepMs)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    /** Advances the paused main looper's clock (delays posted with Handler.postDelayed / Dispatchers.Main). */
    fun advanceMain(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
