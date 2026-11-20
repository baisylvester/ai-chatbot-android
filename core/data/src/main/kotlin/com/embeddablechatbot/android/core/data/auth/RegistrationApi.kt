package com.embeddablechatbot.android.core.data.auth

import com.embeddablechatbot.android.core.domain.model.Outcome

interface RegistrationApi {
    /** Null session means email confirmation is required; no credentials are saved yet. */
    suspend fun signUp(url: String, key: String, email: String, password: String, redirect: String): Outcome<StoredSession?>
    suspend fun resend(url: String, key: String, email: String, redirect: String): Outcome<Unit>
}
