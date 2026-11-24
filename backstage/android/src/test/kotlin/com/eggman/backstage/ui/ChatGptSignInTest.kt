package com.eggman.backstage.ui

import com.eggman.backstage.llm.ChatGptAuth
import com.eggman.backstage.llm.ChatGptTokens
import com.eggman.backstage.ui.settings.ChatGptSignIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ChatGptSignInTest {
    @Test fun connectivityRestoresPollingAndRetryKeepsTheApprovedCode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_auth_id":"d1","user_code":"ABCD-1234","interval":"1"}"""))
            server.enqueue(MockResponse().setBody("""{"authorization_code":"ac","code_verifier":"cv"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"access"}"""))
            val failures = mutableSetOf("/api/accounts/deviceauth/token", "/oauth/token")
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (failures.remove(request.url.encodedPath)) throw UnknownHostException("auth.openai.com")
                chain.proceed(request)
            }.build()
            val connectivity = MutableSharedFlow<Unit>()
            val saved = mutableListOf<ChatGptTokens?>()
            val auth = ChatGptAuth(http, issuer = server.url("/").toString().trimEnd('/'))
            val signIn = ChatGptSignIn({ saved += it }, connectivity, auth, this)
            try {
                withTimeout(15_000) {
                    signIn.startCode()
                    val pollFailure = signIn.state.first { it is ChatGptSignIn.State.Code && it.networkError != null } as ChatGptSignIn.State.Code
                    assertEquals("ABCD-1234", pollFailure.device.userCode)
                    assertTrue(pollFailure.networkError.orEmpty().contains("DNS"))
                    assertEquals(1, server.requestCount)
                    connectivity.emit(Unit)
                    signIn.state.first { it is ChatGptSignIn.State.Code && it.networkError == null }
                    val exchangeFailure = signIn.state.first { it is ChatGptSignIn.State.Code && it.networkError != null } as ChatGptSignIn.State.Code
                    assertEquals(pollFailure.device, exchangeFailure.device)
                    assertEquals(2, server.requestCount)
                    signIn.retry()
                    signIn.state.first { it == ChatGptSignIn.State.Done }
                    assertEquals(listOf("access"), saved.map { it?.accessToken })
                    assertEquals("/api/accounts/deviceauth/usercode", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                    assertEquals("/api/accounts/deviceauth/token", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                    val exchange = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("/oauth/token", exchange.path)
                    assertTrue(exchange.body.readUtf8().contains("code=ac"))
                    assertEquals(3, server.requestCount)
                }
            } finally {
                signIn.cancel()
            }
        }
    }
}
