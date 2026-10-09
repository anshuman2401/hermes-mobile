package com.hermes.bot.data.session

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.context.FakeContextProfileRepository
import com.hermes.bot.data.context.ScopedSessionsResult
import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.*
import com.hermes.bot.data.turn.TurnCoordinator
import com.hermes.bot.feature.sessions.SessionsViewModel
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivitySendTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun pickerAccessInvalidatesAcrossWorkspaceAndConnectionChanges() = runTest(main.testDispatcher) {
        val f = fixture("workspace-a")
        val first = checkNotNull(f.repository.capturePickerAccess(S4ActivityFixture.RUNTIME))
        assertTrue(first.isCurrent())
        f.vault.saveSelectedContextProfile("workspace-b")
        assertFalse(first.isCurrent())
        assertFalse(first.isSameContext())
        assertTrue(first.setReasoning("high") is GatewayResult.Failure)
        f.vault.saveSelectedContextProfile("workspace-a")
        val second = checkNotNull(f.repository.capturePickerAccess(S4ActivityFixture.RUNTIME))
        f.adapter.connectionGeneration++
        assertFalse(second.isCurrent())
        assertTrue(second.isSameContext())
        assertTrue(second.options() is GatewayResult.Failure)
    }

    @Test fun editAccessRequiresExplicitWorkspaceAndRejectsStaleOwnership() = runTest(main.testDispatcher) {
        val absent = fixture(null)
        assertNull(absent.repository.captureMessageEditAccess(S4ActivityFixture.ID, S4ActivityFixture.RUNTIME))
        val f = fixture("workspace-a")
        f.adapter.scopedOrigin = S4ActivityFixture.ORIGIN
        val access = checkNotNull(f.repository.captureMessageEditAccess(S4ActivityFixture.ID, S4ActivityFixture.RUNTIME))
        assertTrue(access.isCurrent())
        f.vault.saveSelectedContextProfile("workspace-b")
        assertFalse(access.isCurrent())
        assertTrue(access.snapshot() is GatewayResult.Failure)
        assertTrue(access.submit("Synthetic edit", 7) is GatewayResult.Failure)
        f.vault.saveSelectedContextProfile("workspace-a")
        val next = checkNotNull(f.repository.captureMessageEditAccess(S4ActivityFixture.ID, S4ActivityFixture.RUNTIME))
        f.adapter.connectionGeneration++
        assertFalse(next.isCurrent())
        assertTrue(next.interrupt() is GatewayResult.Failure)
    }

    @Test fun staleRefreshCannotUndo() = runTest(main.testDispatcher) {
        for (scope in listOf(null, "workspace-a")) {
            val f = fixture(scope)
            val vm = f.sessionsViewModel()
            try {
                vm.uiState.first { !it.isLoading }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val barrier: suspend () -> Unit = { entered.complete(Unit); release.await() }
                if (scope == null) f.adapter.onListSessions = barrier else f.context.onScopedFetch = barrier
                vm.refreshSessions()
                entered.await()
                f.submit()
                vm.uiState.first { it.sessions.single { row -> row.id == S4ActivityFixture.ID }.lastActive == f.now }
                runCurrent()
                val immediate = vm.uiState.value.sessions
                release.complete(Unit)
                vm.uiState.first { !it.isLoading }
                assertActivity(immediate)
                assertActivity(vm.uiState.value.sessions)
                assertActivity(f.cache().sessions)
                assertEquals("Synthetic preview", f.cache().sessions.first().preview)
            } finally { close(vm) }
        }
    }

    @Test fun delayedActiveModelsCannotUndoAndSendPatchesWithoutRefetch() = runTest(main.testDispatcher) {
        val f = fixture("workspace-a")
        val vm = f.sessionsViewModel()
        try {
            vm.uiState.first { !it.isLoading }
            vm.onSearchQueryChange("Synthetic")
            val selected = vm.uiState.value.selectedSession
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.adapter.onListActiveSessions = { entered.complete(Unit); release.await() }
            vm.refreshSessions()
            entered.await()
            f.submit()
            vm.uiState.first { it.sessions.single { row -> row.id == S4ActivityFixture.ID }.lastActive == f.now }
            runCurrent()
            val immediate = vm.uiState.value.sessions
            release.complete(Unit)
            vm.uiState.first { !it.isLoading }
            assertActivity(immediate)
            assertActivity(vm.uiState.value.sessions)
            assertEquals("Synthetic", vm.uiState.value.searchQuery)
            assertEquals(selected, vm.uiState.value.selectedSession)
            assertFalse(vm.uiState.value.isOfflineCache)
            assertActivity(f.cache().sessions)
        } finally { close(vm) }
    }

    @Test fun currentWorkspacePatchDoesNotFetchOrReplaceRows() = runTest(main.testDispatcher) {
        val f = fixture(null)
        val vm = f.sessionsViewModel()
        try {
            vm.uiState.first { !it.isLoading }
            val before = vm.uiState.value.sessions
            val calls = f.adapter.listSessionsCallCount
            f.submit()
            vm.uiState.first { it.sessions.single { row -> row.id == S4ActivityFixture.ID }.lastActive == f.now }
            runCurrent()
            assertActivity(vm.uiState.value.sessions)
            assertEquals(calls, f.adapter.listSessionsCallCount)
            assertEquals(before.associateBy { it.id }.mapValues { it.value.copy(lastActive = null) },
                vm.uiState.value.sessions.associateBy { it.id }.mapValues { it.value.copy(lastActive = null) })
        } finally { close(vm) }
    }

    @Test fun openDoesNotTouch() = runTest(main.testDispatcher) {
        val f = fixture("workspace-a")
        val before = f.cache()
        f.repository.resumeSession(S4ActivityFixture.ID).getOrThrow()
        f.repository.saveDraft(Draft(storedSessionId = S4ActivityFixture.ID, composerText = "Synthetic draft"))
        f.repository.createDraftSession().getOrThrow()
        assertEquals(before, f.cache())
        assertEquals(50.0, f.repository.listSessions(true).getOrThrow().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
        assertEquals(0, f.adapter.submitPromptCallCount)
    }

    @Test fun scopeSwitchRejectsOldToken() = runTest(main.testDispatcher) {
        // The send enters with the original owner BEFORE prepareTurn suspends.
        // Each replacement reuses the durable ID; it must not inherit activity.
        for (change in listOf("workspace", "local-profile", "origin", "credential-epoch", "gateway", "active-binding")) {
            val f = fixture("workspace-a")
            val originalPartition = f.partition()
            val original = f.cache()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.onPrepare = { entered.complete(Unit); release.await() }
            val send = async { f.submit() }
            entered.await()
            when (change) {
                "workspace" -> f.vault.saveSelectedContextProfile("workspace-b")
                "local-profile" -> f.selectLocalProfile("connection-b")
                "origin" -> {
                    val origin = "http://alternate.invalid:9119"
                    f.vault.saveConnectionProfile(ConnectionProfile("connection-a", origin, "Synthetic alternate"))
                    f.saveCredential(origin)
                }
                "credential-epoch" -> f.saveCredential()
                "gateway" -> f.adapter.connectionGeneration++
                "active-binding" -> {
                    f.adapter.resumeSessionResult = GatewayResult.Success(SessionResumeResult("runtime-other", S4ActivityFixture.ID, 0, emptyList()))
                    f.repository.resumeSession(S4ActivityFixture.ID).getOrThrow()
                }
            }
            f.repository.listSessions(true).getOrThrow()
            val replacement = f.cache()
            release.complete(Unit)
            send.await()
            assertEquals("No write activity crosses $change", replacement, f.cache())
            assertEquals(50.0, f.repository.listSessions(true).getOrThrow().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
            if (change == "workspace" || change == "local-profile") {
                val old = f.vault.readSessionSummaryCache(originalPartition) as CacheReadResult.Available<SessionSummaryCache>
                assertEquals(original, old.value)
            }
            assertEquals(1, f.adapter.submitPromptCallCount)
        }
    }

    @Test fun ownerChangeDuringMetadataAwaitCannotPublishIntoReplacementWorkspace() = runTest(main.testDispatcher) {
        val f = fixture("workspace-a")
        val vm = f.sessionsViewModel()
        try {
            vm.uiState.first { !it.isLoading }
            val originalPartition = f.partition()
            val original = f.cache()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val rpcEntered = CompletableDeferred<Unit>()
            val rpcRelease = CompletableDeferred<Unit>()
            f.onCacheRead = { entered.complete(Unit); release.await() }
            f.adapter.onSubmitPrompt = { rpcEntered.complete(Unit); rpcRelease.await() }
            val send = async { f.submit() }
            // Await either real metadata IO or real RPC entry, including the unsupported parent.
            val reachedMetadata = select<Boolean> {
                entered.onAwait { true }
                rpcEntered.onAwait { false }
            }
            if (reachedMetadata) {
                f.onCacheRead = null
                f.vault.saveSelectedContextProfile("workspace-b")
                vm.resetWorkspace()
                vm.loadSessions(true)
                vm.uiState.first { !it.isLoading }
                val replacement = f.cache()
                release.complete(Unit)
                rpcRelease.complete(Unit)
                send.await()
                runCurrent()
                assertEquals(replacement, f.cache())
                assertEquals(50.0, vm.uiState.value.sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
                val old = f.vault.readSessionSummaryCache(originalPartition) as CacheReadResult.Available<SessionSummaryCache>
                assertEquals(original, old.value)
            } else {
                rpcRelease.complete(Unit)
                send.await()
                assertActivity(vm.uiState.value.sessions) // behavioral RED, not unsupported-reflection RED
            }
        } finally { f.onCacheRead = null; close(vm) }
    }

    @Test fun cacheRestartKeepsFloor() = runTest(main.testDispatcher) {
        for (scope in listOf(null, "workspace-a")) {
            val f = fixture(scope)
            val before = f.cache()
            f.submit()
            val bumped = f.cache()
            assertActivity(bumped.sessions)
            assertEquals(before.syncTimeEpochSeconds, bumped.syncTimeEpochSeconds)
            assertTrue(bumped.isFresh(before.syncTimeEpochSeconds + SessionSummaryCache.MAX_RETENTION_SECONDS))
            assertFalse(bumped.isFresh(before.syncTimeEpochSeconds + SessionSummaryCache.MAX_RETENTION_SECONDS + 1))
            assertEquals(before.schemaVersion, bumped.schemaVersion)
            assertEquals(before.cachedProfileName, bumped.cachedProfileName)
            f.offline()
            val restarted = f.newRepository()
            val offline = restarted.listSessions().getOrThrow()
            assertTrue(offline.isOfflineCache)
            assertActivity(offline.sessions)
            f.online()
            assertActivity(restarted.listSessions(true).getOrThrow().sessions)
            assertActivity(f.cache().sessions)
        }
    }

    @Test fun newerIncomingAndKnownCacheAreMonotonicWithoutInventingMissingRows() = runTest(main.testDispatcher) {
        val f = fixture(null)
        val cached = f.cache()
        f.vault.saveSessionSummaryCache(cached.copy(sessions = cached.sessions.map {
            if (it.id == S4ActivityFixture.ID) it.copy(lastActive = 150.0) else it
        }), f.partition())
        val restarted = f.newRepository()
        restarted.resumeSession(S4ActivityFixture.ID).getOrThrow()
        val rows = restarted.listSessions(true).getOrThrow().sessions
        assertEquals(150.0, rows.single { it.id == S4ActivityFixture.ID }.lastActive)
        f.online(f.rows(200.0))
        assertEquals(200.0, restarted.listSessions(true).getOrThrow().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
        f.online(f.rows(50.0))
        assertEquals(200.0, restarted.listSessions(true).getOrThrow().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
        f.online(f.rows().filter { it.id != S4ActivityFixture.ID })
        restarted.listSessions(true).getOrThrow()
        val absent = f.cache()
        f.newCoordinator(restarted).submitPrompt(S4ActivityFixture.ID, S4ActivityFixture.RUNTIME, "Synthetic prompt")
        assertEquals(absent, f.cache())
        assertEquals(listOf("synthetic-other"), restarted.listSessions(true).getOrThrow().sessions.map { it.id })
    }

    private suspend fun fixture(scope: String?) = S4ActivityFixture(temp, main.testDispatcher).apply { initialize(scope) }
    private fun assertActivity(rows: List<SessionSummary>) {
        assertEquals(listOf(S4ActivityFixture.ID, "synthetic-other"), rows.map { it.id })
        assertEquals(100.0, rows.first().lastActive)
    }
    private suspend fun close(vm: SessionsViewModel) {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        ViewModelStore().apply { put("sessions", vm); clear() }
        job.join()
    }
}

/** Shared real encrypted-vault/repository fixture; hooks only delay or fail IO, never compute activity. */
internal class S4ActivityFixture(temp: TemporaryFolder, dispatcher: CoroutineDispatcher) {
    val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = dispatcher)
    val adapter = FakeGatewayProtocolAdapter()
    val context = FakeContextProfileRepository()
    var now = 100.0
    var onPrepare: (suspend () -> Unit)? = null
    var onMark: (suspend () -> Unit)? = null
    var onCacheRead: (suspend () -> Unit)? = null
    var onCacheSave: (suspend () -> Unit)? = null
    val storage = object : VaultStorage by vault {
        override suspend fun savePendingTurn(record: PendingTurnRecord) {
            onPrepare?.invoke()
            vault.savePendingTurn(record)
        }
        override suspend fun updatePendingTurnState(pendingTurnId: String, newState: PendingTurnState) {
            if (newState == PendingTurnState.WRITE_ATTEMPTED) onMark?.invoke()
            vault.updatePendingTurnState(pendingTurnId, newState)
        }
        override suspend fun readSessionSummaryCache(partition: CacheAccessPartition): CacheReadResult<SessionSummaryCache> {
            onCacheRead?.invoke()
            return vault.readSessionSummaryCache(partition)
        }
        override suspend fun saveSessionSummaryCache(cache: SessionSummaryCache, partition: CacheAccessPartition) {
            onCacheSave?.invoke()
            vault.saveSessionSummaryCache(cache, partition)
        }
    }
    lateinit var repository: DefaultSessionRepository
    lateinit var coordinator: TurnCoordinator
    suspend fun initialize(scope: String?) {
        adapter.connectionGeneration = 1L
        selectLocalProfile("connection-a")
        if (scope != null) vault.saveSelectedContextProfile(scope)
        online()
        adapter.resumeSessionResult = GatewayResult.Success(SessionResumeResult(RUNTIME, ID, 0, emptyList()))
        repository = newRepository()
        repository.listSessions(true).getOrThrow()
        repository.resumeSession(ID).getOrThrow()
        // A visibly older capture clock makes accidental TTL renewal detectable even in a fast JVM run.
        val cached = cache()
        vault.saveSessionSummaryCache(cached.copy(syncTimeEpochSeconds = cached.syncTimeEpochSeconds - 3600), partition())
        coordinator = newCoordinator(repository)
    }
    suspend fun selectLocalProfile(id: String) {
        vault.saveConnectionProfile(ConnectionProfile(id, ORIGIN, "Synthetic server"))
        vault.setActiveProfileId(id)
        saveCredential()
    }
    suspend fun saveCredential(origin: String = ORIGIN) {
        vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password",
            cookies = mapOf("session_id" to "synthetic-cookie"), canonicalOrigin = origin),
            profileLocalId = vault.getActiveProfileId())
    }
    suspend fun partition() = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
    suspend fun cache() = (vault.readSessionSummaryCache(partition()) as CacheReadResult.Available<SessionSummaryCache>).value
    fun rows(activity: Double = 50.0) = listOf(
        SessionSummary("synthetic-other", "Synthetic other", "Synthetic preview", 20.0, 1, "android", 80.0),
        SessionSummary(ID, "Synthetic title", "Synthetic preview", 10.0, 1, "android", activity))
    fun online(rows: List<SessionSummary> = rows()) {
        adapter.listSessionsResult = GatewayResult.Success(SessionListResult(rows))
        context.scopedResult = ScopedSessionsResult.Success(rows)
    }
    fun offline() {
        adapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Synthetic offline"))
        context.scopedResult = ScopedSessionsResult.Error(IllegalStateException("Synthetic offline"))
    }
    suspend fun submit() = coordinator.submitPrompt(ID, RUNTIME, "Synthetic prompt")
    suspend fun sessionsViewModel(): SessionsViewModel {
        val vm = SessionsViewModel(repository)
        vm.uiState.first { !it.isLoading && it.selectedSession?.storedSessionId == ID }
        // List entry restores only a durable selection. Reattach through the real chat-open boundary.
        repository.resumeSession(ID).getOrThrow()
        return vm
    }

    fun newRepository() = DefaultSessionRepository(adapter, storage, context, activityClock = { now })
    fun newCoordinator(repo: SessionRepository = repository) = TurnCoordinator(adapter, PendingTurnLedger(storage), repo)
    companion object {
        const val ID = "synthetic-session"
        const val RUNTIME = "synthetic-runtime"
        const val ORIGIN = "http://activity.invalid:9119"
    }
}
