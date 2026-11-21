package com.eggman.backstage.llm

import com.eggman.backstage.data.ModelIds
import com.eggman.backstage.data.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderConfigurationTest {
    private lateinit var server: MockWebServer
    private val ok = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
    private val tools = listOf(ToolSpec("tap", "t", JSONObject("""{"type":"object","properties":{}}""")))

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url(path: String) = server.url(path).toString().trimEnd('/')

    @Test fun oneToolCallPerTurnFlags() = runBlocking {
        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.OPENAI, url("/v1"), "k", "m").complete(LlmRequest("s", listOf(Message.user("x")), tools))
        assertEquals(false, JSONObject(server.takeRequest().body.readUtf8()).getBoolean("parallel_tool_calls"))

        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.GEMINI, url("/v1beta/openai"), "k", "m").complete(LlmRequest("s", listOf(Message.user("x")), tools))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("parallel_tool_calls"))

        val a = AnthropicClient.encodeRequest("m", LlmRequest("s", listOf(Message.user("x")), tools, toolChoice = ToolChoice.REQUIRED))
        val choice = a.getJSONObject("tool_choice")
        assertEquals("any", choice.getString("type"))
        assertTrue(choice.getBoolean("disable_parallel_tool_use"))
        assertEquals("auto", AnthropicClient.encodeRequest("m", LlmRequest("s", listOf(Message.user("x")), tools)).getJSONObject("tool_choice").getString("type"))
    }

    @Test fun veniceAndReasoningEffort() = runBlocking {
        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.VENICE, url("/api/v1"), "k", "m").complete(LlmRequest("s", listOf(Message.user("x"))))
        val v = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(v.getJSONObject("venice_parameters").getBoolean("include_venice_system_prompt"))

        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.OPENAI, url("/v1"), "k", "m", reasoningEffort = "high").complete(LlmRequest("s", listOf(Message.user("x"))))
        assertEquals("high", JSONObject(server.takeRequest().body.readUtf8()).getString("reasoning_effort"))

        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.OPENROUTER, url("/api/v1"), "k", "m", reasoningEffort = "low").complete(LlmRequest("s", listOf(Message.user("x"))))
        assertEquals("low", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("reasoning").getString("effort"))

        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatClient(Provider.OPENAI, url("/v1"), "k", "m", reasoningEffort = "extreme").complete(LlmRequest("s", listOf(Message.user("x"))))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("reasoning_effort"))
    }

    @Test fun urlHandlingForOllamaAndCustom() {
        assertEquals("http://h:11434/v1/chat/completions", OpenAiCompatClient.chatUrl(Provider.OLLAMA, "http://h:11434/"))
        assertEquals("http://h:11434/v1/chat/completions", OpenAiCompatClient.chatUrl(Provider.OLLAMA, "http://h:11434/v1"))
        assertEquals("https://proxy/api/v1/chat/completions", OpenAiCompatClient.chatUrl(Provider.CUSTOM, "https://proxy/api/v1/chat/completions/"))
        assertEquals("https://proxy/api/v1/models", OpenAiCompatClient.modelsUrl(Provider.CUSTOM, "https://proxy/api/v1/chat/completions"))
        assertEquals("https://api.deepseek.com/chat/completions", OpenAiCompatClient.chatUrl(Provider.DEEPSEEK, Provider.DEEPSEEK.defaultBaseUrl))
    }

    @Test fun geminiThoughtSignatureRoundTrips() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"tap","arguments":"{}"},"extra_content":{"google":{"thought_signature":"SIG"}}}]}}]}"""))
        val client = OpenAiCompatClient(Provider.GEMINI, url("/v1beta/openai"), "k", "m")
        val reply = client.complete(LlmRequest("s", listOf(Message.user("x")), tools))
        server.takeRequest()
        server.enqueue(MockResponse().setBody(ok))
        client.complete(LlmRequest("s", listOf(Message.user("x"), reply.message, Message(Role.USER, listOf(Part.ToolResult("c1", "done")))), tools))
        val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(2)
        assertEquals("SIG", sent.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
    }

    @Test fun openCodeRoutesModelFamilies() = runBlocking {
        assertEquals(LlmFactory.OpenCodeFamily.RESPONSES, LlmFactory.openCodeFamily("gpt-5.6-terra"))
        assertEquals(LlmFactory.OpenCodeFamily.MESSAGES, LlmFactory.openCodeFamily("Claude-sonnet-5"))
        assertEquals(LlmFactory.OpenCodeFamily.CHAT, LlmFactory.openCodeFamily("glm-5.3"))
        val base = url("/zen/go/v1")

        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn"}"""))
        LlmFactory.create(ProviderConfig(Provider.OPENCODE, base, "oc-key", "claude-sonnet-5", sessionId = "task-1", userAgent = "Backstage/9"))
            .complete(LlmRequest("s", listOf(Message.user("x"))))
        val m = server.takeRequest()
        assertEquals("/zen/go/v1/messages", m.path)
        assertEquals("Bearer oc-key", m.getHeader("Authorization"))
        assertNull(m.getHeader("x-api-key"))
        assertEquals(java.util.UUID.nameUUIDFromBytes("task-1".toByteArray()).toString(), m.getHeader("x-opencode-session"))
        assertEquals("Backstage/9", m.getHeader("User-Agent"))

        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"function_call","call_id":"fc1","name":"tap","arguments":"{\"index\":1}"}],
            "usage":{"input_tokens":9,"output_tokens":2}}"""))
        val r = LlmFactory.create(ProviderConfig(Provider.OPENCODE, base, "oc-key", "gpt-5.6-terra"))
            .complete(LlmRequest("SYS", listOf(Message.user("x"), Message(Role.ASSISTANT, listOf(Part.ToolCall("a", "tap", JSONObject()))),
                Message(Role.USER, listOf(Part.ToolResult("a", "ok")))), tools, toolChoice = ToolChoice.REQUIRED))
        val resp = server.takeRequest()
        assertEquals("/zen/go/v1/responses", resp.path)
        val body = JSONObject(resp.body.readUtf8())
        assertEquals("SYS", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertEquals("required", body.getString("tool_choice"))
        val input = body.getJSONArray("input")
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
        assertEquals("function_call_output", input.getJSONObject(2).getString("type"))
        assertEquals("fc1", r.message.toolCalls.single().id)
        assertEquals(1, r.message.toolCalls.single().args.getInt("index"))
        assertEquals(9, r.usage.inputTokens)

        server.enqueue(MockResponse().setBody(ok))
        LlmFactory.create(ProviderConfig(Provider.OPENCODE, base, "oc-key", "glm-5.3")).complete(LlmRequest("s", listOf(Message.user("x"))))
        assertEquals("/zen/go/v1/chat/completions", server.takeRequest().path)
    }

    @Test fun providerSetIncludesConfiguredBackends() {
        val names = Provider.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("ANTHROPIC", "OPENAI", "GEMINI", "OPENROUTER", "DEEPSEEK", "OLLAMA", "CUSTOM", "VENICE", "OPENCODE")))
        assertEquals("claude-sonnet-5", Provider.ANTHROPIC.recommended.first { it.default }.id)
        assertEquals(1, Provider.OPENAI.recommended.count { it.default })
        assertFalse(Provider.OPENCODE.recommendedInfo("glm-5.3")!!.vision!!)
        assertTrue(ModelIds.same("claude-sonnet-5-20260101", "claude-sonnet-5"))
        assertTrue(ModelIds.version("gpt-5.6-terra") > ModelIds.version("gpt-5-mini"))
        assertTrue(ProviderConfig(Provider.CUSTOM, "", "", "m").problem()!!.contains("chat-completions URL"))
    }
}

class ModelCatalogTest {
    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes.entries.firstOrNull { request.path!!.startsWith(it.key) }?.value ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }
    private fun base(p: String) = server.url(p).toString().trimEnd('/')

    @Test fun openAiListFiltersAndHidesSnapshots() = runBlocking {
        routes["/v1/models"] = MockResponse().setBody("""{"data":[{"id":"gpt-5.6-terra","created":3},{"id":"gpt-5.6-terra-2026-05-01","created":2},
            {"id":"whisper-1","created":1},{"id":"text-embedding-3","created":1},{"id":"o4-mini","created":1},{"id":"gpt-6-astra","created":4},{"id":"dall-e-3","created":5}]}""")
        val r = ModelCatalog().fetch(ProviderConfig(Provider.OPENAI, base("/v1"), "k", "m")) as ModelListResult.Loaded
        assertEquals(listOf("gpt-6-astra", "gpt-5.6-terra", "o4-mini"), r.models.map { it.id })
        assertEquals(listOf("gpt-5.6-terra-2026-05-01"), r.hidden.map { it.id })
        assertEquals("Bearer k", seen.last().getHeader("Authorization"))
    }

    @Test fun anthropicAndOpenRouterCapabilities() = runBlocking {
        routes["/v1/models"] = MockResponse().setBody("""{"data":[{"id":"claude-sonnet-5","display_name":"Claude Sonnet 5","created_at":"2026-01-01",
            "max_input_tokens":200000,"capabilities":{"image_input":{"supported":true}}}]}""")
        val a = ModelCatalog().fetch(ProviderConfig(Provider.ANTHROPIC, base(""), "ak", "m")) as ModelListResult.Loaded
        assertEquals(200_000L, a.models.single().context)
        assertTrue(a.models.single().vision!!)
        assertEquals("ak", seen.last().getHeader("x-api-key"))
        assertTrue(seen.last().path!!.contains("limit=1000"))

        routes["/api/v1/key"] = MockResponse().setBody("{}")
        routes["/api/v1/models"] = MockResponse().setBody("""{"data":[
            {"id":"a/text","name":"T","context_length":1000,"supported_parameters":["tools"],"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
            {"id":"b/img","architecture":{"output_modalities":["image"]}}]}""")
        val o = ModelCatalog().fetch(ProviderConfig(Provider.OPENROUTER, base("/api/v1"), "ok", "m")) as ModelListResult.Loaded
        assertEquals(listOf("a/text"), o.models.map { it.id })
        assertTrue(o.models.single().tools!! && o.models.single().vision!!)
    }

    @Test fun geminiPaginatesAndFilters() = runBlocking {
        routes["/v1beta/models?pageSize=1000&pageToken=p2"] = MockResponse().setBody("""{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""")
        routes["/v1beta/models?pageSize=1000"] = MockResponse().setBody("""{"models":[
            {"name":"models/gemini-3.8-flash","displayName":"G","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-3-flash-image","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"p2"}""")
        val g = ModelCatalog().fetch(ProviderConfig(Provider.GEMINI, base("/v1beta/openai"), "gk", "m")) as ModelListResult.Loaded
        assertEquals(setOf("gemini-3.8-flash", "gemini-3.5-flash-lite"), g.models.map { it.id }.toSet())
        assertEquals("gk", seen.first().getHeader("x-goog-api-key"))
    }

    @Test fun keyRejectedUnavailableCacheAndOllama() = runBlocking {
        var now = 0L
        val cat = ModelCatalog(clock = { now })
        routes["/v1/models"] = MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key"}}""")
        assertTrue(cat.fetch(ProviderConfig(Provider.DEEPSEEK, base("/v1"), "bad", "m")) is ModelListResult.KeyRejected)
        routes["/v1/models"] = MockResponse().setResponseCode(500).setBody("oops")
        assertTrue(cat.fetch(ProviderConfig(Provider.DEEPSEEK, base("/v1"), "k", "m")) is ModelListResult.Unavailable)
        routes["/v1/models"] = MockResponse().setBody("""{"data":[{"id":"deepseek-flash"}]}""")
        val c = ProviderConfig(Provider.DEEPSEEK, base("/v1"), "k", "m")
        assertTrue(cat.fetch(c) is ModelListResult.Loaded)
        val calls = seen.size
        cat.fetch(c)
        assertEquals(calls, seen.size) // cached
        now += 7 * 3_600_000L
        cat.fetch(c)
        assertEquals(calls + 1, seen.size) // expired

        routes["/api/tags"] = MockResponse().setBody("""{"models":[{"name":"qwen3-vl:8b"},{"name":"llama3:8b"}]}""")
        val ol = cat.fetch(ProviderConfig(Provider.OLLAMA, base(""), "", "m")) as ModelListResult.Loaded
        assertEquals(setOf("qwen3-vl:8b", "llama3:8b"), ol.models.map { it.id }.toSet())
        assertTrue(ol.models.first { it.id.startsWith("qwen") }.vision!!)
    }

    @Test fun mergeKeepsRecommendedFirst() {
        val m = ModelCatalog().merge(Provider.ANTHROPIC, ModelListResult.Loaded(listOf(
            com.eggman.backstage.data.ModelInfo("claude-sonnet-5-20260101", context = 123),
            com.eggman.backstage.data.ModelInfo("claude-experimental"),
        )))
        assertEquals("claude-sonnet-5", m.recommended.first().id)
        assertEquals(listOf("claude-experimental"), m.all.map { it.id })
        assertEquals(3, ModelCatalog().merge(Provider.ANTHROPIC, ModelListResult.Unavailable("x")).recommended.size)
    }

    @Test fun validationClassifiesErrors() = runBlocking {
        val cfg = ProviderConfig(Provider.OPENAI, base("/v1"), "k", "gpt-x")
        routes["/v1/chat/completions"] = MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}""")
        assertTrue(ModelCatalog().validate(cfg).ok)
        routes["/v1/chat/completions"] = MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}""")
        assertEquals(ValidationResult.Kind.AUTH, ModelCatalog().validate(cfg).kind)
        routes["/v1/chat/completions"] = MockResponse().setResponseCode(404).setBody("""{"error":{"message":"The model gpt-x does not exist"}}""")
        assertEquals(ValidationResult.Kind.MODEL, ModelCatalog().validate(cfg).kind)
        assertEquals(ValidationResult.Kind.NETWORK, ModelCatalog.classify(cfg, null, "Unable to resolve host api.openai.com").kind)
        assertEquals(ValidationResult.Kind.OTHER, ModelCatalog.classify(cfg, LlmErrorKind.BAD_REQUEST, "weird").kind)
    }
}
