package com.embeddablechatbot.android.core.domain.model

import java.time.Instant

enum class TurnStatus {
    Running, Completed, Failed, Interrupted;

    val isTerminal: Boolean get() = this != Running
}

enum class Role { User, Assistant }

data class Citation(val n: Int, val title: String, val sourceUrl: String?, val snippet: String)

data class Turn(
    val requestId: String,
    val status: TurnStatus,
    val text: String,
    val citations: List<Citation>,
    val error: String?,
)

/** `revision` is a decimal string: never parse it through floating point. */
data class Conversation(
    val id: String,
    val chatbotId: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: String,
    val lastTurn: Turn?,
)

/** `sequence` is a decimal string; order with [DecimalStrings.compare]. */
data class Message(
    val id: String,
    val conversationId: String,
    val role: Role,
    val text: String,
    val citations: List<Citation>,
    val sequence: String,
    val createdAt: Instant,
    val requestId: String?,
    val status: TurnStatus,
)

data class MessagePage(
    val conversation: Conversation,
    val messages: List<Message>,
    val nextCursor: String,
    val hasMore: Boolean,
)

/** `nextCursor` is opaque: pass it back unchanged. */
data class ConversationPage(val conversations: List<Conversation>, val nextCursor: String?)

enum class PendingState {
    /** Being submitted by this process right now. After process death it becomes [Uncertain]. */
    Submitting,

    /** The request may or may not have reached the server. Read server state before anything else. */
    Uncertain,

    /** The server accepted it and a reply is running (on this or another device). */
    Running,

    /** The server definitely did not accept it. Retrying with the SAME request ID is safe. */
    NotAccepted,
}

/**
 * A user send whose outcome is not yet known. The request ID and the exact normalized text are kept
 * together until the server's committed state resolves it.
 */
data class PendingSend(
    val requestId: String,
    val conversationId: String,
    val text: String,
    val emailManager: Boolean,
    val createdAt: Instant,
    val state: PendingState,
    val errorMessage: String? = null,
)

/** Events from a reply in progress. Streamed text is provisional until [Done]. */
sealed interface SendEvent {
    data class Accepted(val requestId: String) : SendEvent
    data class Token(val text: String) : SendEvent
    data class Sources(val citations: List<Citation>) : SendEvent
    data class Tool(val name: String, val phase: String, val steps: List<String>, val waiting: Int?, val failed: Boolean) : SendEvent
    data class Done(val turn: Turn) : SendEvent
}

/** Decimal-string helpers. Sequences and cursors are compared as integers without lossy conversion. */
object DecimalStrings {
    private val DECIMAL = Regex("^(0|[1-9][0-9]*)$")

    fun isValid(value: String): Boolean = DECIMAL.matches(value)

    fun compare(a: String, b: String): Int =
        if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
}
