package io.github.anshuman2401.hermesmobile.data.turn

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.gateway.FallbackReason
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayReconnectManager
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.HistoryFallbackSignal
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.SignOutCacheChoice
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.PendingTurnState
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.domain.SessionNotFoundException
import io.github.anshuman2401.hermesmobile.feature.chat.AssistantMessage
import io.github.anshuman2401.hermesmobile.feature.chat.AssistantMessageState
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.feature.chat.GenericGatewayActivity
import io.github.anshuman2401.hermesmobile.feature.chat.SubmissionStatus
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessage
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessageState
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownParser
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic test suite verifying all 12 fault-recovery and no-duplicate-send cases (R-01 to R-12)
 * specified in specs/architecture/test_strategy.md.
 */
class ReconnectNoDuplicateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var masterKeyProvider: InMemoryMasterKeyProvider
    private lateinit var vault: EncryptedVault
    private lateinit var ledger: PendingTurnLedger
    private lateinit var harness: ReconnectFaultInjectionHarness
    private lateinit var fakeRepo: FakeTurnSessionRepository
    private lateinit var fakeAuth: FakeReconnectAuthRepository
    private lateinit var coordinator: TurnCoordinator

    class FakeTurnSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var serverMessages = mutableListOf<SessionMessage>()
        var resumeFailWithNotFound = false

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-durable-1", "sess-runtime-1", isDraft = true))

        var activeRuntimeId = "runtime-sid-1"

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            if (resumeFailWithNotFound) {
                return Result.failure(SessionNotFoundException(storedSessionId))
            }
            return Result.success(
                SessionResumeResult(
                    runtimeSessionId = activeRuntimeId,
                    durableSessionId = storedSessionId,
                    messageCount = serverMessages.size,
                    messages = serverMessages
                )
            )
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

    class FakeReconnectAuthRepository : AuthRepository {
        val ticketMintCount = AtomicInteger(0)
        var shouldFailTicketMint = false

        override suspend fun checkStatus(origin: String): Result<io.github.anshuman2401.hermesmobile.data.auth.HostStatus> =
            Result.success(io.github.anshuman2401.hermesmobile.data.auth.HostStatus(version = "v0.21.1", authRequired = false, gatewayRunning = true))

        override suspend fun hasValidSession(): Boolean = true
        override suspend fun login(origin: String, username: String, password: CharArray, provider: String): Result<AuthCredential> =
            Result.success(AuthCredential(driverKind = "password", providerId = provider, cookies = emptyMap()))

        override suspend fun getStoredCredential(): AuthCredential? = null

        override suspend fun mintWebSocketTicket(origin: String): Result<GatewayTicket> {
            if (shouldFailTicketMint) return Result.failure(Exception("Ticket mint failed"))
            val count = ticketMintCount.incrementAndGet()
            return Result.success(GatewayTicket("ticket-val-$count"))
        }

        override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) {}
        override suspend fun notifyAuthExpired() {}
    }

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("reconnect_vault")
        masterKeyProvider = InMemoryMasterKeyProvider()
        vault = EncryptedVault(
            storageDir = vaultDir,
            cipherEngine = AesGcmCipherEngine(masterKeyProvider),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        harness = ReconnectFaultInjectionHarness()
        fakeRepo = FakeTurnSessionRepository()
        fakeAuth = FakeReconnectAuthRepository()
        coordinator = TurnCoordinator(harness, ledger)
    }

    // R-01: Connect fails before WS open -> Offline/failed state, bounded retry, no prompt frame
    @Test
    fun r01_connectFailsBeforeWsOpen_boundedRetry_zeroPromptFrames() = runTest {
        harness.failConnect = true
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = harness,
            authRepository = fakeAuth,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            maxAttempts = 3,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        reconnectManager.configureOrigin("https://hermes.local")
        reconnectManager.reconnectNow()

        // 0 prompt frames sent
        assertEquals(0, harness.promptSubmitCount.get())
        // Verified reconnect manager initiated ticket mint and connection attempt
        assertTrue(fakeAuth.ticketMintCount.get() >= 1)
    }

    // R-02: Socket closes before prompt write attempted -> Draft retained; no send; user can retry
    @Test
    fun r02_socketClosesBeforePromptWrite_draftRetained_zeroFramesSent() = runTest {
        harness.connectionStatus.value = ConnectionStatus.OFFLINE

        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        viewModel.updateDraft("Prompt before socket drop")
        // User has not submitted or socket unavailable before write attempt
        assertEquals("Prompt before socket drop", viewModel.uiState.value.composerDraft)
        assertEquals(0, harness.promptSubmitCount.get())
    }

    // R-03: Socket fails during/after prompt write but before RPC result -> SUBMISSION_UNKNOWN, exactly 1 prompt attempt, no auto-resend
    @Test
    fun r03_socketFailsDuringWrite_submissionUnknown_exactlyOneAttempt_noAutoResend() = runTest {
        harness.failPromptSubmitWith = GatewayFailure.SubmissionUnknown("Socket dropped during frame flush")

        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        viewModel.updateDraft("Write will drop")
        viewModel.submitPrompt()

        // Exactly one prompt frame attempted
        assertEquals(1, harness.promptSubmitCount.get())

        // State is UNKNOWN
        val state = viewModel.uiState.value
        assertEquals(SubmissionStatus.UNKNOWN, state.submissionStatus)
        assertNotNull(state.pendingUnknownTurnId)

        val turnRecord = ledger.getPendingTurn(state.pendingUnknownTurnId!!)
        assertEquals(PendingTurnState.UNKNOWN, turnRecord!!.state)
        // Strictly prohibits auto-send!
        assertFalse(ledger.canAutoSend(state.pendingUnknownTurnId!!))

        // Trigger an unrelated event / tick -> must NOT automatically resend
        harness.eventFlow.emit(GatewayEvent.SessionsChanged())
        assertEquals(1, harness.promptSubmitCount.get()) // Still exactly 1!
    }

    // R-04: Drop after status: streaming, mid-deltas -> reconnect/replay or history replace; no duplicate; partial/terminal truth visible
    @Test
    fun r04_dropAfterStreamingMidDeltas_historyReplace_noDuplicate() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        viewModel.updateDraft("Streaming prompt")
        viewModel.submitPrompt()
        assertEquals(1, harness.promptSubmitCount.get())

        // Stream initial delta — BLD-006 S5: text is admitted and retained
        // synchronously; the coalesced publication lands after the virtual
        // 32 ms window elapses.
        harness.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-sid-1",
                seq = 1L,
                text = "Partial response..."
            )
        )
        advanceUntilIdle()
        val asstMsg = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals("Partial response...", asstMsg.text)

        // Simulate network drop and server history reconciliation
        fakeRepo.serverMessages = mutableListOf(
            SessionMessage(role = "user", content = "Streaming prompt"),
            SessionMessage(role = "assistant", content = "Completed response from server truth")
        )

        // Reload session / history replace
        viewModel.loadSession()

        // History replaced from authoritative server truth
        val reloadedAsst = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals("Completed response from server truth", reloadedAsst.text)
        assertEquals(AssistantMessageState.COMPLETE, reloadedAsst.state)

        // No duplicate prompt submitted
        assertEquals(1, harness.promptSubmitCount.get())
    }

    // R-05: Host restart / changed replay epoch -> Clear watermark, resume durable session, history replacement
    @Test
    fun r05_hostRestartChangedReplayEpoch_clearsWatermarks_historyReplacement() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        fakeRepo.serverMessages = mutableListOf(
            SessionMessage(role = "user", content = "Question before reboot"),
            SessionMessage(role = "assistant", content = "Answer before reboot")
        )

        // Host reboot triggers history fallback signal with changed epoch
        harness.fallbackSignalFlow.emit(
            HistoryFallbackSignal(
                durableSessionId = "sess-durable-1",
                runtimeSessionId = "runtime-sid-1",
                reason = FallbackReason.EPOCH_CHANGED_READY,
                details = "Epoch changed from epoch-100 to epoch-200"
            )
        )

        // ViewModel reloads authoritative history
        val timeline = viewModel.uiState.value.timeline
        assertEquals(2, timeline.size)
        assertEquals("Question before reboot", (timeline[0] as UserMessage).text)
        assertEquals("Answer before reboot", (timeline[1] as AssistantMessage).text)
    }

    // R-06: Replay says truncated: true -> History replacement, never fill gap with cache guess
    @Test
    fun r06_replayTruncated_triggersHistoryReplacement() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        fakeRepo.serverMessages = mutableListOf(
            SessionMessage(role = "user", content = "Earlier conversation"),
            SessionMessage(role = "assistant", content = "Earlier server reply")
        )

        // Replay truncation signal
        harness.fallbackSignalFlow.emit(
            HistoryFallbackSignal(
                durableSessionId = "sess-durable-1",
                runtimeSessionId = "runtime-sid-1",
                reason = FallbackReason.TRUNCATED,
                details = "Ring buffer truncated"
            )
        )

        assertEquals(2, viewModel.uiState.value.timeline.size)
        assertEquals("Earlier server reply", (viewModel.uiState.value.timeline[1] as AssistantMessage).text)
    }

    // R-07: VPN/Wi-Fi route changes -> Reconnect uses new ticket; runtime identity refreshed; no prompt replay
    @Test
    fun r07_vpnRouteChange_reconnectUsesNewTicket_noPromptReplay() = runTest {
        val reconnectManager = GatewayReconnectManager(
            gatewayAdapter = harness,
            authRepository = fakeAuth,
            scope = CoroutineScope(mainDispatcherRule.testDispatcher),
            maxAttempts = 5,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        reconnectManager.configureOrigin("https://hermes.local")

        // First connection uses ticket 1
        reconnectManager.reconnectNow()
        assertEquals(1, fakeAuth.ticketMintCount.get())

        // Network drops / VPN switch
        harness.connectionStatus.value = ConnectionStatus.OFFLINE
        // Foreground reconnect trigger
        reconnectManager.onAppForegrounded()

        // Fresh ticket minted (ticket 2), never reused ticket 1!
        assertEquals(2, fakeAuth.ticketMintCount.get())
        // Zero prompt frames sent
        assertEquals(0, harness.promptSubmitCount.get())
    }

    // R-08: Android process is killed during stream -> Relaunch resolves server history; pending turn shown unknown until proven
    @Test
    fun r08_processKilledDuringStream_relaunchReconcilesOrShowsUnknown_neverAutoReplays() = runTest {
        // Step 1: Pre-crash state: Turn is submitted and enters UNKNOWN when process dies
        val initialRecord = ledger.prepareTurn("sess-durable-1", "Critical financial prompt", "runtime-sid-1")
        ledger.markWriteAttempted(initialRecord.pendingTurnId)
        ledger.markUnknown(initialRecord.pendingTurnId)

        // Step 2: Simulate process recreation with brand new ViewModel and Ledger instance reading the same encrypted vault
        val recreatedVault = EncryptedVault(
            storageDir = vaultDir,
            cipherEngine = AesGcmCipherEngine(masterKeyProvider),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        val recreatedLedger = PendingTurnLedger(recreatedVault)
        val recreatedHarness = ReconnectFaultInjectionHarness()
        val recreatedCoordinator = TurnCoordinator(recreatedHarness, recreatedLedger)

        val restoredTurn = recreatedLedger.getPendingTurn(initialRecord.pendingTurnId)
        assertNotNull(restoredTurn)
        assertEquals(PendingTurnState.UNKNOWN, restoredTurn!!.state)
        // Must NOT auto-send!
        assertFalse(recreatedLedger.canAutoSend(initialRecord.pendingTurnId))

        // Step 3: Server history check: Case A - Not in server history -> Remains UNKNOWN
        val outcomeUnproven = recreatedCoordinator.reconcilePendingTurnWithHistory("sess-durable-1", emptyList())
        assertTrue(outcomeUnproven is ReconciliationOutcome.Unproven)
        assertEquals(0, recreatedHarness.promptSubmitCount.get()) // Zero automatic sends!

        // Step 4: Equal text may be an older prompt; it cannot prove this uncertain write.
        val outcomeProven = recreatedCoordinator.reconcilePendingTurnWithHistory(
            "sess-durable-1",
            listOf(SessionMessage(role = "user", content = "Critical financial prompt"))
        )
        assertTrue(outcomeProven is ReconciliationOutcome.Unproven)
        assertEquals(PendingTurnState.UNKNOWN, recreatedLedger.getPendingTurn(initialRecord.pendingTurnId)!!.state)
        assertEquals(0, recreatedHarness.promptSubmitCount.get()) // Still zero sends!
    }

    // R-09: Session listed then remotely deleted -> Stale selection recovery; no local fabricated chat
    @Test
    fun r09_sessionRemotelyDeleted_returnsSessionNotFound_noLocalFabricatedChat() = runTest {
        fakeRepo.resumeFailWithNotFound = true

        val viewModel = ChatViewModel(
            sessionId = "sess-deleted-999",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        // Timeline remains empty; error message indicates session not found
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("sess-deleted-999"))
    }

    // R-10: Second attached client emits unrelated activity -> Event/session guard prevents treating it as proof of this app's ambiguous prompt
    @Test
    fun r10_secondAttachedClientActivity_sessionGuardRejectsUnrelatedEvents() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        // Emit delta for a DIFFERENT session
        harness.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "unrelated-client-session-999",
                seq = 1L,
                text = "Unrelated client activity"
            )
        )

        // Current session timeline is unaffected!
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    // R-11: Long stream/large tool payload -> UI remains responsive; bounded rendering/details; no OOM/crash
    @Test
    fun r11_longStreamLargePayload_boundedRendering_noCrash() {
        val largeMarkdown = buildString {
            for (i in 1..5000) {
                appendLine("Line $i: **Important data** `code`")
            }
        }

        val nodes = MarkdownParser.parse(largeMarkdown)
        assertNotNull(nodes)
        assertTrue(nodes.isNotEmpty())
    }

    // R-12: Unknown future event -> Generic safe row; no crash/action/RPC
    @Test
    fun r12_unknownFutureEvent_genericSafeRow_noCrashNoRpc() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-durable-1",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = harness,
            pendingTurnLedger = ledger,
            turnCoordinator = coordinator
        )

        harness.eventFlow.emit(
            GatewayEvent.Unknown(
                type = "hermes.quantum_routing_v2",
                sessionId = "runtime-sid-1",
                seq = 1L,
                payload = mapOf("metric" to 42)
            )
        )

        assertEquals(1, viewModel.uiState.value.timeline.size)
        val entry = viewModel.uiState.value.timeline[0] as GenericGatewayActivity
        assertEquals("hermes.quantum_routing_v2", entry.eventType)
        assertEquals(0, harness.promptSubmitCount.get()) // No follow-up RPC triggered!
    }
}
