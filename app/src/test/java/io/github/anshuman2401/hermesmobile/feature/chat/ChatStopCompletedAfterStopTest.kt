package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T2: transcript truthfulness of the stop-vs-turn-completion race.
 *
 * Audit conclusion under test (ChatViewModel decision KDoc at the
 * MessageComplete handler):
 * - `stopStreaming()` finalizes synchronously with no suspension point, so a
 *   host terminal lands fully BEFORE the stop (guard no-ops; row already
 *   COMPLETE — truthful) or fully AFTER it.
 * - The gap: a late `message.complete` with certified status "complete" for
 *   the just-stopped turn must not leave the sealed row claiming INTERRUPTED
 *   while the host's authoritative outcome is COMPLETE — it upgrades the row
 *   (authoritative terminal text, reasoning, trace) one-shot.
 * - Stop-after-complete stays a guard no-op; normal completion and the
 *   bounded generic notice for every other terminal stay unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStopCompletedAfterStopTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: StopRaceSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class StopRaceSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeOutcome: Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> = Result.success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
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

        override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> =
            resumeOutcome

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> =
            Result.success(
                io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
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
        val dir = tempFolder.newFolder("chat_stop_completed_after_stop_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = StopRaceSessionRepository()
    }

    private fun freshVm(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = ledger,
        vaultStorage = vault
    )

    private suspend fun submitTurn(viewModel: ChatViewModel, prompt: String = "hello"): String {
        val known = allTurnIds()
        viewModel.updateDraft(prompt)
        viewModel.submitPrompt()
        return "asst-" + newTurnIdSince(known)
    }

    private suspend fun newTurnIdSince(knownIds: Set<String>): String =
        ledger.getAllPendingTurns().map { it.pendingTurnId }.first { it !in knownIds }

    private suspend fun allTurnIds(): Set<String> =
        ledger.getAllPendingTurns().map { it.pendingTurnId }.toSet()

    private suspend fun emitDelta(text: String) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(sessionId = "runtime-100", seq = 1L, text = text)
        )
    }

    private suspend fun emitComplete(
        text: String?,
        status: String?,
        reasoning: String? = null
    ) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = text,
                status = status,
                usage = null,
                reasoning = reasoning
            )
        )
    }

    // Late completion after Stop: the sealed INTERRUPTED row upgrades to the
    // host's authoritative COMPLETE outcome with the authoritative terminal
    // text (partial stop text replaced), final reasoning attached (no live
    // reasoning existed), trace flips to COMPLETE, no "unattributed" notice,
    // and the reconciliation is one-shot (a duplicate late complete is no
    // longer reconcilable and is silently dropped — plan
    // ui-drop-unattributed-notices-v2-plan.md, PR #178 precedent — without
    // touching the row).
    @Test
    fun lateCompleteAfterStop_upgradesSealedRow_toCompleteWithAuthoritativeText() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("Partial answer.")
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        // Precondition: locally sealed stop with partial text.
        val stopped = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.INTERRUPTED, stopped.state)
        assertEquals("Partial answer.", stopped.text)
        assertNotNull(viewModel.uiState.value.trace)
        assertEquals(TraceOverallState.INTERRUPTED, viewModel.uiState.value.trace!!.overallState)

        // The host had already completed the turn; the terminal arrives late.
        emitComplete(
            text = "Full authoritative answer.",
            status = "complete",
            reasoning = "final reasoning line"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val row = state.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.COMPLETE, row.state)
        assertEquals("Full authoritative answer.", row.text)
        assertFalse(state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(TraceOverallState.COMPLETE, state.trace!!.overallState)
        // The reconciled terminal is attributable — no unattributed notice.
        assertTrue(
            state.timeline.filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.terminal" }
        )
        // F007-S4: no live reasoning on this turn, so the final reasoning attaches.
        assertEquals(1, state.timeline.filterIsInstance<ReasoningEntry>().size)

        // One-shot: a duplicate late complete is no longer reconcilable and
        // is silently dropped (owner-locked, plan
        // ui-drop-unattributed-notices-v2-plan.md, PR #178 precedent) without
        // mutating the row — no generic row, timeline unchanged.
        val sizeBefore = state.timeline.size
        emitComplete(text = "duplicate", status = "complete")
        advanceUntilIdle()

        val after = viewModel.uiState.value
        val rowAfter = after.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.COMPLETE, rowAfter.state)
        assertEquals("Full authoritative answer.", rowAfter.text)
        assertEquals(sizeBefore, after.timeline.size)
        assertTrue(
            after.timeline.filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.terminal" }
        )
    }

    // Late completion with NO terminal text: the losslessly retained partial
    // stop text is kept (never wiped, never fabricated), the state still
    // upgrades to the authoritative COMPLETE outcome, trace follows.
    @Test
    fun lateCompleteAfterStop_nullText_keepsRetainedPartialText_upgradesState() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("Retained partial.")
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val row = state.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.COMPLETE, row.state)
        assertEquals("Retained partial.", row.text)
        assertEquals(TraceOverallState.COMPLETE, state.trace!!.overallState)
        assertTrue(
            state.timeline.filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.terminal" }
        )
    }

    // Stop lands AFTER the turn already completed: the guard rejects the stop
    // (no interrupt fired), and the row keeps the host's COMPLETE truth —
    // the transcript never claims the completed turn was stopped.
    @Test
    fun stopAfterComplete_isNoOp_rowKeepsAuthoritativeCompleteTruth() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("Answer prefix")
        advanceUntilIdle()
        emitComplete(text = "Done deal.", status = "complete")
        advanceUntilIdle()

        val sizeBefore = viewModel.uiState.value.timeline.size
        viewModel.stopStreaming()
        advanceUntilIdle()

        assertTrue(fakeAdapter.interruptedSessions.isEmpty())
        val state = viewModel.uiState.value
        val row = state.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.COMPLETE, row.state)
        assertEquals("Done deal.", row.text)
        assertEquals(sizeBefore, state.timeline.size)
        assertFalse(state.isStreaming)
        assertEquals(TraceOverallState.COMPLETE, state.trace!!.overallState)
    }

    // Normal completion path (no Stop involved) is unchanged: authoritative
    // terminal text seals the row COMPLETE with a COMPLETE trace, exactly one
    // assistant row, no generic notices.
    @Test
    fun normalCompletionWithoutStop_unchanged() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("Streamed part")
        advanceUntilIdle()

        emitComplete(text = "Streamed part + ending.", status = "complete")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val rows = state.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        assertEquals(assistantId, rows.single().id)
        assertEquals(AssistantMessageState.COMPLETE, rows.single().state)
        assertEquals("Streamed part + ending.", rows.single().text)
        assertEquals(TraceOverallState.COMPLETE, state.trace!!.overallState)
        assertFalse(state.isStreaming)
        assertTrue(
            state.timeline.filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.terminal" }
        )
    }

    // Repair R1 (P1): stop → a NEW prompt attempt lands AmbiguousUnknown (the
    // normal outcome of any gateway failure) → the first subsequent
    // status="complete" terminal carries NO turn id, so it must not consume
    // the stale one-shot marker and write that attempt's authoritative text
    // and COMPLETE state into the STOPPED turn's row. The new attempt closes
    // the marker window (frozen invariant: cleared wherever a turn can begin):
    // the stopped row keeps its partial text and INTERRUPTED truth, the trace
    // stays INTERRUPTED, and the unattributed terminal is silently dropped
    // (owner-locked, plan ui-drop-unattributed-notices-v2-plan.md, PR #178
    // precedent): renders nothing, the ambiguous window is not disturbed.
    @Test
    fun submitAmbiguousAfterStop_lateCompleteDoesNotUpgradeStoppedRow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("Partial answer.")
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        // Precondition: locally sealed stop with partial text.
        val stopped = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.INTERRUPTED, stopped.state)
        assertEquals("Partial answer.", stopped.text)

        // A new prompt attempt passes the single-flight guard (stop already
        // reset submitting/streaming) but its submission is ambiguous —
        // every gateway failure maps to AmbiguousUnknown.
        fakeAdapter.submitPromptResult = GatewayResult.Failure(
            GatewayFailure.Transport("gateway degraded")
        )
        viewModel.updateDraft("second prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()

        val afterSubmit = viewModel.uiState.value
        assertEquals(SubmissionStatus.UNKNOWN, afterSubmit.submissionStatus)
        assertNotNull(afterSubmit.pendingUnknownTurnId)

        // The first terminal after the ambiguous attempt belongs to NO
        // attributable local turn; it must never be attributed to the
        // stopped turn's sealed row.
        emitComplete(text = "Fabricated authoritative text.", status = "complete")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val row = state.timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals(AssistantMessageState.INTERRUPTED, row.state)
        assertEquals("Partial answer.", row.text)
        assertTrue(
            state.timeline.filterIsInstance<AssistantMessage>()
                .none { it.text.contains("Fabricated authoritative text") }
        )
        assertNotNull(state.trace)
        assertEquals(TraceOverallState.INTERRUPTED, state.trace!!.overallState)
        // The unattributed terminal is silently dropped (owner-locked, plan
        // ui-drop-unattributed-notices-v2-plan.md, PR #178 precedent): no
        // generic row is appended.
        assertTrue(
            state.timeline.filterIsInstance<GenericGatewayActivity>()
                .none { it.eventType == "message.terminal" }
        )
        // The ambiguous window is not disturbed by the unsolicited terminal.
        assertEquals(SubmissionStatus.UNKNOWN, state.submissionStatus)
        assertNotNull(state.pendingUnknownTurnId)
    }
}
