package com.eggman.backstage.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Target of the wake word's Resume/Retry notification. The tap brings this invisible activity to the front, which
 * then starts the microphone service as a foreground app; the notification itself never starts the service.
 */
class WakeWordResumeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startForegroundService(Intent(this, WakeWordService::class.java)) }
        finish()
    }
}
