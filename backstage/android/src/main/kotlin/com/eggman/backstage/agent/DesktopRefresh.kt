package com.eggman.backstage.agent

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import kotlinx.coroutines.delay

/**
 * Restarting the desktop gives a task a clean screen: no windows, dialogs or half-typed text left by earlier work.
 * Used by the desktop_restart tool, before every automation run, and before a task whose desktop shows only black.
 */
object DesktopRefresh {
    /** Automations: everything that starts without a person typing into Backstage's chat. */
    private val AUTOMATION_SOURCES = setOf(Source.SCHEDULE, Source.TASKER, Source.BROADCAST, Source.MCP)

    /** Whether [request] should start on a freshly restarted desktop. Pure. */
    fun wantedFor(request: TaskRequest, enabled: Boolean, sessionActive: Boolean): Boolean =
        enabled && sessionActive && request.source in AUTOMATION_SOURCES &&
            request.attachments["conversation"] == null // an automation continuing a chat keeps its screen

    /**
     * Stop and start the desktop in the presentation it had ([fallback] when it had none). Prefers the host's own
     * restart (keeps the locked-mode flags); stop + start otherwise. Null when it did not come back in time.
   */
    suspend fun restart(host: DesktopHost, fallback: DesktopHost.Presentation): DesktopHost.Geometry? {
        val p = runCatching { host.presentation() }.getOrNull()?.takeUnless { it == DesktopHost.Presentation.NONE } ?: fallback
        (host as? DesktopHostExtras)?.let { extras ->
            runCatching { extras.restartDesktop(p) }.getOrNull()?.let { return it }
        }
        if (runCatching { host.sessionActive() }.getOrDefault(false)) {
            host.stopDesktop()
            var waited = 0
            while (runCatching { host.sessionActive() }.getOrDefault(false) && waited++ < 30) delay(100)
            delay(500)
        }
        return host.startDesktop(p)
    }
}
