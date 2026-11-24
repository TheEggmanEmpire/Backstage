package com.eggman.backstage.data

import com.eggman.backstage.agent.PromptContext
import com.eggman.backstage.agent.SystemPrompt
import com.eggman.backstage.skills.BuiltinSkills
import com.eggman.backstage.skills.SkillStore
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class GlobalInstructionsAndModelsTest {
    @Test fun defaultGuidanceSurvivesBlankOverrideAndNativePromptUsesEffectiveText() {
        val storage = MemoryStore()
        val s = Settings(storage)
        val default = s.effectiveGlobalInstructions()
        for (term in listOf("desktop", "phone", "window_state", "desktop_launch_app", "left", "right", "maximize", "restore", "minimize",
            "desktop_close_windows", "desktop_presentation", "recents", "two or three", "web_search", "fetch_url", "browser_search", "open_url",
            "data", "primary", "independent", "units", "sources")) assertTrue(term, default.contains(term))
        s.globalInstructions = "Always check the source date."
        assertEquals(s.globalInstructions, Settings(storage).effectiveGlobalInstructions())
        val prompt = SystemPrompt.build(PromptContext(null, null, false, false, true, true, true, 0,
            ZoneId.of("UTC"), Locale.US, globalInstructions = s.effectiveGlobalInstructions()))
        assertTrue(prompt.contains("## Global instructions\nAlways check the source date."))
        s.globalInstructions = " \n "
        assertEquals(default, s.effectiveGlobalInstructions())
        assertEquals(default, Settings.DEFAULT_GLOBAL_INSTRUCTIONS)
    }
    @Test fun retiredGuidanceIsNoLongerInstalledAsSkills() {
        assertFalse(BuiltinSkills.all.containsKey("dex-window-management"))
        assertFalse(BuiltinSkills.all.containsKey("web-research-with-default-browser"))
        val dir = java.nio.file.Files.createTempDirectory("global-skills").toFile()
        try {
            val store = SkillStore(dir)
            store.seedExamples(force = true)
            assertFalse(store.list().any { it.name in setOf("dex-window-management", "web-research-with-default-browser") })
        } finally { dir.deleteRecursively() }
    }
    @Test fun removingActiveModelSelectsNextEnabledModel() {
        val storage = MemoryStore()
        val settings = Settings(storage)
        settings.setEnabledModels(Provider.OPENAI, setOf(" first ", "second", ""))
        assertEquals("first", Settings(storage).model(Provider.OPENAI))
        settings.setEnabledModels(Provider.OPENAI, setOf("second"))
        assertEquals("second", Settings(storage).model(Provider.OPENAI))
        assertEquals(setOf("second"), settings.enabledModels(Provider.OPENAI))
        assertTrue(settings.modelOptions(Provider.OPENAI).contains("first"))
        assertEquals(Provider.ANTHROPIC.suggestedModels.toSet(), settings.enabledModels(Provider.ANTHROPIC))
    }

    @Test fun clearingEnabledModelsPersistsAnUnconfiguredProvider() {
        val storage = MemoryStore()
        val settings = Settings(storage).apply { setApiKey(provider, "test-key") }
        settings.setEnabledModels(settings.provider, emptySet())
        val reloaded = Settings(storage)
        assertEquals("", reloaded.model())
        assertTrue(reloaded.enabledModels().isEmpty())
        assertFalse(reloaded.providerConfigured())
        val readiness = com.eggman.backstage.agent.ReadinessCheck.compute(
            com.eggman.backstage.agent.ReadinessInputs(reloaded.providerConfig().problem(), true,
                true, true, false, false, true, true, 35),
        )
        assertFalse(readiness.providerConfigured)
        assertTrue(readiness.problems.single().contains("No model selected"))
        reloaded.setEnabledModels(reloaded.provider, setOf("favorite"))
        assertEquals("favorite", reloaded.model())
        assertTrue(reloaded.providerConfigured())
    }

    @Test fun explicitModelSelectionEnablesItsModel() {
        val settings = Settings(MemoryStore())
        settings.setEnabledModels(settings.provider, emptySet())
        settings.setModel(settings.provider, "manual")
        assertEquals("manual", settings.model())
        assertEquals(setOf("manual"), settings.enabledModels())
    }

    @Test fun savedMainRemainsEnabledWithLegacyPreferences() {
        val settings = Settings(MemoryStore(mapOf("model_OPENAI" to "custom", "models_enabled_OPENAI" to "other")))
        assertEquals("custom", settings.model(Provider.OPENAI))
        assertEquals(setOf("custom", "other"), settings.enabledModels(Provider.OPENAI))
    }
}
