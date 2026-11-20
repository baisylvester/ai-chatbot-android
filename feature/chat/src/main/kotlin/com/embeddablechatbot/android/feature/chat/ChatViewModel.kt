package com.embeddablechatbot.android.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.chat.LiveReply
import com.embeddablechatbot.android.core.domain.chat.MessageRules
import com.embeddablechatbot.android.core.domain.chat.SendCoordinator
import com.embeddablechatbot.android.core.domain.chat.SendStart
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PendingState
import com.embeddablechatbot.android.core.domain.model.Role
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConnectivityMonitor
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface ChatItem {
    val key: String

    data class Committed(val message: Message) : ChatItem { override val key = "m:${message.id}" }

    /** The user's own message whose outcome is not yet known, or which the server refused. */
    data class Pending(val pending: PendingSend) : ChatItem { override val key = "p:${pending.requestId}" }

    /** Provisional streamed text and tool activity; replaced by the committed reply. */
    data class Live(val reply: LiveReply) : ChatItem { override val key = "l:${reply.requestId}" }

    /** A turn that ended without a usable reply (failed or interrupted). */
    data class TurnNotice(val turn: Turn) : ChatItem { override val key = "t:${turn.requestId}" }
}

data class ChatUiState(
    val title: String = "",
    val items: List<ChatItem> = emptyList(),
    val draft: String = "",
    val freshness: Freshness = Freshness.Loading,
    val error: AppError? = null,
    val remoteReplyRunning: Boolean = false,
    val canSend: Boolean = false,
    val blockedByPending: Boolean = false,
    val chatbot: ChatbotInfo? = null,
    val knowledgeUnavailable: Boolean = false,
    val mailStatus: MailStatus? = null,
    val mailToolsAvailable: Boolean = false,
    val mailStatusError: AppError? = null,
    val notice: ChatNotice? = null,
) {
    val remaining: Int get() = MessageRules.MAX_CHARS - draft.trim().length
}

enum class ChatNotice { TooLong, Blocked }

sealed interface ChatEffect {
    data object ConversationGone : ChatEffect
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val coordinator: SendCoordinator,
    private val chatbots: ChatbotRepository,
    private val mail: MailRepository,
    private val preferences: PreferencesRepository,
    private val config: ConfigRepository,
    private val connectivity: ConnectivityMonitor,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val conversationId: String = checkNotNull(saved["conversationId"]) { "conversationId is required" }

    private val local = MutableStateFlow(ChatUiState(draft = saved[DRAFT] ?: ""))
    private val meta = MutableStateFlow<Conversation?>(null)
    private val effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effect: Flow<ChatEffect> = effects.receiveAsFlow()
    private val syncLock = Mutex()
    private val sendLock = Mutex()
    private var capabilitiesRefreshPending = false

    fun acceptAssistantInput(id: String, text: String) {
        if (saved.get<String>(CONSUMED_INPUT) == id) return
        val current = local.value.draft
        onDraft(if (current.isBlank()) text else "$current\n\n$text")
        saved[CONSUMED_INPUT] = id
    }

    /**
     * Active only while the screen is collected: syncs immediately (covers returning to the foreground),
     * then about every five seconds, and immediately after the network comes back.
     */
    private val poller: Flow<Unit> = channelFlow {
        launch { connectivity.online.collect { if (it) syncNow() } }
        var tick = 0
        while (true) {
            syncNow(refreshMail = tick % 6 == 0)
            send(Unit)
            tick++
            delay(5_000)
        }
    }

    private val live = coordinator.liveReplies.map { all -> all.values.filter { it.conversationId == conversationId } }

    val state: StateFlow<ChatUiState> = combine(
        combine(repository.observeMessages(conversationId), repository.observePendingSends(conversationId), live, ::Triple),
        meta,
        local,
        poller,
    ) { (messages, pending, liveReplies), conversation, ui, _ -> build(messages, pending, liveReplies, conversation, ui) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private fun build(
        messages: List<Message>,
        pending: List<PendingSend>,
        liveReplies: List<LiveReply>,
        conversation: Conversation?,
        ui: ChatUiState,
    ): ChatUiState {
        val committedIds = messages.mapNotNull { it.requestId }.toSet()
        val items = buildList {
            messages.forEach { add(ChatItem.Committed(it)) }
            pending.filter { it.requestId !in committedIds }.forEach { add(ChatItem.Pending(it)) }
            liveReplies.filter { it.requestId !in committedIds || messages.none { m -> m.requestId == it.requestId && m.role == Role.Assistant } }
                .forEach { add(ChatItem.Live(it)) }
            conversation?.lastTurn?.let { turn ->
                val answered = messages.any { it.requestId == turn.requestId && it.role == Role.Assistant && it.text.isNotBlank() }
                if ((turn.status == TurnStatus.Failed || turn.status == TurnStatus.Interrupted) && !answered) add(ChatItem.TurnNotice(turn))
            }
        }
        val unresolved = pending.firstOrNull()
        val ownLive = liveReplies.any()
        val mailStatus = ui.mailStatus
        return ui.copy(
            title = conversation?.title.orEmpty(),
            items = items,
            remoteReplyRunning = conversation?.lastTurn?.status == TurnStatus.Running && !ownLive && unresolved == null,
            blockedByPending = unresolved != null,
            canSend = unresolved == null && ui.draft.isNotBlank() && ui.draft.trim().length <= MessageRules.MAX_CHARS,
            mailToolsAvailable = mailStatus?.canUseMailTools == true,
        )
    }

    private fun syncNow(refreshMail: Boolean = false) {
        if (refreshMail) capabilitiesRefreshPending = true
        viewModelScope.launch {
            if (!syncLock.tryLock()) return@launch
            try {
                when (val result = repository.syncMessages(conversationId)) {
                    is Outcome.Ok -> {
                        meta.value = result.value
                        local.update { it.copy(freshness = Freshness.Current, error = null) }
                        coordinator.reconcile(conversationId)
                    }
                    is Outcome.Err -> when (result.error) {
                        is AppError.NotFound -> effects.send(ChatEffect.ConversationGone)
                        is AppError.Network -> local.update { it.copy(freshness = Freshness.Offline) }
                        else -> local.update { it.copy(error = result.error, freshness = Freshness.Unavailable) }
                    }
                }
                val refreshCapabilities = capabilitiesRefreshPending
                capabilitiesRefreshPending = false
                if (refreshCapabilities || local.value.mailStatus == null) refreshMailStatus()
                if (refreshCapabilities || local.value.chatbot == null) {
                    when (val result = chatbots.chatbot(config.config.value.chatbotId)) {
                        is Outcome.Ok -> local.update { it.copy(chatbot = result.value, knowledgeUnavailable = false) }
                        is Outcome.Err -> local.update { it.copy(chatbot = null, knowledgeUnavailable = true) }
                    }
                }
            } finally {
                syncLock.unlock()
            }
        }
    }

    private suspend fun refreshMailStatus() {
        when (val result = mail.status(config.config.value.chatbotId)) {
            is Outcome.Ok -> local.update { it.copy(mailStatus = result.value, mailStatusError = null) }
            is Outcome.Err -> local.update { it.copy(mailStatus = null, mailStatusError = result.error) }
        }
    }

    fun refresh() = syncNow(refreshMail = true)

    fun onDraft(value: String) {
        saved[DRAFT] = value
        local.update { it.copy(draft = value, notice = null) }
    }

    fun send() {
        if (!sendLock.tryLock()) return
        viewModelScope.launch {
            try {
                val s = state.value
                val stream = preferences.preferences.first().streamReplies
                when (val start = coordinator.send(conversationId, s.draft, s.mailToolsAvailable, stream)) {
                    is SendStart.Started -> onDraft("")
                    SendStart.Empty -> Unit
                    is SendStart.TooLong -> local.update { it.copy(notice = ChatNotice.TooLong) }
                    is SendStart.Blocked -> local.update { it.copy(notice = ChatNotice.Blocked) }
                }
            } finally {
                sendLock.unlock()
            }
        }
    }

    /** Same request ID and text: safe even if the first attempt actually ran. */
    fun retry(requestId: String) = viewModelScope.launch {
        coordinator.retry(requestId, preferences.preferences.first().streamReplies)
    }

    /** Only for sends the server definitely refused: moves the text back into the composer. */
    fun editRefused(requestId: String) = viewModelScope.launch {
        coordinator.discard(requestId)?.let { text -> onDraft(text) }
    }

    fun checkStatus() = viewModelScope.launch { coordinator.reconcile(conversationId) }

    fun dismissError() = local.update { it.copy(error = null) }

    companion object {
        const val ASSISTANT_INPUT = "assistantInput"
        const val ASSISTANT_INPUT_ID = "assistantInputId"
        private const val CONSUMED_INPUT = "consumedAssistantInputId"
        private const val DRAFT = "draft"
    }
}

internal fun PendingSend.isRetryable() = state == PendingState.NotAccepted
