package com.embeddablechatbot.android.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embeddablechatbot.android.core.designsystem.BannerTone
import com.embeddablechatbot.android.core.designsystem.EmptyState
import com.embeddablechatbot.android.core.designsystem.LoadingState
import com.embeddablechatbot.android.core.designsystem.StatusBanner
import com.embeddablechatbot.android.core.designsystem.message
import com.embeddablechatbot.android.core.domain.model.Conversation
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onMailbox: () -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { if (it is ListEffect.OpenConversation) onOpen(it.id) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chat_history)) },
                navigationIcon = { onBack?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_back)) } } },
                actions = {
                    IconButton(onClick = onMailbox) { Icon(Icons.Default.Email, stringResource(R.string.chat_mailbox)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.chat_settings)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::newConversation,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(if (state.creating) R.string.chat_new_creating else R.string.chat_new)) },
                expanded = true,
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = false, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.freshness == Freshness.Offline) StatusBanner(stringResource(R.string.chat_offline_list), BannerTone.Warning)
                        state.error?.let { StatusBanner(it.message(), BannerTone.Error, actionLabel = stringResource(R.string.chat_refresh), onAction = viewModel::refresh) }
                        val pending = state.mailStatus?.pendingApprovals ?: 0
                        if (pending > 0) {
                            StatusBanner(pluralStringResource(R.plurals.chat_approvals_waiting, pending, pending), BannerTone.Info, actionLabel = stringResource(R.string.chat_review), onAction = onMailbox)
                        }
                    }
                }
                if (state.conversations.isEmpty()) {
                    item {
                        if (state.freshness == Freshness.Loading) LoadingState(Modifier.heightIn(min = 240.dp), stringResource(R.string.chat_loading))
                        else EmptyState(stringResource(R.string.chat_empty_title), stringResource(R.string.chat_empty_body))
                    }
                }
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationRow(conversation, deleting = state.deletingId == conversation.id, onOpen = { onOpen(conversation.id) }, onDelete = { confirmDelete = conversation.id })
                    HorizontalDivider()
                }
                if (state.hasMore) {
                    item { TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(stringResource(R.string.chat_load_more)) } }
                }
                item { Row(Modifier.heightIn(min = 88.dp)) {} }
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.chat_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; viewModel.delete(id) }) { Text(stringResource(R.string.chat_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
private fun ConversationRow(conversation: Conversation, deleting: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                conversation.title.ifBlank { stringResource(R.string.chat_untitled) },
                style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(dateFormat.format(conversation.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete, enabled = !deleting) { Icon(Icons.Default.Delete, stringResource(R.string.chat_delete)) }
    }
}
