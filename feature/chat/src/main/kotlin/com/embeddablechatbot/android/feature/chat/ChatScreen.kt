package com.embeddablechatbot.android.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.embeddablechatbot.android.core.designsystem.BannerTone
import com.embeddablechatbot.android.core.designsystem.StatusBanner
import com.embeddablechatbot.android.core.designsystem.message
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import com.embeddablechatbot.android.core.domain.model.KnowledgeState
import com.embeddablechatbot.android.core.domain.model.Message
import com.embeddablechatbot.android.core.domain.model.PendingState
import com.embeddablechatbot.android.core.domain.model.Role as ChatRole
import com.embeddablechatbot.android.core.domain.model.TurnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit, onMailbox: () -> Unit, onHistory: () -> Unit = {}, onWritingTools: () -> Unit = {}, assistantInput: String? = null, assistantInputId: String? = null, onInputConsumed: () -> Unit = {}, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose { } }
    LaunchedEffect(assistantInputId) {
        if (assistantInput != null && assistantInputId != null) {
            viewModel.acceptAssistantInput(assistantInputId, assistantInput)
            onInputConsumed()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { if (it == ChatEffect.ConversationGone) onBack() }
    }
    LaunchedEffect(state.items.size) { if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(stringResource(R.string.chat_assistant_title))
                    if (state.title.isNotBlank()) Text(state.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_back)) } },
                actions = {
                    IconButton(onClick = onWritingTools) { Icon(Icons.Default.Edit, stringResource(R.string.chat_writing_tools)) }
                    IconButton(onClick = onHistory) { Icon(Icons.Default.List, stringResource(R.string.chat_history)) }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.chat_refresh)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.freshness == Freshness.Offline) StatusBanner(stringResource(R.string.chat_offline_chat), BannerTone.Warning)
                state.error?.let { StatusBanner(it.message(), BannerTone.Error, actionLabel = stringResource(R.string.chat_refresh), onAction = viewModel::refresh) }
                val knowledge = state.chatbot?.knowledge
                when {
                    state.knowledgeUnavailable || knowledge?.state == KnowledgeState.Unavailable -> StatusBanner(stringResource(R.string.chat_knowledge_unavailable), BannerTone.Warning, actionLabel = stringResource(R.string.chat_refresh), onAction = viewModel::refresh)
                    knowledge?.state == KnowledgeState.Ready -> Column {
                        Text(stringResource(R.string.chat_knowledge_source, knowledge.sourceName), style = MaterialTheme.typography.labelMedium)
                        Text(pluralStringResource(R.plurals.chat_knowledge_ready, knowledge.readyDocuments, knowledge.readyDocuments), style = MaterialTheme.typography.bodySmall)
                        if (knowledge.processingDocuments > 0) Text(stringResource(R.string.chat_knowledge_processing), style = MaterialTheme.typography.bodySmall)
                    }
                    knowledge?.state == KnowledgeState.Processing -> Text(stringResource(R.string.chat_knowledge_processing), style = MaterialTheme.typography.bodySmall)
                    knowledge?.state == KnowledgeState.Empty -> Text(stringResource(R.string.chat_knowledge_empty), style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
                val pendingApprovals = state.mailStatus?.pendingApprovals ?: 0
                if (pendingApprovals > 0) {
                    StatusBanner(pluralStringResource(R.plurals.chat_approvals_waiting, pendingApprovals, pendingApprovals), BannerTone.Info, actionLabel = stringResource(R.string.chat_review), onAction = onMailbox)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.items.isEmpty()) {
                    item { Bubble(stringResource(R.string.chat_assistant), stringResource(R.string.chat_unified_welcome), mine = false) }
                    if (state.draft.isBlank()) item { AssistantShortcuts(viewModel::onDraft) }
                }
                items(state.items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Committed -> CommittedBubble(item.message)
                        is ChatItem.Pending -> PendingBubble(item, viewModel)
                        is ChatItem.Live -> LiveBubble(item)
                        is ChatItem.TurnNotice -> StatusBanner(
                            if (item.turn.status == TurnStatus.Interrupted) stringResource(R.string.chat_turn_interrupted)
                            else stringResource(R.string.chat_turn_failed, item.turn.error ?: ""),
                            BannerTone.Error,
                        )
                    }
                }
                if (state.remoteReplyRunning) item { StatusBanner(stringResource(R.string.chat_remote_running), BannerTone.Info) }
            }
            Composer(state, viewModel, onMailbox)
        }
    }
}

@Composable
private fun CommittedBubble(message: Message) {
    val mine = message.role == ChatRole.User
    Bubble(stringResource(if (mine) R.string.chat_you else R.string.chat_assistant), message.text, mine) {
        if (message.citations.isNotEmpty()) {
            Text(
                stringResource(R.string.chat_sources) + ":\n" + message.citations.groupBy { it.title to it.sourceUrl }.entries.joinToString("\n") { (source, references) ->
                    "[${references.joinToString(", ") { it.n.toString() }}] ${source.first}"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun PendingBubble(item: ChatItem.Pending, viewModel: ChatViewModel) {
    val pending = item.pending
    Bubble(stringResource(R.string.chat_you), pending.text, mine = true) {
        val label = when (pending.state) {
            PendingState.Submitting -> stringResource(R.string.chat_state_sending)
            PendingState.Uncertain -> stringResource(R.string.chat_state_uncertain)
            PendingState.Running -> stringResource(R.string.chat_state_running)
            PendingState.NotAccepted -> stringResource(R.string.chat_state_refused) + (pending.errorMessage?.let { ": $it" } ?: "")
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (pending.state) {
            PendingState.NotAccepted -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.retry(pending.requestId) }) { Text(stringResource(R.string.chat_retry_same)) }
                TextButton(onClick = { viewModel.editRefused(pending.requestId) }) { Text(stringResource(R.string.chat_edit)) }
            }
            PendingState.Uncertain, PendingState.Running -> TextButton(onClick = viewModel::checkStatus) { Text(stringResource(R.string.chat_check_status)) }
            PendingState.Submitting -> LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LiveBubble(item: ChatItem.Live) {
    val reply = item.reply
    Bubble(stringResource(R.string.chat_assistant), reply.provisionalText, mine = false) {
        reply.tools.forEach { tool ->
            val text = when {
                tool.failed -> stringResource(R.string.chat_tool_failed)
                tool.running -> stringResource(R.string.chat_tool_running)
                (tool.waiting ?: 0) > 0 -> pluralStringResource(R.plurals.chat_tool_waiting, tool.waiting ?: 0, tool.waiting ?: 0)
                else -> null
            }
            text?.let { AssistChip(onClick = {}, label = { Text(it) }) }
            tool.steps.forEach { step -> Text("• $step", style = MaterialTheme.typography.labelSmall) }
        }
        Text(stringResource(R.string.chat_provisional), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun Bubble(author: String, text: String, mine: Boolean, extra: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 560.dp).semantics(mergeDescendants = false) { contentDescription = "$author: $text" },
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Plain text only: email-derived content is never rendered as HTML or with links auto-loaded.
                if (text.isNotEmpty()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                extra()
            }
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, viewModel: ChatViewModel, onMailbox: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MailToolsRow(state, onMailbox)
            when (state.notice) {
                ChatNotice.TooLong -> Text(stringResource(R.string.chat_too_long), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ChatNotice.Blocked -> Text(stringResource(R.string.chat_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                null -> if (state.blockedByPending) Text(stringResource(R.string.chat_blocked), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraft,
                    placeholder = { Text(stringResource(R.string.chat_composer_hint)) },
                    supportingText = if (state.remaining < 500) ({ Text(pluralStringResource(R.plurals.chat_remaining, state.remaining, state.remaining)) }) else null,
                    isError = state.remaining < 0,
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::send, enabled = state.canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun MailToolsRow(state: ChatUiState, onMailbox: () -> Unit) {
    if (state.mailStatusError != null) {
        Text(stringResource(R.string.chat_mail_status_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    val status = state.mailStatus ?: return
    if (!status.eligible) {
        if (status.serverReady) Text(stringResource(R.string.chat_mail_role), style = MaterialTheme.typography.bodySmall)
        return
    }
    val connection = status.connection
    when {
        connection == null -> TextButton(onClick = onMailbox) { Text(stringResource(R.string.chat_mail_tools_connect)) }
        connection.status == ConnectionStatus.Revoked -> TextButton(onClick = onMailbox) { Text(stringResource(R.string.chat_mail_tools_revoked)) }
        state.mailToolsAvailable -> TextButton(onClick = onMailbox) { Text(stringResource(R.string.chat_mail_tools_on, connection.email), style = MaterialTheme.typography.bodySmall) }
        else -> Text(stringResource(R.string.chat_mail_status_unavailable), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AssistantShortcuts(onDraft: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            R.string.chat_shortcut_write to R.string.chat_prompt_write,
            R.string.chat_shortcut_summary to R.string.chat_prompt_summary,
            R.string.chat_shortcut_grammar to R.string.chat_prompt_grammar,
            R.string.chat_shortcut_translate to R.string.chat_prompt_translate,
        ).forEach { (label, prompt) ->
            val text = stringResource(prompt)
            AssistChip(onClick = { onDraft(text) }, label = { Text(stringResource(label)) })
        }
    }
}
