package com.hermes.bot.data.ledger

import com.hermes.bot.data.local.PendingTurnRecord
import com.hermes.bot.data.local.PendingTurnState
import com.hermes.bot.data.local.VaultStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Thread-safe turn coordinator ledger enforcing the pending-turn and outbox policy.
 * Guarantees that any ambiguous write (UNKNOWN) strictly prohibits automatic submission.
 * Follows specs/architecture/contracts/local-persistence-contract.md.
 */
class PendingTurnLedger(
    private val vaultStorage: VaultStorage
) {
    private val mutex = Mutex()

    /**
     * Records a new prompt turn before writing to the gateway socket.
     */
    suspend fun prepareTurn(
        storedSessionId: String,
        promptContent: String,
        runtimeSessionId: String? = null,
        edit: com.hermes.bot.data.local.PendingEditMetadata? = null
    ): PendingTurnRecord = mutex.withLock {
        val record = PendingTurnRecord(
            pendingTurnId = "turn-${UUID.randomUUID()}",
            storedSessionId = storedSessionId,
            runtimeSessionId = runtimeSessionId,
            promptContent = promptContent,
            state = PendingTurnState.PREPARED,
            edit = edit
        )
        vaultStorage.savePendingTurn(record)
        record
    }

    suspend fun markWriteAttempted(pendingTurnId: String): Unit = mutex.withLock {
        vaultStorage.updatePendingTurnState(pendingTurnId, PendingTurnState.WRITE_ATTEMPTED)
    }

    suspend fun markConfirmedAdmitted(pendingTurnId: String): Unit = mutex.withLock {
        vaultStorage.updatePendingTurnState(pendingTurnId, PendingTurnState.CONFIRMED_ADMITTED)
    }

    suspend fun markResolved(pendingTurnId: String): Unit = mutex.withLock {
        vaultStorage.updatePendingTurnState(pendingTurnId, PendingTurnState.RESOLVED)
    }

    /**
     * Marks a turn as UNKNOWN when transport drops or server state is ambiguous.
     * UNKNOWN state strictly prohibits automatic retry.
     */
    suspend fun markUnknown(pendingTurnId: String): Unit = mutex.withLock {
        vaultStorage.updatePendingTurnState(pendingTurnId, PendingTurnState.UNKNOWN)
    }

    /**
     * Evaluates whether a prompt turn is permitted to be automatically sent.
     * Prohibits transmission if the turn is in UNKNOWN state or expired-uncertain (>24h).
     */
    suspend fun canAutoSend(pendingTurnId: String): Boolean = mutex.withLock {
        val turn = vaultStorage.getPendingTurn(pendingTurnId) ?: return false
        turn.canAutoSend()
    }

    /**
     * Explicit user-initiated action to retry an ambiguous (UNKNOWN) prompt.
     * Strictly creates a new attempt with a fresh pendingTurnId; never reuses the old attempt.
     */
    suspend fun createNewAttemptFromAmbiguous(oldPendingTurnId: String): PendingTurnRecord? = mutex.withLock {
        val oldTurn = vaultStorage.getPendingTurn(oldPendingTurnId) ?: return null
        if (oldTurn.edit != null) return null
        // Mark old ambiguous attempt resolved/superseded
        vaultStorage.updatePendingTurnState(oldPendingTurnId, PendingTurnState.RESOLVED)

        // Generate brand new turn record with unique ID
        val newRecord = PendingTurnRecord(
            pendingTurnId = "turn-${UUID.randomUUID()}",
            storedSessionId = oldTurn.storedSessionId,
            runtimeSessionId = oldTurn.runtimeSessionId,
            promptContent = oldTurn.promptContent,
            state = PendingTurnState.PREPARED
        )
        vaultStorage.savePendingTurn(newRecord)
        newRecord
    }

    suspend fun getPendingTurn(pendingTurnId: String): PendingTurnRecord? = mutex.withLock {
        vaultStorage.getPendingTurn(pendingTurnId)
    }

    suspend fun getAllPendingTurns(): List<PendingTurnRecord> = mutex.withLock {
        vaultStorage.getAllPendingTurns()
    }

    suspend fun deletePendingTurn(pendingTurnId: String): Unit = mutex.withLock {
        vaultStorage.deletePendingTurn(pendingTurnId)
    }
}
