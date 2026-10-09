package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-018 PR-A (plan v1 test matrix a-i): live `message.interim` routing.
 *
 * Agent commentary never becomes reply text (REQ-004); already-streamed
 * interims dedup to a full no-op (REQ-005); notes join the turn's tool
 * activity card without inflating the action count (AC-5); a notes-only turn
 * still gets a card titled "Agent notes" (AC-2, OQ-001); the card renders
 * above the sealed reply (AC-3/AC-4); unattributed interims are silently
 * dropped (D-01, owner-locked: renders nothing, never turn proof); session
 * switch replaces the timeline (no duplicate notes); a reply-less turn keeps
 * the note card (plan row i).
 *
 * Harness mirrors ChatTranscriptTruthTest (FakeGatewayProtocolAdapter +
 * vault-backed ledger, MainDispatcherRule).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatInterimNoteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: InterimSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class InterimSessionRepository : SessionRepository {
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
        val dir = tempFolder.newFolder("chat_interim_note_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = InterimSessionRepository()
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

    private suspend fun emitInterim(
        text: String,
        alreadyStreamed: Boolean? = false,
        sessionId: String? = "runtime-100",
        seq: Long = 5L
    ) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageInterim(
                sessionId = sessionId, seq = seq, text = text, alreadyStreamed = alreadyStreamed
            )
        )
    }

    private suspend fun emitComplete(
        text: String? = null,
        status: String? = "complete"
    ) {
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100", seq = 9L, text = text, status = status, usage = null
            )
        )
    }

    // ------------------------------------------------------------------
    // (a) interim during active turn -> one note, reply text unchanged (AC-1)
    // ------------------------------------------------------------------

    @Test
    fun interim_duringActiveTurn_yieldsNoteEntry_replyTextUnchanged() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitInterim("Checking the repo layout…")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val notes = timeline.filterIsInstance<AgentNoteEntry>()
        assertEquals(1, notes.size)
        assertEquals("Checking the repo layout…", notes.single().text)
        assertTrue(notes.single().id.startsWith("note-"))
        // AC-1: the reply bubble never saw the interim text.
        val reply = timeline.filterIsInstance<AssistantMessage>().single { it.id == assistantId }
        assertEquals("", reply.text)
        assertEquals(AssistantMessageState.STREAMING, reply.state)
    }

    // ------------------------------------------------------------------
    // (b) already_streamed=true -> full no-op (extends ChatTranscriptTruthTest:410)
    // ------------------------------------------------------------------

    @Test
    fun interim_alreadyStreamedTrue_isFullNoOp() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        val before = viewModel.uiState.value

        emitInterim("replayed narration", alreadyStreamed = true)
        advanceUntilIdle()

        assertEquals(before, viewModel.uiState.value)
        assertTrue(
            viewModel.uiState.value.timeline.filterIsInstance<AgentNoteEntry>().isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // (c) two interims -> two entries in emit order (REQ-005)
    // ------------------------------------------------------------------

    @Test
    fun interim_twoNotes_appendInEmitOrder() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitInterim("first segment", seq = 5L)
        emitInterim("second segment", seq = 6L)
        advanceUntilIdle()

        val notes = viewModel.uiState.value.timeline.filterIsInstance<AgentNoteEntry>()
        assertEquals(listOf("first segment", "second segment"), notes.map { it.text })
    }

    // ------------------------------------------------------------------
    // (d) interim after tool events -> card steps [tool.., note], count excludes note (AC-5)
    // ------------------------------------------------------------------

    @Test
    fun interim_afterToolEvents_joinsCardSteps_countExcludesNote() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100", seq = 2L, toolId = "t-1",
                name = "tool_one", context = null, args = emptyMap()
            )
        )
        advanceUntilIdle()
        emitInterim("narration while the tool runs")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val userRow = timeline.filterIsInstance<UserMessage>().last()
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val cardIndex = display.indexOfFirst { it is TraceDisplayEntry.BlockCard }
        assertTrue("turn must produce a block card", cardIndex >= 0)
        val block = (display[cardIndex] as TraceDisplayEntry.BlockCard).block

        assertEquals(listOf("t-1", "note"), block.steps.map { it.id }.map { id ->
            when {
                id.startsWith("note-") -> "note"
                else -> id.removePrefix("tool-")
            }
        })
        assertEquals(1, block.totalActionCount)
        assertEquals(2, block.steps.size)
        assertTrue(block.steps[0] is ToolBlockStep.Tool)
        assertTrue(block.steps[1] is ToolBlockStep.Note)
        assertEquals("narration while the tool runs", (block.steps[1] as ToolBlockStep.Note).note.text)
        // AC-5: header copy still counts only real actions.
        assertEquals("1 action", ToolBlockPolicy.headerTitle(block))
        assertFalse(timeline.filterIsInstance<GenericGatewayActivity>().any {
            it.eventType == "message.interim"
        })
        assertTrue(userRow.id.isNotBlank())
    }

    // ------------------------------------------------------------------
    // (e) notes-only turn -> BlockCard whose only step is the note, "Agent notes" (AC-2, OQ-001)
    // ------------------------------------------------------------------

    @Test
    fun interim_onlyTurn_stillGetsCard_titledAgentNotes() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitInterim("all narration, no tools")
        advanceUntilIdle()

        val display = ToolBlockPolicy.buildBlockDisplayEntries(viewModel.uiState.value.timeline)
        val blockCard = display.filterIsInstance<TraceDisplayEntry.BlockCard>().single()
        assertEquals(1, blockCard.block.steps.size)
        val noteStep = blockCard.block.steps.single()
        assertTrue(noteStep is ToolBlockStep.Note)
        assertEquals("all narration, no tools", (noteStep as ToolBlockStep.Note).note.text)
        assertEquals("Agent notes", ToolBlockPolicy.headerTitle(blockCard.block))
        assertEquals(0, blockCard.block.totalActionCount)
    }

    // ------------------------------------------------------------------
    // (f) message.complete seals the reply bubble only; card above reply (AC-3/AC-4)
    // ------------------------------------------------------------------

    @Test
    fun complete_sealsReplyOnly_cardRendersAboveReply() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        val assistantId = submitTurn(viewModel)
        advanceUntilIdle()

        emitInterim("before the answer")
        emitComplete(text = "final answer", status = "complete")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val reply = timeline.filterIsInstance<AssistantMessage>().single { it.id == assistantId }
        assertEquals("final answer", reply.text)
        assertEquals(AssistantMessageState.COMPLETE, reply.state)
        // The note text never leaked into the reply bubble.
        assertFalse(reply.text.contains("before the answer"))

        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val cardIndex = display.indexOfFirst { it is TraceDisplayEntry.BlockCard }
        val replyIndex = display.indexOfFirst {
            it is TraceDisplayEntry.Raw && it.entry.id == assistantId
        }
        assertTrue(cardIndex in 0 until replyIndex)
    }

    // ------------------------------------------------------------------
    // (g) interim with no active admitted turn is dropped, no row (D-01, owner-locked)
    // ------------------------------------------------------------------

    @Test
    fun interim_withoutActiveTurn_isDropped_neverCard() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()

        val before = viewModel.uiState.value.timeline
        emitInterim("unsolicited narration")
        advanceUntilIdle()

        // Owner-locked drop (plan ui-remove-unattributed-activity-notices-v1):
        // an unattributable interim appends nothing — zero new timeline
        // entries, no GenericGatewayActivity row.
        val timeline = viewModel.uiState.value.timeline
        assertEquals(before.size, timeline.size)
        assertTrue(timeline.filterIsInstance<GenericGatewayActivity>().isEmpty())
        assertTrue(timeline.filterIsInstance<AgentNoteEntry>().isEmpty())
        // No user row exists, so no card can be built for this turn.
        assertTrue(
            ToolBlockPolicy.buildBlockDisplayEntries(timeline)
                .filterIsInstance<TraceDisplayEntry.BlockCard>().isEmpty()
        )
        // And no assistant row was fabricated (never a bubble, never turn proof).
        assertTrue(timeline.filterIsInstance<AssistantMessage>().isEmpty())
    }

    // ------------------------------------------------------------------
    // (h) loadSession after live notes -> hydrated rows replace timeline (session-switch reset)
    // ------------------------------------------------------------------

    @Test
    fun loadSession_afterLiveNotes_replacesTimelineWithHydratedRows() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()
        emitInterim("live note before switch")
        advanceUntilIdle()
        assertTrue(
            viewModel.uiState.value.timeline.filterIsInstance<AgentNoteEntry>().isNotEmpty()
        )

        // Session switch: a resume returning authoritative host history.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "q"),
                    SessionMessage(role = "tool", content = "", toolName = "tool_one"),
                    SessionMessage(role = "assistant", content = "hydrated reply")
                )
            )
        )
        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // No duplicate note rows survive the replacement.
        assertTrue(timeline.filterIsInstance<AgentNoteEntry>().isEmpty())
        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is UserMessage)
        assertTrue(timeline[1] is ToolActivity)
        assertTrue(timeline[2] is AssistantMessage)
        assertEquals("hydrated reply", (timeline[2] as AssistantMessage).text)
    }

    // ------------------------------------------------------------------
    // (i) turn ends without reply: complete(null text) -> empty COMPLETE row, note card intact
    // ------------------------------------------------------------------

    @Test
    fun complete_nullText_sealsEmptyReply_noteCardIntact() = runTest {
        val viewModel = freshVm()
        advanceUntilIdle()
        submitTurn(viewModel)
        advanceUntilIdle()

        emitInterim("narration only turn")
        emitComplete(text = null, status = "complete")
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val reply = timeline.filterIsInstance<AssistantMessage>().single()
        assertEquals("", reply.text)
        assertEquals(AssistantMessageState.COMPLETE, reply.state)
        val notes = timeline.filterIsInstance<AgentNoteEntry>()
        assertEquals(1, notes.size)

        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val blockCard = display.filterIsInstance<TraceDisplayEntry.BlockCard>().single()
        assertEquals("Agent notes", ToolBlockPolicy.headerTitle(blockCard.block))
        assertTrue(blockCard.block.steps.single() is ToolBlockStep.Note)
    }
}