package com.eggman.backstage.surfaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.eggman.backstage.data.*
import com.eggman.backstage.llm.ModelListResult
import com.eggman.backstage.ui.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlobalModelsSettingsRobolectricTest {
    @get:Rule val compose = createComposeRule()

    @Test fun globalInstructionsSaveAndResetPersistOnlyOnSave() {
        val settings = Settings(MemoryStore())
        compose.setContent { GlobalInstructionsSection(CoreSettingsStore(settings)) }
        compose.onNode(hasSetTextAction()).performTextReplacement("Shared override")
        compose.runOnIdle { assertEquals("", settings.globalInstructions) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("Shared override", settings.effectiveGlobalInstructions()) }
        compose.onNodeWithText("Reset to default").performClick()
        compose.runOnIdle {
            assertEquals("", settings.globalInstructions)
            assertEquals(Settings.DEFAULT_GLOBAL_INSTRUCTIONS, settings.effectiveGlobalInstructions())
        }
        compose.onNode(hasSetTextAction()).assertTextContains(Settings.DEFAULT_GLOBAL_INSTRUCTIONS)
    }

    @Test fun modelListLoadsEndpointAndAllAndOneSwitchesKeepMainEnabled() {
        val settings = Settings(MemoryStore()).apply {
            provider = Provider.CUSTOM
            setModel(provider, "first")
            setEnabledModels(provider, setOf("first", "second"))
        }
        var calls = 0
        val store = object : BackstageSettingsStore by CoreSettingsStore(settings) {
            override suspend fun fetchModels(providerId: String, force: Boolean): ModelListResult {
                calls++
                return ModelListResult.Loaded(listOf(ModelInfo("third")))
            }
        }
        compose.setContent {
            var main by remember { mutableStateOf(settings.model()) }
            androidx.compose.foundation.layout.Column {
                ProviderModelsSection(store, Provider.CUSTOM.name, "", "https://endpoint.test", main) { main = it }
            }
        }
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitUntil(5_000) { calls > 0 }
        compose.onNodeWithText("All off").performClick()
        compose.runOnIdle { assertEquals(setOf("first"), settings.enabledModels()) }
        compose.onNodeWithContentDescription("Enable first").assertIsNotEnabled().assertIsOn()
        compose.onNodeWithContentDescription("Enable second").performClick()
        compose.onNodeWithContentDescription("Use second as main model").performClick()
        compose.runOnIdle { assertEquals("second", settings.model()) }
        compose.onNodeWithText("All on").performClick()
        compose.runOnIdle { assertEquals(setOf("first", "second", "third"), settings.enabledModels()) }
        compose.onNodeWithContentDescription("Enable first").performClick()
        compose.runOnIdle { assertEquals(setOf("second", "third"), settings.enabledModels()) }
    }
}
