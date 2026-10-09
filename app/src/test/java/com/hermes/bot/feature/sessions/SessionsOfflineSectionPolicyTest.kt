package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HOME-S9 behavior tests for the pure offline-cache section policy (frozen
 * plan `specs/plans/HOME-001-S9-offline-cache-section-plan-v1.md`, §6 File A).
 *
 * Single source of truth is the repository-provided `isOfflineCache` flag
 * (CHG-003-S5 contract); the policy itself is pure and JVM-deterministic.
 */
class SessionsOfflineSectionPolicyTest {

    @Test
    fun sectionLabel_connected_returnsRecentConversations() {
        // TEST-001
        assertEquals("Recent Conversations", SessionsOfflineSectionPolicy.sectionLabel(false))
        assertEquals(SessionsOfflineSectionPolicy.LABEL_LIVE, SessionsOfflineSectionPolicy.sectionLabel(false))
    }

    @Test
    fun sectionLabel_offlineCache_returnsCompactProvenance() {
        // TEST-002
        assertEquals("Saved sessions · offline", SessionsOfflineSectionPolicy.sectionLabel(true))
        assertEquals(SessionsOfflineSectionPolicy.LABEL_CACHED, SessionsOfflineSectionPolicy.sectionLabel(true))
    }
}
