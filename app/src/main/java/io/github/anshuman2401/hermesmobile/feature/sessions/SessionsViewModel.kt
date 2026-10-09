package io.github.anshuman2401.hermesmobile.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.data.session.SessionActivityScope
import io.github.anshuman2401.hermesmobile.data.session.newerActivity
import io.github.anshuman2401.hermesmobile.data.session.sortedByActivity
import io.github.anshuman2401.hermesmobile.data.local.SessionFilters
import io.github.anshuman2401.hermesmobile.domain.LocalCacheException
import io.github.anshuman2401.hermesmobile.domain.SessionError
import io.github.anshuman2401.hermesmobile.domain.SessionNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel managing session listing, creation, and durable resume.
 * Follows session-and-turn-state-machine.md.
 */
class SessionsViewModel(
    private val sessionRepository: SessionRepository? = null,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    deferInitialLoad: Boolean = false
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState(
        isLoading = sessionRepository != null,
        filtersRestoring = sessionRepository != null,
        showPinnedOnly = savedStateHandle["showPinnedOnly"] ?: false,
        selectedSources = savedStateHandle.get<ArrayList<String>>("selectedSources")?.toSet()
    ))
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    private var pinScope: io.github.anshuman2401.hermesmobile.data.local.SessionPinScope? = null
    private var filterScope: io.github.anshuman2401.hermesmobile.data.local.SessionPinScope? = null
    private var lastSavedFilters: SessionFilters? = null
    private var filtersLoadedGeneration = -1L
    private var filterRevision = 0L
    private val filterMutex = kotlinx.coroutines.sync.Mutex()
    private val filterLoadMutex = kotlinx.coroutines.sync.Mutex()
    private var pinRevision = 0L
    private val pinMutex = kotlinx.coroutines.sync.Mutex()
    private var workspaceGeneration = 0L
    private var listSequence = 0L
    private var listJob: Job? = null
    private var pendingRefresh = false
    private var isDisposed = false
    private var initialLoadDeferred = deferInitialLoad
    private var startupCacheStarted = false
    private var startupNetworkError: String? = null

    /** Enter read-only recovery without discarding already displayed rows or selection. */
    fun beginConnectionRecovery() {
        if (initialLoadDeferred || isDisposed) return
        initialLoadDeferred = true
        startupCacheStarted = false
        startupNetworkError = null
        listSequence++
        pendingRefresh = false
        listJob?.cancel()
        stopActiveModelPolling()
        _uiState.update { it.copy(isOfflineCache = it.sessions.isNotEmpty(), isLoading = it.sessions.isEmpty()) }
    }

    /** Admit local reads only after resolving an origin-bound local cache owner. */
    fun restoreSavedLoginSessions() {
        if (!initialLoadDeferred || startupCacheStarted || isDisposed) return
        val repo = sessionRepository ?: return
        startupCacheStarted = true
        restoreSelection()
        _uiState.update { it.copy(isLoading = it.sessions.isEmpty()) }
        val intent = workspaceGeneration
        val sequence = ++listSequence
        listJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureFiltersLoaded(repo, intent)
                val result = repo.loadCachedSessions()
                currentCoroutineContext().ensureActive()
                if (intent != workspaceGeneration || sequence != listSequence) return@launch
                val candidate = result.getOrNull()
                var pinReadFailed = false
                val pins = if (candidate != null) try { repo.getSessionPins() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { pinReadFailed = true; null } else null
                val cached = candidate?.let { repo.reconcileSessionActivity(it) }
                currentCoroutineContext().ensureActive()
                if (intent != workspaceGeneration || sequence != listSequence) return@launch
                listActivityScope = cached?.activityScope
                pinScope = pins?.scope
                _uiState.update { it.copy(
                    sessions = cached?.sessions ?: it.sessions,
                    pinnedSessionIds = pins?.sessionIds.orEmpty(),
                    pinsAvailable = pins != null && !pinReadFailed,
                    pinFailure = if (pinReadFailed) "Couldn't load pins" else null,
                    isOfflineCache = cached?.isOfflineCache == true || it.sessions.isNotEmpty(),
                    selectedProfileName = cached?.selectedProfileName ?: it.selectedProfileName,
                    isLoading = cached == null && it.sessions.isEmpty() && result.isSuccess && startupNetworkError == null,
                    error = if (startupNetworkError != null) SessionError.NetworkFailure(startupNetworkError!!) else if (result.isFailure) SessionError.LocalCacheFailure("Saved sessions could not be loaded.") else null
                ) }
            } finally {
                if (intent == workspaceGeneration && sequence == listSequence) listJob = null
            }
        }
        listJob?.start()
    }

    /** Cancels any unfinished cache read; live server truth wins even without a cache. */
    fun finishStartupConnection() {
        if (!initialLoadDeferred || isDisposed) return
        initialLoadDeferred = false
        startupNetworkError = null
        if (!startupCacheStarted) restoreSelection()
        loadSessions(forceRefresh = true)
    }

    fun revokeSessionAccess() {
        initialLoadDeferred = true
        startupCacheStarted = false
        startupNetworkError = null
        resetWorkspace()
        _uiState.update { it.copy(isLoading = false) }
    }

    private var recoveryHandler: ((io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryRequest, Long) -> Unit)? = null
    private var pendingRecovery: Pair<io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryRequest, Long>? = null
    private var recoveryNotice: String? = null
    private var listActivityScope: SessionActivityScope? = null
    internal fun captureWorkspaceIntent(): Long = workspaceGeneration
    internal fun isWorkspaceIntentCurrent(intent: Long): Boolean = intent == workspaceGeneration

    internal fun resetWorkspace(): Long {
        // A source subset belongs to the previous workspace's loaded catalogue.
        savedStateHandle.set<ArrayList<String>?>("selectedSources", null)
        savedStateHandle["showPinnedOnly"] = false
        filterScope = null
        lastSavedFilters = null
        filtersLoadedGeneration = -1L
        filterRevision++
        workspaceGeneration++
        pinScope = null
        pinRevision++
        listSequence++
        pendingRefresh = false
        listJob?.cancel()
        listJob = null
        stopActiveModelPolling()
        pendingRecovery = null
        recoveryNotice = null
        listActivityScope = null
        _uiState.update { SessionsUiState(isLoading = true, filtersRestoring = true, searchQuery = it.searchQuery,
            activeTurnStreaming = it.activeTurnStreaming) }
        return workspaceGeneration
    }

    internal fun setWorkspaceRecoveryHandler(handler: ((io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryRequest, Long) -> Unit)?) {
        recoveryHandler = handler
        pendingRecovery?.let { (request, generation) ->
            if (generation == workspaceGeneration) handler?.invoke(request, generation)
        }
    }

    internal suspend fun applyWorkspaceRecovery(request: io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryRequest, intent: Long): io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult {
        if (!isWorkspaceIntentCurrent(intent)) return io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult.Stale
        return sessionRepository?.applyWorkspaceRecovery(request)
            ?: io.github.anshuman2401.hermesmobile.data.session.WorkspaceRecoveryApplyResult.Unsupported
    }

    internal fun loadRecoveredWorkspace(intent: Long) {
        if (!isWorkspaceIntentCurrent(intent)) return
        recoveryNotice = io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult.PROFILE_GONE_NOTICE
        loadSessions(forceRefresh = true)
    }

    private var isPollingResumed: Boolean = false
    private var activeModelPollJob: Job? = null
    internal val isPollingActive: Boolean get() = activeModelPollJob?.isActive == true

    init {
        if (sessionRepository != null) {
            if (!initialLoadDeferred) restoreSelection()
            if (!initialLoadDeferred) loadSessions(forceRefresh = false)
            viewModelScope.launch {
                sessionRepository.activeTurnStreaming.collect { value ->
                    _uiState.update { it.copy(activeTurnStreaming = value) }
                }
            }
            viewModelScope.launch {
                sessionRepository.listInvalidations.collect { if (!initialLoadDeferred) refreshSessions(coalesce = true) }
            }
            viewModelScope.launch {
                sessionRepository.activityUpdates.collect { update ->
                    val intent = workspaceGeneration
                    val scope = listActivityScope ?: return@collect
                    if (scope.partition != update.scope.partition) return@collect
                    val current = try { sessionRepository.isSessionActivityScopeCurrent(update.scope) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                    if (!current || intent != workspaceGeneration || listActivityScope !== scope) return@collect
                    _uiState.update { state -> state.copy(sessions = state.sessions.map { row ->
                        if (row.id != update.storedSessionId) row
                        else row.copy(lastActive = newerActivity(row.lastActive, update.lastActive))
                    }.sortedByActivity()) }
                }
            }
        }
    }

    /**
     * Manual pulls retain their concurrent-pull guard. Automatic signals share
     * this entrypoint and retain one trailing refresh behind the entire load.
     */
    fun refreshSessions(coalesce: Boolean = false) {
        if (sessionRepository == null || isDisposed || initialLoadDeferred) return
        if (listJob?.isActive == true) {
            if (coalesce) pendingRefresh = true
            return
        }
        if (_uiState.value.isLoading) return // concurrent-pull guard
        loadSessions(forceRefresh = true)
    }

    fun loadSessions(forceRefresh: Boolean = false) {
        if (isDisposed || initialLoadDeferred) return
        val repo = sessionRepository ?: return
        val intent = workspaceGeneration
        val sequence = ++listSequence
        listJob?.cancel()
        pendingRefresh = false
        stopActiveModelPolling()
        _uiState.update { it.copy(isLoading = true, error = null) }
        // Install ownership before starting, including with eager Main/test dispatchers.
        listJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                var refresh = forceRefresh
                do {
                    pendingRefresh = false
                    stopActiveModelPolling()
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    try { loadSessionList(repo, refresh, intent, sequence) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { publishListFailure(error, intent, sequence) }
                    refresh = true
                } while (pendingRefresh && isActive && intent == workspaceGeneration &&
                    sequence == listSequence && pendingRecovery == null)
            } finally {
                if (intent == workspaceGeneration && sequence == listSequence) {
                    pendingRefresh = false
                    listJob = null
                }
            }
        }
        listJob?.start()
    }

    private suspend fun loadSessionList(repo: SessionRepository, forceRefresh: Boolean, intent: Long, sequence: Long) {
        ensureFiltersLoaded(repo, intent)
        if (intent != workspaceGeneration || sequence != listSequence) return
        val result = try { repo.listSessions(forceRefresh) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        currentCoroutineContext().ensureActive()
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (intent != workspaceGeneration || sequence != listSequence) return
        result.onSuccess { outcome ->
            outcome.recoveryRequirement?.let { request ->
                pendingRecovery = request to intent
                _uiState.update { it.copy(sessions = emptyList(), activeModels = emptyMap(), isLoading = true) }
                recoveryHandler?.invoke(request, intent)
                return
            }
            pendingRecovery = null
            val revision = pinRevision
            var pinReadFailed = false
            val pins = try { repo.getSessionPins() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                pinReadFailed = true
                if (intent == workspaceGeneration) _uiState.update { it.copy(pinFailure = "Couldn't load pins", pinsAvailable = false) }
                null
            }
            if (intent != workspaceGeneration || sequence != listSequence) return
            if (revision == pinRevision && !pinReadFailed) {
                pinScope = pins?.scope
                _uiState.update { it.copy(pinnedSessionIds = pins?.sessionIds.orEmpty(), pinsAvailable = pins != null, pinFailure = null) }
            }
            if (outcome.isOfflineCache) {
                val reconciled = repo.reconcileSessionActivity(outcome)
                if (reconciled == null) {
                    if (intent == workspaceGeneration && sequence == listSequence) _uiState.update { it.copy(isLoading = false) }
                    return
                }
                if (intent != workspaceGeneration || sequence != listSequence) return
                listActivityScope = reconciled.activityScope
                stopActiveModelPolling()
                _uiState.update { current ->
                    current.copy(
                        sessions = reconciled.sessions,
                        isOfflineCache = true,
                        activeModels = emptyMap(),
                        isLoading = false,
                        error = null,
                        selectedProfileName = outcome.selectedProfileName,
                        staleProfileNotice = recoveryNotice ?: outcome.staleProfileNotice
                    )
                }
            } else {
                val models = if (outcome.sessions.isNotEmpty()) {
                    val result = repo.activeSessionModels()
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    result.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
                currentCoroutineContext().ensureActive()
                if (intent != workspaceGeneration || sequence != listSequence) return
                val reconciled = repo.reconcileSessionActivity(outcome)
                if (reconciled == null) {
                    if (intent == workspaceGeneration && sequence == listSequence) _uiState.update { it.copy(isLoading = false) }
                    return
                }
                if (intent != workspaceGeneration || sequence != listSequence) return
                listActivityScope = reconciled.activityScope
                _uiState.update { current ->
                    current.copy(
                        sessions = reconciled.sessions,
                        isOfflineCache = false,
                        activeModels = models,
                        isLoading = false,
                        error = null,
                        selectedProfileName = outcome.selectedProfileName,
                        staleProfileNotice = recoveryNotice ?: outcome.staleProfileNotice
                    )
                }
                if (isPollingResumed && outcome.sessions.isNotEmpty()) {
                    startActiveModelPolling()
                } else {
                    stopActiveModelPolling()
                }
            }
        }.onFailure { error -> publishListFailure(error, intent, sequence) }
    }

    private fun publishListFailure(error: Throwable, intent: Long, sequence: Long) {
        if (intent != workspaceGeneration || sequence != listSequence || isDisposed) return
        stopActiveModelPolling()
        _uiState.update { current ->
            current.copy(
                isLoading = false,
                error = if (error is LocalCacheException) {
                    SessionError.LocalCacheFailure(error.message ?: "Cache failure")
                } else {
                    SessionError.NetworkFailure(error.message ?: "Failed to load sessions")
                }
            )
        }
    }

    fun resumePolling() {
        isPollingResumed = true
        if (!initialLoadDeferred && !_uiState.value.isOfflineCache && _uiState.value.sessions.isNotEmpty()) {
            startActiveModelPolling()
        }
    }

    fun pausePolling() {
        isPollingResumed = false
        stopActiveModelPolling()
    }

    private fun startActiveModelPolling() {
        activeModelPollJob?.cancel()
        val repo = sessionRepository ?: return
        val intent = workspaceGeneration
        val sequence = listSequence
        activeModelPollJob = viewModelScope.launch {
            while (isActive && !_uiState.value.isOfflineCache) {
                delay(10_000L)
                if (!isActive || _uiState.value.isOfflineCache) break
                val modelsResult = repo.activeSessionModels()
                modelsResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                if (intent != workspaceGeneration || sequence != listSequence) return@launch
                modelsResult.onSuccess { models ->
                    _uiState.update { it.copy(activeModels = models) }
                }
            }
        }
    }

    private fun stopActiveModelPolling() {
        activeModelPollJob?.cancel()
        activeModelPollJob = null
    }

    override fun onCleared() {
        isDisposed = true
        workspaceGeneration++
        listSequence++
        pendingRefresh = false
        listJob?.cancel()
        listJob = null
        isPollingResumed = false
        stopActiveModelPolling()
        super.onCleared()
    }


    fun selectAndResumeSession(storedSessionId: String, onResumeSuccess: ((String) -> Unit)? = null) {
        val repo = sessionRepository ?: return
        if (_uiState.value.isLoading || _uiState.value.error != null) return
        val intent = workspaceGeneration
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = repo.resumeSession(storedSessionId)
            if (intent != workspaceGeneration) return@launch
            result.onSuccess { resumeResult ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        selectedSession = repo.activeSession.value,
                        activeMessages = resumeResult.messages,
                        error = null
                    )
                }
                onResumeSuccess?.invoke(resumeResult.durableSessionId)
            }.onFailure { error ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        error = when (error) {
                            is SessionNotFoundException -> SessionError.RemoteNotFound(error.storedSessionId)
                            is LocalCacheException -> SessionError.LocalCacheFailure(error.message ?: "Cache failure")
                            else -> SessionError.NetworkFailure(error.message ?: "Failed to resume session")
                        }
                    )
                }
            }
        }
    }

    fun createNewChat(onCreateSuccess: ((String) -> Unit)? = null) {
        val repo = sessionRepository ?: return
        // CHAT-015 repair: re-entry guard — a second tap while a create is in
        // flight must not launch a concurrent create (two navigation callbacks,
        // first session orphaned). Safe from stranding because `isCreatingSession`
        // is reset on EVERY terminal path below (success, failure, thrown error).
        if (_uiState.value.isCreatingSession) return
        val intent = workspaceGeneration
        // CHAT-015 B2: dedicated create-in-progress state — never the list
        // `isLoading` (pull-to-refresh stays bound to list refresh only).
        _uiState.update {
            it.copy(isCreatingSession = true, newChatFailure = null, error = null)
        }
        viewModelScope.launch {
            // CHAT-015 repair (MAJOR): the create pipeline can THROW (e.g. the
            // repository's durable vault write rethrows IO errors). An uncaught
            // throw crashes the app and strands `isCreatingSession`; map any
            // Throwable onto the standard redacted failure seam instead.
            // CancellationException must propagate untouched — cancellation is
            // never a create failure.
            try {
            val result = try {
                repo.createDraftSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (intent != workspaceGeneration) return@launch
            result.onSuccess { handle ->
                _uiState.update { current ->
                    current.copy(
                        isCreatingSession = false,
                        selectedSession = handle,
                        activeMessages = emptyList(),
                        error = null,
                        newChatFailure = null,
                        // CHAT-015 B1/B2: a degraded-default create carries the
                        // fixed notice into the new chat (bound to the created
                        // session so it clears when the active session changes).
                        newChatDegradedNotice = if (handle.savedDefaultModelApplied == false) {
                            NewChatCreateNotice(
                                sessionId = handle.storedSessionId,
                                message = DEGRADED_DEFAULT_NOTICE_COPY
                            )
                        } else {
                            null
                        }
                    )
                }
                onCreateSuccess?.invoke(handle.storedSessionId)
            }.onFailure { error ->
                // CHAT-015 B2: visible failure in the dedicated state — no
                // navigation, no success callback, and NEVER the suppressed
                // `error` channel (owner decision 2026-09-24).
                _uiState.update { current ->
                    current.copy(
                        isCreatingSession = false,
                        newChatFailure = NewChatCreateFailure.redactedFrom(error)
                    )
                }
            }
            } finally {
                if (intent == workspaceGeneration) _uiState.update { it.copy(isCreatingSession = false) }
            }
        }
    }

    fun restoreSelection() {
        val repo = sessionRepository ?: return
        val intent = workspaceGeneration
        viewModelScope.launch {
            val restored = repo.restoreSelectedSession()
            if (intent != workspaceGeneration) return@launch
            if (restored != null) {
                _uiState.update { it.copy(selectedSession = restored) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * CHAT-015 B2: dismiss the entry-point create-failure notice.
     */
    fun dismissNewChatFailure() {
        _uiState.update { it.copy(newChatFailure = null) }
    }

    /**
     * CHAT-015 B2: dismiss the degraded-default notice.
     */
    fun dismissNewChatDegradedNotice() {
        _uiState.update { it.copy(newChatDegradedNotice = null) }
    }

    /**
     * Surfaces a connection or network failure to the Sessions screen.
     * Sets error to SessionError.NetworkFailure and clears any active loading indicator.
     */
    fun setNetworkError(message: String) {
        if (initialLoadDeferred) startupNetworkError = message
        _uiState.update { it.copy(isLoading = false, error = SessionError.NetworkFailure(message)) }
    }

    private suspend fun ensureFiltersLoaded(repo: SessionRepository, intent: Long) {
        filterLoadMutex.withLock {
            if (intent != workspaceGeneration || filtersLoadedGeneration == intent) return@withLock
            _uiState.update { it.copy(filtersRestoring = true) }
            val restored = try { repo.getSessionFilters() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (intent == workspaceGeneration) {
                    filterScope = null
                    lastSavedFilters = null
                    filtersLoadedGeneration = intent
                    savedStateHandle["showPinnedOnly"] = false
                    savedStateHandle.set<ArrayList<String>?>("selectedSources", null)
                    _uiState.update { it.copy(showPinnedOnly = false, selectedSources = null,
                        filtersRestoring = false, filterFailure = "Couldn't load saved filters. Showing all sessions.") }
                }
                return@withLock
            }
            if (intent != workspaceGeneration) return@withLock
            filterScope = restored?.scope
            lastSavedFilters = restored
            filtersLoadedGeneration = intent
            if (restored == null) {
                _uiState.update { it.copy(filtersRestoring = false, filterFailure = null) }
                return@withLock
            }
            val pinned = restored.pinnedOnly
            val sources = restored.selectedSources
            savedStateHandle["showPinnedOnly"] = pinned
            savedStateHandle["selectedSources"] = sources?.let { ArrayList(it) }
            _uiState.update { it.copy(showPinnedOnly = pinned, selectedSources = sources,
                filtersRestoring = false, filterFailure = null) }
        }
    }

    /** Retry only the encrypted local preference read; no session-list request. */
    fun retrySavedFilters() {
        val repo = sessionRepository ?: return
        val intent = workspaceGeneration
        filtersLoadedGeneration = -1L
        viewModelScope.launch { ensureFiltersLoaded(repo, intent) }
    }

    private fun saveFilterChoice() {
        val repo = sessionRepository ?: return
        val scope = filterScope ?: return
        val intent = workspaceGeneration
        filterRevision++
        viewModelScope.launch {
            filterMutex.withLock {
                if (intent != workspaceGeneration || filterScope != scope) return@withLock
                val state = _uiState.value
                val revision = filterRevision
                val requested = SessionFilters(scope, state.showPinnedOnly, state.selectedSources)
                try {
                    val saved = repo.saveSessionFilters(requested)
                    if (intent == workspaceGeneration && filterScope == scope) {
                        lastSavedFilters = saved
                        if (revision == filterRevision) _uiState.update { it.copy(filterFailure = null) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (intent == workspaceGeneration && filterScope == scope && revision == filterRevision) {
                        val prior = lastSavedFilters ?: SessionFilters(scope)
                        savedStateHandle["showPinnedOnly"] = prior.pinnedOnly
                        savedStateHandle["selectedSources"] = prior.selectedSources?.let { ArrayList(it) }
                        _uiState.update { it.copy(showPinnedOnly = prior.pinnedOnly,
                            selectedSources = prior.selectedSources, filterFailure = "Couldn't save filters. Previous choice restored.") }
                    }
                }
            }
        }
    }

    fun onPinnedFilterChange(pinnedOnly: Boolean) {
        savedStateHandle["showPinnedOnly"] = pinnedOnly
        _uiState.update { it.copy(showPinnedOnly = pinnedOnly) }
        saveFilterChoice()
    }

    fun onAllSourcesChange() {
        setSourceSelection(if (_uiState.value.selectedSources == null) emptySet() else null)
    }

    fun onSourceToggle(source: String) {
        val state = _uiState.value
        setSourceSelection(SessionsSourceFilterPolicy.toggle(
            state.selectedSources, source, SessionsSourceFilterPolicy.options(state.sessions)
        ))
    }

    private fun setSourceSelection(selected: Set<String>?) {
        savedStateHandle["selectedSources"] = selected?.let { ArrayList(it) }
        _uiState.update { it.copy(selectedSources = selected) }
        saveFilterChoice()
    }

    fun resetFilters() {
        savedStateHandle["showPinnedOnly"] = false
        savedStateHandle.set<ArrayList<String>?>("selectedSources", null)
        _uiState.update { it.copy(showPinnedOnly = false, selectedSources = null, searchQuery = "") }
        saveFilterChoice()
    }

    fun dismissPinFailure() { _uiState.update { it.copy(pinFailure = null) } }

    fun toggleSessionPin(sessionId: String) {
        val repo = sessionRepository ?: return
        val scope = pinScope ?: return
        val intent = workspaceGeneration
        val state = _uiState.value
        if (!state.pinsAvailable || sessionId in state.savingPinIds || state.sessions.none { it.id == sessionId }) return
        val pinned = sessionId !in state.pinnedSessionIds
        pinRevision++
        _uiState.update { it.copy(savingPinIds = it.savingPinIds + sessionId, pinFailure = null) }
        viewModelScope.launch {
            pinMutex.lock()
            try {
                if (intent != workspaceGeneration) return@launch
                val saved = repo.setSessionPinned(scope, sessionId, pinned)
                if (intent == workspaceGeneration && pinScope == saved.scope) {
                    pinRevision++
                    _uiState.update { it.copy(pinnedSessionIds = saved.sessionIds) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (intent == workspaceGeneration) _uiState.update { it.copy(pinFailure = "Couldn't save pin") }
            } finally {
                pinMutex.unlock()
                if (intent == workspaceGeneration) _uiState.update { it.copy(savingPinIds = it.savingPinIds - sessionId) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        // HOME-S7: transient client-side query; refresh reapplies it without persistence.
        sessionRepository ?: return
        _uiState.update { it.copy(searchQuery = query) }
    }
}
