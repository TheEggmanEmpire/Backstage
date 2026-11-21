package com.eggman.backstage.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SecretRotationRegressionTest {
    private class Cipher(var missing: Boolean = false, var readable: Boolean = false) : SecretCipher {
        val writes = AtomicInteger()
        override fun encrypt(plain: String): String { writes.incrementAndGet(); return "enc1:$plain" }
        override fun decrypt(stored: String): String? = if (readable) stored.removePrefix("enc1:") else null
        override fun keyMissing() = missing
    }

    @Test fun transientDecryptFailurePreservesBothPersistentSecrets() {
        val original = mapOf("mcp_token" to "enc1:old-mcp", "plugin_secret" to "enc1:old-plugin")
        val store = MemoryStore(original)
        val cipher = Cipher()
        val settings = Settings(store, cipher)
        assertThrows(IllegalStateException::class.java) { settings.mcpToken }
        assertThrows(IllegalStateException::class.java) { settings.pluginSigningSecret }
        original.forEach { (key, value) -> assertEquals(value, store.getString(key)) }
        assertEquals(0, cipher.writes.get())
        cipher.readable = true
        assertEquals("old-mcp", settings.mcpToken)
        assertEquals("old-plugin", settings.pluginSigningSecret)
    }

    @Test fun confirmedMissingKeyPermitsRotationAndPersistsReplacement() {
        val store = MemoryStore(mapOf("mcp_token" to "enc1:old-mcp", "plugin_secret" to "enc1:old-plugin"))
        val cipher = Cipher(missing = true)
        val settings = Settings(store, cipher)
        val mcp = settings.mcpToken
        val plugin = settings.pluginSigningSecret
        assertNotEquals("old-mcp", mcp)
        assertNotEquals("old-plugin", plugin)
        assertEquals("enc1:$mcp", store.getString("mcp_token"))
        assertEquals("enc1:$plugin", store.getString("plugin_secret"))
        assertEquals(2, cipher.writes.get())
    }

    @Test fun concurrentFirstUseReturnsOnePersistedMcpToken() {
        val store = MemoryStore()
        val cipher = Cipher(readable = true)
        val settings = Settings(store, cipher)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..32).map { executor.submit<String> { settings.mcpToken } }.map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, results.toSet().size)
            assertEquals("enc1:${results.first()}", store.getString("mcp_token"))
            assertEquals(1, cipher.writes.get())
        } finally { executor.shutdownNow() }
    }
}
