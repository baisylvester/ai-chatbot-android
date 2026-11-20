package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.auth.AccessTokenProvider
import com.embeddablechatbot.android.core.data.db.ChatDao
import com.embeddablechatbot.android.core.data.db.ConversationEntity
import com.embeddablechatbot.android.core.data.db.ListStateEntity
import com.embeddablechatbot.android.core.data.db.MessageEntity
import com.embeddablechatbot.android.core.data.db.PendingSendEntity
import com.embeddablechatbot.android.core.data.db.SyncStateEntity
import com.embeddablechatbot.android.core.data.db.TransactionRunner
import com.embeddablechatbot.android.core.data.db.UnconfirmedConversationEntity
import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.data.network.Authed
import com.embeddablechatbot.android.core.data.network.CitationDto
import com.embeddablechatbot.android.core.data.network.ClientApi
import com.embeddablechatbot.android.core.data.network.TurnDto
import com.embeddablechatbot.android.core.data.network.toDomain
import com.embeddablechatbot.android.core.data.network.toDto
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PendingState
import com.embeddablechatbot.android.core.domain.model.Role
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/** Identifies the cache namespace of the signed-in account for the configured platform and chatbot. */
@Singleton
class AccountKeys @Inject constructor(private val tokens: AccessTokenProvider, private val config: ConfigRepository) {
    fun current(): String? {
        val user = tokens.currentUserId() ?: return null
        val cfg = config.config.value
        return "$user|${cfg.platformUrl.trim().trimEnd('/')}|${cfg.chatbotId.trim()}"
    }

    val epoch: Long get() = tokens.epoch
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val api: ClientApi,
    private val dao: ChatDao,
    private val transaction: TransactionRunner,
    private val keys: AccountKeys,
    private val config: ConfigRepository,
    private val clock: Clock,
    auth: AuthRepository,
) : ConversationRepository {
    // Account changes are observed through the token provider's epoch; flows re-key on config/auth changes.
    private val accountKeyFlow: Flow<String?> = combine(config.config, auth.authState) { _, _ -> keys.current() }.distinctUntilChanged()
    private val syncLocks = mutableMapOf<String, Mutex>()
    private val listLock = Mutex()
    private val citationList = ListSerializer(CitationDto.serializer())

    private fun lockFor(conversationId: String) = synchronized(syncLocks) { syncLocks.getOrPut(conversationId) { Mutex() } }

    /** Writes only if the account that issued the request is still the active one. */
    private suspend fun <T> guarded(key: String, epoch: Long, block: suspend () -> T): T? =
        if (keys.epoch == epoch && keys.current() == key) block() else null

    override fun observeConversations(): Flow<List<Conversation>> = accountKeyFlow.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else dao.observeConversations(key).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun refreshConversations(): Outcome<Boolean> = listLock.withLock {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        retryUnconfirmed(key)
        when (val result = api.listConversations(config.config.value.chatbotId, null)) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val page = result.value.value.toDomain()
                guarded(key, result.value.epoch) {
                    transaction {
                        replaceWindow(key, page.conversations, page.nextCursor == null)
                        dao.upsertListState(ListStateEntity(key, page.nextCursor, clock.now().toEpochMilli()))
                    }
                }
                Outcome.Ok(page.nextCursor != null)
            }
        }
    }

    override suspend fun loadMoreConversations(): Outcome<Boolean> = listLock.withLock {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        val cursor = dao.listState(key)?.nextCursor ?: return Outcome.Ok(false)
        when (val result = api.listConversations(config.config.value.chatbotId, cursor)) {
            is Outcome.Err -> result
            is Outcome.Ok -> {
                val page = result.value.value.toDomain()
                guarded(key, result.value.epoch) {
                    transaction {
                        dao.upsertConversations(page.conversations.map { it.toEntity(key) })
                        dao.upsertListState(ListStateEntity(key, page.nextCursor, clock.now().toEpochMilli()))
                    }
                }
                Outcome.Ok(page.nextCursor != null)
            }
        }
    }

    /**
     * Replaces the cached rows covered by this page (newest first). Conversations missing from that
     * window were deleted on another device and are removed with their transcript.
     */
    private suspend fun replaceWindow(key: String, page: List<Conversation>, complete: Boolean) {
        val oldest = page.minOfOrNull { it.createdAt.toEpochMilli() }
        val keep = page.map { it.id }.toSet()
        val unconfirmed = dao.unconfirmed(key).map { it.id }.toSet()
        val removed = dao.conversations(key)
            .filter { it.id !in keep && it.id !in unconfirmed && (complete || oldest == null || it.createdAt >= oldest) }
            .map { it.id }
        if (removed.isNotEmpty()) removeLocally(key, removed)
        dao.upsertConversations(page.map { it.toEntity(key) })
    }

    private suspend fun removeLocally(key: String, ids: List<String>) {
        dao.deleteConversations(key, ids)
        dao.deleteMessages(key, ids)
        dao.deleteSyncStates(key, ids)
        dao.deletePendingFor(key, ids)
    }

    private suspend fun retryUnconfirmed(key: String) {
        for (item in dao.unconfirmed(key)) createConversation(item.id)
    }

    override suspend fun createConversation(id: String): Outcome<Conversation> {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        dao.upsertUnconfirmed(UnconfirmedConversationEntity(key, id, clock.now().toEpochMilli()))
        return when (val result = api.createConversation(id, config.config.value.chatbotId)) {
            is Outcome.Ok -> {
                val conversation = result.value.value.toDomain()
                guarded(key, result.value.epoch) {
                    transaction {
                        dao.upsertConversations(listOf(conversation.toEntity(key)))
                        dao.deleteUnconfirmed(key, id)
                    }
                }
                Outcome.Ok(conversation)
            }
            is Outcome.Err -> {
                // Only a definite refusal forgets the ID; an uncertain result keeps it for a retry with the same ID.
                val definite = result.error !is AppError.Network || !(result.error as AppError.Network).uncertain
                if (definite && result.error !is AppError.Network && result.error !is AppError.Server) dao.deleteUnconfirmed(key, id)
                result
            }
        }
    }

    override suspend fun deleteConversation(id: String): Outcome<Unit> {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        return when (val result = api.deleteConversation(id)) {
            is Outcome.Ok -> {
                guarded(key, result.value.epoch) { transaction { removeLocally(key, listOf(id)) } }
                Outcome.Ok(Unit)
            }
            is Outcome.Err -> {
                if (result.error is AppError.NotFound) transaction { removeLocally(key, listOf(id)) }
                if (result.error is AppError.NotFound) Outcome.Ok(Unit) else result
            }
        }
    }

    override suspend fun getConversation(id: String): Outcome<Conversation> {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        return when (val result = api.conversation(id)) {
            is Outcome.Ok -> {
                val conversation = result.value.value.toDomain()
                guarded(key, result.value.epoch) { dao.upsertConversations(listOf(conversation.toEntity(key))) }
                Outcome.Ok(conversation)
            }
            is Outcome.Err -> {
                if (result.error is AppError.NotFound) transaction { removeLocally(key, listOf(id)) }
                result
            }
        }
    }

    override fun observeMessages(conversationId: String): Flow<List<Message>> = accountKeyFlow.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else dao.observeMessages(key, conversationId).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun syncMessages(conversationId: String): Outcome<Conversation> = lockFor(conversationId).withLock {
        val key = keys.current() ?: return Outcome.Err(AppError.Unauthorized)
        var cursor = dao.syncState(key, conversationId)?.cursor ?: "0"
        var latest: Conversation? = null
        // Bounded so a misbehaving server cannot loop forever; 50 pages = 5,000 messages per sync.
        repeat(MAX_PAGES) {
            when (val result = api.messages(conversationId, cursor)) {
                is Outcome.Err -> {
                    if (result.error is AppError.NotFound) transaction { removeLocally(key, listOf(conversationId)) }
                    // Pages already stored stay stored (their cursor was saved); an incomplete sync is reported as failed.
                    return result
                }
                is Outcome.Ok -> {
                    val page = result.value.value.toDomain()
                    guarded(key, result.value.epoch) {
                        transaction {
                            dao.upsertMessages(page.messages.map { it.toEntity(key) })
                            dao.upsertConversations(listOf(page.conversation.toEntity(key)))
                            // The cursor advances only over messages actually returned and stored.
                            dao.upsertSyncState(SyncStateEntity(key, conversationId, page.nextCursor, clock.now().toEpochMilli()))
                        }
                    } ?: return Outcome.Err(AppError.Unauthorized)
                    latest = page.conversation
                    if (!page.hasMore || page.nextCursor == cursor) {
                        evictOldTranscripts(key, conversationId)
                        return Outcome.Ok(page.conversation)
                    }
                    cursor = page.nextCursor
                }
            }
        }
        Outcome.Ok(latest!!)
    }

    /** Keeps transcripts only for recently opened conversations to limit how much email-derived text stays on the device. */
    private suspend fun evictOldTranscripts(key: String, current: String) {
        val stale = dao.staleTranscripts(key, KEEP_TRANSCRIPTS).filter { it != current }
        if (stale.isNotEmpty()) transaction {
            dao.deleteMessages(key, stale)
            dao.deleteSyncStates(key, stale)
        }
    }

    override suspend fun committedRequestIds(conversationId: String): Set<String> {
        val key = keys.current() ?: return emptySet()
        return dao.requestIds(key, conversationId).toSet()
    }

    override suspend fun submitMessage(
        conversationId: String,
        requestId: String,
        text: String,
        emailManager: Boolean,
        stream: Boolean,
        onEvent: (SendEvent) -> Unit,
    ): Outcome<Turn> {
        val result: Outcome<Authed<Turn>> = if (stream) {
            api.sendStream(conversationId, requestId, text, emailManager, onEvent)
        } else {
            api.sendJson(conversationId, requestId, text, emailManager)
        }
        return when (result) {
            is Outcome.Ok -> Outcome.Ok(result.value.value)
            is Outcome.Err -> result
        }
    }

    override fun observePendingSends(conversationId: String): Flow<List<PendingSend>> = accountKeyFlow.flatMapLatest { key ->
        if (key == null) flowOf(emptyList()) else dao.observePending(key, conversationId).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun pendingSends(conversationId: String): List<PendingSend> =
        keys.current()?.let { key -> dao.pending(key, conversationId).map { it.toDomain() } }.orEmpty()

    override suspend fun allPendingSends(): List<PendingSend> =
        keys.current()?.let { key -> dao.allPending(key).map { it.toDomain() } }.orEmpty()

    override suspend fun savePendingSend(pending: PendingSend) {
        val key = keys.current() ?: return
        dao.upsertPending(pending.toEntity(key))
    }

    override suspend fun deletePendingSend(requestId: String) {
        val key = keys.current() ?: return
        dao.deletePending(key, requestId)
    }

    override suspend fun unconfirmedConversationIds(): List<String> =
        keys.current()?.let { key -> dao.unconfirmed(key).map { it.id } }.orEmpty()

    // ---- mapping ----

    private fun Conversation.toEntity(key: String) = ConversationEntity(
        accountKey = key, id = id, chatbotId = chatbotId, title = title,
        createdAt = createdAt.toEpochMilli(), updatedAt = updatedAt.toEpochMilli(), revision = revision,
        lastTurnJson = lastTurn?.let { ApiJson.encodeToString(TurnDto.serializer(), it.toDto()) },
    )

    private fun ConversationEntity.toDomain() = Conversation(
        id = id, chatbotId = chatbotId, title = title,
        createdAt = Instant.ofEpochMilli(createdAt), updatedAt = Instant.ofEpochMilli(updatedAt), revision = revision,
        lastTurn = lastTurnJson?.let { runCatching { ApiJson.decodeFromString(TurnDto.serializer(), it).toDomain() }.getOrNull() },
    )

    private fun Message.toEntity(key: String) = MessageEntity(
        accountKey = key, id = id, conversationId = conversationId, role = role.name.lowercase(), text = text,
        citationsJson = ApiJson.encodeToString(citationList, citations.map { it.toDto() }),
        sequence = sequence, createdAt = createdAt.toEpochMilli(), requestId = requestId, status = status.name.lowercase(),
    )

    private fun MessageEntity.toDomain() = Message(
        id = id, conversationId = conversationId,
        role = if (role == "user") Role.User else Role.Assistant,
        text = text,
        citations = runCatching { ApiJson.decodeFromString(citationList, citationsJson).map { it.toDomain() } }.getOrDefault(emptyList()),
        sequence = sequence, createdAt = Instant.ofEpochMilli(createdAt), requestId = requestId,
        status = TurnStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) } ?: TurnStatus.Completed,
    )

    private fun PendingSend.toEntity(key: String) = PendingSendEntity(
        accountKey = key, requestId = requestId, conversationId = conversationId, text = text, emailManager = emailManager,
        createdAt = createdAt.toEpochMilli(), state = state.name, errorMessage = errorMessage,
    )

    private fun PendingSendEntity.toDomain() = PendingSend(
        requestId = requestId, conversationId = conversationId, text = text, emailManager = emailManager,
        createdAt = Instant.ofEpochMilli(createdAt),
        state = PendingState.entries.firstOrNull { it.name == state } ?: PendingState.Uncertain,
        errorMessage = errorMessage,
    )

    private companion object {
        const val MAX_PAGES = 50
        const val KEEP_TRANSCRIPTS = 20
    }
}
