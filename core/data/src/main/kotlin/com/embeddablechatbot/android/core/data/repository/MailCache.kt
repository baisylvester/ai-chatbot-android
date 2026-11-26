package com.embeddablechatbot.android.core.data.repository

import android.content.Context
import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.data.network.InboxMessageDto
import com.embeddablechatbot.android.core.data.network.OpenMessageDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Read-only offline copy of recent mail: the first page of each folder or label viewed and the last
 * [MAX_MESSAGES] opened messages. Stored in app-private no-backup storage, in one directory per
 * account (hashed user + platform + chatbot), and deleted on sign-out. Actions always need the server.
 */
@Singleton
class MailCache @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val lock = Mutex()
    private val root get() = File(context.noBackupFilesDir, "mail_cache")

    @Serializable private data class Page(val savedAt: Long, val messages: List<InboxMessageDto>)
    @Serializable private data class Opened(val savedAt: Long, val message: OpenMessageDto)

    private fun dir(account: String) = File(root, hash(account)).apply { mkdirs() }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    suspend fun savePage(account: String, view: String, messages: List<InboxMessageDto>) = io {
        File(dir(account), "page_${hash(view)}.json").writeText(ApiJson.encodeToString(Page.serializer(), Page(System.currentTimeMillis(), messages.take(MAX_PAGE))))
    }

    /** The saved page and when it was saved, or null. */
    suspend fun page(account: String, view: String): Pair<Long, List<InboxMessageDto>>? = io {
        val file = File(dir(account), "page_${hash(view)}.json")
        if (!file.exists()) null else runCatching { ApiJson.decodeFromString(Page.serializer(), file.readText()) }.getOrNull()?.let { it.savedAt to it.messages }
    }

    suspend fun saveMessage(account: String, message: OpenMessageDto) = io {
        val folder = File(dir(account), "messages").apply { mkdirs() }
        File(folder, "${hash(message.id)}.json").writeText(ApiJson.encodeToString(Opened.serializer(), Opened(System.currentTimeMillis(), message)))
        // Keep only the most recently opened messages.
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_MESSAGES)?.forEach { it.delete() }
    }

    suspend fun message(account: String, id: String): OpenMessageDto? = io {
        val file = File(File(dir(account), "messages"), "${hash(id)}.json")
        if (!file.exists()) null else runCatching { ApiJson.decodeFromString(Opened.serializer(), file.readText()).message }.getOrNull()
    }

    /** Removes a message everywhere after it was moved or trashed, so the offline copy never shows it in the wrong place. */
    suspend fun forget(account: String, id: String) = io {
        File(File(dir(account), "messages"), "${hash(id)}.json").delete()
        dir(account).listFiles { f -> f.name.startsWith("page_") }?.forEach { file ->
            runCatching { ApiJson.decodeFromString(Page.serializer(), file.readText()) }.getOrNull()?.let { page ->
                if (page.messages.any { it.id == id }) file.writeText(ApiJson.encodeToString(Page.serializer(), page.copy(messages = page.messages.filterNot { it.id == id })))
            }
        }
    }

    suspend fun clear() = io { root.deleteRecursively() }

    /** Storage problems only cost the offline copy: they never fail the online request. */
    private suspend fun <T> io(block: () -> T): T? = withContext(Dispatchers.IO) { lock.withLock { try { block() } catch (_: java.io.IOException) { null } } }

    private companion object {
        const val MAX_PAGE = 50
        const val MAX_MESSAGES = 50
    }
}
