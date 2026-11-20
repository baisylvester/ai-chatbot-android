package com.embeddablechatbot.android.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.IdGenerator
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConnectivityMonitor
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Freshness { Loading, Current, Offline, Unavailable }

data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
    val freshness: Freshness = Freshness.Loading,
    val hasMore: Boolean = false,
    val creating: Boolean = false,
    val error: AppError? = null,
    val chatbot: ChatbotInfo? = null,
    val mailStatus: MailStatus? = null,
    val deletingId: String? = null,
)

sealed interface ListEffect {
    data class OpenConversation(val id: String) : ListEffect
}

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: ConversationRepository,
    private val chatbots: ChatbotRepository,
    private val mail: MailRepository,
    private val config: ConfigRepository,
    private val ids: IdGenerator,
    private val connectivity: ConnectivityMonitor,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val local = MutableStateFlow(ConversationListUiState())
    private val effects = Channel<ListEffect>(Channel.BUFFERED)
    val effect: Flow<ListEffect> = effects.receiveAsFlow()
    private val refreshLock = Mutex()

    /** Refreshes only while the screen is visible: on start, every 30 s, and on reconnect. */
    private val refresher: Flow<Unit> = channelFlow {
        launch { connectivity.online.collect { if (it) refresh() } }
        while (true) {
            refresh()
            send(Unit)
            delay(30_000)
        }
    }

    val state: StateFlow<ConversationListUiState> =
        combine(repository.observeConversations(), local, refresher) { conversations, ui, _ -> ui.copy(conversations = conversations) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationListUiState())

    fun refresh() {
        viewModelScope.launch {
            if (!refreshLock.tryLock()) return@launch
            try {
                val chatbotId = config.config.value.chatbotId
                val result = repository.refreshConversations()
                local.update {
                    when (result) {
                        is Outcome.Ok -> it.copy(freshness = Freshness.Current, hasMore = result.value, error = null)
                        is Outcome.Err -> it.copy(
                            freshness = if (result.error is AppError.Network) Freshness.Offline else Freshness.Unavailable,
                            error = result.error.takeUnless { e -> e is AppError.Network },
                        )
                    }
                }
                if (local.value.chatbot == null) (chatbots.chatbot(chatbotId) as? Outcome.Ok)?.let { r -> local.update { it.copy(chatbot = r.value) } }
                (mail.status(chatbotId) as? Outcome.Ok)?.let { r -> local.update { it.copy(mailStatus = r.value) } }
            } finally {
                refreshLock.unlock()
            }
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            refreshLock.withLock {
                when (val result = repository.loadMoreConversations()) {
                    is Outcome.Ok -> local.update { it.copy(hasMore = result.value) }
                    is Outcome.Err -> local.update { it.copy(error = result.error) }
                }
            }
        }
    }

    /**
     * Creates a conversation with a client-generated ID. The ID survives process death and is reused
     * after an uncertain result, so a retry can never create a duplicate.
     */
    fun newConversation() {
        if (local.value.creating) return
        val id = saved.get<String>(NEW_ID) ?: ids.newId().also { saved[NEW_ID] = it }
        local.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            val result = repository.createConversation(id)
            when (result) {
                is Outcome.Ok -> {
                    saved.remove<String>(NEW_ID)
                    effects.send(ListEffect.OpenConversation(result.value.id))
                }
                is Outcome.Err -> {
                    val uncertain = (result.error as? AppError.Network)?.uncertain == true || result.error is AppError.Server
                    if (!uncertain) saved.remove<String>(NEW_ID)
                }
            }
            local.update { it.copy(creating = false, error = (result as? Outcome.Err)?.error) }
        }
    }

    fun delete(id: String) {
        if (local.value.deletingId != null) return
        local.update { it.copy(deletingId = id) }
        viewModelScope.launch {
            val result = repository.deleteConversation(id)
            local.update { it.copy(deletingId = null, error = (result as? Outcome.Err)?.error) }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private companion object {
        const val NEW_ID = "pendingNewConversationId"
    }
}
