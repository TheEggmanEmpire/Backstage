package com.eggman.backstage.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseClientTest {
    private fun release(extra: String = "", version: String = "v1.0.1") = """{
      "tag_name":"$version", "draft":false, "prerelease":false, $extra
      "assets":[{"name":"backstage-1.0.1-arm64-v8a-release.apk","size":200000000,
      "browser_download_url":"https://github.com/TheEggmanEmpire/Backstage/releases/download/v1.0.1/backstage-1.0.1-arm64-v8a-release.apk"},
      {"name":"SHA256SUMS","size":200,"browser_download_url":"https://github.com/TheEggmanEmpire/Backstage/releases/download/v1.0.1/SHA256SUMS"}]}"""
    @Test fun redirectsToUntrustedHostsAreRejectedBeforeConnecting() {
        MockWebServer().use { server ->
            for (target in listOf("https://other.invalid/release", "https://github.com.other.invalid/release", "http://github.com/release")) {
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
                val error = assertThrows(java.io.IOException::class.java) {
                    GitHubReleaseClient("1", endpoint = server.url("/").toString()).latest()
                }
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Untrusted update redirect"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun defaultEndpointOnlyRequestsTheLatestStableRelease() {
        assertEquals("https://api.github.com/repos/TheEggmanEmpire/Backstage/releases/latest", GitHubReleaseClient.ENDPOINT)
    }
    @Test fun requestUsesGitHubHeadersAndSelectsReleaseAssets() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release()))
            val result = GitHubReleaseClient("1.0.0", endpoint = server.url("/releases/latest").toString()).latest()!!
            assertEquals("1.0.1", result.version)
            assertEquals(200000000L, result.assets.apk.size)
            val request = server.takeRequest()
            assertEquals("/releases/latest", request.path)
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
            assertEquals("Backstage/1.0.0", request.getHeader("User-Agent"))
            assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun draftsAndPrereleasesAreIgnored() {
        MockWebServer().use { server ->
            val client = GitHubReleaseClient("1.0.0", endpoint = server.url("/").toString())
            server.enqueue(MockResponse().setBody(release().replace("\"draft\":false", "\"draft\":true")))
            assertNull(client.latest())
            server.enqueue(MockResponse().setBody(release().replace("\"prerelease\":false", "\"prerelease\":true")))
            assertNull(client.latest())
        }
    }
    @Test fun rateLimitAndInvalidPayloadFailWithoutRetryLoops() {
        MockWebServer().use { server ->
            val client = GitHubReleaseClient("1.0.0", endpoint = server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Retry-After", "7200"))
            val error = assertThrows(UpdateHttpException::class.java) { client.latest() }
            assertEquals(7_200_000L, error.retryAfterMs)
            server.enqueue(MockResponse().setBody("{}"))
            assertThrows(Exception::class.java) { client.latest() }
            server.enqueue(MockResponse().setBody(release().replace("https://github.com/", "http://github.com/")))
            assertThrows(Exception::class.java) { client.latest() }
            assertEquals(3, server.requestCount)
        }
    }
}
