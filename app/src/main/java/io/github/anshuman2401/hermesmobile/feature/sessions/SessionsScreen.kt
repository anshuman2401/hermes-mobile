package io.github.anshuman2401.hermesmobile.feature.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import io.github.anshuman2401.hermesmobile.design.AppThemePreference
import io.github.anshuman2401.hermesmobile.design.AtelierTheme
import io.github.anshuman2401.hermesmobile.design.AtelierSessionChrome
import io.github.anshuman2401.hermesmobile.design.AtelierSectionLabel
import io.github.anshuman2401.hermesmobile.design.AtelierGroupRow
import io.github.anshuman2401.hermesmobile.design.AtelierDivider
import io.github.anshuman2401.hermesmobile.design.AtelierShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.design.RelayOrbitIcon
import io.github.anshuman2401.hermesmobile.domain.SessionError
import java.time.Instant
import java.time.ZoneId

data class SessionItemPlaceholder(
    val id: String,
    val title: String,
    val lastActive: String
)

// Explicit roles replace the historical blanket font reduction.
internal const val SESSIONS_SCREEN_HORIZONTAL_PADDING_DP = 22
internal const val SESSIONS_ROW_MIN_HEIGHT_DP = 60
internal fun sessionsTitleMaxLines(fontScale: Float): Int = if (fontScale > 1.2f) 2 else 1

/**
 * Sessions screen matching specs/architecture/design/ux-navigation.md § 2. Sessions.
 * Integrates with SessionsViewModel; supports server-authoritative lists, offline cache labeling,
 * and visibly distinct remote-not-found vs local-cache errors.
 *
 * CHG-003-S5 contract: the offline-cache label is rendered ONLY from the repository-provided
 * flag (`SessionsUiState.isOfflineCache`, set by `DefaultSessionRepository.listSessions`
 * exactly when serving the vault cache fallback). This screen performs NO screen-local
 * inference of staleness/freshness; the repository already refuses to return cache that is
 * past its 7-day freshness window (such cache surfaces as a failure state, never as list data).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(
    onOpenChat: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onReconnect: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel? = null,
    previewSessions: List<SessionSummary>? = null,
    profileSwitcher: @Composable (() -> Unit)? = null,
    workspaceActionsAllowed: () -> Boolean = { true },
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM,
    sessionOpenAllowed: () -> Boolean = workspaceActionsAllowed,
    connectionState: io.github.anshuman2401.hermesmobile.feature.connect.ConnectState = io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE,
    connectionMessage: String? = null,
    onConnectionSettings: () -> Unit = onNavigateToSettings
) {
    val uiState = viewModel?.uiState?.collectAsState()?.value
    val sessions = uiState?.sessions ?: previewSessions ?: emptyList()
    val listState = rememberLazyListState()
    val pinnedOnly = uiState?.showPinnedOnly ?: false
    val selectedSources = uiState?.selectedSources
    val filtersActive = pinnedOnly || selectedSources != null
    var showFilters by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pinnedOnly, selectedSources) { listState.scrollToItem(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(viewModel, lifecycleOwner) {
        val dispose = viewModel?.let { observeSessionsLifecycle(it, lifecycleOwner.lifecycle) } ?: {}
        onDispose {
            dispose()
        }
    }
    // Single source of truth: the repository flag. No screen-local staleness inference.
    val isOfflineCache = uiState?.isOfflineCache ?: false
    val isConnecting = connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.PREFLIGHT_LOADING
    val isLoading = (uiState?.isLoading ?: false) && !(isOfflineCache && isConnecting)
    // CHAT-015 B2: create progress is its own state, rendered at the entry
    // point only — never fed to the pull-to-refresh indicator.
    val isCreatingSession = uiState?.isCreatingSession ?: false
    // HOME-S2: one consistent "as of" Instant per composition — no ticking
    // timer (out of scope); labels refresh on the next recomposition.
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val currentError = uiState?.error
    AtelierTheme(currentTheme) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
            .padding(horizontal = SESSIONS_SCREEN_HORIZONTAL_PADDING_DP.dp, vertical = 12.dp)) {
            // The list outcome owns its profile label, including offline cache.
            AtelierSessionChrome(
                title = uiState?.selectedProfileName?.takeIf { it.isNotBlank() } ?: "Sessions",
                isConnected = connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE,
                trailingActions = {
                    IconButton(onClick = onNavigateToSettings,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Default.Settings, "Open settings")
                    }
                }
            ) {
                if (profileSwitcher != null) { profileSwitcher() }
            }
            Spacer(Modifier.height(20.dp))
            // HOME-S7: client-side search/filter bar — pure function over the loaded
            // list (no new gateway calls). Filters the session rows.
            val searchQuery = uiState?.searchQuery ?: ""
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel?.onSearchQueryChange(it) },
                    shape = AtelierShapes.field,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 52.dp),
                    singleLine = true,
                    placeholder = { Text("Search conversations...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel?.onSearchQueryChange("") },
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                )

                Box {
                    IconButton(onClick = { showFilters = true }, modifier = Modifier.size(48.dp),
                        enabled = uiState?.filtersRestoring != true && uiState?.filterFailure == null) {
                        val filterColor = if (filtersActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Canvas(Modifier.size(24.dp).semantics {
                            contentDescription = if (filtersActive) "Filter sessions, filters active" else "Filter sessions"
                        }) {
                            listOf(18f, 12f, 6f).forEachIndexed { index, width ->
                                val halfWidth = width.dp.toPx() / 2
                                val y = (7 + index * 5).dp.toPx()
                                drawLine(filterColor, Offset(size.width / 2 - halfWidth, y),
                                    Offset(size.width / 2 + halfWidth, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                            }
                        }
                    }
                    if (filtersActive) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
            // Refresh remains available through the existing pull-to-refresh gesture.
            if (showFilters) {
                SessionsFilterSheet(
                    pinnedOnly = pinnedOnly,
                    selectedSources = selectedSources,
                    options = SessionsSourceFilterPolicy.options(sessions),
                    onPinnedChange = { viewModel?.onPinnedFilterChange(it) },
                    onAllSourcesChange = { viewModel?.onAllSourcesChange() },
                    onSourceToggle = { viewModel?.onSourceToggle(it) },
                    onDismiss = { showFilters = false }
                )
            }
            uiState?.filterFailure?.let { failure ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(failure, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel?.retrySavedFilters() }) { Text("Retry") }
                }
            }
            uiState?.pinFailure?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel?.dismissPinFailure() }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss pin error")
                    }
                }
            }

            if (connectionState != io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE && !isConnecting) {
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.AUTH_REQUIRED) "Sign in required"
                        else connectionMessage ?: connectionState.description,
                        style = MaterialTheme.typography.bodyMedium)
                    if (!isConnecting) {
                        if (connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.HOST_UNREACHABLE) {
                            OutlinedButton(onClick = onReconnect, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Retry") }
                        }
                        OutlinedButton(onClick = onConnectionSettings, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(if (connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.AUTH_REQUIRED) "Sign in" else "Connection settings") }
                    }
                }
            }

            // Owner UI decision (2026-09-24): connection failures are already
            // represented by the red dot beside the profile title. Keep the ViewModel
            // error state for refresh/reconnect logic, but do not duplicate it as
            // a visible banner on Sessions.
            if (currentError != null && currentError !is SessionError.NetworkFailure) {
                SessionErrorBanner(
                    error = currentError,
                    onDismiss = { viewModel?.clearError() }
                )
            }

            if (isOfflineCache) {
                Text(
                    text = SessionsOfflineSectionPolicy.sectionLabel(isOfflineCache),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }

            if (uiState?.staleProfileNotice != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState?.staleProfileNotice?.let { notice ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = notice,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Search remains a single local derivation. Date sections consume
            // only this filtered result and never change its order or identity.
            val filteredSessions = SessionsSearchPolicy.filterSessions(sessions, searchQuery)
                .let { SessionsSourceFilterPolicy.filter(it, selectedSources) }
            val groupedSessions = SessionsPinnedPolicy.groups(filteredSessions, uiState?.pinnedSessionIds.orEmpty(), pinnedOnly, now, zone)

            val pullToRefreshState = rememberPullToRefreshState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
            PullToRefreshBox(isRefreshing = isLoading,
                onRefresh = { viewModel?.refreshSessions() }, state = pullToRefreshState,
                modifier = Modifier.fillMaxSize().semantics {
                    customActions = listOf(CustomAccessibilityAction("Refresh sessions") {
                        if (viewModel != null) { viewModel.refreshSessions(); true } else false
                    })
                }) {
            if (sessions.isEmpty() && connectionState != io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE) {
                Text(if (isConnecting || isLoading) "Loading sessions…" else "No saved sessions available",
                    modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            } else if (!filtersActive && SessionsEmptyStatePolicy.isEmptyStateVisible(sessions.size, isLoading) && connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE) {
                // HOME-S10: designed empty state — icon + spec copy + CTA (ux-navigation §2:
                // "No saved conversations yet. Start a chat."; no claim that a blank remote
                // draft is saved before its first prompt). CTA reuses the byte-identical
                // createNewChat path as the FAB.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RelayOrbitIcon(size = 48.dp, contentDescriptionText = null)
                    Text(
                        text = SessionsEmptyStatePolicy.EMPTY_TITLE,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            if (workspaceActionsAllowed() && !isLoading && currentError == null) {
                                val intent = viewModel?.captureWorkspaceIntent()
                                if (viewModel != null) viewModel.createNewChat { sessionId ->
                                    if (workspaceActionsAllowed() && intent != null && viewModel.isWorkspaceIntentCurrent(intent)) onOpenChat(sessionId)
                                } else if (workspaceActionsAllowed()) onOpenChat("new-session")
                            }
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(SessionsEmptyStatePolicy.EMPTY_CTA)
                    }
                }
            } else {
                // HOME-S7: the single filtered result is grouped for rendering;
                // a no-match state replaces it when sessions exist but none
                // match the query.
                if (groupedSessions.isEmpty() && !isLoading && (isOfflineCache || connectionState == io.github.anshuman2401.hermesmobile.feature.connect.ConnectState.CONNECTED_COMPATIBLE)) {
                    // HOME-S7: sessions exist but none match the current filter.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (selectedSources != null) "No sessions match your filters"
                                else if (searchQuery.isNotBlank()) "No conversations match \"${searchQuery.trim()}\"" else "No pinned conversations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (filtersActive || searchQuery.isNotBlank()) {
                            OutlinedButton(onClick = { viewModel?.resetFilters() },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Reset filters") }
                        }
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        groupedSessions.forEachIndexed { index, group ->
                            if (!pinnedOnly) item(key = "heading:${group.heading}") {
                                AtelierSectionLabel(group.heading,
                                    topPadding = if (isOfflineCache && index == 0) 4.dp else 16.dp)
                            }
                            items(group.sessions, key = { it.id }) { session ->
                                val showRunningMark = SessionsRunningRowPolicy.showRunning(
                                    rowId = session.id,
                                    streamingId = uiState?.activeTurnStreaming?.storedSessionId,
                                    isStreaming = uiState?.activeTurnStreaming?.isStreaming == true,
                                    isOfflineCache = isOfflineCache,
                                    hasNetworkFailure = currentError is SessionError.NetworkFailure,
                                    hasViewModel = viewModel != null
                                )
                                val displayedTitle = SessionsRowPresentationPolicy.displayedTitle(
                                    title = session.title, sessionId = session.id)
                                val first = session.id == group.sessions.first().id
                                val last = session.id == group.sessions.last().id
                                val label = safeRelativeActivityLabel(now, effectiveSessionTimestamp(session, zone))
                                val messageCountLabel = SessionsRowMetaPolicy.messageLabel(session.messageCount)
                                val sourceValue = session.source?.trim()?.takeIf { it.isNotEmpty() }
                                val sourceAccessibilityLabel = SessionsRowMetaPolicy.sourceLabel(session.source)
                                val largeText = LocalDensity.current.fontScale > 1.2f
                                val runningGreen = MaterialTheme.colorScheme.secondary
                                AtelierGroupRow(first, last,
                                    Modifier.padding(bottom = if (last) 16.dp else 0.dp)) {
                                    Column {
                                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = SESSIONS_ROW_MIN_HEIGHT_DP.dp),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            // The pin is a sibling, never a child of the open action.
                                            Column(Modifier.weight(1f)
                                                .clickable(enabled = !isLoading && !isCreatingSession && sessionOpenAllowed()) {
                                                    if (sessionOpenAllowed()) onOpenChat(session.id)
                                                }
                                                .semantics {
                                                    contentDescription = if (showRunningMark) "Session: $displayedTitle. Running: this conversation is streaming a response"
                                                        else "Session: $displayedTitle"
                                                }
                                                .defaultMinSize(minHeight = SESSIONS_ROW_MIN_HEIGHT_DP.dp)
                                                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(text = displayedTitle, modifier = Modifier.weight(1f),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        maxLines = sessionsTitleMaxLines(LocalDensity.current.fontScale),
                                                        overflow = TextOverflow.Ellipsis)
                                                    if (label.isNotEmpty() && !largeText) Text(text = label,
                                                        modifier = Modifier.widthIn(max = 64.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.End, softWrap = true)
                                                }
                                                if (label.isNotEmpty() && largeText) Text(text = label,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        modifier = Modifier.semantics(mergeDescendants = true) {
                                                            contentDescription = messageCountLabel
                                                        }) {
                                                        OutlineChatBubbleGraphic(MaterialTheme.colorScheme.onSurfaceVariant,
                                                            Modifier.size(12.dp))
                                                        Text(messageCountLabel, style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (sourceValue != null) {
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = lerp(MaterialTheme.colorScheme.surfaceContainer,
                                                                MaterialTheme.colorScheme.primary, 0.04f),
                                                            contentColor = MaterialTheme.colorScheme.primary,
                                                            border = BorderStroke(1.dp,
                                                                lerp(MaterialTheme.colorScheme.surfaceContainer,
                                                                    MaterialTheme.colorScheme.primary, 0.42f)),
                                                            modifier = Modifier.semantics(mergeDescendants = true) {
                                                                contentDescription = sourceAccessibilityLabel.orEmpty()
                                                            }
                                                        ) {
                                                            Text(sourceValue,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp))
                                                        }
                                                    }
                                                    if (showRunningMark) {
                                                        Row(verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                            modifier = Modifier.semantics {
                                                                contentDescription = "Running: this conversation is streaming a response"
                                                            }) {
                                                            Box(Modifier.size(6.dp).clip(CircleShape).background(runningGreen))
                                                            Text(text = "Running", color = runningGreen,
                                                                style = MaterialTheme.typography.bodySmall)
                                                        }
                                                    }
                                                }
                                            }
                                            SessionPinAffordance(
                                                pinned = session.id in uiState?.pinnedSessionIds.orEmpty(),
                                                enabled = uiState?.pinsAvailable == true && session.id !in uiState.savingPinIds && workspaceActionsAllowed(),
                                                onClick = { if (workspaceActionsAllowed()) viewModel?.toggleSessionPin(session.id) })
                                        }
                                        if (!last) AtelierDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            // Fixed entry point above the shell-owned bottom bar.
            Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                uiState?.newChatFailure?.let { failure ->
                    NewChatInlineNotice(failure.message, { viewModel?.dismissNewChatFailure() }, isError = true)
                }
                FilledIconButton(
                    onClick = sessionsFabCreateAction(viewModel, workspaceActionsAllowed, isLoading, currentError, onOpenChat),
                    enabled = !isCreatingSession && currentError == null && !isLoading && workspaceActionsAllowed(),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "New chat" }) {
                    if (isCreatingSession) CircularProgressIndicator(Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Add, null, Modifier.size(24.dp))
                }
            }
            }
        }
    }
}

/** The screen effect owns both entry refresh and foreground observation. */
internal fun observeSessionsLifecycle(viewModel: SessionsViewModel, lifecycle: Lifecycle): () -> Unit {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                viewModel.resumePolling()
                viewModel.refreshSessions(coalesce = true)
            }
            Lifecycle.Event.ON_PAUSE -> viewModel.pausePolling()
            else -> Unit
        }
    }
    viewModel.resumePolling()
    viewModel.refreshSessions(coalesce = true)
    lifecycle.addObserver(observer)
    return {
        lifecycle.removeObserver(observer)
        viewModel.pausePolling()
    }
}

/** Local pin action occupies its own touch target inside the conversation card. */
@Composable
private fun SessionPinAffordance(pinned: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)
        .pointerInput(enabled) {
            // A disabled child must not let a save-in-progress tap open its parent card.
            if (!enabled) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }) {
        OutlinePinGraphic(
            color = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            filled = pinned,
            modifier = Modifier.size(16.dp).semantics {
                contentDescription = SessionsRowPresentationPolicy.pinAccessibilityLabel(pinned)
            }
        )
    }
}

@Composable
private fun OutlineChatBubbleGraphic(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.1f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
            size = Size(size.width * 0.8f, size.height * 0.65f),
            cornerRadius = CornerRadius(size.minDimension * 0.2f),
            style = stroke
        )
        val tail = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.75f)
            lineTo(size.width * 0.28f, size.height * 0.93f)
            lineTo(size.width * 0.47f, size.height * 0.75f)
        }
        drawPath(path = tail, color = color, style = stroke)
    }
}

@Composable
private fun OutlinePinGraphic(color: Color, filled: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.09f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        val pin = Path().apply {
            moveTo(size.width * 0.34f, size.height * 0.12f)
            lineTo(size.width * 0.66f, size.height * 0.12f)
            lineTo(size.width * 0.66f, size.height * 0.46f)
            lineTo(size.width * 0.83f, size.height * 0.68f)
            lineTo(size.width * 0.17f, size.height * 0.68f)
            lineTo(size.width * 0.34f, size.height * 0.46f)
            close()
        }
        drawPath(path = pin, color = color, style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke)
        drawLine(
            color = color,
            start = Offset(size.width * 0.5f, size.height * 0.68f),
            end = Offset(size.width * 0.5f, size.height * 0.92f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Visibly distinct error banner for remote not found vs local cache failure.
 */
@Composable
fun SessionErrorBanner(
    error: SessionError,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (error is SessionError.NetworkFailure) return

    val (title, description, containerColor, contentColor) = when (error) {
        is SessionError.RemoteNotFound -> Quadruple(
            "Session Not Found (Remote 4007)",
            "The requested session '${error.storedSessionId}' no longer exists on the remote host.",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        is SessionError.LocalCacheFailure -> Quadruple(
            "Local Cache Error",
            error.message,
            MaterialTheme.colorScheme.surfaceVariant,
            // CHG-008 follow-up: onSurfaceVariant on the UNWIRED dark
            // surfaceVariant M3 1.4.0 baseline (#49454F) is 3.02:1 — the dark
            // refresh took it from 5.18:1. onSurface is the matching content
            // role on this container: 9.34:1 dark / 12.75:1 light.
            MaterialTheme.colorScheme.onSurface
        )
        is SessionError.NetworkFailure -> return
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "Error: $title - $description"
            }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
            OutlinedButton(
                onClick = onDismiss,
                // CHG-008 repair round 1: the label inherits M3 1.4.0's
                // OutlinedButton default contentColor (onSurfaceVariant),
                // which is 3.02:1 on this surfaceVariant container in dark —
                // the same failing pair the Quadruple above fixes for the
                // Texts. Pin the same onSurface role: 9.34:1 dark / 12.75:1
                // light on the banner's background.
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = contentColor
                ),
                modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            ) {
                Text("Dismiss")
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/**
 * CHAT-015 B2 (controller decision 2026-09-26): the ONE shared dismissible
 * inline notice mechanism for the new-chat entry points — an anchored
 * dismissible Surface used for BOTH the create-failure message and the
 * degraded-default notice ("saved default model not applied — chat created
 * with the host default."). No SnackbarHost; fixed app-authored copy only.
 */
@Composable
internal fun NewChatInlineNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss new chat notice",
                    tint = contentColor
                )
            }
        }
    }
}

/** The FAB's production intent/deferred-success path, also exercised on the JVM. */
internal fun sessionsFabCreateAction(
    viewModel: SessionsViewModel?,
    workspaceActionsAllowed: () -> Boolean,
    isLoading: Boolean,
    currentError: SessionError?,
    onOpenChat: (String) -> Unit
): () -> Unit = {
    if (workspaceActionsAllowed() && !isLoading && currentError == null) {
        val intent = viewModel?.captureWorkspaceIntent()
        if (viewModel != null) viewModel.createNewChat { sessionId ->
            if (workspaceActionsAllowed() && intent != null && viewModel.isWorkspaceIntentCurrent(intent)) onOpenChat(sessionId)
        } else if (workspaceActionsAllowed()) onOpenChat("new-session")
    }
}
