package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.SessionMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM behavior tests for HistoryPrependMerger (TASK-007 / REQ-003 / REQ-004).
 * Covers TEST-003, TEST-004, TEST-007.
 */
class HistoryPrependMergeTest {

    @Test
    fun prepend_preserves_order_and_dedupes_by_row_identity() {
        val existingOldest = SessionMessage(
            role = "user",
            content = "Oldest known message",
            toolName = null,
            toolContext = null,
            reasoning = null
        )

        val freshMessages = listOf(
            SessionMessage(role = "system", content = "System prompt"),
            SessionMessage(role = "user", content = "Older row 1"),
            // Adjacent duplicate in older region: should be collapsed per TASK-007
            SessionMessage(role = "user", content = "Older row 1"),
            SessionMessage(role = "assistant", content = "Older row 2", reasoning = "think 2"),
            // Stop row: matches existingOldest by 5-tuple identity
            SessionMessage(role = "user", content = "Oldest known message"),
            SessionMessage(role = "assistant", content = "Newer message")
        )

        val result = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = existingOldest,
            freshMessages = freshMessages,
            messagesOmitted = false
        )

        assertFalse("Beginning should not be reached when older rows exist", result.beginningReached)
        assertFalse("messagesOmitted was false", result.nonExhaustive)
        assertEquals("Stop row must match existing oldest", existingOldest, result.stopRow)

        val expectedOlder = listOf(
            SessionMessage(role = "system", content = "System prompt"),
            SessionMessage(role = "user", content = "Older row 1"),
            SessionMessage(role = "assistant", content = "Older row 2", reasoning = "think 2")
        )
        assertEquals("Older rows must preserve order with adjacent duplicates collapsed", expectedOlder, result.olderRows)
    }

    @Test
    fun beginning_reached_when_fresh_window_adds_nothing() {
        val existingOldest = SessionMessage(role = "user", content = "Top of history")

        val freshMessages = listOf(
            SessionMessage(role = "user", content = "Top of history"),
            SessionMessage(role = "assistant", content = "Reply")
        )

        // Case A: messagesOmitted = false -> beginningReached = true, nonExhaustive = false
        val resultA = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = existingOldest,
            freshMessages = freshMessages,
            messagesOmitted = false
        )

        assertTrue("Zero older rows adds nothing -> beginningReached true", resultA.beginningReached)
        assertFalse("messagesOmitted is false -> nonExhaustive false", resultA.nonExhaustive)
        assertTrue("olderRows should be empty", resultA.olderRows.isEmpty())
        assertEquals(existingOldest, resultA.stopRow)

        // Case B: messagesOmitted = true -> beginningReached = true, nonExhaustive = true
        val resultB = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = existingOldest,
            freshMessages = freshMessages,
            messagesOmitted = true
        )

        assertTrue("Zero older rows -> beginningReached true", resultB.beginningReached)
        assertTrue("messagesOmitted is true -> nonExhaustive true", resultB.nonExhaustive)
        assertTrue("olderRows should be empty", resultB.olderRows.isEmpty())
        assertEquals(existingOldest, resultB.stopRow)
    }

    @Test
    fun no_fabricated_rows() {
        // Property 1: Empty fresh input yields empty output
        val emptyResult = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = SessionMessage(role = "user", content = "hello"),
            freshMessages = emptyList()
        )
        assertTrue(emptyResult.olderRows.isEmpty())
        assertFalse(emptyResult.beginningReached)
        assertNull(emptyResult.stopRow)

        // Property 2: Null existingOldestHistoryRow yields empty output
        val nullOldestResult = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = null,
            freshMessages = listOf(SessionMessage(role = "user", content = "m1"))
        )
        assertTrue(nullOldestResult.olderRows.isEmpty())
        assertFalse(nullOldestResult.beginningReached)
        assertNull(nullOldestResult.stopRow)

        // Property 3: Base row not found (RISK-002: shrunken window) -> 0 older rows, beginningReached false
        val missingBaseResult = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = SessionMessage(role = "user", content = "not-in-fresh"),
            freshMessages = listOf(
                SessionMessage(role = "assistant", content = "resp 1"),
                SessionMessage(role = "user", content = "resp 2")
            )
        )
        assertTrue(missingBaseResult.olderRows.isEmpty())
        assertFalse(missingBaseResult.beginningReached)
        assertNull(missingBaseResult.stopRow)

        // Property 4: Every output tuple appears in freshMessages; output count <= fresh count
        val freshFixture = listOf(
            SessionMessage(role = "system", content = "init"),
            SessionMessage(role = "user", content = "msg1"),
            SessionMessage(role = "assistant", content = "resp1"),
            SessionMessage(role = "user", content = "target")
        )
        val target = SessionMessage(role = "user", content = "target")
        val validResult = HistoryPrependMerger.merge(
            existingTimeline = emptyList(),
            existingOldestHistoryRow = target,
            freshMessages = freshFixture
        )
        assertTrue(validResult.olderRows.size <= freshFixture.size)
        for (row in validResult.olderRows) {
            assertTrue("Output row must be present in freshMessages", freshFixture.contains(row))
        }
    }
}
