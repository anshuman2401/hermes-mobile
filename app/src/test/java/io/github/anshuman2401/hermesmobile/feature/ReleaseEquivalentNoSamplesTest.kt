package io.github.anshuman2401.hermesmobile.feature

import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Release-equivalent tests enforcing that Sessions and Chat never render hard-coded sample data
 * per Lesson 3 and RPR-002 acceptance criteria.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseEquivalentNoSamplesTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun releaseEquivalent_sessionsViewModel_neverInjectsSampleSessions() = runTest(testDispatcher) {
        val fakeRepo = object : SessionRepository {
            override val activeSession: StateFlow<SessionHandle?> = MutableStateFlow(null)
            override val activeMessages: StateFlow<List<SessionMessage>> = MutableStateFlow(emptyList())
            override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

            override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> {
                return Result.success(SessionListOutcome(sessions = emptyList(), isOfflineCache = false))
            }
            override suspend fun createDraftSession(title: String?): Result<SessionHandle> = Result.failure(NotImplementedError())
            override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> = Result.failure(NotImplementedError())
            override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> = Result.failure(NotImplementedError())
            override suspend fun restoreSelectedSession(): SessionHandle? = null
            override suspend fun clearActiveSession() {}
            override suspend fun getActiveSession(): SessionHandle? = null
        }

        val viewModel = SessionsViewModel(
            sessionRepository = fakeRepo,
            savedStateHandle = SavedStateHandle()
        )

        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Sessions loading should be complete", state.isLoading)
        assertTrue("Release sessions state must be truthfully empty, never sample sessions", state.sessions.isEmpty())

        // Verify none of the previous sample session IDs exist
        val sessionIds = state.sessions.map { it.id }.toSet()
        assertFalse(sessionIds.contains("sess-01"))
        assertFalse(sessionIds.contains("sess-02"))
        assertFalse(sessionIds.contains("sess-03"))
    }

    @Test
    fun releaseEquivalent_chatViewModel_neverInjectsSampleTimeline() = runTest(testDispatcher) {
        val fakeRepo = object : SessionRepository {
            override val activeSession: StateFlow<SessionHandle?> = MutableStateFlow(null)
            override val activeMessages: StateFlow<List<SessionMessage>> = MutableStateFlow(emptyList())
            override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

            override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> = Result.success(SessionListOutcome(emptyList(), false))
            override suspend fun createDraftSession(title: String?): Result<SessionHandle> = Result.failure(NotImplementedError())
            override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> = Result.failure(NotImplementedError())
            override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> = Result.failure(NotImplementedError())
            override suspend fun restoreSelectedSession(): SessionHandle? = null
            override suspend fun clearActiveSession() {}
            override suspend fun getActiveSession(): SessionHandle? = null
        }

        val viewModel = ChatViewModel(
            sessionId = "real-session-100",
            sessionRepository = fakeRepo,
            savedStateHandle = SavedStateHandle()
        )

        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Initial loading should complete", state.isInitialLoading)
        assertTrue("Release chat timeline must be truthfully empty, never sample messages", state.timeline.isEmpty())

        // Verify none of the previous sample item IDs exist
        val timelineIds = state.timeline.map { it.id }.toSet()
        assertFalse(timelineIds.contains("sample-1"))
        assertFalse(timelineIds.contains("sample-2"))
        assertFalse(timelineIds.contains("sample-tool-1"))
    }
}
