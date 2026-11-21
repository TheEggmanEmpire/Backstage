package com.eggman.backstage.host

import android.content.Intent

/**
 * Defence in depth for [DesktopHost.launchIntent]: the host starts the Intent with Backstage's own identity, which
 * may open the app's non-exported components and grant access to its own content providers. Whatever the caller
 * built (the agent already sanitises model-supplied URIs), the host never forwards URI grants and never targets
 * Backstage itself (classic intent redirection).
 */
object DesktopIntentPolicy {
    /** URI permission grants (read/write, persistable, prefix): never lent to the launched app. */
    const val GRANT_FLAGS: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

    /** Caller flags without URI grants, plus NEW_TASK (the launch comes from a service). */
    fun sanitizedFlags(flags: Int): Int = (flags and GRANT_FLAGS.inv()) or Intent.FLAG_ACTIVITY_NEW_TASK

    /**
     * [componentPackage] / [intentPackage] / [selectorPackage]: the explicit targets of the Intent (any may be null).
     * Implicit Intents are allowed (Android 14+ delivers them to exported components only); anything aimed at
     * [ownPackage] is refused.
   */
    fun targetAllowed(componentPackage: String?, intentPackage: String?, selectorPackage: String?, ownPackage: String): Boolean =
        listOfNotNull(componentPackage, intentPackage, selectorPackage).none { it == ownPackage }
}
