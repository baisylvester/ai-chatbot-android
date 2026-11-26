package com.embeddablechatbot.android.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.embeddablechatbot.android.core.data.auth.SecretStore
import com.embeddablechatbot.android.core.data.db.ChatDao
import com.embeddablechatbot.android.core.data.db.TransactionRunner
import com.embeddablechatbot.android.core.data.network.ApiJson
import com.embeddablechatbot.android.core.data.network.ClientApi
import com.embeddablechatbot.android.core.data.network.toDomain
import com.embeddablechatbot.android.core.domain.model.ActionFilter
import com.embeddablechatbot.android.core.domain.model.ChatbotInfo
import com.embeddablechatbot.android.core.domain.model.ConnectLaunch
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PendingConnect
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.UserPreferences
import com.embeddablechatbot.android.core.domain.model.map
import com.embeddablechatbot.android.core.domain.repository.AccountDataCleaner
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PendingConnectStore
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Build-time defaults supplied by the app module (BuildConfig). */
data class ConfigDefaults(val config: PlatformConfig, val allowCleartext: Boolean, val migrateLocalPlatform: Boolean = false)

private val Context.configStore: DataStore<Preferences> by preferencesDataStore(name = "platform_config")
private val Context.prefsStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/** Non-secret platform selection. Loaded once at startup before the session is restored. */
@Singleton
class ConfigRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val defaults: ConfigDefaults,
) : ConfigRepository {
    private val state = MutableStateFlow(defaults.config)
    override val config: StateFlow<PlatformConfig> = state.asStateFlow()
    override val allowCleartext: Boolean = defaults.allowCleartext

    suspend fun load() {
        val p = if (defaults.migrateLocalPlatform) context.configStore.edit { current ->
            if (current[LOCAL_PLATFORM_UPGRADE] != true) {
                val upgraded = migrateLocalPlatform(readConfig(current), defaults.config, enabled = true)
                current[PLATFORM] = upgraded.platformUrl
                current[LOCAL_PLATFORM_UPGRADE] = true
            }
        } else context.configStore.data.first()
        state.value = readConfig(p)
    }

    private fun readConfig(p: Preferences) = PlatformConfig(
            platformUrl = p[PLATFORM] ?: defaults.config.platformUrl,
            supabaseUrl = p[SUPABASE] ?: defaults.config.supabaseUrl,
            supabasePublishableKey = p[KEY] ?: defaults.config.supabasePublishableKey,
            chatbotId = p[CHATBOT] ?: defaults.config.chatbotId,
        )

    override suspend fun update(config: PlatformConfig) {
        val clean = config.copy(
            platformUrl = config.platformUrl.trim().trimEnd('/'),
            supabaseUrl = config.supabaseUrl.trim().trimEnd('/'),
            supabasePublishableKey = config.supabasePublishableKey.trim(),
            chatbotId = config.chatbotId.trim(),
        )
        context.configStore.edit {
            it[PLATFORM] = clean.platformUrl
            it[SUPABASE] = clean.supabaseUrl
            it[KEY] = clean.supabasePublishableKey
            it[CHATBOT] = clean.chatbotId
        }
        state.value = clean
    }

    private companion object {
        val PLATFORM = stringPreferencesKey("platform_url")
        val SUPABASE = stringPreferencesKey("supabase_url")
        val KEY = stringPreferencesKey("supabase_publishable_key")
        val CHATBOT = stringPreferencesKey("chatbot_id")
        val LOCAL_PLATFORM_UPGRADE = booleanPreferencesKey("local_platform_upgrade_v1")
    }
}

@Singleton
class PreferencesRepositoryImpl @Inject constructor(@param:ApplicationContext private val context: Context) : PreferencesRepository {
    override val preferences: Flow<UserPreferences> = context.prefsStore.data.map(::read)

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.prefsStore.edit { p ->
            val next = transform(read(p))
            p[STREAM] = next.streamReplies
            p[MAIL_TOOLS] = next.mailToolsEnabled
            p[REMINDERS] = next.approvalReminders
            p[FAVORITES] = next.favoritePrompts
            p[ONBOARDED] = next.onboardedAccounts
            p[THEMES] = encodeThemes(next.accountThemes)
        }
    }

    private fun read(p: androidx.datastore.preferences.core.Preferences) = UserPreferences(
        streamReplies = p[STREAM] ?: true,
        mailToolsEnabled = p[MAIL_TOOLS] ?: true,
        approvalReminders = p[REMINDERS] ?: false,
        favoritePrompts = p[FAVORITES] ?: emptySet(),
        onboardedAccounts = p[ONBOARDED] ?: emptySet(),
        accountThemes = decodeThemes(p[THEMES] ?: emptySet()),
    )

    private companion object {
        val STREAM = booleanPreferencesKey("stream_replies")
        val MAIL_TOOLS = booleanPreferencesKey("mail_tools")
        val REMINDERS = booleanPreferencesKey("approval_reminders")
        val FAVORITES = stringSetPreferencesKey("favorite_prompts")
        val ONBOARDED = stringSetPreferencesKey("onboarded_accounts")
        val THEMES = stringSetPreferencesKey("account_themes")
    }
}

/** Account themes are stored as `userId=palette:mode` entries; malformed entries are skipped. */
internal fun encodeThemes(themes: Map<String, com.embeddablechatbot.android.core.domain.model.ThemeChoice>): Set<String> =
    themes.mapTo(mutableSetOf()) { (user, choice) -> "$user=${choice.encode()}" }

internal fun decodeThemes(entries: Set<String>): Map<String, com.embeddablechatbot.android.core.domain.model.ThemeChoice> =
    entries.mapNotNull { entry ->
        val user = entry.substringBefore('=', "")
        if (user.isBlank()) null else user to com.embeddablechatbot.android.core.domain.model.ThemeChoice.decode(entry.substringAfter('='))
    }.toMap()

@Singleton
class ChatbotRepositoryImpl @Inject constructor(private val api: ClientApi) : ChatbotRepository {
    override suspend fun chatbot(chatbotId: String): Outcome<ChatbotInfo> = api.chatbot(chatbotId).map { it.value.toDomain() }
}

@Singleton
class MailRepositoryImpl @Inject constructor(private val api: ClientApi) : MailRepository {
    override suspend fun status(chatbotId: String): Outcome<MailStatus> = api.mailStatus(chatbotId).map { it.value.toDomain() }
    override suspend fun startConnect(chatbotId: String, verifierChallenge: String): Outcome<ConnectLaunch> =
        api.startConnect(chatbotId, verifierChallenge).map { it.value.toDomain() }
    override suspend fun completeConnect(transactionId: String, code: String, verifier: String): Outcome<MailStatus> =
        api.completeConnect(transactionId, code, verifier).map { it.value.toDomain() }
    override suspend fun disconnect(): Outcome<Unit> = api.disconnectMail().map { }
    override suspend fun actions(filter: ActionFilter): Outcome<List<MailAction>> =
        api.mailActions(filter.name.lowercase()).map { r -> r.value.actions.map { it.toDomain() } }
    override suspend fun actionDetail(id: String): Outcome<MailActionDetail> = api.mailAction(id).map { it.value.toDomain() }
    override suspend fun approve(id: String, version: String, sendAt: java.time.Instant?): Outcome<MailAction> = api.approve(id, version, sendAt?.toString()).map { it.value.action.toDomain() }
    override suspend fun unschedule(id: String): Outcome<MailAction> = api.unschedule(id).map { it.value.action.toDomain() }
    override suspend fun reject(id: String): Outcome<MailAction> = api.reject(id).map { it.value.action.toDomain() }
}

@Serializable
private data class PendingConnectJson(val transactionId: String, val verifier: String, val expiresAt: Long, val accountId: String)

/** The connect verifier is a secret until completion: it lives in the encrypted store. */
@Singleton
class PendingConnectStoreImpl @Inject constructor(private val secrets: SecretStore) : PendingConnectStore {
    override suspend fun save(pending: PendingConnect) = secrets.write(
        KEY,
        ApiJson.encodeToString(
            PendingConnectJson.serializer(),
            PendingConnectJson(pending.transactionId, pending.verifier, pending.expiresAt.toEpochMilli(), pending.accountId),
        ),
    )

    override suspend fun load(): PendingConnect? = secrets.read(KEY)?.let { raw ->
        runCatching { ApiJson.decodeFromString(PendingConnectJson.serializer(), raw) }.getOrNull()
            ?.let { PendingConnect(it.transactionId, it.verifier, Instant.ofEpochMilli(it.expiresAt), it.accountId) }
    }

    override suspend fun clear() = secrets.write(KEY, null)

    private companion object {
        const val KEY = "pending_mail_connect"
    }
}

@Singleton
class AccountDataCleanerImpl @Inject constructor(private val dao: ChatDao, private val transaction: TransactionRunner, private val mailCache: MailCache) : AccountDataCleaner {
    override suspend fun clearAccountData() {
        mailCache.clear()
        clearChat()
    }
    private suspend fun clearChat() = transaction {
        dao.clearPending()
        dao.clearUnconfirmed()
        dao.clearMessages()
        dao.clearSyncStates()
        dao.clearListStates()
        dao.clearConversations()
    }
}
