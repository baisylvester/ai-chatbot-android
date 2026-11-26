package com.embeddablechatbot.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavDestination.Companion.hasRoute
import com.embeddablechatbot.android.feature.auth.WelcomeScreen
import com.embeddablechatbot.android.feature.auth.RegistrationScreen
import com.embeddablechatbot.android.feature.auth.BootstrapScreen
import com.embeddablechatbot.android.feature.mail.ConnectOnboardingScreen
import com.embeddablechatbot.android.core.designsystem.ChatbotTheme
import com.embeddablechatbot.android.core.designsystem.LoadingState
import com.embeddablechatbot.android.core.domain.chat.MessageRules
import com.embeddablechatbot.android.core.domain.mail.ConnectCallback
import com.embeddablechatbot.android.core.domain.model.AuthState
import com.embeddablechatbot.android.core.domain.model.IdGenerator
import com.embeddablechatbot.android.core.domain.model.Outcome
import com.embeddablechatbot.android.core.domain.model.PlatformConfig
import com.embeddablechatbot.android.core.domain.repository.AuthRepository
import com.embeddablechatbot.android.core.domain.repository.ConfigRepository
import com.embeddablechatbot.android.core.domain.repository.ConversationRepository
import com.embeddablechatbot.android.core.domain.repository.PreferencesRepository
import com.embeddablechatbot.android.feature.auth.SettingsScreen
import com.embeddablechatbot.android.feature.auth.SetupScreen
import com.embeddablechatbot.android.feature.auth.SignInScreen
import com.embeddablechatbot.android.feature.chat.ChatScreen
import com.embeddablechatbot.android.feature.chat.AssistantEntryScreen
import com.embeddablechatbot.android.feature.chat.ChatViewModel
import com.embeddablechatbot.android.feature.chat.ConversationListScreen
import com.embeddablechatbot.android.feature.mail.ApprovalScreen
import com.embeddablechatbot.android.feature.mail.ConnectCallbackBus
import com.embeddablechatbot.android.feature.mail.MailScreen
import com.embeddablechatbot.android.feature.mail.MailHubScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object SetupRoute
@Serializable data class SignInRoute(val email: String = "")
@Serializable data object WelcomeRoute
@Serializable data object RegisterRoute
@Serializable data object BootstrapRoute
@Serializable data object ConnectOnboardingRoute
@Serializable data object ConversationsRoute
@Serializable data class AssistantRoute(val draft: String? = null)
@Serializable data object WritingToolsRoute
@Serializable data class ChatRoute(val conversationId: String, val draft: String? = null)
@Serializable data object MailRoute
@Serializable data class ApprovalRoute(val actionId: String)
@Serializable data object SettingsRoute
@Serializable data object MailHomeRoute

sealed interface RootState {
    data object Starting : RootState
    data object NeedsSetup : RootState
    data object SignedOut : RootState
    data class SignedIn(val userId: String) : RootState
}

sealed interface NavRequest {
    data object OpenMail : NavRequest
    data object OpenInbox : NavRequest
    data class OpenChat(val conversationId: String, val draft: String?) : NavRequest
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    config: ConfigRepository,
    private val preferences: PreferencesRepository,
    private val bus: ConnectCallbackBus,
    private val conversations: ConversationRepository,
    private val ids: IdGenerator,
    private val themeSync: com.embeddablechatbot.android.core.domain.repository.ThemeSync,
) : ViewModel() {
    init {
        // Each sign-in takes the account's theme from the server; an account with none saved uploads this phone's.
        viewModelScope.launch {
            auth.authState.map { (it as? AuthState.SignedIn)?.account?.userId }.distinctUntilChanged().collect { user ->
                if (user == null) return@collect
                when (val remote = themeSync.pull()) {
                    is Outcome.Ok -> {
                        if ((auth.authState.value as? AuthState.SignedIn)?.account?.userId != user) return@collect
                        val saved = remote.value
                        if (saved != null) preferences.update { p -> p.copy(accountThemes = p.accountThemes + (user to saved)) }
                        else preferences.preferences.first().accountThemes[user]?.let { themeSync.push(it) }
                    }
                    is Outcome.Err -> Unit
                }
            }
        }
    }
    val root: StateFlow<RootState> = combine(auth.authState, config.config) { a, c ->
        when {
            a == AuthState.Restoring -> RootState.Starting
            a is AuthState.SignedIn -> RootState.SignedIn(a.account.userId)
            PlatformConfig.validate(c, config.allowCleartext) != null -> RootState.NeedsSetup
            else -> RootState.SignedOut
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Starting)

    val remindersEnabled: StateFlow<Boolean> = preferences.preferences.map { it.approvalReminders }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The signed-in account's own theme; the default when signed out. Switches with the account. */
    val theme: StateFlow<com.embeddablechatbot.android.core.domain.model.ThemeChoice> = combine(auth.authState, preferences.preferences) { a, p ->
        p.themeFor((a as? AuthState.SignedIn)?.account?.userId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.embeddablechatbot.android.core.domain.model.ThemeChoice())

    private val requests = Channel<NavRequest>(Channel.BUFFERED)
    val navRequests: Flow<NavRequest> = requests.receiveAsFlow()

    private var pendingMailReturn = false
    fun takeMailReturn(): Boolean = pendingMailReturn.also { pendingMailReturn = false }
    fun finishOnboarding() = viewModelScope.launch {
        val user = (auth.authState.value as? AuthState.SignedIn)?.account?.userId ?: return@launch
        preferences.update { it.copy(onboardedAccounts = it.onboardedAccounts + user) }
        requests.send(NavRequest.OpenInbox)
    }

    /** The OAuth return link only signals the mail screen to finish the authenticated completion. */
    fun onConnectReturn(uri: Uri) {
        if (uri.scheme != "chatbotmail" || uri.host != "oauth" || uri.path != "/gmail") return
        pendingMailReturn = true
        bus.post(ConnectCallback(uri.getQueryParameter("tx"), uri.getQueryParameter("code"), uri.getQueryParameter("error")))
        requests.trySend(NavRequest.OpenMail)
    }

    fun openMail() {
        requests.trySend(NavRequest.OpenMail)
    }

    /** Shared text becomes a draft in a new chat; the user decides whether to send it. */
    fun onSharedText(text: String) {
        if (root.value !is RootState.SignedIn) return
        val draft = text.take(MessageRules.MAX_CHARS)
        viewModelScope.launch {
            val id = ids.newId()
            if (conversations.createConversation(id) is Outcome.Ok) requests.send(NavRequest.OpenChat(id, draft))
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val dark = com.embeddablechatbot.android.core.designsystem.isAppInDarkTheme(theme)
            // Status and navigation bar icons follow the app's theme, which may differ from the system's.
            LaunchedEffect(dark) {
                val style = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ChatbotTheme(theme) { AppRoot(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when {
            intent == null -> Unit
            intent.action == Intent.ACTION_VIEW && intent.data != null -> viewModel.onConnectReturn(intent.data!!)
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(viewModel::onSharedText)
            intent.getStringExtra(EXTRA_OPEN) == OPEN_MAIL -> viewModel.openMail()
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_MAIL = "mail"
        /** A new-mail notification: the app opens where it was, or on the inbox from a cold start. */
        const val OPEN_INBOX = "inbox"
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    val root by viewModel.root.collectAsStateWithLifecycle()
    val reminders by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    NotificationPermission(reminders)
    when (val state = root) {
        RootState.Starting -> LoadingState(label = stringResource(R.string.starting))
        RootState.NeedsSetup, RootState.SignedOut -> {
            val nav = rememberNavController()
            NavHost(nav, startDestination = if (state == RootState.NeedsSetup) SetupRoute else WelcomeRoute) {
                composable<SetupRoute> {
                    SetupScreen(onSaved = { nav.navigate(SignInRoute()) { popUpTo(0) } })
                }
                composable<WelcomeRoute> {
                    WelcomeScreen(onCreateAccount = { nav.navigate(RegisterRoute) }, onSignIn = { nav.navigate(SignInRoute()) })
                }
                composable<RegisterRoute> {
                    RegistrationScreen(onSignIn = { email -> nav.navigate(SignInRoute(email)) { popUpTo(0) } }, onBack = { nav.popBack() })
                }
                composable<SignInRoute> {
                    SignInScreen(onChangePlatform = { nav.navigate(SetupRoute) }, onCreateAccount = { nav.navigate(RegisterRoute) })
                }
            }
        }
        // A new account gets a fresh navigation graph: no screen of the previous account survives.
        is RootState.SignedIn -> key(state.userId) { SignedInGraph(viewModel) }
    }
}

@Composable
private fun SignedInGraph(viewModel: MainViewModel) {
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        viewModel.navRequests.collect { request ->
            when (request) {
                NavRequest.OpenMail -> {
                    when {
                        nav.currentDestination?.hasRoute<BootstrapRoute>() == true -> Unit
                        nav.currentDestination?.hasRoute<ConnectOnboardingRoute>() == true -> viewModel.takeMailReturn()
                        else -> { viewModel.takeMailReturn(); nav.navigate(MailRoute) { launchSingleTop = true } }
                    }
                }
                NavRequest.OpenInbox -> nav.navigate(MailHomeRoute) { popUpTo(nav.graph.id); launchSingleTop = true }
                is NavRequest.OpenChat -> nav.navigate(ChatRoute(request.conversationId, request.draft))
            }
        }
    }
    NavHost(nav, startDestination = BootstrapRoute) {
        composable<BootstrapRoute> {
            BootstrapScreen(onReady = { connected, completed ->
                if (viewModel.takeMailReturn()) nav.navigate(ConnectOnboardingRoute) { popUpTo(nav.graph.id) }
                else if (connected || completed) viewModel.finishOnboarding()
                else nav.navigate(ConnectOnboardingRoute) { popUpTo(nav.graph.id) }
            })
        }
        composable<ConnectOnboardingRoute> {
            ConnectOnboardingScreen(onFinished = { viewModel.finishOnboarding() })
        }
        composable<MailHomeRoute> {
            MailHubScreen(
                onAssistant = { draft -> nav.navigate(AssistantRoute(draft)) { launchSingleTop = true } },
                onSettings = { nav.navigate(SettingsRoute) },
                onConnection = { nav.navigate(MailRoute) },
                onApproval = { nav.navigate(ApprovalRoute(it)) },
            )
        }
        composable<AssistantRoute> { entry ->
            val route = entry.toRoute<AssistantRoute>()
            AssistantEntryScreen(
                onOpen = { id -> nav.navigate(ChatRoute(id, route.draft)) { popUpTo<AssistantRoute> { inclusive = true } } },
                onBack = { nav.popBack() },
                onHistory = { nav.navigate(ConversationsRoute) { popUpTo<AssistantRoute> { inclusive = true } } },
            )
        }
        composable<WritingToolsRoute> {
            MailHubScreen(
                writingToolsOnly = true,
                onBack = { nav.popBack() },
                onAssistant = { text ->
                    val previous = nav.previousBackStackEntry
                    if (text != null && previous?.destination?.hasRoute<ChatRoute>() == true) {
                        previous.savedStateHandle[ChatViewModel.ASSISTANT_INPUT] = text
                        previous.savedStateHandle[ChatViewModel.ASSISTANT_INPUT_ID] = java.util.UUID.randomUUID().toString()
                        nav.popBackStack()
                    } else nav.navigate(AssistantRoute(text)) { popUpTo<WritingToolsRoute> { inclusive = true } }
                },
                onSettings = { nav.navigate(SettingsRoute) },
                onConnection = { nav.navigate(MailRoute) },
                onApproval = { nav.navigate(ApprovalRoute(it)) },
            )
        }
        composable<ConversationsRoute> {
            // History can be the bottom of the stack; Back then goes to the inbox instead of leaving the app.
            BackHandler(enabled = nav.previousBackStackEntry == null) { nav.popBack() }
            ConversationListScreen(
                onOpen = { nav.navigate(ChatRoute(it)) },
                onBack = { nav.popBack() },
                onSettings = { nav.navigate(SettingsRoute) },
                onMailbox = { nav.navigate(MailRoute) { launchSingleTop = true } },
            )
        }
        composable<ChatRoute> { entry ->
            val incoming by entry.savedStateHandle.getStateFlow<String?>(ChatViewModel.ASSISTANT_INPUT, null).collectAsStateWithLifecycle()
            val incomingId by entry.savedStateHandle.getStateFlow<String?>(ChatViewModel.ASSISTANT_INPUT_ID, null).collectAsStateWithLifecycle()
            ChatScreen(
                onBack = { nav.popBack() },
                onMailbox = { nav.navigate(MailRoute) { launchSingleTop = true } },
                onHistory = { nav.openHistory() },
                onWritingTools = { nav.navigate(WritingToolsRoute) { launchSingleTop = true } },
                assistantInput = incoming,
                assistantInputId = incomingId,
                onInputConsumed = {
                    entry.savedStateHandle[ChatViewModel.ASSISTANT_INPUT_ID] = null
                    entry.savedStateHandle[ChatViewModel.ASSISTANT_INPUT] = null
                },
            )
        }
        composable<MailRoute> {
            MailScreen(onBack = { nav.popBack() }, onOpenAction = { nav.navigate(ApprovalRoute(it)) })
        }
        composable<ApprovalRoute> { entry ->
            entry.toRoute<ApprovalRoute>()
            ApprovalScreen(onBack = { nav.popBack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBack() }, onOpenMailbox = { nav.navigate(MailRoute) { launchSingleTop = true } })
        }
    }
}

/** Goes to the previous screen, or to the inbox (the app's home) when nothing is behind this one. */
private fun NavHostController.popBack() {
    if (!popBackStack()) navigate(MailHomeRoute) { popUpTo(0) }
}

/**
 * Returns to History already on the back stack instead of stacking another copy, so Back from
 * History never cycles History → chat → History.
 */
private fun NavHostController.openHistory() {
    if (!popBackStack<ConversationsRoute>(inclusive = false)) navigate(ConversationsRoute) { launchSingleTop = true }
}

@Composable
private fun NotificationPermission(remindersEnabled: Boolean) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(remindersEnabled) {
        if (remindersEnabled && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
