package com.embeddablechatbot.android.core.domain.repository

import com.embeddablechatbot.android.core.domain.model.Account
import com.embeddablechatbot.android.core.domain.model.ActionFilter
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.ConnectLaunch
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ConfigRepository {
    val config: StateFlow<PlatformConfig>
    val allowCleartext: Boolean
    suspend fun update(config: PlatformConfig)
}

interface AuthRepository {
    val authState: StateFlow<AuthState>

    /** Loads the stored session (if any) and refreshes it when expired. */
    suspend fun restore()
    suspend fun signIn(email: String, password: String): Outcome<Account>

    /** Revokes the server session (best effort) and deletes local credentials. */
    suspend fun signOut()
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
}

interface ChatbotRepository {
    suspend fun chatbot(chatbotId: String): Outcome<ChatbotInfo>
}

/**
 * Private conversations of the signed-in user for the configured chatbot. The server is authoritative;
 * the local cache only mirrors committed server state for this account.
 */
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>

    /** Reloads the first page from the server, replacing the cached list (removes other-device deletions). */
    suspend fun refreshConversations(): Outcome<Boolean>

    /** Appends the next page. Returns whether more pages remain. */
    suspend fun loadMoreConversations(): Outcome<Boolean>

    /** Idempotent: retry with the SAME [id] after an uncertain result. */
    suspend fun createConversation(id: String): Outcome<Conversation>
    suspend fun deleteConversation(id: String): Outcome<Unit>
    suspend fun getConversation(id: String): Outcome<Conversation>

    fun observeMessages(conversationId: String): Flow<List<Message>>

    /** Follows the retained cursor until `hasMore` is false and merges by message ID. */
    suspend fun syncMessages(conversationId: String): Outcome<Conversation>

    /** Request IDs that have committed messages in the synced transcript. */
    suspend fun committedRequestIds(conversationId: String): Set<String>

    /** Submits one user message. [onEvent] receives provisional stream events when [stream] is true. */
    suspend fun submitMessage(
        conversationId: String,
        requestId: String,
        text: String,
        emailManager: Boolean,
        stream: Boolean,
        onEvent: (SendEvent) -> Unit,
    ): Outcome<Turn>

    fun observePendingSends(conversationId: String): Flow<List<PendingSend>>
    suspend fun pendingSends(conversationId: String): List<PendingSend>
    suspend fun allPendingSends(): List<PendingSend>
    suspend fun savePendingSend(pending: PendingSend)
    suspend fun deletePendingSend(requestId: String)

    /** Conversation IDs created locally whose server creation is not yet confirmed. */
    suspend fun unconfirmedConversationIds(): List<String>
}

interface MailRepository {
    suspend fun status(chatbotId: String): Outcome<MailStatus>
    suspend fun startConnect(chatbotId: String, verifierChallenge: String): Outcome<ConnectLaunch>
    suspend fun completeConnect(transactionId: String, code: String, verifier: String): Outcome<MailStatus>
    suspend fun disconnect(): Outcome<Unit>
    suspend fun actions(filter: ActionFilter): Outcome<List<MailAction>>
    suspend fun actionDetail(id: String): Outcome<MailActionDetail>
    /** [sendAt] schedules an email for later instead of sending it now; the approval stays bound to [version]. */
    suspend fun approve(id: String, version: String, sendAt: java.time.Instant? = null): Outcome<MailAction>
    suspend fun reject(id: String): Outcome<MailAction>
    /** Returns a scheduled email to waiting for approval. */
    suspend fun unschedule(id: String): Outcome<MailAction>
}

/** Device-held state of an in-progress mailbox connection (encrypted at rest). */
interface PendingConnectStore {
    suspend fun save(pending: PendingConnect)
    suspend fun load(): PendingConnect?
    suspend fun clear()
}

/** Deletes everything scoped to the current account: caches, cursors, pending sends and selections. */
interface AccountDataCleaner {
    suspend fun clearAccountData()
}
