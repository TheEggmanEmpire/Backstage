package com.eggman.backstage.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.eggman.backstage.agent.api.AgentState
import com.eggman.backstage.agent.api.Source
import com.eggman.backstage.agent.api.Target
import com.eggman.backstage.agent.api.TaskRequest
import com.eggman.backstage.agent.api.TaskSnapshot
import com.eggman.backstage.agent.api.TaskStatus
import com.eggman.backstage.entry.AgentAccess
import com.eggman.backstage.entry.EntryRouter
import com.eggman.backstage.ui.chat.Transcript
import com.eggman.backstage.ui.settings.SettingsStores
import com.eggman.backstage.voice.ReplySpeaker
import com.eggman.backstage.voice.Speaker
import com.eggman.backstage.voice.SpeechCapture
import com.eggman.backstage.voice.SpeechResult
import com.eggman.backstage.voice.VoiceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, SavedStateHandle())

    val settings = SettingsStores.get(app)
    /** The composer's draft, shared with the other chat surface and persisted ([ComposerDraft]). */
    private val drafts = ComposerDrafts.get(app)
    /** Chat threads: the in-app chat and the popup chat show and continue the same current thread. */
    val threads: com.eggman.backstage.data.ChatThreadStore = com.eggman.backstage.data.ChatThreads.get(app)
    val threadId: StateFlow<String> get() = threads.currentId
    /** The chat history list is shown instead of the transcript. */
    val showThreads = MutableStateFlow(false)
    /** Bumped when the composer should take focus (a thread is reopened: type to continue it). */
    val composerFocus = MutableStateFlow(0)

    private val _agent = MutableStateFlow(AgentState())
    val agent: StateFlow<AgentState> = _agent.asStateFlow()

    private val _transcript = MutableStateFlow(Transcript())
    val transcript: StateFlow<Transcript> = _transcript.asStateFlow()

    private val _draft = MutableStateFlow("")
    /** The composer text; change it with [updateDraft]. */
    val draft: StateFlow<String> = _draft.asStateFlow()
    /** The chips: the ONE current target shared by every chat surface and the notification ([CurrentTarget]). */
    val target = MutableStateFlow(settings.defaultTarget)
    /** What the last send did (the popup chat shows it under its field), e.g. "Task started.". */
    val lastSent = MutableStateFlow<String?>(null)
    /** Source of messages typed here (the popup chat: [Source.POPUP_CHAT]); null = chat/voice. */
    var defaultSource: Source? = null
    /** Text sent while a task runs becomes an instruction for it (the popup chat), instead of a queued task. */
    var instructWhileRunning = false
    /** Why nothing may be sent (e.g. the phone is locked), or null. */
    var sendGuard: () -> String? = { null }
    /** Why the agent may not be stopped from here (the popup chat: the phone is locked), or null. */
    var stopGuard: () -> String? = { null }
    private val _planOverride = MutableStateFlow<Boolean?>(null)
    /** null = use the Plan Mode setting; change it with [setPlanOverride]. Part of the shared draft. */
    val planOverride: StateFlow<Boolean?> = _planOverride.asStateFlow()
    val listening = MutableStateFlow(false)
    val partial = MutableStateFlow<String?>(null)
    val snackbar = MutableStateFlow<String?>(null)
    val coreInstalled = MutableStateFlow(AgentAccess.controller() != null)
    val screen = MutableStateFlow(EntryRouter.Screen.CHAT)
    /** Source of the next submission when it is started by an entry point (assist, share, tile). */
    private var entrySource: Source? = null
    private var micJob: Job? = null
    /** The composer text came from speech since the last send: the replies are read aloud (ReplySpeaker). */
    private var draftFromVoice = false
    /** A voice entry waiting for the microphone permission (survives rotation with the ViewModel). */
    private var pendingVoice: EntryRouter.Action? = null

    private var loadedThread: String? = null

    init {
        // An activity's saved state wins when it is newer than the stored draft (a write lost with the process).
        ComposerDraft.decode(saved.get<String>(SAVED_DRAFT))?.let(drafts::restore)
        applyDraft(drafts.state.value)
        loadThread(threads.currentId.value)
        viewModelScope.launch { drafts.state.collect { applyDraft(it) } }
        viewModelScope.launch {
            threads.currentId.collect { id -> if (id != loadedThread) loadThread(id) }
        }
        viewModelScope.launch {
            while (AgentAccess.controller() == null) delay(500)
            coreInstalled.value = true
            AgentAccess.state().collect { st ->
                _agent.value = st
                if (belongsToThread(st.current)) _transcript.update { it.onSnapshot(st.current) }
            }
        }
    }

    private fun applyDraft(d: ComposerDraft) {
        _draft.value = d.text
        _planOverride.value = d.planOverride
        saved[SAVED_DRAFT] = d.encode()
    }

    /** Shows [id]'s stored transcript plus the live task when it belongs to that thread. */
    private fun loadThread(id: String) {
        loadedThread = id
        var t = Transcript.fromThread(threads.get(id))
        _agent.value.current?.takeIf { belongsToThread(it) }?.let { t = t.onSnapshot(it) }
        _transcript.value = t
    }

    /** A live task is shown in the current thread when it names that thread, or (voice, notification) names none. */
    private fun belongsToThread(s: TaskSnapshot?): Boolean {
        if (s == null) return false
        val t = s.request.attachments[com.eggman.backstage.agent.AgentControllerImpl.THREAD_KEY]
        if (t != null) return t == threads.currentId.value
        return s.request.source in CHAT_SOURCES
    }

    /** The composer text changed (typing, a chip, speech). An emptied field drops text another app put there. */
    fun updateDraft(text: String) {
        drafts.update { d -> if (text.isBlank()) d.copy(text = text, prefilled = null, attachments = emptyMap()) else d.copy(text = text) }
    }

    /** The user cleared the field. */
    fun clearDraft() = updateDraft("")

    fun setPlanOverride(value: Boolean?) {
        drafts.update { it.copy(planOverride = value) }
    }

    private fun sentDraft() {
        drafts.update { it.copy(text = "", prefilled = null, attachments = emptyMap()) }
    }

    // ---- chat threads ------------------------------------------------------------------------------

    /** "New chat": a new, empty thread; the old one stays in the chat history. */
    fun newChat() {
        threads.newThread()
        showThreads.value = false
    }

    /** Reopens a thread: its transcript is shown and the next message continues it. */
    fun openThread(id: String) {
        if (!threads.select(id)) return
        showThreads.value = false
        screen.value = EntryRouter.Screen.CHAT
        composerFocus.value += 1
    }

    fun renameThread(id: String, title: String) = threads.rename(id, title)

    fun deleteThread(id: String) = threads.delete(id)

    /** Remember a voice entry until the microphone permission is answered. */
    fun awaitMicPermission(action: EntryRouter.Action, source: Source?) {
        pendingVoice = action
        handle(EntryRouter.Action.Open(EntryRouter.Screen.CHAT), source)
    }

    /**
     * The permission dialog is answered. Granting it is not consent to run what the microphone hears: only our
     * own tile/widget entry ([EntryRouter.Action.Voice]) auto-submits, as it would have without the dialog.
   */
    fun onMicPermission(granted: Boolean) {
        val action = pendingVoice ?: return
        pendingVoice = null
        if (granted) startVoice(autoSubmit = EntryRouter.autoSubmits(action))
    }

    fun handle(action: EntryRouter.Action, source: Source? = null) {
        entrySource = source
        when (action) {
            EntryRouter.Action.None -> Unit
            EntryRouter.Action.Voice -> {
                screen.value = EntryRouter.Screen.CHAT
                startVoice(autoSubmit = true)
            }
            EntryRouter.Action.VoiceDraft -> {
                // Started by another app or a link: never run what the microphone hears without a tap on Send.
                screen.value = EntryRouter.Screen.CHAT
                startVoice(autoSubmit = false)
            }
            is EntryRouter.Action.Open -> screen.value = action.screen
            is EntryRouter.Action.Prefill -> {
                screen.value = EntryRouter.Screen.CHAT
                drafts.update {
                    it.copy(text = action.text, prefilled = action.text, attachments = action.attachment?.let { a -> mapOf(a) } ?: emptyMap())
                }
                snackbar.value = "Review the text, then press send"
            }
        }
    }

    fun send(fromVoice: Boolean = false) = send(fromVoice, shownTask = null)

    /** The composer passes its rendered snapshot when its text could answer an approval. */
    fun send(fromVoice: Boolean, shownTask: TaskSnapshot?) {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        val d = drafts.state.value
        sendGuard()?.let { lastSent.value = it; return }
        val spoken = fromVoice || draftFromVoice
        draftFromVoice = false
        val current = _agent.value.current
        when (com.eggman.backstage.ui.Composer.mode(current)) {
            com.eggman.backstage.ui.Composer.Mode.ANSWER -> {
                if (current?.question?.confirm == true) {
                    val shown = shownTask?.question?.takeIf { shownTask.status == TaskStatus.WAITING_FOR_USER && it.confirm }
                    if (shown == null) {
                        snackbar.value = "Review the current question in Backstage before approving it."
                        return
                    }
                    answer(text, shownTask.id, shown.text)
                } else answer(text)
                return
            }
            com.eggman.backstage.ui.Composer.Mode.RESUME -> { resumeWith(text); lastSent.value = "Instruction added; the task continues."; return }
            com.eggman.backstage.ui.Composer.Mode.NEW_TASK -> Unit
        }
        if (instructWhileRunning && current != null && current.status == TaskStatus.RUNNING &&
            AgentAccess.instruct(current.id, text)
        ) {
            _transcript.update { it.onAnswer(text) }
            sentDraft()
            lastSent.value = "Instruction added to the running task."
            return
        }
        val handle = AgentAccess.submit(
            TaskRequest(
                prompt = text,
                source = entrySource ?: defaultSource ?: if (fromVoice) Source.VOICE else Source.CHAT,
                // The chips' target, unless the message itself names the other screen ("on my phone", "on DeX").
                target = com.eggman.backstage.entry.CurrentTarget.forMessage(target.value, text),
                planMode = planOverride.value,
                // The thread this message continues: the agent picks up that conversation.
                attachments = d.attachments + (com.eggman.backstage.agent.AgentControllerImpl.THREAD_KEY to threads.currentId.value),
                timeoutMs = settings.taskTimeLimitMin.takeIf { it > 0 }?.let { it * 60_000L },
                waiverText = ownWords(text, d.prefilled),
            ),
        )
        if (handle == null) {
            snackbar.value = "The agent core is not installed in this build"
            return
        }
        if (spoken) ReplySpeaker.markVoice(handle.id)
        lastSent.value = "Task started."
        sentDraft()
        entrySource = null
    }

    fun answer(text: String) {
        val current = _agent.value.current ?: return
        val question = current.question?.takeIf { current.status == TaskStatus.WAITING_FOR_USER } ?: return
        if (question.confirm) {
            snackbar.value = "Review the current question in Backstage before approving it."
            return
        }
        answer(text, current.id, question.text)
    }

    /** Buttons pass the snapshot they rendered, so a stale view cannot approve a newer question. */
    fun answer(text: String, taskId: String, completeQuestionText: String) {
        sendGuard()?.let { lastSent.value = it; return }
        if (!AgentAccess.answerFromUi(text, taskId, completeQuestionText)) {
            snackbar.value = "This question is no longer current. Review the current question before answering."
            return
        }
        _transcript.update { it.onAnswer(text) }
        sentDraft()
        lastSent.value = "Answer sent."
    }

    fun stop() = AgentAccess.cancel(_agent.value.current?.id)

    /** The popup chat's Stop: the running task and the queue; the desktop keeps running. */
    fun stopAgent() {
        stopGuard()?.let { lastSent.value = it; return }
        com.eggman.backstage.agent.SessionStop.stopAgent(AgentAccess.controller())
        lastSent.value = com.eggman.backstage.ui.minichat.MiniChatModel.STOPPED_NOTICE
    }


    /** Picks up a target chosen on another surface (the chips are one shared setting). */
    fun refreshTarget() {
        target.value = settings.defaultTarget
    }

    /** Resume the paused task with [text] as an added instruction (the same task and conversation continue). */
    fun resumeWith(text: String) {
        val s = _agent.value.current ?: return
        if (AgentAccess.resume(s.id, text)) {
            _transcript.update { it.onAnswer(text) }
            sentDraft()
        } else snackbar.value = "The task is no longer paused"
    }

    /** Pause a running task between steps, or let a paused one continue. */
    fun togglePause() {
        val s = _agent.value.current ?: return
        if (s.paused) AgentAccess.resume(s.id) else AgentAccess.pause(s.id)
    }


    fun startVoice(autoSubmit: Boolean) {
        if (micJob?.isActive == true) {
            micJob?.cancel()
            return
        }
        micJob = viewModelScope.launch {
            Speaker.stop()
            VoiceSession.active.value = true
            try {
                val r = SpeechCapture.listenOnce(
                    getApplication(), settings.preferOnDeviceStt,
                    onPartial = { partial.value = it }, onListening = { listening.value = it },
                )
                when (r) {
                    is SpeechResult.Text -> {
                        updateDraft(r.text)
                        draftFromVoice = true
                        if (autoSubmit) send(fromVoice = true)
                    }
                    is SpeechResult.Error -> snackbar.value = r.message
                    SpeechResult.NoMatch -> snackbar.value = "Didn't catch that — try again"
                }
            } finally {
                partial.value = null
                listening.value = false
                VoiceSession.active.value = false
            }
        }
    }

    suspend fun history(): List<TaskSnapshot> = runCatching { AgentAccess.controller()?.history(100) }.getOrNull().orEmpty()

    fun rerun(s: TaskSnapshot) {
        updateDraft(s.request.prompt)
        target.value = s.request.target
        screen.value = EntryRouter.Screen.CHAT
    }

    /** A chip: the current target for every surface (persisted, [CurrentTarget]). */
    fun setTarget(t: Target) {
        target.value = t
        settings.defaultTarget = t
        com.eggman.backstage.shell.ShellLifecycle.onTargetChosen(t)
    }

    companion object {
        private const val SAVED_DRAFT = "composer_draft"
        /** Sources whose tasks join the current chat thread (as the controller's chat sources). */
        private val CHAT_SOURCES = setOf(Source.CHAT, Source.VOICE, Source.WAKE_WORD, Source.OVERLAY, Source.TILE, Source.WIDGET,
            Source.ASSIST, Source.NOTIFICATION, Source.POPUP_CHAT)

        /**
         * The part of [text] the user typed or spoke: without the [prefilled] text another app or a link supplied.
         * null (no waiver) when that text is edited and cannot be told apart from the user's own.
       */
        fun ownWords(text: String, prefilled: String?): String? {
            val p = prefilled?.trim()?.takeIf { it.isNotEmpty() } ?: return text
            val at = text.indexOf(p)
            if (at < 0) return null
            return text.removeRange(at, at + p.length).trim()
        }
    }
}
