package com.eggman.backstage.mcp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible activity that turns the screen on (and shows over the keyguard) when a task or an
 * MCP client needs the device while the screen is off, then closes itself.
 */
class WakeScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 1_200)
    }
}
