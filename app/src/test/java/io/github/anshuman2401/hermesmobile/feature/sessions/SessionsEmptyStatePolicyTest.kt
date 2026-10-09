package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOME-S10 pure behavior tests for `SessionsEmptyStatePolicy` (frozen plan
 * `specs/plans/HOME-001-S10-designed-empty-state-plan-v1.md`, TEST-001..TEST-004).
 *
 * JVM-deterministic: the policy is pure with no time, network, or randomness
 * inputs (same shape as `SessionsOfflineSectionPolicy`).
 */
class SessionsEmptyStatePolicyTest {

    @Test
    fun emptyStateVisible_zeroSessionsNotLoading_isTrue() {
        // TEST-001
        assertTrue(SessionsEmptyStatePolicy.isEmptyStateVisible(0, false))
    }

    @Test
    fun emptyStateVisible_zeroSessionsLoading_isFalse() {
        // TEST-002: loading still suppresses the empty state.
        assertFalse(SessionsEmptyStatePolicy.isEmptyStateVisible(0, true))
    }

    @Test
    fun emptyStateVisible_sessionsPresent_isFalse() {
        // TEST-003: sessions present -> never the designed empty state.
        assertFalse(SessionsEmptyStatePolicy.isEmptyStateVisible(1, false))
        assertFalse(SessionsEmptyStatePolicy.isEmptyStateVisible(3, true))
    }

    @Test
    fun emptyState_copy_matchesSpecSentence() {
        // TEST-004: frozen copy split renders the exact ux-navigation §2 sentence.
        assertEquals("No saved conversations yet.", SessionsEmptyStatePolicy.EMPTY_TITLE)
        assertEquals("Start a chat", SessionsEmptyStatePolicy.EMPTY_CTA)
        assertEquals(
            "No saved conversations yet. Start a chat",
            "${SessionsEmptyStatePolicy.EMPTY_TITLE} ${SessionsEmptyStatePolicy.EMPTY_CTA}".trim()
        )
    }
}
