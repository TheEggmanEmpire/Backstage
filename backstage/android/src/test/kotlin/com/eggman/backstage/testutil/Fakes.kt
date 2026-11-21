package com.eggman.backstage.testutil

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import com.eggman.backstage.exec.ActionResult
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.exec.Box
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.ScreenCapture
import com.eggman.backstage.exec.ScreenImage
import com.eggman.backstage.exec.ScreenSerializer
import com.eggman.backstage.exec.ScreenState
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.exec.UiElement
import com.eggman.backstage.exec.UiNode
import com.eggman.backstage.exec.UiWindow
import com.eggman.backstage.exec.WindowKind
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.llm.LlmClient
import com.eggman.backstage.llm.LlmException
import com.eggman.backstage.llm.LlmReply
import com.eggman.backstage.llm.LlmRequest
import com.eggman.backstage.llm.Message
import com.eggman.backstage.llm.Part
import com.eggman.backstage.llm.ProviderId
import com.eggman.backstage.llm.Role
import com.eggman.backstage.llm.StopReason
import com.eggman.backstage.llm.Usage
import com.eggman.backstage.tools.CalendarEvent
import com.eggman.backstage.tools.CalendarInfo
import com.eggman.backstage.tools.CalendarService
import com.eggman.backstage.tools.DeviceServices
import com.eggman.backstage.tools.HttpResult
import com.eggman.backstage.tools.NotificationEntry
import org.json.JSONObject

/** Builds a one-window capture from (label, clickable/editable) pairs laid out vertically. */
object Screens {
    fun node(text: String = "", desc: String = "", cls: String = "android.widget.TextView", b: Box, clickable: Boolean = false,
             editable: Boolean = false, scrollable: Boolean = false, hint: String = "", id: String = "", children: List<UiNode> = emptyList(),
             visible: Boolean = true, focused: Boolean = false) =
        UiNode(className = cls, text = text, contentDescription = desc, hint = hint, resourceId = id, bounds = b, clickable = clickable,
            editable = editable, scrollable = scrollable, visible = visible, focused = focused, children = children, packageName = "com.example")

    fun app(vararg nodes: UiNode, pkg: String = "com.example", w: Int = 1080, h: Int = 2400, displayId: Int = 0): ScreenCapture =
        ScreenCapture(displayId, w, h, listOf(
            UiWindow(1, WindowKind.APPLICATION, "Example", pkg, layer = 1, bounds = Box(0, 0, w, h), active = true, focused = true,
                root = UiNode(className = "android.widget.FrameLayout", bounds = Box(0, 0, w, h), packageName = pkg, children = nodes.toList())),
        ))

    fun buttons(vararg labels: String): ScreenCapture = app(*labels.mapIndexed { i, l ->
        node(text = l, cls = "android.widget.Button", b = Box(0, 200 + i * 150, 1080, 330 + i * 150), clickable = true)
    }.toTypedArray())
}

/** Scriptable executor: each call to screen returns the current capture; actions are recorded. */
class FakeExecutor(
    override val kind: TargetKind = TargetKind.PHONE,
    var capture: ScreenCapture = Screens.buttons("OK"),
    override val width: Int = 1080,
    override val height: Int = 2400,
) : Executor {
    override val displayId: Int get() = capture.displayId
    override val description: String get() = "fake ${kind.label}"
    val actions = mutableListOf<String>()
    /** Applied after each action: lets a test change the screen in response. */
    var onAction: (String) -> Unit = {}
    var screenshotImage: ScreenImage? = ScreenImage("SU1H", "image/jpeg", 540, 1200, 1080, 2400)
    var failTaps = false
    private val serializer = ScreenSerializer(setOf("com.eggman.backstage"))

    override var lastScreen: ScreenState? = null
    override var lastScreenshot: ScreenImage? = null
    override var lastCapture: ScreenCapture? = null

    override suspend fun screen(): ScreenState { lastCapture = capture; return serializer.serialize(capture, kind.label).also { lastScreen = it } }
    override suspend fun screenshot(): ScreenImage? = screenshotImage.also { lastScreenshot = it }
    override suspend fun observe(): Pair<ScreenState, ScreenCapture?> = serializer.serialize(capture, kind.label) to capture
    override suspend fun observeScreenshot(): ScreenImage? = screenshotImage

    private fun act(a: String, ok: Boolean = true, msg: String = a): ActionResult {
        actions += a
        if (ok) onAction(a)
        return if (ok) ActionResult.ok(msg) else ActionResult.fail("$msg failed")
    }

    override suspend fun tap(x: Float, y: Float) = act("tap ${x.toInt()},${y.toInt()}", !failTaps)
    override suspend fun longPress(x: Float, y: Float, durationMs: Long) = act("long ${x.toInt()},${y.toInt()}")
    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        act("swipe ${x1.toInt()},${y1.toInt()}->${x2.toInt()},${y2.toInt()}")
    override suspend fun clickElement(element: UiElement, long: Boolean) = act("click [${element.index}] ${element.label}", !failTaps)
    override suspend fun setText(element: UiElement?, text: String) = act("text ${element?.index} $text")
    override suspend fun scrollElement(element: UiElement?, direction: String) = act("scroll $direction")
    override suspend fun key(name: String) = act("key $name")
    override suspend fun launchApp(packageName: String, slot: String?, bounds: Box?, window: com.eggman.backstage.exec.LaunchWindow, position: com.eggman.backstage.exec.PopupPosition) =
        act(("launch $packageName ${slot ?: ""}${bounds ?: ""}".trim() + if (window != com.eggman.backstage.exec.LaunchWindow.DEFAULT) " window=${window.name.lowercase()}" else ""), msg = "Launched $packageName")
    override suspend fun openUri(uri: String) = act("open $uri")
    override suspend fun awaitSettle(maxMs: Long) = Unit
    override suspend fun awaitSettleOutcome(maxMs: Long, expectChangeMs: Long): com.eggman.backstage.exec.SettleOutcome {
        val before = lastScreen
        val changed = before == null || serializer.serialize(capture, kind.label).fingerprint != before.fingerprint
        return com.eggman.backstage.exec.SettleOutcome(changed, true, 0)
    }
    /** Accessibility events since the last screen (null = no event stream, as before ). */
    var uiEvents: Long? = null
    override fun uiEventsSinceScreen(): Long? = uiEvents
    override suspend fun rightClick(x: Float, y: Float) = act("right ${x.toInt()},${y.toInt()}")
    override suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float) = act("wheel $vertical")
}

class FakeCalendar(var granted: Boolean = true) : CalendarService {
    val inserted = mutableListOf<CalendarEvent>()
    var events = mutableListOf<CalendarEvent>()
    val reminderMap = mutableMapOf<Long, List<Int>>()
    override fun event(id: Long) = (events + inserted).firstOrNull { it.id == id }
    override fun update(id: Long, title: String?, startMs: Long?, endMs: Long?, location: String?, description: String?): Boolean {
        val e = event(id) ?: return false
        val n = e.copy(title = title ?: e.title, startMs = startMs ?: e.startMs, endMs = endMs ?: e.endMs,
            location = location ?: e.location, description = description ?: e.description)
        events.removeAll { it.id == id }; inserted.removeAll { it.id == id }; events += n
        return true
    }
    override fun delete(id: Long): Boolean = events.removeAll { it.id == id } or inserted.removeAll { it.id == id }
    override fun reminders(eventId: Long) = reminderMap[eventId].orEmpty()
    override fun setReminders(eventId: Long, minutes: List<Int>) { reminderMap[eventId] = minutes }
    override fun hasPermission(write: Boolean) = granted
    override fun calendars() = listOf(CalendarInfo(1, "Personal", "me@example.com", writable = true, primary = true), CalendarInfo(2, "Holidays", "x", false, false))
    override fun events(fromMs: Long, toMs: Long, query: String?) =
        events.filter { it.startMs < toMs && it.endMs > fromMs && (query == null || it.title.contains(query, true)) }
    override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, location: String?, description: String?, timeZone: String): Long {
        val e = CalendarEvent(100L + inserted.size, calendarId, title, startMs, endMs, allDay, location.orEmpty(), description.orEmpty())
        inserted += e
        return e.id
    }
}

class FakeAlarms : com.eggman.backstage.tools.AlarmService {
    val calls = mutableListOf<String>()
    var next: Long? = null
    override fun setAlarm(hour: Int, minute: Int, message: String?, vibrate: Boolean, skipUi: Boolean) = true.also { calls += "alarm $hour:$minute $message $vibrate" }
    override fun setTimer(seconds: Int, message: String?, skipUi: Boolean) = true.also { calls += "timer $seconds" }
    override fun nextAlarmMs() = next
    override fun showAlarms() = true.also { calls += "show" }
    override fun dismissAlarm() = true.also { calls += "dismiss" }
    override fun snoozeAlarm() = true.also { calls += "snooze" }
}

open class FakeServices(
    var apps: List<AppEntry> = listOf(AppEntry("Gmail", "com.google.android.gm"), AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("WhatsApp", "com.whatsapp"), AppEntry("Settings", "com.android.settings")),
    override val desktop: DesktopHost? = null,
    override val calendar: CalendarService? = FakeCalendar(),
) : DeviceServices {
    val http = mutableMapOf<String, HttpResult>()
    val requested = mutableListOf<String>()
    val posted = mutableListOf<Triple<String, Map<String, String>, String>>()
    var config = com.eggman.backstage.tools.ModuleConfig()
    override val alarms = FakeAlarms()
    override val memory = com.eggman.backstage.data.InMemoryMemory { now }
    var schedulerImpl: com.eggman.backstage.tools.ScheduledTaskApi? = null
    override val scheduler get() = schedulerImpl
    var skillStore: com.eggman.backstage.skills.SkillStore? = null
    override val skills get() = skillStore
    override fun modules() = config
    override suspend fun httpPost(url: String, headers: Map<String, String>, jsonBody: String): HttpResult {
        posted += Triple(url, headers, jsonBody)
        return http.entries.firstOrNull { url.startsWith(it.key) }?.value ?: HttpResult(404, "")
    }
    var clipboard: String? = null
    var notifications = listOf<NotificationEntry>()
    var engine = "duckduckgo"
    var now = 1_767_000_000_000L // 2025-12-29

    override fun installedApps() = apps
    var defaults: List<com.eggman.backstage.exec.DefaultApp> = emptyList()
    override fun defaultApps() = defaults
    override suspend fun httpGet(url: String, headers: Map<String, String>): HttpResult {
        requested += url
        return http.entries.firstOrNull { url.startsWith(it.key) }?.value ?: HttpResult(404, "")
    }
    override fun clipboardGet() = clipboard
    override fun clipboardSet(text: String): Boolean { clipboard = text; return true }
    override fun recentNotifications() = notifications
    override fun nowMs() = now
    override fun timeZoneId() = "Europe/Berlin"
    override fun searchEngine() = engine
}

/** LLM that replays scripted replies and records requests. */
class ScriptedLlm(private val script: MutableList<(LlmRequest) -> LlmReply>) : LlmClient {
    override val provider: ProviderId = ProviderId.ANTHROPIC
    override val model: String = "test-model"
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmReply {
        requests += request
        if (script.isEmpty()) return text("(script exhausted)")
        return script.removeAt(0)(request)
    }

    companion object {
        private var n = 0
        fun call(name: String, args: String = "{}", text: String = ""): LlmReply = calls(listOf(name to args), text)
        fun calls(calls: List<Pair<String, String>>, text: String = ""): LlmReply = LlmReply(
            Message(Role.ASSISTANT, (if (text.isNotEmpty()) listOf(Part.Text(text)) else emptyList<Part>()) +
                calls.map { (name, args) -> Part.ToolCall("c${n++}", name, JSONObject(args)) }),
            StopReason.TOOL_USE, Usage(100, 10),
        )
        fun text(t: String, stop: StopReason = StopReason.END_TURN) = LlmReply(Message.assistant(t), stop, Usage(50, 5))
        fun error(e: LlmException): (LlmRequest) -> LlmReply = { throw e }
    }
}

fun script(vararg replies: LlmReply): MutableList<(LlmRequest) -> LlmReply> = replies.map { r -> { _: LlmRequest -> r } }.toMutableList()

/** Minimal DesktopHost for desktop tool tests. */
class FakeDesktopHost(var active: Boolean = true, var privileged: Boolean = true) : DesktopHost {
    var presentation = DesktopHost.Presentation.MINI
    val calls = mutableListOf<String>()
    var direct = true
    override fun privilegedAvailable() = privileged
    override suspend fun shell(vararg argv: String) = DesktopHost.ShellResult(0, "", "").also { calls += "shell ${argv.joinToString(" ")}" }
    override fun systemService(name: String, aidlInterface: String): Any? = null
    override fun sessionActive() = active
    override fun geometry() = if (active) DesktopHost.Geometry(7, 1920, 1080, 160) else null
    override fun directDisplay() = direct
    override suspend fun startDesktop(presentation: DesktopHost.Presentation, timeoutMs: Long): DesktopHost.Geometry? {
        calls += "start $presentation"; active = true; this.presentation = presentation; return geometry()
    }
    override fun stopDesktop() { calls += "stop"; active = false }
    override fun presentation() = if (active) presentation else DesktopHost.Presentation.NONE
    override fun setPresentation(presentation: DesktopHost.Presentation): Boolean { calls += "present $presentation"; this.presentation = presentation; return true }
    override fun addListener(listener: DesktopHost.Listener) = Unit
    override fun removeListener(listener: DesktopHost.Listener) = Unit
    override suspend fun captureFrame(): Bitmap? = null
    override suspend fun tap(x: Float, y: Float) = true.also { calls += "tap $x,$y" }
    override suspend fun longPress(x: Float, y: Float, durationMs: Long) = true
    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) = true
    override suspend fun key(keyCode: Int, metaState: Int) = true.also { calls += "key $keyCode $metaState" }
    override suspend fun typeText(text: String) = true
    override suspend fun rightClick(x: Float, y: Float) = true
    override suspend fun scrollWheel(x: Float, y: Float, vertical: Float, horizontal: Float) = true
    override fun launchPackage(packageName: String, bounds: Rect?) = true.also { calls += "launch $packageName" }
    override fun launchIntent(intent: Intent, bounds: Rect?) = true
    override fun launchPackageAt(packageName: String, slot: String) = true.also { calls += "launchAt $packageName $slot" }
    override fun globalAction(action: String) = true.also { calls += "global $action" }
}
