package com.eggman.backstage.surfaces

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import com.eggman.backstage.automation.BroadcastApiReceiver
import com.eggman.backstage.automation.LocaleFireReceiver
import com.eggman.backstage.notify.NotificationActionReceiver
import com.eggman.backstage.overlay.BubbleService
import com.eggman.backstage.schedule.ScheduleAlarmReceiver
import com.eggman.backstage.voice.WakeWordService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Checks of the merged library manifest for the  components (exported flags, FGS types). */
@RunWith(RobolectricTestRunner::class)
class SurfacesManifestRobolectricTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val pm get() = ctx.packageManager

    @Test fun bubbleServiceIsSpecialUseOnly() {
        val info = pm.getServiceInfo(ComponentName(ctx, BubbleService::class.java), 0)
        // voice runs in the chat sheet the bubble opens (a visible activity), not in the service.
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        assertFalse(info.exported)
    }

    @Test fun wakeWordServiceIsMicrophoneOnlyAndPrivate() {
        val info = pm.getServiceInfo(ComponentName(ctx, WakeWordService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, info.foregroundServiceType)
        assertFalse(info.exported)
    }

    @Test fun internalReceiversAreNotExported() {
        listOf(NotificationActionReceiver::class.java, ScheduleAlarmReceiver::class.java).forEach {
            assertFalse(it.simpleName, pm.getReceiverInfo(ComponentName(ctx, it), PackageManager.GET_META_DATA).exported)
        }
        listOf(BroadcastApiReceiver::class.java, LocaleFireReceiver::class.java).forEach {
            assertTrue(it.simpleName, pm.getReceiverInfo(ComponentName(ctx, it), 0).exported)
        }
    }
}
