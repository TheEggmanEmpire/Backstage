package com.eggman.backstage.companion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.privilege.CompanionPrivilege
import com.eggman.backstage.companion.update.CompanionUpdates

/** One launcher entry. This screen never accepts command extras or automatically consents/enables a service. */
class CompanionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private val refresh = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1_000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }
    override fun onStart() { super.onStart(); CompanionRuntime.uiStarted() }
    override fun onStop() { CompanionRuntime.uiStopped(); super.onStop() }
    override fun onResume() { super.onResume(); refresh.run() }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun render() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12); content.addView(this)
        }
        fun button(label: String, action: () -> Unit) {
            content.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        text("Backstage Agent", 26f)
        status = text("")
        // Prominent, in-app disclosure precedes consent and the Android permission picker. It names the data,
        // purpose, recipient and off-device sharing; a link to a privacy policy alone would not be enough.
        text("Screen, notification and action access", 21f)
        text("Backstage Agent uses Android Accessibility to read text and app names on your phone and connected displays, " +
            "capture screenshots, and read notification titles and messages. This can include personal or sensitive information shown by other apps. " +
            "It performs taps, typing, scrolling, gestures and navigation so Backstage can carry out tasks you request, including AI-planned actions.")
        text("This companion shares that data and receives action requests only over a local connection with an approved Backstage app signed " +
            "with the same certificate. Backstage can send screen content, screenshots and task context to the AI provider you configure, " +
            "which may process them off this device. The companion uses the internet to check for and download its updates. Live nodes and event summaries are held " +
            "in memory; the companion does not save a screen or notification history.")
        text("Access continues while this service is enabled, including when its screen is closed. Enable it only if you trust your Backstage " +
            "installation and AI provider. You can decline now, revoke consent here, switch the service off in Android Accessibility settings, or uninstall this companion.")
        if (!CompanionRuntime.consent(this)) {
            button("I agree and continue") { CompanionRuntime.setConsent(this, true); render() }
            button("No thanks") { CompanionRuntime.setConsent(this, false); finish() }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                text(getString(R.string.companion_restricted_settings))
                button(getString(R.string.companion_open_app_info)) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
            button("Enable service in Android Settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            button("Revoke consent and stop access") { CompanionRuntime.setConsent(this, false); render() }
        }
        CompanionPrivilege.attach(this, content)
        CompanionUpdates.attach(this, content)
        setContentView(ScrollView(this).apply { addView(content) })
        refreshStatus()
    }
    private fun refreshStatus() {
        status?.text = when (CompanionRuntime.status(this)) {
            CompanionProtocol.READY -> "Status: service connected. Only the approved, same-signed Backstage app can request screen access."
            CompanionProtocol.CONSENT_NEEDED -> "Status: access stopped. Consent has not been granted."
            else -> "Status: consent granted; accessibility service is off. Enable Backstage Agent in Android Settings."
        }
        status?.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
}
