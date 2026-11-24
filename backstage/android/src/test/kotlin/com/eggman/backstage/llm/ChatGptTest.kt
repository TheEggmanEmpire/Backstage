package com.eggman.backstage.llm

import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.Provider
import com.eggman.backstage.data.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class ChatGptTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun issuer() = server.url("/").toString().trimEnd('/')

    private fun jwt(claims: JSONObject): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{\"alg\":\"none\"}".toByteArray()) + "." + enc.encodeToString(claims.toString().toByteArray()) + ".sig"
    }

    private val idToken = jwt(JSONObject().put("email", "me@example.com")
        .put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acc-1").put("chatgpt_plan_type", "plus")))
    private fun access(expSec: Long) = jwt(JSONObject().put("exp", expSec))

    private fun tokenReply(accessToken: String, refresh: String? = "r1") = JSONObject()
        .put("id_token", idToken).put("access_token", accessToken).apply { refresh?.let { put("refresh_token", it) } }.toString()

    @Test fun authorizeUrlCarriesPkceAndState() {
        val pkce = Pkce("verifier", Pkce.challengeOf("verifier"))
        val url = ChatGptAuth().authorizeUrl(pkce, "st8")
        val q = ChatGptAuth.query(url.substringAfter('?'))
        assertEquals(ChatGptAuth.CLIENT_ID, q["client_id"])
        assertEquals(ChatGptAuth.LOOPBACK_REDIRECT, q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(pkce.challenge, q["code_challenge"])
        assertEquals("st8", q["state"])
        assertTrue(q["scope"]!!.contains("offline_access"))
        // RFC 7636 appendix B test vector
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun codeExchangeReadsAccountFromIdToken() = runBlocking {
        server.enqueue(MockResponse().setBody(tokenReply(access(2_000_000_000))))
        val t = ChatGptAuth(issuer = issuer()).exchangeCode("c0de", "ver")
        val req = server.takeRequest()
        assertEquals("/oauth/token", req.path)
        val form = req.body.readUtf8()
        assertTrue(form.contains("grant_type=authorization_code") && form.contains("code=c0de") && form.contains("code_verifier=ver"))
        assertEquals("acc-1", t.accountId)
        assertEquals("me@example.com", t.email)
        assertEquals("plus", t.plan)
        assertEquals(2_000_000_000_000, t.expiresAtMs)
        assertEquals(t, ChatGptTokens.fromJson(t.toJson()))
    }

    @Test fun deviceCodePendingThenApproved() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"device_auth_id":"d1","user_code":"ABCD-1234","interval":"1"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"pending"}"""))
        server.enqueue(MockResponse().setBody("""{"authorization_code":"ac","code_verifier":"cv","code_challenge":"cc"}"""))
        server.enqueue(MockResponse().setBody(tokenReply(access(2_000_000_000))))
        val auth = ChatGptAuth(issuer = issuer())
        val d = auth.startDeviceCode()
        assertEquals("ABCD-1234", d.userCode)
        assertEquals(1, d.intervalSec)
        assertEquals(issuer() + "/codex/device", d.verificationUrl)
        assertNull(auth.pollDeviceCode(d))
        val t = auth.pollDeviceCode(d)!!
        assertEquals("acc-1", t.accountId)
        server.takeRequest(); server.takeRequest()
        val poll = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("d1", poll.getString("device_auth_id"))
        val exchange = server.takeRequest().body.readUtf8()
        assertTrue(exchange.contains("code=ac") && exchange.contains("code_verifier=cv") && exchange.contains("deviceauth%2Fcallback"))
    }

    @Test fun deviceCodeRejectionExplainsHowToEnableCodeSignIn() = runBlocking {
        for (status in listOf(403, 404)) {
            server.enqueue(MockResponse().setResponseCode(status))
            val error = runCatching { ChatGptAuth(issuer = issuer()).startDeviceCode() }.exceptionOrNull() as LlmException
            assertTrue(error.message.orEmpty().contains("Allow device code sign-in in ChatGPT → Settings → Security"))
            assertFalse(error.message.orEmpty().contains("or use the browser"))
        }
    }

    @Test fun sessionRefreshesNearExpiryAndKeepsRefreshToken() = runBlocking {
        val store = object : ChatGptTokenStore {
            var t: ChatGptTokens? = ChatGptTokens(idToken, "old", "r1", "acc-1", expiresAtMs = 1_000)
            override fun load() = t
            override fun save(tokens: ChatGptTokens?) { t = tokens }
        }
        server.enqueue(MockResponse().setBody(tokenReply(access(9_999_999_999), refresh = null)))
        val fresh = ChatGptSession(store, ChatGptAuth(issuer = issuer()), clock = { 0 }).tokens()
        assertEquals("r1", fresh.refreshToken)
        assertFalse(fresh.accessToken == "old")
        assertEquals(fresh, store.t)
        assertEquals("refresh_token", JSONObject(server.takeRequest().body.readUtf8()).getString("grant_type"))
    }

    @Test fun clientStreamsAndRetriesOnceAfter401() = runBlocking {
        var current = ChatGptTokens(idToken, "a1", "r1", "acc-1")
        val store = object : ChatGptTokenStore {
            override fun load() = current
            override fun save(tokens: ChatGptTokens?) { current = tokens!! }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        server.enqueue(MockResponse().setBody(tokenReply("a2")))
        val sse = """
            event: response.output_item.done
            data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"c1","name":"tap","arguments":"{\"x\":1}"}}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed","output":[],"usage":{"input_tokens":5,"output_tokens":2}}}

        """.trimIndent()
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(sse))
        val session = ChatGptSession(store, ChatGptAuth(issuer = issuer()))
        val client = ChatGptClient("gpt-x", session, baseUrl = issuer() + "/codex", reasoningEffort = "high")
        val reply = client.complete(LlmRequest("sys", listOf(Message.user("hi")), listOf(ToolSpec("tap", "t", JSONObject("{}")))))
        assertEquals(StopReason.TOOL_USE, reply.stopReason)
        assertEquals(5, reply.usage.inputTokens)
        val first = server.takeRequest()
        assertEquals("/codex/responses", first.path)
        assertEquals("Bearer a1", first.getHeader("Authorization"))
        assertEquals("acc-1", first.getHeader("chatgpt-account-id"))
        val body = JSONObject(first.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("max_output_tokens"))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("/oauth/token", server.takeRequest().path)
        assertEquals("Bearer a2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun usageLimitIsNotRetriedAndReadsPlainly() {
        val e = ChatGptClient.mapError(429, """{"error":{"type":"usage_limit_reached","plan_type":"plus","resets_in_seconds":600}}""")
        assertFalse(e.retryable)
        assertEquals("ChatGPT usage limit reached (plus plan), resets in about 10 min", e.userMessage())
    }

    @Test fun settingsStoreSignInAsSecretKey() {
        val kv = MemoryStore()
        val s = Settings(kv)
        s.provider = Provider.CHATGPT
        assertTrue(s.providerConfig().problem()!!.contains("Not signed in"))
        s.chatGptTokens = ChatGptTokens(idToken, "a1", "r1", "acc-1")
        assertEquals("a1", s.apiKey())
        assertTrue(s.keyVerified())
        assertNull(s.providerConfig().problem())
        assertTrue(LlmFactory.create(s.providerConfig()) is ChatGptClient)
        s.setApiKey(Provider.CHATGPT, "pasted") // ignored
        assertEquals("a1", s.apiKey())
        s.chatGptTokens = null
        assertEquals("", s.apiKey())
    }

    @Test fun loopbackAcceptsOnlyMatchingState() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val cb = LoopbackCallback(port)
        val socket = cb.bind()
        val code = async(kotlinx.coroutines.Dispatchers.IO) { cb.await(socket, "good") }
        fun get(q: String): Int = (URL("http://127.0.0.1:$port/auth/callback?$q").openConnection() as HttpURLConnection).let {
            try { it.responseCode } finally { it.disconnect() }
        }
        assertEquals(400, get("code=x&state=bad"))
        assertEquals(200, get("code=the-code&state=good"))
        assertEquals("the-code", code.await())
        assertEquals("c" to "s", ChatGptAuth.parseCallback("http://localhost:1455/auth/callback?code=c&state=s"))
        assertNull(ChatGptAuth.parseCallback("https://example.com/"))
    }

    @Test fun idleAndMalformedConnectionsDoNotEndTheListener() = runBlocking {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val cb = LoopbackCallback(port)
        val socket = cb.bind()
        val code = async(kotlinx.coroutines.Dispatchers.IO) { cb.await(socket, "good") }
        java.net.Socket("127.0.0.1", port).close() // connects, sends nothing
        java.net.Socket("127.0.0.1", port).use { it.getOutputStream().write("GET /auth/callback?x=%zz HTTP/1.1\r\n\r\n".toByteArray()); it.getInputStream().readBytes() }
        val c = URL("http://127.0.0.1:$port/auth/callback?code=ok&state=good").openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode); c.disconnect()
        assertEquals("ok", code.await())
    }

    @Test fun streamErrorsKeepTheirMeaning() {
        fun fail(code: String) = runCatching {
            ChatGptClient.decodeStream("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"$code\",\"message\":\"m\"}}}")
        }.exceptionOrNull() as LlmException
        assertEquals(LlmErrorKind.CONTEXT_LENGTH, fail("context_length_exceeded").kind)
        assertFalse(fail("insufficient_quota").retryable)
        assertFalse(ChatGptClient.mapError(429, """{"error":{"type":"usage_not_included"}}""").retryable)
        // A stream that stops before its final event is retried, not acted on.
        val early = runCatching { ChatGptClient.decodeStream("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\"}}") }.exceptionOrNull() as LlmException
        assertTrue(early.retryable)
    }

    @Test fun refreshDoesNotUndoASignOut() = runBlocking {
        val store = object : ChatGptTokenStore {
            var t: ChatGptTokens? = ChatGptTokens(idToken, "old", "r1", "acc-1", expiresAtMs = 1_000)
            override fun load() = t
            override fun save(tokens: ChatGptTokens?) { t = tokens }
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                store.t = null // the user signs out while the refresh is in flight
                return MockResponse().setBody(tokenReply(access(9_999_999_999)))
            }
        }
        val r = runCatching { ChatGptSession(store, ChatGptAuth(issuer = issuer()), clock = { 0 }).tokens() }
        assertTrue(r.isFailure)
        assertNull(store.t)
        assertFalse(ChatGptTokens(idToken, "secret-access", "secret-refresh", "a").toString().contains("secret"))
    }
}
