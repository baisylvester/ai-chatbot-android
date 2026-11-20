package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.mail.InboxMessage
import kotlinx.serialization.Serializable

@Serializable data class InboxMessageDto(val id: String, val threadId: String = "", val from: String = "", val subject: String = "", val date: String = "", val snippet: String = "", val unread: Boolean = false, val starred: Boolean = false) {
    fun toDomain() = InboxMessage(id, threadId, from, subject, date, snippet, unread, starred)
}
@Serializable data class InboxPageDto(val messages: List<InboxMessageDto>, val nextCursor: String? = null)
@Serializable data class OpenMessageDto(val id: String, val threadId: String = "", val from: String = "", val subject: String = "", val date: String = "", val snippet: String = "", val unread: Boolean = false, val starred: Boolean = false, val to: String = "", val body: String = "", val truncated: Boolean = false)
@Serializable data class ChangeMailBody(val ids: List<String>, val action: String, val requestId: String)
@Serializable data class ChangeMailDto(val actionId: String? = null, val waitingForApproval: Boolean = false)
@Serializable data class SaveDraftBody(val requestId: String, val to: String, val subject: String, val body: String, val replyToMessageId: String? = null)
@Serializable data class DraftReceiptDto(val draftId: String, val actionId: String, val to: String, val subject: String)
@Serializable data class WritingBody(val operation: String, val text: String, val tone: String, val length: String, val language: String)
@Serializable data class WritingDto(val text: String)
