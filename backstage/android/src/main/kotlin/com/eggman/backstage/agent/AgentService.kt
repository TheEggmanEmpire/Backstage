package com.eggman.backstage.agent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.eggman.backstage.R
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.mcp.BackstageMcpTools
import com.eggman.backstage.mcp.McpHttpServer
import com.eggman.backstage.mcp.McpProtocol
import com.eggman.backstage.mcp.McpServerState
import com.eggman.backstage.mcp.McpServerStatus
import com.eggman.backstage.notify.SessionNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * Foreground service (type specialUse) that keeps the process alive while the agent runs tasks
 * and while the MCP server is enabled. Its notification shows progress and a Stop button.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    @Volatile private var mcp: McpHttpServer? = null
    @Volatile private var mcpError: String? = null
    @Volatile private var destroyed = false
    private var mcpSyncPending = 0 // main-thread lifecycle accounting
    private val mcpLane = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Backstage-MCP-lifecycle").apply { isDaemon = true }
    }
    /** Address shown for the MCP server; looked up when the server starts, never per state change. */
    @Volatile private var mcpAddress: String = "127.0.0.1"
    private val launchIntent by lazy { packageManager.getLaunchIntentForPackage(packageName) }
    /** Id of the newest start delivered to [onStartCommand]; [shutdown] stops only for that one. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!Backstage.isInitialized) Backstage.init(applicationContext)
        val controller = Backstage.controller
        // startForegroundService obliges startForeground on every start.
        startForegroundCompat(notification(controller.state.value))
        when (intent?.action) {
            ACTION_STOP -> controller.cancel(null)
            ACTION_STOP_ALL -> controller.cancelAll()
            ACTION_STOP_MCP -> {
                // The notification's "Stop MCP server": switch the server off for good (the setting), not just
                // until the next refresh; syncMcp below stops it.
                Backstage.environment.settings.mcpEnabled = false
            }
        }
        syncMcp(forceRestart = intent?.action == ACTION_RESTART_MCP)
        if (observer == null) {
            observer = scope.launch {
                controller.state.collectLatest { s ->
                    // Steps arrive in bursts: coalesce them (collectLatest drops superseded states), so the
                    // notification is not rebuilt faster than NotificationManager accepts and the final state wins.
                    delay(NOTIFY_COALESCE_MS)
                    update(s)
                    if (!busy(s) && mcp == null && mcpSyncPending == 0 && !paused) shutdown()
                }
            }
        }
        if (!busy(controller.state.value) && mcp == null && mcpSyncPending == 0 && !paused) shutdown()
        return START_NOT_STICKY
    }

    private fun busy(s: AgentState) = s.busy

    /**
     * Brings the MCP server in line with the settings: started when enabled, stopped when disabled, and restarted
     * when the port or the LAN setting differs from the running server's (or on [forceRestart], e.g. Retry). A failed
     * start leaves a notification of its own that outlives this service (see [postMcpError]).
   */
    private fun syncMcp(forceRestart: Boolean = false) {
        mcpSyncPending++
        (laneOverride ?: mcpLane).execute {
            try {
                if (!destroyed) syncMcpBlocking(forceRestart)
            } finally {
                scope.launch {
                    mcpSyncPending--
                    val state = Backstage.controller.state.value
                    update(state)
                    if (!busy(state) && mcp == null && mcpSyncPending == 0 && !paused) shutdown()
                }
            }
        }
    }

    /** One background lane preserves stop -> bind ordering, including repeated refreshes. */
    private fun syncMcpBlocking(forceRestart: Boolean) {
        val settings = Backstage.environment.settings
        if (!settings.mcpEnabled) {
            stopMcp()
            clearMcpError()
            McpServerStatus.publish(McpServerState())
            return
        }
        val port = settings.mcpPort
        val lan = settings.mcpLan
        val cur = mcp
        if (cur != null && !forceRestart && cur.running && cur.port == port && cur.lan == lan) return
        // Only right after stopping our own server can the port still be held for a moment: retry briefly then.
        // A port another app holds fails at once; bind retries run on the lifecycle lane.
        val restarting = cur != null
        stopMcp()
        val env = Backstage.environment
        val protocol = McpProtocol(BackstageMcpTools({ Backstage.controller }, env, wake = { env.wakeIfNeeded() }), serverVersion = versionName())
        val server = McpHttpServer(port, lan, { settings.mcpToken }, protocol,
            bindRetryDelaysMs = if (restarting) RESTART_BIND_RETRY_MS else emptyList())
        try {
            server.start()
            mcp = server
            mcpAddress = if (lan) lanAddress() ?: "127.0.0.1" else "127.0.0.1"
            clearMcpError()
            McpServerStatus.publish(McpServerState(running = true, port = server.boundPort, lan = lan))
        } catch (t: Throwable) {
            Log.w(TAG, "MCP server failed to start", t)
            runCatching { server.stop() }
            val message = McpServerStatus.startError(port, t)
            mcpError = message
            postMcpError(message)
            McpServerStatus.publish(McpServerState(running = false, port = port, lan = lan, error = message))
        }
    }

    /**
     * Bind failures get their own notification (own id and channel, not the foreground one), so it stays visible after
     * an idle service stops itself, with Retry and a tap that opens the app to change the port.
   */
    private fun postMcpError(message: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = launchIntent?.let { PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val retry = PendingIntent.getForegroundService(this, 2, Intent(this, AgentService::class.java).setAction(ACTION_RESTART_MCP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, Backstage.CHANNEL_MCP_ERROR)
            .setSmallIcon(R.drawable.bs_ic_agent)
            .setContentTitle(MCP_ERROR_TITLE)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Retry", retry).build())
        open?.let { b.setContentIntent(it) }
        runCatching { nm.notify(MCP_ERROR_NOTIFICATION_ID, b.build()) }
    }

    private fun clearMcpError() {
        mcpError = null
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(MCP_ERROR_NOTIFICATION_ID) }
    }

    private fun stopMcp() {
        mcp?.stop()
        mcp = null
    }

    /**
     * Stops only when no newer start is pending: a startForegroundService from refresh that arrives
     * while we shut down must reach onStartCommand (which calls startForeground) instead of being torn down with the
     * service, which would crash the process ("did not then call startForeground") and the desktop with it.
   */
    private fun shutdown() {
        // The card stays while the desktop runs; the session notification removes it when nothing is left to show.
        SessionNotification.mcpLine = null
        stopForeground(STOP_FOREGROUND_DETACH)
        SessionNotification.renderNow(this)
        if (!stopSelfResult(lastStartId)) Log.i(TAG, "newer start pending; staying up")
    }

    override fun onDestroy() {
        destroyed = true
        (laneOverride ?: mcpLane).execute {
            stopMcp()
            McpServerStatus.publish(McpServerStatus.state.value.copy(running = false))
        }
        mcpLane.shutdown()
        scope.cancel()
        observer = null
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        runCatching { startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun update(s: AgentState) {
        notification(s)
        // Already coalesced above (NOTIFY_COALESCE_MS).
        SessionNotification.renderNow(this)
    }

    private fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"

    /**
     * The foreground notification IS the session notification (same id): one card shows the task, the desktop and the
     * MCP server. The MCP line is handed over first.
   */
    private fun notification(@Suppress("UNUSED_PARAMETER") s: AgentState): Notification {
        SessionNotification.mcpLine = mcp?.let { mcpUrlLine(mcpAddress, it.boundPort, it.lan) } ?: mcpError
        return SessionNotification.build(this, SessionNotification.model(s))
    }

    companion object {
        private const val TAG = "AgentService"

        /** Tests only: runs the MCP lifecycle lane on this executor (e.g. inline) instead of its own thread. */
        @Volatile internal var laneOverride: java.util.concurrent.Executor? = null

        /**
         * A detector pause holds the process while the accessibility service is off (DetectorPause), so its re-enable
         * tick survives the unbind; the notification stays up meanwhile.
       */
        @Volatile var paused = false
        const val NOTIFICATION_ID = SessionNotification.ID
        /** The "MCP server could not start" notification (channel [Backstage.CHANNEL_MCP_ERROR]). */
        const val MCP_ERROR_NOTIFICATION_ID = 0xBA58
        const val MCP_ERROR_TITLE = "MCP server is not running"
        /** Bind retries after stopping our own server (a restart on the same port). */
        private val RESTART_BIND_RETRY_MS = listOf(50L, 100L, 200L)
        const val ACTION_STOP = "com.eggman.backstage.action.STOP_CURRENT"
        const val ACTION_STOP_ALL = "com.eggman.backstage.action.STOP_ALL"
        const val ACTION_RESTART_MCP = "com.eggman.backstage.action.RESTART_MCP"
        const val ACTION_STOP_MCP = "com.eggman.backstage.action.STOP_MCP"
        const val STOP_MCP_LABEL = "Stop MCP server"
        private const val NOTIFY_COALESCE_MS = 250L

        /** Loopback-only servers are reached through `adb forward`, so the notification says so. */
        fun mcpUrlLine(address: String, port: Int, lan: Boolean): String =
            if (lan) "MCP server: http://$address:$port/mcp"
            else "MCP server: http://127.0.0.1:$port/mcp (this phone only; from a computer: adb forward tcp:$port tcp:$port)"

        fun restartMcp(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AgentService::class.java).setAction(ACTION_RESTART_MCP)) }
                .onFailure { Log.i(TAG, "foreground service start refused: ${it.message}") }
        }

        /**
         * A setting in [com.eggman.backstage.data.Settings.MCP_SERVER_KEYS] changed (enable, port, LAN): let the service
         * start, rebind or stop the server. Nothing to do while the server is off and is not failing.
       */
        fun mcpSettingsChanged(context: Context, enabled: Boolean) {
            val st = McpServerStatus.state.value
            if (enabled || st.running || st.error != null) refresh(context)
        }

        /** Re-evaluate whether the service should run (task activity or MCP enabled). */
        fun refresh(context: Context) {
            // Android 12+ refuses FGS starts from the background (e.g. a Tasker broadcast) unless an
            // exemption applies; the enabled accessibility service grants one, otherwise the task still
            // runs without the notification.
            runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
                .onFailure { Log.i(TAG, "foreground service start refused: ${it.message}") }
        }

        /** LAN IPv4 shown to MCP clients: private and CGNAT/VPN ranges preferred, else the first IPv4. */
        fun lanAddress(): String? = runCatching {
            val all = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
            preferredAddress(all)
        }.getOrNull()

        fun preferredAddress(addresses: List<String>): String? =
            addresses.firstOrNull { a -> listOf("192.168.", "10.", "172.", "100.").any { a.startsWith(it) } } ?: addresses.firstOrNull()
    }
}
