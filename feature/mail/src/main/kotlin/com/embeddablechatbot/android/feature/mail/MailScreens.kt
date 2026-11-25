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
import com.embeddablechatbot.android.core.domain.model.MailAction
import com.embeddablechatbot.android.core.domain.model.MailActionKind
import com.embeddablechatbot.android.core.domain.model.MailActionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(onBack: () -> Unit, onOpenAction: (String) -> Unit, viewModel: MailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
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
                null -> Unit
            }
            Text(stringResource(R.string.mail_connection), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val status = state.status
            val connection = status?.connection
            when {
                status == null -> Unit
                !status.serverReady -> StatusBanner(stringResource(R.string.mail_not_configured), BannerTone.Warning)
                !status.eligible -> StatusBanner(stringResource(R.string.mail_role_required), BannerTone.Warning)
                connection == null -> Text(stringResource(R.string.mail_not_connected))
                connection.status == ConnectionStatus.Active -> Text(stringResource(R.string.mail_connected_as, connection.email))
                else -> StatusBanner(stringResource(R.string.mail_revoked, connection.email), BannerTone.Warning)
            }
            Text(stringResource(R.string.mail_explain), style = MaterialTheme.typography.bodySmall)
            if (status != null && status.serverReady && status.eligible) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Reauthorization preserves the current connection until the new browser flow succeeds.
                    Button(onClick = viewModel::startConnect, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (connection == null) R.string.mail_connect else R.string.mail_reconnect))
                    }
                    if (connection != null) {
                        OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.mail_disconnect))
                        }
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
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            text = { Text(stringResource(R.string.mail_disconnect_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; viewModel.disconnect() }) { Text(stringResource(R.string.mail_disconnect)) } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
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
