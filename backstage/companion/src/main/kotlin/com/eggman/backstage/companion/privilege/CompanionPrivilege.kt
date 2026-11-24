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
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.CompanionRuntime
import com.eggman.backstage.companion.omp.CompanionOmpRuntime
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.eggman.backstage.companion.R
import com.eggman.backstage.privileged.PrivilegeProviders
import com.google.android.material.button.MaterialButton
import rikka.shizuku.Shizuku
import com.eggman.backstage.privileged.IShellService
import com.eggman.backstage.privileged.ShellUserService
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.eggman.backstage.companion.BuildConfig

internal enum class CompanionPermissionState(val label: Int) {
    MISSING(R.string.companion_shizuku_missing),
    STOPPED(R.string.companion_shizuku_stopped),
    NEEDS_PERMISSION(R.string.companion_shizuku_needed),
    GRANTED(R.string.companion_shizuku_granted);

    companion object {
        fun from(installed: Boolean, binderAlive: Boolean, granted: Boolean) = when {
            binderAlive && granted -> GRANTED
            binderAlive -> NEEDS_PERMISSION
            installed -> STOPPED
            else -> MISSING
        }
    }
}

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
        val timeoutMs: Long,
        val script: CompanionSkillScript? = null,
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
    private var grantButton: WeakReference<Button>? = null
    private var queueStatus: WeakReference<TextView>? = null
    private var permissionMessage: Int? = null
    private var waitingForBinder = false
    private var foreground = false
    private val binderTimeout = Runnable { waitingForBinder = false; refreshUi() }
    @Volatile private var shell: IShellService? = null
    @Volatile private var binding: ServiceConnection? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == REQUEST_PERMISSION) main.post {
            permissionMessage = if (result == PackageManager.PERMISSION_GRANTED) null else R.string.companion_shizuku_denied
            refreshUi()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { shell = null; refreshUi() }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shell = null
        main.post {
            if (foreground && waitingForBinder && binderAlive()) {
                cancelBinderWait()
                requestShizukuPermission()
            }
            refreshUi()
        }
    }
    private val listenersInstalled = AtomicBoolean(false)

    /** Capability is advertised only while Shizuku is granted and an in-app or notification approval route is live. */
    @JvmStatic fun capabilities(context: Context): Int {
        if (!canUseShell()) return 0
        val activity = activityRef?.get()
        val foreground = activity != null && !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
        if (!foreground && !notificationsEnabled(context)) return 0
        return CAP_SHELL or CAP_PACKAGES or CompanionProtocol.CAP_SKILL_SCRIPTS
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

    /** Connect the permission card and the local request approval controls. */
    fun attach(activity: Activity, status: TextView, grant: Button, more: LinearLayout) {
        ensureNotificationChannel(activity)
        activityRef = WeakReference(activity)
        applicationContextRef = activity.applicationContext
        statusText = WeakReference(status)
        grantButton = WeakReference(grant)
        grant.setOnClickListener { grantPermission(activity) }
        more.addView(MaterialButton(activity).apply {
            setText(R.string.companion_open_shizuku_manager)
            visibility = if (installedManager(activity) == null) android.view.View.GONE else android.view.View.VISIBLE
            setOnClickListener { installedManager(activity)?.let { openManager(activity, it) } }
        })
        more.addView(TextView(activity).apply { setText(R.string.companion_approval_queue) })
        queueStatus = WeakReference(TextView(activity).also(more::addView))
        more.addView(MaterialButton(activity).apply {
            setText(R.string.companion_approval_notifications)
            setOnClickListener {
                if (notificationsEnabled(activity)) setStatus(activity.getString(R.string.companion_approval_notifications_enabled))
                else activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQUEST_PERMISSION + 1)
            }
        })
        reviewButton = WeakReference(MaterialButton(activity).apply {
            text = "Review privileged request"
            setOnClickListener { reviewNext(activity) }
            more.addView(this)
        })
        installListeners()
        refreshUi()
    }

    fun onResume(activity: Activity) {
        if (activityRef?.get() !== activity) return
        foreground = true
        if (!binderAlive() && installedManager(activity) == PrivilegeProviders.STELLAR_PACKAGE) requestStellarBinder(activity)
        refreshUi()
    }

    fun onPause(activity: Activity) {
        if (activityRef?.get() !== activity) return
        foreground = false
        cancelBinderWait()
    }

    private fun installedManager(context: Context): String? = PrivilegeProviders.packages.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0)) }.isSuccess
    }

    private fun grantPermission(activity: Activity) {
        permissionMessage = null
        if (binderAlive()) requestShizukuPermission()
        else when (val manager = installedManager(activity)) {
            PrivilegeProviders.STELLAR_PACKAGE -> {
                waitingForBinder = true
                main.removeCallbacks(binderTimeout)
                main.postDelayed(binderTimeout, 4_000)
                if (requestStellarBinder(activity).isFailure) {
                    cancelBinderWait()
                    permissionMessage = R.string.companion_shizuku_failed
                }
            }
            null -> Unit
            else -> openManager(activity, manager)
        }
        refreshUi()
    }

    private fun requestStellarBinder(context: Context) = runCatching {
        context.sendBroadcast(Intent(PrivilegeProviders.STELLAR_REQUEST_BINDER).setPackage(PrivilegeProviders.STELLAR_PACKAGE))
    }

    private fun openManager(activity: Activity, manager: String) {
        activity.packageManager.getLaunchIntentForPackage(manager)?.let { intent ->
            runCatching { activity.startActivity(intent) }
        }
    }

    private fun cancelBinderWait() {
        waitingForBinder = false
        main.removeCallbacks(binderTimeout)
    }

    private fun requestShizukuPermission() {
        if (shizukuGranted()) return
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) permissionMessage = R.string.companion_shizuku_denied
            else Shizuku.requestPermission(REQUEST_PERMISSION)
        }.onFailure {
            Log.w("CompanionPrivilege", "Shizuku permission request failed", it)
            permissionMessage = R.string.companion_shizuku_failed
        }
    }

    private fun start(context: Context, owner: Any, id: String, request: Bundle): Bundle {
        if (!shizukuGranted()) return failed(id, "Grant Shizuku permission in the companion before requesting privileged operations.")
        val operation = request.getString("operation").orEmpty()
        val rawArgument = request.getString("argument").orEmpty()
        val script = if (operation == "skill_script") try {
            CompanionSkillScript.from(request)
        } catch (_: Exception) {
            return failed(id, "Invalid skill archive, script path or arguments.")
        } else null
        val validation = validateRequest(operation, rawArgument)
        if (validation != null) return failed(id, validation)
        val argument = rawArgument
        val timeoutMs = if (operation == "shell" || script != null) {
            request.getInt("timeoutSeconds", 15).coerceIn(5, 300) * 1000L
        } else COMMAND_TIMEOUT_MS
        if (!shellIdentity()) return failed(id, "Privileged execution requires Shizuku shell identity (uid 2000). Root identity is not allowed.")
        synchronized(lock) {
            trimResults()
            if (requests.containsKey(id)) return failed(id, "Request ID is already in use.")
            val pendingCount = requests.values.count { it.state in setOf(State.PENDING, State.APPROVED, State.RUNNING) }
            if (pendingCount >= 8) return failed(id, "Too many privileged requests are already pending or running.")
            if (capabilities(context) == 0) return failed(id, "Open the companion and enable notifications before requesting privileged operations.")
            requests[id] = Result(id, owner, operation, argument, timeoutMs, script)
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
            setStatus("Unlock the device and bring Backstage-play-agent to the foreground to review this request.")
            return
        }
        val verb = when (next.operation) {
            "shell" -> "Run shell command"
            "skill_script" -> "Run skill script"
            "install" -> "Install package"
            else -> "Uninstall package"
        }
        val body = when (next.operation) {
            "shell" -> next.argument
            "skill_script" -> "${next.argument}/${requireNotNull(next.script).approvalText()}"
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
            var scriptStage: String? = null
            try {
                validateRequest(result.operation, result.argument)?.let { throw IllegalArgumentException(it) }
                if (!shellIdentity()) throw IllegalStateException("Shizuku shell identity (uid 2000) is unavailable; root identity is not allowed.")
                val service = awaitShellService(context)
                val argv = when (result.operation) {
                    "shell" -> listOf("sh", "-c", result.argument)
                    "skill_script" -> requireNotNull(result.script).let { script ->
                        val stage = "/data/local/tmp/backstage-agent-skill-${UUID.randomUUID()}"
                        val command = script.argv(stage)
                        scriptStage = stage
                        if (script.interpreter == "sh") command
                        else CompanionOmpRuntime.skillArgv(context, command)
                            ?: error("Install the companion OMP runtime before running ${script.interpreter} scripts")
                    }
                    "install" -> listOf("pm", "install", "-r", "--user", "0", result.argument)
                    "uninstall" -> listOf("pm", "uninstall", "--user", "0", result.argument)
                    else -> error("Invalid operation")
                }
                synchronized(lock) {
                    if (requests[result.id] !== result || result.state != State.APPROVED) return@execute
                    result.state = State.RUNNING
                }
                // Once Binder exec is entered, any transport failure is ambiguous. Never invoke it again.
                executionDispatched = true
                val (code, out, err) = decodeShellResult(service.exec(argv.toTypedArray(), result.timeoutMs))
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
            } finally {
                if (executionDispatched) scriptStage?.let { stage ->
                    runCatching { runSystemCommand(context, listOf("rm", "-rf", stage), 5_000) }
                }
                refreshUi()
            }
        }
    }

    @JvmStatic internal fun canUseShell(): Boolean = shizukuGranted() && shellIdentity()

    @JvmStatic internal fun runSystemCommand(context: Context, argv: List<String>, timeoutMs: Long): Triple<Int, String, String> {
        if (!canUseShell()) return Triple(126, "", "Shizuku shell access is unavailable.")
        return decodeShellResult(awaitShellService(context).exec(argv.toTypedArray(), timeoutMs))
    }

    private fun decodeShellResult(raw: String): Triple<Int, String, String> {
        val code = raw.substringBefore('\n').trim().toIntOrNull() ?: 127
        val body = raw.substringAfter('\n', "")
        return Triple(code, body.substringBefore("\n\u0000"), body.substringAfter("\n\u0000", ""))
    }

    internal fun setOwnAccessibilityEnabled(context: Context, enabled: Boolean, onComplete: (Boolean) -> Unit) {
        io.execute {
            val success = runCatching {
                synchronized(accessibilityLock) {
                    check(!enabled || CompanionRuntime.consent(context))
                    val key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    val component = ComponentName(context.packageName, CompanionProtocol.ACCESSIBILITY_SERVICE)
                    val (code, value) = runSystemCommand(context, listOf("settings", "get", "secure", key), 5_000)
                    check(code == 0)
                    val entries = value.trim().takeUnless { it == "null" }.orEmpty().split(':').filter(String::isNotBlank)
                    val listed = entries.any { ComponentName.unflattenFromString(it) == component }
                    check(!enabled || CompanionRuntime.consent(context))
                    if (listed != enabled) {
                        val next = if (enabled) entries + component.flattenToString()
                        else entries.filterNot { ComponentName.unflattenFromString(it) == component }
                        check(runSystemCommand(context, listOf("settings", "put", "secure", key, next.joinToString(":")), 5_000).first == 0)
                    }
                    if (enabled && android.provider.Settings.Secure.getInt(
                            context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0,
                        ) != 1) {
                        check(CompanionRuntime.consent(context))
                        check(runSystemCommand(context, listOf("settings", "put", "secure", android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1"), 5_000).first == 0)
                    }
                    true
                }
            }.getOrDefault(false)
            onComplete(success)
        }
    }

    private val accessibilityLock = Any()

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
        "skill_script" -> if (!Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}-]{0,63}").matches(argument)) "Invalid skill name." else null
        "uninstall" -> if (argument.length > MAX_ARG_CHARS || !PACKAGE_ID.matches(argument)) "Uninstall requires a strict Android package ID." else null
        else -> "Unsupported privileged operation."
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
        val alive = binderAlive()
        val granted = alive && shizukuGranted()
        statusText?.get()?.let { status ->
            val state = CompanionPermissionState.from(installedManager(status.context) != null, alive, granted)
            status.setText(when {
                waitingForBinder -> R.string.companion_shizuku_waiting
                state == CompanionPermissionState.GRANTED -> state.label
                else -> permissionMessage ?: state.label
            })
            grantButton?.get()?.isEnabled = !waitingForBinder && state != CompanionPermissionState.GRANTED &&
                state != CompanionPermissionState.MISSING
        }
        queueStatus?.get()?.apply { text = resources.getQuantityString(R.plurals.companion_requests_waiting, pending, pending) }
        reviewButton?.get()?.isEnabled = pending > 0 && granted
        applicationContextRef?.let(::updateNotification)
    }

    private fun setStatus(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) queueStatus?.get()?.text = text
        else main.post { queueStatus?.get()?.text = text }
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
                description = "A private reminder to review local device administration requests in Backstage-play-agent."
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
            .setContentTitle("Backstage-play-agent")
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
