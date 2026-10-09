package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.InterruptSessionResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult

import io.github.anshuman2401.hermesmobile.data.history.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticEvent
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReplayEventsResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption
import io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult
import io.github.anshuman2401.hermesmobile.data.gateway.AttachImageResult
import io.github.anshuman2401.hermesmobile.data.gateway.AttachFileResult
import io.github.anshuman2401.hermesmobile.data.gateway.DetachImageResult
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.Draft
import io.github.anshuman2401.hermesmobile.data.local.SessionSelection
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.domain.LocalCacheException
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.domain.SessionNotFoundException
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.WorkspaceRecoveryOwnerResult
import io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult

/**
 * Implementation of SessionRepository coordinating between GatewayProtocolAdapter and VaultStorage.
 * Follows specs/architecture/contracts/session-and-turn-state-machine.md.
 */
class DefaultSessionRepository(
    private val gatewayAdapter: GatewayProtocolAdapter,
    private val vaultStorage: VaultStorage,
    private val contextProfileRepository: ContextProfileRepository? = null,
    private val httpTransport: AuthHttpTransport = OkHttpAuthTransport(),
    private val diagnosticRing: DiagnosticRing? = null,
    private val storedHistoryAdapter: StoredHistoryProtocolAdapter? = null,
    private val activityClock: () -> Double = { System.currentTimeMillis() / 1000.0 }
) : SessionRepository {
    override suspend fun getSessionPins() = vaultStorage.getSessionPins()
    override suspend fun setSessionPinned(scope: io.github.anshuman2401.hermesmobile.data.local.SessionPinScope, sessionId: String, pinned: Boolean) =
        vaultStorage.setSessionPinned(scope, sessionId, pinned)
    override suspend fun getSessionFilters() = vaultStorage.getSessionFilters()
    override suspend fun saveSessionFilters(filters: io.github.anshuman2401.hermesmobile.data.local.SessionFilters) =
        vaultStorage.saveSessionFilters(filters)

    // One current partition, bounded to actual list/cache rows. Switching back seeds its encrypted cache.
    private val activityMutex = Mutex()
    private var activityPartition: CacheAccessPartition? = null
    private var activityRowIds = emptySet<String>()
    private val activityFloors = mutableMapOf<String, Double>()
    private val _activityUpdates = MutableSharedFlow<SessionActivityUpdate>(extraBufferCapacity = 64)
    override val activityUpdates = _activityUpdates.asSharedFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val listInvalidations: Flow<Unit> = merge(
        gatewayAdapter.events.filter { event ->
            event.hasValidSession && (event is GatewayEvent.SessionsChanged ||
                event is GatewayEvent.MessageComplete || event is GatewayEvent.Error)
        }.map { gatewayAdapter.connectionGeneration },
        flow {
            var previous = gatewayAdapter.connectionStatus.value
            gatewayAdapter.connectionStatus.collect { status ->
                val connectedEdge = status == ConnectionStatus.CONNECTED && previous != ConnectionStatus.CONNECTED
                previous = status
                if (connectedEdge) emit(gatewayAdapter.connectionGeneration)
            }
        }
    ).filter { generation -> generation == gatewayAdapter.connectionGeneration }.map { Unit }
    // merge captures the originating generation before buffering; check again at publication.
    // Cold collection belongs to the consumer's lifetime, with no repository timer or service.

    override suspend fun isSessionActivityConnectionCurrent(scope: SessionActivityScope): Boolean =
        gatewayAdapter.connectionStatus.value == ConnectionStatus.CONNECTED && gatewayAdapter.connectionGeneration == scope.gatewayGeneration

    override suspend fun isSessionActivityScopeCurrent(scope: SessionActivityScope): Boolean = try {
        val partition = (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
        partition == scope.partition && gatewayAdapter.connectionGeneration == scope.gatewayGeneration
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    override suspend fun isCommandOwnerCurrent(owner: SessionActivityOwner): Boolean = isActivityOwnerCurrent(owner)

    private suspend fun isActivityOwnerCurrent(owner: SessionActivityOwner): Boolean {
        if (!bindingMatches(owner) || !isSessionActivityScopeCurrent(owner.scope)) return false
        val current = (vaultStorage.captureWorkspaceRecoveryOwner() as? WorkspaceRecoveryOwnerResult.Captured)?.owner
            ?: return false
        val expected = owner.authority.lease
        // Authoritative reads create fresh Selected instances; compare workspace values, not identity.
        val sameSelection = when (val selection = owner.authority.selection) {
            is SelectedContextReadResult.Selected -> (current.selection as? SelectedContextReadResult.Selected)?.name == selection.name
            SelectedContextReadResult.Absent -> current.selection == SelectedContextReadResult.Absent
            else -> false
        }
        return sameSelection &&
            current.lease.profileLocalId == expected.profileLocalId && current.lease.origin == expected.origin &&
            current.lease.epoch == expected.epoch && current.lease.credential == expected.credential &&
            gatewayAdapter.connectionGeneration == owner.scope.gatewayGeneration && bindingMatches(owner)
    }

    private fun bindingMatches(owner: SessionActivityOwner): Boolean {
        val active = _activeSession.value
        return owner.repository === this && active?.storedSessionId == owner.storedSessionId &&
            active.runtimeSessionId == owner.runtimeSessionId
    }

    override suspend fun captureSendActivityOwner(storedSessionId: String, runtimeSessionId: String): SessionActivityOwner? {
        val generation = gatewayAdapter.connectionGeneration
        val active = _activeSession.value
        if (storedSessionId.isBlank() || runtimeSessionId.isBlank() ||
            gatewayAdapter.connectionStatus.value != io.github.anshuman2401.hermesmobile.domain.ConnectionStatus.CONNECTED ||
            active?.storedSessionId != storedSessionId || active.runtimeSessionId != runtimeSessionId) return null
        val authority = (vaultStorage.captureWorkspaceRecoveryOwner() as? WorkspaceRecoveryOwnerResult.Captured)?.owner
            ?: return null
        val partition = (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
            ?: return null
        val owner = SessionActivityOwner(this, SessionActivityScope(partition, generation), authority, storedSessionId, runtimeSessionId)
        return owner.takeIf { isActivityOwnerCurrent(it) }
    }

    private fun mergeActivityRows(scope: SessionActivityScope, rows: List<SessionSummary>, replaceRows: Boolean): List<SessionSummary> {
        if (activityPartition != scope.partition) {
            activityPartition = scope.partition
            activityFloors.clear()
            activityRowIds = emptySet()
        }
        val ids = rows.map { it.id }.toSet()
        activityRowIds = if (replaceRows) ids else activityRowIds + ids
        if (replaceRows) activityFloors.keys.retainAll(ids)
        rows.forEach { row -> newerActivity(row.lastActive, activityFloors[row.id])?.let { activityFloors[row.id] = it } }
        return rows.withActivityFloor(activityFloors)
    }

    private suspend fun readActivityCache(scope: SessionActivityScope, selected: String?): SessionSummaryCache? = try {
        val cache = (vaultStorage.readSessionSummaryCache(scope.partition) as? CacheReadResult.Available)?.value
        cache?.takeIf { it.isFresh() && it.profileLocalId == scope.partition.profileLocalId && it.cachedProfileName == selected }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    override suspend fun recordSendActivity(owner: SessionActivityOwner) {
        if (!isActivityOwnerCurrent(owner)) return
        val selected = (owner.authority.selection as? SelectedContextReadResult.Selected)?.name
        // Obsolete, delayed reads cannot hold the replacement workspace's metadata lane.
        readActivityCache(owner.scope, selected)
        if (!isActivityOwnerCurrent(owner)) return
        activityMutex.withLock { recordCurrentActivity(owner, selected) }
    }

    private suspend fun recordCurrentActivity(owner: SessionActivityOwner, selected: String?) {
        if (!isActivityOwnerCurrent(owner)) return
        // Read the latest row under serialization; a concurrent list may have changed its title/preview.
        val cache = readActivityCache(owner.scope, selected)
        if (!isActivityOwnerCurrent(owner)) return
        if (cache != null) mergeActivityRows(owner.scope, cache.sessions, replaceRows = false)
        if (activityPartition != owner.scope.partition || owner.storedSessionId !in activityRowIds) return
        val activity = newerActivity(activityFloors[owner.storedSessionId], activityClock()) ?: return
        activityFloors[owner.storedSessionId] = activity
        // Observed write metadata only: no preview, admission claim, history or invented summary.
        _activityUpdates.tryEmit(SessionActivityUpdate(owner.scope, owner.storedSessionId, activity))
        if (cache != null && cache.sessions.any { it.id == owner.storedSessionId } &&
            isActivityOwnerCurrent(owner)) {
            val rows = cache.sessions.map { row ->
                if (row.id == owner.storedSessionId) row.copy(lastActive = activity) else row
            }.sortedByActivity()
            saveSummaryCache(cache.copy(sessions = rows), owner.scope.partition)
        }
    }

    override suspend fun reconcileSessionActivity(outcome: SessionListOutcome): SessionListOutcome? {
        val scope = outcome.activityScope ?: return outcome
        return activityMutex.withLock {
            if (!isSessionActivityScopeCurrent(scope)) null
            else outcome.copy(sessions = mergeActivityRows(scope, outcome.sessions, replaceRows = false))
        }
    }

    // Resolve-once cache IO may finish for an earlier workspace under the same root authority.
    // This current partition is for authorization only; IO still targets the captured partition.
    private suspend fun authorizedActivityCachePartition(scope: SessionActivityScope): CacheAccessPartition? = try {
        val current = (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
        current?.takeIf { it.profileLocalId == scope.partition.profileLocalId &&
            it.originSha256 == scope.partition.originSha256 && it.cacheEpoch == scope.partition.cacheEpoch &&
            gatewayAdapter.connectionGeneration == scope.gatewayGeneration }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun capturedActivityRows(scope: SessionActivityScope, rows: List<SessionSummary>, cachedRows: List<SessionSummary> = emptyList()): List<SessionSummary> {
        // An obsolete result must not replace the current workspace's in-memory floors.
        val floors = if (activityPartition == scope.partition) activityFloors.toMutableMap() else mutableMapOf<String, Double>()
        cachedRows.forEach { row -> newerActivity(row.lastActive, floors[row.id])?.let { floors[row.id] = it } }
        return rows.withActivityFloor(floors)
    }

    private suspend fun cacheListedActivity(
        rows: List<SessionSummary>, scope: SessionActivityScope?, partition: CacheAccessPartition?, selected: String?
    ): List<SessionSummary>? = activityMutex.withLock {
        if (scope != null && authorizedActivityCachePartition(scope) == null) return@withLock null
        val cache = if (scope != null) readActivityCache(scope, selected) else null
        val authorized = if (scope != null) authorizedActivityCachePartition(scope) ?: return@withLock null else null
        val sessions = when {
            scope == null -> rows.sortedByActivity()
            authorized != scope.partition -> capturedActivityRows(scope, rows, cache?.sessions.orEmpty())
            else -> {
                if (cache != null) mergeActivityRows(scope, cache.sessions, replaceRows = false)
                mergeActivityRows(scope, rows, replaceRows = true)
            }
        }
        val profile = scope?.partition?.profileLocalId ?: vaultStorage.getActiveProfileId()
        saveSummaryCache(SessionSummaryCache(sessions, profileLocalId = profile, cachedProfileName = selected), partition)
        if (scope != null && authorizedActivityCachePartition(scope) == null) null else sessions
    }

    private suspend fun cachedActivityOutcome(cache: SessionSummaryCache, scope: SessionActivityScope?, selected: String?): Result<SessionListOutcome> =
        activityMutex.withLock {
            val authorized = if (scope != null) authorizedActivityCachePartition(scope) ?: return@withLock staleActivityList() else null
            val sessions = when {
                scope == null -> cache.sessions.sortedByActivity()
                authorized != scope.partition -> capturedActivityRows(scope, cache.sessions)
                else -> mergeActivityRows(scope, cache.sessions, replaceRows = true)
            }
            Result.success(SessionListOutcome(sessions, true, selected, activityScope = scope))
        }

    private fun staleActivityList(): Result<SessionListOutcome> = Result.failure(LocalCacheException("Session list owner changed"))

    override suspend fun captureStoredHistoryScope(
        localProfileId: String, origin: CanonicalOrigin, remoteProfile: String,
        durableSessionId: String, requestGeneration: Long, tailGeneration: Long
    ): Result<StoredHistoryScope> {
        val capturedGeneration = gatewayAdapter.connectionGeneration
        val invalid = listOf(localProfileId, remoteProfile, durableSessionId).any {
            it.isBlank() || it != it.trim() || it.length > 256 || it.any { c ->
                c.isISOControl() || c in "\\/" || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'
            }
        } || durableSessionId in listOf(".", "..") || durableSessionId.contains("..")
        if (invalid || storedHistoryAdapter == null)
            return Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
        return try {
            val lease = vaultStorage.readAuthCredentialLease(origin.originUrl)
            if (lease == null || lease.profileLocalId != localProfileId || lease.origin != origin.originUrl)
                Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
            else {
                val scope = StoredHistoryScope(localProfileId, origin, remoteProfile, durableSessionId,
                    requestGeneration, tailGeneration, lease, vaultStorage.getSelectedContextProfile())
                capturedHistoryConnection = scope to capturedGeneration
                Result.success(scope)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)) }
    }

    override suspend fun fetchStoredHistoryPage(
        scope: StoredHistoryScope, budget: StoredHistoryBudget, offset: Int
    ): Result<StoredHistoryPage> {
        val adapter = storedHistoryAdapter
            ?: return Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
        // No selection, runtime, replay, snapshot or diagnostics mutation belongs to this read.
        // Capture the lane before any suspension; supersession must not turn scoped work into a pure read.
        val operationBound = scopedResumeOwner?.first === scope
        return try {
            if (!operationBound) {
                if (vaultStorage.getSelectedContextProfile() != scope.selectedContext) scope.invalidate()
                if (!scope.current) return Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
                val result = adapter.fetchPage(scope, budget, offset)
                if (vaultStorage.getSelectedContextProfile() != scope.selectedContext) scope.invalidate()
                return if (!scope.current) Result.failure(StoredHistoryException(StoredHistoryFailure.STALE)) else result
            }
            if (!isStoredHistoryPublicationCurrent(scope, budget))
                return Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
            val candidate = adapter.fetchPageForOpen(scope, budget, offset).getOrThrow()
            if (!isStoredHistoryPublicationCurrent(scope, budget))
                return Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
            if (candidate.isDescendant) {
                val owner = scopedResumeOwner
                val active = _activeSession.value
                if (owner?.first !== scope || !openLiveCurrent(scope, budget, owner.second) ||
                    owner.third == null || owner.third !== active ||
                    active?.storedSessionId != candidate.page.resolvedSessionId || active.runtimeSessionId.isNullOrBlank())
                    return Result.failure(StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT))
            }
            if (!isStoredHistoryPublicationCurrent(scope, budget))
                Result.failure(StoredHistoryException(StoredHistoryFailure.STALE)) else Result.success(candidate.page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: StoredHistoryException) { Result.failure(failure) }
        catch (_: Exception) { Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)) }
    }

    @Volatile private var capturedHistoryConnection: Pair<StoredHistoryScope, Long>? = null
    @Volatile private var scopedResumeOwner: Triple<StoredHistoryScope, Long, SessionHandle?>? = null

    override suspend fun isStoredHistoryCurrent(scope: StoredHistoryScope, budget: StoredHistoryBudget?, requireConnection: Boolean): Boolean {
        if (!scope.current || scope.remoteProfile != scope.selectedContext || capturedHistoryConnection?.first !== scope ||
            vaultStorage.getSelectedContextProfile() != scope.selectedContext) return false
        val expected = budget?.receipt ?: scope.lease
        val actual = vaultStorage.readAuthCredentialLease(scope.origin.originUrl) ?: return false
        if (actual.profileLocalId != scope.localProfileId || actual.origin != expected.origin ||
            actual.epoch != expected.epoch || actual.credential != expected.credential) return false
        val owner = scopedResumeOwner
        return vaultStorage.getSelectedContextProfile() == scope.selectedContext &&
            scope.current && capturedHistoryConnection?.first === scope &&
            owner?.first === scope && owner.second == gatewayAdapter.connectionGeneration &&
            (!requireConnection || gatewayAdapter.isScopedConnectionCurrent(scope.origin, owner.second))
    }

    private suspend fun requireStoredHistoryCurrent(scope: StoredHistoryScope) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!isStoredHistoryCurrent(scope)) throw StoredHistoryException(StoredHistoryFailure.STALE)
    }

    override suspend fun resumeStoredHistory(scope: StoredHistoryScope): Result<SessionResumeResult> {
        val captured = capturedHistoryConnection
        if (captured?.first !== scope) return Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
        val generation = captured.second
        scopedResumeOwner?.first?.takeUnless { it === scope }?.invalidate()
        scopedResumeOwner = Triple(scope, generation, null)
        if (!gatewayAdapter.isScopedConnectionCurrent(scope.origin, generation))
            return Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
        return try {
            requireStoredHistoryCurrent(scope)
            val result = gatewayAdapter.resumeSessionScoped(scope.requestedSessionId, scope.remoteProfile, scope.origin, generation)
            requireStoredHistoryCurrent(scope)
            when (result) {
                is GatewayResult.Failure -> {
                    // No raw host errors or payloads enter diagnostic/presentation sinks.
                    diagnosticRing?.record(DiagnosticEvent.WebSocket.ResumeOutcome(false, "Scoped resume unavailable"))
                    Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
                }
                is GatewayResult.Success -> {
                    val data = result.value
                    if (data.durableSessionId != scope.requestedSessionId || data.runtimeSessionId.isBlank())
                        throw StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT)
                    val prior = vaultStorage.getSessionSelection()
                    requireStoredHistoryCurrent(scope)
                    val title = prior?.takeIf { it.profileLocalId == scope.localProfileId &&
                        it.storedSessionId == data.durableSessionId }?.lastKnownTitle
                    if (!vaultStorage.saveSessionSelectionIfCurrent(
                            SessionSelection(data.durableSessionId, title, profileLocalId = scope.localProfileId),
                            scope.lease, scope.selectedContext))
                        throw StoredHistoryException(StoredHistoryFailure.STALE)
                    requireStoredHistoryCurrent(scope)
                    _activeSession.value = SessionHandle(data.durableSessionId, data.runtimeSessionId, title, false)
                    scopedResumeOwner = Triple(scope, generation, _activeSession.value)
                    _activeMessages.value = data.withoutToolPayloads().messages
                    requireStoredHistoryCurrent(scope)
                    diagnosticRing?.record(DiagnosticEvent.WebSocket.ResumeOutcome(true))
                    gatewayAdapter.markHistoryRehydrated(data.runtimeSessionId)
                    requireStoredHistoryCurrent(scope)
                    seedReplayWatermark(data)
                    requireStoredHistoryCurrent(scope)
                    Result.success(data)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: StoredHistoryException) { Result.failure(failure) }
        catch (_: Exception) { Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)) }
    }

    override suspend fun isStoredHistoryPublicationCurrent(scope: StoredHistoryScope, budget: StoredHistoryBudget): Boolean {
        val attempted = budget.receipt
        return publicationCurrent(scope, budget) || advanceOwnHeaders(scope, budget, attempted)
    }

    private suspend fun publicationCurrent(scope: StoredHistoryScope, budget: StoredHistoryBudget): Boolean {
        currentCoroutineContext().ensureActive()
        fun owned() = scope.current && budget.scope === scope &&
            capturedHistoryConnection?.first === scope && scope.remoteProfile == scope.selectedContext
        if (!owned() || vaultStorage.getSelectedContextProfile() != scope.selectedContext) return false
        val expected = budget.receipt
        val actual = vaultStorage.readAuthCredentialLease(scope.origin.originUrl) ?: return false
        if (!owned() || budget.receipt !== expected || actual.profileLocalId != scope.localProfileId ||
            actual.origin != expected.origin || actual.epoch != expected.epoch || actual.credential != expected.credential ||
            vaultStorage.getSelectedContextProfile() != scope.selectedContext) return false
        // The context read above is another suspension boundary: validate this operation's receipt again.
        val confirmed = vaultStorage.readAuthCredentialLease(scope.origin.originUrl) ?: return false
        return owned() && budget.receipt === expected && confirmed.profileLocalId == scope.localProfileId &&
            confirmed.origin == expected.origin && confirmed.epoch == expected.epoch && confirmed.credential == expected.credential
    }

    /** Await only a header attempt already installed by this operation, never a future exchange/body. */
    private suspend fun advanceOwnHeaders(scope: StoredHistoryScope, budget: StoredHistoryBudget,
        attempted: io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease): Boolean {
        val decision = budget.headerDecision
        if (decision.issuingReceipt !== attempted) return false
        val outcome = if (decision.isComplete) decision.await() else {
            val remaining = budget.remainingMillis()
            if (remaining <= 0) return false
            withTimeoutOrNull(remaining) { decision.await() } ?: return false
        }
        return outcome.state == StoredHistoryHeaderState.ACCEPTED &&
            outcome.acceptedReceipt === budget.receipt && budget.receipt !== attempted &&
            publicationCurrent(scope, budget)
    }

    private suspend fun openLiveCurrent(scope: StoredHistoryScope, budget: StoredHistoryBudget, generation: Long): Boolean {
        if (!isStoredHistoryPublicationCurrent(scope, budget)) return false
        currentCoroutineContext().ensureActive()
        return scopedResumeOwner?.let { it.first === scope && it.second == generation } == true &&
            capturedHistoryConnection?.let { it.first === scope && it.second == generation } == true &&
            gatewayAdapter.isScopedConnectionCurrent(scope.origin, generation) && scope.current
    }

    override suspend fun openStoredHistory(scope: StoredHistoryScope, budget: StoredHistoryBudget,
        onStored: suspend (Result<StoredHistoryPage>) -> Unit,
        onResumed: suspend (Result<SessionResumeResult>) -> Unit): Result<Unit> {
        val captured = capturedHistoryConnection
        if (captured?.first !== scope || budget.scope !== scope)
            return Result.failure(StoredHistoryException(StoredHistoryFailure.STALE))
        val generation = captured.second
        scopedResumeOwner?.first?.takeUnless { it === scope }?.invalidate()
        scopedResumeOwner = Triple(scope, generation, null)
        val stored = CompletableDeferred<Result<StoredHistoryCandidate>>()
        val resumed = CompletableDeferred<Result<SessionResumeResult>>()
        suspend fun liveCurrent() = openLiveCurrent(scope, budget, generation)
        suspend fun bound(data: SessionResumeResult) = data.runtimeSessionId.isNotBlank() &&
            data.observedRuntimeSessionId == data.runtimeSessionId &&
            data.observedDurableSessionId == data.durableSessionId && data.durableSessionId.isNotBlank() && liveCurrent()
        suspend fun <T> read(block: suspend () -> Result<T>): Result<T> = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: StoredHistoryException) { Result.failure(failure) }
            catch (_: Exception) { Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)) }
        return try {
            supervisorScope {
                val restJob = async(start = CoroutineStart.UNDISPATCHED) {
                    val result = read {
                        if (!isStoredHistoryPublicationCurrent(scope, budget))
                            throw StoredHistoryException(StoredHistoryFailure.STALE)
                        storedHistoryAdapter?.fetchPageForOpen(scope, budget)
                            ?: Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
                    }
                    stored.complete(result)
                    val admitted = result.fold(onSuccess = { candidate ->
                        if (!candidate.isDescendant) Result.success(candidate.page)
                        else {
                            val peer = resumed.await().getOrNull()
                            if (peer != null && bound(peer) && peer.durableSessionId == candidate.page.resolvedSessionId)
                                Result.success(candidate.page)
                            else Result.failure(StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT))
                        }
                    }, onFailure = { Result.failure(it) })
                    if (isStoredHistoryPublicationCurrent(scope, budget)) onStored(admitted)
                }
                val liveJob = async {
                    val result = read {
                        if (!liveCurrent()) throw StoredHistoryException(StoredHistoryFailure.STALE)
                        when (val wire = gatewayAdapter.resumeSessionScoped(scope.requestedSessionId,
                            scope.remoteProfile, scope.origin, generation)) {
                            is GatewayResult.Success -> Result.success(wire.value)
                            is GatewayResult.Failure -> Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE))
                        }
                    }
                    resumed.complete(result)
                    val admitted = read {
                        val data = result.getOrThrow()
                        if (!bound(data)) throw StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT)
                        if (data.durableSessionId != scope.requestedSessionId) {
                            val peer = stored.await().getOrNull()
                            if (peer == null || peer.page.resolvedSessionId != data.durableSessionId || !bound(data))
                                throw StoredHistoryException(StoredHistoryFailure.UNBOUND_DESCENDANT)
                        }
                        val prior = vaultStorage.getSessionSelection()
                        if (!liveCurrent()) throw StoredHistoryException(StoredHistoryFailure.STALE)
                        val title = prior?.takeIf { it.profileLocalId == scope.localProfileId &&
                            it.storedSessionId == data.durableSessionId }?.lastKnownTitle
                        var selected = false
                        for (attempt in 1..3) {
                            if (!liveCurrent()) break
                            val receipt = budget.receipt
                            selected = vaultStorage.saveSessionSelectionIfCurrent(
                                SessionSelection(data.durableSessionId, title, profileLocalId = scope.localProfileId),
                                receipt, scope.selectedContext)
                            if (selected && liveCurrent()) break
                            selected = false
                            if (!advanceOwnHeaders(scope, budget, receipt)) break
                        }
                        if (!selected || !liveCurrent()) throw StoredHistoryException(StoredHistoryFailure.STALE)
                        _activeSession.value = SessionHandle(data.durableSessionId, data.runtimeSessionId, title, false)
                    scopedResumeOwner = Triple(scope, generation, _activeSession.value)
                        _activeMessages.value = data.withoutToolPayloads().messages
                        Result.success(data)
                    }
                    if (liveCurrent()) {
                        // Install presentation/buffer attribution before releasing replay-held events.
                        onResumed(admitted)
                        val data = admitted.getOrNull()
                        if (data != null && liveCurrent()) {
                            gatewayAdapter.markHistoryRehydrated(data.runtimeSessionId)
                            seedReplayWatermark(data, scope, budget, generation)
                        }
                    }
                }
                restJob.await()
                liveJob.await()
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: StoredHistoryException) { Result.failure(failure) }
        catch (_: Exception) { Result.failure(StoredHistoryException(StoredHistoryFailure.UNAVAILABLE)) }
        finally { stored.cancel(); resumed.cancel() }
    }

    override suspend fun attachImage(runtimeSessionId: String, bytes: ByteArray, name: String, ext: String?): GatewayResult<AttachImageResult> =
        gatewayAdapter.attachImageBytes(runtimeSessionId, java.util.Base64.getEncoder().encodeToString(bytes), name, ext)

    override suspend fun attachFile(runtimeSessionId: String, bytes: ByteArray, mimeType: String, name: String): GatewayResult<AttachFileResult> =
        gatewayAdapter.attachFile(runtimeSessionId, "data:$mimeType;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}", name)

    override suspend fun detachImage(runtimeSessionId: String, serverPath: String): GatewayResult<DetachImageResult> =
        gatewayAdapter.detachImage(runtimeSessionId, serverPath)

    override suspend fun captureMessageEditAccess(storedId: String, runtimeId: String): io.github.anshuman2401.hermesmobile.data.turn.MessageEditAccess? {
        val owner = captureSendActivityOwner(storedId, runtimeId) ?: return null
        val origin = io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin.parse(owner.authority.lease.origin).getOrThrow()
        val profile = (owner.authority.selection as? SelectedContextReadResult.Selected)?.name ?: return null
        return object : io.github.anshuman2401.hermesmobile.data.turn.MessageEditAccess {
            override val ownerKey = java.security.MessageDigest.getInstance("SHA-256").digest(
                listOf(owner.authority.lease.profileLocalId, owner.authority.lease.origin,
                    owner.authority.lease.epoch, profile, storedId).joinToString("\u0000").toByteArray()
            ).joinToString("") { "%02x".format(it) }
            override suspend fun isCurrent() = isCommandOwnerCurrent(owner) &&
                gatewayAdapter.isScopedConnectionCurrent(origin, owner.scope.gatewayGeneration)
            override suspend fun snapshot(): GatewayResult<SessionResumeResult> {
                if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                val result = gatewayAdapter.resumeSessionScoped(storedId, profile, origin, owner.scope.gatewayGeneration)
                if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                if (result is GatewayResult.Success && (result.value.runtimeSessionId != runtimeId || result.value.durableSessionId != storedId ||
                        result.value.observedRuntimeSessionId != runtimeId || result.value.observedDurableSessionId != storedId))
                    return GatewayResult.Failure(GatewayFailure.NotConnected("Session changed"))
                return result
            }
            override suspend fun interrupt(): GatewayResult<InterruptSessionResult> {
                if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                val result = gatewayAdapter.interruptEditScoped(runtimeId, origin, owner.scope.gatewayGeneration)
                return if (isCurrent()) result else GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Session changed"))
            }
            override suspend fun submit(text: String, rowId: Long): GatewayResult<PromptSubmitResult> {
                if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                val result = gatewayAdapter.submitEditScoped(runtimeId, text, rowId, origin, owner.scope.gatewayGeneration)
                return if (isCurrent()) result else GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Session changed"))
            }
            override fun toString() = "MessageEditAccess([REDACTED])"
        }
    }

    private val pickerWriteMutex = kotlinx.coroutines.sync.Mutex()

    override suspend fun capturePickerAccess(runtimeSessionId: String): SessionPickerAccess? {
        val stored = _activeSession.value?.storedSessionId ?: return null
        val owner = captureSendActivityOwner(stored, runtimeSessionId) ?: return null
        val origin = io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin.parse(owner.authority.lease.origin).getOrThrow()
        return object : SessionPickerAccess {
            override suspend fun isCurrent() = isCommandOwnerCurrent(owner)
            override suspend fun isSameContext() = isCommandOwnerCurrent(SessionActivityOwner(
                this@DefaultSessionRepository, SessionActivityScope(owner.scope.partition, gatewayAdapter.connectionGeneration),
                owner.authority, owner.storedSessionId, owner.runtimeSessionId))
            override suspend fun options(): GatewayResult<List<SessionModelOption>> {
                if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                val result = gatewayAdapter.listSessionModelOptionsScoped(runtimeSessionId, origin, owner.scope.gatewayGeneration)
                return if (isCurrent()) result else GatewayResult.Failure(GatewayFailure.NotConnected())
            }
            private suspend fun write(option: SessionModelOption?, effort: String?): GatewayResult<SessionPickerSetResult> {
                if (!pickerWriteMutex.tryLock()) return GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("Change already pending"))
                try {
                    if (!isCurrent()) return GatewayResult.Failure(GatewayFailure.NotConnected())
                    val result = gatewayAdapter.setSessionPickerScoped(runtimeSessionId, option, effort, origin, owner.scope.gatewayGeneration)
                    return if (isCurrent()) result else GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Session changed"))
                } finally { pickerWriteMutex.unlock() }
            }
            override suspend fun setModel(option: SessionModelOption) = write(option, null)
            override suspend fun setReasoning(effort: String) = write(null, effort)
        }
    }

    override suspend fun listSessionModelOptions(runtimeSessionId: String): GatewayResult<List<SessionModelOption>> =
        capturePickerAccess(runtimeSessionId)?.options() ?: GatewayResult.Failure(GatewayFailure.NotConnected())
    override suspend fun setSessionModel(runtimeSessionId: String, option: SessionModelOption): GatewayResult<SessionPickerSetResult> =
        capturePickerAccess(runtimeSessionId)?.setModel(option) ?: GatewayResult.Failure(GatewayFailure.NotConnected())
    override suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): GatewayResult<SessionPickerSetResult> =
        capturePickerAccess(runtimeSessionId)?.setReasoning(effort) ?: GatewayResult.Failure(GatewayFailure.NotConnected())

    // F007-S3: sessions whose replay-watermark seed failed deterministically
    // (RPC failure / truncation / epoch anomaly). The fallback loop
    // (history fallback -> loadSession() -> resume) must not re-attempt the seed
    // for the same resume cycle or it re-triggers the fallback forever; a fresh
    // epoch (host restart) invalidates the memory and seeding is retried.
    // F-007 polish: keyed by durableSessionId (stable across resumes of the
    // same session, unlike runtimeSessionId), ConcurrentHashMap to match the
    // reducer's defensive style, and a seed failure is only remembered after
    // one bounded same-resume retry — a single transient blip must not disable
    // seeding for the whole epoch.
    private val seedFailedEpochs = java.util.concurrent.ConcurrentHashMap<String, String?>()

    private companion object {
        /** F-007 polish: bounded seed attempts per resume before the failure is remembered. */
        const val SEED_MAX_ATTEMPTS = 2
    }

    private val _activeSession = MutableStateFlow<SessionHandle?>(null)
    override val activeSession: StateFlow<SessionHandle?> = _activeSession.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
    override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages.asStateFlow()

    private val _activeTurnStreaming = MutableStateFlow(ChatStreamingStatus())
    override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
        _activeTurnStreaming.asStateFlow()

    /**
     * HOME-S3: publish/clear the active session's streaming mark, called by the
     * chat surface that owns the turn lifecycle. Safe no-op when the reporting
     * session is no longer the active one (e.g. cleared after a switch).
     */
    fun publishTurnStreaming(storedSessionId: String?, isStreaming: Boolean) {
        if (storedSessionId == null || storedSessionId == _activeSession.value?.storedSessionId) {
            _activeTurnStreaming.value = ChatStreamingStatus(storedSessionId, isStreaming)
        } else {
            _activeTurnStreaming.value = ChatStreamingStatus(storedSessionId = null, isStreaming = false)
        }
    }

    override suspend fun applyWorkspaceRecovery(request: WorkspaceRecoveryRequest): WorkspaceRecoveryApplyResult {
        if (!request.consume()) return WorkspaceRecoveryApplyResult.Stale
        return when (vaultStorage.clearSelectedContextProfileIfCurrent(request.owner.lease, request.selectedName)) {
            io.github.anshuman2401.hermesmobile.data.local.WorkspaceClearResult.Applied -> WorkspaceRecoveryApplyResult.Applied
            io.github.anshuman2401.hermesmobile.data.local.WorkspaceClearResult.Stale -> WorkspaceRecoveryApplyResult.Stale
            io.github.anshuman2401.hermesmobile.data.local.WorkspaceClearResult.Failed -> WorkspaceRecoveryApplyResult.Failed
            io.github.anshuman2401.hermesmobile.data.local.WorkspaceClearResult.Unsupported -> WorkspaceRecoveryApplyResult.Unsupported
        }
    }

    override suspend fun loadCachedSessions(): Result<SessionListOutcome?> = try {
        val partition = (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition
        if (partition == null) Result.success(null) else {
            val selected = vaultStorage.getSelectedContextProfile()
            val read = vaultStorage.readSessionSummaryCache(partition)
            if (read is CacheReadResult.Corrupt || read is CacheReadResult.Unavailable)
                Result.failure(LocalCacheException("Saved sessions could not be loaded."))
            else {
                val cache = (read as? CacheReadResult.Available)?.value
                if (cache == null || !cache.isFresh() || cache.profileLocalId != partition.profileLocalId ||
                    cache.cachedProfileName != selected ||
                    (vaultStorage.activeAccessPartition() as? CacheAccessPartitionResult.Available)?.partition != partition)
                    Result.success(null)
                else cachedActivityOutcome(cache, SessionActivityScope(partition, gatewayAdapter.connectionGeneration), selected)
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.failure(LocalCacheException("Saved sessions could not be loaded.")) }

    override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> {
        val activityGeneration = gatewayAdapter.connectionGeneration
        val captured = vaultStorage.captureWorkspaceRecoveryOwner() as? io.github.anshuman2401.hermesmobile.data.local.WorkspaceRecoveryOwnerResult.Captured
            ?: return Result.failure(LocalCacheException("Workspace authority unavailable"))
        val selectedProfile = (captured.owner.selection as? io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Selected)?.name
        // PR-1 REQ-06 (review BLOCKER-3): resolve the access partition EXACTLY
        // ONCE at operation entry (architecture §4) and thread this single
        // resolution through this operation's cache write AND fallback reads —
        // a mid-flight workspace/selection change must never redirect either.
        val entryPartition = (vaultStorage.activeAccessPartition()
            as? CacheAccessPartitionResult.Available)?.partition
        val activityScope = entryPartition?.let { SessionActivityScope(it, activityGeneration) }
        if (selectedProfile != null) {
            val repo = contextProfileRepository ?: DefaultContextProfileRepository(vaultStorage, httpTransport, diagnosticRing = diagnosticRing)
            val scopedResult = repo.getScopedSessionsCaptured(selectedProfile, captured.owner.lease)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // Profile scope stamp for the cache entry written by THIS path.
            // Kept as a local (not recomputed after the fetch) so the guard and
            // the write always stamp the same value, and so a preference change
            // mid-flight can never mismatch read-guard vs write stamp.
            val cachedProfileName = selectedProfile
            if (scopedResult is ScopedSessionsResult.StaleSelection) {
                return Result.success(SessionListOutcome(emptyList(), false, selectedProfile,
                    recoveryRequirement = WorkspaceRecoveryRequest(captured.owner, selectedProfile)))
            }
            return when {
                scopedResult is ScopedSessionsResult.Success -> {
                    val sessions = cacheListedActivity(scopedResult.sessions, activityScope, entryPartition, cachedProfileName)
                        ?: return staleActivityList()
                    diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = sessions.size, isOfflineCache = false, isScoped = true, profile = selectedProfile))
                    propagateServerTitle(sessions)
                    Result.success(
                        SessionListOutcome(
                            sessions = sessions,
                            isOfflineCache = false,
                            selectedProfileName = selectedProfile,
                            activityScope = activityScope
                        )
                    )
                }
                scopedResult is ScopedSessionsResult.Error -> {
                    // Offline fallback: serve the vault cache ONLY when it was
                    // written by this profile-scoped path (cachedProfileName
                    // matches the selected profile). A cache entry written by
                    // the unscoped gatewayAdapter path (cachedProfileName null)
                    // is never presented as the selected profile's sessions.
                    val failureEx = scopedResult.exception
                    // REQ-06 (review BLOCKER-3): fallback read uses the
                    // partition resolved at THIS operation's entry — never a
                    // mid-fetch re-resolution (a selection change during the
                    // fetch can therefore neither serve nor clobber another
                    // scope's record; identity binding still fail-closes).
                    val cached = when (entryPartition) {
                        null -> null
                        else ->
                            when (val read =
                                vaultStorage.readSessionSummaryCache(entryPartition)
                            ) {
                                is CacheReadResult.Available -> read.value
                                else -> null
                            }
                    }
                    if (cached != null && cached.isFresh() &&
                        isRecordForActiveProfile(cached.profileLocalId) &&
                        cached.cachedProfileName == selectedProfile
                    ) {
                        diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = cached.sessions.size, isOfflineCache = true, isScoped = true, profile = selectedProfile))
                        cachedActivityOutcome(cached, activityScope, selectedProfile)
                    } else {
                        diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = 0, isOfflineCache = false, isScoped = true, profile = selectedProfile, error = failureEx.message ?: "Failed to list scoped sessions"))
                        // PR-1 SEC: fail closed — a cache miss under the live
                        // partition (or another scope's record) is never served.
                        // The original scoped-fetch message is preserved (existing
                        // contract) but surfaced as a LocalCacheException so the
                        // UI maps it to the cache-failure state.
                        Result.failure(
                            LocalCacheException(
                                failureEx.message ?: "Failed to list scoped sessions",
                                cause = failureEx
                            )
                        )
                    }
                }
                else -> Result.failure(
                    LocalCacheException("Network unavailable and no cached session summary available.")
                )
            }
        }

        val networkResult = gatewayAdapter.listSessions()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return when (networkResult) {
            is GatewayResult.Success -> {
                // Use the same activity policy for live and cached pages.
                val sessions = cacheListedActivity(networkResult.value.sessions, activityScope, entryPartition, null)
                    ?: return staleActivityList()
                diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = sessions.size, isOfflineCache = false, isScoped = false))

                // CHG-003-S1: server-authoritative title propagation.
                // session.list is the ONLY title source (owner contract Q2). When a summary
                // matches the active session's durable ID, propagate its title into the
                // active handle and refresh SessionSelection.lastKnownTitle in the vault.
                // A summary without a title never clears a previously known one.
                propagateServerTitle(sessions)

                Result.success(SessionListOutcome(sessions = sessions, isOfflineCache = false, selectedProfileName = null, activityScope = activityScope))
            }
            is GatewayResult.Failure -> {
                // Fall back to local vault cache ONLY when it is inside its 7-day
                // freshness window (CHG-003-S5): cache past expiry is NOT returned
                // as list data — it behaves as a failure/offline state instead.
                // REQ-06 (review BLOCKER-3): entry-resolved partition — no
                // mid-operation re-resolution.
                val cached = when (entryPartition) {
                        null -> null
                        else ->
                            when (val read =
                                vaultStorage.readSessionSummaryCache(entryPartition)
                            ) {
                                is CacheReadResult.Available -> read.value
                                else -> null
                            }
                    }
                if (cached != null && cached.isFresh() && isRecordForActiveProfile(cached.profileLocalId)) {
                    diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = cached.sessions.size, isOfflineCache = true, isScoped = false))
                    cachedActivityOutcome(cached, activityScope, null)
                } else {
                    diagnosticRing?.record(DiagnosticEvent.SessionList.Outcome(count = 0, isOfflineCache = false, isScoped = false, error = networkResult.failure.toString()))
                    Result.failure(
                        LocalCacheException("Network unavailable and no cached session summary available.")
                    )
                }
            }
        }
    }

    override suspend fun createDraftSession(title: String?): Result<SessionHandle> {
        // Thread the Android-selected workspace profile into the RPC so the new session
        // lands in the chosen profile without requiring a host-wide sticky-default mutation.
        val selectedProfile = vaultStorage.getSelectedContextProfile()
        // Brain owns host defaults. The local ProfileModelDefault record remains
        // an explicitly stale/display-only cache and is never a create authority.
        val result = gatewayAdapter.createSession(
            title = title,
            source = "android",
            profile = selectedProfile,
            model = null,
            reasoningEffort = null
        )
        return when (result) {
            is GatewayResult.Success -> {
                val storedId = result.value.durableSessionId
                val runtimeId = result.value.runtimeSessionId

                // CHG-003-S1: create titles come only from the current create request. A
                // titleless create must not fabricate a title from a prior vault selection.
                val effectiveTitle = title

                val activeProfileId = vaultStorage.getActiveProfileId()
                // Persist selection durable ID
                vaultStorage.saveSessionSelection(
                    SessionSelection(
                        storedSessionId = storedId,
                        lastKnownTitle = effectiveTitle,
                        profileLocalId = activeProfileId
                    )
                )

                val handle = SessionHandle(
                    storedSessionId = storedId,
                    runtimeSessionId = runtimeId,
                    title = effectiveTitle,
                    isDraft = true,
                    // Normal create inherits the host defaults; true means no
                    // degraded-default notice, not verified local-cache adoption.
                    savedDefaultModelApplied = true
                )
                _activeSession.value = handle
                _activeMessages.value = emptyList()
                Result.success(handle)
            }
            is GatewayResult.Failure -> {
                // CHAT-015 B2: typed failure so the UI can build a REDACTED
                // notice (never rendering host-controlled Rpc.data).
                Result.failure(SessionCreateFailedException(result.failure))
            }
        }
    }

    override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
        // Thread the Android-selected workspace profile so the resumed session is
        // explicitly routed to the chosen profile without a host-wide sticky mutation.
        val selectedProfile = vaultStorage.getSelectedContextProfile()
        val result = gatewayAdapter.resumeSession(storedSessionId, profile = selectedProfile)
        return when (result) {
            is GatewayResult.Success -> {
                diagnosticRing?.record(DiagnosticEvent.WebSocket.ResumeOutcome(success = true))
                val data = result.value

                // CHG-003-S1: preserve previously known title.
                // Per owner contract Q2, resume responses carry no title (session.list is the
                // only title source), so the prior SessionSelection.lastKnownTitle must never
                // be overwritten with null. The persisted title keeps flowing to
                // restoreSelectedSession and to the next listSessions title refresh.
                val priorSelection = vaultStorage.getSessionSelection()
                val priorTitle = if (priorSelection != null && isRecordForActiveProfile(priorSelection.profileLocalId)) {
                    priorSelection.takeUnless { it.storedSessionId != data.durableSessionId }?.lastKnownTitle
                } else {
                    null
                }

                val activeProfileId = vaultStorage.getActiveProfileId()
                vaultStorage.saveSessionSelection(
                    SessionSelection(
                        storedSessionId = data.durableSessionId,
                        lastKnownTitle = priorTitle,
                        profileLocalId = activeProfileId
                    )
                )

                _activeSession.value = SessionHandle(
                    storedSessionId = data.durableSessionId,
                    runtimeSessionId = data.runtimeSessionId,
                    title = priorTitle,
                    isDraft = false
                )
                _activeMessages.value = data.messages

                // Mark history rehydrated in gateway adapter so sequence replay can proceed
                gatewayAdapter.markHistoryRehydrated(data.runtimeSessionId)

                // F007-S3: seed the replay watermark from the host's replay state so
                // live sequenced events on an attached historical session stream into
                // the timeline instead of tripping the gap detector (seq >= 2 vs an
                // absent watermark) and collapsing to drop-and-rehydrate loops.
                // Uses the reducer-tested `session.events.since` first-sweep path
                // (replayEventsSince): the watermark is committed only after all
                // returned events parse, and epoch/truncation/count anomalies still
                // dispatch the existing history fallback signals (re-hydration
                // preserved). Seed outcome is deliberately not propagated to the
                // resume Result: the resumed messages remain the authoritative
                // hydration either way.
                seedReplayWatermark(data)

                Result.success(data)
            }
            is GatewayResult.Failure -> {
                val failure = result.failure
                diagnosticRing?.record(DiagnosticEvent.WebSocket.ResumeOutcome(success = false, error = "Resume failed"))
                if (failure is GatewayFailure.Rpc && failure.code == 4007) {
                    Result.failure(SessionNotFoundException(storedSessionId))
                } else if (failure.toString().contains("4007", ignoreCase = true) || failure.toString().contains("not found", ignoreCase = true)) {
                    Result.failure(SessionNotFoundException(storedSessionId))
                } else {
                    Result.failure(Exception("Failed to resume session: $failure"))
                }
            }
        }
    }

    /**
     * CHAT-004 TASK-004 (frozen plan): read-only passthrough of
     * `session.resume` for the load-earlier fetch. Deliberately does NOT
     * assign _activeMessages, does NOT call markHistoryRehydrated, does NOT
     * mutate replay watermarks, and does NOT touch the active handle —
     * hydration remains exclusively the resumeSession() path.
     */
    override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> {
        return when (val result = gatewayAdapter.resumeSession(durableSessionId)) {
            is GatewayResult.Success -> Result.success(result.value)
            is GatewayResult.Failure -> {
                val failure = result.failure
                if (failure is GatewayFailure.Rpc && failure.code == 4007) {
                    Result.failure(SessionNotFoundException(durableSessionId))
                } else {
                    Result.failure(Exception("Failed to fetch history snapshot: $failure"))
                }
            }
        }
    }

    override suspend fun restoreSelectedSession(): SessionHandle? {
        val selection = vaultStorage.getSessionSelection() ?: return null
        if (!isRecordForActiveProfile(selection.profileLocalId)) {
            return null
        }

        // Invariant: never restore a runtimeSessionId as trusted across process restart.
        // The durable storedSessionId is restored with runtimeSessionId = null until resumeSession is called.
        val restored = SessionHandle(
            storedSessionId = selection.storedSessionId,
            runtimeSessionId = null,
            title = selection.lastKnownTitle,
            isDraft = false
        )
        _activeSession.value = restored
        return restored
    }

    override suspend fun clearActiveSession() {
        _activeSession.value = null
        _activeMessages.value = emptyList()
        vaultStorage.clearSessionSelection()
    }

    override suspend fun getActiveSession(): SessionHandle? {
        return _activeSession.value
    }

    override suspend fun activeSessionModels(): Result<Map<String, String>> {
        val result = gatewayAdapter.listActiveSessions()
        return when (result) {
            is GatewayResult.Success -> {
                val map = mutableMapOf<String, String>()
                for (item in result.value.sessions) {
                    val model = item.model?.takeUnless { it.isBlank() } ?: continue
                    if (item.id.isNotBlank()) {
                        map[item.id] = model
                    }
                    val sessionKey = item.sessionKey
                    if (!sessionKey.isNullOrBlank()) {
                        map[sessionKey] = model
                    }
                }
                Result.success(map)
            }
            is GatewayResult.Failure -> {
                Result.success(emptyMap())
            }
        }
    }

    /**
     * CHG-003-S1: propagate the server-authoritative title from a session.list summary
     * into the active handle and vault selection. Title comes ONLY from server data —
     * never fabricated locally. A summary without a title never clears a previously
     * known one. Titles are never logged (redaction rules).
     */
    private suspend fun propagateServerTitle(sessions: List<SessionSummary>) {
        val active = _activeSession.value ?: return
        val summary = sessions.firstOrNull { it.id == active.storedSessionId } ?: return
        val serverTitle = summary.title?.takeUnless { it.isBlank() } ?: return

        _activeSession.value = active.copy(title = serverTitle)

        val selection = vaultStorage.getSessionSelection()
        if (selection != null && isRecordForActiveProfile(selection.profileLocalId) &&
            selection.storedSessionId == active.storedSessionId &&
            selection.lastKnownTitle != serverTitle
        ) {
            val activeProfileId = vaultStorage.getActiveProfileId()
            vaultStorage.saveSessionSelection(
                selection.copy(
                    lastKnownTitle = serverTitle,
                    profileLocalId = selection.profileLocalId ?: activeProfileId
                )
            )
        }
    }

    /**
     * F007-S3: seeds the replay watermark for a freshly resumed session via the
     * reducer-tested `session.events.since` first-sweep (`replayEventsSince`).
     *
     * Contract (tui-gateway-client-contract.md replay/ordering):
     * - The watermark advances only after all returned events parse; the adapter
     *   commits it from the returned `latest_seq` on a clean batch.
     * - Epoch mismatch, truncation, count anomalies, or an RPC failure still
     *   dispatch the existing history fallback signals — the caller re-hydrates
     *   exactly as the contract requires. The just-completed resume hydration
     *   remains authoritative; the seed outcome never propagates to the resume
     *   Result.
     * - A deterministic seed failure is remembered per (session, epoch) so the
     *   fallback -> resume loop does not re-seed and re-trigger the fallback
     *   forever. A changed epoch (host restart) clears the memory.
     */
    private suspend fun seedReplayWatermark(data: SessionResumeResult, scope: StoredHistoryScope? = null,
        budget: StoredHistoryBudget? = null, generation: Long = 0L) {
        suspend fun current() = scope == null || openLiveCurrent(scope, budget!!, generation)
        if (!current()) return
        if (data.runtimeSessionId.isBlank()) return

        val freshEpoch = gatewayAdapter.currentReplayEpoch
        // F-007 polish: memory is keyed by durableSessionId — stable across
        // resumes (a later resume of the same durable session can carry a new
        // runtime ID; the remembered failure must survive that).
        val memoryKey = data.durableSessionId
        if (memoryKey.isNotBlank() &&
            seedFailedEpochs.containsKey(memoryKey) &&
            seedFailedEpochs[memoryKey] == freshEpoch
        ) {
            // Known-deterministic failure for this epoch: keep hydration only.
            return
        }

        // F-007 polish: one bounded retry before the epoch is marked seed-failed.
        // A single transient blip (socket hiccup, momentary 5xx) must not
        // disable seeding for the whole epoch; a genuinely deterministic RPC
        // failure fails both attempts and is then remembered. A TRUNCATED
        // batch is NOT retried: the replay ring is genuinely bounded, the
        // outcome is deterministic, and truncation already dispatched the
        // contract's history fallback (re-hydration) — retrying would only
        // re-fetch the same truncated window.
        var seedResult: GatewayResult<ReplayEventsResult>? = null
        for (attempt in 1..SEED_MAX_ATTEMPTS) {
            if (!current()) return
            seedResult = if (scope == null) gatewayAdapter.replayEventsSince(data.runtimeSessionId, 0L, data.durableSessionId)
                else gatewayAdapter.replayEventsSinceScoped(data.runtimeSessionId, 0L, data.durableSessionId,
                    scope.origin, generation) { current() }
            currentCoroutineContext().ensureActive()
            if (!current()) return
            if (seedResult is GatewayResult.Success) break
        }
        if (!current()) return
        when (val finalSeedResult = seedResult!!) {
            is GatewayResult.Success -> {
                // Clean batch: the adapter committed the watermark from
                // latest_seq and already applied any held live events. A
                // truncated batch stays a remembered failure.
                if (finalSeedResult.value.truncated) {
                    seedFailedEpochs[memoryKey] = freshEpoch
                } else {
                    seedFailedEpochs.remove(memoryKey)
                }
            }
            is GatewayResult.Failure -> {
                seedFailedEpochs[memoryKey] = freshEpoch
            }
        }
    }

    override suspend fun getDraft(storedSessionId: String): Draft? {
        val draft = vaultStorage.getDraft(storedSessionId) ?: return null
        return if (isRecordForActiveProfile(draft.profileLocalId)) draft else null
    }

    override suspend fun saveDraft(draft: Draft) {
        val activeId = vaultStorage.getActiveProfileId()
        val scopedDraft = if (draft.profileLocalId == null) {
            draft.copy(profileLocalId = activeId)
        } else {
            draft
        }
        vaultStorage.saveDraft(scopedDraft)
    }

    override suspend fun deleteDraft(storedSessionId: String) {
        if (getDraft(storedSessionId) != null) {
            vaultStorage.deleteDraft(storedSessionId)
        }
    }

    override suspend fun getAllDrafts(): List<Draft> {
        return vaultStorage.getAllDrafts().filter { isRecordForActiveProfile(it.profileLocalId) }
    }

    override suspend fun getSessionSelection(): SessionSelection? {
        val selection = vaultStorage.getSessionSelection() ?: return null
        return if (isRecordForActiveProfile(selection.profileLocalId)) selection else null
    }

    override suspend fun getSessionSummaryCache(): SessionSummaryCache? {
        // PR-1: offline serving is gated on a resolved partition (SEC-02).
        val partition = (vaultStorage.activeAccessPartition()
            as? CacheAccessPartitionResult.Available)?.partition
            ?: return null
        val cache = when (val read = vaultStorage.readSessionSummaryCache(partition)) {
            is CacheReadResult.Available -> read.value
            else -> null
        } ?: return null
        return if (isRecordForActiveProfile(cache.profileLocalId)) cache else null
    }

    /**
     * PR-1 REQ-06 (review BLOCKER-3): writes ONLY under the partition resolved
     * at THIS operation's entry — never re-resolves here (architecture §4:
     * "resolve once, before the fetch"), so a mid-flight selection change can
     * never redirect the write into another scope's subtree.
     *
     * PR-1 REQ-09 (review BLOCKER-2): write failures are contained HERE at the
     * repository boundary so a live fetch always displays — cache I/O is
     * availability, never authorization (reads remain fail-closed).
     */
    private suspend fun saveSummaryCache(
        cache: SessionSummaryCache,
        entryPartition: io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition?
    ) {
        val partition = entryPartition ?: return
        try {
            vaultStorage.saveSessionSummaryCache(cache, partition)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // OQ-04/REQ-09 fail-soft: the fetched list already displays; the
            // cache stays stale/empty and reads stay fail-closed.
        }
    }

    private suspend fun isRecordForActiveProfile(recordProfileLocalId: String?): Boolean {
        val activeId = vaultStorage.getActiveProfileId()
        if (activeId == null) {
            return recordProfileLocalId == null
        }
        val effectiveId = recordProfileLocalId ?: vaultStorage.getMigratedProfileId()
        return effectiveId == activeId
    }
}

/**
 * HOME-S3: Extension dispatch for [SessionRepository] callers to publish turn streaming
 * state to the underlying [DefaultSessionRepository], or no-op on non-default repos.
 */
fun SessionRepository.publishTurnStreaming(storedSessionId: String?, isStreaming: Boolean) {
    (this as? DefaultSessionRepository)?.publishTurnStreaming(storedSessionId, isStreaming)
}

/**
 * CHAT-015 B2: typed create failure carrying the [GatewayFailure] so UI state
 * builders can produce a REDACTED notice (failure category + credential-free
 * code/message) without ever rendering host-controlled `Rpc.data`. The
 * exception message keeps today's wording for legacy consumers.
 */
class SessionCreateFailedException(
    val failure: GatewayFailure,
    message: String = "Failed to create session: $failure"
) : Exception(message)
