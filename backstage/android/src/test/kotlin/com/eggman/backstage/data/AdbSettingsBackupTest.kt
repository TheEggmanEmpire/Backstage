package com.eggman.backstage.data

import com.eggman.backstage.ui.settings.CoreSettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class AdbSettingsBackupTest {
    @Test fun approvalDefaultsToAskAndUiWritesSameKey() {
        val memory = MemoryStore()
        val settings = Settings(memory)
        val ui = CoreSettingsStore(settings)
        assertFalse(settings.adbAutoApprove)
        assertFalse(ui.adbAutoApprove)
        val changes = mutableListOf<String>()
        val stop = ui.observe { changes += it }
        ui.adbAutoApprove = true
        assertTrue(settings.adbAutoApprove)
        assertEquals("true", memory.getString("adb_auto_approve"))
        settings.adbAutoApprove = false
        assertFalse(ui.adbAutoApprove)
        assertEquals(listOf("adb_auto_approve", "adb_auto_approve"), changes)
        stop()
    }
    @Test fun backupExportsAndRestoresAdbAutoApprove() {
        assertTrue(SettingsBackup.includeKey(Settings.PREFS, "adb_auto_approve"))
        val output = ByteArrayOutputStream()
        SettingsBackup.export(SettingsBackup.Snapshot(mapOf(Settings.PREFS to mapOf("adb_auto_approve" to true))),
            SecretCipher.Plain, output, "test")
        var restored: SettingsBackup.Snapshot? = null
        SettingsBackup.import(ByteArrayInputStream(output.toByteArray()), SecretCipher.Plain, object : SettingsBackup.Store {
            override fun replace(snapshot: SettingsBackup.Snapshot) { restored = snapshot }
        })
        assertEquals(true, restored!!.prefs[Settings.PREFS]!!["adb_auto_approve"])
    }
}
