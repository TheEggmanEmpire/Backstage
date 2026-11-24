package com.eggman.backstage.companion

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggman.backstage.a11y.LocalAccessibilityBackend
import com.eggman.backstage.a11y.companion.*
import com.eggman.backstage.companion.privilege.CompanionPrivilege
import com.eggman.backstage.companion.omp.CompanionOmpRuntime
import com.eggman.backstage.companion.update.CompanionUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Manifest signature permission protects binding. Package names and SHA-256 current signing certificates are
 * checked again on EVERY method, on the original incoming Binder thread; onBind's caller is system_server.
 */
class CompanionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<Int, Session>()
    private val changed: () -> Unit = {
        sessions.values.forEach { session ->
            session.handles.clear()
            session.send { onStateChanged(CompanionRuntime.status(this@CompanionService)) }
        }
    }
    private val event: (CompanionEvent) -> Unit = { e -> sessions.values.forEach { it.send { onEvent(e) } } }

    override fun onCreate() {
        super.onCreate()
        CompanionRuntime.stateListeners += changed
        CompanionRuntime.eventListeners += event
        CompanionRuntime.restoreAccessibilitySettings(applicationContext)
    }
    private fun caller() = CompanionSignatures.enforceCaller(this)
    private fun session(): Session = sessions[caller()] ?: throw SecurityException("Subscribe before requesting screen access")
    private fun backend(): LocalAccessibilityBackend? =
        if (!CompanionRuntime.protectCompanionUi && CompanionRuntime.status(this) == CompanionProtocol.READY) CompanionRuntime.backend else null

    private val endpoint = object : IAgentCompanion.Stub() {
        override fun protocolVersion(): Int { caller(); return CompanionProtocol.VERSION }
        override fun metadata(): Bundle {
            caller()
            val identity = Binder.clearCallingIdentity()
            try {
                val context = this@CompanionService
                val screen = if (backend() != null) CompanionProtocol.CAP_SCREEN else 0
                val accessibility = if (CompanionRuntime.consent(context) && CompanionPrivilege.canUseShell()) {
                    CompanionProtocol.CAP_ACCESSIBILITY_SETTINGS
                } else 0
                return Bundle().apply {
                    putLong("versionCode", BuildConfig.VERSION_CODE.toLong())
                    putInt("protocolVersion", CompanionProtocol.VERSION)
                    putInt(
                        "capabilities", screen or accessibility or CompanionPrivilege.capabilities(context) or
                            CompanionOmpRuntime.capabilities(context),
                    )
                    putBoolean("updateAvailable", CompanionUpdates.available(context))
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        override fun privilegedRequest(request: Bundle): Bundle {
            val owner = session()
            val identity = Binder.clearCallingIdentity()
            try {
                if (!owner.active) throw SecurityException("Backstage-play-agent session closed")
                val reply = CompanionPrivilege.request(this@CompanionService, owner, request)
                if (!owner.active) CompanionPrivilege.cancelOwner(owner)
                return reply
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
        override fun updateAccessibilitySettings(settings: Bundle): Boolean {
            val owner = session()
            val identity = Binder.clearCallingIdentity()
            try {
                if (!owner.active) throw SecurityException("Backstage-play-agent session closed")
                val context = this@CompanionService
                return CompanionRuntime.consent(context) && CompanionRuntime.applyAccessibilitySettings(context, settings)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun ompRequest(request: Bundle): Bundle {
            val owner = session()
            val identity = Binder.clearCallingIdentity()
            try {
                if (!owner.active) throw SecurityException("Backstage-play-agent session closed")
                val reply = CompanionOmpRuntime.request(this@CompanionService, owner, request)
                if (!owner.active) CompanionOmpRuntime.cancelOwner(owner)
                return reply
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun status(): Int { caller(); return CompanionRuntime.status(this@CompanionService) }
        override fun subscribe(callback: IAgentCallback) {
            val uid = caller()
            val session = Session(uid, callback)
            sessions.put(uid, session)?.close()
            try {
                callback.asBinder().linkToDeath(session.death, 0)
                session.connected()
                session.send { onStateChanged(CompanionRuntime.status(this@CompanionService)) }
            } catch (_: Exception) { remove(session) }
        }
        override fun unsubscribe(callback: IAgentCallback) {
            val uid = caller()
            sessions[uid]?.takeIf { it.callback.asBinder() == callback.asBinder() }?.let(::remove)
        }
        override fun capabilityGaps(): Array<String> {
            caller()
            val info = backend()?.service?.serviceInfo ?: return emptyArray()
            return buildList {
                val caps = info.capabilities
                if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0) add("window content")
                if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) add("gestures")
                if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) add("screenshots")
                if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) add("interactive windows")
                if (info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) add("view ids")
                // isAccessibilityTool is intentionally absent. Android's sensitive-view restrictions remain in force.
            }.toTypedArray()
        }
        override fun trackedDisplays(): IntArray { session(); return backend()?.trackedDisplays().orEmpty().take(128).toIntArray() }
        override fun windowsOn(displayId: Int): MutableList<AccessibilityWindowInfo> {
            session()
            // Metadata only. Root nodes/capture handles are supplied by capture, not platform connection IDs
            // that are local to the companion process. This retains existing callers' window-info API.
            return backend()?.windowsOn(displayId).orEmpty().take(128).toMutableList()
        }
        override fun capture(displayId: Int, width: Int, height: Int): ParcelFileDescriptor? {
            val session = session()
            if (backend() == null || width !in 1..16384 || height !in 1..16384) return null
            return session.pipe(CaptureCodec.MAX_BYTES) { output ->
                val local = backend() ?: error("Service off")
                // Android's remote view lookup can take time. Do not hold the registry lock during it: a service
                // disconnect must be able to invalidate handles immediately while a capture is still in flight.
                val capture = local.capture(displayId, width, height)
                if (!session.active || local !== backend()) error("Service disconnected")
                val bytes = session.handles.replace { register ->
                    ByteArrayOutputStream().also { out ->
                        CaptureCodec.write(capture, out) { handle -> (handle as? AccessibilityNodeInfo)?.let(register) ?: 0L }
                    }.toByteArray()
                }
                if (!session.active || local !== backend()) { session.handles.clear(); error("Service disconnected") }
                output.write(bytes)
            }
        }
        override fun screenshot(displayId: Int): ParcelFileDescriptor? {
            val session = session()
            if (backend() == null) return null
            return session.pipe(PipeTransport.MAX_IMAGE_BYTES) { output ->
                val local = backend() ?: error("Service off")
                val bitmap = local.screenshot(displayId) ?: error("Screenshot unavailable")
                try {
                    if (!session.active || local !== backend()) error("Service disconnected")
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, CompanionProtocol.SCREENSHOT_JPEG_QUALITY, output))
                } finally { bitmap.recycle() }
            }
        }
        override fun nodeAction(handle: Long, action: Int, displayId: Int, text: String?): Boolean {
            val session = session()
            val local = backend() ?: return false
            if (text != null && text.length > 32768) return false
            if (action == CompanionProtocol.CLIPBOARD_UNRESTORED) return local.clipboardCouldNotBeRestored(displayId)
            fun act(node: AccessibilityNodeInfo?): Boolean = runCatching {
                when (action) {
                    CompanionProtocol.CLICK -> local.click(node)
                    CompanionProtocol.LONG_CLICK -> local.click(node, true)
                    CompanionProtocol.SET_TEXT -> local.setText(node, displayId, text ?: return false)
                    CompanionProtocol.SET_SECRET_TEXT -> {
                        val value = text ?: return false
                        com.eggman.backstage.a11y.TextInputSession.onThread(secret = true) { local.setText(node, displayId, value) }
                    }
                    CompanionProtocol.SCROLL_FORWARD -> local.scroll(node, true)
                    CompanionProtocol.SCROLL_BACKWARD -> local.scroll(node, false)
                    CompanionProtocol.FOCUS -> local.focus(node)
                    else -> false
                }
            }.getOrDefault(false)
            if (handle == 0L) return action in setOf(CompanionProtocol.SET_TEXT, CompanionProtocol.SET_SECRET_TEXT) && act(null)
            return session.handles.withNode(handle, ::act) ?: false
        }
        override fun imeEnter(displayId: Int): Boolean { session(); return runCatching { backend()?.imeEnter(displayId) == true }.getOrDefault(false) }
        override fun globalAction(action: Int): Boolean { session(); return backend()?.global(action) == true }
        override fun gesture(requestId: Long, displayId: Int, points: FloatArray, durationMs: Long) {
            val session = session()
            val local = backend()
            val path = runCatching { GesturePoints.path(points) }.getOrNull()
            if (local == null || path == null || displayId < 0 || durationMs !in 1..59000 || !session.gesturePermit.tryAcquire()) {
                session.send { onGestureResult(requestId, false) }; return
            }
            scope.launch(Dispatchers.Main) {
                try {
                    // A stale display or a platform stroke-builder failure is a failed action, never an uncaught
                    // coroutine exception that terminates the companion process.
                    val completed = runCatching { session.active && local === backend() && local.gesture(displayId, path, durationMs) }
                        .getOrDefault(false)
                    session.send { onGestureResult(requestId, completed) }
                } finally { session.gesturePermit.release() }
            }
        }
    }

    private inner class Session(val uid: Int, val callback: IAgentCallback) {
        @Volatile var active = true
        val handles = NodeHandles<AccessibilityNodeInfo>(release = { @Suppress("DEPRECATION") it.recycle() })
        val gesturePermit = Semaphore(1)
        private val pipePermit = Semaphore(2)
        val death = IBinder.DeathRecipient { remove(this) }
        fun send(block: IAgentCallback.() -> Unit) {
            if (active) try { callback.block() } catch (_: Exception) { remove(this) }
        }
        fun pipe(limit: Int, block: suspend (java.io.OutputStream) -> Unit): ParcelFileDescriptor? {
            if (!active || !pipePermit.tryAcquire()) return null
            return try { PipeTransport.produce(scope, limit, finished = { pipePermit.release() }) { out ->
                if (!active) error("Client disconnected")
                block(out)
            } } catch (_: Exception) { pipePermit.release(); null }
        }
        @Synchronized fun connected() {
            if (active) CompanionRuntime.clientConnected(this)
        }
        @Synchronized fun close() {
            active = false
            CompanionRuntime.clientDisconnected(this)
            CompanionPrivilege.cancelOwner(this)
            CompanionOmpRuntime.cancelOwner(this)
            handles.clear()
            runCatching { callback.asBinder().unlinkToDeath(death, 0) }
        }
    }
    private fun remove(session: Session) { sessions.remove(session.uid, session); session.close() }
    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onUnbind(intent: Intent?): Boolean { sessions.values.toList().forEach(::remove); return false }
    override fun onDestroy() {
        CompanionRuntime.stateListeners -= changed
        CompanionRuntime.eventListeners -= event
        sessions.values.toList().forEach(::remove)
        scope.cancel()
        super.onDestroy()
    }
}
