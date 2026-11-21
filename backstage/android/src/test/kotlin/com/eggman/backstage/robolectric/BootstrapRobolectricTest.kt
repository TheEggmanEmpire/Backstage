package com.eggman.backstage.robolectric

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.agent.api.Agents
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfacesInitProvider
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.init.BackstageInitProvider
import com.eggman.backstage.notify.NotifyChannels
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.tools.AgentExtensions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList

/** Host with the optional extras the real glue implements (mini window caption). */
class ExtrasDesktopHost : DesktopHost by FakeDesktopHost(), DesktopHostExtras {
    val captions = CopyOnWriteArrayList<String?>()
    val listeners = CopyOnWriteArrayList<DesktopHost.Listener>()
    override fun addListener(listener: DesktopHost.Listener) { listeners += listener }
    override fun setMiniStatus(text: String?) { captions += text }
    override fun setMiniShape(shape: String) = true
    override fun miniShape() = DesktopHostExtras.SHAPE_ROUNDED
    override fun usePrivilegeProvider(provider: String) = false
    override fun desktopAccessibilityComponent(): String? = null
}

/**
 * Stand-in for Dextop's `moe.n4tsu.dextop.BackstageDesktopBridge` (an `object` with `install(Context)`): registers a
 * host exactly like the real glue does inside the merged app.
 */
object TestDesktopBridge {
    @Volatile var installedWith: Context? = null
    val host = ExtrasDesktopHost()

    @Suppress("unused") // called reflectively by Backstage.init
    fun install(context: Context) {
        installedWith = context
        DesktopHosts.current = host
    }
}

/** Glue whose install throws (e.g. a Dextop internal API changed): the agent must still come up, phone-only. */
object BrokenDesktopBridge {
    @Suppress("unused")
    fun install(context: Context): Unit = throw IllegalStateException("glue out of date")
}

@RunWith(RobolectricTestRunner::class)
class BootstrapRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        RoboSupport.resetProcess()
        TestDesktopBridge.installedWith = null
        TestDesktopBridge.host.captions.clear()
        TestDesktopBridge.host.listeners.clear()
    }

    @After fun tearDown() = RoboSupport.resetProcess()

    private fun startProvider() = Robolectric.setupContentProvider(BackstageInitProvider::class.java, "${app.packageName}.backstage-init")

    @Test fun providerIsDeclaredPrivateAndEarly() {
        val core = app.packageManager.getProviderInfo(ComponentName(app, BackstageInitProvider::class.java), 0)
        val surfaces = app.packageManager.getProviderInfo(ComponentName(app, SurfacesInitProvider::class.java), 0)
        assertFalse(core.exported)
        assertFalse(surfaces.exported)
        assertEquals("${app.packageName}.backstage-init", core.authority)
        // Higher initOrder runs first: the core installs Agents.controller before the surfaces look for it.
        assertTrue(core.initOrder > surfaces.initOrder)
    }

    @Test fun bootstrapWithoutGlueRunsPhoneOnly() {
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        assertNull(AgentAccess.controller())
        org.robolectric.Shadows.shadowOf(app).clearStartedServices()
        startProvider()

        assertTrue(Backstage.isInitialized)
        assertEquals(Backstage.Bridge.ABSENT, Backstage.bridge)
        assertNull(DesktopHosts.current)
        assertSame(Backstage.controller, Agents.controller)
        assertSame(Backstage.controller, AgentAccess.controller())
        assertSame(app, Backstage.context)
        val nm = app.getSystemService(NotificationManager::class.java)
        listOf(Backstage.CHANNEL_AGENT, Backstage.CHANNEL_MCP, Backstage.CHANNEL_ATTENTION).forEach { assertNotNull(it, nm.getNotificationChannel(it)) }
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Backstage.CHANNEL_ATTENTION).importance)
        // MCP is off by default: bootstrap does not start the foreground service.
        assertTrue(org.robolectric.Shadows.shadowOf(app).allStartedServices.none { it.component?.className == AgentService::class.java.name })
        // The readiness report knows there is no desktop.
        val r = Backstage.controller.readiness()
        assertFalse(r.desktopActive)
        assertFalse(r.providerConfigured)

        // A second init (AgentService, a second provider) is a no-op.
        val controller = Backstage.controller
        Backstage.init(app)
        assertSame(controller, Backstage.controller)
    }

    @Test fun bootstrapWithGlueInstallsTheHostAndDrivesTheMiniCaption() {
        Backstage.bridgeClassName = TestDesktopBridge::class.java.name
        startProvider()

        assertEquals(Backstage.Bridge.INSTALLED, Backstage.bridge)
        assertSame(app, TestDesktopBridge.installedWith)
        assertSame(TestDesktopBridge.host, DesktopHosts.current)
        // Accessibility auto-enable follows desktop start/stop.
        assertEquals(1, TestDesktopBridge.host.listeners.size)

        // A task without a configured provider fails at once; its outcome reaches the mini window's caption.
        val handle = Backstage.controller.submit(TaskRequest("Open the calculator", Source.CHAT))
        assertTrue("task ended", RoboSupport.waitFor { Backstage.controller.state.value.current?.status == TaskStatus.FAILED })
        assertEquals(handle.id, Backstage.controller.state.value.current?.id)
        assertTrue("caption pushed: ${TestDesktopBridge.host.captions}",
            RoboSupport.waitFor { TestDesktopBridge.host.captions.any { it?.startsWith("Failed:") == true } })
        // Readiness reports the host (desktop session of the fake is active).
        assertTrue(Backstage.controller.readiness().desktopActive)
    }

    @Test fun brokenGlueStillBootsPhoneOnly() {
        Backstage.bridgeClassName = BrokenDesktopBridge::class.java.name
        startProvider()
        assertTrue(Backstage.isInitialized)
        assertEquals(Backstage.Bridge.FAILED, Backstage.bridge)
        assertNull(DesktopHosts.current)
        assertNotNull(AgentAccess.controller())
    }

    @Test fun mcpEnabledAtBootStartsTheForegroundService() {
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        com.eggman.backstage.data.Settings(app).mcpEnabled = true
        startProvider()
        assertTrue(org.robolectric.Shadows.shadowOf(app).allStartedServices.any { it.component?.className == AgentService::class.java.name })
    }

    @Test fun surfacesProviderWiresChannelsAndTheAgentScheduler() {
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        startProvider()
        Robolectric.setupContentProvider(SurfacesInitProvider::class.java, "${app.packageName}.backstage-surfaces")
        val nm = app.getSystemService(NotificationManager::class.java)
        assertNotNull(nm.getNotificationChannel(NotifyChannels.STATUS))
        assertNotNull(nm.getNotificationChannel(NotifyChannels.SCHEDULE))
        assertNotNull(AgentExtensions.scheduler)
    }
}
