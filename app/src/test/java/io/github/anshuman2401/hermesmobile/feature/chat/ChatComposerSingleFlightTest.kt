package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BLD-006 S3 — active-turn composer single flight (M-03 composer matrix).
 *
 * While a local turn is submitting or streaming, a second Send/IME Send is
 * unavailable, and any local rejection preserves the draft and leaves no
 * stranded optimistic SENDING row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatComposerSingleFlightTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: SingleFlightSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class SingleFlightSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeOutcome: Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> = Result.success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage(role = "user", content = "Initial question"),
                    io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage(role = "assistant", content = "Initial answer")
                )
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

    /** Test-local adapter wrapper recording every prompt.submit frame. */
    private class RecordingAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter by delegate {
        val submittedFrames = mutableListOf<Pair<String, String>>()
        override suspend fun submitPrompt(
            sessionId: String,
            text: String
        ): GatewayResult<PromptSubmitResult> {
            submittedFrames.add(sessionId to text)
            return delegate.submitPrompt(sessionId, text)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ChatViewModel.uiStateFlow(): MutableStateFlow<ChatUiState> {
        val field = ChatViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        return field.get(this) as MutableStateFlow<ChatUiState>
    }

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_single_flight_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = SingleFlightSessionRepository()
    }

    /** VM hydrated with a fresh runtime (history rows 0 and 1) so submit is allowed.
     *  Callers must run [advanceUntilIdle] after construction so loadSession()
     *  attaches the fresh runtime before submit-dependent assertions. */
    private fun singleFlightVm(adapter: RecordingAdapter): ChatViewModel =
        ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )

    // 1. Streaming: a second Send during the live local turn is a no-op —
    //    zero second frames, no new timeline rows, no new ledger rows.
    @Test
    fun streamingTurn_secondSend_suppressed_zeroExtraFrames() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("first prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()

        val streamingState = viewModel.uiState.value
        assertTrue(streamingState.isStreaming)
        assertFalse(streamingState.isSubmitting)
        assertEquals(1, adapter.submittedFrames.size)
        val streamingTimelineSize = streamingState.timeline.size
        val beforeLedger = ledger.getAllPendingTurns().size

        // A second Send/IME Send while streaming must be fully inert.
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals(1, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertEquals(streamingTimelineSize, viewModel.uiState.value.timeline.size)
        assertFalse(viewModel.uiState.value.composerDraft.isNotBlank() &&
            viewModel.uiState.value.isStreaming.not())
    }

    // 2. Submitting window: a second Send while SUBMITTING is a no-op. The
    //    submit coroutine is held open by a deferred adapter write, so the
    //    guard (not just post-admission state) is what blocks the resend.
    @Test
    fun submittingTurn_secondSend_suppressed_zeroExtraFrames() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()
        viewModel.updateDraft("only once")
        advanceUntilIdle()

        val beforeLedger = ledger.getAllPendingTurns().size
        viewModel.submitPrompt()
        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals(1, adapter.submittedFrames.size)
        assertEquals(beforeLedger + 1, ledger.getAllPendingTurns().size)
    }

    // 3. Readiness flag: false while streaming, true again at the terminal;
    //    false while initial loading, true after hydration.
    @Test
    fun composerReadiness_followsSubmitStreamLoadMatrix() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("matrix prompt")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isComposerReady)

        viewModel.submitPrompt()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isComposerReady)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 3L,
                text = "final",
                status = "complete"
            )
        )
        advanceUntilIdle()
        // The admitted submit cleared the draft; readiness returns once the
        // user types the next prompt (no blocked runtime/loading/turn state).
        assertFalse(viewModel.uiState.value.isComposerReady)
        viewModel.updateDraft("next prompt")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isComposerReady)
    }

    // 4. Initial load: readiness false while the resume is in flight, true
    //    once the history hydrates (loading finishes).
    @Test
    fun initialLoad_readinessFalse_untilHistoryHydrated() = runTest {
        val repo = BlockingResumeRepository(fakeRepo)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = repo,
            pendingTurnLedger = ledger
        )

        assertTrue(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.isComposerReady)

        repo.releaseResume.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isInitialLoading)
        // History hydrated; readiness requires a nonblank draft (typed after load).
        viewModel.updateDraft("ready text")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isComposerReady)
    }

    // 5. Rejected submit (the S3 local-rejection rows are the pre-mutation
    //    guards): a draft whose trimmed text is blank is rejected before any
    //    mutation — the trimmed submit guard — yet the raw in-memory draft
    //    stays visible/editable and creates no optimistic row or frame.
    @Test
    fun blankDraftRejection_preservesDraft_noOptimisticRow() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("   ")
        advanceUntilIdle()

        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals("   ", viewModel.uiState.value.composerDraft)
        assertEquals(0, adapter.submittedFrames.size)
        // Timeline is exactly the two hydrated history rows: the rejected
        // submit created no optimistic row (and no SENDING row survives).
        assertEquals(2, viewModel.uiState.value.timeline.size)
        assertTrue(
            viewModel.uiState.value.timeline.filterIsInstance<UserMessage>()
                .none { it.state == UserMessageState.SENDING }
        )
    }

    // 6. Missing-runtime rejection keeps the draft editable with no stranded
    //    optimistic SENDING row (S2 contract re-proven under the S3 flag).
    @Test
    fun missingRuntimeRejection_preservesDraft_noStrandedSendingRow() = runTest {
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("resume unavailable"))
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )
        viewModel.updateDraft("blocked draft")
        advanceUntilIdle()

        viewModel.submitPrompt()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("blocked draft", state.composerDraft)
        assertEquals("blocked draft", vault.getDraft("sess-100")?.composerText)
        assertTrue(state.timeline.isEmpty())
        assertTrue(
            state.timeline.filterIsInstance<UserMessage>()
                .none { it.state == UserMessageState.SENDING }
        )
        assertFalse(state.isComposerReady)
    }

    // 7. Rejection leaves no stranded SENDING row even when a row exists in
    //    the timeline from an earlier admitted turn: the streaming guard keeps
    //    the second send from creating any new optimistic row at all.
    @Test
    fun secondSendDuringActiveTurn_neverCreatesNewOptimisticSendingRow() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()
        viewModel.updateDraft("turn one")
        viewModel.submitPrompt()
        advanceUntilIdle()

        val userRows = viewModel.uiState.value.timeline.filterIsInstance<UserMessage>()
        val admittedTurnIds = userRows.map { it.id }.toSet()

        viewModel.submitPrompt()
        advanceUntilIdle()

        val afterRows = viewModel.uiState.value.timeline.filterIsInstance<UserMessage>()
        assertEquals(admittedTurnIds.size, afterRows.size)
        assertTrue(afterRows.all { it.id in admittedTurnIds })
        assertTrue(afterRows.none { it.state == UserMessageState.SENDING })
    }

    // 8. Post-terminal readiness: after the assistant turn seals, the composer
    //    is ready again and a new prompt submits exactly one frame.
    @Test
    fun postTerminalTurn_composerReady_nextPromptSubmitsOneFrame() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()

        viewModel.updateDraft("first")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertEquals(1, adapter.submittedFrames.size)
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "first answer",
                status = "complete"
            )
        )
        advanceUntilIdle()
        // The admitted first submit cleared the draft; readiness returns once
        // the next prompt is typed.
        assertFalse(viewModel.uiState.value.isComposerReady)
        viewModel.updateDraft("second")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isComposerReady)

        viewModel.submitPrompt()
        advanceUntilIdle()
        assertEquals(2, adapter.submittedFrames.size)
    }

    // 9. IME-equivalent path: submitPrompt during streaming keeps the draft
    //    the user typed after admission intact (no silent second-frame draft
    //    clear), so nothing the user typed mid-turn is lost.
    @Test
    fun streamingTurn_secondSend_doesNotClearDraftTypedMidTurn() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = singleFlightVm(adapter)
        advanceUntilIdle()
        viewModel.updateDraft("turn one")
        viewModel.submitPrompt()
        advanceUntilIdle()

        viewModel.updateDraft("turn one plus more typing")
        advanceUntilIdle()

        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals("turn one plus more typing", viewModel.uiState.value.composerDraft)
        assertEquals(1, adapter.submittedFrames.size)
    }

    /** Delegates everything; resumeSession is held until released. */
    private class BlockingResumeRepository(
        private val delegate: SingleFlightSessionRepository
    ) : SessionRepository by delegate {
        val releaseResume = CompletableDeferred<Unit>()
        override suspend fun resumeSession(storedSessionId: String): Result<io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult> {
            releaseResume.await()
            return delegate.resumeSession(storedSessionId)
        }
    }
}
