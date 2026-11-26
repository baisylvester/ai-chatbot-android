package com.embeddablechatbot.android.feature.mail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embeddablechatbot.android.core.designsystem.BannerTone
import com.embeddablechatbot.android.core.designsystem.LoadingState
import com.embeddablechatbot.android.core.designsystem.StatusBanner
import com.embeddablechatbot.android.core.designsystem.message
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import com.embeddablechatbot.android.core.domain.model.ConnectTarget
import com.embeddablechatbot.android.core.domain.model.ImapServers
import com.embeddablechatbot.android.core.domain.model.MailboxInfo
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionKind
import com.embeddablechatbot.android.core.domain.model.MailActionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(onBack: () -> Unit, onOpenAction: (String) -> Unit, viewModel: MailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDisconnect by rememberSaveable { mutableStateOf<String?>(null) }
    var imapForm by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            if (effect is MailEffect.OpenBrowser) {
                // System browser / Custom Tab: Google's consent page never runs inside an app WebView.
                val uri = Uri.parse(effect.url)
                try {
                    CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().launchUrl(context, uri)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.mail_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mail_back)) } },
            actions = { IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.mail_refresh)) } },
        )
    }) { padding ->
        if (state.loading && state.status == null) {
            LoadingState(Modifier.padding(padding), stringResource(R.string.mail_loading))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { StatusBanner(it.message(), BannerTone.Error, actionLabel = stringResource(R.string.mail_refresh), onAction = viewModel::refresh) }
            state.declined?.let { StatusBanner(it, BannerTone.Warning) }
            when (state.notice) {
                MailNotice.Connected -> StatusBanner(stringResource(R.string.mail_connected_notice), BannerTone.Info)
                MailNotice.Disconnected -> StatusBanner(stringResource(R.string.mail_disconnected_notice), BannerTone.Info)
                MailNotice.ConnectIgnored -> StatusBanner(stringResource(R.string.mail_ignored_notice), BannerTone.Warning)
                MailNotice.Switched -> StatusBanner(stringResource(R.string.mail_switched_notice), BannerTone.Info)
                MailNotice.CalendarDisconnected -> StatusBanner(stringResource(R.string.mail_calendar_disconnected), BannerTone.Info)
                null -> Unit
            }
            Text(stringResource(R.string.mail_mailboxes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val status = state.status
            when {
                status == null -> Unit
                !status.serverReady -> StatusBanner(stringResource(R.string.mail_not_configured), BannerTone.Warning)
                !status.eligible -> StatusBanner(stringResource(R.string.mail_role_required), BannerTone.Warning)
                status.mailboxes.isEmpty() && status.connection == null -> Text(stringResource(R.string.mail_not_connected))
            }
            status?.mailboxes?.forEach { box ->
                MailboxRow(box, busy = state.busy, onSelect = { viewModel.select(box.id) }, onDisconnect = { confirmDisconnect = box.id },
                    onReconnect = { when (box.provider) { "outlook" -> viewModel.startConnect(ConnectTarget.Outlook); "imap" -> imapForm = true; else -> viewModel.startConnect() } })
            }
            Text(stringResource(R.string.mail_explain), style = MaterialTheme.typography.bodySmall)
            if (status != null && status.serverReady && status.eligible) {
                Text(stringResource(R.string.mail_add), style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.providers.gmail) OutlinedButton(onClick = { viewModel.startConnect() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_connect)) }
                    if (status.providers.outlook) OutlinedButton(onClick = { viewModel.startConnect(ConnectTarget.Outlook) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_connect_outlook)) }
                    if (status.providers.imap) OutlinedButton(onClick = { imapForm = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_connect_imap)) }
                }
                if (status.providers.calendar || status.calendarEmail != null) {
                    HorizontalDivider()
                    Text(stringResource(R.string.mail_calendar), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.mail_calendar_explain), style = MaterialTheme.typography.bodySmall)
                    val calendar = status.calendarEmail
                    if (calendar != null) {
                        Text(stringResource(R.string.mail_calendar_connected, calendar))
                        OutlinedButton(onClick = viewModel::disconnectCalendar, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_calendar_disconnect)) }
                    } else {
                        Button(onClick = { viewModel.startConnect(ConnectTarget.Calendar) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_calendar_connect)) }
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.mail_pending), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (state.pending.isEmpty() && state.status != null && state.error == null && !state.loading) Text(stringResource(R.string.mail_pending_empty), style = MaterialTheme.typography.bodyMedium)
            state.pending.forEach { ActionCard(it) { onOpenAction(it.id) } }
            if (state.recent.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.mail_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                state.recent.forEach { ActionCard(it) { onOpenAction(it.id) } }
            }
        }
    }
    confirmDisconnect?.let { id ->
        val email = state.status?.mailboxes?.firstOrNull { it.id == id }?.email.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            text = { Text(stringResource(R.string.mail_disconnect_one_confirm, email)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = null; viewModel.disconnectMailbox(id) }) { Text(stringResource(R.string.mail_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    if (imapForm) ImapDialog(busy = state.busy, onDismiss = { imapForm = false }, onConnect = { email, password, servers -> imapForm = false; viewModel.connectImap(email, password, servers) })
}

@Composable
private fun MailboxRow(box: MailboxInfo, busy: Boolean, onSelect: () -> Unit, onDisconnect: () -> Unit, onReconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(box.email, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(box.providerName, if (box.selected) stringResource(R.string.mail_in_use) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (box.status != ConnectionStatus.Active) StatusBanner(stringResource(R.string.mail_revoked, box.email), BannerTone.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!box.selected && box.status == ConnectionStatus.Active) Button(onClick = onSelect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_use_this)) }
                if (box.status != ConnectionStatus.Active) Button(onClick = onReconnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_reconnect)) }
                OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.mail_disconnect)) }
            }
        }
    }
}

/**
 * Email address and app password; server names only for providers without a preset. The password is
 * never stored on the phone: it goes to the server once, which keeps it encrypted.
 */
@Composable
private fun ImapDialog(busy: Boolean, onDismiss: () -> Unit, onConnect: (String, String, ImapServers) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var imapHost by rememberSaveable { mutableStateOf("") }
    var smtpHost by rememberSaveable { mutableStateOf("") }
    var starttls by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_connect_imap)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.mail_imap_explain), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(email, { email = it.trim().take(320) }, label = { Text(stringResource(R.string.mail_imap_email)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it.take(200) }, label = { Text(stringResource(R.string.mail_imap_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { advanced = !advanced }) { Text(stringResource(if (advanced) R.string.mail_imap_hide_servers else R.string.mail_imap_servers)) }
                if (advanced) {
                    OutlinedTextField(imapHost, { imapHost = it.trim().take(253) }, label = { Text(stringResource(R.string.mail_imap_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(smtpHost, { smtpHost = it.trim().take(253) }, label = { Text(stringResource(R.string.mail_smtp_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(starttls, { starttls = it })
                        Text(stringResource(R.string.mail_smtp_587))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(email, password, ImapServers(imapHost.ifBlank { null }, smtpHost.ifBlank { null }, if (advanced) (if (starttls) 587 else 465) else null)) },
                enabled = !busy && email.contains('@') && password.length >= 4,
            ) { Text(stringResource(R.string.mail_imap_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

@Composable
fun statusLabel(status: MailActionStatus): String = stringResource(
    when (status) {
        MailActionStatus.Pending -> R.string.mail_status_pending
        MailActionStatus.Scheduled -> R.string.mail_status_scheduled
        MailActionStatus.Processing -> R.string.mail_status_processing
        MailActionStatus.Done -> R.string.mail_status_done
        MailActionStatus.Rejected -> R.string.mail_status_rejected
        MailActionStatus.Failed -> R.string.mail_status_failed
        MailActionStatus.Uncertain -> R.string.mail_status_uncertain
        MailActionStatus.Unknown -> R.string.mail_status_unknown
    },
)

@Composable
private fun ActionCard(action: MailAction, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (action.kind == MailActionKind.Send) R.string.mail_kind_send else R.string.mail_kind_trash),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(action.summary, style = MaterialTheme.typography.bodyMedium)
            Text(statusLabel(action.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action.scheduledFor?.let { Text(stringResource(R.string.review_scheduled_for, formatSendTime(it)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            action.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(onBack: () -> Unit, viewModel: ApprovalViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    var choosingTime by rememberSaveable { mutableStateOf(false) }
    var scheduleAt by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.review_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.mail_back)) } },
            actions = { IconButton(onClick = { viewModel.load() }) { Icon(Icons.Default.Refresh, stringResource(R.string.mail_refresh)) } },
        )
    }) { padding ->
        val detail = state.detail
        if (detail == null) {
            if (state.loading) LoadingState(Modifier.padding(padding), stringResource(R.string.mail_loading))
            else state.error?.let { StatusBanner(it.message(), BannerTone.Error, Modifier.padding(padding).padding(16.dp), stringResource(R.string.mail_refresh)) { viewModel.load() } }
            return@Scaffold
        }
        val action = detail.action
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state.notice) {
                ApprovalNotice.Stale -> StatusBanner(stringResource(R.string.review_stale), BannerTone.Warning)
                ApprovalNotice.AlreadyHandled -> StatusBanner(stringResource(R.string.review_already), BannerTone.Info)
                ApprovalNotice.Done -> StatusBanner(stringResource(R.string.review_done), BannerTone.Info)
                ApprovalNotice.Rejected -> StatusBanner(stringResource(if (action.kind == MailActionKind.Send) R.string.review_rejected else R.string.review_trash_rejected), BannerTone.Info)
                ApprovalNotice.Failed -> StatusBanner(stringResource(R.string.review_failed), BannerTone.Error)
                ApprovalNotice.Uncertain -> StatusBanner(stringResource(R.string.review_uncertain), BannerTone.Warning)
                ApprovalNotice.Scheduled -> StatusBanner(stringResource(R.string.review_scheduled_notice), BannerTone.Info)
                ApprovalNotice.Unscheduled -> StatusBanner(stringResource(R.string.review_unscheduled_notice), BannerTone.Info)
                null -> Unit
            }
            state.error?.let { StatusBanner(it.message(), BannerTone.Error) }
            Labeled(stringResource(R.string.review_status), statusLabel(action.status))
            action.scheduledFor?.let { Labeled(stringResource(R.string.review_send_time), formatSendTime(it)) }
            action.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Labeled(stringResource(R.string.review_account), detail.account)
            detail.send?.let { send ->
                Labeled(stringResource(R.string.review_to), send.to.joinToString())
                if (send.cc.isNotEmpty()) Labeled(stringResource(R.string.review_cc), send.cc.joinToString())
                if (send.bcc.isNotEmpty()) Labeled(stringResource(R.string.review_bcc), send.bcc.joinToString())
                Labeled(stringResource(R.string.review_subject), send.subject)
                Text(stringResource(R.string.review_body), style = MaterialTheme.typography.labelLarge)
                Card(Modifier.fillMaxWidth()) {
                    // Plain text, complete body: exactly what Gmail will send.
                    SelectionContainer { Text(send.body, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                Labeled(
                    stringResource(R.string.review_attachments),
                    if (send.attachments.isEmpty()) stringResource(R.string.review_no_attachments)
                    else send.attachments.joinToString { "${it.filename} (${it.mimeType}, ${it.size} B)" },
                )
                if (!send.draftExists && action.status == MailActionStatus.Pending) StatusBanner(stringResource(R.string.review_draft_missing), BannerTone.Error)
                if (action.status == MailActionStatus.Uncertain) {
                    StatusBanner(
                        stringResource(if (send.draftExists) R.string.review_uncertain_draft_present else R.string.review_uncertain_draft_gone),
                        BannerTone.Warning,
                    )
                }
            }
            detail.trash?.let { trash ->
                Text(stringResource(if (action.status == MailActionStatus.Pending) R.string.review_trash_targets else R.string.review_trash_reviewed), style = MaterialTheme.typography.labelLarge)
                trash.messages.forEach { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.subject, fontWeight = FontWeight.SemiBold)
                            Text(m.from, style = MaterialTheme.typography.bodySmall)
                            Text(m.date, style = MaterialTheme.typography.bodySmall)
                            Text(m.snippet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (trash.missing.isNotEmpty()) Text(pluralStringResource(R.plurals.review_trash_missing, trash.missing.size, trash.missing.size), style = MaterialTheme.typography.bodySmall)
            }
            if (action.status == MailActionStatus.Pending) {
                StatusBanner(stringResource(R.string.review_untrusted), BannerTone.Info)
                val isSend = action.kind == MailActionKind.Send
                Button(
                    onClick = { confirm = true },
                    enabled = detail.canApprove && !state.working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(if (state.working) R.string.review_working else if (isSend) R.string.review_send else R.string.review_trash)) }
                if (isSend) OutlinedButton(onClick = { choosingTime = true }, enabled = detail.canApprove && !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_schedule))
                }
                OutlinedButton(onClick = viewModel::reject, enabled = !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (isSend) R.string.review_keep_draft else R.string.review_dont_trash))
                }
            }
            if (action.status == MailActionStatus.Scheduled) {
                StatusBanner(stringResource(R.string.review_scheduled_info), BannerTone.Info)
                OutlinedButton(onClick = viewModel::unschedule, enabled = !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_unschedule))
                }
                OutlinedButton(onClick = viewModel::reject, enabled = !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.review_keep_draft))
                }
            }
        }
        if (choosingTime) ScheduleSendDialog(onDismiss = { choosingTime = false }, onPick = { choosingTime = false; scheduleAt = it.toEpochMilli() })
        scheduleAt?.let { millis ->
            val at = java.time.Instant.ofEpochMilli(millis)
            AlertDialog(
                onDismissRequest = { scheduleAt = null },
                text = { Text(stringResource(R.string.review_confirm_schedule, detail.send?.to?.joinToString().orEmpty(), formatSendTime(at))) },
                confirmButton = { TextButton(onClick = { scheduleAt = null; viewModel.schedule(at) }) { Text(stringResource(R.string.review_schedule_confirm)) } },
                dismissButton = { TextButton(onClick = { scheduleAt = null }) { Text(stringResource(R.string.mail_cancel)) } },
            )
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                text = {
                    Text(
                        if (action.kind == MailActionKind.Send) stringResource(R.string.review_confirm_send, detail.send?.to?.joinToString().orEmpty())
                        else (detail.trash?.messages?.size ?: 0).let { n -> pluralStringResource(R.plurals.review_confirm_trash, n, n) },
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirm = false; viewModel.approve() }) {
                        Text(stringResource(if (action.kind == MailActionKind.Send) R.string.review_send else R.string.review_trash))
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.mail_cancel)) } },
            )
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
