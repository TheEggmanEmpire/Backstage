package com.eggman.backstage.tools

import com.eggman.backstage.agent.api.PlanStep
import com.eggman.backstage.exec.Executor
import com.eggman.backstage.exec.TargetKind
import com.eggman.backstage.testutil.FakeCalendar
import com.eggman.backstage.testutil.FakeDesktopHost
import com.eggman.backstage.testutil.FakeExecutor
import com.eggman.backstage.testutil.FakeServices
import com.eggman.backstage.testutil.Screens
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.eggman.backstage.tools.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TestToolContext(
    val phone: FakeExecutor = FakeExecutor(TargetKind.PHONE),
    val desktopExec: FakeExecutor = FakeExecutor(TargetKind.DESKTOP, Screens.buttons("Desk"), 1920, 1080),
    override val services: FakeServices = FakeServices(),
    override val hooks: AgentHooks? = null,
    override var visionEnabled: Boolean = true,
    override val autoScreenshot: Boolean = true,
    override val stabilityMs: Long = 0,
) : ToolContext {
    override var currentTarget: TargetKind? = TargetKind.PHONE
    override suspend fun executor(kind: TargetKind?): Executor = when (kind ?: currentTarget) {
        TargetKind.DESKTOP -> desktopExec
        else -> phone
    }
    override fun switchTarget(kind: TargetKind) { currentTarget = kind }
}

class RecordingHooks(var answer: String? = "blue", var approve: Boolean = true) : AgentHooks {
    var plan: List<PlanStep> = emptyList()
    var finished: Pair<String, Boolean>? = null
    val asked = mutableListOf<String>()
    override fun onPlan(steps: List<PlanStep>) { plan = steps }
    override suspend fun ask(question: String, choices: List<String>): String? { asked += question; return answer }
    override suspend fun confirm(action: String): Boolean { asked += "confirm:$action"; return approve }
    override suspend fun confirmIrreversible(action: String): Boolean { asked += "irreversible:$action"; return approve }
    override fun onFinish(summary: String, success: Boolean) { finished = summary to success }
}

class ToolsTest {
    private val reg = ToolRegistry.default()
    private fun run(ctx: ToolContext, name: String, args: String = "{}") = runBlocking { reg.execute(ctx, name, JSONObject(args)) }

    @Test fun getScreenListsElements() {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.buttons("Send", "Cancel")
        val r = run(ctx, "get_screen")
        assertTrue(r.text.contains("[0] Button \"Send\""))
        assertNull(r.image)
    }

    @Test fun sparseScreenGetsAutomaticScreenshot() {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.app()
        assertNotNull(run(ctx, "get_screen").image)
        ctx.visionEnabled = false
        assertNull(run(ctx, "get_screen").image)
        assertTrue(run(ctx, "screenshot").isError)
    }

    @Test fun tapByIndexClicksElementAndReturnsNewScreen() {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.buttons("Next")
        run(ctx, "get_screen")
        ctx.phone.onAction = { ctx.phone.capture = Screens.buttons("Done") }
        val r = run(ctx, "tap", """{"index":0}""")
        assertFalse(r.isError)
        assertEquals("click [0] Next", ctx.phone.actions.single())
        assertTrue(r.text.contains("\"Done\""))
    }

    @Test fun tapByCoordinatesAndImageSpace() {
        val ctx = TestToolContext()
        run(ctx, "tap", """{"x":100,"y":200}""")
        assertEquals("tap 100,200", ctx.phone.actions.last())
        run(ctx, "screenshot")
        run(ctx, "tap", """{"x":100,"y":200,"space":"image"}""")
        assertEquals("tap 200,400", ctx.phone.actions.last())
    }

    @Test fun argumentErrorsAreReportedNotThrown() {
        val ctx = TestToolContext()
        assertTrue(run(ctx, "tap").text.contains("Invalid arguments"))
        assertTrue(run(ctx, "tap", """{"index":42}""").text.contains("No element [42]"))
        assertTrue(run(ctx, "tap", """{"x":5000,"y":1}""").isError)
        assertTrue(run(ctx, "nope").text.contains("Unknown tool"))
        assertTrue(runBlocking { reg.execute(ctx, "tap", JSONObject().put("_raw", "{bad")) }.text.contains("not valid JSON"))
        assertTrue(run(ctx, "tap", """{"index":"abc"}""").isError)
        // string numbers are accepted
        run(ctx, "get_screen")
        assertFalse(run(ctx, "tap", """{"index":"0"}""").isError)
    }

    @Test fun failedActionIsAnErrorWithScreen() {
        val ctx = TestToolContext()
        ctx.phone.failTaps = true
        val r = run(ctx, "tap", """{"x":10,"y":10}""")
        assertTrue(r.isError)
        assertTrue(r.text.contains("Screen phone"))
    }

    @Test fun scrollReportsUnchangedScreen() {
        val ctx = TestToolContext()
        run(ctx, "get_screen")
        val r = run(ctx, "scroll", """{"direction":"down"}""")
        assertTrue(r.text.contains("Nothing moved"))
        ctx.phone.onAction = { ctx.phone.capture = Screens.buttons("More") }
        assertFalse(run(ctx, "scroll", """{"direction":"down"}""").text.contains("Nothing moved"))
    }

    @Test fun typeTextWithSubmit() {
        val ctx = TestToolContext()
        ctx.phone.capture = Screens.app(Screens.node(cls = "android.widget.EditText", b = com.eggman.backstage.exec.Box(0, 0, 1080, 100), editable = true))
        run(ctx, "get_screen")
        run(ctx, "type_text", """{"text":"pizza near me","index":0,"submit":true}""")
        assertEquals(listOf("text 0 pizza near me", "key enter"), ctx.phone.actions)
    }

    @Test fun pressKeyValidation() {
        val ctx = TestToolContext()
        assertTrue(run(ctx, "press_key", """{"key":"warp"}""").isError)
        assertFalse(run(ctx, "press_key", """{"key":"back"}""").isError)
        assertFalse(run(ctx, "press_key", """{"key":"notifications"}""").isError)
    }

    @Test fun launchAppResolvesNamesAndSlots() {
        val ctx = TestToolContext()
        val r = run(ctx, "launch_app", """{"app":"maps"}""")
        assertTrue(r.text, r.text.contains("Google Maps"))
        assertEquals("launch com.google.android.apps.maps", ctx.phone.actions.last())
        assertTrue(run(ctx, "launch_app", """{"app":"telegram"}""").text.contains("No installed app"))
        assertTrue(run(ctx, "launch_app", """{"app":"gmail","slot":"left"}""").text.contains("only apply on the desktop"))
        run(ctx, "desktop_launch_app", """{"app":"gmail","slot":"left"}""")
        assertEquals("launch com.google.android.gm left", ctx.desktopExec.actions.last())
        assertTrue(run(ctx, "launch_app", """{"app":"gmail","slot":"diagonal","target":"desktop"}""").isError)
    }

    @Test fun openUrlRejectsDangerousSchemes() {
        val ctx = TestToolContext()
        assertTrue(run(ctx, "open_url", """{"url":"file:///sdcard/x"}""").isError)
        assertTrue(run(ctx, "open_url", """{"url":"not a url"}""").isError)
        assertFalse(run(ctx, "open_url", """{"url":"geo:0,0?q=coffee"}""").isError)
        assertEquals("open geo:0,0?q=coffee", ctx.phone.actions.last())
    }

    @Test fun explicitTargetArgumentSelectsExecutor() {
        val ctx = TestToolContext()
        run(ctx, "get_screen", """{"target":"desktop"}""")
        assertNotNull(ctx.desktopExec.lastScreen)
        assertNull(ctx.phone.lastScreen)
    }

    @Test fun webSearchDuckDuckGoAndSearxng() {
        val ctx = TestToolContext()
        ctx.services.config = ModuleConfig(searchProvider = "duckduckgo")
        ctx.services.http["https://html.duckduckgo.com/html/"] = HttpResult(200, DDG_HTML)
        val r = run(ctx, "web_search", """{"query":"pizza berlin"}""")
        assertTrue(r.text, r.text.contains("1. Best Pizza &amp; Pasta".replace("&amp;", "&")))
        assertTrue(r.text.contains("https://example.com/pizza"))
        assertFalse(r.text.contains("y.js")) // ads skipped
        assertTrue(ctx.services.requested.last().contains("q=pizza+berlin"))

        ctx.services.config = ModuleConfig(searchProvider = "searxng", searxngUrl = "https://searx.local")
        ctx.services.http["https://searx.local/search"] = HttpResult(200, """{"results":[{"title":"T","url":"https://a.b","content":"snip"}]}""")
        assertTrue(run(ctx, "web_search", """{"query":"x"}""").text.contains("https://a.b"))
    }

    @Test fun fetchUrlReadableText() {
        val ctx = TestToolContext()
        ctx.services.http["https://news.example/a"] = HttpResult(200, """<html><head><title>Hello &amp; welcome</title><script>evil()</script></head>
            <body><nav>menu menu</nav><p>First para.</p><ul><li>One</li><li>Two</li></ul><footer>foot</footer></body></html>""", "text/html")
        // This test isolates readable-text parsing; secure destination/redirect handling has its own tests.
        val fetchRegistry = ToolRegistry(listOf(FetchUrlTool { url, _ -> ctx.services.httpGet(url) }))
        fun fetch(url: String) = runBlocking { fetchRegistry.execute(ctx, "fetch_url", JSONObject().put("url", url)) }
        val r = fetch("https://news.example/a")
        assertFalse(r.text, r.isError)
        assertTrue(r.text.contains("# Hello & welcome"))
        assertTrue(r.text.contains("First para."))
        assertTrue(r.text.contains("• One"))
        assertFalse(r.text.contains("evil"))
        assertFalse(r.text.contains("menu menu"))
        assertTrue(fetch("ftp://x").isError)
        assertEquals(listOf("https://news.example/a"), ctx.services.requested)
        assertTrue(fetch("https://missing.example").isError)
    }

    @Test fun calendarCreateListModifyDelete() {
        val ctx = TestToolContext()
        val cal = ctx.services.calendar as FakeCalendar
        val berlin = ZoneId.of("Europe/Berlin")
        // ISO form
        val r = run(ctx, "create_calendar_event", """{"title":"Dentist","start":"2026-03-12T15:00","location":"Main St"}""")
        assertFalse(r.text, r.isError)
        assertTrue(JSONObject(r.text).getBoolean("success"))
        val e = cal.inserted.single()
        assertEquals(java.time.LocalDateTime.of(2026, 3, 12, 15, 0).atZone(berlin).toInstant().toEpochMilli(), e.startMs)
        assertEquals(3_600_000L, e.endMs - e.startMs)
        assertEquals(1L, e.calendarId)
        // Calendar form with duration and reminder; all-day form
        run(ctx, "create_calendar_event", """{"title":"Gym","start_date":"2026-03-13","start_time":"7:30","duration_minutes":45,"reminder_minutes":10}""")
        val gym = cal.inserted.last()
        assertEquals(45 * 60_000L, gym.endMs - gym.startMs)
        assertEquals(listOf(10), cal.reminders(gym.id))
        run(ctx, "create_calendar_event", """{"title":"Holiday","start_date":"2026-03-14"}""")
        val allDay = cal.inserted.last()
        assertTrue(allDay.allDay)
        assertEquals(java.time.LocalDate.of(2026, 3, 14).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), allDay.startMs)
        assertTrue(run(ctx, "create_calendar_event", """{"title":"x","start_date":"tomorrow"}""").text.contains("YYYY-MM-DD"))
        assertTrue(run(ctx, "create_calendar_event", """{"title":"x","start":"tomorrow 3pm"}""").text.contains("Cannot read date"))

        cal.events += e
        ctx.services.now = java.time.LocalDateTime.of(2026, 3, 12, 8, 0).atZone(berlin).toInstant().toEpochMilli()
        val list = JSONObject(run(ctx, "get_calendar_events", """{"days":2}""").text)
        assertTrue(list.getInt("count") >= 1)
        assertEquals("Dentist", list.getJSONArray("events").getJSONObject(0).getString("title"))
        assertTrue(list.getJSONArray("events").getJSONObject(0).getString("start").startsWith("2026-03-12 15:00"))
        val today = JSONObject(run(ctx, "get_calendar_events", """{"today_only":true}""").text)
        assertEquals(1, today.getInt("count"))
        assertEquals(1, JSONObject(run(ctx, "get_calendars").text).getJSONArray("calendars").let { a -> (0 until a.length()).count { a.getJSONObject(it).getBoolean("writable") } })

        // modify: move to 16:30 keeping the duration; then remove reminders
        assertFalse(run(ctx, "modify_calendar_event", """{"event_id":${e.id},"start_time":"16:30","reminder_minutes":-1}""").isError)
        val moved = cal.event(e.id)!!
        assertEquals(java.time.LocalDateTime.of(2026, 3, 12, 16, 30).atZone(berlin).toInstant().toEpochMilli(), moved.startMs)
        assertEquals(3_600_000L, moved.endMs - moved.startMs)
        assertTrue(run(ctx, "modify_calendar_event", """{"event_id":${e.id}}""").text.contains("Nothing to change"))
        assertTrue(run(ctx, "modify_calendar_event", """{"event_id":999,"title":"x"}""").text.contains("No event"))
        // Deleting is irreversible: without anyone to approve (a direct MCP call) it is refused and nothing is removed.
        assertTrue(run(ctx, "delete_calendar_event", """{"event_id":${e.id}}""").isError)
        assertNotNull(cal.event(e.id))
        val hooks = RecordingHooks(approve = false)
        val withUser = TestToolContext(services = ctx.services, hooks = hooks)
        val refused = run(withUser, "delete_calendar_event", """{"event_id":${e.id}}""")
        assertTrue(refused.isError)
        assertNotNull(cal.event(e.id))
        // The question names the exact event, worded by the tool (not the model).
        assertEquals(listOf("irreversible:Delete the calendar event “Dentist” (Thu 12 Mar 2026, 16:30)"), hooks.asked)
        hooks.approve = true
        assertFalse(run(withUser, "delete_calendar_event", """{"event_id":${e.id}}""").isError)
        assertEquals(null, cal.event(e.id))
        // An unknown event is reported without asking anyone.
        hooks.asked.clear()
        assertTrue(run(withUser, "delete_calendar_event", """{"event_id":${e.id}}""").text.contains("No event"))
        assertTrue(hooks.asked.isEmpty())
        cal.granted = false
        assertTrue(run(ctx, "get_calendar_events").text.contains("permission"))
    }

    @Test fun alarmTools() {
        val ctx = TestToolContext()
        assertFalse(run(ctx, "set_alarm", """{"hour":7,"minute":5,"message":"Gym"}""").isError)
        assertEquals("alarm 7:5 Gym true", ctx.services.alarms.calls.last())
        assertTrue(run(ctx, "set_alarm", """{"hour":25,"minute":0}""").isError)
        assertEquals("No upcoming alarms.", run(ctx, "get_alarms").text)
        ctx.services.alarms.next = java.time.LocalDateTime.of(2026, 3, 13, 6, 45).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        assertEquals("Next alarm: Fri 13 Mar, 06:45.", run(ctx, "get_alarms", """{"action":"next"}""").text)
        run(ctx, "get_alarms", """{"action":"snooze"}""")
        run(ctx, "cancel_alarm")
        run(ctx, "set_timer", """{"seconds":300}""")
        assertEquals(listOf("snooze", "show", "timer 300"), ctx.services.alarms.calls.drop(1))
    }

    @Test fun weatherTools() {
        val ctx = TestToolContext()
        assertTrue(run(ctx, "get_weather", """{"location":"Berlin"}""").text.contains("not configured"))
        ctx.services.config = ModuleConfig(weatherApiKey = "owm", weatherDefaultLocation = "Berlin")
        ctx.services.http["https://api.openweathermap.org/data/2.5/weather"] = HttpResult(200, """{"name":"Berlin","sys":{"country":"DE"},
            "main":{"temp":21.4,"humidity":40},"weather":[{"main":"Clouds","description":"broken clouds"}],"wind":{"speed":3.2}}""")
        val w = JSONObject(run(ctx, "get_weather").text)
        assertEquals("Berlin, DE", w.getString("location"))
        assertEquals("21°C", w.getString("temperature"))
        assertEquals("40%", w.getString("humidity"))
        assertEquals("3 m/s", w.getString("wind"))
        assertTrue(ctx.services.requested.last().contains("q=Berlin&appid=owm&units=metric"))
        val base = 1_767_000_000L // seconds
        ctx.services.http["https://api.openweathermap.org/data/2.5/forecast"] = HttpResult(200, """{"city":{"name":"Berlin"},"list":[
            {"dt":$base,"main":{"temp":10,"temp_min":8,"temp_max":12},"weather":[{"main":"Rain"}]},
            {"dt":${base + 10800},"main":{"temp":14,"temp_min":13,"temp_max":15},"weather":[{"main":"Rain"}]},
            {"dt":${base + 86400},"main":{"temp":20,"temp_min":18,"temp_max":22},"weather":[{"main":"Clear"}]}]}""")
        ctx.services.now = base * 1000
        val f = JSONObject(run(ctx, "get_weather_forecast", """{"days":2}""").text)
        val days = f.getJSONArray("forecast")
        assertEquals(2, days.length())
        assertEquals("Today", days.getJSONObject(0).getString("date"))
        assertEquals("15°C", days.getJSONObject(0).getString("high"))
        assertEquals("8°C", days.getJSONObject(0).getString("low"))
        assertEquals("Clear", days.getJSONObject(1).getString("condition"))
    }

    @Test fun searchProviders() {
        val ctx = TestToolContext()
        ctx.services.config = ModuleConfig(searchProvider = "brave", searchApiKey = "bk")
        ctx.services.http["https://api.search.brave.com/"] = HttpResult(200, """{"web":{"results":[{"title":"<b>B</b>","url":"https://b.example","description":"bd"}]}}""")
        val b = run(ctx, "web_search", """{"query":"q","num_results":3}""")
        assertTrue(b.text.contains("https://b.example"))
        assertTrue(ctx.services.requested.last().contains("count=3"))
        assertEquals("https://b.example", b.data!!.getJSONArray("results").getJSONObject(0).getString("link"))

        ctx.services.config = ModuleConfig(searchProvider = "serper", searchApiKey = "sk")
        ctx.services.http["https://google.serper.dev/search"] = HttpResult(200, """{"organic":[{"title":"S","link":"https://s.example","snippet":"ss"}]}""")
        assertTrue(run(ctx, "web_search", """{"query":"pizza"}""").text.contains("https://s.example"))
        assertEquals("sk", ctx.services.posted.last().second["X-API-KEY"])
        assertEquals("pizza", JSONObject(ctx.services.posted.last().third).getString("q"))

        ctx.services.config = ModuleConfig(searchProvider = "tavily", searchApiKey = "tk")
        ctx.services.http["https://api.tavily.com/search"] = HttpResult(200, """{"results":[{"title":"T","url":"https://t.example","content":"tc"}]}""")
        assertTrue(run(ctx, "web_search", """{"query":"x"}""").text.contains("https://t.example"))
        assertEquals("Bearer tk", ctx.services.posted.last().second["Authorization"])

        // a keyed provider without a key falls back to the keyless engine
        ctx.services.config = ModuleConfig(searchProvider = "brave", searchApiKey = "")
        ctx.services.http["https://html.duckduckgo.com/html/"] = HttpResult(200, DDG_HTML)
        assertTrue(run(ctx, "web_search", """{"query":"x"}""").text.contains("example.com/pizza"))
    }

    @Test fun memoryTools() {
        val ctx = TestToolContext()
        run(ctx, "memory_save", """{"content":"The user's wife is Anna; she likes tulips","reason":"family"}""")
        run(ctx, "memory_save", """{"content":"Gym membership at FitX, Tuesdays"}""")
        val r = run(ctx, "memory_search", """{"query":"what flowers does Anna like"}""")
        assertTrue(r.text, r.text.contains("tulips"))
        assertFalse(r.text.contains("FitX"))
        assertEquals("No memories found.", run(ctx, "memory_search", """{"query":"cars"}""").text)
        assertEquals(2, ctx.services.memory.all().size)
        run(ctx, "memory_save", """{"content":"Gym membership at FitX, Tuesdays"}""") // duplicate ignored
        assertEquals(2, ctx.services.memory.all().size)
    }

    @Test fun scheduledTaskToolsNeedAnImplementation() {
        val hooks = RecordingHooks()
        val ctx = TestToolContext(hooks = hooks)
        assertFalse(reg.forAgent(ToolRegistry.Availability.of(ctx.services, false, true, false)).any { it.name == "create_scheduled_task" })
        val store = mutableMapOf<String, ScheduledTaskInfo>()
        ctx.services.schedulerImpl = object : ScheduledTaskApi {
            override fun list() = store.values.toList()
            override fun create(description: String, cron: String, source: String): ScheduledTaskInfo {
                require(cron.split(' ').size == 5) { "Invalid cron expression '$cron'" }
                return ScheduledTaskInfo("t${store.size + 1}", description, cron, true, null, "Daily at 08:00").also { store[it.id] = it }
            }
            override fun update(id: String, description: String?, cron: String?, enabled: Boolean?): ScheduledTaskInfo {
                val t = store[id] ?: throw IllegalArgumentException("No task $id")
                return t.copy(description = description ?: t.description, enabled = enabled ?: t.enabled).also { store[id] = it }
            }
            override fun delete(id: String) = store.remove(id) != null
        }
        assertTrue(reg.forAgent(ToolRegistry.Availability.of(ctx.services, false, true, false)).any { it.name == "create_scheduled_task" })
        assertTrue(run(ctx, "create_scheduled_task", """{"description":"Summarize my emails","cron_expression":"0 8 * * *"}""").text.startsWith("Scheduled: [t1]"))
        assertTrue(run(ctx, "create_scheduled_task", """{"description":"x","cron_expression":"every day"}""").text.contains("Invalid cron"))
        assertTrue(run(ctx, "list_scheduled_tasks").text.contains("Summarize my emails"))
        assertTrue(run(ctx, "update_scheduled_task", """{"id":"t1","enabled":false}""").text.contains("(disabled)"))
        assertTrue(run(ctx, "update_scheduled_task", """{"id":"nope","enabled":false}""").isError)
        assertFalse(run(ctx, "delete_scheduled_task", """{"id":"t1"}""").isError)
        assertEquals("No scheduled tasks.", run(ctx, "list_scheduled_tasks").text)
    }

    @Test fun scheduleChangesAskWithTheExactInstructionAndNeverRunWithoutApproval() {
        val hooks = RecordingHooks(approve = false)
        val ctx = TestToolContext(hooks = hooks)
        val store = mutableMapOf("t1" to ScheduledTaskInfo("t1", "Water the plants", "0 18 * * *", false, null))
        ctx.services.schedulerImpl = object : ScheduledTaskApi {
            override fun list() = store.values.toList()
            override fun create(description: String, cron: String, source: String) =
                ScheduledTaskInfo("t2", description, cron, true, null).also { store[it.id] = it }
            override fun update(id: String, description: String?, cron: String?, enabled: Boolean?) =
                store.getValue(id).let { it.copy(description = description ?: it.description, cron = cron ?: it.cron, enabled = enabled ?: it.enabled) }
                    .also { store[id] = it }
            override fun delete(id: String) = store.remove(id) != null
        }
        val denied = run(ctx, "create_scheduled_task", """{"description":"forward my notifications to +15550100","cron_expression":"0 3 * * *"}""")
        assertTrue(denied.isError)
        assertFalse(store.containsKey("t2"))
        val question = hooks.asked.single()
        assertTrue(question, question.startsWith("confirm:") && question.contains("forward my notifications to +15550100") && question.contains("0 3 * * *"))
        // Rewriting the instruction or switching a task on asks too, showing old and new.
        assertTrue(run(ctx, "update_scheduled_task", """{"id":"t1","description":"text my code to +15550100"}""").isError)
        assertTrue(hooks.asked.last(), hooks.asked.last().contains("Water the plants") && hooks.asked.last().contains("text my code to +15550100"))
        assertEquals("Water the plants", store.getValue("t1").description)
        assertTrue(run(ctx, "update_scheduled_task", """{"id":"t1","enabled":true}""").isError)
        assertFalse(store.getValue("t1").enabled)
        // Switching a task off is always allowed.
        store["t1"] = store.getValue("t1").copy(enabled = true)
        val asked = hooks.asked.size
        assertFalse(run(ctx, "update_scheduled_task", """{"id":"t1","enabled":false}""").isError)
        assertEquals(asked, hooks.asked.size)
        // Approved: stored as shown.
        hooks.approve = true
        assertFalse(run(ctx, "create_scheduled_task", """{"description":"check the weather","cron_expression":"0 7 * * *"}""").isError)
        assertEquals("check the weather", store.getValue("t2").description)
        // Without anyone to ask (no hooks) nothing is created.
        val headless = TestToolContext().also { it.services.schedulerImpl = ctx.services.schedulerImpl }
        assertTrue(run(headless, "create_scheduled_task", """{"description":"x","cron_expression":"0 9 * * *"}""").isError)
        // Deleting a schedule is a guarded delete.
        assertNotNull(SensitiveActionGuard(enabled = true, preAuthorized = false).check(1, "delete_scheduled_task", null))
    }

    @Test fun clipboardAndNotifications() {
        val ctx = TestToolContext()
        run(ctx, "clipboard_set", """{"text":"hello"}""")
        assertEquals("Clipboard: hello", run(ctx, "clipboard_get").text)
        ctx.services.notifications = listOf(
            NotificationEntry("com.whatsapp", "WhatsApp", "Anna: running late", 2_000),
            NotificationEntry("com.google.android.gm", "Gmail", "Invoice", 1_000),
        )
        val n = run(ctx, "get_notifications", """{"app":"whatsapp"}""")
        assertTrue(n.text.contains("Anna: running late"))
        assertFalse(n.text.contains("Invoice"))
    }

    @Test fun desktopTools() {
        val host = FakeDesktopHost(active = false)
        val ctx = TestToolContext(services = FakeServices(desktop = host))
        val r = run(ctx, "desktop_start", """{"presentation":"hidden"}""")
        assertFalse(r.text, r.isError)
        assertEquals(TargetKind.DESKTOP, ctx.currentTarget)
        assertTrue(host.calls.contains("present HIDDEN"))
        host.active = true
        assertFalse(run(ctx, "desktop_presentation", """{"presentation":"full"}""").isError)
        host.direct = false
        assertTrue(run(ctx, "desktop_presentation", """{"presentation":"mini"}""").isError)
        run(ctx, "desktop_right_click", """{"x":10,"y":20}""")
        assertEquals("right 10,20", ctx.desktopExec.actions.last())
        run(ctx, "desktop_scroll_wheel", """{"amount":-2}""")
        assertEquals("wheel -2.0", ctx.desktopExec.actions.last())
        assertTrue(run(ctx, "desktop_window_list").text.contains("Example"))
        run(ctx, "desktop_stop")
        assertTrue(host.calls.contains("stop"))
    }

    @Test fun controlToolsUseHooks() {
        val hooks = RecordingHooks()
        val ctx = TestToolContext(hooks = hooks)
        val p = run(ctx, "update_plan", """{"steps":[{"title":"Open app","status":"done"},{"title":"Send","status":"in_progress"}]}""")
        assertTrue(p.text.contains("1/2"))
        assertEquals(2, hooks.plan.size)
        assertTrue(run(ctx, "update_plan", """{"steps":[{"title":"x","status":"weird"}]}""").isError)
        assertEquals("User answered: blue", run(ctx, "ask_user", """{"question":"Which color?"}""").text)
        hooks.answer = null
        assertTrue(run(ctx, "ask_user", """{"question":"?"}""").isError)
        assertTrue(run(ctx, "confirm_action", """{"action":"Send"}""").text.startsWith("approved"))
        hooks.approve = false
        assertTrue(run(ctx, "confirm_action", """{"action":"Send"}""").text.startsWith("denied"))
        val f = run(ctx, "finish", """{"summary":"Done it","success":true}""")
        assertTrue(f.terminal)
        assertEquals("Done it" to true, hooks.finished)
        // without hooks (MCP) control tools refuse
        assertTrue(run(TestToolContext(), "finish", """{"summary":"x"}""").isError)
    }

    @Test fun registryFiltering() {
        val all = reg.forAgent(ToolRegistry.Availability(desktop = true, vision = true, planMode = true)).map { it.name }
        assertTrue("desktop_start" in all && "update_plan" in all && "screenshot" in all)
        val phoneOnly = reg.forAgent(ToolRegistry.Availability(desktop = false, vision = false, planMode = false)).map { it.name }
        assertFalse("desktop_start" in phoneOnly)
        assertFalse("screenshot" in phoneOnly)
        assertFalse("update_plan" in phoneOnly)
        assertTrue("web_search" in phoneOnly)
        val mcp = reg.forMcp(ToolRegistry.Availability(desktop = true)).map { it.name }
        assertFalse("finish" in mcp || "ask_user" in mcp || "confirm_action" in mcp)
        assertTrue("tap" in mcp)
        // every schema is a JSON-schema object
        reg.tools.forEach { assertEquals("object", it.schema.getString("type")) }
    }

    @Test fun sensitiveGuard() {
        val g = SensitiveActionGuard(enabled = true, preAuthorized = false)
        assertNotNull(g.check(1, "tap", "Send"))
        assertNotNull(g.check(1, "tap", "Jetzt kaufen"))
        assertNotNull(g.check(1, "tap", "Löschen"))
        assertNotNull(g.check(1, "tap", "Überweisen"))
        assertNull(g.check(1, "tap", "Reply"))
        assertNull(g.check(1, "tap", "Settings"))
        assertNull(g.check(1, "tap", "Anna · Can you send me the photos from the trip yesterday please?"))
        assertNotNull(g.check(1, "type_text", null, submitText = true, fieldHint = "Type a message"))
        assertNull(g.check(1, "type_text", null, submitText = true, fieldHint = "Search"))
        g.noteApproval(2)
        assertNull(g.check(5, "tap", "Send"))
        assertNotNull(g.check(20, "tap", "Send"))
        assertNull(SensitiveActionGuard(enabled = false, preAuthorized = false).check(1, "tap", "Delete"))
        assertNull(SensitiveActionGuard(enabled = true, preAuthorized = true).check(1, "tap", "Delete"))
        assertTrue(SensitiveActionGuard.requestWaivesConfirmation("Send it to Tom without asking"))
        assertFalse(SensitiveActionGuard.requestWaivesConfirmation("Send it to Tom"))
    }

    @Test fun dateParsing() {
        val z = ZoneId.of("Europe/Berlin")
        assertTrue(DateParsing.parse("2026-03-14", z).dateOnly)
        assertFalse(DateParsing.parse("2026-03-14 09:30", z).dateOnly)
        assertEquals(DateParsing.parse("2026-03-14T08:30:00Z", z).epochMs, DateParsing.parse("2026-03-14T09:30", z).epochMs)
        assertEquals(DateParsing.parse("2026-03-14T09:30+01:00", z).epochMs, DateParsing.parse("2026-03-14T09:30", z).epochMs)
    }

    class FakeDeviceControl(val caps: Set<String>) : DeviceControl {
        val calls = mutableListOf<String>()
        override fun has(capability: String) = capability in caps
        override fun volumes() = mapOf("media" to 40, "ring" to 80)
        override fun setVolume(stream: String, percent: Int) = true.also { calls += "vol $stream $percent" }
        override fun setRingerMode(mode: String) = true
        override fun setFlashlight(on: Boolean) = true
        override fun wifiStatus() = JSONObject().put("enabled", true)
        override fun bluetoothStatus() = JSONObject().put("enabled", false)
        override fun brightness() = JSONObject().put("percentage", 50).put("auto", false)
        override suspend fun setBrightness(percent: Int?, auto: Boolean?) = true
        override suspend fun setScreenTimeout(seconds: Int) = true
        override fun autoRotate() = true
        override suspend fun setAutoRotate(on: Boolean) = true
        override fun dnd() = "off"
        override suspend fun setDnd(mode: String) = true.also { calls += "dnd $mode" }
        override fun searchContacts(query: String, max: Int) = listOf(Contact("Anna K", listOf("+49 170 1234567"), emptyList()))
        override fun sendSms(number: String, message: String) = true.also { calls += "sms $number $message" }
        override fun call(number: String) = true
        override suspend fun location() = JSONObject().put("latitude", 52.5).put("longitude", 13.4)
    }

    class DcServices(val dc: FakeDeviceControl) : FakeServices(), DeviceControlProvider {
        override val deviceControl: DeviceControl = dc
    }

    @Test fun deviceControlToolsAreGatedByCapability() {
        val dc = FakeDeviceControl(setOf("volume", "contacts", "sms", "dnd"))
        val ctx = TestToolContext(services = DcServices(dc), hooks = RecordingHooks(approve = true))
        val names = reg.forAgent(ToolRegistry.Availability.of(ctx.services, false, true, false)).map { it.name }
        assertTrue("volume_set" in names && "contacts_search" in names && "sms_send" in names)
        assertFalse("phone_call" in names || "location_get" in names || "flashlight_set" in names)
        assertFalse(reg.forAgent(ToolRegistry.Availability.of(FakeServices(), false, true, false)).any { it.name == "volume_set" })
        assertEquals(80, JSONObject(run(ctx, "volume_get").text).getInt("ring"))
        run(ctx, "volume_set", """{"stream":"media","percentage":130}""")
        assertEquals("vol media 100", dc.calls.last())
        assertTrue(run(ctx, "volume_set", """{"stream":"bass","percentage":1}""").isError)
        assertTrue(run(ctx, "contacts_search", """{"query":"anna"}""").text.contains("+49 170 1234567"))
        assertTrue(run(ctx, "sms_send", """{"phone_number":"not a number!","message":"hi"}""").isError)
        assertEquals("SMS sent to +49 170 1234567.", run(ctx, "sms_send", """{"phone_number":"+49 170 1234567","message":"On my way"}""").text)
        assertEquals("sms +49 170 1234567 On my way", dc.calls.last())
        assertTrue(run(ctx, "location_get").text.contains("Location permission"))
    }

    /** every permission-gated device capability has a way to be granted (none can stay unreachable). */
    @Test fun everyGatedDeviceCapabilityHasARequestPath() {
        val alwaysAvailable = setOf("volume", "ringer", "wifi", "bluetooth", "flashlight") // no permission, or hardware only
        DeviceControlTools.capabilityOf.values.toSet().minus(alwaysAvailable).forEach { cap ->
            val kind = DeviceAccess.kindOf(cap) ?: throw AssertionError("no request path for capability $cap")
            assertTrue(cap, kind.permissions.isNotEmpty() || kind.settingsAction != null)
        }
        // granted mirrors what the registry offers: all capabilities of a kind must be usable.
        assertEquals(setOf(DeviceAccess.Kind.CONTACTS), DeviceAccess.granted(FakeDeviceControl(setOf("contacts", "brightness", "screen_timeout"))))
        assertEquals(setOf(DeviceAccess.Kind.CONTACTS, DeviceAccess.Kind.SYSTEM_SETTINGS),
            DeviceAccess.granted(FakeDeviceControl(setOf("contacts", "brightness", "screen_timeout", "auto_rotate"))))
        assertTrue(DeviceAccess.shellGrant("p", DeviceAccess.Kind.SMS).isEmpty())
        assertTrue(DeviceAccess.shellGrant("p", DeviceAccess.Kind.LOCATION).isEmpty())
    }

    @Test fun smsAndCallsAlwaysAskAndAreRefusedWithoutAnyoneToAsk() {
        val dc = FakeDeviceControl(setOf("sms", "call", "contacts"))
        val sms = """{"phone_number":"+49 170 1234567","message":"On my way"}"""
        // A direct MCP call (no hooks): nobody can approve, so nothing is sent; the tools are not even offered over MCP.
        val direct = TestToolContext(services = DcServices(dc))
        val r = run(direct, "sms_send", sms)
        assertTrue(r.isError)
        assertTrue(r.text, r.text.contains("execute_task"))
        assertTrue(run(direct, "phone_call", """{"phone_number":"+49 170 1234567"}""").isError)
        assertTrue(dc.calls.isEmpty())
        val mcp = reg.forMcp(ToolRegistry.Availability.of(direct.services, false, true, false)).map { it.name }
        assertFalse("sms_send" in mcp || "phone_call" in mcp || "delete_calendar_event" in mcp)
        assertTrue("contacts_search" in mcp)

        // In a task the exact number and text are put to the user; a refusal sends nothing.
        val hooks = RecordingHooks(approve = false)
        val ctx = TestToolContext(services = DcServices(dc), hooks = hooks)
        val denied = run(ctx, "sms_send", sms)
        assertTrue(denied.isError)
        assertTrue(denied.text, denied.text.startsWith("Not done"))
        assertEquals(listOf("irreversible:Send an SMS to +49 170 1234567: “On my way”"), hooks.asked)
        assertTrue(dc.calls.isEmpty())
        hooks.approve = true
        assertFalse(run(ctx, "sms_send", sms).isError)
        assertEquals("sms +49 170 1234567 On my way", dc.calls.last())
        assertFalse(run(ctx, "phone_call", """{"phone_number":"+49 170 1234567"}""").isError)
        assertEquals("irreversible:Call +49 170 1234567", hooks.asked.last())

        // Bad arguments or a missing permission: reported without asking anyone to approve something that cannot work.
        hooks.asked.clear()
        assertTrue(run(ctx, "sms_send", """{"phone_number":"not a number!","message":"hi"}""").isError)
        val noSms = run(TestToolContext(services = DcServices(FakeDeviceControl(setOf("call"))), hooks = hooks), "sms_send", sms)
        assertTrue(noSms.text, noSms.isError && noSms.text.contains("SMS permission"))
        assertTrue(hooks.asked.isEmpty())

        // The tap guard leaves these tools to the registry, so the user is never asked twice.
        assertNull(SensitiveActionGuard(true, false).check(1, "sms_send", null))
        assertNull(SensitiveActionGuard(true, false).check(1, "phone_call", null))
        assertNotNull(SensitiveActionGuard(true, false).check(1, "delete_scheduled_task", null))
        assertTrue(listOf("sms_send", "phone_call", "delete_calendar_event").all { reg[it]!!.irreversible })
    }

    companion object {
        val DDG_HTML = """
            <div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpizza&amp;rut=1">Best Pizza &amp; Pasta</a>
            <a class="result__snippet" href="x">Wood-fired <b>pizza</b> in Berlin.</a></div>
            <div class="result"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a><a class="result__snippet">ad</a></div>
            <div class="result"><a class="result__a" href="https://second.example/">Second</a><a class="result__snippet" href="y">Snippet two</a></div>
        """.trimIndent()
    }
}
