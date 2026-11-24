package com.eggman.backstage.companion

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.eggman.backstage.a11y.companion.CompanionProtocol
import com.eggman.backstage.companion.privilege.CompanionPrivilege
import com.eggman.backstage.companion.update.CompanionUpdates
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CompanionActivity : AppCompatActivity() {
    private lateinit var screenControlStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var more: LinearLayout
    private lateinit var moreToggle: MaterialButton
    private lateinit var revoke: MaterialButton
    private val stateChanged: () -> Unit = { runOnUiThread { refreshStatus() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.companion_activity)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.companion_scroll)) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        screenControlStatus = findViewById(R.id.companion_screen_control_status)
        connectionStatus = findViewById(R.id.companion_connection_status)
        more = findViewById(R.id.companion_more)
        moreToggle = findViewById(R.id.companion_more_toggle)
        moreToggle.setOnClickListener { expandMore(more.visibility != View.VISIBLE) }
        expandMore(savedInstanceState?.getBoolean("more_expanded") == true)
        listOf(R.id.companion_open_accessibility, R.id.companion_open_accessibility_again).forEach { id ->
            findViewById<MaterialButton>(id).setOnClickListener {
                if (CompanionRuntime.consent(this)) openAccessibilitySettings() else showConsent()
            }
        }
        findViewById<MaterialButton>(R.id.companion_back_to_backstage).setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(CompanionProtocol.PLAY_PACKAGE)
            if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else finish()
        }
        findViewById<MaterialButton>(R.id.companion_app_details).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        revoke = MaterialButton(this).apply {
            setText(R.string.companion_revoke)
            setOnClickListener { CompanionRuntime.setConsent(this@CompanionActivity, false) }
        }.also(more::addView)
        CompanionPrivilege.attach(this, findViewById(R.id.companion_shizuku_status), findViewById(R.id.companion_shizuku_grant), more)
        CompanionUpdates.attach(this, more)
        refreshStatus()
    }

    override fun onStart() {
        super.onStart()
        CompanionRuntime.stateListeners += stateChanged
        CompanionRuntime.clientListeners += stateChanged
        CompanionRuntime.uiStarted()
    }

    override fun onResume() {
        super.onResume()
        CompanionPrivilege.onResume(this)
        refreshStatus()
    }

    override fun onPause() {
        CompanionPrivilege.onPause(this)
        super.onPause()
    }

    override fun onStop() {
        CompanionRuntime.stateListeners -= stateChanged
        CompanionRuntime.clientListeners -= stateChanged
        CompanionRuntime.uiStopped()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("more_expanded", more.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun showConsent() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.companion_screen_control)
            .setMessage(R.string.companion_consent_message)
            .setNegativeButton(R.string.companion_consent_decline, null)
            .setPositiveButton(R.string.companion_consent_allow) { _, _ ->
                CompanionRuntime.setConsent(this, true)
                if (!accessibilityEnabled()) openAccessibilitySettings()
            }
            .show()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun expandMore(expanded: Boolean) {
        more.visibility = if (expanded) View.VISIBLE else View.GONE
        moreToggle.setText(if (expanded) R.string.companion_less else R.string.companion_more)
    }

    private fun accessibilityEnabled(): Boolean {
        val component = ComponentName(this, AgentAccessibilityService::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    private fun refreshStatus() {
        screenControlStatus.setText(when (CompanionRuntime.status(this)) {
            CompanionProtocol.READY -> R.string.companion_screen_control_enabled
            CompanionProtocol.CONSENT_NEEDED -> R.string.companion_confirmation_needed
            else -> if (accessibilityEnabled() && !CompanionRuntime.consent(this)) R.string.companion_confirmation_needed
                else R.string.companion_screen_control_disabled
        })
        connectionStatus.setText(if (CompanionRuntime.hasBackstageClient) R.string.companion_connected else R.string.companion_disconnected)
        revoke.visibility = if (CompanionRuntime.consent(this)) View.VISIBLE else View.GONE
    }
}
