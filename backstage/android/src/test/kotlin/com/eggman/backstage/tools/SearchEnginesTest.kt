package com.eggman.backstage.tools

import com.eggman.backstage.exec.AppRole
import com.eggman.backstage.exec.DefaultApp
import com.eggman.backstage.exec.DefaultHow
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.ui.settings.ModuleSettingsLogic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kagi, the custom search engine and browser_search. */
class SearchEnginesTest {
    private val reg = ToolRegistry.default()
    private fun run(ctx: ToolContext, name: String, args: String) = runBlocking { reg.execute(ctx, name, JSONObject(args)) }

    private val kagiV0 = """{"meta":{"id":"1","node":"eu","ms":120,"api_balance":4.2},"data":[
        {"t":0,"rank":1,"url":"https://a.example/","title":"A <b>title</b>","snippet":"first","published":"2026-01-01T00:00:00Z"},
        {"t":1,"list":["related one","related two"]},
        {"t":0,"rank":2,"url":"https://b.example/","title":"B"}]}"""
    private val kagiV1 = """{"meta":{"trace":"t","ms":90},"data":{"search":[{"url":"https://v1.example/","title":"V1","snippet":"s1"}],
        "related_search":[{"url":"https://kagi.com/search?q=x","title":"x"}]}}"""

    @Test fun kagiParsesBothApiVersionsAndSkipsRelatedSearches() {
        val v0 = KagiSearch.parse(kagiV0, 10)
        assertEquals(listOf("https://a.example/", "https://b.example/"), v0.map { it.url })
        assertEquals("A title", v0[0].title)
        assertEquals("first", v0[0].snippet)
        assertEquals(1, KagiSearch.parse(kagiV0, 1).size)
        val v1 = KagiSearch.parse(kagiV1, 10)
        assertEquals(listOf("https://v1.example/"), v1.map { it.url })
    }

    @Test fun kagiErrorsAreReadable() {
        assertTrue(KagiSearch.errorMessage(401, "").contains("rejected the API key"))
        val credit = KagiSearch.errorMessage(402, """{"meta":{},"data":null,"error":[{"code":1,"msg":"Insufficient credit"}]}""")
        assertTrue(credit, credit.contains("no API credit") && credit.contains("Insufficient credit"))
        val v1 = KagiSearch.errorMessage(429, """{"meta":{},"data":[],"error":[{"code":"search.limit","url":"u","message":"Usage limit exhausted"}]}""")
        assertTrue(v1, v1.contains("429") && v1.contains("Usage limit exhausted"))
        assertTrue(KagiSearch.errorMessage(500, "not json").contains("HTTP 500"))
    }

    @Test fun kagiToolUsesV1AndFallsBackToV0Keys() {
        val ctx = TestToolContext()
        ctx.services.config = ModuleConfig(searchProvider = "kagi", searchApiKey = "kk")
        ctx.services.http[KagiSearch.V1] = HttpResult(200, kagiV1)
        val r = run(ctx, "web_search", """{"query":"pizza","num_results":4}""")
        assertTrue(r.text, r.text.contains("https://v1.example/"))
        assertEquals("Bearer kk", ctx.services.posted.last().second["Authorization"])
        val body = JSONObject(ctx.services.posted.last().third)
        assertEquals("pizza", body.getString("query"))
        assertEquals(4, body.getInt("limit"))

        // a v1 refusal (old key): the v0 API with the Bot scheme
        ctx.services.http[KagiSearch.V1] = HttpResult(401, """{"meta":{},"error":[{"code":"auth","url":"u","message":"bad token"}]}""")
        val got = mutableListOf<Map<String, String>>()
        val services = object : FakeServices() {
            override suspend fun httpGet(url: String, headers: Map<String, String>): HttpResult { got += headers; return super.httpGet(url, headers) }
        }
        services.http.putAll(ctx.services.http)
        services.http[KagiSearch.V0] = HttpResult(200, kagiV0)
        services.config = ctx.services.config
        val ctx2 = TestToolContext(services = services)
        val r2 = run(ctx2, "web_search", """{"query":"a b"}""")
        assertTrue(r2.text, r2.text.contains("https://a.example/"))
        assertFalse(r2.text.contains("related one"))
        assertEquals("Bot kk", got.last()["Authorization"])
        assertTrue(services.requested.last().contains("q=a+b") && services.requested.last().contains("limit=5"))

        // both refuse: the error says so
        services.http[KagiSearch.V0] = HttpResult(402, """{"error":[{"code":1,"msg":"No credit"}]}""")
        val r3 = run(ctx2, "web_search", """{"query":"x"}""")
        assertTrue(r3.isError)
        assertTrue(r3.text, r3.text.contains("no API credit"))
        // no key: the keyless engine
        assertEquals("duckduckgo", WebSearch.effectiveProvider(ModuleConfig(searchProvider = "kagi")))
    }

    @Test fun customTemplateEncodingAndValidation() {
        assertEquals("https://s.example/q/k%C3%BCche%20%26%20bad?n=7", CustomSearch.fill("https://s.example/q/{query}?n={count}", "küche & bad", 7))
        assertNull(CustomSearch.templateProblem("https://s.example/api?q={query}"))
        assertNotNull(CustomSearch.templateProblem("https://s.example/api?q=fixed"))
        assertNotNull(CustomSearch.templateProblem(""))
        assertNotNull(CustomSearch.templateProblem("http://public.example/api?q={query}"))
        assertNotNull(CustomSearch.templateProblem("ftp://s.example/{query}"))
        assertNull(CustomSearch.templateProblem("http://192.168.1.5:8080/search?q={query}"))
        assertNotNull(CustomSearch.templateWarning("http://192.168.1.5:8080/search?q={query}"))
        assertNull(CustomSearch.templateWarning("https://s.example/?q={query}"))
        assertNull(CustomSearch.templateProblem("http://searx.local/search?q={query}&format=json"))
        // settings view
        assertEquals(1, ModuleSettingsLogic.searchProblems("custom", "", "", "https://x.example/?q=fixed").size)
        assertTrue(ModuleSettingsLogic.searchProblems("custom", "", "", "https://x.example/?q={query}", "Authorization").isEmpty())
        assertEquals(1, ModuleSettingsLogic.searchProblems("custom", "", "", "https://x.example/?q={query}", "Bad Header:").size)
        assertEquals(1, ModuleSettingsLogic.searchProblems("kagi", "", "").size)
        assertEquals(1, ModuleSettingsLogic.searchWarnings("custom", "http://localhost:8888/?q={query}").size)
        assertEquals("duckduckgo", WebSearch.effectiveProvider(ModuleConfig(searchProvider = "custom", customSearchUrl = "https://x.example/")))
    }

    @Test fun customJsonShapes() {
        fun urls(json: String) = CustomSearch.parse(json, "application/json", 10, "https://s.example/").map { it.url }
        assertEquals(listOf("https://r.example"), urls("""{"results":[{"title":"R","url":"https://r.example","content":"c"}]}"""))
        assertEquals(listOf("https://d.example"), urls("""{"data":[{"name":"D","link":"https://d.example","description":"d"}]}"""))
        assertEquals(listOf("https://i.example"), urls("""{"items":[{"title":"I","link":"https://i.example","snippet":"s"}]}"""))
        assertEquals(listOf("https://w.example"), urls("""{"web":{"results":[{"title":"W","url":"https://w.example"}]}}"""))
        assertEquals(listOf("https://o.example"), urls("""{"organic":[{"title":"O","link":"https://o.example"}]}"""))
        assertEquals(listOf("https://h.example"), urls("""[{"title":"H","href":"https://h.example","body":"b"}]"""))
        // an unknown key found by the generic walk; non-http entries skipped
        assertEquals(listOf("https://n.example"), urls("""{"response":{"docs":[{"title":"N","url":"https://n.example"},{"title":"x","url":"javascript:1"}]}}"""))
        val hit = CustomSearch.parse("""{"results":[{"name":"<b>Name</b>","href":"https://n.example","body":"Body text"}]}""", null, 10, "").single()
        assertEquals("Name", hit.title)
        assertEquals("Body text", hit.snippet)
        assertTrue(urls("""{"nothing":1}""").isEmpty())
    }

    @Test fun customHtmlAndTextFallback() {
        val html = """<html><head><title>x</title><script>var a='<a href="https://evil.example">no</a>'</script></head><body>
            <a href="/settings">Settings</a><a href="/about">About us</a>
            <div class="r"><a href="https://one.example/page">First &amp; best</a><p>The first snippet text.</p></div>
            <div class="r"><a href="https://two.example/">Second result</a><span>Another snippet</span></div>
            <a href="https://one.example/page#x">First again</a></body></html>"""
        val hits = CustomSearch.parse(html, "text/html", 10, "https://search.example/search?q=a")
        assertEquals(listOf("https://one.example/page", "https://two.example/"), hits.map { it.url })
        assertEquals("First & best", hits[0].title)
        assertTrue(hits[0].snippet, hits[0].snippet.contains("The first snippet text."))
        // only own-site links: relative links resolved
        val own = CustomSearch.parse("""<a href="/r/1">Result one</a> text one <a href="/r/2">Result two</a>""", "text/html", 10, "https://s.example/q")
        assertEquals(listOf("https://s.example/r/1", "https://s.example/r/2"), own.map { it.url })
        val text = CustomSearch.parse("Alpha - https://a.example/x\nBeta https://b.example\n", "text/plain", 10, "")
        assertEquals(listOf("https://a.example/x", "https://b.example"), text.map { it.url })
        assertEquals("Alpha", text[0].title)
        // bounded: a huge page does not blow up
        val big = "<p>" + "x".repeat(2_000_000) + "</p><a href=\"https://late.example\">late link</a>"
        assertTrue(CustomSearch.parse(big, "text/html", 5, "https://s.example/").isEmpty())
    }

    @Test fun customToolSendsHeaderAndTemplate() {
        val ctx = TestToolContext()
        val seen = mutableListOf<Map<String, String>>()
        val services = object : FakeServices() {
            override suspend fun httpGet(url: String, headers: Map<String, String>): HttpResult { seen += headers; return super.httpGet(url, headers) }
        }
        services.config = ModuleConfig(searchProvider = "custom", customSearchUrl = "https://s.example/api?q={query}&n={count}",
            customSearchHeaderName = "Authorization", customSearchHeaderValue = "Bearer tok")
        services.http["https://s.example/api"] = HttpResult(200, """{"results":[{"title":"T","url":"https://t.example"}]}""", "application/json")
        val c = TestToolContext(services = services)
        val r = run(c, "web_search", """{"query":"a b","num_results":3}""")
        assertTrue(r.text, r.text.contains("https://t.example"))
        assertEquals("https://s.example/api?q=a%20b&n=3", services.requested.last())
        assertEquals("Bearer tok", seen.last()["Authorization"])
        services.http["https://s.example/api"] = HttpResult(500, "")
        assertTrue(run(c, "web_search", """{"query":"x"}""").isError)
        assertNotNull(ctx)
    }

    /** The real HTTP path (OkHttp + MockWebServer): the custom engine's template, header and JSON answer. */
    @Test fun customEngineAgainstMockWebServer() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[{"title":"M","link":"https://m.example","snippet":"ms"}]}"""))
        server.start()
        try {
            val client = OkHttpClient()
            val services = object : FakeServices() {
                override suspend fun httpGet(url: String, headers: Map<String, String>): HttpResult {
                    val b = Request.Builder().url(url)
                    headers.forEach { (k, v) -> b.header(k, v) }
                    client.newCall(b.build()).execute().use { return HttpResult(it.code, it.body!!.string(), it.header("Content-Type"), url) }
                }
            }
            val cfg = ModuleConfig(searchProvider = "custom", customSearchUrl = server.url("/s").toString() + "?q={query}",
                customSearchHeaderName = "X-Key", customSearchHeaderValue = "secret")
            // http on localhost is allowed for a custom engine
            assertNull(CustomSearch.templateProblem(cfg.customSearchUrl))
            val out = runBlocking { WebSearch.search(services, cfg, "ä b", 5) }
            assertEquals("https://m.example", out.hits.single().url)
            val req = server.takeRequest()
            assertEquals("/s?q=%C3%A4%20b", req.path)
            assertEquals("secret", req.getHeader("X-Key"))
            val text = ModuleSettingsLogic.searchTestResult("custom", out, "custom")
            assertTrue(text, text.startsWith("Works (Custom)") && text.contains("https://m.example"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun searchTestResultTexts() {
        assertTrue(ModuleSettingsLogic.searchTestResult("kagi", null, "kagi").contains("starting"))
        assertEquals("boom", ModuleSettingsLogic.searchTestResult("kagi", SearchOutcome(emptyList(), "boom"), "kagi"))
        assertTrue(ModuleSettingsLogic.searchTestResult("kagi", SearchOutcome(listOf(SearchHit("t", "https://u", ""))), "duckduckgo").contains("not set up"))
        assertTrue(ModuleSettingsLogic.searchTestResult("custom", SearchOutcome(emptyList()), "custom").contains("no results"))
    }

    @Test fun browserSearchUrls() {
        assertNull(BrowserSearch.url("", "x"))
        assertEquals("https://kagi.com/search?q=caf%C3%A9%20au%20lait", BrowserSearch.url("https://kagi.com/search?q={query}", "café au lait"))
        assertEquals("kagi", BrowserSearch.presetOf("https://kagi.com/search?q={query}")?.id)
        assertEquals("browser", BrowserSearch.presetOf("")?.id)
        assertNull(BrowserSearch.presetOf("https://mine.example/?q={query}"))
        assertNull(BrowserSearch.problem(""))
        assertNotNull(BrowserSearch.problem("https://mine.example/"))
        BrowserSearch.PRESETS.filter { it.template.isNotEmpty() }.forEach { assertNull(it.id, BrowserSearch.problem(it.template)) }
        assertEquals("intent:#Intent;action=android.intent.action.WEB_SEARCH;package=org.mozilla.firefox;S.query=a%20%3B%20b;end",
            BrowserSearch.webSearchIntent("a ; b", "org.mozilla.firefox"))
    }

    @Test fun browserSearchToolOpensTemplateOrBrowserSearch() {
        val ctx = TestToolContext()
        ctx.services.config = ModuleConfig(browserSearchUrl = "https://kagi.com/search?q={query}")
        run(ctx, "browser_search", """{"query":"best pizza"}""")
        assertTrue(ctx.phone.actions.toString(), ctx.phone.actions.any { it == "open https://kagi.com/search?q=best%20pizza" })

        ctx.services.config = ModuleConfig()
        ctx.services.defaults = listOf(DefaultApp(AppRole.BROWSER, "org.mozilla.firefox", "Firefox", DefaultHow.DEFAULT))
        run(ctx, "browser_search", """{"query":"x"}""")
        assertTrue(ctx.phone.actions.last(), ctx.phone.actions.last().startsWith("open intent:#Intent;action=android.intent.action.WEB_SEARCH;package=org.mozilla.firefox"))

        ctx.services.defaults = emptyList()
        val r = run(ctx, "browser_search", """{"query":"x"}""")
        assertTrue(r.isError)
        assertTrue(r.text.contains("launch_app"))
        ctx.services.config = ModuleConfig(browserSearchUrl = "https://bad.example/")
        assertTrue(run(ctx, "browser_search", """{"query":"x"}""").isError)
    }
}
