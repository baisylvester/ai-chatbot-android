package com.embeddablechatbot.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.embeddablechatbot.android.core.data.repository.ConfigDefaults
import com.embeddablechatbot.android.core.data.repository.ConfigRepositoryImpl
import com.embeddablechatbot.android.core.domain.auth.AccountLifecycle
import com.embeddablechatbot.android.core.domain.chat.ApplicationScope
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.work.ReminderScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Ordered startup: platform config, then the stored session, then account-change handling. */
@Singleton
class AppStartup @Inject constructor(
    private val config: ConfigRepositoryImpl,
    private val auth: AuthRepository,
    private val lifecycle: AccountLifecycle,
    private val reminders: ReminderScheduler,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val ready = CompletableDeferred<Unit>()

    fun start() {
        scope.launch {
            config.load()
            auth.restore()
            ready.complete(Unit)
            launch { lifecycle.run() }
            launch { reminders.run() }
        }
    }

    suspend fun awaitReady() = ready.await()
}

@HiltAndroidApp
class MailAssistantApp : Application(), Configuration.Provider {
    @Inject lateinit var startup: AppStartup
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, getString(R.string.notification_channel_approvals), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.notification_channel_approvals_desc)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        startup.start()
    }

    companion object {
        const val CHANNEL_APPROVALS = "approvals"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun configDefaults(): ConfigDefaults = ConfigDefaults(
        config = PlatformConfig(
            platformUrl = BuildConfig.DEFAULT_PLATFORM_URL,
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            chatbotId = BuildConfig.DEFAULT_CHATBOT_ID,
        ),
        allowCleartext = BuildConfig.ALLOW_CLEARTEXT,
        migrateLocalPlatform = BuildConfig.MIGRATE_LOCAL_PLATFORM,
    )
}
