package com.eggman.backstage.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionReleaseLinkTest {
    private val name = "backstage-play-agent-1.10.2-release.apk"
    private val download = "https://github.com/TheEggmanEmpire/Backstage/releases/download/v1.10.2/$name"

    @Test fun latestVersionedAgentAssetOpensDirectly() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"assets":[
                    {"name":"backstage-play-agent-debug.apk","browser_download_url":"https://example.test/ignored"},
                    {"name":"$name","browser_download_url":"$download"}
                ]}
            """.trimIndent()))
            val link = CompanionReleaseLink(endpoint = server.url("/releases/latest").toString())
            assertEquals(download, link.resolve())
            val request = server.takeRequest()
            assertEquals("/releases/latest", request.path)
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun missingOrUntrustedAssetFallsBackToLatestReleasePage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"assets":[]}"""))
            server.enqueue(MockResponse().setBody("""
                {"assets":[{"name":"$name","browser_download_url":"https://example.test/$name"}]}
            """.trimIndent()))
            val link = CompanionReleaseLink(endpoint = server.url("/releases/latest").toString())
            repeat(2) { assertEquals(CompanionReleaseLink.FALLBACK, link.resolve()) }
        }
    }

    @Test fun failedMetadataRequestFallsBackToLatestReleasePage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("invalid json"))
            val link = CompanionReleaseLink(endpoint = server.url("/releases/latest").toString())
            repeat(2) { assertEquals(CompanionReleaseLink.FALLBACK, link.resolve()) }
        }
    }
}
