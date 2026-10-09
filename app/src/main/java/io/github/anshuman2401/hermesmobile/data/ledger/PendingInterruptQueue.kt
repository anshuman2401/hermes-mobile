package io.github.anshuman2401.hermesmobile.data.ledger

import io.github.anshuman2401.hermesmobile.data.local.PendingInterruptRecord
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable queue of undelivered `session.interrupt` holds (T3 plan FD-001..FD-005).
 *
 * Holds live in the encrypted [VaultStorage] so a stop tapped while disconnected
 * survives process death. All operations are mutex-serialized over the storage
 * (pattern: [PendingTurnLedger]).
 *
 * - FD-003: TTL of 60 s measured against the injected [nowMs] clock; expiry
 *   deletes the record silently — never sent, never logged.
 * - FD-004: enqueue is idempotent per stored session id while a hold is live,
 *   and [dueForFlush] delivers at most once per connection generation.
 */
class PendingInterruptQueue(
    private val vaultStorage: VaultStorage,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var lastFlushGeneration = Long.MIN_VALUE

    /**
     * Records a hold for [storedSessionId]. Idempotent (FD-004): returns false
     * when a non-expired hold already exists for that id; an expired hold is
     * purged and replaced with a fresh one.
     *
     * [runtimeSessionId] is an enqueue-time snapshot only — never trusted after
     * process death (RISK-004).
     */
    suspend fun enqueue(storedSessionId: String, runtimeSessionId: String? = null): Boolean =
        mutex.withLock {
            val now = nowMs()
            val existing = vaultStorage.getPendingInterrupts()
                .firstOrNull { it.storedSessionId == storedSessionId }
            if (existing != null) {
                if (!existing.isExpired(now)) {
                    return@withLock false
                }
                vaultStorage.deletePendingInterrupt(storedSessionId)
            }
            vaultStorage.savePendingInterrupt(
                PendingInterruptRecord(
                    storedSessionId = storedSessionId,
                    runtimeSessionId = runtimeSessionId,
                    enqueuedAtMs = now
                )
            )
            true
        }

    /**
     * Returns the non-expired holds to deliver for [connectionGeneration],
     * purging expired holds silently (FD-003). At most one flush evaluation per
     * connection generation (FD-004): repeat calls with the same or an older
     * generation return empty, so a flapping connection or re-entrant flush
     * cannot multiply sends. Returned holds stay in the vault until
     * [ackSuccess] (or expiry) — a failure simply re-holds them.
     */
    suspend fun dueForFlush(connectionGeneration: Long): List<PendingInterruptRecord> =
        mutex.withLock {
            val now = nowMs()
            val live = vaultStorage.getPendingInterrupts().filter { hold ->
                if (hold.isExpired(now)) {
                    vaultStorage.deletePendingInterrupt(hold.storedSessionId)
                    false
                } else {
                    true
                }
            }
            if (connectionGeneration <= lastFlushGeneration) {
                return@withLock emptyList()
            }
            lastFlushGeneration = connectionGeneration
            live
        }

    /** The host acknowledged the interrupt: the hold is no longer needed. */
    suspend fun ackSuccess(storedSessionId: String): Unit = mutex.withLock {
        vaultStorage.deletePendingInterrupt(storedSessionId)
    }

    /**
     * Delivery failed: the hold survives untouched (FD-004 re-hold) unless its
     * TTL lapsed in the meantime, in which case it is dropped silently.
     */
    suspend fun ackFailure(storedSessionId: String): Unit = mutex.withLock {
        val hold = vaultStorage.getPendingInterrupts()
            .firstOrNull { it.storedSessionId == storedSessionId }
            ?: return@withLock
        if (hold.isExpired(nowMs())) {
            vaultStorage.deletePendingInterrupt(storedSessionId)
        }
    }

    /** Read API for callers that must observe holds without consuming a generation. */
    suspend fun getPendingHolds(): List<PendingInterruptRecord> = mutex.withLock {
        val now = nowMs()
        vaultStorage.getPendingInterrupts().filter { hold ->
            if (hold.isExpired(now)) {
                vaultStorage.deletePendingInterrupt(hold.storedSessionId)
                false
            } else {
                true
            }
        }
    }

    /** Drops every hold immediately (reset paths; PR-2 flush uses [ackSuccess] per hold). */
    suspend fun clear(): Unit = mutex.withLock {
        vaultStorage.clearPendingInterrupts()
    }
}
