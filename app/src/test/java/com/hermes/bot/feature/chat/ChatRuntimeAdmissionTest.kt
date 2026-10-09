package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * BLD-006 S1 — F-01 minimal runtime-ID-only event admission guard (M-01 rows at
 * S1 scope) plus the F-03 session-switch flush-target assertion.
 *
 * At S1 head: an event mutates the timeline only when
 * `event.sessionId == _uiState.value.runtimeSessionId`. Events matching only
 * the durable ID, a stale runtime ID, another session's ID, or a null/blank ID
 * are all rejected. (S4 owns the full D-01 matrix; these rows must also pass
 * there.)
 */
class ChatRuntimeAdmissionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeAdmissionSessionRepository

    class FakeAdmissionSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeOutcome: Result<SessionResumeResult> = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList()
            )
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            resumeOutcome

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Before
    fun setUp() {
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeAdmissionSessionRepository()
    }

    private fun newViewModel(sessionId: String): ChatViewModel = ChatViewModel(
        sessionId = sessionId,
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter
    )

    private suspend fun emitDelta(sessionId: String?, text: String) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = sessionId,
                seq = 1L,
                text = text
            )
        )
    }

    // ------------------------------------------------------------------
    // Admitted: exact current runtime ID
    // ------------------------------------------------------------------

    @Test
    fun event_withExactCurrentRuntimeId_isAdmitted_noTurn_deltaDropped() = runTest {
        val viewModel = newViewModel("sess-100")
        assertEquals("runtime-100", viewModel.uiState.value.runtimeSessionId)

        emitDelta("runtime-100", "admitted delta")

        // BLD-006 S4 (D-01), owner-locked drop (plan
        // ui-remove-unattributed-activity-notices-v1): with no active admitted
        // local turn, exact-runtime text is still not local-turn proof — it is
        // silently dropped (renders nothing), never a fabricated assistant row
        // or a generic notice.
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun event_withExactCurrentRuntimeId_updatesSessionTitle() = runTest {
        val viewModel = newViewModel("sess-100")

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionTitle(
                sessionId = "runtime-100",
                seq = 1L,
                title = "Fresh runtime title"
            )
        )

        assertEquals("Fresh runtime title", viewModel.uiState.value.sessionTitle)
    }

    // ------------------------------------------------------------------
    // Rejected: everything that is not the exact current runtime ID
    // ------------------------------------------------------------------

    @Test
    fun event_matchingOnlyDurableId_isRejected() = runTest {
        val viewModel = newViewModel("sess-100")

        emitDelta("sess-100", "durable-only delta")

        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun event_withStaleRuntimeId_isRejected_afterRuntimeRefresh() = runTest {
        val viewModel = newViewModel("sess-100")
        assertEquals("runtime-100", viewModel.uiState.value.runtimeSessionId)

        // Runtime rotates (e.g. resume / reconnect hands out a fresh runtime ID)
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-fresh-200",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList()
            )
        )
        viewModel.loadSession()
        assertEquals("runtime-fresh-200", viewModel.uiState.value.runtimeSessionId)

        // Stale runtime delta from the previous runtime must not mutate the timeline
        emitDelta("runtime-100", "stale runtime delta")
        assertTrue(viewModel.uiState.value.timeline.isEmpty())

        // Current runtime reaches the reducer, but with no active admitted
        // local turn the exact-runtime delta is owner-locked-dropped (plan
        // ui-remove-unattributed-activity-notices-v1) — never a generic
        // notice, never a fabricated assistant row.
        emitDelta("runtime-fresh-200", "fresh runtime delta")
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun event_fromAnotherSession_isRejected() = runTest {
        val viewModel = newViewModel("sess-100")

        emitDelta("runtime-other-999", "other session delta")

        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun event_withNullSessionId_isRejected() = runTest {
        val viewModel = newViewModel("sess-100")

        emitDelta(null, "null session delta")

        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    @Test
    fun event_withBlankSessionId_isRejected() = runTest {
        val viewModel = newViewModel("sess-100")

        emitDelta("", "empty session delta")
        emitDelta("   ", "whitespace session delta")

        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    // ------------------------------------------------------------------
    // F-03: session-switch flush-target assertion
    // ------------------------------------------------------------------

    @Test
    fun sessionSwitch_flushedOldRuntimeText_landsOnlyInDisposingCollector_selectedTranscriptUnchanged() = runTest {
        // Disposing collector: previous session's VM (runtime-100)
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList()
            )
        )
        val oldVm = newViewModel("sess-100")
        oldVm.updateDraft("flushed text from old session")

        // User switches to a different session: a new VM is selected (runtime-200)
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-200",
                durableSessionId = "sess-200",
                messageCount = 0,
                messages = emptyList()
            )
        )
        val newVm = newViewModel("sess-200")

        // Late-arriving text flushed for the OLD runtime while the disposing
        // collector is still briefly alive
        emitDelta("runtime-100", "late flushed delta")

        // The flushed text is dropped in the DISPOSING collector (BLD-006 S4
        // D-01, owner-locked drop: no active admitted local turn in the old
        // VM, so the text is never local-turn proof and renders nothing).
        assertTrue(oldVm.uiState.value.timeline.isEmpty())
        // The draft stayed in the old VM as well
        assertEquals("flushed text from old session", oldVm.uiState.value.composerDraft)

        // The newly selected transcript receives nothing from the old runtime
        assertTrue(newVm.uiState.value.timeline.isEmpty())
        assertEquals("", newVm.uiState.value.composerDraft)

        // And the old collector never adopts the new runtime's events either
        emitDelta("runtime-200", "new runtime delta")
        assertTrue(oldVm.uiState.value.timeline.isEmpty())
        // In the new VM the exact-runtime delta is likewise dropped (no active
        // admitted local turn there either) — both transcripts stay empty.
        assertTrue(newVm.uiState.value.timeline.isEmpty())
    }
}
