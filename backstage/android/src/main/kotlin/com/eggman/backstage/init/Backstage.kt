package com.eggman.backstage.init

import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.eggman.backstage.a11y.AccessibilityAutoEnable
import com.eggman.backstage.a11y.NotificationFeed
import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.agent.AndroidAgentEnvironment
import com.eggman.backstage.agent.MiniStatusBridge
import com.eggman.backstage.agent.api.Agents
import com.eggman.backstage.data.Settings
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.privileged.PrivilegedSetup
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Process bootstrap for the:backstage library (there is no Application subclass: Dextop's app
 * uses the default one). Called once from [BackstageInitProvider].
 */
object Backstage {
    private const val TAG = "Backstage"
    private const val BRIDGE_CLASS = "moe.n4tsu.dextop.BackstageDesktopBridge"

    const val CHANNEL_AGENT = "backstage_agent"
    const val CHANNEL_MCP = "backstage_mcp"
    const val CHANNEL_ATTENTION = "backstage_attention"
    const val CHANNEL_MCP_ERROR = "backstage_mcp_error"

    @Volatile private var initialized = false
    lateinit var context: Context
        private set
    lateinit var environment: AndroidAgentEnvironment
        private set
    lateinit var controller: AgentControllerImpl
        private set

    val isInitialized: Boolean get() = initialized

    /** Glue class looked up by [init]; tests point it at a fake bridge (Robolectric bootstrap test). */
    @Volatile internal var bridgeClassName: String = BRIDGE_CLASS

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return
        context = appContext.applicationContext ?: appContext
        // IInputManager / IDisplayManager reflection needs hidden-API exemptions (same as Dextop).
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }.onFailure { Log.w(TAG, "hidden api bypass failed", it) }
        runCatching { createChannels(context) }.onFailure { Log.w(TAG, "notification channels", it) }
        environment = AndroidAgentEnvironment(context)
        environment.settings.appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
        controller = AgentControllerImpl(environment)
        Agents.controller = controller
        NotificationFeed.ignoredPackages = NotificationFeed.ignoredPackages + context.packageName
        bridge = installDesktopBridge(context, bridgeClassName)
        DesktopHosts.current?.let { host ->
            runCatching { host.addListener(AccessibilityAutoEnable.desktopListener(context) { environment.settings.mayAutoEnableAccessibility }) }
        }
        // Locked mode: CPU and the desktop's own display group stay awake for tasks (AgentKeepAwake).
        runCatching { com.eggman.backstage.agent.AgentKeepAwake.install(context) }.onFailure { Log.w(TAG, "keep-awake", it) }
        // Agent status -> desktop mini window caption (no-op without the Dextop glue).
        MiniStatusBridge.start(controller.state)
        // Shizuku-first: once Shizuku permission is granted, make it Dextop's provider, switch on the
        // accessibility service and grant what the shell can (once; re-run from onboarding at any time).
        runCatching {
            PrivilegedSetup.installAutoSetup(
                context,
                shouldRun = { environment.settings.mayAutoEnableAccessibility && !environment.settings.privilegedSetupDone },
                // Once the accessibility service is on, never again automatically (respects a later manual
                // disable); grants a device refuses (e.g. an app-op it lacks) do not make it retry forever.
                onDone = { report -> if (report.accessibilityDone) environment.settings.privilegedSetupDone = true },
            )
        }.onFailure { Log.w(TAG, "Shizuku auto-setup unavailable", it) }
        // A detector pause interrupted by a process death: put the accessibility service back.
        runCatching { com.eggman.backstage.a11y.DetectorPause.recover(context) }
        initialized = true
        // MCP settings take effect at once, whoever writes them (settings page, store, restore): enabling starts the
        // server, disabling stops it, a new port or LAN choice rebinds it.
        val settings = environment.settings
        settings.observe { key -> if (key in Settings.MCP_SERVER_KEYS) AgentService.mcpSettingsChanged(context, settings.mcpEnabled) }
        com.eggman.backstage.omp.OmpHarness.initialize(context, settings)
        // The selected model's real context window from the provider's model list (network, off the main thread).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            com.eggman.backstage.llm.ContextDetect.detectAll(settings)
        }
        if (settings.mcpEnabled) AgentService.refresh(context)
    }

    /** How the Dextop glue is found at start-up (diagnostics; also returned for tests). */
    enum class Bridge { ABSENT, INSTALLED, FAILED }

    @Volatile var bridge: Bridge = Bridge.ABSENT
        private set

    /**
     * Inside the Dextop app the glue class registers [DesktopHosts.current]; standalone builds do not
     * contain it and run in phone-only mode. Only reflection: the library never links against Dextop.
   */
    internal fun installDesktopBridge(context: Context?, className: String = BRIDGE_CLASS): Bridge {
        val cls = runCatching { Class.forName(className) }.getOrNull() ?: run {
            runCatching { Log.i(TAG, "no Dextop bridge: phone-only mode") }
            return Bridge.ABSENT
        }
        return runCatching {
            val static = runCatching { cls.getMethod("install", Context::class.java) }.getOrNull()
            if (static != null && java.lang.reflect.Modifier.isStatic(static.modifiers)) {
                static.invoke(null, context)
            } else {
                val instance = cls.getField("INSTANCE").get(null)
                cls.getMethod("install", Context::class.java).invoke(instance, context)
            }
            Bridge.INSTALLED
        }.getOrElse {
            runCatching { Log.w(TAG, "Dextop bridge install failed", it) }
            Bridge.FAILED
        }
    }

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_AGENT, "Agent activity", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of running tasks"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_MCP, "MCP server", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Shown while the MCP server accepts connections"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ATTENTION, "Agent questions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "The agent needs an answer or an approval"
                },
                NotificationChannel(CHANNEL_MCP_ERROR, "MCP server problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The MCP server is enabled but could not start (for example, its port is in use)"
                },
            ),
        )
    }
}
