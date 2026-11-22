package com.eggman.backstage.a11y.companion

/** Versioned deliberately: an incompatible companion fails closed with an update hint. */
object CompanionProtocol {
    const val VERSION = 2
    const val MIN_VERSION = 2
    const val CAP_SCREEN = 1
    const val CAP_SHELL = 2
    const val CAP_PACKAGES = 4

    fun supports(version: Int): Boolean = version >= MIN_VERSION
    const val PLAY_PACKAGE = "com.eggman.backstage.play"
    const val PACKAGE = "com.eggman.backstage.agent"
    const val SERVICE = "com.eggman.backstage.companion.CompanionService"
    const val ACCESSIBILITY_SERVICE = "com.eggman.backstage.companion.AgentAccessibilityService"
    const val ACTIVITY = "com.eggman.backstage.companion.CompanionActivity"
    const val PERMISSION = "com.eggman.backstage.agent.permission.CONTROL"
    const val RELEASES = "https://github.com/TheEggmanEmpire/Backstage/releases"
    const val OFF = 0
    const val READY = 1
    const val CONSENT_NEEDED = 2
    const val CLICK = 1
    const val LONG_CLICK = 2
    const val SET_TEXT = 3
    const val SCROLL_FORWARD = 4
    const val SCROLL_BACKWARD = 5
    const val FOCUS = 6
    // Additive actions: an older companion rejects secret typing rather than losing its secret flag.
    const val SET_SECRET_TEXT = 7
    const val CLIPBOARD_UNRESTORED = 8
}

/** Only this opaque id crosses processes; it is meaningful solely to the latest capture of its session. */
data class RemoteNodeHandle(val id: Long)
