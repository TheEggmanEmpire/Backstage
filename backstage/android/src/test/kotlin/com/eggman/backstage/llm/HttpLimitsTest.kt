package com.eggman.backstage.llm

import com.eggman.backstage.exec.LiveDisplaySize
import com.eggman.backstage.exec.SafeIntents
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Bounded HTTP reads and live display sizing. */
class HttpLimitsTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun get(path: String) = Request.Builder().url(server.url(path)).build()

    @Test fun largeBodyIsCutAtTheLimitInsteadOfBufferedWhole() = runBlocking {
        val big = Buffer().apply { repeat(3_000) { writeUtf8("x".repeat(1_000)) } } // 3 MB
        server.enqueue(MockResponse().setBody(big))
        val r = LlmHttp.execute(client, get("/big"), maxBytes = 100_000)
        assertEquals(200, r.code)
        assertTrue(r.truncated)
        assertEquals(100_000, r.body.length)
    }

    @Test fun smallBodyIsCompleteAndRedirectTargetIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("hello").setHeader("Content-Type", "text/plain; charset=utf-8"))
        val r = LlmHttp.execute(client, get("/start"), maxBytes = 100_000)
        assertEquals("hello", r.body)
        assertFalse(r.truncated)
        assertTrue(r.finalUrl!!.endsWith("/final"))
    }

    @Test fun oversizedProviderReplyIsAnErrorNotACrash() = runBlocking {
        val big = Buffer().apply { repeat(300) { writeUtf8("y".repeat(1_000)) } }
        server.enqueue(MockResponse().setBody(big))
        try {
            LlmHttp(client, maxResponse = 100_000).postJson(server.url("/v1/chat").toString(), emptyMap(), org.json.JSONObject())
            fail("expected an error")
        } catch (e: LlmException) {
            assertEquals(LlmErrorKind.INVALID_RESPONSE, e.kind)
        }
    }

    @Test fun displaySizeFollowsRotationAndKeepsLastGoodValue() {
        var current: Pair<Int, Int>? = 1080 to 2400
        val size = LiveDisplaySize { current ?: throw IllegalStateException("display gone") }
        assertEquals(1080 to 2400, size.get())
        current = 2400 to 1080 // rotated to landscape
        assertEquals(2400 to 1080, size.get())
        current = null // read fails: last good value
        assertEquals(2400 to 1080, size.get())
        current = 0 to 0
        assertEquals(2400 to 1080, size.get())
        assertEquals(720 to 1600, LiveDisplaySize(720 to 1600) { null }.get())
    }

    @Test fun dangerousSchemesAreRefusedBeforeAnyIntentIsBuilt() {
        for (bad in listOf("file:///sdcard/x", "content://com.eggman.backstage.files/x", "javascript:alert(1)", "android-app://com.x", "no scheme", "")) {
            try {
                SafeIntents.checkedScheme(bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("intent", SafeIntents.checkedScheme("intent://x#Intent;scheme=https;end"))
        assertEquals("https", SafeIntents.checkedScheme(" https://example.org "))
    }
}
