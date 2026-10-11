package com.hermes.bot.navigation
import androidx.compose.runtime.rememberUpdatedState

import androidx.activity.compose.BackHandler
import com.hermes.bot.feature.work.WorkCronsViewModel
import com.hermes.bot.feature.work.WorkScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import com.hermes.bot.feature.connect.ConnectAtelierTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermes.bot.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.feature.brain.BrainScreen
import com.hermes.bot.feature.brain.BrainMainSettingsViewModel
import com.hermes.bot.feature.brain.BrainAuxiliaryModelsViewModel
import com.hermes.bot.feature.brain.BrainSkillsViewModel
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.feature.bots.BotsScreen
import com.hermes.bot.feature.bots.BotsViewModel
import com.hermes.bot.feature.bots.BotChannelViewModel
import com.hermes.bot.feature.bots.BotChannelScreen
import com.hermes.bot.feature.bots.BotChannelComposer
import com.hermes.bot.feature.bots.botBoardConversations
import com.hermes.bot.feature.bots.isChannelListReady
import com.hermes.bot.feature.bots.isChannelListVisible
import com.hermes.bot.feature.chat.ChatScreen
import com.hermes.bot.feature.connect.StartupPresentation
import com.hermes.bot.feature.connect.ConnectScreen
import com.hermes.bot.feature.connect.ConnectState
import com.hermes.bot.feature.connect.ConnectUiState
import com.hermes.bot.feature.connect.ProfilesUiState
import com.hermes.bot.feature.sessions.SessionsScreen
import com.hermes.bot.feature.settings.SettingsScreen
import com.hermes.bot.feature.settings.DebugLogsScreen
import com.hermes.bot.feature.settings.DebugLogsViewModel

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.hermes.bot.data.context.ContextProfileSelectionResult
import com.hermes.bot.data.context.ContextProfileRepository
import com.hermes.bot.data.context.ContextProfilesResult
import com.hermes.bot.data.context.RosterSource
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.domain.SessionError
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.feature.sessions.SessionsUiState
import com.hermes.bot.feature.sessions.SessionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Active navigation destination in the local navigation shell.
 */
enum class ShellScreen {
    CONNECT,
    SESSIONS,
    CHAT,
    BOT_SCREEN,
    BOT_CHANNEL,
    SETTINGS,
    DEBUG_LOGS
}

/**
 * Adaptive navigation shell matching specs/architecture/design/ux-navigation.md.
 *
 * First launch shows Connect only.
 * When connected:
 * - Compact screens: Sessions -> Chat, with Settings as an app-bar destination.
 * - Expanded screens (>= 600dp): side-by-side adaptive layout for Sessions and Chat.
 */
@Composable
fun HermesNavShell(
    connectUiState: ConnectUiState,
    onStartupPresented: () -> Unit = {},
    themePreference: AppThemePreference,
    onThemeChanged: (AppThemePreference) -> Unit,
    onServerNameChanged: (String) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onTestConnection: () -> Unit,
    onAuthenticate: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
    onOpenSessions: () -> Unit,
    onRetry: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onClearOfflineCache: () -> Unit,
    onClearLocalData: () -> Unit,
    modifier: Modifier = Modifier,
    sessionsViewModel: SessionsViewModel? = null,
    chatViewModelProvider: ((String) -> ChatViewModel)? = null,
    botsViewModelProvider: (() -> BotsViewModel)? = null,
    botScreenViewModelProvider: (() -> com.hermes.bot.feature.botscreen.BotScreenViewModel)? = null,
    botThreadPreviewRepository: com.hermes.bot.data.context.BotThreadPreviewRepository? = null,
    hostInsightsViewModelProvider: (() -> com.hermes.bot.feature.settings.HostInsightsViewModel)? = null,
    hostUpdateViewModelProvider: (() -> com.hermes.bot.feature.settings.HostUpdateViewModel)? = null,
    debugLogsViewModel: DebugLogsViewModel? = null,
    contextProfileRepository: ContextProfileRepository? = null,
    profilesUiState: ProfilesUiState = ProfilesUiState(),
    onSwitchProfile: (String) -> Unit = {},
    onEnterAddProfile: () -> Unit = {},
    onRemoveProfile: (String) -> Unit = {},
    brainModelsViewModelProvider: (() -> BrainMainSettingsViewModel)? = null,
    brainAuxiliaryViewModelProvider: (() -> BrainAuxiliaryModelsViewModel)? = null,
    brainSkillsViewModelProvider: (() -> BrainSkillsViewModel)? = null,
    diagnosticRing: DiagnosticRing? = null,
    workCronsViewModel: WorkCronsViewModel? = null,
    kanbanViewModel: com.hermes.bot.feature.work.KanbanViewModel? = null,
    managedFilesViewModel: com.hermes.bot.feature.work.ManagedFilesViewModel? = null
) {
    val channelVm: BotChannelViewModel = viewModel(factory = viewModelFactory {
        initializer { BotChannelViewModel(threadPreviewRepository = botThreadPreviewRepository) }
    })
    val channelState by channelVm.uiState.collectAsState()
    var botChat by rememberSaveable { mutableStateOf(false) }
    val selectedConnection = profilesUiState.profiles.firstOrNull { it.localId == profilesUiState.activeProfileId }
    val latestBotConnection by rememberUpdatedState(Triple(profilesUiState.activeProfileId,
        selectedConnection?.normalizedOrigin, connectUiState.state))
    var currentScreen by rememberSaveable { mutableStateOf(StartupLandingPolicy.initialScreen(connectUiState.startupPresentation)) }
    var previousScreen by rememberSaveable { mutableStateOf(ShellScreen.CONNECT) }
    var activeChatSessionId by rememberSaveable { mutableStateOf("") }
    var hasChatSelection by rememberSaveable { mutableStateOf(false) }
    var workspaceIntentGeneration by remember { mutableStateOf(0L) }
    var selectedTab by rememberSaveable { mutableStateOf(BottomNavTab.DEFAULT) }
    var botScreenProfile by rememberSaveable { mutableStateOf("") }
    var botScreenDisplayName by rememberSaveable { mutableStateOf("") }
    var botScreenConnectionId by rememberSaveable { mutableStateOf("") }
    var botScreenOrigin by rememberSaveable { mutableStateOf("") }
    val botScreenVm: com.hermes.bot.feature.botscreen.BotScreenViewModel? = if (botScreenViewModelProvider != null)
        viewModel(factory = viewModelFactory { initializer { botScreenViewModelProvider.invoke() } }) else null
    fun openBotScreen(profile: String, name: String) {
        val connectionId = profilesUiState.activeProfileId ?: return
        val connection = profilesUiState.profiles.firstOrNull { it.localId == connectionId }
        val origin = connection?.normalizedOrigin ?: return
        if (profile.isBlank() || origin.isBlank()) return
        botScreenProfile = profile
        botScreenDisplayName = name
        botScreenConnectionId = connectionId
        botScreenOrigin = origin
        previousScreen = currentScreen
        currentScreen = ShellScreen.BOT_SCREEN
    }
    var isSwitchPending by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceSheet by rememberSaveable { mutableStateOf(false) }
    var workspaceSheetState by remember { mutableStateOf<ContextProfileSheetState>(ContextProfileSheetState.Loading) }
    val coroutineScope = rememberCoroutineScope()

    // PR-7 (REQ-05, A-08): in-flight guard for an optimistic workspace switch
    // — while a workspace switch launch runs, the reconnect sheet reload gate
    // stays closed (no existing flag covered this; it mirrors
    // reduceOptimisticSwitch's own in-flight window).
    var isWorkspaceSwitchInFlight by remember { mutableStateOf(false) }
    // PR-7 (REQ-05): one remembered prev-state holder for the sheet reload
    // gate — null until the first observation (never an edge), updated on
    // EVERY effect run, sheet open or closed.
    var previousShellConnectState by remember { mutableStateOf<ConnectState?>(null) }

    // PR-7 (REQ-05): ONE remembered sheet read with a retainOnError flag. The
    // open path calls it with false (a manual open may replace any
    // presentation — PR-2's fail-closed Error on failure); the RECONNECT path
    // (below) calls it with true, so a failed reload never destroys the
    // cached roster + caption. Success/Empty always land (server truth
    // replaces the roster through the SAME Loaded path, LIVE source).
    // GET-only: roster read only, no activation (SSR-3).
    val loadWorkspaceSheet: suspend (Boolean) -> Unit = remember(contextProfileRepository) {
        { retainOnError ->
            val repo = contextProfileRepository
            if (repo == null) {
                if (!retainOnError) {
                    workspaceSheetState = ContextProfileSheetState.Error(ContextProfileSwitcherPolicy.ERROR_UNAVAILABLE)
                }
            } else {
                val next = when (val result = repo.getProfiles()) {
                    is ContextProfilesResult.Success -> {
                        if (result.profiles.isEmpty()) {
                            ContextProfileSheetState.Empty
                        } else {
                            ContextProfileSheetState.Loaded(
                                profiles = ContextProfileSwitcherPolicy.toRowStates(result.profiles),
                                activeProfileName = result.activeProfileName,
                                source = result.source,
                                capturedAtMillis = result.capturedAtMillis
                            )
                        }
                    }
                    is ContextProfilesResult.Error -> {
                        ContextProfileSheetState.Error(result.safeMessage)
                    }
                }
                // retainOnError blocks ONLY the error writes — a failed
                // reconnect reload leaves workspaceSheetState untouched.
                if (!retainOnError || next !is ContextProfileSheetState.Error) {
                    workspaceSheetState = next
                }
            }
        }
    }

    LaunchedEffect(showWorkspaceSheet) {
        if (showWorkspaceSheet) {
            workspaceSheetState = ContextProfileSheetState.Loading
            loadWorkspaceSheet(false)
        }
    }

    // PR-7 (REQ-05): RECONNECT reload of the CACHED Workspaces roster. The
    // decision is CacheReconnectGatePolicy (pure, JVM-tested): an edge into
    // CONNECTED_COMPATIBLE (prev-holder non-null, previously not connected),
    // sheet open, presenting PR-2's CACHED roster, and no optimistic switch
    // in flight. Closed-sheet edges do nothing — the open effect above fetches
    // on the next open, so state observed while closed is never stale on
    // presentation. Success replaces the roster (LIVE caption clears a stale
    // one); a failed reload leaves workspaceSheetState untouched (cached
    // caption persists). GET-only: roster read only, no activation (SSR-3).
    LaunchedEffect(showWorkspaceSheet, connectUiState.state) {
        val currentConnect = connectUiState.state
        val wasConnected: Boolean? = previousShellConnectState?.let { it == ConnectState.CONNECTED_COMPATIBLE }
        previousShellConnectState = currentConnect
        val loadedRoster = workspaceSheetState as? ContextProfileSheetState.Loaded
        if (CacheReconnectGatePolicy.shouldReloadSheet(
            wasConnected = wasConnected,
            isConnected = currentConnect == ConnectState.CONNECTED_COMPATIBLE,
            sheetOpen = showWorkspaceSheet,
            presentingCachedRoster = loadedRoster?.source == RosterSource.CACHED,
            optimisticSwitchInFlight = isWorkspaceSwitchInFlight
        )) {
            loadWorkspaceSheet(true)
        }
    }

    val handleSwitchProfile: (String) -> Unit = { localId ->
        if (currentScreen == ShellScreen.CHAT || currentScreen == ShellScreen.SETTINGS) {
            isSwitchPending = true
        }
        if (localId != profilesUiState.activeProfileId) sessionsViewModel?.revokeSessionAccess()
        onSwitchProfile(localId)
    }

    // CHG-005-S4 (TASK-007): the tapped SessionSummary's server-authoritative
    // title, threaded from the Sessions list into the chat route. No
    // session.list re-fetch on reopen (owner decision: ALT-003 rejected) —
    // the summary title the user tapped is good enough.
    var activeChatSessionTitle by rememberSaveable { mutableStateOf<String?>(null) }

    // BLD-006 S1: keyed ChatViewModel store owned by one activity-retained
    // ViewModel. `remember(activeChatSessionId)` VMs previously leaked a live
    // gateway collector per branch switch / session change; this owner makes
    // disposal explicit via clear(previousKey).
    // BUGFIX (chat composer dead input): `viewModel()` with no factory could not
    // construct ChatRouteOwnerViewModel because its single constructor parameter
    // has no default, so the default AndroidViewModelFactory silently returned a
    // null owner. Every ChatScreen then rendered with viewModel == null and the
    // composer's onValueChange no-op'd, making typed text invisible. Explicitly
    // construct the owner through a factory that forwards the provider.
    val chatOwner: ChatRouteOwnerViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ChatRouteOwnerViewModel(chatViewModelProvider) }
        }
    )
    val chatVmAvailable = chatViewModelProvider != null
    var previousChatKey by rememberSaveable { mutableStateOf<String?>(null) }

    fun resetShellWorkspace() {
        channelVm.leaveChannel()
        botChat = false
        workspaceIntentGeneration++
        sessionsViewModel?.resetWorkspace()
        hasChatSelection = false
        activeChatSessionId = ""
        activeChatSessionTitle = null
        previousChatKey = null
        previousScreen = ShellScreen.SESSIONS
        currentScreen = ShellScreen.SESSIONS
        selectedTab = BottomNavTab.CHATS
    }

    val composedWorkspaceIntent = workspaceIntentGeneration
    val workspaceActionsAllowed: () -> Boolean = {
        workspaceActionIsCurrent(composedWorkspaceIntent, workspaceIntentGeneration,
            StartupLandingPolicy.remoteActionsAllowed(connectUiState.state) && !isWorkspaceSwitchInFlight &&
                sessionsViewModel?.uiState?.value?.let { !it.isLoading && it.error == null && !it.isCreatingSession } != false)
    }
    val sessionOpenAllowed: () -> Boolean = {
        workspaceActionsAllowed() || (!isWorkspaceSwitchInFlight &&
            connectUiState.startupPresentation == StartupPresentation.SESSIONS &&
            !StartupLandingPolicy.remoteActionsAllowed(connectUiState.state) &&
            sessionsViewModel?.uiState?.value?.let { it.isOfflineCache && !it.isLoading && !it.isCreatingSession } == true)
    }
    val openSelectedChat: (String) -> Unit = workspaceOpenCallback(sessionOpenAllowed) { id ->
        activeChatSessionId = id
        hasChatSelection = true
        activeChatSessionTitle = sessionsViewModel?.uiState?.value?.sessions?.firstOrNull { it.id == id }?.title
        currentScreen = ShellScreen.CHAT
    }

    DisposableEffect(sessionsViewModel, contextProfileRepository) {
        sessionsViewModel?.setWorkspaceRecoveryHandler { request, generation ->
            if (!isWorkspaceSwitchInFlight && sessionsViewModel.isWorkspaceIntentCurrent(generation) &&
                !sessionsViewModel.uiState.value.isCreatingSession) {
                isWorkspaceSwitchInFlight = true
                coroutineScope.launch {
                    performWorkspaceRecovery(request, generation, contextProfileRepository, chatOwner,
                        sessionsViewModel, ::resetShellWorkspace) { inFlight -> isWorkspaceSwitchInFlight = inFlight }
                }
            }
        }
        onDispose { sessionsViewModel?.setWorkspaceRecoveryHandler(null) }
    }

    // CHG-005-S4 (TASK-007): looks up the tapped session's summary title from
    // the live Sessions list state and threads it onto the keyed ChatViewModel
    // (the provider signature `(String) -> ChatViewModel` is frozen outside
    // this slice's allowed paths, so the title rides on AFTER creation).
    // Blank/absent titles are ignored — never fabricated, never the raw ID.
    fun resolveTappedSessionTitle(sessionId: String): String? =
        sessionsViewModel?.uiState?.value?.sessions
            ?.firstOrNull { it.id == sessionId }
            ?.title
            ?.takeUnless { it.isBlank() }

    // One-behavior cleanup effect: whenever the displayed chat key changes
    // (session switch or route exit), dispose the collector of the previously
    // displayed key. Keyed child creation is synchronous, so by the time this
    // effect runs on recomposition, the new key is already materialized.
    // The disposal decision itself is the extracted, unit-tested
    // ChatShellCleanupPolicy — the effect only executes it.

    // If disconnected from within connected screens, return to CONNECT
    val isConnected = connectUiState.state == ConnectState.CONNECTED_COMPATIBLE
    LaunchedEffect(isConnected, profilesUiState.activeProfileId) {
        workCronsViewModel?.connectionChanged(isConnected, profilesUiState.activeProfileId)
        kanbanViewModel?.connectionChanged(isConnected, profilesUiState.activeProfileId)
    }
    LaunchedEffect(profilesUiState.activeProfileId, selectedConnection?.normalizedOrigin, isConnected) {
        channelVm.clearConnection()
        if (currentScreen == ShellScreen.BOT_CHANNEL || botChat) {
            chatOwner.clear(previousChatKey)
            previousChatKey = null
            botChat = false
            hasChatSelection = false
            currentScreen = if (isConnected) ShellScreen.SESSIONS else ShellScreen.CONNECT
            selectedTab = BottomNavTab.BOTS
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp


        // CHG-006-S2 (TASK-007): root-exit state machine scoped to the shell.
        // Production clock is the monotonic SystemClock.elapsedRealtime();
        // JVM tests drive a fake clock through the injected seam.
        val context = LocalContext.current
        val rootExitPolicy = remember {
            RootExitPolicy(clock = SystemClock::elapsedRealtime, windowMs = 2000L)
        }

        val workspaceTrigger: @Composable () -> Unit = {
            ContextProfileSwitcherTrigger(
                isHostConnected = isConnected,
                dropdownOnly = currentScreen == ShellScreen.SESSIONS,
                onClick = {
                    diagnosticRing?.record(DiagnosticEvent.Switch.SheetOpened())
                    showWorkspaceSheet = true
                })
        }

        // CHG-006-S1 (TASK-002) + CHG-006-S2 (TASK-007): ONE shell-level
        // system-back handler, enabled on ALL screens now: non-root screens
        // navigate inward-out, root screens run the double-back-to-exit
        // pattern (toast on first press, Activity.finish on the second press
        // within 2000 ms). The decision is the extracted, unit-tested
        // BackPressPolicy / RootExitPolicy; this lambda only executes the
        // decisions.
        // Stale-capture audit (GUD-002, TASK-009): the lambda reads only the
        // snapshot-backed `currentScreen`/`previousScreen`
        // (rememberSaveable + mutableStateOf), the remembered
        // `rootExitPolicy`, and the stable `context` — no plain values are
        // captured at first composition, so no decision can freeze.
        BackHandler(enabled = true) {
            if (channelState.transferring || isWorkspaceSwitchInFlight) return@BackHandler
            if (currentScreen == ShellScreen.CONNECT && connectUiState.startupPresentation == StartupPresentation.SESSIONS) {
                currentScreen = if (previousScreen == ShellScreen.CHAT) ShellScreen.CHAT else ShellScreen.SESSIONS
                return@BackHandler
            }
            when (BackPressPolicy.decide(currentScreen, isExpanded, botChat)) {
                BackPressAction.NavigateToChannel -> { currentScreen = ShellScreen.SESSIONS; selectedTab = BottomNavTab.BOTS; sessionsViewModel?.refreshSessions() }
                BackPressAction.NavigateToBots -> { channelVm.leaveChannel(); currentScreen = ShellScreen.SESSIONS; selectedTab = BottomNavTab.BOTS }
                BackPressAction.NavigateToSessions -> currentScreen = ShellScreen.SESSIONS
                BackPressAction.NavigateToPrevious -> currentScreen = previousScreen
                BackPressAction.NavigateToSettings -> currentScreen = ShellScreen.SETTINGS
                BackPressAction.RootPrompt -> when (rootExitPolicy.onRootBackPress()) {
                    RootExitDecision.SHOW_EXIT_PROMPT -> Toast.makeText(
                        context,
                        R.string.back_exit_warning,
                        Toast.LENGTH_SHORT
                    ).show()
                    RootExitDecision.EXIT ->
                        // Safe-cast: if the context is not an Activity
                        // (preview/test harness), EXIT degrades to a no-op
                        // rather than crashing (plan RISK-003).
                        (context as? Activity)?.finish()
                }
            }
        }

        BackHandler(enabled = currentScreen == ShellScreen.SESSIONS && selectedTab != BottomNavTab.CHATS) {
            selectedTab = BottomNavTab.CHATS
        }

        LaunchedEffect(currentScreen, activeChatSessionId, chatVmAvailable, isExpanded, hasChatSelection, selectedTab) {
            if (chatVmAvailable) {
                val decision = ChatShellCleanupPolicy.decide(
                    currentScreen = if (currentScreen == ShellScreen.BOT_SCREEN && previousScreen == ShellScreen.CHAT)
                        ShellScreen.CHAT else currentScreen,
                    isExpanded = isExpanded,
                    activeKey = activeChatSessionId,
                    trackedKey = previousChatKey,
                    hasSelection = hasChatSelection && (currentScreen == ShellScreen.CHAT || selectedTab == BottomNavTab.CHATS)
                )
                chatOwner.clear(decision.clearKey)
                previousChatKey = decision.nextTrackedKey
            }
        }

        // CHG-005-S4 (TASK-007): thread the tapped summary title onto the
        // keyed ChatViewModel after creation. Gated on a non-null title so a
        // shell pass that never opened a chat (e.g. first composition at
        // CONNECT) never materializes a keyed VM — and its live gateway
        // collector — outside the cleanup policy's tracking. Blank titles are
        // ignored inside applyInitialTitle; a real title source always wins
        // and the raw durable ID is never fabricated as a fallback. The
        // never-overwrite-a-live-title guard lives inside applyInitialTitle
        // itself (title precedence stays in one place): a re-tap of the same
        // session in expanded dual-pane re-resolves a possibly stale summary
        // title, and it must not clobber a newer live server title
        // (GatewayEvent.SessionTitle) the keyed VM already holds.
        LaunchedEffect(activeChatSessionId, activeChatSessionTitle, chatVmAvailable, hasChatSelection, currentScreen, isExpanded, selectedTab) {
            if (hasChatSelection && chatVmAvailable && activeChatSessionTitle != null) {
                selectedChatForDisplay(chatOwner, hasChatSelection, currentScreen,
                    isExpanded, selectedTab == BottomNavTab.CHATS, activeChatSessionId)
                    ?.applyInitialTitle(activeChatSessionTitle)
            }
        }

        // CHG-007-S1 (TASK-002): when a connect becomes CONNECTED_COMPATIBLE
        // while the shell is parked on CONNECT (launch auto-connect with
        // saved credentials, manual form connect, or process-death restore),
        // land on SESSIONS with no user tap. The decision is the extracted,
        // unit-tested ConnectLandingPolicy; this effect only executes it.
        // Stale-capture audit (GUD-002, PR-7 REQ-03): the effect keys EVERY
        // value it reads — `connectUiState.state` (snapshot-backed via
        // collectAsState in MainActivity), `currentScreen` (rememberSaveable),
        // `isSwitchPending`, and now the two sessions flags — so it re-fires
        // on every transition and never freezes on first-composition
        // captures. PR-7 (REQ-03): the flags are composition-observed
        // StateFlow values (`collectAsState`) and key parts — an
        // offline-cache or local-cache-error flip alone re-fires the
        // decision; the old code read `sessionsViewModel?.uiState?.value`
        // once per key change and missed same-key flag flips (the stale-key
        // gap this fixes). Null-ViewModel fallback is a remembered
        // non-offline default; a fresh sessions VM instance gets a fresh
        // default via the remember key. The lambda reads only these values
        // and mutates only `currentScreen`; no keyed ChatViewModel is
        // materialized (CHG-005-S4 learning).
        val fallbackSessionsUiState = remember(sessionsViewModel) { MutableStateFlow(SessionsUiState()) }
        val sessionsStateFlow = sessionsViewModel?.uiState ?: fallbackSessionsUiState
        val sessionsUiState by sessionsStateFlow.collectAsState()
        val isOfflineCache = sessionsUiState.isOfflineCache
        val hasLocalCacheError = sessionsUiState.error is SessionError.LocalCacheFailure
        var admittedProfile by remember { mutableStateOf<String?>(null) }
        var admittedLocalRevision by remember { mutableStateOf(connectUiState.localAccessRevision) }
        LaunchedEffect(connectUiState.startupPresentation, connectUiState.startupRoutePending, connectUiState.state,
            profilesUiState.activeProfileId, connectUiState.localAccessRevision) {
            val profile = profilesUiState.activeProfileId
            if (admittedProfile != null && (admittedProfile != profile ||
                    admittedLocalRevision != connectUiState.localAccessRevision)) {
                sessionsViewModel?.revokeSessionAccess()
                chatOwner.clear(previousChatKey)
                hasChatSelection = false
                if (currentScreen == ShellScreen.CHAT) currentScreen = ShellScreen.SESSIONS
            }
            admittedProfile = profile
            admittedLocalRevision = connectUiState.localAccessRevision
            if (connectUiState.startupRoutePending) {
                currentScreen = StartupLandingPolicy.initialScreen(connectUiState.startupPresentation)
                previousScreen = currentScreen
                selectedTab = BottomNavTab.CHATS
                hasChatSelection = false
                botChat = false
                onStartupPresented()
            }
            when {
                StartupLandingPolicy.requiresSignIn(connectUiState.startupPresentation, connectUiState.state) -> {
                    sessionsViewModel?.revokeSessionAccess()
                    chatOwner.clear(previousChatKey)
                    previousChatKey = null
                    hasChatSelection = false
                    botChat = false
                    currentScreen = ShellScreen.CONNECT
                    previousScreen = ShellScreen.CONNECT
                }
                connectUiState.state == ConnectState.CONNECTED_COMPATIBLE -> sessionsViewModel?.finishStartupConnection()
                connectUiState.startupPresentation == StartupPresentation.SESSIONS -> {
                    sessionsViewModel?.beginConnectionRecovery()
                    sessionsViewModel?.restoreSavedLoginSessions()
                }
                connectUiState.startupPresentation == StartupPresentation.SETUP -> sessionsViewModel?.revokeSessionAccess()
            }
        }

        LaunchedEffect(connectUiState.state, currentScreen, isSwitchPending, isOfflineCache, hasLocalCacheError) {
            if (ConnectLandingPolicy.shouldLandOnSessions(connectUiState.state, currentScreen, isSwitchPending)) {
                currentScreen = if (currentScreen == ShellScreen.CONNECT && previousScreen == ShellScreen.CHAT &&
                    hasChatSelection && connectUiState.startupPresentation == StartupPresentation.SESSIONS)
                    ShellScreen.CHAT else ShellScreen.SESSIONS
                isSwitchPending = false
            } else if (isSwitchPending && (connectUiState.state == ConnectState.AUTH_REQUIRED || connectUiState.state.isErrorState)) {
                if (currentScreen == ShellScreen.CHAT) {
                    currentScreen = ShellScreen.CONNECT
                }
                isSwitchPending = false
            }
            when (SessionsReconnectPolicy.decide(currentScreen, connectUiState.state, isOfflineCache, hasLocalCacheError)) {
                SessionsReconnectAction.REFRESH_SESSIONS -> {
                    sessionsViewModel?.refreshSessions()
                }
                SessionsReconnectAction.SURFACE_ERROR -> {
                    val message = SessionsReconnectPolicy.errorMessage(
                        connectUiState.state,
                        connectUiState.errorMessage
                    )
                    sessionsViewModel?.setNetworkError(message)
                }
                SessionsReconnectAction.NONE -> Unit
            }
        }

        @Composable
        fun BotBoardChannelContent() {
            fun currentChannelConnection(): Boolean {
                val current = channelVm.uiState.value
                return latestBotConnection.third == ConnectState.CONNECTED_COMPATIBLE &&
                    current.connectionId == latestBotConnection.first && current.normalizedOrigin == latestBotConnection.second
            }
            val channelAdmitted = currentChannelConnection()
            val openCreated: (String, BotChannelViewModel.ChannelEntry) -> Boolean = { id, entry ->
                if (!currentChannelConnection() || channelVm.uiState.value.channel != entry.profile) false else {
                    try {
                        val child = chatOwner.get(id)
                        if (child?.acceptChannelFirstPrompt(entry.token, entry.text) == true) {
                            activeChatSessionId = id
                            activeChatSessionTitle = null
                            hasChatSelection = true
                            previousChatKey?.takeIf { it != id }?.let { chatOwner.clear(it) }
                            previousChatKey = id
                            botChat = true
                            currentScreen = ShellScreen.CHAT
                            true
                        } else { chatOwner.clear(id); false }
                    } catch (_: Exception) { chatOwner.clear(id); false }
                }
            }
            BotChannelScreen(if (channelAdmitted) channelState else com.hermes.bot.feature.bots.BotChannelUiState(),
                if (channelAdmitted) sessionsUiState else SessionsUiState(isLoading = true),
                onBack = { channelVm.leaveChannel(); currentScreen = ShellScreen.SESSIONS; selectedTab = BottomNavTab.BOTS },
                onRefresh = { channelVm.clearThreadPreviews(); sessionsViewModel?.refreshSessions() },
                onVisibleThreads = { ids -> channelVm.showThreadPreviews(ids,
                    currentChannelConnection() && isChannelListReady(channelState.channel, sessionsUiState) && !sessionsUiState.isOfflineCache && sessionsUiState.error == null) },
                onOpen = { id ->
                    if (currentChannelConnection() && isChannelListReady(channelState.channel, sessionsUiState) && workspaceActionsAllowed()) {
                        activeChatSessionId = id
                        activeChatSessionTitle = resolveTappedSessionTitle(id)
                        hasChatSelection = true
                        botChat = true
                        currentScreen = ShellScreen.CHAT
                    }
                }, locked = channelState.transferring, currentTheme = themePreference,
                embedded = true, onScrollPosition = channelVm::saveScrollPosition,
                composer = { BotChannelComposer(if (channelAdmitted) channelState else com.hermes.bot.feature.bots.BotChannelUiState(),
                    channelAdmitted && isChannelListReady(channelState.channel, sessionsUiState) && !sessionsUiState.isOfflineCache && sessionsUiState.error == null,
                    channelVm::updateDraft,
                    { sessionsViewModel?.let { channelVm.send(it, currentChannelConnection(), openCreated) } },
                    { channelVm.openCreated(openCreated) }) })
        }

        when (if (connectUiState.startupRoutePending) StartupLandingPolicy.initialScreen(connectUiState.startupPresentation)
            else if (StartupLandingPolicy.requiresSignIn(connectUiState.startupPresentation, connectUiState.state)) ShellScreen.CONNECT else currentScreen) {
            ShellScreen.CONNECT -> {
                // Connect fills the screen. Workspace and settings controls
                // become available only after a compatible connection.
                ConnectAtelierTheme(themePreference) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                        ConnectScreen(
                            uiState = connectUiState,
                            onHostChanged = onHostChanged,
                            onServerNameChanged = onServerNameChanged,
                            onPortChanged = onPortChanged,
                            onTestConnection = onTestConnection,
                            onAuthenticate = onAuthenticate,
                            onUsernameChanged = onUsernameChanged,
                            onPasswordChanged = onPasswordChanged,
                            onLogin = onLogin,
                            onOpenSessions = {
                                onOpenSessions()
                                currentScreen = ShellScreen.SESSIONS
                            },
                            onRetry = onRetry,
                            onToggleDiagnostics = onToggleDiagnostics,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isConnected) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        previousScreen = ShellScreen.CONNECT
                                        currentScreen = ShellScreen.SETTINGS
                                    },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Open appearance settings"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ShellScreen.SESSIONS -> {
                // BLD-006 S1: obtain the ChatViewModel from the keyed owner store
                // instead of `remember(activeChatSessionId) { provider(...) }`.
                val activeChatVm = selectedChatForDisplay(chatOwner, hasChatSelection, currentScreen,
                    isExpanded, selectedTab == BottomNavTab.CHATS, activeChatSessionId)
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        HermesBottomNavBar(
                            currentTheme = themePreference,
                            selectedTab = selectedTab,
                            onTabSelected = { if (!isWorkspaceSwitchInFlight && !channelState.transferring) { botChat = false; selectedTab = it } }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            BottomNavTab.CHATS -> {
                                if (isExpanded) {
                                    // Expanded width adaptive layout: side-by-side Sessions rail and Chat detail
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        SessionsScreen(
                                            currentTheme = themePreference,
                                            onOpenChat = openSelectedChat,
                                            workspaceActionsAllowed = workspaceActionsAllowed,
                                            sessionOpenAllowed = sessionOpenAllowed,
                                            connectionState = connectUiState.state,
                                            connectionMessage = connectUiState.errorMessage,
                                            onConnectionSettings = { previousScreen = currentScreen; currentScreen = ShellScreen.CONNECT },
                                            onNavigateToSettings = {
                                                previousScreen = ShellScreen.SESSIONS
                                                currentScreen = ShellScreen.SETTINGS
                                            },
                                            onReconnect = onRetry,
                                            viewModel = sessionsViewModel,
                                            profileSwitcher = workspaceTrigger,
                                            modifier = Modifier
                                                .width(320.dp)
                                                .fillMaxHeight()
                                        )
                                        VerticalDivider()
                                        if (!hasChatSelection) {
                                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                                Text("Select a conversation or start a new chat")
                                            }
                                        } else ChatScreen(
                                            sessionId = activeChatSessionId,
                                            recoveryState = connectUiState.state,
                                            onRecoverySignIn = { previousScreen = currentScreen; currentScreen = ShellScreen.CONNECT },
                                            onRecoveryRetry = onRetry,
                                            currentTheme = themePreference,
                                            onNavigateBack = { /* In expanded dual pane, back is unnecessary */ },
                                            onNavigateToSettings = {
                                                previousScreen = ShellScreen.SESSIONS
                                                currentScreen = ShellScreen.SETTINGS
                                            },
                                            // CHAT-015 B2: shared create-failure +
                                            // degraded-default notices from the
                                            // shared SessionsViewModel.
                                            newChatFailure = sessionsUiState.newChatFailure,
                                            newChatDegradedNotice = sessionsUiState.newChatDegradedNotice,
                                            onDismissNewChatFailure = {
                                                sessionsViewModel?.dismissNewChatFailure()
                                            },
                                            onDismissNewChatDegradedNotice = {
                                                sessionsViewModel?.dismissNewChatDegradedNotice()
                                            },
                                            onNewSlashChat = {
                                                if (workspaceActionsAllowed()) {
                                                    val intent = sessionsViewModel?.captureWorkspaceIntent()
                                                    sessionsViewModel?.createNewChat { id ->
                                                        if (workspaceActionsAllowed() && intent != null && sessionsViewModel.isWorkspaceIntentCurrent(intent)) openSelectedChat(id)
                                                    }
                                                }
                                            },
                                            onBrowseSlashChats = { query -> sessionsViewModel?.onSearchQueryChange(query) },
                                            viewModel = activeChatVm,
                                            // BLD-006 S2: same resume path as the compact branch —
                                            // no no-op callback in the expanded layout (TASK-007).
                                            onResumeOrRestore = { activeChatVm?.loadSession() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        )
                                    }
                                } else {
                                    // Compact phone layout: full Sessions screen
                                    SessionsScreen(
                                        currentTheme = themePreference,
                                        onOpenChat = openSelectedChat,
                                        workspaceActionsAllowed = workspaceActionsAllowed,
                                        sessionOpenAllowed = sessionOpenAllowed,
                                        connectionState = connectUiState.state,
                                        connectionMessage = connectUiState.errorMessage,
                                        onConnectionSettings = { previousScreen = currentScreen; currentScreen = ShellScreen.CONNECT },
                                        onNavigateToSettings = {
                                            previousScreen = ShellScreen.SESSIONS
                                            currentScreen = ShellScreen.SETTINGS
                                        },
                                        onReconnect = onRetry,
                                        viewModel = sessionsViewModel,
                                        profileSwitcher = workspaceTrigger
                                    )
                                }
                            }
                            BottomNavTab.WORK -> {
                                WorkScreen(workCronsViewModel, kanbanViewModel = kanbanViewModel,
                                    filesViewModel = managedFilesViewModel,
                                    filesConnectionKey = selectedConnection?.let { "${it.localId}|${it.normalizedOrigin}" },
                                    filesOnline = isConnected, currentTheme = themePreference,
                                    onReconnect = onRetry,
                                    onNavigateToSettings = { previousScreen = ShellScreen.SESSIONS; currentScreen = ShellScreen.SETTINGS })
                            }
                            BottomNavTab.BOTS -> {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    val botsVm = remember(botsViewModelProvider) { botsViewModelProvider?.invoke() }
                                    val savedTarget = profilesUiState.profiles.firstOrNull { it.localId == profilesUiState.activeProfileId }
                                    BotsScreen(
                                        currentTheme = themePreference,
                                        viewModel = botsVm,
                                        localId = profilesUiState.activeProfileId,
                                        normalizedOrigin = savedTarget?.normalizedOrigin,
                                        connected = connectUiState.state == ConnectState.CONNECTED_COMPATIBLE,
                                        selecting = isWorkspaceSwitchInFlight || channelState.transferring ||
                                            !ContextProfileSwitcherPolicy.isSwitchingAllowed(sessionsUiState.activeTurnStreaming.isStreaming),
                                        channel = channelState.channel.takeIf { channelState.connectionId == profilesUiState.activeProfileId && channelState.normalizedOrigin == savedTarget?.normalizedOrigin },
                                        conversationCount = botBoardConversations(sessionsUiState.sessions).size.takeIf { isChannelListVisible(channelState.channel, sessionsUiState) },
                                        channelContent = { BotBoardChannelContent() },
                                        onRefreshChannel = { channelVm.clearThreadPreviews(); sessionsViewModel?.refreshSessions() },
                                        onOpenScreen = { row -> openBotScreen(row.profile.name, row.profile.displayName) },
                                        onNavigateToSettings = { previousScreen = ShellScreen.SESSIONS; currentScreen = ShellScreen.SETTINGS },
                                        onSelect = { row ->
                                            if (com.hermes.bot.feature.bots.needsBotSelection(row.profile.name, channelState.channel, sessionsUiState.selectedProfileName) && !isWorkspaceSwitchInFlight && !channelState.transferring &&
                                                ContextProfileSwitcherPolicy.isSwitchingAllowed(sessionsUiState.activeTurnStreaming.isStreaming)) {
                                                val expectedConnection = latestBotConnection
                                                isWorkspaceSwitchInFlight = true
                                                coroutineScope.launch {
                                                    try {
                                                        val result = performWorkspaceSelection(row.profile.name, contextProfileRepository, chatOwner,
                                                            canSwitch = { latestBotConnection == expectedConnection },
                                                            sessions = sessionsViewModel, resetShell = ::resetShellWorkspace)
                                                        if (result is ContextProfileSelectionResult.Success && latestBotConnection == expectedConnection) {
                                                            channelVm.enter(row.profile.name, row.profile.displayName, row.avatar,
                                                                expectedConnection.first, expectedConnection.second)
                                                            sessionsViewModel?.loadSessions(true)
                                                            selectedTab = BottomNavTab.BOTS
                                                            currentScreen = ShellScreen.SESSIONS
                                                        } else Toast.makeText(context, "Couldn't select bot. Please retry.", Toast.LENGTH_SHORT).show()
                                                    } finally { isWorkspaceSwitchInFlight = false }
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Normalize a restored legacy route onto the unified root Bot page.
            ShellScreen.BOT_CHANNEL -> {
                LaunchedEffect(Unit) { currentScreen = ShellScreen.SESSIONS; selectedTab = BottomNavTab.BOTS }
            }

            ShellScreen.BOT_SCREEN -> {
                val current = profilesUiState.profiles.firstOrNull { it.localId == profilesUiState.activeProfileId }
                if (profilesUiState.activeProfileId != botScreenConnectionId || current?.normalizedOrigin != botScreenOrigin) {
                    LaunchedEffect(profilesUiState.activeProfileId, current?.normalizedOrigin) {
                        botScreenVm?.leave()
                        currentScreen = ShellScreen.SESSIONS
                    }
                } else {
                    com.hermes.bot.feature.botscreen.BotScreenScreen(
                        profile = botScreenProfile,
                        displayName = botScreenDisplayName,
                        target = com.hermes.bot.data.bots.BotTarget(botScreenConnectionId, botScreenOrigin),
                        viewModel = botScreenVm,
                        theme = themePreference,
                        onBack = { botScreenVm?.leave(); currentScreen = previousScreen }
                    )
                }
            }

            ShellScreen.CHAT -> {
                // BLD-006 S1: obtain the ChatViewModel from the keyed owner store;
                // route exit cleanup runs in the shell-level LaunchedEffect above.
                val activeChatVm = selectedChatForDisplay(chatOwner, hasChatSelection, currentScreen,
                    isExpanded, selectedTab == BottomNavTab.CHATS, activeChatSessionId)
                if (hasChatSelection) ChatScreen(
                    sessionId = activeChatSessionId,
                    recoveryState = connectUiState.state,
                    onRecoverySignIn = { previousScreen = currentScreen; currentScreen = ShellScreen.CONNECT },
                    onRecoveryRetry = onRetry,
                    currentTheme = themePreference,
                    onNavigateBack = { currentScreen = ShellScreen.SESSIONS
                        if (botChat) { selectedTab = BottomNavTab.BOTS; sessionsViewModel?.refreshSessions() } },
                    onNavigateToSettings = {
                        previousScreen = ShellScreen.CHAT
                        currentScreen = ShellScreen.SETTINGS
                    },
                    onOpenBotScreen = if (botChat && !sessionsUiState.selectedProfileName.isNullOrBlank()) {
                        { openBotScreen(sessionsUiState.selectedProfileName.orEmpty(), sessionsUiState.selectedProfileName.orEmpty()) }
                    } else null,
                    // CHAT-015 B2: shared create-failure + degraded-default
                    // notices from the shared SessionsViewModel.
                    newChatFailure = sessionsUiState.newChatFailure,
                    newChatDegradedNotice = sessionsUiState.newChatDegradedNotice,
                    onDismissNewChatFailure = { sessionsViewModel?.dismissNewChatFailure() },
                    onDismissNewChatDegradedNotice = {
                        sessionsViewModel?.dismissNewChatDegradedNotice()
                    },
                    onNewSlashChat = {
                        if (workspaceActionsAllowed()) {
                            val intent = sessionsViewModel?.captureWorkspaceIntent()
                            sessionsViewModel?.createNewChat { id ->
                                if (workspaceActionsAllowed() && intent != null && sessionsViewModel.isWorkspaceIntentCurrent(intent)) openSelectedChat(id)
                            }
                        }
                    },
                    onBrowseSlashChats = { query ->
                        sessionsViewModel?.onSearchQueryChange(query)
                        currentScreen = ShellScreen.SESSIONS
                    },
                    viewModel = activeChatVm,
                    // BLD-006 S2: dedicated resume action invokes the existing
                    // loadSession() flow exactly once per user activation.
                    onResumeOrRestore = { activeChatVm?.loadSession() }
                )
            }

            ShellScreen.SETTINGS -> {
                var brainOpen by remember { mutableStateOf(false) }
                var updatesOpen by rememberSaveable { mutableStateOf(false) }
                var insightsPage by rememberSaveable { mutableStateOf<com.hermes.bot.feature.settings.HostInsightsPage?>(null) }
                val readBrainProfile: suspend () -> SelectedContextReadResult = remember(contextProfileRepository) {
                    { contextProfileRepository?.readSelectedProfileAuthoritative() ?: SelectedContextReadResult.Failed }
                }
                val openInsights = insightsPage
                if (updatesOpen) {
                    key(profilesUiState.activeProfileId, connectUiState.origin, connectUiState.state, workspaceIntentGeneration) {
                        val connection = profilesUiState.profiles.firstOrNull { it.localId == profilesUiState.activeProfileId }
                        com.hermes.bot.feature.settings.HostUpdateRoute(
                            provider = hostUpdateViewModelProvider,
                            currentTheme = themePreference,
                            connectionLabel = connection?.displayLabel?.trim()?.ifBlank { "Selected host" } ?: "Selected host",
                            onBack = { updatesOpen = false }
                        )
                    }
                } else if (openInsights != null) {
                    key(profilesUiState.activeProfileId, connectUiState.origin, connectUiState.state, workspaceIntentGeneration) {
                        com.hermes.bot.feature.settings.HostInsightsRoute(openInsights, hostInsightsViewModelProvider, currentTheme = themePreference) { insightsPage = null }
                    }
                } else if (brainOpen) {
                    BrainScreen(
                        onNavigateBack = { brainOpen = false },
                        readSelectedProfile = readBrainProfile,
                        modelsViewModelProvider = brainModelsViewModelProvider,
                        auxiliaryViewModelProvider = brainAuxiliaryViewModelProvider,
                        skillsViewModelProvider = brainSkillsViewModelProvider,
                        currentTheme = themePreference
                    )
                } else SettingsScreen(
                    onOpenBrain = { brainOpen = true },
                    onOpenHealth = { insightsPage = com.hermes.bot.feature.settings.HostInsightsPage.HEALTH },
                    onOpenUpdates = { updatesOpen = true },
                    onOpenAnalytics = { insightsPage = com.hermes.bot.feature.settings.HostInsightsPage.ANALYTICS },
                    onOpenHostLogs = { insightsPage = com.hermes.bot.feature.settings.HostInsightsPage.LOGS },
                    currentTheme = themePreference,
                    onThemeChanged = onThemeChanged,
                    connectedOrigin = if (isConnected && connectUiState.origin.isNotBlank()) connectUiState.origin else null,
                    onClearOfflineCache = onClearOfflineCache,
                    onClearLocalData = {
                        onClearLocalData()
                        currentScreen = ShellScreen.CONNECT
                    },
                    onNavigateBack = { currentScreen = previousScreen },
                    onOpenDebugLogs = { currentScreen = ShellScreen.DEBUG_LOGS },
                    onOpenDiagnostics = { currentScreen = ShellScreen.DEBUG_LOGS },
                    profiles = profilesUiState.profiles,
                    activeProfileId = profilesUiState.activeProfileId,
                    onSwitchProfile = { localId -> handleSwitchProfile(localId) },
                    onRemoveProfile = { localId ->
                        val wasActive = profilesUiState.activeProfileId == localId
                        onRemoveProfile(localId)
                        if (wasActive) {
                            currentScreen = ShellScreen.CONNECT
                        }
                    },
                    onAddConnection = {
                        onEnterAddProfile()
                        currentScreen = ShellScreen.CONNECT
                    },
                    profileSwitcher = null
                )
            }
            ShellScreen.DEBUG_LOGS -> {
                debugLogsViewModel?.let {
                    DebugLogsScreen(
                        it,
                        onNavigateBack = { currentScreen = ShellScreen.SETTINGS },
                        profileSwitcher = null,
                        currentTheme = themePreference
                    )
                }
            }
        }

        // PR-6 (REQ-06): ONE shared sign-out funnel for all four entries —
        // the dialog resolves KEEP/CLEAR (default Keep-cache); cancel/back
        // just returns to IDLE with no choice escaping.
        if (showWorkspaceSheet) {
            val isStreaming = sessionsViewModel?.uiState?.collectAsState()?.value?.activeTurnStreaming?.isStreaming ?: false
            ContextProfileBottomSheet(
                currentTheme = themePreference,
                state = workspaceSheetState,
                onDismissRequest = { showWorkspaceSheet = false },
                isStreaming = isStreaming,
                onConfirmSwitch = { targetName ->
                    diagnosticRing?.record(DiagnosticEvent.Switch.ConfirmTapped(targetProfile = targetName))
                    if (!ContextProfileSwitcherPolicy.isSwitchingAllowed(isStreaming)) {
                        Toast.makeText(
                            context,
                            ContextProfileSwitcherPolicy.SWITCH_DISABLED_STREAMING_EXPLANATION,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@ContextProfileBottomSheet false
                    }
                    val repo = contextProfileRepository ?: return@ContextProfileBottomSheet false
                    if (isWorkspaceSwitchInFlight || sessionsViewModel?.uiState?.value?.isCreatingSession == true ||
                        !targetName.matches(Regex("[A-Za-z0-9._-]{1,80}"))) return@ContextProfileBottomSheet false
                    isWorkspaceSwitchInFlight = true
                    coroutineScope.launch {
                        try {
                            val result = performWorkspaceSelection(targetName, repo, chatOwner,
                                resetShell = ::resetShellWorkspace,
                                sessions = sessionsViewModel,
                                onAuthoritativeProfile = { after ->
                                    if (after is com.hermes.bot.data.local.SelectedContextReadResult.Selected ||
                                        after == com.hermes.bot.data.local.SelectedContextReadResult.Absent) {
                                        sessionsViewModel?.loadSessions(forceRefresh = true)
                                    } else sessionsViewModel?.setNetworkError("Workspace selection unavailable")
                                })
                            if (result is ContextProfileSelectionResult.Error) Toast.makeText(
                                context, ContextProfileSwitcherPolicy.activationErrorMessageFor(result.reason), Toast.LENGTH_SHORT
                            ).show()
                        } finally { isWorkspaceSwitchInFlight = false }
                    }
                    true
                }
            )
        }
    }
}
