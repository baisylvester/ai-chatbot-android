package com.embeddablechatbot.android.core.domain.repository

import com.embeddablechatbot.android.core.domain.model.Outcome

enum class RegistrationStatus { SignedIn, ConfirmEmail }

interface RegistrationRepository {
    suspend fun register(email: String, password: String): Outcome<RegistrationStatus>
    suspend fun resendConfirmation(email: String): Outcome<Unit>
}

interface OnboardingRepository {
    /** The server selects the caller's private assistant; caller-supplied organization IDs are never accepted. */
    suspend fun prepare(): Outcome<Unit>
}
