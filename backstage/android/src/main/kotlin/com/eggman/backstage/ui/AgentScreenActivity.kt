package com.eggman.backstage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import android.view.Display
import com.eggman.backstage.shell.ShellPage
import com.eggman.backstage.shell.shellWideLayout
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.entry.EntryRouter.Screen
import com.eggman.backstage.notify.TaskNotifier
import com.eggman.backstage.overlay.Overlay
import com.eggman.backstage.ui.chat.ChatScreen
import com.eggman.backstage.ui.desktop.DesktopScreen
import com.eggman.backstage.ui.history.HistoryScreen
import com.eggman.backstage.ui.licenses.LicensesScreen
import com.eggman.backstage.ui.onboarding.SetupScreen
import com.eggman.backstage.ui.schedule.ScheduleScreen
import com.eggman.backstage.ui.settings.SettingsScreen
import com.eggman.backstage.ui.theme.BackstageTheme
import com.eggman.backstage.voice.WakeWord

/**
 * The Agent tab of Backstage (opened from Home's "Agent" destination, the backstage://agent deep link, ASSIST, the share
 * target, PROCESS_TEXT, notifications, widgets and the bubble). Not a launcher entry: the one launcher icon opens
 * Dextop's Home. External intents only ever prefill the UI. Its fixed shell returns directly to Home, Linux or app Settings.
 */
class AgentScreenActivity : ComponentActivity() {
    private val vm: AgentViewModel by viewModels()
    private val updatesRequest = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            if (!vm.settings.onboardingDone) vm.screen.value = Screen.SETUP
            route(intent)
        }
        // Android may kill the app while Install unknown apps Settings is in front. Restore its settings card
        // (Compose saves the destination and pending action), so returning can re-check approval and continue.
        if (savedInstanceState?.getBoolean("backstage_settings") == true) vm.screen.value = Screen.SETTINGS
        setContent { BackstageTheme { AppRoot(vm, updatesRequest) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("backstage_settings", vm.screen.value == Screen.SETTINGS)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    override fun onResume() {
        super.onResume()
        TaskNotifier.appVisible = true
        // Foreground = allowed to (re)start the microphone service and the bubble.
        WakeWord.refresh(this)
        Overlay.refresh(this)
        // Refresh the last task's readiness banner because the lock state may differ.
        Thread { runCatching { com.eggman.backstage.entry.AgentAccess.controller()?.readiness() } }.start()
    }

    override fun onPause() {
        TaskNotifier.appVisible = false
        super.onPause()
    }

    private fun route(intent: Intent?) {
        if (intent == null) return
        val extras = com.eggman.backstage.automation.SafeExtras.read(
            com.eggman.backstage.automation.SafeExtras.of(intent), listOf(Intent.EXTRA_TEXT, Intent.EXTRA_SUBJECT,
                Intent.EXTRA_PROCESS_TEXT, EntryRouter.EXTRA_VOICE, EntryRouter.EXTRA_SCREEN, EntryRouter.EXTRA_TRUST, com.eggman.backstage.update.AppUpdates.EXTRA_OPEN))
        val action = EntryRouter.route(
            EntryRouter.Incoming(
                action = intent.action,
                dataUri = intent.dataString,
                type = intent.type,
                sendText = extras[Intent.EXTRA_TEXT] as? String,
                sendSubject = extras[Intent.EXTRA_SUBJECT] as? String,
                processText = extras[Intent.EXTRA_PROCESS_TEXT] as? String,
                voiceExtra = (extras[EntryRouter.EXTRA_VOICE] as? Boolean ?: false),
                screenExtra = extras[EntryRouter.EXTRA_SCREEN] as? String,
                // Only our own components (which carry the per-process token) may arm the microphone or open screens:
                // an exported activity cannot trust a plain MAIN intent from another app.
                internal = (intent.action == null || intent.action == Intent.ACTION_MAIN) &&
                    (extras[EntryRouter.EXTRA_TRUST] as? String) == EntryRouter.trustToken,
            ),
        )
        val source = when (intent.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> Source.ASSIST
            Intent.ACTION_SEND, Intent.ACTION_PROCESS_TEXT -> Source.SHARE
            else -> if ((extras[EntryRouter.EXTRA_VOICE] as? Boolean ?: false)) Source.TILE else null
        }
        if ((action == EntryRouter.Action.Voice || action == EntryRouter.Action.VoiceDraft) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Remember the entry (voice draft or our own auto-submitting tile); the permission callback starts listening.
            vm.awaitMicPermission(action, source)
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if ((extras[EntryRouter.EXTRA_TRUST] as? String) == EntryRouter.trustToken &&
            extras[com.eggman.backstage.update.AppUpdates.EXTRA_OPEN] == true) updatesRequest.value++
        vm.handle(action, source)
    }
}

/** Kept so prototype code that references `ui.MainActivity` keeps compiling (the core's notification). */
typealias MainActivity = AgentScreenActivity

/** Same task, no transition. The explicit page survives cold starts and reordered warm activities. */
internal fun openHome(activity: android.app.Activity, page: ShellPage = ShellPage.HOME) {
    val home = com.eggman.backstage.notify.SessionNotification.openAppIntent(activity)
        .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(ShellPage.EXTRA, page.wireName)
    runCatching {
        activity.startActivity(home)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}

@Composable
private fun AppRoot(vm: AgentViewModel, updateRequests: kotlinx.coroutines.flow.StateFlow<Int>) {
    val updatesRequest by updateRequests.collectAsState()
    val screen by vm.screen.collectAsState()
    val snack by vm.snackbar.collectAsState()
    val host = remember { SnackbarHostState() }
    LaunchedEffect(snack) {
        snack?.let { host.showSnackbar(it); vm.snackbar.value = null }
    }
    val activity = androidx.activity.compose.LocalActivity.current
    val sub = screen == Screen.SETUP || screen == Screen.LICENSES || screen == Screen.SKILLS
    BackHandler {
        if (screen == Screen.CHAT && activity != null) openHome(activity)
        else vm.screen.value = if (screen == Screen.LICENSES || screen == Screen.SKILLS) Screen.SETTINGS else Screen.CHAT
    }
    val configuration = LocalConfiguration.current
    val desktopWindow = runCatching { activity?.display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true }.getOrDefault(false) ||
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_DESK
    val wide = shellWideLayout(configuration.screenWidthDp, desktopWindow,
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    Scaffold(
        snackbarHost = { SnackbarHost(host) },
        bottomBar = {
            if (!sub && !wide) ShellNavigation(wide = false) { page -> activity?.let { openHome(it, page) } }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (!sub && wide) ShellNavigation(wide = true) { page -> activity?.let { openHome(it, page) } }
            Column(Modifier.weight(1f)) {
                if (!sub) AiTabs(screen) { vm.screen.value = it }
                val m = Modifier.weight(1f).fillMaxSize()
                when (screen) {
                    Screen.CHAT -> ChatScreen(vm, onOpenSetup = { vm.screen.value = Screen.SETUP }, modifier = m)
                    Screen.HISTORY -> HistoryScreen(vm, m)
                    Screen.DESKTOP -> DesktopScreen(m)
                    Screen.SCHEDULES -> ScheduleScreen(m)
                    Screen.SETTINGS -> SettingsScreen(vm.settings, openUpdatesRequest = updatesRequest, onOpenSetup = { vm.screen.value = Screen.SETUP },
                        onOpenAppSettings = { activity?.let { openHome(it, ShellPage.SETTINGS) } },
                        onOpenLicenses = { vm.screen.value = Screen.LICENSES }, onOpenSkills = { vm.screen.value = Screen.SKILLS }, modifier = m)
                    Screen.SETUP -> SetupScreen(onOpenSettings = { vm.screen.value = Screen.SETTINGS }, onDone = { vm.screen.value = Screen.CHAT }, modifier = m)
                    Screen.LICENSES -> LicensesScreen(m)
                    Screen.SKILLS -> com.eggman.backstage.ui.settings.SkillsScreen(onBack = { vm.screen.value = Screen.SETTINGS }, modifier = m)
                }
            }
        }
    }
}
