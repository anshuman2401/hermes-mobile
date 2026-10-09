package com.hermes.bot.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM behavior tests for LoadEarlierTriggerPolicy (TASK-006 / REQ-001).
 * Covers TEST-001 and TEST-002.
 */
class LoadEarlierTriggerPolicyTest {

    @Test
    fun fires_only_on_false_to_true_top_edge_while_idle() {
        val policy = LoadEarlierTriggerPolicy(initialAtTop = false)

        // Still at top = false: must not fire
        val fireAtBottom = policy.onAtTopChanged(
            atTop = false,
            fetchInFlight = false,
            historyBeginningReached = false,
            isInitialLoading = false,
            timelineNonEmpty = true
        )
        assertFalse("Must not fire when not at top", fireAtBottom)

        // Edge false -> true: fires
        val fireEdge = policy.onAtTopChanged(
            atTop = true,
            fetchInFlight = false,
            historyBeginningReached = false,
            isInitialLoading = false,
            timelineNonEmpty = true
        )
        assertTrue("Must fire on false -> true edge while idle and ungated", fireEdge)

        // Held top position (atTop = true again): must not fire
        val fireHeld = policy.onAtTopChanged(
            atTop = true,
            fetchInFlight = false,
            historyBeginningReached = false,
            isInitialLoading = false,
            timelineNonEmpty = true
        )
        assertFalse("Held top position must not re-fire", fireHeld)

        // Leave top (atTop = false)
        val leaveTop = policy.onAtTopChanged(
            atTop = false,
            fetchInFlight = false,
            historyBeginningReached = false,
            isInitialLoading = false,
            timelineNonEmpty = true
        )
        assertFalse("Leaving top must not fire", leaveTop)

        // Return to top (edge false -> true): fires again
        val fireReturn = policy.onAtTopChanged(
            atTop = true,
            fetchInFlight = false,
            historyBeginningReached = false,
            isInitialLoading = false,
            timelineNonEmpty = true
        )
        assertTrue("Returning to top must fire again", fireReturn)
    }

    @Test
    fun does_not_fire_when_gated() {
        // Gate 1: fetchInFlight
        run {
            val policy = LoadEarlierTriggerPolicy(initialAtTop = false)
            val fired = policy.onAtTopChanged(
                atTop = true,
                fetchInFlight = true,
                historyBeginningReached = false,
                isInitialLoading = false,
                timelineNonEmpty = true
            )
            assertFalse("Must not fire while fetch is in flight", fired)
        }

        // Gate 2: historyBeginningReached
        run {
            val policy = LoadEarlierTriggerPolicy(initialAtTop = false)
            val fired = policy.onAtTopChanged(
                atTop = true,
                fetchInFlight = false,
                historyBeginningReached = true,
                isInitialLoading = false,
                timelineNonEmpty = true
            )
            assertFalse("Must not fire when history beginning reached", fired)
        }

        // Gate 3: isInitialLoading
        run {
            val policy = LoadEarlierTriggerPolicy(initialAtTop = false)
            val fired = policy.onAtTopChanged(
                atTop = true,
                fetchInFlight = false,
                historyBeginningReached = false,
                isInitialLoading = true,
                timelineNonEmpty = true
            )
            assertFalse("Must not fire during initial loading", fired)
        }

        // Gate 4: empty timeline
        run {
            val policy = LoadEarlierTriggerPolicy(initialAtTop = false)
            val fired = policy.onAtTopChanged(
                atTop = true,
                fetchInFlight = false,
                historyBeginningReached = false,
                isInitialLoading = false,
                timelineNonEmpty = false
            )
            assertFalse("Must not fire on empty timeline", fired)
        }

        // Gate 5: not at top (firstVisibleItemIndex > 0)
        run {
            val policy = LoadEarlierTriggerPolicy(initialAtTop = false)
            val fired = policy.onAtTopChanged(
                atTop = false,
                fetchInFlight = false,
                historyBeginningReached = false,
                isInitialLoading = false,
                timelineNonEmpty = true
            )
            assertFalse("Must not fire when firstVisibleItemIndex > 0", fired)
        }
    }
}
