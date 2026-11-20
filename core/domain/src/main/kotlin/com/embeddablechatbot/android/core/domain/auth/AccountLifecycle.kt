package com.embeddablechatbot.android.core.domain.auth

import com.embeddablechatbot.android.core.domain.chat.SendCoordinator
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.repository.AccountDataCleaner
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.PendingConnectStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Clears account-scoped state whenever the signed-in account changes or ends: cached transcripts,
 * cursors, pending sends, connect attempts and in-flight local work.
 */
@Singleton
class AccountLifecycle @Inject constructor(
    private val auth: AuthRepository,
    private val cleaner: AccountDataCleaner,
    private val coordinator: SendCoordinator,
    private val pendingConnect: PendingConnectStore,
) {
    /** Suspends forever; run it in the application scope. */
    suspend fun run() {
        var previous: String? = null
        var initialized = false
        auth.authState
            .map { state ->
                when (state) {
                    AuthState.Restoring -> UNKNOWN
                    is AuthState.SignedOut -> null
                    is AuthState.SignedIn -> state.account.userId
                }
            }
            .distinctUntilChanged()
            .collect { current ->
                if (current == UNKNOWN) return@collect
                val changed = !initialized && current == null || initialized && previous != current
                if (changed) {
                    coordinator.cancelAll()
                    cleaner.clearAccountData()
                    pendingConnect.clear()
                }
                previous = current
                initialized = true
            }
    }

    private companion object {
        const val UNKNOWN = "\u0000restoring"
    }
}
