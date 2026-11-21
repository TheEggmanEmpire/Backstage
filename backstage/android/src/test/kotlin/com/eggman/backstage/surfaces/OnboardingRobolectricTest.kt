package com.eggman.backstage.surfaces

import android.Manifest
import android.app.Application
import com.eggman.backstage.ui.onboarding.AndroidPermissionProbe
import com.eggman.backstage.ui.onboarding.Importance
import com.eggman.backstage.ui.onboarding.ItemId
import com.eggman.backstage.ui.onboarding.PermissionChecklist
import com.eggman.backstage.ui.settings.SettingsStores
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The setup checklist built from the real (shadowed) permission state. */
@RunWith(RobolectricTestRunner::class)
class OnboardingRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = SettingsStores.set(memorySettings())

    @After fun tearDown() = SettingsStores.set(null)

    private fun item(id: ItemId) = PermissionChecklist.build(AndroidPermissionProbe.snapshot(app)).single { it.id == id }

    @Test fun checklistFollowsPermissionGrants() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(item(ItemId.MICROPHONE).done)
        assertFalse(item(ItemId.NOTIFICATIONS).done)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(item(ItemId.MICROPHONE).done)
        assertTrue(item(ItemId.NOTIFICATIONS).done)
        // Wanting the wake word makes the microphone required.
        SettingsStores.get(app).wakeWordEnabled = true
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(Importance.REQUIRED, item(ItemId.MICROPHONE).importance)
    }

    /** device-action items follow the grants, and SMS/calls only appear where telephony exists. */
    @Test fun deviceActionItemsFollowGrantsAndTelephony() {
        val ids = { PermissionChecklist.build(AndroidPermissionProbe.snapshot(app)).map { it.id }.toSet() }
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_CALLING, false)
        assertTrue(ItemId.CONTACTS in ids() && ItemId.LOCATION in ids() && ItemId.SYSTEM_SETTINGS in ids() && ItemId.DND in ids())
        assertFalse(ItemId.SMS in ids() || ItemId.PHONE in ids())
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app.packageManager).setSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY_CALLING, true)
        assertTrue(ItemId.SMS in ids() && ItemId.PHONE in ids())

        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
        assertFalse(item(ItemId.CONTACTS).done)
        assertFalse(item(ItemId.SMS).done)
        assertEquals(Importance.OPTIONAL, item(ItemId.SMS).importance)
        // Personal data is never granted through the shell; special access can be.
        assertFalse(item(ItemId.SMS).privilegedGrantable || item(ItemId.CONTACTS).privilegedGrantable)
        assertTrue(item(ItemId.SYSTEM_SETTINGS).privilegedGrantable)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
        assertTrue(item(ItemId.CONTACTS).done)
        assertTrue(item(ItemId.SMS).done)
        // The same grant is what makes the registry offer the tool (re-read per task, no restart).
        val names = com.eggman.backstage.tools.ToolRegistry.default().forAgent(
            com.eggman.backstage.tools.ToolRegistry.Availability(desktop = false,
                deviceCapabilities = com.eggman.backstage.tools.DeviceAccess.granted(app).flatMap { it.capabilities }.toSet()),
        ).map { it.name }
        assertTrue("contacts_search" in names && "sms_send" in names)
        assertFalse("phone_call" in names)
    }

    @Test fun defaultAssistantIsAnOptionalItemWithASettingsShortcut() {
        val a = item(ItemId.ASSISTANT)
        assertFalse(a.done)
        assertEquals(Importance.OPTIONAL, a.importance)
        assertEquals(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS, AndroidPermissionProbe.assistantSettings().action)
        // Optional items never block the agent.
        assertTrue(PermissionChecklist.blockers(listOf(a)).isEmpty())
    }
}
