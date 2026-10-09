package io.github.anshuman2401.hermesmobile.data.ledger

import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingInterruptQueueTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var cipherEngine: AesGcmCipherEngine
    private lateinit var vault: EncryptedVault
    private var now = 1_700_000_000_000L
    private lateinit var queue: PendingInterruptQueue

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("interrupt_queue_test")
        // One engine for the whole test: production Keystore keys persist by
        // alias across process restarts, so "process death" keeps the key.
        cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        vault = EncryptedVault(vaultDir, cipherEngine)
        queue = PendingInterruptQueue(vault) { now }
    }

    @Test
    fun enqueue_secondTapWhileHoldIsLive_isIdempotent() = runBlocking {
        assertTrue(queue.enqueue("sess-held", runtimeSessionId = "rt-1"))
        assertFalse(
            "Second enqueue for the same stored id must be a no-op while the hold is live",
            queue.enqueue("sess-held", runtimeSessionId = "rt-1")
        )
        assertEquals("Exactly one hold per stored session id", 1, vault.getPendingInterrupts().size)
        assertEquals(1, queue.getPendingHolds().size)
    }

    @Test
    fun enqueue_distinctSessions_holdIndependently() = runBlocking {
        val first = now
        assertTrue(queue.enqueue("sess-a"))
        now += 1_000L
        assertTrue(queue.enqueue("sess-b"))

        val holds = queue.dueForFlush(connectionGeneration = 1L)

        assertEquals(setOf("sess-a", "sess-b"), holds.map { it.storedSessionId }.toSet())
        assertEquals(first, holds.first { it.storedSessionId == "sess-a" }.enqueuedAtMs)
        assertEquals(now, holds.first { it.storedSessionId == "sess-b" }.enqueuedAtMs)
    }

    @Test
    fun dueForFlush_holdExactlyAtTtlIsLive_pastTtlIsDroppedAndPurged() = runBlocking {
        queue.enqueue("sess-ttl")
        now += 60_000L // exactly TTL: only "older than 60 s" drops a hold
        assertEquals(1, queue.dueForFlush(connectionGeneration = 1L).size)

        now += 1L // 60_001 ms old — strictly older than TTL
        assertEquals("Hold past TTL must be dropped at flush", 0, queue.dueForFlush(connectionGeneration = 2L).size)
        assertEquals("Expiry must delete the vault record", 0, vault.getPendingInterrupts().size)
        assertEquals("A later re-flush stays empty", 0, queue.dueForFlush(connectionGeneration = 3L).size)
    }

    @Test
    fun enqueue_afterExpiry_replacesHoldWithFreshTimestamp() = runBlocking {
        assertTrue(queue.enqueue("sess-replace"))
        now += 60_001L
        assertTrue("An expired hold must be replaced by a fresh enqueue", queue.enqueue("sess-replace"))

        val holds = vault.getPendingInterrupts()
        assertEquals("Still exactly one hold after replacement", 1, holds.size)
        assertEquals(now, holds[0].enqueuedAtMs)
        assertEquals(1, queue.dueForFlush(connectionGeneration = 1L).size)
    }

    @Test
    fun dueForFlush_attemptsAtMostOncePerConnectionGeneration() = runBlocking {
        queue.enqueue("sess-gen")
        assertEquals(1, queue.dueForFlush(connectionGeneration = 7L).size)
        assertEquals(
            "Repeat flush in the same generation must not re-deliver (FD-004 storm guard)",
            0,
            queue.dueForFlush(connectionGeneration = 7L).size
        )
        assertEquals(
            "A failed attempt re-holds: the next generation delivers again",
            1,
            queue.dueForFlush(connectionGeneration = 8L).size
        )
    }

    @Test
    fun ackSuccess_removesHold() = runBlocking {
        queue.enqueue("sess-ack")
        queue.dueForFlush(connectionGeneration = 1L)
        queue.ackSuccess("sess-ack")
        assertEquals(0, vault.getPendingInterrupts().size)
        assertEquals(0, queue.dueForFlush(connectionGeneration = 2L).size)
    }

    @Test
    fun ackFailure_keepsLiveHold_butDropsLapsedHold() = runBlocking {
        queue.enqueue("sess-fail")
        queue.dueForFlush(connectionGeneration = 1L)

        queue.ackFailure("sess-fail")
        assertEquals("Failed delivery must re-hold", 1, vault.getPendingInterrupts().size)

        now += 60_001L
        queue.ackFailure("sess-fail")
        assertEquals("A hold lapsed past TTL is dropped on ack", 0, vault.getPendingInterrupts().size)
    }

    @Test
    fun restoreAfterProcessDeath_newQueueOverFreshVaultSeesHold() = runBlocking {
        queue.enqueue("sess-restore", runtimeSessionId = "rt-snapshot")
        now += 5_000L
        // Process death: brand-new vault instance over the same directory, brand-new queue.
        val restored = PendingInterruptQueue(EncryptedVault(vaultDir, cipherEngine)) { now }

        val holds = restored.getPendingHolds()
        assertEquals(1, holds.size)
        assertEquals("sess-restore", holds[0].storedSessionId)
        assertEquals("rt-snapshot", holds[0].runtimeSessionId)
        assertEquals("Enqueue timestamp must round-trip", now - 5_000L, holds[0].enqueuedAtMs)
        assertEquals(1, restored.dueForFlush(connectionGeneration = 1L).size)
    }

    @Test
    fun restoreAfterProcessDeath_expiredHoldIsDroppedNotDelivered() = runBlocking {
        queue.enqueue("sess-dead")
        now += 60_001L
        val restored = PendingInterruptQueue(EncryptedVault(vaultDir, cipherEngine)) { now }

        assertEquals(0, restored.getPendingHolds().size)
        assertEquals(0, restored.dueForFlush(connectionGeneration = 1L).size)
        assertEquals(0, EncryptedVault(vaultDir, cipherEngine).getPendingInterrupts().size)
    }

    @Test
    fun clear_dropsAllHolds() = runBlocking {
        queue.enqueue("sess-clear-a")
        queue.enqueue("sess-clear-b")
        queue.clear()
        assertEquals(0, queue.getPendingHolds().size)
    }

    @Test
    fun recordToString_neverExposesSessionIdentifier() = runBlocking {
        queue.enqueue("sess-private")
        val hold = queue.getPendingHolds().single()
        assertFalse(
            "SEC-001: toString must not leak the session id",
            hold.toString().contains("sess-private")
        )
    }
}
