package com.hermes.bot.feature.chat
import com.hermes.bot.data.queue.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.history.*
import com.hermes.bot.data.gateway.ResumeActivityEvidence
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.session.withoutToolPayloads
import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.gateway.SessionModelOption
import com.hermes.bot.data.gateway.SESSION_REASONING_LEVELS
import com.hermes.bot.data.ledger.PendingInterruptQueue
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.Draft
import com.hermes.bot.data.local.ProfileModelDefault
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.data.session.publishTurnStreaming
import com.hermes.bot.data.turn.ReconciliationOutcome
import com.hermes.bot.data.turn.TurnCoordinator
import com.hermes.bot.data.turn.TurnSubmissionOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.hermes.bot.data.context.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hermes.bot.domain.ConnectionStatus
import java.util.Locale
import java.util.UUID

/**
 * CHG-005-S4: header labels for sessions with no title source anywhere.
 * These are short friendly labels — never a fabricated title and never the
 * raw durable session ID (TASK-008; acceptance (b)).
 */
internal const val NEW_CONVERSATION_HEADER_LABEL = "New Conversation"
internal const val UNTITLED_CONVERSATION_HEADER_LABEL = "Conversation"

/**
 * CHG-005-S4 (skeptic fix-up): the single screen-level fallback used when no
 * ViewModel state carries a title. Mirrors the ChatViewModel constructor
 * fallback so the raw durable session ID can never leak into the header from
 * either path (TASK-008 + skeptic fix-up for ChatScreen.kt:107).
 */
internal fun chatHeaderFallbackTitle(sessionId: String): String =
    if (sessionId == "new-session") NEW_CONVERSATION_HEADER_LABEL
    else UNTITLED_CONVERSATION_HEADER_LABEL

/** File refs are prompt text; queued images ride the server queue. */
internal fun buildAttachmentPrompt(
    draft: String,
    images: List<PendingImageAttachment>,
    files: List<PendingFileAttachment>
): String = buildList {
    if (draft.isNotBlank()) add(draft)
    else addAll(images.map { it.serverText })
    addAll(files.map { it.refText })
}.joinToString("\n")

/**
 * T3 PR-2 marker: T1's best-effort `interruptSession` threw before yielding a
 * [GatewayResult], so there is no typed failure to classify.
 */
private val INTERRUPT_DELIVERY_THREW: Any = Any()

/**
 * T3 PR-2 capture rule (FD-005 / TASK-005): which interrupt failures become
 * durable holds. Captured = host-unreachable/connection class, which a
 * reconnect can genuinely deliver later: [GatewayFailure.NotConnected]
 * (sendRpc pre-flight), non-terminal [GatewayFailure.Transport], and
 * non-terminal [GatewayFailure.Rpc]. NOT captured — reconnect cannot make
 * these succeed, so a hold would only re-send a doomed interrupt: auth/policy
 * rejections (4401/4403, 401/403, [GatewayFailure.AuthenticationRequired]),
 * capability-missing (-32601 method-not-found) and invalid-request (-32600)
 * rejections, and protocol-incompatible failures (all terminal per
 * [GatewayFailure.isTerminal]).
 */
private fun GatewayFailure.qualifiesForStopHold(): Boolean = when (this) {
    is GatewayFailure.NotConnected -> true
    is GatewayFailure.Transport -> !isTerminal
    is GatewayFailure.Rpc -> !isTerminal
    is GatewayFailure.AuthenticationRequired,
    is GatewayFailure.IncompatibleProtocol,
    is GatewayFailure.SubmissionUnknown -> false
}

/**
 * ViewModel managing the chat timeline, streamed turns, tool activity cards,
 * single-flight prompt submission, and history reconciliation per ARCH-001 / BLD-007.
 */
class ChatViewModel(
    val sessionId: String,
    private val sessionRepository: SessionRepository? = null,
    private val gatewayProtocolAdapter: GatewayProtocolAdapter? = null,
    private val pendingTurnLedger: PendingTurnLedger? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    turnCoordinator: TurnCoordinator? = null,
    private val vaultStorage: VaultStorage? = null,
    /**
     * BLD-006 S5 (D-04): 32 ms coalescing publication cadence over admitted
     * presentation updates. Constructor-injectable so deterministic
     * virtual-time tests can shrink the window and pin the context (PAT-004).
     */
    deltaPublicationIntervalMs: Long = DeltaPublicationScheduler.DEFAULT_PUBLICATION_INTERVAL_MS,
    deltaPublicationContext: kotlin.coroutines.CoroutineContext =
        kotlinx.coroutines.Dispatchers.Main.immediate,
    /**
     * CHG-005-S4 (TASK-007): optional initial title threaded from the Sessions
     * list (`SessionSummary.title` of the tapped row) by the shell/factory.
     * Server summary is the FIRST title source; the raw session-ID fallback it
     * replaces is deliberately gone. Never fabricated client-side.
     */
    initialTitle: String? = null,
    /**
     * TRD1 slice 1 (TASK-003): wall-clock source for the turn-scoped
     * ExecutionTrace stamps (A2: client-measured wall clock). Defaulted so the
     * production factory and keyed owner need no change (RISK-005); JVM tests
     * inject a fake clock. Write-only here — no reducer logic reads the trace.
     */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * T3 PR-2 (FD-005): vault-backed queue for a stop whose best-effort
     * interrupt failed because the connection was down; the flush observer
     * delivers it on the next reconnect within the 60 s TTL. Optional like
     * the other collaborator seams: absent (tests, legacy call sites) simply
     * restores T1's swallow-only best-effort behavior.
     */
    private val pendingInterruptQueue: PendingInterruptQueue? = null,
    /**
     * PR-3 (plan CACHE-PR3): offline conversation snapshot cache. Optional like
     * the other collaborator seams — absent (existing tests, legacy factories)
     * keeps today's resume-only behavior with no cache-first gate.
     */
    private val conversationCacheRepository: ConversationCacheRepository? = null,
    private val photoAttachmentReader: PhotoAttachmentReader? = null,
    private val photoReadContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val documentAttachmentReader: DocumentAttachmentReader? = null,
    private val documentReadContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val slashCommands: com.hermes.bot.data.commands.SlashCommandRepository? = null,
    private val contextUsageRepository: ContextUsageRepository? = null,
    private val subagentRepository: com.hermes.bot.data.subagent.SubagentRepository? = null,
    private val voiceCoordinator: com.hermes.bot.data.voice.VoiceDictationCoordinator? = null,
    private val chatQueueStore: ChatQueueStore? = null,
    private val chatQueueFactory: ((() -> String?) -> ChatQueueStore)? = null,
    private val voiceClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val approvalRepository: com.hermes.bot.data.approval.ApprovalRepository? = null,
    private val fileRepository: com.hermes.bot.data.files.ConversationFileRepository? = null,
    private val chatPreviewRepository: ChatPreviewRepository? = null
) : ViewModel() {

    val files by lazy { fileRepository?.let {
        com.hermes.bot.feature.chat.files.ConversationFilesController(viewModelScope, it)
    } }
    /** UI lifecycle invokes this; state projection does not make network calls. */
    suspend fun updateConversationFiles(state: ChatUiState) {
        files?.update(state.durableSessionId, state.runtimeSessionId,
            state.isAttachmentConnectionAvailable && state.savedSnapshotCapturedAtMillis == null, state.timeline)
    }

    private var capturedPreviewOwner: com.hermes.bot.data.local.ConversationCacheReadOwner? = null
    private var memoryPreviewOwner: com.hermes.bot.data.local.ConversationCacheReadOwner? = null
    private var stopPreviewObservation: (() -> Unit)? = null
    private var historyGeneration = 0L
    private var storedHistoryScope: StoredHistoryScope? = null
    private var historyLoadJob: kotlinx.coroutines.Job? = null
    private var historyDisposed = false
    private var historyReadSocketGeneration: Long? = null
    private var pendingHistoryGap = false
    private var pendingHistoryGapRuntime: String? = null
    private var acceptedHistoryRows: List<StoredHistoryRow> = emptyList()
    private class StoredWindow(val scope: StoredHistoryScope, var budget: StoredHistoryBudget,
        var offset: Int, var possiblyEarlier: Boolean,
        val activateCycle: (StoredHistoryBudget) -> Unit,
        val publishOlder: suspend (StoredHistoryPage, StoredHistoryBudget) -> Boolean)
    private var storedWindow: StoredWindow? = null
    private var earlierHistoryJob: kotlinx.coroutines.Job? = null
    private var olderResourceBlocked = false
    private var remoteActiveGeneration: Long? = null
    private var activeHistoryGroups: List<ActiveFoldMessage> = emptyList()
    private var pendingActiveDeltaBytes = 0L
    private var activeHistoryEventAdmission: (suspend (GatewayEvent) -> Boolean)? = null

    private suspend fun captureHistoryScope(repo: SessionRepository, generation: Long): StoredHistoryScope? {
        val storage = vaultStorage ?: return null
        val local = storage.getActiveProfileId() ?: return null
        val profile = storage.getConnectionProfile(local) ?: return null
        // Absence is not a named default profile. Only a trusted explicit selection opts in.
        val remote = storage.getSelectedContextProfile()?.takeIf { it.isNotBlank() } ?: return null
        val origin = CanonicalOrigin.parse(profile.normalizedOrigin).getOrNull() ?: return null
        val scope = repo.captureStoredHistoryScope(local, origin, remote, sessionId, generation, generation).getOrThrow()
        if (historyDisposed || historyGeneration != generation) { scope?.invalidate(); return null }
        storedHistoryScope = scope
        return scope
    }

    /** Frozen S5 (D-04): the 32 ms coalescing window, test-injectable. */
    private val deltaPublicationIntervalMs: Long = deltaPublicationIntervalMs

    /** Frozen S5 (D-04/PAT-004): publication loop context, test-injectable. */
    private val deltaPublicationContext: kotlin.coroutines.CoroutineContext =
        deltaPublicationContext

    private val coordinator: TurnCoordinator? = turnCoordinator
        ?: if (gatewayProtocolAdapter != null && pendingTurnLedger != null) {
            TurnCoordinator(gatewayProtocolAdapter, pendingTurnLedger, sessionRepository)
        } else {
            null
        }

    private val _uiState = MutableStateFlow(
        ChatUiState(
            sessionId = sessionId,
            durableSessionId = if (sessionId != "new-session") sessionId else null,
            sessionTitle = initialHeaderTitle(initialTitle),
            isInitialLoading = sessionRepository != null && sessionId != "new-session"
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val attachmentController by lazy {
        ChatAttachmentController(
            uiState = _uiState,
            scope = viewModelScope,
            repository = sessionRepository,
            photoAttachmentReader = photoAttachmentReader,
            photoReadContext = photoReadContext,
            documentAttachmentReader = documentAttachmentReader,
            documentReadContext = documentReadContext,
            gatewayAvailable = {
                gatewayProtocolAdapter?.connectionStatus?.value?.let { it == ConnectionStatus.CONNECTED } ?: true
            }
        )
    }

    /**
     * CHG-005-S2 (RISK-003): count of history entries skipped as blank-body
     * during loadSession() hydration. Debug diagnostics only — no UI reads
     * it; blanks are omitted from the timeline, never rendered.
     */
    var skippedHistoryCount: Int = 0
        private set

    /**
     * CHAT-004 (frozen plan TASK-010): the raw SessionMessage at snapshot
     * index 0 of the last successful hydration — the dedupe base row for
     * load-earlier prepends. Null on session switch and on hydration
     * replacement (the base must re-derive from the next successful
     * loadSession()).
     */
    private var oldestRenderedHistoryRow: SessionMessage? = null

    /**
     * Monotonic per-VM prepend epoch for load-earlier prepend IDs
     * (CHAT-004 repair round 1): `hist-prev-<epoch>-<index>` so IDs minted
     * by successive non-empty prepends never collide as LazyColumn keys.
     * Hydration IDs (`hist-*`) are unaffected.
     */
    private var prependEpoch = 0

    /**
     * CHG-005-S4 (TASK-008): title precedence at construction — the threaded
     * server summary title wins; anything else gets a short friendly label.
     * The previous `"Session $sessionId"` raw-ID fallback is deliberately
     * gone: no title source means a friendly label, never the durable ID.
     */
    private fun initialHeaderTitle(serverSummaryTitle: String?): String? =
        serverSummaryTitle?.takeUnless { it.isBlank() }
            ?: if (sessionId == "new-session") NEW_CONVERSATION_HEADER_LABEL
            else UNTITLED_CONVERSATION_HEADER_LABEL

    private val chatQueue: ChatQueueCoordinator? = (chatQueueStore ?: chatQueueFactory?.invoke { currentDurableSessionId() })?.let(::ChatQueueCoordinator)
    private val queueAcceptMutex = Mutex()
    private var queueForeground = false
    private var queueTurnCompletion: CompletableDeferred<QueueExecutionResult>? = null
    private var queuedPromptTerminal: GatewayEvent? = null
    private var promptAwaitingAdmission = false
    private var queueCommandResult = QueueExecutionResult.COMPLETE
    private var queueRequiresIdle = false
    private var queueAwaitingTurnSettlement = false
    private var queuedExecutionId: String? = null
    private var queueRequestedYolo: Boolean? = null

    private fun publishStreaming(isStreaming: Boolean) {
        val id = _uiState.value.durableSessionId ?: sessionId
        sessionRepository?.publishTurnStreaming(storedSessionId = id, isStreaming = isStreaming)
    }

    /**
     * CHAT-004 (PAT-001, extracted verbatim from loadSession()'s mapping):
     * maps ONE raw history SessionMessage to its timeline entries, using the
     * same rules for both hydration (prefix "hist") and load-earlier prepends
     * (prefix "hist-prev"). Blank TEXT rows are skipped (counted via
     * [onBlankSkipped]); tool rows carry the F007-S1 evidence rules; unknown
     * roles collapse to ONE bounded generic row (SEC-001); assistant reasoning
     * rides right after its row (F-007 polish).
     */
    private fun mapHistoryMessageToEntries(
        msg: SessionMessage,
        index: Int,
        prefix: String,
        onBlankSkipped: () -> Unit = {}
    ): List<TimelineEntry> {
        // F-007 polish (deferred from F007-S4): a historical assistant row
        // carrying raw reasoning exposes it under the same collapsed reasoning
        // surface as live (S2) and final (S4) reasoning — one ReasoningEntry
        // appended right after the assistant row. Blank reasoning is absent
        // (no fabricated empty card).
        val historyReasoning: ReasoningEntry? =
            msg.reasoning?.takeUnless { it.isBlank() }?.let { reasoning ->
                ReasoningEntry(
                    id = reasoningEntryId("$prefix-asst-$index"),
                    kind = ReasoningDisplayKind.REASONING,
                    text = ReasoningPolicy.accumulate("", reasoning),
                    isExpanded = false
                )
            }
        val isToolRole = msg.role.equals("tool", ignoreCase = true)
        // Tool identity is the host's nonblank name/context evidence —
        // never the (absent) body. Also gating on the role token keeps
        // a body-bearing legacy tool row on the tool path instead of
        // degrading it to a generic unknown-role row.
        val hasToolEvidence = !msg.toolName.isNullOrBlank() ||
            !msg.toolContext.isNullOrBlank()
        val mapped: List<TimelineEntry> = if (msg.content.isBlank() && !isToolRole) {
            // Blank TEXT row (user/assistant/unknown): skipped as before.
            // The reasoning detail (if any) rides only a rendered
            // assistant row — never an orphan entry on a skipped row.
            onBlankSkipped()
            emptyList()
        } else if (isToolRole && hasToolEvidence) {
            listOfNotNull(
                ToolActivity(
                    id = "$prefix-tool-$index",
                    toolId = null,
                    name = BoundedPayloadMapper.boundedNameType(msg.toolName),
                    status = ToolStatus.COMPLETED,
                    context = BoundedPayloadMapper.boundedContext(msg.toolContext),
                    argsSummary = null,
                    resultSummary = null,
                    durationSeconds = null,
                    isExpanded = false,
                    // CHAT-009 (FZ-10/FZ-15): the row timestamp rides every
                    // mapped entry (0L sentinel when the host row carried no
                    // parseable timestamp) so day detection still works on
                    // tool-only days; caption rendering gates on entry type.
                    timestamp = msg.createdAtMillis ?: 0L,
                    files = com.hermes.bot.data.files.ConversationFileReferences.tool(msg.content, msg.toolName.orEmpty())
                )
            )
        } else if (msg.content.isBlank()) {
            // F007-S1: bare tool row (role:"tool", no name/context, no
            // body) — never silently vanishes; ONE bounded generic row.
            listOfNotNull(
                GenericGatewayActivity(
                    id = "$prefix-event-$index",
                    eventType = BoundedPayloadMapper.boundedNameType("history.tool"),
                    summary = BoundedPayloadMapper.boundedCollapsedSummary(
                        "Historical Hermes activity: tool"
                    ),
                    payloadPreview = null
                )
            )
        } else if (msg.role.equals("user", ignoreCase = true)) {
            listOfNotNull(
                UserMessage(
                    id = "$prefix-user-$index",
                    text = msg.content,
                    state = UserMessageState.SENT,
                    // CHAT-009 (FZ-10): 0L = absent sentinel (FZ-7) — no
                    // fabricated "now" for hydrated rows.
                    timestamp = msg.createdAtMillis ?: 0L,
                    editable = msg.editable
                )
            )
        } else if (msg.role.equals("assistant", ignoreCase = true)) {
            // F-007 polish: the historical reasoning entry rides
            // directly after its assistant row.
            listOfNotNull(
                AssistantMessage(
                    id = "$prefix-asst-$index",
                    text = msg.content,
                    state = AssistantMessageState.COMPLETE,
                    // CHAT-009 (FZ-10): 0L = absent sentinel (FZ-7).
                    timestamp = msg.createdAtMillis ?: 0L
                ),
                historyReasoning
            )
        } else {
            // CHG-005-S3: unknown or blank role with a non-blank body.
            // The collapsed summary carries the role classification
            // only — never the raw payload (SEC-001).
            val boundedRole = msg.role.trim()
            val roleClass =
                if (boundedRole.isBlank()) "unknown" else boundedRole
            listOfNotNull(
                GenericGatewayActivity(
                    id = "$prefix-event-$index",
                    eventType = BoundedPayloadMapper.boundedNameType("history.$roleClass"),
                    summary = BoundedPayloadMapper.boundedCollapsedSummary(
                        "Historical Hermes activity: $roleClass"
                    ),
                    payloadPreview = null
                )
            )
        }
        return mapped

    }

    private var activeTurnId: String? = null

    /**
     * BLD-006 S4 (D-02): one stable assistant entry ID per admitted local
     * turn, minted at submit time and independent of timeline position.
     */
    private var activeAssistantEntryId: String? = null

    /**
     * BLD-006 S4 (D-04): canonical retained source + presentation window for
     * the active admitted local turn.
     */
    private var activeTurnBuffer: AssistantTurnStreamBuffer? = null

    /**
     * T2: identity of the turn a user Stop sealed INTERRUPTED, held as a
     * one-shot window for reconciling that turn's authoritative terminal when
     * it arrives AFTER the optimistic local finalize (the stop-vs-completion
     * race). Set by [stopStreaming]; consumed by the
     * [GatewayEvent.MessageComplete] handler (which holds the full audit
     * decision) or superseded by a newly admitted turn. Never persisted —
     * presentation state only; a row replaced by history hydration fails the
     * upgrade's exact row lookup and the terminal falls back to the bounded
     * generic notice.
     */
    private var stoppedTurnMarker: StoppedTurnMarker? = null

    /** T2: entry + trace ids of the locally stopped turn awaiting its terminal. */
    private data class StoppedTurnMarker(
        val entryId: String,
        val traceTurnId: String?
    )

    /**
     * BLD-006 S5 (TASK-021/D-04): coalescing publication scheduler for the
     * active admitted local turn. Admitted delta text is appended to the S4
     * buffer immediately (retention stays lossless and synchronous) and the
     * 1 MiB presentation window is republished onto the stable assistant
     * entry at most once per 32 ms window, so Compose recomposition and
     * Markdown parsing ride coalesced publications instead of token-rate
     * deltas.
     */
    private var deltaPublicationScheduler: DeltaPublicationScheduler? = null

    /**
     * BLD-006 S5 (TASK-021): the per-turn scheduler captures the stable entry
     * ID; its publication callback re-reads [activeTurnBuffer], so the exact
     * buffer instance the publication observes is always the live one.
     */
    private fun mintDeltaPublicationScheduler(assistantEntryId: String) {
        deltaPublicationScheduler?.dispose()
        pendingActiveDeltaBytes = 0L
        val capturedBuffer = activeTurnBuffer
        val capturedScope = storedHistoryScope
        val capturedGeneration = historyGeneration
        val capturedSocket = remoteActiveGeneration
        deltaPublicationScheduler = DeltaPublicationScheduler(
            onPublish = { _ ->
                if (activeAssistantEntryId == assistantEntryId && activeTurnBuffer === capturedBuffer &&
                    (capturedSocket == null || (!historyDisposed && capturedGeneration == historyGeneration &&
                        capturedScope?.current == true && capturedSocket == gatewayProtocolAdapter?.connectionGeneration))) {
                    pendingActiveDeltaBytes = 0L
                    capturedBuffer?.let { publishAssistantPresentation(assistantEntryId, it) }
                }
            },
            publicationIntervalMs = deltaPublicationIntervalMs,
            schedulerContext = deltaPublicationContext
        )
    }

    private val draftOperationMutex = Mutex()
    private var draftGeneration = 0L
    private val channelInitialRead = CompletableDeferred<Boolean>()
    private var channelInitialReadStarted = false
    private var channelEntryClaimed = false
    private var channelAutoSend: Job? = null

    /** A new channel thread transfers its draft once and only the first initialization may send it. */
    fun acceptChannelFirstPrompt(entryId: String, text: String): Boolean {
        if (sessionRepository == null || entryId.isBlank() || text.isBlank() || channelEntryClaimed || sessionId == "new-session") return false
        channelEntryClaimed = true
        updateDraft(text)
        val acceptedGeneration = currentDraftGeneration()
        channelAutoSend = viewModelScope.launch {
            if (!channelInitialRead.await()) return@launch
            fun ready(): Boolean {
                val current = _uiState.value
                return gatewayProtocolAdapter?.connectionStatus?.value == ConnectionStatus.CONNECTED &&
                    current.durableSessionId == sessionId && !current.runtimeSessionId.isNullOrBlank() &&
                    current.copy(queueEnabled = false).isComposerReady && isCurrentDraftGeneration(acceptedGeneration)
            }
            // Queue restoration may finish after hydration. Wait only for that read;
            // an initially busy/failed handoff must never arm a later automatic send.
            if (!ready()) return@launch
            if (chatQueue != null) {
                _uiState.first { it.queue.loaded || it.queue.error != null }
                if (!queueForeground || !ready() || !_uiState.value.canEnqueue ||
                    _uiState.value.queue.pause == QueuePause.STORAGE) return@launch
            }
            channelAutoSend = null
            submitPrompt()
        }
        return true
    }

    /**
     * CHAT-011 S2 (frozen plan §State-ownership): serializes the single seed
     * path so the two triggers (resume success, session.info event) can never
     * double-RPC or double-save when both fire for one session load. One
     * writer (ChatViewModel), one trigger at a time.
     */
    private val profileModelDefaultSeedMutex = Mutex()
    @Volatile private var pickerSwitchStarted = false
    private var pickerPendingRuntimeId: String? = null
    private var pickerPendingTurnCompleted = false
    // An RPC owns the picker independently of hydration's transient busy display.
    private var pickerRequestOwner: Any? = null
    private var pickerRequestRuntimeId: String? = null

    private var pickerAccess: com.hermes.bot.data.session.SessionPickerAccess? = null
    private var pickerCatalogRevision = 0L
    private var pickerRevision = 0L
    private var pickerObservationRevision = 0L
    private var pickerObservedInfo: Map<String, Any?>? = null
    private var pickerConfirmationJob: Job? = null

    fun openSessionPicker() {
        if (chatQueue != null) pauseQueue()
        if (_uiState.value.edit != null) return
        val state = _uiState.value
        val runtime = state.runtimeSessionId ?: return
        if (!state.canOpenPicker || state.pickerCatalogLoading) return
        val repo = sessionRepository ?: return
        val revision = ++pickerCatalogRevision
        val changeRevision = pickerRevision
        _uiState.update { it.copy(pickerCatalogLoading = true) }
        viewModelScope.launch {
            try {
                val previous = pickerAccess
                if (previous != null && !previous.isCurrent() && revision == pickerCatalogRevision && _uiState.value.runtimeSessionId == runtime) {
                    val sameContext = previous.isSameContext()
                    if (revision != pickerCatalogRevision || _uiState.value.runtimeSessionId != runtime) return@launch
                    _uiState.update { it.copy(pickerOptions = emptyList(), pickerChange = if (sameContext || pickerRevision != changeRevision) it.pickerChange else null) }
                }
                val access = repo.capturePickerAccess(runtime) ?: run {
                    if (revision == pickerCatalogRevision) _uiState.update { it.copy(pickerOptions = emptyList(), pickerNotice = "Model catalog unavailable") }
                    return@launch
                }
                val result = access.options()
                if (revision != pickerCatalogRevision || _uiState.value.runtimeSessionId != runtime) return@launch
                if (!access.isCurrent()) {
                    if (revision == pickerCatalogRevision) _uiState.update { it.copy(pickerOptions = emptyList()) }
                    return@launch
                }
                pickerAccess = access
                _uiState.update { current -> current.copy(
                    pickerOptions = if (result is GatewayResult.Success) result.value else current.pickerOptions,
                    pickerNotice = when {
                        result is GatewayResult.Failure -> "Model catalog unavailable — showing previous options"
                        result is GatewayResult.Success && result.value.isEmpty() -> "No configured models available"
                        else -> null
                    }) }
            } catch (exception: CancellationException) { throw exception
            } catch (_: Exception) {
                if (revision == pickerCatalogRevision) _uiState.update { it.copy(pickerNotice = "Model catalog unavailable") }
            } finally {
                if (revision == pickerCatalogRevision) _uiState.update { it.copy(pickerCatalogLoading = false) }
            }
        }
    }

    fun pickSessionModel(option: SessionModelOption) {
        if (option !in _uiState.value.pickerOptions) return
        performSessionPick(model = option.model, provider = option.providerSlug)
    }

    fun pickSessionReasoning(effort: String) {
        if (effort !in SESSION_REASONING_LEVELS) return
        performSessionPick(effort = effort)
    }

    fun checkPickerStatus() {
        if (_uiState.value.activePickerChange?.phase == PickerChange.Phase.WRITING) return
        refreshSession(preserveQueuedAttachments = true)
    }

    private fun performSessionPick(model: String? = null, provider: String? = null, effort: String? = null,
        slashDraft: String? = null, draftGeneration: Long? = null) {
        val state = _uiState.value
        val runtime = state.runtimeSessionId ?: return
        val repo = sessionRepository ?: return
        if (!state.canChangePicker || pickerRequestOwner != null) return
        val owner = Any()
        val revision = ++pickerRevision
        pickerObservedInfo = null
        pickerRequestOwner = owner
        pickerRequestRuntimeId = runtime
        pickerSwitchStarted = true
        _uiState.update { it.copy(pickerBusy = true, pickerSwitchStarted = true, pickerNotice = null,
            pickerChange = PickerChange(runtime, model, provider, effort)) }
        viewModelScope.launch {
            fun current() = pickerRequestOwner === owner && pickerRevision == revision && _uiState.value.runtimeSessionId == runtime
            var access: com.hermes.bot.data.session.SessionPickerAccess? = null
            try {
                access = repo.capturePickerAccess(runtime)
                if (!current()) return@launch
                if (access == null || !access.isCurrent()) {
                    if (queuedExecutionId != null) queueCommandResult = QueueExecutionResult.FAILED
                    _uiState.update { it.copy(pickerChange = null, pickerNotice = "Restore the chat before changing settings.") }
                    return@launch
                }
                pickerAccess = access
                val option = if (model != null) {
                    if (provider != null) SessionModelOption(provider, model) else {
                        val options = access.options()
                        if (!current() || !access.isCurrent()) return@launch
                        val matches = (options as? GatewayResult.Success)?.value.orEmpty().filter {
                            it.model.equals(model, true) || (it.providerSlug + "/" + it.model).equals(model, true)
                        }
                        if (matches.size != 1) {
                            if (queuedExecutionId != null) queueCommandResult = QueueExecutionResult.FAILED
                            _uiState.update { it.copy(pickerChange = null, pickerNotice = "Choose a model from the model picker.") }
                            return@launch
                        }
                        matches.single()
                    }
                } else null
                if (!current() || !access.isCurrent()) return@launch
                if (option != null) _uiState.update { it.copy(pickerChange = it.pickerChange?.copy(model = option.model, provider = option.providerSlug)) }
                val result = if (option != null) access.setModel(option) else access.setReasoning(checkNotNull(effort))
                if (!current()) return@launch
                if (!access.isCurrent()) {
                    _uiState.update { it.copy(pickerChange = null, pickerOptions = emptyList(), pickerNotice = null) }
                    return@launch
                }
                when (result) {
                    is GatewayResult.Failure -> {
                        val rejected = result.failure is GatewayFailure.Rpc || result.failure is GatewayFailure.AuthenticationRequired
                        if (queuedExecutionId != null) queueCommandResult = if (rejected) QueueExecutionResult.FAILED else QueueExecutionResult.UNKNOWN
                        _uiState.update { it.copy(pickerChange = if (rejected) null else it.pickerChange?.copy(phase = PickerChange.Phase.UNCONFIRMED),
                            pickerNotice = if (rejected) "Couldn’t change the setting." else null) }
                    }
                    is GatewayResult.Success -> {
                        if (queuedExecutionId != null && result.value.confirmRequired) queueCommandResult = QueueExecutionResult.CONFIRMATION
                        if (slashDraft != null && draftGeneration != null && isCurrentDraftGeneration(draftGeneration) && _uiState.value.composerDraft == slashDraft) updateDraft("")
                        _uiState.update { it.copy(pickerChange = if (result.value.confirmRequired) null else it.pickerChange?.copy(
                            phase = if (result.value.deferred) PickerChange.Phase.DEFERRED else PickerChange.Phase.CONFIRMING),
                            pickerNotice = if (result.value.confirmRequired) "Hermes requires confirmation; this version did not switch" else null) }
                        if (!result.value.confirmRequired && !result.value.deferred) {
                            pickerObservedInfo?.let { reconcilePicker(it, revision) }
                            pickerConfirmationJob?.cancel()
                            pickerConfirmationJob = viewModelScope.launch {
                                kotlinx.coroutines.delay(10_000)
                                if (pickerRevision == revision && _uiState.value.runtimeSessionId == runtime)
                                    _uiState.update { it.copy(pickerChange = it.activePickerChange?.copy(phase = PickerChange.Phase.UNCONFIRMED)) }
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) { throw exception
            } catch (_: Exception) {
                if (current()) _uiState.update { it.copy(pickerChange = it.activePickerChange?.copy(phase = PickerChange.Phase.UNCONFIRMED)) }
            } finally {
                if (current()) {
                    pickerRequestOwner = null
                    pickerRequestRuntimeId = null
                    _uiState.update { it.copy(pickerBusy = false) }
                }
            }
        }
    }

    /**
     * Callers have already admitted this observation to the current owner.
     * Keep publication synchronous: suspending here would split an observer's
     * state transition and let a replacement session run before its tail.
     */
    private fun reconcilePicker(info: Map<String, Any?>, revision: Long = pickerRevision, fromRefresh: Boolean = false) {
        val change = _uiState.value.activePickerChange ?: return
        if (change.phase == PickerChange.Phase.WRITING || change.phase == PickerChange.Phase.DEFERRED || revision != pickerRevision) return
        val reported = if (change.model != null) SessionModelLabelPolicy.modelFromInfo(info) else SessionModelLabelPolicy.reasoningEffortFromInfo(info)
        val expected = change.model ?: change.effort
        if (reported == null || (!fromRefresh && reported != expected)) return
        val reportedProvider = info["provider"] as? String
        if (change.provider != null && !fromRefresh &&
            ((reportedProvider != null && reportedProvider != change.provider) ||
                (reportedProvider == null && _uiState.value.pickerOptions.count { it.model == change.model } > 1))) return
        pickerConfirmationJob?.cancel()
        val previousModel = lastReportedSessionModel
        val previousEffort = lastReportedSessionEffort
        val model = SessionModelLabelPolicy.modelFromInfo(info) ?: _uiState.value.sessionModelName
        val effort = if (info.containsKey("reasoning_effort") || info.containsKey("model"))
            SessionModelLabelPolicy.reasoningEffortFromInfo(info) else _uiState.value.sessionReasoningEffort
        _uiState.update { it.copy(pickerChange = null, pickerNotice = null,
            sessionModelName = model, sessionReasoningEffort = effort) }
        lastReportedSessionModel = model
        lastReportedSessionEffort = effort
        if (!fromRefresh) ModelChangeNoticePolicy.diff(previousModel, previousEffort, model, effort)?.let(::addModelChangeNotice)
    }

    private var contextReadJob: Job? = null
    private var liveUsage: LiveUsageSummary? = null
    private var usageRevision = 0L
    private var usageSequence: Long? = null
    private var modelEventRuntime: String? = null
    private var contextReadRequest = 0L
    private var compressionNoticeJob: Job? = null
    fun dismissSlashResults() {
        _uiState.update { it.copy(slashResults = emptyList(),
            compressionNotice = if (it.isCompressionBusy) it.compressionNotice else null) }
    }
    fun dismissCompressionNotice() {
        if (_uiState.value.isCompressionBusy) return
        compressionNoticeJob?.cancel()
        _uiState.update { it.copy(compressionNotice = null,
            slashResults = it.slashResults.filterNot { result -> result.command == "/compress" }) }
    }
    private fun showCompressionResult(message: String, successful: Boolean) {
        if (queuedExecutionId != null && !successful) queueCommandResult = QueueExecutionResult.FAILED
        compressionNoticeJob?.cancel()
        val result = com.hermes.bot.data.commands.SlashResult("/compress", message)
        _uiState.update { it.copy(compressionNotice = message,
            slashResults = (it.slashResults.filterNot { row -> row.command == "/compress" } + result).takeLast(8)) }
        if (successful) compressionNoticeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            _uiState.update { state ->
                if (state.isCompressionBusy || state.slashResults.none { it === result }) state
                else state.copy(compressionNotice = null,
                    slashResults = state.slashResults.filterNot { it === result })
            }
        }
    }
    private var compressionOwner: com.hermes.bot.data.commands.CommandSession? = null
    private var compressionRuntime: String? = null
    private var compressionFailed = false
    private var compressionCompleted = false
    private var compressionTerminal: ContextUsageSignalKind? = null

    private val subagentController by lazy { subagentRepository?.let { SubagentChatController(it, viewModelScope) } }
    private val approvalController by lazy { approvalRepository?.let { ApprovalChatController(it, viewModelScope) } }
    fun setApprovalVisibility(visible: Boolean) { approvalController?.visibility(visible) }
    fun decideApproval(requestId: String, choice: com.hermes.bot.data.approval.ApprovalChoice) { approvalController?.decide(requestId, choice) }
    fun checkApprovals() { approvalController?.check() }
    fun setSubagentVisibility(visible: Boolean) { subagentController?.visibility(visible) }

    fun toggleSubagentDock() { subagentController?.toggleDock() }
    fun openSubagent(child: com.hermes.bot.data.subagent.Subagent) { subagentController?.open(child) }
    fun closeSubagent() { subagentController?.close() }
    fun backSubagent() { subagentController?.back() }
    fun updateSubagentDraft(text: String) { subagentController?.draft(text) }
    fun viewSubagentTranscript() { subagentController?.transcript() }
    fun steerSubagent() { subagentController?.control(true) }
    fun stopSubagent() { subagentController?.control(false) }

    private val voiceController by lazy {
        voiceCoordinator?.let { coordinator ->
            VoiceDictationController(coordinator, viewModelScope, voiceClock,
                canStart = { _uiState.value.canStartDictation },
                publish = { voice -> _uiState.update { it.copy(voice = voice) } },
                append = { transcript -> updateDraft(appendDictation(_uiState.value.composerDraft, transcript)) })
        }
    }

    init {
        stopPreviewObservation = chatPreviewRepository?.observeInvalidation {
            viewModelScope.launch {
                val owner = memoryPreviewOwner ?: return@launch
                if (chatPreviewRepository.withOwner(owner) {} || historyDisposed) return@launch
                memoryPreviewOwner = null
                _uiState.update { state ->
                    if (state.savedSnapshotCapturedAtMillis != null && state.runtimeSessionId == null)
                        state.copy(timeline = emptyList(), cachedToolTrace = emptyList(),
                            savedSnapshotCapturedAtMillis = null, isCachedHistoryPreview = false)
                    else state
                }
            }
        }
        chatPreviewRepository?.publishIfPresent(sessionId) { snapshot -> publishMemoryPreview(snapshot) }
        _uiState.update { it.copy(dictationAvailable = voiceCoordinator != null, queueEnabled = chatQueue != null) }
        chatQueue?.let { queue ->
            viewModelScope.launch { queue.state.collect { state -> _uiState.update { it.copy(queue = state) } } }
            viewModelScope.launch {
                _uiState.first { !it.isInitialLoading && currentDurableSessionId() != null }
                queue.restore()
                if (queue.state.value.pause != null) queueRequiresIdle = true
            }
            viewModelScope.launch {
                _uiState.collect {
                    if (it.queue.loaded && it.headerConnectionStatus != ConnectionStatus.CONNECTED &&
                        (it.queue.waiting.isNotEmpty() || it.queue.active != null)) {
                        queueRequiresIdle = true
                        if (queue.state.value.pause in setOf(null, QueuePause.UNKNOWN, QueuePause.DISCONNECTED))
                            queue.pause(QueuePause.DISCONNECTED)
                        // A settled local stream loses terminal certainty on disconnect.
                        // Let its owning execution finish as unknown; never resend it.
                        if (!it.isSubmitting) queueTurnCompletion?.complete(QueueExecutionResult.UNKNOWN)
                    }
                }
            }
            viewModelScope.launch { _uiState.collect { queue.runNext(::canDispatchQueue, ::executeQueueItem) } }
        }
        approvalController?.let { controller ->
            viewModelScope.launch { controller.state.collect { state -> _uiState.update { it.copy(approvals = state) } } }
            viewModelScope.launch {
                _uiState.map { Triple(it.durableSessionId ?: sessionId, it.runtimeSessionId,
                    it.headerConnectionStatus == ConnectionStatus.CONNECTED && !it.isInitialLoading && it.savedSnapshotCapturedAtMillis == null) }
                    .distinctUntilChanged().collect { (stored, runtime, connected) -> controller.environment(stored, runtime, connected) }
            }
        }
        subagentController?.let { controller ->
            viewModelScope.launch { controller.state.collect { state -> _uiState.update { it.copy(subagents = state) } } }
            viewModelScope.launch {
                _uiState.map { Triple(it.durableSessionId ?: sessionId, it.runtimeSessionId,
                    it.headerConnectionStatus == ConnectionStatus.CONNECTED && !it.isInitialLoading && it.savedSnapshotCapturedAtMillis == null) }
                    .distinctUntilChanged().collect { (stored, runtime, connected) -> controller.environment(stored, runtime, connected) }
            }
        }
        observeContextUsage()
        restoreDraft()
        if (sessionRepository != null) {
            loadSession()
        }
        if (gatewayProtocolAdapter != null) {
            observeGatewayEvents()
        }
        // CHAT-011 S2 (TASK-006): surface the cached per-workspace default
        // into presentation state at init — the offline/no-echo display
        // fallback needs no RPC. A vault read failure leaves it null
        // (fail-closed; the seed retries when connected).
        if (vaultStorage != null) {
            restoreCachedProfileModelDefault()
        }
    }

    /**
     * CHG-005-S4 (TASK-007, reopen path): the shell threads the tapped
     * `SessionSummary.title` onto the keyed ViewModel AFTER creation (the
     * `(String) -> ChatViewModel` provider signature is frozen outside this
     * slice's allowed paths). A blank/absent summary title is ignored — a
     * blank never replaces an existing title and never fabricates a raw-ID
     * fallback.
     */
    fun setQueueVisibility(visible: Boolean, pauseOnHide: Boolean = true) {
        if (chatQueue == null) return
        queueForeground = visible
        if (!visible && pauseOnHide) {
            queueRequiresIdle = true
            viewModelScope.launch { chatQueue.pause(QueuePause.BACKGROUND) }
        } else if (visible) viewModelScope.launch { chatQueue.runNext(::canDispatchQueue, ::executeQueueItem) }
    }
    fun pauseQueue() { viewModelScope.launch { chatQueue?.pause(QueuePause.USER) } }
    fun resumeQueue() {
        viewModelScope.launch {
            if (!queueForeground || !checkQueueIdle()) {
                chatQueue?.notice("Cannot resume yet. Reconnect and check that Hermes has stopped.")
                return@launch
            }
            if (_uiState.value.queue.pause == QueuePause.STORAGE) chatQueue?.restore()
            chatQueue?.resume()
        }
    }
    private suspend fun checkQueueIdle(): Boolean {
        if (!queueRequiresIdle && _uiState.value.queue.active?.state !in
            setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN, QueueItemState.CONFIRMING)) return true
        val stored = currentDurableSessionId() ?: return false
        val runtime = _uiState.value.runtimeSessionId ?: return false
        val snapshot = try { sessionRepository?.fetchHistorySnapshot(stored)?.getOrNull() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } ?: return false
        if (historyDisposed || !queueForeground || currentDurableSessionId() != stored ||
            _uiState.value.runtimeSessionId != runtime || snapshot.activityEvidence != ResumeActivityEvidence.NON_RUNNING) return false
        queueRequiresIdle = false
        return true
    }
    private suspend fun reconcileQueueAfterHistory(data: SessionResumeResult, current: suspend () -> Boolean) {
        val queue = chatQueue ?: return
        val active = queue.state.value.active
        val pending = active?.pendingTurnId ?: activeTurnId ?: _uiState.value.pendingUnknownTurnId
        if (!current() || !queueForeground || data.durableSessionId != currentDurableSessionId() ||
            data.runtimeSessionId != _uiState.value.runtimeSessionId ||
            data.activityEvidence != ResumeActivityEvidence.NON_RUNNING) return
        if (pending != null) {
            if (coordinator?.reconcileCompletedQueueTurn(pending, data.durableSessionId, data.messages,
                    data.activityEvidence) != true || !current()) return
            if (active != null && !queue.resolveCompletedPrompt(active.id)) return
            try { coordinator?.markResolved(pending) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { queue.pause(QueuePause.STORAGE); return }
            if (!current() || !queueForeground || _uiState.value.runtimeSessionId != data.runtimeSessionId) return
            if (activeTurnId == pending) activeTurnId = null
            queueAwaitingTurnSettlement = false
            _uiState.update { it.copy(pendingUnknownTurnId = it.pendingUnknownTurnId.takeUnless { id -> id == pending },
                isStreaming = false, submissionStatus = SubmissionStatus.IDLE) }
        } else if (active != null) return
        if (!current() || !queueForeground) return
        queueRequiresIdle = false
        if (queue.state.value.pause == QueuePause.DISCONNECTED) queue.resume()
        viewModelScope.launch {
            _uiState.first { !it.isHistoryReadInFlight && !it.isRefreshing }
            queue.runNext(::canDispatchQueue, ::executeQueueItem)
        }
    }

    fun cancelQueuedItem(id: String) { viewModelScope.launch { chatQueue?.cancel(id) } }
    fun editQueuedItem(id: String) { viewModelScope.launch { chatQueue?.beginEdit(id) } }
    fun updateQueuedEdit(text: String) { viewModelScope.launch { chatQueue?.editText(text) } }
    fun finishQueuedEdit(save: Boolean) { viewModelScope.launch { chatQueue?.finishEdit(save) } }
    fun retryQueuedItem() { viewModelScope.launch { chatQueue?.retryFailed() } }
    fun useCommandSuggestion() {
        val state = _uiState.value
        if (state.composerDraft.isNotEmpty() || state.edit != null) return
        state.commandSuggestion?.let { updateDraft(it); _uiState.update { current -> current.copy(commandSuggestion = null) } }
    }
    fun checkQueueStatus() {
        val state = _uiState.value
        if (state.isSubmitting || (state.isStreaming && state.queue.active?.state != QueueItemState.UNKNOWN)) return
        queueRequiresIdle = true
        // An unknown queue outcome can retain an obsolete local streaming flag.
        // Read authoritative history rather than letting that flag reject Refresh.
        if (_uiState.value.queue.active?.state == QueueItemState.UNKNOWN && !_uiState.value.isSubmitting &&
            !_uiState.value.isRefreshing && !_uiState.value.isHistoryReadInFlight)
            startHistoryRead(preserveAttachments = true, recovery = true, manual = true)
        else refreshSession()
    }
    fun dismissQueuedOutcome() {
        viewModelScope.launch {
            if (!checkQueueIdle()) {
                chatQueue?.notice("Cannot dismiss yet. Reconnect and check that Hermes has stopped.")
                return@launch
            }
            val pending = _uiState.value.queue.active?.pendingTurnId
            if (pending != null && _uiState.value.pendingUnknownTurnId == pending) {
                try { coordinator?.markResolved(pending) } // explicit dismissal does not assert success
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { chatQueue?.notice("Could not save the review. The queue remains paused."); return@launch }
                _uiState.update { it.copy(pendingUnknownTurnId = null, submissionStatus = SubmissionStatus.IDLE) }
            }
            chatQueue?.dismissActive()
        }
    }
    private fun canDispatchQueue(): Boolean {
        val state = _uiState.value
        return queueForeground && !historyDisposed && !queueRequiresIdle && !queueAwaitingTurnSettlement &&
            !state.isInitialLoading && !state.isHistoryReadInFlight && !state.isRefreshing && !state.isSubmitting && !state.isStreaming &&
            !state.slashBusy && !state.isCompressionBusy && !state.pickerBusy && state.activePickerChange == null &&
            state.pendingUnknownTurnId == null && state.unknownEditId == null && state.edit == null && !state.voice.isBusy &&
            !state.approvals.blocksSend && state.savedSnapshotCapturedAtMillis == null &&
            state.submitBlockedReason == null && state.isAttachmentConnectionAvailable &&
            state.pendingImages.isEmpty() && state.pendingFiles.isEmpty() && !state.isAttachingImage && !state.isAttachingFile &&
            !state.runtimeSessionId.isNullOrBlank()
    }
    private suspend fun executeQueueItem(item: QueueItem): QueueExecutionResult {
        queuedExecutionId = item.id
        try {
            return when (val payload = item.payload) {
                is QueuePayload.Prompt -> executeQueuedPrompt(payload.text)
                is QueuePayload.Command -> {
                    queueCommandResult = QueueExecutionResult.COMPLETE
                    queueRequestedYolo = null
                    if (payload.completion == CommandCompletion.YOLO_STATE && payload.invocation.argument.isBlank() &&
                        _uiState.value.sessionYolo == null) {
                        chatQueue?.progress(item.id, QueueItemState.CONFIRMING)
                        refreshSession()
                        val fresh = withTimeoutOrNull(15_000) { _uiState.first { !it.isRefreshing } }
                        if (fresh?.sessionYolo == null || fresh.refreshErrorMessage != null) return QueueExecutionResult.CONFIRMATION
                    }
                    if (chatQueue?.canContinue(item.id) != true) return QueueExecutionResult.UNKNOWN
                    executeSlash(payload.invocation, fromQueue = true)
                    slashExecutionJob?.join()
                    if (queueCommandResult != QueueExecutionResult.COMPLETE) return queueCommandResult
                    chatQueue?.progress(item.id, QueueItemState.CONFIRMING)
                    val settled = withTimeoutOrNull(15_000) {
                        _uiState.first { !it.slashBusy && !it.isRefreshing && !it.pickerBusy &&
                            (it.activePickerChange == null || it.activePickerChange?.phase == PickerChange.Phase.UNCONFIRMED) &&
                            (!it.isCompressionBusy || it.compressionPhase == CompressionPhase.UNKNOWN) }
                    } ?: return QueueExecutionResult.CONFIRMATION
                    if (settled.activePickerChange != null || settled.yoloUnconfirmed || settled.isCompressionBusy ||
                        settled.refreshErrorMessage != null || (queueRequestedYolo != null && settled.sessionYolo != queueRequestedYolo)) return QueueExecutionResult.CONFIRMATION
                    queueCommandResult
                }
            }
        } finally { queuedExecutionId = null }
    }
    private suspend fun executeQueuedPrompt(text: String, display: String? = null): QueueExecutionResult {
        val id = queuedExecutionId ?: return QueueExecutionResult.FAILED
        if (chatQueue?.canContinue(id) != true) return QueueExecutionResult.UNKNOWN
        if (!canDispatchQueue()) return QueueExecutionResult.UNKNOWN
        val completion = CompletableDeferred<QueueExecutionResult>()
        queueTurnCompletion = completion
        try {
            submitPlainPrompt(text, display, clearDraft = false)
            if (!_uiState.value.isSubmitting && !_uiState.value.isStreaming) return QueueExecutionResult.FAILED
            return completion.await()
        } finally { queueTurnCompletion = null; queuedPromptTerminal = null }
    }
    private fun enqueueTextOrCommand(command: com.hermes.bot.data.commands.SlashInvocation?) {
        val state = _uiState.value
        if (!state.canEnqueue) return
        val text = state.composerDraft
        val generation = currentDraftGeneration()
        if (command != null) {
            val name = com.hermes.bot.data.commands.SlashCommandPolicy.aliases["/"+command.name]?.removePrefix("/") ?: command.name
            if ("/"+name in com.hermes.bot.data.commands.SlashCommandPolicy.unsupported ||
                (name == "yolo" && command.argument !in setOf("", "on", "off")) ||
                (name == "reasoning" && command.argument.isNotBlank() && command.argument !in SESSION_REASONING_LEVELS)) {
                slashNotice(command.text, "This command or argument is not supported.")
                return
            }
        }
        val payload = if (command == null) QueuePayload.Prompt(text.trim()) else QueuePayload.Command(command)
        viewModelScope.launch {
            queueAcceptMutex.withLock {
                if (!isCurrentDraftGeneration(generation) || _uiState.value.composerDraft != text) return@withLock
                if (chatQueue?.enqueue(payload) == true && isCurrentDraftGeneration(generation) && _uiState.value.composerDraft == text)
                    updateDraft("")
            }
        }
    }

    fun applyInitialTitle(title: String?) {
        val sanitized = title?.takeUnless { it.isBlank() } ?: return
        // The server summary is the FIRST title source, so a threaded
        // nonblank summary title also supersedes any stale title (or
        // friendly placeholder) previously held by the keyed ViewModel.
        // POLISH (S4 dual-pane re-tap guard): it must not supersede a LIVE
        // server title, though. Re-tapping the same session in expanded
        // dual-pane re-resolves the summary title from the Sessions list,
        // which can be stale after a host rename; a GatewayEvent.SessionTitle
        // the VM already received is newer and wins. Only blank or
        // placeholder titles are still filled in.
        _uiState.update { current ->
            val hasLiveServerTitle = current.sessionTitle?.let { live ->
                live.isNotBlank() && live != UNTITLED_CONVERSATION_HEADER_LABEL
            } == true
            if (hasLiveServerTitle) {
                current
            } else {
                current.copy(sessionTitle = sanitized)
            }
        }
    }

    fun requestDictationPermission(): Long? = voiceController?.requestPermission()
    fun dictationPermissionResult(token: Long, granted: Boolean, permanent: Boolean) {
        val state = _uiState.value
        if (state.runtimeSessionId.isNullOrBlank() || !state.copy(voice = VoiceUiState()).canStartDictation) { cancelDictation(); return }
        val storedId = currentDurableSessionId() ?: run { cancelDictation(); return }
        voiceController?.permissionResult(token, granted, permanent, storedId, state.runtimeSessionId)
    }
    fun stopDictation() { voiceController?.stop() }
    fun cancelDictation() { voiceController?.cancel() }
    fun dismissDictationMessage() { voiceController?.dismissMessage() }

    private var editAccess: com.hermes.bot.data.turn.MessageEditAccess? = null
    private var editJob: kotlinx.coroutines.Job? = null
    private var editGeneration = 0L

    fun beginMessageEdit(messageId: String) {
        if (_uiState.value.queue.edit != null || _uiState.value.queue.active != null) return
        pauseQueue()
        val state = _uiState.value
        val message = state.timeline.filterIsInstance<UserMessage>().firstOrNull { it.id == messageId } ?: return
        if (!state.canEdit(message)) return
        val runtime = state.runtimeSessionId ?: return
        val stored = state.durableSessionId ?: return
        val target = message.editable ?: return
        val generation = ++editGeneration
        dismissSlashSuggestions()
        _uiState.update { it.copy(edit = MessageEditState(messageId, target.rowId, message.text,
            it.reviewEditText ?: message.text, phase = com.hermes.bot.data.turn.EditPhase.CHECKING, targetReady = false), reviewEditText = null) }
        editJob = viewModelScope.launch {
            val access = try { coordinator?.captureEditAccess(stored, runtime) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (historyDisposed || generation != editGeneration || _uiState.value.runtimeSessionId != runtime) return@launch
            editAccess = access
            _uiState.update { it.copy(edit = it.edit?.copy(phase = null, targetReady = access != null,
                error = if (access == null) "Refresh this conversation before editing." else null)) }
        }
    }

    fun cancelMessageEdit() {
        if (_uiState.value.edit?.busy == true) return
        editGeneration++
        editJob?.cancel()
        editAccess = null
        _uiState.update { it.copy(edit = null) }
    }

    fun reviewUnknownEdit() {
        val state = _uiState.value
        val stored = state.durableSessionId ?: return
        val runtime = state.runtimeSessionId ?: return
        val id = state.unknownEditId ?: return
        viewModelScope.launch {
            val record = coordinator?.pendingEditForReview(stored, runtime) ?: return@launch
            if (record.pendingTurnId != id || _uiState.value.runtimeSessionId != runtime || historyDisposed) return@launch
            coordinator?.markResolved(id) // explicit local user decision; does not assert a host outcome
            _uiState.update { it.copy(unknownEditId = null, pendingUnknownTurnId = null,
                reviewEditText = record.promptContent, submissionStatus = SubmissionStatus.IDLE,
                errorMessage = "Choose Edit on the message you want to replace after reviewing the current history.") }
        }
    }

    private suspend fun restoreUnknownEdit(data: SessionResumeResult) {
        val generation = historyGeneration
        val record = coordinator?.pendingEditForReview(data.durableSessionId, data.runtimeSessionId) ?: return
        if (historyDisposed || historyGeneration != generation || _uiState.value.runtimeSessionId != data.runtimeSessionId) return
        _uiState.update { it.copy(unknownEditId = record.pendingTurnId, pendingUnknownTurnId = null,
            submissionStatus = SubmissionStatus.UNKNOWN, errorMessage = "Edit outcome unknown. Check history before trying again.") }
    }

    fun resendEditedMessage() {
        val state = _uiState.value
        if (!state.canResendEdit()) return
        val edit = state.edit ?: return
        val access = editAccess ?: return
        val coord = coordinator ?: return
        val runtime = state.runtimeSessionId ?: return
        val stored = state.durableSessionId ?: return
        val generation = ++editGeneration
        // Fence old history, replay, pagination and buffered stream publications before preparation.
        historyGeneration++
        historyLoadJob?.cancel()
        earlierHistoryJob?.cancel()
        storedHistoryScope?.invalidate()
        deltaPublicationScheduler?.dispose()
        deltaPublicationScheduler = null
        _uiState.update { it.copy(edit = edit.copy(phase = com.hermes.bot.data.turn.EditPhase.CHECKING, error = null)) }
        editJob = viewModelScope.launch {
            fun current() = !historyDisposed && generation == editGeneration && _uiState.value.runtimeSessionId == runtime
            val outcome = coord.editMessage(stored, runtime, edit.rowId, edit.text.trim(), access, ::current) { phase ->
                if (current()) _uiState.update { it.copy(edit = it.edit?.copy(phase = phase)) }
            }
            if (!current()) return@launch
            when (outcome) {
                is com.hermes.bot.data.turn.MessageEditOutcome.Admitted -> {
                    coord.markResolved(outcome.pendingId)
                    if (!current()) return@launch
                    val cut = state.timeline.indexOfFirst { it.id == edit.messageId }
                    _uiState.update { it.copy(edit = null, timeline = state.timeline.take(cut.coerceAtLeast(0)) +
                        UserMessage(outcome.pendingId, edit.text.trim()), isStreaming = false, trace = null,
                        taskProgress = TaskProgressState(), historyTailRevision = it.historyTailRevision + 1) }
                    clearEditTimelineOwnership()
                    loadSession()
                }
                is com.hermes.bot.data.turn.MessageEditOutcome.Unknown -> {
                    _uiState.update { it.copy(edit = null, unknownEditId = outcome.pendingId,
                        submissionStatus = SubmissionStatus.UNKNOWN, isStreaming = false,
                        errorMessage = "Edit outcome unknown. Check history before trying again.") }
                    clearEditTimelineOwnership()
                    loadSession()
                }
                is com.hermes.bot.data.turn.MessageEditOutcome.Refused -> {
                    _uiState.update { it.copy(edit = it.edit?.copy(phase = null, error = outcome.message)) }
                    // A stop may have landed even though no edit was sent. Read truth without replaying the edit.
                    clearEditTimelineOwnership()
                    loadSession()
                }
            }
        }
    }

    private fun clearEditTimelineOwnership() {
        activeHistoryGroups = emptyList()
        activeHistoryEventAdmission = null
        remoteActiveGeneration = null
        activeTurnId = null
        activeAssistantEntryId = null
        activeTurnBuffer = null
        stoppedTurnMarker = null
        storedWindow = null
        acceptedHistoryRows = emptyList()
        storedHistoryScope = null
        publishStreaming(false)
    }

    fun updateDraft(newDraft: String) {
        if (_uiState.value.edit != null) {
            if (_uiState.value.edit?.busy != true && newDraft.length <= com.hermes.bot.data.gateway.MessageEditPolicy.MAX_TEXT_CHARS)
                _uiState.update { it.copy(edit = it.edit?.copy(text = newDraft, error = null)) }
            return
        }
        channelAutoSend?.cancel()
        channelAutoSend = null
        _uiState.update { it.copy(composerDraft = newDraft) }
        requestSlashSuggestions(newDraft)
        val generation = nextDraftGeneration()
        val storedSessionId = currentDurableSessionId() ?: return
        val storage = vaultStorage ?: return
        queueDraftOperation(generation) {
            if (newDraft.isBlank()) {
                val existing = storage.getDraft(storedSessionId)
                if (existing != null && isRecordForActiveProfile(storage, existing.profileLocalId)) {
                    storage.deleteDraft(storedSessionId)
                }
            } else {
                storage.saveDraft(
                    Draft(
                        storedSessionId = storedSessionId,
                        composerText = newDraft,
                        profileLocalId = storage.getActiveProfileId()
                    )
                )
            }
        }
    }

    fun openContextUsage() {
        dismissSlashSuggestions()
        _uiState.update { it.copy(contextUsage = ContextUsagePresentation(visible = true, live = liveUsage, detailsStale = liveUsage != null)) }
    }
    fun closeContextUsage() {
        invalidateContextRead()
        _uiState.update { it.copy(contextUsage = ContextUsagePresentation()) }
    }
    fun toggleContextBreakdown() {
        _uiState.update { it.copy(contextUsage = it.contextUsage.copy(expanded = !it.contextUsage.expanded)) }
    }
    fun refreshContextUsage() {
        if (_uiState.value.isCompressionBusy && _uiState.value.compressionPhase != CompressionPhase.REQUESTING) {
            refreshSession(preserveQueuedAttachments = true)
        } else readContextUsage()
    }
    fun compressContext() {
        if (chatQueue != null) {
            if (_uiState.value.canQueueCommand) viewModelScope.launch { chatQueue.enqueue(QueuePayload.Command(com.hermes.bot.data.commands.SlashInvocation("compress", ""))) }
            return
        }
        if (_uiState.value.canCompressContext)
            executeSlash(com.hermes.bot.data.commands.SlashInvocation("compress", ""), fromToolbar = true)
    }
    private fun invalidateContextRead() {
        contextReadRequest++
        contextReadJob?.cancel()
        _uiState.update { it.copy(contextUsage = it.contextUsage.copy(snapshot = null, loading = false,
            live = liveUsage, detailsStale = liveUsage != null, error = null)) }
    }
    private fun clearLiveUsage() {
        liveUsage = null
        usageSequence = null
        usageRevision++
        _uiState.update { it.copy(contextUsage = it.contextUsage.copy(live = null, detailsStale = false)) }
    }
    private suspend fun applyUsageEvent(event: GatewayEvent.SessionUsageUpdated) {
        val runtime = _uiState.value.runtimeSessionId ?: return
        val generation = gatewayProtocolAdapter?.connectionGeneration
        if (event.seq != null && usageSequence?.let { event.seq <= it } == true) return
        val stored = currentDurableSessionId() ?: return
        val signal = contextUsageRepository?.admitEvent(stored, event) ?: run {
            clearLiveUsage()
            return
        }
        // Authority validation suspends: recheck the UI owner before publication.
        if (_uiState.value.runtimeSessionId != runtime || currentDurableSessionId() != stored ||
            gatewayProtocolAdapter?.connectionGeneration != generation ||
            gatewayProtocolAdapter?.connectionStatus?.value != ConnectionStatus.CONNECTED) return
        val update = signal.usage ?: return
        if (event.seq != null) usageSequence = event.seq
        if (update.context == null && update.total == null && update.model == null) return
        usageRevision++
        liveUsage = (liveUsage ?: LiveUsageSummary()).merge(update)
        _uiState.update { state ->
            val changedModel = update.model != null && update.model != state.sessionModelName
            state.copy(
                sessionModelName = update.model ?: state.sessionModelName,
                sessionReasoningEffort = if (changedModel) null else state.sessionReasoningEffort,
                sessionUsageEffortUnknown = changedModel || state.sessionUsageEffortUnknown,
                contextUsage = state.contextUsage.copy(live = liveUsage,
                    detailsStale = state.contextUsage.detailsStale || update.context != null || update.total != null)
            )
        }
        if (update.model != null) {
            pickerObservationRevision++
            reconcilePicker(mapOf("model" to update.model))
            modelEventRuntime = runtime
            lastReportedSessionModel = update.model
            lastReportedSessionEffort = _uiState.value.sessionReasoningEffort
        }
    }
    private fun contextReadKey(state: ChatUiState) = ContextReadKey(
        state.contextUsage.visible, state.durableSessionId ?: sessionId, state.runtimeSessionId,
        !state.isInitialLoading && !state.isRefreshing && !state.isSubmitting && !state.isStreaming &&
            !state.slashBusy && !state.isCompressionBusy && state.savedSnapshotCapturedAtMillis == null,
        state.headerConnectionStatus == ConnectionStatus.CONNECTED
    )
    private fun observeContextUsage() {
        viewModelScope.launch {
            var previous: ContextReadKey? = null
            _uiState.map(::contextReadKey).distinctUntilChanged().collect {
                if (previous?.runtime != it.runtime || !it.connected) clearLiveUsage()
                previous = it
                invalidateContextRead()
                if (it.visible) readContextUsage()
            }
        }
        val repo = contextUsageRepository ?: return
        viewModelScope.launch {
            repo.signals.collect { signal ->
                if (signal.kind == ContextUsageSignalKind.INVALIDATED) {
                    clearLiveUsage()
                    invalidateContextRead()
                    if (_uiState.value.contextUsage.visible) readContextUsage()
                } else handleCompressionSignal(signal)
            }
        }
    }
    private fun readContextUsage() {
        val state = _uiState.value
        val key = contextReadKey(state)
        if (!key.visible || state.contextUsage.loading) return
        if (!key.idle || !key.connected || key.runtime.isNullOrBlank()) {
            _uiState.update { it.copy(contextUsage = it.contextUsage.copy(error =
                if (!key.connected) "Reconnect to read context and account allowance."
                else "Context updates when the conversation is idle.")) }
            return
        }
        val repo = contextUsageRepository
        if (repo == null) {
            _uiState.update { it.copy(contextUsage = it.contextUsage.copy(error = "Context usage unavailable.")) }
            return
        }
        val revision = usageRevision
        val request = ++contextReadRequest
        contextReadJob?.cancel()
        _uiState.update { it.copy(contextUsage = it.contextUsage.copy(loading = true, snapshot = null, error = null)) }
        contextReadJob = viewModelScope.launch {
            try {
                val owner = repo.capture(checkNotNull(key.stored), key.runtime) ?: run {
                    clearLiveUsage()
                    error("Unavailable")
                }
                val result = repo.read(owner)
                if (request != contextReadRequest || key != contextReadKey(_uiState.value)) return@launch
                val owned = repo.isCurrent(owner)
                // Authority validation can suspend; dismissal/newer requests still own publication.
                if (request != contextReadRequest || key != contextReadKey(_uiState.value)) return@launch
                if (!owned) {
                    clearLiveUsage()
                    invalidateContextRead()
                    _uiState.update { it.copy(contextUsage = it.contextUsage.copy(error = "Session changed. Refresh context usage.")) }
                    return@launch
                }
                if (revision == usageRevision && result is GatewayResult.Success) {
                    liveUsage = LiveUsageSummary(result.value.context, result.value.usage.total, liveUsage?.model)
                }
                _uiState.update { it.copy(contextUsage = it.contextUsage.copy(loading = false,
                    snapshot = (result as? GatewayResult.Success)?.value,
                    live = liveUsage,
                    detailsStale = revision != usageRevision,
                    error = if (result is GatewayResult.Failure) "Unable to read context usage. Try again." else null)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == contextReadRequest && key == contextReadKey(_uiState.value))
                    _uiState.update { it.copy(contextUsage = it.contextUsage.copy(loading = false,
                        error = "Unable to read context usage. Try again.")) }
            }
        }
    }
    private suspend fun handleCompressionSignal(signal: ContextUsageSignal) {
        if (!_uiState.value.isCompressionBusy || signal.runtimeId != compressionRuntime ||
            signal.runtimeId != _uiState.value.runtimeSessionId) return
        val owner = compressionOwner ?: return
        if (slashCommands?.isCurrent(owner) != true || compressionOwner !== owner ||
            !_uiState.value.isCompressionBusy || signal.runtimeId != _uiState.value.runtimeSessionId) return
        if (signal.kind == ContextUsageSignalKind.FAILED) { compressionFailed = true; return }
        if (signal.kind !in setOf(ContextUsageSignalKind.COMPACTED, ContextUsageSignalKind.READY, ContextUsageSignalKind.STOPPED)) return
        if (_uiState.value.compressionPhase == CompressionPhase.REQUESTING) {
            compressionTerminal = signal.kind
            return
        }
        reconcileCompression(signal.kind)
    }
    private fun reconcileCompression(kind: ContextUsageSignalKind? = null) {
        if (kind == ContextUsageSignalKind.COMPACTED && !compressionFailed) compressionCompleted = true
        _uiState.update { it.copy(compressionPhase = CompressionPhase.RECONCILING, compressionNotice =
            if (compressionFailed) "Compression failed. Refreshing server history."
            else if (kind == ContextUsageSignalKind.COMPACTED) "Context compressed. Refreshing server history."
            else "Checking server history before another compression.") }
        refreshSession(preserveQueuedAttachments = true)
    }
    private fun reconcileCompressionFromResume(data: SessionResumeResult) {
        if (!_uiState.value.isCompressionBusy || _uiState.value.compressionPhase == CompressionPhase.REQUESTING) return
        if (data.activityEvidence != ResumeActivityEvidence.NON_RUNNING && data.info["running"] != false) return
        compressionOwner = null; compressionRuntime = null; compressionTerminal = null
        _uiState.update { it.copy(compressionPhase = CompressionPhase.IDLE,
            compressionNotice = if (compressionFailed) "Hermes could not compress this conversation."
                else if (compressionCompleted) "Conversation context compressed."
                else "Server history refreshed. You can compress again.") }
        showCompressionResult(_uiState.value.compressionNotice!!, successful = compressionCompleted && !compressionFailed)
        compressionFailed = false
    }

    private var slashSuggestionsJob: kotlinx.coroutines.Job? = null
    private var slashRequest = 0L
    private var slashExecution = 0L
    private var slashExecutionJob: kotlinx.coroutines.Job? = null

    // The acknowledgement is not the effective mode: host policy can still bypass approvals.
    // Keep the optimistic projection through the owned read; only admitted host data confirms it.
    private var yoloWritePending = false

    private fun ChatUiState.acceptYolo(value: Boolean?): ChatUiState =
        if (yoloWritePending) this else copy(sessionYolo = value,
            confirmedSessionYolo = value ?: confirmedSessionYolo, yoloUnconfirmed = value == null && yoloUnconfirmed)

    private fun finishYoloRefresh() {
        if (!_uiState.value.yoloPending || yoloWritePending) return
        _uiState.update { it.copy(yoloPending = false,
            sessionYolo = if (it.yoloUnconfirmed) null else it.sessionYolo) }
        if (_uiState.value.yoloUnconfirmed) slashNotice("/yolo", "YOLO status unconfirmed. Refresh chat to check.")
    }

    private fun unknownYolo() {
        yoloWritePending = false
        _uiState.update { it.copy(sessionYolo = null, yoloUnconfirmed = true) }
        slashNotice("/yolo", "YOLO status unconfirmed. Refresh chat to check.")
    }

    private fun cancelSlashExecution() {
        if (queuedExecutionId != null && slashExecutionJob?.isActive == true)
            queueCommandResult = QueueExecutionResult.UNKNOWN
        if (_uiState.value.yoloPending) {
            unknownYolo()
            _uiState.update { it.copy(yoloPending = false, isRefreshing = false) }
        }
        slashExecution++
        slashExecutionJob?.cancel()
        _uiState.update { it.copy(slashBusy = false,
            compressionPhase = if (it.isCompressionBusy) CompressionPhase.UNKNOWN else it.compressionPhase,
            compressionNotice = if (it.isCompressionBusy) "Compression outcome unknown. Refresh to check server history." else it.compressionNotice) }
    }

    fun dismissSlashSuggestions() {
        slashRequest++
        slashSuggestionsJob?.cancel()
        _uiState.update { it.copy(slashMenuVisible = false, slashLoading = false) }
    }
    fun chooseSlashSuggestion(suggestion: com.hermes.bot.data.commands.SlashSuggestion) {
        if (_uiState.value.voice.isBusy) return
        if (suggestion !in _uiState.value.slashSuggestions) return
        updateDraft(suggestion.text + " ")
        if (suggestion.group == "Options" || suggestion.text !in setOf("/reasoning", "/model", "/resume")) dismissSlashSuggestions()
    }
    fun consumeSlashNavigation() { _uiState.update { it.copy(slashNavigation = null) } }

    private fun requestSlashSuggestions(draft: String) {
        val repo = slashCommands ?: return
        slashSuggestionsJob?.cancel()
        val request = ++slashRequest
        val query = com.hermes.bot.data.commands.SlashCommandPolicy.query(draft)
        val state = _uiState.value
        if (query == null || state.runtimeSessionId.isNullOrBlank() || state.isInitialLoading ||
            state.savedSnapshotCapturedAtMillis != null || !state.isAttachmentConnectionAvailable) {
            _uiState.update { it.copy(slashMenuVisible = false, slashLoading = false, slashSuggestions = emptyList()) }
            return
        }
        _uiState.update { it.copy(slashMenuVisible = true, slashLoading = true,
            slashSuggestions = emptyList(), slashSuggestionError = null) }
        val runtime = state.runtimeSessionId
        val stored = currentDurableSessionId() ?: return
        slashSuggestionsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(80)
            try {
                val owner = repo.capture(stored, runtime) ?: error("Unavailable")
                val suggestions = repo.suggestions(owner, query)
                if (request == slashRequest && _uiState.value.composerDraft == draft &&
                    _uiState.value.runtimeSessionId == runtime && repo.isCurrent(owner)) {
                    _uiState.update { it.copy(slashSuggestions = suggestions, slashLoading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == slashRequest) _uiState.update { it.copy(slashLoading = false,
                    slashSuggestionError = "Suggestions unavailable. You can still type a command.") }
            }
        }
    }
    private fun slashNotice(command: String, text: String) {
        _uiState.update { it.copy(slashResults = (it.slashResults +
            com.hermes.bot.data.commands.SlashResult(command, text.take(16_384))).takeLast(8)) }
    }
    fun toggleYolo() {
        if (chatQueue != null) {
            if (_uiState.value.canQueueCommand) viewModelScope.launch { chatQueue.enqueue(QueuePayload.Command(com.hermes.bot.data.commands.SlashInvocation("yolo", ""))) }
            return
        }
        if (!_uiState.value.canToggleYolo) return
        executeSlash(com.hermes.bot.data.commands.SlashInvocation("yolo", ""), fromToolbar = true)
    }

    private fun executeSlash(invocation: com.hermes.bot.data.commands.SlashInvocation, fromToolbar: Boolean = false, fromQueue: Boolean = false) {
        if (_uiState.value.voice.isBusy) return
        val repo = slashCommands ?: run { if (fromQueue) queueCommandResult = QueueExecutionResult.FAILED; return }
        val state = _uiState.value
        if (!fromQueue && if (fromToolbar) {
            if (invocation.name == "compress") !state.canCompressContext else !state.canToggleYolo
        } else !state.isComposerReady) return
        if (invocation.name == "yolo" && state.yoloPending) return
        if (fromQueue && !canDispatchQueue()) { queueCommandResult = QueueExecutionResult.FAILED; return }
        val runtime = state.runtimeSessionId ?: return
        val stored = currentDurableSessionId() ?: return
        if (state.isStreaming && invocation.name !in setOf("status", "help", "commands", "stop", "new", "reset", "clear", "resume", "sessions", "switch")) {
            slashNotice("/"+invocation.name, "Stop the active reply before running this command.")
            return
        }
        if (invocation.name in setOf("model", "reasoning") && invocation.argument.isNotBlank()) {
            if (invocation.name == "reasoning" && invocation.argument !in SESSION_REASONING_LEVELS) {
                slashNotice("/reasoning", "Choose a supported reasoning level.")
                return
            }
            dismissSlashSuggestions()
            performSessionPick(model = invocation.argument.takeIf { invocation.name == "model" },
                effort = invocation.argument.takeIf { invocation.name == "reasoning" },
                slashDraft = if (fromQueue) null else state.composerDraft, draftGeneration = if (fromQueue) null else currentDraftGeneration())
            return
        }
        val command = if (invocation.name == "yolo" && invocation.argument.isBlank()) {
            val enabled = state.sessionYolo
            if (enabled == null) {
                if (fromQueue) queueCommandResult = QueueExecutionResult.CONFIRMATION
                slashNotice("/yolo", "Approval mode unknown. Refreshing chat; check the mode before trying again, or use /yolo on or /yolo off.")
                refreshSession()
                return
            }
            com.hermes.bot.data.commands.SlashInvocation("yolo", if (enabled) "off" else "on")
        } else invocation
        if (command.name == "yolo" && command.argument !in setOf("on", "off")) {
            slashNotice("/yolo", "Use /yolo, /yolo on or /yolo off.")
            return
        }
        val draft = state.composerDraft
        val generation = currentDraftGeneration()
        dismissSlashSuggestions()
        if (fromQueue && command.name == "yolo") queueRequestedYolo = command.argument == "on"
        yoloWritePending = command.name == "yolo"
        _uiState.update { it.copy(slashBusy = true,
            sessionYolo = if (yoloWritePending && !fromQueue) command.argument == "on" else it.sessionYolo,
            yoloPending = yoloWritePending || it.yoloPending,
            yoloUnconfirmed = yoloWritePending || it.yoloUnconfirmed) }
        if (command.name == "compress") {
            compressionNoticeJob?.cancel()
            compressionRuntime = runtime; compressionFailed = false; compressionCompleted = false; compressionTerminal = null
            _uiState.update { it.copy(compressionPhase = CompressionPhase.REQUESTING, compressionNotice = "Compressing context…") }
        }
        val execution = ++slashExecution
        slashExecutionJob = viewModelScope.launch {
            try {
                val owner = repo.capture(stored, runtime) ?: run {
                    if (fromQueue) queueCommandResult = QueueExecutionResult.FAILED
                    slashNotice("/"+invocation.name, "Restore the chat before running a command.")
                    if (command.name == "compress") _uiState.update { it.copy(compressionPhase = CompressionPhase.IDLE,
                        compressionNotice = "Restore the chat before compressing context.") }
                    return@launch
                }
                if (command.name == "compress") compressionOwner = owner
                val outcome = if (invocation.name == "stop") {
                    if (!repo.isCurrent(owner)) return@launch
                    stopStreaming()
                    com.hermes.bot.data.commands.CommandOutcome.Result(
                        com.hermes.bot.data.commands.SlashDirective.Output(if (state.isStreaming) "Stopped the active turn." else "No active turn on this device."))
                } else repo.execute(owner, command)
                if (historyDisposed || execution != slashExecution || _uiState.value.runtimeSessionId != runtime || !repo.isCurrent(owner)) {
                    if (fromQueue) queueCommandResult = QueueExecutionResult.UNKNOWN
                    if (!historyDisposed && command.name == "yolo" && execution == slashExecution &&
                        _uiState.value.runtimeSessionId == runtime) unknownYolo()
                    if (command.name == "compress" && execution == slashExecution)
                        _uiState.update { it.copy(compressionPhase = CompressionPhase.UNKNOWN,
                            compressionNotice = "Session changed. Restore server history before compressing again.") }
                    return@launch
                }
                fun clearOriginalDraft() {
                    if (!fromToolbar && !fromQueue && isCurrentDraftGeneration(generation) && _uiState.value.composerDraft == draft) updateDraft("")
                }
                when (outcome) {
                    is com.hermes.bot.data.commands.CommandOutcome.Failure -> {
                        if (fromQueue) queueCommandResult = if (outcome.outcomeUnknown) QueueExecutionResult.UNKNOWN else QueueExecutionResult.FAILED
                        slashNotice("/"+invocation.name, outcome.message)
                        if (command.name == "yolo") {
                            yoloWritePending = false
                            if (outcome.outcomeUnknown) { unknownYolo(); refreshSession() }
                            else _uiState.update { it.copy(sessionYolo = it.confirmedSessionYolo,
                                yoloPending = false, yoloUnconfirmed = it.confirmedSessionYolo == null) }
                        }
                        if (command.name == "compress") _uiState.update { it.copy(
                            compressionPhase = if (outcome.outcomeUnknown) CompressionPhase.UNKNOWN else CompressionPhase.IDLE,
                            compressionNotice = outcome.message) }
                    }
                    is com.hermes.bot.data.commands.CommandOutcome.Navigate -> {
                        clearOriginalDraft()
                        _uiState.update { it.copy(slashNavigation = outcome.destination, slashNavigationQuery = outcome.query) }
                    }
                    is com.hermes.bot.data.commands.CommandOutcome.Result -> when (val directive = outcome.directive) {
                        is com.hermes.bot.data.commands.SlashDirective.YoloChanged -> {
                            yoloWritePending = false
                            clearOriginalDraft()
                            slashNotice("/yolo", if (command.argument == "on")
                                "Session approval bypass enabled. Refreshing effective mode."
                            else "Session approval bypass disabled. Host policy may still bypass approvals; refreshing effective mode.")
                            refreshSession()
                        }
                        is com.hermes.bot.data.commands.SlashDirective.Output -> {
                            clearOriginalDraft(); slashNotice("/"+invocation.name, directive.text)
                            if (invocation.name in setOf("model", "reasoning", "title")) refreshSession()
                        }
                        is com.hermes.bot.data.commands.SlashDirective.Prefill -> {
                            if (fromQueue) {
                                _uiState.update { it.copy(commandSuggestion = directive.message) }
                                slashNotice("/"+invocation.name, "Suggested text ready. Use it when your draft is empty.")
                            }
                            else if (isCurrentDraftGeneration(generation)) updateDraft(directive.message)
                            directive.notice?.let { slashNotice("/"+invocation.name, it) }
                            if (invocation.name == "undo") refreshSession()
                        }
                        is com.hermes.bot.data.commands.SlashDirective.Send -> {
                            if (fromQueue) {
                                _uiState.update { it.copy(slashBusy = false) }
                                queueCommandResult = executeQueuedPrompt(directive.message, directive.display ?: invocation.text)
                            } else if (_uiState.value.isStreaming || _uiState.value.isSubmitting) {
                                slashNotice("/"+invocation.name, "Stop the active reply before sending this command.")
                            } else {
                                directive.notice?.let { slashNotice("/"+invocation.name, it) }
                                _uiState.update { it.copy(slashBusy = false) }
                                // Expanded instructions travel through the existing single-write ledger.
                                submitPlainPrompt(directive.message, directive.display ?: invocation.text,
                                    clearDraft = isCurrentDraftGeneration(generation))
                            }
                        }
                        is com.hermes.bot.data.commands.SlashDirective.Compressed -> {
                            clearOriginalDraft(); slashNotice("/"+invocation.name, directive.message)
                            _uiState.update { it.copy(compressionNotice = directive.message,
                                compressionPhase = if (directive.pending) CompressionPhase.PENDING else CompressionPhase.IDLE) }
                            if (directive.pending && compressionTerminal != null) reconcileCompression(compressionTerminal)
                            else if (!directive.pending && directive.changed) reconcileCompression(ContextUsageSignalKind.COMPACTED)
                            else if (!directive.pending) showCompressionResult(directive.message, successful = !directive.failed)
                        }
                        is com.hermes.bot.data.commands.SlashDirective.Alias -> Unit
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!historyDisposed && execution == slashExecution && _uiState.value.runtimeSessionId == runtime) {
                    if (fromQueue) queueCommandResult = QueueExecutionResult.UNKNOWN
                    slashNotice("/"+invocation.name, "Command outcome unknown. Check the chat before trying again.")
                    if (command.name == "yolo") { unknownYolo(); refreshSession() }
                    if (command.name == "compress") _uiState.update { it.copy(compressionPhase = CompressionPhase.UNKNOWN,
                        compressionNotice = "Compression outcome unknown. Refresh to check server history.") }
                }
            }
            finally { if (!historyDisposed && execution == slashExecution && _uiState.value.runtimeSessionId == runtime) {
                if (yoloWritePending) {
                    yoloWritePending = false
                    _uiState.update { it.copy(sessionYolo = it.confirmedSessionYolo,
                        yoloPending = false, yoloUnconfirmed = it.confirmedSessionYolo == null) }
                }
                _uiState.update { it.copy(slashBusy = false) }
                if (!_uiState.value.isRefreshing) finishYoloRefresh()
            } }
        }
    }

    fun onPhotoSelected(uri: String) = attachmentController.onPhotoSelected(uri)

    fun onDocumentSelected(uri: String) = attachmentController.onDocumentSelected(uri)

    fun dismissAttachmentMessage() = attachmentController.dismissMessage()

    fun removePendingFile(refText: String) = attachmentController.removePendingFile(refText)

    fun removePendingImage(serverPath: String) = attachmentController.removePendingImage(serverPath)

    private fun restoreDraft() {
        val storage = vaultStorage ?: return
        val storedSessionId = sessionId.takeUnless { it.isBlank() || it == "new-session" } ?: return
        val generationAtRead = currentDraftGeneration()
        viewModelScope.launch {
            try {
                val storedDraft = storage.getDraft(storedSessionId) ?: return@launch
                if (!isRecordForActiveProfile(storage, storedDraft.profileLocalId)) return@launch
                if (storedDraft.composerText.isBlank()) return@launch
                synchronized(this@ChatViewModel) {
                    if (draftGeneration != generationAtRead) return@launch
                    _uiState.update { current ->
                        if (current.composerDraft.isBlank()) {
                            current.copy(composerDraft = storedDraft.composerText)
                        } else {
                            current
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Local persistence is best effort for presentation state. A vault
                // failure must not cancel this ViewModel or replace its text.
            }
        }
    }

    private fun clearPersistedDraftAtSendPoint() {
        _uiState.update { it.copy(composerDraft = "") }
        val generation = nextDraftGeneration()
        val storedSessionId = currentDurableSessionId() ?: return
        val storage = vaultStorage ?: return
        queueDraftOperation(generation) {
            val existing = storage.getDraft(storedSessionId)
            if (existing != null && isRecordForActiveProfile(storage, existing.profileLocalId)) {
                storage.deleteDraft(storedSessionId)
            }
        }
    }

    private fun queueDraftOperation(generation: Long, operation: suspend () -> Unit) {
        viewModelScope.launch {
            draftOperationMutex.withLock {
                if (!isCurrentDraftGeneration(generation)) return@withLock
                try {
                    operation()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Draft persistence must not alter or cancel the ViewModel.
                }
            }
        }
    }

    /**
     * CHAT-011 S2 (frozen plan TASK-006): reads the cached per-workspace
     * default record for the selected workspace into presentation state at
     * init — the display fallback needs no RPC and works offline. Serialized
     * against the seed path by the seed mutex so a concurrently finishing
     * seed can never be overwritten by a stale (pre-save) read. A vault read
     * failure leaves the fields null (fail-closed = cache-miss, plan
     * §Fail paths); the seed retries when connected.
     */
    private fun restoreCachedProfileModelDefault() {
        val storage = vaultStorage ?: return
        viewModelScope.launch {
            profileModelDefaultSeedMutex.withLock {
                try {
                    val profile = storage.getSelectedContextProfile()
                    val record = storage.getProfileModelDefault(
                        ProfileModelDefault.workspaceKey(profile)
                    )
                    _uiState.update { current ->
                        current.copy(
                            cachedDefaultModelName = record?.modelName,
                            cachedDefaultReasoningEffort = record?.reasoningEffort
                        )
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Vault read failure = no-record (fail-closed); the
                    // caption falls back to live-session truth only.
                }
            }
        }
    }

    /**
     * CHAT-011 S2 (frozen plan TASK-007, §State-ownership): the SINGLE seed
     * path and the ONLY writer of the per-workspace default record. Two
     * triggers, both passing the just-observed info echo model: resume
     * success and a runtime-ID-guarded session.info event. Guards, in
     * order: a non-blank echo model; CONNECTED; cache-miss (an existing
     * record is a hit — no RPC, no write); then the key-restricted
     * `config.get` reasoning probe (S1; the provider is never requested,
     * shown, or sent). Atomicity: the record is saved only when BOTH the
     * echo model and the RPC effort resolve — a partial fetch never
     * half-seeds. All failures swallowed per §Fail paths: RPC failure → no
     * write, retried on the next session load (the bounded retry — never a
     * storm); vault write failure → swallowed, the caption keeps this
     * session's in-memory truth; no path throws to the UI.
     */
    private fun seedProfileModelDefaultIfMissing(echoModel: String?) {
        if (pickerSwitchStarted) return
        val adapter = gatewayProtocolAdapter ?: return
        val storage = vaultStorage ?: return
        val model = echoModel?.takeUnless { it.isBlank() } ?: return
        viewModelScope.launch {
            profileModelDefaultSeedMutex.withLock {
                try {
                    if (pickerSwitchStarted) return@withLock
                    if (adapter.connectionStatus.value != ConnectionStatus.CONNECTED) {
                        return@withLock
                    }
                    val profile = storage.getSelectedContextProfile()
                    val existing = storage.getProfileModelDefault(
                        ProfileModelDefault.workspaceKey(profile)
                    )
                    if (existing != null) {
                        return@withLock
                    }
                    when (val probe = adapter.getReasoningDefault(profile)) {
                        is GatewayResult.Success -> {
                            val effort = probe.value.effort
                                ?.takeUnless { it.isBlank() }
                                ?: return@withLock
                            val record = ProfileModelDefault(
                                workspaceName = ProfileModelDefault.workspaceKey(profile),
                                modelName = model,
                                reasoningEffort = effort,
                                updatedAtMillis = clock()
                            )
                            if (pickerSwitchStarted) return@withLock
                            storage.saveProfileModelDefault(record)
                            _uiState.update { current ->
                                current.copy(
                                    cachedDefaultModelName = record.modelName,
                                    cachedDefaultReasoningEffort = record.reasoningEffort
                                )
                            }
                        }
                        is GatewayResult.Failure -> Unit
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Swallowed per §Fail paths: caption unaffected, no
                    // crash, no retry storm (the next load is the retry).
                }
            }
        }
    }

    private fun currentDurableSessionId(): String? =
        (_uiState.value.durableSessionId ?: sessionId)
            .takeUnless { it.isBlank() || it == "new-session" }

    @Synchronized
    private fun nextDraftGeneration(): Long {
        draftGeneration += 1
        return draftGeneration
    }

    @Synchronized
    private fun currentDraftGeneration(): Long = draftGeneration

    private suspend fun isRecordForActiveProfile(storage: VaultStorage, recordProfileLocalId: String?): Boolean {
        val activeId = storage.getActiveProfileId()
        if (activeId == null) {
            return recordProfileLocalId == null
        }
        val effectiveId = recordProfileLocalId ?: storage.getMigratedProfileId()
        return effectiveId == activeId
    }

    @Synchronized
    private fun isCurrentDraftGeneration(generation: Long): Boolean = draftGeneration == generation

    /**
     * CHAT-017 (TASK-003): the last model/effort pair the host reported for
     * the attached session, plus a pending change notice for the next live
     * assistant-row mint. All three are null at session attach and reset
     * alongside the CHAT-010 label clear in [loadSession] — a fresh attach
     * re-learns the pair from the resume echo and new events, and the first
     * report after reset is never a change (REQ-004). VM-private
     * presentation state only; deliberately NOT ChatUiState fields (plan
     * CON-002). Never persisted (REQ-008).
     */
    private var lastReportedSessionModel: String? = null
    private var lastReportedSessionEffort: String? = null

    /**
     * PR-7 (REQ-01): [reconnectReplace] is set ONLY by the reconnect edge
     * consumer below (a hard edge to CONNECTED while PR-3's labelled offline
     * snapshot is on screen). It keeps the strip flag set across the load
     * entry so a failed replace can never leave an unlabelled cached
     * timeline: a fresh cache is re-served byte-identically (PR-3 failure
     * fallback, NO_RUNTIME never armed), a dead cache keeps the strip beside
     * an honestly armed NO_RUNTIME, and the success path clears it as before
     * and re-arms the composer. The default call (false) is byte-for-byte the
     * pre-PR-7 behavior (CHAT-T7 self-heal, Restore button, sessions open,
     * all existing tests).
     */
    fun loadSession(reconnectReplace: Boolean = false, preserveAttachments: Boolean = false) {
        startHistoryRead(reconnectReplace, preserveAttachments)
    }

    private fun startHistoryRead(reconnectReplace: Boolean = false, preserveAttachments: Boolean = false,
        recovery: Boolean = false, externalGap: Boolean = false, manual: Boolean = false) {
        if (_uiState.value.edit?.busy == true) return
        val repo = sessionRepository ?: return
        val pickerReadRevision = pickerRevision
        val pickerReadObservation = pickerObservationRevision
        pickerCatalogRevision++
        _uiState.update { it.copy(pickerCatalogLoading = false) }
        val firstChannelRead = !channelInitialReadStarted
        channelInitialReadStarted = true
        val admittedSocket = gatewayProtocolAdapter?.connectionGeneration
        if ((recovery || manual) && historyLoadJob?.isActive == true && historyReadSocketGeneration == admittedSocket) {
            if (externalGap) {
                pendingHistoryGap = true
                pendingHistoryGapRuntime = _uiState.value.runtimeSessionId
            }
            return
        }
        pendingHistoryGap = false
        historyReadSocketGeneration = admittedSocket
        if (sessionId == "new-session") {
            channelInitialRead.complete(false)
            _uiState.update { it.copy(isInitialLoading = false) }
            return
        }

        earlierHistoryJob?.cancel()
        earlierHistoryJob = null
        if (!recovery && !manual) {
            storedWindow = null
            olderResourceBlocked = false
            acceptedHistoryRows = emptyList()
        }
        val previousScope = storedHistoryScope
        val previousSocketGeneration = remoteActiveGeneration
        dismissSlashSuggestions()
        if (!manual) cancelSlashExecution()
        if (!manual && !recovery) _uiState.update { it.copy(slashResults = emptyList(), slashNavigation = null) }
        val generation = ++historyGeneration
        remoteActiveGeneration = null
        activeHistoryEventAdmission = null
        storedHistoryScope?.invalidate()
        storedHistoryScope = null
        historyLoadJob?.cancel()
        val previousState = _uiState.value
        val keepQueuedAttachments = preserveAttachments ||
            previousState.submitBlockedReason == SubmitBlockedReason.NO_RUNTIME ||
            previousState.savedSnapshotCapturedAtMillis != null
        attachmentController.invalidateForHistoryLoad(keepQueuedAttachments && previousState.pendingImages.isNotEmpty())
        // BLD-006 S5 (TASK-023): a session switch flushes admitted buffered
        // text before the timeline is replaced by hydration.
        deltaPublicationScheduler?.flush()
        // PR-3 (§6): loadSession entry clears the saved-snapshot strip flag —
        // it only claims an offline copy being served right now.
        // PR-4 (CON-01): the phone-captured trace projection clears at the
        // SAME entry — a stale group never survives into the new load; the
        // store itself is untouched (CON-01: only replace-on-resume carries).
        // PR-7 (REQ-01): ONLY the reconnect load (reconnectReplace = true)
        // keeps the strip flag set across entry — so a failed replace can
        // never present cached rows unlabelled: a fresh cache is re-served
        // byte-identically (PR-3 failure fallback, NO_RUNTIME never armed),
        // a dead cache keeps the strip beside an honestly armed NO_RUNTIME,
        // and the success path clears it as before. Every other caller keeps
        // the clear-on-entry contract (byte-for-byte pre-PR-7).
        // CHAT-017 (TASK-003): the change-notice baseline resets alongside
        // the CHAT-010 label clear on loadSession entry — a new attach
        // re-learns the reported pair from the resume echo and fresh
        // SessionInfo events; the first report after reset is never a change
        // (REQ-004).
        modelEventRuntime = null
        clearLiveUsage()
        lastReportedSessionModel = null
        lastReportedSessionEffort = null
        _uiState.update { current ->
            current.copy(
                isInitialLoading = if (manual) current.isInitialLoading else true,
                isHistoryReadInFlight = true,
                isRefreshing = manual,
                isLoadingEarlier = false,
                storedHistoryEarlierAvailable = if (storedHistoryScope != null || storedWindow != null) false else null,
                historyTailRevision = current.historyTailRevision + 1,
                refreshErrorMessage = if (manual) null else current.refreshErrorMessage,
                pendingImages = if (keepQueuedAttachments) current.pendingImages else emptyList(),
                pendingFiles = if (keepQueuedAttachments) current.pendingFiles else emptyList(),
                isAttachingImage = false,
                isAttachingFile = false,
                attachmentErrorMessage = null,
                savedSnapshotCapturedAtMillis = if (reconnectReplace || current.isCachedHistoryPreview || memoryPreviewOwner != null) current.savedSnapshotCapturedAtMillis else null,
                isCachedHistoryPreview = (reconnectReplace || current.isCachedHistoryPreview || memoryPreviewOwner != null) && current.savedSnapshotCapturedAtMillis != null &&
                    gatewayProtocolAdapter?.connectionStatus?.value == ConnectionStatus.CONNECTED,
                cachedToolTrace = emptyList(),
                taskProgress = if (manual || recovery) current.taskProgress.copy(live = false) else TaskProgressState(),
                hasAcceptedStoredHistory = if (recovery || manual) current.hasAcceptedStoredHistory else false,
                // CHAT-010: a label never survives from a previous load —
                // clear on entry; only a successful resume re-populates it
                // (a failed resume leaves it empty, plan §state-ownership).
                sessionUsageEffortUnknown = false,
                sessionModelName = if (current.activePickerChange != null) current.sessionModelName else null,
                sessionYolo = if (manual && current.yoloPending) current.sessionYolo else null,
                confirmedSessionYolo = if (manual) current.confirmedSessionYolo else null,
                sessionReasoningEffort = if (current.activePickerChange != null) current.sessionReasoningEffort else null,
                pickerOptions = current.pickerOptions, pickerBusy = pickerRequestRuntimeId == current.runtimeSessionId && pickerRequestOwner != null,
                pickerNotice = if (pickerPendingRuntimeId == current.runtimeSessionId)
                    current.pickerPendingNotice else null
            )
        }
        val read = viewModelScope.launch {
            // PR-3 (REQ-09): CACHE-FIRST gate — offline (connectionStatus !=
            // CONNECTED) with a readable snapshot ⇒ serve it with ZERO adapter,
            // prompt, or replay calls. runtimeSessionId stays null and
            // submitBlockedReason is never NO_RUNTIME on this path (RISK-03/
            // OQ-03: the PR-7 reconnect lane stays untouched).
            val currentStatus = gatewayProtocolAdapter?.connectionStatus?.value
            if (currentStatus != null && currentStatus != ConnectionStatus.CONNECTED &&
                memoryPreviewOwner != null && _uiState.value.savedSnapshotCapturedAtMillis != null) {
                _uiState.update { it.copy(isInitialLoading = false, isCachedHistoryPreview = false) }
                return@launch
            }
            if (currentStatus != null &&
                currentStatus != ConnectionStatus.CONNECTED &&
                serveConversationSnapshotIfPresent(sessionId)
            ) {
                return@launch
            }
            // Cache owns a sibling lane: it never awaits host scope/auth preparation.
            val preview = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) preview@ {
                try {
                    if (manual || recovery || _uiState.value.timeline.isNotEmpty() ||
                        gatewayProtocolAdapter?.connectionStatus?.value != ConnectionStatus.CONNECTED) return@preview
                    fun previewCurrent() = !historyDisposed && generation == historyGeneration &&
                        _uiState.value.isInitialLoading && admittedSocket == gatewayProtocolAdapter?.connectionGeneration &&
                        gatewayProtocolAdapter?.connectionStatus?.value == ConnectionStatus.CONNECTED
                    val owner = conversationCacheRepository?.captureReadOwner() ?: return@preview
                    capturedPreviewOwner = owner
                    serveConversationSnapshotIfPresent(sessionId, preview = true, readOwner = owner) { previewCurrent() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Cache failures never cancel the host read. */ }
            }
            val scope = try { captureHistoryScope(repo, generation) }
            catch (cancelled: CancellationException) { preview.cancel(); throw cancelled }
            catch (_: Exception) {
                // Let independent cache work settle before closing failed network preparation.
                preview.join()
                if (!historyDisposed && generation == historyGeneration) _uiState.update {
                    it.copy(isInitialLoading = false, errorMessage = "Unable to restore chat from storage.",
                        submitBlockedReason = SubmitBlockedReason.NO_RUNTIME)
                }
                return@launch
            }
            if (historyDisposed || generation != historyGeneration) { preview.cancel(); return@launch }
            if (scope != null) {
                try {
                    openActiveHistory(repo, scope, generation, previousScope, previousSocketGeneration, recovery || manual, manual, admittedSocket)
                } finally { preview.cancel() }
                return@launch
            }
            val resumeResult = try { repo.resumeSession(sessionId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Result.failure(IllegalStateException("Couldn't load conversation")) }
            suspend fun current(requireConnection: Boolean = true): Boolean =
                !historyDisposed && generation == historyGeneration
            if (!current()) { preview.cancel(); return@launch }
            resumeResult.onSuccess { data ->
                // CHG-005-S2 (TASK-004/005, RISK-003) + CHG-005-S3 (TASK-010, SEC-001):
                // map ONLY entries with a non-blank body to timeline rows; blank-body
                // entries are skipped entirely (no empty containers) and counted for
                // debug diagnostics. Original order and stable hist-* IDs (source
                // indices) are preserved. Role interpretation: 'user' ->
                // UserMessage(SENT); 'assistant' -> AssistantMessage(COMPLETE); any
                // other/unknown non-blank role (tool/step events, blank-role bodies)
                // renders as ONE collapsed bounded GenericGatewayActivity row via
                // BoundedPayloadMapper — consistent with the live GatewayEvent.Unknown
                // path: bounded, redacted, and the raw body is never rendered.
                // RISK-002: no invented cards — a row exists only when the host
                // reported a non-blank entry.
                //
                // F007-S1: the blank-skip above applies to TEXT rows only. Host
                // role:"tool" resume rows carry name/context and NO content, so a
                // nonblank toolName/toolContext is itself the non-blank host
                // evidence: render it as a completed, collapsed ToolActivity row
                // (same rendering path as live tool cards) with a stable
                // hist-tool-<index> ID. A tool row with no usable name/context
                // still falls through to the unknown-role generic row so it can
                // never silently vanish.
                var skippedBlanks = 0
                val historyEntries = data.messages.mapIndexed { index, msg ->
                    val entries = mapHistoryMessageToEntries(msg, index, prefix = "hist") { skippedBlanks++ }
                    entries
                }
                skippedHistoryCount = skippedBlanks

                // CHG-005-S4 (TASK-008): on loadSession() success the header
                // title precedence is: the threaded server summary title
                // (already in state from the tapped SessionSummary) > the
                // repository's active-handle title (CHG-003-S1 vault
                // lastKnownTitle propagation) > a short friendly label. A
                // friendly placeholder never blocks a real title source, and
                // the raw durable session ID is never displayed.
                val repoTitle = repo.getActiveSession()?.title?.takeUnless { it.isBlank() }
                if (!current()) return@onSuccess

                if (pickerRequestRuntimeId != null && pickerRequestRuntimeId != data.runtimeSessionId) {
                    pickerRequestOwner = null
                    pickerRequestRuntimeId = null
                }
                if (pickerPendingRuntimeId != null && pickerPendingRuntimeId != data.runtimeSessionId) {
                    pickerPendingRuntimeId = null
                    pickerPendingTurnCompleted = false
                }
                _uiState.update { current ->
                    val existingRealTitle = current.sessionTitle
                        ?.takeUnless {
                            it.isBlank() || it == UNTITLED_CONVERSATION_HEADER_LABEL
                        }
                    current.copy(
                        durableSessionId = data.durableSessionId,
                        runtimeSessionId = data.runtimeSessionId,
                        pickerChange = current.pickerChange?.takeIf { it.runtime == data.runtimeSessionId },
                        pickerOptions = if (current.runtimeSessionId == data.runtimeSessionId) current.pickerOptions else emptyList(),
                        pickerNotice = if (pickerPendingRuntimeId == data.runtimeSessionId)
                            if (pickerPendingTurnCompleted && !current.isStreaming && !current.isSubmitting)
                                "Queued by Hermes — not yet confirmed; check the model on the next turn"
                            else current.pickerPendingNotice else null,
                        pickerPendingNotice = if (pickerPendingRuntimeId == data.runtimeSessionId)
                            if (pickerPendingTurnCompleted && !current.isStreaming && !current.isSubmitting)
                                "Queued by Hermes — not yet confirmed; check the model on the next turn"
                            else current.pickerPendingNotice else null,
                        pickerBusy = pickerRequestOwner != null && pickerRequestRuntimeId == data.runtimeSessionId,
                        sessionTitle = existingRealTitle
                            ?: repoTitle
                            ?: chatHeaderFallbackTitle(sessionId),
                        timeline = historyEntries.flatten(),
                        taskProgress = TaskProgressState.restore(data.taskSnapshot, data.activityEvidence == ResumeActivityEvidence.RUNNING),
                        // PR-3 (REQ-10): a successful remote resume atomically
                        // REPLACES the saved snapshot — the label clears.
                        savedSnapshotCapturedAtMillis = null,
                        isCachedHistoryPreview = false,
                        hasAcceptedStoredHistory = false,
                        isInitialLoading = false,
                        errorMessage = null,
                        submitBlockedReason = null,
                        // TRD1 slice 1 (TASK-003): server truth replaces the
                        // local trace (A2 reconnect honesty; REQ-003) — the
                        // surface then renders ungrouped per A1.
                        trace = null,
                        // CHAT-004 TASK-010: hydration replacement re-derives
                        // the prepend base row and resets the indicator (the
                        // fresh window is authoritative).
                        historyBeginState = ChatUiState.HistoryBeginState.NONE,
                        // CHAT-010: the resume info map is the label source —
                        // set both raw fields from host data (blank reads as
                        // null, never fabricated; the derived label maps them).
                        sessionUsageEffortUnknown = modelEventRuntime == data.runtimeSessionId && current.sessionUsageEffortUnknown,
                        sessionModelName = if (modelEventRuntime == data.runtimeSessionId || current.holdsPickerEchoFor(data.runtimeSessionId)) current.sessionModelName else SessionModelLabelPolicy.modelFromInfo(data.info),

                        sessionReasoningEffort = if (modelEventRuntime == data.runtimeSessionId || current.holdsPickerEchoFor(data.runtimeSessionId)) current.sessionReasoningEffort else SessionModelLabelPolicy.reasoningEffortFromInfo(data.info)
                    ).acceptYolo(data.info["yolo"] as? Boolean)
                }
                retainHostPreview(data.durableSessionId, historyEntries.flatten(), data.messagesOmitted) { current() }
                if (pickerReadObservation == pickerObservationRevision) reconcilePicker(data.info, pickerReadRevision, fromRefresh = true)
                // CHAT-017 (REQ-004): the resume echo seeds the last-reported
                // pair, so a re-attach followed by a SessionInfo event that
                // repeats the same values is not a change. Deliberately
                // OUTSIDE the update lambda above — StateFlow.update may
                // retry its lambda; VM-field writes must not ride a retried
                // block.
                reconcileCompressionFromResume(data)
                lastReportedSessionModel = _uiState.value.sessionModelName
                lastReportedSessionEffort = _uiState.value.sessionReasoningEffort
                oldestRenderedHistoryRow = data.messages.firstOrNull()

                // CHAT-011 S2 (TASK-007): resume-success seed trigger — the
                // resume info echo is the profile-default model source when
                // no record exists (plan §State-ownership: cache-miss +
                // connected + echo). The seed function owns every guard; this
                // is one of exactly two trigger sites.
                seedProfileModelDefaultIfMissing(
                    SessionModelLabelPolicy.modelFromInfo(data.info)
                )

                // PR-3 (REQ-10): the authoritative resume history atomically
                // REPLACES any cached snapshot (soft, never disturbs the UI).
                if (!current()) return@onSuccess
                restoreUnknownEdit(data)
                replaceRemoteSnapshotBestEffort(data, null, null)
                if (!current()) return@onSuccess

                // PR-4 (§6): hydration projection — the carried phone-captured
                // trace surfaces WITH the hydrated timeline (best-effort: a
                // failed read leaves the group empty, nothing else moves).
                projectCachedToolTrace(data.durableSessionId, null, null) { current() }
                if (!current()) return@onSuccess

                reconcileQueueAfterHistory(data) { current() }
                if (!current()) return@onSuccess

                // Reconcile active/unknown pending turn with authoritative history
                val targetStored = data.durableSessionId
                if (chatQueue?.state?.value?.active?.state != QueueItemState.UNKNOWN) {
                    coordinator?.reconcilePendingTurnWithHistory(targetStored, data.messages)?.let { outcome ->
                        if (outcome is ReconciliationOutcome.ProvenByHistory) {
                            _uiState.update { current ->
                                current.copy(
                                    pendingUnknownTurnId = null,
                                    submissionStatus = SubmissionStatus.IDLE
                                )
                            }
                        }
                    }
                }
            }.onFailure { error ->
                if (!current(false)) return@onFailure
                // PR-3 (§6 row 3, REQ-09): CONNECTED-but-failed resume falls
                // back to the labelled snapshot when one is fresh — the failed
                // resume did NOT rewrite the cache (not authoritative, T-10(c)).
                if (preserveMemoryPreviewAfterFailure("Unable to restore chat.")) return@onFailure
                if (serveConversationSnapshotIfPresent(sessionId)) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isInitialLoading = false,
                        errorMessage = error.message ?: "Failed to resume session",
                        // BLD-006 S2: a failed resume leaves no runtime attached,
                        // so the chat surface must show the blocked state and the
                        // restore-session notice (submitPrompt() also guards on
                        // runtimeSessionId; this is the presentation trigger).
                        submitBlockedReason = SubmitBlockedReason.NO_RUNTIME
                    )
                }
            }
            preview.cancel()
        }
        historyLoadJob = read
        read.invokeOnCompletion { failure ->
            if (!historyDisposed && generation == historyGeneration) {
                _uiState.update { it.copy(isCachedHistoryPreview = false, isHistoryReadInFlight = false,
                    isInitialLoading = if (it.savedSnapshotCapturedAtMillis != null && it.runtimeSessionId == null) false else it.isInitialLoading) }
            }
            if (firstChannelRead && failure != null) channelInitialRead.complete(false)
        }
        // The operation includes both sibling lanes and seed completion, not early REST loading state.
        viewModelScope.launch {
            read.join()
            if (firstChannelRead) {
                val current = _uiState.value
                channelInitialRead.complete(!read.isCancelled && !historyDisposed && generation == historyGeneration &&
                    historyLoadJob === read && current.durableSessionId == sessionId &&
                    !current.runtimeSessionId.isNullOrBlank() && current.savedSnapshotCapturedAtMillis == null &&
                    current.errorMessage == null && current.submitBlockedReason == null &&
                    !current.isInitialLoading && !current.isStreaming && !current.isSubmitting)
            }
            if (historyDisposed || generation != historyGeneration || historyLoadJob !== read || read.isCancelled) return@launch
            if (manual) { _uiState.update { it.copy(isRefreshing = false) }; finishYoloRefresh() }
            val scope = storedHistoryScope
            val runtime = _uiState.value.runtimeSessionId
            if (pendingHistoryGap && scope != null && !runtime.isNullOrBlank() && runtime == pendingHistoryGapRuntime &&
                admittedSocket == gatewayProtocolAdapter?.connectionGeneration && repo.isStoredHistoryCurrent(scope)) {
                pendingHistoryGap = false
                startHistoryRead(preserveAttachments = true, recovery = true)
            }
        }
    }


    private fun presentationGroups(entries: List<TimelineEntry>, source: List<ActiveFoldMessage>): List<ActiveFoldMessage> {
        val out = ArrayList<ActiveFoldMessage>()
        for (entry in entries) {
            val known = source.firstOrNull { group -> group.parts.any { it.id == entry.id } }
            val index = if (known == null) -1 else out.indexOfFirst { it.id == known.id }
            if (index >= 0) { out[index] = out[index].copy(parts = out[index].parts + entry); continue }
            if (known != null) { out.add(known.copy(parts = listOf(entry))); continue }
            val kind = when (entry) {
                is UserMessage -> entry.sourceKind
                is AssistantMessage -> entry.sourceKind
                else -> ActiveSourceKind.LIVE
            }
            if (entry !is UserMessage && entry !is AssistantMessage && out.lastOrNull()?.parts?.any {
                    it is AssistantMessage && it.id == activeAssistantEntryId
                } == true) {
                out[out.lastIndex] = out.last().copy(parts = out.last().parts + entry)
            } else out.add(ActiveFoldMessage(entry.id, if (entry is UserMessage) "user" else "assistant",
                listOf(entry), kind, pending = (entry as? AssistantMessage)?.state == AssistantMessageState.STREAMING))
        }
        return out.map { row ->
            if (row.sourceKind == ActiveSourceKind.STREAM || row.sourceKind == ActiveSourceKind.INFLIGHT)
                row.copy(pending = row.parts.filterIsInstance<AssistantMessage>().any { it.state == AssistantMessageState.STREAMING })
            else row
        }
    }

    private fun compactGroups(data: SessionResumeResult): List<ActiveFoldMessage> = data.messages.mapIndexed { i, message ->
        ActiveFoldMessage("hist-$i", message.role, mapHistoryMessageToEntries(message, i, "hist") {},
            ActiveSourceKind.COMPACT, message.sourceTimestampSeconds, sourceMetadata = message,
            retainedSourceBytes = 256L + listOfNotNull(message.content, message.reasoning, message.toolName, message.toolContext)
                .sumOf { BoundedPayloadMapper.utf8ByteCount(it).toLong() })
    }

    private fun activeProjectionGroups(data: SessionResumeResult, base: List<ActiveFoldMessage>, runtime: List<ActiveFoldMessage>): List<ActiveFoldMessage> {
        val projection = data.activeProjection ?: return base
        val inflight = projection.inflight.value
        val out = base.toMutableList()
        if (inflight != null) {
            val user = inflight.user.value
            val hidden = inflight.displayKind.value == "hidden"
            val represented = user != null && base.lastOrNull { it.role == "user" }?.text?.trim() == user.trim()
            if (!hidden && !user.isNullOrBlank() && !ActiveHistoryFold.suppressInflightUser(base, runtime, user)) {
                val id = "inflight-user-${data.runtimeSessionId}"
                out.add(ActiveFoldMessage(id, "user", listOf(UserMessage(id, user, timestamp = 0,
                    sourceKind = ActiveSourceKind.LOCAL_USER)), ActiveSourceKind.LOCAL_USER, sourceMetadata = inflight, retainedSourceBytes = projection.retainedBytes.toLong(), retainedSource = projection))
            }
            val answer = inflight.assistant.value
            val streaming = data.activityEvidence == ResumeActivityEvidence.RUNNING && inflight.streaming.value == true
            val failed = !inflight.error.value.isNullOrBlank()
            val currentUser = base.indexOfLast { it.role == "user" }
            val tail = base.drop(currentUser + 1)
            fun currentTurn(row: ActiveFoldMessage): Boolean = represented && row.sourceTimestampSeconds != null &&
                projection.turnStartedAt.value?.let { row.sourceTimestampSeconds >= it } == true
            val structured = tail.lastOrNull { currentTurn(it) && it.parts.any { part -> part is ReasoningEntry || part is ToolActivity } }
            val offsets = inflight.correctionOffsets
            val corrections = inflight.corrections
            val reliableOffsets = offsets.presence == com.hermes.bot.data.gateway.WirePresence.VALUE &&
                corrections.presence == com.hermes.bot.data.gateway.WirePresence.VALUE && !offsets.truncated &&
                !corrections.truncated && offsets.value?.size == corrections.value?.size
            val committed = tail.lastOrNull { row -> currentTurn(row) && !failed && !row.live &&
                row.parts.none { it is ToolActivity || (it is GenericGatewayActivity && it.eventType == "history.tool") } &&
                row.text.isNotBlank() && !answer.isNullOrBlank() && row.text.trim().startsWith(answer.trim()) }
            val partial = tail.lastOrNull { row -> currentTurn(row) && streaming && !failed && !reliableOffsets &&
                row.role == "assistant" && row.text.isNotBlank() && !answer.isNullOrBlank() &&
                answer.trim().startsWith(row.text.trim()) }
            if (committed == null && partial != null) {
                val lastAssistant = partial.parts.filterIsInstance<AssistantMessage>().lastOrNull()
                val parts = partial.parts.map { part ->
                    if (part === lastAssistant) (part as AssistantMessage).copy(
                        text = if (structured == null) answer.orEmpty() else part.text,
                        state = AssistantMessageState.STREAMING, sourceKind = ActiveSourceKind.STREAM)
                    else part
                }
                out[out.indexOf(partial)] = partial.copy(parts = parts, sourceKind = ActiveSourceKind.STREAM, pending = true)
            } else if ((streaming || !answer.isNullOrBlank() || failed) && committed == null) {
                val id = "assistant-stream-${data.runtimeSessionId}"
                val parts = ArrayList<TimelineEntry>()
                val text = if (structured != null && !failed && !reliableOffsets) "" else answer.orEmpty()
                var cursor = 0
                if (reliableOffsets) {
                    corrections.value.orEmpty().forEachIndexed { index, correction ->
                        val offset = offsets.value!![index].coerceIn(cursor.toLong(), text.length.toLong()).toInt()
                        if (offset > cursor) parts.add(AssistantMessage("$id-part-$index", text.substring(cursor, offset),
                            if (failed) AssistantMessageState.FAILED else AssistantMessageState.UNKNOWN, timestamp = 0,
                            sourceKind = if (streaming) ActiveSourceKind.STREAM else ActiveSourceKind.INFLIGHT))
                        if (correction.isNotBlank() && tail.none { it.text == correction })
                            parts.add(UserMessage("$id-correction-$index", correction, timestamp = 0, sourceKind = ActiveSourceKind.CORRECTION))
                        cursor = offset
                    }
                }
                val suffix = text.substring(cursor)
                parts.add(AssistantMessage(id, suffix, if (failed) AssistantMessageState.FAILED
                    else if (streaming) AssistantMessageState.STREAMING else AssistantMessageState.UNKNOWN,
                    errorMessage = inflight.error.value, timestamp = 0, sourceKind = if (streaming) ActiveSourceKind.STREAM else ActiveSourceKind.INFLIGHT))
                if (!reliableOffsets) corrections.value.orEmpty().forEachIndexed { index, correction ->
                    if (correction.isNotBlank() && tail.none { it.text == correction })
                        parts.add(UserMessage("$id-correction-$index", correction, timestamp = 0, sourceKind = ActiveSourceKind.CORRECTION))
                }
                out.add(ActiveFoldMessage(id, "assistant", parts, if (streaming) ActiveSourceKind.STREAM else ActiveSourceKind.INFLIGHT,
                    pending = streaming, sourceMetadata = inflight, retainedSourceBytes = projection.retainedBytes.toLong(), retainedSource = projection))
            }
        }
        val queued = projection.queued.value?.user?.value
        if (!queued.isNullOrBlank()) {
            val id = "user-queued-${data.runtimeSessionId}"
            out.add(ActiveFoldMessage(id, "user", listOf(UserMessage(id, queued, timestamp = 0,
                sourceKind = ActiveSourceKind.QUEUED)), ActiveSourceKind.QUEUED, sourceMetadata = projection.queued, retainedSourceBytes = projection.retainedBytes.toLong(), retainedSource = projection))
        }
        return out
    }

    private suspend fun openActiveHistory(repo: SessionRepository, scope: StoredHistoryScope, generation: Long, previousScope: StoredHistoryScope?, previousSocketGeneration: Long?, refresh: Boolean, manual: Boolean, socketGeneration: Long?) {
        val pickerReadRevision = pickerRevision
        val pickerReadObservation = pickerObservationRevision
        val previousWindow = storedWindow
        val budget = StoredHistoryBudget(scope).also {
            if (refresh) it.acceptedLimit = previousWindow?.budget?.acceptedLimit ?: 120
        }
        var publicationBudget = budget
        storedWindow = null
        _uiState.update { it.copy(storedHistoryEarlierAvailable = false) }
        suspend fun current(live: Boolean = false): Boolean = !historyDisposed && generation == historyGeneration && scope.current &&
            repo.isStoredHistoryPublicationCurrent(scope, publicationBudget) &&
            (!live || (socketGeneration == gatewayProtocolAdapter?.connectionGeneration && repo.isStoredHistoryCurrent(scope, publicationBudget))) &&
            repo.isStoredHistoryPublicationCurrent(scope, publicationBudget) &&
            !historyDisposed && generation == historyGeneration && scope.current
        var rows: List<StoredHistoryRow> = emptyList()
        var storedGroups: List<ActiveFoldMessage>? = null
        var localSnapshot: List<ActiveFoldMessage>? = null
        var runtimeBaseline: List<ActiveFoldMessage> = emptyList()
        var resumed: SessionResumeResult? = null
        var resumedTitle: String? = null
        var storedNotice: String? = null
        var projectionBytes = 0L
        val preserveOpen = previousScope != null && previousScope.localProfileId == scope.localProfileId &&
            previousScope.origin == scope.origin && previousScope.remoteProfile == scope.remoteProfile &&
            previousScope.requestedSessionId == scope.requestedSessionId
        val previousRows = if (preserveOpen && refresh) acceptedHistoryRows else emptyList()
        _uiState.update { it.copy(hasAcceptedStoredHistory = preserveOpen && refresh && it.hasAcceptedStoredHistory) }
        if (previousRows.isNotEmpty()) {
            rows = previousRows
            storedGroups = StoredHistoryHydrator.hydrateForActive(previousRows)
        }
        if (!preserveOpen || !refresh) acceptedHistoryRows = emptyList()
        var attached = false
        val openStart = if (preserveOpen && _uiState.value.durableSessionId == scope.requestedSessionId)
            presentationGroups(_uiState.value.timeline, activeHistoryGroups).filter { refresh || it.sourceKind != ActiveSourceKind.STORED } else emptyList()
        val publicationMutex = Mutex()
        val preserveRuntime = preserveOpen && previousSocketGeneration == socketGeneration
        fun installEventAdmission() {
            activeHistoryEventAdmission = { event ->
                if (!current() || socketGeneration != gatewayProtocolAdapter?.connectionGeneration ||
                    !current(true)) false
                else try {
                    val latestGroups = presentationGroups(_uiState.value.timeline, activeHistoryGroups)
                    StoredHistoryHydrator.admit(rows, latestGroups,
                        (activeTurnBuffer?.canonicalByteCount ?: 0L) + pendingActiveDeltaBytes + activeEventReservation(event),
                        listOf(openStart, localSnapshot.orEmpty(), runtimeBaseline),
                        listOfNotNull(resumed?.activeProjection?.let { it to projectionBytes }))
                    true
                } catch (_: StoredHistoryException) {
                    if (current(true)) _uiState.update { it.copy(errorMessage = "Stored history limit reached. Reopen to load the latest history.") }
                    false
                }
            }
        }
        if (preserveRuntime && activeAssistantEntryId != null && _uiState.value.runtimeSessionId != null &&
            current() && socketGeneration == gatewayProtocolAdapter?.connectionGeneration) {
            val buffer = activeTurnBuffer
            val entryId = activeAssistantEntryId!!
            if (buffer != null) {
                val continued = _uiState.value.timeline.map { part ->
                    if (part is AssistantMessage && part.id == entryId) part.copy(text = buffer.presentationWindow()) else part
                }
                try {
                    StoredHistoryHydrator.admit(emptyList(), presentationGroups(continued, activeHistoryGroups),
                        buffer.canonicalByteCount, listOf(openStart))
                    if (current() && socketGeneration == gatewayProtocolAdapter?.connectionGeneration) {
                        remoteActiveGeneration = socketGeneration
                        mintDeltaPublicationScheduler(entryId)
                        publishAssistantPresentation(entryId, buffer)
                        installEventAdmission()
                    }
                } catch (_: StoredHistoryException) {
                    if (current()) _uiState.update { it.copy(errorMessage = "Stored history limit reached. Reopen to load the latest history.") }
                }
            }
        }
        suspend fun publish(proposed: List<ActiveFoldMessage>, incoming: SessionResumeResult?, bind: Boolean,
            storedPublication: Boolean = false, olderPage: StoredHistoryPage? = null): Boolean = publicationMutex.withLock {
            if (olderPage != null) {
                if (!current() || publicationBudget.remainingMillis() <= 0) return@withLock false
                deltaPublicationScheduler?.flush()
                val proposedRows = StoredHistoryHydrator.mergeOlder(rows, olderPage.rows)
                val latest = presentationGroups(_uiState.value.timeline, activeHistoryGroups)
                val expansions = latest.flatMap { it.parts }.filterIsInstance<ToolActivity>().associateBy { it.id }
                val base = StoredHistoryHydrator.hydrateForActive(proposedRows).map { group -> group.copy(
                    parts = group.parts.map { part -> if (part is ToolActivity)
                        part.copy(isExpanded = expansions[part.id]?.isExpanded ?: part.isExpanded) else part }) }
                val nonStored = StoredHistoryHydrator.removeRepresentedTools(proposedRows, latest)
                    .filter { it.sourceKind != ActiveSourceKind.STORED }
                val next = ActiveHistoryFold.overlay(base, emptyList(), nonStored)
                StoredHistoryHydrator.admit(proposedRows, next,
                    (activeTurnBuffer?.canonicalByteCount ?: 0L) + pendingActiveDeltaBytes,
                    listOf(openStart, localSnapshot.orEmpty(), runtimeBaseline),
                    listOfNotNull(resumed?.activeProjection?.let { it to projectionBytes }))
                if (!current() || publicationBudget.remainingMillis() <= 0) return@withLock false
                rows = proposedRows
                acceptedHistoryRows = proposedRows
                storedGroups = base
                activeHistoryGroups = next
                storedWindow?.let { it.offset = olderPage.nextOffset; it.possiblyEarlier = olderPage.possiblyEarlier }
                _uiState.update { it.copy(timeline = next.flatMap { it.parts }, isLoadingEarlier = false,
                    errorMessage = null, olderHistoryRevision = it.olderHistoryRevision + 1,
                    storedHistoryEarlierAvailable = olderPage.possiblyEarlier && !olderResourceBlocked,
                    historyBeginState = if (olderPage.possiblyEarlier) ChatUiState.HistoryBeginState.NONE
                        else ChatUiState.HistoryBeginState.NON_EXHAUSTIVE) }
                return@withLock true
            }
            val base = if (storedPublication) proposed else storedGroups ?: proposed
            if (!current()) return@withLock false
            val data = if (incoming != null && current(true)) incoming else null
            if (!current(data != null)) return@withLock false
            deltaPublicationScheduler?.flush()
            val source = localSnapshot ?: openStart.takeIf { it.isNotEmpty() } ?: base
            val latest = StoredHistoryHydrator.removeRepresentedTools(rows,
                if (preserveOpen || attached || localSnapshot != null)
                    presentationGroups(_uiState.value.timeline, source).filter { refresh || it.sourceKind != ActiveSourceKind.STORED } else emptyList())
            val retainedOpen = StoredHistoryHydrator.removeRepresentedTools(rows, openStart)
            val sameRuntime = data != null && (preserveOpen || attached) && _uiState.value.runtimeSessionId == data.runtimeSessionId
            val hasProjection = data?.activeProjection?.let { it.inflight.value != null || it.queued.value != null } == true
            val currentPresentation = ActiveHistoryFold.removeRepresentedTail(base,
                if (sameRuntime || data == null || localSnapshot != null) latest else retainedOpen)
            // Freeze REST's own graft/reconciliation BEFORE applying the resume projection.
            val optimistic = currentPresentation.filter {
                it.sourceKind != ActiveSourceKind.STORED && it.sourceKind != ActiveSourceKind.COMPACT
            }
            val postRest = if (storedPublication) ActiveHistoryFold.overlay(
                ActiveHistoryFold.overlay(base, emptyList(), optimistic), retainedOpen, currentPresentation)
                else localSnapshot
            var next = if (data != null && hasProjection) activeProjectionGroups(data, base, compactGroups(data))
                else postRest ?: if (preserveOpen && refresh && previousRows.isNotEmpty())
                    // An unavailable REST lane cannot erase previously accepted compact host messages.
                    ActiveHistoryFold.overlay(base, emptyList(), retainedOpen.filter { it.sourceKind == ActiveSourceKind.COMPACT }) else base
            if (data == null && postRest == null) {
                val optimistic = ActiveHistoryFold.removeRepresentedTail(base, retainedOpen).filter {
                    it.sourceKind != ActiveSourceKind.STORED && it.sourceKind != ActiveSourceKind.COMPACT
                }
                next = ActiveHistoryFold.overlay(next, emptyList(), optimistic)
            }
            val presentationAfterRest = if (storedPublication) postRest.orEmpty() else currentPresentation
            next = ActiveHistoryFold.overlay(next, postRest ?: retainedOpen, presentationAfterRest)
            if (sameRuntime) {
                val runtimeCurrent = if (storedPublication) postRest.orEmpty().map { row ->
                    optimistic.firstOrNull { it.id == row.id } ?: row
                } + optimistic.filter { row -> postRest.orEmpty().none { it.id == row.id } } else latest
                next = ActiveHistoryFold.overlay(next, runtimeBaseline, runtimeCurrent)
            }
            val streaming = next.flatMap { it.parts }.filterIsInstance<AssistantMessage>().lastOrNull { it.state == AssistantMessageState.STREAMING }
            val proposedProjectionBytes = if (data == null) projectionBytes else data.activeProjection?.retainedBytes?.toLong() ?: 0L
            val rawBytes = if (streaming == null) 0L else if (bind || activeAssistantEntryId != streaming.id)
                BoundedPayloadMapper.utf8ByteCount(streaming.text).toLong() else activeTurnBuffer?.canonicalByteCount ?: 0L
            StoredHistoryHydrator.admit(rows, next, rawBytes + pendingActiveDeltaBytes,
                listOf(openStart, postRest.orEmpty(), localSnapshot.orEmpty(), runtimeBaseline),
                listOfNotNull(data?.activeProjection?.let { it to proposedProjectionBytes },
                    resumed?.activeProjection?.let { it to it.retainedBytes.toLong() }))
            if (historyDisposed || generation != historyGeneration || !scope.current) return@withLock false
            if (data != null) {
                if (pickerRequestRuntimeId != null && pickerRequestRuntimeId != data.runtimeSessionId) {
                    pickerRequestOwner = null; pickerRequestRuntimeId = null
                }
                if (pickerPendingRuntimeId != null && pickerPendingRuntimeId != data.runtimeSessionId) {
                    pickerPendingRuntimeId = null; pickerPendingTurnCompleted = false
                }
            }
            if (streaming != null && (bind || activeAssistantEntryId != streaming.id)) {
                deltaPublicationScheduler?.dispose()
                activeTurnId = null
                activeAssistantEntryId = streaming.id
                activeTurnBuffer = AssistantTurnStreamBuffer().apply { replaceWithTerminalText(streaming.text) }
                remoteActiveGeneration = socketGeneration
                mintDeltaPublicationScheduler(streaming.id)
            } else if (streaming != null && preserveRuntime) {
                remoteActiveGeneration = socketGeneration
                mintDeltaPublicationScheduler(streaming.id)
            } else if (streaming == null && activeAssistantEntryId != null) {
                deltaPublicationScheduler?.dispose()
                deltaPublicationScheduler = null
                activeAssistantEntryId = null
                activeTurnBuffer = null
                activeTurnId = null
            }
            _uiState.update { state -> state.copy(
                timeline = next.flatMap { it.parts }, isInitialLoading = false,
                runtimeSessionId = data?.runtimeSessionId ?: if (attached || (preserveRuntime && socketGeneration == gatewayProtocolAdapter?.connectionGeneration)) state.runtimeSessionId else null,
                durableSessionId = data?.durableSessionId ?: state.durableSessionId,
                savedSnapshotCapturedAtMillis = null, isCachedHistoryPreview = false, errorMessage = storedNotice,
                hasAcceptedStoredHistory = storedPublication || (storedGroups != null && state.hasAcceptedStoredHistory),
                sessionTitle = if (data == null) state.sessionTitle else state.sessionTitle?.takeUnless {
                    it.isBlank() || it == UNTITLED_CONVERSATION_HEADER_LABEL
                } ?: resumedTitle ?: chatHeaderFallbackTitle(sessionId),
                trace = if (data == null) state.trace else null,
                taskProgress = if (data == null || (storedPublication && sameRuntime)) state.taskProgress else
                    TaskProgressState.restore(data.taskSnapshot, data.activityEvidence == ResumeActivityEvidence.RUNNING),
                historyBeginState = if (data == null) state.historyBeginState else ChatUiState.HistoryBeginState.NONE,
                pickerNotice = if (data == null || pickerPendingRuntimeId == data.runtimeSessionId) state.pickerNotice else null,
                pickerPendingNotice = if (data == null || pickerPendingRuntimeId == data.runtimeSessionId) state.pickerPendingNotice else null,
                pickerBusy = pickerRequestOwner != null && pickerRequestRuntimeId == (data?.runtimeSessionId ?: state.runtimeSessionId),
                pickerChange = if (data == null) state.pickerChange else state.pickerChange?.takeIf { it.runtime == data.runtimeSessionId },
                pickerOptions = if (data == null || data.runtimeSessionId == state.runtimeSessionId) state.pickerOptions else emptyList(),
                submitBlockedReason = when {
                    data?.activityEvidence == ResumeActivityEvidence.NON_RUNNING -> null
                    // A stored callback carries the earlier resume, not new runtime evidence.
                    // Preserve the current gate for that same already-attached runtime.
                    storedPublication && attached && sameRuntime &&
                        data?.activityEvidence == ResumeActivityEvidence.RUNNING -> state.submitBlockedReason
                    else -> SubmitBlockedReason.NO_RUNTIME
                },
                isStreaming = streaming != null,
                sessionUsageEffortUnknown = (storedPublication || modelEventRuntime == data?.runtimeSessionId) && state.sessionUsageEffortUnknown,
                sessionModelName = if (storedPublication || modelEventRuntime == data?.runtimeSessionId || state.holdsPickerEchoFor(data?.runtimeSessionId)) state.sessionModelName else data?.let { SessionModelLabelPolicy.modelFromInfo(it.info) } ?: state.sessionModelName,

                sessionReasoningEffort = if (storedPublication || modelEventRuntime == data?.runtimeSessionId || state.holdsPickerEchoFor(data?.runtimeSessionId)) state.sessionReasoningEffort else data?.let { SessionModelLabelPolicy.reasoningEffortFromInfo(it.info) } ?: state.sessionReasoningEffort
            ).let { if (data == null || storedPublication) it else it.acceptYolo(data.info["yolo"] as? Boolean) } }
            if (data != null && !storedPublication && pickerReadObservation == pickerObservationRevision)
                reconcilePicker(data.info, pickerReadRevision, fromRefresh = true)
            activeHistoryGroups = next
            projectionBytes = proposedProjectionBytes
            installEventAdmission()
            if (storedPublication) {
                storedGroups = proposed
                acceptedHistoryRows = rows
                localSnapshot = postRest
            }
            if (data != null) {
                if (!storedPublication) reconcileCompressionFromResume(data)
                attached = true; remoteActiveGeneration = socketGeneration
                lastReportedSessionModel = _uiState.value.sessionModelName
                lastReportedSessionEffort = _uiState.value.sessionReasoningEffort
                oldestRenderedHistoryRow = data.messages.firstOrNull()
            }
            else if (preserveRuntime) remoteActiveGeneration = socketGeneration
            true
        }
        val result = repo.openStoredHistory(scope, budget, onStored = { page ->
            if (current()) page.fold(onSuccess = { accepted ->
                try {
                    var extended = accepted.rows
                    var page = accepted
                    val priorIds = previousRows.map { it.id }.toSet()
                    val oldest = previousRows.minOfOrNull { it.id }
                    if (extended.isNotEmpty() && priorIds.isNotEmpty() && extended.none { it.id in priorIds }) {
                        var overlap = false
                        for (older in 1..4) {
                            if (!page.possiblyEarlier || !current()) break
                            val fetched = repo.fetchStoredHistoryPage(scope, budget, page.nextOffset).getOrNull() ?: break
                            if (!current() || fetched.rows.isEmpty()) break
                            // Disconnected pages are provisional until a durable anchor is found.
                            extended = (fetched.rows + extended).distinctBy { it.id }
                            StoredHistoryHydrator.admit(extended, StoredHistoryHydrator.hydrateForActive(extended),
                                baselines = listOf(openStart, localSnapshot.orEmpty(), runtimeBaseline))
                            if (fetched.rows.any { it.id in priorIds }) { page = fetched; overlap = true; break }
                            if (oldest != null && fetched.rows.any { it.id < oldest }) break
                            page = fetched
                        }
                        if (!overlap) { extended = accepted.rows; page = accepted }
                    }
                    if (!current() || budget.remainingMillis() <= 0) return@fold
                    val proposedRows = if (accepted.rows.isEmpty() && previousRows.isNotEmpty()) previousRows
                        else StoredHistoryHydrator.graftRefresh(extended, previousRows)
                    val candidate = StoredHistoryHydrator.hydrateForActive(proposedRows)
                    val oldRows = rows
                    rows = proposedRows
                    try {
                        if (publish(candidate, resumed, false, storedPublication = true)) {
                            retainHostPreview(scope.requestedSessionId, candidate.filterNot { it.live }.flatMap { it.parts },
                                page.possiblyEarlier) { current() }
                            val keepLane = accepted.rows.isEmpty() && previousRows.isNotEmpty() && previousWindow != null
                            storedWindow = StoredWindow(scope, budget,
                                if (keepLane) previousWindow!!.offset else page.nextOffset,
                                if (keepLane) previousWindow!!.possiblyEarlier else page.possiblyEarlier,
                                { publicationBudget = it }) { older, cycle ->
                                    publicationBudget = cycle
                                    publish(emptyList(), null, false, olderPage = older)
                                }
                            _uiState.update { it.copy(storedHistoryEarlierAvailable = storedWindow!!.possiblyEarlier && !olderResourceBlocked,
                                historyBeginState = if (storedWindow!!.possiblyEarlier) ChatUiState.HistoryBeginState.NONE
                                    else ChatUiState.HistoryBeginState.NON_EXHAUSTIVE) }
                        } else rows = oldRows
                    }
                    catch (failure: StoredHistoryException) { rows = oldRows; throw failure }
                } catch (_: StoredHistoryException) {
                    storedNotice = "Stored history limit reached. Reopen to load the latest history."
                    if (current()) _uiState.update { it.copy(isInitialLoading = false,
                        errorMessage = storedNotice) }
                }
            }, onFailure = {
                // A certified lazy draft has no persisted transcript until its first prompt.
                if (resumed?.isUnpersistedDraft != true || storedGroups != null) {
                    storedNotice = "Stored history unavailable. Live activity may still appear."
                    if (current()) _uiState.update { it.copy(errorMessage = storedNotice) }
                }
            })
        }, onResumed = { response ->
            // Capture at public resume resolution, before any guard/title/cache suspension.
            val resolved = response.getOrNull()
            val resolvedRuntimeBaseline = if (resolved != null && (preserveOpen || attached) &&
                _uiState.value.runtimeSessionId == resolved.runtimeSessionId)
                presentationGroups(_uiState.value.timeline, activeHistoryGroups).filter { refresh || it.sourceKind != ActiveSourceKind.STORED } else emptyList()
            if (current(true)) response.fold(onSuccess = { data ->
                runtimeBaseline = resolvedRuntimeBaseline
                val title = repo.getActiveSession()?.title?.takeUnless { it.isBlank() }
                if (!current(true)) return@fold
                resumedTitle = title
                val previousResume = resumed
                resumed = data
                if (data.isUnpersistedDraft && storedGroups == null) storedNotice = null
                try {
                    val hostGroups = storedGroups ?: compactGroups(data)
                    if (publish(hostGroups, data, true)) retainHostPreview(data.durableSessionId,
                        hostGroups.filterNot { it.live }.flatMap { it.parts }, data.messagesOmitted || storedWindow?.possiblyEarlier == true) { current(true) }
                }
                catch (_: StoredHistoryException) {
                    resumed = previousResume
                    if (current(true)) _uiState.update { it.copy(isInitialLoading = false,
                        errorMessage = "Stored history limit reached. Reopen to load the latest history.",
                        submitBlockedReason = SubmitBlockedReason.NO_RUNTIME) }
                    return@fold
                }
                if (current(true)) reconcileQueueAfterHistory(data) { current(true) }
                if (current(true)) seedProfileModelDefaultIfMissing(SessionModelLabelPolicy.modelFromInfo(data.info))
                if (current(true)) { restoreUnknownEdit(data); replaceRemoteSnapshotBestEffort(data, budget.receipt, scope.selectedContext) }
                if (current(true)) projectCachedToolTrace(data.durableSessionId, budget.receipt, scope.selectedContext) { current(true) }
            }, onFailure = {
                if (current()) _uiState.update { it.copy(isInitialLoading = false,
                    errorMessage = "Live activity unavailable.", submitBlockedReason = SubmitBlockedReason.NO_RUNTIME) }
            })
        })
        if (storedWindow == null && previousWindow != null && previousRows.isNotEmpty() && current()) {
            storedWindow = StoredWindow(scope, budget, previousWindow.offset, previousWindow.possiblyEarlier,
                { publicationBudget = it }) { older, cycle ->
                    publicationBudget = cycle
                    publish(emptyList(), null, false, olderPage = older)
                }
            _uiState.update { it.copy(storedHistoryEarlierAvailable = previousWindow.possiblyEarlier && !olderResourceBlocked) }
        }
        if (olderResourceBlocked && current()) _uiState.update { it.copy(
            errorMessage = "Stored history limit reached. Reopen to load the latest history.", storedHistoryEarlierAvailable = false) }
        if (manual && current()) _uiState.update { it.copy(isRefreshing = false,
            refreshErrorMessage = if (storedNotice != null || resumed == null || result.isFailure)
                "Unable to refresh chat. Try again." else null) }
        if (current() && resumed == null && storedGroups == null && !preserveOpen) {
            if (preserveMemoryPreviewAfterFailure("Unable to restore chat.")) return
            if (serveConversationSnapshotIfPresent(sessionId, budget.receipt, scope.selectedContext) { current() }) return
            if (current()) _uiState.update { it.copy(isInitialLoading = false,
                errorMessage = "Unable to restore chat.", submitBlockedReason = SubmitBlockedReason.NO_RUNTIME) }
        } else if (current() && result.isFailure) _uiState.update { it.copy(isInitialLoading = false,
            errorMessage = "Unable to restore chat.", submitBlockedReason = SubmitBlockedReason.NO_RUNTIME) }
    }

    /** CHAT-019: guarded manual refresh of the currently attached durable session. */
    fun refreshSession() = refreshSession(preserveQueuedAttachments = false)

    private fun refreshSession(preserveQueuedAttachments: Boolean) {
        cancelDictation()
        val current = _uiState.value
        val repo = sessionRepository ?: return
        if (
            sessionId == "new-session" ||
            current.isRefreshing ||
            yoloWritePending ||
            current.isSubmitting ||
            current.isStreaming
        ) return

        if (storedHistoryScope != null) {
            startHistoryRead(preserveAttachments = true, manual = true)
            return
        }
        attachmentController.invalidateForRefresh()
        _uiState.update { it.copy(
            isRefreshing = true, refreshErrorMessage = null,
            sessionYolo = if (it.yoloPending) it.sessionYolo else null,
            pendingImages = if (preserveQueuedAttachments) it.pendingImages else emptyList(),
            pendingFiles = if (preserveQueuedAttachments) it.pendingFiles else emptyList(),
            isAttachingImage = false, isAttachingFile = false, attachmentErrorMessage = null
        ) }
        val pickerReadRevision = pickerRevision
        val pickerReadObservation = pickerObservationRevision
        val refreshExecution = slashExecution
        val refreshHistory = historyGeneration
        val refreshRuntime = current.runtimeSessionId
        viewModelScope.launch {
            val owner = refreshRuntime?.let { slashCommands?.capture(sessionId, it) }
            suspend fun currentRefresh() = !historyDisposed && refreshExecution == slashExecution &&
                refreshHistory == historyGeneration && (refreshRuntime == null || _uiState.value.runtimeSessionId == refreshRuntime) &&
                (slashCommands == null || refreshRuntime == null ||
                    (owner != null && slashCommands.isCurrent(owner)))
            try {
                val result = repo.resumeSession(sessionId)
                if (!currentRefresh()) {
                    if (!historyDisposed && refreshExecution == slashExecution && refreshHistory == historyGeneration &&
                        _uiState.value.runtimeSessionId == refreshRuntime) {
                        unknownYolo()
                        setRefreshFailure()
                        finishYoloRefresh()
                    }
                    return@launch
                }
                result.fold(
                    onSuccess = { data ->
                        applyManualRefreshSuccess(repo, data, ::currentRefresh)
                        if (currentRefresh() && pickerReadObservation == pickerObservationRevision) reconcilePicker(data.info, pickerReadRevision, fromRefresh = true)
                    },
                    onFailure = { setRefreshFailure() }
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (currentRefresh()) setRefreshFailure()
            }
            if (refreshExecution == slashExecution) finishYoloRefresh()
        }
    }

    private fun setRefreshFailure() {
        _uiState.update { state ->
            state.copy(
                isRefreshing = false,
                refreshErrorMessage = "Unable to refresh chat. Try again."
            )
        }
    }

    fun dismissRefreshError() {
        _uiState.update { it.copy(refreshErrorMessage = null) }
    }

    /** Reuses the authoritative history mapper while leaving initial-load/cache state untouched. */
    private suspend fun applyManualRefreshSuccess(
        repo: SessionRepository,
        data: SessionResumeResult,
        currentRefresh: suspend () -> Boolean = { true }
    ) {
        var skippedBlanks = 0
        val historyEntries = data.messages.mapIndexed { index, msg ->
            mapHistoryMessageToEntries(msg, index, prefix = "hist") { skippedBlanks++ }
        }.flatten()
        skippedHistoryCount = skippedBlanks
        val repoTitle = repo.getActiveSession()?.title?.takeUnless { it.isBlank() }
        if (!currentRefresh()) return

        if (pickerRequestRuntimeId != null && pickerRequestRuntimeId != data.runtimeSessionId) {
            pickerRequestOwner = null
            pickerRequestRuntimeId = null
        }
        if (pickerPendingRuntimeId != null && pickerPendingRuntimeId != data.runtimeSessionId) {
            pickerPendingRuntimeId = null
            pickerPendingTurnCompleted = false
        }
        _uiState.update { current ->
            val existingRealTitle = current.sessionTitle?.takeUnless {
                it.isBlank() || it == UNTITLED_CONVERSATION_HEADER_LABEL
            }
            current.copy(
                durableSessionId = data.durableSessionId,
                runtimeSessionId = data.runtimeSessionId,
                        pickerChange = current.pickerChange?.takeIf { it.runtime == data.runtimeSessionId },
                        pickerOptions = if (current.runtimeSessionId == data.runtimeSessionId) current.pickerOptions else emptyList(),
                pickerNotice = if (pickerPendingRuntimeId == data.runtimeSessionId)
                    current.pickerPendingNotice else null,
                pickerPendingNotice = if (pickerPendingRuntimeId == data.runtimeSessionId)
                    current.pickerPendingNotice else null,
                pickerBusy = pickerRequestOwner != null && pickerRequestRuntimeId == data.runtimeSessionId,
                sessionTitle = existingRealTitle ?: repoTitle ?: chatHeaderFallbackTitle(sessionId),
                timeline = historyEntries,
                taskProgress = TaskProgressState.restore(data.taskSnapshot, data.activityEvidence == ResumeActivityEvidence.RUNNING),
                savedSnapshotCapturedAtMillis = null,
                isCachedHistoryPreview = false,
                cachedToolTrace = emptyList(),
                hasAcceptedStoredHistory = false,
                isRefreshing = false,
                refreshErrorMessage = null,
                trace = null,
                historyBeginState = ChatUiState.HistoryBeginState.NONE,
                sessionUsageEffortUnknown = modelEventRuntime == data.runtimeSessionId && current.sessionUsageEffortUnknown,
                        sessionModelName = if (modelEventRuntime == data.runtimeSessionId || current.holdsPickerEchoFor(data.runtimeSessionId)) current.sessionModelName else SessionModelLabelPolicy.modelFromInfo(data.info),

                sessionReasoningEffort = if (modelEventRuntime == data.runtimeSessionId || current.holdsPickerEchoFor(data.runtimeSessionId)) current.sessionReasoningEffort else SessionModelLabelPolicy.reasoningEffortFromInfo(data.info)
            ).acceptYolo(data.info["yolo"] as? Boolean)
        }
        reconcileCompressionFromResume(data)
        lastReportedSessionModel = _uiState.value.sessionModelName
        lastReportedSessionEffort = _uiState.value.sessionReasoningEffort
        oldestRenderedHistoryRow = data.messages.firstOrNull()
        restoreUnknownEdit(data)
        replaceRemoteSnapshotBestEffort(data)
        projectCachedToolTrace(data.durableSessionId)
        reconcileQueueAfterHistory(data, currentRefresh)
        if (!currentRefresh()) return
        if (chatQueue?.state?.value?.active?.state != QueueItemState.UNKNOWN) {
            coordinator?.reconcilePendingTurnWithHistory(data.durableSessionId, data.messages)?.let { outcome ->
                if (outcome is ReconciliationOutcome.ProvenByHistory) {
                    _uiState.update { it.copy(pendingUnknownTurnId = null, submissionStatus = SubmissionStatus.IDLE) }
                }
            }
        }
    }

    /**
     * Serve a snapshot into presentation state, optionally as a quiet preview
     * while the same scoped operation reads host history. Preview keeps the
     * loading/action gate armed and requires its caller's publication guard.
     * Returns false when nothing readable — the caller falls through to the
     * live resume. Invariants: runtimeSessionId is NEVER restored from cache
     * (stays null), submitBlockedReason stays null (never NO_RUNTIME on this
     * path — RISK-03/OQ-03), and rendering reuses the SAME history mapper as
     * live hydration, so a snapshot looks exactly like history.
     */
    private suspend fun serveConversationSnapshotIfPresent(durableId: String,
        expected: com.hermes.bot.data.local.AuthCredentialLease? = null, selectedContext: String? = null,
        preview: Boolean = false,
        readOwner: com.hermes.bot.data.local.ConversationCacheReadOwner? = null,
        current: suspend () -> Boolean = { true }): Boolean {
        val cacheRepo = conversationCacheRepository ?: return false
        if (!current()) return false
        val localOwner = readOwner ?: if (expected == null && chatPreviewRepository != null) {
            try { cacheRepo.captureReadOwner() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        if (expected == null && chatPreviewRepository != null && localOwner == null) return false
        val snapshotCache = try {
            when (val read = if (localOwner != null) cacheRepo.loadForReadOwner(durableId, localOwner)
                else if (expected == null) cacheRepo.load(durableId)
                else cacheRepo.loadScoped(durableId, expected, selectedContext)) {
                is ConversationCacheReadResult.Snapshot -> read.value
                else -> return false
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return false
        }
        if (!current()) return false
        val messages = snapshotCache.rows.map { row ->
            SessionMessage(
                role = row.role,
                content = row.content,
                toolName = row.toolName,
                toolContext = row.toolContext,
                reasoning = row.reasoning,
                createdAtMillis = row.createdAtMillis
            )
        }
        var skippedBlanks = 0
        val snapshotEntries = messages
            .mapIndexed { index, msg ->
                mapHistoryMessageToEntries(msg, index, prefix = "hist") { skippedBlanks++ }
            }
            .flatten()
        if (preview && snapshotEntries.isEmpty()) return false
        // Plan §6 title chain (repair finding 3): state title → ACTIVE-HANDLE
        // title → friendly label — resolved OUTSIDE update{} because it suspends.
        val activeHandleTitle = try {
            sessionRepository?.getActiveSession()?.takeIf { it.storedSessionId == durableId }?.title
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }?.takeUnless { it.isBlank() || it == UNTITLED_CONVERSATION_HEADER_LABEL }
        if (!current() || (localOwner != null && !cacheRepo.isReadOwnerCurrent(localOwner)) || !current()) return false
        skippedHistoryCount = skippedBlanks
        oldestRenderedHistoryRow = messages.firstOrNull()
        val publishSnapshot = {
            _uiState.update { current ->
                val existingRealTitle = current.sessionTitle?.takeUnless {
                    it.isBlank() || it == UNTITLED_CONVERSATION_HEADER_LABEL
                }
                current.copy(
                    durableSessionId = snapshotCache.durableSessionId,
                    // PR-3 hard invariant: a cached runtime ID is NEVER restored.
                    runtimeSessionId = null,
                    sessionTitle = existingRealTitle ?: activeHandleTitle ?: chatHeaderFallbackTitle(sessionId),
                    timeline = snapshotEntries,
                    taskProgress = TaskProgressState(),
                    // PR-3 (§6): strip flag carries the capture time while the
                    // surface renders this offline copy.
                    savedSnapshotCapturedAtMillis = snapshotCache.capturedAtMillis,
                    isCachedHistoryPreview = preview,
                    hasAcceptedStoredHistory = false,
                    // PR-4 (§6): fallback/cache-first projection — the
                    // phone-captured group renders with the served rows from
                    // the SAME load (one read, one atomic update).
                    cachedToolTrace = ExecutionTracePolicy.buildCachedToolTraceEntryList(
                        snapshotCache.localTrace
                    ),
                    isInitialLoading = preview,
                    errorMessage = null,
                    // Never NO_RUNTIME on the snapshot path (PR-7 lane untouched).
                    submitBlockedReason = null,
                    trace = null,
                    historyBeginState = if (snapshotCache.messagesOmitted) {
                        ChatUiState.HistoryBeginState.NON_EXHAUSTIVE
                    } else {
                        ChatUiState.HistoryBeginState.REACHED
                    }
                )
            }
        }
        if (localOwner != null && chatPreviewRepository != null) {
            if (!chatPreviewRepository.withOwner(localOwner) { publishSnapshot(); memoryPreviewOwner = localOwner }) return false
            chatPreviewRepository.remember(localOwner, durableId, snapshotEntries, snapshotCache.capturedAtMillis,
                _uiState.value.sessionTitle, snapshotCache.messagesOmitted)
        } else publishSnapshot()
        return true
    }

    private fun preserveMemoryPreviewAfterFailure(message: String): Boolean {
        val owner = memoryPreviewOwner ?: return false
        val memory = chatPreviewRepository ?: return false
        if (_uiState.value.savedSnapshotCapturedAtMillis == null || _uiState.value.runtimeSessionId != null) return false
        return memory.withOwner(owner) {
            _uiState.update { it.copy(isInitialLoading = false, isCachedHistoryPreview = false,
                errorMessage = message, submitBlockedReason = null) }
        }
    }

    private fun publishMemoryPreview(snapshot: ChatPreviewSnapshot) {
        memoryPreviewOwner = snapshot.owner
        capturedPreviewOwner = snapshot.owner
        _uiState.update { state -> state.copy(timeline = snapshot.timeline, runtimeSessionId = null,
            sessionTitle = state.sessionTitle ?: snapshot.title,
            savedSnapshotCapturedAtMillis = snapshot.capturedAtMillis,
            isCachedHistoryPreview = gatewayProtocolAdapter?.connectionStatus?.value == ConnectionStatus.CONNECTED,
            isInitialLoading = true, hasAcceptedStoredHistory = false,
            historyBeginState = if (snapshot.messagesOmitted) ChatUiState.HistoryBeginState.NON_EXHAUSTIVE
                else ChatUiState.HistoryBeginState.REACHED) }
    }

    private suspend fun retainHostPreview(durableId: String, entries: List<TimelineEntry>, omitted: Boolean,
        current: suspend () -> Boolean) {
        val memory = chatPreviewRepository ?: return
        // The caller just published accepted host evidence on Main. Reuse validated local
        // authority synchronously, so Back cannot cancel retention behind ancillary IO.
        val readyOwner = capturedPreviewOwner ?: memoryPreviewOwner
        if (readyOwner != null && memory.remember(readyOwner, durableId, entries, clock(),
                _uiState.value.sessionTitle, omitted)) return
        try {
            val owner = conversationCacheRepository?.captureReadOwner() ?: return
            if (!current()) return
            memory.remember(owner, durableId, entries, clock(), _uiState.value.sessionTitle, omitted)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retention failure cannot disturb accepted host history. */ }
    }

    /**
     * PR-3 (REQ-10, §5.1): a successful remote resume atomically REPLACES the
     * stored snapshot via `replaceRemoteSnapshot(storedSessionId, resume,
     * nowMillis)` — Hermes authoritative. Best effort: guard hits are Skipped
     * and I/O failures map to Failed; neither may disturb the hydrated UI.
     */
    private suspend fun replaceRemoteSnapshotBestEffort(
        resume: SessionResumeResult,
        expected: com.hermes.bot.data.local.AuthCredentialLease? = null,
        selectedContext: String? = null
    ) {
        val cacheRepo = conversationCacheRepository ?: return
        try {
            val sanitized = resume.withoutToolPayloads()
            if (expected == null) cacheRepo.replaceRemoteSnapshot(resume.durableSessionId, sanitized, clock())
            else cacheRepo.replaceRemoteSnapshotScoped(resume.durableSessionId, sanitized, clock(), expected, selectedContext)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Capture failure never disturbs the already-hydrated UI.
        }
    }

    /**
     * PR-4 (plan CACHE-PR4 §6): project the phone-captured trace from the
     * JUST-LOADED snapshot into UI state (hydration, §5.2 order). Best-effort
     * by construction — an empty trace is the default, so a failure here
     * changes nothing (CON-01: replace-on-resume never deletes it, so this
     * projection is the same trace that survived the resume).
     */
    private suspend fun projectCachedToolTrace(
        storedSessionId: String,
        expected: com.hermes.bot.data.local.AuthCredentialLease? = null,
        selectedContext: String? = null,
        current: suspend () -> Boolean = { true }
    ) {
        if (storedSessionId.isBlank()) return
        val read = try {
            val cacheRepo = conversationCacheRepository ?: return
            if (expected == null) cacheRepo.load(storedSessionId)
            else cacheRepo.loadScoped(storedSessionId, expected, selectedContext)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Cache unavailable ⇒ no group (never blocks hydration).
            return
        }
        val trace = (read as? ConversationCacheReadResult.Snapshot)?.value?.localTrace
            ?: return
        if (!current()) return
        _uiState.update { it.copy(cachedToolTrace = ExecutionTracePolicy.buildCachedToolTraceEntryList(trace)) }
    }

    /**
     * PR-4 (plan CACHE-PR4 REQ-03): the four observation points call this
     * with the ALREADY-ADMITTED event (the collector guard above guarantees
     * `currentRuntime` is an exact runtime match — no re-validation, no new
     * authorization). Fire-and-forget best effort: a cache failure never
     * disturbs the reducer (the tool activity still renders live).
     */
    private fun recordObservedToolEventOnDevice(
        event: GatewayEvent,
        runtimeSessionId: String?
    ) {
        if (runtimeSessionId.isNullOrBlank()) return
        val repository = conversationCacheRepository ?: return
        val stored = currentDurableSessionId() ?: return
        if (stored.isBlank()) return
        val now = clock()
        viewModelScope.launch {
            try {
                val outcome = repository.recordObservedToolEvent(
                    stored, runtimeSessionId, event, now
                )
                if (outcome is ConversationCacheWriteResult.Written) {
                    // The just-written event becomes visible immediately: one
                    // read, one atomic group update — the live tool row keeps
                    // rendering from the reducer either way.
                    projectCachedToolTrace(stored)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Capture failure is never surfaced — the trace is advisory
                // evidence only and never authorizes anything (SEC-06).
            }
        }
    }

    private var taskCollapseJob: Job? = null

    fun toggleTaskProgress() {
        taskCollapseJob?.cancel()
        _uiState.update { it.copy(taskProgress = it.taskProgress.copy(expanded = !it.taskProgress.expanded)) }
    }

    private fun acceptTaskSnapshot(snapshot: com.hermes.bot.domain.TaskSnapshot?) {
        val previous = _uiState.value.taskProgress
        val accepted = previous.accept(snapshot)
        val next = if (_uiState.value.headerConnectionStatus == ConnectionStatus.CONNECTED) accepted else accepted.copy(live = false)
        if (next == previous) return
        taskCollapseJob?.cancel()
        _uiState.update { it.copy(taskProgress = next) }
        if (snapshot != null && snapshot.items.isNotEmpty() && !snapshot.hasOpenTasks) {
            val runtime = _uiState.value.runtimeSessionId
            taskCollapseJob = viewModelScope.launch {
                kotlinx.coroutines.delay(4_000)
                if (_uiState.value.runtimeSessionId == runtime && _uiState.value.taskProgress.snapshot == snapshot) {
                    _uiState.update { it.copy(taskProgress = it.taskProgress.copy(expanded = false, live = false)) }
                }
            }
        }
    }

    private fun settleTaskProgress() {
        _uiState.update { it.copy(taskProgress = it.taskProgress.settle()) }
    }

    private fun beginTaskTurn() {
        taskCollapseJob?.cancel()
        _uiState.update { it.copy(taskProgress = it.taskProgress.beginTurn()) }
    }

    private fun observeGatewayEvents() {
        val adapter = gatewayProtocolAdapter ?: return

        // CHAT-T7 (B-4/B-5): stale restore-banner self-heal. When the chat
        // surface is blocked on NO_RUNTIME and the gateway adapter's
        // connection status takes an EDGE to CONNECTED (never the initial
        // emission, never a repeated CONNECTED value), resume exactly once —
        // the same loadSession() the banner's Restore button invokes, so the
        // banner clears via the existing success path. No new protocol
        // frames, no auto-submit (loadSession never touches composerDraft);
        // a failed self-heal resume leaves NO_RUNTIME set (the manual path
        // remains the fallback).
        var lastObservedConnectionStatus: ConnectionStatus? = null
        viewModelScope.launch {
            adapter.connectionStatus.collect { status ->
                if (status != ConnectionStatus.CONNECTED) {
                    cancelDictation()
                    dismissSlashSuggestions()
                    cancelSlashExecution()
                    pickerCatalogRevision++
                    pickerRevision++
                    pickerConfirmationJob?.cancel()
                    _uiState.update { it.copy(pickerOptions = emptyList(), pickerCatalogLoading = false,
                        pickerChange = it.activePickerChange?.let { change ->
                            if (change.phase == PickerChange.Phase.DEFERRED) change
                            else change.copy(phase = PickerChange.Phase.UNCONFIRMED)
                        }) }
                    pickerPendingRuntimeId = null
                    pickerPendingTurnCompleted = false
                    pickerRequestOwner = null
                    pickerRequestRuntimeId = null
                }
                if (status != ConnectionStatus.CONNECTED && _uiState.value.edit != null) {
                    editGeneration++
                    editJob?.cancel()
                    editAccess = null
                    _uiState.update { it.copy(edit = it.edit?.copy(phase = null, targetReady = false,
                        error = "Connection changed. Cancel and refresh before editing again.")) }
                }
                _uiState.update { it.copy(headerConnectionStatus = status,
                    taskProgress = if (status == ConnectionStatus.CONNECTED) it.taskProgress else it.taskProgress.copy(live = false),
                    sessionYolo = if (status == ConnectionStatus.CONNECTED) it.sessionYolo else null,
                    pickerConnected = status == ConnectionStatus.CONNECTED,
                    isAttachmentConnectionAvailable = status == ConnectionStatus.CONNECTED,
                    pickerNotice = if (status == ConnectionStatus.CONNECTED) it.pickerNotice else null,
                    pickerPendingNotice = if (status == ConnectionStatus.CONNECTED) it.pickerPendingNotice else null,
                    pickerBusy = if (status == ConnectionStatus.CONNECTED) it.pickerBusy else false) }
                val previous = lastObservedConnectionStatus
                lastObservedConnectionStatus = status
                val isEdgeToConnected = previous != null &&
                    previous != ConnectionStatus.CONNECTED &&
                    status == ConnectionStatus.CONNECTED
                if (isEdgeToConnected && _uiState.value.yoloUnconfirmed &&
                    !_uiState.value.isRefreshing && !_uiState.value.isInitialLoading) refreshSession()
                if (isEdgeToConnected && _uiState.value.pendingImages.isNotEmpty()) {
                    attachmentController.verifyImageCountAfterReconnect()
                }
                if (isEdgeToConnected &&
                    (storedHistoryScope != null || _uiState.value.submitBlockedReason == SubmitBlockedReason.NO_RUNTIME ||
                        _uiState.value.queue.pause == QueuePause.DISCONNECTED) &&
                    sessionRepository != null &&
                    sessionId != "new-session"
                ) {
                    startHistoryRead(reconnectReplace = storedHistoryScope != null &&
                        _uiState.value.savedSnapshotCapturedAtMillis != null,
                        preserveAttachments = true, recovery = true)
                }
                // PR-7 (REQ-01): second consumer of the SAME edge — while
                // PR-3's labelled offline snapshot is on screen (and the
                // CHAT-T7 lane above does not own the edge), replace the
                // cached projection with remote truth exactly once per edge:
                // never on the first observation (previous == null ⇒ no
                // edge), never while a load is in flight (single-flight via
                // isInitialLoading), never twice for one edge — mutual
                // exclusion with the lane above: PR-3's render rules keep the
                // strip and NO_RUNTIME distinct, and this lane additionally
                // requires a non-NO_RUNTIME blocked reason, so at most one
                // loadSession runs per edge. Stored session with a repository
                // only. The load keeps the strip flag set across entry
                // (loadSession(reconnectReplace = true)) so a failed replace
                // can never present cached rows unlabelled; success clears
                // the flag and re-arms the composer. This lane never
                // authorizes anything: trace and snapshot authorization are
                // untouched (REQ-04).
                if (isEdgeToConnected &&
                    _uiState.value.savedSnapshotCapturedAtMillis != null &&
                    _uiState.value.submitBlockedReason != SubmitBlockedReason.NO_RUNTIME &&
                    !_uiState.value.isInitialLoading &&
                    sessionRepository != null &&
                    sessionId != "new-session"
                ) {
                    startHistoryRead(reconnectReplace = true, preserveAttachments = true, recovery = true)
                }
            }
        }

        // Observe history fallback signals requiring authoritative rehydration
        viewModelScope.launch {
            adapter.historyFallbackSignals.collect { signal ->
                val runtime = signal.runtimeSessionId?.takeIf { it.isNotBlank() }
                val durable = signal.durableSessionId?.takeIf { it.isNotBlank() }
                val matching = if (runtime != null) runtime == _uiState.value.runtimeSessionId
                    else storedHistoryScope?.current == true && durable != null &&
                        durable == _uiState.value.durableSessionId
                if (matching && _uiState.value.edit?.busy != true) startHistoryRead(preserveAttachments = true, recovery = true, externalGap = true)
            }
        }

        viewModelScope.launch {
            adapter.events.collect { event ->
                if (_uiState.value.edit?.busy == true) return@collect
                val currentRuntime = _uiState.value.runtimeSessionId

                // BLD-006 S4 (D-01/SEC-001, TASK-017): exact fresh-runtime-ID-
                // only admission. A session-scoped event mutates the timeline
                // only when it carries a NONBLANK session_id exactly equal to
                // the currently attached fresh runtime session ID. Durable IDs,
                // stale/other/null/blank IDs never mutate the transcript. (The
                // S1 guard rejected non-equal IDs; this also closes the
                // blank-vs-null edge and documents the nonblank requirement.)
                val eventSessionId = event.sessionId
                if (eventSessionId.isNullOrBlank() || eventSessionId != currentRuntime) {
                    return@collect
                }

                if (storedHistoryScope != null && activeHistoryEventAdmission == null && historyLoadJob?.isActive == true) return@collect
                if (remoteActiveGeneration != null && remoteActiveGeneration != adapter.connectionGeneration) return@collect
                if (activeHistoryEventAdmission?.invoke(event) == false) return@collect
                if (_uiState.value.isCompressionBusy) {
                    try {
                        currentDurableSessionId()?.let { stored ->
                            contextUsageRepository?.admitEvent(stored, event)?.let { handleCompressionSignal(it) }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        // A feature read/authority failure must not kill the existing transcript collector.
                        _uiState.update { it.copy(compressionPhase = CompressionPhase.UNKNOWN,
                            compressionNotice = "Compression outcome unknown. Refresh to check server history.") }
                    }
                }
                // The writer acknowledgement can lag the terminal event. Keep one
                // admitted-runtime terminal in memory until the pending ledger settles;
                // ambiguity still holds the queue and never authorizes another write.
                if (promptAwaitingAdmission && _uiState.value.isSubmitting && activeAssistantEntryId == null &&
                    (event is GatewayEvent.MessageComplete || event is GatewayEvent.Error)) {
                    if (queuedPromptTerminal == null) queuedPromptTerminal = event
                    return@collect
                }
                when (event) {
                    is GatewayEvent.MessageStart -> beginTaskTurn()
                    is GatewayEvent.TasksUpdated -> acceptTaskSnapshot(event.snapshot)
                    is GatewayEvent.MessageDelta -> {
                        applyMessageDelta(event.text)
                    }

                    is GatewayEvent.MessageInterim -> {
                        // CHAT-018 PR-A (REQ-005): interim text is agent
                        // commentary — it routes into the active turn's tool
                        // activity card as an [AgentNoteEntry], never into the
                        // reply bubble (owner-locked: notes are NOT bubbles in
                        // any turn state). `already_streamed` dedups
                        // (REQ-005): replayed interim text was already shown
                        // once, so the event is a full no-op. Without an
                        // attributable active admitted local turn, the interim
                        // is silently dropped (plan
                        // ui-remove-unattributed-activity-notices-v1,
                        // owner-locked): no GenericGatewayActivity row is
                        // appended and the timeline does not grow — D-01 holds
                        // in its stronger form ("renders nothing"; never turn
                        // proof, never a bubble). applyMessageDelta /
                        // finalizeAssistantTurn stay byte-identical: the reply
                        // bubble path never sees interim text (REQ-004).
                        when {
                            event.alreadyStreamed == true -> Unit
                            activeAssistantEntryId != null -> applyInterimNote(event.text)
                            else -> Unit
                        }
                    }

                    is GatewayEvent.MessageComplete -> {
                        settleTaskProgress()
                        if (pickerPendingRuntimeId == currentRuntime) pickerPendingTurnCompleted = true
                        // BLD-006 S4 (D-03, TASK-017): terminal status mapping
                        // after Locale.ROOT lowercase+trim. Only the exact
                        // certified values map to their states; absent, blank,
                        // and uncertified synonyms map to UNKNOWN — never
                        // fabricated to COMPLETE.
                        val terminalState = when (event.status?.trim()?.lowercase(Locale.ROOT)) {
                            "complete" -> AssistantMessageState.COMPLETE
                            "interrupted" -> AssistantMessageState.INTERRUPTED
                            "error" -> AssistantMessageState.FAILED
                            else -> AssistantMessageState.UNKNOWN
                        }
                        // T2 stop-vs-completion audit decision (transcript
                        // truthfulness). (a) stopStreaming() has no suspension
                        // point between its guard and its local finalize, so a
                        // terminal can only land fully BEFORE the stop — the
                        // guard then no-ops and the row keeps its COMPLETE
                        // truth (already truthful, no change) — or fully AFTER
                        // it, which is this branch. (b) Before T2, a late
                        // certified "complete" hit finalizeAssistantTurn's
                        // null-entry path: it was dropped to a bounded generic
                        // notice while the sealed row kept claiming
                        // INTERRUPTED — contradicting the host's authoritative
                        // outcome and discarding MessageComplete text
                        // (authoritative per finalizeAssistantTurn's KDoc).
                        // That was the gap. (c) Decision: the FIRST terminal
                        // for the just-stopped turn (stoppedTurnMarker set,
                        // no newly admitted turn active) is reconciled
                        // one-shot — status "complete" upgrades the sealed
                        // INTERRUPTED row to COMPLETE with the authoritative
                        // terminal text via [upgradeStoppedTurnToComplete];
                        // every other status, and every terminal without a
                        // marker, keeps the pre-T2 bounded generic notice
                        // unchanged. Correlation limit: message.complete
                        // carries no turn id, so the marker plus the exact
                        // runtime-ID admission guard above is the identity
                        // evidence; if the sealed row is gone (hydration
                        // replaced it), the upgrade declines and the generic
                        // notice records the terminal as before.
                        val marker = stoppedTurnMarker
                        if (marker != null && activeAssistantEntryId == null) {
                            stoppedTurnMarker = null
                            if (terminalState == AssistantMessageState.COMPLETE &&
                                upgradeStoppedTurnToComplete(marker, event)
                            ) {
                                return@collect
                            }
                            // Non-complete status, or the sealed row is gone:
                            // fall through — finalize's null-entry path records
                            // the bounded generic notice exactly as before.
                        }
                        finalizeAssistantTurn(
                            terminalState,
                            terminalText = event.text,
                            usage = event.usage,
                            finalReasoning = event.reasoning
                        )
                    }

                    is GatewayEvent.Error -> {
                        settleTaskProgress()
                        // SEC-004: the event message is already redacted at the
                        // gateway boundary; the reducer keeps it as the typed
                        // failure detail shown by the FAILED chip.
                        finalizeAssistantTurn(
                            AssistantMessageState.FAILED,
                            errorMessage = event.message
                        )
                    }

                    is GatewayEvent.ToolGenerating -> {
                        // PR-4 (REQ-03): observation point 1 — exact runtime
                        // admission already held (collector guard above).
                        recordObservedToolEventOnDevice(event, currentRuntime)
                        addToolPreparing(event.name)
                    }

                    is GatewayEvent.ToolStart -> {
                        // PR-4 (REQ-03): observation point 2.
                        recordObservedToolEventOnDevice(event, currentRuntime)
                        addOrUpdateToolStart(
                            toolId = event.toolId,
                            name = event.name,
                            context = event.context,
                            args = event.args
                        )
                    }

                    is GatewayEvent.ApprovalCancelled -> Unit
                    is GatewayEvent.SubagentUpdate -> { subagentController?.admit(event) }
                    is GatewayEvent.ToolProgress -> {
                        // PR-4 (REQ-03): observation point 3 — safe
                        // progressive tool feedback (type/payload never persisted).
                        recordObservedToolEventOnDevice(event, currentRuntime)
                        addToolProgress(event.type, event.payload)
                    }

                    is GatewayEvent.ReasoningDelta -> {
                        // F007-S2: live reasoning accumulates into one bounded
                        // collapsed block attached to the active admitted turn.
                        applyReasoningDelta(event.kind, event.text)
                    }

                    is GatewayEvent.ToolComplete -> {
                        if (event.name == "todo_list" || event.name == "todo") acceptTaskSnapshot(event.taskSnapshot)
                        // PR-4 (REQ-03): observation point 4 — the terminal
                        // observation (status COMPLETE is derived HERE, never
                        // fabricated elsewhere).
                        recordObservedToolEventOnDevice(event, currentRuntime)
                        finalizeToolComplete(
                            toolId = event.toolId,
                            name = event.name,
                            duration = event.durationSeconds,
                            result = event.result ?: event.summary,
                            args = event.args
                        )
                    }

                    is GatewayEvent.SessionUsageUpdated -> {
                        try { applyUsageEvent(event) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            // A metadata authority failure must not stop transcript collection.
                            clearLiveUsage()
                        }
                    }

                    is GatewayEvent.SessionTitle -> {
                        _uiState.update { it.copy(sessionTitle = event.title) }
                    }

                    is GatewayEvent.SessionInfo -> {
                        val infoPickerRevision = pickerRevision
                        val infoExecution = slashExecution
                        val infoHistory = historyGeneration
                        val commands = slashCommands
                        if (commands != null) {
                            val owner = currentDurableSessionId()?.let { commands.capture(it, currentRuntime) }
                            if (owner == null || !commands.isCurrent(owner) ||
                                _uiState.value.runtimeSessionId != currentRuntime) return@collect
                        }
                        if (infoPickerRevision != pickerRevision) return@collect
                        val pickerOwner = pickerAccess
                        if (_uiState.value.activePickerChange != null && pickerOwner != null && !pickerOwner.isCurrent()) return@collect
                        if (infoPickerRevision != pickerRevision) return@collect
                        pickerObservationRevision++
                        pickerObservedInfo = event.info
                        // CHAT-010: session-scoped, non-authoritative label
                        // update (frozen contract line: update non-authoritative
                        // labels only; never replace transcript state). The
                        // collector's exact fresh-runtime-ID guard above has
                        // already scoped this event to the attached session,
                        // so the payload is for THIS session. Model identity
                        // refreshes from the event's info map; blank host
                        // values read as null (never fabricated).
                        val holdPickerEcho = _uiState.value.activePickerChange?.holdsUnappliedEcho == true
                        val reportedModel = if (holdPickerEcho) null else SessionModelLabelPolicy.modelFromInfo(event.info)
                        val hasEffortReport = !holdPickerEcho && (reportedModel != null || event.info.containsKey("reasoning_effort"))
                        if (reportedModel != null || hasEffortReport) modelEventRuntime = currentRuntime
                        val newModel = reportedModel ?: _uiState.value.sessionModelName
                        val newEffort = if (hasEffortReport) SessionModelLabelPolicy.reasoningEffortFromInfo(event.info)
                            else _uiState.value.sessionReasoningEffort
                        // CHAT-017 (TASK-003): capture the previous report,
                        // record the new one, then diff via the pure policy.
                        // The first report for the session (or after a
                        // loadSession reset) has no previous to differ from —
                        // never a notice (REQ-004); an identical repeat report
                        // fires nothing (REQ-006).
                        val previousModel = lastReportedSessionModel
                        val previousEffort = lastReportedSessionEffort
                        lastReportedSessionModel = newModel
                        lastReportedSessionEffort = newEffort
                        _uiState.update { current ->
                            current.copy(
                                sessionModelName = newModel,

                                sessionUsageEffortUnknown = if (hasEffortReport) false else current.sessionUsageEffortUnknown,
                                sessionReasoningEffort = newEffort
                            ).let { if (current.yoloPending || current.isRefreshing || infoExecution != slashExecution ||
                                infoHistory != historyGeneration) it else it.acceptYolo(
                                if (adapter.connectionStatus.value == ConnectionStatus.CONNECTED) event.info["yolo"] as? Boolean else null) }
                        }

                        reconcilePicker(event.info, infoPickerRevision)
                        // CHAT-017 v2 (REQ-001): a changed report becomes a
                        // standalone notice row appended to the timeline
                        // IMMEDIATELY at ack time — no pending hold and no
                        // attachment to any reply (owner-directed redesign of
                        // v1). One row per qualifying SessionInfo event
                        // (REQ-005); the row is live-only (REQ-004).
                        val changeNotice = ModelChangeNoticePolicy.diff(
                            previousModel = previousModel,
                            previousEffort = previousEffort,
                            newModel = newModel,
                            newEffort = newEffort
                        )
                        if (changeNotice != null) {
                            addModelChangeNotice(changeNotice)
                        }

                        // CHAT-011 S2 (TASK-007): the second and final seed
                        // trigger. The collector's exact runtime-ID guard
                        // (above) has already scoped this event to the
                        // attached session, and the seed function owns every
                        // other guard (cache-miss + connected + echo model).
                        seedProfileModelDefaultIfMissing(reportedModel)
                    }

                    is GatewayEvent.Unknown -> {
                        // BLD-006 S4 (D-01): unsolicited session-scoped
                        // activity that cannot be attributed to an active
                        // admitted local turn stays a bounded generic notice —
                        // never local-turn proof.
                        addGenericActivity(
                            eventType = event.type,
                            summary = "Inbound Hermes activity: ${event.type}",
                            payload = event.payload
                        )
                    }

                    else -> {
                        // Other events (Ready, SessionsChanged) do not render in
                        // timeline. (CHAT-010: SessionInfo moved to its own
                        // label-update branch above.)
                    }
                }
            }
        }
    }

    fun submitPrompt() {
        channelAutoSend?.cancel()
        channelAutoSend = null
        if (_uiState.value.edit != null) { resendEditedMessage(); return }
        if (_uiState.value.unknownEditId != null) return
        if (_uiState.value.voice.isBusy) return
        val state = _uiState.value
        val command = com.hermes.bot.data.commands.SlashCommandPolicy.parse(state.composerDraft)
        if (slashCommands != null && command == null && com.hermes.bot.data.commands.SlashCommandPolicy.query(state.composerDraft) != null) {
            slashNotice("/", "Choose or type a command before sending.")
            return
        }
        if (slashCommands != null && command != null && state.pendingImages.isEmpty() && state.pendingFiles.isEmpty()) {
            val immediate = command.name in setOf("stop", "new", "reset", "clear", "resume", "sessions", "switch") ||
                (command.name in setOf("model", "reasoning") && command.argument.isBlank())
            if (chatQueue != null && !immediate) enqueueTextOrCommand(command) else {
                if (chatQueue != null && command.name != "stop") pauseQueue()
                executeSlash(command)
            }
            return
        }
        if (state.shouldQueuePrompt && state.pendingImages.isEmpty() && state.pendingFiles.isEmpty()) {
            enqueueTextOrCommand(null)
            return
        }
        if (chatQueue != null && !state.isComposerReady) return
        submitPlainPrompt()
    }

    private fun submitPlainPrompt(promptOverride: String? = null, displayText: String? = null, clearDraft: Boolean = true) {
        if (_uiState.value.edit != null || _uiState.value.unknownEditId != null) return
        channelAutoSend?.cancel()
        channelAutoSend = null
        val stateAtSend = _uiState.value
        if (chatQueue != null && (stateAtSend.pendingImages.isNotEmpty() || stateAtSend.pendingFiles.isNotEmpty()) &&
            !stateAtSend.canAttachImage) return
        val draft = promptOverride ?: stateAtSend.composerDraft.trim()
        val submittedText = buildAttachmentPrompt(draft, stateAtSend.pendingImages, stateAtSend.pendingFiles)
        // BLD-006 S3 single-flight: the guard covers BOTH the submitting window
        // and the local-turn streaming window, so a second Send/IME Send while
        // an admitted turn is live is a no-op (no frame, no row, no draft clear).
        //
        // This synchronous writer guard preserves single flight. Queued intent
        // also validates captured authority after ledger IO, so owner revocation
        // can return ValidationFailed without a frame. Definite rejection removes
        // the optimistic row and restores only a direct-send draft; accepted
        // queue intent remains held in its existing entry.
        if (stateAtSend.unknownEditId != null || stateAtSend.edit != null || submittedText.isBlank() || stateAtSend.isInitialLoading || stateAtSend.isRefreshing ||
            stateAtSend.isAttachingImage || stateAtSend.isAttachingFile ||
            stateAtSend.isSubmitting || stateAtSend.isStreaming || stateAtSend.slashBusy || stateAtSend.isCompressionBusy ||
            stateAtSend.savedSnapshotCapturedAtMillis != null) return

        // CHG-003-S3 pre-mutation runtime guard: validate BEFORE any mutation
        // (no draft clear, no SUBMITTING, no bubble, no ledger, no coordinator call).
        val runtimeId = stateAtSend.runtimeSessionId
        if (runtimeId.isNullOrBlank()) {
            _uiState.update {
                it.copy(submitBlockedReason = SubmitBlockedReason.NO_RUNTIME)
            }
            return
        }
        if (stateAtSend.submitBlockedReason != null || !stateAtSend.isAttachmentConnectionAvailable) return

        queuedPromptTerminal = null
        promptAwaitingAdmission = true
        if (clearDraft) clearPersistedDraftAtSendPoint()
        val draftGenerationAtSend = currentDraftGeneration()
        _uiState.update {
            it.copy(
                isSubmitting = true,
                submissionStatus = SubmissionStatus.SUBMITTING,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val storedId = _uiState.value.durableSessionId ?: sessionId
            val effectiveRuntimeSession = runtimeId

            val coord = coordinator
            if (coord != null) {
                // Optimistic user bubble marked SENDING
                val tempId = "turn-${UUID.randomUUID()}"
                val userEntry = UserMessage(
                    id = tempId,
                    text = displayText ?: submittedText,
                    state = UserMessageState.SENDING,
                    sourceKind = ActiveSourceKind.LOCAL_USER
                )
                _uiState.update { current ->
                    current.copy(timeline = current.timeline + userEntry)
                }

                val queueAttempt = queuedExecutionId.takeIf { queueTurnCompletion != null }
                val outcome = try { coord.submitPrompt(storedId, effectiveRuntimeSession, submittedText,
                    queueCurrent = queueAttempt?.let { id -> suspend {
                        chatQueue?.canContinue(id) == true && !historyDisposed && queueForeground && !queueRequiresIdle &&
                            currentDurableSessionId() == storedId && _uiState.value.runtimeSessionId == effectiveRuntimeSession &&
                            _uiState.value.isAttachmentConnectionAvailable &&
                            _uiState.value.queue.pause !in setOf(QueuePause.DISCONNECTED, QueuePause.BACKGROUND, QueuePause.STOPPED)
                    } }) }
                catch (cancelled: CancellationException) { promptAwaitingAdmission = false; queueTurnCompletion?.complete(QueueExecutionResult.UNKNOWN); throw cancelled }
                catch (_: Exception) {
                    promptAwaitingAdmission = false
                    if (queueTurnCompletion == null) throw IllegalStateException("Prompt preparation failed")
                    queueTurnCompletion?.complete(QueueExecutionResult.UNKNOWN)
                    _uiState.update { it.copy(isSubmitting = false, submissionStatus = SubmissionStatus.UNKNOWN,
                        errorMessage = "Prompt outcome unknown. Check history before continuing.") }
                    return@launch
                }
                promptAwaitingAdmission = false
                when (outcome) {
                    is TurnSubmissionOutcome.Admitted -> {
                        activeTurnId = outcome.pendingTurnId
                        // BLD-006 S4 (D-02): the assistant row ID is minted
                        // here, at submit time, and stays stable for the whole
                        // turn regardless of interleaved tool/generic rows.
                        val assistantEntryId = "asst-${outcome.pendingTurnId}"
                        activeAssistantEntryId = assistantEntryId
                        activeTurnBuffer = AssistantTurnStreamBuffer()
                        // T2: a fresh admitted turn supersedes any pending
                        // stopped-turn reconciliation (one-shot marker window).
                        stoppedTurnMarker = null
                        beginTaskTurn()
                        // BLD-006 S5 (TASK-021): one scheduler per admitted
                        // local turn, scoped to the ViewModel's lifetime.
                        mintDeltaPublicationScheduler(assistantEntryId)
                        updateUserMessageState(tempId, UserMessageState.SENT)
                        val provisionalAssistant = AssistantMessage(
                            id = assistantEntryId,
                            text = "",
                            state = AssistantMessageState.STREAMING,
                            sourceKind = ActiveSourceKind.STREAM
                        )
                        _uiState.update { current ->
                            current.copy(
                                timeline = current.timeline.map { if (it.id == tempId) (it as UserMessage).copy(id = outcome.pendingTurnId, editable =
                                    outcome.userRowId?.takeIf { stateAtSend.pendingImages.isEmpty() && stateAtSend.pendingFiles.isEmpty() &&
                                        com.hermes.bot.data.gateway.MessageEditPolicy.plainText(submittedText) }?.let { com.hermes.bot.data.gateway.EditableMessage(it) }) else it } + provisionalAssistant,
                                isSubmitting = false,
                                isStreaming = true,
                                pendingImages = emptyList(),
                                pendingFiles = emptyList(),
                                submissionStatus = SubmissionStatus.STREAMING,
                                // TRD1 slice 1 (TASK-003): turn-scoped trace
                                // starts at the confirmed admission (A2 wall
                                // clock). Presentation-only; no reducer logic
                                // reads it.
                                trace = TraceState(
                                    turnId = outcome.pendingTurnId,
                                    startedAtMillis = clock()
                                )
                            )
                        }
                        queuedExecutionId?.let { id -> viewModelScope.launch { chatQueue?.progress(id, QueueItemState.STREAMING, outcome.pendingTurnId) } }
                        publishStreaming(true)
                        val earlyTerminal = queuedPromptTerminal
                        queuedPromptTerminal = null
                        when (earlyTerminal) {
                            is GatewayEvent.MessageComplete -> {
                                settleTaskProgress()
                                finalizeAssistantTurn(
                                    when (earlyTerminal.status?.trim()?.lowercase(Locale.ROOT)) {
                                        "complete" -> AssistantMessageState.COMPLETE
                                        "interrupted" -> AssistantMessageState.INTERRUPTED
                                        "error" -> AssistantMessageState.FAILED
                                        else -> AssistantMessageState.UNKNOWN
                                    }, terminalText = earlyTerminal.text, usage = earlyTerminal.usage,
                                    finalReasoning = earlyTerminal.reasoning)
                            }
                            is GatewayEvent.Error -> {
                                settleTaskProgress()
                                finalizeAssistantTurn(AssistantMessageState.FAILED, errorMessage = earlyTerminal.message)
                            }
                            else -> Unit
                        }
                    }

                    is TurnSubmissionOutcome.AmbiguousUnknown -> {
                        queuedExecutionId?.let { id -> chatQueue?.progress(id, QueueItemState.UNKNOWN, outcome.pendingTurnId) }
                        queueTurnCompletion?.complete(QueueExecutionResult.UNKNOWN)
                        activeTurnId = outcome.pendingTurnId
                        // T2: a new prompt attempt begins here (same frozen
                        // invariant as the admitted-turn clear above), so the
                        // one-shot stopped-turn reconciliation window closes
                        // with it. Without this clear, the first subsequent
                        // terminal (message.complete carries no turn id) could
                        // consume the stale marker and write THIS attempt's
                        // authoritative text/COMPLETE state into the STOPPED
                        // turn's row. A late terminal for the stopped turn
                        // during the ambiguous window keeps the pre-T2
                        // bounded generic notice instead.
                        stoppedTurnMarker = null
                        updateUserMessageState(tempId, UserMessageState.UNKNOWN)
                        _uiState.update { current ->
                            current.copy(
                                timeline = current.timeline.map { if (it.id == tempId) (it as UserMessage).copy(id = outcome.pendingTurnId) else it },
                                isSubmitting = false,
                                isStreaming = false,
                                submissionStatus = SubmissionStatus.UNKNOWN,
                                pendingUnknownTurnId = outcome.pendingTurnId,
                                errorMessage = "Prompt submission status unknown. Please check history or send as a new prompt."
                            )
                        }
                        queuedPromptTerminal = null
                        if (queueAttempt == null && _uiState.value.queue.waiting.isNotEmpty()) {
                            queueRequiresIdle = true
                            chatQueue?.pause(QueuePause.UNKNOWN)
                        }
                        publishStreaming(false)
                    }

                    is TurnSubmissionOutcome.RejectedBusy, is TurnSubmissionOutcome.ValidationFailed -> {
                        queueTurnCompletion?.complete(QueueExecutionResult.FAILED)
                        if (queueAttempt == null && _uiState.value.queue.waiting.isNotEmpty()) {
                            queueRequiresIdle = true
                            chatQueue?.pause(QueuePause.FAILED)
                        }
                        // BLD-006 S3: a local rejection preserves the draft
                        // (in memory and in the vault) and leaves no stranded
                        // optimistic SENDING row.
                        //
                        // A direct send restores its trimmed draft if the user
                        // has not edited it. A queued send already owns durable
                        // intent and must not duplicate it into the composer.
                        _uiState.update { current ->
                            val withoutOptimisticRow =
                                current.timeline.filterNot { it.id == tempId }
                            val restoredDraft =
                                if (queueAttempt == null && current.composerDraft.isBlank() &&
                                    isCurrentDraftGeneration(draftGenerationAtSend)
                                ) draft else current.composerDraft
                            current.copy(
                                timeline = withoutOptimisticRow,
                                composerDraft = restoredDraft,
                                isSubmitting = false,
                                errorMessage = "Unable to submit prompt"
                            )
                        }
                        if (queueAttempt == null && isCurrentDraftGeneration(draftGenerationAtSend)) {
                            queueDraftOperation(draftGenerationAtSend) {
                                vaultStorage?.let { storage ->
                                    storage.saveDraft(
                                        Draft(
                                            storedSessionId = storedId,
                                            composerText = draft,
                                            profileLocalId = storage.getActiveProfileId()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                return@launch
            }

            promptAwaitingAdmission = false
            if (queueTurnCompletion != null) {
                queueTurnCompletion?.complete(QueueExecutionResult.FAILED)
                _uiState.update { it.copy(isSubmitting = false) }
                return@launch
            }
            // Fallback when coordinator is absent (e.g. standalone preview)
            val turnId = "turn-${UUID.randomUUID()}"
            val userEntry = UserMessage(
                id = turnId,
                text = displayText ?: submittedText,
                state = UserMessageState.SENT
            )
            _uiState.update { current ->
                current.copy(
                    timeline = current.timeline + userEntry,
                    isSubmitting = false,
                    submissionStatus = SubmissionStatus.IDLE
                )
            }
        }
    }

    /**
     * User-initiated explicit retry from an ambiguous UNKNOWN prompt.
     * Guarantees a fresh pendingTurnId attempt; never reuses the old attempt.
     */
    fun retryAmbiguousPrompt() {
        if (_uiState.value.queue.active != null || _uiState.value.queue.waiting.isNotEmpty()) {
            viewModelScope.launch { chatQueue?.notice("Review the uncertain queued item before creating a new attempt.") }
            return
        }
        if (_uiState.value.unknownEditId != null || _uiState.value.edit != null) return
        val oldTurnId = _uiState.value.pendingUnknownTurnId ?: return

        // CHAT-019 repair round 2 (PROD-003): every prompt-initiation path is
        // fenced while the manual-refresh window is open, mirroring the
        // submitPrompt() guard family. A retry launched mid-refresh would be
        // displaced by the authoritative timeline replacement in
        // applyManualRefreshSuccess(). This is a pure no-op fence: no state
        // mutation, no ledger write, no coordinator call — the UNKNOWN
        // affordance simply stays available until the refresh completes.
        if (_uiState.value.isRefreshing || _uiState.value.isSubmitting || _uiState.value.isStreaming) return

        // CHG-003-S3 pre-mutation runtime guard: same blocked condition as submitPrompt(),
        // before isSubmitting update, ledger mutation, or coordinator call.
        val runtimeId = _uiState.value.runtimeSessionId
        if (runtimeId.isNullOrBlank()) {
            _uiState.update {
                it.copy(submitBlockedReason = SubmitBlockedReason.NO_RUNTIME)
            }
            return
        }

        val coord = coordinator
        if (coord != null) {
            viewModelScope.launch {
                val effectiveRuntimeSession = runtimeId
                _uiState.update { it.copy(isSubmitting = true) }
                when (val outcome = coord.retryAmbiguousTurn(oldTurnId, effectiveRuntimeSession)) {
                    is TurnSubmissionOutcome.Admitted -> {
                        activeTurnId = outcome.pendingTurnId
                        // BLD-006 S4 (D-02): same stable-ID/buffer minting on
                        // the retry path.
                        val assistantEntryId = "asst-${outcome.pendingTurnId}"
                        activeAssistantEntryId = assistantEntryId
                        activeTurnBuffer = AssistantTurnStreamBuffer()
                        // T2: a fresh admitted turn supersedes any pending
                        // stopped-turn reconciliation (one-shot marker window).
                        stoppedTurnMarker = null
                        // BLD-006 S5 (TASK-021): same per-turn scheduler on
                        // the retry path.
                        beginTaskTurn()
                        mintDeltaPublicationScheduler(assistantEntryId)
                        val userEntry = UserMessage(
                            id = outcome.pendingTurnId,
                            text = SkillInvocationDisplay.text(outcome.prompt),
                            state = UserMessageState.SENT
                        )
                        val provisionalAssistant = AssistantMessage(
                            id = assistantEntryId,
                            text = "",
                            state = AssistantMessageState.STREAMING,
                            sourceKind = ActiveSourceKind.STREAM
                        )
                        _uiState.update { current ->
                            current.copy(
                                timeline = current.timeline + userEntry + provisionalAssistant,
                                isSubmitting = false,
                                isStreaming = true,
                                submissionStatus = SubmissionStatus.STREAMING,
                                pendingUnknownTurnId = null,
                                errorMessage = null,
                                // TRD1 slice 1 (TASK-003): same trace start on
                                // the retry path (a fresh attempt is a fresh
                                // trace; A1 turn-derived identity).
                                trace = TraceState(
                                    turnId = outcome.pendingTurnId,
                                    startedAtMillis = clock()
                                )
                            )
                        }
                        publishStreaming(true)
                    }

                    is TurnSubmissionOutcome.AmbiguousUnknown -> {
                        // T2: a retry attempt begins here (same frozen
                        // invariant as the admitted-turn clear above), so the
                        // one-shot stopped-turn reconciliation window closes
                        // with it — for the same reason as submitPrompt's
                        // AmbiguousUnknown branch: message.complete carries no
                        // turn id, and a stale marker would let this attempt's
                        // terminal overwrite the STOPPED turn's row. A late
                        // terminal for the stopped turn during the ambiguous
                        // window keeps the pre-T2 bounded generic notice.
                        stoppedTurnMarker = null
                        val userEntry = UserMessage(
                            id = outcome.pendingTurnId,
                            text = SkillInvocationDisplay.text(outcome.prompt),
                            state = UserMessageState.UNKNOWN
                        )
                        _uiState.update { current ->
                            current.copy(
                                timeline = current.timeline + userEntry,
                                isSubmitting = false,
                                isStreaming = false,
                                submissionStatus = SubmissionStatus.UNKNOWN,
                                pendingUnknownTurnId = outcome.pendingTurnId,
                                errorMessage = "Retry attempt status unknown."
                            )
                        }
                        publishStreaming(false)
                    }

                    is TurnSubmissionOutcome.RejectedBusy, is TurnSubmissionOutcome.ValidationFailed -> {
                        // BLD-006 S3: a local retry rejection keeps the UNKNOWN
                        // turn state (and its retry affordance) intact; no
                        // optimistic row exists on this path to strand.
                        _uiState.update { it.copy(isSubmitting = false) }
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            val newRecord = pendingTurnLedger?.createNewAttemptFromAmbiguous(oldTurnId)
            if (newRecord != null) {
                _uiState.update {
                    it.copy(
                        composerDraft = newRecord.promptContent,
                        pendingUnknownTurnId = null,
                        submissionStatus = SubmissionStatus.IDLE,
                        errorMessage = null
                    )
                }
                submitPrompt()
            }
        }
    }

    /**
     * T1: Stops the active assistant turn — best-effort host interrupt plus
     * optimistic local finalize.
     *
     * Guards on `isStreaming == true` and `activeAssistantEntryId != null`.
     *
     * BEFORE the local finalize, fires a best-effort `session.interrupt` for
     * the current live runtime session id (the same addressing id prompt
     * submission uses) through the injected [GatewayProtocolAdapter]. The
     * interrupt is initiated first and never awaited: a typed failure or a
     * thrown transport exception can never block, delay, or crash the local
     * stop, and undelivered interrupts are NOT queued here (offline/reconnect
     * delivery is a later task). There is no ViewModel logging seam, so a
     * failed interrupt is silent: the typed adapter result is discarded by
     * design — delivery retry/queueing is a later task, not this call.
     *
     * The local finalize always runs: [finalizeAssistantTurn] with
     * [AssistantMessageState.INTERRUPTED] and null terminal text, which
     * flushes and disposes the publication scheduler,
     * retains admitted buffer text losslessly without fabricating completion,
     * seals the trace as [TraceOverallState.INTERRUPTED], marks the pending turn
     * resolved in the ledger, and resets submission status to IDLE.
     *
     * T2: the stop also records a one-shot [StoppedTurnMarker] for the sealed
     * row, so a host `message.complete` arriving AFTER this optimistic
     * finalize reconciles the transcript truthfully instead of being dropped
     * as unsolicited activity (see the [GatewayEvent.MessageComplete] handler
     * and [upgradeStoppedTurnToComplete] for the race audit and rules).
     */
    fun stopStreaming() {
        if (chatQueue != null) {
            queueRequiresIdle = true
            viewModelScope.launch { chatQueue.pause(QueuePause.STOPPED) }
        }
        if (_uiState.value.edit?.busy == true) return
        if (!_uiState.value.isStreaming || activeAssistantEntryId == null) {
            return
        }
        // T2: record the one-shot stop marker (row + trace identity) before
        // the finalize below nulls the active-turn ids, so a host terminal
        // landing AFTER this optimistic finalize reconciles the transcript
        // truthfully instead of being dropped as unsolicited activity (see
        // the MessageComplete handler and [upgradeStoppedTurnToComplete]).
        stoppedTurnMarker = activeAssistantEntryId?.let { entryId ->
            StoppedTurnMarker(entryId = entryId, traceTurnId = activeTurnId)
        }
        // T1: best-effort delivery to the host, initiated before the local
        // finalize below. Failures/throws are swallowed inside the coroutine —
        // the optimistic local stop is unconditional.
        // T3 PR-2 (FD-005 / TASK-005): the SAME call's connection-class
        // failure (or throw) is captured as a durable hold keyed by the
        // STORED session id, so the stop is delivered once on the next
        // reconnect — success and terminal rejections never enqueue, and
        // there is no capture at all without a stored id to key by.
        val adapter = gatewayProtocolAdapter
        val runtimeId = _uiState.value.runtimeSessionId
        val storedId = _uiState.value.durableSessionId
        if (adapter != null && !runtimeId.isNullOrBlank()) {
            viewModelScope.launch {
                val outcome: Any = try {
                    adapter.interruptSession(runtimeId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Delivery threw before a typed result: connection-class
                    // by assumption; the flush path re-derives the truth.
                    INTERRUPT_DELIVERY_THREW
                }
                val captured = when (outcome) {
                    is GatewayResult.Success<*> -> false
                    is GatewayResult.Failure -> outcome.failure.qualifiesForStopHold()
                    else -> true
                }
                if (captured && !storedId.isNullOrBlank()) {
                    // Reviewer P3: a vault write failure is best-effort here —
                    // it must never escape this coroutine after the local
                    // stop already happened.
                    runCatching { pendingInterruptQueue?.enqueue(storedId, runtimeId) }
                }
            }
        }
        settleTaskProgress()
        finalizeAssistantTurn(AssistantMessageState.INTERRUPTED)
    }

    /**
     * CHAT-004 (frozen plan TASK-011): scroll-to-top load-earlier. One bounded
     * fetch per gesture: single-flight guarded, read-only history snapshot via
     * [SessionRepository.fetchHistorySnapshot], prepend-merge of rows older
     * than [oldestRenderedHistoryRow] through [HistoryPrependMerger], and a
     * bounded beginning-of-history indicator when the fresh window adds
     * nothing. CON-003: NEVER sets isInitialLoading (viewport yank guard);
     * CON-004 honesty: on failure the timeline is never mutated and no rows
     * are fabricated. Stale fetches after a session switch are dropped.
     */
    fun loadEarlier() {
        if (_uiState.value.edit != null) return
        val repo = sessionRepository ?: return
        if (sessionId == "new-session") return
        val state = _uiState.value
        // PR-3 (SEC-04): while a saved snapshot is shown the surface is
        // read-only — zero adapter calls from this path.
        if (state.savedSnapshotCapturedAtMillis != null) return
        if (state.isLoadingEarlier) return

        if (storedHistoryScope != null) {
            loadStoredEarlier(repo)
            return
        }
        val fetchTarget = state.durableSessionId ?: sessionId
        _uiState.update { it.copy(isLoadingEarlier = true) }
        viewModelScope.launch {
            val result = repo.fetchHistorySnapshot(fetchTarget)
            // Stale-fetch guard: the fetch target must still be the current
            // session before any state mutation. Clear the in-flight flag
            // here too, or the single-flight guard stays stuck (repair R1).
            if ((_uiState.value.durableSessionId ?: sessionId) != fetchTarget) {
                _uiState.update { current ->
                    current.copy(isLoadingEarlier = false)
                }
                return@launch
            }
            result.onSuccess { snapshot ->
                val merge = HistoryPrependMerger.merge(
                    existingTimeline = _uiState.value.timeline,
                    existingOldestHistoryRow = oldestRenderedHistoryRow,
                    freshMessages = snapshot.messages,
                    messagesOmitted = snapshot.messagesOmitted
                )
                // PAT-001: map prepended raw rows through the SAME history
                // mapping rules loadSession() uses, with epoch-qualified
                // hist-prev-<epoch>-<index> IDs so successive prepends never
                // collide as LazyColumn keys (repair R1); hydration IDs
                // (hist-*) are unchanged.
                val epoch = prependEpoch
                val prependedEntries = merge.olderRows.mapIndexed { index, msg ->
                    mapHistoryMessageToEntries(msg, index, prefix = "hist-prev-$epoch")
                }.flatten()

                val beginState = when {
                    merge.beginningReached && merge.nonExhaustive ->
                        ChatUiState.HistoryBeginState.NON_EXHAUSTIVE
                    merge.beginningReached -> ChatUiState.HistoryBeginState.REACHED
                    else -> ChatUiState.HistoryBeginState.NONE
                }

                _uiState.update { current ->
                    current.copy(
                        timeline = prependedEntries + current.timeline,
                        historyBeginState = beginState,
                        isLoadingEarlier = false
                    )
                }
                // The prepend base advances to the oldest row now rendered,
                // and the prepend epoch advances so the next round's IDs
                // cannot collide with this round's (repair R1).
                if (merge.olderRows.isNotEmpty()) {
                    oldestRenderedHistoryRow = merge.olderRows.first()
                    prependEpoch += 1
                }
            }.onFailure {
                // Never replace the timeline on failure; clear the in-flight flag.
                _uiState.update { current ->
                    current.copy(isLoadingEarlier = false)
                }
            }
        }
    }

    private fun loadStoredEarlier(repo: SessionRepository) {
        val window = storedWindow ?: return
        if (historyLoadJob?.isActive == true || earlierHistoryJob?.isActive == true ||
            olderResourceBlocked || !window.possiblyEarlier) return
        val scope = window.scope
        val generation = historyGeneration
        val offset = window.offset
        val cycle = window.budget.nextCycle()
        window.budget = cycle
        window.activateCycle(cycle)
        fun owned() = !historyDisposed && generation == historyGeneration && storedWindow === window &&
            storedHistoryScope === scope && scope.current
        _uiState.update { it.copy(isLoadingEarlier = true) }
        earlierHistoryJob = viewModelScope.launch {
            try {
                val result = repo.fetchStoredHistoryPage(scope, cycle, offset)
                if (!owned() || !repo.isStoredHistoryPublicationCurrent(scope, cycle) || !owned()) return@launch
                result.fold(onSuccess = { page ->
                    if (cycle.remainingMillis() > 0) window.publishOlder(page, cycle)
                }, onFailure = { failure ->
                    val kind = (failure as? StoredHistoryException)?.failure
                    if (kind != StoredHistoryFailure.STALE) _uiState.update { it.copy(errorMessage =
                        if (kind == StoredHistoryFailure.OVERSIZE || kind == StoredHistoryFailure.DEPTH)
                            "Stored history exceeds this app's limit. Live activity may still appear."
                        else "Stored history unavailable. Live activity may still appear.") }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: StoredHistoryException) {
                if (owned() && repo.isStoredHistoryPublicationCurrent(scope, cycle) && owned()) {
                    olderResourceBlocked = true
                    _uiState.update { it.copy(storedHistoryEarlierAvailable = false,
                        errorMessage = "Stored history limit reached. Reopen to load the latest history.") }
                }
            } finally {
                if (owned() && repo.isStoredHistoryPublicationCurrent(scope, cycle) && owned())
                    _uiState.update { it.copy(isLoadingEarlier = false) }
            }
        }
    }

    private fun updateUserMessageState(turnId: String, newState: UserMessageState) {
        _uiState.update { current ->
            val updated = current.timeline.map { entry ->
                if (entry is UserMessage && entry.id == turnId) {
                    entry.copy(state = newState)
                } else {
                    entry
                }
            }
            current.copy(timeline = updated)
        }
    }

    /**
     * BLD-006 S4 (D-01/D-02/D-04, TASK-017): an admitted delta is appended to
     * the canonical retained source (bounds enforced incrementally in
     * [AssistantTurnStreamBuffer]) and the 1 MiB presentation window is
     * republished on the single stable assistant entry for the active local
     * turn. Unsolicited text without an attributable active admitted local
     * turn NEVER mutates the transcript as local-turn proof.
     */
    private fun activeEventReservation(event: GatewayEvent): Long {
        fun bytes(text: String?) = text?.let { BoundedPayloadMapper.utf8ByteCount(it).toLong() } ?: 0L
        return when (event) {
            is GatewayEvent.MessageDelta -> if (activeAssistantEntryId == null) 0L else 512L + 3 * bytes(event.text)
            is GatewayEvent.MessageComplete -> if (activeAssistantEntryId == null) 0L else 1024L + 2 * bytes(event.text) + bytes(event.reasoning)
            is GatewayEvent.MessageInterim -> if (event.alreadyStreamed == true || activeAssistantEntryId == null) 0L else 512L + bytes(event.text)
            is GatewayEvent.ReasoningDelta -> if (activeAssistantEntryId == null) 0L else 512L + minOf(bytes(event.text), 33 * 1024L)
            is GatewayEvent.ToolStart -> 24 * 1024L + bytes(event.toolId)
            is GatewayEvent.ToolComplete -> 24 * 1024L + bytes(event.toolId)
            is GatewayEvent.ToolGenerating, is GatewayEvent.ToolProgress -> 24 * 1024L
            is GatewayEvent.SessionInfo -> 4096L + listOfNotNull(
                SessionModelLabelPolicy.modelFromInfo(event.info), SessionModelLabelPolicy.reasoningEffortFromInfo(event.info)
            ).sumOf { bytes(it) }
            else -> 4096L
        }
    }

    private fun applyMessageDelta(delta: String) {
        val entryId = activeAssistantEntryId
        val buffer = activeTurnBuffer
        val entryStillVisible = entryId != null &&
            _uiState.value.timeline.any { it is AssistantMessage && it.id == entryId }
        if (entryId == null || buffer == null || !entryStillVisible) {
            // No attributable active admitted local turn (never opened, already
            // sealed, or its row was replaced by history hydration — a BLD-007
            // concern): the unsolicited delta is silently dropped (plan
            // ui-remove-unattributed-activity-notices-v1, owner-locked) — no
            // GenericGatewayActivity row is appended and the timeline does not
            // grow. D-01 holds in its stronger form: renders nothing, never
            // local-turn proof.
            return
        }

        // BLD-006 S5: retention stays synchronous and lossless in the S4
        // buffer; only the timeline publication is coalesced. The scheduler
        // republishes the full 1 MiB presentation window on the stable entry
        // ID, so every admitted scalar appears exactly once, in order, and
        // Compose/Markdown work runs at most once per 32 ms window.
        buffer.appendDelta(delta)
        admitDeltaToScheduler(delta)
        _uiState.update { it.copy(isStreaming = true) }
        publishStreaming(true)
    }

    /**
     * BLD-006 S5 (TASK-021): admits the raw streamed delta into the 32 ms
     * coalescing scheduler. PR-38 review polish: the delta (O(delta)) is
     * admitted instead of a freshly built 1 MiB presentation window copy
     * (O(1 MiB) per admitted fragment) — the publication callback discards
     * the coalesced payload and re-reads the live buffer, so the scheduler's
     * internal aggregate is never rendered; exactly-once remains a property
     * of the published window content. The callback rewrites ONLY the stable
     * assistant entry for the active turn — attribution never drifts to
     * another row (D-02) — and carries the buffer's overflow flag so the
     * visible tail stays consistent with retention.
     */
    private fun admitDeltaToScheduler(delta: String) {
        // The scheduler is minted with the turn on both admission paths
        // (submit and retry); a null here means no attributable turn, which
        // the caller already guards.
        pendingActiveDeltaBytes += BoundedPayloadMapper.utf8ByteCount(delta)
        deltaPublicationScheduler?.admit(delta)
    }

    /**
     * BLD-006 S5: one coalesced publication = one timeline mutation of the
     * stable assistant entry with the current presentation window. The text
     * is the full window (not the raw delta), so a dropped or duplicated
     * intermediate publication can never lose or double scalars.
     */
    private fun publishAssistantPresentation(
        entryId: String,
        buffer: AssistantTurnStreamBuffer
    ) {
        val presentation = buffer.presentationWindow()
        _uiState.update { current ->
            current.copy(
                timeline = current.timeline.map { entry ->
                    if (entry is AssistantMessage && entry.id == entryId) {
                        entry.copy(
                            text = presentation,
                            state = AssistantMessageState.STREAMING,
                            sourceOverflow = buffer.sourceOverflow
                        )
                    } else {
                        entry
                    }
                }
            )
        }
    }

    /**
     * BLD-006 S4 (D-02/D-03, TASK-017): terminal handling flushes buffered
     * deltas (they are already admitted synchronously), then seals ONLY the
     * identified active assistant entry — never a `timeline.lastOrNull()`
     * search and never every streaming row.
     *
     * Non-null terminal text is authoritative and replaces the canonical
     * source under the same 8 MiB ceiling; when null, the lossless admitted
     * canonical delta text is retained, including any explicit overflow state.
     */
    private fun finalizeAssistantTurn(
        terminalState: AssistantMessageState,
        terminalText: String? = null,
        errorMessage: String? = null,
        usage: com.hermes.bot.data.gateway.GatewayUsage? = null,
        finalReasoning: String? = null
    ) {
        val entryId = activeAssistantEntryId
        val buffer = activeTurnBuffer

        if (entryId == null || buffer == null) {
            // BLD-006 S4 (D-01): terminal/error activity without an
            // attributable active admitted local turn is unsolicited — D-01
            // holds in its stronger form (plan
            // ui-drop-unattributed-notices-v2-plan.md, owner-locked, PR #178
            // precedent): the unattributed turn activity is silently dropped
            // (renders nothing, no GenericGatewayActivity row, timeline does
            // not grow), never local-turn proof, and no assistant row is
            // sealed.
            return
        }

        // BLD-006 S5 (TASK-023): a terminal event flushes pending coalesced
        // text BEFORE the terminal state is published, so the sealed row can
        // never trail admitted text.
        deltaPublicationScheduler?.flush()

        // Apply the authoritative terminal replacement to the buffer BEFORE the
        // state update (StateFlow.update may retry its lambda; the buffer
        // mutation must not ride inside a retried block).
        buffer.replaceWithTerminalText(terminalText)
        val presentation = buffer.presentationWindow()
        val overflow = buffer.sourceOverflow

        // F007-S4 chosen rule: prefer LIVE reasoning. If the turn already
        // accumulated live reasoning (F007-S2 path), the host's final
        // `reasoning` from message.complete is intentionally dropped — the
        // host sends both, and attaching the final text again would duplicate
        // the same content in the collapsed reasoning surface. Only a turn
        // with NO accumulated live reasoning adopts the final reasoning as a
        // collapsed ReasoningEntry on the same surface as live reasoning.
        // F-007 polish: a blank/whitespace final reasoning is treated as
        // absent (same precedent as blank terminal status -> UNKNOWN) — no
        // empty collapsed reasoning card is fabricated.
        val effectiveFinalReasoning = finalReasoning?.takeUnless { it.isBlank() }
        val existingLiveReasoning = effectiveFinalReasoning != null && _uiState.value.timeline.any {
            it is ReasoningEntry && it.id == reasoningEntryId(entryId)
        }
        val attachFinalReasoning: ReasoningEntry? =
            if (effectiveFinalReasoning != null && !existingLiveReasoning) {
                ReasoningEntry(
                    id = reasoningEntryId(entryId),
                    kind = ReasoningDisplayKind.REASONING,
                    text = ReasoningPolicy.accumulate("", effectiveFinalReasoning)
                )
            } else {
                null
            }

        // Direct sends own their ledger settlement too; queued work must not
        // use the transient non-streaming state before that IO completes.
        queueAwaitingTurnSettlement = chatQueue != null && activeTurnId != null
        _uiState.update { current ->
            var updatedTimeline = current.timeline.map { entry ->
                if (entry is AssistantMessage && entry.id == entryId &&
                    entry.state == AssistantMessageState.STREAMING
                ) {
                    entry.copy(
                        text = presentation,
                        state = terminalState,
                        errorMessage = errorMessage,
                        usage = usage,
                        sourceOverflow = overflow
                    )
                } else {
                    entry
                }
            }
            // F007-S4: attach the collapsed final-reasoning entry after the
            // sealed assistant row, preserving event order (same rule as the
            // live reasoning entry: appended at its natural position).
            if (attachFinalReasoning != null) {
                updatedTimeline = appendActivePart(updatedTimeline, attachFinalReasoning)
            }
            // TRD1 slice 1 (TASK-003): seal the turn-scoped trace in the
            // same lambda as the terminal state update — 1:1 mapping from
            // the fail-closed terminal state (UNKNOWN passes through),
            // frozen terminal elapsed (REQ-002/003). `terminalState` here
            // is never STREAMING (the caller maps every certified status
            // fail-closed), so the mapping is exhaustive over reachable
            // inputs.
            val sealedTrace = current.trace?.takeIf { it.turnId == activeTurnId }?.let { trace ->
                trace.copy(
                    endedAtMillis = clock(),
                    overallState = when (terminalState) {
                        AssistantMessageState.COMPLETE -> TraceOverallState.COMPLETE
                        AssistantMessageState.INTERRUPTED -> TraceOverallState.INTERRUPTED
                        AssistantMessageState.FAILED -> TraceOverallState.FAILED
                        else -> TraceOverallState.UNKNOWN
                    }
                )
            }
            current.copy(
                timeline = updatedTimeline,
                isStreaming = false,
                submissionStatus = SubmissionStatus.IDLE,
                // Current remote completion ends the temporary recovered-RUNNING gate.
                // Unknown outcomes retain it; local turns keep their existing lifecycle.
                submitBlockedReason = if (activeTurnId == null && remoteActiveGeneration != null &&
                    terminalState == AssistantMessageState.COMPLETE && !current.runtimeSessionId.isNullOrBlank())
                    null else current.submitBlockedReason,
                trace = sealedTrace ?: current.trace
            )
        }
        publishStreaming(false)

        // The active turn is sealed; the next submit mints a fresh entry ID.
        val completingQueueTurn = queueTurnCompletion
        val completionResult = when (terminalState) {
            AssistantMessageState.COMPLETE -> QueueExecutionResult.COMPLETE
            AssistantMessageState.FAILED, AssistantMessageState.INTERRUPTED -> QueueExecutionResult.FAILED
            else -> QueueExecutionResult.UNKNOWN
        }
        val completedTurnId = activeTurnId
        // The turn is sealed: dispose the scheduler (its final drain is a
        // no-op after the flush above — no duplicated text).
        deltaPublicationScheduler?.dispose()
        deltaPublicationScheduler = null
        activeAssistantEntryId = null
        activeTurnBuffer = null
        completedTurnId?.let { turnId ->
            viewModelScope.launch {
                pendingTurnLedger?.markResolved(turnId)
                val recovered = chatQueue?.state?.value?.active
                if (completionResult == QueueExecutionResult.COMPLETE && recovered?.pendingTurnId == turnId &&
                    recovered.state == QueueItemState.UNKNOWN && chatQueue?.resolveCompletedPrompt(recovered.id) == true)
                    queueRequiresIdle = false
                if (completingQueueTurn == null && completionResult != QueueExecutionResult.COMPLETE &&
                    _uiState.value.queue.waiting.isNotEmpty()) {
                    queueRequiresIdle = true
                    chatQueue?.pause(if (completionResult == QueueExecutionResult.UNKNOWN) QueuePause.UNKNOWN else QueuePause.FAILED)
                }
                if (activeTurnId == turnId) activeTurnId = null
                queueAwaitingTurnSettlement = false
                completingQueueTurn?.complete(completionResult)
                if (completingQueueTurn == null) chatQueue?.runNext(::canDispatchQueue, ::executeQueueItem)
            }
        } ?: completingQueueTurn?.complete(completionResult)
    }

    /**
     * T2: reconciles a late authoritative completion for the turn a user Stop
     * already sealed locally — the stop-vs-completion race (the full audit
     * decision lives at the [GatewayEvent.MessageComplete] handler).
     *
     * Truthful-upgrade rules; this never fabricates and never re-opens the
     * turn:
     * - Applies ONLY to the marker's exact row while it still carries
     *   INTERRUPTED. A row replaced by history hydration or already terminal
     *   fails the lookup and returns false, so the caller records the bounded
     *   generic notice instead. The marker is consumed exactly once.
     * - The certified "complete" status is the host's authoritative outcome,
     *   so the row upgrades INTERRUPTED -> COMPLETE. MessageComplete text is
     *   authoritative (same contract as [finalizeAssistantTurn]): non-null
     *   text replaces the partial stop text through the same bounded 8 MiB
     *   ceiling / 1 MiB window machinery ([AssistantTurnStreamBuffer]); null
     *   text keeps the losslessly retained partial text — never wiped, never
     *   fabricated. Usage rides along; final reasoning follows the F007-S4
     *   rule (attaches only when no live reasoning entry exists for the turn).
     * - The turn-scoped trace sealed INTERRUPTED at stop time flips to
     *   COMPLETE (fresh terminal stamp = time the late terminal was observed)
     *   so the trace surface cannot contradict the row; a trace belonging to
     *   another turn is never touched.
     * - isStreaming / submission status / ledger are untouched: the stop
     *   already terminated local streaming and resolved the pending turn, and
     *   a late terminal must never resurrect submission state.
     *
     * Returns true only when the sealed row was actually upgraded.
     */
    private fun upgradeStoppedTurnToComplete(
        marker: StoppedTurnMarker,
        event: GatewayEvent.MessageComplete
    ): Boolean {
        val snapshot = _uiState.value
        val rowStillStopped = snapshot.timeline.any {
            it is AssistantMessage && it.id == marker.entryId &&
                it.state == AssistantMessageState.INTERRUPTED
        }
        if (!rowStillStopped) return false

        // Authoritative terminal text under the same bounded layers the
        // normal finalize path uses; null keeps the retained partial text.
        val authoritative = event.text?.let { raw ->
            val sealedBuffer = AssistantTurnStreamBuffer()
            sealedBuffer.replaceWithTerminalText(raw)
            sealedBuffer.presentationWindow() to sealedBuffer.sourceOverflow
        }
        val finalReasoning = event.reasoning?.takeUnless { it.isBlank() }
        val attachFinalReasoning = finalReasoning != null &&
            snapshot.timeline.none {
                it is ReasoningEntry && it.id == reasoningEntryId(marker.entryId)
            }

        _uiState.update { current ->
            var updatedTimeline = current.timeline.map { entry ->
                if (entry is AssistantMessage && entry.id == marker.entryId &&
                    entry.state == AssistantMessageState.INTERRUPTED
                ) {
                    entry.copy(
                        text = authoritative?.first ?: entry.text,
                        state = AssistantMessageState.COMPLETE,
                        usage = event.usage ?: entry.usage,
                        sourceOverflow = authoritative?.second ?: entry.sourceOverflow
                    )
                } else {
                    entry
                }
            }
            if (attachFinalReasoning && finalReasoning != null) {
                updatedTimeline = updatedTimeline + ReasoningEntry(
                    id = reasoningEntryId(marker.entryId),
                    kind = ReasoningDisplayKind.REASONING,
                    text = ReasoningPolicy.accumulate("", finalReasoning)
                )
            }
            val upgradedTrace = current.trace?.takeIf {
                marker.traceTurnId != null && it.turnId == marker.traceTurnId
            }?.let { trace ->
                trace.copy(
                    endedAtMillis = clock(),
                    overallState = TraceOverallState.COMPLETE
                )
            }
            current.copy(
                timeline = updatedTimeline,
                trace = upgradedTrace ?: current.trace
            )
        }
        return true
    }

    /**
     * BLD-006 S7: tool.generating is an independent generic "preparing" row.
     * It carries no tool_id and is never correlated with, upgraded by, or
     * attached to a keyed tool card; keyed correlation happens ONLY on a
     * validated nonblank tool_id delivered by tool.start / tool.complete.
     */
    private fun appendActivePart(timeline: List<TimelineEntry>, part: TimelineEntry): List<TimelineEntry> {
        val active = activeAssistantEntryId
        if (remoteActiveGeneration == null || active == null) return timeline + part
        val assistant = timeline.indexOfFirst { it.id == active }
        val queued = timeline.indexOfFirst { it is UserMessage && it.sourceKind == ActiveSourceKind.QUEUED }
        return if (assistant >= 0 && queued > assistant) timeline.take(queued) + part + timeline.drop(queued)
            else timeline + part
    }

    private fun addToolPreparing(toolName: String) {
        // BLD-006 S5 (TASK-023): a tool/generic boundary event flushes pending
        // text BEFORE that event is published.
        deltaPublicationScheduler?.flush()
        val entry = ToolActivity(
            id = "tool-gen-${UUID.randomUUID()}",
            toolId = null,
            name = BoundedPayloadMapper.boundedNameType(toolName),
            status = ToolStatus.PREPARING
        )
        _uiState.update { it.copy(timeline = appendActivePart(it.timeline, entry)) }
    }

    /**
     * BLD-006 S7: tool.start correlates ONLY by a validated nonblank tool_id.
     * Name and adjacency fallbacks are removed — two tools running at the same
     * time with the same name stay separate cards. A blank/malformed tool_id
     * becomes a bounded generic row, never a keyed card.
     */
    private fun addOrUpdateToolStart(
        toolId: String,
        name: String,
        context: String?,
        args: Map<String, Any?>
    ) {
        val validatedId = toolId.trim()
        // BLD-006 S5 (TASK-023): flush pending text before the tool row.
        deltaPublicationScheduler?.flush()
        if (validatedId.isBlank()) {
            addGenericActivity(
                eventType = "tool.start",
                summary = "Tool activity (unidentified): ${name.ifBlank { "unknown" }}",
                payload = emptyMap()
            )
            return
        }
        _uiState.update { current ->
            // An admitted start is live evidence even when a stored occurrence reused its ID.
            val existingIndex = current.timeline.indexOfFirst {
                it is ToolActivity && it.storedSource == null && it.toolId == validatedId
            }
            // BLD-006 S8 (D-09): name/context/args bounded during traversal via
            // BoundedPayloadMapper; the raw map is never passed onward (SEC-002).
            val boundedName = BoundedPayloadMapper.boundedNameType(name)
            val boundedContext = BoundedPayloadMapper.boundedContext(context)
            val formattedArgs = ChatSecurityPolicy.formatToolArgs(args)

            if (existingIndex != -1) {
                val existing = current.timeline[existingIndex] as ToolActivity
                // A late/duplicate tool.start never clobbers an already-terminal
                // card: the completion is authoritative, keeping exactly one
                // correct terminal card when completion precedes start.
                if (existing.status == ToolStatus.COMPLETED) {
                    current
                } else {
                    val updated = current.timeline.toMutableList()
                    updated[existingIndex] = existing.copy(
                        toolId = validatedId,
                        name = boundedName,
                        context = boundedContext,
                        argsSummary = formattedArgs,
                        delegatedTasks = SubagentPresentation.tasks(name, args, null, validatedId),
                        status = ToolStatus.RUNNING
                    )
                    current.copy(timeline = updated)
                }
            } else {
                val newTool = ToolActivity(
                    id = "tool-$validatedId",
                    toolId = validatedId,
                    name = boundedName,
                    context = boundedContext,
                    argsSummary = formattedArgs,
                    delegatedTasks = SubagentPresentation.tasks(name, args, null, validatedId),
                    status = ToolStatus.RUNNING
                )
                current.copy(timeline = appendActivePart(current.timeline, newTool))
            }
        }
    }

    /**
     * F007-S2: admits a live reasoning delta into the active turn's bounded
     * reasoning block. The block attaches to the active admitted local turn
     * exactly like assistant text (D-01/D-02): without an attributable active
     * admitted local turn, the delta stays a bounded generic notice — never
     * local-turn proof. Accumulation is bounded by [ReasoningPolicy] (32 KiB
     * UTF-8, labeled truncation) and runs synchronously; per-frame UI commits
     * are already coalesced upstream by the transport/event flow cadence, so
     * no extra scheduler is introduced in this slice.
     */
    private fun applyReasoningDelta(
        kind: com.hermes.bot.data.gateway.ReasoningKind,
        delta: String
    ) {
        val entryId = activeAssistantEntryId
        val entryStillVisible = entryId != null &&
            _uiState.value.timeline.any { it is AssistantMessage && it.id == entryId }
        if (entryId == null || !entryStillVisible) {
            // Polish P-3 companion fix: attribute the generic row to the delta's
            // ACTUAL event type — `thinking.delta` for THINKING, `reasoning.delta`
            // for REASONING — instead of hardcoding the reasoning type.
            //
            // D-01 owner-locked drop (plan
            // ui-drop-unattributed-notices-v2-plan.md, PR #178 precedent):
            // without an attributable active admitted local turn the
            // reasoning/thinking delta is silently dropped — no
            // GenericGatewayActivity row is appended ("Unattributed reasoning
            // activity" rendered pure noise with its empty payload) and the
            // timeline does not grow. D-01 holds in its stronger form:
            // renders nothing, never local-turn proof, never a bubble.
            return
        }

        val displayKind = when (kind) {
            com.hermes.bot.data.gateway.ReasoningKind.REASONING -> ReasoningDisplayKind.REASONING
            com.hermes.bot.data.gateway.ReasoningKind.THINKING -> ReasoningDisplayKind.THINKING
        }
        _uiState.update { current ->
            val existing = current.timeline.filterIsInstance<ReasoningEntry>()
                .firstOrNull { it.id == reasoningEntryId(entryId) }
            val updated: ReasoningEntry = if (existing != null) {
                // Polish P-3: the label is LATCHED on the first delta's kind for
                // this turn — a host that emits both `reasoning.delta` and
                // `thinking.delta` in one turn concatenates the text under one
                // stable label instead of the label alternating mid-turn. Only
                // the first mint gets to name the entry.
                existing.copy(
                    text = ReasoningPolicy.accumulate(existing.text, delta)
                )
            } else {
                ReasoningEntry(
                    id = reasoningEntryId(entryId),
                    kind = displayKind,
                    text = ReasoningPolicy.accumulate("", delta)
                )
            }
            // Preserve event order between assistant segments: the entry is
            // appended at its natural position on first appearance and then
            // updated in place — never re-sorted.
            val timeline = if (existing == null) {
                appendActivePart(current.timeline, updated)
            } else {
                current.timeline.map { if (it is ReasoningEntry && it.id == updated.id) updated else it }
            }
            current.copy(
                timeline = timeline,
                isStreaming = true
            )
        }
        publishStreaming(true)
    }

    /** F007-S2: stable reasoning-entry ID derived from the active turn's entry. */
    private fun reasoningEntryId(assistantEntryId: String): String = "reasoning-$assistantEntryId"

    /**
     * CHAT-018 PR-A (REQ-005): appends one interim agent-commentary segment as
     * an [AgentNoteEntry] at the timeline end (chronological), attributed to
     * the active admitted local turn's card. The caller has already verified
     * the attribution gate (`activeAssistantEntryId != null`, same admission
     * evidence as [applyMessageDelta]); the reply bubble is never touched
     * (REQ-004) and the note is not snapshot-persisted (TimelineEntry.kt).
     */
    private fun applyInterimNote(text: String) {
        val note = AgentNoteEntry(
            id = "note-${UUID.randomUUID()}",
            text = text
        )
        _uiState.update { current ->
            current.copy(timeline = appendActivePart(current.timeline, note))
        }
    }

    private fun addToolProgress(type: String, payload: Map<String, Any?>) {
        // BLD-006 S5 (TASK-023): flush pending text before the activity row.
        deltaPublicationScheduler?.flush()
        // Safe generic progress activity if not bound to a specific toolId.
        // BLD-006 S8 (D-09/SEC-002): the raw map is reduced through the bounded
        // traversal — never payload.toString() — and previewed under the 1 KiB
        // generic budget.
        val entry = GenericGatewayActivity(
            id = "prog-${UUID.randomUUID()}",
            eventType = BoundedPayloadMapper.boundedNameType("tool.progress.$type"),
            summary = BoundedPayloadMapper.boundedCollapsedSummary("Tool progress: $type"),
            payloadPreview = BoundedPayloadMapper.boundedGenericPreview(
                BoundedPayloadMapper.traverseStructured(payload)
            )
        )
        _uiState.update { it.copy(timeline = appendActivePart(it.timeline, entry)) }
    }

    /**
     * BLD-006 S7: tool.complete correlates ONLY by a validated nonblank tool_id.
     * The `|| it.name == name` fallback is removed — a completion can never
     * attach to an unrelated same-name card. A valid completion arriving before
     * its start creates a safe terminal card keyed by that ID; a blank/malformed
     * tool_id becomes a bounded generic row, never a keyed card.
     */
    private fun finalizeToolComplete(
        toolId: String,
        name: String,
        duration: Double?,
        result: Any?,
        args: Map<String, Any?> = emptyMap()
    ) {
        val validatedId = toolId.trim()
        // BLD-006 S5 (TASK-023): flush pending text before the tool row.
        deltaPublicationScheduler?.flush()
        if (validatedId.isBlank()) {
            addGenericActivity(
                eventType = "tool.complete",
                summary = "Tool completion (unidentified): ${name.ifBlank { "unknown" }}",
                payload = emptyMap()
            )
            return
        }
        _uiState.update { current ->
            val existingIndex = current.timeline.indexOfFirst {
                it is ToolActivity && it.storedSource == null && it.toolId == validatedId
            }
            // Complete actual live evidence first; only a bare recovery completion can fold away.
            if (existingIndex == -1 && storedHistoryScope != null &&
                validatedId in StoredHistoryHydrator.representedTools(
                    acceptedHistoryRows, presentationGroups(current.timeline, activeHistoryGroups))) return@update current
            // BLD-006 S8 (D-09/SEC-004): result is reduced through the bounded
            // traversal and the 16 KiB expanded budget; name is bounded. A typed
            // failure result carries the typed non-secret copy instead of raw
            // error text.
            val boundedName = BoundedPayloadMapper.boundedNameType(name)
            val formattedResult = ChatSecurityPolicy.formatToolResult(result)

            if (existingIndex != -1) {
                val updated = current.timeline.toMutableList()
                val existing = updated[existingIndex] as ToolActivity
                updated[existingIndex] = existing.copy(
                    toolId = validatedId,
                    name = boundedName,
                    durationSeconds = duration,
                    resultSummary = formattedResult,
                    files = com.hermes.bot.data.files.ConversationFileReferences.tool(result, name),
                    delegatedTasks = SubagentPresentation.tasks(name, args, result, validatedId, historical = true).ifEmpty {
                        SubagentPresentation.tasks(name, mapOf("tasks" to existing.delegatedTasks.map { mapOf("goal" to it.goal) }), result, validatedId, historical = true)
                    },
                    status = ToolStatus.COMPLETED
                )
                current.copy(timeline = updated)
            } else {
                val completed = ToolActivity(
                    id = "tool-$validatedId",
                    toolId = validatedId,
                    name = boundedName,
                    durationSeconds = duration,
                    resultSummary = formattedResult,
                    files = com.hermes.bot.data.files.ConversationFileReferences.tool(result, name),
                    delegatedTasks = SubagentPresentation.tasks(name, args, result, validatedId, historical = true),
                    status = ToolStatus.COMPLETED
                )
                current.copy(timeline = appendActivePart(current.timeline, completed))
            }
        }
    }

    private fun addGenericActivity(
        eventType: String,
        summary: String,
        payload: Map<String, Any?>
    ) {
        // BLD-006 S5 (TASK-023): a generic activity boundary flushes pending
        // text BEFORE this notice is published.
        deltaPublicationScheduler?.flush()
        // BLD-006 S8 (SEC-002): event type and collapsed summary use the
        // code-point budgets; the raw payload map is reduced through the bounded
        // traversal — never payload.toString() — under the 1 KiB generic budget.
        val entry = GenericGatewayActivity(
            id = "generic-${UUID.randomUUID()}",
            eventType = BoundedPayloadMapper.boundedNameType(eventType),
            summary = BoundedPayloadMapper.boundedCollapsedSummary(summary),
            payloadPreview = BoundedPayloadMapper.boundedGenericPreview(
                BoundedPayloadMapper.traverseStructured(payload)
            )
        )
        _uiState.update { it.copy(timeline = appendActivePart(it.timeline, entry)) }
    }

    /**
     * CHAT-017 v2 (TASK-002): appends one standalone [ModelChangeNoticeRow]
     * to the end of the live timeline IMMEDIATELY — the same plain append
     * convention as the other live notice rows (flush the streaming row's
     * buffered text first so the row never interleaves mid-growth, mint with
     * the UUID id convention, atomic `_uiState.update`). Live-only: nothing
     * here touches the snapshot path.
     */
    private fun addModelChangeNotice(text: String) {
        deltaPublicationScheduler?.flush()
        val entry = ModelChangeNoticeRow(
            id = "mcr-${UUID.randomUUID()}",
            text = text
        )
        _uiState.update { it.copy(timeline = appendActivePart(it.timeline, entry)) }
    }

    fun toggleToolExpanded(entryId: String) {
        _uiState.update { current ->
            val updated = current.timeline.map { entry ->
                if (entry is ToolActivity && entry.id == entryId) {
                    entry.copy(isExpanded = !entry.isExpanded)
                } else {
                    entry
                }
            }
            current.copy(timeline = updated)
        }
    }

    fun toggleGenericActivityExpanded(entryId: String) {
        _uiState.update { current ->
            val updated = current.timeline.map { entry ->
                if (entry is GenericGatewayActivity && entry.id == entryId) {
                    entry.copy(isExpanded = !entry.isExpanded)
                } else {
                    entry
                }
            }
            current.copy(timeline = updated)
        }
    }

    /** F007-S2: expand/collapse affordance for the live reasoning block. */
    fun toggleReasoningExpanded(entryId: String) {
        _uiState.update { current ->
            val updated = current.timeline.map { entry ->
                if (entry is ReasoningEntry && entry.id == entryId) {
                    entry.copy(isExpanded = !entry.isExpanded)
                } else {
                    entry
                }
            }
            current.copy(timeline = updated)
        }
    }

    /**
     * BLD-006 S5 (TASK-023): ViewModel clear is a cancellation boundary — the
     * per-turn scheduler drains admitted buffered text one last time before
     * disposal (locally possible; never claims retention for data that was
     * never admitted). The ViewModel scope is cancelling, so the drain runs
     * synchronously on the caller's context instead of a doomed launch.
     */
    override fun onCleared() {
        stopPreviewObservation?.invoke()
        stopPreviewObservation = null
        editGeneration++
        editJob?.cancel()
        editAccess = null
        files?.close()
        cancelDictation()
        contextReadRequest++
        contextReadJob?.cancel()
        historyDisposed = true
        historyGeneration++
        storedHistoryScope?.invalidate()
        historyLoadJob?.cancel()
        earlierHistoryJob?.cancel()
        storedWindow = null
        acceptedHistoryRows = emptyList()
        activeHistoryEventAdmission = null
        activeHistoryGroups = emptyList()
        publishStreaming(false)
        // Synchronous final drain: onCleared runs during ViewModel teardown,
        // where another coroutine launch could be silently dropped.
        deltaPublicationScheduler?.dispose()
        deltaPublicationScheduler = null
        super.onCleared()
    }
}
