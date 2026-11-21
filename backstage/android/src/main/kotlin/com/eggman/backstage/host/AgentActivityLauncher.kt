package com.eggman.backstage.host

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/** Agent starts never borrow an accessibility-service Context or its background-launch exemption. */
class AgentActivityLauncher(
    context: Context,
    private val privileged: () -> Boolean,
    private val shell: (Array<String>) -> Boolean,
) {
    private val app = context.applicationContext

    fun launch(intent: Intent, displayId: Int, bounds: Rect? = null, freeform: Boolean = false): Boolean {
        if (displayId <= 0) return false
        val safe = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val component = safe.component ?: safe.resolveActivity(app.packageManager)?.also { safe.component = it }
            ?: return false
        if (privileged()) {
            val command = buildList {
                addAll(listOf("am", "start", "--display", displayId.toString(), "-n", component.flattenToShortString()))
                if (freeform) addAll(listOf("--windowingMode", "5"))
                // Intent URI preserves action/categories, data and typed extras without constructing shell syntax.
                add(safe.toUri(Intent.URI_INTENT_SCHEME))
            }.toTypedArray()
            if (runCatching { shell(command) }.getOrDefault(false)) return true
        }
        return runCatching {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            if (bounds != null) options.launchBounds = bounds
            if (freeform) runCatching {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(options, 5)
            }
            app.startActivity(safe, options.toBundle())
        }.isSuccess
    }
}
