package com.eggman.backstage.exec

import android.content.Intent
import android.net.Uri

/**
 * Turns a model-supplied URI into an Intent that cannot be used for intent redirection: the agent (and
 * anything that prompt-injects it) runs with Backstage's identity, which may start the app's own non-exported
 * components and grant access to its own content providers. `intent:` URIs keep only action, data, type,
 * categories, package and plain extras; component, selector, grant/persistable flags and clip data are dropped,
 * BROWSABLE is required, and targets inside our own app are refused.
 */
object SafeIntents {
    val blockedSchemes = setOf("file", "content", "javascript", "android-app")

    /** The URI's scheme when it may be opened at all; throws IllegalArgumentException otherwise (pure, unit-tested). */
    fun checkedScheme(uri: String): String {
        val scheme = uri.trim().substringBefore(':', "").lowercase()
        require(scheme.isNotEmpty() && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) { "not a URI" }
        require(scheme !in blockedSchemes) { "$scheme: URIs are not allowed" }
        return scheme
    }

    /**
     * [resolvesTo] names the package whose activity would handle the intent (PackageManager.resolveActivity);
     * an intent that would land in Backstage itself is refused even without an explicit package.
   */
    fun forUri(
        uri: String,
        ownPackages: Set<String>,
        resolvesTo: ((Intent) -> String?)? = null,
        /** the app to use when Android has no default for this link (it would ask with a chooser). */
        pinWhenNoDefault: ((Intent) -> String?)? = null,
    ): Intent {
        val trimmed = uri.trim()
        val scheme = checkedScheme(trimmed)
        val intent = if (scheme == "intent") {
            val parsed = Intent.parseUri(trimmed, Intent.URI_INTENT_SCHEME)
            parsed.component = null
            parsed.selector = null
            parsed.clipData = null
            parsed.flags = 0
            // A web search aimed at one app (browser_search: the default browser's own engine) carries only a query;
            // browsers register ACTION_WEB_SEARCH without the BROWSABLE category, so it is not added there.
            if (!(parsed.action == Intent.ACTION_WEB_SEARCH && parsed.`package` != null && parsed.data == null)) {
                parsed.addCategory(Intent.CATEGORY_BROWSABLE)
            }
            parsed.data?.scheme?.lowercase()?.let { require(it !in blockedSchemes) { "$it: URIs are not allowed" } }
            parsed
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        require(intent.`package` == null || intent.`package` !in ownPackages) { "cannot open Backstage's own components" }
        val handler = resolvesTo?.let { r -> runCatching { r(intent) }.getOrNull() }
        require(handler == null || handler !in ownPackages) { "cannot open Backstage's own components" }
        if (intent.`package` == null && intent.component == null) {
            pinWhenNoDefault?.let { p -> runCatching { p(intent) }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it !in ownPackages }?.let { intent.setPackage(it) }
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
