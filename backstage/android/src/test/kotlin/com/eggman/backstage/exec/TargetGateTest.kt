package com.eggman.backstage.exec

import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent is gated to the DeX desktop by default and only uses the phone after an explicit switch. */
class TargetGateTest {
    private fun ctx(
        requested: Target = Target.AUTO,
        default: Target = Target.DESKTOP,
        source: Source = Source.CHAT,
        prompt: String = "reply to anna on whatsapp",
        host: Boolean = true,
        active: Boolean = false,
        priv: Boolean = true,
        externalPhone: Boolean = false,
        conv: TargetKind? = null,
    ) = TargetContext(requested, default, source, prompt, host, active, priv, true, conv, externalPhone)

    @Test fun defaultDesktopRunsOnTheDesktopAndStartsIt() {
        val d = TargetResolver.resolve(ctx())
        assertEquals(TargetKind.DESKTOP, d.kind)
        assertTrue(d.needsDesktopStart)
    }

    @Test fun defaultDesktopUsesARunningSessionWithoutRestart() {
        val d = TargetResolver.resolve(ctx(active = true))
        assertEquals(TargetKind.DESKTOP, d.kind)
        assertEquals(false, d.needsDesktopStart)
    }

    @Test fun defaultDesktopNeverFallsBackToThePhone() {
        // desktop not running and cannot be started (no Shizuku/wireless debugging)
        val d = TargetResolver.resolve(ctx(active = false, priv = false))
        assertNull(d.kind)
        assertTrue(d.reason.contains("switch the target to Phone"))
        // phone-sounding wording does not leak to the phone either
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(prompt = "what is on this screen")).kind)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(source = Source.ASSIST, prompt = "summarise this")).kind)
        // a phone follow-up from an earlier session does not pull the task off the desktop
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(conv = TargetKind.PHONE)).kind)
        // phone-only build (no Dextop glue): still no phone fallback
        assertNull(TargetResolver.resolve(ctx(host = false)).kind)
    }

    @Test fun explicitPhoneSwitchFromTheUserIsHonoured() {
        for (src in listOf(Source.CHAT, Source.VOICE, Source.WAKE_WORD, Source.OVERLAY, Source.TILE, Source.ASSIST, Source.SCHEDULE, Source.TASKER)) {
            assertEquals(src.name, TargetKind.PHONE, TargetResolver.resolve(ctx(requested = Target.PHONE, source = src)).kind)
        }
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(default = Target.PHONE)).kind)
    }

    @Test fun remoteCallersAreGatedToTheDesktopUnlessAllowed() {
        for (src in listOf(Source.MCP, Source.BROADCAST)) {
            assertNull(TargetResolver.resolve(ctx(requested = Target.PHONE, source = src)).kind)
            assertNull(TargetResolver.resolve(ctx(default = Target.PHONE, source = src)).kind)
            assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(source = src)).kind)
            assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(requested = Target.PHONE, source = src, externalPhone = true)).kind)
        }
    }

    @Test fun explicitDesktopRequestWorksWhateverTheDefault() {
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(requested = Target.DESKTOP, default = Target.PHONE)).kind)
    }

    @Test fun autoDefaultKeepsTheHeuristicPolicy() {
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(default = Target.AUTO, prompt = "what is on this screen")).kind)
        assertEquals(TargetKind.DESKTOP, TargetResolver.resolve(ctx(default = Target.AUTO, active = true)).kind)
        assertEquals(TargetKind.PHONE, TargetResolver.resolve(ctx(default = Target.AUTO, priv = false)).kind)
    }
}
