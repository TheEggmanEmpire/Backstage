package com.eggman.backstage.entry

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eggman.backstage.R
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.automation.QuickActionStore
import com.eggman.backstage.overlay.BubbleService
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.ui.AgentScreenActivity
import com.eggman.backstage.ui.settings.SettingsStores
import com.eggman.backstage.ui.theme.BackstageTheme

/** Shared RemoteViews helpers for the three home-screen widgets (no Glance dependency). */
object Widgets {
    private fun views(context: Context, label: String, pi: PendingIntent) =
        RemoteViews(context.packageName, R.layout.bs_widget_button).apply {
            setTextViewText(R.id.bs_widget_label, label)
            setOnClickPendingIntent(R.id.bs_widget_root, pi)
        }

    fun listenViews(context: Context) = views(
        context, "Speak to Backstage",
        PendingIntent.getActivity(context, 10, Intent(context, AssistEntryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    )

    fun bubbleViews(context: Context): RemoteViews {
        val shown = BubbleService.running
        val action = if (shown) BubbleService.ACTION_STOP else BubbleService.ACTION_SHOW
        val pi = if (Overlay.canDraw(context)) {
            PendingIntent.getForegroundService(context, 11, Intent(context, BubbleService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(context, 11, Intent(context, AgentScreenActivity::class.java)
                .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken).putExtra(EntryRouter.EXTRA_SCREEN, EntryRouter.Screen.SETTINGS.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return views(context, if (shown) "Hide bubble" else "Show bubble", pi)
    }

    fun quickViews(context: Context, widgetId: Int): RemoteViews {
        val actionId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w$widgetId", null)
        val action = actionId?.let { QuickActionStore.get(context).get(it) }
        val intent = Intent(context, QuickRunActivity::class.java)
            .setData(android.net.Uri.parse("backstage-widget://quick/$widgetId"))
            .putExtra(QuickRunActivity.EXTRA_ACTION_ID, actionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return views(context, action?.name ?: "Pick a quick action",
            PendingIntent.getActivity(context, 1000 + widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /** Refresh the bubble toggle widgets after the bubble appears/disappears. */
    fun updateBubble(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, BubbleWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, bubbleViews(context))
    }

    fun updateQuick(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        mgr.getAppWidgetIds(ComponentName(context, QuickActionWidget::class.java)).forEach { mgr.updateAppWidget(it, quickViews(context, it)) }
    }

    const val PREFS = "backstage_widgets"
}

/** Tap to speak a command (floating panel when allowed, otherwise the app). */
class ListenWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, Widgets.listenViews(context))
    }
}

/** Shows or hides the floating bubble. */
class BubbleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, Widgets.bubbleViews(context))
    }
}

/** Runs one saved quick action. */
class QuickActionWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, Widgets.quickViews(context, it)) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val e = context.getSharedPreferences(Widgets.PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("w$it") }
        e.apply()
    }
}

/** Widget configuration: choose which quick action the widget runs. */
class QuickActionWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.let { incoming ->
            com.eggman.backstage.automation.SafeExtras.read(com.eggman.backstage.automation.SafeExtras.of(incoming),
                listOf(AppWidgetManager.EXTRA_APPWIDGET_ID))[AppWidgetManager.EXTRA_APPWIDGET_ID] as? Int
        } ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val actions = QuickActionStore.get(this).list()
        setContent {
            BackstageTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose a quick action", style = MaterialTheme.typography.titleLarge)
                        if (actions.isEmpty()) {
                            Text("You have no quick actions yet. Create them in Backstage → Automate.")
                            TextButton(onClick = {
                                startActivity(Intent(this@QuickActionWidgetConfigActivity, AgentScreenActivity::class.java)
                                    .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken).putExtra(EntryRouter.EXTRA_SCREEN, EntryRouter.Screen.SCHEDULES.name))
                                finish()
                            }) { Text("Open Backstage") }
                        }
                        actions.forEach { a ->
                            Card(onClick = {
                                getSharedPreferences(Widgets.PREFS, MODE_PRIVATE).edit().putString("w$widgetId", a.id).apply()
                                AppWidgetManager.getInstance(this@QuickActionWidgetConfigActivity)
                                    .updateAppWidget(widgetId, Widgets.quickViews(this@QuickActionWidgetConfigActivity, widgetId))
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                                    Text(a.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Invisible trampoline that runs a quick action from a widget tap (foreground context). */
class QuickRunActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION_ID)?.let { QuickActionStore.get(this).get(it) }
        when {
            action == null -> startActivity(Intent(this, AgentScreenActivity::class.java)
                .putExtra(EntryRouter.EXTRA_TRUST, EntryRouter.trustToken).putExtra(EntryRouter.EXTRA_SCREEN, EntryRouter.Screen.SCHEDULES.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Overlay.canDraw(this) -> runCatching {
                startForegroundService(BubbleService.startTaskIntent(this, action.prompt, Source.WIDGET, autoHide = !SettingsStores.get(this).bubbleEnabled, target = action.target))
            }.onFailure { submitDirect(action.prompt, action.target) }
            else -> submitDirect(action.prompt, action.target)
        }
        finish()
    }

    private fun submitDirect(prompt: String, target: com.eggman.backstage.agent.api.Target) {
        val h = AgentAccess.submit(TaskRequest(prompt, Source.WIDGET, target = target, waiverText = prompt))
        Toast.makeText(this, if (h == null) "Backstage agent is not ready" else "Backstage: $prompt", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ACTION_ID = "quick_action_id"
    }
}
