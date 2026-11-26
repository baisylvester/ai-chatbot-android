package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.mail.InboxMessage
import com.embeddablechatbot.android.core.domain.mail.MailLabel
import com.embeddablechatbot.android.core.domain.mail.MessageAttachment
import kotlinx.serialization.Serializable

@Serializable data class InboxMessageDto(val id: String, val threadId: String = "", val from: String = "", val subject: String = "", val date: String = "", val snippet: String = "", val unread: Boolean = false, val starred: Boolean = false, val labelIds: List<String> = emptyList()) {
    fun toDomain() = InboxMessage(id, threadId, from, subject, date, snippet, unread, starred, labelIds)
}
@Serializable data class InboxPageDto(val messages: List<InboxMessageDto>, val nextCursor: String? = null)
@Serializable data class MessageAttachmentDto(val partId: String, val filename: String = "", val mimeType: String = "application/octet-stream", val size: Long = 0) {
    fun toDomain() = MessageAttachment(partId, filename, mimeType, size)
}
@Serializable data class OpenMessageDto(val id: String, val threadId: String = "", val from: String = "", val subject: String = "", val date: String = "", val snippet: String = "", val unread: Boolean = false, val starred: Boolean = false, val to: String = "", val body: String = "", val truncated: Boolean = false, val labelIds: List<String> = emptyList(), val attachments: List<MessageAttachmentDto> = emptyList())
@Serializable data class ChangeMailBody(val ids: List<String>, val action: String, val requestId: String)
@Serializable data class ChangeMailDto(val actionId: String? = null, val waitingForApproval: Boolean = false)
@Serializable data class OutgoingAttachmentDto(val filename: String, val mimeType: String, val data: String)
@Serializable data class SaveDraftBody(val requestId: String, val to: String, val subject: String, val body: String, val replyToMessageId: String? = null, val format: String = "plain", val attachments: List<OutgoingAttachmentDto>? = null)
@Serializable data class DraftReceiptDto(val draftId: String, val actionId: String, val to: String, val subject: String)
@Serializable data class WritingBody(val operation: String, val text: String, val tone: String, val length: String, val language: String)
@Serializable data class WritingDto(val text: String)
@Serializable data class MailLabelDto(val id: String, val name: String, val color: String? = null) {
    fun toDomain() = MailLabel(id, name, color)
}
@Serializable data class MailLabelListDto(val labels: List<MailLabelDto> = emptyList())
@Serializable data class CreateLabelBody(val name: String)
@Serializable data class ThemeDto(val palette: String, val mode: String)
/** `theme` is always sent, so explicitNulls=false cannot drop it. */
@Serializable data class PreferencesDto(val theme: ThemeDto? = null)
@Serializable data class PinnedListDto(val messages: List<InboxMessageDto> = emptyList())
@Serializable data class PinBody(val id: String)
@Serializable data class MailContactDto(val name: String = "", val email: String)
@Serializable data class MailContactListDto(val contacts: List<MailContactDto> = emptyList())
@Serializable data class UpdateLabelBody(val name: String? = null, val color: String? = null)
@Serializable data class RelabelBody(val ids: List<String>, val add: List<String>, val remove: List<String>)
@Serializable data class OkDto(val ok: Boolean = true)
@Serializable data class AttachmentTextDto(val filename: String = "", val text: String = "", val truncated: Boolean = false)
