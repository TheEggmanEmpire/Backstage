package com.eggman.backstage.robolectric

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.graphics.Rect
import com.eggman.backstage.agent.AgentService
import com.eggman.backstage.exec.UiNode
import com.eggman.backstage.init.Backstage
import com.eggman.backstage.voice.Speaker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration

/** Service shutdown, MCP stop actions, idle speech release, and password capture masking. */
@RunWith(RobolectricTestRunner::class)
class ServiceSpeechAndCaptureRobolectricTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var service: ServiceController<AgentService>? = null

    @Before fun setUp() {
        com.eggman.backstage.agent.AgentService.laneOverride = java.util.concurrent.Executor { it.run() }
        RoboSupport.resetProcess()
        Backstage.bridgeClassName = "moe.n4tsu.dextop.NotInThisBuild"
        Backstage.init(app)
    }

    @After fun tearDown() {
        com.eggman.backstage.agent.AgentService.laneOverride = null
        runCatching { Backstage.controller.cancelAll() }
        service?.let { runCatching { it.destroy() } }
        Speaker.shutdown()
        ShadowTextToSpeech.reset()
        RoboSupport.resetProcess()
    }

    private fun start(action: String? = null, startId: Int): ServiceController<AgentService> {
        val intent = Intent(app, AgentService::class.java).apply { action?.let(::setAction) }
        val c = service?.withIntent(intent) ?: Robolectric.buildService(AgentService::class.java, intent).create().also { service = it }
        c.startCommand(0, startId)
        return c
    }

    @Test fun idleShutdownStopsOnlyForTheNewestStart() {
        val c = start(startId = 7)
        val sh = shadowOf(c.get())
        assertTrue(sh.isStoppedBySelf)
        // stopSelfResult(newest start id), never stopSelf: a start that is still on its way keeps the service.
        assertEquals(7, sh.stopSelfResultId)
        assertEquals(-1, sh.stopSelfId.takeIf { it != 0 } ?: -1)
    }

    @Test fun mcpNotificationAlwaysOffersStopMcp() {
        val settings = Backstage.environment.settings
        val port = RoboSupport.freePort()
        settings.mcpPort = port
        settings.mcpEnabled = true
        start(startId = 1)
        assertTrue(RoboSupport.listening(port))
        RoboSupport.advanceMain(300)
        val n: Notification = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(AgentService.NOTIFICATION_ID)!!
        val stop = n.actions.orEmpty().firstOrNull { it.title == AgentService.STOP_MCP_LABEL }
        assertNotNull("Stop MCP action", stop)
        // The action switches the server off for good and frees the port.
        start(AgentService.ACTION_STOP_MCP, startId = 2)
        assertFalse(settings.mcpEnabled)
        assertTrue(RoboSupport.portReleased(port))
    }

    @Test fun speakerReleasesTheEngineAfterAnIdleMinute() {
        Speaker.speak(app, "The task is done.")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val shadow = shadowOf(tts)
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertEquals("The task is done.", shadow.lastSpokenText)
        assertTrue(Speaker.engineBound)
        // The shadow engine reports each utterance done on the main looper; a new reply re-arms the idle timer.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30_000))
        Speaker.speak(app, "And one more thing.")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Speaker.IDLE_RELEASE_MS - 5_000))
        assertTrue(Speaker.engineBound)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000))
        assertFalse("released after an idle minute", Speaker.engineBound)
        assertTrue(shadow.isShutdown)
        // The next reply binds a fresh engine.
        Speaker.speak(app, "Again.")
        assertTrue(Speaker.engineBound)
    }

    /** the accessibility capture never hands a password field's text to anything. */
    @Test fun captureMasksPasswordFieldsAtTheSource() {
        val a11y = A11yTrees.connectService()
        try {
            val pw = A11yTrees.node("hunter2", cls = "android.widget.EditText", bounds = Rect(0, 100, 1080, 200), pkg = "com.example.bank", editable = true)
            pw.isPassword = true
            val root = A11yTrees.node(cls = "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 1920), pkg = "com.example.bank", children = listOf(pw))
            shadowOf(a11y.get()).setWindows(listOf(A11yTrees.window(1, root, Rect(0, 0, 1080, 1920))))
            val capture = A11yTrees.agent().capture(0, 1080, 1920)
            val field = capture.windows.single().root!!.children.single()
            assertTrue(field.password)
            assertEquals(UiNode.PASSWORD_MASK, field.text)
            assertFalse(com.eggman.backstage.exec.ScreenMarkup.render(capture).contains("hunter2"))
        } finally {
            a11y.destroy()
        }
    }
}
