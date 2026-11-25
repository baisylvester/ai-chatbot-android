package com.embeddablechatbot.android.core.domain.model

import java.time.Instant

/** Result of a domain operation. Errors are values; cancellation is never converted into one. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value
fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Err)?.error

/** Machine-readable reasons the server attaches to 409/403 errors (`error.reason`). */
enum class ErrorReason(val wire: String) {
    ConversationBusy("conversation_busy"),
    RequestRunning("request_running"),
    RequestMismatch("request_mismatch"),
    LeaseLost("lease_lost"),
    MailboxNotConnected("mailbox_not_connected"),
    MailRoleRequired("mail_role_required"),
    MailNotConfigured("mail_not_configured"),
    StaleApproval("stale_approval"),
    AlreadyHandled("already_handled"),
    AlreadyCompleted("already_completed"),
    TransactionNotFound("transaction_not_found"),
    TransactionExpired("transaction_expired"),
    VerificationFailed("verification_failed"),
    CallbackPending("callback_pending"),
    OAuthDenied("oauth_denied"),
    OAuthPermission("oauth_permission"),
    OAuthFailed("oauth_failed"),
    DraftMissing("draft_missing"),
    ActionNotFound("action_not_found"),
    MailboxRevoked("mailbox_revoked"),
    AttachmentUnsupported("attachment_unsupported"),
    AttachmentUnreadable("attachment_unreadable"),
    AttachmentTooLarge("attachment_too_large"),
    AttachmentInvalid("attachment_invalid"),
    LabelExists("label_exists"),
    ScheduleInvalid("schedule_invalid"),
    Unknown("unknown");

    companion object {
        fun fromWire(value: String?): ErrorReason? = value?.let { v -> entries.firstOrNull { it.wire == v } ?: Unknown }
    }
}

sealed interface AppError {
    /** The session is gone (expired, revoked or signed out). Sign in again; never create new conversations for this. */
    data object Unauthorized : AppError
    data class Forbidden(val reason: ErrorReason?, val message: String) : AppError
    data class NotFound(val message: String) : AppError
    data class Conflict(val reason: ErrorReason, val message: String) : AppError
    data class Gone(val reason: ErrorReason?, val message: String) : AppError
    data class RateLimited(val retryAfterSeconds: Long?) : AppError
    data class Invalid(val message: String) : AppError
    data class Unavailable(val reason: ErrorReason?, val message: String) : AppError
    data class Server(val status: Int, val message: String) : AppError

    /**
     * The connection failed. [uncertain] is true when a non-idempotent request may have reached the
     * server (timeout, reset, 502 interruption): the outcome must be read from the server, never retried blindly.
     */
    data class Network(val uncertain: Boolean) : AppError
    data class Protocol(val message: String) : AppError
    data class NotConfigured(val message: String) : AppError
}

/** Abstraction over wall-clock time so rules and tests control "now". */
fun interface Clock {
    fun now(): Instant

    companion object {
        val System: Clock = Clock { Instant.now() }
    }
}

/** Abstraction over random UUIDs so tests can assert stable IDs. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random: IdGenerator = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}
