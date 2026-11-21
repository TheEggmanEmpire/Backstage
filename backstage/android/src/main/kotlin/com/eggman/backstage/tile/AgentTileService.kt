package com.eggman.backstage.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.StatusText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Tap: open Backstage with the microphone armed for a voice command.
 * While a task runs the tile is active and shows its status; tapping it then opens the chat.
 */
class AgentTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        job = s.launch { AgentAccess.state().collect { render() } }
        render()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = AgentAccess.state().value
        val active = StatusText.isActive(state.current)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (active) StatusText.line(state).take(40) else "Voice command"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val active = StatusText.isActive(AgentAccess.state().value.current)
        val intent = Intent(this, AgentScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EntryRouter.EXTRA_VOICE, !active)
            .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken)
        val launch = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch()
    }
}
