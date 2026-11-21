package com.eggman.backstage.omp

import com.eggman.backstage.data.Provider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmpConfigContextTest {
    @Test fun customProviderModelCarriesContextWindowAndMaxTokens() {
        val models = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.VENICE, Provider.VENICE.defaultBaseUrl,
            "deepseek-v4-1-flash-1017-uncensored", true, context = 512_000, maxTokens = 8192)), Provider.VENICE, "deepseek-v4-1-flash-1017-uncensored").models
        assertTrue(models, "      - id: \"deepseek-v4-1-flash-1017-uncensored\"\n        contextWindow: 512000\n        maxTokens: 8192" in models)
        assertFalse(models, "modelOverrides" in models)
    }

    @Test fun customProviderWithNoKnownWindowEmitsNoContextWindow() {
        val models = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.VENICE, Provider.VENICE.defaultBaseUrl,
            "m", true, context = null)), Provider.VENICE, "m").models
        assertFalse(models, "contextWindow" in models)  // never cap a custom model at a defaulted window
    }

    @Test fun customProviderModelDeclaresVisionInputModalities() {
        val withVision = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.VENICE, Provider.VENICE.defaultBaseUrl,
            "m", true, vision = true)), Provider.VENICE, "m").models
        assertTrue(withVision, "        input: [text, image]" in withVision)
        val noVision = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.VENICE, Provider.VENICE.defaultBaseUrl,
            "m", true, vision = false)), Provider.VENICE, "m").models
        assertTrue(noVision, "        input: [text]" in noVision)
    }

    @Test fun builtInProviderGetsAnOverrideOnlyForAKnownValue() {
        val known = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.ANTHROPIC, Provider.ANTHROPIC.defaultBaseUrl,
            "claude-sonnet-5", true, context = 1_000_000)), Provider.ANTHROPIC, "claude-sonnet-5").models
        assertTrue(known, "    modelOverrides:\n      \"claude-sonnet-5\":\n        contextWindow: 1000000" in known)
        assertFalse(known, "    models:" in known)
        val unknown = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.ANTHROPIC, Provider.ANTHROPIC.defaultBaseUrl,
            "claude-sonnet-5", true)), Provider.ANTHROPIC, "claude-sonnet-5").models
        assertFalse(unknown, "modelOverrides" in unknown)
    }
}
