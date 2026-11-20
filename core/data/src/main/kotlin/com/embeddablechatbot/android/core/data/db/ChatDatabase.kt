package com.embeddablechatbot.android.core.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Every row carries `accountKey` = userId|platformUrl|chatbotId, so one account's cache can never be
 * read as another's. Sequences, revisions and cursors are stored as decimal strings.
 */

@Entity(tableName = "conversations", primaryKeys = ["accountKey", "id"])
data class ConversationEntity(
    val accountKey: String,
    val id: String,
    val chatbotId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: String,
    val lastTurnJson: String?,
)

@Entity(
    tableName = "messages",
    primaryKeys = ["accountKey", "id"],
    indices = [Index(value = ["accountKey", "conversationId"])],
)
data class MessageEntity(
    val accountKey: String,
    val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val citationsJson: String,
    val sequence: String,
    val createdAt: Long,
    val requestId: String?,
    val status: String,
)

@Entity(tableName = "sync_state", primaryKeys = ["accountKey", "conversationId"])
data class SyncStateEntity(val accountKey: String, val conversationId: String, val cursor: String, val lastSyncedAt: Long)

@Entity(tableName = "list_state", primaryKeys = ["accountKey"])
data class ListStateEntity(val accountKey: String, val nextCursor: String?, val lastRefreshedAt: Long)

@Entity(tableName = "pending_sends", primaryKeys = ["accountKey", "requestId"])
data class PendingSendEntity(
    val accountKey: String,
    val requestId: String,
    val conversationId: String,
    val text: String,
    val emailManager: Boolean,
    val createdAt: Long,
    val state: String,
    val errorMessage: String?,
)

@Entity(tableName = "unconfirmed_conversations", primaryKeys = ["accountKey", "id"])
data class UnconfirmedConversationEntity(val accountKey: String, val id: String, val createdAt: Long)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE accountKey = :account ORDER BY createdAt DESC, id DESC")
    fun observeConversations(account: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE accountKey = :account")
    suspend fun conversations(account: String): List<ConversationEntity>

    @Upsert
    suspend fun upsertConversations(items: List<ConversationEntity>)

    @Query("DELETE FROM conversations WHERE accountKey = :account AND id IN (:ids)")
    suspend fun deleteConversations(account: String, ids: List<String>)

    // Ordered numerically without converting decimal strings: shorter means smaller.
    @Query("SELECT * FROM messages WHERE accountKey = :account AND conversationId = :conversationId ORDER BY length(sequence), sequence")
    fun observeMessages(account: String, conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT requestId FROM messages WHERE accountKey = :account AND conversationId = :conversationId AND requestId IS NOT NULL")
    suspend fun requestIds(account: String, conversationId: String): List<String>

    @Upsert
    suspend fun upsertMessages(items: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE accountKey = :account AND conversationId IN (:conversationIds)")
    suspend fun deleteMessages(account: String, conversationIds: List<String>)

    @Query("SELECT * FROM sync_state WHERE accountKey = :account AND conversationId = :conversationId")
    suspend fun syncState(account: String, conversationId: String): SyncStateEntity?

    @Upsert
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT conversationId FROM sync_state WHERE accountKey = :account ORDER BY lastSyncedAt DESC LIMIT -1 OFFSET :keep")
    suspend fun staleTranscripts(account: String, keep: Int): List<String>

    @Query("DELETE FROM sync_state WHERE accountKey = :account AND conversationId IN (:conversationIds)")
    suspend fun deleteSyncStates(account: String, conversationIds: List<String>)

    @Query("SELECT * FROM list_state WHERE accountKey = :account")
    suspend fun listState(account: String): ListStateEntity?

    @Upsert
    suspend fun upsertListState(state: ListStateEntity)

    @Query("SELECT * FROM pending_sends WHERE accountKey = :account AND conversationId = :conversationId ORDER BY createdAt")
    fun observePending(account: String, conversationId: String): Flow<List<PendingSendEntity>>

    @Query("SELECT * FROM pending_sends WHERE accountKey = :account AND conversationId = :conversationId ORDER BY createdAt")
    suspend fun pending(account: String, conversationId: String): List<PendingSendEntity>

    @Query("SELECT * FROM pending_sends WHERE accountKey = :account ORDER BY createdAt")
    suspend fun allPending(account: String): List<PendingSendEntity>

    @Upsert
    suspend fun upsertPending(item: PendingSendEntity)

    @Query("DELETE FROM pending_sends WHERE accountKey = :account AND requestId = :requestId")
    suspend fun deletePending(account: String, requestId: String)

    @Query("DELETE FROM pending_sends WHERE accountKey = :account AND conversationId IN (:conversationIds)")
    suspend fun deletePendingFor(account: String, conversationIds: List<String>)

    @Query("SELECT * FROM unconfirmed_conversations WHERE accountKey = :account")
    suspend fun unconfirmed(account: String): List<UnconfirmedConversationEntity>

    @Upsert
    suspend fun upsertUnconfirmed(item: UnconfirmedConversationEntity)

    @Query("DELETE FROM unconfirmed_conversations WHERE accountKey = :account AND id = :id")
    suspend fun deleteUnconfirmed(account: String, id: String)

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncStates()

    @Query("DELETE FROM list_state")
    suspend fun clearListStates()

    @Query("DELETE FROM pending_sends")
    suspend fun clearPending()

    @Query("DELETE FROM unconfirmed_conversations")
    suspend fun clearUnconfirmed()
}

@Database(
    entities = [
        ConversationEntity::class, MessageEntity::class, SyncStateEntity::class, ListStateEntity::class,
        PendingSendEntity::class, UnconfirmedConversationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

/** Runs a block atomically. Room's implementation uses `withTransaction`; tests run it directly. */
interface TransactionRunner {
    suspend operator fun <T> invoke(block: suspend () -> T): T
}
