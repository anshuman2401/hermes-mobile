package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.LocalCacheException
import com.hermes.bot.domain.SessionError
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.domain.SessionNotFoundException
import com.hermes.bot.feature.chat.ChatStreamingStatus
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeRepo: FakeSessionRepository
    private lateinit var viewModel: SessionsViewModel

    class FakeSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var listOutcome: Result<SessionListOutcome> = Result.success(
            SessionListOutcome(
                sessions = listOf(
                    SessionSummary("s-1", "Alpha", "Preview 1", 100.0, 1, "android")
                ),
                isOfflineCache = false
            )
        )

        var resumeOutcome: Result<SessionResumeResult> = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-1",
                durableSessionId = "stored-1",
                messageCount = 1,
                messages = listOf(SessionMessage("user", "Hello"))
            )
        )

        var createOutcome: Result<SessionHandle> = Result.success(
            SessionHandle("stored-new", "runtime-new", isDraft = true)
        )

        var listSessionsCallCount: Int = 0
        val listSessionsForceRefreshCalls = mutableListOf<Boolean>()
        var listGate: CompletableDeferred<Unit>? = null

        var restoredSession: SessionHandle? = null

        var activeModelsOutcome: Result<Map<String, String>> = Result.success(
            mapOf("s-1" to "claude-3-5-sonnet")
        )
        var activeModelsCallCount: Int = 0

        override suspend fun activeSessionModels(): Result<Map<String, String>> {
            activeModelsCallCount++
            return activeModelsOutcome
        }

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> {
            listSessionsCallCount++
            listSessionsForceRefreshCalls.add(forceRefresh)
            listGate?.await()
            return listOutcome
        }
        override suspend fun createDraftSession(title: String?): Result<SessionHandle> {
            val res = createOutcome
            if (res.isSuccess) {
                _activeSession.value = res.getOrThrow()
            }
            return res
        }

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            val res = resumeOutcome
            if (res.isSuccess) {
                val data = res.getOrThrow()
                _activeSession.value = SessionHandle(data.durableSessionId, data.runtimeSessionId)
                _activeMessages.value = data.messages
            }
            return res
        }

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? {
            val s = restoredSession
            if (s != null) _activeSession.value = s
            return s
        }

        override suspend fun clearActiveSession() {
            _activeSession.value = null
            _activeMessages.value = emptyList()
        }

        override suspend fun getActiveSession(): SessionHandle? = _activeSession.value
    }

    @Before
    fun setUp() {
        fakeRepo = FakeSessionRepository()
        viewModel = SessionsViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        viewModel.pausePolling()
    }

    @Test
    fun loadSessions_success_updatesSessionsAndNotOffline() {
        val state = viewModel.uiState.value
        assertEquals(1, state.sessions.size)
        assertEquals("Alpha", state.sessions[0].title)
        assertFalse(state.isOfflineCache)
        assertFalse(state.isLoading)
    }

    @Test
    fun loadSessions_offlineCache_exposesIsOfflineCacheTrue() {
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(
                    SessionSummary("s-cache", "Cached Title", "Offline preview", 50.0, 1, "android")
                ),
                isOfflineCache = true
            )
        )

        viewModel.loadSessions(forceRefresh = true)
        val state = viewModel.uiState.value

        assertEquals(1, state.sessions.size)
        assertTrue("Offline cache must be explicitly flagged in UI state", state.isOfflineCache)
    }

    @Test
    fun selectAndResumeSession_success_updatesSelectedSessionAndHydratesMessages() {
        var callbackCalledWith: String? = null
        viewModel.selectAndResumeSession("stored-1") { callbackCalledWith = it }

        val state = viewModel.uiState.value
        assertEquals("stored-1", callbackCalledWith)
        assertNotNull(state.selectedSession)
        assertEquals("runtime-1", state.selectedSession!!.runtimeSessionId)
        assertEquals(1, state.activeMessages.size)
        assertEquals("Hello", state.activeMessages[0].content)
    }

    @Test
    fun selectAndResumeSession_remoteNotFound_exposesRemoteNotFoundDistinctError() {
        fakeRepo.resumeOutcome = Result.failure(SessionNotFoundException("missing-id"))

        viewModel.selectAndResumeSession("missing-id")
        val state = viewModel.uiState.value

        assertNotNull(state.error)
        assertTrue("Error must be RemoteNotFound", state.error is SessionError.RemoteNotFound)
        assertEquals("missing-id", (state.error as SessionError.RemoteNotFound).storedSessionId)
    }

    @Test
    fun selectAndResumeSession_localCacheError_exposesLocalCacheFailureDistinctError() {
        fakeRepo.resumeOutcome = Result.failure(LocalCacheException("Corrupted disk record"))

        viewModel.selectAndResumeSession("corrupted-id")
        val state = viewModel.uiState.value

        assertNotNull(state.error)
        assertTrue("Error must be LocalCacheFailure", state.error is SessionError.LocalCacheFailure)
    }

    @Test
    fun createNewChat_createsDraftAndUpdatesSelectedSession() {
        var createdId: String? = null
        viewModel.createNewChat { createdId = it }

        val state = viewModel.uiState.value
        assertEquals("stored-new", createdId)
        assertNotNull(state.selectedSession)
        assertTrue(state.selectedSession!!.isDraft)
    }

    // --- CHG-003-S5: label semantics — the screen renders ONLY the repository flag ---

    @Test
    fun loadSessions_failure_afterOfflineCache_keepsListExplicitlyLabeled_notUnlabeled() {
        // Stale-past-expiry cache surfaces as a failure. The previously served offline
        // list stays visible only WITH its explicit offline label (the label mirrors the
        // last repository outcome) — the ViewModel never strips the label to present
        // cached rows as current server truth. NOTE: SessionsViewModel is outside the
        // CHG-003-S5 touch set, so the assertion locks existing label semantics.
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(SessionSummary("s-cache", "Cached", "Old", 50.0, 1, "android")),
                isOfflineCache = true
            )
        )
        viewModel.loadSessions(forceRefresh = false)
        assertTrue(viewModel.uiState.value.isOfflineCache)

        fakeRepo.listOutcome = Result.failure(LocalCacheException("No usable cache"))
        viewModel.loadSessions(forceRefresh = false)
        val state = viewModel.uiState.value

        assertNotNull(state.error)
        assertTrue(state.error is SessionError.LocalCacheFailure)
        assertTrue(
            "Cached list remaining on screen after failure must stay explicitly labeled",
            state.isOfflineCache
        )
        assertEquals(1, state.sessions.size)
    }

    @Test
    fun loadSessions_failure_afterServerTruth_doesNotGainOfflineLabel() {
        // A failed refresh after a server-truth load must NOT invent an offline label
        // (no screen-local inference): the flag keeps mirroring the repository outcome.
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(SessionSummary("s-live", "Live", "Fresh", 100.0, 1, "android")),
                isOfflineCache = false
            )
        )
        viewModel.loadSessions(forceRefresh = true)
        assertFalse(viewModel.uiState.value.isOfflineCache)

        fakeRepo.listOutcome = Result.failure(LocalCacheException("No usable cache"))
        viewModel.loadSessions(forceRefresh = false)
        val state = viewModel.uiState.value

        assertNotNull(state.error)
        assertFalse(
            "Failure must not fabricate an offline-cache label for server-truth data",
            state.isOfflineCache
        )
        assertEquals(1, state.sessions.size)
    }

    @Test
    fun loadSessions_successAfterOffline_restoresServerTruthAndClearsOfflineLabel() {
        // Online data after an offline-cache load must flip the label back to connected truth.
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(SessionSummary("s-live", "Live", "Fresh", 100.0, 1, "android")),
                isOfflineCache = false
            )
        )
        viewModel.loadSessions(forceRefresh = true)
        assertFalse(viewModel.uiState.value.isOfflineCache)

        // Then an offline fallback load flips it to the explicit offline label.
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(SessionSummary("s-cache", "Cached", "Old", 50.0, 1, "android")),
                isOfflineCache = true
            )
        )
        viewModel.loadSessions(forceRefresh = false)
        assertTrue(viewModel.uiState.value.isOfflineCache)

        // And back online, the label must follow the repository flag exactly again.
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(SessionSummary("s-live2", "Live2", "Fresh", 100.0, 1, "android")),
                isOfflineCache = false
            )
        )
        viewModel.loadSessions(forceRefresh = true)
        val state = viewModel.uiState.value
        assertFalse(state.isOfflineCache)
        assertEquals("Live2", state.sessions[0].title)
    }

    @Test
    fun uiState_initialState_hasNoOfflineLabelBeforeRepositoryServesCache() {
        // Label semantics: the offline label can only come from the repository outcome,
        // never from screen-local inference — a fresh ViewModel (repo served online data)
        // must carry isOfflineCache=false.
        assertFalse(viewModel.uiState.value.isOfflineCache)
        assertFalse(viewModel.uiState.value.sessions.isEmpty())
        assertEquals(
            "UI-state flag must mirror the repository outcome exactly",
            fakeRepo.listOutcome.getOrThrow().isOfflineCache,
            viewModel.uiState.value.isOfflineCache
        )
    }

    // --- HOME-S4: pull-to-refresh tests ---

    @Test
    fun refreshSessions_callsListSessionsWithForceRefreshTrue_exactlyOncePerPull() {
        fakeRepo.listSessionsCallCount = 0
        fakeRepo.listSessionsForceRefreshCalls.clear()

        viewModel.refreshSessions()

        assertEquals(1, fakeRepo.listSessionsCallCount)
        assertEquals(listOf(true), fakeRepo.listSessionsForceRefreshCalls)
    }

    @Test
    fun refreshSessions_concurrentPullWhileLoading_isNoOp() {
        fakeRepo.listSessionsCallCount = 0
        fakeRepo.listSessionsForceRefreshCalls.clear()
        val gate = CompletableDeferred<Unit>()
        fakeRepo.listGate = gate

        viewModel.refreshSessions()

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(1, fakeRepo.listSessionsCallCount)

        viewModel.refreshSessions()

        assertEquals(1, fakeRepo.listSessionsCallCount)

        gate.complete(Unit)

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, fakeRepo.listSessionsCallCount)
        assertEquals(listOf(true), fakeRepo.listSessionsForceRefreshCalls)
    }

    @Test
    fun refreshSessions_selectionAndDraftSurviveRefreshCycle() {
        viewModel.selectAndResumeSession("stored-1")
        val resumedSession = viewModel.uiState.value.selectedSession
        val resumedMessages = viewModel.uiState.value.activeMessages
        assertNotNull(resumedSession)
        assertFalse(resumedMessages.isEmpty())

        viewModel.refreshSessions()

        assertEquals(resumedSession, viewModel.uiState.value.selectedSession)
        assertEquals(resumedMessages, viewModel.uiState.value.activeMessages)

        viewModel.createNewChat()
        val draftSession = viewModel.uiState.value.selectedSession
        val draftMessages = viewModel.uiState.value.activeMessages
        assertNotNull(draftSession)
        assertTrue(draftSession!!.isDraft)

        viewModel.refreshSessions()

        assertEquals(draftSession, viewModel.uiState.value.selectedSession)
        assertEquals(draftMessages, viewModel.uiState.value.activeMessages)
    }

    @Test
    fun refreshSessions_failure_keepsPriorListAndSurfacesError() {
        val priorSessions = viewModel.uiState.value.sessions
        assertFalse(priorSessions.isEmpty())

        fakeRepo.listOutcome = Result.failure(LocalCacheException("Disk read failed"))
        viewModel.refreshSessions()

        val state = viewModel.uiState.value
        assertEquals(priorSessions, state.sessions)
        assertTrue("Error must be LocalCacheFailure", state.error is SessionError.LocalCacheFailure)
        assertEquals("Disk read failed", (state.error as SessionError.LocalCacheFailure).message)
        assertFalse(state.isLoading)
    }

    @Test
    fun setNetworkError_surfacesNetworkFailureAndClearsLoading() {
        viewModel.setNetworkError("Host unreachable. Check your network or Tailscale connection.")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue("Error must be NetworkFailure", state.error is SessionError.NetworkFailure)
        assertEquals(
            "Host unreachable. Check your network or Tailscale connection.",
            (state.error as SessionError.NetworkFailure).message
        )

        viewModel.clearError()
        val clearedState = viewModel.uiState.value
        assertNull(clearedState.error)
    }

    // --- TASK-008: activeModels merge and polling tests ---

    @Test
    fun loadSessions_onlineSuccess_mergesActiveModels() {
        fakeRepo.activeModelsOutcome = Result.success(mapOf("s-1" to "claude-3-5-sonnet"))
        viewModel.loadSessions(forceRefresh = true)

        val state = viewModel.uiState.value
        assertEquals("claude-3-5-sonnet", state.activeModels["s-1"])
        assertFalse("Polling should not run before screen is resumed", viewModel.isPollingActive)
    }

    @Test
    fun resumePolling_whenOnlineAndHasSessions_startsPollingJob() {
        fakeRepo.activeModelsOutcome = Result.success(mapOf("s-1" to "claude-3-5-sonnet"))
        viewModel.resumePolling()
        assertTrue(viewModel.isPollingActive)
        viewModel.pausePolling()
    }

    @Test
    fun loadSessions_gatewayFailure_degradesToEmptyMapNoThrow() {
        fakeRepo.activeModelsOutcome = Result.failure(Exception("Gateway error"))
        viewModel.loadSessions(forceRefresh = true)

        val state = viewModel.uiState.value
        assertTrue(state.activeModels.isEmpty())
        assertNull(state.error)
        assertFalse(state.isLoading)
    }

    @Test
    fun loadSessions_offlineCache_cancelsPollingAndClearsActiveModels() {
        // Start online with models and resume polling
        fakeRepo.activeModelsOutcome = Result.success(mapOf("s-1" to "claude-3-5-sonnet"))
        viewModel.resumePolling()
        assertTrue(viewModel.isPollingActive)
        assertEquals("claude-3-5-sonnet", viewModel.uiState.value.activeModels["s-1"])

        // Transition to offline cache
        fakeRepo.listOutcome = Result.success(
            SessionListOutcome(
                sessions = listOf(
                    SessionSummary("s-cache", "Cached Title", "Offline preview", 50.0, 1, "android")
                ),
                isOfflineCache = true
            )
        )
        viewModel.loadSessions(forceRefresh = true)

        val state = viewModel.uiState.value
        assertTrue(state.isOfflineCache)
        assertTrue(state.activeModels.isEmpty())
        assertFalse("Polling must be cancelled when offline cache is served", viewModel.isPollingActive)
    }

    @Test
    fun pausePolling_cancelsPollingJob() {
        fakeRepo.activeModelsOutcome = Result.success(mapOf("s-1" to "claude-3-5-sonnet"))
        viewModel.resumePolling()
        assertTrue(viewModel.isPollingActive)

        viewModel.pausePolling()
        assertFalse(viewModel.isPollingActive)
    }
}
