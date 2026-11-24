package com.eggman.backstage.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Settings
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.llm.ModelListResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsSearchScreenRobolectricTest {
    @get:Rule val compose = createComposeRule()

    private var previousHost: com.eggman.backstage.host.DesktopHost? = null
    @Before fun isolateHost() { previousHost = DesktopHosts.current; DesktopHosts.current = null }
    @After fun restoreHost() { DesktopHosts.current = previousHost }

    private val settings = Settings(MemoryStore()).apply { provider = com.eggman.backstage.data.Provider.ANTHROPIC }
    private val store = object : BackstageSettingsStore by CoreSettingsStore(settings) {
        override suspend fun fetchModels(providerId: String, force: Boolean): ModelListResult =
            ModelListResult.Unavailable("Offline test")
        override suspend fun detectContext(providerId: String) = Unit
    }

    @Test fun typoResultOpensItsGroupAndTheOriginalToggleStillPersists() {
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("plann")
        compose.onNodeWithText("Plan Mode").performClick()
        compose.onNodeWithText("How the agent works").assertExists()
        compose.onNodeWithText("Model & provider").assertDoesNotExist()
        val before = settings.planMode
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(!before, settings.planMode) }
        compose.onNodeWithText("Back to search results").performScrollTo().performClick()
        compose.onNodeWithText("Plan Mode").assertExists()
        compose.onNodeWithText("How the agent works").assertDoesNotExist()
    }

    @Test fun cardsStartCollapsedAndOpenOnTap() {
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Model & provider").assertExists()
        compose.onNodeWithText("API key").assertDoesNotExist()
        compose.onNodeWithText("Model & provider").performClick()
        compose.onNodeWithText("API key").assertExists()
        compose.onNodeWithText("Model & provider").performClick()
        compose.onNodeWithText("API key").assertDoesNotExist()
    }

    @Test fun noMatchesAndClearRestoreTheCards() {
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("zzzzzzzz")
        compose.onNodeWithText("No settings match").assertExists()
        compose.onNodeWithText("Model & provider").assertDoesNotExist()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Model & provider").assertExists()
        compose.onNodeWithText("Instructions").assertExists()
    }

    @Test fun appAndSkillsSynonymsOpenTheirDestinations() {
        var apps = 0
        var skills = 0
        compose.setContent { SettingsScreen(store, {}, {}, onOpenSkills = { skills++ }, onOpenAppSettings = { apps++ }) }
        compose.onNodeWithText("Search settings").performTextInput("linux")
        compose.onNodeWithText("App settings").performClick()
        compose.runOnIdle { assertEquals(1, apps) }
        compose.onNodeWithText("Search settings").performTextReplacement("plugins")
        compose.onNodeWithText("Skills").performClick()
        compose.runOnIdle { assertEquals(1, skills) }
    }

    @Test fun credentialSynonymOpensTheProviderFieldAndPreservesItsBinding() {
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("credential")
        compose.onNodeWithText("API key").performClick()
        compose.onNodeWithText("Model & provider").assertExists()
        compose.onNodeWithText("API key").performScrollTo().performTextInput("unit-test-key")
        compose.runOnIdle { assertEquals("unit-test-key", settings.apiKey()) }
    }

    @Test fun desktopResultIsAvailableWhenTheHostExists() {
        DesktopHosts.current = com.eggman.backstage.testutil.FakeDesktopHost()
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("dex")
        compose.onNodeWithText("Desktop popup").performScrollTo().performClick()
        compose.onNodeWithText("Pop out when leaving the app").assertExists()
        compose.onNodeWithText("Model & provider").assertDoesNotExist()
        val before = store.popOutOnLeave
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(!before, store.popOutOnLeave) }
    }

    @Test fun registeredToolResultExpandsExperimentalMcpControls() {
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("tap_screen")
        compose.onNode(hasText("tap_screen") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Connections (MCP)").assertExists()
        compose.onNodeWithText("Hide experimental tools").assertExists()
        compose.onNode(hasText("tap_screen") and !hasSetTextAction()).performScrollTo().assertExists()
    }

    @Test fun unavailableDesktopAndAppDestinationsAreNotOffered() {
        assertNull(DesktopHosts.current)
        compose.setContent { SettingsScreen(store, {}, {}) }
        compose.onNodeWithText("Search settings").performTextInput("picture in picture")
        compose.onNodeWithText("Desktop popup").assertDoesNotExist()
        compose.onNodeWithText("Search settings").performTextReplacement("linux")
        compose.onNodeWithText("App settings").assertDoesNotExist()
        compose.onNodeWithText("No settings match").assertExists()
    }
}
