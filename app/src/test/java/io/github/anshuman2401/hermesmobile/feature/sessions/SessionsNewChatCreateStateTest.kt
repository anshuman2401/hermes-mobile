package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.session.SessionCreateFailedException
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionError
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
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

/**
 * CHAT-015 B2 ViewModel state: honest create progress + visible, dismissible,
 * redacted create failure at the tapped entry point; degraded-default notice
 * rides a successful create with `savedDefaultModelApplied == false`.
 *
 * Invariants pinned here:
 * - `createNewChat` sets `isCreatingSession`, NEVER the list-refresh `isLoading`.
 * - A create failure lands in the dedicated `newChatFailure` state and NEVER in
 *   the suppressed `error` channel (owner decision 2026-09-24 keeps
 *   `SessionError.NetworkFailure` filtered at the UI boundary).
 * - No navigation and no success callback on failure; navigation on success is
 *   unchanged (callback fires with the new session id).
 *
 * RED first: tests 7, 8, 9 must fail before the production change is made.
 * Fake pattern follows SessionsViewModelTest.FakeSessionRepository.
 */
class SessionsNewChatCreateStateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeRepo: FakeCreateSessionRepository
    private lateinit var viewModel: SessionsViewModel

    class FakeCreateSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
            MutableStateFlow(ChatStreamingStatus())

        var listOutcome: Result<SessionListOutcome> = Result.success(
            SessionListOutcome(sessions = emptyList(), isOfflineCache = false)
        )

        var createOutcome: Result<SessionHandle> = Result.success(
            SessionHandle("stored-new", "runtime-new", isDraft = true, savedDefaultModelApplied = true)
        )
        var createGate: CompletableDeferred<Unit>? = null

        /** CHAT-015 repair: records every create entry (double-tap guard proof). */
        var createCallCount: Int = 0

        /** CHAT-015 repair: when set, [createDraftSession] THROWS instead of returning. */
        var createThrows: Throwable? = null

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            listOutcome

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> {
            createCallCount += 1
            createThrows?.let { throw it }
            createGate?.await()
            val res = createOutcome
            if (res.isSuccess) {
                _activeSession.value = res.getOrThrow()
            }
            return res
        }

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-1",
                    durableSessionId = storedSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            resumeSession(durableSessionId)

        override suspend fun restoreSelectedSession(): SessionHandle? = null

        override suspend fun clearActiveSession() {
            _activeSession.value = null
            _activeMessages.value = emptyList()
        }

        override suspend fun getActiveSession(): SessionHandle? = _activeSession.value
    }

    @Before
    fun setUp() {
        fakeRepo = FakeCreateSessionRepository()
        viewModel = SessionsViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        viewModel.pausePolling()
    }

    // 7. create progress is its own state, never the list-refresh loading flag.
    @Test
    fun createNewChat_inProgress_setsCreateProgressNotListRefreshLoading() {
        fakeRepo.createGate = CompletableDeferred()

        var navigated = false
        viewModel.createNewChat { navigated = true }

        val state = viewModel.uiState.value
        assertTrue("create-in-progress must set isCreatingSession", state.isCreatingSession)
        assertFalse("create must never drive the pull-to-refresh isLoading", state.isLoading)
        assertFalse("no navigation while the create is in flight", navigated)
    }

    // 8. failure -> dedicated dismissible state, redacted, never the suppressed
    //    error channel, never a success callback.
    @Test
    fun createNewChat_failure_exposesDismissibleCreateFailure_notSuppressedNetworkError() {
        // Host-controlled Rpc.data (model/provider/suggestions strings) must
        // NEVER be rendered verbatim in the notice.
        fakeRepo.createOutcome = Result.failure(
            SessionCreateFailedException(
                GatewayFailure.Rpc(
                    code = -32602,
                    message = "invalid params",
                    data = "model=secret-host-model provider=secret-provider"
                )
            )
        )

        var navigated = false
        viewModel.createNewChat { navigated = true }

        val state = viewModel.uiState.value
        val failure = state.newChatFailure
        assertNotNull("create failure must surface in the dedicated state", failure)
        val message = failure!!.message
        assertTrue("notice carries the fixed app copy", message.contains(NewChatCreateFailure.FIXED_COPY))
        assertTrue("notice carries the credential-free code", message.contains("-32602"))
        assertFalse("host-controlled Rpc.data must never render verbatim", message.contains("secret-host-model"))
        assertFalse("host-controlled Rpc.data must never render verbatim", message.contains("secret-provider"))
        assertFalse(
            "create failure must NOT enter the suppressed error channel",
            state.error is SessionError.NetworkFailure
        )
        assertNull("create failure must not set any error channel value", state.error)
        assertFalse("no navigation on failure", navigated)
        assertFalse("create progress clears after failure", state.isCreatingSession)

        viewModel.dismissNewChatFailure()
        assertNull("dismiss must clear the notice", viewModel.uiState.value.newChatFailure)
    }

    // 9. degraded-default success carries the fixed notice; navigation unchanged.
    @Test
    fun createNewChat_degradedSuccess_carriesSavedDefaultNotAppliedNotice() {
        fakeRepo.createOutcome = Result.success(
            SessionHandle("stored-degraded", "runtime-degraded", isDraft = true, savedDefaultModelApplied = false)
        )

        var navigatedTo: String? = null
        viewModel.createNewChat { navigatedTo = it }

        val state = viewModel.uiState.value
        val notice = state.newChatDegradedNotice
        assertNotNull("degraded create must carry the notice", notice)
        assertEquals("notice is bound to the created session", "stored-degraded", notice!!.sessionId)
        // CHAT-015 repair (NIT): assert the FULL frozen contract copy
        // (controller decision 2026-09-26), not a substring.
        assertEquals(
            "notice carries the full fixed contract copy",
            DEGRADED_DEFAULT_NOTICE_COPY,
            notice.message
        )
        assertEquals(
            "success callback still fires — navigation on success is unchanged",
            "stored-degraded",
            navigatedTo
        )
        assertNull("no failure notice on a degraded success", state.newChatFailure)
    }

    // CHAT-015 repair (MAJOR): a THROW in the create pipeline (e.g. the
    // repository's durable vault write rethrowing an IO error) must never crash
    // the app or strand `isCreatingSession` — it maps through the standard
    // redaction seam into the dismissible create-failure notice.
    @Test
    fun createNewChat_repositoryThrows_mapsToRedactedFailure_andResetsCreatingState() {
        fakeRepo.createThrows = IllegalStateException("vault write failed")

        var navigated = false
        viewModel.createNewChat { navigated = true }

        val state = viewModel.uiState.value
        val failure = state.newChatFailure
        assertNotNull("a thrown create must surface the create-failure notice", failure)
        assertTrue(
            "notice must come from the redaction seam (fixed app copy, no raw exception text)",
            failure!!.message.startsWith(NewChatCreateFailure.FIXED_COPY)
        )
        assertFalse("creating state must always reset after a thrown create", state.isCreatingSession)
        assertNull("a thrown create must not enter the suppressed error channel", state.error)
        assertFalse("no navigation on a thrown create", navigated)

        viewModel.dismissNewChatFailure()
        assertNull("dismiss must clear the notice", viewModel.uiState.value.newChatFailure)
    }

    // CHAT-015 repair (MINOR): a second tap while a create is in flight must be
    // ignored — two concurrent creates mean two navigation callbacks and an
    // orphaned first session.
    @Test
    fun createNewChat_reentryWhileCreateInFlight_isIgnored_singleCreate() {
        fakeRepo.createGate = CompletableDeferred()

        var navigations = 0
        viewModel.createNewChat { navigations += 1 }
        viewModel.createNewChat { navigations += 1 }

        assertEquals("double tap must not launch a concurrent create", 1, fakeRepo.createCallCount)

        fakeRepo.createGate?.complete(Unit)
        assertEquals("exactly one navigation for one create", 1, navigations)
        assertFalse(
            "creating state resets when the single create completes",
            viewModel.uiState.value.isCreatingSession
        )
    }

    // CHAT-015 repair (reviewer fix-risk guard): cancellation is NOT a failure —
    // it must never be converted into a create-failure notice.
    @Test
    fun createNewChat_cancelled_neverConvertedToFailureNotice() {
        fakeRepo.createGate = CompletableDeferred()
        viewModel.createNewChat()
        assertTrue("create in flight", viewModel.uiState.value.isCreatingSession)

        viewModel.viewModelScope.cancel()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

        assertNull(
            "cancellation must not become a create-failure notice",
            viewModel.uiState.value.newChatFailure
        )
    }
}
