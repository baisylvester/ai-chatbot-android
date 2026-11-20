package com.embeddablechatbot.android.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embeddablechatbot.android.core.designsystem.BannerTone
import com.embeddablechatbot.android.core.designsystem.StatusBanner
import com.embeddablechatbot.android.core.designsystem.message

@Composable
fun SetupScreen(onSaved: () -> Unit, viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.auth_setup_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.auth_setup_intro), style = MaterialTheme.typography.bodyMedium)
            Field(state.platformUrl, R.string.auth_platform_url, KeyboardType.Uri) { viewModel.edit(platform = it) }
            Field(state.supabaseUrl, R.string.auth_supabase_url, KeyboardType.Uri) { viewModel.edit(supabase = it) }
            Field(state.supabaseKey, R.string.auth_supabase_key, KeyboardType.Ascii) { viewModel.edit(key = it) }

            state.problem?.let { StatusBanner(it, BannerTone.Error) }
            Button(onClick = { viewModel.save(onSaved) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.auth_save))
            }
        }
    }
}

@Composable
private fun Field(value: String, label: Int, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SignInScreen(onChangePlatform: () -> Unit, onCreateAccount: () -> Unit, viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.auth_sign_in_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.auth_sign_in_intro), style = MaterialTheme.typography.bodyMedium)
            if (state.expired) StatusBanner(stringResource(R.string.auth_session_expired), BannerTone.Warning)
            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmail,
                label = { Text(stringResource(R.string.auth_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPassword,
                label = { Text(stringResource(R.string.auth_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { StatusBanner(it.message(), BannerTone.Error) }
            Button(
                onClick = viewModel::signIn,
                enabled = !state.busy && state.email.isNotBlank() && state.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (state.busy) R.string.auth_signing_in else R.string.auth_sign_in)) }
            TextButton(onClick = onCreateAccount) { Text(stringResource(R.string.onboard_create_account)) }
            if (state.developerOptions) TextButton(onClick = onChangePlatform) { Text(stringResource(R.string.auth_change_platform)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenMailbox: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            state.email?.let { Text(stringResource(R.string.settings_signed_in_as, it)) }
            Text("${stringResource(R.string.settings_platform)}: ${state.platformUrl}", style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.settings_chatbot)}: ${state.chatbotId}", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.settings_change_hint), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            ToggleRow(stringResource(R.string.settings_stream), stringResource(R.string.settings_stream_hint), state.preferences.streamReplies, viewModel::setStreaming)
            ToggleRow(stringResource(R.string.settings_reminders), stringResource(R.string.settings_reminders_hint), state.preferences.approvalReminders, viewModel::setReminders)
            HorizontalDivider()
            OutlinedButton(onClick = onOpenMailbox, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_mailbox)) }
            Button(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_sign_out)) }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            text = { Text(stringResource(R.string.settings_sign_out_confirm)) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text(stringResource(R.string.settings_sign_out)) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
