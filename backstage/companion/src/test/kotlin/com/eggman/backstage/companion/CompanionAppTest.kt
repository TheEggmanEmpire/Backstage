package com.eggman.backstage.companion

import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import com.eggman.backstage.a11y.companion.CompanionProtocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CompanionAppTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @After fun clearConsent() { CompanionRuntime.setConsent(app, false) }

    @Test fun consentIsRequiredAndRevocationDropsTheLiveBackend() {
        val service = Robolectric.buildService(AgentAccessibilityService::class.java).create()
        try {
            CompanionRuntime.setConsent(app, false)
            CompanionRuntime.connected(service.get())
            assertNull(CompanionRuntime.backend)
            assertEquals(CompanionProtocol.CONSENT_NEEDED, CompanionRuntime.status(app))
            CompanionRuntime.setConsent(app, true)
            CompanionRuntime.connected(service.get())
            assertNotNull(CompanionRuntime.backend)
            assertEquals(CompanionProtocol.READY, CompanionRuntime.status(app))
            CompanionRuntime.setConsent(app, false)
            assertNull(CompanionRuntime.backend)
            assertEquals(CompanionProtocol.CONSENT_NEEDED, CompanionRuntime.status(app))
        } finally { service.destroy() }
    }

    @Test fun everyBinderMethodRejectsAnUnapprovedCallerIncludingAForwardedBinder() {
        val service = Robolectric.buildService(CompanionService::class.java).create()
        try {
            val endpoint = IAgentCompanion.Stub.asInterface(service.get().onBind(Intent()))
            val callback = object : IAgentCallback.Stub() {
                override fun onStateChanged(status: Int) = Unit
                override fun onEvent(event: CompanionEvent?) = Unit
                override fun onGestureResult(requestId: Long, completed: Boolean) = Unit
            }
            val calls: List<() -> Any?> = listOf(
                { endpoint.protocolVersion() }, { endpoint.status() }, { endpoint.subscribe(callback) },
                { endpoint.unsubscribe(callback) }, { endpoint.capabilityGaps() }, { endpoint.trackedDisplays() },
                { endpoint.windowsOn(0) }, { endpoint.capture(0, 1, 1) }, { endpoint.screenshot(0) },
                { endpoint.nodeAction(0, CompanionProtocol.SET_TEXT, 0, "secret") },
                { endpoint.imeEnter(0) }, { endpoint.globalAction(1) }, { endpoint.gesture(1, 0, floatArrayOf(0f, 1f, 1f), 60) },
                { endpoint.metadata() }, { endpoint.privilegedRequest(Bundle()) },
            )
            calls.forEachIndexed { index, call ->
                val failure = runCatching { call() }.exceptionOrNull()
                assertTrue("AIDL method $index accepted an unapproved caller: $failure", failure is SecurityException)
            }
        } finally { service.destroy() }
    }

    @Test fun theCompanionManifestAllowsUpdatesAndProtectsBothServices() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val services = info.services.orEmpty().associateBy { it.name }
        assertEquals(CompanionProtocol.PERMISSION, services.getValue(CompanionProtocol.SERVICE).permission)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", services.getValue(CompanionProtocol.ACCESSIBILITY_SERVICE).permission)
        val permission = app.packageManager.getPermissionInfo(CompanionProtocol.PERMISSION, 0)
        assertEquals(android.content.pm.PermissionInfo.PROTECTION_SIGNATURE, permission.protection)
    }
}
