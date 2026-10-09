package com.hermes.bot.data.turn

import com.hermes.bot.data.gateway.*
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.*
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MessageEditSubmissionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun ledger() = PendingTurnLedger(EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = Dispatchers.Unconfined))
    private class Access : MessageEditAccess {
        override val ownerKey = "a".repeat(64)
        var current = true
        var running = false
        var stopWorks = true
        var target = 12L
        var calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var result: GatewayResult<PromptSubmitResult> = GatewayResult.Success(PromptSubmitResult("streaming", 30))
        override suspend fun isCurrent() = current
        override suspend fun snapshot(): GatewayResult<SessionResumeResult> {
            calls += "read"
            return GatewayResult.Success(SessionResumeResult("runtime", "stored", 2,
                listOf(SessionMessage("user", "Same text", rowId = 11), SessionMessage("user", "Same text", rowId = target)),
                running = running, activityEvidence = if (running) ResumeActivityEvidence.RUNNING else ResumeActivityEvidence.NON_RUNNING))
        }
        override suspend fun interrupt(): GatewayResult<InterruptSessionResult> {
            calls += "stop"
            if (stopWorks) running = false
            return GatewayResult.Success(InterruptSessionResult(true))
        }
        override suspend fun submit(text: String, rowId: Long): GatewayResult<PromptSubmitResult> {
            calls += "write:$rowId"
            gate?.await()
            return result
        }
    }
    @Test fun stopsThenReadsIdleAndTargetsExactDuplicateRow() = runTest {
        val ledger = ledger(); val access = Access().apply { running = true }
        val coord = TurnCoordinator(FakeGatewayProtocolAdapter(), ledger)
        assertTrue(coord.editMessage("stored", "runtime", 12, "New text", access) is MessageEditOutcome.Admitted)
        assertEquals(listOf("read", "stop", "read", "write:12"), access.calls)
        val record = ledger.getAllPendingTurns().single()
        assertEquals(12L, record.edit?.rowId)
        assertEquals(PendingTurnState.CONFIRMED_ADMITTED, record.state)
        assertFalse(record.canAutoSend())
    }
    @Test fun unknownCannotMatchDuplicateTextOrUseNormalRetry() = runTest {
        val ledger = ledger(); val access = Access().apply { result = GatewayResult.Failure(GatewayFailure.Transport("synthetic")) }
        val coord = TurnCoordinator(FakeGatewayProtocolAdapter(), ledger)
        val result = coord.editMessage("stored", "runtime", 12, "New text", access) as MessageEditOutcome.Unknown
        assertEquals(PendingTurnState.UNKNOWN, ledger.getPendingTurn(result.pendingId)?.state)
        assertTrue(coord.reconcilePendingTurnWithHistory("stored", listOf(SessionMessage("user", "New text"))) is ReconciliationOutcome.Unproven)
        assertTrue(coord.retryAmbiguousTurn(result.pendingId, "runtime") is TurnSubmissionOutcome.ValidationFailed)
        assertEquals(1, access.calls.count { it.startsWith("write") })
    }
    @Test fun busyIsDefiniteRejectionAndNeverRetries() = runTest {
        val ledger = ledger(); val access = Access().apply { result = GatewayResult.Failure(GatewayFailure.Rpc(4009, "busy")) }
        assertTrue(TurnCoordinator(FakeGatewayProtocolAdapter(), ledger).editMessage("stored", "runtime", 12, "New text", access) is MessageEditOutcome.Refused)
        assertEquals(PendingTurnState.RESOLVED, ledger.getAllPendingTurns().single().state)
        assertEquals(1, access.calls.count { it.startsWith("write") })
    }
    @Test fun timeoutStaleTargetAndChangedOwnerNeverWrite() = runTest {
        for (kind in listOf("timeout", "target", "owner")) {
            val ledger = ledger(); val access = Access().apply {
                if (kind == "timeout") { running = true; stopWorks = false }
                if (kind == "target") target = 14
                if (kind == "owner") current = false
            }
            assertTrue(TurnCoordinator(FakeGatewayProtocolAdapter(), ledger).editMessage("stored", "runtime", 12, "New text", access) is MessageEditOutcome.Refused)
            assertFalse(access.calls.any { it.startsWith("write") })
            assertTrue(ledger.getAllPendingTurns().isEmpty())
        }
    }
    @Test fun cancellationAfterWritePersistsUnknownAndConcurrentEditIsRefused() = runTest {
        val ledger = ledger(); val access = Access().apply { gate = CompletableDeferred() }
        val coord = TurnCoordinator(FakeGatewayProtocolAdapter(), ledger)
        val first = launch { coord.editMessage("stored", "runtime", 12, "New text", access) }
        runCurrent()
        assertTrue(access.calls.any { it.startsWith("write") })
        assertTrue(coord.editMessage("stored", "runtime", 12, "Different", access) is MessageEditOutcome.Refused)
        first.cancelAndJoin()
        assertEquals(PendingTurnState.UNKNOWN, ledger.getAllPendingTurns().single().state)
    }
}
