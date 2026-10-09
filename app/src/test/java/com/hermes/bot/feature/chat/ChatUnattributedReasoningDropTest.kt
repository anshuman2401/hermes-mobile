package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.ReasoningKind
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Plan ui-drop-unattributed-notices-v2-plan.md (owner-locked, PR #178
 * precedent): reasoning/thinking deltas with NO attributable active admitted
 * local turn are silently dropped — no GenericGatewayActivity row is appended
 * and the timeline does not grow. D-01 holds in its stronger form ("renders
 * nothing"): unsolicited gateway activity is never local-turn proof.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatUnattributedReasoningDropTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = org.junit.rules.TemporaryFolder()
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var ledger: PendingTurnLedger

    @org.junit.Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_test_vault")
        val vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
    }

    @Test
    fun reasoningDelta_withoutActiveTurn_isSilentlyDropped() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = FakeChatSessionRepository(),
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        // No submit: no admitted turn exists.
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ReasoningDelta(
                sessionId = "runtime-100",
                seq = 1L,
                kind = ReasoningKind.REASONING,
                text = "unsolicited reasoning"
            )
        )
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.timeline.size)
    }

    @Test
    fun thinkingDelta_withoutActiveTurn_isSilentlyDropped() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = FakeChatSessionRepository(),
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ReasoningDelta(
                sessionId = "runtime-100",
                seq = 1L,
                kind = ReasoningKind.THINKING,
                text = "unsolicited thinking"
            )
        )
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.timeline.size)
    }

    @Test
    fun thinkingDelta_afterTurnSealed_isSilentlyDropped() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = FakeChatSessionRepository(),
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        viewModel.updateDraft("streaming prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()

        // Late delta arrives after the turn is sealed: no longer visible.
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "final",
                status = "complete"
            )
        )
        advanceUntilIdle()
        val sealedTimeline = viewModel.uiState.value.timeline.size

        fakeAdapter.eventFlow.emit(
            GatewayEvent.ReasoningDelta(
                sessionId = "runtime-100",
                seq = 2L,
                kind = ReasoningKind.THINKING,
                text = "late thinking"
            )
        )
        advanceUntilIdle()

        assertEquals(sealedTimeline, viewModel.uiState.value.timeline.size)
    }

    class FakeChatSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages =
            MutableStateFlow<List<com.hermes.bot.data.gateway.SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<com.hermes.bot.data.gateway.SessionMessage>> =
            _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
            MutableStateFlow(ChatStreamingStatus())

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-100",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-100",
                    durableSessionId = storedSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }
}