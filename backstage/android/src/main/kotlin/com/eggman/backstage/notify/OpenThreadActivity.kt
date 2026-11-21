package com.eggman.backstage.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.eggman.backstage.agent.AgentControllerImpl
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.ui.AgentScreenActivity

/**
 * Invisible trampoline behind task notifications: selects the chat thread of the run the notification is about
 * (named, or the live task's), then opens the AI chat on it.
 */
class OpenThreadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = intent.getStringExtra(EXTRA_THREAD)?.takeIf { it.isNotBlank() }
            ?: runCatching { AgentAccess.state().value.current?.request?.attachments?.get(AgentControllerImpl.THREAD_KEY) }.getOrNull()
        if (thread != null) runCatching { com.eggman.backstage.data.ChatThreads.get(this).select(thread) }
        runCatching {
            startActivity(
                Intent(this, AgentScreenActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(EntryRouter.EXTRA_SCREEN, EntryRouter.Screen.CHAT.name.lowercase())
                    .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_THREAD = "com.eggman.backstage.extra.THREAD"
    }
}
