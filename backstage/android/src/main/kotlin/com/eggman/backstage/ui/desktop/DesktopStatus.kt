package com.eggman.backstage.ui.desktop

import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHost.Presentation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Snapshot of the Dextop desktop as seen through [DesktopHost]. */
data class DesktopStatus(
    /** A Dextop host is installed (false in phone-only builds). */
    val hostAvailable: Boolean = false,
    val privileged: Boolean = false,
    val active: Boolean = false,
    val presentation: Presentation = Presentation.NONE,
    val geometry: DesktopHost.Geometry? = null,
    val direct: Boolean = false,
) {
    /** Presentation buttons that make sense. */
    val presentationChoices: List<Presentation>
        get() = if (!active) emptyList()
        else if (!direct) listOf(Presentation.FULL) // MINI/HIDDEN need the direct owned display
        else listOf(Presentation.FULL, Presentation.MINI, Presentation.HIDDEN)

    companion object {
        fun of(host: DesktopHost?): DesktopStatus {
            if (host == null) return DesktopStatus()
            val active = runCatching { host.sessionActive() }.getOrDefault(false)
            return DesktopStatus(
                hostAvailable = true,
                privileged = runCatching { host.privilegedAvailable() }.getOrDefault(false),
                active = active,
                presentation = if (active) runCatching { host.presentation() }.getOrDefault(Presentation.NONE) else Presentation.NONE,
                geometry = if (active) runCatching { host.geometry() }.getOrNull() else null,
                direct = active && runCatching { host.directDisplay() }.getOrDefault(false),
            )
        }

        fun label(p: Presentation): String = when (p) {
            Presentation.FULL -> "Full screen"
            Presentation.MINI -> "Mini window"
            Presentation.HIDDEN -> "Background"
            Presentation.IN_APP -> "In the app"
            Presentation.NONE -> "Not running"
        }
    }
}

/** Live desktop status: host listener events plus a slow poll (privilege can change silently). */
fun desktopStatusFlow(pollMs: Long = 3000): Flow<DesktopStatus> = callbackFlow {
    var registered: DesktopHost? = null
    val listener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: Presentation) {
            trySend(DesktopStatus.of(AgentAccess.host()))
        }
    }
    val poll = launch {
        while (true) {
            val host = AgentAccess.host()
            if (host !== registered) {
                registered?.removeListener(listener)
                host?.addListener(listener)
                registered = host
            }
            trySend(DesktopStatus.of(host))
            delay(pollMs)
        }
    }
    awaitClose {
        poll.cancel()
        registered?.removeListener(listener)
    }
}.distinctUntilChanged()
