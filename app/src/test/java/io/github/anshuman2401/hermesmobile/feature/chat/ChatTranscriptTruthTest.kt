package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult
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
 * BLD-006 S4 — authoritative streamed-turn transcript (TASK-017/TASK-019).
 *
 * M-01 admission rows: an exact fresh runtime session ID + an active admitted
 * local turn mutates that turn; durable-only / stale / other / null / blank
 * IDs never mutate the transcript; unsolicited activity without an
 * attributable active turn is a bounded generic notice, never local-turn proof.
 *
 * M-02 stream/terminal rows: `start → delta A → tool rows → delta B →
 * complete` yields ONE assistant row (D-02 stable entry ID); terminal status
 * mapping (D-03) and source-ceiling behavior (D-04) per the frozen matrices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatTranscriptTruthTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: TranscriptSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class TranscriptSessionRepository : SessionRepository {
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
        val dir = tempFolder.newFolder("chat_transcript_truth_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = TranscriptSessionRepository()
    }

    /** VM with a fresh runtime attachment and no history rows. */
    private fun freshVm(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = ledger,
        vaultStorage = vault
    )

    /**
     * Submits a prompt and returns the minted assistant entry ID
     * (`asst-<pendingTurnId>`), resolved from the ledger record the submit
     * added (ledger listing order is filesystem order, so first/last on the
     * full list is not reliable).
     */
    private suspend fun submitTurn(viewModel: ChatViewModel, prompt: String = "hello"): String {
        val known = allTurnIds()
        viewModel.updateDraft(prompt)
        viewModel.submitPrompt()
        return "asst-" + newTurnIdSince(known)
    }

    /**
     * Returns the ledger turn ID minted by the most recent submit: the newly
     * added record vs. the [knownIds] snapshot. Ledger listing order is
     * filesystem order, so first()/last() on the full list is not reliable.
     */
    private suspend fun newTurnIdSince(knownIds: Set<String>): String =
        ledger.getAllPendingTurns().map { it.pendingTurnId }.first { it !in knownIds }

    private suspend fun allTurnIds(): Set<String> =
        ledger.getAllPendingTurns().map { it.pendingTurnId }.toSet()

    private suspend fun emitDelta(sessionId: String?, text: String) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(sessionId = sessionId, seq = 1L, text = text)
        )
    }

    private suspend fun emitComplete(
        sessionId: String? = "runtime-100",
        text: String? = null,
        status: String? = null,
        omitStatus: Boolean = false
    ) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = sessionId,
                seq = 2L,
                text = text,
                status = if (omitStatus) null else status,
                usage = null
            )
        )
    }

    // ==================================================================
    // M-01: session/event admission (D-01)
    // ==================================================================

    @Test
    fun admission_exactRuntimeId_activeLocalTurn_appliesToThatTurn() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "delta A")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val assistantRows = timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, assistantRows.size)
        assertEquals(assistantId, assistantRows.single().id)
        assertEquals("delta A", assistantRows.single().text)
        assertEquals(AssistantMessageState.STREAMING, assistantRows.single().state)
    }

    @Test
    fun admission_durableIdOnly_isRejected_neverMutatesTranscript() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        val before = viewModel.uiState.value.timeline

        emitDelta("sess-100", "durable-only delta")
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.timeline)
    }

    @Test
    fun admission_nullSessionId_isRejected() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        val before = viewModel.uiState.value.timeline

        emitDelta(null, "null session delta")
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.timeline)
    }

    @Test
    fun admission_blankAndWhitespaceSessionIds_areRejected() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        val before = viewModel.uiState.value.timeline

        emitDelta("", "empty session delta")
        emitDelta("   ", "whitespace session delta")
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.timeline)
    }

    @Test
    fun admission_staleRuntimeId_afterRotation_isRejected() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        // Runtime rotates (fresh attachment hands out a new runtime ID)
        fakeRepo.resumeOutcome = Result.success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
                runtimeSessionId = "runtime-fresh-200",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList()
            )
        )
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals("runtime-fresh-200", viewModel.uiState.value.runtimeSessionId)
        val before = viewModel.uiState.value.timeline

        emitDelta("runtime-100", "stale runtime delta")
        advanceUntilIdle()
        assertEquals(before, viewModel.uiState.value.timeline)

        // New runtime still admitted — but its assistant row was replaced by
        // the hydration wipe, so the fresh delta is unattributable under D-01
        // and is silently dropped (owner-locked: renders nothing; mid-turn
        // hydration is a BLD-007 concern; S4 only guarantees no stale
        // mutation).
        emitDelta("runtime-fresh-200", "fresh delta")
        advanceUntilIdle()
        assertEquals(before.size, viewModel.uiState.value.timeline.size)
        assertTrue(
            viewModel.uiState.value.timeline
                .filterIsInstance<GenericGatewayActivity>().isEmpty()
        )
    }

    @Test
    fun admission_anotherSessionId_isRejected() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        val before = viewModel.uiState.value.timeline

        emitDelta("runtime-other-999", "other session delta")
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value.timeline)
    }

    @Test
    fun admission_unsolicitedDelta_noActiveTurn_isDropped_neverLocalTurnProof() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        // No local turn was admitted: nothing to attribute the delta to.
        val before = viewModel.uiState.value.timeline

        emitDelta("runtime-100", "unsolicited assistant text")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // Owner-locked drop (plan ui-remove-unattributed-activity-notices-v1):
        // the timeline is unchanged — no generic row, no growth.
        assertEquals(before.size, timeline.size)
        assertTrue(timeline.filterIsInstance<GenericGatewayActivity>().isEmpty())
        // No assistant row was fabricated from the unsolicited text.
        assertTrue(timeline.filterIsInstance<AssistantMessage>().isEmpty())
    }

    @Test
    fun admission_unsolicitedTerminal_noActiveTurn_isDropped_neverLocalTurnProof() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        // No local turn was admitted: nothing to attribute the terminal to.
        val before = viewModel.uiState.value.timeline

        emitComplete(text = "stray complete", status = "complete")
        advanceUntilIdle()

        // Owner-locked drop (plan ui-drop-unattributed-notices-v2-plan.md,
        // PR #178 precedent): the unattributed terminal renders nothing —
        // no generic row, no growth.
        assertEquals(before, viewModel.uiState.value.timeline)
        assertTrue(viewModel.uiState.value.timeline.filterIsInstance<GenericGatewayActivity>().isEmpty())
        assertTrue(viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().isEmpty())
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun admission_unsolicitedError_noActiveTurn_isDropped_neverLocalTurnProof() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val before = viewModel.uiState.value.timeline

        fakeAdapter.eventFlow.emit(
            GatewayEvent.Error(sessionId = "runtime-100", seq = 1L, message = "boom")
        )
        advanceUntilIdle()

        // Same owner-locked drop family (unattributed terminal): the error
        // terminal with no active turn renders nothing.
        assertEquals(before, viewModel.uiState.value.timeline)
        assertTrue(viewModel.uiState.value.timeline.filterIsInstance<GenericGatewayActivity>().isEmpty())
        assertTrue(viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>().isEmpty())
    }

    // ==================================================================
    // M-02: stream and terminal (D-02 / D-03)
    // ==================================================================

    @Test
    fun stream_exactRuntime_start_deltaA_toolRows_deltaB_complete_oneAssistantRow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "delta A")
        advanceUntilIdle()

        // Interleaved tool rows
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolGenerating(sessionId = "runtime-100", seq = 1L, name = "tool_one")
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100", seq = 2L, toolId = "t-1",
                name = "tool_one", context = null, args = emptyMap()
            )
        )
        advanceUntilIdle()

        emitDelta("runtime-100", "delta B")
        advanceUntilIdle()

        emitComplete(text = "delta Adelta B", status = "complete")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val assistantRows = timeline.filterIsInstance<AssistantMessage>()
        // D-02: exactly one assistant row with the stable submit-time ID
        assertEquals(1, assistantRows.size)
        assertEquals(assistantId, assistantRows.single().id)
        assertEquals("delta Adelta B", assistantRows.single().text)
        assertEquals(AssistantMessageState.COMPLETE, assistantRows.single().state)
        // Interleaved tool rows are present and independent
        assertTrue(timeline.filterIsInstance<ToolActivity>().isNotEmpty())
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun stream_zeroDelta_complete_sealsEmptyRow_withTerminalText() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitComplete(text = "final answer", status = "complete")
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        assertEquals(assistantId, rows.single().id)
        assertEquals("final answer", rows.single().text)
        assertEquals(AssistantMessageState.COMPLETE, rows.single().state)
    }

    @Test
    fun stream_zeroDelta_completeNullText_sealsEmptyRow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        assertEquals("", rows.single().text)
        assertEquals(AssistantMessageState.COMPLETE, rows.single().state)
    }

    @Test
    fun stream_interim_alreadyStreamedTrue_notReAdmitted() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "delta A")
        advanceUntilIdle()
        val afterFirst = viewModel.uiState.value.timeline

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageInterim(
                sessionId = "runtime-100", seq = 3L,
                text = "delta A", alreadyStreamed = true
            )
        )
        advanceUntilIdle()
        assertEquals(afterFirst, viewModel.uiState.value.timeline)

        // CHAT-018 PR-A: not-already-streamed interim text is agent commentary —
        // it becomes an AgentNoteEntry inside the active turn's card, NOT reply
        // text (the reply bubble is never touched by interim events, REQ-004).
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageInterim(
                sessionId = "runtime-100", seq = 4L,
                text = "more", alreadyStreamed = false
            )
        )
        advanceUntilIdle()
        val timelineAfterInterim = viewModel.uiState.value.timeline
        assertTrue(timelineAfterInterim.last() is AgentNoteEntry)
        assertEquals("more", (timelineAfterInterim.last() as AgentNoteEntry).text)
        assertEquals(
            "reply bubble text unchanged by interim (REQ-004)",
            "delta A",
            timelineAfterInterim.filterIsInstance<AssistantMessage>().single().text
        )
    }

    @Test
    fun stream_errorBeforeText_sealsFailedRow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.Error(sessionId = "runtime-100", seq = 1L, message = "boom")
        )
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        assertEquals(assistantId, rows.single().id)
        assertEquals("", rows.single().text)
        assertEquals(AssistantMessageState.FAILED, rows.single().state)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    /**
     * PR-36 review pin: the AssistantMessage.errorMessage field stays
     * UNRENDERED. It is retained on the row for VM diagnostics/tests only;
     * the raw gateway error line is never merged into the rendered row text
     * (the transcript is a durable, copyable surface — SEC-004) and the
     * FAILED chip shows only the typed non-secret status. If a future change
     * starts rendering this field, this contract must be revisited first.
     */
    @Test
    fun stream_errorRow_errorMessageStaysUnrendered_diagnosticsOnly() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.Error(sessionId = "runtime-100", seq = 2L, message = "transport: connection reset by peer")
        )
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(assistantId, row.id)
        // Diagnostics-only retention: the raw line is kept on the row…
        assertEquals("transport: connection reset by peer", row.errorMessage)
        // …but never rendered as (or into) the visible transcript text.
        assertEquals("", row.text)
        assertEquals(AssistantMessageState.FAILED, row.state)
    }

    @Test
    fun stream_terminalTextDifferent_replacesAggregate() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "streamed aggregate")
        advanceUntilIdle()
        emitComplete(text = "authoritative final", status = "complete")
        advanceUntilIdle()

        assertEquals(
            "authoritative final",
            (viewModel.uiState.value.timeline.last() as AssistantMessage).text
        )
    }

    @Test
    fun stream_terminalTextNull_keepsAdmittedDeltas() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "kept ")
        emitDelta("runtime-100", "deltas")
        advanceUntilIdle()
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        assertEquals(
            "kept deltas",
            (viewModel.uiState.value.timeline.last() as AssistantMessage).text
        )
    }

    // ------------------------------------------------------------------
    // D-03 status mapping rows (raw nullable status from the adapter)
    // ------------------------------------------------------------------

    private suspend fun statusRow(
        status: String?,
        omitStatus: Boolean,
        expectedState: AssistantMessageState
    ) = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "text")
        advanceUntilIdle()
        emitComplete(text = "text", status = status, omitStatus = omitStatus)
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals(expectedState, row.state)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test fun status_exactComplete_mapsToComplete() = runTest { statusRow("complete", false, AssistantMessageState.COMPLETE) }
    // D-03: trimming + Locale.ROOT lower-casing precedes the exact match, so
    // case variants of the certified values map to their states.
    @Test fun status_caseVariantCOMPLETE_mapsToComplete() = runTest { statusRow("COMPLETE", false, AssistantMessageState.COMPLETE) }
    @Test fun status_exactInterrupted_mapsToInterrupted() = runTest { statusRow("interrupted", false, AssistantMessageState.INTERRUPTED) }
    @Test fun status_caseVariantInterrupted_mapsToInterrupted() = runTest { statusRow("Interrupted", false, AssistantMessageState.INTERRUPTED) }
    @Test fun status_exactError_mapsToFailed() = runTest { statusRow("error", false, AssistantMessageState.FAILED) }
    @Test fun status_absent_mapsToUnknown() = runTest { statusRow(null, true, AssistantMessageState.UNKNOWN) }
    @Test fun status_blank_mapsToUnknown() = runTest { statusRow("", false, AssistantMessageState.UNKNOWN) }
    @Test fun status_whitespaceOnly_mapsToUnknown() = runTest { statusRow("   ", false, AssistantMessageState.UNKNOWN) }
    @Test fun status_completedSynonym_mapsToUnknown() = runTest { statusRow("completed", false, AssistantMessageState.UNKNOWN) }
    @Test fun status_doneSynonym_mapsToUnknown() = runTest { statusRow("done", false, AssistantMessageState.UNKNOWN) }
    @Test fun status_failedSynonym_mapsToUnknown() = runTest { statusRow("failed", false, AssistantMessageState.UNKNOWN) }
    @Test fun status_futureValue_mapsToUnknown() = runTest { statusRow("quantum-flux", false, AssistantMessageState.UNKNOWN) }

    @Test
    fun status_trimmedExactComplete_isAccepted() = runTest { statusRow(" complete ", false, AssistantMessageState.COMPLETE) }
    @Test
    fun status_trimmedExactInterrupted_isAccepted() = runTest { statusRow("\tinterrupted\n", false, AssistantMessageState.INTERRUPTED) }
    @Test
    fun status_trimmedExactError_isAccepted() = runTest { statusRow(" error ", false, AssistantMessageState.FAILED) }

    // ------------------------------------------------------------------
    // M-02 source rows through the reducer (1 MiB window, 8 MiB ceiling)
    // ------------------------------------------------------------------

    @Test
    fun source_belowPresentationWindow_noLabels() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "a".repeat(1024))
        advanceUntilIdle()
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals("a".repeat(1024), row.text)
        assertFalse(row.sourceOverflow)
        assertFalse(row.text.contains(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL))
    }

    @Test
    fun source_abovePresentationWindow_showsClipLabel_canonicalRetained() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        // 1 MiB + 16 scalars: presentation clips, canonical retains
        emitDelta("runtime-100", "a".repeat(1 * 1024 * 1024))
        emitDelta("runtime-100", "b".repeat(16))
        advanceUntilIdle()
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertTrue(row.text.startsWith("a".repeat(1 * 1024 * 1024)))
        assertTrue(row.text.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL))
        assertFalse(row.sourceOverflow)
    }

    @Test
    fun source_atHardCeiling_delta_thenOverflow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "a".repeat(8 * 1024 * 1024))
        emitDelta("runtime-100", "tail")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertTrue(row.sourceOverflow)
        assertTrue(
            row.text.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL +
                AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL)
        )
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()
        val sealed = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertTrue(sealed.sourceOverflow)
    }

    @Test
    fun source_terminalTextOverCap_sealsAdmittedPrefix_withOverflow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "streamed small")
        advanceUntilIdle()
        emitComplete(text = "z".repeat(8 * 1024 * 1024 + 3), status = "complete")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals(AssistantMessageState.COMPLETE, row.state)
        assertTrue(row.sourceOverflow)
        // Sealed admitted prefix: exactly 8 MiB of 'z' + both labels
        assertTrue(row.text.startsWith("z".repeat(1 * 1024 * 1024)))
        assertTrue(
            row.text.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL +
                AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL)
        )
    }

    @Test
    fun source_terminalTextEqual_replacesAggregate_exactlyOnce() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "same")
        advanceUntilIdle()
        emitComplete(text = "same", status = "complete")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals("same", row.text)
        assertFalse(row.sourceOverflow)
    }

    @Test
    fun source_terminalTextNull_keepsOverflowFromDeltas() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitDelta("runtime-100", "a".repeat(8 * 1024 * 1024))
        emitDelta("runtime-100", "b")
        advanceUntilIdle()
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val row = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertTrue(row.sourceOverflow)
        assertTrue(row.text.startsWith("a".repeat(1024)))
    }

    // ------------------------------------------------------------------
    // D-02: stable entry ID / no per-fragment rows
    // ------------------------------------------------------------------

    @Test
    fun stableId_thousandsOfFragments_stillOneAssistantRow() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        val expected = StringBuilder()
        repeat(1000) { i ->
            val fragment = "f$i|"
            expected.append(fragment)
            emitDelta("runtime-100", fragment)
        }
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, rows.size)
        assertEquals(assistantId, rows.single().id)
        assertEquals(expected.toString(), rows.single().text)
    }

    @Test
    fun stableId_secondTurn_getsFreshEntryId() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val firstId = submitTurn(viewModel)
        advanceUntilIdle()
        emitDelta("runtime-100", "one")
        emitComplete(text = "one", status = "complete")
        advanceUntilIdle()

        val draft = "second"
        viewModel.updateDraft(draft)
        viewModel.submitPrompt()
        advanceUntilIdle()
        val secondId = "asst-" + newTurnIdSince(setOf(firstId.removePrefix("asst-")))
        emitDelta("runtime-100", "two")
        emitComplete(text = "two", status = "complete")
        advanceUntilIdle()

        val rows = viewModel.uiState.value.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(2, rows.size)
        assertEquals(firstId, rows[0].id)
        assertEquals(secondId, rows[1].id)
        assertEquals("two", rows[1].text)
    }

    @Test
    fun ledger_turnResolvedOnTerminal() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val knownIds = allTurnIds()
        viewModel.updateDraft("prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()
        val turnId = newTurnIdSince(knownIds)

        emitComplete(text = "answer", status = "complete")
        advanceUntilIdle()

        val turn = ledger.getPendingTurn(turnId)
        assertNotNull(turn)
        assertEquals(io.github.anshuman2401.hermesmobile.data.local.PendingTurnState.RESOLVED, turn!!.state)
    }

    // ------------------------------------------------------------------
    // Decoded events (adapter-side probe for the D-03 decode boundary)
    // ------------------------------------------------------------------

    @Test
    fun decode_nonStringStatus_decodesAsNull_neverFabricated() {
        val adapter = io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter(
            transport = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport(),
            dispatcher = mainDispatcherRule.testDispatcher
        )
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","payload":{"text":"done","status":42}}"""
        )
        assertEquals(null, (event as GatewayEvent.MessageComplete).status)
    }

    @Test
    fun decode_absentStatus_staysNull() {
        val adapter = io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter(
            transport = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport(),
            dispatcher = mainDispatcherRule.testDispatcher
        )
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","payload":{"text":"done"}}"""
        )
        assertEquals(null, (event as GatewayEvent.MessageComplete).status)
    }

    @Test
    fun decode_whitespaceStatus_preservedVerbatim() {
        val adapter = io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter(
            transport = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport(),
            dispatcher = mainDispatcherRule.testDispatcher
        )
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","payload":{"status":"  "}}"""
        )
        assertEquals("  ", (event as GatewayEvent.MessageComplete).status)
    }

    @Test
    fun decode_blankStatus_staysBlankString() {
        val adapter = io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter(
            transport = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport(),
            dispatcher = mainDispatcherRule.testDispatcher
        )
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","payload":{"status":""}}"""
        )
        assertEquals("", (event as GatewayEvent.MessageComplete).status)
    }

    @Test
    fun decode_certifiedComplete_isPreserved() {
        val adapter = io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter(
            transport = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport(),
            dispatcher = mainDispatcherRule.testDispatcher
        )
        val event = adapter.parseEventForTest(
            """{"type":"message.complete","payload":{"status":"complete"}}"""
        )
        assertEquals("complete", (event as GatewayEvent.MessageComplete).status)
    }
}
