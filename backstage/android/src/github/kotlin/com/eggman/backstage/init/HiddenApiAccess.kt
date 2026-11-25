package com.eggman.backstage.init

import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Process-wide hidden-API exemption for the IInputManager / IDisplayManager reflection. */
internal object HiddenApiAccess {
    fun exemptAll(): Boolean = HiddenApiBypass.addHiddenApiExemptions("")
}
