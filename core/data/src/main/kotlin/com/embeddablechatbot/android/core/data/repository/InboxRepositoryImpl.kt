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
class InboxRepositoryImpl @Inject constructor(
    private val api: ClientApi,
    private val devicePdf: DevicePdfText,
    private val cache: MailCache,
    private val auth: com.embeddablechatbot.android.core.domain.repository.AuthRepository,
    private val config: com.embeddablechatbot.android.core.domain.repository.ConfigRepository,
) : InboxRepository {
    /** The signed-in account's cache key, or null when signed out (nothing is cached then). */
    private fun account(): String? = (auth.authState.value as? com.embeddablechatbot.android.core.domain.model.AuthState.SignedIn)?.account?.userId
        ?.let { "$it|${config.config.value.platformUrl}|${config.config.value.chatbotId}" }
    private fun offline(error: AppError) = error is AppError.Network

    /**
     * First pages of folders and labels are saved for offline reading. Without a connection, the saved
     * page is returned with [InboxPage.savedAt] set; searches and later pages need the server.
     */
    override suspend fun list(folder: MailFolder, query: String, cursor: String?, labelId: String?): Outcome<InboxPage> {
        val view = "${folder.wire}|${labelId.orEmpty()}"
        val cacheable = cursor == null && query.isBlank()
        val account = account()
        return when (val result = api.inbox(folder.wire, query, cursor, labelId)) {
            is Outcome.Ok -> {
                if (cacheable && account != null) cache.savePage(account, view, result.value.value.messages)
                Outcome.Ok(InboxPage(result.value.value.messages.map(InboxMessageDto::toDomain), result.value.value.nextCursor))
            }
            is Outcome.Err -> {
                val saved = if (cacheable && account != null && offline(result.error)) cache.page(account, view) else null
                if (saved != null) Outcome.Ok(InboxPage(saved.second.map(InboxMessageDto::toDomain), null, saved.first)) else result
            }
        }
    }
    override suspend fun read(id: String): Outcome<OpenMessage> {
        val account = account()
        return when (val result = api.inboxMessage(id)) {
            is Outcome.Ok -> { account?.let { cache.saveMessage(it, result.value.value) }; Outcome.Ok(result.value.value.toOpen(false)) }
            is Outcome.Err -> (if (account != null && offline(result.error)) cache.message(account, id) else null)?.let { Outcome.Ok(it.toOpen(true)) } ?: result
        }
    }
    private fun OpenMessageDto.toOpen(offline: Boolean) = OpenMessage(InboxMessage(id, threadId, from, subject, date, snippet, unread, starred, labelIds), to, body, truncated, attachments.map(MessageAttachmentDto::toDomain), offline)
    override suspend fun change(id: String, change: MailChange, requestId: String) = api.changeMail(ChangeMailBody(listOf(id), change.wire, requestId)).map {
        // A moved message must not reappear in an old offline copy of the folder it left.
        if (change != MailChange.Read && change != MailChange.Unread && change != MailChange.Star && change != MailChange.Unstar) account()?.let { a -> cache.forget(a, id) }
        MailChangeReceipt(it.value.actionId, it.value.waitingForApproval)
    }
    override suspend fun saveDraft(draft: ComposeDraft) = api.saveMailDraft(SaveDraftBody(
        draft.requestId, draft.to, draft.subject, draft.body, draft.replyToMessageId,
        format = if (draft.formatted) "markdown" else "plain",
        attachments = draft.attachments.takeIf { it.isNotEmpty() }?.map { OutgoingAttachmentDto(it.filename, it.mimeType, Base64.getEncoder().encodeToString(it.bytes)) },
    )).map { DraftReceipt(it.value.draftId, it.value.actionId, it.value.to, it.value.subject) }
    override suspend fun write(request: WritingRequest) = api.writeMail(WritingBody(request.operation.wire, request.text, request.tone, request.length, request.language)).map { it.value.text }
    override suspend fun labels() = api.mailLabels().map { r -> r.value.labels.map(MailLabelDto::toDomain) }
    override suspend fun createLabel(name: String) = api.createMailLabel(name.trim()).map { it.value.toDomain() }
    override suspend fun updateLabel(id: String, name: String?, color: String?) = api.updateMailLabel(id, UpdateLabelBody(name?.trim(), color)).map { it.value.toDomain() }
    override suspend fun deleteLabel(id: String) = api.deleteMailLabel(id).map { }
    override suspend fun clearOfflineCopy() { cache.clear() }
    override suspend fun pinned() = api.pinnedMail().map { r -> r.value.messages.map(InboxMessageDto::toDomain) }
    override suspend fun pin(id: String) = api.pinMail(id).map { }
    override suspend fun unpin(id: String) = api.unpinMail(id).map { }
    override suspend fun contacts() = api.mailContacts().map { r -> r.value.contacts.map { MailContact(it.name, it.email) } }
    override suspend fun relabel(ids: List<String>, add: List<String>, remove: List<String>) = api.relabel(RelabelBody(ids, add, remove)).map { }
    override suspend fun attachment(messageId: String, partId: String) = api.attachmentBytes(messageId, partId).map { DownloadedFile(partId, it.value.first, it.value.second) }
    /**
     * The server reads the attachment first. When it cannot, the phone reads it instead: PDFs with
     * Android's text API (15+), and Excel, PowerPoint and legacy Word files with [DeviceDocumentText].
     */
    override suspend fun attachmentText(messageId: String, partId: String): Outcome<AttachmentText> {
        val server = api.attachmentText(messageId, partId).map { AttachmentText(it.value.filename, it.value.text, it.value.truncated) }
        val refused = (server as? Outcome.Err)?.error.let { it is AppError.Conflict && (it.reason == ErrorReason.AttachmentUnsupported || it.reason == ErrorReason.AttachmentUnreadable) }
        if (!refused) return server
        val file = (api.attachmentBytes(messageId, partId) as? Outcome.Ok)?.value?.value ?: return server
        val text = (if (devicePdf.available) devicePdf.text(file.second) else null)
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DeviceDocumentText.text(file.second) }
            ?: return server
        return Outcome.Ok(AttachmentText(partId, text, text.length >= DeviceDocumentText.MAX_CHARS))
    }
}
