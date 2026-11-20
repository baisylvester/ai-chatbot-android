package com.embeddablechatbot.android

import com.embeddablechatbot.android.core.domain.mail.InboxRepository
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import com.embeddablechatbot.android.core.domain.repository.OnboardingRepository
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.ChatbotRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** In-process access for explicitly opted-in debug backend integration tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackendVerificationEntryPoint {
    fun startup(): AppStartup
    fun config(): ConfigRepository
    fun auth(): AuthRepository
    fun onboarding(): OnboardingRepository
    fun mail(): MailRepository
    fun inbox(): InboxRepository
    fun conversations(): ConversationRepository
    fun chatbots(): ChatbotRepository
}
