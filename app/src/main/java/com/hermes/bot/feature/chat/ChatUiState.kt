package com.hermes.bot.feature.chat

/**
 * State of prompt submission lifecycle per ARCH-001 (session-and-turn-state-machine.md).
 */
enum class SubmissionStatus {
    IDLE,
    PREPARING,
    SUBMITTING,
    STREAMING,
    UNKNOWN,
    ERROR
}

/**
 * Reasons a prompt submission was blocked before any side effect (CHG-003-S3).
 * Rendered by the Chat screen as the BLD-006 S2 runtime recovery notice
 * (see ChatScreen): NO_RUNTIME shows the restore-session notice, disables
 * Send/IME Send, and keeps the draft editable.
 */
enum class SubmitBlockedReason {
    NO_RUNTIME
}

/**
 * TRD1 slice 1 (TASK-001): turn-scoped overall state of the ExecutionTrace
 * header. Mirrors the reducer's fail-closed terminal mapping 1:1; RUNNING
 * covers the confirmed-admission → terminal window.
 */
enum class TraceOverallState {
    RUNNING,
    COMPLETE,
    INTERRUPTED,
    FAILED,
    UNKNOWN
}

/**
 * TRD1 slice 1 (TASK-001): presentation-only trace state for the turn-scoped
 * ExecutionTrace surface (REQ-001). Turn-derived identity per A1 (the reducer
 * owns this key as `pendingTurnId`); wall-clock client timing per A2.
 *
 * No reducer logic reads the trace; nothing is sent to the host and nothing
 * is logged (SEC-001). Cleared on history replacement (server truth replaces
 * the local trace; REQ-003 — historical turns render ungrouped, A1).
 */
data class TraceState(
    val turnId: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val overallState: TraceOverallState = TraceOverallState.RUNNING
)

/** A locally tracked image staged on the host for this runtime session. */
data class PendingImageAttachment(
    val serverPath: String,
    val displayName: String,
    val serverText: String
)

/** A server-returned file reference staged for the next prompt. */
data class PendingFileAttachment(
    val refText: String,
    val displayName: String = "Document",
    val sizeBytes: Int = 0
)

/**
 * Presentation state for the Chat feature.
 */
data class ChatUiState(
    val voice: VoiceUiState = VoiceUiState(),
    val dictationAvailable: Boolean = false,
    val sessionId: String = "",
    val durableSessionId: String? = null,
    val runtimeSessionId: String? = null,
    val sessionTitle: String? = null,
    val headerConnectionStatus: com.hermes.bot.domain.ConnectionStatus? = null,
    val timeline: List<TimelineEntry> = emptyList(),
    val taskProgress: TaskProgressState = TaskProgressState(),
    val approvals: com.hermes.bot.data.approval.ApprovalState = com.hermes.bot.data.approval.ApprovalState(),
    /**
     * PR-3 (plan CACHE-PR3 §6): wall clock of the last resume capture shown
     * as a cached snapshot, or null while the surface shows
     * authoritative/live state. Outside an owned background preview, non-null
     * renders the 'Saved snapshot — offline' strip and the empty-state branch;
     * cleared on loadSession entry and by every live
     * resume success (replace semantics, REQ-10). Also gates
     * isComposerReady below (SEC-03), so Send stays disabled while shown;
     * runtimeSessionId is never derived from it (REQ-09).
     */
    val savedSnapshotCapturedAtMillis: Long? = null,
    /** Temporary cache projection while the owned host read runs; never authorizes actions. */
    val isCachedHistoryPreview: Boolean = false,
    /** Covers both host hydration lanes, including the interval after the first rows arrive. */
    val isHistoryReadInFlight: Boolean = false,
    val composerDraft: String = "",
    val edit: MessageEditState? = null,
    val unknownEditId: String? = null,
    val reviewEditText: String? = null,
    val pendingImages: List<PendingImageAttachment> = emptyList(),
    val pendingFiles: List<PendingFileAttachment> = emptyList(),
    val isAttachingImage: Boolean = false,
    val isAttachingFile: Boolean = false,
    val attachmentErrorMessage: String? = null,
    /** Updated from the gateway status; tests without an adapter use the live-session gate. */
    val isAttachmentConnectionAvailable: Boolean = true,
    val isSubmitting: Boolean = false,
    val isStreaming: Boolean = false,
    val submissionStatus: SubmissionStatus = SubmissionStatus.IDLE,
    val pendingUnknownTurnId: String? = null,
    val submitBlockedReason: SubmitBlockedReason? = null,
    /**
     * TRD1 slice 1 (TASK-001): turn-scoped ExecutionTrace state for the
     * active/last confirmed local turn, or `null` when no trace exists
     * (fresh session, historical turns, after history replacement — REQ-003).
     */
    val trace: TraceState? = null,
    /**
     * PR-4 (plan CACHE-PR4 §6): phone-captured tool trace projected from the
     * conversation cache — the server-truth timeline never carries it; the
     * group renders BELOW the timeline with its provenance caption (labelled,
     * never authoritative, authorizes nothing). Populated only by the
     * hydration / cache-first / fallback projections and cleared on
     * loadSession entry (a stale group never survives — CON-01).
     */
    val cachedToolTrace: List<CachedToolTraceEntry> = emptyList(),
    /** Display provenance only: the current tail includes an admitted stored-history publication. */
    val hasAcceptedStoredHistory: Boolean = false,
    val errorMessage: String? = null,
    /** Manual-refresh lane only; never drives initial loading or turn/runtime errors. */
    val isRefreshing: Boolean = false,
    /** Fixed app-authored inline error for the manual-refresh lane only. */
    val refreshErrorMessage: String? = null,
    val isInitialLoading: Boolean = false,
    /**
     * CHAT-004 (frozen plan TASK-008): load-earlier fetch in flight. Distinct
     * from isInitialLoading — CON-003 forbids arming the hydration jump
     * (viewport yank guard) from a scroll-to-top fetch.
     */
    val isLoadingEarlier: Boolean = false,
    /** Null preserves legacy compact load-earlier behavior. */
    val storedHistoryEarlierAvailable: Boolean? = null,
    val olderHistoryRevision: Long = 0,
    /** Invalidates an in-flight viewport capture on tail replacement. */
    val historyTailRevision: Long = 0,
    /** CHAT-004 TASK-008: bounded beginning-of-history indicator state. */
    val historyBeginState: HistoryBeginState = HistoryBeginState.NONE,
    /**
     * CHAT-010 (frozen plan v1): raw host-reported model identity for the
     * current session, from session.resume info / session.info events.
     * Null = the host never reported one (row absent). Never fabricated.
     */
    val sessionModelName: String? = null,
    /** Displayed effective mode or pending requested mode; null means unknown. */
    val sessionYolo: Boolean? = null,
    val confirmedSessionYolo: Boolean? = null,
    val yoloPending: Boolean = false,
    val yoloUnconfirmed: Boolean = false,
    /**
     * CHAT-010: raw host-reported reasoning effort for the current session.
     * Blank host effort (provider default, unknown to the client) reads as
     * null; literal `none` is preserved verbatim.
     */
    val sessionReasoningEffort: String? = null,
    /** Usage changed the model without reporting its effort; suppress saved-effort fallback. */
    val sessionUsageEffortUnknown: Boolean = false,
    /**
     * CHAT-011 S2 (frozen plan TASK-006): cached per-workspace default model
     * name from the vault record (seeded once from the host info echo by the
     * ChatViewModel seed path). Null = no record / vault read failure (treated
     * as cache-miss; never fabricated). Per-field fallback only: fills the
     * caption's model field when the live session reports none.
     */
    val cachedDefaultModelName: String? = null,
    /**
     * CHAT-011 S2: cached per-workspace default reasoning effort from the
     * vault record (host vocabulary verbatim, including `none`). Per-field
     * fallback only: fills the caption's effort field when the live session
     * reports a blank effort (the owner's reported bug).
     */
    val cachedDefaultReasoningEffort: String? = null,
    val pickerOptions: List<com.hermes.bot.data.gateway.SessionModelOption> = emptyList(),
    val pickerBusy: Boolean = false,
    val pickerCatalogLoading: Boolean = false,
    val pickerChange: PickerChange? = null,
    val pickerNotice: String? = null,
    /** Unconfirmed host queue for the attached runtime, independent of catalog messages. */
    val pickerPendingNotice: String? = null,
    val pickerSwitchStarted: Boolean = false,
    val pickerConnected: Boolean = false,
    val slashMenuVisible: Boolean = false,
    val slashLoading: Boolean = false,
    val slashSuggestions: List<com.hermes.bot.data.commands.SlashSuggestion> = emptyList(),
    val slashSuggestionError: String? = null,
    val slashBusy: Boolean = false,
    val slashResults: List<com.hermes.bot.data.commands.SlashResult> = emptyList(),
    val slashNavigation: com.hermes.bot.data.commands.SlashNavigation? = null,
    val slashNavigationQuery: String = "",
    val contextUsage: ContextUsagePresentation = ContextUsagePresentation(),
    val compressionPhase: CompressionPhase = CompressionPhase.IDLE,
    val subagents: SubagentChatState = SubagentChatState(),
    val commandSuggestion: String? = null,
    val queueEnabled: Boolean = false,
    val queue: com.hermes.bot.data.queue.ChatQueueState = com.hermes.bot.data.queue.ChatQueueState(),
    val compressionNotice: String? = null
) {
    /** Loading gates actions even when an already displayed transcript remains readable. */
    val showInitialLoading: Boolean get() = isInitialLoading && timeline.isEmpty()
    val showHistoryRefresh: Boolean get() = isRefreshing || (showHistoryLoading && timeline.isNotEmpty())
    val showHistoryLoading: Boolean get() = isInitialLoading && !isCachedHistoryPreview
    val showSavedSnapshot: Boolean get() = savedSnapshotCapturedAtMillis != null && !isCachedHistoryPreview

    /**
     * CHAT-004 (frozen plan TASK-008): REACHED = the rendered window already
     * contains the full available history; NON_EXHAUSTIVE = the last fetch
     * reported messagesOmitted=true (older messages may exist on the server).
     */
    enum class HistoryBeginState { NONE, REACHED, NON_EXHAUSTIVE }
    /**
     * CHAT-010 (frozen plan v1) / CHAT-011 S2 (frozen plan §Display rules):
     * the derived caption text shown below the composer input row — the
     * single formatting seam (UI renders it verbatim, never formats). Null =
     * the row is absent (never "Unknown", never a placeholder). Mirrors the
     * isComposerReady derived-flag pattern.
     *
     * CHAT-011 S2 per-field fallback: live session values win each field
     * independently; the cached per-workspace default fills each blank gap
     * per field (model = live `sessionModelName` if non-blank else
     * `cachedDefaultModelName`; effort = live `sessionReasoningEffort` if
     * non-blank else `cachedDefaultReasoningEffort` — the owner's reported
     * bug: a session reports a model but a blank host effort = provider
     * default, which the cached default effort resolves). Caption absent
     * when no model resolves on either field.
     */
    val sessionModelLabel: String?
        get() {
            val model = activePickerChange?.model ?: sessionModelName?.takeUnless { it.isBlank() }
                ?: cachedDefaultModelName?.takeUnless { it.isBlank() }
            val effort = activePickerChange?.effort ?: sessionReasoningEffort?.takeUnless { it.isBlank() }
                ?: cachedDefaultReasoningEffort?.takeUnless { it.isBlank() }?.takeUnless { pickerSwitchStarted || sessionUsageEffortUnknown }
            return SessionModelLabelPolicy.displayLabel(model, effort)
                ?: activePickerChange?.effort?.let { "Requested reasoning: $it" }
        }
    fun holdsPickerEchoFor(runtime: String?): Boolean = pickerChange?.let {
        it.runtime == runtime && it.holdsUnappliedEcho
    } == true
    val activePickerChange: PickerChange? get() = pickerChange?.takeIf { it.runtime == runtimeSessionId }
    val pickerStatus: String? get() = activePickerChange?.notice ?: pickerNotice
    val canChangePicker: Boolean get() = canOpenPicker && !pickerBusy && activePickerChange == null && !isRefreshing && !slashBusy
    val canOpenPicker: Boolean get() = !voice.isBusy && pickerConnected && !runtimeSessionId.isNullOrBlank() &&
        !isInitialLoading && !isSubmitting && !isStreaming && savedSnapshotCapturedAtMillis == null
    /**
     * BLD-006 S3: single derived composer readiness flag. Send/IME Send are
     * enabled only when the draft is nonblank, a fresh runtime attachment
     * exists, the initial load is done, and no local turn is submitting or
     * streaming. Consumed by ChatComposer; the text field stays enabled so the
     * draft remains editable.
     */
    val isSlashCommand: Boolean get() =
        com.hermes.bot.data.commands.SlashCommandPolicy.parse(composerDraft) != null &&
            pendingImages.isEmpty() && pendingFiles.isEmpty()
    /** Idle prompts use direct send; only busy chat or existing intent needs FIFO. */
    val shouldQueuePrompt: Boolean get() = queueEnabled && (isStreaming || isSubmitting ||
        slashBusy || isCompressionBusy || queue.waiting.isNotEmpty() || queue.active != null)
    val queuesComposerInput: Boolean get() = shouldQueuePrompt ||
        (queueEnabled && isSlashCommand && !isImmediateSlash)

    val canEnqueue: Boolean get() = queueEnabled && queue.loaded && !queue.isFull &&
        edit == null && unknownEditId == null && !voice.isBusy && isAttachmentConnectionAvailable &&
        composerDraft.isNotBlank() && !runtimeSessionId.isNullOrBlank() && !isInitialLoading &&
        !isAttachingImage && !isAttachingFile && pendingImages.isEmpty() && pendingFiles.isEmpty() &&
        savedSnapshotCapturedAtMillis == null

    val isImmediateSlash: Boolean get() {
        val command = com.hermes.bot.data.commands.SlashCommandPolicy.parse(composerDraft) ?: return false
        return command.name in setOf("stop", "new", "reset", "clear", "resume", "sessions", "switch") ||
            (command.name in setOf("model", "reasoning") && command.argument.isBlank())
    }
    private val queueAllowsAttachments: Boolean get() = !queueEnabled ||
        (queue.loaded && queue.pause != com.hermes.bot.data.queue.QueuePause.STORAGE && queue.waiting.isEmpty() && queue.active == null)
    val isComposerReady: Boolean
        get() = if (queueEnabled && (queuesComposerInput || isImmediateSlash) && pendingImages.isEmpty() && pendingFiles.isEmpty()) canEnqueue ||
            (isImmediateSlash && edit == null && unknownEditId == null && !voice.isBusy &&
                !runtimeSessionId.isNullOrBlank() && savedSnapshotCapturedAtMillis == null && !isInitialLoading) else edit == null && unknownEditId == null && !voice.isBusy && isAttachmentConnectionAvailable && queueAllowsAttachments &&
            (composerDraft.isNotBlank() || pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) &&
            !runtimeSessionId.isNullOrBlank() &&
            !isInitialLoading &&
            !isRefreshing &&
            !isAttachingImage &&
            !isAttachingFile &&
            !isSubmitting &&
            (!isStreaming || isSlashCommand) &&
            !slashBusy && !isCompressionBusy && !approvals.blocksSend &&
            submitBlockedReason == null &&
            // PR-3 (§6): an offline saved snapshot never arms Send — Hermes
            // reconnect (§6 row 1 / PR-7) is what re-enables the composer.
            savedSnapshotCapturedAtMillis == null

    val canAttachImage: Boolean
        get() = edit == null && unknownEditId == null && !voice.isBusy && !runtimeSessionId.isNullOrBlank() &&
            !isInitialLoading && !isRefreshing && !isSubmitting && !isStreaming && !slashBusy && !isCompressionBusy &&
            queueAllowsAttachments &&
            !isAttachingImage && !isAttachingFile && submitBlockedReason == null &&
            savedSnapshotCapturedAtMillis == null && isAttachmentConnectionAvailable

    val isCompressionBusy: Boolean get() = compressionPhase != CompressionPhase.IDLE
    val canCompressContext: Boolean get() = if (queueEnabled) canQueueCommand else canAttachImage && !isCompressionBusy &&
        !pickerBusy && pendingUnknownTurnId == null

    val canQueueCommand: Boolean get() = queueEnabled && queue.loaded && !queue.isFull &&
        edit == null && unknownEditId == null && !voice.isBusy && isAttachmentConnectionAvailable &&
        !runtimeSessionId.isNullOrBlank() && !isInitialLoading &&
        !isAttachingImage && !isAttachingFile && pendingImages.isEmpty() && pendingFiles.isEmpty() && savedSnapshotCapturedAtMillis == null
    val canToggleYolo: Boolean get() = if (queueEnabled) canQueueCommand else sessionYolo != null && !yoloPending && !yoloUnconfirmed && canAttachImage

    val canStartDictation: Boolean get() = dictationAvailable && canAttachImage &&
        !pickerBusy && pendingUnknownTurnId == null

    val canAttachFile: Boolean get() = canAttachImage

    val attachmentDisabledReason: String?
        get() = when {
            isCachedHistoryPreview -> null
            savedSnapshotCapturedAtMillis != null -> "Reconnect to add attachments to this saved snapshot."
            runtimeSessionId.isNullOrBlank() || submitBlockedReason == SubmitBlockedReason.NO_RUNTIME ->
                null
            queueEnabled && (queue.waiting.isNotEmpty() || queue.active != null) -> "Attachments are available when the queue is empty and chat is idle."
            !isAttachmentConnectionAvailable -> "Reconnect to add attachments."
            else -> null
        }
}
