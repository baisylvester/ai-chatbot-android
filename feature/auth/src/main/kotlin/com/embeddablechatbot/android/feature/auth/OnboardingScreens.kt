package com.embeddablechatbot.android.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embeddablechatbot.android.core.designsystem.*

@Composable
fun WelcomeScreen(onCreateAccount:()->Unit,onSignIn:()->Unit) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(28.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Spacer(Modifier.height(44.dp))
            Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.primaryContainer) {Icon(Icons.Default.Email,null,Modifier.padding(24.dp).size(52.dp),tint=MaterialTheme.colorScheme.primary)}
            Text(stringResource(R.string.onboard_welcome),style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics{heading()})
            Text(stringResource(R.string.onboard_welcome_body),style=MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.onboard_privacy),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick=onCreateAccount,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.onboard_get_started))}
            OutlinedButton(onClick=onSignIn,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.onboard_existing_account))}
        }
    }
}

@Composable
fun RegistrationScreen(onSignIn:(String)->Unit,onBack:()->Unit,viewModel:RegistrationViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold {padding->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            TextButton(onClick=onBack,enabled=!state.busy){Text(stringResource(R.string.onboard_back))}
            Text(stringResource(if(state.checkEmail)R.string.onboard_check_email else R.string.onboard_create_account),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics{heading()})
            if(state.checkEmail) {
                Text(stringResource(R.string.onboard_confirmation_body,state.email))
                Text(stringResource(R.string.onboard_existing_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.resent)StatusBanner(stringResource(R.string.onboard_resent),BannerTone.Info)
                Button(onClick={onSignIn(state.email)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(R.string.onboard_confirmed))}
                OutlinedButton(onClick=viewModel::resend,enabled=!state.busy&&state.resendSeconds==0,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text(if(state.resendSeconds>0)stringResource(R.string.onboard_resend_timer,"00:"+state.resendSeconds.toString().padStart(2,'0')) else stringResource(R.string.onboard_resend))
                }
            } else {
                Text(stringResource(R.string.onboard_account_body))
                OutlinedTextField(state.email,viewModel::email,label={Text(stringResource(R.string.auth_email))},singleLine=true,enabled=!state.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,autoCorrectEnabled=false),modifier=Modifier.fillMaxWidth())
                OutlinedTextField(state.password,viewModel::password,label={Text(stringResource(R.string.auth_password))},singleLine=true,enabled=!state.busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),supportingText={Text(stringResource(R.string.onboard_password_hint))},modifier=Modifier.fillMaxWidth())
                OutlinedTextField(state.confirmation,viewModel::confirmation,label={Text(stringResource(R.string.onboard_confirm_password))},singleLine=true,enabled=!state.busy,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
                Button(onClick=viewModel::register,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(stringResource(if(state.busy)R.string.onboard_creating else R.string.onboard_create_account))}
                TextButton(onClick={onSignIn(state.email)},enabled=!state.busy){Text(stringResource(R.string.onboard_existing_account))}
            }
            state.error?.let{StatusBanner(it.message(),BannerTone.Error)}
        }
    }
}

@Composable
fun BootstrapScreen(onReady:(Boolean,Boolean)->Unit,viewModel:OnboardingViewModel=hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ready){if(state.ready)onReady(state.connected,state.previouslyOnboarded)}
    Column(Modifier.fillMaxSize().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterVertically)) {
        Text(stringResource(R.string.onboard_preparing),style=MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboard_preparing_body))
        if(state.busy)CircularProgressIndicator()
        state.error?.let {
            StatusBanner(it.message(),BannerTone.Error)
            Button(onClick=viewModel::prepare){Text(stringResource(R.string.onboard_retry))}
            TextButton(onClick={viewModel.signOut()}){Text(stringResource(R.string.settings_sign_out))}
        }
    }
}
