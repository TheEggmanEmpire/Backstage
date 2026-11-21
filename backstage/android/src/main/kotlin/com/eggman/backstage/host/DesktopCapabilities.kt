package com.eggman.backstage.host

import java.util.Locale

/**
 * Read-only device hints for desktop creation. Identity selects an attempt order, never a promise that DeX,
 * DisplayPort, freeform, trusted displays or locked mode work. Only the created display and task state prove that.
 * No Samsung extension, global setting write or external monitor is required by the detector.
 */
object DesktopCapabilities {
    enum class Family { SAMSUNG, PIXEL, GENERIC }

    data class Facts(
        val manufacturer: String?,
        val model: String?,
        val sdk: Int,
        val freeformFeature: Boolean,
        val connectedDisplays: Int = 0,
    )

    data class Profile(
        val family: Family,
        val sdk: Int,
        val freeformAdvertised: Boolean,
        val externalDisplayPresent: Boolean,
    ) {
        val nativeDesktop: Boolean get() = sdk >= 36
        val description: String get() = "${family.name.lowercase(Locale.ROOT)} / Android API $sdk / " +
            (if (nativeDesktop) "native desktop candidate" else "compat desktop") + " / " +
            (if (externalDisplayPresent) "connected display" else "phone only") + " / freeform " +
            (if (freeformAdvertised) "advertised" else "not advertised (runtime decides)")
    }

    fun detect(facts: Facts): Profile {
        val maker = facts.manufacturer.orEmpty().trim().lowercase(Locale.ROOT)
        val model = facts.model.orEmpty().trim().lowercase(Locale.ROOT)
        val family = when {
            maker == "samsung" -> Family.SAMSUNG
            maker == "google" && (model == "pixel" || model.startsWith("pixel ")) -> Family.PIXEL
            else -> Family.GENERIC
        }
        return Profile(family, facts.sdk, facts.freeformFeature, facts.connectedDisplays > 0)
    }

    // VirtualDisplay flags, not Display.flags (the latter use different bits). Keep the upstream base order.
    private const val PUBLIC = 1
    private const val PRESENTATION = 2
    private const val OWN_CONTENT_ONLY = 8
    private const val TRUSTED = 1024
    private const val PUBLIC_CONTENT = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY

    /**
     * Samsung retains the existing ordered ladder byte for byte. Pixel/AOSP and other devices try each existing
     * optional bundle against every trusted base before giving up that bundle: e.g. a device that refuses touch or
     * decorations may still accept an independent, unlocked group. No optional flag reaches an untrusted display.
     * Undecorated displays keep only upstream's trusted rungs: refusal falls back to its overlay session.
     * The current four bases and five optional bundles yield at most 24 attempts, deduplicated. Native Android 16
     * uses the same conservative AOSP order until a real device provides evidence for a narrower override.
   */
    fun flagLadder(profile: Profile, bases: IntArray, extras: IntArray): IntArray {
        if (bases.isEmpty()) return bases
        val original = extras.map { bases[0] or it } + bases.toList()
        if (profile.family == Family.SAMSUNG || bases.any { it and PUBLIC_CONTENT != PUBLIC_CONTENT }) {
            return original.toIntArray()
        }
        return buildList {
            extras.forEach { extra -> bases.filter { it and TRUSTED != 0 }.forEach { add(it or extra) } }
            addAll(bases.toList())
        }.distinct().toIntArray()
    }
}

/** The desktop's geometry comes only from its owned session, never from a default/external-display fallback. */
object DesktopDisplayPolicy {
    fun geometry(displayId: Int, width: Int, height: Int, densityDpi: Int): DesktopHost.Geometry? =
        if (displayId > 0 && width > 0 && height > 0 && densityDpi > 0) {
            DesktopHost.Geometry(displayId, width, height, densityDpi)
        } else null

    /** Rechecked on the ordered input lane: a queued event cannot target a missing/replaced desktop or display 0. */
    fun inputTarget(displayId: Int, currentDisplayId: Int?): Boolean = displayId > 0 && displayId == currentDisplayId
}
