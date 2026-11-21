package com.eggman.backstage.tools

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FetchUrlSecurityTest {
    @Test fun nonPublicAddressesAreRestricted() {
        for (ip in listOf("0.0.0.0", "0.2.3.4", "10.2.3.4", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "172.31.255.254", "192.168.1.1", "100.64.0.1", "224.0.0.1",
            "::", "::1", "fe80::1", "fc00::1", "fdff::1", "ff02::1", "::ffff:192.168.1.1")) {
            assertTrue(ip, FetchUrlPolicy.restricted(InetAddress.getByName(ip)))
        }
    }
    @Test fun publicAddressesRemainReadable() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1", "192.169.1.1", "2606:4700:4700::1111")) {
            assertFalse(ip, FetchUrlPolicy.restricted(InetAddress.getByName(ip)))
        }
    }
    @Test fun explicitlyMappedIpv6PrivateAddressesAreRestricted() {
        val bytes = ByteArray(16)
        bytes[10] = 0xff.toByte(); bytes[11] = 0xff.toByte()
        bytes[12] = 192.toByte(); bytes[13] = 168.toByte(); bytes[14] = 1; bytes[15] = 1
        assertTrue(FetchUrlPolicy.restricted(java.net.Inet6Address.getByAddress(null, bytes, -1)))
    }
    @Test fun oneIpv6HostMustNotAuthorizeAnotherWithTheSamePrefix() {
        assertFalse(FetchUrlPolicy.userNamedHost("fc00::1", listOf("read http://[fc00::1:2]/")))
        assertTrue(FetchUrlPolicy.userNamedHost("fc00::1", listOf("read http://[fc00::1]:80/")))
    }
    @Test fun mixedDnsAndPrivateHostsAreDeniedWithoutExactUserWords() {
        val privateAddress = InetAddress.getByName("192.168.1.1")
        assertThrows(ToolArgumentException::class.java) {
            FetchUrlPolicy.addresses("router.local", listOf("summarize the news")) { listOf(InetAddress.getByName("8.8.8.8"), privateAddress) }
        }
        assertThrows(ToolArgumentException::class.java) {
            FetchUrlPolicy.addresses("router.local", null) { listOf(privateAddress) }
        }
        assertEquals(listOf(privateAddress), FetchUrlPolicy.addresses("router.local", listOf("Read http://router.local/status")) { listOf(privateAddress) })
        assertFalse(FetchUrlPolicy.userNamedHost("router.local", listOf("not-router.local.example")))
        assertFalse(FetchUrlPolicy.userNamedHost("192.168.1.1", listOf("192.168.1.10")))
    }
    @Test fun unspecifiedAndMulticastNeverBecomeNamedHosts() {
        for (ip in listOf("0.0.0.0", "::", "224.0.0.1")) assertThrows(ToolArgumentException::class.java) {
            FetchUrlPolicy.addresses(ip, listOf(ip)) { listOf(InetAddress.getByName(ip)) }
        }
    }
    @Test fun privateRedirectIsCheckedBeforeSecondRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${server.port}/private"))
            val url = "http://localhost:${server.port}/start"
            try {
                SafeUrlFetch.get(url, listOf("Read $url"))
                fail("redirect should have been denied")
            } catch (e: ToolArgumentException) {
                assertTrue(e.message.orEmpty().contains("127.0.0.1"))
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun relativeRedirectsAndFiveHopLimitAreEnforced() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/hop$it")) }
            server.start()
            val url = server.url("/start").toString()
            try {
                SafeUrlFetch.get(url, listOf("Read $url"))
                fail("sixth redirect should fail")
            } catch (e: ToolArgumentException) { assertTrue(e.message.orEmpty().contains("maximum 5")) }
            assertEquals(6, server.requestCount)
        }
    }
}
