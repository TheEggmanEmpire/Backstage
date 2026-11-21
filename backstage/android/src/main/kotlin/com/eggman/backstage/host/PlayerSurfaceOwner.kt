package com.eggman.backstage.host

/**
 * One owner of the IN_APP player surface at a time: Home's live player (Flutter texture) or the agent chat's
 * player at the top of the Agent tab. MirrorService has one player slot; this decides what is bound to it. Pure (the glue
 * passes the real Surfaces and MirrorService.Backstage.setPlayerSurface), unit-tested in PlayerSurfaceOwnerTest.
 *
 * Rules: the chat's surface exists only while the Agent tab is on screen, so it wins while it exists; Home's player keeps
 * its surface while the chat covers it and is bound again when the chat's surface goes. A surface is unbound before it
 * is released (the display moves to the other player or to the sink).
 */
class PlayerSurfaceOwner<S : Any>(private val bind: (S?) -> Unit, private val bound: () -> S?) {
    var home: S? = null
        private set
    var chat: S? = null
        private set

    fun chatAttached(surface: S) {
        chat = surface
        bind(surface)
    }

    fun chatDetached(surface: S) {
        if (chat === surface) chat = null
        if (bound() === surface) bind(home)
    }

    /** Home's player has a (new) surface: bound unless the chat's player is on screen. */
    fun homeAttached(surface: S) {
        home = surface
        if (chat == null) bind(surface)
    }

    fun homeDetached(surface: S?) {
        if (surface == null || home === surface) home = null
        val current = bound()
        if (current != null && (current === surface || surface == null) && current !== chat) bind(chat)
    }
}
