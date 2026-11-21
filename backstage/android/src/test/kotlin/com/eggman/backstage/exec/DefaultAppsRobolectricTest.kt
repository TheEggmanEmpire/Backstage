package com.eggman.backstage.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** open_url pins a link to the default app's place only when Android has no default (a chooser). */
@RunWith(RobolectricTestRunner::class)
class DefaultAppsRobolectricTest {
    private val own = setOf("com.eggman.backstage")

    @Test fun plainLinksArePinnedWhenAskedIntentUrisKeepTheirOwnPackage() {
        val pinned = SafeIntents.forUri("https://example.org/", own, { "android" }) { "com.sec.android.app.sbrowser" }
        assertEquals("com.sec.android.app.sbrowser", pinned.`package`)
        val left = SafeIntents.forUri("https://example.org/", own, { "org.mozilla.firefox" }) { null }
        assertNull(left.`package`)
        // Never our own app, whatever the pin says.
        assertNull(SafeIntents.forUri("https://example.org/", own, null) { "com.eggman.backstage" }.`package`)
        val explicit = SafeIntents.forUri("intent://x#Intent;scheme=https;package=org.mozilla.firefox;end", own, null) { "com.sec.android.app.sbrowser" }
        assertEquals("org.mozilla.firefox", explicit.`package`)
    }

    /** browser_search: the default browser's own search, query decoded intact, no BROWSABLE requirement. */
    @Test fun webSearchIntentForTheDefaultBrowser() {
        val uri = com.eggman.backstage.tools.BrowserSearch.webSearchIntent("küche & bad; a=b", "org.mozilla.firefox")
        val i = SafeIntents.forUri(uri, own, null, null)
        assertEquals("android.intent.action.WEB_SEARCH", i.action)
        assertEquals("org.mozilla.firefox", i.`package`)
        assertEquals("küche & bad; a=b", i.getStringExtra("query"))
        org.junit.Assert.assertFalse(i.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        // Other intent: URIs keep BROWSABLE, and a web search without a package does too.
        val other = SafeIntents.forUri("intent:#Intent;action=android.intent.action.WEB_SEARCH;S.query=x;end", own, null, null)
        org.junit.Assert.assertTrue(other.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SafeIntents.forUri(com.eggman.backstage.tools.BrowserSearch.webSearchIntent("x", "com.eggman.backstage"), own, null, null)
        }
    }

    @Test fun probesBecomeTheIntentsTheManifestDeclares() {
        val i = AndroidDefaultApps.intent(DefaultAppsResolver.probes.getValue(AppRole.GALLERY).first())
        assertEquals("android.intent.action.VIEW", i.action)
        assertEquals("image/*", i.type)
        val b = AndroidDefaultApps.intent(DefaultAppsResolver.probes.getValue(AppRole.BROWSER).first())
        assertEquals("https", b.data?.scheme)
    }
}
