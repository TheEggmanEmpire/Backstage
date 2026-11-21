package com.eggman.backstage.llm

import com.eggman.backstage.data.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProvidersTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val tools = listOf(
        ToolSpec("tap", "Tap an element", JSONObject("""{"type":"object","properties":{"index":{"type":"integer"}},"required":["index"]}""")),
    )
    private val img = Part.Image("QUJD", "image/jpeg", 10, 20)

    private fun conversation() = listOf(
        Message.user("Open settings"),
        Message(Role.ASSISTANT, listOf(Part.Text("Tapping"), Part.ToolCall("call_1", "tap", JSONObject().put("index", 3)))),
        Message(Role.USER, listOf(Part.ToolResult("call_1", "Tapped", image = img))),
    )

    @Test fun anthropicRequestAndResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
             "content":[{"type":"text","text":"Done tapping"},{"type":"tool_use","id":"toolu_9","name":"tap","input":{"index":5}}],
             "stop_reason":"tool_use","usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":100,"cache_creation_input_tokens":7}}
        """.trimIndent()))
        val client = AnthropicClient(server.url("/").toString(), "sk-ant", "claude-x")
        val reply = client.complete(LlmRequest("SYS", conversation(), tools, maxTokens = 1000))

        val rec = server.takeRequest()
        assertEquals("/v1/messages", rec.path)
        assertEquals("sk-ant", rec.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, rec.getHeader("anthropic-version"))
        val body = JSONObject(rec.body.readUtf8())
        assertEquals("claude-x", body.getString("model"))
        assertEquals(1000, body.getInt("max_tokens"))
        assertEquals("SYS", body.getJSONArray("system").getJSONObject(0).getString("text"))
        assertEquals("tap", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(body.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        val msgs = body.getJSONArray("messages")
        assertEquals(3, msgs.length())
        val assistant = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("tool_use", assistant.getJSONObject(1).getString("type"))
        assertEquals(3, assistant.getJSONObject(1).getJSONObject("input").getInt("index"))
        val result = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("call_1", result.getString("tool_use_id"))
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"))

        assertEquals(StopReason.TOOL_USE, reply.stopReason)
        assertEquals("Done tapping", reply.message.text)
        assertEquals("toolu_9", reply.message.toolCalls.single().id)
        assertEquals(5, reply.message.toolCalls.single().args.getInt("index"))
        assertEquals(227, reply.usage.inputTokens) // total prompt = input + cache read + cache write
        assertEquals(100, reply.usage.cacheReadTokens)
        assertEquals(7, reply.usage.cacheWriteTokens)
    }

    @Test fun anthropicMergesSameRoleAndStartsWithUser() {
        val msgs = AnthropicClient.encodeMessages(listOf(
            Message.assistant("hello"),
            Message.user("a"),
            Message.user("b"),
        ))
        assertEquals("user", msgs.getJSONObject(0).getString("role"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        assertEquals(3, msgs.length())
        assertEquals(2, msgs.getJSONObject(2).getJSONArray("content").length())
    }

    @Test fun anthropicErrorMapping() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        val client = AnthropicClient(server.url("/").toString(), "k", "m")
        try {
            client.complete(LlmRequest("s", listOf(Message.user("x"))))
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.OVERLOADED, e.kind)
            assertTrue(e.retryable)
            assertTrue(e.message!!.contains("Overloaded"))
        }
    }

    @Test fun openAiRequestAndResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"id":"x","model":"gpt-test","choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
             "tool_calls":[{"id":"c7","type":"function","function":{"name":"tap","arguments":"{\"index\":2}"}}]}}],
             "usage":{"prompt_tokens":50,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":20}}}
        """.trimIndent()))
        val client = OpenAiCompatClient(Provider.OPENAI, server.url("/v1").toString(), "sk-oa", "gpt-test")
        val reply = client.complete(LlmRequest("SYS", conversation(), tools, maxTokens = 777, toolChoice = ToolChoice.REQUIRED))

        val rec = server.takeRequest()
        assertEquals("/v1/chat/completions", rec.path)
        assertEquals("Bearer sk-oa", rec.getHeader("Authorization"))
        val body = JSONObject(rec.body.readUtf8())
        assertEquals(777, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertEquals("required", body.getString("tool_choice"))
        val msgs = body.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("Open settings", msgs.getJSONObject(1).getString("content"))
        val asst = msgs.getJSONObject(2)
        assertEquals("tap", asst.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("{\"index\":3}", asst.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("tool", msgs.getJSONObject(3).getString("role"))
        assertEquals("call_1", msgs.getJSONObject(3).getString("tool_call_id"))
        // screenshot travels in a follow-up user message as a data URL
        val follow = msgs.getJSONObject(4).getJSONArray("content")
        assertEquals("image_url", follow.getJSONObject(1).getString("type"))
        assertTrue(follow.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,QUJD"))

        assertEquals(StopReason.TOOL_USE, reply.stopReason)
        assertEquals(2, reply.message.toolCalls.single().args.getInt("index"))
        assertEquals(50, reply.usage.inputTokens)
        assertEquals(20, reply.usage.cacheReadTokens)
    }

    @Test fun openRouterHeadersAndCost() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"cost":0.0012}}
        """.trimIndent()))
        val client = OpenAiCompatClient(Provider.OPENROUTER, server.url("/api/v1").toString(), "or-key", "anthropic/x")
        val reply = client.complete(LlmRequest("s", listOf(Message.user("hey"))))
        val rec = server.takeRequest()
        assertEquals("Backstage", rec.getHeader("X-Title"))
        val body = JSONObject(rec.body.readUtf8())
        assertTrue(body.getJSONObject("usage").getBoolean("include"))
        assertTrue(body.has("max_tokens"))
        assertEquals("Hi", reply.message.text)
        assertEquals(StopReason.END_TURN, reply.stopReason)
        assertEquals(0.0012, reply.usage.reportedCostUsd!!, 1e-9)
    }

    @Test fun ollamaNoKeyObjectArgumentsAndAutoToolChoice() = runBlocking {
        server.enqueue(MockResponse().setBody("""
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"",
              "tool_calls":[{"function":{"name":"tap","arguments":{"index":4}}}]}}]}
        """.trimIndent()))
        val client = OpenAiCompatClient(Provider.OLLAMA, server.url("/v1").toString(), "", "qwen")
        val reply = client.complete(LlmRequest("s", listOf(Message.user("x")), tools, toolChoice = ToolChoice.REQUIRED))
        val rec = server.takeRequest()
        assertNull(rec.getHeader("Authorization"))
        assertEquals("auto", JSONObject(rec.body.readUtf8()).getString("tool_choice"))
        val call = reply.message.toolCalls.single()
        assertEquals(4, call.args.getInt("index"))
        assertTrue(call.id.isNotBlank())
        assertEquals(StopReason.TOOL_USE, reply.stopReason)
    }

    @Test fun deepSeekWithoutVisionReplacesImages() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"prompt_cache_hit_tokens":1}}"""))
        val client = OpenAiCompatClient(Provider.DEEPSEEK, server.url("/v1").toString(), "k", "deepseek-chat", vision = false)
        val reply = client.complete(LlmRequest("s", conversation(), tools))
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("image_url"))
        assertTrue(body.contains("image omitted"))
        assertEquals(1, reply.usage.cacheReadTokens)
    }

    @Test fun geminiCompatErrorEnvelopeArray() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""[{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}]"""))
        val client = OpenAiCompatClient(Provider.GEMINI, server.url("/v1beta/openai").toString(), "g", "gemini-x")
        try {
            client.complete(LlmRequest("s", listOf(Message.user("x"))))
            fail()
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.BAD_REQUEST, e.kind)
            assertTrue(e.message!!.contains("API key not valid"))
        }
        assertEquals("/v1beta/openai/chat/completions", server.takeRequest().path)
    }

    @Test fun errorMappingTable() {
        assertEquals(LlmErrorKind.AUTH, LlmHttp.mapHttpError(401, "{}").kind)
        assertEquals(LlmErrorKind.RATE_LIMIT, LlmHttp.mapHttpError(429, """{"error":{"message":"Rate limit reached"}}""", "7").kind)
        assertEquals(7000L, LlmHttp.mapHttpError(429, "{}", "7").retryAfterMs)
        assertEquals(LlmErrorKind.AUTH, LlmHttp.mapHttpError(429, """{"error":{"message":"You exceeded your current quota"}}""").kind)
        assertEquals(LlmErrorKind.SERVER, LlmHttp.mapHttpError(502, "bad gateway").kind)
        assertEquals(LlmErrorKind.OVERLOADED, LlmHttp.mapHttpError(503, "").kind)
        assertEquals(LlmErrorKind.CONTEXT_LENGTH, LlmHttp.mapHttpError(400, """{"error":{"message":"This model's maximum context length is 128000 tokens"}}""").kind)
        assertEquals(LlmErrorKind.NOT_FOUND, LlmHttp.mapHttpError(404, """{"error":{"message":"model not found"}}""").kind)
        assertEquals(LlmErrorKind.BAD_REQUEST, LlmHttp.mapHttpError(400, "nope").kind)
    }

    @Test fun networkFailureIsRetryable() = runBlocking {
        val url = server.url("/v1").toString()
        server.shutdown()
        val client = OpenAiCompatClient(Provider.OPENAI, url, "k", "m",
            http = LlmHttp(timeouts = HttpTimeouts(connectMs = 500, readMs = 500, callMs = 2000)))
        try {
            client.complete(LlmRequest("s", listOf(Message.user("x"))))
            fail()
        } catch (e: LlmException) {
            assertTrue(e.kind == LlmErrorKind.NETWORK || e.kind == LlmErrorKind.TIMEOUT)
            assertTrue(e.retryable)
        }
    }

    @Test fun factoryValidatesConfig() {
        try {
            LlmFactory.create(ProviderConfig(Provider.OPENAI, "https://x", "", "m"))
            fail()
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.CONFIG, e.kind)
        }
        assertNull(ProviderConfig(Provider.OLLAMA, "http://10.0.0.2:11434/v1", "", "m").problem())
        assertTrue(LlmFactory.create(ProviderConfig(Provider.ANTHROPIC, "https://x", "k", "m")) is AnthropicClient)
        assertTrue(LlmFactory.create(ProviderConfig(Provider.GEMINI, "https://x", "k", "m")) is OpenAiCompatClient)
    }

    @Test fun retryPolicyRetriesTransientOnly() = runTest {
        var calls = 0
        val retries = mutableListOf<Long>()
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, jitter = 0.0)
        val r = policy.run({ _, d, _ -> retries += d }) {
            calls++
            if (calls < 3) throw LlmException(LlmErrorKind.SERVER, "boom")
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(listOf(100L, 200L), retries)

        calls = 0
        try {
            policy.run { calls++; throw LlmException(LlmErrorKind.AUTH, "bad key") }
            fail()
        } catch (e: LlmException) {
            assertEquals(1, calls)
        }
        assertEquals(5000L, policy.copy(maxDelayMs = 60_000).delayFor(1, LlmException(LlmErrorKind.RATE_LIMIT, "x", retryAfterMs = 5000)))
    }

    @Test fun usageMeterAndPrices() {
        val prices = PriceTable.parse("""
            # comment
            claude-x = 3, 15, 0.3
            bad line
        """.trimIndent())
        val meter = UsageMeter(prices)
        meter.record("claude-x", Usage(inputTokens = 1_000_000, outputTokens = 100_000, cacheReadTokens = 500_000))
        // 500k uncached * 3 + 500k cached * 0.3 + 100k * 15 = 1.5 + 0.15 + 1.5
        assertEquals(3.15, meter.costUsd!!, 1e-9)
        meter.record("unknown-model", Usage(inputTokens = 10))
        assertNull(meter.costUsd)
        assertEquals(1_100_010L, meter.usage.totalTokens)
        val reported = UsageMeter()
        reported.record("x", Usage(reportedCostUsd = 0.5))
        assertEquals(0.5, reported.costUsd!!, 1e-9)
    }
}
