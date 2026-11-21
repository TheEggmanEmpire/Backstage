package com.eggman.backstage.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.TaskHandle
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.SurfaceScope
import com.eggman.backstage.notify.Notifications
import com.eggman.backstage.ui.settings.SettingsStores
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal object AutomationRunner {
    data class Outcome(val snapshot: TaskSnapshot?, val timedOut: Boolean)

    /** Waits for the task; on timeout the task is cancelled so automation never leaves it running. */
    suspend fun await(handle: TaskHandle, timeoutMs: Long): Outcome {
        val snap = withTimeoutOrNull(timeoutMs) { handle.await() }
        if (snap == null) AgentAccess.cancel(handle.id)
        return Outcome(snap, snap == null)
    }

    @Volatile
    private var lastAuthNoticeAt = 0L

    /** Tell the user about rejected (unauthenticated) automation calls, at most once a minute. */
    fun noticeRejected(context: Context, source: String, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAuthNoticeAt < 60_000L) return
        lastAuthNoticeAt = now
        Notifications.automationNotice(context, "Blocked $source request", reason)
    }
}

/**
 * Reading extras that any app can send. Unparcelling an unknown Parcelable/Serializable throws
 * (BadParcelableException), and an uncaught throw in onReceive kills the whole process (Dextop's desktop too).
 * So: only the listed keys are read, each one guarded, and only plain values are kept.
 */
internal object SafeExtras {
    @Suppress("DEPRECATION")
    fun read(bundle: Bundle?, keys: Collection<String>): Map<String, Any?> {
        if (bundle == null) return emptyMap()
        val out = HashMap<String, Any?>()
        for (key in keys) {
            val v = try {
                bundle.get(key)
            } catch (_: RuntimeException) {
                null
            }
            when (v) {
                is String, is Int, is Long, is Boolean, is Double, is Float -> out[key] = v
                is CharSequence -> out[key] = v.toString()
                else -> Unit
            }
        }
        return out
    }

    /** The intent's extras bundle, or null when it cannot even be copied. */
    fun of(intent: Intent): Bundle? = try {
        intent.extras
    } catch (_: RuntimeException) {
        null
    }

    fun bundleExtra(intent: Intent, key: String): Bundle? = try {
        intent.getBundleExtra(key)
    } catch (_: RuntimeException) {
        null
    }

    fun stringExtra(intent: Intent, key: String): String? = try {
        intent.getStringExtra(key)
    } catch (_: RuntimeException) {
        null
    }
}

/** Last line of defence for exported receivers: a bug or a hostile extra must never crash the process. */
internal inline fun guardReceive(tag: String, block: () -> Unit) {
    try {
        block()
    } catch (e: RuntimeException) {
        Log.w("Backstage", "$tag: ignored a broadcast that failed: ${e.javaClass.simpleName}")
    }
}

internal fun Map<String, Any?>.toBundle(): Bundle = Bundle().also { b ->
    forEach { (k, v) ->
        when (v) {
            null -> b.putString(k, null)
            is String -> b.putString(k, v)
            is Int -> b.putInt(k, v)
            is Long -> b.putLong(k, v)
            is Boolean -> b.putBoolean(k, v)
            is Array<*> -> b.putStringArray(k, v.map { it.toString() }.toTypedArray())
            else -> b.putString(k, v.toString())
        }
    }
}

/**
 * Explicit broadcast API for MacroDroid / Tasker "Send Intent" / adb / other apps.
 * Every call must carry the access token (constant-time compared).
 */
class BroadcastApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = guardReceive("broadcast api") {
        val app = context.applicationContext
        val settings = SettingsStores.get(app)
        val extras = SafeExtras.read(SafeExtras.of(intent), BroadcastApi.REQUEST_KEYS)
        when (val cmd = BroadcastApi.parse(intent.action, extras, settings.mcpToken)) {
            is BroadcastApi.Command.Rejected -> {
                if (cmd.authFailure) AutomationRunner.noticeRejected(app, "broadcast", "A broadcast without a valid access token was ignored.")
                val requestId = extras[BroadcastApi.EXTRA_REQUEST_ID] as? String
                if (!cmd.authFailure && intent.action == BroadcastApi.ACTION_RUN) {
                    sendResult(app, BroadcastApi.ACTION_RESULT, BroadcastApi.resultExtras(null, requestId, false, cmd.reason),
                        BroadcastApi.replyPackage(extras))
                }
            }
            is BroadcastApi.Command.Stop -> AgentAccess.cancel(cmd.taskId)
            is BroadcastApi.Command.Answer -> {
                // An answer naming a task only reaches that task's question.
                val current = AgentAccess.state().value.current
                val forTask = extras[BroadcastApi.EXTRA_TASK_ID] as? String
                if (current?.status == TaskStatus.WAITING_FOR_USER && current.question?.confirm == false &&
                    (forTask == null || forTask == current.id)) {
                    AgentAccess.answer(cmd.text)
                }
            }
            is BroadcastApi.Command.Run -> {
                // a start while another task runs is refused at once, never queued behind it (the caller's
                // timeout would otherwise run out while it waits, or cancel it shortly after it starts).
                if (AgentAccess.state().value.busy) {
                    sendResult(app, BroadcastApi.ACTION_RESULT, BroadcastApi.busyExtras(cmd.requestId), cmd.replyPackage)
                    return@guardReceive
                }
                val handle = AgentAccess.submit(
                    TaskRequest(
                        prompt = cmd.prompt,
                        source = Source.BROADCAST,
                        target = cmd.target,
                        planMode = cmd.planMode,
                        interactive = false,
                        timeoutMs = cmd.timeoutSec * 1000L,
                        attachments = listOfNotNull(
                            cmd.requestId?.let { "request_id" to it },
                            ("allow_irreversible" to "true").takeIf { cmd.allowIrreversible },
                        ).toMap(),
                    ),
                )
                if (handle == null) {
                    sendResult(app, BroadcastApi.ACTION_RESULT,
                        BroadcastApi.resultExtras(null, cmd.requestId, false, "agent not available"), cmd.replyPackage)
                    return@guardReceive
                }
                sendResult(app, BroadcastApi.ACTION_STARTED,
                    mapOf(BroadcastApi.EXTRA_TASK_ID to handle.id, BroadcastApi.EXTRA_REQUEST_ID to cmd.requestId), cmd.replyPackage)
                val includeText = cmd.replyPackage != null || settings.broadcastResultText
                SurfaceScope.launch {
                    val out = AutomationRunner.await(handle, cmd.timeoutSec * 1000L + 30_000L)
                    sendResult(app, BroadcastApi.ACTION_RESULT,
                        BroadcastApi.resultExtras(out.snapshot, cmd.requestId, includeText, if (out.timedOut) "timeout" else null),
                        cmd.replyPackage)
                }
            }
        }
    }

    private fun sendResult(context: Context, action: String, extras: Map<String, Any?>, replyPackage: String?) {
        val out = Intent(action).putExtras(extras.toBundle())
        if (replyPackage != null) out.setPackage(replyPackage)
        // A result that arrives after the app context went away (process shutting down) is dropped, never a crash.
        runCatching { context.sendBroadcast(out) }.onFailure { android.util.Log.w("BroadcastApi", "result not delivered", it) }
    }
}

/** Locale/Tasker/MacroDroid plugin "fire" entry point. */
class LocaleFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = guardReceive("plugin") {
        if (intent.action != PluginProtocol.ACTION_FIRE_SETTING) return@guardReceive
        val app = context.applicationContext
        val settings = SettingsStores.get(app)
        val values = SafeExtras.read(SafeExtras.bundleExtra(intent, PluginProtocol.EXTRA_BUNDLE), PluginBundleCodec.KEYS)
        val config = when (val d = PluginBundleCodec.decode(values, settings.pluginSigningSecret)) {
            is PluginBundleCodec.Decoded.Invalid -> {
                AutomationRunner.noticeRejected(app, "plugin", "A plugin action was ignored: ${d.reason}.")
                finishSync(PluginProtocol.RESULT_CODE_FAILED, mapOf(
                    PluginProtocol.VAR_SUCCESS to "false", PluginProtocol.VAR_ERROR to d.reason, PluginProtocol.VAR_ERRMSG to d.reason,
                ))
                return@guardReceive
            }
            is PluginBundleCodec.Decoded.Ok -> d.config
        }
        val timeoutMs = config.timeoutMin * 60_000L
        if (AgentAccess.state().value.busy) {
            // refused at once with %error=busy (plugin success, so Tasker hands over the variables).
            finishSync(PluginProtocol.RESULT_CODE_OK, PluginProtocol.busyVariables())
            return@guardReceive
        }
        val handle = AgentAccess.submit(
            TaskRequest(
                prompt = config.prompt,
                source = Source.TASKER,
                target = config.target,
                planMode = config.planMode,
                interactive = false,
                timeoutMs = timeoutMs,
                // The signed template minus its %variables: an SMS or clipboard text in a variable never waives anything.
                waiverText = config.ownText,
            ),
        )
        if (handle == null) {
            finishSync(PluginProtocol.RESULT_CODE_OK, PluginProtocol.resultVariables(null, false))
            return@guardReceive
        }
        if (config.showBubble && com.eggman.backstage.overlay.Overlay.canDraw(app)) {
            // Background FGS starts are allowed with the overlay permission (Android 15+: only while a Backstage
            // overlay window is already visible); otherwise the task still runs and the status notification shows it.
            runCatching { app.startForegroundService(com.eggman.backstage.overlay.BubbleService.showProgressIntent(app, handle.id)) }
        }
        val completion = SafeExtras.stringExtra(intent, PluginProtocol.EXTRA_COMPLETION_INTENT)
        if (!config.waitForResult || completion == null || !isOrderedBroadcast) {
            finishSync(PluginProtocol.RESULT_CODE_OK, mapOf(PluginProtocol.VAR_TASK_ID to handle.id))
            return@guardReceive
        }
        // Tasker synchronous execution: report "pending", deliver variables via the completion intent later.
        resultCode = PluginProtocol.RESULT_CODE_PENDING
        val sender = if (android.os.Build.VERSION.SDK_INT >= 34) runCatching { sentFromPackage }.getOrNull() else null
        val done = sanitizedCompletion(completion, sender, app.packageName)
        if (done == null) {
            Log.w("Backstage", "plugin: completion intent refused (it must go back to the automation app)")
            finishSync(PluginProtocol.RESULT_CODE_OK, mapOf(PluginProtocol.VAR_TASK_ID to handle.id))
            return@guardReceive
        }
        SurfaceScope.launch {
            val out = AutomationRunner.await(handle, timeoutMs)
            done.putExtra(PluginProtocol.EXTRA_RESULT_CODE, PluginProtocol.resultCode(out.snapshot, out.timedOut))
            done.putExtra(PluginProtocol.EXTRA_VARIABLES_BUNDLE,
                PluginProtocol.resultVariables(out.snapshot, out.timedOut, config.timeoutMin).toBundle())
            runCatching { app.sendBroadcast(done) }
        }
    }

    private fun finishSync(code: Int, vars: Map<String, String>) {
        if (!isOrderedBroadcast) return
        resultCode = code
        setResultExtras(Bundle().apply { putBundle(PluginProtocol.EXTRA_VARIABLES_BUNDLE, vars.toBundle()) })
    }

    companion object {
        /**
         * Only plain broadcasts back to the automation app: no selector, no URI grants, and never into this app
         *: the completion string is not covered by the bundle signature and is sent with Backstage's
         * identity, so a redirect to one of its own receivers (notification answers, schedule alarms) would be trusted.
         * Delivery is pinned to the target package ([PluginProtocol.completionPackage]); null = do not send.
       */
        internal fun sanitizedCompletion(uri: String, senderPackage: String?, ownPackage: String): Intent? {
            val i = runCatching {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    selector = null
                    flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
                    clipData = null
                }
            }.getOrNull() ?: return null
            val pkg = PluginProtocol.completionPackage(i.component?.packageName, i.`package`, senderPackage, ownPackage) ?: return null
            return i.setPackage(pkg)
        }
    }
}
