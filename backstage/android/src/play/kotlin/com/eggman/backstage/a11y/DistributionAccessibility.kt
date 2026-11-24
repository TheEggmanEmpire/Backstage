package com.eggman.backstage.a11y

import android.content.Context

internal object DistributionAccessibility {
    val status: String get() = CompanionConnection.state.value.message
    val hostAvailable = false
    val screenAvailable: Boolean get() = !CompanionConnection.required || AgentAccessibility.instance != null
    val companionCapabilities: Int get() = CompanionConnection.capabilities.value
    val companionOmp: Boolean get() = companionCapabilities and com.eggman.backstage.a11y.companion.CompanionProtocol.CAP_OMP != 0
    fun gaps(): List<String> = CompanionConnection.currentGaps()
    fun connect(context: Context) = CompanionConnection.connect(context)
}
