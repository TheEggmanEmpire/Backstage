package com.eggman.backstage.update

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class GitHubRedirectTest {
    private val hosts = listOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

    private fun withServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("GitHub test endpoint").apply {
            hosts.forEach { addSubjectAlternativeName(it) }
        }.build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        check(hostname in hosts) { "Untrusted host reached DNS: $hostname" }
                        return listOf(InetAddress.getLoopbackAddress())
                    }
                }).build()
            block(server, client)
        }
    }

    @Test fun allGitHubReleaseHostsCanBeFollowedInOneChain() = withServer { server, client ->
        val urls = hosts.map { server.url("/release").newBuilder().host(it).build() }
        urls.drop(1).forEach { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", it)) }
        server.enqueue(MockResponse().setBody("verified payload"))
        client.withGitHubRedirects().newCall(Request.Builder().url(urls.first()).build()).execute().use {
            assertEquals(urls.last(), it.request.url)
            assertEquals("verified payload", it.body!!.string())
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun trustedFirstHopCannotRedirectToAnotherHost() = withServer { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",
            server.url("/asset").newBuilder().host("github.com").build()))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.invalid/asset"))
        val error = assertThrows(IOException::class.java) {
            client.withGitHubRedirects().newCall(Request.Builder()
                .url(server.url("/api").newBuilder().host("api.github.com").build()).build()).execute().close()
        }
        assertEquals("Untrusted update redirect", error.message)
        assertEquals(2, server.requestCount)
    }
}
