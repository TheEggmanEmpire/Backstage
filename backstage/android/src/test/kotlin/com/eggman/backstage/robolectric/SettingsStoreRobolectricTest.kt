package com.eggman.backstage.robolectric

import android.app.Application
import android.content.Context
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.data.NonInteractiveConfirm
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.ResultDisplay
import com.eggman.backstage.data.Settings
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.ui.settings.CoreSettingsStore
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Settings over real SharedPreferences: every value survives a new instance (= a process restart). */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = RoboSupport.resetProcess()

    @After fun tearDown() = RoboSupport.resetProcess()

    @Test fun defaultsOnAFreshInstall() {
        val s = Settings(app)
        assertEquals(Provider.ANTHROPIC, s.provider)
        assertEquals("", s.apiKey())
        assertFalse(s.mcpEnabled)
        assertEquals(Settings.DEFAULT_MCP_PORT, s.mcpPort)
        assertFalse(s.mcpLan)
        assertTrue(s.planMode)
        assertTrue(s.confirmSensitive)
        assertFalse(s.onboardingDone)
        assertEquals(80, s.maxSteps)
        assertEquals(0, s.taskTimeLimitMin) // off by default
        assertEquals(15, s.taskTimeLimitMinutes)
        assertTrue(s.remoteMcpServers.isEmpty())
        assertNull(s.calendarDefaultId)
    }

    @Test fun everyKindOfValueRoundTrips() {
        val a = Settings(app)
        a.provider = Provider.OPENROUTER
        a.setApiKey(Provider.OPENROUTER, "  sk-or-123  ")
        a.setModel(Provider.OPENROUTER, "vendor/model-x")
        a.setBaseUrl(Provider.CUSTOM, "http://10.0.0.2:8080/v1")
        a.setReasoningEffort(Provider.OPENAI, "high")
        a.setKeyVerified(Provider.OPENROUTER, true)
        a.setContextWindow(Provider.OPENROUTER, 200_000L)
        a.defaultTarget = Target.DESKTOP
        a.desktopPresentation = DesktopHost.Presentation.HIDDEN
        a.maxSteps = 77
        a.taskTimeLimitMin = 12
        a.screenshotQuality = 55
        a.planMode = false
        a.confirmSensitive = false
        a.nonInteractiveConfirm = NonInteractiveConfirm.ALLOW_IF_REQUESTED
        a.customInstructions = "Always answer in German.\nNever pay."
        a.appRules = mapOf("com.whatsapp" to "Ask before sending")
        a.calendarDefaultId = 42L
        a.calendarEnabledIds = setOf(1L, 2L)
        a.calendarDefaultReminder = 15
        a.remoteMcpServers = listOf(RemoteMcpServer("r1", "Home", "http://192.168.1.5:9000/mcp", "tok", enabled = false))
        a.wakeWordEnabled = true
        a.wakePhrase = "hey stage"
        a.bubbleEnabled = true
        a.resultDisplay = ResultDisplay.COMPACT
        a.mcpEnabled = true
        a.mcpPort = 9123
        a.mcpLan = true
        a.mcpExtendedTools = true
        a.mcpEnabledTools = setOf("execute_task", "wake_device")
        a.onboardingDone = true
        a.privilegedSetupDone = true
        val token = a.mcpToken
        val secret = a.pluginSigningSecret

        val b = Settings(app) // fresh instance, same preferences file
        assertEquals(Provider.OPENROUTER, b.provider)
        assertEquals("sk-or-123", b.apiKey(Provider.OPENROUTER))
        assertEquals("vendor/model-x", b.model())
        assertEquals("http://10.0.0.2:8080/v1", b.baseUrl(Provider.CUSTOM))
        assertEquals("high", b.reasoningEffort(Provider.OPENAI))
        assertTrue(b.keyVerified(Provider.OPENROUTER))
        assertEquals(200_000L, b.contextWindow(Provider.OPENROUTER))
        assertEquals(Target.DESKTOP, b.defaultTarget)
        assertEquals(DesktopHost.Presentation.HIDDEN, b.desktopPresentation)
        assertEquals(77, b.maxSteps)
        assertEquals(12, b.taskTimeLimitMin)
        assertEquals(55, b.screenshotQuality)
        assertFalse(b.planMode)
        assertFalse(b.confirmSensitive)
        assertEquals(NonInteractiveConfirm.ALLOW_IF_REQUESTED, b.nonInteractiveConfirm)
        assertEquals("Always answer in German.\nNever pay.", b.customInstructions)
        assertEquals(mapOf("com.whatsapp" to "Ask before sending"), b.appRules)
        assertEquals(42L, b.calendarDefaultId)
        assertEquals(setOf(1L, 2L), b.calendarEnabledIds)
        assertEquals(15, b.calendarDefaultReminder)
        assertEquals(listOf(RemoteMcpServer("r1", "Home", "http://192.168.1.5:9000/mcp", "tok", enabled = false)), b.remoteMcpServers)
        assertTrue(b.wakeWordEnabled)
        assertEquals("hey stage", b.wakePhrase)
        assertTrue(b.bubbleEnabled)
        assertEquals(ResultDisplay.COMPACT, b.resultDisplay)
        assertTrue(b.mcpEnabled)
        assertEquals(9123, b.mcpPort)
        assertTrue(b.mcpLan)
        assertTrue(b.mcpExtendedTools)
        assertEquals(setOf("execute_task", "wake_device"), b.mcpEnabledTools)
        assertTrue(b.onboardingDone)
        assertTrue(b.privilegedSetupDone)
        // Secrets are generated once and stay stable across instances.
        assertEquals(token, b.mcpToken)
        assertEquals(secret, b.pluginSigningSecret)
        assertTrue(token.length >= 32)

        // Clearing nullable values removes them again.
        b.calendarDefaultId = null
        assertNull(Settings(app).calendarDefaultId)
    }

    @Test fun outOfRangeValuesAreClamped() {
        val s = Settings(app)
        s.mcpPort = 80
        assertEquals(1024, Settings(app).mcpPort)
        s.mcpPort = 70_000
        assertEquals(65535, Settings(app).mcpPort)
        // 10 -> 20 and 500 -> 200.
        s.maxSteps = 10
        assertEquals(20, Settings(app).maxSteps)
        s.maxSteps = 500
        assertEquals(200, Settings(app).maxSteps)
        s.taskTimeLimitMin = -5
        assertEquals(0, Settings(app).taskTimeLimitMin)
        // 5–120 minutes; 15 when first switched on; the minutes survive switching it off.
        s.taskTimeLimitEnabled = true
        assertEquals(15, Settings(app).taskTimeLimitMin)
        s.taskTimeLimitMin = 1
        assertEquals(5, Settings(app).taskTimeLimitMin)
        s.taskTimeLimitMin = 240
        assertEquals(120, Settings(app).taskTimeLimitMin)
        s.taskTimeLimitEnabled = false
        assertEquals(0, Settings(app).taskTimeLimitMin)
        assertEquals(120, Settings(app).taskTimeLimitMinutes)
        s.taskTimeLimitEnabled = true
        assertEquals(120, Settings(app).taskTimeLimitMin)
    }

    @Test fun oldBuildsStoredOnlyTheMinutes() {
        val prefs = app.getSharedPreferences(Settings.PREFS, android.content.Context.MODE_PRIVATE)
        // An older build stored "30" (= on, 30 minutes) and a too-small step limit.
        prefs.edit().putInt("task_time_limit_min", 30).putInt("max_steps", 3).commit()
        val s = Settings(app)
        assertTrue(s.taskTimeLimitEnabled)
        assertEquals(30, s.taskTimeLimitMin)
        assertEquals(20, s.maxSteps) // stored values outside the range are read clamped
        // Off in an older build (0): stays off, and choosing minutes does not switch it on.
        prefs.edit().putInt("task_time_limit_min", 0).remove("task_time_limit_on").commit()
        assertEquals(0, Settings(app).taskTimeLimitMin)
        Settings(app).taskTimeLimitMinutes = 45
        assertFalse(Settings(app).taskTimeLimitEnabled)
        assertEquals(45, Settings(app).taskTimeLimitMinutes)
    }

    @Test fun rotatingSecretsInvalidatesTheOldOnes() {
        val s = Settings(app)
        val token = s.mcpToken
        val rotated = s.rotateMcpToken()
        assertNotEquals(token, rotated)
        assertEquals(rotated, Settings(app).mcpToken)
        val secret = s.pluginSigningSecret
        assertNotEquals(secret, s.rotatePluginSigningSecret())
    }

    @Test fun apiKeysRoundTripThroughTheCipher() {
        val s = Settings(app)
        s.setApiKey(Provider.ANTHROPIC, "sk-ant-secret")
        val raw = app.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).getString("key_ANTHROPIC", null)
        // Robolectric has no AndroidKeyStore: the cipher falls back to keeping the key usable rather than losing it.
        // What matters here is that the value read back is the trimmed key, however it is stored.
        assertEquals("sk-ant-secret", s.apiKey(Provider.ANTHROPIC))
        assertTrue(raw == "sk-ant-secret" || raw!!.startsWith("enc1:"))
    }

    @Test fun uiStoreObservesChangesMadeByAnotherInstance() {
        val ui = CoreSettingsStore(Settings(app))
        val changed = mutableListOf<String>()
        val unregister = ui.observe { changed += it }
        Settings(app).mcpEnabled = true
        assertTrue(RoboSupport.waitFor { "mcp" in changed })
        assertTrue(ui.mcpEnabled)
        unregister()
        Settings(app).bubbleEnabled = true
        RoboSupport.waitFor(timeoutMs = 200) { false }
        assertFalse("bubble_enabled" in changed)
    }

    @Test fun uiAndAgentShareOneSettingsObjectOnceTheCoreIsUp() {
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        Backstage.init(app)
        val ui = SettingsStores.get(app)
        assertSame(ui, SettingsStores.get(app))
        ui.maxSteps = 25
        ui.defaultTarget = Target.PHONE
        ui.providerId = "OLLAMA"
        assertEquals(25, Backstage.environment.settings.maxSteps)
        assertEquals(Target.PHONE, Backstage.environment.settings.defaultTarget)
        assertEquals(Provider.OLLAMA, Backstage.environment.settings.provider)
        // Unknown provider ids from old builds are ignored instead of corrupting the setting.
        ui.providerId = "NO_SUCH_PROVIDER"
        assertEquals(Provider.OLLAMA, Backstage.environment.settings.provider)
        // The broadcast API and the MCP server authenticate against the same token.
        assertEquals(Backstage.environment.settings.mcpToken, ui.mcpToken)
    }
}
