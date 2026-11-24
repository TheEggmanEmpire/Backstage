package com.eggman.backstage.agent

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.a11y.DistributionAccessibility
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.eggman.backstage.a11y.AccessibilityAutoEnable
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.data.Settings
import com.eggman.backstage.data.SqliteTaskHistory
import com.eggman.backstage.data.TaskHistoryStore
import com.eggman.backstage.exec.DesktopExecutor
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.PhoneExecutor
import com.eggman.backstage.exec.TargetContext
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.llm.LlmClient
import com.eggman.backstage.llm.LlmFactory
import com.eggman.backstage.llm.ProviderConfig
import com.eggman.backstage.mcp.WakeScreenActivity
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.tools.AndroidDeviceServices
import com.eggman.backstage.tools.DeviceServices
import com.eggman.backstage.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Production [AgentEnvironment]: Android services, Dextop host, accessibility, SQLite history. */
class AndroidAgentEnvironment(private val context: Context) : AgentEnvironment {
    override val settings = Settings(context)
    override val registry: ToolRegistry = ToolRegistry.default()
    override val services: DeviceServices = AndroidDeviceServices(context, settings)
    override val history: TaskHistoryStore = SqliteTaskHistory(context) { settings.historyLimit }

    private val ownPackages = setOf(context.packageName)
    private val deviceFactsReader = AndroidDeviceFacts(context)

    init {
        // The "[device]" line of screen listings (only changes per step).
        DeviceStateDiff.source = { deviceFactsReader.volatile() }
    }

    init {
        // Unlock / screen on: recompute readiness so no stale "locked" or "screen off" line is shown.
        runCatching {
            val app = context.applicationContext ?: context
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) {
                    Thread { runCatching { com.eggman.backstage.entry.AgentAccess.controller()?.readiness() } }.start()
                }
            }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)
        }
    }

    override fun deviceFacts(): DeviceFacts? = runCatching { deviceFactsReader.facts() }.getOrNull()

    override val threads: com.eggman.backstage.data.ChatThreadStore
        get() = com.eggman.backstage.data.ChatThreads.get(context)

    init {
        // The animated agent cursor ("Show agent cursor", on by default).
        com.eggman.backstage.cursor.AgentCursor.sink = com.eggman.backstage.cursor.CursorOverlay(context.applicationContext ?: context)
        com.eggman.backstage.cursor.AgentCursor.enabled = { settings.showAgentCursor }
        com.eggman.backstage.cursor.AgentCursor.leadSettingMs = { com.eggman.backstage.exec.AgentPace.cursorLeadMs(settings.agentCursorLeadMs) }
        // App list and default apps are read once in the background, not by the first task.
        com.eggman.backstage.exec.DefaultAppsCache.warm(context.applicationContext ?: context)
        // Fast mode (on by default): shorter settle waits, no cursor lead.
        com.eggman.backstage.exec.AgentPace.fast = { settings.fastMode }
    }
    private val execLock = Mutex()
    @Volatile private var phone: PhoneExecutor? = null
    @Volatile private var desktop: DesktopExecutor? = null

    override fun llm(config: ProviderConfig): LlmClient = LlmFactory.create(config)

    private fun privileged(): Boolean = Privileged.available() || runCatching { DesktopHosts.current?.privilegedAvailable() == true }.getOrDefault(false)

    override fun targetContext(request: TaskRequest, conversationTarget: TargetKind?): TargetContext {
        val host = DesktopHosts.current
        return TargetContext(
            requested = request.target,
            defaultTarget = settings.defaultTarget,
            source = request.source,
            prompt = request.prompt,
            hostPresent = host != null,
            desktopActive = runCatching { host?.sessionActive() == true }.getOrDefault(false),
            privilegedAvailable = privileged(),
            autoStartDesktop = settings.mayAutoStartDesktop,
            conversationTarget = conversationTarget,
            externalPhoneAllowed = settings.externalPhoneControl,
        )
    }

    override suspend fun executor(kind: TargetKind): Executor = execLock.withLock {
        if (AgentAccessibility.instance == null && settings.mayAutoEnableAccessibility) {
            AccessibilityAutoEnable.ensureConnected(context)
        }
        when (kind) {
            TargetKind.PHONE -> phone ?: PhoneExecutor(context, ownPackages).also { phone = it }
            TargetKind.DESKTOP -> desktopExecutor()
        }.also { e ->
            (e as? com.eggman.backstage.exec.A11yExecutorBase)?.let {
                it.screenshotQuality = settings.screenshotQuality
                it.screenshotScale = settings.screenshotScale
            }
        }
    }

    /** Keyguard up, or the phone's own screen off (then a desktop in the phone's display group sleeps too). */
    private fun phoneLocked(): Boolean =
        runCatching { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false) ||
            !PhoneScreen.on(context)

    private suspend fun desktopExecutor(): Executor {
        check(settings.onboardingDone) { "Finish Backstage setup before starting the desktop." }
        val host = DesktopHosts.current ?: throw IllegalStateException("This build has no Dextop desktop; use the phone screen.")
        val extras = host as? com.eggman.backstage.host.DesktopHostExtras
        val locked = phoneLocked()
        val active = runCatching { host.sessionActive() }.getOrDefault(false)
        val status = if (locked) runCatching { extras?.lockedModeStatus() }.getOrNull() else null
        val step = DesktopLockGate.decide(
            phoneLocked = locked,
            sessionActive = active,
            runsWhileLocked = runCatching { extras?.desktopRunsWhileLocked() == true }.getOrDefault(false),
            optedIn = runCatching { extras?.runWhileLocked() == true }.getOrDefault(false),
            createdWithOptIn = status?.createdWithOptIn,
            hostFallback = status?.hostFallback,
            startable = settings.mayAutoStartDesktop && privileged(),
            restartTriedRecently = LockedModeLog.restartTriedRecently(),
        )
        var geo: com.eggman.backstage.host.DesktopHost.Geometry? = null
        when (step) {
            is DesktopLockGate.Step.Refuse -> {
                if (step.reason != DesktopLockGate.LOCKED) {
                    LockedModeLog.record("a task was refused while locked" + (status?.hostFallback?.let { ": $it" } ?: ""))
                }
                throw IllegalStateException(step.reason)
            }
            is DesktopLockGate.Step.Restart -> {
                LockedModeLog.markRestart()
                LockedModeLog.record("restarted the desktop while locked: ${step.reason}")
                desktop = null
                geo = extras?.restartDesktop(com.eggman.backstage.host.DesktopHost.Presentation.HIDDEN)
                    ?: throw IllegalStateException(DesktopLockGate.cannotRunLocked("the desktop could not be restarted with the setting"))
                if (settings.mayAutoEnableAccessibility) AccessibilityAutoEnable.reassertSoon(context, delayMs = 600)
                waitForDisplay(geo.displayId)
            }
            DesktopLockGate.Step.Proceed -> Unit
        }
        if (geo == null) geo = if (host.sessionActive()) host.geometry() else null
        if (geo == null) {
            if (SessionStop.autoStartBlocked()) throw IllegalStateException(SessionStop.BLOCKED)
            if (!settings.autoStartDesktop) throw IllegalStateException("The desktop is not running and auto-start is off. Start it on the Desktop tab first.")
            if (!privileged()) throw IllegalStateException("The desktop cannot start without Dextop's wireless debugging (or Shizuku) connection.")
            geo = host.startDesktop(DesktopLockGate.startPresentation(locked, settings.desktopPresentation))
                ?: throw IllegalStateException("The desktop did not start in time.")
            // Dextop may rewrite the accessibility list while starting; make sure its service is back and sees the display.
            if (settings.mayAutoEnableAccessibility) AccessibilityAutoEnable.reassertSoon(context, delayMs = 600)
            waitForDisplay(geo.displayId)
        }
        if (locked && runCatching { extras?.desktopRunsWhileLocked() != true }.getOrDefault(true)) {
            // Started (or restarted) for this locked phone, but the platform did not apply the flags.
            val reason = runCatching { extras?.lockedModeStatus()?.hostFallback }.getOrNull()
            LockedModeLog.record(reason ?: "the new desktop does not keep running while locked")
            throw IllegalStateException(DesktopLockGate.cannotRunLocked(reason))
        }
        AgentKeepAwake.desktopInUse(context)
        val cur = desktop
        if (cur != null && cur.displayId == geo.displayId && cur.width == geo.width && cur.height == geo.height) return cur
        return DesktopExecutor(host, geo, ownPackages).also { desktop = it }
    }

    private suspend fun waitForDisplay(displayId: Int, timeoutMs: Long = 4_000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val s = AgentAccessibility.instance
            if (s != null && displayId in s.trackedDisplays()) return
            delay(200)
        }
    }

    override fun peekExecutor(kind: TargetKind): Executor? = when (kind) {
        TargetKind.PHONE -> phone
        TargetKind.DESKTOP -> desktop?.takeIf { d ->
            val host = DesktopHosts.current
            host != null && runCatching { host.sessionActive() && host.geometry()?.displayId == d.displayId }.getOrDefault(false)
        }
    }

    override fun readinessInputs(): ReadinessInputs {
        val host = DesktopHosts.current
        val extras = host as? com.eggman.backstage.host.DesktopHostExtras
        val km = context.getSystemService(KeyguardManager::class.java)
        val privileged = privileged()
        val active = runCatching { host?.sessionActive() == true }.getOrDefault(false)
        return ReadinessInputs(
            providerProblem = settings.providerConfig().problem(),
            accessibilityEnabled = AgentAccessibility.instance != null,
            accessibilityHost = DistributionAccessibility.hostAvailable,
            companionStatus = DistributionAccessibility.status,
            accessibilityGaps = DistributionAccessibility.gaps(),
            privilegedAvailable = privileged,
            hostPresent = host != null,
            desktopActive = active,
            directDisplay = runCatching { host?.directDisplay() != false }.getOrDefault(true),
            screenOn = PhoneScreen.on(context),
            unlocked = runCatching { !km.isKeyguardLocked }.getOrDefault(true),
            sdkInt = Build.VERSION.SDK_INT,
            appLabel = runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Backstage"),
            desktopRunsWhileLocked = active && runCatching { extras?.desktopRunsWhileLocked() == true }.getOrDefault(false),
            desktopStartableWhileLocked = !active && host != null && settings.mayAutoStartDesktop && privileged &&
                runCatching { extras?.runWhileLocked() == true }.getOrDefault(false),
            // Created before the opt-in: a task started while locked restarts it with the flags (DesktopLockGate).
            desktopRestartableWhileLocked = active && settings.mayAutoStartDesktop && privileged &&
                runCatching { extras?.runWhileLocked() == true }.getOrDefault(false) &&
                runCatching { extras?.lockedModeStatus()?.createdWithOptIn == false }.getOrDefault(false) &&
                !LockedModeLog.restartTriedRecently(),
        )
    }

    override fun onBusyChanged(busy: Boolean) {
        // CPU (and the desktop's own display group) stay awake for the task, also with the phone locked.
        AgentKeepAwake.setBusy(context, busy)
        AgentService.refresh(context)
    }

    override val prewarmLlmConnection: Boolean get() = true

    override suspend fun beforeTask(request: TaskRequest) {
        if (PLAY_DISTRIBUTION && AgentAccessibility.instance == null) return
        if (AgentAccessibility.instance == null && settings.mayAutoEnableAccessibility) {
            AccessibilityAutoEnable.ensureConnected(context)
        }
        val target = runCatching { com.eggman.backstage.exec.TargetResolver.resolve(targetContext(request, null)).kind }.getOrNull()
        // an automation starts on a freshly restarted desktop, so nothing left on screen by earlier work misleads it.
        val host = DesktopHosts.current
        var restarted = false
        if (target == TargetKind.DESKTOP && host != null &&
            DesktopRefresh.wantedFor(request, settings.freshDesktopForAutomation, runCatching { host.sessionActive() }.getOrDefault(false))) {
            desktop = null
            restarted = services.restartDesktop() != null
            if (!restarted) LockedModeLog.record("could not restart the desktop before an automation")
        }
        // A desktop that runs but shows only black cannot be operated: restart it before the task starts. A second
        // frame confirms the first, so a desktop caught mid-transition is left alone.
        if (target == TargetKind.DESKTOP && host != null && !restarted && desktopBlank(host)) {
            delay(1_500)
            if (desktopBlank(host)) {
                desktop = null
                if (services.restartDesktop() == null) LockedModeLog.record("the desktop showed only black and did not come back after a restart")
                else LockedModeLog.record("the desktop showed only black; restarted it before the task")
            }
        }
        // A desktop that keeps running while locked (or will be started/restarted that way) needs no phone screen:
        // leave the phone dark and locked.
        if (ReadinessCheck.needsPhoneScreen(readinessInputs(), target)) wakeIfNeeded()
    }

    /** One frame of the running desktop is black throughout (same capture path as the desktop executor). */
    private suspend fun desktopBlank(host: com.eggman.backstage.host.DesktopHost): Boolean {
        val displayId = runCatching { host.geometry()?.displayId }.getOrNull() ?: return false
        return DesktopHealth.blank(host) {
            (if (PLAY_DISTRIBUTION) null else runCatching { host.captureFrame() }.getOrNull())
                ?: AgentAccessibility.instance?.screenshot(displayId)
        }
    }

    /**
     * Turns the phone's screen on for tasks started while it is off (MCP, schedule, Tasker). The default display's state
     * decides, not PowerManager.isInteractive, which stays true while a desktop's own display group is awake.
   */
    suspend fun wakeIfNeeded(): Boolean {
        if (PhoneScreen.on(context)) return true
        // inject the wake key through the privileged input manager (no `input` process to spawn); the shell
        // command stays as the fallback.
        if (!PLAY_DISTRIBUTION && privileged() && !com.eggman.backstage.exec.InputInjector(android.view.Display.DEFAULT_DISPLAY).key(android.view.KeyEvent.KEYCODE_WAKEUP)) {
            Privileged.exec("input", "keyevent", "KEYCODE_WAKEUP")
        }
        if (!PhoneScreen.on(context)) {
            runCatching {
                val launcher = AgentAccessibility.instance?.service ?: context
                launcher.startActivity(Intent(context, WakeScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }
        repeat(15) { if (PhoneScreen.on(context)) return true; delay(100) }
        return PhoneScreen.on(context)
    }

    override fun ownPackages(): Set<String> = ownPackages

    private val remote = com.eggman.backstage.mcp.RemoteMcpTools()
    override suspend fun remoteTools(): List<com.eggman.backstage.tools.Tool> = remote.tools(settings.remoteMcpServers)

    override fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"
}
