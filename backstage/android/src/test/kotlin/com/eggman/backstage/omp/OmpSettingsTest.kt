package com.eggman.backstage.omp

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.data.MemoryStore
import com.eggman.backstage.data.SecretCipher
import com.eggman.backstage.data.SettingsBackup
import com.eggman.backstage.data.Settings
import com.eggman.backstage.ui.settings.CoreSettingsStore
import org.junit.Assert.*
import org.junit.Test

class OmpSettingsTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc1:$plain"
        override fun decrypt(stored: String) = stored.removePrefix("enc1:")
    }
    @Test fun defaultsAndUiStoreDelegateToCore() {
        val s = Settings(MemoryStore(), cipher)
        val ui = CoreSettingsStore(s)
        assertFalse(ui.ompEnabled)
        assertFalse(ui.ompAutoApprove)
        assertFalse(ui.ompTermuxEnabled)
        assertFalse(ui.termuxShellEnabled)
        assertFalse(ui.termuxShellAutoApprove)
        assertEquals(47821, ui.ompBrokerPort)
        ui.ompEnabled = true; ui.ompAutoApprove = true; ui.ompTermuxEnabled = true
        ui.ompBrokerPort = 1
        assertEquals(!PLAY_DISTRIBUTION, s.ompEnabled)
        assertTrue(s.ompAutoApprove && s.ompTermuxEnabled)
        assertEquals(1024, s.ompBrokerPort)
        ui.termuxShellEnabled = true; ui.termuxShellAutoApprove = true
        assertTrue(s.termuxShellEnabled && s.termuxShellAutoApprove)
        assertFalse(s.adbAutoApprove)
    }
    @Test fun backupsMarkBothTokensAsSecretsAndReencryptOnImport() {
        val output = java.io.ByteArrayOutputStream()
        val snapshot = SettingsBackup.Snapshot(mapOf(Settings.PREFS to mapOf("omp_harness_token" to "enc1:harness", "omp_termux_token" to "enc1:termux")))
        SettingsBackup.export(snapshot, cipher, output, "test")
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(java.io.ByteArrayInputStream(output.toByteArray()), cipher, object : SettingsBackup.Store {
            override fun replace(snapshot: SettingsBackup.Snapshot) { restored = snapshot }
        })
        assertEquals("enc1:harness", restored!!.prefs[Settings.PREFS]!!["omp_harness_token"])
        assertEquals("enc1:termux", restored!!.prefs[Settings.PREFS]!!["omp_termux_token"])
    }
    @Test fun independentTokensAreEncryptedStableAndRotatable() {
        val store = MemoryStore()
        val s = Settings(store, cipher)
        val h = s.ompHarnessToken
        val t = s.ompTermuxToken
        assertTrue(h.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(h, t)
        assertEquals(h, s.ompHarnessToken)
        assertEquals("enc1:$h", store.getString("omp_harness_token"))
        assertEquals("enc1:$t", store.getString("omp_termux_token"))
        assertNotEquals(t, s.rotateOmpTermuxToken())
        assertEquals(h, s.ompHarnessToken)
        assertNotEquals(h, s.rotateOmpHarnessToken())
    }
    @Test fun shellSettingsRoundTripInBackupWithoutChangingLegacyKeys() {
        val output = java.io.ByteArrayOutputStream()
        val prefs = mapOf("termux_shell_enabled" to true, "termux_shell_auto_approve" to true, "adb_auto_approve" to false)
        SettingsBackup.export(SettingsBackup.Snapshot(mapOf(Settings.PREFS to prefs)), cipher, output, "test")
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(java.io.ByteArrayInputStream(output.toByteArray()), cipher, object : SettingsBackup.Store {
            override fun replace(snapshot: SettingsBackup.Snapshot) { restored = snapshot }
        })
        assertEquals(prefs, restored!!.prefs[Settings.PREFS])
    }
}
