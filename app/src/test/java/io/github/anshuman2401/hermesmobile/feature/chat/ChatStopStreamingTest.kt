package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.PendingTurnState
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.feature.chat.GenericGatewayActivity
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
 * CHAT-003: Behavior-level JVM tests for stop-streaming button (Plan v1, Section 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStopStreamingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: TestSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class TestSessionRepository : SessionRepository {
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

    private class RecordingAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : GatewayProtocolAdapter by delegate {
        val submittedFrames = mutableListOf<Pair<String, String>>()
        override suspend fun submitPrompt(
            sessionId: String,
            text: String
        ): GatewayResult<PromptSubmitResult> {
            submittedFrames.add(sessionId to text)
            return delegate.submitPrompt(sessionId, text)
        }
    }

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_stop_streaming_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = TestSessionRepository()
    }

    private fun createViewModel(adapter: RecordingAdapter): ChatViewModel =
        ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )

    // 1. stopButton_visibleOnlyWhileStreaming
    // Given a confirmed admitted turn streaming (isStreaming=true), assert uiState.isStreaming
    // is true and the Stop affordance derives solely from that flag; after terminal,
    // isStreaming=false and the stop affordance is gone.
    @Test
    fun stopButton_visibleOnlyWhileStreaming() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        assertFalse("Initially not streaming", viewModel.uiState.value.isStreaming)

        viewModel.updateDraft("start turn")
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertTrue("While streaming, isStreaming is true (stop affordance visible)", viewModel.uiState.value.isStreaming)

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertFalse("After stop terminal, isStreaming is false (stop affordance gone)", viewModel.uiState.value.isStreaming)
    }

    // 2. stopStreaming_sealsPartialContent_interrupted_noFabricatedCompletion
    // Stream two deltas, tap stop; assert the assistant row text equals the two admitted
    // deltas concatenated, state == INTERRUPTED, no COMPLETE row or completion text was injected,
    // stream seals TraceOverallState.INTERRUPTED, scheduler disposed (late delta is owner-locked to render nothing: no generic row).
    @Test
    fun stopStreaming_sealsPartialContent_interrupted_noFabricatedCompletion() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("tell me something")
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStreaming)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 1L,
                text = "Delta 1. "
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 2L,
                text = "Delta 2."
            )
        )
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)

        val assistantMessages = state.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, assistantMessages.size)
        val assistantMessage = assistantMessages.single()
        assertEquals("Delta 1. Delta 2.", assistantMessage.text)
        assertEquals(AssistantMessageState.INTERRUPTED, assistantMessage.state)

        // no COMPLETE row or completion text was injected
        assertTrue(state.timeline.none { it is AssistantMessage && it.state == AssistantMessageState.COMPLETE })

        // trace seals TraceOverallState.INTERRUPTED
        val trace = state.trace
        assertNotNull(trace)
        assertEquals(TraceOverallState.INTERRUPTED, trace!!.overallState)
        assertNotNull(trace.endedAtMillis)

        // scheduler disposed (deltaPublicationScheduler null-path: a late emitted GatewayEvent.MessageDelta afterward is unattributable — owner-locked drop, never a new streaming row)
        val timelineSizeBeforeLateDelta = state.timeline.size
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 3L,
                text = "Late delta"
            )
        )
        advanceUntilIdle()

        val afterLateDeltaState = viewModel.uiState.value
        // Assistant row was not touched by the late delta
        val assistantAfter = afterLateDeltaState.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals("Delta 1. Delta 2.", assistantAfter.text)
        assertEquals(AssistantMessageState.INTERRUPTED, assistantAfter.state)
        // Owner-locked drop (plan ui-remove-unattributed-activity-notices-v1):
        // the late delta appends nothing — timeline size unchanged, no
        // message.delta generic row.
        assertTrue(
            afterLateDeltaState.timeline
                .filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.delta" }
        )
        assertEquals(timelineSizeBeforeLateDelta, afterLateDeltaState.timeline.size)
    }

    // 3. stopStreaming_neverResends_pendingTurnResolvedOnce
    // After stop: fakeAdapter.submitPrompt invocation count unchanged (exactly the one original submission;
    // no second prompt.submit frame); ledger record for the turn is RESOLVED, pendingUnknownTurnId stays null,
    // and a subsequent submitPrompt() call creates a NEW pendingTurnId (fresh attempt).
    @Test
    fun stopStreaming_neverResends_pendingTurnResolvedOnce() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("turn one")
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals(1, adapter.submittedFrames.size)
        val firstTurnId = viewModel.uiState.value.timeline.filterIsInstance<UserMessage>().single().id

        // Call stopStreaming
        viewModel.stopStreaming()
        advanceUntilIdle()

        // submitPrompt invocation count unchanged (exactly the one original submission)
        assertEquals(1, adapter.submittedFrames.size)

        // ledger record for the turn is RESOLVED
        val ledgerTurn = ledger.getPendingTurn(firstTurnId)
        assertNotNull(ledgerTurn)
        assertEquals(PendingTurnState.RESOLVED, ledgerTurn!!.state)

        // pendingUnknownTurnId stays null
        assertNull(viewModel.uiState.value.pendingUnknownTurnId)

        // subsequent submitPrompt() call creates a NEW pendingTurnId (fresh attempt)
        viewModel.updateDraft("turn two")
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals(2, adapter.submittedFrames.size)
        val userMessages = viewModel.uiState.value.timeline.filterIsInstance<UserMessage>()
        assertEquals(2, userMessages.size)
        val secondTurnId = userMessages.last().id
        assertTrue(firstTurnId != secondTurnId)
    }

    // 4. stopStreaming_hostInterruptedTerminalAfterwards_isBoundedGenericNotice
    // After local stop, emit a host message.complete status:"interrupted"; assert no assistant row
    // is re-sealed (the turn was already sealed) and the late unattributed terminal is silently
    // dropped (plan ui-drop-unattributed-notices-v2-plan.md, PR #178 precedent): renders nothing,
    // timeline unchanged.
    @Test
    fun stopStreaming_hostInterruptedTerminalAfterwards_isBoundedGenericNotice() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("initial prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 1L,
                text = "Partial response."
            )
        )
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        val assistantBeforeLateTerminal = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals("Partial response.", assistantBeforeLateTerminal.text)
        assertEquals(AssistantMessageState.INTERRUPTED, assistantBeforeLateTerminal.state)
        val timelineSizeBefore = viewModel.uiState.value.timeline.size

        // Host emits message.complete status:"interrupted" afterwards
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = "Host final replacement",
                status = "interrupted"
            )
        )
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value
        // No assistant row is re-sealed (the turn was already sealed)
        val assistantAfter = stateAfter.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals("Partial response.", assistantAfter.text)
        assertEquals(AssistantMessageState.INTERRUPTED, assistantAfter.state)

        // Owner-locked drop (plan ui-drop-unattributed-notices-v2-plan.md,
        // PR #178 precedent): the late unattributed terminal renders nothing
        // — no generic row, timeline unchanged.
        assertTrue(
            stateAfter.timeline.filterIsInstance<GenericGatewayActivity>().none {
                it.eventType == "message.terminal"
            }
        )
        assertEquals(timelineSizeBefore, stateAfter.timeline.size)
    }

    // T1: Stop delivers session.interrupt to the host.
    // Given a streaming turn with a live runtime session, stopStreaming() must
    // fire interruptSession addressed with the SAME live runtime id prompt
    // submission used, and the local optimistic finalize must still land
    // (streaming off, row sealed INTERRUPTED).
    @Test
    fun stopStreaming_firesHostInterrupt_forLiveRuntimeSession() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("stream me an answer")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals("runtime-100", viewModel.uiState.value.runtimeSessionId)

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals(listOf("runtime-100"), fakeAdapter.interruptedSessions)
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(
            AssistantMessageState.INTERRUPTED,
            state.timeline.filterIsInstance<AssistantMessage>().single().state
        )
    }

    // T1: best-effort = a failed interrupt result never blocks the local stop.
    @Test
    fun stopStreaming_interruptFailure_stillFinalizesLocally() = runTest {
        fakeAdapter.interruptResult = GatewayResult.Failure(
            GatewayFailure.Transport("connection closed before interrupt ack")
        )
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("do not block me")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.stopStreaming()
        advanceUntilIdle()

        // The interrupt WAS attempted (and failed)...
        assertEquals(listOf("runtime-100"), fakeAdapter.interruptedSessions)
        // ...and the local optimistic finalize still ran untouched.
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(
            AssistantMessageState.INTERRUPTED,
            state.timeline.filterIsInstance<AssistantMessage>().single().state
        )
    }

    // T1: best-effort = a thrown interrupt never crashes or skips the local stop.
    @Test
    fun stopStreaming_interruptThrows_stillFinalizesLocally() = runTest {
        fakeAdapter.interruptException = IllegalStateException("transport blew up")
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("still stop locally")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.stopStreaming()
        advanceUntilIdle()

        // The attempt was made before the throw...
        assertEquals(listOf("runtime-100"), fakeAdapter.interruptedSessions)
        // ...and the local optimistic finalize still ran untouched.
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(
            AssistantMessageState.INTERRUPTED,
            state.timeline.filterIsInstance<AssistantMessage>().single().state
        )
    }

    // T1 guard: no interrupt attempt when the stop guard fails (not streaming,
    // or a second stop after the turn was already sealed) — and no rows are
    // fabricated by the rejected calls.
    @Test
    fun stopStreaming_guardFails_noInterruptAttempted() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = createViewModel(adapter)
        advanceUntilIdle()

        // Not streaming yet: guard fails, no interrupt, no assistant row.
        viewModel.stopStreaming()
        advanceUntilIdle()
        assertTrue(fakeAdapter.interruptedSessions.isEmpty())
        assertTrue(viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().isEmpty())

        // One live turn -> exactly one interrupt; a second stop is a no-op.
        viewModel.updateDraft("one turn")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)

        viewModel.stopStreaming()
        advanceUntilIdle()
        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals(listOf("runtime-100"), fakeAdapter.interruptedSessions)
        val state = viewModel.uiState.value
        assertFalse(state.isStreaming)
        assertEquals(1, state.timeline.filterIsInstance<AssistantMessage>().size)
    }
}
