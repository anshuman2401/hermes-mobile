package com.hermes.bot.data.turn

import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.*
import com.hermes.bot.data.session.*
import com.hermes.bot.feature.chat.ChatComposerSingleFlightTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QueuedPromptAuthorityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun queueOrCapturedOwnerRevocationDuringLedgerIoCannotWrite() = runTest {
        for (revokeQueue in listOf(false, true)) {
            val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val storage = object : VaultStorage by vault {
                override suspend fun savePendingTurn(record: PendingTurnRecord) {
                    vault.savePendingTurn(record); entered.complete(Unit); release.await()
                }
            }
            val ledger = PendingTurnLedger(storage); val adapter = FakeGatewayProtocolAdapter()
            var ownerCurrent = true; var queueCurrent = true
            val repository = object : SessionRepository by ChatComposerSingleFlightTest.SingleFlightSessionRepository() {
                override suspend fun capturePromptSubmissionAccess(storedId: String, runtimeId: String) = object : PromptSubmissionAccess {
                    override suspend fun isCurrent() = ownerCurrent
                    override suspend fun submit(prompt: String) = adapter.submitPrompt(runtimeId, prompt)
                }
            }
            val coordinator = TurnCoordinator(adapter, ledger, repository)
            val send = async { coordinator.submitPrompt("synthetic-session", "synthetic-runtime", "Synthetic queued prompt", queueCurrent = { queueCurrent }) }
            entered.await()
            if (revokeQueue) queueCurrent = false else ownerCurrent = false
            release.complete(Unit)
            assertTrue(send.await() is TurnSubmissionOutcome.ValidationFailed)
            assertEquals(0, adapter.submitPromptCallCount)
            assertEquals(PendingTurnState.RESOLVED, ledger.getAllPendingTurns().single().state)
        }
    }

    @Test fun completionRecoveryRequiresAdmissionIdleReplyAndCorrectSession() = runTest {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined)
        val ledger = PendingTurnLedger(vault)
        val adapter = FakeGatewayProtocolAdapter()
        val coordinator = TurnCoordinator(adapter, ledger)
        val turn = ledger.prepareTurn("synthetic-session", "Synthetic prompt", "synthetic-runtime")
        val messages = listOf(com.hermes.bot.data.gateway.SessionMessage("user", "Synthetic prompt"),
            com.hermes.bot.data.gateway.SessionMessage("assistant", "Synthetic reply"))
        val idle = com.hermes.bot.data.gateway.ResumeActivityEvidence.NON_RUNNING
        ledger.markUnknown(turn.pendingTurnId)
        assertFalse(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "synthetic-session", messages, idle))
        ledger.markConfirmedAdmitted(turn.pendingTurnId)
        assertFalse(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "other-session", messages, idle))
        assertFalse(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "synthetic-session", messages.take(1), idle))
        assertFalse(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "synthetic-session", messages,
            com.hermes.bot.data.gateway.ResumeActivityEvidence.RUNNING))
        assertFalse(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "synthetic-session", messages,
            com.hermes.bot.data.gateway.ResumeActivityEvidence.UNKNOWN))
        assertTrue(coordinator.reconcileCompletedQueueTurn(turn.pendingTurnId, "synthetic-session", messages, idle))
        assertEquals(PendingTurnState.CONFIRMED_ADMITTED, ledger.getPendingTurn(turn.pendingTurnId)?.state)
        assertEquals(0, adapter.submitPromptCallCount)
    }

    @Test fun missingCapturedQueueAuthorityFailsClosedWithoutPreparingOrWriting() = runTest {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined)
        val ledger = PendingTurnLedger(vault); val adapter = FakeGatewayProtocolAdapter()
        val coordinator = TurnCoordinator(adapter, ledger, ChatComposerSingleFlightTest.SingleFlightSessionRepository())
        assertTrue(coordinator.submitPrompt("synthetic-session", "synthetic-runtime", "Synthetic queued prompt", queueCurrent = { true }) is TurnSubmissionOutcome.ValidationFailed)
        assertEquals(0, adapter.submitPromptCallCount)
        assertTrue(ledger.getAllPendingTurns().isEmpty())
    }
}
