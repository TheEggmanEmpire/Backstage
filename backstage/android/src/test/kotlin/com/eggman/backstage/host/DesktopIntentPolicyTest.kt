package com.eggman.backstage.host

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopIntentPolicyTest {
    private val own = "com.eggman.backstage"

    @Test
    fun uriGrantsAreNeverForwarded() {
        val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = DesktopIntentPolicy.sanitizedFlags(requested)
        assertEquals(0, flags and DesktopIntentPolicy.GRANT_FLAGS)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun backstageItselfIsNeverATarget() {
        assertFalse(DesktopIntentPolicy.targetAllowed(own, null, null, own))
        assertFalse(DesktopIntentPolicy.targetAllowed(null, own, null, own))
        assertFalse(DesktopIntentPolicy.targetAllowed(null, null, own, own))
        assertFalse(DesktopIntentPolicy.targetAllowed("org.mozilla.firefox", null, own, own))
    }

    @Test
    fun otherAppsAndImplicitIntentsAreAllowed() {
        assertTrue(DesktopIntentPolicy.targetAllowed(null, null, null, own))
        assertTrue(DesktopIntentPolicy.targetAllowed("org.mozilla.firefox", "org.mozilla.firefox", null, own))
        assertTrue(DesktopIntentPolicy.targetAllowed(null, "com.eggman.backstage.cardex", null, own))
    }
}
