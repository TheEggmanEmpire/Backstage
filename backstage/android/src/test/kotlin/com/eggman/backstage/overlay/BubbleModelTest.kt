package com.eggman.backstage.overlay

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.data.ResultDisplay
import com.eggman.backstage.ui.chat.sourceLabel
import com.eggman.backstage.ui.minichat.MiniChatModel
import com.eggman.backstage.host.DesktopHost.Presentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleModelTest {
    @Test fun messagesFromTheBubbleAreLabelledBubble() {
        assertEquals(Source.OVERLAY, BubbleModel.SOURCE)
        assertEquals("Bubble", sourceLabel(BubbleModel.SOURCE))
        assertEquals("Popup chat", sourceLabel(Source.POPUP_CHAT))
    }

    @Test fun shownWhenOnAndNotDraggedAway() {
        assertTrue(BubbleModel.wanted(enabled = true, dismissed = false))
        assertFalse(BubbleModel.wanted(enabled = true, dismissed = true))
        assertFalse(BubbleModel.wanted(enabled = false, dismissed = false))
    }

    @Test fun aResultOpensTheChatOnlyWhenNothingElseShowsIt() {
        fun opens(
            display: ResultDisplay = ResultDisplay.EXPANDED, app: Boolean = false, chat: Boolean = false,
            interactive: Boolean = true, locked: Boolean = false,
        ) = BubbleModel.resultOpensChat(display, app, chat, interactive, locked)
        assertTrue(opens())
        assertFalse(opens(display = ResultDisplay.COMPACT))
        assertFalse(opens(app = true))
        assertFalse(opens(chat = true))
        assertFalse(opens(interactive = false))
        assertFalse(opens(locked = true))
    }

    @Test fun theBubblesSheetStaysOverTheAppButNotUnderFullScreen() {
        assertTrue(MiniChatModel.keepOpen(Presentation.IN_APP, fromBubble = true))
        assertFalse(MiniChatModel.keepOpen(Presentation.IN_APP, fromBubble = false))
        for (fromBubble in listOf(true, false)) {
            assertFalse(MiniChatModel.keepOpen(Presentation.FULL, fromBubble))
            for (p in listOf(Presentation.MINI, Presentation.HIDDEN, Presentation.NONE)) assertTrue(MiniChatModel.keepOpen(p, fromBubble))
        }
    }
}
