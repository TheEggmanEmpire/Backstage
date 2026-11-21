package com.eggman.backstage.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.eggman.backstage.BuildConfig
import com.eggman.backstage.host.DesktopHosts
import com.eggman.backstage.init.Backstage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
    override fun toString() = if (ok) out.trim() else "exit=$code ${err.ifBlank { out }.trim()}"
}

/**
 * Shell-identity (uid 2000) access for the agent.
 *
 * Order of preference:
 *  1. the Dextop host ([DesktopHosts.current]) — Dextop's embedded wireless-ADB runtime or its
 *     Stellar/Shizuku connection, already authorised by the user for the desktop;
 *  2. the shared Shizuku API directly, with a tiny UserService for commands. Stellar supplies
 *     that API through its compatibility layer. With compatibility off it cannot supply this
 *     binder: setup explains the switch, while the host's embedded runtime remains the fallback.
 * No second Stellar client is installed: permission and connection state stay owned by Dextop.
 *
 * Security: callers use fixed command templates (input, am start, settings for our own
 * accessibility component). The sole raw-shell exception is the user-gated adb_command tool,
 * which requires approval for each command unless the user enables ADB Auto.
 */
object Privileged {
    private const val PERMISSION_REQUEST = 4711
    private const val SHELL_SERVICE_VERSION = 3

    enum class Source { DESKTOP_HOST, SHIZUKU, NONE }

    @Volatile private var shell: IShellService? = null
    private val bindLock = Mutex()

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = binderAlive() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private fun hostAvailable(): Boolean = runCatching { DesktopHosts.current?.privilegedAvailable() == true }.getOrDefault(false)

    fun source(): Source = when {
        hostAvailable() -> Source.DESKTOP_HOST
        shizukuGranted() -> Source.SHIZUKU
        else -> Source.NONE
    }

    /** Any shell-identity path is usable. */
    fun available(): Boolean = source() != Source.NONE

    /** Compat name used by the prototype UI: true when shell identity is usable. */
    fun granted(): Boolean = available()

    fun uid(): Int = runCatching { Shizuku.getUid() }.getOrDefault(-1)

    fun requestPermission() {
        if (binderAlive() && !shizukuGranted()) runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
    }

    /** Hidden system-service AIDL proxy whose transactions run as shell, or null. */
    fun serviceInterfaceOrNull(name: String, interfaceClass: String): Any? {
        DesktopHosts.current?.takeIf { hostAvailable() }?.let { host ->
            runCatching { host.systemService(name, interfaceClass) }.getOrNull()?.let { return it }
        }
        if (!shizukuGranted()) return null
        return runCatching {
            val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name))
            Class.forName("$interfaceClass\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        }.getOrNull()
    }

    fun serviceInterface(name: String, interfaceClass: String): Any =
        serviceInterfaceOrNull(name, interfaceClass) ?: error("No privileged access (Stellar, Shizuku or Dextop wireless debugging) for $name")

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(Backstage.context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("backstage_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(SHELL_SERVICE_VERSION)
    }

    private suspend fun shellService(): IShellService? = bindLock.withLock {
        if (!shizukuGranted()) return null
        shell?.takeIf { runCatching { it.asBinder().isBinderAlive }.getOrDefault(false) }?.let { return it }
        val ready = CompletableDeferred<IShellService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) ready.complete(IShellService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) { shell = null }
        }
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }.onFailure { return null }
        withTimeoutOrNull(10_000) { ready.await() }?.also { shell = it }
    }

    suspend fun exec(vararg argv: String, timeoutMs: Long = 15_000): ShellResult {
        DesktopHosts.current?.takeIf { hostAvailable() }?.let { host ->
            // Only a host that cannot run bounded commands at all falls through to Shizuku. Any other failure may come
            // after the command started (binder death, a transport deadline), so it is reported, never run twice.
            val r = try { host.shell(*argv, timeoutMs = timeoutMs, maxOutputBytes = BoundedShell.DEFAULT_MAX_OUTPUT_BYTES) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: UnsupportedOperationException) { null }
                catch (e: Exception) {
                    return ShellResult(127, "", "The desktop host's shell failed (${e.javaClass.simpleName}: ${e.message.orEmpty()}); " +
                        "the command may have run, so it was not retried.")
                }
            if (r != null) return ShellResult(r.exitCode, r.output, r.error)
        }
        val service = shellService()
            ?: return ShellResult(126, "", "No privileged access: start Stellar (enable Shizuku compatibility), Shizuku or Dextop's wireless-debugging connection")
        // Binder calls cannot be interrupted in flight. Cancel the continuation immediately;
        // the remote process group still has its own deadline and bounded output.
        return suspendCancellableCoroutine { continuation ->
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable
                val result = runCatching { decode(service.exec(arrayOf(*argv), timeoutMs)) }
                    .getOrElse { ShellResult(127, "", it.message.orEmpty()) }
                continuation.resumeWith(Result.success(result))
            })
        }
    }

    /** Wire format of [IShellService.exec]: "<exitCode>\n<stdout>\n\u0000<stderr>". */
    fun decode(raw: String): ShellResult {
        val code = raw.substringBefore('\n').trim().toIntOrNull() ?: 127
        val rest = raw.substringAfter('\n', "")
        return ShellResult(code, rest.substringBefore("\n\u0000"), rest.substringAfter("\n\u0000", ""))
    }

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var hooked = false

    /** Returns an unregister function. Shizuku listeners are installed once per process. */
    fun addPermissionListener(onChange: () -> Unit): () -> Unit {
        listeners += onChange
        if (!hooked) synchronized(this) {
            if (!hooked) {
                hooked = true
                runCatching {
                    Shizuku.addRequestPermissionResultListener { _, _ -> listeners.forEach { it() } }
                    // A provider change can deliver a different shared binder without killing the old UserService.
                    // Rebind on the new connection; never keep executing through the previous manager.
                    Shizuku.addBinderReceivedListenerSticky { shell = null; listeners.forEach { it() } }
                    Shizuku.addBinderDeadListener { shell = null; listeners.forEach { it() } }
                }
            }
        }
        return { listeners -= onChange }
    }

    internal fun notifyChanged() = listeners.forEach { runCatching { it() } }
}
