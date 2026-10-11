package com.hermes.bot.bootstrap

import android.os.Bundle
import com.hermes.bot.feature.work.WorkCronsViewModel
import com.hermes.bot.feature.work.ManagedFilesViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.HermesBotTheme
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.feature.connect.ConnectViewModel
import com.hermes.bot.feature.settings.ThemeViewModel
import com.hermes.bot.feature.settings.DebugLogsViewModel
import com.hermes.bot.data.diagnostics.CatalogEvent
import com.hermes.bot.data.diagnostics.DiagnosticsLifecycle
import com.hermes.bot.navigation.HermesNavShell

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? HermesBotApplication
            ?: throw IllegalStateException("Application must be HermesBotApplication")
        val container = app.appContainer
        DiagnosticsLifecycle(container.diagnostics).activityCreated()

        validateCompositionRoot(container)

        setContent {
            val themeViewModel: ThemeViewModel = viewModel {
                container.createThemeViewModel()
            }
            val themePrefString by themeViewModel.themePreference.collectAsState()
            val themePreference = try {
                AppThemePreference.valueOf(themePrefString)
            } catch (_: Exception) {
                AppThemePreference.SYSTEM
            }

            HermesBotTheme(themePreference = themePreference) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HermesBotAppRoot(
                        container = container,
                        themePreference = themePreference,
                        onThemeChanged = { themeViewModel.setThemePreference(it) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? HermesBotApplication)?.diagnostics?.let { DiagnosticsLifecycle(it).foregrounded() }
        // BUG-012: forward Activity lifecycle to the reconnect manager so a dead
        // connection recovers when the app returns to the foreground.
        (application as? HermesBotApplication)?.appContainer?.reconnectManager?.onAppForegrounded()
    }

    override fun onStop() {
        (application as? HermesBotApplication)?.diagnostics?.let { DiagnosticsLifecycle(it).backgrounded() }
        // BUG-012: pause retries and heartbeat while backgrounded.
        (application as? HermesBotApplication)?.appContainer?.reconnectManager?.onAppBackgrounded()
        super.onStop()
    }


    companion object {
        fun validateCompositionRoot(container: AppContainer) {
            checkNotNull(container.vaultStorage) { "vaultStorage must not be null" }
            checkNotNull(container.authRepository) { "authRepository must not be null" }
            checkNotNull(container.contextProfileRepository) { "contextProfileRepository must not be null" }
            checkNotNull(container.gatewayTransport) { "gatewayTransport must not be null" }
            checkNotNull(container.gatewayAdapter) { "gatewayAdapter must not be null" }
            checkNotNull(container.sessionRepository) { "sessionRepository must not be null" }
            checkNotNull(container.pendingTurnLedger) { "pendingTurnLedger must not be null" }
            checkNotNull(container.turnCoordinator) { "turnCoordinator must not be null" }
            checkNotNull(container.reconnectManager) { "reconnectManager must not be null" }
            checkNotNull(container.diagnostics) { "diagnostics must not be null" }
        }
    }
}

@Composable
fun HermesBotAppRoot(
    container: AppContainer? = null,
    themePreference: AppThemePreference = AppThemePreference.SYSTEM,
    onThemeChanged: (AppThemePreference) -> Unit = {},
    connectViewModel: ConnectViewModel? = null
) {
    val startupFactory = remember(container) { startupViewModelFactory(container) }
    val effectiveConnectVm: ConnectViewModel = connectViewModel ?: viewModel(factory = startupFactory)

    val connectUiState by effectiveConnectVm.uiState.collectAsState()
    val profilesUiState by effectiveConnectVm.profilesUiState.collectAsState()

    val sessionsViewModel: com.hermes.bot.feature.sessions.SessionsViewModel? =
        if (container != null) viewModel(factory = startupFactory) else null

    val chatVmProvider: ((String) -> ChatViewModel)? = remember(container) {
        container?.let { c -> { sessionId: String -> c.createChatViewModel(sessionId) } }
    }
    val botsVmProvider: (() -> com.hermes.bot.feature.bots.BotsViewModel)? = remember(container) {
        container?.let { c -> { c.createBotsViewModel() } }
    }
    val botScreenVmProvider: (() -> com.hermes.bot.feature.botscreen.BotScreenViewModel)? = remember(container) {
        container?.let { c -> { c.createBotScreenViewModel() } }
    }
    val brainModelsProvider: (() -> com.hermes.bot.feature.brain.BrainMainSettingsViewModel)? = remember(container) {
        container?.let { c -> { c.createBrainMainSettingsViewModel() } }
    }
    val brainAuxiliaryProvider: (() -> com.hermes.bot.feature.brain.BrainAuxiliaryModelsViewModel)? = remember(container) {
        container?.let { c -> { c.createBrainAuxiliaryModelsViewModel() } }
    }
    val brainSkillsProvider: (() -> com.hermes.bot.feature.brain.BrainSkillsViewModel)? = remember(container) {
        container?.let { c -> { c.createBrainSkillsViewModel() } }
    }
    val hostInsightsProvider: (() -> com.hermes.bot.feature.settings.HostInsightsViewModel)? = remember(container) {
        container?.let { c -> { c.createHostInsightsViewModel() } }
    }
    val hostUpdateProvider: (() -> com.hermes.bot.feature.settings.HostUpdateViewModel)? = remember(container) {
        container?.let { c -> { c.createHostUpdateViewModel() } }
    }
    val debugLogsViewModel = remember(container) { container?.createDebugLogsViewModel() }
    val contextProfileRepository = remember(container) { container?.contextProfileRepository }
    val workCronsViewModel = container?.cronRepository?.let { repository ->
        viewModel<WorkCronsViewModel> {
            WorkCronsViewModel(repository, container.contextProfileRepository)
        }
    }
    val kanbanViewModel = container?.kanbanRepository?.let { repository ->
        viewModel<com.hermes.bot.feature.work.KanbanViewModel> {
            com.hermes.bot.feature.work.KanbanViewModel(repository)
        }
    }
    val managedFilesViewModel = container?.managedFileRepository?.let { repository ->
        viewModel<ManagedFilesViewModel> { ManagedFilesViewModel(repository) }
    }
    // PR-5 (plan CACHE-PR5): Settings' first view model — owns deferred
    // execution of the two confirmation-gated local resets (composition
    // root; no Compose-scope launches). Adjacent to the debug-logs VM it
    // mirrors (FGU-004).
    val settingsViewModel = remember(container) { container?.createSettingsViewModel() }

    if (connectUiState.startupPresentation == com.hermes.bot.feature.connect.StartupPresentation.LOADING ||
        connectUiState.startupPresentation == com.hermes.bot.feature.connect.StartupPresentation.STORAGE_UNAVAILABLE) {
        com.hermes.bot.feature.connect.StartupScreen(
            storageUnavailable = connectUiState.startupPresentation == com.hermes.bot.feature.connect.StartupPresentation.STORAGE_UNAVAILABLE,
            onRetry = { effectiveConnectVm.reloadSavedConnection() }
        )
        return
    }

    HermesNavShell(
        connectUiState = connectUiState,
        onStartupPresented = { effectiveConnectVm.onStartupPresented() },
        themePreference = themePreference,
        onThemeChanged = onThemeChanged,
        onServerNameChanged = { effectiveConnectVm.updateServerName(it) },
        onHostChanged = { effectiveConnectVm.updateHost(it) },
        onPortChanged = { effectiveConnectVm.updatePort(it) },
        onTestConnection = { effectiveConnectVm.testConnection() },
        onAuthenticate = { effectiveConnectVm.authenticate() },
        onUsernameChanged = { effectiveConnectVm.updateUsername(it) },
        onPasswordChanged = { effectiveConnectVm.updatePassword(it) },
        onRememberLoginChanged = { effectiveConnectVm.updateRememberLogin(it) },
        onForgetSavedLogin = { effectiveConnectVm.forgetSavedLogin(it) },
        onLogin = { effectiveConnectVm.login() },
        onOpenSessions = { /* Nav shell transitions to Sessions */ },
        onRetry = { effectiveConnectVm.retry() },
        onToggleDiagnostics = { effectiveConnectVm.toggleDiagnostics() },
        sessionsViewModel = sessionsViewModel,
        chatViewModelProvider = chatVmProvider,
        botsViewModelProvider = botsVmProvider,
        botScreenViewModelProvider = botScreenVmProvider,
        botThreadPreviewRepository = container?.botThreadPreviewRepository,
        brainModelsViewModelProvider = brainModelsProvider,
        brainAuxiliaryViewModelProvider = brainAuxiliaryProvider,
        brainSkillsViewModelProvider = brainSkillsProvider,
        hostInsightsViewModelProvider = hostInsightsProvider,
        hostUpdateViewModelProvider = hostUpdateProvider,
        debugLogsViewModel = debugLogsViewModel,
        contextProfileRepository = contextProfileRepository,
        workCronsViewModel = workCronsViewModel,
        kanbanViewModel = kanbanViewModel,
        managedFilesViewModel = managedFilesViewModel,
        profilesUiState = profilesUiState,
        onSwitchProfile = { effectiveConnectVm.onSwitchProfile(it) },
        onEnterAddProfile = { effectiveConnectVm.enterAddProfileMode() },
        onRemoveProfile = { effectiveConnectVm.deleteProfile(it) },
        // PR-5 REQ-04/REQ-07: two explicit root callbacks. Full local data
        // first runs the local sign-out teardown (effectiveConnectVm), then
        // the vault wipe — single-root teardown-before-wipe, mirroring the
        // Cache clear needs no teardown (REQ-05): it never touches
        // credentials or the connection profile.
        onClearOfflineCache = { settingsViewModel?.clearOfflineCache() },
        onClearLocalData = {
            effectiveConnectVm.disconnect()
            settingsViewModel?.clearAllLocalData()
        },
        diagnosticRing = container?.diagnosticRing
    )
}

/**
 * PR-5 REQ-04 composition-root seam (union with PR-6): the reviewed
 * `onClearLocalData` call site keeps its zero-arg teardown idiom
 * (`effectiveConnectVm.disconnect()`), while PR-6's
 * `ConnectViewModel.disconnect(cacheChoice)` stays non-defaulted and
 * unmodified. "Clear all local app data" is about to wipe everything on this
 * device, so the explicitly forwarded choice is CLEAR — retention is never
 * implied (PR-6 requires KEEP to be explicitly designated).
 */
private fun ConnectViewModel.disconnect() = disconnect(SignOutCacheChoice.CLEAR)
