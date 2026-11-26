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
    /** Each signed-in account's own theme, by account user id. Kept after sign-out so it returns on sign-in. */
    val accountThemes: Map<String, ThemeChoice> = emptyMap(),
) {
    /** The theme for this account, or the default when it has none (or nobody is signed in). */
    fun themeFor(userId: String?): ThemeChoice = userId?.let(accountThemes::get) ?: ThemeChoice()
}

/** App color palettes. [Device] uses the phone's wallpaper colors (Android 12+), else the default. */
enum class ThemePalette(val id: String, val title: String) {
    BlueMint("blue_mint", "Blue & mint"),
    Violet("violet", "Violet"),
    Forest("forest", "Forest"),
    Sunset("sunset", "Sunset"),
    Rose("rose", "Rose"),
    Graphite("graphite", "Graphite"),
    Device("device", "Match my wallpaper");

    companion object {
        fun fromId(id: String?): ThemePalette = entries.firstOrNull { it.id == id } ?: BlueMint
    }
}

enum class ThemeMode { System, Light, Dark }

data class ThemeChoice(val palette: ThemePalette = ThemePalette.BlueMint, val mode: ThemeMode = ThemeMode.System) {
    /** Stored as `palette:mode`, for example `forest:Dark`. Unknown parts fall back to defaults. */
    fun encode(): String = "${palette.id}:${mode.name}"

    companion object {
        fun decode(value: String): ThemeChoice {
            val (palette, mode) = (value.split(':') + listOf("", "")).take(2)
            return ThemeChoice(ThemePalette.fromId(palette), ThemeMode.entries.firstOrNull { it.name == mode } ?: ThemeMode.System)
        }
    }
}
