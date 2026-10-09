package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.PromptSubmitResult
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.feature.chat.ChatStreamingStatus
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.feature.sessions.SessionsViewModel
import com.hermes.bot.navigation.ChatRouteOwnerViewModel
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * HOME-S3: Behavior-level JVM tests for active session streaming mark (specs/plans/HOME-001-S3-streaming-mark-plan-v1.md).
 * Deterministic, virtual-time dispatchers only, no real clock, no emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStreamingMarkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var vault: EncryptedVault
    private lateinit var ledger: PendingTurnLedger
    private lateinit var repo: DefaultSessionRepository

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("streaming_mark_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        fakeAdapter = FakeGatewayProtocolAdapter()
        repo = DefaultSessionRepository(fakeAdapter, vault)
        ledger = PendingTurnLedger(vault)
    }

    private fun createViewModel(sessionId: String = "sess-01"): ChatViewModel =
        ChatViewModel(
            sessionId = sessionId,
            sessionRepository = repo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )

    private suspend fun setupActiveSession(sessionId: String = "sess-01") {
        fakeAdapter.resumeSessionResult = GatewayResult.Success(
            SessionResumeResult(
                runtimeSessionId = "runtime-$sessionId",
                durableSessionId = sessionId,
                messages = emptyList(),
                messageCount = 0
            )
        )
        val resumeRes = repo.resumeSession(sessionId)
        assertTrue(resumeRes.isSuccess)
    }

    @Test
    fun streamingMark_visibleOnlyForActiveSessionWhileStreaming() = runTest {
        setupActiveSession("sess-01")
        val sessionsVm = SessionsViewModel(repo)
        val chatVm = createViewModel("sess-01")
        advanceUntilIdle()

        // Initially not streaming
        assertEquals(ChatStreamingStatus(), repo.activeTurnStreaming.value)
        assertFalse(sessionsVm.uiState.value.activeTurnStreaming.isStreaming)

        // Submit prompt admitted as streaming
        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        // Assert broadcast and sessions ViewModel reflect active turn streaming
        assertEquals(ChatStreamingStatus("sess-01", true), repo.activeTurnStreaming.value)
        assertTrue(sessionsVm.uiState.value.activeTurnStreaming.isStreaming)
        assertEquals("sess-01", sessionsVm.uiState.value.activeTurnStreaming.storedSessionId)
    }

    @Test
    fun streamingMark_absentForOtherSessions() = runTest {
        setupActiveSession("sess-01")
        val sessionsVm = SessionsViewModel(repo)
        val chatVm = createViewModel("sess-01")
        advanceUntilIdle()

        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        val streamingState = sessionsVm.uiState.value.activeTurnStreaming
        assertTrue(streamingState.isStreaming)
        assertEquals("sess-01", streamingState.storedSessionId)

        // Evaluate the D2 row visibility predicate
        val showForActive = streamingState.isStreaming && streamingState.storedSessionId == "sess-01"
        val showForOther = streamingState.isStreaming && streamingState.storedSessionId == "sess-02"
        assertTrue("Active session row shows running mark", showForActive)
        assertFalse("Non-active session row must not show running mark", showForOther)
    }

    @Test
    fun streamingMark_clearsOnTurnTerminal() = runTest {
        setupActiveSession("sess-01")
        val chatVm = createViewModel("sess-01")
        advanceUntilIdle()

        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        assertTrue(chatVm.uiState.value.isStreaming)
        assertTrue(repo.activeTurnStreaming.value.isStreaming)

        // Terminal complete event emitted on adapter
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-sess-01",
                seq = 1L,
                text = null,
                status = "complete"
            )
        )
        advanceUntilIdle()

        assertFalse("Chat UI state isStreaming cleared", chatVm.uiState.value.isStreaming)
        assertFalse("Repository activeTurnStreaming isStreaming cleared", repo.activeTurnStreaming.value.isStreaming)
    }

    @Test
    fun streamingMark_clearsOnStopStreaming() = runTest {
        setupActiveSession("sess-01")
        val chatVm = createViewModel("sess-01")
        advanceUntilIdle()

        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        assertTrue(chatVm.uiState.value.isStreaming)
        assertTrue(repo.activeTurnStreaming.value.isStreaming)

        chatVm.stopStreaming()
        advanceUntilIdle()

        assertFalse("Chat UI state cleared on stopStreaming", chatVm.uiState.value.isStreaming)
        assertFalse("Repository activeTurnStreaming cleared on stopStreaming", repo.activeTurnStreaming.value.isStreaming)
    }

    @Test
    fun streamingMark_clearsOnDisconnect() = runTest {
        setupActiveSession("sess-01")
        val chatVm = createViewModel("sess-01")
        advanceUntilIdle()

        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        assertTrue(chatVm.uiState.value.isStreaming)
        assertTrue(repo.activeTurnStreaming.value.isStreaming)

        // Transport drop converted by adapter into terminal error event
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-sess-01",
                seq = 1L,
                text = null,
                status = "error"
            )
        )
        advanceUntilIdle()

        assertFalse("Chat UI state cleared on disconnect terminal event", chatVm.uiState.value.isStreaming)
        assertFalse("Repository activeTurnStreaming cleared on disconnect", repo.activeTurnStreaming.value.isStreaming)
    }

    @Test
    fun streamingMark_clearsOnSessionSwitch() = runTest {
        setupActiveSession("sess-01")
        val owner = ChatRouteOwnerViewModel { key -> createViewModel(key) }
        val chatVm = owner.get("sess-01")!!
        advanceUntilIdle()

        chatVm.updateDraft("hello")
        chatVm.submitPrompt()
        advanceUntilIdle()

        assertTrue(repo.activeTurnStreaming.value.isStreaming)
        assertEquals("sess-01", repo.activeTurnStreaming.value.storedSessionId)

        // onCleared publishes false (simulates keyed-owner disposal on switch/exit)
        owner.clear("sess-01")
        advanceUntilIdle()

        assertFalse("Mark cleared on session switch / VM teardown", repo.activeTurnStreaming.value.isStreaming)

        // Stale-report guard: non-active publisher while sess-01 is active yields null/false
        repo.publishTurnStreaming("other-id", true)
        assertEquals(
            ChatStreamingStatus(storedSessionId = null, isStreaming = false),
            repo.activeTurnStreaming.value
        )
    }

    @Test
    fun streamingMark_neverClaimedByNonActivePublisher() = runTest {
        assertNull(repo.activeSession.value)
        repo.publishTurnStreaming("unknown-session", true)
        assertFalse("Broadcast must stay isStreaming == false", repo.activeTurnStreaming.value.isStreaming)
        assertNull("storedSessionId must be null", repo.activeTurnStreaming.value.storedSessionId)
    }
}
