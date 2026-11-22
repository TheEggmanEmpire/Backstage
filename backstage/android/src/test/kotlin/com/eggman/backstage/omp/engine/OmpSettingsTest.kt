package com.eggman.backstage.omp.engine

import com.eggman.backstage.BuildConfig.PLAY_DISTRIBUTION
import com.eggman.backstage.data.*
import com.eggman.backstage.ui.settings.CoreSettingsStore
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OmpSettingsTest {
    @Test fun defaultsToNativePersistsAndRejectsUnknownValues() {
        val values = MemoryStore()
        val settings = Settings(values)
        assertEquals(AgentEngine.BACKSTAGE, settings.agentEngine)
        val ui = CoreSettingsStore(settings)
        ui.agentEngine = AgentEngine.OMP
        val expected = if (PLAY_DISTRIBUTION) AgentEngine.BACKSTAGE else AgentEngine.OMP
        assertEquals(expected, ui.agentEngine)
        assertEquals("OMP", values.getString("agent_engine"))
        assertEquals(expected, Settings(values).agentEngine)
        values.putString("agent_engine", "future")
        assertEquals(AgentEngine.BACKSTAGE, settings.agentEngine)
    }
    @Test fun engineChoiceRoundTripsThroughSettingsBackup() {
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String): String = plain
            override fun decrypt(stored: String): String = stored
        }
        val archive = ByteArrayOutputStream()
        SettingsBackup.export(SettingsBackup.Snapshot(mapOf(Settings.PREFS to mapOf("agent_engine" to "OMP"))), cipher, archive, "test")
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(archive.toByteArray()), cipher, object : SettingsBackup.Store {
            override fun replace(snapshot: SettingsBackup.Snapshot) { restored = snapshot }
        })
        assertEquals("OMP", restored!!.prefs.getValue(Settings.PREFS)["agent_engine"])
    }
}
