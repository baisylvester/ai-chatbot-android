package com.embeddablechatbot.android.core.data

import com.embeddablechatbot.android.core.data.auth.AccessTokenProvider
import com.embeddablechatbot.android.core.data.auth.TokenResult
import com.embeddablechatbot.android.core.data.db.ChatDao
import com.embeddablechatbot.android.core.data.db.ConversationEntity
import com.embeddablechatbot.android.core.data.db.ListStateEntity
import com.embeddablechatbot.android.core.data.db.MessageEntity
import com.embeddablechatbot.android.core.data.db.PendingSendEntity
import com.embeddablechatbot.android.core.data.db.SyncStateEntity
import com.embeddablechatbot.android.core.data.db.TransactionRunner
import com.embeddablechatbot.android.core.data.db.UnconfirmedConversationEntity
import com.embeddablechatbot.android.core.domain.model.DecimalStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeTokens(var userId: String? = "user-1") : AccessTokenProvider {
    var access = "token-1"
    var refreshes = 0
    override var epoch: Long = 1
    var onRefresh: () -> Unit = {}

    override suspend fun token(): TokenResult = userId?.let { TokenResult.Token(access, it, epoch) } ?: TokenResult.SignedOut
    override suspend fun refreshAfterUnauthorized(staleToken: String): TokenResult {
        val user = userId ?: return TokenResult.SignedOut
        if (staleToken == access) {
            refreshes++
            access = "token-${refreshes + 1}"
            onRefresh()
        }
        return TokenResult.Token(access, user, epoch)
    }
    override fun currentUserId(): String? = userId
}

object DirectTransactions : TransactionRunner {
    override suspend fun <T> invoke(block: suspend () -> T): T = block()
}

/** In-memory ChatDao mirroring the Room queries' semantics (including numeric decimal ordering). */
class FakeChatDao : ChatDao {
    val conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val syncStates = mutableMapOf<Pair<String, String>, SyncStateEntity>()
    val listStates = mutableMapOf<String, ListStateEntity>()
    val pending = MutableStateFlow<List<PendingSendEntity>>(emptyList())
    val unconfirmed = mutableListOf<UnconfirmedConversationEntity>()

    override fun observeConversations(account: String): Flow<List<ConversationEntity>> =
        conversations.map { all -> all.filter { it.accountKey == account }.sortedWith(compareByDescending<ConversationEntity> { it.createdAt }.thenByDescending { it.id }) }
    override suspend fun conversations(account: String) = conversations.value.filter { it.accountKey == account }
    override suspend fun upsertConversations(items: List<ConversationEntity>) {
        conversations.value = conversations.value.filterNot { c -> items.any { it.accountKey == c.accountKey && it.id == c.id } } + items
    }
    override suspend fun deleteConversations(account: String, ids: List<String>) {
        conversations.value = conversations.value.filterNot { it.accountKey == account && it.id in ids }
    }
    override fun observeMessages(account: String, conversationId: String): Flow<List<MessageEntity>> =
        messages.map { all -> all.filter { it.accountKey == account && it.conversationId == conversationId }.sortedWith { a, b -> DecimalStrings.compare(a.sequence, b.sequence) } }
    override suspend fun requestIds(account: String, conversationId: String) =
        messages.value.filter { it.accountKey == account && it.conversationId == conversationId }.mapNotNull { it.requestId }
    override suspend fun upsertMessages(items: List<MessageEntity>) {
        messages.value = messages.value.filterNot { m -> items.any { it.accountKey == m.accountKey && it.id == m.id } } + items
    }
    override suspend fun deleteMessages(account: String, conversationIds: List<String>) {
        messages.value = messages.value.filterNot { it.accountKey == account && it.conversationId in conversationIds }
    }
    override suspend fun syncState(account: String, conversationId: String) = syncStates[account to conversationId]
    override suspend fun upsertSyncState(state: SyncStateEntity) { syncStates[state.accountKey to state.conversationId] = state }
    override suspend fun staleTranscripts(account: String, keep: Int) =
        syncStates.values.filter { it.accountKey == account }.sortedByDescending { it.lastSyncedAt }.drop(keep).map { it.conversationId }
    override suspend fun deleteSyncStates(account: String, conversationIds: List<String>) {
        conversationIds.forEach { syncStates.remove(account to it) }
    }
    override suspend fun listState(account: String) = listStates[account]
    override suspend fun upsertListState(state: ListStateEntity) { listStates[state.accountKey] = state }
    override fun observePending(account: String, conversationId: String) =
        pending.map { all -> all.filter { it.accountKey == account && it.conversationId == conversationId } }
    override suspend fun pending(account: String, conversationId: String) =
        pending.value.filter { it.accountKey == account && it.conversationId == conversationId }
    override suspend fun allPending(account: String) = pending.value.filter { it.accountKey == account }
    override suspend fun upsertPending(item: PendingSendEntity) {
        pending.value = pending.value.filterNot { it.accountKey == item.accountKey && it.requestId == item.requestId } + item
    }
    override suspend fun deletePending(account: String, requestId: String) {
        pending.value = pending.value.filterNot { it.accountKey == account && it.requestId == requestId }
    }
    override suspend fun deletePendingFor(account: String, conversationIds: List<String>) {
        pending.value = pending.value.filterNot { it.accountKey == account && it.conversationId in conversationIds }
    }
    override suspend fun unconfirmed(account: String) = unconfirmed.filter { it.accountKey == account }
    override suspend fun upsertUnconfirmed(item: UnconfirmedConversationEntity) {
        unconfirmed.removeAll { it.accountKey == item.accountKey && it.id == item.id }
        unconfirmed += item
    }
    override suspend fun deleteUnconfirmed(account: String, id: String) { unconfirmed.removeAll { it.accountKey == account && it.id == id } }
    override suspend fun clearConversations() { conversations.value = emptyList() }
    override suspend fun clearMessages() { messages.value = emptyList() }
    override suspend fun clearSyncStates() = syncStates.clear()
    override suspend fun clearListStates() = listStates.clear()
    override suspend fun clearPending() { pending.value = emptyList() }
    override suspend fun clearUnconfirmed() = unconfirmed.clear()
}

fun conversationJson(id: String = "c1", revision: String = "2", lastTurn: String = "null", createdAt: String = "2026-11-17T00:00:00.000Z") =
    """{"id":"$id","chatbotId":"cb_testbot","title":"T","createdAt":"$createdAt","updatedAt":"2026-11-17T00:00:01.000Z","revision":"$revision","lastTurn":$lastTurn}"""

fun messageJson(id: String, sequence: String, role: String = "user", requestId: String? = "r1") =
    """{"id":"$id","role":"$role","text":"t$id","citations":[],"sequence":"$sequence","createdAt":"2026-11-17T00:00:00.000Z","requestId":${requestId?.let { "\"$it\"" } ?: "null"},"status":"completed"}"""
