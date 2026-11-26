package com.embeddablechatbot.android.feature.mail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.mail.ApprovalResult
import com.embeddablechatbot.android.core.domain.mail.ConnectCallback
import com.embeddablechatbot.android.core.domain.mail.ConnectMailboxUseCase
import com.embeddablechatbot.android.core.domain.mail.ConnectResult
import com.embeddablechatbot.android.core.domain.mail.ReviewMailActionUseCase
import com.embeddablechatbot.android.core.domain.model.ActionFilter
import com.embeddablechatbot.android.core.domain.model.AppError
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionDetail
import com.embeddablechatbot.android.core.domain.model.MailActionStatus
import com.embeddablechatbot.android.core.domain.model.MailStatus
import com.embeddablechatbot.android.core.domain.model.ConnectTarget
import com.embeddablechatbot.android.core.domain.model.ImapServers
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.MailRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hands the `chatbotmail://oauth/gmail` return link from the activity to the mail screen. The link is
 * untrusted: it only triggers an authenticated completion request.
 */
@Singleton
class ConnectCallbackBus @Inject constructor() {
    private val latest = MutableStateFlow<ConnectCallback?>(null)
    val callbacks: Flow<ConnectCallback> = latest.filterNotNull()

    fun post(callback: ConnectCallback) {
        latest.value = callback
    }

    /** Consumes the callback once so recomposition or collector restarts can't complete it twice. */
    fun take(): ConnectCallback? = latest.getAndUpdate { null }
}

/** [Unfinished]: the user came back from the browser without a completed sign-in (cancelled, or Google showed an error). */
enum class MailNotice { Connected, Disconnected, ConnectIgnored, Switched, CalendarDisconnected, Unfinished }

data class MailUiState(
    val loading: Boolean = true,
    val status: MailStatus? = null,
    val pending: List<MailAction> = emptyList(),
    val recent: List<MailAction> = emptyList(),
    val busy: Boolean = false,
    val error: AppError? = null,
    val declined: String? = null,
    val notice: MailNotice? = null,
)

sealed interface MailEffect {
    data class OpenBrowser(val url: String) : MailEffect
}

@HiltViewModel
class MailViewModel @Inject constructor(
    private val mail: MailRepository,
    private val connect: ConnectMailboxUseCase,
    private val auth: AuthRepository,
    private val config: ConfigRepository,
    private val bus: ConnectCallbackBus,
) : ViewModel() {
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state.asStateFlow()
    private val effects = Channel<MailEffect>(Channel.BUFFERED)
    val effect: Flow<MailEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { bus.callbacks.collect { bus.take()?.let { completeConnect(it) } } }
    }

    private val accountId: String? get() = (auth.authState.value as? AuthState.SignedIn)?.account?.userId

    /** Set while a browser sign-in is open; cleared when its result comes back. */
    private var awaitingBrowser = false

    /** Called whenever the screen starts (including returning from the browser). */
    fun refresh() {
        if (awaitingBrowser) viewModelScope.launch {
            // The return link is delivered just after the screen resumes; give it a moment first.
            kotlinx.coroutines.delay(2_000)
            if (awaitingBrowser) { awaitingBrowser = false; _state.update { if (it.busy) it else it.copy(notice = MailNotice.Unfinished) } }
        }
        viewModelScope.launch {
            val chatbotId = config.config.value.chatbotId
            val status = mail.status(chatbotId)
            val pending = mail.actions(ActionFilter.Pending)
            val recent = mail.actions(ActionFilter.Recent)
            _state.update {
                it.copy(
                    loading = false,
                    status = (status as? Outcome.Ok)?.value ?: it.status,
                    pending = (pending as? Outcome.Ok)?.value ?: it.pending,
                    recent = ((recent as? Outcome.Ok)?.value ?: it.recent).filter { a -> a.status != MailActionStatus.Pending },
                    error = (status as? Outcome.Err)?.error ?: (pending as? Outcome.Err)?.error,
                )
            }
        }
    }

    /** Opens the browser sign-in for a Gmail or Outlook mailbox, or for Google Calendar. */
    fun startConnect(target: ConnectTarget = ConnectTarget.Gmail) {
        val account = accountId ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, declined = null, notice = null) }
        viewModelScope.launch {
            when (val result = connect.start(config.config.value.chatbotId, account, target)) {
                is Outcome.Ok -> { awaitingBrowser = true; effects.send(MailEffect.OpenBrowser(result.value)) }
                is Outcome.Err -> _state.update { it.copy(error = result.error) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun completeConnect(callback: ConnectCallback) {
        awaitingBrowser = false
        val account = accountId ?: return
        _state.update { it.copy(busy = true, error = null, declined = null) }
        when (val result = connect.complete(callback, account)) {
            is ConnectResult.Connected -> _state.update { it.copy(status = result.status, notice = MailNotice.Connected) }
            is ConnectResult.Declined -> _state.update { it.copy(declined = result.message) }
            ConnectResult.Ignored -> _state.update { it.copy(notice = MailNotice.ConnectIgnored) }
            is ConnectResult.Failed -> _state.update { it.copy(error = result.error) }
        }
        _state.update { it.copy(busy = false) }
        refresh()
    }

    /** Connects an IMAP mailbox; the password is sent once to the server, which stores it encrypted. */
    fun connectImap(email: String, appPassword: String, servers: ImapServers) {
        if (_state.value.busy || email.isBlank() || appPassword.isBlank()) return
        _state.update { it.copy(busy = true, error = null, declined = null, notice = null) }
        viewModelScope.launch {
            // A refresh after a failure would clear the error, so only success reloads the list.
            when (val result = mail.connectImap(config.config.value.chatbotId, email, appPassword, servers)) {
                is Outcome.Ok -> { _state.update { it.copy(notice = MailNotice.Connected, busy = false) }; refresh() }
                is Outcome.Err -> _state.update { it.copy(error = result.error, busy = false) }
            }
        }
    }

    fun select(mailboxId: String) = run(MailNotice.Switched) { mail.selectMailbox(mailboxId) }
    fun disconnectMailbox(mailboxId: String) = run(MailNotice.Disconnected) { mail.disconnect(mailboxId) }
    fun disconnectCalendar() = run(MailNotice.CalendarDisconnected) { mail.disconnectCalendar() }

    private fun run(notice: MailNotice, action: suspend () -> Outcome<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = action()) {
                is Outcome.Ok -> { _state.update { it.copy(notice = notice, busy = false) }; refresh() }
                is Outcome.Err -> _state.update { it.copy(error = result.error, busy = false) }
            }
        }
    }

    fun disconnect() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = mail.disconnect()) {
                is Outcome.Ok -> _state.update { it.copy(notice = MailNotice.Disconnected) }
                is Outcome.Err -> _state.update { it.copy(error = result.error) }
            }
            _state.update { it.copy(busy = false) }
            refresh()
        }
    }
}

enum class ApprovalNotice { Stale, AlreadyHandled, Done, Rejected, Failed, Uncertain, Scheduled, Unscheduled }

data class ApprovalUiState(
    val loading: Boolean = true,
    val detail: MailActionDetail? = null,
    val working: Boolean = false,
    val error: AppError? = null,
    val notice: ApprovalNotice? = null,
)

@HiltViewModel
class ApprovalViewModel @Inject constructor(
    private val mail: MailRepository,
    private val review: ReviewMailActionUseCase,
    saved: SavedStateHandle,
) : ViewModel() {
    private val actionId: String = checkNotNull(saved["actionId"]) { "actionId is required" }
    private val _state = MutableStateFlow(ApprovalUiState())
    val state: StateFlow<ApprovalUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(notice: ApprovalNotice? = null) {
        _state.update { it.copy(loading = it.detail == null, error = null) }
        viewModelScope.launch {
            when (val result = mail.actionDetail(actionId)) {
                is Outcome.Ok -> _state.update { it.copy(loading = false, detail = result.value, notice = notice ?: it.notice) }
                is Outcome.Err -> _state.update { it.copy(loading = false, error = result.error, notice = notice ?: it.notice) }
            }
        }
    }

    /** Approves exactly the version on screen. Never invoked from a notification or background work. */
    fun approve() {
        val detail = _state.value.detail ?: return
        if (_state.value.working || !detail.canApprove) return
        _state.update { it.copy(working = true, error = null, notice = null) }
        viewModelScope.launch {
            handle(review.approve(detail))
        }
    }

    /** Approves exactly the version on screen for sending at [sendAt]; the server sends it when due. */
    fun schedule(sendAt: java.time.Instant) {
        val detail = _state.value.detail ?: return
        if (_state.value.working || !detail.canApprove) return
        _state.update { it.copy(working = true, error = null, notice = null) }
        viewModelScope.launch { handle(review.approve(detail, sendAt)) }
    }

    fun unschedule() {
        val detail = _state.value.detail ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null, notice = null) }
        viewModelScope.launch { handle(review.unschedule(detail)) }
    }

    fun reject() {
        val detail = _state.value.detail ?: return
        if (_state.value.working) return
        _state.update { it.copy(working = true, error = null, notice = null) }
        viewModelScope.launch { handle(review.reject(detail)) }
    }

    private fun handle(result: ApprovalResult) {
        _state.update { it.copy(working = false) }
        when (result) {
            is ApprovalResult.Finished -> {
                val notice = when (result.action.status) {
                    MailActionStatus.Done -> ApprovalNotice.Done
                    MailActionStatus.Rejected -> ApprovalNotice.Rejected
                    MailActionStatus.Uncertain -> ApprovalNotice.Uncertain
                    MailActionStatus.Scheduled -> ApprovalNotice.Scheduled
                    MailActionStatus.Pending -> ApprovalNotice.Unscheduled
                    else -> ApprovalNotice.Failed
                }
                load(notice)
            }
            ApprovalResult.Stale -> load(ApprovalNotice.Stale)
            ApprovalResult.AlreadyHandled -> load(ApprovalNotice.AlreadyHandled)
            is ApprovalResult.Failed -> {
                // An uncertain transport failure may still have executed: re-read the server state.
                _state.update { it.copy(error = result.error) }
                load()
            }
        }
    }
}
