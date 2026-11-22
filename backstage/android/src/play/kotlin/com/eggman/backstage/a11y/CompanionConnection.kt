package com.eggman.backstage.a11y

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.view.accessibility.AccessibilityEvent
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.a11y.companion.CompanionSignatures
import com.eggman.backstage.companion.CompanionEvent
import com.eggman.backstage.companion.IAgentCallback
import com.eggman.backstage.companion.IAgentCompanion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class CompanionState(val message: String) {
    MISSING("Backstage Agent: companion missing. Install Backstage Agent from the project's GitHub releases, then open it and enable its accessibility service."),
    SIGNATURE_MISMATCH("Backstage Agent: accessibility unavailable because the signing certificates differ. Install a companion signed with the same certificate as Backstage."),
    PERMISSION_MISSING("Backstage Agent: this Backstage build lacks the companion accessibility signature permission. Update both apps from matching releases."),
    CONNECTING("Backstage Agent: connecting to the verified accessibility companion."),
    CONSENT_NEEDED("Backstage Agent: open the companion and accept or decline its screen and notification disclosure before enabling accessibility."),
    SERVICE_OFF("Backstage Agent: service off. Open Backstage Agent and enable its accessibility service in Android Settings."),
    CONNECTED("Backstage Agent: connected. Screen access is provided by the separate companion."),
    INCOMPATIBLE("Backstage Agent update needed. Open Backstage Agent to check for an update."),
    DISCONNECTED("Backstage Agent: accessibility connection lost. Reconnecting; actions that may have run are not repeated."),
}

/**
 * Play-only connection owner. A short status loop also detects installation, consent revocation and service toggles
 * while a task/UI is absent. It never enables accessibility, and reconnects the transport without replaying actions.
 */
@SuppressLint("StaticFieldLeak") // Only applicationContext is retained, including inside Binding; never an Activity.
object CompanionConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _state = MutableStateFlow(CompanionState.MISSING)
    val state: StateFlow<CompanionState> = _state
    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable
    @Volatile var metadata: CompanionMetadata? = null
        private set
    @SuppressLint("StaticFieldLeak") @Volatile private var app: Context? = null
    @Volatile private var binding: Binding? = null
    @Volatile var required: Boolean = false
        private set

    fun isRequired(context: Context): Boolean = context.packageName == CompanionProtocol.PLAY_PACKAGE

    fun connect(context: Context) {
        if (!isRequired(context)) return
        synchronized(lock) {
            required = true
            if (app != null) return
            app = context.applicationContext ?: context
        }
        scope.launch {
            while (true) {
                refreshNow()
                delay(2_000)
            }
        }
    }

    fun refresh() { scope.launch { refreshNow() } }

    private fun refreshNow() {
        val context = app ?: return
        binding?.let { b ->
            val remote = b.remote ?: return
            try {
                b.publishMetadata(remote.metadata())
                b.publish(remote.status())
            } catch (_: Exception) { b.lost(CompanionState.DISCONNECTED) }
            return
        }
        val component = ComponentName(CompanionProtocol.PACKAGE, CompanionProtocol.SERVICE)
        val info = runCatching { context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0)) }.getOrNull()
        val failure = when {
            info == null -> CompanionState.MISSING
            !CompanionSignatures.companionMatches(context) -> CompanionState.SIGNATURE_MISMATCH
            info.permission != CompanionProtocol.PERMISSION || !info.exported || !info.enabled -> CompanionState.INCOMPATIBLE
            context.checkSelfPermission(CompanionProtocol.PERMISSION) != PackageManager.PERMISSION_GRANTED -> CompanionState.PERMISSION_MISSING
            else -> null
        }
        if (failure != null) { _state.value = failure; return }
        val next = Binding(context, info!!.applicationInfo.uid)
        synchronized(lock) { if (binding != null) return; binding = next; _state.value = CompanionState.CONNECTING }
        try {
            if (!context.bindService(Intent().setComponent(component), next, Context.BIND_AUTO_CREATE)) next.lost(CompanionState.DISCONNECTED)
        } catch (_: SecurityException) { next.lost(CompanionState.PERMISSION_MISSING) }
        catch (_: Exception) { next.lost(CompanionState.DISCONNECTED) }
    }

    fun currentGaps(): List<String> = if (required && state.value != CompanionState.CONNECTED) listOf(state.value.message)
        else binding?.gaps.orEmpty()

    internal fun privilegedEndpoint(capability: Int): IAgentCompanion? =
        synchronized(lock) {
            if (metadata?.has(capability) == true) binding?.remote else null
        }

    private class Binding(val context: Context, private val uid: Int) : ServiceConnection {
        @Volatile var remote: IAgentCompanion? = null
        @Volatile var gaps: List<String> = emptyList()
        private var binder: IBinder? = null
        private var backend: RemoteAccessibilityBackend? = null
        private var agent: AgentAccessibility? = null
        private val death = IBinder.DeathRecipient { lost(CompanionState.DISCONNECTED) }
        private val callback = object : IAgentCallback.Stub() {
            private fun trusted() = Binder.getCallingUid() == uid && binding === this@Binding
            override fun onStateChanged(status: Int) { if (trusted()) publish(status) }
            override fun onEvent(event: CompanionEvent?) { if (trusted() && event != null) agent?.onRemoteEvent(event) }
            override fun onGestureResult(requestId: Long, completed: Boolean) { if (trusted()) backend?.gestureResult(requestId, completed) }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            scope.launch {
                if (binding !== this@Binding) return@launch
                // Verify again after binding: replacement between package lookup and connection must fail closed.
                if (!runCatching { name.packageName == CompanionProtocol.PACKAGE && CompanionSignatures.companionMatches(context) &&
                    context.packageManager.getApplicationInfo(CompanionProtocol.PACKAGE, 0).uid == uid }.getOrDefault(false)) {
                    lost(CompanionState.SIGNATURE_MISMATCH); return@launch
                }
                try {
                    service.linkToDeath(death, 0)
                    binder = service
                    val endpoint = IAgentCompanion.Stub.asInterface(service)
                    val version = endpoint.protocolVersion()
                    if (!CompanionProtocol.supports(version)) {
                        lost(CompanionState.INCOMPATIBLE)
                        return@launch
                    }
                    publishMetadata(endpoint.metadata())
                    backend = RemoteAccessibilityBackend(endpoint) { lost(CompanionState.DISCONNECTED) }
                    remote = endpoint
                    endpoint.subscribe(callback)
                    publish(endpoint.status())
                } catch (_: SecurityException) { lost(CompanionState.PERMISSION_MISSING) }
                catch (_: Exception) { lost(CompanionState.DISCONNECTED) }
            }
        }

        fun publishMetadata(reply: Bundle) {
            val value = CompanionMetadata(
                reply.getLong("versionCode"), reply.getInt("capabilities"), reply.getBoolean("updateAvailable"),
            )
            synchronized(lock) {
                if (binding !== this) return
                metadata = value
                _updateAvailable.value = value.updateAvailable
            }
        }

        fun publish(status: Int) {
            synchronized(lock) {
                if (binding !== this) return
                val next = when (status) {
                    CompanionProtocol.READY -> CompanionState.CONNECTED
                    CompanionProtocol.CONSENT_NEEDED -> CompanionState.CONSENT_NEEDED
                    else -> CompanionState.SERVICE_OFF
                }
                if (next == CompanionState.CONNECTED) {
                    if (agent == null) agent = backend?.let { AgentAccessibility(RemoteAccessibilityContext(context), it) }
                    AgentAccessibility.instance = agent
                    AgentAccessibility._connected.value = agent != null
                } else {
                    backend?.serviceStopped()
                    if (AgentAccessibility.instance === agent) AgentAccessibility.instance = null
                    AgentAccessibility._connected.value = false
                    agent = null
                }
                _state.value = next
            }
            // Never make a synchronous Binder call while processing an incoming callback: it could deadlock the
            // companion's subscription/state lock. Capability refresh happens separately on IO.
            if (status == CompanionProtocol.READY) scope.launch {
                gaps = runCatching { remote?.capabilityGaps()?.toList().orEmpty() }.getOrDefault(emptyList())
            }
        }

        fun lost(reason: CompanionState) {
            synchronized(lock) {
                if (binding !== this) return
                binding = null
                metadata = null
                _updateAvailable.value = false
                backend?.disconnect()
                if (AgentAccessibility.instance === agent) AgentAccessibility.instance = null
                AgentAccessibility._connected.value = false
                agent = null
                _state.value = reason
            }
            runCatching { binder?.unlinkToDeath(death, 0) }
            runCatching { context.unbindService(this) }
        }
        override fun onServiceDisconnected(name: ComponentName) = lost(CompanionState.DISCONNECTED)
        override fun onBindingDied(name: ComponentName) = lost(CompanionState.DISCONNECTED)
        override fun onNullBinding(name: ComponentName) = lost(CompanionState.DISCONNECTED)
    }
}

data class CompanionMetadata(val versionCode: Long, val capabilities: Int, val updateAvailable: Boolean) {
    fun has(capability: Int) = capabilities and capability == capability
}

internal fun AgentAccessibility.onRemoteEvent(event: com.eggman.backstage.companion.CompanionEvent) {
    if (event.type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
        if (event.packageName != service.packageName) {
            NotificationFeed.add(com.eggman.backstage.tools.NotificationEntry(
                event.packageName, event.appLabel, event.text, event.timeMs,
            ))
        }
    } else if (event.displayId != android.view.Display.DEFAULT_DISPLAY || event.packageName != service.packageName) {
        observeWindowEvent(event.displayId, event.type, event.packageName)
    }
}
