package com.eggman.backstage.ui.minichat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.ui.AgentViewModel
import com.eggman.backstage.ui.chat.ChatInputRow
import com.eggman.backstage.ui.chat.ChatTargetRow
import com.eggman.backstage.ui.chat.EntryRow
import com.eggman.backstage.ui.chat.QuestionBar
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.host.DesktopHost
import com.eggman.backstage.host.DesktopHostExtras
import com.eggman.backstage.notify.SessionNotification
import com.eggman.backstage.shell.ShellLifecycle
import com.eggman.backstage.ui.chat.Transcript
import com.eggman.backstage.ui.theme.BackstageTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * The chat panel of the desktop popup (the MINI window's chat button): a compact bottom sheet over whatever the phone
 * shows with the SAME controls as the in-app chat : the same [AgentViewModel] and composables, i.e. the
 * target chips (one shared current target), Plan, microphone, send, clear, the step list and results, plus a Stop
 * button that stops the agent (not the desktop). Messages are labelled "Popup chat" ([Source.POPUP_CHAT]); text sent
 * while a task runs becomes an instruction for it; a locked phone sends and stops nothing.
 *
 * Design: an activity, not an overlay window. The popup is an accessibility overlay that never takes focus; an
 * ordinary (translucent, full-screen, own task, not in recents) activity gets the IME, Back, TalkBack and rotation from
 * the platform. It is marked [ShellLifecycle.OverThePhone], so opening it is not "the app is opened": the desktop
 * stays the popup and the pop-out-on-leaving rules ignore it. It reports its top edge to the host
 * ([DesktopHostExtras.setMiniPanelTop]); the popup moves above it (and above the keyboard) and returns when it closes.
 * Leaving it (Home, another app, screen off, the desktop going full screen or into the app) closes it.
 *
 * The floating bubble opens the same sheet ([bubbleIntent]): messages are labelled "Bubble"
 * ([com.eggman.backstage.overlay.BubbleModel.SOURCE]), the bubble's extras (Open Backstage, quick actions, desktop
 * popup toggles) are added, voice can start right away (long-press on the bubble, the assistant entry) and a finished
 * task can open it without the keyboard ("peek", closing itself after 5 s when the user does not touch it). From the
 * bubble only full screen closes it (the bubble is also shown over the app). While any chat sheet shows, the bubble
 * hides ([showing]).
 *
 * The DeX popup next to the sheet ([ShellLifecycle.onBubbleChatOpened] / [ShellLifecycle.onSheetShown]): the
 * bubble's sheet opened with a DeX desktop or Auto target brings a background desktop up as the popup (or starts one as
 * the popup when shell access is there; otherwise it says why and chats on); the Phone target shows and starts nothing.
 * While any sheet shows, the Phone chip (or the agent moving its task to the phone) sends the popup to the background and
 * DeX/Auto brings it back at once. The popup keeps above the sheet and the keyboard by itself (the reported top edge).
 */
class MiniChatActivity : ComponentActivity(), ShellLifecycle.OverThePhone {
    private var reportedTop: Int? = null
    private var host: DesktopHost? = null
    internal lateinit var vm: AgentViewModel
        private set
    /** Opened from the floating bubble (or the assistant entry): source "Bubble" and the bubble's extras. */
    internal var fromBubble = false
        private set
    /** Interaction after a "peek" opening keeps the sheet open, as does typed text. */
    private var interacted = false
    private var peekClose: kotlinx.coroutines.Job? = null
    /** The voice capture waiting for the permission dialog sends what it hears (the bubble's long-press). */
    private var pendingVoiceSends = false

    private val hostListener = object : DesktopHost.Listener {
        override fun onDesktopChanged(geometry: DesktopHost.Geometry?, presentation: DesktopHost.Presentation) {
            val now = if (geometry == null) DesktopHost.Presentation.NONE else presentation
            if (!MiniChatModel.keepOpen(now, fromBubble)) runOnUiThread { if (!isFinishing) finish() }
        }
    }

    /** The microphone permission, asked like the in-app chat asks it; granted -> listen right away. */
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice(autoSubmit = pendingVoiceSends) else vm.lastSent.value = "Microphone permission is needed for voice input."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        fromBubble = intent?.getStringExtra(EXTRA_ORIGIN) == ORIGIN_BUBBLE
        val peek = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_PEEK, false) == true
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                // A result shown by itself must not pop the keyboard over the user's app.
                if (peek) WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN else WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
        host = AgentAccess.host()?.also { h ->
            h.addListener(hostListener)
            val now = runCatching { if (h.sessionActive()) h.presentation() else DesktopHost.Presentation.NONE }
                .getOrDefault(DesktopHost.Presentation.NONE)
            if (!MiniChatModel.keepOpen(now, fromBubble)) finish()
        }
        vm = ViewModelProvider(this)[AgentViewModel::class.java].also { m ->
            m.defaultSource = if (fromBubble) com.eggman.backstage.overlay.BubbleModel.SOURCE else Source.POPUP_CHAT
            m.instructWhileRunning = true
            m.sendGuard = { if (SessionNotification.deviceLocked(this)) MiniChatModel.LOCKED_NOTICE else null }
            // The panel can show over the keyguard: stopping needs the owner too (the notification's Stop is the
            // lock-screen way to stop).
            m.stopGuard = { if (SessionNotification.deviceLocked(this)) MiniChatModel.LOCKED_STOP_NOTICE else null }
            m.refreshTarget()
        }
        setContent {
            BackstageTheme {
                MiniChatPanel(
                    vm = vm, onMic = { mic(sendWhenHeard = false) }, onClose = ::finish, onTop = ::reportTop,
                    headerExtras = if (fromBubble) { { com.eggman.backstage.overlay.BubbleChatOpenApp(onOpen = ::openApp) } } else null,
                    extras = if (fromBubble) { { com.eggman.backstage.overlay.BubbleChatExtras(vm) } } else null,
                )
            }
        }
        if (savedInstanceState == null) startFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startFromIntent(intent)
    }

    /** Voice and the self-closing result of a bubble opening. */
    private fun startFromIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getStringExtra(EXTRA_ORIGIN) == ORIGIN_BUBBLE) {
            // with a DeX/Auto target the desktop shows as the popup above the sheet (Phone: nothing).
            val show: (String) -> Unit = { vm.lastSent.value = it }
            ShellLifecycle.onBubbleChatOpened(this, peek = intent.getBooleanExtra(EXTRA_PEEK, false), notice = show)?.let(show)
        }
        when (intent.getStringExtra(EXTRA_VOICE)) {
            VOICE_DRAFT -> mic(sendWhenHeard = false)
            VOICE_SEND -> mic(sendWhenHeard = true)
        }
        peekClose?.cancel()
        if (intent.getBooleanExtra(EXTRA_PEEK, false) && intent.getBooleanExtra(EXTRA_AUTO_CLOSE, false)) {
            interacted = false
            peekClose = lifecycleScope.launch {
                kotlinx.coroutines.delay(MiniChatModel.PEEK_CLOSE_MS)
                if (!interacted && vm.draft.value.isBlank() && !vm.listening.value && !isFinishing) finish()
            }
        } else interacted = true
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        interacted = true
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        _showing.value = true
        // While the sheet is on screen, a chip (or the agent) switching to the phone hides the popup and back shows it.
        ShellLifecycle.onSheetShown(fromBubble) { vm.lastSent.value = it }
    }

    /** The bubble's "Open Backstage": the app comes to the front (the normal app rules apply), the sheet closes. */
    private fun openApp() {
        runCatching {
            startActivity(Intent(this, com.eggman.backstage.ui.AgentScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        _showing.value = false
        ShellLifecycle.onSheetHidden()
        // Out of sight (Home, another app, screen off): the panel is done; the popup returns to its place.
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        host?.removeListener(hostListener)
        host = null
        if (!isChangingConfigurations) reportTop(null)
        super.onDestroy()
    }

    private fun reportTop(top: Int?) {
        if (top == reportedTop) return
        reportedTop = top
        runCatching { (AgentAccess.host() as? DesktopHostExtras)?.setMiniPanelTop(top) }
    }

    /**
     * [sendWhenHeard]: only the bubble's own long-press (a user gesture on our button) sends what it hears; the mic
     * button and the assistant entry (which any app can fire) put it into the field.
   */
    private fun mic(sendWhenHeard: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice(autoSubmit = sendWhenHeard)
        else {
            pendingVoiceSends = sendWhenHeard
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Sends [text] like the in-app chat (tests). */
    internal fun send(text: String) {
        vm.updateDraft(text)
        vm.send()
    }

    companion object {
        const val EXTRA_ORIGIN = "bs_origin"
        const val ORIGIN_BUBBLE = "bubble"
        const val EXTRA_VOICE = "bs_voice"
        const val VOICE_DRAFT = "draft"
        const val VOICE_SEND = "send"
        const val EXTRA_PEEK = "bs_peek"
        const val EXTRA_AUTO_CLOSE = "bs_auto_close"

        private val _showing = MutableStateFlow(false)
        /** A chat sheet is on screen (the floating bubble hides meanwhile). */
        val showing: StateFlow<Boolean> = _showing

        /** Own task, not in recents: never brings Backstage's Home task to the front. */
        fun intent(context: Context): Intent = Intent(context, MiniChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /**
         * The sheet as the floating bubble opens it. [voice]: null, [VOICE_DRAFT] or [VOICE_SEND]. [peek]: a finished
         * task shows its result (no keyboard); [autoClose]: then it closes after 5 s unless touched.
       */
        fun bubbleIntent(context: Context, voice: String? = null, peek: Boolean = false, autoClose: Boolean = false): Intent =
            intent(context).putExtra(EXTRA_ORIGIN, ORIGIN_BUBBLE).putExtra(EXTRA_VOICE, voice)
                .putExtra(EXTRA_PEEK, peek).putExtra(EXTRA_AUTO_CLOSE, autoClose)

        /** The popup's chat button (registered by the Dextop glue). Main thread. */
        @JvmStatic
        fun open(context: Context) {
            context.startActivity(intent(context))
        }
    }
}

@Composable
private fun MiniChatPanel(
    vm: AgentViewModel,
    onMic: () -> Unit,
    onClose: () -> Unit,
    onTop: (Int?) -> Unit,
    headerExtras: (@Composable () -> Unit)? = null,
    extras: (@Composable () -> Unit)? = null,
) {
    val agent by vm.agent.collectAsState()
    val transcript by vm.transcript.collectAsState()
    val shownNotice by vm.lastSent.collectAsState()
    val view = LocalView.current
    val current = agent.current
    val question = current?.question?.takeIf { current.status == TaskStatus.WAITING_FOR_USER }
    val listState = rememberLazyListState()
    LaunchedEffect(transcript.entries.size) { if (transcript.entries.isNotEmpty()) listState.scrollToItem(transcript.entries.size - 1) }

    Box(Modifier.fillMaxSize()) {
        // Above the panel: a tap closes it (like a bottom sheet); the popup itself floats above everything.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .onGloballyPositioned { coords ->
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    onTop(location[1] + coords.positionInWindow().y.toInt())
                }
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(MiniChatModel.TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        MiniChatModel.status(agent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                headerExtras?.invoke()
                com.eggman.backstage.ui.chat.ThreadButtons(vm)
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close chat") }
            }
            val showThreads by vm.showThreads.collectAsState()
            if (showThreads) {
                com.eggman.backstage.ui.chat.ThreadList(vm, Modifier.heightIn(max = 320.dp))
            } else if (transcript.entries.isNotEmpty()) {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(horizontal = 14.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(transcript.entries, key = { it.id }) { EntryRow(it) }
                }
            }
            if (question != null) QuestionBar(question.text, question.confirm, question.choices,
                onAnswer = { vm.answer(it, current.id, question.text) })
            extras?.invoke()
            ChatTargetRow(vm)
            ChatInputRow(vm, agent, onMic = onMic, onStopAgent = vm::stopAgent)
            shownNotice?.let {
                Text(it, Modifier.padding(horizontal = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
