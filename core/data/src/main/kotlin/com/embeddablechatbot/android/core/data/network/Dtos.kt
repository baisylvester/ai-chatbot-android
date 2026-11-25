package com.embeddablechatbot.android.core.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Unknown fields are ignored so additive server changes don't break older apps. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// ---- Client API (docs/CLIENT_API.md). IDs, revision, sequence and cursors stay strings. ----

@Serializable
data class CitationDto(val n: Int, val title: String = "", val sourceUrl: String? = null, val snippet: String = "")

@Serializable
data class TurnDto(
    val requestId: String,
    val status: String,
    val text: String = "",
    val citations: List<CitationDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ConversationDto(
    val id: String,
    val chatbotId: String,
    val title: String = "",
    val createdAt: String,
    val updatedAt: String,
    val revision: String,
    val lastTurn: TurnDto? = null,
)

@Serializable
data class ConversationPageDto(val conversations: List<ConversationDto>, val nextCursor: String? = null)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val text: String,
    val citations: List<CitationDto> = emptyList(),
    val sequence: String,
    val createdAt: String,
    val requestId: String? = null,
    val status: String = "completed",
)

@Serializable
data class MessagePageDto(
    val conversation: ConversationDto,
    val messages: List<MessageDto>,
    val nextCursor: String,
    val hasMore: Boolean,
)

@Serializable
data class CreateConversationBody(val id: String, val chatbotId: String)

@Serializable
data class SendMessageBody(
    val requestId: String,
    val message: String,
    val stream: Boolean,
    val emailManager: Boolean? = null,
)

@Serializable
data class SendResultDto(val turn: TurnDto)

@Serializable
data class ChatbotConfigDto(
    val chatbotId: String,
    val name: String = "",
    val welcomeMessage: String = "",
    val suggestedQuestions: List<String> = emptyList(),
    val knowledge: KnowledgeInfoDto? = null,
)

@Serializable
data class KnowledgeInfoDto(
    val state: String = "unavailable",
    val sourceChatbotId: String = "",
    val sourceName: String = "",
    val readyDocuments: Int = 0,
    val processingDocuments: Int = 0,
    val chunks: Int = 0,
    val updatedAt: String? = null,
    val shared: Boolean = false,
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDto? = null)

@Serializable
data class ApiErrorDto(val code: String? = null, val message: String? = null, val reason: String? = null)

// ---- SSE payloads ----

@Serializable
data class StreamEventDto(
    val type: String? = null,
    val requestId: String? = null,
    val text: String? = null,
    val citations: List<CitationDto>? = null,
    val name: String? = null,
    val phase: String? = null,
    val steps: List<String>? = null,
    val waiting: Int? = null,
    val failed: Boolean? = null,
    val turn: TurnDto? = null,
    val code: String? = null,
    val message: String? = null,
)

// ---- Native mail API ----

@Serializable
data class MailConnectionDto(val email: String, val status: String)

@Serializable
data class MailStatusDto(
    val provider: String = "gmail",
    val serverReady: Boolean,
    val eligible: Boolean,
    val role: String? = null,
    val connection: MailConnectionDto? = null,
    val pendingApprovals: Int = 0,
)

@Serializable
data class ConnectBody(val chatbotId: String, val verifierChallenge: String)

@Serializable
data class ConnectLaunchDto(val transactionId: String, val authorizationUrl: String, val expiresAt: String)

@Serializable
data class CompleteConnectBody(val transactionId: String, val code: String, val verifier: String)

@Serializable
data class MailActionDto(
    val id: String,
    val kind: String,
    val summary: String = "",
    val status: String,
    val error: String? = null,
    val createdAt: String,
    val resolvedAt: String? = null,
    val to: String? = null,
    val subject: String? = null,
    val messageCount: Int? = null,
    val scheduledFor: String? = null,
)

@Serializable
data class MailActionListDto(val actions: List<MailActionDto>)

@Serializable
data class MailActionResultDto(val action: MailActionDto)

@Serializable
data class AttachmentDto(val filename: String = "", val mimeType: String = "", val size: Long = 0)

@Serializable
data class SendDraftDto(
    val draftId: String,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    val attachments: List<AttachmentDto> = emptyList(),
    val draftExists: Boolean = true,
)

@Serializable
data class MailSummaryDto(
    val id: String,
    val from: String = "",
    val subject: String = "",
    val date: String = "",
    val snippet: String = "",
)

@Serializable
data class TrashTargetsDto(val messages: List<MailSummaryDto> = emptyList(), val missing: List<String> = emptyList())

@Serializable
data class MailActionDetailDto(
    val action: MailActionDto,
    val account: String = "",
    val version: String? = null,
    val send: SendDraftDto? = null,
    val trash: TrashTargetsDto? = null,
)

@Serializable
data class ApproveBody(val version: String, val sendAt: String? = null)

// ---- Supabase Auth (GoTrue) ----

@Serializable
data class PasswordGrantBody(val email: String, val password: String)

@Serializable
data class RefreshGrantBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class AuthUserDto(val id: String, val email: String? = null)

@Serializable
data class AuthTokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: AuthUserDto,
)

@Serializable
data class AuthErrorDto(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val msg: String? = null,
    val message: String? = null,
)

@Serializable
data class NativeAccountDto(val chatbotId: String)
