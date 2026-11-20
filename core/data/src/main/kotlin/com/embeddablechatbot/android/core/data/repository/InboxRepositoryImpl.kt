package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.network.*
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboxRepositoryImpl @Inject constructor(private val api: ClientApi) : InboxRepository {
    override suspend fun list(folder: MailFolder, query: String, cursor: String?) = api.inbox(folder.wire, query, cursor).map { InboxPage(it.value.messages.map(InboxMessageDto::toDomain), it.value.nextCursor) }
    override suspend fun read(id: String) = api.inboxMessage(id).map { r -> r.value.let { OpenMessage(InboxMessage(it.id,it.threadId,it.from,it.subject,it.date,it.snippet,it.unread,it.starred),it.to,it.body,it.truncated) } }
    override suspend fun change(id: String, change: MailChange, requestId: String) = api.changeMail(ChangeMailBody(listOf(id),change.wire,requestId)).map { MailChangeReceipt(it.value.actionId,it.value.waitingForApproval) }
    override suspend fun saveDraft(draft: ComposeDraft) = api.saveMailDraft(SaveDraftBody(draft.requestId,draft.to,draft.subject,draft.body,draft.replyToMessageId)).map { DraftReceipt(it.value.draftId,it.value.actionId,it.value.to,it.value.subject) }
    override suspend fun write(request: WritingRequest) = api.writeMail(WritingBody(request.operation.wire,request.text,request.tone,request.length,request.language)).map { it.value.text }
}
