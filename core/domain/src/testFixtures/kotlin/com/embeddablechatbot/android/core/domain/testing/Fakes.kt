package com.embeddablechatbot.android.core.domain.testing

import com.embeddablechatbot.android.core.domain.model.Account
import com.embeddablechatbot.android.core.domain.model.ActionFilter
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.ConnectLaunch
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import com.embeddablechatbot.android.core.domain.model.Conversation
import com.embeddablechatbot.android.core.domain.model.IdGenerator
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailActionKind
import com.embeddablechatbot.android.core.domain.model.MailActionStatus
import com.embeddablechatbot.android.core.domain.model.MailConnection
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.OrgRole
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.model.PendingSend
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.Role
import com.embeddablechatbot.android.core.domain.model.SendDraft
import com.embeddablechatbot.android.core.domain.model.SendEvent
import com.embeddablechatbot.android.core.domain.model.Turn
import com.embeddablechatbot.android.core.domain.model.TurnStatus
import com.embeddablechatbot.android.core.domain.model.UserPreferences
import com.embeddablechatbot.android.core.domain.repository.AccountDataCleaner
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConnectivityMonitor
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PendingConnectStore
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class FixedClock(var instant: Instant = Instant.parse("2026-11-18T00:00:00Z")) : Clock {
    override fun now(): Instant = instant
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    var count = 0
        private set

    override fun newId(): String = "$prefix-${++count}"
}

fun conversation(id: String = "c1", lastTurn: Turn? = null, title: String = "Title") = Conversation(
    id = id, chatbotId = "cb_test", title = title,
    createdAt = Instant.parse("2026-11-17T00:00:00Z"), updatedAt = Instant.parse("2026-11-17T00:00:01Z"),
    revision = "1", lastTurn = lastTurn,
)

fun turn(requestId: String, status: TurnStatus = TurnStatus.Completed, text: String = "reply", error: String? = null) =
    Turn(requestId, status, text, emptyList(), error)

fun message(id: String, conversationId: String = "c1", role: Role = Role.User, sequence: String = "1", requestId: String? = null) =
    Message(id, conversationId, role, "text $id", emptyList(), sequence, Instant.parse("2026-11-17T00:00:00Z"), requestId, TurnStatus.Completed)

/** In-memory conversation repository with scriptable server responses. */
class FakeConversationRepository : ConversationRepository {
    val conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val pending = MutableStateFlow<Map<String, PendingSend>>(emptyMap())
    val submissions = mutableListOf<Triple<String, String, String>>() // conversationId, requestId, text
    val created = mutableListOf<String>()

    /** Responses for submitMessage, consumed in order; default completes successfully. */
    val submitResults = ArrayDeque<Outcome<Turn>>()
    var submitGate: CompletableDeferred<Unit>? = null
    var syncResult: (String) -> Outcome<Conversation> = { id -> Outcome.Ok(conversations.value.firstOrNull { it.id == id } ?: conversation(id)) }
    var createResult: (String) -> Outcome<Conversation> = { Outcome.Ok(conversation(it)) }
    var refreshResult: Outcome<Boolean> = Outcome.Ok(false)
    var syncCount = 0
        private set

    override fun observeConversations(): Flow<List<Conversation>> = conversations
    override suspend fun refreshConversations(): Outcome<Boolean> = refreshResult
    override suspend fun loadMoreConversations(): Outcome<Boolean> = Outcome.Ok(false)
    override suspend fun createConversation(id: String): Outcome<Conversation> {
        created += id
        return createResult(id).also { if (it is Outcome.Ok) conversations.value = conversations.value.filter { c -> c.id != id } + it.value }
    }
    override suspend fun deleteConversation(id: String): Outcome<Unit> {
        conversations.value = conversations.value.filter { it.id != id }
        return Outcome.Ok(Unit)
    }
    override suspend fun getConversation(id: String): Outcome<Conversation> = syncResult(id)
    override fun observeMessages(conversationId: String): Flow<List<Message>> = messages.map { it[conversationId].orEmpty() }
    override suspend fun syncMessages(conversationId: String): Outcome<Conversation> {
        syncCount++
        return syncResult(conversationId)
    }
    override suspend fun committedRequestIds(conversationId: String): Set<String> =
        messages.value[conversationId].orEmpty().mapNotNull { it.requestId }.toSet()

    override suspend fun submitMessage(
        conversationId: String, requestId: String, text: String, emailManager: Boolean, stream: Boolean, onEvent: (SendEvent) -> Unit,
    ): Outcome<Turn> {
        submissions += Triple(conversationId, requestId, text)
        submitGate?.await()
        val result = submitResults.removeFirstOrNull() ?: Outcome.Ok(turn(requestId))
        if (result is Outcome.Ok) onEvent(SendEvent.Done(result.value))
        return result
    }

    override fun observePendingSends(conversationId: String): Flow<List<PendingSend>> =
        pending.map { all -> all.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt } }
    override suspend fun pendingSends(conversationId: String): List<PendingSend> =
        pending.value.values.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
    override suspend fun allPendingSends(): List<PendingSend> = pending.value.values.toList()
    override suspend fun savePendingSend(pending: PendingSend) { this.pending.value = this.pending.value + (pending.requestId to pending) }
    override suspend fun deletePendingSend(requestId: String) { pending.value = pending.value - requestId }
    override suspend fun unconfirmedConversationIds(): List<String> = emptyList()
}

fun mailStatus(connected: Boolean = true, eligible: Boolean = true, pending: Int = 0) = MailStatus(
    provider = "gmail", serverReady = true, eligible = eligible, role = if (eligible) OrgRole.Owner else OrgRole.Member,
    connection = if (connected) MailConnection("me@example.test", ConnectionStatus.Active) else null, pendingApprovals = pending,
)

fun mailAction(id: String = "a1", status: MailActionStatus = MailActionStatus.Pending, kind: MailActionKind = MailActionKind.Send) = MailAction(
    id, kind, "Send \"Hi\" to x@example.test", status, null, Instant.parse("2026-11-17T00:00:00Z"), null, "x@example.test", "Hi", null,
)

fun sendDetail(id: String = "a1", version: String? = "v1", status: MailActionStatus = MailActionStatus.Pending, body: String = "Full body") =
    MailActionDetail(
        mailAction(id, status), "me@example.test", version,
        SendDraft("d1", listOf("x@example.test"), listOf("cc@example.test"), emptyList(), "Hi", body, emptyList(), true), null,
    )

class FakeMailRepository : MailRepository {
    var statusResult: Outcome<MailStatus> = Outcome.Ok(mailStatus())
    var startResult: Outcome<ConnectLaunch> = Outcome.Ok(ConnectLaunch("tx-1", "https://accounts.google.com/o/oauth2/v2/auth?x", Instant.parse("2026-11-18T00:10:00Z")))
    var completeResult: Outcome<MailStatus> = Outcome.Ok(mailStatus())
    var actionsResult: Outcome<List<MailAction>> = Outcome.Ok(emptyList())
    var detailResult: Outcome<MailActionDetail> = Outcome.Ok(sendDetail())
    val approveResults = ArrayDeque<Outcome<MailAction>>()
    var approveGate: CompletableDeferred<Unit>? = null
    val approvals = mutableListOf<Pair<String, String>>()
    val rejections = mutableListOf<String>()
    val challenges = mutableListOf<String>()
    val completions = mutableListOf<Triple<String, String, String>>()
    var disconnects = 0

    override suspend fun status(chatbotId: String) = statusResult
    override suspend fun startConnect(chatbotId: String, verifierChallenge: String): Outcome<ConnectLaunch> {
        challenges += verifierChallenge
        return startResult
    }
    override suspend fun completeConnect(transactionId: String, code: String, verifier: String): Outcome<MailStatus> {
        completions += Triple(transactionId, code, verifier)
        return completeResult
    }
    override suspend fun disconnect(): Outcome<Unit> { disconnects++; return Outcome.Ok(Unit) }
    override suspend fun actions(filter: ActionFilter) = actionsResult
    override suspend fun actionDetail(id: String) = detailResult
    override suspend fun approve(id: String, version: String): Outcome<MailAction> {
        approvals += id to version
        approveGate?.await()
        return approveResults.removeFirstOrNull() ?: Outcome.Ok(mailAction(id, MailActionStatus.Done))
    }
    override suspend fun reject(id: String): Outcome<MailAction> {
        rejections += id
        return Outcome.Ok(mailAction(id, MailActionStatus.Rejected))
    }
}

class FakePendingConnectStore : PendingConnectStore {
    var value: PendingConnect? = null
    override suspend fun save(pending: PendingConnect) { value = pending }
    override suspend fun load(): PendingConnect? = value
    override suspend fun clear() { value = null }
}

class FakeAuthRepository(initial: AuthState = AuthState.SignedIn(Account("user-1", "me@example.test"))) : AuthRepository {
    val state = MutableStateFlow(initial)
    override val authState: StateFlow<AuthState> = state.asStateFlow()
    var signOuts = 0
    override suspend fun restore() = Unit
    override suspend fun signIn(email: String, password: String): Outcome<Account> =
        Outcome.Ok(Account("user-1", email)).also { state.value = AuthState.SignedIn(it.value) }
    override suspend fun signOut() { signOuts++; state.value = AuthState.SignedOut() }
}

class FakeConfigRepository(
    initial: PlatformConfig = PlatformConfig("https://platform.test", "https://auth.test", "sb_publishable_x", "cb_testbot"),
    override val allowCleartext: Boolean = false,
) : ConfigRepository {
    private val state = MutableStateFlow(initial)
    override val config: StateFlow<PlatformConfig> = state.asStateFlow()
    override suspend fun update(config: PlatformConfig) { state.value = config }
}

class FakePreferencesRepository(initial: UserPreferences = UserPreferences(streamReplies = false)) : PreferencesRepository {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<UserPreferences> = state
    override suspend fun update(transform: (UserPreferences) -> UserPreferences) { state.value = transform(state.value) }
}

class FakeConnectivity(initial: Boolean = true) : ConnectivityMonitor {
    val state = MutableStateFlow(initial)
    override val online: Flow<Boolean> = state
}

class FakeChatbotRepository : ChatbotRepository {
    var result: Outcome<ChatbotInfo>? = null
    override suspend fun chatbot(chatbotId: String): Outcome<ChatbotInfo> = result ?: Outcome.Ok(ChatbotInfo(chatbotId, "Mail bot", "Hello", emptyList()))
}

class FakeCleaner : AccountDataCleaner {
    var clears = 0
    override suspend fun clearAccountData() { clears++ }
}

val networkUncertain: Outcome<Nothing> = Outcome.Err(AppError.Network(uncertain = true))
