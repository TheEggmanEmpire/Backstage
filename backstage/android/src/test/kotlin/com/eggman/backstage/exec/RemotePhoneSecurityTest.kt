package com.eggman.backstage.exec

import com.eggman.backstage.agent.TaskTools
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.tools.ToolRegistry
import org.junit.Assert.*
import org.junit.Test

class RemotePhoneSecurityTest {
    private fun context(source: Source, prompt: String = "on my phone", host: Boolean = true,
                        previous: TargetKind? = null, allowed: Boolean = false) = TargetContext(
        Target.AUTO, Target.AUTO, source, prompt, host, false, false, false, previous, allowed)

    @Test fun remotePhoneHintIsGated() {
        for (source in listOf(Source.MCP, Source.BROADCAST)) {
            val c = context(source)
            assertEquals(TargetDecision(null, false, TargetResolver.PHONE_LOCKED), TargetResolver.resolve(c))
            assertFalse(TargetResolver.phoneAllowed(c))
        }
    }
    @Test fun everyRemotePhoneFallbackAndConversationIsGated() {
        for (source in listOf(Source.MCP, Source.BROADCAST)) for (host in listOf(false, true)) {
            for (previous in listOf(null, TargetKind.PHONE)) {
                val c = context(source, "check something", host, previous)
                assertNull(TargetResolver.resolve(c).kind)
                assertEquals(TargetResolver.PHONE_LOCKED, TargetResolver.resolve(c).reason)
                assertFalse(TargetResolver.phoneAllowed(c))
            }
        }
    }
    @Test fun explicitRemotePhoneAndPhoneDefaultAreGated() {
        for (source in listOf(Source.MCP, Source.BROADCAST)) {
            assertNull(TargetResolver.resolve(context(source).copy(requested = Target.PHONE)).kind)
            assertNull(TargetResolver.resolve(context(source).copy(defaultTarget = Target.PHONE)).kind)
        }
    }
    @Test fun optInAndLocalSurfaceStillWork() {
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(context(Source.MCP, allowed = true)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(context(Source.CHAT)).kind)
        val desktop = context(Source.MCP).copy(prompt = "on the desktop", desktopActive = true)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(desktop).kind)
    }
    @Test fun phoneOnlyBuildDoesNotAdvertiseLockedPhoneTools() {
        assertFalse(TaskTools.availability(ToolRegistry.Availability(desktop = false),
            hostPresent = false, target = null, phoneAllowed = false).phone)
    }
}
