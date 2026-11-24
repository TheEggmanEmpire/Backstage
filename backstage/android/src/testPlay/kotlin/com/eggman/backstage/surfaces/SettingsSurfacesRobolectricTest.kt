package com.eggman.backstage.surfaces

import android.app.Application
import android.os.Binder
import android.provider.Settings.Secure
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import android.content.ContextWrapper
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.eggman.backstage.agent.AndroidAgentEnvironment
import com.eggman.backstage.agent.ReadinessCheck
import com.eggman.backstage.data.InMemoryMemory
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.privileged.Privileged
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.tools.HttpResult
import com.eggman.backstage.ui.licenses.LicensesScreen
import com.eggman.backstage.ui.licenses.licenseText
import com.eggman.backstage.ui.onboarding.AccessibilityDisclosure
import com.eggman.backstage.ui.onboarding.AccessibilityDisclosureDialog
import com.eggman.backstage.ui.onboarding.PlaySetupScreen
import com.eggman.backstage.ui.settings.AccessibilitySection
import com.eggman.backstage.ui.settings.CoreSettingsStore
import com.eggman.backstage.ui.settings.MemorySection
import com.eggman.backstage.ui.settings.ModulesSection
import com.eggman.backstage.ui.settings.RemoteMcpSection
import com.eggman.backstage.ui.settings.RulesSection
import com.eggman.backstage.ui.settings.SettingsServices
import com.eggman.backstage.ui.settings.SettingsStores
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import rikka.shizuku.Shizuku

/** Accessibility consent, module settings and offline license text as rendered Compose UI. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsSurfacesRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun playContent(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        val playContext = remember(app) {
            object : ContextWrapper(app) {
                override fun getPackageName(): String = "com.eggman.backstage.play"
            }
        }
        CompositionLocalProvider(LocalContext provides playContext, content = content)
    }
    private lateinit var store: CoreSettingsStore

    private val memory0 = SettingsServices.memory
    private val http0 = SettingsServices.httpGet
    private val probe0 = SettingsServices.probeMcp
    private val apps0 = SettingsServices.launchableApps
    private val calendars0 = SettingsServices.calendars

    @Before fun setUp() {
        store = memorySettings()
        SettingsStores.set(store)
        SettingsServices.calendars = { emptyList() }
    }

    @After fun tearDown() {
        SettingsStores.set(null)
        SettingsServices.memory = memory0
        SettingsServices.httpGet = http0
        SettingsServices.probeMcp = probe0
        SettingsServices.launchableApps = apps0
        SettingsServices.calendars = calendars0
    }

    private fun scrolling(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
    }

    private fun waitForText(text: String, substring: Boolean = true) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    // ----  ---------------------------------------------------------------------------------
    @Test fun agreeStaysDisabledUntilTheBoxIsTicked() {
        var agreed = false
        compose.setContent { AccessibilityDisclosureDialog(onAgree = { agreed = true }, onDecline = {}) }
        compose.onNodeWithText(AccessibilityDisclosure.AGREE).assertIsNotEnabled()
        compose.onNodeWithText(AccessibilityDisclosure.CHECKBOX).performClick()
        compose.onNodeWithText(AccessibilityDisclosure.AGREE).assertIsEnabled().performClick()
        assertTrue(agreed)
    }

    @Test fun setupRequiresPrivilegeBeforeOfferingProviderSetup() {
        playContent { PlaySetupScreen(onDone = {}) }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Get Stellar").assertExists()
        compose.onNodeWithText("Get Shizuku Plus").assertExists()
        compose.onNodeWithText("Skip for now").assertDoesNotExist()
    }

    @Test fun onboardingModelSelectionBecomesTheActiveMainModel() = MockWebServer().use { models ->
        models.start()
        models.enqueue(MockResponse().setBody("""{"data":[{"id":"onboarding-test-model","display_name":"Onboarding test"}]}"""))
        val settings = Settings(app)
        val env = AndroidAgentEnvironment(app)
        val previousHost = DesktopHosts.current
        val mirrorService = "com.eggman.backstage.play/moe.n4tsu.dextop.MirrorService"
        val resolver = app.contentResolver
        val previousServices = Secure.getString(resolver, Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Secure.getString(resolver, Secure.ACCESSIBILITY_ENABLED)
        assertFalse(ReadinessCheck.compute(env.readinessInputs()).providerConfigured)
        SettingsStores.set(CoreSettingsStore(settings))
        DesktopHosts.current = object : DesktopHost by FakeDesktopHost(), DesktopHostExtras {
            override fun setMiniStatus(text: String?) = Unit
            override fun setMiniShape(shape: String) = true
            override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
            override fun usePrivilegeProvider(provider: String) = false
            override fun desktopAccessibilityComponent() = mirrorService
        }
        playContent { PlaySetupScreen(onDone = {}) }
        compose.onNodeWithText("Next").performClick()
        ReflectionHelpers.setStaticField(Shizuku::class.java, "binder", Binder())
        ReflectionHelpers.setStaticField(Shizuku::class.java, "permissionGranted", true)
        try {
            compose.runOnIdle { Privileged.notifyChanged() }
            compose.onNodeWithText("Next").assertIsEnabled().performClick()
            compose.onNodeWithText("Allow desktop accessibility").assertExists()
            compose.onNodeWithText("Next").assertIsNotEnabled()
            compose.onAllNodesWithText("Open Accessibility")[0].performClick()
            compose.onNodeWithText("Open app details").assertExists()
            compose.onAllNodesWithText("Open Accessibility")[1].assertExists()
            compose.onNodeWithText(AccessibilityDisclosure.TITLE).assertExists()
            compose.onNodeWithText(AccessibilityDisclosure.DECLINE).performClick()
            compose.runOnIdle { settings.accessibilityConsent = true }
            compose.onNodeWithText("Next").assertIsNotEnabled()
            compose.runOnIdle {
                Secure.putString(resolver, Secure.ENABLED_ACCESSIBILITY_SERVICES, mirrorService)
                Secure.putInt(resolver, Secure.ACCESSIBILITY_ENABLED, 1)
            }
            compose.onNodeWithText("Enabled").assertExists()
            compose.onNodeWithText("Next").assertIsEnabled().performClick()
            compose.onNodeWithText("Next").assertIsNotEnabled()
            compose.onNodeWithText("Sign in with ChatGPT (paid plan)").assertExists()
            compose.onNodeWithText("API key").assertDoesNotExist()
            compose.onNodeWithText("Use your own API key (BYOK)").performClick()
            compose.onNodeWithText("Base URL").assertExists()
            compose.onNodeWithText("Main model / add model ID").assertDoesNotExist()
            compose.onNodeWithText("API key").performTextInput("test-onboarding-key")
            compose.onNodeWithText("Base URL").performTextReplacement(models.url("/").toString())
            compose.onNodeWithText("Next").assertIsEnabled().performScrollTo().performClick()
            compose.onNodeWithText("Choose your model").assertExists()
            compose.onNodeWithText("Next").assertIsNotEnabled()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("onboarding-test-model").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("onboarding-test-model").performClick()
            compose.onNodeWithText("Next").assertIsEnabled().performScrollTo().performClick()
        } finally {
            ReflectionHelpers.setStaticField(Shizuku::class.java, "binder", null)
            ReflectionHelpers.setStaticField(Shizuku::class.java, "permissionGranted", false)
            DesktopHosts.current = previousHost
            Secure.putString(resolver, Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
            Secure.putString(resolver, Secure.ACCESSIBILITY_ENABLED, previousEnabled)
        }
        compose.onNodeWithText("Let the agent operate apps").assertExists()
        compose.onNodeWithText("Get Backstage-play-agent").assertExists()
        compose.onNodeWithText("If Google Play Protect asks before installing, choose to install without scanning.").assertExists()
        compose.onNodeWithText("I installed it").assertDoesNotExist()
        compose.onNodeWithText("Finish setup").assertIsNotEnabled()
        SettingsStores.set(null)
        val restarted = AndroidAgentEnvironment(app)
        assertEquals(Provider.ANTHROPIC, restarted.settings.provider)
        assertEquals("onboarding-test-model", restarted.settings.model())
        assertTrue(ReadinessCheck.compute(restarted.readinessInputs()).providerConfigured)
    }

    @Test fun accessibilitySettingsExplainThePlayCompanionBoundary() {
        scrolling { AccessibilitySection(store) }
        compose.onNodeWithText("Accessibility").assertExists()
        compose.onNodeWithText("Enable desktop accessibility through Dextop setup. Backstage-play-agent provides AI screen access.")
            .assertExists()
        assertTrue(compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isEmpty())
        assertFalse(store.accessibilityConsent)
    }

    // ----  ---------------------------------------------------------------------------------
    @Test fun weatherAndSearchSettingsWriteThroughAndTheWeatherKeyCanBeTested() {
        var asked: String? = null
        SettingsServices.httpGet = { url ->
            asked = url
            HttpResult(200, """{"name":"Berlin","sys":{"country":"DE"},"main":{"temp":7},"weather":[{"main":"Rain","description":"light rain"}]}""")
        }
        scrolling { ModulesSection(store) }
        compose.onNodeWithText("OpenWeatherMap API key").performTextInput("owm-key")
        compose.onNodeWithText("Home location").performTextInput("Berlin, DE")
        assertEquals("owm-key", store.weatherApiKey)
        assertEquals("Berlin, DE", store.weatherDefaultLocation)
        compose.onNodeWithText("Test").performClick()
        waitForText("Works: Berlin, DE")
        assertTrue(asked!!.contains("appid=owm-key"))

        compose.onNodeWithText("Brave").performScrollTo().performClick()
        compose.onNodeWithText("Brave API key").performScrollTo().performTextInput("brave-key")
        assertEquals("brave", store.searchProvider)
        assertEquals("brave-key", store.searchApiKey)
    }

    @Test fun remoteMcpServersCanBeAddedAndTested() {
        SettingsServices.probeMcp = { s -> "Connected: 3 tools at ${s.url}" }
        scrolling { RemoteMcpSection(store) }
        compose.onNodeWithText("Add a server").performClick()
        compose.onNodeWithText("Name").performTextInput("Home")
        compose.onNodeWithText("Server URL").performTextReplacement("https://mcp.example.org/mcp")
        compose.onNodeWithText("Bearer token (optional)").performTextInput("secret")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        val saved = store.remoteMcpServers.single()
        assertEquals("Home", saved.name)
        assertEquals("secret", saved.token)
        compose.onNodeWithText("Test").performClick()
        waitForText("Connected: 3 tools")
    }

    @Test fun perAppRulesUseTheAppPicker() {
        SettingsServices.launchableApps = { listOf(AppEntry("Spotify", "com.spotify.music"), AppEntry("Chrome", "com.android.chrome")) }
        scrolling { RulesSection(store) }
        compose.onNodeWithText("Add a rule for an app").performClick()
        compose.onNodeWithText("Spotify").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rule for Spotify").performTextInput("Never start a paid trial")
        assertEquals(mapOf("com.spotify.music" to "Never start a paid trial"), store.appRules)
        compose.onNodeWithText("Remove rule").performClick()
        assertTrue(store.appRules.isEmpty())
    }

    @Test fun memoriesCanBeBrowsedSearchedAndDeleted() {
        val mem = InMemoryMemory()
        mem.save("My gym is FitX Kreuzberg", "agent")
        mem.save("Anna's birthday is 3 May", "agent")
        SettingsServices.memory = { mem }
        scrolling { MemorySection(store) }
        waitForText("My gym is FitX Kreuzberg", substring = false)
        compose.onNodeWithText("Anna's birthday is 3 May").assertExists()
        compose.onNodeWithText("Search memories").performTextInput("gym")
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Anna's birthday is 3 May")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { mem.all().size == 1 }
        assertEquals("Anna's birthday is 3 May", mem.all().single().content)
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.waitForIdle()
        assertFalse(store.memoryEnabled)
    }

    // ---- offline legal notices --------------------------------------------------------------------------
    @Test fun theFullLicenseIsReadableOffline() {
        val text = licenseText(app)!!
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE") && text.contains("Version 3, 29 June 2007"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        compose.setContent { LicensesScreen() }
        compose.onNodeWithText("Copyright © 2026 TheEggmanEmpire", substring = true).assertExists()
        compose.onNodeWithText("Read the GPL-3.0").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("TERMS AND CONDITIONS", substring = true).assertExists()
        assertNull("no browser needed", shadowOf(app).nextStartedActivity)
    }
}
