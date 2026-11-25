package com.eggman.backstage.init

/** The Google Play build ships no API-bypass library: hidden APIs keep the platform's normal policy. */
internal object HiddenApiAccess {
    fun exemptAll(): Boolean = false
}
