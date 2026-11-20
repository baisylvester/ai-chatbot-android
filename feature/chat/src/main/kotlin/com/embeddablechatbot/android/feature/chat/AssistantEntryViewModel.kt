package com.embeddablechatbot.android.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.IdGenerator
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class AssistantEntryState(val opening: Boolean = false, val error: AppError? = null)

/** Resolves the same private conversation used by chat, writing shortcuts and mailbox requests. */
@HiltViewModel
class AssistantEntryViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val mutable = MutableStateFlow(AssistantEntryState())
    val state = mutable.asStateFlow()
    private val opened = Channel<String>(Channel.BUFFERED)
    val effect = opened.receiveAsFlow()

    fun open() {
        if (mutable.value.opening) return
        mutable.value = AssistantEntryState(opening = true)
        viewModelScope.launch {
            // A prompt starts a fresh thread, preserving any draft in the previous conversation.
            val hasPrompt = !saved.get<String>("draft").isNullOrBlank()
            val pendingId = saved.get<String>(PENDING_ID)
            if (!hasPrompt && pendingId == null) {
                val refreshed = conversations.refreshConversations()
                val latest = conversations.observeConversations().first().maxByOrNull { it.updatedAt }
                if (latest != null) {
                    opened.send(latest.id)
                    return@launch
                }
                if (refreshed is Outcome.Err) {
                    mutable.value = AssistantEntryState(error = refreshed.error)
                    return@launch
                }
            }
            val id = pendingId ?: ids.newId().also { saved[PENDING_ID] = it }
            when (val result = conversations.createConversation(id)) {
                is Outcome.Ok -> {
                    saved.remove<String>(PENDING_ID)
                    opened.send(result.value.id)
                }
                is Outcome.Err -> {
                    val uncertain = (result.error as? AppError.Network)?.uncertain == true || result.error is AppError.Server
                    if (!uncertain) saved.remove<String>(PENDING_ID)
                    mutable.value = AssistantEntryState(error = result.error)
                }
            }
        }
    }

    private companion object { const val PENDING_ID = "pendingAssistantConversationId" }
}
