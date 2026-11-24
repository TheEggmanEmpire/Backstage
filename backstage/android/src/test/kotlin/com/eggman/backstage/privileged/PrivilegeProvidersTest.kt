package com.eggman.backstage.privileged

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class PrivilegeProvidersTest {
    private val stellar = PrivilegeProviders.STELLAR_PACKAGE
    private val shizuku = PrivilegeProviders.shizukuPackages.first()

    @Test fun stellarWinsRegardlessOfDiscoveryOrder() {
        assertEquals(stellar, PrivilegeProviders.select(listOf(shizuku, stellar)))
        assertEquals(stellar, PrivilegeProviders.select(listOf(stellar, shizuku)))
        assertEquals(shizuku, PrivilegeProviders.select(listOf(shizuku)))
        assertEquals(shizuku, PrivilegeProviders.select(PrivilegeProviders.shizukuPackages.reversed()))
        assertNull(PrivilegeProviders.select(emptyList()))
    }

    @Test fun shizukuPlusCanBeSelectedAsTheShizukuManager() {
        val plus = "af.shizuku.plus.api"
        assertTrue(plus in PrivilegeProviders.packages)
        assertEquals(plus, PrivilegeProviders.choice(listOf(plus), PrivilegeProviders.SHIZUKU,
            null, null, externalUsable = true, sessionRunning = false).selected)
    }

    @Test fun explicitChoiceSurvivesAndRemovedChoiceFallsBack() {
        assertEquals(shizuku, PrivilegeProviders.select(listOf(stellar, shizuku), shizuku))
        assertEquals(shizuku, PrivilegeProviders.select(listOf(stellar, shizuku), "shizuku"))
        assertEquals(stellar, PrivilegeProviders.select(listOf(shizuku, stellar), "stellar"))
        assertEquals(stellar, PrivilegeProviders.select(listOf(shizuku, stellar), "removed.provider"))
        assertEquals("custom.manager", PrivilegeProviders.select(listOf(stellar, shizuku, "custom.manager"), "custom.manager"))
    }

    @Test fun selectedStellarGetsAChanceEvenWhenShizukuIsAlreadyRunning() {
        assertTrue(PrivilegeProviders.requestStellar(stellar, binderAlive = true, requested = false))
        assertFalse(PrivilegeProviders.requestStellar(stellar, binderAlive = true, requested = true))
        assertTrue(PrivilegeProviders.requestStellar(stellar, binderAlive = false, requested = true))
        assertFalse(PrivilegeProviders.requestStellar(shizuku, binderAlive = false, requested = false))
        assertFalse(PrivilegeProviders.requestStellar(null, binderAlive = false, requested = false))
    }

    @Test fun preferredPreservesDeliberateEmbeddedAndActiveSessions() {
        val installed = listOf(stellar, shizuku)
        val embedded = PrivilegeProviders.choice(installed, "preferred", null, "embedded", true, false)
        assertNull(embedded.selected)
        assertFalse(embedded.writePreferences)
        assertFalse(embedded.disableEmbedded)
        val fresh = PrivilegeProviders.choice(installed, "preferred", null, null, true, false)
        assertEquals(stellar, fresh.selected)
        assertTrue(fresh.writePreferences)
        assertTrue(fresh.disableEmbedded)
        val running = PrivilegeProviders.choice(installed, "preferred", shizuku, "external", true, true)
        assertEquals(shizuku, running.selected)
        assertFalse(running.disableEmbedded)
        for (provider in listOf("stellar", "shizuku")) {
            val explicit = PrivilegeProviders.choice(installed, provider, null, "embedded", true, true)
            assertEquals(if (provider == "stellar") stellar else shizuku, explicit.selected)
            assertTrue(explicit.writePreferences)
            assertTrue(explicit.disableEmbedded)
        }
        assertFalse(PrivilegeProviders.choice(installed, "stellar", null, "embedded", false, true).disableEmbedded)
    }

    @Test fun providerSnapshotIsCachedUntilTtlOrExplicitInvalidation() {
        var now = 0L
        var reads = 0
        val cache = ProviderSnapshotCache<Int>(clock = { now })
        repeat(100) { assertEquals(1, cache.get { ++reads }) }
        now = 4999
        assertEquals(1, cache.get { ++reads })
        now = 5000
        assertEquals(2, cache.get { ++reads })
        cache.invalidate()
        assertEquals(3, cache.get { ++reads })
    }

    @Test fun concurrentProviderProbesLoadOnlyOneSnapshot() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val cache = ProviderSnapshotCache<Int>(clock = { 0 })
        val threads = (1..12).map { Thread { repeat(50) { cache.get { reads.incrementAndGet() } } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, reads.get())
    }

    @Test fun installationOrAnUngrantedBinderNeverCountsAsPrivilege() {
        assertFalse(PrivilegeProviders.usable(false, false, true))
        assertFalse(PrivilegeProviders.usable(false, true, false))
        assertTrue(PrivilegeProviders.usable(false, true, true))
        // Includes Stellar compatibility disabled, stopped providers, and permission revocation.
        assertTrue(PrivilegeProviders.usable(true, false, false))
        assertTrue(PrivilegeProviders.usable(true, true, false))
    }
}
