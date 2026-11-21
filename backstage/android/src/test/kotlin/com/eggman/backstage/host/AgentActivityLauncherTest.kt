package com.eggman.backstage.host

import android.app.Application
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AgentActivityLauncherTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val intent get() = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("example.app", "example.app.Main"))
    private val serviceLike get() = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent, options: Bundle?) { error("service context must never launch") }
    }

    @Test fun privilegedLaunchCarriesTheDesktopAndExplicitComponent() {
        var command = emptyList<String>()
        val launcher = AgentActivityLauncher(serviceLike, { true }, { command = it.toList(); true })
        assertTrue(launcher.launch(intent, 7))
        assertEquals(listOf("am", "start", "--display", "7", "-n", "example.app/.Main"), command.take(6))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun ordinaryFallbackAlwaysUsesApplicationContextAndDisplayOptions() {
        val launcher = AgentActivityLauncher(serviceLike, { false }, { error("no privilege") })
        assertTrue(launcher.launch(intent, 9))
        val started = shadowOf(app).nextStartedActivityForResult
        assertEquals(intent.component, started.intent.component)
        assertEquals(9, started.options.getInt("android.activity.launchDisplayId"))
    }

    @Test fun failedShellFallsBackAndInvalidDisplaysNeverLaunch() {
        var calls = 0
        val launcher = AgentActivityLauncher(serviceLike, { true }, { calls++; false })
        assertFalse(launcher.launch(intent, 0))
        assertFalse(launcher.launch(intent, -1))
        assertEquals(0, calls)
        assertTrue(launcher.launch(intent, 7))
        assertEquals(1, calls)
        assertNotNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun desktopHomeAlsoUsesShellRatherThanAnAccessibilityContext() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
            .setComponent(ComponentName("com.sec.android.app.launcher", "com.honeyspace.dexservice.SecondaryLauncher"))
        var serialized = ""
        val launcher = AgentActivityLauncher(serviceLike, { true }, { serialized = it.last(); true })
        assertTrue(launcher.launch(home, 8))
        assertTrue(serialized.contains("SECONDARY_HOME"))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
