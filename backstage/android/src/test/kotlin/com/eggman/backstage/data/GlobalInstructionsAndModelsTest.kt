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
    @Test fun enabledModelsPersistPerProviderAndAlwaysIncludeMain() {
        val storage = MemoryStore()
        val s = Settings(storage)
        for (p in Provider.entries) {
            val defaults = p.recommended.map { it.id }.ifEmpty { listOf(p.defaultModel) }.filter(String::isNotBlank).toSet()
            assertEquals(p.name, defaults + listOf(s.model(p)).filter(String::isNotBlank), s.enabledModels(p))
        }
        val p = Provider.OPENAI
        s.setEnabledModels(p, setOf(" first ", "second", ""))
        assertEquals(setOf("first", "second", p.defaultModel), Settings(storage).enabledModels(p))
        assertNotNull(storage.getString("models_enabled_OPENAI"))
        s.setModel(p, "second")
        assertEquals("second", s.model(p))
        s.setEnabledModels(p, emptySet()) // All off keeps chat's main.
        assertEquals(setOf("second"), s.enabledModels(p))
        assertTrue(s.modelOptions(p).containsAll(listOf("first", "second")))
        s.setEnabledModels(p, setOf("first", "second", "third")) // All on.
        s.setEnabledModels(p, s.enabledModels(p) - "third")
        assertEquals(setOf("first", "second"), s.enabledModels(p))
        assertEquals(Provider.ANTHROPIC.suggestedModels.toSet(), s.enabledModels(Provider.ANTHROPIC))
    }
}
