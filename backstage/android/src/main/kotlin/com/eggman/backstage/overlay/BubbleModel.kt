package com.eggman.backstage.overlay

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.data.ResultDisplay

/**
 * The floating bubble's rules (pure, BubbleModelTest). the bubble is a launcher: a tap opens the same chat
 * sheet as the desktop popup's chat button ([com.eggman.backstage.ui.minichat.MiniChatActivity]), labelled "Bubble";
 * dragging it onto the remove target at the bottom hides it until it is shown again.
 */
object BubbleModel {
    /** Messages typed in the chat opened from the bubble ("Bubble" in the chat and the history). */
    val SOURCE = Source.OVERLAY

    const val HIDDEN_TOAST = "Bubble hidden — show it again from the notification"
    const val HIDDEN_TITLE = "Bubble hidden"
    const val HIDDEN_TEXT = "Tap to show the bubble again."
    /** TalkBack name of the bubble: the app's name (its status is the state description). */
    const val LABEL = "Backstage"
    /** The bubble shows the Backstage app icon (round rendition of the launcher icon), not a generic agent glyph. */
    val ICON: Int = com.eggman.backstage.R.drawable.bs_ic_launcher_round
    const val REMOVE_LABEL = "Remove the bubble"
    const val OPEN_LABEL = "Open the chat"
    const val VOICE_LABEL = "Speak a task"

    /** The bubble is shown when the setting is on and the user did not drag it away since. */
    fun wanted(enabled: Boolean, dismissed: Boolean): Boolean = enabled && !dismissed

    /**
     * A finished task opens the bubble's chat with the result only when the user chose that, the phone is in use and
     * unlocked, and neither the app nor a chat sheet already shows it.
   */
    fun resultOpensChat(
        display: ResultDisplay,
        appVisible: Boolean,
        chatShowing: Boolean,
        interactive: Boolean,
        locked: Boolean,
    ): Boolean = display == ResultDisplay.EXPANDED && !appVisible && !chatShowing && interactive && !locked
}
