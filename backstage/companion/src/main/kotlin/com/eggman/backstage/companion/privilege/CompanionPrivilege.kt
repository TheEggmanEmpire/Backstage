package com.eggman.backstage.companion.privilege

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku
import com.eggman.backstage.privileged.IShellService
import com.eggman.backstage.privileged.ShellUserService
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.eggman.backstage.companion.BuildConfig

/** Companion-local, explicitly approved Shizuku/Stellar shell operations. */
object CompanionPrivilege {
    const val CAP_SHELL = 2
    const val CAP_PACKAGES = 4

    private const val REQUEST_PERMISSION = 4712
    private const val USER_SERVICE_VERSION = 1
    private const val CHANNEL_ID = "privileged_approval"
    private const val NOTIFICATION_ID = 4713
    private const val COMMAND_TIMEOUT_MS = 15_000L
    private const val MAX_COMMAND_CHARS = 1_000
    private const val MAX_ARG_CHARS = 4_096
    private const val MAX_RESULTS = 64
    private const val MAX_RESULT_AGE_MS = 10 * 60_000L

    private enum class State { PENDING, APPROVED, RUNNING, FINISHED, FAILED }
    private data class Result(
        val id: String,
        val owner: Any,
        val operation: String,
        val argument: String,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var state: State = State.PENDING,
        @Volatile var exitCode: Int? = null,
        @Volatile var stdout: String = "",
        @Volatile var stderr: String = "",
        @Volatile var message: String = "",
    )

    private val lock = Any()
    private val shellLock = Any()
    private val requests = LinkedHashMap<String, Result>()
    private val io = Executors.newCachedThreadPool { task -> Thread(task, "Backstage-privileged").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var activityRef: WeakReference<Activity>? = null
    @Volatile private var applicationContextRef: Context? = null
    @Volatile private var reviewButton: WeakReference<Button>? = null
    @Volatile private var statusText: WeakReference<TextView>? = null
    @Volatile private var shell: IShellService? = null
    @Volatile private var binding: ServiceConnection? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_PERMISSION) main.post(::refreshUi)
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { shell = null; refreshUi() }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { shell = null; refreshUi() }
    private val listenersInstalled = AtomicBoolean(false)

    /** Capability is advertised only while Shizuku is granted and an in-app or notification approval route is live. */
    @JvmStatic fun capabilities(context: Context): Int {
        if (!shizukuGranted() || !shellIdentity()) return 0
        val activity = activityRef?.get()
        val foreground = activity != null && !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
        if (!foreground && !notificationsEnabled(context)) return 0
        return CAP_SHELL or CAP_PACKAGES
    }

    /** start queues for explicit approval; poll reads only the authenticated owner's result; cancel drops pending work. */
    @JvmStatic fun request(context: Context, owner: Any, request: Bundle): Bundle {
        val action = request.getString("action").orEmpty()
        val id = request.getString("requestId")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        return when (action) {
            "start" -> start(context.applicationContext, owner, id, request)
            "poll" -> synchronized(lock) { requests[id]?.takeIf { it.owner === owner }?.let(::toBundle) ?: failed(id, "Unknown request") }
            "cancel" -> cancel(id, owner)
            else -> failed(id, "Unsupported action")
        }
    }

    /** Cancel all not-yet-approved requests owned by a disconnected authenticated companion session. */
    @JvmStatic fun cancelOwner(owner: Any) {
        synchronized(lock) {
            // No caller remains to poll these results. Removing all of this session's records also drops its
            // callback/session references; any visible dialog can no longer transition a removed request to run.
            requests.entries.removeAll { it.value.owner === owner }
            trimResults()
        }
        refreshUi()
    }

    /** Add setup and a human-operated review queue to the existing launcher screen. */
    @JvmStatic fun attach(activity: Activity, content: LinearLayout) {
        installListeners()
        ensureNotificationChannel(activity)
        activityRef = WeakReference(activity)
        applicationContextRef = activity.applicationContext
        content.addView(TextView(activity).apply {
            text = "Privileged local administration (Shizuku / Stellar)"
            textSize = 20f
            setPadding(0, 20, 0, 6)
        })
        content.addView(TextView(activity).apply {
            text = "Shell commands and package changes wait for a separate approval here. Each approval runs once with a 15-second deadline and bounded output. This does not enable accessibility or expose screen content."
            setPadding(0, 4, 0, 10)
        })
        statusText = WeakReference(TextView(activity).also { content.addView(it) })
        content.addView(Button(activity).apply {
            text = "Grant Shizuku permission"
            setOnClickListener {
                if (!binderAlive()) setStatus("Start Shizuku or Stellar with Shizuku compatibility enabled, then return here.")
                else if (!shizukuGranted()) runCatching { Shizuku.requestPermission(REQUEST_PERMISSION) }
                else setStatus("Shizuku permission is already granted.")
            }
        })
        content.addView(Button(activity).apply {
            text = "Enable approval notifications"
            setOnClickListener {
                if (notificationsEnabled(activity)) setStatus("Approval notifications are enabled.")
                else activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQUEST_PERMISSION + 1)
            }
        })
        reviewButton = WeakReference(Button(activity).apply {
            text = "Review privileged request"
            setOnClickListener { reviewNext(activity) }
            content.addView(this)
        })
        refreshUi()
    }

    private fun start(context: Context, owner: Any, id: String, request: Bundle): Bundle {
        if (!shizukuGranted()) return failed(id, "Grant Shizuku permission in the companion before requesting privileged operations.")
        val operation = request.getString("operation").orEmpty()
        val rawArgument = request.getString("argument").orEmpty()
        val validation = validateRequest(operation, rawArgument)
        if (validation != null) return failed(id, validation)
        val argument = rawArgument
        if (!shellIdentity()) return failed(id, "Privileged execution requires Shizuku shell identity (uid 2000). Root identity is not allowed.")
        synchronized(lock) {
            trimResults()
            if (requests.containsKey(id)) return failed(id, "Request ID is already in use.")
            val pendingCount = requests.values.count { it.state in setOf(State.PENDING, State.APPROVED, State.RUNNING) }
            if (pendingCount >= 8) return failed(id, "Too many privileged requests are already pending or running.")
            if (capabilities(context) == 0) return failed(id, "Open the companion and enable notifications before requesting privileged operations.")
            requests[id] = Result(id, owner, operation, argument)
        }
        updateNotification(context)
        refreshUi()
        return Bundle().apply { putString("state", "pending"); putString("requestId", id); putString("message", "Waiting for explicit approval in the companion.") }
    }

    private fun cancel(id: String, owner: Any): Bundle = synchronized(lock) {
        val result = requests[id]?.takeIf { it.owner === owner } ?: return@synchronized failed(id, "Unknown request")
        when (result.state) {
            State.PENDING -> {
                result.state = State.FAILED
                result.message = "Canceled before approval; command was not run."
                toBundle(result)
            }
            State.APPROVED -> {
                result.state = State.FAILED
                result.message = "Canceled before shell execution; command was not run."
                toBundle(result)
            }
            State.RUNNING -> failed(id, "Execution has already started and cannot be safely interrupted; it will not be retried.")
            else -> toBundle(result)
        }.also { refreshUi() }
    }

    private fun reviewNext(activity: Activity) {
        val next = synchronized(lock) { requests.values.firstOrNull { it.state == State.PENDING } } ?: run {
            setStatus("No request is waiting for approval."); return
        }
        if (!shizukuGranted()) {
            failPending(next.id, "Shizuku permission is no longer granted.")
            return
        }
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true || !activity.hasWindowFocus()) {
            setStatus("Unlock the device and bring Backstage Agent to the foreground to review this request.")
            return
        }
        val verb = when (next.operation) { "shell" -> "Run shell command"; "install" -> "Install package"; else -> "Uninstall package" }
        val body = when (next.operation) {
            "shell" -> next.argument
            "install" -> "Install APK from:\n${next.argument}"
            else -> "Uninstall package:\n${next.argument}"
        }
        AlertDialog.Builder(activity)
            .setTitle("$verb?")
            .setMessage("$body\n\nThis operation will run once with shell privileges on this device.")
            .setNegativeButton("Deny") { _, _ -> failPending(next.id, "Denied by the device user; command was not run.") }
            .setPositiveButton("Approve once") { _, _ -> approve(next.id, activity.applicationContext) }
            .setOnCancelListener { failPending(next.id, "Approval dialog dismissed; command was not run.") }
            .show()
    }

    private fun approve(id: String, context: Context) {
        val result = synchronized(lock) {
            requests[id]?.takeIf { it.state == State.PENDING }?.apply { state = State.APPROVED }
        } ?: return
        refreshUi()
        io.execute {
            var executionDispatched = false
            try {
                validateRequest(result.operation, result.argument)?.let { throw IllegalArgumentException(it) }
                if (!shellIdentity()) throw IllegalStateException("Shizuku shell identity (uid 2000) is unavailable; root identity is not allowed.")
                val service = awaitShellService(context)
                val argv = when (result.operation) {
                    "shell" -> arrayOf("sh", "-c", result.argument)
                    "install" -> arrayOf("pm", "install", "-r", "--user", "0", result.argument)
                    "uninstall" -> arrayOf("pm", "uninstall", "--user", "0", result.argument)
                    else -> error("Invalid operation")
                }
                synchronized(lock) {
                    if (requests[result.id] !== result || result.state != State.APPROVED) return@execute
                    result.state = State.RUNNING
                }
                // Once Binder exec is entered, any transport failure is ambiguous. Never invoke it again.
                executionDispatched = true
                val raw = service.exec(argv, COMMAND_TIMEOUT_MS)
                val code = raw.substringBefore('\n').trim().toIntOrNull() ?: 127
                val rest = raw.substringAfter('\n', "")
                val out = rest.substringBefore("\n\u0000")
                val err = rest.substringAfter("\n\u0000", "")
                synchronized(lock) {
                    if (result.state == State.RUNNING) {
                        result.exitCode = code
                        result.stdout = out
                        result.stderr = err
                        result.state = State.FINISHED
                        if (code == 124) result.message = "Command timed out. It may have partially completed; it was not retried."
                    }
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    if (result.state == State.APPROVED || result.state == State.RUNNING) {
                        result.state = State.FAILED
                        result.message = if (executionDispatched) {
                            "Execution failed (${t.javaClass.simpleName}: ${t.message.orEmpty()}); it may have run and was not retried."
                        } else {
                            "Execution was not started (${t.javaClass.simpleName}: ${t.message.orEmpty()}); no command was run."
                        }
                    }
                }
            } finally { refreshUi() }
        }
    }

    private fun awaitShellService(context: Context): IShellService {
        shell?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let { return it }
        synchronized(shellLock) {
            shell?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let { return it }
            val monitor = Object()
            var result: IShellService? = null
            var error: Throwable? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    synchronized(monitor) { result = binder?.let(IShellService.Stub::asInterface); monitor.notifyAll() }
                }
                override fun onServiceDisconnected(name: ComponentName?) { shell = null }
            }
            binding?.let { runCatching { Shizuku.unbindUserService(userServiceArgs(context), it, true) }; binding = null }
            binding = connection
            try { Shizuku.bindUserService(userServiceArgs(context), connection) }
            catch (t: Throwable) { error = t }
            if (error == null) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                synchronized(monitor) {
                    while (result == null && error == null) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) break
                        TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
                    }
                }
            }
            error?.let { throw it }
            val connected = result ?: throw IllegalStateException("Timed out connecting to the Shizuku shell service")
            shell = connected
            return connected
        }
    }

    private fun userServiceArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShellUserService::class.java.name)
    ).daemon(false).processNameSuffix("backstage_companion_shell").debuggable(BuildConfig.DEBUG).version(USER_SERVICE_VERSION)

    internal fun validateRequest(operation: String, argument: String): String? = when (operation) {
        "shell" -> when {
            argument.isBlank() -> "Shell command must not be empty."
            argument.length > MAX_COMMAND_CHARS || '\u0000' in argument -> "Shell command exceeds the input limit."
            hasForbiddenInvisible(argument, allowLineBreaks = true) -> "Shell command contains unsupported control or formatting characters."
            forbiddenShell(argument) -> "This shell command path is not available for screen access or accessibility configuration."
            else -> null
        }
        "install" -> {
            when {
                argument.length > MAX_ARG_CHARS || !argument.startsWith('/') -> "Install requires an absolute local APK path."
                !argument.endsWith(".apk", ignoreCase = true) -> "Install path must point to an APK file."
                hasForbiddenInvisible(argument) -> "Install path contains unsupported control or formatting characters."
                else -> null
            }
        }
        "uninstall" -> if (argument.length > MAX_ARG_CHARS || !PACKAGE_ID.matches(argument)) "Uninstall requires a strict Android package ID." else null
        else -> "Operation must be shell, install, or uninstall."
    }

    internal fun forbiddenShell(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "screencap", "screenrecord", "uiautomator", "enabled_accessibility_services",
            "accessibility_enabled", "bind_accessibility_service", "cmd accessibility",
            "dumpsys window", "service call window", "cmd window", "input tap", "input swipe",
            "input text", "input keyevent",
        ).any(normalized::contains)
    }

    private fun hasForbiddenInvisible(value: String, allowLineBreaks: Boolean = false): Boolean {
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val type = Character.getType(codePoint)
            val allowedWhitespace = allowLineBreaks && (codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code)
            if ((type == Character.CONTROL.toInt() && !allowedWhitespace) || type == Character.FORMAT.toInt()) return true
            offset += Character.charCount(codePoint)
        }
        return false
    }

    private val PACKAGE_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private fun failPending(id: String, message: String) {
        synchronized(lock) {
            requests[id]?.takeIf { it.state == State.PENDING }?.apply { state = State.FAILED; this.message = message }
        }
        applicationContextRef?.let(::updateNotification)
        refreshUi()
    }

    private fun shizukuGranted(): Boolean = binderAlive() && runCatching {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun shellIdentity(): Boolean = runCatching { Shizuku.getUid() == 2000 }.getOrDefault(false)

    private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun installListeners() {
        if (!listenersInstalled.compareAndSet(false, true)) return
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        }
    }

    private fun refreshUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(::refreshUi); return }
        val pending = synchronized(lock) { requests.values.count { it.state == State.PENDING } }
        val granted = shizukuGranted()
        statusText?.get()?.text = when {
            !binderAlive() -> "Status: start Shizuku or Stellar (with Shizuku compatibility) to continue."
            !granted -> "Status: Shizuku permission not granted."
            pending > 0 -> "Status: $pending request(s) waiting for your approval."
            else -> "Status: ready. Each operation requires approval here."
        }
        reviewButton?.get()?.apply { text = if (pending == 0) "Review privileged request (none waiting)" else "Review privileged request ($pending waiting)"; isEnabled = pending > 0 && granted }
        applicationContextRef?.let(::updateNotification)
    }

    private fun setStatus(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) statusText?.get()?.text = text
        else main.post { statusText?.get()?.text = text }
    }

    private fun trimResults() {
        val cutoff = System.currentTimeMillis() - MAX_RESULT_AGE_MS
        requests.entries.removeAll { it.value.createdAt < cutoff && it.value.state in setOf(State.FINISHED, State.FAILED) }
        while (requests.size >= MAX_RESULTS) {
            val oldest = requests.values.firstOrNull { it.state in setOf(State.FINISHED, State.FAILED) } ?: break
            requests.remove(oldest.id)
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Privileged request approvals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A private reminder to review local device administration requests in Backstage Agent."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
        }
    }

    private fun notificationsEnabled(context: Context): Boolean {
        if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun updateNotification(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { updateNotification(context) }; return }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val pending = synchronized(lock) { requests.values.count { it.state == State.PENDING } }
        if (pending == 0 || !notificationsEnabled(context)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val intent = Intent(context, com.eggman.backstage.companion.CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Backstage Agent")
            .setContentText("A local device request is waiting for your review.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .build())
    }

    private fun toBundle(result: Result) = Bundle().apply {
        putString("requestId", result.id)
        putString("state", when (result.state) {
            State.PENDING, State.APPROVED, State.RUNNING -> "pending"
            State.FINISHED -> "finished"
            State.FAILED -> "failed"
        })
        result.exitCode?.let { putInt("exitCode", it) }
        putString("stdout", result.stdout)
        putString("stderr", result.stderr)
        if (result.message.isNotBlank()) putString("message", result.message)
    }

    private fun failed(id: String, message: String) = Bundle().apply {
        putString("requestId", id); putString("state", "failed"); putString("message", message)
    }
}
