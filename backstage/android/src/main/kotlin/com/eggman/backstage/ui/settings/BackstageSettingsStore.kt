package com.eggman.backstage.ui.settings

import android.content.Context
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings

/**
 * Settings the user surfaces read and write (UI seam).
 *
 * There is ONE settings implementation: [com.eggman.backstage.data.Settings] (agent core). Every key,
 * default and validation lives there (API keys encrypted at rest, model defaults from the provider
 * catalog); [CoreSettingsStore] only adapts it to this interface, so the UI and the agent can never
 * disagree about a value.
 */
interface BackstageSettingsStore {
    // ---- provider (bring your own key) -------------------------------------------------------
    var providerId: String
    fun providerConfigured(): Boolean
    fun providerCredentialsConfigured(): Boolean = providerConfigured()
    fun apiKey(providerId: String = this.providerId): String
    fun setApiKey(providerId: String, key: String)
    fun model(providerId: String = this.providerId): String
    fun modelOptions(providerId: String = this.providerId): List<String> = Provider.parse(providerId)?.suggestedModels.orEmpty()
    fun enabledModels(providerId: String = this.providerId): Set<String> = setOf(model(providerId)).filter(String::isNotBlank).toSet()
    fun setEnabledModels(providerId: String, models: Set<String>, preferred: String? = null) {}
    suspend fun fetchModels(providerId: String = this.providerId, force: Boolean = false): com.eggman.backstage.llm.ModelListResult =
        com.eggman.backstage.llm.ModelListResult.Unavailable("Model list unavailable")
    /** Context window in effect for the provider's model, the user's override (null = automatic), detection. */
    fun contextWindow(providerId: String = this.providerId): Long = 0L
    fun contextOverride(providerId: String = this.providerId): Long? = null
    fun setContextOverride(providerId: String, tokens: Long?) {}
    /** True/false if the provider's model list reported it for the selected model, null if unknown. */
    fun visionSupported(providerId: String = this.providerId): Boolean = true
    fun toolsSupported(providerId: String = this.providerId): Boolean? = null
    /** The user's explicit vision choice (null = automatic/detected). */
    fun visionOverride(providerId: String = this.providerId): Boolean? = null
    fun setVisionOverride(providerId: String, value: Boolean?) {}
    /** Refresh capabilities from the provider's model endpoint (no-op in previews). */
    suspend fun detectContext(providerId: String = this.providerId) {}
    fun setModel(providerId: String, model: String)
    fun baseUrl(providerId: String = this.providerId): String
    fun setBaseUrl(providerId: String, url: String)
    /** The ChatGPT sign-in (provider CHATGPT); null = signed out. */
    var chatGptTokens: com.eggman.backstage.llm.ChatGptTokens?
        get() = null
        set(@Suppress("UNUSED_PARAMETER") v) = Unit

    // ---- agent behaviour ---------------------------------------------------------------------
    var agentEngine: com.eggman.backstage.data.AgentEngine
        get() = com.eggman.backstage.data.AgentEngine.BACKSTAGE
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var planMode: Boolean
    var confirmActions: Boolean
    var adbAutoApprove: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var vision: Boolean
    var maxSteps: Int
    /** The agent chat's live desktop view is collapsed. */
    var agentDesktopCollapsed: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** Chat threads kept in the chat history. */
    var chatThreadsMax: Int
        get() = 100
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var defaultTarget: Target
    /** MCP clients / broadcast API may use the phone screen (off: they are gated to the DeX desktop). */
    var externalPhoneControl: Boolean
    /** "Show agent cursor": the animated pointer that shows what the agent does (on by default). */
    var showAgentCursor: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** Restart a running desktop before each automation run (on by default). */
    var freshDesktopForAutomation: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** Fast mode (on by default): shorter settle waits and no cursor lead. */
    var fastMode: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** How long the cursor leads each action (ms, 0 = no delay). */
    var agentCursorLeadMs: Int
        get() = 200
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** Optional wall-clock limit per task in minutes (0 = off); waiting for the user does not count. */
    var taskTimeLimitMin: Int
    /** User-written standing instructions appended to every task ("rules"). */
    var customInstructions: String
    var globalInstructions: String
        get() = ""
        set(@Suppress("UNUSED_PARAMETER") v) = Unit
    fun effectiveGlobalInstructions(): String = globalInstructions.takeIf(String::isNotBlank) ?: Settings.DEFAULT_GLOBAL_INSTRUCTIONS
    /** Screenshot JPEG quality (30-100) and scale (10-100 % of the display size). */
    var screenshotQuality: Int
    var screenshotScale: Int
    /** What happens to a confirmation request when nobody can answer it (Tasker, schedule, MCP). */
    var nonInteractiveConfirm: NonInteractiveConfirm
    /** Per-app rules: package → instructions shown to the agent while that app is in front. */
    var appRules: Map<String, String>

    // ---- accessibility consent ---------------------------------------------------------------
    /** The user accepted the in-app accessibility disclosure; withdrawing it stops all automatic enabling. */
    var accessibilityConsent: Boolean
    /** Switch the accessibility service back on by itself (after consent) when the desktop resets it. */
    var autoEnableAccessibility: Boolean
    /** Switch the service off while a detector app (one that warns about enabled accessibility services) is in front, and back on after it left. */
    var detectorPause: Boolean
    /** Re-check interval of a pause in ms. */
    var detectorPauseDelayMs: Int
    /** Packages that must not see the service enabled, separated by colon or comma. */
    var detectorPackages: String

    // ---- tool modules and remote MCP servers --------------------------------------------------
    var weatherApiKey: String
    /** metric | imperial */
    var weatherUnits: String
    var weatherDefaultLocation: String
    /** duckduckgo | searxng | brave | serper | tavily | kagi | custom */
    var searchProvider: String
    var searchApiKey: String
    /** Base URL of the user's SearXNG instance (used when [searchProvider] is searxng). */
    var searxngUrl: String
    /** Custom search engine: URL template with {query}, optional header name and (encrypted) value. */
    var customSearchUrl: String get() = ""; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var customSearchHeaderName: String get() = ""; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var customSearchHeaderValue: String get() = ""; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    /** browser_search's URL template ; empty = the default browser's own search engine. */
    var browserSearchUrl: String get() = ""; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var alarmVibrate: Boolean
    var alarmSkipUi: Boolean
    var calendarDefaultId: Long?
    var calendarEnabledIds: Set<Long>
    var calendarMaxEvents: Int
    /** Minutes before events for new reminders; null = none. */
    var calendarDefaultReminder: Int?
    var memoryEnabled: Boolean
    var remoteMcpServers: List<RemoteMcpServer>

    // ---- voice -------------------------------------------------------------------------------
    var speakReplies: Boolean
    var preferOnDeviceStt: Boolean
    var wakeWordEnabled: Boolean
    var wakePhrase: String
    var wakeModelId: String
    var wakePauseWhenScreenOff: Boolean
    var wakePauseWhenLocked: Boolean
    var wakeOnlyWhileCharging: Boolean

    // ---- floating bubble ---------------------------------------------------------------------
    var bubbleEnabled: Boolean
    /** EXPANDED: the bubble opens with the result when a task ends; COMPACT: notification only. */
    var resultDisplay: ResultDisplay
    /** Close the expanded result card automatically after a few seconds. */
    var resultAutoDismiss: Boolean

    // ---- notifications -----------------------------------------------------------------------
    var notifyResults: Boolean

    // ---- desktop in the app ------------------------------------------------------------------
    var popOutOnLeave: Boolean
    var startDesktopOnOpen: Boolean

    // ---- OMP harness -------------------------------------------------------------------------
    var ompEnabled: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var skillScriptsEnabled: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var skillScriptsAutoApprove: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var ompAutoApprove: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var ompBrokerPort: Int get() = 47821; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var ompTermuxEnabled: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var termuxShellEnabled: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    var termuxShellAutoApprove: Boolean get() = false; set(@Suppress("UNUSED_PARAMETER") v) = Unit
    val ompHarnessToken: String get() = ""
    val ompTermuxToken: String get() = ""
    fun rotateOmpTermuxToken(): String = ""
    fun rotateOmpHarnessToken(): String = ""

    // ---- MCP server + automation token -------------------------------------------------------
    var mcpEnabled: Boolean
    var mcpPort: Int
    var mcpLan: Boolean
    /** MCP tools offered to clients (default: execute_task, get_screen_state, get_device_status). */
    var mcpEnabledTools: Set<String>
    /** Also offer every low-level agent tool (tap, type_text, desktop_*...) over MCP. */
    var mcpExtendedTools: Boolean
    /** Bearer token for MCP; also authorises the broadcast API (extra "token"). */
    val mcpToken: String
    fun rotateMcpToken(): String
    /** Include the result text in implicit (package-less) result broadcasts. */
    var broadcastResultText: Boolean

    /** Device-local secret used to sign Tasker/Locale plugin bundles (never shown to the user). */
    val pluginSigningSecret: String
    fun rotatePluginSigningSecret(): String

    var onboardingDone: Boolean

    /** Register for changes; returns an unregister function. Listener receives the changed key. */
    fun observe(listener: (String) -> Unit): () -> Unit
}

typealias ResultDisplay = com.eggman.backstage.data.ResultDisplay
typealias NonInteractiveConfirm = com.eggman.backstage.data.NonInteractiveConfirm
typealias RemoteMcpServer = com.eggman.backstage.data.RemoteMcpServer

/** The UI view of [Settings]: pure delegation, no keys of its own. */
class CoreSettingsStore(private val s: Settings) : BackstageSettingsStore {

    override var providerId: String
        get() = s.provider.name
        set(v) { Provider.parse(v)?.let { s.provider = it } }

    override fun providerConfigured() = s.providerConfigured()
    override fun providerCredentialsConfigured() = s.providerConfig().problem(requireModel = false) == null

    private fun provider(id: String): Provider = Provider.parse(id) ?: s.provider

    override fun apiKey(providerId: String) = s.apiKey(provider(providerId))
    override var chatGptTokens: com.eggman.backstage.llm.ChatGptTokens?
        get() = s.chatGptTokens
        set(v) { s.chatGptTokens = v }
    override fun setApiKey(providerId: String, key: String) = s.setApiKey(provider(providerId), key)
    override fun model(providerId: String) = s.model(provider(providerId))
    override fun modelOptions(providerId: String) = s.modelOptions(provider(providerId))
    override fun enabledModels(providerId: String) = s.enabledModels(provider(providerId))
    override fun setEnabledModels(providerId: String, models: Set<String>, preferred: String?) =
        s.setEnabledModels(provider(providerId), models, preferred)
    override suspend fun fetchModels(providerId: String, force: Boolean) = com.eggman.backstage.llm.ContextDetect.fetchModels(s, provider(providerId), force)
    override fun contextWindow(providerId: String) = s.contextWindow(provider(providerId))
    override fun contextOverride(providerId: String) = s.contextOverride(provider(providerId))
    override fun setContextOverride(providerId: String, tokens: Long?) = s.setContextWindow(provider(providerId), tokens)
    override fun visionSupported(providerId: String) = s.visionSupported(provider(providerId))
    override fun toolsSupported(providerId: String) = s.toolsSupported(provider(providerId))
    override fun visionOverride(providerId: String) = s.visionOverride(provider(providerId))
    override fun setVisionOverride(providerId: String, value: Boolean?) = s.setVisionSupported(provider(providerId), value)
    override suspend fun detectContext(providerId: String) { com.eggman.backstage.llm.ContextDetect.detect(s, provider(providerId)) }
    override fun setModel(providerId: String, model: String) = s.setModel(provider(providerId), model)
    override fun baseUrl(providerId: String) = s.baseUrl(provider(providerId))
    override fun setBaseUrl(providerId: String, url: String) = s.setBaseUrl(provider(providerId), url)

    override var planMode: Boolean get() = s.planMode; set(v) { s.planMode = v }
    override var confirmActions: Boolean get() = s.confirmSensitive; set(v) { s.confirmSensitive = v }
    override var adbAutoApprove: Boolean get() = s.adbAutoApprove; set(v) { s.adbAutoApprove = v }
    override var vision: Boolean get() = s.vision; set(v) { s.vision = v }
    override var maxSteps: Int get() = s.maxSteps; set(v) { s.maxSteps = v }
    override var chatThreadsMax: Int get() = s.chatThreadsMax; set(v) { s.chatThreadsMax = v }
    override var agentDesktopCollapsed: Boolean get() = s.agentDesktopCollapsed; set(v) { s.agentDesktopCollapsed = v }
    override var defaultTarget: Target get() = s.defaultTarget; set(v) { s.defaultTarget = v }
    override var externalPhoneControl: Boolean get() = s.externalPhoneControl; set(v) { s.externalPhoneControl = v }
    override var showAgentCursor: Boolean get() = s.showAgentCursor; set(v) { s.showAgentCursor = v }
    override var agentCursorLeadMs: Int get() = s.agentCursorLeadMs; set(v) { s.agentCursorLeadMs = v }
    override var fastMode: Boolean get() = s.fastMode; set(v) { s.fastMode = v }
    override var freshDesktopForAutomation: Boolean get() = s.freshDesktopForAutomation; set(v) { s.freshDesktopForAutomation = v }
    override var taskTimeLimitMin: Int get() = s.taskTimeLimitMin; set(v) { s.taskTimeLimitMin = v }
    override var customInstructions: String
        get() = s.customInstructions
        set(v) { s.customInstructions = v.take(MAX_INSTRUCTIONS) }
    override var globalInstructions: String get() = s.globalInstructions; set(v) { s.globalInstructions = v }
    override fun effectiveGlobalInstructions() = s.effectiveGlobalInstructions()
    override var screenshotQuality: Int get() = s.screenshotQuality; set(v) { s.screenshotQuality = v }
    override var screenshotScale: Int get() = s.screenshotScale; set(v) { s.screenshotScale = v }
    override var nonInteractiveConfirm: NonInteractiveConfirm get() = s.nonInteractiveConfirm; set(v) { s.nonInteractiveConfirm = v }
    override var appRules: Map<String, String>
        get() = s.appRules
        set(v) { s.appRules = v.mapValues { it.value.take(MAX_RULE) } }

    override var accessibilityConsent: Boolean get() = s.accessibilityConsent; set(v) { s.accessibilityConsent = v }
    override var autoEnableAccessibility: Boolean get() = s.autoEnableAccessibility; set(v) { s.autoEnableAccessibility = v }
    override var detectorPause: Boolean get() = s.detectorPause; set(v) { s.detectorPause = v }
    override var detectorPauseDelayMs: Int get() = s.detectorPauseDelayMs; set(v) { s.detectorPauseDelayMs = v }
    override var detectorPackages: String get() = s.detectorPackages; set(v) { s.detectorPackages = v }

    override var weatherApiKey: String get() = s.weatherApiKey; set(v) { s.weatherApiKey = v }
    override var weatherUnits: String get() = s.weatherUnits; set(v) { s.weatherUnits = if (v == "imperial") "imperial" else "metric" }
    override var weatherDefaultLocation: String get() = s.weatherDefaultLocation; set(v) { s.weatherDefaultLocation = v }
    override var searchProvider: String
        get() = s.searchProvider
        set(v) { s.searchProvider = v.lowercase().takeIf { it in SEARCH_PROVIDERS } ?: "duckduckgo" }
    override var searchApiKey: String get() = s.searchApiKey; set(v) { s.searchApiKey = v }
    override var searxngUrl: String
        get() = s.searchEngine.takeIf { it.startsWith("http") }.orEmpty()
        set(v) { s.searchEngine = v.trim().ifEmpty { "duckduckgo" } }
    override var customSearchUrl: String get() = s.customSearchUrl; set(v) { s.customSearchUrl = v }
    override var customSearchHeaderName: String get() = s.customSearchHeaderName; set(v) { s.customSearchHeaderName = v }
    override var customSearchHeaderValue: String get() = s.customSearchHeaderValue; set(v) { s.customSearchHeaderValue = v }
    override var browserSearchUrl: String get() = s.browserSearchUrl; set(v) { s.browserSearchUrl = v }
    override var alarmVibrate: Boolean get() = s.alarmVibrate; set(v) { s.alarmVibrate = v }
    override var alarmSkipUi: Boolean get() = s.alarmSkipUi; set(v) { s.alarmSkipUi = v }
    override var calendarDefaultId: Long? get() = s.calendarDefaultId; set(v) { s.calendarDefaultId = v }
    override var calendarEnabledIds: Set<Long> get() = s.calendarEnabledIds; set(v) { s.calendarEnabledIds = v }
    override var calendarMaxEvents: Int get() = s.calendarMaxEvents; set(v) { s.calendarMaxEvents = v }
    override var calendarDefaultReminder: Int? get() = s.calendarDefaultReminder; set(v) { s.calendarDefaultReminder = v?.coerceIn(0, 40_320) }
    override var memoryEnabled: Boolean get() = s.memoryEnabled; set(v) { s.memoryEnabled = v }
    override var remoteMcpServers: List<RemoteMcpServer> get() = s.remoteMcpServers; set(v) { s.remoteMcpServers = v }

    override var speakReplies: Boolean get() = s.speakReplies; set(v) { s.speakReplies = v }
    override var preferOnDeviceStt: Boolean get() = s.preferOnDeviceStt; set(v) { s.preferOnDeviceStt = v }
    override var wakeWordEnabled: Boolean get() = s.wakeWordEnabled; set(v) { s.wakeWordEnabled = v }
    override var wakePhrase: String get() = s.wakePhrase; set(v) { s.wakePhrase = v }
    override var wakeModelId: String get() = s.wakeModelId; set(v) { s.wakeModelId = v }
    override var wakePauseWhenScreenOff: Boolean get() = s.wakePauseWhenScreenOff; set(v) { s.wakePauseWhenScreenOff = v }
    override var wakePauseWhenLocked: Boolean get() = s.wakePauseWhenLocked; set(v) { s.wakePauseWhenLocked = v }
    override var wakeOnlyWhileCharging: Boolean get() = s.wakeOnlyWhileCharging; set(v) { s.wakeOnlyWhileCharging = v }

    override var bubbleEnabled: Boolean get() = s.bubbleEnabled; set(v) { s.bubbleEnabled = v }
    override var resultDisplay: ResultDisplay get() = s.resultDisplay; set(v) { s.resultDisplay = v }
    override var resultAutoDismiss: Boolean get() = s.resultAutoDismiss; set(v) { s.resultAutoDismiss = v }
    override var notifyResults: Boolean get() = s.notifyResults; set(v) { s.notifyResults = v }
    override var popOutOnLeave: Boolean get() = s.popOutOnLeave; set(v) { s.popOutOnLeave = v }
    override var startDesktopOnOpen: Boolean get() = s.startDesktopOnOpen; set(v) { s.startDesktopOnOpen = v }

    override var agentEngine: com.eggman.backstage.data.AgentEngine get() = s.agentEngine; set(v) { s.agentEngine = v }
    override var ompEnabled: Boolean get() = s.ompEnabled; set(v) { s.ompEnabled = v }
    override var skillScriptsEnabled: Boolean get() = s.skillScriptsEnabled; set(v) { s.skillScriptsEnabled = v }
    override var skillScriptsAutoApprove: Boolean get() = s.skillScriptsAutoApprove; set(v) { s.skillScriptsAutoApprove = v }
    override var ompAutoApprove: Boolean get() = s.ompAutoApprove; set(v) { s.ompAutoApprove = v }
    override var ompBrokerPort: Int get() = s.ompBrokerPort; set(v) { s.ompBrokerPort = v }
    override var ompTermuxEnabled: Boolean get() = s.ompTermuxEnabled; set(v) { s.ompTermuxEnabled = v }
    override var termuxShellEnabled: Boolean get() = s.termuxShellEnabled; set(v) { s.termuxShellEnabled = v }
    override var termuxShellAutoApprove: Boolean get() = s.termuxShellAutoApprove; set(v) { s.termuxShellAutoApprove = v }
    override val ompHarnessToken: String get() = s.ompHarnessToken
    override val ompTermuxToken: String get() = s.ompTermuxToken
    override fun rotateOmpTermuxToken() = s.rotateOmpTermuxToken()
    override fun rotateOmpHarnessToken() = s.rotateOmpHarnessToken()

    override var mcpEnabled: Boolean get() = s.mcpEnabled; set(v) { s.mcpEnabled = v }
    override var mcpPort: Int get() = s.mcpPort; set(v) { s.mcpPort = v }
    override var mcpLan: Boolean get() = s.mcpLan; set(v) { s.mcpLan = v }
    override var mcpEnabledTools: Set<String> get() = s.mcpEnabledTools; set(v) { s.mcpEnabledTools = v }
    override var mcpExtendedTools: Boolean get() = s.mcpExtendedTools; set(v) { s.mcpExtendedTools = v }
    override val mcpToken: String get() = s.mcpToken
    override fun rotateMcpToken(): String = s.rotateMcpToken()
    override var broadcastResultText: Boolean get() = s.broadcastResultText; set(v) { s.broadcastResultText = v }

    override val pluginSigningSecret: String get() = s.pluginSigningSecret
    override fun rotatePluginSigningSecret(): String = s.rotatePluginSigningSecret()

    override var onboardingDone: Boolean get() = s.onboardingDone; set(v) { s.onboardingDone = v }

    override fun observe(listener: (String) -> Unit) = s.observe(listener)

    companion object {
        const val DEFAULT_MCP_PORT = Settings.DEFAULT_MCP_PORT
        const val DEFAULT_WAKE_PHRASE = Settings.DEFAULT_WAKE_PHRASE
        const val MAX_INSTRUCTIONS = 4000
        const val MAX_RULE = 2000
        val SEARCH_PROVIDERS = listOf("duckduckgo", "searxng", "brave", "serper", "tavily", "kagi", "custom")
    }
}

/** Process-wide accessor: the agent core's [Settings] when it is initialised, else a fresh view of the same prefs. */
object SettingsStores {
    @Volatile
    private var instance: BackstageSettingsStore? = null

    @Volatile
    var factory: (Context) -> BackstageSettingsStore = { ctx ->
        val core = runCatching {
            com.eggman.backstage.init.Backstage.takeIf { it.isInitialized }?.environment?.settings
        }.getOrNull()
        CoreSettingsStore(core ?: Settings(ctx.applicationContext))
    }

    fun get(context: Context): BackstageSettingsStore =
        instance ?: synchronized(this) { instance ?: factory(context).also { instance = it } }

    /** The store once something created it, else null (no context at hand). */
    fun peek(): BackstageSettingsStore? = instance

    /** Test seam. */
    internal fun set(store: BackstageSettingsStore?) {
        instance = store
    }
}
