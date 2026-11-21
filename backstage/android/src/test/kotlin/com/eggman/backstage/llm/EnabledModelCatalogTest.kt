package com.eggman.backstage.llm

import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import com.eggman.backstage.omp.OmpConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EnabledModelCatalogTest {
    @Test fun endpointListStoresCapabilitiesForEverySelectableModel() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{}")) // Venice key validation.
            server.enqueue(MockResponse().setBody("""{"data":[
                {"id":"main","model_spec":{"availableContextTokens":128000,"capabilities":{"supportsVision":false,"supportsFunctionCalling":true}}},
                {"id":"other","model_spec":{"availableContextTokens":777000,"capabilities":{"supportsVision":true,"supportsFunctionCalling":true}}}
            ]}"""))
            val settings = Settings(MemoryStore()).apply {
                provider = Provider.VENICE
                setApiKey(provider, "synthetic-key")
                setBaseUrl(provider, server.url("/v1").toString())
                setModel(provider, "main")
                setEnabledModels(provider, setOf("other"))
            }
            val result = ContextDetect.fetchModels(settings, force = true) as ModelListResult.Loaded
            assertEquals(setOf("main", "other"), result.models.map { it.id }.toSet())
            assertEquals(777000L, settings.detectedContext(Provider.VENICE, "other"))
            assertEquals(true, settings.detectedCapability(Provider.VENICE, "other", "vision"))
            assertTrue(OmpConfig.from(settings).models.contains("      - id: \"other\"\n        input: [text, image]\n        contextWindow: 777000"))
            assertEquals("/v1/api_keys/rate_limits", server.takeRequest().path)
            assertEquals("/v1/models?type=text", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
