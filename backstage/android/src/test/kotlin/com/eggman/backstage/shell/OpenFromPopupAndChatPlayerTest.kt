package com.eggman.backstage.shell

import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.host.DesktopHost.Presentation
import com.eggman.backstage.host.PlayerSurfaceOwner
import com.eggman.backstage.surfaces.memorySettings
import com.eggman.backstage.ui.chat.DesktopPaneRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Open Backstage" from the popup keeps the desktop in the background; the chat's own live player. */
class OpenFromPopupAndChatPlayerTest {
    @Test fun openingFromThePopupKeepsTheDesktopInTheBackground() {
        // Home borrows HIDDEN only while its player is visible; MINI stays explicit.
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.HIDDEN, false, true))
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.MINI, false, true))
        // Opened with the popup's button: it stays (or goes) in the background, nothing is started.
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.HIDDEN, true, true, keepBackground = true))
        assertEquals(ShellPresence.Decision.Present(Presentation.HIDDEN), ShellPresence.onAppVisible(Presentation.MINI, true, true, keepBackground = true))
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppVisible(Presentation.NONE, true, true, keepBackground = true))
        // Leaving the app afterwards follows the normal pop-out rule.
        assertEquals(ShellPresence.Decision.Present(Presentation.MINI), ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = true))
        assertEquals(ShellPresence.Decision.Nothing, ShellPresence.onAppLeft(Presentation.HIDDEN, popOut = false))
    }

    @Test fun theChatPaneShowsOnlyForDesktopTasks() {
        assertTrue(DesktopPaneRules.visible(Target.DESKTOP, hostPresent = true))
        assertTrue(DesktopPaneRules.visible(Target.AUTO, hostPresent = true))
        assertFalse(DesktopPaneRules.visible(Target.PHONE, hostPresent = true))
        assertFalse(DesktopPaneRules.visible(Target.DESKTOP, hostPresent = false))
        assertEquals(DesktopPaneRules.Content.PLAYER, DesktopPaneRules.content(Presentation.IN_APP))
        // no "Show desktop here" placeholder; elsewhere it is a status line while the chat takes it.
        assertEquals(DesktopPaneRules.Content.STATUS, DesktopPaneRules.content(Presentation.HIDDEN))
        assertEquals(DesktopPaneRules.Content.STATUS, DesktopPaneRules.content(Presentation.MINI))
        assertEquals(DesktopPaneRules.Content.START, DesktopPaneRules.content(Presentation.NONE))
    }

    @Test fun theCollapsedStateIsRemembered() {
        val settings = memorySettings()
        assertFalse(settings.agentDesktopCollapsed)
        settings.agentDesktopCollapsed = true
        assertTrue(settings.agentDesktopCollapsed)
    }

    @Test fun onePlayerSurfaceOwnerAtATime() {
        val log = mutableListOf<String?>()
        var bound: String? = null
        val owner = PlayerSurfaceOwner<String>(bind = { bound = it; log += it }, bound = { bound })
        owner.homeAttached("home")
        assertEquals("home", bound)
        // The Agent tab's player takes the slot while it is on screen; Home's keeps its surface meanwhile.
        owner.chatAttached("chat")
        assertEquals("chat", bound)
        owner.homeAttached("home2") // Home's player resized behind the chat
        assertEquals("chat", bound)
        // The chat goes: Home's player is bound again, before the chat's surface is released.
        owner.chatDetached("chat")
        assertEquals("home2", bound)
        // Home's goes too: the display moves to the sink.
        owner.homeDetached(null)
        assertNull(bound)
        // A stale detach of a surface that is not bound changes nothing.
        owner.chatAttached("chat2")
        owner.homeDetached(null)
        assertEquals("chat2", bound)
        owner.chatDetached("old")
        assertEquals("chat2", bound)
        assertEquals(listOf<String?>("home", "chat", "home2", null, "chat2"), log)
    }
}
