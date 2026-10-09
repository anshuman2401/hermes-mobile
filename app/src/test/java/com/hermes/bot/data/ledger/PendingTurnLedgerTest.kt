package com.hermes.bot.data.ledger

import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.PendingTurnRecord
import com.hermes.bot.data.local.PendingTurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingTurnLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vault: EncryptedVault
    private lateinit var ledger: PendingTurnLedger

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("ledger_test")
        vault = EncryptedVault(dir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        ledger = PendingTurnLedger(vault)
    }

    @Test
    fun promptLifecycle_preparedToWriteAttemptedToConfirmedAdmitted() = runBlocking {
        val record = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "What is the weather?"
        )

        assertEquals(PendingTurnState.PREPARED, record.state)
        assertTrue("Prepared turn can auto send", ledger.canAutoSend(record.pendingTurnId))

        ledger.markWriteAttempted(record.pendingTurnId)
        assertEquals(PendingTurnState.WRITE_ATTEMPTED, ledger.getPendingTurn(record.pendingTurnId)!!.state)

        ledger.markConfirmedAdmitted(record.pendingTurnId)
        val admitted = ledger.getPendingTurn(record.pendingTurnId)!!
        assertEquals(PendingTurnState.CONFIRMED_ADMITTED, admitted.state)
        assertTrue("Submission evidence flag set", admitted.submissionEvidenceFlag)

        ledger.markResolved(record.pendingTurnId)
        assertEquals(PendingTurnState.RESOLVED, ledger.getPendingTurn(record.pendingTurnId)!!.state)
    }

    @Test
    fun unknownState_strictlyProhibitsAutoSend() = runBlocking {
        val record = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "Uncertain prompt"
        )
        ledger.markWriteAttempted(record.pendingTurnId)

        // Socket drops or server sends unexpected close frame -> mark UNKNOWN
        ledger.markUnknown(record.pendingTurnId)

        val unknownTurn = ledger.getPendingTurn(record.pendingTurnId)!!
        assertEquals(PendingTurnState.UNKNOWN, unknownTurn.state)

        // HARD CONTRACT INVARIANT: UNKNOWN prohibits auto send
        assertFalse(
            "An ambiguous prompt in UNKNOWN state must NEVER be automatically resent",
            ledger.canAutoSend(record.pendingTurnId)
        )
    }

    @Test
    fun expiredUncertainTurn_prohibitsAutoSend() = runBlocking {
        val record = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "Old prompt"
        )

        // Manually age the record beyond 24 hours
        val agedRecord = record.copy(
            updatedAt = System.currentTimeMillis() - (25 * 3600 * 1000L)
        )
        vault.savePendingTurn(agedRecord)

        assertFalse(
            "Turns older than 24h must be marked expired-uncertain and prohibit auto-send",
            ledger.canAutoSend(record.pendingTurnId)
        )
    }

    @Test
    fun createNewAttemptFromAmbiguous_generatesBrandNewTurnIdAndResolvesOld() = runBlocking {
        val originalRecord = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "Ambiguous prompt"
        )
        ledger.markUnknown(originalRecord.pendingTurnId)

        // User explicitly taps resend
        val newAttempt = ledger.createNewAttemptFromAmbiguous(originalRecord.pendingTurnId)
        assertNotNull(newAttempt)
        assertNotEquals("New attempt must have a distinct pendingTurnId", originalRecord.pendingTurnId, newAttempt!!.pendingTurnId)
        assertEquals(PendingTurnState.PREPARED, newAttempt.state)
        assertEquals(originalRecord.promptContent, newAttempt.promptContent)

        // Verify old attempt was marked RESOLVED
        val resolvedOld = ledger.getPendingTurn(originalRecord.pendingTurnId)!!
        assertEquals(PendingTurnState.RESOLVED, resolvedOld.state)
    }

    @Test
    fun processRecreation_restoresPendingTurnLedgerFromEncryptedVault() = runBlocking {
        val record = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "Survives process recreation"
        )
        ledger.markUnknown(record.pendingTurnId)

        // Simulate app process restart by instantiating new ledger over existing vault
        val newLedger = PendingTurnLedger(vault)
        val restored = newLedger.getPendingTurn(record.pendingTurnId)

        assertNotNull(restored)
        assertEquals(PendingTurnState.UNKNOWN, restored!!.state)
        assertEquals("Survives process recreation", restored.promptContent)
        assertFalse(newLedger.canAutoSend(record.pendingTurnId))
    }
}
