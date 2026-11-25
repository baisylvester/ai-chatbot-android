package com.embeddablechatbot.android.core.data.network

import com.embeddablechatbot.android.core.data.auth.AccessTokenProvider
import com.embeddablechatbot.android.core.data.auth.TokenResult
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A response tagged with the account epoch it was obtained under. */
data class Authed<T>(val value: T, val epoch: Long)

/**
 * HTTP client for `/api/v1/client`. Authenticates with the Supabase access token (never an Origin header
 * or cookies), refreshes once on 401, and rejects responses that belong to a different account.
 */
@Singleton
class ClientApi @Inject constructor(
    private val client: OkHttpClient,
    private val tokens: AccessTokenProvider,
    private val config: ConfigRepository,
) {
    private val jsonType = "application/json".toMediaType()

    /** Recover stale pooled connections only for reads; mutations must never be replayed. */
    private val readClient: OkHttpClient by lazy { client.newBuilder().retryOnConnectionFailure(true).build() }

    /** The documented JSON send can take up to four minutes. */
    private val longClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(270, TimeUnit.SECONDS).callTimeout(285, TimeUnit.SECONDS).build()
    }

    /** Streams may be silent while a mailbox tool runs; the server bounds a turn at four minutes. */
    private val streamClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(250, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl? {
        val base = config.config.value.platformUrl.trim().trimEnd('/')
        val parsed = "$base/api/v1/client$path".toHttpUrlOrNull() ?: return null
        if (!config.allowCleartext && !parsed.isHttps) return null
        return parsed.newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    }

    suspend fun prepareAccount() = send("POST", "/onboarding", "{}", NativeAccountDto.serializer(), idempotent = true)

    suspend fun chatbot(chatbotId: String) =
        get("/chatbots/${seg(chatbotId)}", emptyMap(), ChatbotConfigDto.serializer())

    suspend fun listConversations(chatbotId: String, before: String?) =
        get("/conversations", buildMap { put("chatbotId", chatbotId); before?.let { put("before", it) } }, ConversationPageDto.serializer())

    /** Idempotent by design: the same client-generated [id] may be resent after an uncertain result. */
    suspend fun createConversation(id: String, chatbotId: String) =
        send("POST", "/conversations", ApiJson.encodeToString(CreateConversationBody.serializer(), CreateConversationBody(id, chatbotId)), ConversationDto.serializer(), idempotent = true)

    suspend fun conversation(id: String) = get("/conversations/${seg(id)}", emptyMap(), ConversationDto.serializer())

    suspend fun deleteConversation(id: String): Outcome<Authed<Unit>> = execute("DELETE", url("/conversations/${seg(id)}"), null, idempotent = true, client) { Unit }

    suspend fun messages(id: String, after: String) =
        get("/conversations/${seg(id)}/messages", mapOf("after" to after), MessagePageDto.serializer())

    /** Waits for the committed turn. HTTP 200 may still carry a failed or interrupted turn. */
    suspend fun sendJson(conversationId: String, requestId: String, text: String, emailManager: Boolean): Outcome<Authed<Turn>> {
        val body = ApiJson.encodeToString(SendMessageBody.serializer(), SendMessageBody(requestId, text, false, emailManager.takeIf { it }))
        return when (val r = execute("POST", url("/conversations/${seg(conversationId)}/messages"), body, idempotent = false, longClient) { res ->
            ApiJson.decodeFromString(SendResultDto.serializer(), res.body.string()).turn.toDomain()
        }) {
            is Outcome.Ok -> validateTurn(r.value.value, requestId)?.let { Outcome.Err(it) } ?: r
            is Outcome.Err -> r
        }
    }

    /**
     * POST + SSE. Text is provisional until `done`; EOF without `done`, or an `error` event, is an
     * uncertain outcome that must be reconciled from server state (mailbox tools may already have run).
     */
    suspend fun sendStream(
        conversationId: String,
        requestId: String,
        text: String,
        emailManager: Boolean,
        onEvent: (SendEvent) -> Unit,
    ): Outcome<Authed<Turn>> {
        val body = ApiJson.encodeToString(SendMessageBody.serializer(), SendMessageBody(requestId, text, true, emailManager.takeIf { it }))
        val result = execute("POST", url("/conversations/${seg(conversationId)}/messages"), body, idempotent = false, streamClient, accept = "text/event-stream") { res ->
            readStream(res, requestId, onEvent)
        }
        return when (result) {
            is Outcome.Ok -> when (val outcome = result.value.value) {
                is StreamOutcome.Done -> Outcome.Ok(Authed(outcome.turn, result.value.epoch))
                is StreamOutcome.Failed -> Outcome.Err(outcome.error)
            }
            is Outcome.Err -> result
        }
    }

    private sealed interface StreamOutcome {
        data class Done(val turn: Turn) : StreamOutcome
        data class Failed(val error: AppError) : StreamOutcome
    }

    private suspend fun readStream(response: Response, requestId: String, onEvent: (SendEvent) -> Unit): StreamOutcome {
        val input = response.body.byteStream()
        val decoder = SseDecoder()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            val events = if (read < 0) decoder.finish() else decoder.feed(buffer, read)
            for (event in events) {
                val dto = ApiJson.decodeFromString(StreamEventDto.serializer(), event.data)
                when (event.event) {
                    "error" -> return StreamOutcome.Failed(AppError.Network(uncertain = true))
                    "done" -> {
                        val turn = dto.turn?.toDomain() ?: return StreamOutcome.Failed(AppError.Protocol("Invalid completion"))
                        validateTurn(turn, requestId)?.let { return StreamOutcome.Failed(it) }
                        onEvent(SendEvent.Done(turn))
                        return StreamOutcome.Done(turn)
                    }
                    else -> dto.toDomain(event.event)?.let(onEvent)
                }
            }
            if (read < 0) return StreamOutcome.Failed(AppError.Network(uncertain = true))
        }
    }

    private fun validateTurn(turn: Turn, requestId: String): AppError? =
        if (turn.requestId != requestId || turn.status == TurnStatus.Running) AppError.Protocol("Invalid completion. Refresh the conversation.") else null

    // ---- Native mail API ----

    suspend fun inbox(folder: String, query: String, cursor: String?, labelId: String? = null) = get("/mail/messages", buildMap {
        put("chatbotId", config.config.value.chatbotId); put("folder", folder); put("query", query); cursor?.let { put("cursor", it) }; labelId?.let { put("labelId", it) }
    }, InboxPageDto.serializer())
    suspend fun mailLabels() = get("/mail/labels", mapOf("chatbotId" to config.config.value.chatbotId), MailLabelListDto.serializer())
    suspend fun createMailLabel(name: String) = send("POST", mailboxPath("labels"), ApiJson.encodeToString(CreateLabelBody.serializer(), CreateLabelBody(name)), MailLabelDto.serializer(), idempotent = false)
    /** Setting the same labels twice has the same result, so a retry is safe. */
    suspend fun relabel(body: RelabelBody) = send("POST", mailboxPath("labels/apply"), ApiJson.encodeToString(RelabelBody.serializer(), body), OkDto.serializer(), idempotent = true)
    suspend fun attachmentBytes(messageId: String, partId: String): Outcome<Authed<Pair<String, ByteArray>>> =
        execute("GET", url("/mail/messages/${seg(messageId)}/attachments/${seg(partId)}", mapOf("chatbotId" to config.config.value.chatbotId)), null, idempotent = true, longClient, accept = "*/*") {
            (it.header("Content-Type") ?: "application/octet-stream").substringBefore(';').trim() to it.body.bytes()
        }
    suspend fun attachmentText(messageId: String, partId: String) =
        get("/mail/messages/${seg(messageId)}/attachments/${seg(partId)}/text", mapOf("chatbotId" to config.config.value.chatbotId), AttachmentTextDto.serializer())
    suspend fun inboxMessage(id: String) = get("/mail/messages/${seg(id)}", mapOf("chatbotId" to config.config.value.chatbotId), OpenMessageDto.serializer())
    private fun mailboxPath(path: String) = "/mail/$path?chatbotId=${seg(config.config.value.chatbotId)}"
    suspend fun changeMail(body: ChangeMailBody) = send("POST", mailboxPath("modify"), ApiJson.encodeToString(ChangeMailBody.serializer(), body), ChangeMailDto.serializer(), idempotent = false)
    suspend fun saveMailDraft(body: SaveDraftBody) = send("POST", mailboxPath("drafts"), ApiJson.encodeToString(SaveDraftBody.serializer(), body), DraftReceiptDto.serializer(), idempotent = false, slow = true)
    suspend fun writeMail(body: WritingBody) = send("POST", "/mail/writing", ApiJson.encodeToString(WritingBody.serializer(), body), WritingDto.serializer(), idempotent = true, slow = true)

    suspend fun mailStatus(chatbotId: String) = get("/mail/status", mapOf("chatbotId" to chatbotId), MailStatusDto.serializer())

    suspend fun startConnect(chatbotId: String, challenge: String) =
        send("POST", "/mail/connect", ApiJson.encodeToString(ConnectBody.serializer(), ConnectBody(chatbotId, challenge)), ConnectLaunchDto.serializer(), idempotent = false)

    suspend fun completeConnect(transactionId: String, code: String, verifier: String) =
        send("POST", "/mail/connect/complete", ApiJson.encodeToString(CompleteConnectBody.serializer(), CompleteConnectBody(transactionId, code, verifier)), MailStatusDto.serializer(), idempotent = true)

    suspend fun disconnectMail(): Outcome<Authed<Unit>> = execute("DELETE", url("/mail/connection"), null, idempotent = true, client) { Unit }

    suspend fun mailActions(filter: String) = get("/mail/actions", mapOf("filter" to filter), MailActionListDto.serializer())

    suspend fun mailAction(id: String) = get("/mail/actions/${seg(id)}", emptyMap(), MailActionDetailDto.serializer())

    /** Not idempotent from the client's view: never retried automatically. The server enforces single use. */
    suspend fun approve(id: String, version: String, sendAt: String? = null) =
        send("POST", "/mail/actions/${seg(id)}/approve", ApiJson.encodeToString(ApproveBody.serializer(), ApproveBody(version, sendAt)), MailActionResultDto.serializer(), idempotent = false, slow = true)

    suspend fun reject(id: String) =
        send("POST", "/mail/actions/${seg(id)}/reject", "{}", MailActionResultDto.serializer(), idempotent = true)

    suspend fun unschedule(id: String) =
        send("POST", "/mail/actions/${seg(id)}/unschedule", "{}", MailActionResultDto.serializer(), idempotent = true)

    // ---- plumbing ----

    private suspend fun <T> get(path: String, query: Map<String, String>, serializer: KSerializer<T>) =
        execute("GET", url(path, query), null, idempotent = true, readClient) { ApiJson.decodeFromString(serializer, it.body.string()) }

    private suspend fun <T> send(method: String, path: String, body: String, serializer: KSerializer<T>, idempotent: Boolean, slow: Boolean = false) =
        execute(method, url(path), body, idempotent, if (slow) longClient else client) { ApiJson.decodeFromString(serializer, it.body.string()) }

    private suspend fun <T> execute(
        method: String,
        url: HttpUrl?,
        body: String?,
        idempotent: Boolean,
        httpClient: OkHttpClient,
        accept: String = "application/json",
        parse: suspend (Response) -> T,
    ): Outcome<Authed<T>> {
        if (url == null) return Outcome.Err(AppError.NotConfigured("The platform URL is missing or not HTTPS."))
        var token = when (val t = tokens.token()) {
            is TokenResult.Token -> t
            TokenResult.SignedOut -> return Outcome.Err(AppError.Unauthorized)
            is TokenResult.Unavailable -> return Outcome.Err(t.error)
        }
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token.accessToken}")
                .header("Accept", accept)
                .method(method, body?.toRequestBody(jsonType))
                .build()
            try {
                val call = httpClient.newCall(request)
                // Cancelling the coroutine also aborts a blocking stream read.
                val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
                val result = try { withContext(Dispatchers.IO) {
                    call.await().use { response ->
                        when {
                            response.code == 401 -> null
                            !response.isSuccessful -> Outcome.Err(
                                HttpErrors.fromResponse(response.code, response.body.string(), response.header("Retry-After")),
                            )
                            response.header("X-Authenticated-User") != token.userId || tokens.epoch != token.epoch ->
                                Outcome.Err(AppError.Unauthorized)
                            else -> Outcome.Ok(Authed(parse(response), token.epoch))
                        }
                    }
                } } finally {
                    cancelHandle?.dispose()
                }
                if (result != null) return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.Err(HttpErrors.fromException(e, idempotent))
            }
            // 401: the request was rejected before any work ran, so one retry with a fresh token is safe.
            if (attempt == 0) {
                token = when (val t = tokens.refreshAfterUnauthorized(token.accessToken)) {
                    is TokenResult.Token -> t
                    TokenResult.SignedOut -> return Outcome.Err(AppError.Unauthorized)
                    is TokenResult.Unavailable -> return Outcome.Err(t.error)
                }
            }
        }
        return Outcome.Err(AppError.Unauthorized)
    }

    private fun seg(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
