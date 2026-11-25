package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.network.*
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboxRepositoryImpl @Inject constructor(private val api: ClientApi, private val devicePdf: DevicePdfText) : InboxRepository {
    override suspend fun list(folder: MailFolder, query: String, cursor: String?, labelId: String?) =
        api.inbox(folder.wire, query, cursor, labelId).map { InboxPage(it.value.messages.map(InboxMessageDto::toDomain), it.value.nextCursor) }
    override suspend fun read(id: String) = api.inboxMessage(id).map { r ->
        r.value.let { OpenMessage(InboxMessage(it.id, it.threadId, it.from, it.subject, it.date, it.snippet, it.unread, it.starred, it.labelIds), it.to, it.body, it.truncated, it.attachments.map(MessageAttachmentDto::toDomain)) }
    }
    override suspend fun change(id: String, change: MailChange, requestId: String) = api.changeMail(ChangeMailBody(listOf(id), change.wire, requestId)).map { MailChangeReceipt(it.value.actionId, it.value.waitingForApproval) }
    override suspend fun saveDraft(draft: ComposeDraft) = api.saveMailDraft(SaveDraftBody(
        draft.requestId, draft.to, draft.subject, draft.body, draft.replyToMessageId,
        format = if (draft.formatted) "markdown" else "plain",
        attachments = draft.attachments.takeIf { it.isNotEmpty() }?.map { OutgoingAttachmentDto(it.filename, it.mimeType, Base64.getEncoder().encodeToString(it.bytes)) },
    )).map { DraftReceipt(it.value.draftId, it.value.actionId, it.value.to, it.value.subject) }
    override suspend fun write(request: WritingRequest) = api.writeMail(WritingBody(request.operation.wire, request.text, request.tone, request.length, request.language)).map { it.value.text }
    override suspend fun labels() = api.mailLabels().map { r -> r.value.labels.map(MailLabelDto::toDomain) }
    override suspend fun createLabel(name: String) = api.createMailLabel(name.trim()).map { it.value.toDomain() }
    override suspend fun relabel(ids: List<String>, add: List<String>, remove: List<String>) = api.relabel(RelabelBody(ids, add, remove)).map { }
    override suspend fun attachment(messageId: String, partId: String) = api.attachmentBytes(messageId, partId).map { DownloadedFile(partId, it.value.first, it.value.second) }
    /**
     * The server reads the attachment first. When it cannot (no PDF-capable model configured), a PDF
     * is read on the phone instead, where Android supports it.
     */
    override suspend fun attachmentText(messageId: String, partId: String): Outcome<AttachmentText> {
        val server = api.attachmentText(messageId, partId).map { AttachmentText(it.value.filename, it.value.text, it.value.truncated) }
        val refused = (server as? Outcome.Err)?.error.let { it is AppError.Conflict && (it.reason == ErrorReason.AttachmentUnsupported || it.reason == ErrorReason.AttachmentUnreadable) }
        if (!refused || !devicePdf.available) return server
        val file = (api.attachmentBytes(messageId, partId) as? Outcome.Ok)?.value?.value ?: return server
        val text = devicePdf.text(file.second) ?: return server
        return Outcome.Ok(AttachmentText(partId, text, text.length >= 20_000))
    }
}
