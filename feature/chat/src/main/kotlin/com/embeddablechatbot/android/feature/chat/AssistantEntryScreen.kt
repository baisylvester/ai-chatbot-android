package com.embeddablechatbot.android.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embeddablechatbot.android.core.designsystem.BannerTone
import com.embeddablechatbot.android.core.designsystem.LoadingState
import com.embeddablechatbot.android.core.designsystem.StatusBanner
import com.embeddablechatbot.android.core.designsystem.message

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantEntryScreen(onOpen: (String) -> Unit, onBack: () -> Unit, onHistory: () -> Unit, viewModel: AssistantEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.open(); viewModel.effect.collect(onOpen) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.chat_assistant_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.opening) LoadingState(label = stringResource(R.string.chat_loading))
            state.error?.let { StatusBanner(it.message(), BannerTone.Error, actionLabel = stringResource(R.string.chat_retry_same), onAction = viewModel::open) }
            TextButton(onClick = onHistory) { Text(stringResource(R.string.chat_history)) }
        }
    }
}
