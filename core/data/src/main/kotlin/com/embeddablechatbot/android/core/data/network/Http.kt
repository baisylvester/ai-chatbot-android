package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.ErrorReason
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Executes a call without blocking a thread; coroutine cancellation cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

object HttpErrors {
    /**
     * Maps an HTTP error response. `reason` comes from the server's additive `error.reason` field;
     * older servers without it are recognised by their documented messages.
     */
    fun fromResponse(status: Int, body: String?, retryAfter: String?, now: Instant = Instant.now()): AppError {
        if (status == 404 && body?.trimStart()?.startsWith("<") == true) return AppError.NotConfigured("The mail service is not available on this server yet. Contact support or choose another platform.")
        val dto = body?.let { runCatching { ApiJson.decodeFromString(ApiErrorBody.serializer(), it).error }.getOrNull() }
        val message = dto?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(status)
        val reason = ErrorReason.fromWire(dto?.reason)
        return when (status) {
            400 -> if (reason != null && reason != ErrorReason.Unknown) AppError.Conflict(reason, message) else AppError.Invalid(message)
            401 -> AppError.Unauthorized
            403 -> AppError.Forbidden(reason, message)
            404 -> if (reason == ErrorReason.TransactionNotFound) AppError.Conflict(reason, message) else AppError.NotFound(message)
            409 -> AppError.Conflict(reason ?: inferConflict(message), message)
            410 -> AppError.Gone(reason, message)
            429 -> AppError.RateLimited(retryAfterSeconds(retryAfter, now))
            503 -> AppError.Unavailable(reason, message)
            else -> AppError.Server(status, message)
        }
    }

    fun inferConflict(message: String): ErrorReason = when {
        message.contains("already used for another message", ignoreCase = true) -> ErrorReason.RequestMismatch
        message.contains("still running", ignoreCase = true) -> ErrorReason.RequestRunning
        message.contains("Another reply is running", ignoreCase = true) -> ErrorReason.ConversationBusy
        message.contains("Connect Gmail", ignoreCase = true) -> ErrorReason.MailboxNotConnected
        message.contains("interrupted", ignoreCase = true) -> ErrorReason.LeaseLost
        else -> ErrorReason.Unknown
    }

    fun retryAfterSeconds(value: String?, now: Instant = Instant.now()): Long? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            Duration.between(now, at).seconds.coerceIn(0, 86_400)
        }.getOrNull()
    }

    /**
     * A failure before the request could leave the device is certain; anything later (timeouts, resets)
     * means a non-idempotent request may have been executed.
     */
    fun fromException(e: Throwable, idempotent: Boolean): AppError = when (e) {
        is ContractException, is SerializationException, is SseProtocolException -> AppError.Protocol(e.message ?: "Unexpected response")
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLHandshakeException -> AppError.Network(uncertain = false)
        is InterruptedIOException, is IOException -> AppError.Network(uncertain = !idempotent)
        else -> AppError.Protocol("Unexpected response")
    }

    private fun defaultMessage(status: Int) = when (status) {
        in 500..599 -> "The server had a problem. Refresh before retrying."
        else -> "Request failed ($status)."
    }
}
