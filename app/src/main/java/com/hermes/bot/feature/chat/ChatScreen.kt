package com.hermes.bot.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.hermes.bot.feature.chat.components.ContextUsageBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermes.bot.feature.chat.components.DelegatedTaskCards
import com.hermes.bot.feature.chat.components.SubagentLiveDock
import com.hermes.bot.feature.chat.components.SubagentDetailSheet
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.bot.design.HermesTheme
import com.hermes.bot.feature.chat.ExecutionTracePolicy
import com.hermes.bot.feature.chat.TraceDisplayEntry
import com.hermes.bot.feature.chat.TraceOverallState
import com.hermes.bot.feature.chat.ToolActivityBlock
import com.hermes.bot.feature.chat.components.ActivityBottomSheet
import com.hermes.bot.feature.chat.components.AssistantMessageBubble
import com.hermes.bot.feature.chat.components.ChatComposer
import com.hermes.bot.feature.chat.components.AttachmentMenu
import com.hermes.bot.feature.chat.components.ModelPickerDialog
import com.hermes.bot.feature.chat.components.ExecutionTraceHeader
import com.hermes.bot.feature.chat.components.ExecutionTraceSurface
import com.hermes.bot.feature.chat.components.GenericGatewayCard
import com.hermes.bot.feature.chat.components.ReasoningCard
import com.hermes.bot.feature.chat.components.ToolActivityBlockCard
import com.hermes.bot.feature.chat.components.ToolActivityCard
import com.hermes.bot.feature.chat.components.UserMessageBubble
import com.hermes.bot.feature.sessions.NewChatCreateFailure
import com.hermes.bot.feature.sessions.NewChatCreateNotice
import com.hermes.bot.feature.sessions.NewChatInlineNotice
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * Exact S2 missing-runtime copy (typed, non-secret; SEC-004). The same
 * callback checks status when a runtime is attached but the turn remains gated.
 */
internal const val NO_RUNTIME_NOTICE_TEXT = "Session needs to be restored before sending."
internal const val NO_RUNTIME_ACTION_LABEL = "Restore session"
internal const val NO_RUNTIME_SEMANTICS = "Session not attached. Restore session to enable sending."
internal const val NO_RUNTIME_SUBTITLE = "Session not attached"

internal data class ChatRestoreNotice(val message: String, val action: String, val semantics: String)

/** Keep an attached, running turn distinct from a missing runtime attachment. */
internal object ChatRestoreNoticePolicy {
    fun forState(state: ChatUiState?): ChatRestoreNotice? {
        if (state?.submitBlockedReason != SubmitBlockedReason.NO_RUNTIME) return null
        if (state.runtimeSessionId.isNullOrBlank()) return ChatRestoreNotice(
            NO_RUNTIME_NOTICE_TEXT, NO_RUNTIME_ACTION_LABEL, NO_RUNTIME_SEMANTICS
        )
        return if (state.isStreaming) ChatRestoreNotice(
            "Hermes is still working. Sending will be available when this turn finishes.",
            "Check status",
            "Session is active. Check status before sending."
        ) else ChatRestoreNotice(
            "The turn outcome could not be confirmed. Check session status before sending.",
            "Check status",
            "Session status is unconfirmed. Check status before sending."
        )
    }
}

/**
 * BLD-006 S6 (TASK-027); CHAT-020 (D-02): "new activity" affordance semantics.
 * The affordance is icon-only — no visible text label is rendered on the
 * surface. The semantic description for TalkBack and the activation that
 * returns to the latest timeline row are unchanged.
 */
internal const val NEW_ACTIVITY_SEMANTICS = "New activity below. Return to the latest message."

/**
 * CHAT-004 (TASK-013, GUD-001): bounded beginning-of-history indicator copy.
 * Non-interactive list item that scrolls away (not an overlay); carries no
 * session content (SEC-001).
 */
internal const val BEGINNING_OF_HISTORY_LABEL = "Beginning of history"
internal const val BEGINNING_OF_HISTORY_SEMANTICS =
    "Beginning of history. The full available conversation history is shown."
internal const val HISTORY_NON_EXHAUSTIVE_LABEL = "Older messages may exist on the server"
internal const val HISTORY_NON_EXHAUSTIVE_SEMANTICS =
    "Older messages may exist on the server that are not shown here."
/**
 * PR-4 (plan CACHE-PR4 §6): THE provenance caption for the phone-captured
 * tool-trace group — single copy (plan Files §ChatScreen); byte-exact pinned
 * by ChatCachedToolTraceTest.
 */
internal const val CACHED_TOOL_TRACE_PROVENANCE =
    "Captured on this device; not confirmed by server history"

/**
 * PR-3 (plan CACHE-PR3 §6 REQ-09): typed copy constants beside the NO_RUNTIME
 * set — the exact strip label for an OFFLINE SAVED SNAPSHOT plus its talkback
 * semantics string, shared by the notice strip and the empty-state branch so
 * both say exactly one thing.
 */
internal const val SAVED_SNAPSHOT_LABEL = "Saved snapshot — offline"
internal const val SAVED_SNAPSHOT_SEMANTICS =
    "Saved snapshot — offline. Showing the last saved copy of this conversation."

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf", "text/plain", "text/csv", "application/rtf",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text", "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
)

/**
 * Chat screen matching specs/architecture/design/ux-navigation.md § 3. Chat
 * and specs/architecture/design/chat-rendering.md.
 *
 * Implements:
 * - Server-authoritative timeline
 * - Progressive streaming delta display
 * - Fenced code blocks with copy action and horizontal scroll
 * - Tool activity cards (collapsed by default, expandable on gesture)
 * - Safe fallback for unknown gateway events
 * - No WebView / execution surface
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenBotScreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel? = null,
    previewTimeline: List<TimelineEntry>? = null,
    onResumeOrRestore: () -> Unit = {},
    // BLD-006 S2: static presentation state for Compose semantics tests/previews
    // (mirrors the existing previewTimeline seam; ignored when a viewModel is set).
    previewUiState: ChatUiState? = null,
    // CHAT-015 B2: entry-point create-failure + degraded-default notices,
    // wired from HermesNavShell's shared SessionsViewModel.
    newChatFailure: NewChatCreateFailure? = null,
    newChatDegradedNotice: NewChatCreateNotice? = null,
    onDismissNewChatFailure: () -> Unit = {},
    onDismissNewChatDegradedNotice: () -> Unit = {},
    onNewSlashChat: () -> Unit = {},
    onBrowseSlashChats: (String) -> Unit = {},
    currentTheme: com.hermes.bot.design.AppThemePreference = com.hermes.bot.design.AppThemePreference.SYSTEM,
    recoveryState: com.hermes.bot.feature.connect.ConnectState? = null,
    onRecoverySignIn: () -> Unit = {},
    onRecoveryRetry: () -> Unit = {}
) {
    com.hermes.bot.design.ChatAtelierTheme(currentTheme) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    // BLD-006 S1: lifecycle-aware collection — no state updates while the host
    // is stopped, and collection fully stops when the composable leaves.
    val uiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value ?: previewUiState
    val fileController = viewModel?.files
    val fileState = fileController?.state?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(uiState?.timeline, uiState?.durableSessionId, uiState?.runtimeSessionId, uiState?.isAttachmentConnectionAvailable, uiState?.savedSnapshotCapturedAtMillis) {
        uiState?.let { viewModel?.updateConversationFiles(it) }
    }
    DisposableEffect(fileController) { onDispose { fileController?.close() } }
    if (fileState?.visible == true) {
        com.hermes.bot.feature.chat.files.ConversationFilesScreen(fileState, fileController)
    }
    var dictationPermissionToken by remember(viewModel) { mutableStateOf<Long?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val token = dictationPermissionToken
        dictationPermissionToken = null
        var activityContext: android.content.Context? = context
        while (activityContext is android.content.ContextWrapper && activityContext !is android.app.Activity) {
            activityContext = activityContext.baseContext
        }
        val activity = activityContext as? android.app.Activity
        val permanent = !granted && activity != null &&
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.RECORD_AUDIO)
        if (token != null) viewModel?.dictationPermissionResult(token, granted, permanent)
    }
    val startDictation: () -> Unit = {
        val token = viewModel?.requestDictationPermission()
        if (token != null) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                viewModel?.dictationPermissionResult(token, true, false)
            } else {
                dictationPermissionToken = token
                microphonePermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    var showModelPicker by remember(sessionId) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = uiState?.queue?.edit != null) { viewModel?.finishQueuedEdit(false) }
    androidx.activity.compose.BackHandler(enabled = uiState?.edit != null) { viewModel?.cancelMessageEdit() }
    androidx.activity.compose.BackHandler(enabled = uiState?.slashMenuVisible == true) { viewModel?.dismissSlashSuggestions() }
    androidx.activity.compose.BackHandler(enabled = uiState?.voice?.isBusy == true) { viewModel?.cancelDictation() }
    LaunchedEffect(uiState?.slashNavigation) {
        val destination = uiState?.slashNavigation ?: return@LaunchedEffect
        val query = uiState.slashNavigationQuery
        viewModel?.consumeSlashNavigation()
        when (destination) {
            com.hermes.bot.data.commands.SlashNavigation.MODEL -> { showModelPicker = true; viewModel?.openSessionPicker() }
            com.hermes.bot.data.commands.SlashNavigation.NEW_CHAT -> onNewSlashChat()
            com.hermes.bot.data.commands.SlashNavigation.SESSIONS -> onBrowseSlashChats(query)
        }
    }

    var showAttachmentMenu by remember(sessionId) { mutableStateOf(false) }
    var photoPickerOwnerSession by remember(sessionId) { mutableStateOf<String?>(null) }
    var documentPickerOwnerSession by remember(sessionId) { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        if (photoPickerOwnerSession == sessionId) {
            selected?.let { viewModel?.onPhotoSelected(it.toString()) }
        }
        photoPickerOwnerSession = null
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (documentPickerOwnerSession == sessionId) {
            selected?.let { viewModel?.onDocumentSelected(it.toString()) }
        }
        documentPickerOwnerSession = null
    }
    if (showModelPicker) {
        // Settings-style dialog. Picker requests and their outcomes remain
        // owned by ChatViewModel; the presentation forwards intents only.
        ModelPickerDialog(
                options = uiState?.pickerOptions ?: emptyList(),
                currentModelName = uiState?.sessionModelName,
                currentReasoningEffort = uiState?.activePickerChange?.effort ?: uiState?.sessionReasoningEffort,
                sessionModelLabel = uiState?.sessionModelLabel,
                pickerBusy = uiState?.pickerBusy == true,
                pickerNotice = uiState?.pickerNotice,
                pickerPendingNotice = uiState?.activePickerChange?.notice,
                catalogLoading = uiState?.pickerCatalogLoading == true,
                canChangePicker = uiState?.canChangePicker == true,
                requestedProvider = uiState?.activePickerChange?.provider,
                onCheckStatus = if (uiState?.activePickerChange != null) ({ viewModel?.checkPickerStatus(); Unit }) else null,
                canOpenPicker = uiState?.canOpenPicker == true,
                onRefresh = { viewModel?.openSessionPicker() },
                onPickModel = { viewModel?.pickSessionModel(it) },
                onPickReasoning = { viewModel?.pickSessionReasoning(it) },
                onDismiss = { showModelPicker = false }
        )
    }

    if (uiState?.contextUsage?.visible == true) {
        ContextUsageBottomSheet(uiState,
            onRefresh = { viewModel?.refreshContextUsage() },
            onToggleBreakdown = { viewModel?.toggleContextBreakdown() },
            onCompress = { viewModel?.compressContext() },
            onDismiss = { viewModel?.closeContextUsage() },
            onDismissCompressionNotice = { viewModel?.dismissCompressionNotice() })
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { viewModel?.setQueueVisibility(true); viewModel?.setSubagentVisibility(true); viewModel?.setApprovalVisibility(true) }
            if (event == Lifecycle.Event.ON_STOP) { fileController?.background(); viewModel?.setQueueVisibility(false, pauseOnHide = (context as? android.app.Activity)?.isChangingConfigurations != true); viewModel?.setSubagentVisibility(false); viewModel?.setApprovalVisibility(false); viewModel?.cancelDictation() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel?.setSubagentVisibility(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        viewModel?.setApprovalVisibility(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        viewModel?.setQueueVisibility(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); viewModel?.setQueueVisibility(false, pauseOnHide = (context as? android.app.Activity)?.isChangingConfigurations != true); viewModel?.setSubagentVisibility(false); viewModel?.setApprovalVisibility(false); viewModel?.cancelDictation() }
    }
    uiState?.let { state ->
        SubagentDetailSheet(state.subagents, onBack = { viewModel?.backSubagent() }, onDismiss = { viewModel?.closeSubagent() },
            onDraft = { viewModel?.updateSubagentDraft(it) }, onSteer = { viewModel?.steerSubagent() },
            onStop = { viewModel?.stopSubagent() }, onTranscript = { viewModel?.viewSubagentTranscript() })
    }

    val timeline = uiState?.timeline ?: previewTimeline ?: emptyList()
    val displayEntries = remember(timeline, uiState?.trace) {
        // Only the conversation and its normal Actions belong in chat.
        // Device-captured observations remain stored but are never displayed here.
        SubagentPresentation.display(ExecutionTracePolicy.buildBlockDisplayEntries(
            timeline,
            uiState?.trace
        ))
    }

    var activeSheetBlock by remember { mutableStateOf<ToolActivityBlock?>(null) }
    var activeSheetStepId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(displayEntries) {
        val currentBlock = activeSheetBlock
        if (currentBlock != null) {
            val updatedBlockCard = displayEntries
                .filterIsInstance<TraceDisplayEntry.BlockCard>()
                .firstOrNull { it.block.id == currentBlock.id }
            if (updatedBlockCard != null) {
                activeSheetBlock = updatedBlockCard.block
                if (activeSheetStepId != null && updatedBlockCard.block.steps.none { it.id == activeSheetStepId }) {
                    activeSheetStepId = null
                }
            } else {
                activeSheetBlock = null
                activeSheetStepId = null
            }
        }
    }
    // CHAT-009 (FZ-5/FZ-13): day separators are a remember-derivation on the
    // display list, OUTSIDE the trace grouping (FZ-14 — headers neither
    // trigger nor receive separators). The zone is captured once per
    // derivation; `today` from the same clock so Today/Yesterday labels stay
    // coherent. Pure logic lives in ChatDaySeparatorPolicy (JVM-tested).
    val displayEntriesWithDays = remember(displayEntries) {
        val zone = ZoneId.systemDefault()
        ChatDaySeparatorPolicy.withDaySeparators(displayEntries, zone, LocalDate.now(zone))
    }
    val trace = uiState?.trace
    val isStreaming = uiState?.isStreaming ?: false
    val isSubmitting = uiState?.isSubmitting ?: false
    val isRefreshing = uiState?.showHistoryRefresh ?: false
    val isInitialLoading = uiState?.isInitialLoading ?: false
    val showInitialLoading = uiState?.showInitialLoading ?: false
    val composerDraft = uiState?.composerDraft ?: ""
    // The blocked state gates Send. The shell injects one resume/status-check
    // callback for both layouts; it fires only on user activation here.
    val restoreNotice = ChatRestoreNoticePolicy.forState(uiState)
    val isBlocked = restoreNotice != null

    val listState = key(sessionId) { rememberLazyListState() }
    if (ChatRenderProbe.layout != null) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo to listState.canScrollForward }
                .collect { ChatRenderProbe.layout?.invoke(it.second) }
        }
    }
    var followingLatest by remember(sessionId) { mutableStateOf(true) }
    var userMovingToBottom by remember(sessionId) { mutableStateOf(false) }
    var explicitJump by remember(sessionId) { mutableStateOf(false) }
    var hydrationJump by remember(sessionId) { mutableStateOf(false) }
    var preservePrepend by remember(sessionId) { mutableStateOf(false) }
    var jumpAnimation by remember(sessionId) { mutableStateOf<Job?>(null) }
    var automaticScroll by remember(sessionId) { mutableStateOf<Job?>(null) }
    // User intent is latched: layout growth must not masquerade as scrolling away.
    val userScrollConnection = remember(listState, sessionId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    userMovingToBottom = false
                    automaticScroll?.cancel()
                    jumpAnimation?.cancel()
                    explicitJump = false
                    hydrationJump = false
                    followingLatest = TimelineFollowPolicy.followingAfterScroll(
                        followingLatest, movingToEarlier = true, atBottom = false
                    )
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y < 0f) userMovingToBottom = true
                if (userMovingToBottom && consumed.y < 0f) {
                    followingLatest = TimelineFollowPolicy.followingAfterScroll(
                        followingLatest, movingToEarlier = false, atBottom = !listState.canScrollForward
                    )
                    if (followingLatest) preservePrepend = false
                }
                return Offset.Zero
            }
        }
    }
    val tailRevision = uiState?.historyTailRevision ?: 0L
    val olderRevision = uiState?.olderHistoryRevision ?: 0L
    var olderAnchor by remember(sessionId, tailRevision) { mutableStateOf<OlderHistoryAnchor?>(null) }
    var olderAnchorUserMoved by remember(sessionId, tailRevision) { mutableStateOf(false) }
    val actualDisplayKeys = remember(displayEntriesWithDays, uiState?.historyBeginState) {
        (if (uiState?.historyBeginState != null && uiState.historyBeginState != ChatUiState.HistoryBeginState.NONE)
            listOf("history-begin-indicator") else emptyList()) + displayEntriesWithDays.map { it.key }
    }
    LaunchedEffect(uiState?.isLoadingEarlier) {
        if (uiState?.isLoadingEarlier == true) snapshotFlow { listState.isScrollInProgress }
            .collect { if (it) olderAnchorUserMoved = true }
    }


    // BLD-006 S6: user-controlled timeline following (D-05). Follow state is
    // derived only from the scroll viewport and the admitted timeline — never
    // from connection/replay state (TASK-029 stop condition honored).
    val followStateMachine = remember(sessionId) { TimelineFollowStateMachine() }
    val timelineContentSignature = remember(timeline) {
        TimelineFollowPolicy.contentSignature(timeline)
    }
    LaunchedEffect(showInitialLoading) {
        if (showInitialLoading) {
            // Hydration jump request: consumed by the first non-empty timeline
            // change so initial history lands on the message end instantly and
            // never animates row-by-row (TASK-027).
            followStateMachine.markHydrationStarted()
        }
    }
    LaunchedEffect(timelineContentSignature, olderRevision) {
        when (followStateMachine.onTimelineChanged(followingLatest, timeline.isNotEmpty(), olderRevision)) {
            FollowDecision.JUMP_TO_LATEST -> hydrationJump = true
            FollowDecision.FOLLOW_LATEST -> preservePrepend = false
            FollowDecision.PRESERVE_VIEWPORT -> if (olderAnchor != null) preservePrepend = true
        }
    }
    // Read current composition inputs from one long-lived layout collector. Text
    // publication can precede formatting by several frames; layout is the trigger.
    val currentDisplayKeys by rememberUpdatedState(actualDisplayKeys)
    val currentOlderRevision by rememberUpdatedState(olderRevision)
    val currentTailRevision by rememberUpdatedState(tailRevision)
    val loadingEarlier by rememberUpdatedState(uiState?.isLoadingEarlier == true)
    val hasTimeline by rememberUpdatedState(timeline.isNotEmpty())
    LaunchedEffect(listState, sessionId) {
        snapshotFlow {
            val layout = listState.layoutInfo
            TimelineLayoutSignal(
                followingLatest, explicitJump, hydrationJump, preservePrepend,
                olderAnchor, currentOlderRevision, currentTailRevision, hasTimeline, loadingEarlier,
                listState.canScrollForward, layout.totalItemsCount,
                layout.viewportStartOffset, layout.viewportEndOffset,
                layout.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) },
                jumpAnimation?.isActive == true
            )
        }.conflate().collect {
            withFrameNanos { }
            val anchor = olderAnchor
            if (anchor != null && currentOlderRevision != anchor.olderRevision) {
                jumpAnimation?.cancel()
                TimelineFollowPolicy.olderAnchorTarget(anchor, currentDisplayKeys,
                    currentTailRevision, olderAnchorUserMoved)?.let { (index, offset) ->
                    runTimelineScroll({ automaticScroll = it }) {
                        listState.scrollToItem(index, offset)
                    }
                }
                olderAnchor = null
                preservePrepend = true
                hydrationJump = false
                return@collect
            }
            // Failed/no-op fetches do not advance the revision. Their anchor
            // must not indefinitely block subsequent layout following.
            if (anchor != null && !TimelineFollowPolicy.anchorPending(anchor, loadingEarlier, currentOlderRevision)) {
                olderAnchor = null
            }
            if (!followingLatest) {
                jumpAnimation?.cancel()
                return@collect
            }
            if (!hasTimeline || olderAnchor != null) return@collect
            if (explicitJump) {
                explicitJump = false
                preservePrepend = false
                hydrationJump = false
                jumpAnimation?.cancelAndJoin()
                if (!followingLatest) return@collect
                jumpAnimation = launch {
                    try {
                        listState.animateScrollToItem(TimelineFollowPolicy.bottomTargetIndex(currentDisplayKeys.size))
                    } finally {
                        jumpAnimation = null
                    }
                }
            } else if (TimelineFollowPolicy.shouldCorrectLayout(
                    followingLatest, listState.canScrollForward, hydrationJump,
                    preservePrepend, jumpAnimation?.isActive == true)) {
                hydrationJump = false
                runTimelineScroll({ automaticScroll = it }) {
                    listState.scrollToItem(TimelineFollowPolicy.bottomTargetIndex(currentDisplayKeys.size))
                }
            }
        }
    }
    // CHAT-004 (TASK-012): scroll-to-top edge gate feeding the frozen
    // LoadEarlierTriggerPolicy — fires one bounded history fetch per gesture.
    // The policy instance persists across recompositions (stateful edge gate).
    val loadEarlierTriggerPolicy = remember(sessionId) { LoadEarlierTriggerPolicy(initialAtTop = false) }
    if (viewModel != null) {
        LaunchedEffect(
            uiState?.isInitialLoading,
            uiState?.isLoadingEarlier,
            uiState?.historyBeginState,
            uiState?.storedHistoryEarlierAvailable
        ) {
            snapshotFlow { listState.firstVisibleItemIndex == 0 }
                .distinctUntilChanged()
                .collect { atTop ->
                    val fire = loadEarlierTriggerPolicy.onAtTopChanged(
                        atTop = atTop,
                        fetchInFlight = uiState?.isLoadingEarlier == true,
                        historyBeginningReached =
                            uiState?.historyBeginState == ChatUiState.HistoryBeginState.REACHED ||
                                uiState?.storedHistoryEarlierAvailable == false,
                        isInitialLoading = uiState?.isInitialLoading == true,
                        timelineNonEmpty = timeline.isNotEmpty()
                    )
                    if (fire) {
                        olderAnchor = OlderHistoryAnchor(tailRevision, olderRevision,
                            listState.layoutInfo.visibleItemsInfo.map {
                                it.key.toString() to if (it.index == listState.firstVisibleItemIndex)
                                    listState.firstVisibleItemScrollOffset else listState.layoutInfo.beforeContentPadding - it.offset
                            })
                        olderAnchorUserMoved = false
                        viewModel.loadEarlier()
                    }
                }
        }
    }

    // Affordance visibility derives from one snapshot of the scroll layout to
    // avoid reading @FrequentlyChangingValue state repeatedly in composition.
    // Reading `layoutInfo` once inside `derivedStateOf` still tracks changes
    // but recomputes only when the visible-window snapshot differs, and keeps
    // the frequently-changing read out of direct per-frame composition.
    // PR #40 review F1: every non-snapshot input (`timeline`,
    // `isInitialLoading`) is a remember key — an unkeyed `derivedStateOf`
    // captured them by value at first composition, freezing the affordance in
    // both the new-session (empty list) and hydrated-session (initial-loading)
    // flows. The viewport snapshot remains the only snapshot-state read.
    val showNewActivityAffordance by remember(timeline, isInitialLoading) {
        derivedStateOf {
            val layout = listState.layoutInfo
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = LazyListViewport(
                    firstVisibleItemIndex = layout.visibleItemsInfo.firstOrNull()?.index ?: 0,
                    lastVisibleItemIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    totalItemsCount = layout.totalItemsCount,
                    canScrollForward = listState.canScrollForward
                ),
                timelineNonEmpty = timeline.isNotEmpty(),
                isInitialLoading = isInitialLoading
            )
        }
    }

    // BLD-006 S11 (TASK-050, D-11): external-link confirmation state. The
    // pending target is kept as the already-validated canonical link; the
    // dialog discloses ONLY the canonical host (+ non-default port) and the
    // target is revalidated immediately before the ACTION_VIEW intent is
    // created. Never a WebView or deep link: the only way out is the system
    // browser via ACTION_VIEW, and only after explicit user confirmation.
    var pendingLinkToOpen by remember { mutableStateOf<ExternalLinkPolicy.CanonicalLink?>(null) }

    // Visible, non-secret failure when no external browser (resolver) exists.
    // D-11 requires this never to be silently swallowed.
    var linkLaunchFailed by remember { mutableStateOf(false) }
    if (linkLaunchFailed) {
        AlertDialog(
            onDismissRequest = { linkLaunchFailed = false },
            title = { Text("Cannot open link") },
            text = { Text("No external browser is available to open this link.") },
            confirmButton = {
                TextButton(
                    onClick = { linkLaunchFailed = false },
                    // CHG-008 repair round 1 (P1-A): dialog labels default to the
                    // primary fill token, which is 2.93:1 on the elevated dialog
                    // container.
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = HermesTheme.extendedColors.primaryText
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("OK")
                }
            }
        )
    }

    pendingLinkToOpen?.let { confirmedCandidate ->
        AlertDialog(
            onDismissRequest = { pendingLinkToOpen = null },
            title = { Text("Open external link") },
            text = {
                Text(
                    "This link leaves the app and opens in your external browser.\n\n" +
                        "Destination: " + ExternalLinkPolicy.canonicalDisclosure(confirmedCandidate)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLinkToOpen = null
                        // Launch-time revalidation of the exact target: the link
                        // may have been mutated between confirmation and launch;
                        // a mismatched or re-rejected target never launches.
                        val revalidated = ExternalLinkPolicy.revalidateForLaunch(confirmedCandidate)
                        if (revalidated != null) {
                            try {
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(revalidated.url)
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(browserIntent)
                            } catch (_: Exception) {
                                // TASK-050: a missing resolver is a visible,
                                // non-secret failure — never silently swallowed.
                                linkLaunchFailed = true
                            }
                        }
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingLinkToOpen = null },
                    // CHG-008 repair round 1 (P1-A): same dialog-label fix.
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = HermesTheme.extendedColors.primaryText
                    ),
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        bottomBar = {
            BoxWithConstraints(modifier = Modifier.imePadding()) {
                // Leave room for the editable draft, caption row and Back header at large fonts.
                val approvalHeight = maxHeight * 0.35f
                val queueHeight = maxHeight * 0.30f
                val editScroll = rememberScrollState()
                // Keep Back reachable and let the editor scroll instead of crushing its input under the IME.
                val editorBounds = if (uiState?.edit != null) Modifier
                    .heightIn(max = (maxHeight - 64.dp).coerceAtLeast(48.dp))
                    .verticalScroll(editScroll) else Modifier
                Column(editorBounds) {
                    uiState?.let { SubagentLiveDock(it.subagents, { viewModel?.toggleSubagentDock() }, { child -> viewModel?.openSubagent(child) }) }
                    uiState?.let { state ->
                        com.hermes.bot.feature.chat.components.TaskProgressPanel(
                            state = state.taskProgress,
                            connected = state.headerConnectionStatus == com.hermes.bot.domain.ConnectionStatus.CONNECTED,
                            onToggle = { viewModel?.toggleTaskProgress() }
                        )
                    }
                    uiState?.let { state ->
                        com.hermes.bot.feature.chat.components.ApprovalCard(state.approvals, maxHeight = approvalHeight,
                            onDecide = { id, choice -> viewModel?.decideApproval(id, choice) },
                            onCheck = { viewModel?.checkApprovals() })
                    }
                    if (uiState?.unknownEditId != null) {
                        Text("Edit outcome unknown. Review the current history before resending.",
                            modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { viewModel?.loadSession() }, enabled = !isSubmitting && !isRefreshing) { Text("Check history") }
                            TextButton(onClick = { viewModel?.reviewUnknownEdit() }, enabled = uiState.isInitialLoading.not() && !isRefreshing) { Text("Review edit") }
                        }
                    }
                    uiState?.let { state ->
                        if (state.commandSuggestion != null) TextButton(onClick = { viewModel?.useCommandSuggestion() },
                            enabled = state.composerDraft.isEmpty()) { Text("Use suggested text") }
                        com.hermes.bot.feature.chat.components.ChatQueuePresentation(state.queue, state.isStreaming, queueHeight,
                            onPause = { viewModel?.pauseQueue() }, onResume = { viewModel?.resumeQueue() },
                            onCancel = { viewModel?.cancelQueuedItem(it) }, onEdit = { viewModel?.editQueuedItem(it) },
                            onEditText = { viewModel?.updateQueuedEdit(it) }, onFinishEdit = { viewModel?.finishQueuedEdit(it) },
                            onCheck = { viewModel?.checkQueueStatus() }, onDismiss = { viewModel?.dismissQueuedOutcome() },
                            onRetry = { viewModel?.retryQueuedItem() })
                    }
                    ChatComposer(
                        queueEnabled = uiState?.queuesComposerInput == true && uiState.pendingImages.isEmpty() && uiState.pendingFiles.isEmpty(),
                        draftText = uiState?.edit?.text ?: composerDraft,
                        edit = uiState?.edit,
                        canResendEdit = uiState?.canResendEdit() == true,
                        onCancelEdit = { viewModel?.cancelMessageEdit() },
                        voice = uiState?.voice ?: VoiceUiState(),
                        dictationAvailable = uiState?.dictationAvailable == true,
                        canStartDictation = uiState?.canStartDictation == true,
                        onDictationClick = startDictation,
                        onStopDictation = { viewModel?.stopDictation() },
                        onCancelDictation = { viewModel?.cancelDictation() },
                        onDismissDictationMessage = { viewModel?.dismissDictationMessage() },
                        onOpenMicrophoneSettings = {
                            try { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { }
                        },
                        onDraftChange = { viewModel?.updateDraft(it) },
                        onSend = { viewModel?.submitPrompt() },
                        onStopStreaming = { viewModel?.stopStreaming() },
                        isSubmitting = isSubmitting,
                        isStreaming = isStreaming,
                        isSendBlocked = isBlocked,
                        // BLD-006 S3: the single derived readiness flag gates Send and
                        // IME Send (draft nonblank, fresh runtime, not loading, no
                        // submit in progress, no local turn streaming, not blocked).
                        // PR-34 review F3 (allowed-path deviation, documented): the S3
                        // plan's frozen allowed paths were ChatViewModel.kt,
                        // ChatUiState.kt, components/ChatComposer.kt, and matching test
                        // sources; this call-site-only `isComposerReady =` pass-through
                        // (+4/−1) on ChatScreen.kt was a deliberate deviation required
                        // by TASK-011's "consumed by ChatComposer" — the flag is owned
                        // by ChatUiState and must flow through the one call site that
                        // instantiates ChatComposer. Legitimate in substance; recorded
                        // here so closeout does not re-flag it.
                        isComposerReady = uiState?.isComposerReady,
                        pendingImages = uiState?.pendingImages ?: emptyList(),
                        pendingFiles = uiState?.pendingFiles ?: emptyList(),
                        attachmentErrorMessage = uiState?.attachmentErrorMessage,
                        onDismissAttachmentMessage = if (viewModel != null) {
                            { viewModel.dismissAttachmentMessage() }
                        } else null,
                        attachmentDisabledReason = uiState?.attachmentDisabledReason,
                        onRemoveImage = if (uiState?.canAttachImage == true) {
                            { path -> viewModel?.removePendingImage(path) }
                        } else null,
                        onRemoveFile = if (uiState?.canAttachFile == true) {
                            { refText -> viewModel?.removePendingFile(refText) }
                        } else null,
                        onAttachmentClick = if (uiState?.canAttachImage == true && viewModel != null) {
                            { showAttachmentMenu = true }
                        } else null,
                        attachmentMenu = {
                            AttachmentMenu(
                                expanded = showAttachmentMenu,
                                onPhotos = {
                                    showAttachmentMenu = false
                                    photoPickerOwnerSession = sessionId
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onDocuments = {
                                    showAttachmentMenu = false
                                    documentPickerOwnerSession = sessionId
                                    documentPicker.launch(DOCUMENT_MIME_TYPES)
                                },
                                onDismiss = { showAttachmentMenu = false }
                            )
                        },
                        // CHAT-010: the derived session model + reasoning label —
                        // pass-through only (the caption row lives in ChatComposer).
                        // CHAT-016 TASK-017 (OQ-001 resolution): the caption row is
                        // unconditionally tappable; openSessionPicker() keeps its
                        // runtime/canOpenPicker guards (safe no-op without a runtime),
                        // so the dead enablement argument is removed.
                        sessionModelLabel = uiState?.sessionModelLabel,
                        modelChangeStatus = uiState?.pickerStatus,
                        modelChangePending = uiState?.activePickerChange?.phase in setOf(PickerChange.Phase.WRITING, PickerChange.Phase.CONFIRMING),
                        sessionYolo = uiState?.sessionYolo,
                        yoloPending = uiState?.yoloPending == true,
                        canToggleYolo = uiState?.canToggleYolo == true,
                        onToggleYolo = { viewModel?.toggleYolo() },
                        onOpenContextUsage = if (sessionId != "new-session" && viewModel != null) ({
                            keyboard?.hide()
                            focusManager.clearFocus()
                            viewModel.openContextUsage()
                        }) else null,
                        onOpenPicker = { showModelPicker = true; viewModel?.openSessionPicker() },
                        isSlashCommand = uiState?.isSlashCommand == true,
                        slashContent = {
                            com.hermes.bot.feature.chat.components.SlashCommandPanel(
                                visible = uiState?.slashMenuVisible == true,
                                loading = uiState?.slashLoading == true,
                                suggestions = uiState?.slashSuggestions ?: emptyList(),
                                error = uiState?.slashSuggestionError,
                                busy = uiState?.slashBusy == true,
                                result = uiState?.slashResults?.lastOrNull(),
                                onChoose = { viewModel?.chooseSlashSuggestion(it) },
                                onDismiss = { viewModel?.dismissSlashSuggestions() },
                                onDismissResult = { viewModel?.dismissSlashResults() }
                            )
                        }
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Mockup header: Back, authoritative title/connection, Refresh, future options.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (uiState?.edit != null) viewModel?.cancelMessageEdit()
                        else if (uiState?.voice?.isBusy == true) viewModel?.cancelDictation()
                        else if (uiState?.slashMenuVisible == true) viewModel?.dismissSlashSuggestions() else onNavigateBack() },
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = "Back to sessions"
                        }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to sessions"
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        text = ChatScreenTopBarPolicy.title(uiState?.sessionTitle, sessionId == "new-session"),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(5.dp).background(
                            if (ChatScreenTopBarPolicy.isConnected(uiState)) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = androidx.compose.foundation.shape.CircleShape))
                        Text(ChatScreenTopBarPolicy.connectionLabel(uiState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onOpenBotScreen != null) TextButton(onClick = onOpenBotScreen) { Text("Screen") }
                    if (sessionId != "new-session" && fileController != null) {
                        TextButton(onClick = fileController::show) { Text("Files") }
                    }
                    if (sessionId != "new-session") {
                        IconButton(
                            onClick = { viewModel?.refreshSession() },
                            enabled = !isRefreshing && !isSubmitting && !isStreaming,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "Refresh chat" }
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh chat"
                                )
                            }
                        }
                    }
                }
            }
            if (recoveryState == com.hermes.bot.feature.connect.ConnectState.PREFLIGHT_LOADING ||
                uiState?.isInitialLoading == true) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (recoveryState == com.hermes.bot.feature.connect.ConnectState.PREFLIGHT_LOADING) "Connecting…" else "Syncing…",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            } else if (recoveryState == com.hermes.bot.feature.connect.ConnectState.AUTH_REQUIRED) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign in required", modifier = Modifier.weight(1f))
                    TextButton(onClick = onRecoverySignIn) { Text("Sign in") }
                }
            } else if (recoveryState == com.hermes.bot.feature.connect.ConnectState.HOST_UNREACHABLE) {
                TextButton(onClick = onRecoveryRetry) { Text("Retry connection") }
            }
            if (uiState?.refreshErrorMessage != null) {
                ChatRefreshErrorNotice(
                    message = uiState.refreshErrorMessage,
                    canRetry = !isRefreshing && !isSubmitting && !isStreaming,
                    onRetry = { viewModel?.refreshSession() },
                    onDismiss = { viewModel?.dismissRefreshError() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            // CHAT-015 B2: the shared dismissible entry-point notice surface,
            // anchored under the chat action row. Failure notice first;
            // the degraded-default notice is bound to this chat's session id so
            // it clears when the active session changes.
            if (newChatFailure != null) {
                NewChatInlineNotice(
                    message = newChatFailure.message,
                    onDismiss = onDismissNewChatFailure,
                    isError = true,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            val degradedNotice = newChatDegradedNotice?.takeIf { it.sessionId == sessionId }
            if (degradedNotice != null) {
                NewChatInlineNotice(
                    message = degradedNotice.message,
                    onDismiss = onDismissNewChatDegradedNotice,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            // Render the appropriate recovery notice while blocked and idle.
            // The spinner wins during a resume already in flight.
            if (restoreNotice != null && !isInitialLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp)
                        .semantics {
                            contentDescription = restoreNotice.semantics
                            onClick(label = restoreNotice.action) { onResumeOrRestore(); true }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = restoreNotice.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onResumeOrRestore,
                        // CHG-008 repair round 1 (P1-A): this button sits on the
                        // #1E202B notice strip; the default primary label colour
                        // is 2.93:1 there.
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = HermesTheme.extendedColors.primaryText
                        ),
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(restoreNotice.action)
                    }
                }
            }
            // PR-3 (REQ-09): the surface currently renders an OFFLINE SAVED
            // SNAPSHOT — label it explicitly. Deliberately non-interactive: the
            // snapshot path never arms NO_RUNTIME, which is the PR-7 lane's job.
            if (uiState?.savedSnapshotCapturedAtMillis != null && !showInitialLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { contentDescription = SAVED_SNAPSHOT_SEMANTICS },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = SAVED_SNAPSHOT_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
            if (showInitialLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (timeline.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (restoreNotice != null) {
                        // S2: the generic empty-state copy invites sending, which
                        // is wrong while sending is blocked — show the notice instead.
                        Text(
                            text = restoreNotice.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (uiState?.savedSnapshotCapturedAtMillis != null) {
                        // PR-3 (REQ-09): an empty saved snapshot is still labelled
                        // as such — never the "send a prompt" invitation, because
                        // sending is offline-blocked here.
                        Text(
                            text = SAVED_SNAPSHOT_LABEL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No messages yet in this session.\nSend a prompt below to start the conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
                    // The spacing before the zero-height end target supplies the bottom inset.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val historyBeginState = uiState?.historyBeginState
                        ?: ChatUiState.HistoryBeginState.NONE
                    if (historyBeginState != ChatUiState.HistoryBeginState.NONE) {
                        // CHAT-004 (TASK-013): bounded indicator as the FIRST
                        // list item — scrolls away, non-interactive, styled
                        // like the existing dim captions (GUD-001).
                        item(key = "history-begin-indicator") {
                            val label = if (historyBeginState ==
                                ChatUiState.HistoryBeginState.NON_EXHAUSTIVE
                            ) {
                                HISTORY_NON_EXHAUSTIVE_LABEL
                            } else {
                                BEGINNING_OF_HISTORY_LABEL
                            }
                            val semantics = if (historyBeginState ==
                                ChatUiState.HistoryBeginState.NON_EXHAUSTIVE
                            ) {
                                HISTORY_NON_EXHAUSTIVE_SEMANTICS
                            } else {
                                BEGINNING_OF_HISTORY_SEMANTICS
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = semantics }
                            )
                        }
                    }
                    items(
                        items = displayEntriesWithDays,
                        key = { it.key }
                    ) { displayItem ->
                        key(sessionId) {
                        when (displayItem) {
                            is TraceDisplayEntry.DaySeparator -> {
                                // CHAT-009 (FZ-14): full-width centered dim
                                // label; non-interactive (GUD-001), same
                                // visual family as the other dim captions.
                                Text(
                                    text = displayItem.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            }

                            is TraceDisplayEntry.Header -> {
                                // TRD1 slice 1 (TASK-008): live while the turn
                                // streams; frozen terminal elapsed after the
                                // seal (REQ-003).
                                ExecutionTraceSurface(
                                    header = {
                                        ExecutionTraceHeader(
                                            trace = displayItem.trace,
                                            actionCount = ExecutionTracePolicy.actionCount(
                                                timeline,
                                                displayItem.trace.turnId
                                            ),
                                            elapsedText = ExecutionTracePolicy.elapsedText(
                                                displayItem.trace.startedAtMillis,
                                                displayItem.trace.endedAtMillis,
                                                System.currentTimeMillis()
                                            ),
                                            isLive = isStreaming &&
                                                displayItem.trace.overallState == TraceOverallState.RUNNING
                                        )
                                    },
                                    content = {}
                                )
                            }

                            is TraceDisplayEntry.BlockCard -> {
                                ToolActivityBlockCard(
                                    block = displayItem.block,
                                    onHeaderClick = {
                                        activeSheetBlock = displayItem.block
                                        activeSheetStepId = null
                                    },
                                    onStepClick = { stepId ->
                                        activeSheetBlock = displayItem.block
                                        activeSheetStepId = stepId
                                    }
                                )
                            }

                            // Legacy projection helpers can still produce this type;
                            // the chat screen never renders device-captured observations.
                            is TraceDisplayEntry.CachedTrace -> Unit

                            is TraceDisplayEntry.Raw -> when (val entry = displayItem.entry) {
                            is UserMessage -> {
                                UserMessageBubble(
                                    message = entry,
                                    onEdit = if (uiState?.canEdit(entry) == true) ({ viewModel?.beginMessageEdit(entry.id) }) else null,
                                    onRetryAsNewPrompt = { viewModel?.retryAmbiguousPrompt() },
                                    onCheckHistory = { viewModel?.loadSession() },
                                    // CHAT-019 repair round 2 (PROD-003):
                                    // defense-in-depth with the ViewModel fence —
                                    // the retry affordance is disabled while the
                                    // manual refresh is in flight, exactly like
                                    // the refresh control and its Retry notice.
                                    retryEnabled = !isRefreshing
                                )
                            }

                            is AssistantMessage -> {
                                Column {
                                AssistantMessageBubble(
                                    message = entry,
                                    onUrlClick = { url ->
                                        // The click hands over the already-policy-
                                        // validated canonical link target only.
                                        ExternalLinkPolicy.validate(url)
                                            ?.let { pendingLinkToOpen = it }
                                    }
                                )
                                fileState?.byMessage?.get(entry.id)?.take(3)?.forEach { file ->
                                    com.hermes.bot.feature.chat.files.ConversationFileCard(file) { fileController?.open(file) }
                                }
                                }
                            }

                            is ToolActivity -> {
                                fileState?.byMessage?.get(entry.id)?.take(3)?.forEach { file ->
                                    com.hermes.bot.feature.chat.files.ConversationFileCard(file) { fileController?.open(file) }
                                }
                                if (entry.delegatedTasks.isNotEmpty()) {
                                    DelegatedTaskCards(entry, timeline, uiState?.subagents ?: SubagentChatState(), { viewModel?.openSubagent(it) })
                                } else ToolActivityCard(
                                    activity = entry,
                                    onToggleExpand = { viewModel?.toggleToolExpanded(entry.id) }
                                )
                            }

                            is GenericGatewayActivity -> {
                                GenericGatewayCard(
                                    activity = entry,
                                    onToggleExpand = { viewModel?.toggleGenericActivityExpanded(entry.id) }
                                )
                            }

                            is ReasoningEntry -> {
                                ReasoningCard(
                                    entry = entry,
                                    onToggleExpand = { viewModel?.toggleReasoningExpanded(entry.id) }
                                )
                            }

                            is TurnNotice -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            when (entry.type) {
                                                NoticeType.ERROR -> MaterialTheme.colorScheme.errorContainer
                                                NoticeType.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
                                                NoticeType.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
                                            }
                                        )
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "${entry.title}: ${entry.message}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            is ConnectionNotice -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            is LocalDraftNotice -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = entry.message,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            is ModelChangeNoticeRow -> {
                                // CHAT-017 v2 (REQ-003): the quietest visual
                                // tier in the timeline — plain light-gray
                                // text, full-width, NO background fill, no
                                // padding box, no clicks/semantics (a change
                                // log line, not a banner).
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            is AgentNoteEntry -> {
                                // CHAT-018 PR-A (A5): defensive orphan render —
                                // live notes always sit inside a turn's card via
                                // buildBlockDisplayEntries; this branch exists
                                // only so the exhaustive `when` stays total
                                // (ModelChangeNoticeRow styling precedent).
                                // NEVER routed to AssistantMessageBubble.
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            }
                        }
                    }
                    }
                    item(key = "timeline-bottom") {
                        Spacer(Modifier.height(0.dp))
                    }
                }
            }
            // BLD-006 S6 (TASK-027, PR #40 review minor b): accessible "new
            // activity" affordance. Visible, semantic, non-color-only, >=48dp
            // target; returns to the latest row on activation. Rendered only
            // while unpinned and there is content to return to. Declared AFTER
            // the timeline content so message bubbles cannot draw over it
            // (Compose draws later children of a Box on top).
            if (showNewActivityAffordance) {
                TextButton(
                    onClick = {
                        followingLatest = true
                        explicitJump = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = NEW_ACTIVITY_SEMANTICS
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                    // CHAT-020 (D-02): icon-only affordance — no visible text
                    // label. TalkBack announcement comes from the semantics
                    // contentDescription above (NEW_ACTIVITY_SEMANTICS).
                }
            }
            }
        }
    }

    if (activeSheetBlock != null) {
        ActivityBottomSheet(
            block = activeSheetBlock!!,
            selectedStepId = activeSheetStepId,
            onStepClick = { stepId -> activeSheetStepId = stepId },
            onBackToLevel1 = { activeSheetStepId = null },
            onDismiss = {
                activeSheetBlock = null
                activeSheetStepId = null
            }
        )
    }
    }
}

@Composable
private fun ChatRefreshErrorNotice(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onRetry,
            enabled = canRetry,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text("Retry")
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Text("Dismiss")
        }
    }
}
