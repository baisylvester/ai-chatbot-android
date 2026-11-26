package com.embeddablechatbot.android.core.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.embeddablechatbot.android.core.data.auth.AccessTokenProvider
import com.embeddablechatbot.android.core.data.auth.AuthApi
import com.embeddablechatbot.android.core.data.auth.SecretStore
import com.embeddablechatbot.android.core.data.auth.SessionManager
import com.embeddablechatbot.android.core.data.auth.SupabaseAuthApi
import com.embeddablechatbot.android.core.data.auth.TinkSecretStore
import com.embeddablechatbot.android.core.data.db.ChatDao
import com.embeddablechatbot.android.core.data.db.ChatDatabase
import com.embeddablechatbot.android.core.data.db.TransactionRunner
import com.embeddablechatbot.android.core.data.repository.AccountDataCleanerImpl
import com.embeddablechatbot.android.core.data.repository.ChatbotRepositoryImpl
import com.embeddablechatbot.android.core.data.repository.ConfigRepositoryImpl
import com.embeddablechatbot.android.core.data.repository.ConversationRepositoryImpl
import com.embeddablechatbot.android.core.data.repository.MailRepositoryImpl
import com.embeddablechatbot.android.core.data.repository.PendingConnectStoreImpl
import com.embeddablechatbot.android.core.data.repository.PreferencesRepositoryImpl
import com.embeddablechatbot.android.core.domain.chat.ApplicationScope
import com.embeddablechatbot.android.core.domain.model.Clock
import com.embeddablechatbot.android.core.domain.model.IdGenerator
import com.embeddablechatbot.android.core.domain.repository.AccountDataCleaner
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.PendingConnectStore
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun config(impl: ConfigRepositoryImpl): ConfigRepository
    @Binds abstract fun themeSync(impl: com.embeddablechatbot.android.core.data.repository.ThemeSyncImpl): com.embeddablechatbot.android.core.domain.repository.ThemeSync
    @Binds abstract fun auth(impl: SessionManager): AuthRepository
    @Binds abstract fun registration(impl: SessionManager): com.embeddablechatbot.android.core.domain.repository.RegistrationRepository
    @Binds abstract fun registrationApi(impl: SupabaseAuthApi): com.embeddablechatbot.android.core.data.auth.RegistrationApi
    @Binds abstract fun onboarding(impl: com.embeddablechatbot.android.core.data.repository.OnboardingRepositoryImpl): com.embeddablechatbot.android.core.domain.repository.OnboardingRepository
    @Binds abstract fun tokens(impl: SessionManager): AccessTokenProvider
    @Binds abstract fun authApi(impl: SupabaseAuthApi): AuthApi
    @Binds abstract fun secrets(impl: TinkSecretStore): SecretStore
    @Binds abstract fun preferences(impl: PreferencesRepositoryImpl): PreferencesRepository
    @Binds abstract fun chatbots(impl: ChatbotRepositoryImpl): ChatbotRepository
    @Binds abstract fun conversations(impl: ConversationRepositoryImpl): ConversationRepository
    @Binds abstract fun mail(impl: MailRepositoryImpl): MailRepository
    @Binds abstract fun inbox(impl: com.embeddablechatbot.android.core.data.repository.InboxRepositoryImpl): com.embeddablechatbot.android.core.domain.mail.InboxRepository
    @Binds abstract fun pendingConnect(impl: PendingConnectStoreImpl): PendingConnectStore
    @Binds abstract fun cleaner(impl: AccountDataCleanerImpl): AccountDataCleaner
    @Binds abstract fun connectivity(impl: com.embeddablechatbot.android.core.data.repository.AndroidConnectivityMonitor): com.embeddablechatbot.android.core.domain.repository.ConnectivityMonitor
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // never silently replay a POST that may have executed
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "EmbeddableChatbotAndroid/1").build()) }
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ChatDatabase =
        Room.databaseBuilder(context, ChatDatabase::class.java, "chat_cache.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // a cache: the server is authoritative
            .build()

    @Provides
    fun chatDao(db: ChatDatabase): ChatDao = db.chatDao()

    @Provides @Singleton
    fun transactions(db: ChatDatabase): TransactionRunner = object : TransactionRunner {
        override suspend fun <T> invoke(block: suspend () -> T): T = db.withTransaction { block() }
    }

    @Provides @Singleton
    fun clock(): Clock = Clock.System

    @Provides @Singleton
    fun ids(): IdGenerator = IdGenerator.Random

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
