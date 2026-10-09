package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReasoningKind
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TRD1 slice 1 (TASK-005): JVM reducer-wiring tests for the turn-scoped
 * [TraceState] — trace set on confirmed admission (submit + retry paths),
 * terminal seal per certified status (fail-closed UNKNOWN), action counting,
 * history-replacement clearing, and the reducer regression baseline
 * (tool correlation, generic rows, flush order — per the existing
 * ChatViewModelTest/ChatTranscriptTruthTest assertions). Synthetic fixtures
 * only; no real payload text (SEC-001).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatExecutionTraceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeChatSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    /** Injected fake clock: each read advances by a fixed step (deterministic). */
    private var fakeNowMillis: Long = 0L
    private val fakeClock: () -> Long = {
        fakeNowMillis += 1_000L
        fakeNowMillis
    }

    class FakeChatSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeOutcome: Result<SessionResumeResult> = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "synthetic question"),
                    SessionMessage(role = "assistant", content = "synthetic answer")
                )
            )
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))

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
        val dir = tempFolder.newFolder("chat_test_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeChatSessionRepository()
        fakeNowMillis = 0L
    }

    /** An admitted streaming turn on the submit path (requires a runTest scope). */
    private suspend fun kotlinx.coroutines.test.TestScope.startStreamingTurn(viewModel: ChatViewModel) {
        viewModel.updateDraft("synthetic streaming prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()
    }

    private fun admittedTurnId(viewModel: ChatViewModel): String {
        val user = viewModel.uiState.value.timeline.last { it is UserMessage && !it.id.startsWith("hist-") }
        return user.id
    }

    // ------------------------------------------------------------------
    // (a) Trace set on confirmed admission (submit path)
    // ------------------------------------------------------------------

    @Test
    fun submitPath_confirmedAdmission_setsTraceWithPendingTurnIdAndFakeStart() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)

        val trace = viewModel.uiState.value.trace
        assertNotNull(trace)
        val turnId = admittedTurnId(viewModel)
        assertEquals(turnId, trace!!.turnId)
        assertTrue(trace.turnId.isNotBlank())
        // First clock() read is consumed by the admission-site start stamp.
        assertEquals(1_000L, trace.startedAtMillis)
        assertNull(trace.endedAtMillis)
        assertEquals(TraceOverallState.RUNNING, trace.overallState)
    }

    @Test
    fun submitPath_ambiguousUnknown_doesNotSetTrace() = runTest {
        // (a) admission-only: AmbiguousUnknown is NOT a confirmed admission.
        fakeAdapter.submitPromptResult = GatewayResult.Failure(
            GatewayFailure.SubmissionUnknown("synthetic transport failure")
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        viewModel.updateDraft("synthetic unknown prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.trace)
        assertEquals(SubmissionStatus.UNKNOWN, viewModel.uiState.value.submissionStatus)
    }

    // ------------------------------------------------------------------
    // (b) Terminal seal maps each certified status 1:1 (fail-closed)
    // ------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.sealWithStatus(status: String?): TraceState {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "synthetic final answer",
                status = status
            )
        )
        advanceUntilIdle()
        return viewModel.uiState.value.trace!!
    }

    @Test
    fun terminalSeal_completeStatus_mapsToComplete_andFreezesElapsed() = runTest {
        val trace = sealWithStatus("complete")
        assertEquals(TraceOverallState.COMPLETE, trace.overallState)
        assertNotNull(trace.endedAtMillis)
        assertTrue(trace.endedAtMillis!! > trace.startedAtMillis)
    }

    @Test
    fun terminalSeal_interruptedStatus_mapsToInterrupted() = runTest {
        val trace = sealWithStatus("interrupted")
        assertEquals(TraceOverallState.INTERRUPTED, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }

    @Test
    fun terminalSeal_errorStatus_mapsToFailed() = runTest {
        val trace = sealWithStatus("error")
        assertEquals(TraceOverallState.FAILED, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }

    @Test
    fun terminalSeal_blankStatus_mapsFailClosedToUnknown() = runTest {
        val trace = sealWithStatus("   ")
        assertEquals(TraceOverallState.UNKNOWN, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }

    @Test
    fun terminalSeal_absentStatus_mapsFailClosedToUnknown() = runTest {
        val trace = sealWithStatus(null)
        assertEquals(TraceOverallState.UNKNOWN, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }

    @Test
    fun terminalSeal_errorEvent_mapsToFailed() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)
        fakeAdapter.eventFlow.emit(
            GatewayEvent.Error(
                sessionId = "runtime-100",
                seq = 1L,
                message = "synthetic failure"
            )
        )
        advanceUntilIdle()

        val trace = viewModel.uiState.value.trace!!
        assertEquals(TraceOverallState.FAILED, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }

    // ------------------------------------------------------------------
    // (c) Mid-turn activity rows are counted by ExecutionTracePolicy
    // ------------------------------------------------------------------

    @Test
    fun midTurnRows_areCountedByActionCount() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)
        val turnId = admittedTurnId(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100",
                seq = 1L,
                toolId = "synthetic-tool-1",
                name = "synthetic_tool",
                context = "synthetic context",
                args = emptyMap()
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolProgress(
                sessionId = "runtime-100",
                seq = 2L,
                type = "synthetic.progress",
                payload = emptyMap()
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ReasoningDelta(
                sessionId = "runtime-100",
                seq = 3L,
                kind = ReasoningKind.REASONING,
                text = "synthetic reasoning"
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(3, ExecutionTracePolicy.actionCount(timeline, turnId))

        // Display list contains exactly one header before the first activity row.
        val display = ExecutionTracePolicy.buildDisplayEntries(
            timeline,
            viewModel.uiState.value.trace
        )
        assertEquals(timeline.size + 1, display.size)
    }

    // ------------------------------------------------------------------
    // (d) History replacement (loadSession) clears the trace
    // ------------------------------------------------------------------

    @Test
    fun historyReplacement_clearsTrace() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)
        assertNotNull(viewModel.uiState.value.trace)

        // Server truth replaces the local timeline (session.resume snapshot).
        viewModel.loadSession()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.trace)
    }

    // ------------------------------------------------------------------
    // (e) Unattributed terminal activity never creates a trace
    // ------------------------------------------------------------------

    @Test
    fun unattributedTerminalActivity_neverCreatesTrace() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        // No submit: no admitted turn exists.
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "unsolicited synthetic terminal",
                status = "complete"
            )
        )
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.trace)
        // Plan ui-drop-unattributed-notices-v2-plan.md (owner-locked, PR #178
        // precedent): the unattributed terminal is a silent drop — it renders
        // nothing and adds no timeline entry. The init hydration baseline
        // (2 synthetic rows) is unchanged: no GenericGatewayActivity row was
        // appended for the unsolicited terminal.
        assertTrue(
            "unattributed terminal must render nothing (timeline unchanged)",
            viewModel.uiState.value.timeline.none {
                it is GenericGatewayActivity
            }
        )
        assertEquals(2, viewModel.uiState.value.timeline.size)
    }

    // ------------------------------------------------------------------
    // (f) Reducer regression baseline: retry path + existing behaviors
    // ------------------------------------------------------------------

    @Test
    fun retryPath_confirmedAdmission_setsFreshTrace() = runTest {
        fakeAdapter.submitPromptResult = GatewayResult.Failure(
            GatewayFailure.SubmissionUnknown("synthetic unknown")
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        viewModel.updateDraft("synthetic retry prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.trace)

        // Reset to success; explicit user retry is a fresh attempt.
        fakeAdapter.submitPromptResult =
            GatewayResult.Success(PromptSubmitResult("streaming"))
        viewModel.retryAmbiguousPrompt()
        advanceUntilIdle()

        val trace = viewModel.uiState.value.trace
        assertNotNull(trace)
        val user = viewModel.uiState.value.timeline.last { it is UserMessage }
        assertEquals(user.id, trace!!.turnId)
        assertEquals(TraceOverallState.RUNNING, trace.overallState)
        assertNull(trace.endedAtMillis)
        assertNull(viewModel.uiState.value.pendingUnknownTurnId)
    }

    @Test
    fun reducerBaseline_toolCorrelation_genericRows_unchangedByTrace() = runTest {
        // Regression baseline (per ChatViewModelTest/ToolIdentityCorrelationTest):
        // the trace ride-along must not alter tool correlation or generic rows.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolGenerating(
                sessionId = "runtime-100",
                seq = 1L,
                name = "synthetic_tool"
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100",
                seq = 2L,
                toolId = "synthetic-tool-1",
                name = "synthetic_tool",
                context = "synthetic context",
                args = emptyMap()
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = 3L,
                toolId = "synthetic-tool-1",
                name = "synthetic_tool",
                durationSeconds = 1.5,
                result = "synthetic result"
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // tool.generating row (unkeyed preparing) + keyed tool row (upgraded in
        // place to COMPLETED) — exactly one keyed card, identity preserved.
        val keyed = timeline.filterIsInstance<ToolActivity>()
            .filter { it.toolId == "synthetic-tool-1" }
        assertEquals(1, keyed.size)
        assertEquals(ToolStatus.COMPLETED, keyed.single().status)
        assertEquals(1.5, keyed.single().durationSeconds!!, 0.01)

        val preparing = timeline.filterIsInstance<ToolActivity>()
            .filter { it.status == ToolStatus.PREPARING }
        assertEquals(1, preparing.size)

        // Trace is RUNNING and unrelated to the tool lifecycle.
        val trace = viewModel.uiState.value.trace
        assertEquals(TraceOverallState.RUNNING, trace!!.overallState)
        assertNull(trace.endedAtMillis)
    }

    @Test
    fun reducerBaseline_otherSessionEventRejected_unchangedByTrace() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)

        // A second (non-runtime) session's event is rejected by the admission
        // guard entirely — the timeline and trace stay untouched.
        fakeAdapter.eventFlow.emit(
            GatewayEvent.Unknown(
                type = "synthetic.other_session_notice",
                sessionId = "runtime-999",
                seq = 99L,
                payload = emptyMap()
            )
        )
        advanceUntilIdle()

        assertEquals(
            0,
            viewModel.uiState.value.timeline.filterIsInstance<GenericGatewayActivity>().size
        )
        assertEquals(TraceOverallState.RUNNING, viewModel.uiState.value.trace!!.overallState)
    }

    @Test
    fun reducerBaseline_schedulerFlushBeforeTerminal_unchangedByTrace() = runTest {
        // Reuse the ChatViewModelTest flush-order assertion: text admitted and
        // sealed by message.complete keeps every scalar exactly once.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            clock = fakeClock
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 1L,
                text = "synthetic streamed "
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = "synthetic streamed final",
                status = "complete"
            )
        )
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.timeline
            .filterIsInstance<AssistantMessage>()
            .last { it.id.startsWith("asst-") }
        assertEquals("synthetic streamed final", assistant.text)
        assertEquals(AssistantMessageState.COMPLETE, assistant.state)
        assertFalse(viewModel.uiState.value.isStreaming)

        // Trace sealed alongside the same terminal update.
        val trace = viewModel.uiState.value.trace!!
        assertEquals(TraceOverallState.COMPLETE, trace.overallState)
        assertNotNull(trace.endedAtMillis)
    }
}
