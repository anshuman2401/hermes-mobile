package io.github.anshuman2401.hermesmobile.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayReplayReducerTest {

    private lateinit var reducer: GatewayReplayReducer

    @Before
    fun setUp() {
        reducer = GatewayReplayReducer()
    }

    @Test
    fun reduceLiveEvent_deduplicatesEqualOrLowerSequences() {
        val event1 = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 1L, text = "a")
        val event2 = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 2L, text = "b")
        val duplicate1 = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 1L, text = "a")
        val lowerEvent = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 2L, text = "b")
        val event3 = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 3L, text = "c")

        assertNotNull(reducer.reduceLiveEvent(event1))
        assertEquals(1L, reducer.getWatermark("sid-1"))

        assertNotNull(reducer.reduceLiveEvent(event2))
        assertEquals(2L, reducer.getWatermark("sid-1"))

        // Duplicate seq 1 and 2 must be dropped
        assertNull(reducer.reduceLiveEvent(duplicate1))
        assertNull(reducer.reduceLiveEvent(lowerEvent))
        assertEquals(2L, reducer.getWatermark("sid-1"))

        // Higher seq 3 accepted
        assertNotNull(reducer.reduceLiveEvent(event3))
        assertEquals(3L, reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceLiveEvent_passesThroughEventsWithoutSeq() {
        val unsequenced = GatewayEvent.SessionsChanged()
        val reduced = reducer.reduceLiveEvent(unsequenced)
        assertNotNull(reduced)
    }

    @Test
    fun updateEpoch_detectsEpochMismatchAndClearsWatermarks() {
        reducer.setWatermark("sid-1", 42L)
        assertTrue(reducer.updateEpoch("epoch-alpha").isUnchanged)
        assertEquals(42L, reducer.getWatermark("sid-1"))

        // Same epoch: valid
        assertTrue(reducer.updateEpoch("epoch-alpha").isUnchanged)
        assertEquals(42L, reducer.getWatermark("sid-1"))

        // Changed epoch: invalid, clears watermarks
        val changedResult = reducer.updateEpoch("epoch-beta")
        assertFalse(changedResult.isUnchanged)
        assertTrue(changedResult.changed)
        assertEquals("epoch-alpha", changedResult.oldEpoch)
        assertEquals("epoch-beta", changedResult.newEpoch)
        assertTrue(changedResult.affectedSessions.contains("sid-1"))
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_ordersAndDedupesEvents() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 1L)

        // Events out of order on wire with duplicate seq 1
        val batch = ReplayEventsResult(
            count = 3,
            epoch = "epoch-1",
            latestSeq = 3L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 3L, text = "three"),
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 1L, text = "one-dup"),
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 2L, text = "two")
            )
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch, commitWatermark = true)
        assertTrue(outcome is ReplayBatchOutcome.Accepted)
        val accepted = outcome as ReplayBatchOutcome.Accepted

        // Duplicate seq 1 was removed; remaining sorted ascending: [2, 3]
        assertEquals(2, accepted.events.size)
        assertEquals(2L, accepted.events[0].seq)
        assertEquals(3L, accepted.events[1].seq)
        assertEquals(3L, reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_epochMismatchDispatchesFallbackSignal() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 5L)

        val batch = ReplayEventsResult(
            count = 1,
            epoch = "epoch-2-mismatch",
            latestSeq = 6L,
            truncated = false,
            events = listOf(GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 6L, text = "x"))
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome is ReplayBatchOutcome.Fallback)
        val fallback = outcome as ReplayBatchOutcome.Fallback
        assertEquals(FallbackReason.EPOCH_MISMATCH, fallback.signal.reason)
        assertEquals("durable-1", fallback.signal.durableSessionId)
        assertEquals("sid-1", fallback.signal.runtimeSessionId)
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_truncatedDispatchesFallbackSignal() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 5L)

        val batch = ReplayEventsResult(
            count = 1,
            epoch = "epoch-1",
            latestSeq = 50L,
            truncated = true,
            events = listOf(GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 50L, text = "far-ahead"))
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome is ReplayBatchOutcome.Fallback)
        val fallback = outcome as ReplayBatchOutcome.Fallback
        assertEquals(FallbackReason.TRUNCATED, fallback.signal.reason)
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_gapFromNonzeroWatermark_dispatchesInvalidOrderOrGap() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 1L)

        // Watermark is 1, batch contains seq 2 and 4 (gap: missing 3)
        val batch = ReplayEventsResult(
            count = 2,
            epoch = "epoch-1",
            latestSeq = 4L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 2L, text = "two"),
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 4L, text = "four")
            )
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome is ReplayBatchOutcome.Fallback)
        val fallback = outcome as ReplayBatchOutcome.Fallback
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallback.signal.reason)
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_initialGapWithNoWatermark_dispatchesInvalidOrderOrGap() {
        reducer.updateEpoch("epoch-1")
        // No watermark set (effective watermark 0), batch starts at seq 3 (missing 1, 2)
        val batch = ReplayEventsResult(
            count = 1,
            epoch = "epoch-1",
            latestSeq = 3L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 3L, text = "three")
            )
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome is ReplayBatchOutcome.Fallback)
        val fallback = outcome as ReplayBatchOutcome.Fallback
        assertEquals(FallbackReason.INVALID_ORDER_OR_GAP, fallback.signal.reason)
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun reduceReplayBatch_nullSeqEvent_dispatchesDecodeError() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 1L)

        val batch = ReplayEventsResult(
            count = 1,
            epoch = "epoch-1",
            latestSeq = 2L,
            truncated = false,
            events = listOf(
                GatewayEvent.Unknown(type = "malformed", sessionId = "sid-1", seq = null)
            )
        )

        val outcome = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome is ReplayBatchOutcome.Fallback)
        val fallback = outcome as ReplayBatchOutcome.Fallback
        assertEquals(FallbackReason.DECODE_ERROR, fallback.signal.reason)
        assertNull(reducer.getWatermark("sid-1"))
    }

    @Test
    fun updateEpoch_awaitingRehydrationEnforcedUntilMarked() {
        reducer.updateEpoch("epoch-1")
        reducer.setWatermark("sid-1", 5L)

        // Epoch change to epoch-2
        val epochResult = reducer.updateEpoch("epoch-2")
        assertTrue(epochResult.changed)
        assertTrue(reducer.isAwaitingHistoryRehydration("sid-1"))
        assertNull(reducer.getWatermark("sid-1"))

        // Attempting replay while awaiting rehydration must fail with EPOCH_CHANGED_READY
        val batch = ReplayEventsResult(
            count = 1,
            epoch = "epoch-2",
            latestSeq = 1L,
            truncated = false,
            events = listOf(
                GatewayEvent.MessageDelta(sessionId = "sid-1", seq = 1L, text = "one")
            )
        )
        val outcome1 = reducer.reduceReplayBatch("sid-1", "durable-1", batch)
        assertTrue(outcome1 is ReplayBatchOutcome.Fallback)
        assertEquals(FallbackReason.EPOCH_CHANGED_READY, (outcome1 as ReplayBatchOutcome.Fallback).signal.reason)

        // Mark rehydrated
        reducer.markHistoryRehydrated("sid-1")
        assertFalse(reducer.isAwaitingHistoryRehydration("sid-1"))

        // Now replay is accepted
        val outcome2 = reducer.reduceReplayBatch("sid-1", "durable-1", batch, commitWatermark = true)
        assertTrue(outcome2 is ReplayBatchOutcome.Accepted)
        assertEquals(1, (outcome2 as ReplayBatchOutcome.Accepted).events.size)
        assertEquals(1L, reducer.getWatermark("sid-1"))
    }

    @Test
    fun evaluateLiveEvent_sessionScopedNullSeqWhileAwaitingRehydration_rejected() {
        reducer.markAwaitingHistoryRehydration("sid-1")

        // Session-scoped event with null seq while awaiting rehydration is rejected
        val sessionEventNullSeq = GatewayEvent.MessageDelta(sessionId = "sid-1", seq = null, text = "unsequenced")
        val outcome = reducer.evaluateLiveEvent(sessionEventNullSeq)
        assertTrue(outcome is LiveEventOutcome.Rejected)
        assertNull(reducer.checkLiveEvent(sessionEventNullSeq))
        assertNull(reducer.reduceLiveEvent(sessionEventNullSeq))
        assertNull(reducer.getWatermark("sid-1"))

        // Genuinely global event with no session ID is accepted
        val globalEvent = GatewayEvent.SessionsChanged(sessionId = null, seq = null)
        val globalOutcome = reducer.evaluateLiveEvent(globalEvent)
        assertTrue(globalOutcome is LiveEventOutcome.Accepted)
        assertNotNull(reducer.checkLiveEvent(globalEvent))
        assertNotNull(reducer.reduceLiveEvent(globalEvent))

        // After rehydration, session-scoped null seq event is accepted
        reducer.markHistoryRehydrated("sid-1")
        val rehydratedOutcome = reducer.evaluateLiveEvent(sessionEventNullSeq)
        assertTrue(rehydratedOutcome is LiveEventOutcome.Accepted)
    }
}
