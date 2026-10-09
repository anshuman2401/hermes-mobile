package io.github.anshuman2401.hermesmobile.bootstrap

import android.content.Context
import io.github.anshuman2401.hermesmobile.data.cron.CronRepository
import io.github.anshuman2401.hermesmobile.data.cron.DefaultCronRepository
import io.github.anshuman2401.hermesmobile.data.cron.CronHttpAdapter
import io.github.anshuman2401.hermesmobile.data.cron.cronHttpTransport
import io.github.anshuman2401.hermesmobile.data.kanban.KanbanRepository
import io.github.anshuman2401.hermesmobile.data.kanban.KanbanHttpAdapter
import io.github.anshuman2401.hermesmobile.data.kanban.KanbanHttpTransport
import io.github.anshuman2401.hermesmobile.data.files.ManagedFileRepository
import io.github.anshuman2401.hermesmobile.data.files.DefaultManagedFileRepository
import io.github.anshuman2401.hermesmobile.data.files.DefaultManagedFileProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.files.OkHttpManagedFileTransport
import io.github.anshuman2401.hermesmobile.data.bots.BotRepository
import io.github.anshuman2401.hermesmobile.data.bots.DefaultBotRepository
import io.github.anshuman2401.hermesmobile.feature.bots.BotsViewModel
import io.github.anshuman2401.hermesmobile.feature.botscreen.BotScreenViewModel
import io.github.anshuman2401.hermesmobile.data.botscreen.BotScreenRepository
import io.github.anshuman2401.hermesmobile.data.botscreen.DefaultScreenOverviewRepository
import io.github.anshuman2401.hermesmobile.data.botscreen.ScreenOverviewRepository
import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.history.DefaultStoredHistoryProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.history.OkHttpStoredHistoryTransport
import io.github.anshuman2401.hermesmobile.data.brain.BrainMainSettingsRepository
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryModelsRepository
import io.github.anshuman2401.hermesmobile.data.brain.BrainSkillsRepository
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing
import io.github.anshuman2401.hermesmobile.data.diagnostics.Diagnostics
import io.github.anshuman2401.hermesmobile.data.diagnostics.PrivateDiagnosticsRepository
import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayReconnectManager
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.okhttp.OkHttpGatewayTransport
import io.github.anshuman2401.hermesmobile.data.ledger.PendingInterruptQueue
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.DefaultLocalDataResetCoordinator
import io.github.anshuman2401.hermesmobile.data.local.LocalDataResetCoordinator
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.session.DefaultSessionRepository
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.data.turn.TurnCoordinator
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.feature.chat.ContentResolverPhotoAttachmentReader
import io.github.anshuman2401.hermesmobile.feature.chat.ContentResolverDocumentAttachmentReader
import io.github.anshuman2401.hermesmobile.feature.connect.ConnectViewModel
import io.github.anshuman2401.hermesmobile.feature.brain.BrainMainSettingsViewModel
import io.github.anshuman2401.hermesmobile.feature.brain.BrainAuxiliaryModelsViewModel
import io.github.anshuman2401.hermesmobile.feature.brain.BrainSkillsViewModel
import io.github.anshuman2401.hermesmobile.feature.brain.BrainSkillDetailIdentity
import io.github.anshuman2401.hermesmobile.feature.brain.BrainSkillDetailViewModel
import io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel
import io.github.anshuman2401.hermesmobile.feature.settings.ThemeViewModel
import io.github.anshuman2401.hermesmobile.feature.settings.DebugLogsViewModel
import io.github.anshuman2401.hermesmobile.feature.settings.SettingsViewModel
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application-scoped composition root defining the real production dependency graph.
 */
interface AppContainer {
    val diagnostics: Diagnostics
    val diagnosticRing: DiagnosticRing
    val vaultStorage: VaultStorage
    val authRepository: AuthRepository
    val contextProfileRepository: ContextProfileRepository
    val cronRepository: CronRepository? get() = null
    val kanbanRepository: KanbanRepository? get() = null
    val managedFileRepository: ManagedFileRepository? get() = null

    /**
     * PR-3: offline conversation snapshot cache over the same vault partition.
     */
    val conversationCacheRepository: ConversationCacheRepository

    val gatewayTransport: GatewayTransport
    val gatewayAdapter: GatewayProtocolAdapter
    val botRepository: BotRepository
    val botThreadPreviewRepository: io.github.anshuman2401.hermesmobile.data.context.BotThreadPreviewRepository? get() = null
    val brainMainSettingsRepository: BrainMainSettingsRepository
    val brainAuxiliaryModelsRepository: BrainAuxiliaryModelsRepository
    val brainSkillsRepository: BrainSkillsRepository
        get() = throw IllegalStateException("Skills repository unavailable")
    val sessionRepository: SessionRepository
    val pendingTurnLedger: PendingTurnLedger
    val pendingInterruptQueue: PendingInterruptQueue
    val turnCoordinator: TurnCoordinator
    val reconnectManager: GatewayReconnectManager

    fun createConnectViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): ConnectViewModel
    fun createSessionsViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): SessionsViewModel
    fun createChatViewModel(
        sessionId: String,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        initialTitle: String? = null
    ): ChatViewModel
    fun createThemeViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): ThemeViewModel
    fun createDebugLogsViewModel(): DebugLogsViewModel
    fun createBotsViewModel(): BotsViewModel
    fun createBotScreenViewModel(): BotScreenViewModel = throw IllegalStateException("Bot Screen unavailable")
    fun createBrainMainSettingsViewModel(): BrainMainSettingsViewModel
    fun createBrainAuxiliaryModelsViewModel(): BrainAuxiliaryModelsViewModel
    fun createBrainSkillsViewModel(): BrainSkillsViewModel =
        throw IllegalStateException("Skills ViewModel unavailable")
    fun createBrainSkillDetailViewModel(
        snapshotId: Long,
        name: String,
        selectedProfile: String
    ): BrainSkillDetailViewModel = throw IllegalStateException("Skill detail ViewModel unavailable")

    /** Page-local host information; the navigation store owns disposal. */
    fun createHostInsightsViewModel(): io.github.anshuman2401.hermesmobile.feature.settings.HostInsightsViewModel =
        throw IllegalStateException("Host insights unavailable")

    fun createHostUpdateViewModel(): io.github.anshuman2401.hermesmobile.feature.settings.HostUpdateViewModel =
        throw IllegalStateException("Host updates unavailable")

    /**
     * PR-5 (plan CACHE-PR5): Settings' first view model — the two
     * confirmation-gated local resets (FGU-004; composition root seam so the
     * view-model rule stays unmodified).
     */
    fun createSettingsViewModel(): SettingsViewModel
}

/**
 * Production implementation constructing real implementations of all components.
 */
class DefaultAppContainer(
    private val context: Context,
    private val privateDiagnostics: PrivateDiagnosticsRepository = PrivateDiagnosticsRepository.fromContext(context.filesDir),
    override val diagnosticRing: DiagnosticRing = DiagnosticRing()
) : AppContainer {
    override val diagnostics: Diagnostics = privateDiagnostics

    override val vaultStorage: VaultStorage by lazy {
        val storageDir = File(context.noBackupFilesDir, "vault")
        EncryptedVault(storageDir)
    }

    /**
     * PR-5: the local-reset coordinator, lazy beside vaultStorage (single
     * composition-root seam shared by Settings' two reset rows).
     */
    private val localDataResetCoordinator: LocalDataResetCoordinator by lazy {
        DefaultLocalDataResetCoordinator(vault = vaultStorage)
    }

    override val authRepository: AuthRepository by lazy {
        DefaultAuthRepository(
            vaultStorage = vaultStorage,
            httpTransport = OkHttpAuthTransport(diagnosticRing = diagnosticRing),
            diagnosticRing = diagnosticRing
        )
    }

    override val contextProfileRepository: ContextProfileRepository by lazy {
        DefaultContextProfileRepository(
            vaultStorage = vaultStorage,
            httpTransport = OkHttpAuthTransport(diagnosticRing = diagnosticRing),
            diagnosticRing = diagnosticRing
        )
    }

    override val cronRepository: CronRepository by lazy {
        DefaultCronRepository(
            CronHttpAdapter(vaultStorage,
                cronHttpTransport())
        )
    }

    override val kanbanRepository: KanbanRepository by lazy {
        KanbanHttpAdapter(vaultStorage, KanbanHttpTransport(), authRepository)
    }

    override val managedFileRepository: ManagedFileRepository by lazy {
        DefaultManagedFileRepository(vaultStorage, authRepository,
            DefaultManagedFileProtocolAdapter(authRepository, OkHttpManagedFileTransport()), context.cacheDir)
    }

    // PR-3: offline conversation snapshot cache over the same vault partition
    // (shares vaultStorage, so profile/origin purge/reset cover it by layout).
    override val conversationCacheRepository: ConversationCacheRepository by lazy {
        VaultConversationCacheRepository(vaultStorage = vaultStorage)
    }

    override val gatewayTransport: GatewayTransport by lazy {
        OkHttpGatewayTransport()
    }

    override val gatewayAdapter: GatewayProtocolAdapter by lazy {
        DefaultGatewayProtocolAdapter(
            transport = gatewayTransport,
            diagnosticRing = diagnosticRing
        )
    }

    override val botRepository: BotRepository by lazy { createBotsRepository(gatewayAdapter, vaultStorage) }
    override fun createBotScreenViewModel(): BotScreenViewModel =
        BotScreenViewModel(BotScreenRepository(botRepository, gatewayAdapter))
    override val botThreadPreviewRepository: io.github.anshuman2401.hermesmobile.data.context.BotThreadPreviewRepository by lazy {
        io.github.anshuman2401.hermesmobile.data.context.DefaultBotThreadPreviewRepository(vaultStorage, authRepository,
            DefaultStoredHistoryProtocolAdapter(authRepository, OkHttpStoredHistoryTransport(maxBodyBytes = 128 * 1024)))
    }

    override val brainMainSettingsRepository: BrainMainSettingsRepository by lazy {
        createBrainMainSettingsRepository(
            vaultStorage = vaultStorage,
            authRepository = authRepository,
            gatewayAdapter = gatewayAdapter
        )
    }

    override val brainAuxiliaryModelsRepository: BrainAuxiliaryModelsRepository by lazy {
        createBrainAuxiliaryModelsRepository(
            vaultStorage = vaultStorage,
            authRepository = authRepository,
            gatewayAdapter = gatewayAdapter
        )
    }

    override val brainSkillsRepository: BrainSkillsRepository by lazy {
        createBrainSkillsRepository(
            vaultStorage = vaultStorage,
            authRepository = authRepository,
            gatewayAdapter = gatewayAdapter
        )
    }

    override val sessionRepository: SessionRepository by lazy {
        DefaultSessionRepository(
            gatewayAdapter = gatewayAdapter,
            vaultStorage = vaultStorage,
            contextProfileRepository = contextProfileRepository,
            httpTransport = OkHttpAuthTransport(diagnosticRing = diagnosticRing),
            diagnosticRing = diagnosticRing,
            storedHistoryAdapter = storedHistoryAdapter
        )
    }

    private val storedHistoryAdapter by lazy {
        DefaultStoredHistoryProtocolAdapter(authRepository, OkHttpStoredHistoryTransport())
    }

    override val pendingTurnLedger: PendingTurnLedger by lazy {
        PendingTurnLedger(vaultStorage)
    }

    // T3 PR-2 (FD-001): durable queue of undelivered stop interrupts. The
    // stop's capture path (ChatViewModel.stopStreaming) writes it and the
    // composition-time flush observer below drains it on reconnect.
    override val pendingInterruptQueue: PendingInterruptQueue by lazy {
        PendingInterruptQueue(vaultStorage)
    }

    override val turnCoordinator: TurnCoordinator by lazy {
        TurnCoordinator(
            gatewayAdapter = gatewayAdapter,
            pendingTurnLedger = pendingTurnLedger,
            sessionRepository = sessionRepository
        )
    }

    private val connectionRecovery by lazy {
        io.github.anshuman2401.hermesmobile.data.auth.ConnectionRecoveryCoordinator(authRepository, gatewayAdapter)
    }

    override val reconnectManager: GatewayReconnectManager by lazy {
        GatewayReconnectManager(
            gatewayAdapter = gatewayAdapter,
            authRepository = authRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            recovery = connectionRecovery,
            diagnosticRing = diagnosticRing
        )
    }

    /**
     * T3 PR-2 scope for the pending-interrupt flush observer — app-scoped,
     * same shape as [reconnectManager]'s scope.
     */
    private val pendingInterruptFlushScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // T3 PR-2 (GUD-001 / BUG-012 rule 1): composition-time arming. Declared
    // after every lazy it touches so the delegates exist when init runs. The
    // observer must start with the container — a flush hook reachable only
    // through a first-use path is exactly the dormancy BUG-012 cost us once.
    init {
        PendingInterruptFlushObserver(
            queue = pendingInterruptQueue,
            adapter = gatewayAdapter,
            scope = pendingInterruptFlushScope,
            // FD-001 gate / RISK-004: live-state resolver — returns the LIVE
            // runtime id only when the active session handle IS this stored
            // id and its runtime id is non-blank; otherwise null and the
            // hold waits for TTL. Read at flush time, never at composition.
            liveRuntimeIdFor = { storedId ->
                sessionRepository.activeSession.value
                    .takeIf { it?.storedSessionId == storedId }
                    ?.runtimeSessionId
                    ?.takeIf { it.isNotBlank() }
            }
        )
    }

    override fun createConnectViewModel(savedStateHandle: SavedStateHandle): ConnectViewModel =
        ConnectViewModel(
            savedStateHandle = savedStateHandle,
            authRepository = authRepository,
            gatewayAdapter = gatewayAdapter,
            vaultStorage = vaultStorage,
            diagnostics = diagnostics,
            // BUG-012: arm automatic reconnect on connect success; disarm on explicit disconnect.
            // Lazy reconnectManager access here is safe: it is only touched after a connect.
            onGatewayConnected = { origin -> reconnectManager.configureOrigin(origin) },
            onGatewayDisconnected = { reconnectManager.clearOrigin() },
            recovery = connectionRecovery
        )

    override fun createSessionsViewModel(savedStateHandle: SavedStateHandle): SessionsViewModel =
        SessionsViewModel(
            sessionRepository = sessionRepository,
            savedStateHandle = savedStateHandle,
            deferInitialLoad = true
        )

    override fun createChatViewModel(
        sessionId: String,
        savedStateHandle: SavedStateHandle,
        initialTitle: String?
    ): ChatViewModel =
        ChatViewModel(
            sessionId = sessionId,
            sessionRepository = sessionRepository,
            gatewayProtocolAdapter = gatewayAdapter,
            pendingTurnLedger = pendingTurnLedger,
            savedStateHandle = savedStateHandle,
            turnCoordinator = turnCoordinator,
            vaultStorage = vaultStorage,
            initialTitle = initialTitle,
            pendingInterruptQueue = pendingInterruptQueue,
            conversationCacheRepository = conversationCacheRepository,
            photoAttachmentReader = ContentResolverPhotoAttachmentReader { context.contentResolver },
            documentAttachmentReader = ContentResolverDocumentAttachmentReader { context.contentResolver },
            slashCommands = io.github.anshuman2401.hermesmobile.data.commands.DefaultSlashCommandRepository(gatewayAdapter, sessionRepository),
            contextUsageRepository = io.github.anshuman2401.hermesmobile.data.context.DefaultContextUsageRepository(gatewayAdapter, sessionRepository),
            subagentRepository = io.github.anshuman2401.hermesmobile.data.subagent.DefaultSubagentRepository(gatewayAdapter, sessionRepository),
            voiceCoordinator = io.github.anshuman2401.hermesmobile.data.voice.DefaultVoiceDictationCoordinator(
                io.github.anshuman2401.hermesmobile.data.voice.DefaultVoiceRepository(sessionRepository, vaultStorage, authRepository,
                    io.github.anshuman2401.hermesmobile.data.voice.DefaultVoiceProtocolAdapter(authRepository,
                        io.github.anshuman2401.hermesmobile.data.voice.OkHttpVoiceTransport())),
                io.github.anshuman2401.hermesmobile.data.voice.AndroidVoiceRecorder()),
            approvalRepository = io.github.anshuman2401.hermesmobile.data.approval.DefaultApprovalRepository(gatewayAdapter, sessionRepository),
            fileRepository = io.github.anshuman2401.hermesmobile.data.files.DefaultConversationFileRepository(sessionRepository, vaultStorage, authRepository,
                io.github.anshuman2401.hermesmobile.data.files.DefaultFileProtocolAdapter(authRepository, io.github.anshuman2401.hermesmobile.data.files.OkHttpFileTransport()))
        )

    override fun createThemeViewModel(savedStateHandle: SavedStateHandle): ThemeViewModel =
        ThemeViewModel(savedStateHandle = savedStateHandle)

    override fun createDebugLogsViewModel(): DebugLogsViewModel =
        DebugLogsViewModel(repository = privateDiagnostics, diagnosticRing = diagnosticRing)

    override fun createBotsViewModel(): BotsViewModel = createBotsViewModel(botRepository,
        DefaultScreenOverviewRepository(botRepository, gatewayAdapter))

    override fun createBrainMainSettingsViewModel(): BrainMainSettingsViewModel =
        BrainMainSettingsViewModel(
            repository = brainMainSettingsRepository,
            contextProfileRepository = contextProfileRepository
        )

    override fun createBrainAuxiliaryModelsViewModel(): BrainAuxiliaryModelsViewModel =
        BrainAuxiliaryModelsViewModel(
            repository = brainAuxiliaryModelsRepository,
            contextProfileRepository = contextProfileRepository
        )

    override fun createBrainSkillsViewModel(): BrainSkillsViewModel =
        BrainSkillsViewModel(
            repository = brainSkillsRepository,
            contextProfileRepository = contextProfileRepository,
            detailViewModelFactory = ::createBrainSkillDetailViewModel
        )

    override fun createBrainSkillDetailViewModel(
        snapshotId: Long,
        name: String,
        selectedProfile: String
    ): BrainSkillDetailViewModel = BrainSkillDetailViewModel(
        identity = BrainSkillDetailIdentity(snapshotId, name, selectedProfile),
        repository = brainSkillsRepository
    )

    private val hostInsightsRepository by lazy {
        io.github.anshuman2401.hermesmobile.data.insights.DefaultHostInsightsRepository(vaultStorage, authRepository, gatewayAdapter,
            profiles = contextProfileRepository)
    }
    override fun createHostInsightsViewModel() =
        io.github.anshuman2401.hermesmobile.feature.settings.HostInsightsViewModel(hostInsightsRepository)

    private val hostUpdateRepository by lazy {
        io.github.anshuman2401.hermesmobile.data.update.DefaultHostUpdateRepository(hostInsightsRepository,
            io.github.anshuman2401.hermesmobile.data.update.HostUpdateAdapter(authRepository))
    }
    override fun createHostUpdateViewModel() =
        io.github.anshuman2401.hermesmobile.feature.settings.HostUpdateViewModel(hostUpdateRepository)

    override fun createSettingsViewModel(): SettingsViewModel =
        SettingsViewModel(resetCoordinator = localDataResetCoordinator)
}

/** Pure Bots graph seams, retaining the actual supplied adapter and repository. */
internal fun createBotsRepository(gatewayAdapter: GatewayProtocolAdapter, vault: VaultStorage? = null): DefaultBotRepository = DefaultBotRepository(gatewayAdapter, vault)
internal fun createBotsViewModel(repository: BotRepository,
    screenOverview: ScreenOverviewRepository? = null): BotsViewModel = BotsViewModel(repository, screenOverview)

/** Small pure construction seam used by the production lazy graph and JVM wiring proof. */
internal fun createBrainMainSettingsRepository(
    vaultStorage: VaultStorage,
    authRepository: AuthRepository,
    gatewayAdapter: GatewayProtocolAdapter
): BrainMainSettingsRepository = BrainMainSettingsRepository(
    vaultStorage = vaultStorage,
    authRepository = authRepository,
    gatewayAdapter = gatewayAdapter
)

/** Pure production construction seam; all three supplied instances are retained. */
internal fun createBrainAuxiliaryModelsRepository(
    vaultStorage: VaultStorage,
    authRepository: AuthRepository,
    gatewayAdapter: GatewayProtocolAdapter
): BrainAuxiliaryModelsRepository = BrainAuxiliaryModelsRepository(
    vaultStorage = vaultStorage,
    authRepository = authRepository,
    gatewayAdapter = gatewayAdapter
)

/** Pure production construction seam; Skills shares the existing auth graph. */
internal fun createBrainSkillsRepository(
    vaultStorage: VaultStorage,
    authRepository: AuthRepository,
    gatewayAdapter: GatewayProtocolAdapter
): BrainSkillsRepository = BrainSkillsRepository(
    vaultStorage = vaultStorage,
    authRepository = authRepository,
    gatewayAdapter = gatewayAdapter
)

/**
 * T3 PR-2 (FD-001 / FD-002 / TASK-004): composition-time flush wiring for the
 * durable pending-interrupt queue.
 *
 * Observes [GatewayProtocolAdapter.connectionStatus] and, on each OBSERVED
 * transition into [ConnectionStatus.CONNECTED] (never in-flight
 * CONNECTING/preflight states — AGENTS.md steady-state rule), asks the queue
 * for the holds due on the current [GatewayProtocolAdapter.connectionGeneration]
 * and delivers each through the SAME T1 seam (`interruptSession`) the
 * in-session stop uses — no second wire path (FD-005). [PendingInterruptQueue.ackSuccess]
 * drops a delivered hold; a typed failure or a throw re-holds it via
 * [PendingInterruptQueue.ackFailure]; holds already past the 60 s TTL were
 * purged by the queue and are never sent (FD-003). Per-generation
 * single-flight lives in [PendingInterruptQueue.dueForFlush] (FD-004), so a
 * FAILED↔CONNECTED flap or overlapping flushes can never multiply sends.
 *
 * FD-001 gate (cost (b) / RISK-004): a hold's `runtimeSessionId` is an
 * enqueue-time snapshot and restored snapshots are NEVER trusted after
 * process death. Each hold flushes only when [liveRuntimeIdFor] resolves a
 * LIVE runtime id for the hold's stored session id from current session
 * state, and delivery goes to that live id — never the snapshot.
 * With no live id the hold stays in the vault for the 60 s TTL to drop:
 * expiry is the accepted safe outcome (FD-001/FD-003).
 *
 * Constructed from [DefaultAppContainer]'s init block — BUG-012 rule 1:
 * composition wiring, not a first-use path.
 */
internal class PendingInterruptFlushObserver(
    private val queue: PendingInterruptQueue,
    private val adapter: GatewayProtocolAdapter,
    private val liveRuntimeIdFor: (storedId: String) -> String?,
    scope: CoroutineScope
) {
    init {
        scope.launch {
            // The first emission is the current steady state, not a
            // transition: latch it instead of flushing on subscription.
            var wasConnected = adapter.connectionStatus.value == ConnectionStatus.CONNECTED
            adapter.connectionStatus.collect { status ->
                val connected = status == ConnectionStatus.CONNECTED
                if (connected && !wasConnected) {
                    try {
                        flushDueHolds()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best-effort flush: never kill the collector. The
                        // holds stay durable; the next transition retries and
                        // the 60 s TTL bounds any straggler. Nothing about a
                        // session id is ever logged (SEC-001).
                    }
                }
                wasConnected = connected
            }
        }
    }

    private suspend fun flushDueHolds() {
        for (hold in queue.dueForFlush(adapter.connectionGeneration)) {
            // FD-001 gate (cost (b)) / RISK-004: the enqueue-time snapshot on
            // the hold is never trusted after process death. Deliver ONLY
            // against a LIVE runtime id resolved from current session state
            // for this stored id; without one the hold stays in the vault and
            // the 60 s TTL drops it (accepted safe outcome).
            val liveRuntimeId = liveRuntimeIdFor(hold.storedSessionId)
            if (liveRuntimeId.isNullOrBlank()) {
                continue
            }
            val delivered = try {
                adapter.interruptSession(liveRuntimeId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when (delivered) {
                is GatewayResult.Success -> queue.ackSuccess(hold.storedSessionId)
                else -> queue.ackFailure(hold.storedSessionId)
            }
        }
    }
}
