package com.hermes.bot.data.turn

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayReconnectManager
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.PendingTurnState
import com.hermes.bot.data.session.S4ActivityFixture
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.feature.chat.UserMessage
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TurnActivityWriteBoundaryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun bumpBeforeRpcReturns() = runTest(main.testDispatcher) {
        val f = fixture()
        val vm = f.sessionsViewModel()
        vm.uiState.first { !it.isLoading }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.adapter.onSubmitPrompt = { entered.complete(Unit); release.await() }
        val send = async { f.submit() }
        try {
            entered.await()
            vm.uiState.first { it.sessions.single { row -> row.id == S4ActivityFixture.ID }.lastActive == f.now }
            runCurrent()
            val pending = f.vault.getAllPendingTurns().single()
            assertEquals(PendingTurnState.WRITE_ATTEMPTED, pending.state)
            assertEquals(1, f.adapter.submitPromptCallCount)
            assertFalse(send.isCompleted)
            assertEquals(S4ActivityFixture.RUNTIME, f.adapter.lastSubmittedSessionId)
            assertEquals("Synthetic prompt", f.adapter.lastSubmittedText)
            assertEquals(100.0, f.cache().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
            assertEquals(S4ActivityFixture.ID, vm.uiState.value.sessions.first().id)
            assertEquals(100.0, vm.uiState.value.sessions.first().lastActive)
        } finally {
            release.complete(Unit)
            send.await()
            close(vm)
        }
    }

    @Test fun blankAndPreWriteLedgerFailuresDoNotBumpOrWrite() = runTest(main.testDispatcher) {
        for (failure in listOf("blank", "prepare", "mark")) {
            val f = fixture()
            val before = f.cache()
            if (failure == "prepare") f.onPrepare = { throw IllegalStateException("Synthetic ledger unavailable") }
            if (failure == "mark") f.onMark = { throw IllegalStateException("Synthetic ledger unavailable") }
            val result = runCatching { f.coordinator.submitPrompt(S4ActivityFixture.ID, S4ActivityFixture.RUNTIME,
                if (failure == "blank") "  " else "Synthetic prompt") }
            if (failure == "blank") assertTrue(result.getOrThrow() is TurnSubmissionOutcome.ValidationFailed)
            else assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(before, f.cache())
            assertEquals(0, f.adapter.submitPromptCallCount)
            assertEquals(50.0, f.repository.listSessions(true).getOrThrow().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
        }
    }

    @Test fun ledgerMarkMustCompleteBeforeActivityAppears() = runTest(main.testDispatcher) {
        val f = fixture()
        val before = f.cache()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onMark = { entered.complete(Unit); release.await() }
        val send = async { f.submit() }
        entered.await()
        assertEquals(PendingTurnState.PREPARED, f.vault.getAllPendingTurns().single().state)
        assertEquals(before, f.cache())
        assertEquals(0, f.adapter.submitPromptCallCount)
        release.complete(Unit)
        assertTrue(send.await() is TurnSubmissionOutcome.Admitted)
        assertEquals(100.0, f.cache().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
    }

    @Test fun unknownNeverResends() = runTest(main.testDispatcher) {
        val f = fixture()
        f.adapter.submitPromptResult = GatewayResult.Failure(GatewayFailure.SubmissionUnknown("Synthetic ambiguous write"))
        val unknown = f.submit() as TurnSubmissionOutcome.AmbiguousUnknown
        val ledger = PendingTurnLedger(f.storage)
        assertEquals(PendingTurnState.UNKNOWN, ledger.getPendingTurn(unknown.pendingTurnId)!!.state)
        assertFalse(ledger.canAutoSend(unknown.pendingTurnId))
        // Exercise actual reconnect manager + authoritative resume/reconciliation, never a fake resend policy.
        val auth = ReconnectNoDuplicateTest.FakeReconnectAuthRepository()
        val ticketMinted = CompletableDeferred<Unit>()
        val observedAuth = object : com.hermes.bot.data.auth.AuthRepository by auth {
            override suspend fun mintWebSocketTicket(origin: com.hermes.bot.data.origin.CanonicalOrigin): Result<com.hermes.bot.data.gateway.GatewayTicket> =
                auth.mintWebSocketTicket(origin).also { ticketMinted.complete(Unit) }
        }
        val reconnectScope = CoroutineScope(SupervisorJob() + main.testDispatcher)
        try {
            val manager = GatewayReconnectManager(f.adapter, observedAuth, reconnectScope, ioDispatcher = main.testDispatcher)
            manager.configureOrigin(S4ActivityFixture.ORIGIN)
            runCurrent() // Install the CONNECTED observer before the automatic reconnect trigger.
            f.adapter.connectionStatus.value = ConnectionStatus.OFFLINE
            ticketMinted.await()
            assertEquals(1, auth.ticketMintCount.get())
            f.adapter.connectionStatus.value = ConnectionStatus.CONNECTED
            f.repository.resumeSession(S4ActivityFixture.ID).getOrThrow()
            assertTrue(f.coordinator.reconcilePendingTurnWithHistory(S4ActivityFixture.ID, emptyList()) is ReconciliationOutcome.Unproven)
            assertEquals(1, f.adapter.submitPromptCallCount)
            val kept = f.cache().sessions.single { it.id == S4ActivityFixture.ID }.lastActive
            f.now = 200.0
            val retry = f.coordinator.retryAmbiguousTurn(unknown.pendingTurnId, S4ActivityFixture.RUNTIME)
                as TurnSubmissionOutcome.AmbiguousUnknown
            assertNotEquals(unknown.pendingTurnId, retry.pendingTurnId)
            assertEquals(2, f.adapter.submitPromptCallCount)
            assertEquals(PendingTurnState.RESOLVED, ledger.getPendingTurn(unknown.pendingTurnId)!!.state)
            assertEquals(PendingTurnState.UNKNOWN, ledger.getPendingTurn(retry.pendingTurnId)!!.state)
            assertEquals(100.0, kept)
            assertEquals(200.0, f.cache().sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
        } finally { reconnectScope.cancel() }
    }

    @Test fun metadataReadAndSaveFailuresDoNotPreventPromptWriteOrUiBump() = runTest(main.testDispatcher) {
        for (operation in listOf("read", "save")) {
            val f = fixture()
            val vm = f.sessionsViewModel()
            try {
                vm.uiState.first { !it.isLoading }
                val fail: suspend () -> Unit = { throw IllegalStateException("Synthetic metadata unavailable") }
                if (operation == "read") f.onCacheRead = fail else f.onCacheSave = fail
                assertTrue(f.submit() is TurnSubmissionOutcome.Admitted)
                vm.uiState.first { it.sessions.single { row -> row.id == S4ActivityFixture.ID }.lastActive == f.now }
                runCurrent()
                assertEquals(1, f.adapter.submitPromptCallCount)
                assertEquals(PendingTurnState.CONFIRMED_ADMITTED, f.vault.getAllPendingTurns().single().state)
                assertEquals(100.0, vm.uiState.value.sessions.single { it.id == S4ActivityFixture.ID }.lastActive)
            } finally { close(vm) }
        }
    }

    @Test fun metadataCancellationPropagatesBeforeGatewayWrite() = runTest(main.testDispatcher) {
        val f = fixture()
        f.onCacheRead = { throw CancellationException("Synthetic cancellation") }
        val cancelled = runCatching { f.submit() }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        assertEquals(0, f.adapter.submitPromptCallCount)
        assertEquals(PendingTurnState.WRITE_ATTEMPTED, f.vault.getAllPendingTurns().single().state)
    }

    @Test fun coordinatorNullPreviewDoesNotManufactureActivity() = runTest(main.testDispatcher) {
        val f = fixture()
        val before = f.cache()
        val chat = ChatViewModel(S4ActivityFixture.ID, sessionRepository = f.repository,
            gatewayProtocolAdapter = f.adapter)
        try {
            chat.uiState.first { !it.isInitialLoading }
            assertFalse(chat.uiState.value.isInitialLoading)
            assertEquals(S4ActivityFixture.RUNTIME, chat.uiState.value.runtimeSessionId)
            chat.updateDraft("Synthetic preview prompt")
            assertTrue("The preview fixture must pass every existing send eligibility gate", chat.uiState.value.isComposerReady)
            chat.submitPrompt()
            chat.uiState.first { state -> state.timeline.any { it is UserMessage && it.text == "Synthetic preview prompt" } }
            assertTrue(chat.uiState.value.timeline.any { it is UserMessage && it.text == "Synthetic preview prompt" })
            assertEquals(0, f.adapter.submitPromptCallCount)
            assertTrue(f.vault.getAllPendingTurns().isEmpty())
            assertEquals(before, f.cache())
        } finally { close(chat) }
    }

    private suspend fun fixture() = S4ActivityFixture(temp, main.testDispatcher).apply { initialize(null) }
    private suspend fun close(vm: androidx.lifecycle.ViewModel) {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        ViewModelStore().apply { put("test", vm); clear() }
        job.join()
    }
}
