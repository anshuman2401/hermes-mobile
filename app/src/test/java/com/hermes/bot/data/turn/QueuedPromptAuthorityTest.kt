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

    @Test fun missingCapturedQueueAuthorityFailsClosedWithoutPreparingOrWriting() = runTest {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined)
        val ledger = PendingTurnLedger(vault); val adapter = FakeGatewayProtocolAdapter()
        val coordinator = TurnCoordinator(adapter, ledger, ChatComposerSingleFlightTest.SingleFlightSessionRepository())
        assertTrue(coordinator.submitPrompt("synthetic-session", "synthetic-runtime", "Synthetic queued prompt", queueCurrent = { true }) is TurnSubmissionOutcome.ValidationFailed)
        assertEquals(0, adapter.submitPromptCallCount)
        assertTrue(ledger.getAllPendingTurns().isEmpty())
    }
}
