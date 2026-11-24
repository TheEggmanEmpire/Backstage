package com.eggman.backstage.omp

import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.RemoteMcpServer
import com.eggman.backstage.data.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OmpConfigTest {
    @Test fun enabledModelsKeepChatMainAndExposeEveryCustomModelAndBuiltinSelector() {
        val settings = Settings(MemoryStore()).apply {
            provider = Provider.CUSTOM
            setBaseUrl(Provider.CUSTOM, "https://custom.test/v1/chat/completions")
            setApiKey(Provider.CUSTOM, "synthetic-secret")
            setModel(Provider.CUSTOM, "main")
            setEnabledModels(Provider.CUSTOM, setOf("other", "main"))
            setDetectedContext(Provider.CUSTOM, "other", 777_000)
            setDetectedCapability(Provider.CUSTOM, "other", "vision", true)
            setApiKey(Provider.OPENAI, "synthetic-builtin-secret")
            setModel(Provider.OPENAI, "gpt-main")
            setEnabledModels(Provider.OPENAI, setOf("gpt-main", "gpt-other"))
        }
        val files = OmpConfig.from(settings)
        assertTrue(files.config.startsWith("modelRoles:\n  default: \"backstage-custom/main\"\n"))
        for (selector in listOf("backstage-custom/main", "backstage-custom/other", "openai/gpt-main", "openai/gpt-other"))
            assertTrue(selector, files.config.contains("  - \"$selector\""))
        assertTrue(files.config.contains("enabledModels:\n"))
        assertTrue(files.config.contains("cycleOrder:\n"))
        assertTrue(files.models.contains("      - id: \"main\""))
        assertTrue(files.models.contains("      - id: \"other\"\n        input: [text, image]\n        contextWindow: 777000"))
        assertFalse(files.models.contains("      - id: \"gpt-other\""))
        assertFalse((files.models + files.config).contains("synthetic-secret"))
    }
    @Test fun noActiveModelOmitsTheDefaultSelector() {
        val settings = Settings(MemoryStore()).apply { setEnabledModels(provider, emptySet()) }
        val files = OmpConfig.from(settings)
        assertFalse(files.config.contains("modelRoles:"))
        assertFalse(files.config.contains("/${settings.provider.defaultModel}\""))
    }

    private fun mcpFiles(servers: List<RemoteMcpServer>) = OmpConfig.from(Settings(MemoryStore()).apply {
        remoteMcpServers = servers
    })
    @Test fun cachedMcpCredentialReferenceNeverMovesToAnotherServer() {
        val original = RemoteMcpServer("a-b", "Original", "https://original.test/mcp", "original-secret")
        val colliding = RemoteMcpServer("A_B", "New", "https://new.test/mcp", "other-secret")
        val cached = JSONObject(mcpFiles(listOf(original)).mcp).getJSONObject("mcpServers")
            .getJSONObject("Original").getJSONObject("headers").getString("Authorization").substringAfterLast(' ')
        assertEquals("Bearer original-secret", OmpHarness.mcpKey(cached, listOf(colliding, original)))
        assertNull(OmpHarness.mcpKey(cached, listOf(colliding)))
        val updated = JSONObject(mcpFiles(listOf(colliding, original)).mcp).getJSONObject("mcpServers")
            .getJSONObject("Original").getJSONObject("headers").getString("Authorization").substringAfterLast(' ')
        assertEquals(cached, updated)
    }
    @Test fun duplicateMcpIdsCannotSelectACredential() {
        val server = RemoteMcpServer("same", "One", "https://one.test", "one-secret")
        val duplicate = server.copy(name = "Two", url = "https://two.test", token = "two-secret")
        assertNull(OmpHarness.mcpKey(OmpConfig.mcpSlug(server), listOf(server, duplicate)))
        assertThrows(IllegalArgumentException::class.java) { Settings(MemoryStore()).remoteMcpServers = listOf(server, duplicate) }
    }
    @Test fun remoteMcpUsesHttpAndCredentialCommandsWithoutWritingTokens() {
        val server = RemoteMcpServer("A_B", "Remote", "https://remote.test/mcp", "synthetic-mcp-secret")
        val files = mcpFiles(listOf(server))
        val remote = JSONObject(files.mcp).getJSONObject("mcpServers").getJSONObject("Remote")
        assertEquals("http", remote.getString("type"))
        assertEquals(server.url, remote.getString("url"))
        assertTrue(remote.getBoolean("enabled"))
        assertEquals("!backstage-omp-key ${OmpConfig.mcpSlug(server)}", remote.getJSONObject("headers").getString("Authorization"))
        assertFalse((files.models + files.config + files.mcp).contains(server.token))
        assertEquals("{\"mcpServers\":{}}", mcpFiles(emptyList()).mcp)
    }
    @Test fun remoteMcpOmitsDisabledAndAllKnownToolsOffButSharesPartialAndUnknown() {
        val servers = listOf(
            RemoteMcpServer("disabled", "Disabled", "https://remote.test", enabled = false),
            RemoteMcpServer("all", "All off", "https://remote.test", knownTools = setOf("a", "b"), disabledTools = setOf("a", "b", "old")),
            RemoteMcpServer("partial", "Partial", "https://remote.test", knownTools = setOf("a", "b"), disabledTools = setOf("a")),
            RemoteMcpServer("unknown", "Unknown", "https://remote.test", disabledTools = setOf("old")),
        )
        val shared = JSONObject(mcpFiles(servers).mcp).getJSONObject("mcpServers")
        assertEquals(setOf("Partial", "Unknown"), shared.keys().asSequence().toSet())
        assertEquals("{\"mcpServers\":{}}", mcpFiles(servers.take(2)).mcp)
    }
    @Test fun remoteMcpEscapesNamesAndUrlsAndAllocatesUniqueFallbackNames() {
        val reservedName = OmpConfig.mcpSlug(RemoteMcpServer("one", "", "https://one.test"))
        val servers = listOf(
            RemoteMcpServer("one", "Duplicate", "https://one.test"),
            RemoteMcpServer("two", "Duplicate", "https://two.test"),
            RemoteMcpServer("blank", " ", "https://blank.test"),
            RemoteMcpServer("reserved", reservedName, "https://reserved.test"),
            RemoteMcpServer("escaped", "Quoted\"\\\n\u0001", "https://escaped.test/\"\\\n\u0002"),
        )
        val shared = JSONObject(mcpFiles(servers).mcp).getJSONObject("mcpServers")
        assertEquals(servers.size, shared.length())
        assertFalse(shared.has("Duplicate"))
        assertEquals("https://reserved.test", shared.getJSONObject(reservedName).getString("url"))
        assertEquals(servers.last().url, shared.getJSONObject(servers.last().name).getString("url"))
        assertEquals(servers.map { it.url }.toSet(), shared.keys().asSequence().map { shared.getJSONObject(it).getString("url") }.toSet())
    }
    @Test fun mcpSlugsAreDeterministicValidAndCollisionSafeEvenAfterTruncation() {
        val servers = listOf("A_B", "a-b", "a-b-2", "!!!", "", "日本語", "x".repeat(100), "x".repeat(100) + "Y")
            .map { RemoteMcpServer(it, "", "https://remote.test") }
        val slugs = servers.map { OmpConfig.mcpSlug(it) }
        assertEquals(servers.size, slugs.toSet().size)
        assertTrue(slugs.all { it.matches(Regex("^[a-z0-9-]{1,64}$")) && it.startsWith("mcp-") })
        assertEquals("mcp-6c1908f93f8dee4431404ef7a45823bc47949c25", OmpConfig.mcpSlug(servers.first()))
        assertEquals("mcp-d44362d67d921091c7b9674d752e9e23c1f9ec8a", OmpConfig.mcpSlug(servers[1]))
        assertEquals(slugs.reversed(), servers.reversed().map { OmpConfig.mcpSlug(it) })
        val shared = JSONObject(mcpFiles(servers).mcp).getJSONObject("mcpServers")
        assertEquals(slugs.toSet(), shared.keys().asSequence().toSet())
        for (slug in slugs) assertEquals("!backstage-omp-key $slug", shared.getJSONObject(slug).getJSONObject("headers").getString("Authorization"))
    }
    @Test fun mcpSlugsStayConsistentWhenACollidingServerIsDisabled() {
        val servers = listOf(
            RemoteMcpServer("A_B", "Off", "https://off.test", enabled = false),
            RemoteMcpServer("a-b", "On", "https://on.test", "synthetic-mcp-secret"),
        )
        val shared = JSONObject(mcpFiles(servers).mcp).getJSONObject("mcpServers")
        val slug = OmpConfig.mcpSlug(servers.last())
        assertEquals("mcp-d44362d67d921091c7b9674d752e9e23c1f9ec8a", slug)
        assertEquals("!backstage-omp-key $slug", shared.getJSONObject("On").getJSONObject("headers").getString("Authorization"))
        assertEquals("Bearer synthetic-mcp-secret", OmpHarness.mcpKey(slug, servers))
        assertNull(OmpHarness.mcpKey("mcp-unknown", servers))
        assertNull(OmpHarness.mcpKey(slug, servers.map { it.copy(token = "") }))
    }
    @Test fun everyProviderMapsAndNeverContainsASecret() {
        val ids = listOf("anthropic", "openai", "openai-codex", "google", "openrouter", "deepseek", "backstage-ollama", "backstage-custom", "backstage-venice", "backstage-opencode")
        assertEquals(ids.toSet(), Provider.entries.map { OmpConfig.providerId(it) }.toSet())
        for (p in Provider.entries) {
            val c = OmpConfig.generate(listOf(OmpConfig.Entry(p, p.defaultBaseUrl, "test/model", true)), p, "test/model")
            assertTrue(c.models.contains("!backstage-omp-key ${OmpConfig.providerId(p)}"))
            assertTrue(c.config.contains("${OmpConfig.providerId(p)}/test/model"))
            if (p == Provider.CHATGPT) {
                assertTrue(c.models.contains("oauth"))
                assertTrue(c.models.contains("chatgpt-account-id"))
            }
            if (p == Provider.GEMINI) assertFalse(c.models.contains("/openai"))
        }
    }
    private val builtinBases = mapOf(
        Provider.ANTHROPIC to "https://api.anthropic.com",
        Provider.OPENAI to "https://api.openai.com/v1",
        Provider.GEMINI to "https://generativelanguage.googleapis.com/v1beta",
        Provider.OPENROUTER to "https://openrouter.ai/api/v1",
        Provider.DEEPSEEK to "https://api.deepseek.com",
        Provider.CHATGPT to "https://chatgpt.com/backend-api",
    )
    private fun assertBuiltin(provider: Provider) {
        val files = OmpConfig.generate(listOf(OmpConfig.Entry(provider, provider.defaultBaseUrl, provider.defaultModel, true)), provider, provider.defaultModel)
        assertFalse("$provider must be override-only", files.models.contains("    models:"))
        assertFalse("$provider must inherit its catalog API", files.models.contains("    api:"))
        assertTrue("$provider must use its omp catalog base", files.models.contains("    baseUrl: ${OmpConfig.yaml(builtinBases.getValue(provider))}"))
        assertTrue(files.models.contains("apiKey: ${OmpConfig.yaml("!backstage-omp-key ${OmpConfig.providerId(provider)}")}"))
    }
    @Test fun anthropicIsOverrideOnly() = assertBuiltin(Provider.ANTHROPIC)
    @Test fun openaiIsOverrideOnly() = assertBuiltin(Provider.OPENAI)
    @Test fun googleIsOverrideOnly() = assertBuiltin(Provider.GEMINI)
    @Test fun openrouterIsOverrideOnly() = assertBuiltin(Provider.OPENROUTER)
    @Test fun deepseekIsOverrideOnly() = assertBuiltin(Provider.DEEPSEEK)
    @Test fun codexIsOverrideOnly() = assertBuiltin(Provider.CHATGPT)
    @Test fun changedBuiltinUrlIsPreserved() {
        val files = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.ANTHROPIC, "https://relay.test/v1", "model", true)), Provider.ANTHROPIC, "model")
        assertTrue(files.models.contains("https://relay.test/v1"))
        assertFalse(files.models.contains("    models:"))
    }
    @Test fun everyCustomProviderHasACompleteDefinition() {
        for (provider in listOf(Provider.CUSTOM, Provider.OLLAMA, Provider.VENICE, Provider.OPENCODE)) {
            val files = OmpConfig.generate(listOf(OmpConfig.Entry(provider, "https://endpoint.test/v1/chat/completions/", "custom-model", true)), provider, "custom-model")
            assertTrue("$provider API", files.models.contains("    api: \"openai-completions\""))
            assertTrue("$provider base URL", files.models.contains("    baseUrl: \"https://endpoint.test/v1\""))
            assertTrue("$provider model", files.models.contains("    models:\n      - id: \"custom-model\""))
        }
    }
    @Test fun emitFixturesForTheBundledParser() {
        val output = java.io.File(System.getProperty("omp.fixture.dir", "build/omp-config-fixtures"))
        fun write(name: String, entries: List<OmpConfig.Entry>, current: Provider) {
            val files = OmpConfig.generate(entries, current, entries.first { it.provider == current }.model)
            java.io.File(output, name).apply { mkdirs() }.let {
                java.io.File(it, "models.yml").writeText(files.models)
                java.io.File(it, "config.yml").writeText(files.config)
            }
        }
        val entries = Provider.entries.map { OmpConfig.Entry(it, if (it == Provider.CUSTOM) "https://example.test/v1/chat/completions" else it.defaultBaseUrl, it.defaultModel.ifBlank { "custom-model" }, it.requiresKey) }
        entries.forEach { write(it.provider.name.lowercase(), listOf(it), it.provider) }
        write("mixed", entries, Provider.OPENAI)
        write("multi", entries + entries.map { it.copy(model = it.model + "-other", context = 512_000, vision = true) }, Provider.CUSTOM)
    }
    @Test fun customStripsCompletionSuffixAndSupportsKeyless() {
        val c = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.CUSTOM, "https://example.test/v1/chat/completions/", "my-model", false)), Provider.CUSTOM, "my-model")
        assertTrue(c.models.contains("https://example.test/v1"))
        assertFalse(c.models.contains("/chat/completions"))
        assertTrue(c.models.contains("\"none\""))
        assertTrue(c.models.contains("openai-completions"))
        assertFalse(c.models.contains("apiKey:"))
    }
    @Test fun yamlEscapesQuotesNewlinesAndControls() {
        assertEquals("\"a\\\"\\\\\\n\\r\\t\\u0001\"", OmpConfig.yaml("a\"\\\n\r\t\u0001"))
        val c = OmpConfig.generate(listOf(OmpConfig.Entry(Provider.CUSTOM, "https://e/\nproviders: evil", "x\"\ny", true)), Provider.CUSTOM, "x\"\ny")
        assertFalse(c.models.contains("\nproviders: evil"))
        assertTrue(c.models.contains("x\\\"\\ny"))
    }
}
