package com.embeddablechatbot.android.core.domain.model

import java.time.Instant

enum class OrgRole { Owner, Admin, Member }

enum class ConnectionStatus { Active, Revoked }

data class MailConnection(val email: String, val status: ConnectionStatus)

/**
 * The caller's mailbox capability for one chatbot. [eligible] means the user is an owner/admin of the
 * chatbot's organization, the existing server rule for mailbox tools.
 */
data class MailStatus(
    val provider: String,
    val serverReady: Boolean,
    val eligible: Boolean,
    val role: OrgRole?,
    val connection: MailConnection?,
    val pendingApprovals: Int,
) {
    val canUseMailTools: Boolean get() = serverReady && eligible && connection?.status == ConnectionStatus.Active
}

data class ConnectLaunch(val transactionId: String, val authorizationUrl: String, val expiresAt: Instant)

/** A connect attempt this device started; the verifier never leaves the device until completion. */
data class PendingConnect(val transactionId: String, val verifier: String, val expiresAt: Instant, val accountId: String)

enum class MailActionKind { Send, Trash }

enum class MailActionStatus {
    Pending, Scheduled, Processing, Done, Rejected, Failed, Uncertain, Unknown;

    /** Waiting for a decision: only these can be approved. */
    val isOpen: Boolean get() = this == Pending
    /** Can still be declined: waiting, or approved for a later time and not yet sent. */
    val canDecline: Boolean get() = this == Pending || this == Scheduled
}

data class MailAction(
    val id: String,
    val kind: MailActionKind,
    val summary: String,
    val status: MailActionStatus,
    val error: String?,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val to: String?,
    val subject: String?,
    val messageCount: Int?,
    /** When an approved, scheduled email is due. */
    val scheduledFor: Instant? = null,
)

data class Attachment(val filename: String, val mimeType: String, val size: Long)

data class SendDraft(
    val draftId: String,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val attachments: List<Attachment>,
    val draftExists: Boolean,
)

data class MailMessageSummary(val id: String, val from: String, val subject: String, val date: String, val snippet: String)

data class TrashTargets(val messages: List<MailMessageSummary>, val missing: List<String>)

/**
 * What the user reviews before approving. [version] binds an approval to exactly this content: the
 * server recomputes it and refuses a stale approval. Null means approval is impossible right now.
 */
data class MailActionDetail(
    val action: MailAction,
    val account: String,
    val version: String?,
    val send: SendDraft?,
    val trash: TrashTargets?,
) {
    val canApprove: Boolean get() = action.status.isOpen && version != null
}

enum class ActionFilter { Pending, Recent }
