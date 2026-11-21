package com.eggman.backstage.tools

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.app.AlarmManager
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.eggman.backstage.a11y.AgentAccessibility
import com.eggman.backstage.data.SqliteMemory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.eggman.backstage.a11y.NotificationFeed
import com.eggman.backstage.data.Settings
import com.eggman.backstage.exec.AppCatalog
import com.eggman.backstage.exec.AppEntry
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.llm.LlmHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Production [DeviceServices]: PackageManager, OkHttp, CalendarContract, ClipboardManager, notification feed. */
class AndroidDeviceServices(private val context: Context, private val settings: Settings) : DeviceServices, DeviceControlProvider {
    override val deviceControl: DeviceControl = AndroidDeviceControl(context)

    private val web: OkHttpClient = LlmHttp.sharedClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun installedApps(): List<AppEntry> = AppCatalog.installedApps(context)

    override fun defaultApps(): List<com.eggman.backstage.exec.DefaultApp> =
        runCatching { com.eggman.backstage.exec.DefaultAppsCache.current(context) }.getOrDefault(emptyList())

    override suspend fun httpGet(url: String, headers: Map<String, String>): HttpResult {
        val b = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = LlmHttp.execute(web, b.build(), MAX_BODY)
        return HttpResult(r.code, r.body, r.contentType, r.finalUrl ?: url)
    }

    override val calendar: CalendarService = AndroidCalendar(context)

    override suspend fun httpPost(url: String, headers: Map<String, String>, jsonBody: String): HttpResult {
        val b = Request.Builder().url(url).post(jsonBody.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = LlmHttp.execute(web, b.build(), MAX_BODY)
        return HttpResult(r.code, r.body, r.contentType, r.finalUrl ?: url)
    }

    override val alarms: AlarmService = AndroidAlarms(context)

    private val memoryStore by lazy { SqliteMemory(context) }
    override val memory: MemoryService? get() = if (settings.memoryEnabled) memoryStore else null

    override val skills: com.eggman.backstage.skills.SkillStore get() = com.eggman.backstage.skills.SkillStore.shared(context.filesDir)

    override fun modules(): ModuleConfig = settings.moduleConfig()

    override fun clipboardGet(): String? = runCatching {
        context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    override fun clipboardSet(text: String): Boolean = runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Backstage", text))
        true
    }.getOrDefault(false)

    override fun recentNotifications(): List<NotificationEntry> = NotificationFeed.recent()

    override val desktop: DesktopHost? get() = DesktopHosts.current

    override suspend fun restartDesktop(): DesktopHost.Geometry? {
        val host = desktop ?: return null
        val geo = com.eggman.backstage.agent.DesktopRefresh.restart(host, settings.desktopPresentation) ?: return null
        // Dextop may rewrite the accessibility list while starting; make sure the service is back and sees the display.
        if (settings.mayAutoEnableAccessibility) com.eggman.backstage.a11y.AccessibilityAutoEnable.reassertSoon(context, delayMs = 600)
        val until = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < until) {
            if (com.eggman.backstage.a11y.AgentAccessibility.instance?.trackedDisplays()?.contains(geo.displayId) == true) break
            kotlinx.coroutines.delay(200)
        }
        return geo
    }

    override fun searchEngine(): String = settings.searchEngine

    private companion object {
        /** Web tools read at most this many bytes of a response; the rest is never downloaded into memory. */
        const val MAX_BODY = 2_000_000L
    }
}

/** AlarmClock intents (the clock app owns alarms); started from the accessibility context, which may start activities from the background. */
class AndroidAlarms(private val context: Context) : AlarmService {
    private fun start(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (AgentAccessibility.instance?.service ?: context).startActivity(intent)
        true
    }.getOrDefault(false)

    override fun setAlarm(hour: Int, minute: Int, message: String?, vibrate: Boolean, skipUi: Boolean) = start(
        Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_VIBRATE, vibrate).putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            .apply { message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
    )

    override fun setTimer(seconds: Int, message: String?, skipUi: Boolean) = start(
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            .apply { message?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } },
    )

    override fun nextAlarmMs(): Long? = runCatching { context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()
    override fun showAlarms() = start(Intent(AlarmClock.ACTION_SHOW_ALARMS))
    override fun dismissAlarm() = start(Intent(AlarmClock.ACTION_DISMISS_ALARM))
    override fun snoozeAlarm() = start(Intent(AlarmClock.ACTION_SNOOZE_ALARM))
}

/** CalendarContract adapter (needs READ_CALENDAR / WRITE_CALENDAR runtime permissions). */
class AndroidCalendar(private val context: Context) : CalendarService {
    override fun hasPermission(write: Boolean): Boolean {
        val p = if (write) Manifest.permission.WRITE_CALENDAR else Manifest.permission.READ_CALENDAR
        return context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    override fun calendars(): List<CalendarInfo> {
        val out = ArrayList<CalendarInfo>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE,
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1).orEmpty(),
                    account = c.getString(2).orEmpty(),
                    writable = c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    primary = c.getInt(4) == 1,
                )
            }
        }
        return out
    }

    override fun events(fromMs: Long, toMs: Long, query: String?): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION,
        )
        val out = ArrayList<CalendarEvent>()
        context.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val e = CalendarEvent(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getLong(3), c.getLong(4), c.getInt(5) == 1,
                    c.getString(6).orEmpty(), c.getString(7).orEmpty())
                val q = query?.lowercase()
                if (q == null || e.title.lowercase().contains(q) || e.location.lowercase().contains(q) || e.description.lowercase().contains(q)) out += e
            }
        }
        return out
    }

    override fun insert(calendarId: Long, title: String, startMs: Long, endMs: Long, allDay: Boolean, location: String?, description: String?, timeZone: String): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("Calendar provider rejected the event")
        return ContentUris.parseId(uri)
    }

    override fun event(id: Long): CalendarEvent? {
        val proj = arrayOf(
            CalendarContract.Events._ID, CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
        )
        return context.contentResolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else CalendarEvent(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getLong(3),
                if (c.isNull(4)) c.getLong(3) + 3_600_000 else c.getLong(4), c.getInt(5) == 1, c.getString(6).orEmpty(), c.getString(7).orEmpty())
        }
    }

    override fun update(id: Long, title: String?, startMs: Long?, endMs: Long?, location: String?, description: String?): Boolean {
        val values = ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it) }
            startMs?.let { put(CalendarContract.Events.DTSTART, it) }
            endMs?.let { put(CalendarContract.Events.DTEND, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        if (values.size() == 0) return true
        return context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null) > 0
    }

    override fun delete(id: Long): Boolean =
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0

    override fun reminders(eventId: Long): List<Int> =
        context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0)) }
        } ?: emptyList()

    override fun setReminders(eventId: Long, minutes: List<Int>) {
        val cr = context.contentResolver
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        minutes.forEach { m ->
            cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, m)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
    }
}
