package com.eggman.backstage.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.minichat.MiniChatActivity

/**
 * Digital-assistant entry (ACTION_ASSIST / VOICE_COMMAND, e.g. long-press power). Invisible:
 * when the overlay permission is granted it opens the bubble's chat sheet over whatever app is in front with the
 * microphone listening (what it hears goes into the field; the user sends it); otherwise it opens the Backstage app in
 * voice mode.
 */
class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viaBubble = Overlay.canDraw(this) && runCatching {
            startActivity(MiniChatActivity.bubbleIntent(this, voice = MiniChatActivity.VOICE_DRAFT))
        }.isSuccess
        if (!viaBubble) {
            startActivity(
                Intent(this, AgentScreenActivity::class.java)
                    .setAction(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
