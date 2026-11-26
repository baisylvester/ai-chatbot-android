package com.embeddablechatbot.android.feature.mail

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.embeddablechatbot.android.core.designsystem.*
import com.embeddablechatbot.android.core.domain.mail.*
import com.embeddablechatbot.android.core.domain.model.ConnectionStatus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailHubScreen(onAssistant: (String?) -> Unit, onSettings: () -> Unit, onConnection: () -> Unit, onApproval: (String) -> Unit, viewModel: MailHubViewModel = hiltViewModel(), writingToolsOnly: Boolean = false, onBack: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val writer by viewModel.writer.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val preferences by viewModel.userPreferences.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val accountDescription = stringResource(R.string.hub_account)
    LifecycleResumeEffect(viewModel) { viewModel.refresh(); onPauseOrDispose { } }
    val context = LocalContext.current
    var labelTarget by remember { mutableStateOf<InboxMessage?>(null) }
    var newLabel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is HubEffect.Review -> onApproval(effect.id)
                is HubEffect.OpenFile -> openDownloadedFile(context, effect.file)
            }
        }
    }
    labelTarget?.let { target -> LabelPicker(state.labels, target.labelIds.toSet(), onDismiss = { labelTarget = null }, onApply = { viewModel.setLabels(target, it); labelTarget = null }, onNew = { labelTarget = null; newLabel = true }) }
    if (newLabel) NewLabelDialog(onDismiss = { newLabel = false }, onCreate = { viewModel.createLabel(it); newLabel = false })
    val closeCompose = { if (!composer.saving) { if (composer.body.isNotBlank() && !composer.uncertain) discard = true else viewModel.closeComposer() } }
    BackHandler(enabled = composer.visible || state.selectedId != null) { if (composer.visible) closeCompose() else viewModel.closeMessage() }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, text = { Text(stringResource(R.string.hub_discard_title)) }, confirmButton = { TextButton(onClick = { discard = false; viewModel.closeComposer() }) { Text(stringResource(R.string.hub_discard)) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.hub_keep_editing)) } })
    if (composer.visible) {
        Composer(composer, writer, viewModel, closeCompose)
        return
    }
    if (state.selectedId != null) {
        MessageReader(state, viewModel, onLabels = { labelTarget = it })
        return
    }
    if (writingToolsOnly) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.hub_writing_tools)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hub_back)) }
        }) }) { padding ->
            WritingBody(writer, viewModel, Modifier.padding(padding), onContinue = { onAssistant(it) })
        }
        return
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Text("Mail Assistant", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            state.status?.connection?.email?.let { Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MailFolder.entries.forEach { folder -> NavigationDrawerItem(label = { Text(folder.title) }, selected = state.labelId == null && state.folder == folder, onClick = { viewModel.selectTab(HubTab.Mail); viewModel.selectFolder(folder); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp)) }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Text(stringResource(R.string.hub_labels), Modifier.padding(horizontal = 28.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.labels.forEach { label -> NavigationDrawerItem(label = { Text(label.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, icon = { Text("●", color = labelColor(label)) }, selected = state.labelId == label.id, onClick = { viewModel.selectTab(HubTab.Mail); viewModel.selectLabel(label); scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp)) }
                NavigationDrawerItem(label = { Text(stringResource(R.string.hub_new_label)) }, icon = { Icon(Icons.Default.Add, null) }, selected = false, onClick = { newLabel = true; scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(16.dp))
            }
        }
    }) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TopAppBar(
                title = { Text(when (state.tab) { HubTab.Mail -> state.labels.firstOrNull { it.id == state.labelId }?.name ?: if (state.folder in listOf(MailFolder.Inbox,MailFolder.Unread,MailFolder.Starred,MailFolder.Attachments)) stringResource(R.string.hub_inbox) else state.folder.title; HubTab.Writing -> stringResource(R.string.hub_ai); HubTab.Prompts -> stringResource(R.string.hub_prompts) }, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, stringResource(R.string.hub_folders)) } },
                actions = {
                    if (state.tab == HubTab.Mail) IconButton(onClick = { searchVisible = !searchVisible }) { Icon(Icons.Default.Search, stringResource(R.string.hub_search)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = onConnection, modifier = Modifier.semantics { contentDescription = accountDescription }) { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(34.dp)) { Box(contentAlignment = Alignment.Center) { Text(state.status?.connection?.email?.take(1)?.uppercase() ?: "M", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) } } }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) },
            bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(selected = state.tab == HubTab.Mail, onClick = { viewModel.selectTab(HubTab.Mail) }, icon = { Icon(Icons.Default.MailOutline, null) }, label = { Text(stringResource(R.string.hub_inbox)) })
                NavigationBarItem(selected = false, onClick = { onAssistant(null) }, icon = { Text("✦", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary) }, label = { Text(stringResource(R.string.hub_assistant)) })
                NavigationBarItem(selected = state.tab == HubTab.Prompts, onClick = { viewModel.selectTab(HubTab.Prompts) }, icon = { Icon(Icons.Default.List, null) }, label = { Text(stringResource(R.string.hub_prompts)) })
            } },
            floatingActionButton = { if (state.tab == HubTab.Mail && state.status?.connection?.status == ConnectionStatus.Active) ExtendedFloatingActionButton(onClick = { viewModel.compose() }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text(stringResource(R.string.hub_compose)) }, containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) },
        ) { padding ->
            when (state.tab) {
                HubTab.Mail -> InboxBody(state, viewModel, searchVisible, onConnection, Modifier.padding(padding), onLabels = { labelTarget = it })
                HubTab.Writing -> WritingBody(writer, viewModel, Modifier.padding(padding), onContinue = { onAssistant(it) })
                HubTab.Prompts -> PromptLibrary(preferences.favoritePrompts, viewModel::toggleFavorite, { onAssistant(it.text) }, Modifier.padding(padding))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxBody(state: MailHubState, vm: MailHubViewModel, searchVisible: Boolean, onConnection: () -> Unit, modifier: Modifier, onLabels: (InboxMessage) -> Unit) {
    Column(modifier.fillMaxSize()) {
        if (searchVisible) OutlinedTextField(state.query, vm::query, label = { Text(stringResource(R.string.hub_search)) }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search,null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search() }), trailingIcon = { IconButton(onClick = vm::search) { Icon(Icons.Default.Check,"Search") } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(24.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MailFolder.Inbox to "All mail",MailFolder.Unread to "Unread",MailFolder.Starred to "Starred",MailFolder.Attachments to "Attachments").forEach { (folder,title) -> FilterChip(selected = state.labelId == null && state.folder == folder, onClick = { vm.selectFolder(folder) }, label = { Text(title) }, shape = RoundedCornerShape(24.dp)) }
            }
            IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh,stringResource(R.string.hub_refresh)) }
        }
        state.error?.let { StatusBanner(it.message(), BannerTone.Error, Modifier.padding(16.dp), actionLabel = stringResource(R.string.hub_refresh), onAction = vm::refresh) }
        state.status?.pendingApprovals?.takeIf { it > 0 }?.let { count -> StatusBanner(pluralStringResource(R.plurals.hub_approval_count,count,count),BannerTone.Info,Modifier.padding(horizontal = 16.dp),actionLabel = stringResource(R.string.hub_review),onAction = onConnection) }
        state.notice?.let { StatusBanner(it, BannerTone.Info, Modifier.padding(horizontal = 16.dp), actionLabel = if (state.undo != null) stringResource(R.string.hub_undo) else null, onAction = if (state.undo != null) vm::undo else null) }
        if (state.loading && !state.loaded) LoadingState(Modifier.weight(1f), stringResource(R.string.hub_loading))
        else if (state.error != null && !state.loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { OutlinedButton(onClick = onConnection) { Text(stringResource(R.string.hub_connect)) } }
        } else if (state.status?.connection?.status != ConnectionStatus.Active || state.status?.eligible != true) {
            EmptyState(stringResource(R.string.hub_connect_title), stringResource(R.string.hub_connect_body), Modifier.weight(1f)) { Button(onClick = onConnection) { Text(stringResource(R.string.hub_connect)) } }
        } else {
            if (state.messages.isEmpty() && state.loaded) EmptyState(stringResource(if (state.query.isBlank()) R.string.hub_no_mail else R.string.hub_no_results),stringResource(if (state.query.isBlank()) R.string.hub_no_mail_body else R.string.hub_no_results_body), Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp)) {
                items(state.messages,key = InboxMessage::id) { message ->
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                        if (!state.changing) when (value) { SwipeToDismissBoxValue.StartToEnd -> vm.change(message,if (message.starred) MailChange.Unstar else MailChange.Star); SwipeToDismissBoxValue.EndToStart -> vm.change(message,MailChange.Archive); else -> Unit }
                        false
                    })
                    SwipeToDismissBox(state = swipe, backgroundContent = { Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Star,null); Text(stringResource(R.string.hub_archive)) } }, enableDismissFromStartToEnd = !state.changing, enableDismissFromEndToStart = !state.changing) { MessageRow(message, state.changing, { vm.open(message) }, { vm.change(message,it) }, labels = state.labels.filter { it.id in message.labelIds }, onLabels = { onLabels(message) }) }
                }
                if (state.cursor != null) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = vm::more, enabled = !state.loadingMore) { if (state.loadingMore) CircularProgressIndicator(Modifier.size(20.dp)) else Text(stringResource(R.string.hub_load_more)) } } }
            }
        }
    }
}

@Composable
private fun MessageRow(message: InboxMessage, busy: Boolean, onOpen: () -> Unit, change: (MailChange) -> Unit, labels: List<MailLabel> = emptyList(), onLabels: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val name = message.from.substringBefore('<').trim().trim('"').ifBlank { message.from }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                val (avatarBg, avatarInk) = avatarColors(name)
                Surface(shape = CircleShape, color = avatarBg, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase().ifBlank { "M" },color = avatarInk,fontWeight = FontWeight.SemiBold) } }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name.ifBlank { "Unknown sender" }, Modifier.weight(1f), fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(mailDate(message.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(message.subject.ifBlank { stringResource(R.string.hub_no_subject) }, fontWeight = if (message.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(message.snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (labels.isNotEmpty()) LabelChips(labels.take(3), Modifier.padding(top = 4.dp))
                }
                Column {
                    IconButton(onClick = { change(if (message.starred) MailChange.Unstar else MailChange.Star) }, enabled = !busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Star, stringResource(if (message.starred) R.string.hub_unstar else R.string.hub_star), tint = if (message.starred) Color(0xFFF6AD32) else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(21.dp)) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert,stringResource(R.string.hub_more),modifier = Modifier.size(20.dp)) }
                        DropdownMenu(menu,{ menu = false }) {
                            listOf((if (message.unread) R.string.hub_mark_read to MailChange.Read else R.string.hub_mark_unread to MailChange.Unread), R.string.hub_archive to MailChange.Archive,R.string.hub_move_inbox to MailChange.Inbox,R.string.hub_spam to MailChange.Spam,R.string.hub_trash to MailChange.Trash).forEach { (label,action) -> DropdownMenuItem(text = { Text(stringResource(label)) },onClick = { menu = false; change(action) }) }
                            DropdownMenuItem(text = { Text(stringResource(R.string.hub_edit_labels)) }, onClick = { menu = false; onLabels() })
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 74.dp),color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        }
    }
}

private fun mailDate(raw: String): String = runCatching { ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME).format(DateTimeFormatter.ofPattern("MMM d")) }.getOrElse { raw.take(10) }

@Composable
private fun WritingBody(state: WriterState, vm: MailHubViewModel, modifier: Modifier, onContinue: (String) -> Unit) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("A little inspiration. A better email.",style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) { WritingTask.entries.forEach { task -> FilterChip(selected = state.task == task,onClick = { vm.writing(task = task) },label = { Text(task.title) },enabled = !state.busy,shape = CircleShape) } }
        Surface(shape = RoundedCornerShape(22.dp),color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp)) {
                TextField(value = state.text,onValueChange = { vm.writing(text = it) },enabled = !state.busy,placeholder = { Text(stringResource(R.string.hub_write_hint)) },modifier = Modifier.fillMaxWidth().heightIn(min = 210.dp),colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,unfocusedContainerColor = Color.Transparent,disabledContainerColor = Color.Transparent,focusedIndicatorColor = Color.Transparent,unfocusedIndicatorColor = Color.Transparent))
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween,verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { val clip = context.getSystemService(ClipboardManager::class.java).primaryClip; val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString(); text?.let { vm.writing(text = it) } },enabled = !state.busy) { Text(stringResource(R.string.hub_paste)) }
                    Text("${state.text.length}/20000",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(shape = RoundedCornerShape(22.dp),color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(18.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.hub_options),fontWeight = FontWeight.SemiBold,style = MaterialTheme.typography.titleMedium)
                Choice(stringResource(R.string.hub_language),state.language,listOf("English","Filipino","Spanish","French","German","Japanese","Korean","Vietnamese","Thai","Chinese"),!state.busy) { vm.writing(language = it) }
                Choice(stringResource(R.string.hub_tone),state.tone,listOf("Professional","Formal","Casual","Friendly","Confident","Empathetic"),!state.busy) { vm.writing(tone = it) }
                Choice(stringResource(R.string.hub_length),state.length,listOf("Short","Medium","Long"),!state.busy) { vm.writing(length = it) }
            }
        }
        state.error?.let { StatusBanner(it.message(),BannerTone.Error) }
        Button(onClick = vm::generate,enabled = !state.busy && state.text.isNotBlank(),modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),shape = CircleShape) { if (state.busy) { CircularProgressIndicator(Modifier.size(18.dp),strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }; Text(stringResource(if (state.busy) R.string.hub_generating else R.string.hub_generate)) }
        if (state.result.isNotBlank()) OutlinedCard(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(18.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.hub_result),fontWeight = FontWeight.SemiBold,modifier = Modifier.semantics { heading() })
                SelectionContainer { Text(state.result) }
                TextButton(onClick = { onContinue(state.result) }) { Text(stringResource(R.string.hub_continue_assistant)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Email",state.result)) }) { Text(stringResource(R.string.hub_copy)) }
                    Button(onClick = { vm.compose(body = state.result) }) { Text(stringResource(R.string.hub_use_email)) }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String,value: String,options: List<String>,enabled: Boolean = true,onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true },enabled = enabled,shape = CircleShape,color = MaterialTheme.colorScheme.surface,modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 16.dp,vertical = 10.dp),horizontalArrangement = Arrangement.SpaceBetween) { Text(label,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value,style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.primary) } }
        DropdownMenu(expanded,{ expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) },onClick = { expanded = false; onSelect(option) }) } }
    }
}

@Composable
private fun PromptLibrary(favorites: Set<String>,onFavorite: (String) -> Unit,onUse: (MailPrompt) -> Unit,modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("All") }
    val prompts = MailPrompts.all.filter { (category == "All" || it.category == category || category == "Pinned" && it.id in favorites) && "${it.title} ${it.description}".contains(query,true) }.sortedByDescending { it.id in favorites }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(query,{ query = it },label = { Text(stringResource(R.string.hub_search_prompts)) },leadingIcon = { Icon(Icons.Default.Search,null) },singleLine = true,shape = CircleShape,modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All","Pinned","Work","Marketing","Sales","Personal").forEach { name -> FilterChip(selected = category == name,onClick = { category = name },label = { Text(name) },shape = CircleShape) } }
        LazyColumn(contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(prompts,key = MailPrompt::id) { prompt ->
                Card(onClick = { onUse(prompt) },shape = RoundedCornerShape(20.dp),colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prompt.title,Modifier.weight(1f),style = MaterialTheme.typography.titleMedium,fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { onFavorite(prompt.id) }) { Icon(Icons.Default.Star,stringResource(if (prompt.id in favorites) R.string.hub_unpin else R.string.hub_pin),tint = if (prompt.id in favorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) }
                        }
                        Text(prompt.description,color = MaterialTheme.colorScheme.onSurfaceVariant,style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = CircleShape,color = MaterialTheme.colorScheme.primaryContainer) { Text(prompt.category,Modifier.padding(horizontal = 10.dp,vertical = 4.dp),style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    }
                }
            }
            if (prompts.isEmpty()) item { EmptyState("No prompts found","Try another category or search.") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageReader(state: MailHubState,vm: MailHubViewModel,onLabels: (InboxMessage) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Message") },navigationIcon = { IconButton(onClick = vm::closeMessage) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.hub_back)) } },actions = { state.selected?.let { m -> IconButton(onClick = { vm.change(m.summary,MailChange.Archive) },enabled = !state.changing) { Icon(Icons.Default.Check,stringResource(R.string.hub_archive)) }; IconButton(onClick = { vm.change(m.summary,MailChange.Trash) },enabled = !state.changing) { Icon(Icons.Default.Delete,stringResource(R.string.hub_trash)) } } }) }) { padding ->
        if (state.opening) LoadingState(Modifier.padding(padding),stringResource(R.string.hub_loading))
        else Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(18.dp)) {
            state.error?.let { StatusBanner(it.message(),BannerTone.Error,actionLabel = stringResource(R.string.hub_refresh),onAction = vm::retryMessage) }
            state.selected?.let { mail ->
                Text(mail.summary.subject.ifBlank { stringResource(R.string.hub_no_subject) },style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
                Text(mail.summary.from,fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.hub_to_line,mail.to),color = MaterialTheme.colorScheme.onSurfaceVariant,style = MaterialTheme.typography.bodySmall)
                Text(mail.summary.date,color = MaterialTheme.colorScheme.onSurfaceVariant,style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val labels = state.labels.filter { it.id in mail.summary.labelIds }
                    if (labels.isNotEmpty()) LabelChips(labels, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onLabels(mail.summary) }, enabled = !state.changing) { Text("🏷  " + stringResource(R.string.hub_edit_labels)) }
                }
                if (mail.attachments.isNotEmpty()) AttachmentList(mail.attachments, state.attachmentBusy, vm::openAttachment, vm::summarizeAttachment)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(WritingTask.Summary,WritingTask.Translate,WritingTask.Grammar).forEach { task -> OutlinedButton(onClick = { vm.assistMessage(task) }) { Text("✦ ${task.title}") } } }
                HorizontalDivider(); SelectionContainer { Text(mail.body,style = MaterialTheme.typography.bodyLarge) }
                if (mail.truncated) StatusBanner(stringResource(R.string.hub_truncated),BannerTone.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.compose(subject = if (mail.summary.subject.startsWith("Re:",true)) mail.summary.subject else "Re: ${mail.summary.subject}",replyId = mail.summary.id) }) { Text(stringResource(R.string.hub_reply)) }
                    OutlinedButton(onClick = { vm.compose(subject = "Fwd: ${mail.summary.subject}",body = "\n\n---------- Forwarded message ----------\nFrom: ${mail.summary.from}\nSubject: ${mail.summary.subject}\n\n${mail.body}") }) { Text(stringResource(R.string.hub_forward)) }
                }
                TextButton(onClick = { vm.change(mail.summary,if (mail.summary.unread) MailChange.Read else MailChange.Unread) },enabled = !state.changing) { Text(stringResource(if (mail.summary.unread) R.string.hub_mark_read else R.string.hub_mark_unread)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(state: ComposerState,writer: WriterState,vm: MailHubViewModel,onBack: () -> Unit) {
    var showAi by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.hub_new_email)) },navigationIcon = { IconButton(onClick = onBack,enabled = !state.saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack,stringResource(R.string.hub_back)) } },actions = { TextButton(onClick = vm::saveDraft,enabled = !state.saving && !state.uncertain && state.body.isNotBlank()) { Text(stringResource(if (state.saving) R.string.hub_saving else R.string.hub_review_draft)) } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.replyId == null) OutlinedTextField(state.to,{ vm.editComposer(to = it) },label = { Text(stringResource(R.string.hub_to)) },singleLine = true,enabled = !state.saving && !state.uncertain,keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.subject,{ vm.editComposer(subject = it) },label = { Text(stringResource(R.string.hub_subject)) },singleLine = true,enabled = !state.saving && !state.uncertain,modifier = Modifier.fillMaxWidth())
            ComposerBody(state, vm)
            ComposerAttachments(state, vm)
            Text(stringResource(R.string.hub_draft_safety),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { StatusBanner(it.message(),BannerTone.Error) }
            if (state.uncertain) StatusBanner(stringResource(R.string.hub_draft_uncertain),BannerTone.Warning,actionLabel = stringResource(R.string.hub_check_drafts),onAction = { vm.closeComposer(); vm.selectFolder(MailFolder.Drafts); vm.selectTab(HubTab.Mail) })
            OutlinedButton(onClick = { showAi = true; vm.writing(task = WritingTask.Grammar,text = state.body); vm.generate() },enabled = !state.saving && !state.uncertain && !writer.busy && state.body.isNotBlank()) { Text(if (writer.busy) stringResource(R.string.hub_generating) else "✦ ${stringResource(R.string.hub_grammar)}") }
            if (showAi) writer.error?.let { StatusBanner(it.message(),BannerTone.Error) }
        }
    }
    if (showAi && writer.result.isNotBlank()) AlertDialog(onDismissRequest = { showAi = false },title = { Text(stringResource(R.string.hub_result)) },text = { Text(writer.result,Modifier.verticalScroll(rememberScrollState())) },confirmButton = { TextButton(onClick = { vm.editComposer(body = writer.result); showAi = false }) { Text(stringResource(R.string.hub_apply)) } },dismissButton = { TextButton(onClick = { showAi = false }) { Text(stringResource(R.string.hub_cancel)) } })
}
