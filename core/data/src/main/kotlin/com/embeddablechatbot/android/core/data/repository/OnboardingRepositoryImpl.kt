package com.embeddablechatbot.android.core.data.repository

import com.embeddablechatbot.android.core.data.auth.AccessTokenProvider
import com.embeddablechatbot.android.core.data.network.ClientApi
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.OnboardingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class OnboardingRepositoryImpl @Inject constructor(private val api: ClientApi, private val tokens: AccessTokenProvider, private val config: ConfigRepository) : OnboardingRepository {
    private val mutex = Mutex()
    override suspend fun prepare(): Outcome<Unit> = mutex.withLock { when (val result = api.prepareAccount()) {
        is Outcome.Err -> result
        is Outcome.Ok -> {
            if (tokens.epoch != result.value.epoch || tokens.currentUserId() == null) Outcome.Err(AppError.Unauthorized)
            else {
                config.update(config.config.value.copy(chatbotId = result.value.value.chatbotId))
                if (tokens.epoch == result.value.epoch) Outcome.Ok(Unit) else Outcome.Err(AppError.Unauthorized)
            }
        }
    }
}
}
