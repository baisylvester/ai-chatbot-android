package com.embeddablechatbot.android.feature.auth

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embeddablechatbot.android.core.domain.model.*
import com.embeddablechatbot.android.core.domain.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RegistrationUiState(val email: String="",val password: String="",val confirmation: String="",val busy: Boolean=false,val checkEmail: Boolean=false,val resendSeconds: Int=0,val error: AppError?=null,val resent: Boolean=false)

@HiltViewModel
class RegistrationViewModel @Inject constructor(private val registration: RegistrationRepository,private val saved: SavedStateHandle) : ViewModel() {
    private val mutable=MutableStateFlow(RegistrationUiState(email=saved["registration_email"] ?: "",checkEmail=saved["confirmation_pending"] ?: false))
    val state=mutable.asStateFlow()
    private var cooldown: Job?=null
    fun email(value:String) { if(state.value.busy)return; saved["registration_email"]=value;mutable.update{it.copy(email=value,error=null)} }
    fun password(value:String) {if(!state.value.busy)mutable.update{it.copy(password=value,error=null)}}
    fun confirmation(value:String) {if(!state.value.busy)mutable.update{it.copy(confirmation=value,error=null)}}
    fun register() {
        val s=state.value
        if(s.busy || s.checkEmail)return
        val problem=when {
            !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(s.email.trim()) -> "Enter a valid email address."
            s.password.length < 12 -> "Use a password with at least 12 characters."
            s.password != s.confirmation -> "Passwords do not match."
            else -> null
        }
        if(problem!=null){mutable.update{it.copy(error=AppError.Invalid(problem))};return}
        mutable.update{it.copy(busy=true,error=null)}
        viewModelScope.launch {
            when(val result=registration.register(s.email.trim(),s.password)) {
                is Outcome.Err -> mutable.update{it.copy(busy=false,error=result.error)}
                is Outcome.Ok -> {
                    val pending=result.value==RegistrationStatus.ConfirmEmail
                    saved["confirmation_pending"]=pending
                    mutable.update{it.copy(busy=false,password="",confirmation="",checkEmail=pending)}
                    if(pending)startCooldown()
                }
            }
        }
    }
    fun resend() {
        val s=state.value;if(s.busy || s.resendSeconds>0 || !s.checkEmail)return
        mutable.update{it.copy(busy=true,error=null,resent=false)}
        viewModelScope.launch {
            when(val result=registration.resendConfirmation(s.email.trim())) {
                is Outcome.Ok -> {mutable.update{it.copy(busy=false,resent=true)};startCooldown()}
                is Outcome.Err -> mutable.update{it.copy(busy=false,error=result.error)}
            }
        }
    }
    private fun startCooldown() {
        cooldown?.cancel();mutable.update{it.copy(resendSeconds=60)}
        cooldown=viewModelScope.launch {while(state.value.resendSeconds>0){delay(1000);mutable.update{it.copy(resendSeconds=(it.resendSeconds-1).coerceAtLeast(0))}}}
    }
}

data class BootstrapUiState(val busy:Boolean=true,val ready:Boolean=false,val connected:Boolean=false,val previouslyOnboarded:Boolean=false,val error:AppError?=null)

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val onboarding:OnboardingRepository,private val mail:MailRepository,private val config:ConfigRepository,private val auth:AuthRepository,private val preferences:PreferencesRepository) : ViewModel() {
    private val mutable=MutableStateFlow(BootstrapUiState())
    val state=mutable.asStateFlow()
    init{prepare()}
    fun prepare() {
        if(state.value.ready)return
        mutable.value=BootstrapUiState()
        viewModelScope.launch {
            when(val prepared=onboarding.prepare()) {
                is Outcome.Err -> mutable.value=BootstrapUiState(busy=false,error=prepared.error)
                is Outcome.Ok -> when(val status=mail.status(config.config.value.chatbotId)) {
                    is Outcome.Err -> mutable.value=BootstrapUiState(busy=false,error=status.error)
                    is Outcome.Ok -> {
                        val user=(auth.authState.value as? AuthState.SignedIn)?.account?.userId ?: return@launch
                        val completed=user in preferences.preferences.first().onboardedAccounts
                        mutable.value=BootstrapUiState(busy=false,ready=true,connected=status.value.connection?.status==ConnectionStatus.Active,previouslyOnboarded=completed)
                    }
                }
            }
        }
    }
    fun signOut()=viewModelScope.launch {auth.signOut()}
}
