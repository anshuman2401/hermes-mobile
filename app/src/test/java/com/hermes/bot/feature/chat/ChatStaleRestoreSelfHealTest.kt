package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.PromptSubmitResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-T7 — stale session restore banner self-heal (frozen plan v1, B-4/B-5/B-6).
 *
 * When the chat surface is blocked on NO_RUNTIME and the gateway adapter's
 * connectionStatus takes an EDGE to CONNECTED (never the initial emission,
 * never a repeated CONNECTED value), the ViewModel performs exactly ONE
 * loadSession() — the same method the banner's Restore button invokes.
 * The draft is preserved and zero prompt frames are emitted (B-6). The
 * manual tap path is unchanged and remains the fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStaleRestoreSelfHealTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeSelfHealSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class FakeSelfHealSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
            MutableStateFlow(ChatStreamingStatus())

        var resumeCalls = 0

        var resumeOutcome: Result<SessionResumeResult> = Result.failure(
            IllegalStateException("Session restore is temporarily unavailable")
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            resumeCalls++
            return resumeOutcome
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

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    /** Test-local adapter wrapper recording every prompt.submit frame (B-6). */
    private class RecordingAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : com.hermes.bot.data.gateway.GatewayProtocolAdapter by delegate {
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
        val dir = tempFolder.newFolder("chat_self_heal_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeSelfHealSessionRepository()
    }

    private fun successResume(): Result<SessionResumeResult> = Result.success(
        SessionResumeResult(
            runtimeSessionId = "runtime-100",
            durableSessionId = "sess-100",
            messageCount = 2,
            messages = listOf(
                SessionMessage(role = "user", content = "Initial question"),
                SessionMessage(role = "assistant", content = "Initial answer")
            )
        )
    )

    // 1. B-4/B-5 happy path: blocked + OFFLINE -> CONNECTED edge => exactly
    //    one self-heal loadSession(); banner clears; draft preserved; zero frames.
    @Test
    fun connectedEdge_whileBlocked_performsExactlyOneSelfHealResume() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        // Init loadSession() failed once: blocked, resume counter at 1.
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals(1, fakeRepo.resumeCalls)

        viewModel.updateDraft("draft kept")
        fakeRepo.resumeOutcome = successResume()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, fakeRepo.resumeCalls) // exactly ONE extra loadSession()
        assertNull(state.submitBlockedReason)
        assertEquals("runtime-100", state.runtimeSessionId)
        assertEquals(2, state.timeline.size)
        assertEquals("draft kept", state.composerDraft)
        assertEquals(0, adapter.submittedFrames.size) // B-6: no auto-submit
    }

    // 2. B-5(iv): collector starting on an already-CONNECTED adapter never
    //    fires — no edge was observed.
    @Test
    fun collectorStartOnAlreadyConnected_doesNotFireSelfHeal() = runTest {
        val adapter = RecordingAdapter(fakeAdapter) // CONNECTED from the start
        val viewModel = blockedVm(adapter)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)

        fakeRepo.resumeOutcome = successResume()
        advanceUntilIdle()

        assertEquals(1, fakeRepo.resumeCalls) // only the failed init load
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
    }

    // 3. B-5(ii): repeated CONNECTED value without an edge => no re-fire; and
    //    once not blocked, edges no longer trigger a self-heal.
    @Test
    fun repeatedConnectedValue_withoutEdge_doesNotRefire() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        assertEquals(1, fakeRepo.resumeCalls)

        fakeRepo.resumeOutcome = successResume()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(2, fakeRepo.resumeCalls)

        // Same CONNECTED value re-set (no edge): no additional loadSession().
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(2, fakeRepo.resumeCalls)

        // Now unblocked; a fresh OFFLINE -> CONNECTED edge must NOT fire.
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(2, fakeRepo.resumeCalls)
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 4. B-5(ii): steady-state CONNECTED chat (never blocked) => no loadSession
    //    on any edge.
    @Test
    fun connectedEdge_whileNotBlocked_doesNotFireSelfHeal() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = successResume() // init resume succeeds: not blocked
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.submitBlockedReason)
        assertEquals(1, fakeRepo.resumeCalls)

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertEquals(1, fakeRepo.resumeCalls)
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 5. B-5(iii): sessionId == "new-session" => nothing to restore, no fire.
    @Test
    fun connectedEdge_onNewSession_doesNotFireSelfHeal() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "new-session",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )
        // Block via the submit runtime guard (the only way a new-session chat blocks).
        viewModel.updateDraft("hello")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals(0, fakeRepo.resumeCalls)

        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertEquals(0, fakeRepo.resumeCalls)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 6. B-4 failure path: self-heal loadSession() failing keeps the banner
    //    honest (NO_RUNTIME stays set); manual path remains the fallback.
    @Test
    fun selfHealResumeFailure_keepsBlockedState() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        assertEquals(1, fakeRepo.resumeCalls)
        viewModel.updateDraft("draft kept")

        // resumeOutcome stays a failure; the edge still fires exactly one try.
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()

        assertEquals(2, fakeRepo.resumeCalls)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals("draft kept", viewModel.uiState.value.composerDraft)
        assertEquals(0, adapter.submittedFrames.size)
    }

    /** VM whose resume failed at init, leaving runtimeSessionId null (blocked). */
    private fun blockedVm(adapter: RecordingAdapter): ChatViewModel =
        ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )
}
