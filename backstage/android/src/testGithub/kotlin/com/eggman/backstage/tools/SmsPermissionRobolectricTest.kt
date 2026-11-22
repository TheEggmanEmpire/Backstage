package com.eggman.backstage.tools

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SmsPermissionRobolectricTest {
    @Test fun sendingRequiresTheDeclaredRuntimePermission() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        val control = AndroidDeviceControl(app)
        assertFalse(control.has("sms"))
        assertEquals(SmsSendResult.FAILED, control.sendSmsResult("12345", "hello"))
        assertNull(shadowOf(app).nextStartedActivity)
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        assertTrue(control.has("sms"))
    }
}
