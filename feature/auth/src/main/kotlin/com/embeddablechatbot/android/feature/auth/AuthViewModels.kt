package com.embeddablechatbot.android.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.model.SignOutReason
import com.embeddablechatbot.android.core.domain.model.UserPreferences
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val platformUrl: String = "",
    val supabaseUrl: String = "",
    val supabaseKey: String = "",
    val chatbotId: String = "",
    val problem: String? = null,
    val saving: Boolean = false,
)

/** Platform/chatbot selection. Public configuration only; edits are allowed while signed out. */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(
        config.config.value.let {
            SetupUiState(
                platformUrl = saved["platform"] ?: it.platformUrl,
                supabaseUrl = saved["supabase"] ?: it.supabaseUrl,
                supabaseKey = saved["key"] ?: it.supabasePublishableKey,
                chatbotId = saved["chatbot"] ?: it.chatbotId,
            )
        },
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun edit(platform: String? = null, supabase: String? = null, key: String? = null, chatbot: String? = null) {
        _state.update {
            it.copy(
                platformUrl = platform ?: it.platformUrl, supabaseUrl = supabase ?: it.supabaseUrl,
                supabaseKey = key ?: it.supabaseKey, chatbotId = chatbot ?: it.chatbotId, problem = null,
            )
        }
        val s = _state.value
        saved["platform"] = s.platformUrl; saved["supabase"] = s.supabaseUrl; saved["key"] = s.supabaseKey; saved["chatbot"] = s.chatbotId
    }

    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.saving) return
        val candidate = PlatformConfig(s.platformUrl.trim(), s.supabaseUrl.trim(), s.supabaseKey.trim(), s.chatbotId.trim())
        PlatformConfig.validate(candidate, config.allowCleartext)?.let { problem ->
            _state.update { it.copy(problem = problem) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            config.update(candidate)
            _state.update { it.copy(saving = false) }
            onSaved()
        }
    }
}

data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val expired: Boolean = false,
    val developerOptions: Boolean = false,
    val platformUrl: String = "",
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val auth: AuthRepository,
    config: ConfigRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    private val savedState = saved
    private val _state = MutableStateFlow(
        SignInUiState(
            email = saved["email"] ?: "",
            expired = (auth.authState.value as? AuthState.SignedOut)?.reason == SignOutReason.SessionExpired,
            platformUrl = config.config.value.platformUrl,
            developerOptions = config.allowCleartext,
        ),
    )
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    fun onEmail(value: String) {
        savedState["email"] = value
        _state.update { it.copy(email = value, error = null) }
    }

    // The password is deliberately not written to SavedStateHandle.
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun signIn() {
        val s = _state.value
        if (s.busy || s.email.isBlank() || s.password.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.signIn(s.email, s.password)) {
                is Outcome.Ok -> _state.update { it.copy(busy = false, password = "") }
                is Outcome.Err -> _state.update { it.copy(busy = false, error = result.error) }
            }
        }
    }
}

data class SettingsUiState(
    val email: String? = null,
    val platformUrl: String = "",
    val chatbotId: String = "",
    val preferences: UserPreferences = UserPreferences(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val config: ConfigRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(auth.authState, config.config, preferences.preferences) { a, c, p ->
        SettingsUiState((a as? AuthState.SignedIn)?.account?.email, c.platformUrl, c.chatbotId, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private var signingOut = false

    fun setStreaming(enabled: Boolean) = viewModelScope.launch { preferences.update { it.copy(streamReplies = enabled) } }
    fun setReminders(enabled: Boolean) = viewModelScope.launch { preferences.update { it.copy(approvalReminders = enabled) } }

    fun signOut() {
        if (signingOut) return
        signingOut = true
        viewModelScope.launch { auth.signOut() }
    }
}
