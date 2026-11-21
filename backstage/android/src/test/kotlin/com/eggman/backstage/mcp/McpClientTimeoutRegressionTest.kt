package com.eggman.backstage.mcp

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class McpClientTimeoutRegressionTest {
    @Test fun discoveryKeepsFifteenSecondsAndToolCallsGetTwoMinutes() = runBlocking {
        val seen = mutableListOf<Triple<String, Int, Long>>()
        val base = OkHttpClient.Builder().addInterceptor { chain ->
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val request = JSONObject(body)
            val method = request.getString("method")
            seen += Triple(method, chain.readTimeoutMillis(), TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos()))
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", "2025-06-18")
                "tools/list" -> JSONObject().put("tools", org.json.JSONArray())
                else -> JSONObject().put("content", org.json.JSONArray())
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("jsonrpc", "2.0").put("id", request.optInt("id")).put("result", result).toString()
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = McpClient("http://localhost/mcp", base = base)
        client.listTools()
        client.callTool("mutate", JSONObject())
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), seen.map { it.first })
        assertTrue(seen.filter { it.first != "tools/call" }.all { it.second == 15_000 && it.third == 15_000L })
        assertEquals(Triple("tools/call", 120_000, 120_000L), seen.last())
    }

    @Test fun twentySecondToolCompletesOnceBeyondTheDiscoveryTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"side effect complete"}]}}""")
            .setBodyDelay(20, TimeUnit.SECONDS))
        server.start()
        try {
            val result = McpClient(server.url("/mcp").toString()).callTool("mutate", JSONObject())
            assertFalse(result.isError)
            assertEquals("side effect complete", result.content.single().text)
            assertEquals("No automatic replay of the side effect", 3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun timedOutCallWarnsThatItMayHaveCompletedAndIsNotRetried() = runBlocking {
        var calls = 0
        val base = OkHttpClient.Builder().addInterceptor { chain ->
            val request = JSONObject(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8())
            if (request.getString("method") == "tools/call") {
                calls++
                throw SocketTimeoutException("read timed out")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("id", request.optInt("id")).put("result", JSONObject().put("protocolVersion", "2025-06-18"))
                    .toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            McpClient("http://localhost/mcp", base = base).callTool("mutate", JSONObject())
            fail("expected timeout")
        } catch (e: McpRemoteException) {
            assertTrue(e.message!!, e.message!!.contains("timed out and may have completed"))
            assertTrue(e.message!!.contains("before retrying"))
            assertFalse(e.message!!.contains("Cannot reach"))
        }
        assertEquals(1, calls)
    }
}
