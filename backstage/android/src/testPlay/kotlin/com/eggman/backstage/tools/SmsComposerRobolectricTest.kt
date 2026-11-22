package com.eggman.backstage.tools

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Play omits SEND_SMS. Its device tool opens the user's SMS app and never claims a send happened. */
@RunWith(RobolectricTestRunner::class)
class SmsComposerRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun omitSmsPermission() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions = info.requestedPermissions.orEmpty().filterNot { it == Manifest.permission.SEND_SMS }.toTypedArray()
        shadowOf(app.packageManager).installPackage(info)
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, true)
    }

    private fun addComposer() {
        val component = ComponentName("example.sms", "example.sms.Compose")
        shadowOf(app.packageManager).addActivityIfNotPresent(component).apply { exported = true; enabled = true }
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SENDTO).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("smsto")
        })
    }

    @Test fun missingPermissionOpensPrefilledComposer() {
        omitSmsPermission()
        addComposer()
        val control = AndroidDeviceControl(app, canOpenDraft = { true })
        assertTrue(control.has("sms"))
        assertTrue(control.smsRequiresUserSend)
        assertEquals(SmsSendResult.DRAFT_OPENED, control.sendSmsResult("+49 170 1234567", "Meet at 18:00 & bring tea"))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("smsto:+49 170 1234567", Uri.decode(started.dataString))
        assertEquals("Meet at 18:00 & bring tea", started.getStringExtra("sms_body"))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun backgroundDraftWaitsForANotificationTap() {
        omitSmsPermission(); addComposer()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val control = AndroidDeviceControl(app, canOpenDraft = { false })
        assertEquals(SmsSendResult.DRAFT_READY, control.sendSmsResult("12345", "review me"))
        assertNull(shadowOf(app).nextStartedActivity)
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val notification = shadowOf(nm).allNotifications.single()
        assertEquals("Review SMS draft", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        notification.contentIntent.send()
        val draft = shadowOf(app).nextStartedActivity
        assertEquals("review me", draft.getStringExtra("sms_body"))
        assertEquals("smsto:12345", draft.dataString)
    }

    @Test fun blockedNotificationsCannotClaimADraftIsReady() {
        omitSmsPermission(); addComposer()
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        shadowOf(nm).setNotificationsEnabled(false)
        assertEquals(SmsSendResult.FAILED, AndroidDeviceControl(app, canOpenDraft = { false }).sendSmsResult("12345", "hello"))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun attachedButHiddenOverlayCannotClaimTheComposerOpened() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val old = com.eggman.backstage.exec.OverlayHooks.controller
        var visible = true
        com.eggman.backstage.exec.OverlayHooks.controller = object : com.eggman.backstage.exec.OverlayController {
            override suspend fun hide() { visible = false }
            override fun restore() { visible = true }
            override fun bounds() = if (visible) com.eggman.backstage.exec.Box(0, 0, 50, 50) else null
        }
        try {
            assertTrue(SmsDraftPolicy.canOpen(app))
            visible = false
            assertFalse(SmsDraftPolicy.canOpen(app))
        } finally { com.eggman.backstage.exec.OverlayHooks.controller = old }
    }

    @Test fun onlyForegroundOrVisibleGrantedOverlayAllowsImmediateDrafts() {
        assertTrue(SmsDraftPolicy.canOpen(true, false, false))
        assertTrue(SmsDraftPolicy.canOpen(false, true, true))
        assertFalse(SmsDraftPolicy.canOpen(false, true, false))
        assertFalse(SmsDraftPolicy.canOpen(false, false, true))
        assertFalse(SmsDraftPolicy.canOpen(false, false, false))
    }

    @Test fun noComposerMeansUnavailableAndSafeFailure() {
        omitSmsPermission()
        val control = AndroidDeviceControl(app)
        assertFalse(control.has("sms"))
        assertFalse(control.sendSms("12345", "hello"))
    }

}
