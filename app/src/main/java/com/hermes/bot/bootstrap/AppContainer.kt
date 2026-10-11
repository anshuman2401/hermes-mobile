package com.hermes.bot.bootstrap

import android.content.Context
import com.hermes.bot.data.cron.CronRepository
import com.hermes.bot.data.cron.DefaultCronRepository
import com.hermes.bot.data.cron.CronHttpAdapter
import com.hermes.bot.data.cron.cronHttpTransport
import com.hermes.bot.data.kanban.KanbanRepository
import com.hermes.bot.data.kanban.KanbanHttpAdapter
import com.hermes.bot.data.kanban.KanbanHttpTransport
import com.hermes.bot.data.files.ManagedFileRepository
import com.hermes.bot.data.files.DefaultManagedFileRepository
import com.hermes.bot.data.files.DefaultManagedFileProtocolAdapter
import com.hermes.bot.data.files.OkHttpManagedFileTransport
import com.hermes.bot.data.bots.BotRepository
import com.hermes.bot.data.bots.DefaultBotRepository
import com.hermes.bot.feature.bots.BotsViewModel
import com.hermes.bot.feature.botscreen.BotScreenViewModel
import com.hermes.bot.data.botscreen.BotScreenRepository
import com.hermes.bot.data.botscreen.DefaultScreenOverviewRepository
import com.hermes.bot.data.botscreen.ScreenOverviewRepository
import androidx.lifecycle.SavedStateHandle
import com.hermes.bot.data.history.DefaultStoredHistoryProtocolAdapter
import com.hermes.bot.data.history.OkHttpStoredHistoryTransport
import com.hermes.bot.data.brain.BrainMainSettingsRepository
import com.hermes.bot.data.brain.BrainAuxiliaryModelsRepository
import com.hermes.bot.data.brain.BrainSkillsRepository
import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.DefaultAuthRepository
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.context.ContextProfileRepository
import com.hermes.bot.data.context.DefaultContextProfileRepository
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.Diagnostics
import com.hermes.bot.data.diagnostics.PrivateDiagnosticsRepository
import com.hermes.bot.data.gateway.DefaultGatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayReconnectManager
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayTransport
import com.hermes.bot.data.gateway.okhttp.OkHttpGatewayTransport
import com.hermes.bot.data.ledger.PendingInterruptQueue
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.DefaultLocalDataResetCoordinator
import com.hermes.bot.data.local.LocalDataResetCoordinator
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.session.DefaultSessionRepository
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.data.turn.TurnCoordinator
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.feature.chat.ContentResolverPhotoAttachmentReader
import com.hermes.bot.feature.chat.ContentResolverDocumentAttachmentReader
import com.hermes.bot.feature.connect.ConnectViewModel
import com.hermes.bot.feature.brain.BrainMainSettingsViewModel
import com.hermes.bot.feature.brain.BrainAuxiliaryModelsViewModel
import com.hermes.bot.feature.brain.BrainSkillsViewModel
import com.hermes.bot.feature.brain.BrainSkillDetailIdentity
import com.hermes.bot.feature.brain.BrainSkillDetailViewModel
import com.hermes.bot.feature.sessions.SessionsViewModel
import com.hermes.bot.feature.settings.ThemeViewModel
import com.hermes.bot.feature.settings.DebugLogsViewModel
import com.hermes.bot.feature.settings.SettingsViewModel
import com.hermes.bot.domain.ConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asAndroidBitmap
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
    val botThreadPreviewRepository: com.hermes.bot.data.context.BotThreadPreviewRepository? get() = null
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
    fun createHostInsightsViewModel(): com.hermes.bot.feature.settings.HostInsightsViewModel =
        throw IllegalStateException("Host insights unavailable")

    fun createHostUpdateViewModel(): com.hermes.bot.feature.settings.HostUpdateViewModel =
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
        DefaultLocalDataResetCoordinator(vault = vaultStorage, clearMemory = { botMemory.clear() })
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
    private val chatPreviewRepository by lazy { com.hermes.bot.feature.chat.ChatPreviewRepository(vaultStorage) }

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

    private val botMemory = com.hermes.bot.data.bots.BotMemoryCache()
    private val botLifetime = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    override val botRepository: BotRepository by lazy {
        DefaultBotRepository(gatewayAdapter, vaultStorage, memory = botMemory).also { repository ->
            botLifetime.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                repository.connectionStatus.collect { status ->
                    if (status != com.hermes.bot.domain.ConnectionStatus.CONNECTED) repository.clearCache()
                }
            }
        }
    }
    private val botThumbnails by lazy {
        com.hermes.bot.feature.bots.BotThumbnailCache<androidx.compose.ui.graphics.ImageBitmap>(
            botMemory, botRepository::isCurrent, { it.asAndroidBitmap().allocationByteCount })
    }
    override fun createBotScreenViewModel(): BotScreenViewModel =
        BotScreenViewModel(BotScreenRepository(botRepository, gatewayAdapter))
    override val botThreadPreviewRepository: com.hermes.bot.data.context.BotThreadPreviewRepository by lazy {
        com.hermes.bot.data.context.DefaultBotThreadPreviewRepository(vaultStorage, authRepository,
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
        com.hermes.bot.data.auth.ConnectionRecoveryCoordinator(authRepository, gatewayAdapter)
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

    private val cameraPhotoStore by lazy { com.hermes.bot.data.attachments.AndroidCameraPhotoStore(context) }

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
            onGatewayDisconnected = { botMemory.disconnect(gatewayAdapter.connectionGeneration); reconnectManager.clearOrigin() },
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
            chatQueueFactory = { id -> com.hermes.bot.data.queue.VaultChatQueueStore(vaultStorage, id) },
            initialTitle = initialTitle,
            pendingInterruptQueue = pendingInterruptQueue,
            conversationCacheRepository = conversationCacheRepository,
            chatPreviewRepository = chatPreviewRepository,
            photoAttachmentReader = ContentResolverPhotoAttachmentReader { context.contentResolver },
            cameraPhotoStore = cameraPhotoStore,
            documentAttachmentReader = ContentResolverDocumentAttachmentReader { context.contentResolver },
            slashCommands = com.hermes.bot.data.commands.DefaultSlashCommandRepository(gatewayAdapter, sessionRepository),
            contextUsageRepository = com.hermes.bot.data.context.DefaultContextUsageRepository(gatewayAdapter, sessionRepository),
            subagentRepository = com.hermes.bot.data.subagent.DefaultSubagentRepository(gatewayAdapter, sessionRepository),
            voiceCoordinator = com.hermes.bot.data.voice.DefaultVoiceDictationCoordinator(
                com.hermes.bot.data.voice.DefaultVoiceRepository(sessionRepository, vaultStorage, authRepository,
                    com.hermes.bot.data.voice.DefaultVoiceProtocolAdapter(authRepository,
                        com.hermes.bot.data.voice.OkHttpVoiceTransport())),
                com.hermes.bot.data.voice.AndroidVoiceRecorder()),
            approvalRepository = com.hermes.bot.data.approval.DefaultApprovalRepository(gatewayAdapter, sessionRepository),
            fileRepository = com.hermes.bot.data.files.DefaultConversationFileRepository(sessionRepository, vaultStorage, authRepository,
                com.hermes.bot.data.files.DefaultFileProtocolAdapter(authRepository, com.hermes.bot.data.files.OkHttpFileTransport()))
        )

    override fun createThemeViewModel(savedStateHandle: SavedStateHandle): ThemeViewModel =
        ThemeViewModel(savedStateHandle = savedStateHandle)

    override fun createDebugLogsViewModel(): DebugLogsViewModel =
        DebugLogsViewModel(repository = privateDiagnostics, diagnosticRing = diagnosticRing)

    override fun createBotsViewModel(): BotsViewModel = BotsViewModel(botRepository,
        DefaultScreenOverviewRepository(botRepository, gatewayAdapter), botThumbnails)

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
        com.hermes.bot.data.insights.DefaultHostInsightsRepository(vaultStorage, authRepository, gatewayAdapter,
            profiles = contextProfileRepository)
    }
    override fun createHostInsightsViewModel() =
        com.hermes.bot.feature.settings.HostInsightsViewModel(hostInsightsRepository)

    private val hostUpdateRepository by lazy {
        com.hermes.bot.data.update.DefaultHostUpdateRepository(hostInsightsRepository,
            com.hermes.bot.data.update.HostUpdateAdapter(authRepository))
    }
    override fun createHostUpdateViewModel() =
        com.hermes.bot.feature.settings.HostUpdateViewModel(hostUpdateRepository)

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
