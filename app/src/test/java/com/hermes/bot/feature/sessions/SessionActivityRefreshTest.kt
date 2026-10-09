package com.hermes.bot.feature.sessions

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.auth.AuthHttpTransport
import com.hermes.bot.data.auth.HttpResponse
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.CacheReadResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.SessionSelection
import com.hermes.bot.data.local.SessionSummaryCache
import com.hermes.bot.data.session.DefaultSessionRepository
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.test.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S5 witnesses use real repository/vault/VM behavior; only the protocol adapter is controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivityRefreshTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun repositoryMapsExistingEventsAndOnlyConnectedEdges() = runTest(main.testDispatcher) {
        val f = fixture()
        f.gateway.connectionStatus.value = ConnectionStatus.OFFLINE
        val signals = mutableListOf<Unit>()
        val collector = backgroundScope.launch(main.testDispatcher) {
            f.repository.listInvalidations.collect { signals.add(it) }
        }
        try {
            runCurrent()
            assertEquals(0, signals.size)
            for (event in relevantEvents()) {
                f.gateway.eventFlow.emit(event)
                runCurrent()
            }
            assertEquals("Existing producer events must reach the repository collector", 3, f.deliveredEvents)
            assertEquals("SessionsChanged and terminal events must invalidate the list", 3, signals.size)
            f.gateway.eventFlow.emit(GatewayEvent.SessionTitle(ID, 4L, "Synthetic event title"))
            f.gateway.eventFlow.emit(GatewayEvent.MessageDelta(ID, 5L, "Synthetic delta"))
            runCurrent()
            assertEquals(5, f.deliveredEvents)
            assertEquals("Nonterminal payloads are not list refresh signals", 3, signals.size)
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTING
            runCurrent()
            assertEquals(3, signals.size)
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertEquals(4, signals.size)
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertEquals("A repeated CONNECTED value is not another edge", 4, signals.size)
            f.gateway.connectionStatus.value = ConnectionStatus.OFFLINE
            runCurrent()
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertEquals(5, signals.size)
            assertEquals("The repository signal producer does not fetch by itself", 0, f.gateway.listSessionsCallCount)
        } finally { collector.cancel() }
    }

    @Test fun staleAdapterGenerationIsIgnoredBeforeInvalidationPublication() = runTest(main.testDispatcher) {
        val f = fixture()
        f.gateway.connectionStatus.value = ConnectionStatus.OFFLINE
        val held = Gate()
        var signals = 0
        val collector = backgroundScope.launch(main.testDispatcher) {
            f.repository.listInvalidations.collect {
                signals++
                if (signals == 1) held.awaitRelease()
            }
        }
        try {
            f.gateway.eventFlow.emit(GatewayEvent.SessionsChanged())
            runCurrent()
            assertTrue("A current-generation producer signal must arrive before the barrier", held.entered.isCompleted)
            held.entered.await()
            f.gateway.eventFlow.emit(GatewayEvent.SessionsChanged())
            runCurrent()
            assertEquals("The old-generation event must actually enter the producer flow", 2, f.deliveredEvents)
            f.gateway.connectionGeneration++
            held.release.complete(Unit)
            runCurrent()
            assertEquals("Queued old-generation work must not invalidate the replacement connection", 1, signals)
            f.gateway.eventFlow.emit(GatewayEvent.SessionsChanged())
            runCurrent()
            assertEquals("The replacement generation remains eligible", 2, signals)
        } finally { held.release.complete(Unit); collector.cancel() }
    }

    @Test fun existingEventsAndReconnectRefreshCurrentOwnerWithoutUsingPayloadAsSummary() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        try {
            vm.onSearchQueryChange("Synthetic")
            val selected = vm.uiState.value.selectedSession
            var calls = f.gateway.listSessionsCallCount
            for ((index, event) in relevantEvents().withIndex()) {
                val rows = f.rows(activity = 100.0 + index)
                f.online(rows)
                val listEntered = CompletableDeferred<Unit>()
                f.gateway.onListSessions = { listEntered.complete(Unit) }
                f.emitAndAwaitDelivery(event)
                listEntered.await()
                runCurrent()
                assertEquals("Each steady-state existing signal refreshes through listSessions", ++calls, f.gateway.listSessionsCallCount)
                awaitListIdle(vm)
                assertEquals(rows, vm.uiState.value.sessions)
                assertEquals("Synthetic server title", vm.uiState.value.sessions.single().title)
                assertEquals("Synthetic server preview", vm.uiState.value.sessions.single().preview)
            }
            assertEquals(3, f.deliveredEvents)
            f.gateway.connectionStatus.value = ConnectionStatus.OFFLINE
            yield() // Let the existing StateFlow collector observe this edge before CONNECTED replaces it.
            runCurrent()
            f.online(f.rows(activity = 200.0))
            val reconnectListEntered = CompletableDeferred<Unit>()
            f.gateway.onListSessions = { reconnectListEntered.complete(Unit) }
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTED
            reconnectListEntered.await()
            runCurrent()
            assertEquals(++calls, f.gateway.listSessionsCallCount)
            awaitListIdle(vm)
            assertEquals(200.0, vm.uiState.value.sessions.single().lastActive)
            assertEquals("Synthetic", vm.uiState.value.searchQuery)
            assertEquals(selected, vm.uiState.value.selectedSession)
            assertEquals(0, f.gateway.submitPromptCallCount)
        } finally { close(vm) }
    }

    @Test fun coalescesAndPreservesRows() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val failed = Gate()
        val trailing = Gate()
        try {
            vm.onSearchQueryChange("Synthetic")
            val selected = vm.uiState.value.selectedSession
            val usefulRows = vm.uiState.value.sessions
            val baseline = f.gateway.listSessionsCallCount
            // A real expired cache makes the held transport failure a failure, not offline success.
            f.expireSummaryCache()
            f.gateway.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Synthetic unavailable"))
            f.gateway.onListSessions = {
                if (f.gateway.listSessionsCallCount == baseline + 1) failed.awaitRelease()
                else trailing.awaitRelease()
            }
            vm.refreshSessions() // Existing baseline entrypoint guarantees the first barrier is reachable.
            failed.entered.await()
            for (event in relevantEvents() + List(5) { GatewayEvent.SessionsChanged() }) {
                f.emitAndAwaitDelivery(event)
            }
            f.gateway.connectionStatus.value = ConnectionStatus.OFFLINE
            yield()
            runCurrent()
            f.gateway.connectionStatus.value = ConnectionStatus.CONNECTED
            runCurrent()
            assertEquals("No burst signal starts another concurrent list read", baseline + 1, f.gateway.listSessionsCallCount)
            assertEquals(usefulRows, vm.uiState.value.sessions)
            f.online(f.rows(activity = 300.0))
            failed.release.complete(Unit)
            trailing.entered.await()
            runCurrent()
            assertEquals("Failure must drain exactly one pending trailing refresh", baseline + 2, f.gateway.listSessionsCallCount)
            assertTrue(trailing.entered.isCompleted)
            trailing.entered.await()
            assertEquals("Useful same-owner rows survive failure and the held trailing fetch", usefulRows, vm.uiState.value.sessions)
            trailing.release.complete(Unit)
            awaitListIdle(vm)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.error)
            assertEquals(300.0, vm.uiState.value.sessions.single().lastActive)
            assertEquals(baseline + 2, f.gateway.listSessionsCallCount)
            assertEquals("Every queued event came through the real producer flow", 8, f.deliveredEvents)
            assertEquals("Synthetic", vm.uiState.value.searchQuery)
            assertEquals(selected, vm.uiState.value.selectedSession)
        } finally { failed.release.complete(Unit); trailing.release.complete(Unit); close(vm) }
    }

    @Test fun failedRefreshWithoutTrailingRetainsUsefulRowsAndHonestError() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        try {
            val usefulRows = vm.uiState.value.sessions
            f.expireSummaryCache()
            f.gateway.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Synthetic unavailable"))
            vm.refreshSessions()
            awaitListIdle(vm)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertNotNull(vm.uiState.value.error)
            assertEquals(usefulRows, vm.uiState.value.sessions)
            assertFalse(vm.uiState.value.isOfflineCache)
        } finally { close(vm) }
    }

    @Test fun invalidationsWaitForModelIoBeforeOneTrailingRefresh() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val models = Gate()
        val trailing = Gate()
        try {
            val usefulRows = vm.uiState.value.sessions
            val baseline = f.gateway.listSessionsCallCount
            f.online(f.rows(activity = 400.0))
            f.gateway.onListActiveSessions = { models.awaitRelease() }
            vm.refreshSessions()
            models.entered.await()
            for (event in relevantEvents()) f.emitAndAwaitDelivery(event)
            runCurrent()
            assertEquals(baseline + 1, f.gateway.listSessionsCallCount)
            assertEquals("List completion alone cannot finish a load waiting on model IO", usefulRows, vm.uiState.value.sessions)
            assertTrue(vm.uiState.value.isLoading)
            f.gateway.onListActiveSessions = null
            f.online(f.rows(activity = 500.0))
            f.gateway.onListSessions = { trailing.awaitRelease() }
            models.release.complete(Unit)
            trailing.entered.await()
            runCurrent()
            assertEquals("The pending refresh starts only after model IO finishes", baseline + 2, f.gateway.listSessionsCallCount)
            assertTrue(trailing.entered.isCompleted)
            assertEquals(400.0, vm.uiState.value.sessions.single().lastActive)
            trailing.release.complete(Unit)
            awaitListIdle(vm)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(500.0, vm.uiState.value.sessions.single().lastActive)
            assertEquals(baseline + 2, f.gateway.listSessionsCallCount)
            assertEquals(3, f.deliveredEvents)
        } finally { models.release.complete(Unit); trailing.release.complete(Unit); close(vm) }
    }

    @Test fun workspaceChangeClearsPendingAndObsoleteModelPublication() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val models = Gate()
        try {
            val baseline = f.gateway.listSessionsCallCount
            f.online(f.rows(activity = 600.0))
            f.gateway.onListActiveSessions = { withContext(NonCancellable) { models.awaitRelease() } }
            vm.refreshSessions()
            models.entered.await()
            for (event in relevantEvents()) f.emitAndAwaitDelivery(event)
            runCurrent()
            assertEquals("Reset witness must first admit real invalidation producers", 3, f.deliveredEvents)
            // This is the real workspace preference boundary plus the shell's existing VM reset.
            f.vault.saveSelectedContextProfile("synthetic-workspace-b")
            vm.resetWorkspace()
            models.release.complete(Unit)
            runCurrent()
            assertEquals("No obsolete trailing fetch is dispatched after workspace reset", baseline + 1, f.gateway.listSessionsCallCount)
            assertTrue(vm.uiState.value.sessions.isEmpty())
            assertTrue(vm.uiState.value.activeModels.isEmpty())
            assertNull(vm.uiState.value.selectedSession)
            // Establish a different eligible owner for a positive post-reset list read; no REST host.
            f.vault.clearSelectedContextProfile()
            f.selectLocalProfile("synthetic-connection-b")
            f.gateway.onListActiveSessions = null
            f.online(f.rows(id = "synthetic-session-b", activity = 700.0))
            vm.loadSessions(forceRefresh = true)
            awaitListIdle(vm)
            runCurrent()
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(listOf("synthetic-session-b"), vm.uiState.value.sessions.map { it.id })
            assertEquals(baseline + 2, f.gateway.listSessionsCallCount)
            f.online(f.rows(id = "synthetic-session-b", activity = 750.0))
            val replacementListEntered = CompletableDeferred<Unit>()
            f.gateway.onListSessions = { replacementListEntered.complete(Unit) }
            f.emitAndAwaitDelivery(GatewayEvent.SessionsChanged())
            replacementListEntered.await()
            runCurrent()
            assertEquals("New signals refresh the replacement owner", baseline + 3, f.gateway.listSessionsCallCount)
            awaitListIdle(vm)
            assertEquals(listOf("synthetic-session-b"), vm.uiState.value.sessions.map { it.id })
            assertEquals(750.0, vm.uiState.value.sessions.single().lastActive)
            assertEquals("Obsolete pending work must not try the abandoned workspace route", 0, f.unexpectedHttpCalls)
        } finally { models.release.complete(Unit); close(vm) }
    }

    @Test fun disposalClearsPendingPublicationAndGatewayCollectors() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val held = Gate()
        var disposed = false
        try {
            val usefulRows = vm.uiState.value.sessions
            val baseline = f.gateway.listSessionsCallCount
            f.online(f.rows(activity = 800.0))
            f.gateway.onListSessions = { withContext(NonCancellable) { held.awaitRelease() } }
            vm.refreshSessions()
            held.entered.await()
            f.emitAndAwaitDelivery(GatewayEvent.SessionsChanged())
            runCurrent()
            assertEquals("Disposal witness must first admit an actual producer signal", 1, f.deliveredEvents)
            val scopeJob = vm.viewModelScope.coroutineContext[Job]!!
            ViewModelStore().apply { put("sessions", vm); clear() }
            disposed = true
            held.release.complete(Unit)
            scopeJob.join()
            runCurrent()
            assertEquals(usefulRows, vm.uiState.value.sessions)
            assertEquals(baseline + 1, f.gateway.listSessionsCallCount)
            assertEquals("VM disposal removes the real adapter flow subscription", 0, f.gateway.eventFlow.subscriptionCount.value)
            f.gateway.eventFlow.emit(GatewayEvent.SessionsChanged())
            runCurrent()
            assertEquals(baseline + 1, f.gateway.listSessionsCallCount)
        } finally { held.release.complete(Unit); if (!disposed) close(vm) }
    }

    @Test fun listEntryAndForegroundRefresh() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val owner = TestLifecycleOwner()
        var dispose: () -> Unit = {}
        try {
            vm.onSearchQueryChange("Synthetic")
            val selected = vm.uiState.value.selectedSession
            val baseline = f.gateway.listSessionsCallCount
            dispose = observeSessionsLifecycle(vm, owner.lifecycle)
            awaitListIdle(vm)
            runCurrent()
            assertEquals("Entering the screen calls the refresh entrypoint", baseline + 1, f.gateway.listSessionsCallCount)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            awaitListIdle(vm)
            runCurrent()
            assertEquals("ON_RESUME refreshes even without a connection edge", baseline + 2, f.gateway.listSessionsCallCount)
            assertTrue(vm.isPollingActive)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            awaitListIdle(vm)
            runCurrent()
            assertEquals(baseline + 3, f.gateway.listSessionsCallCount)
            assertEquals("Synthetic", vm.uiState.value.searchQuery)
            assertEquals(selected, vm.uiState.value.selectedSession)
            dispose()
            assertFalse(vm.isPollingActive)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            awaitListIdle(vm)
            runCurrent()
            assertEquals("Disposed screen observer cannot request another refresh", baseline + 3, f.gateway.listSessionsCallCount)
        } finally { dispose(); close(vm) }
    }

    @Test fun entryResumeAndGatewayBurstShareOneTrailingRefresh() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        val held = Gate()
        val owner = TestLifecycleOwner()
        var dispose: () -> Unit = {}
        try {
            val baseline = f.gateway.listSessionsCallCount
            f.gateway.onListSessions = { held.awaitRelease() }
            vm.refreshSessions()
            held.entered.await()
            dispose = observeSessionsLifecycle(vm, owner.lifecycle)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            for (event in relevantEvents()) f.emitAndAwaitDelivery(event)
            runCurrent()
            assertEquals(baseline + 1, f.gateway.listSessionsCallCount)
            f.gateway.onListSessions = null
            held.release.complete(Unit)
            awaitListIdle(vm)
            runCurrent()
            assertEquals("Entry, resume and gateway events coalesce behind the same load", baseline + 2, f.gateway.listSessionsCallCount)
            assertFalse(vm.uiState.value.isLoading)
            dispose()
            assertFalse(vm.isPollingActive)
        } finally { dispose(); held.release.complete(Unit); close(vm) }
    }

    @Test fun screenWiresTheBehaviorallyTestedLifecycleRegistrationAndCleanup() {
        val source = File("src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt").let {
            if (it.exists()) it else File("app/src/main/java/com/hermes/bot/feature/sessions/SessionsScreen.kt")
        }.readText()
        val effect = source.substringAfter("DisposableEffect(").substringBefore("// Single source of truth")
        assertTrue("The real screen must wire the tested lifecycle registration", effect.contains("observeSessionsLifecycle("))
        assertTrue("The observer must follow the current LifecycleOwner", source.contains("LocalLifecycleOwner.current"))
        assertTrue("Effect disposal must call the returned cleanup", effect.contains("onDispose") && effect.contains("dispose()"))
        // Supplementary wiring only; lifecycle behavior is exercised above with the real VM.
    }

    private suspend fun fixture() = Fixture(temp, main.testDispatcher).apply { initialize() }

    private fun relevantEvents(): List<GatewayEvent> = listOf(
        GatewayEvent.SessionsChanged(sessionId = "synthetic-event-session", seq = 1L),
        GatewayEvent.MessageComplete("synthetic-event-runtime", 2L, "Synthetic event content", "complete"),
        GatewayEvent.Error("synthetic-event-runtime", 3L, "Synthetic terminal error")
    )

    /** Real summary IO uses Dispatchers.IO internally; await presentation completion, then its continuation. */
    private suspend fun awaitListIdle(vm: SessionsViewModel) {
        do {
            vm.uiState.first { !it.isLoading }
            yield()
        } while (vm.uiState.value.isLoading)
    }

    private suspend fun close(vm: SessionsViewModel) {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        ViewModelStore().apply { put("sessions", vm); clear() }
        job.join()
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun awaitRelease() { entered.complete(Unit); release.await() }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class Fixture(temp: TemporaryFolder, dispatcher: CoroutineDispatcher) {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = dispatcher)
        val gateway = FakeGatewayProtocolAdapter()
        private val deliveredEventCount = MutableStateFlow(0)
        val deliveredEvents: Int get() = deliveredEventCount.value
        private val adapter = object : GatewayProtocolAdapter by gateway {
            override val events = gateway.eventFlow.onEach { deliveredEventCount.value++ }
        }
        var unexpectedHttpCalls = 0
        // A broken obsolete-work dispatch must fail locally rather than reach a network transport.
        private val rejectHttp = object : AuthHttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                unexpectedHttpCalls++
                error("Unexpected synthetic scoped request")
            }
        }
        val repository = DefaultSessionRepository(adapter, vault, httpTransport = rejectHttp)

        suspend fun initialize() {
            gateway.connectionGeneration = 1L
            selectLocalProfile("synthetic-connection-a")
            vault.saveSessionSelection(SessionSelection(ID, "Synthetic server title", profileLocalId = vault.getActiveProfileId()))
            online(rows())
        }

        suspend fun selectLocalProfile(id: String) {
            val origin = "http://refresh.invalid:9119"
            vault.saveConnectionProfile(ConnectionProfile(id, origin, "Synthetic server"))
            vault.setActiveProfileId(id)
            vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password",
                cookies = mapOf("session_id" to "synthetic-cookie"), canonicalOrigin = origin), profileLocalId = id)
            assertTrue("Fixture must satisfy real cache authority guards", vault.activeAccessPartition() is CacheAccessPartitionResult.Available)
        }

        suspend fun viewModel(): SessionsViewModel {
            val vm = SessionsViewModel(repository)
            try {
                vm.uiState.first { !it.isLoading && it.selectedSession?.storedSessionId == ID }
                // The list's real IO completion does not certify asynchronous merge collector registration.
                gateway.eventFlow.subscriptionCount.first { it > 0 }
                gateway.connectionStatus.subscriptionCount.first { it > 0 }
                yield() // Let the initial load release job ownership after publishing its rows.
                check(vm.uiState.value.error == null && vm.uiState.value.sessions == rows())
                return vm
            } catch (error: Throwable) {
                ViewModelStore().apply { put("sessions", vm); clear() }
                throw error
            }
        }

        suspend fun emitAndAwaitDelivery(event: GatewayEvent) {
            val expected = deliveredEvents + 1
            gateway.eventFlow.emit(event)
            // Observe consumption by the adapter flow used by the actual repository/VM, not a second collector.
            deliveredEventCount.first { it >= expected }
            yield() // Allow the repository's queued invalidation to reach its VM collector before reset/disposal.
        }

        fun rows(id: String = ID, activity: Double = 50.0) = listOf(
            SessionSummary(id, "Synthetic server title", "Synthetic server preview", 10.0, 1, "android", activity)
        )

        fun online(rows: List<SessionSummary>) {
            gateway.listSessionsResult = GatewayResult.Success(SessionListResult(rows))
        }

        suspend fun expireSummaryCache() {
            val partition = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
            val cache = (vault.readSessionSummaryCache(partition) as CacheReadResult.Available<SessionSummaryCache>).value
            vault.saveSessionSummaryCache(cache.copy(syncTimeEpochSeconds =
                System.currentTimeMillis() / 1000 - SessionSummaryCache.MAX_RETENTION_SECONDS - 3600), partition)
        }
    }

    private companion object { const val ID = "synthetic-session" }
}
