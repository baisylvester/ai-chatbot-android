package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.model.Attachment
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.KnowledgeInfo
import com.embeddablechatbot.android.core.domain.model.KnowledgeState
import com.embeddablechatbot.android.core.domain.model.Citation
import com.embeddablechatbot.android.core.domain.model.ConnectLaunch
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.ConversationPage
import com.embeddablechatbot.android.core.domain.model.DecimalStrings
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailActionKind
import com.embeddablechatbot.android.core.domain.model.MailActionStatus
import com.embeddablechatbot.android.core.domain.model.MailConnection
import com.embeddablechatbot.android.core.domain.model.MailMessageSummary
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.MessagePage
import com.embeddablechatbot.android.core.domain.model.OrgRole
import com.embeddablechatbot.android.core.domain.model.Role
import com.embeddablechatbot.android.core.domain.model.SendDraft
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.TrashTargets
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import java.time.Instant

/** Thrown for responses that violate the documented contract (mapped to AppError.Protocol). */
class ContractException(message: String) : Exception(message)

internal fun parseInstant(value: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw ContractException("Invalid timestamp") }

internal fun decimal(value: String, field: String): String {
    if (!DecimalStrings.isValid(value)) throw ContractException("$field must be a decimal string")
    return value
}

internal fun turnStatus(value: String): TurnStatus = when (value) {
    "running" -> TurnStatus.Running
    "completed" -> TurnStatus.Completed
    "failed" -> TurnStatus.Failed
    "interrupted" -> TurnStatus.Interrupted
    else -> throw ContractException("Unknown turn status")
}

fun CitationDto.toDomain() = Citation(n, title, sourceUrl, snippet)
fun Citation.toDto() = CitationDto(n, title, sourceUrl, snippet)

fun TurnDto.toDomain() = Turn(requestId, turnStatus(status), text, citations.map { it.toDomain() }, error)

fun Turn.toDto() = TurnDto(
    requestId = requestId,
    status = status.name.lowercase(),
    text = text,
    citations = citations.map { it.toDto() },
    error = error,
)

fun ConversationDto.toDomain() = Conversation(
    id = id,
    chatbotId = chatbotId,
    title = title,
    createdAt = parseInstant(createdAt),
    updatedAt = parseInstant(updatedAt),
    revision = decimal(revision, "revision"),
    lastTurn = lastTurn?.toDomain(),
)

fun MessageDto.toDomain(conversationId: String) = Message(
    id = id,
    conversationId = conversationId,
    role = when (role) {
        "user" -> Role.User
        "assistant" -> Role.Assistant
        else -> throw ContractException("Unknown role")
    },
    text = text,
    citations = citations.map { it.toDomain() },
    sequence = decimal(sequence, "sequence"),
    createdAt = parseInstant(createdAt),
    requestId = requestId,
    status = turnStatus(status),
)

fun MessagePageDto.toDomain(): MessagePage {
    val conversation = conversation.toDomain()
    return MessagePage(conversation, messages.map { it.toDomain(conversation.id) }, decimal(nextCursor, "nextCursor"), hasMore)
}

fun ConversationPageDto.toDomain() = ConversationPage(conversations.map { it.toDomain() }, nextCursor)

fun ChatbotConfigDto.toDomain() = ChatbotInfo(chatbotId, name, welcomeMessage, suggestedQuestions, knowledge?.toDomain())

fun KnowledgeInfoDto.toDomain(): KnowledgeInfo {
    if (readyDocuments < 0 || processingDocuments < 0 || chunks < 0) throw ContractException("Invalid knowledge counts")
    val status = when (state) {
        "ready" -> KnowledgeState.Ready
        "processing" -> KnowledgeState.Processing
        "empty" -> KnowledgeState.Empty
        else -> KnowledgeState.Unavailable
    }
    return KnowledgeInfo(status, sourceChatbotId, sourceName, readyDocuments, processingDocuments, chunks, updatedAt?.let(::parseInstant), shared)
}

fun StreamEventDto.toDomain(event: String): SendEvent? = when (event) {
    "accepted" -> SendEvent.Accepted(requestId.orEmpty())
    "token" -> text?.let { SendEvent.Token(it) }
    "sources" -> SendEvent.Sources(citations.orEmpty().map { it.toDomain() })
    "tool" -> SendEvent.Tool(name.orEmpty(), phase.orEmpty(), steps.orEmpty(), waiting, failed == true)
    "done" -> turn?.let { SendEvent.Done(it.toDomain()) }
    else -> null
}

fun MailStatusDto.toDomain() = MailStatus(
    provider = provider,
    serverReady = serverReady,
    eligible = eligible,
    role = when (role) {
        "owner" -> OrgRole.Owner
        "admin" -> OrgRole.Admin
        "member" -> OrgRole.Member
        else -> null
    },
    connection = connection?.let {
        MailConnection(it.email, if (it.status == "active") ConnectionStatus.Active else ConnectionStatus.Revoked)
    },
    pendingApprovals = pendingApprovals,
)

fun ConnectLaunchDto.toDomain(): ConnectLaunch {
    if (!authorizationUrl.startsWith("https://")) throw ContractException("Authorization URL must be HTTPS")
    return ConnectLaunch(transactionId, authorizationUrl, parseInstant(expiresAt))
}

fun MailActionDto.toDomain() = MailAction(
    id = id,
    kind = when (kind) {
        "send" -> MailActionKind.Send
        "trash" -> MailActionKind.Trash
        else -> throw ContractException("Unknown action kind")
    },
    summary = summary,
    status = when (status) {
        "pending" -> MailActionStatus.Pending
        "processing" -> MailActionStatus.Processing
        "done" -> MailActionStatus.Done
        "rejected" -> MailActionStatus.Rejected
        "failed" -> MailActionStatus.Failed
        "uncertain" -> MailActionStatus.Uncertain
        else -> MailActionStatus.Unknown
    },
    error = error,
    createdAt = parseInstant(createdAt),
    resolvedAt = resolvedAt?.let(::parseInstant),
    to = to,
    subject = subject,
    messageCount = messageCount,
)

fun MailActionDetailDto.toDomain() = MailActionDetail(
    action = action.toDomain(),
    account = account,
    version = version,
    send = send?.let { s ->
        SendDraft(s.draftId, s.to, s.cc, s.bcc, s.subject, s.body, s.attachments.map { Attachment(it.filename, it.mimeType, it.size) }, s.draftExists)
    },
    trash = trash?.let { t ->
        TrashTargets(t.messages.map { MailMessageSummary(it.id, it.from, it.subject, it.date, it.snippet) }, t.missing)
    },
)
