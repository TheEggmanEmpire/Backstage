package com.eggman.backstage.llm

import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import com.eggman.backstage.omp.OmpConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FreeModelsTest {
    @Before fun clearCooldowns() { FreeModels.cooldown.clear(); FreeModels.rejectsImages.clear() }

    private val caps = mapOf(
        "ovh/DeepSeek-V4.1-Flash-1017" to FreeModels.Caps(1_048_576, null),
        "kilo/nvidia/nemotron-3.5-super-vl:free" to FreeModels.Caps(1_000_000, true),
        "kilo/inclusionai/ling-3.2-flash:free" to FreeModels.Caps(1_048_576, true),
        "kilo/reflection/beam-free" to FreeModels.Caps(1_048_576, false),
        "ovh/Qwen3.8-27B" to FreeModels.Caps(262_144, null),
        "kilo/nvidia/nemotron-3-ultra-550b-a55b:free" to FreeModels.Caps(1_000_000, false),
        "kilo/kilo-auto/free" to FreeModels.Caps(256_000, false),
        "ovh/gpt-oss-120b" to FreeModels.Caps(131_072, null),
    )

    @Test fun rankingScoresIntelligenceContextAndVision() {
        val ranked = FreeModels.rank(listOf(FreeModels.AUTO) + caps.keys) { caps[it] ?: FreeModels.Caps() }
        val score = ranked.associate { it.id to it.score }
        assertEquals("ovh/DeepSeek-V4.1-Flash-1017", ranked.first().id)
        assertEquals(43.0, score.getValue("ovh/DeepSeek-V4.1-Flash-1017"), 0.0)
        assertEquals(28.9, score.getValue("kilo/nvidia/nemotron-3.5-super-vl:free"), 0.0)
        assertEquals(28.0, score.getValue("kilo/reflection/beam-free"), 0.0)
        assertEquals(26.0, score.getValue("ovh/Qwen3.8-27B"), 0.0)
        // Unscored model: median of the known scores (4, 12, 14, 18, 20, 33) = 16.
        val ling = ranked.first { it.id == "kilo/inclusionai/ling-3.2-flash:free" }
        assertTrue(ling.estimated)
        assertEquals(16.0, ling.intelligence, 0.0)
        assertEquals("routers go last", "kilo/kilo-auto/free", ranked.last().id)
        assertFalse("Free Auto is not its own candidate", ranked.any { it.id == FreeModels.AUTO })
    }

    @Test fun autoContextIsTheSmallestWindowFreeAutoCanReach() {
        val ranked = FreeModels.rank(caps.keys) { caps[it] ?: FreeModels.Caps() }
        assertEquals(131_072L, FreeModels.autoContext(ranked, 128_000))
        assertEquals(128_000L, FreeModels.autoContext(emptyList(), 128_000))
    }

    @Test fun catalogKeepsFreeChatModelsWithTheirCaps() {
        val kilo = JSONObject("""{"data":[
            {"id":"a/b:free","name":"B","isFree":true,"context_length":262144,"architecture":{"input_modalities":["text","image"]}},
            {"id":"paid/x","isFree":false,"context_length":1}]}""")
        val k = FreeModels.parseCatalog(FreeModels.KILO, kilo)
        assertEquals(listOf("kilo/a/b:free"), k.map { it.id })
        assertEquals(262_144L, k[0].context); assertEquals(true, k[0].vision)
        val ovh = JSONObject("""{"data":[{"id":"Qwen3.8-27B","context_length":262144},{"id":"nvr-tts-de-de","context_length":0},
            {"id":"solaria-3","context_length":0},{"id":"bge-m3","context_length":8192},{"id":"Qwen3Guard-Gen-8B","context_length":32768}]}""")
        val o = FreeModels.parseCatalog(FreeModels.OVH, ovh)
        assertEquals(listOf("ovh/Qwen3.8-27B"), o.map { it.id })
        assertNull("OVH does not report vision", o[0].vision)
    }

    private class Fake(val id: String, val fail: LlmException?) : LlmClient {
        override val provider = Provider.FREE
        override val model = id
        var calls = 0
        override suspend fun complete(request: LlmRequest): LlmReply {
            calls++
            fail?.let { throw it }
            return LlmReply(Message.assistant("ok from $id"), StopReason.END_TURN)
        }
    }

    private fun cand(id: String, vision: Boolean? = null) = FreeModels.Candidate(id, "https://x", id, "L", 0.0, 0.0, false, null, vision)

    @Test fun freeAutoFailsOverAndCoolsDownRateLimitedModels() = runBlocking {
        var now = 1_000_000L
        val fakes = mapOf(
            "a" to Fake("a", LlmException(LlmErrorKind.RATE_LIMIT, "429", 429, retryAfterMs = 5_000)),
            "b" to Fake("b", LlmException(LlmErrorKind.SERVER, "503", 503)),
            "c" to Fake("c", null),
        )
        val auto = FreeAutoClient(listOf(cand("a"), cand("b"), cand("c")), false, { c, _ -> fakes.getValue(c.id) }) { now }
        val reply = auto.complete(LlmRequest("", listOf(Message.user("hi"))))
        assertEquals("c", reply.model)
        assertEquals(now + 5_000, FreeModels.cooldown["a"])
        assertEquals(now + FreeModels.COOLDOWN_MS, FreeModels.cooldown["b"])
        // Next request skips both cooling models.
        auto.complete(LlmRequest("", listOf(Message.user("hi"))))
        assertEquals(1, fakes.getValue("a").calls)
        assertEquals(2, fakes.getValue("c").calls)
        now += 10 * 60_000
        auto.complete(LlmRequest("", listOf(Message.user("hi"))))
        assertEquals(2, fakes.getValue("a").calls)
    }

    @Test fun freeAutoTriesAtMostFourModelsAndThrowsTheLastFailure() = runBlocking {
        val fakes = (1..6).associate { "m$it" to Fake("m$it", LlmException(LlmErrorKind.BAD_REQUEST, "bad $it", 400)) }
        val auto = FreeAutoClient(fakes.keys.map { cand(it) }, false, { c, _ -> fakes.getValue(c.id) })
        val e = runCatching { auto.complete(LlmRequest("", listOf(Message.user("hi")))) }.exceptionOrNull() as LlmException
        assertEquals("bad 4", e.message)
        assertEquals(4, fakes.values.sumOf { it.calls })
    }

    @Test fun freeAutoSkipsAStalledModelAndBenchesIt() = runBlocking {
        val stalled = object : LlmClient {
            override val provider = Provider.FREE
            override val model = "slow"
            override suspend fun complete(request: LlmRequest): LlmReply = kotlinx.coroutines.awaitCancellation()
        }
        val ok = Fake("fast", null)
        val now = 5_000_000L
        val auto = FreeAutoClient(listOf(cand("slow"), cand("fast")), false, { c, _ -> if (c.id == "slow") stalled else ok }, clock = { now }, stallMs = 50)
        assertEquals("fast", auto.complete(LlmRequest("", listOf(Message.user("hi")))).model)
        assertEquals(now + FreeModels.STALL_COOLDOWN_MS, FreeModels.cooldown["slow"])
    }

    @Test fun imageRejectionsDoNotUseUpRetriesAndAreRemembered() = runBlocking {
        val rejects = (1..5).associate { "t$it" to Fake("t$it", LlmException(LlmErrorKind.BAD_REQUEST, "text only", 400)) }
        val eyes = Fake("eyes", null)
        val all = rejects + ("eyes" to eyes)
        val auto = FreeAutoClient(rejects.keys.map { cand(it) } + cand("eyes"), true, { c, _ -> all.getValue(c.id) })
        val withImage = Message(Role.USER, listOf(Part.Text("look"), Part.Image("AAAA")))
        assertEquals("eyes", auto.complete(LlmRequest("", listOf(withImage))).model)
        assertTrue(FreeModels.rejectsImages.containsAll(rejects.keys))
        assertTrue("text-only models are not benched for text", FreeModels.cooldown.isEmpty())
        auto.complete(LlmRequest("", listOf(withImage)))
        assertEquals(1, rejects.values.sumOf { it.calls } / 5)
    }

    @Test fun exhaustedQuotaSitsOutLongButDoesNotDelayTheRetry() = runBlocking {
        val now = 1_000L
        val spent = Fake("spent", LlmException(LlmErrorKind.RATE_LIMIT, "429", 429, retryAfterMs = 37L * 24 * 3_600_000))
        val auto = FreeAutoClient(listOf(cand("spent")), false, { _, _ -> spent }, clock = { now })
        val e = runCatching { auto.complete(LlmRequest("", listOf(Message.user("hi")))) }.exceptionOrNull() as LlmException
        assertEquals(now + FreeModels.MAX_RATE_LIMIT_COOLDOWN_MS, FreeModels.cooldown["spent"])
        assertNull(e.retryAfterMs)
    }

    @Test fun quotaWorded429StillCoolsTheModelDown() {
        val e = LlmHttp.mapHttpError(429, """{"error":{"message":"quota exceeded"}}""")
        assertEquals(FreeModels.COOLDOWN_MS, FreeModels.cooldownFor(e))
    }

    @Test fun screenshotsSkipTextOnlyModels() = runBlocking {
        val fakes = mapOf("text" to Fake("text", null), "eyes" to Fake("eyes", null))
        val auto = FreeAutoClient(listOf(cand("text", vision = false), cand("eyes", vision = true)), true, { c, _ -> fakes.getValue(c.id) })
        val withImage = Message(Role.USER, listOf(Part.Text("look"), Part.Image("AAAA")))
        assertEquals("eyes", auto.complete(LlmRequest("", listOf(withImage))).model)
        assertEquals("text", auto.complete(LlmRequest("", listOf(Message.user("no image")))).model)
    }

    @Test fun freshInstallDefaultsToFreeAuto() {
        val s = Settings(MemoryStore())
        s.initFreeModels()
        assertEquals(Provider.FREE, s.provider)
        assertEquals(FreeModels.AUTO, s.model())
        assertTrue(s.freeModelsEnabled)
        assertNull(s.providerConfig().problem())
        assertTrue(LlmFactory.create(s.providerConfig()) is FreeAutoClient)
        // Adding a key later keeps Free models on and the provider unchanged.
        s.setApiKey(Provider.ANTHROPIC, "sk-test")
        assertTrue(s.freeModelsEnabled)
        assertEquals(Provider.FREE, s.provider)
        assertEquals("backstage-free-ovh/DeepSeek-V4.1-Flash-1017", OmpConfig.mainModelId(s))
    }

    @Test fun existingInstallKeepsItsProviderAndFreeModelsStartOff() {
        val s = Settings(MemoryStore())
        s.setApiKey(Provider.ANTHROPIC, "sk-test")
        s.initFreeModels()
        assertEquals(Provider.ANTHROPIC, s.provider)
        assertFalse(s.freeModelsEnabled)
    }

    @Test fun switchedOffFreeModelsAreNotUsable() {
        val s = Settings(MemoryStore())
        s.initFreeModels()
        s.freeModelsEnabled = false
        assertTrue(s.providerConfig().problem()!!.contains("switched off"))
        assertFalse(OmpConfig.from(s).models.contains("backstage-free"))
    }

    @Test fun ompGetsOneProviderPerLaneAndTheTopModelAsDefault() {
        val s = Settings(MemoryStore())
        s.initFreeModels()
        val files = OmpConfig.from(s)
        assertTrue(files.models.contains("\"backstage-free-kilo\":"))
        assertTrue(files.models.contains("\"backstage-free-ovh\":"))
        assertTrue(files.models.contains("auth: \"none\""))
        assertFalse(files.models.contains("id: \"auto\""))
        assertTrue(files.config.contains("default: \"backstage-free-ovh/DeepSeek-V4.1-Flash-1017\""))
    }
}
