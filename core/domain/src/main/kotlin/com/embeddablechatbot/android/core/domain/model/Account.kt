package com.embeddablechatbot.android.core.domain.model

/** Public client configuration. Not a credential: never place private keys here. */
data class PlatformConfig(
    val platformUrl: String,
    val supabaseUrl: String,
    val supabasePublishableKey: String,
    val chatbotId: String,
) {
    val isComplete: Boolean
        get() = platformUrl.isNotBlank() && supabaseUrl.isNotBlank() && supabasePublishableKey.isNotBlank() 

    companion object {
        private val CHATBOT_ID = Regex("^cb_[A-Za-z0-9_-]{4,76}$")

        /** Returns a user-facing problem, or null when valid. HTTPS is required unless [allowCleartext] (debug builds). */
        fun validate(config: PlatformConfig, allowCleartext: Boolean): String? {
            fun urlProblem(label: String, value: String): String? {
                val v = value.trim()
                if (v.isEmpty()) return "$label is required."
                if (v.startsWith("https://")) return null
                if (allowCleartext && v.startsWith("http://")) return null
                return "$label must start with https://."
            }
            val keyProblem = if (config.supabasePublishableKey.isBlank()) "Supabase publishable key is required." else null
            val botProblem = if (config.chatbotId.isNotBlank() && !CHATBOT_ID.matches(config.chatbotId.trim())) "Enter the chatbot's public ID (cb_…)." else null
            return urlProblem("Platform URL", config.platformUrl)
                ?: urlProblem("Supabase URL", config.supabaseUrl)
                ?: keyProblem
                ?: botProblem
        }
    }
}

data class Account(val userId: String, val email: String)

enum class SignOutReason { UserRequested, SessionExpired }

sealed interface AuthState {
    data object Restoring : AuthState
    data class SignedOut(val reason: SignOutReason? = null) : AuthState
    data class SignedIn(val account: Account) : AuthState
}

data class ChatbotInfo(
    val chatbotId: String,
    val name: String,
    val welcomeMessage: String,
    val suggestedQuestions: List<String>,
    val knowledge: KnowledgeInfo? = null,
)

enum class KnowledgeState { Ready, Processing, Empty, Unavailable }

data class KnowledgeInfo(
    val state: KnowledgeState,
    val sourceChatbotId: String,
    val sourceName: String,
    val readyDocuments: Int,
    val processingDocuments: Int,
    val chunks: Int,
    val updatedAt: java.time.Instant?,
    val shared: Boolean,
)

data class UserPreferences(
    val streamReplies: Boolean = true,
    val mailToolsEnabled: Boolean = true,
    val approvalReminders: Boolean = false,
    val favoritePrompts: Set<String> = emptySet(),
    val onboardedAccounts: Set<String> = emptySet(),
)
