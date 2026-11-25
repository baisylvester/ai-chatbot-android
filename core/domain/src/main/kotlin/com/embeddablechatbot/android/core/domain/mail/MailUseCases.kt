package com.embeddablechatbot.android.core.domain.mail

import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PendingConnectStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** RFC 7636-style verifier/challenge pair binding a connect attempt to this device. */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        ByteArray(32).also(random::nextBytes).let(encoder::encodeToString)

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** Parameters of the `chatbotmail://oauth/gmail?…` return link. Untrusted input. */
data class ConnectCallback(val transactionId: String?, val code: String?, val error: String?)

sealed interface ConnectResult {
    data class Connected(val status: MailStatus) : ConnectResult
    data class Declined(val message: String) : ConnectResult

    /** The link did not belong to an attempt started on this device and account; nothing was changed. */
    data object Ignored : ConnectResult
    data class Failed(val error: AppError) : ConnectResult
}

/**
 * Connects Gmail through the system browser. The callback link is only a signal: the connection is
 * completed with an authenticated request carrying this device's verifier, and the result is the
 * server's status, never the link itself.
 */
@Singleton
class ConnectMailboxUseCase @Inject constructor(
    private val repository: MailRepository,
    private val store: PendingConnectStore,
    private val clock: Clock,
) {
    private val completing = Mutex()

    suspend fun start(chatbotId: String, accountId: String): Outcome<String> {
        val verifier = Pkce.newVerifier()
        return when (val launch = repository.startConnect(chatbotId, Pkce.challenge(verifier))) {
            is Outcome.Ok -> {
                store.save(PendingConnect(launch.value.transactionId, verifier, launch.value.expiresAt, accountId))
                Outcome.Ok(launch.value.authorizationUrl)
            }
            is Outcome.Err -> launch
        }
    }

    suspend fun complete(callback: ConnectCallback, accountId: String): ConnectResult = completing.withLock {
        val pending = store.load() ?: return ConnectResult.Ignored
        if (callback.transactionId.isNullOrBlank() || callback.transactionId != pending.transactionId || pending.accountId != accountId) {
            return ConnectResult.Ignored
        }
        if (clock.now().isAfter(pending.expiresAt)) {
            store.clear()
            return ConnectResult.Declined("The connection attempt expired. Start again.")
        }
        callback.error?.let { error ->
            store.clear()
            return ConnectResult.Declined(
                when (error) {
                    "denied" -> "Gmail access was not granted."
                    "permission" -> "Gmail permission was not granted. Tick the Gmail box on Google's consent screen."
                    "expired" -> "The connection attempt expired. Start again."
                    else -> "Gmail could not be connected. Try again."
                },
            )
        }
        val code = callback.code?.takeIf { it.length in 16..256 } ?: run {
            store.clear()
            return ConnectResult.Declined("The response from the browser was incomplete. Start again.")
        }
        when (val result = repository.completeConnect(pending.transactionId, code, pending.verifier)) {
            is Outcome.Ok -> {
                store.clear()
                ConnectResult.Connected(result.value)
            }
            is Outcome.Err -> {
                // Network failure: keep the attempt so a later re-check can still complete it before expiry.
                if (result.error !is AppError.Network) store.clear()
                ConnectResult.Failed(result.error)
            }
        }
    }
}

sealed interface ApprovalResult {
    data class Finished(val action: MailAction) : ApprovalResult

    /** The draft or targets changed since review; the user must review again. Nothing was executed. */
    data object Stale : ApprovalResult

    /** Another tap or device handled it first. */
    data object AlreadyHandled : ApprovalResult
    data class Failed(val error: AppError) : ApprovalResult
}

/**
 * Approval is tied to the exact reviewed version. Duplicate execution is prevented by the server's
 * atomic claim; the local lock only avoids sending pointless duplicate requests.
 */
@Singleton
class ReviewMailActionUseCase @Inject constructor(private val repository: MailRepository) {
    private val inFlight = Mutex()

    /** [sendAt] schedules an email instead of sending it now. Trash requests cannot be scheduled. */
    suspend fun approve(reviewed: MailActionDetail, sendAt: java.time.Instant? = null): ApprovalResult {
        val version = reviewed.version
        if (!reviewed.canApprove || version == null) return ApprovalResult.Stale
        if (sendAt != null && reviewed.action.kind != com.embeddablechatbot.android.core.domain.model.MailActionKind.Send) {
            return ApprovalResult.Failed(AppError.Invalid("Only emails can be scheduled."))
        }
        if (!inFlight.tryLock()) return ApprovalResult.AlreadyHandled
        try {
            return mapResult(repository.approve(reviewed.action.id, version, sendAt))
        } finally {
            inFlight.unlock()
        }
    }

    suspend fun reject(reviewed: MailActionDetail): ApprovalResult {
        if (!reviewed.action.status.canDecline) return ApprovalResult.AlreadyHandled
        return inFlight.withLock { mapResult(repository.reject(reviewed.action.id)) }
    }

    suspend fun unschedule(reviewed: MailActionDetail): ApprovalResult {
        if (reviewed.action.status != com.embeddablechatbot.android.core.domain.model.MailActionStatus.Scheduled) return ApprovalResult.AlreadyHandled
        return inFlight.withLock { mapResult(repository.unschedule(reviewed.action.id)) }
    }

    private fun mapResult(result: Outcome<MailAction>): ApprovalResult = when (result) {
        is Outcome.Ok -> ApprovalResult.Finished(result.value)
        is Outcome.Err -> when ((result.error as? AppError.Conflict)?.reason) {
            ErrorReason.StaleApproval -> ApprovalResult.Stale
            ErrorReason.AlreadyHandled -> ApprovalResult.AlreadyHandled
            else -> ApprovalResult.Failed(result.error)
        }
    }
}
