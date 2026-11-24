package com.eggman.backstage.data

import com.eggman.backstage.agent.api.PendingQuestion
import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {
    @Test fun settingsDefaultsAndProviderConfig() {
        val s = Settings(MemoryStore())
        assertEquals(Provider.FREE, s.provider) // fresh install: Free models, Free Auto
        assertTrue(s.providerConfigured())
        assertEquals(Settings.DEFAULT_MCP_PORT, s.mcpPort)
        assertEquals(8485, s.mcpPort)
        s.provider = Provider.ANTHROPIC
        assertFalse(s.providerConfigured())
        s.setApiKey(Provider.ANTHROPIC, "  sk-1  ")
        assertEquals("sk-1", s.apiKey())
        assertTrue(s.providerConfigured())
        s.provider = Provider.OLLAMA
        assertTrue(s.providerConfigured()) // no key needed
        s.setBaseUrl(Provider.OLLAMA, "http://10.0.0.5:11434/v1")
        val c = s.providerConfig()
        assertEquals("http://10.0.0.5:11434/v1", c.baseUrl)
        assertEquals(Provider.OLLAMA.defaultModel, c.model)
        s.setModel(Provider.OLLAMA, "llava")
        assertEquals("llava", s.providerConfig().model)
        s.setCustomModels(Provider.OLLAMA, listOf("llava", "my-model", " "))
        assertEquals(Provider.OLLAMA.suggestedModels + listOf("llava", "my-model"), s.modelOptions(Provider.OLLAMA))
        // vision follows provider default and the global switch
        s.provider = Provider.OPENCODE
        assertFalse(s.providerConfig().vision) // recommended GLM models have no vision
        s.setVisionSupported(Provider.OPENCODE, true)
        assertTrue(s.providerConfig().vision)
        s.vision = false
        assertFalse(s.providerConfig().vision)
        s.maxSteps = 1000
        assertEquals(200, s.maxSteps)
        s.mcpEnabledTools = setOf("tap", "execute_task")
        assertEquals(setOf("tap", "execute_task"), s.mcpEnabledTools)
    }

    @Test fun tokensAreStableUntilRotated() {
        val s = Settings(MemoryStore())
        val t = s.mcpToken
        assertEquals(48, t.length)
        assertEquals(t, s.mcpToken)
        val n = s.rotateMcpToken()
        assertNotEquals(t, n)
        assertEquals(n, s.mcpToken)
    }

    @Test fun legacyModeMapsToTarget() {
        val s = Settings(MemoryStore())
        s.mode = AgentMode.FOREGROUND
        assertEquals(Target.PHONE, s.defaultTarget)
        s.mode = AgentMode.DESKTOP
        assertEquals(Target.DESKTOP, s.defaultTarget)
        assertEquals(Provider.GEMINI, Provider.parse("google"))
        assertNull(Provider.parse("nope"))
    }

    private fun snapshot(id: String, started: Long) = TaskSnapshot(
        id, TaskRequest("Do \"it\"\nnow", Source.TASKER, Target.DESKTOP, planMode = true, attachments = mapOf("k" to "v"), interactive = false, timeoutMs = 9_000),
        TaskStatus.SUCCEEDED, 4, 50, listOf(PlanStep("Open", "done")), "tap [3]", PendingQuestion("q?", true, listOf("a", "b")), "Result", started, started + 10,
    )

    @Test fun recordCodecRoundTrip() {
        val r = TaskRecord(snapshot("t1", 1000), "desktop", 10, 20, 0.5, "m", null, listOf(TranscriptEntry("action", "tap", "ok", 5)))
        val back = TaskRecordCodec.decode(JSONObject(TaskRecordCodec.encode(r).toString()))
        assertEquals(r, back)
        val minimal = TaskRecord(snapshot("t2", 1).copy(lastAction = null, question = null, result = null, finishedAtMs = null,
            request = TaskRequest("x", Source.CHAT)))
        assertEquals(minimal, TaskRecordCodec.decode(TaskRecordCodec.encode(minimal)))
    }

    @Test fun memoryHistoryOrdersAndCaps() = runBlocking {
        val h = MemoryTaskHistory(maxRecords = 3)
        (1..5).forEach { h.save(TaskRecord(snapshot("t$it", it * 100L))) }
        val list = h.list(10)
        assertEquals(listOf("t5", "t4", "t3"), list.map { it.snapshot.id })
        h.delete("t4")
        assertNull(h.get("t4"))
        assertEquals(2, h.list(10).size)
        h.clear()
        assertTrue(h.list(10).isEmpty())
    }

    @Test fun memoryScoringAndExport() {
        val now = 100L * 86_400_000
        val items = listOf(
            com.eggman.backstage.tools.MemoryItem("a", "Anna likes tulips", "agent", now - 1_000),
            com.eggman.backstage.tools.MemoryItem("b", "Parking spot is level 3", "agent", now - 90L * 86_400_000),
            com.eggman.backstage.tools.MemoryItem("c", "Anna's birthday is May 4", "agent", now - 1_000),
        )
        val r = MemoryScoring.rank("When is Anna's birthday?", items, 5, now)
        assertEquals("c", r.first().id)
        assertTrue(r.none { it.id == "b" })
        assertTrue(MemoryScoring.score("parking", "Parking spot is level 3") > 0.9)
        assertTrue(MemoryScoring.score("park", "Parking spot") > 0.6) // prefix match
        assertEquals(0.0, MemoryScoring.score("the a of", "anything"), 0.0)
        val exported = MemoryScoring.export(items, "1.0", now)
        assertEquals(3, exported.getInt("memoryCount"))
        assertEquals(items.map { it.content }, MemoryScoring.import(JSONObject(exported.toString())).map { it.content })
        try { MemoryScoring.import(JSONObject().put("schema", "other")); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }

    @Test fun moduleSettings() {
        val s = Settings(MemoryStore())
        assertEquals("duckduckgo", s.searchProvider)
        s.searchEngine = "https://searx.example"
        assertEquals("searxng", s.searchProvider)
        assertEquals("https://searx.example", s.moduleConfig().searxngUrl)
        s.searchProvider = "Brave"
        s.searchApiKey = " key "
        assertEquals("brave", s.moduleConfig().searchProvider)
        assertEquals("key", s.moduleConfig().searchApiKey)
        s.calendarEnabledIds = setOf(3L, 5L)
        assertEquals(setOf(3L, 5L), s.moduleConfig().calendarEnabledIds)
        s.calendarDefaultReminder = 15
        assertEquals(15, s.moduleConfig().calendarDefaultReminder)
        s.calendarDefaultReminder = null
        assertEquals(null, s.moduleConfig().calendarDefaultReminder)
        s.appRules = mapOf("com.whatsapp" to "Never send voice notes", "x" to " ")
        assertEquals(mapOf("com.whatsapp" to "Never send voice notes"), s.appRules)
    }

    @Test fun secretsAreStoredThroughTheCipher() {
        val store = MemoryStore()
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = "x:" + plain.reversed()
            override fun decrypt(stored: String) = if (stored.startsWith("x:")) stored.removePrefix("x:").reversed() else stored
        }
        val s = Settings(store, cipher)
        s.setApiKey(Provider.OPENAI, "sk-secret")
        assertEquals("x:terces-ks", store.getString("key_OPENAI"))
        assertEquals("sk-secret", s.apiKey(Provider.OPENAI))
        store.putString("key_ANTHROPIC", "legacy-plain")
        assertEquals("legacy-plain", s.apiKey(Provider.ANTHROPIC))
    }

    /** The MCP bearer token is encrypted at rest. */
    @Test fun mcpTokenIsEncryptedAtRestAndLegacyPlainTokensAreMigrated() {
        val cipher = object : SecretCipher {
            var decrypts = 0
            override fun encrypt(plain: String) = if (plain.isEmpty()) "" else "X:" + plain.reversed()
            override fun decrypt(stored: String): String? { decrypts++; return if (stored.startsWith("X:")) stored.removePrefix("X:").reversed() else stored }
        }
        val store = MemoryStore()
        val s = Settings(store, cipher)
        val t = s.mcpToken
        assertTrue(t.length >= 32)
        assertEquals("X:" + t.reversed(), store.getString("mcp_token"))
        assertEquals(t, s.mcpToken)
        val rotated = s.rotateMcpToken()
        assertNotEquals(t, rotated)
        assertEquals(rotated, s.mcpToken)
        // a token written in plain text by an older build keeps working and gets encrypted
        val legacy = MemoryStore(mapOf("mcp_token" to "legacytoken123"))
        val s2 = Settings(legacy, cipher)
        assertEquals("legacytoken123", s2.mcpToken)
        assertEquals("X:" + "legacytoken123".reversed(), legacy.getString("mcp_token"))
        // repeated reads are served from memory (the server reads it on every request)
        val before = cipher.decrypts
        repeat(5) { s2.mcpToken }
        assertEquals(before, cipher.decrypts)
    }
}
