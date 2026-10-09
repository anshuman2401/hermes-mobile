package io.github.anshuman2401.hermesmobile.data.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * Result of an epoch update evaluation.
 */
data class EpochUpdateResult(
    val isUnchanged: Boolean,
    val oldEpoch: String?,
    val newEpoch: String?,
    val affectedSessions: Set<String>
) {
    val changed: Boolean get() = !isUnchanged
}

/**
 * Replay and sequence reducer enforcing ascending ordering, deduplication, and continuity per session.
 * Dispatches history fallback signals on epoch change, truncated buffers, sequence gaps, or decode errors.
 */
class GatewayReplayReducer {

    private val watermarks = ConcurrentHashMap<String, Long>()
    private val awaitingHistoryRehydration = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var currentReplayEpoch: String? = null
        private set

    fun getWatermark(sessionId: String): Long? = watermarks[sessionId]

    fun setWatermark(sessionId: String, seq: Long) {
        watermarks[sessionId] = seq
    }

    fun clearWatermark(sessionId: String) {
        watermarks.remove(sessionId)
    }

    fun clearAllWatermarks() {
        watermarks.clear()
    }

    fun isAwaitingHistoryRehydration(sessionId: String): Boolean {
        return awaitingHistoryRehydration.contains(sessionId)
    }

    fun markHistoryRehydrated(sessionId: String) {
        awaitingHistoryRehydration.remove(sessionId)
    }

    fun markAwaitingHistoryRehydration(sessionId: String) {
        awaitingHistoryRehydration.add(sessionId)
    }

    /**
     * Updates the replay epoch from gateway.ready.
     * Returns an [EpochUpdateResult] indicating whether the epoch matches or changed,
     * including the set of affected session IDs whose watermarks were cleared.
     */
    fun updateEpoch(newEpoch: String?): EpochUpdateResult {
        val old = currentReplayEpoch
        currentReplayEpoch = newEpoch
        if (old != null && newEpoch != null && old != newEpoch) {
            val affected = watermarks.keys.toSet()
            awaitingHistoryRehydration.addAll(affected)
            clearAllWatermarks()
            return EpochUpdateResult(
                isUnchanged = false,
                oldEpoch = old,
                newEpoch = newEpoch,
                affectedSessions = affected
            )
        }
        return EpochUpdateResult(
            isUnchanged = true,
            oldEpoch = old,
            newEpoch = newEpoch,
            affectedSessions = emptySet()
        )
    }

    /**
     * Evaluates an inbound live event enforcing contiguous sequence order and deduplication.
     * When a sequence gap is detected (seq > watermark + 1), clears watermark and signals Fallback.
     */
    fun evaluateLiveEvent(event: GatewayEvent, durableSessionId: String? = null): LiveEventOutcome {
        val sid = event.sessionId
        if (sid != null && awaitingHistoryRehydration.contains(sid)) {
            return LiveEventOutcome.Rejected("Session $sid is awaiting history rehydration")
        }
        val seq = event.seq
        if (sid == null || seq == null) {
            return LiveEventOutcome.Accepted(event)
        }
        if (seq <= 0L) {
            return LiveEventOutcome.Deduplicated
        }
        val currentWatermark = watermarks[sid]
        if (currentWatermark != null) {
            if (seq <= currentWatermark) {
                // Deduplicate
                return LiveEventOutcome.Deduplicated
            }
            if (seq > currentWatermark + 1L) {
                // Gap detected: seq > currentWatermark + 1
                clearWatermark(sid)
                awaitingHistoryRehydration.add(sid)
                return LiveEventOutcome.Fallback(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sid,
                        reason = FallbackReason.INVALID_ORDER_OR_GAP,
                        details = "Live sequence gap detected: expected ${currentWatermark + 1L} but received $seq (watermark: $currentWatermark)"
                    )
                )
            }
        }
        return LiveEventOutcome.Accepted(event)
    }

    /**
     * Checks an inbound live event without committing the sequence watermark.
     * Returns the event if it should be emitted, or null if deduplicated (seq <= watermark) or invalid/gap.
     */
    fun checkLiveEvent(event: GatewayEvent): GatewayEvent? {
        return when (val outcome = evaluateLiveEvent(event)) {
            is LiveEventOutcome.Accepted -> outcome.event
            is LiveEventOutcome.Deduplicated, is LiveEventOutcome.Fallback, is LiveEventOutcome.Rejected -> null
        }
    }

    /**
     * Commits sequence watermark only after downstream observable acceptance.
     */
    fun commitWatermark(sessionId: String, seq: Long) {
        if (seq <= 0L) return
        watermarks.compute(sessionId) { _, current ->
            maxOf(current ?: 0L, seq)
        }
    }

    /**
     * Ingests an inbound live event and atomically updates the watermark.
     * Returns the event if it should be emitted, or null if it was deduplicated (seq <= watermark) or gap.
     */
    fun reduceLiveEvent(event: GatewayEvent): GatewayEvent? {
        val outcome = evaluateLiveEvent(event)
        if (outcome !is LiveEventOutcome.Accepted) return null
        val accepted = outcome.event
        val sid = accepted.sessionId
        val seq = accepted.seq
        if (sid != null && seq != null && seq > 0L) {
            commitWatermark(sid, seq)
        }
        return accepted
    }

    /**
     * Evaluates a replay batch from session.events.since.
     * Enforces epoch matching, non-truncated buffers, rehydration requirements,
     * ascending continuity (previous + 1), deduplication, and watermark bounds.
     */
    fun reduceReplayBatch(
        sessionId: String,
        durableSessionId: String?,
        result: ReplayEventsResult,
        lastSeenFloor: Long? = null,
        commitWatermark: Boolean = false
    ): ReplayBatchOutcome {
        if (result.epoch != null && currentReplayEpoch != null && result.epoch != currentReplayEpoch) {
            clearWatermark(sessionId)
            awaitingHistoryRehydration.add(sessionId)
            return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.EPOCH_MISMATCH,
                    details = "Replay epoch ${result.epoch} does not match gateway ready epoch $currentReplayEpoch"
                )
            )
        }

        if (awaitingHistoryRehydration.contains(sessionId)) {
            clearWatermark(sessionId)
            return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.EPOCH_CHANGED_READY,
                    details = "Session $sessionId must be rehydrated via session.resume before post-restart replay"
                )
            )
        }

        if (result.truncated) {
            clearWatermark(sessionId)
            awaitingHistoryRehydration.add(sessionId)
            return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.TRUNCATED,
                    details = "Replay buffer was truncated on server"
                )
            )
        }

        // Validate that all events carry a positive finite seq and belong to this session
        for (ev in result.events) {
            if (ev.seq == null || ev.seq!! <= 0L) {
                clearWatermark(sessionId)
                awaitingHistoryRehydration.add(sessionId)
                return ReplayBatchOutcome.Fallback(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sessionId,
                        reason = FallbackReason.DECODE_ERROR,
                        details = "Replay event missing positive sequence number or failed decode"
                    )
                )
            }
            if (ev.sessionId != null && ev.sessionId != sessionId) {
                clearWatermark(sessionId)
                awaitingHistoryRehydration.add(sessionId)
                return ReplayBatchOutcome.Fallback(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sessionId,
                        reason = FallbackReason.INVALID_ORDER_OR_GAP,
                        details = "Cross-session replay event detected: expected $sessionId but found ${ev.sessionId}"
                    )
                )
            }
        }

        val currentWatermark = watermarks[sessionId] ?: 0L
        val effectiveWatermark = maxOf(currentWatermark, lastSeenFloor ?: 0L)

        // Deduplicate seq <= effectiveWatermark
        val remaining = result.events.filter { (it.seq ?: Long.MIN_VALUE) > effectiveWatermark }

        // Cheap count inconsistency check if raw events count does not match
        if (result.count >= 0 && result.count != result.events.size) {
            clearWatermark(sessionId)
            awaitingHistoryRehydration.add(sessionId)
            return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.INVALID_ORDER_OR_GAP,
                    details = "Replay count inconsistency: reported ${result.count} but parsed ${result.events.size}"
                )
            )
        }

        if (remaining.isEmpty()) {
            if (result.latestSeq != null && result.latestSeq < effectiveWatermark) {
                clearWatermark(sessionId)
                awaitingHistoryRehydration.add(sessionId)
                return ReplayBatchOutcome.Fallback(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sessionId,
                        reason = FallbackReason.INVALID_ORDER_OR_GAP,
                        details = "Replay latest_seq ${result.latestSeq} is behind effective watermark $effectiveWatermark"
                    )
                )
            }
            return ReplayBatchOutcome.Accepted(emptyList())
        }

        // Sort ascending by sequence number
        val sorted = remaining.sortedBy { it.seq ?: Long.MAX_VALUE }

        var expectedSeq = effectiveWatermark + 1
        for (ev in sorted) {
            val seq = ev.seq ?: return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.DECODE_ERROR,
                    details = "Replay event missing sequence number"
                )
            )

            if (seq != expectedSeq) {
                clearWatermark(sessionId)
                awaitingHistoryRehydration.add(sessionId)
                return ReplayBatchOutcome.Fallback(
                    HistoryFallbackSignal(
                        durableSessionId = durableSessionId,
                        runtimeSessionId = sessionId,
                        reason = FallbackReason.INVALID_ORDER_OR_GAP,
                        details = "Sequence gap detected: expected $expectedSeq but received $seq (floor: $effectiveWatermark)"
                    )
                )
            }
            expectedSeq = seq + 1
        }

        val lastSeq = sorted.last().seq!!
        if (result.latestSeq != null && result.latestSeq != lastSeq) {
            clearWatermark(sessionId)
            awaitingHistoryRehydration.add(sessionId)
            return ReplayBatchOutcome.Fallback(
                HistoryFallbackSignal(
                    durableSessionId = durableSessionId,
                    runtimeSessionId = sessionId,
                    reason = FallbackReason.INVALID_ORDER_OR_GAP,
                    details = "latest_seq ${result.latestSeq} does not match last parsed event seq $lastSeq"
                )
            )
        }

        if (commitWatermark) {
            val finalWatermark = result.latestSeq ?: lastSeq
            watermarks[sessionId] = maxOf(currentWatermark, finalWatermark)
        }

        return ReplayBatchOutcome.Accepted(sorted)
    }
}

sealed interface LiveEventOutcome {
    data class Accepted(val event: GatewayEvent) : LiveEventOutcome
    data object Deduplicated : LiveEventOutcome
    data class Fallback(val signal: HistoryFallbackSignal) : LiveEventOutcome
    data class Rejected(val reason: String = "") : LiveEventOutcome
}

sealed interface ReplayBatchOutcome {
    data class Accepted(val events: List<GatewayEvent>) : ReplayBatchOutcome
    data class Fallback(val signal: HistoryFallbackSignal) : ReplayBatchOutcome
}
