package com.eggman.backstage.a11y

/**
 * Turns the app's accessibility service (Dextop's MirrorService, the only one Backstage has) on through the
 * privileged shell (shell uid holds WRITE_SECURE_SETTINGS), the same way Dextop enables it on the github flavor.
 *
 * The secure setting is a ':'-separated list of flattened ComponentNames shared with every other
 * accessibility service (TalkBack, password managers…). We only ever append our component and never drop or reorder
 * others; the one exception is our own retired agent service (Backstage ), whose stale entry is removed.
 * Dextop rewrites the list when it starts a desktop while its service is not connected (remove + re-add of its own
 * component 180 ms apart), which can lose a concurrent write — callers therefore re-assert after desktop session changes.
 */
object AccessibilityEnabler {
    const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"
    const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"

    /** Shell runner abstraction so the logic is testable: returns (exitCode, stdout). */
    fun interface Shell {
        suspend fun run(vararg argv: String): Pair<Int, String>
    }

    fun parse(value: String?): List<String> =
        value?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            ?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Equality on flattened component names, tolerating the short form "pkg/.Cls". */
    fun sameComponent(a: String, b: String): Boolean = normalize(a) == normalize(b)

    private fun normalize(c: String): String {
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/', "")
        return "$pkg/" + if (cls.startsWith(".")) pkg + cls else cls
    }

    fun contains(value: String?, component: String): Boolean = parse(value).any { sameComponent(it, component) }

    /** New list value with [component] appended (no-op when already present). Order of others is preserved. */
    fun merge(value: String?, component: String): String {
        val list = parse(value)
        if (list.any { sameComponent(it, component) }) return list.joinToString(":")
        return (list + component).distinct().joinToString(":")
    }

    fun remove(value: String?, component: String): String =
        parse(value).filterNot { sameComponent(it, component) }.joinToString(":")

    /** Why nothing can be switched on: a build without the desktop engine has no accessibility service. */
    const val NO_HOST = "This build has no accessibility service (it comes with the desktop engine), so the agent cannot " +
        "read or operate any screen."

    sealed interface Outcome {
        data object AlreadyEnabled : Outcome
        data object Enabled : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Ensures [component] is in the enabled list and accessibility is switched on; a [retired] component of ours is
     * dropped from the list on the way. Uses `settings get/put secure` through [shell]; verifies the write.
   */
    suspend fun ensureEnabled(shell: Shell, component: String, retired: String? = null): Outcome {
        val (code, current) = shell.run("settings", "get", "secure", KEY_ENABLED_SERVICES)
        if (code != 0) return Outcome.Failed("cannot read secure settings (exit $code)")
        if (contains(current, component) && (retired == null || !contains(current, retired))) {
            val (_, on) = shell.run("settings", "get", "secure", KEY_ACCESSIBILITY_ENABLED)
            if (on.trim() == "1") return Outcome.AlreadyEnabled
            shell.run("settings", "put", "secure", KEY_ACCESSIBILITY_ENABLED, "1")
            return Outcome.Enabled
        }
        val merged = merge(if (retired != null) remove(current, retired) else current, component)
        val (putCode, putOut) = shell.run("settings", "put", "secure", KEY_ENABLED_SERVICES, merged)
        if (putCode != 0) return Outcome.Failed("settings put failed (exit $putCode) ${putOut.trim()}")
        shell.run("settings", "put", "secure", KEY_ACCESSIBILITY_ENABLED, "1")
        val (_, verify) = shell.run("settings", "get", "secure", KEY_ENABLED_SERVICES)
        return if (contains(verify, component)) Outcome.Enabled else Outcome.Failed("the system did not keep the setting")
    }

    /**
     * Guidance shown when the service cannot be enabled automatically. Since Android 13, apps
     * installed outside an app store get "restricted settings": the accessibility toggle is greyed
     * out until the user allows it from the app-info screen.
   */
    fun manualGuidance(appLabel: String, sdkInt: Int): String = buildString {
        append("Enable the \"").append(appLabel).append("\" accessibility service: Settings → Accessibility → Installed apps → ")
            .append(appLabel).append(" → On.")
        if (sdkInt >= 33) {
            append(" On Android 13+ the toggle can be locked for apps installed outside a store (\"Restricted setting\"). In that case open Settings → Apps → ")
                .append(appLabel).append(", choose \"Allow restricted settings\" from the ⋮ menu, confirm, and return to the toggle.")
        }
        append(" With Shizuku or the desktop's wireless-debugging connection active, ").append(appLabel)
            .append(" can switch it on for you.")
    }
}
