package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** List-state behavior retained when the redundant resume-card policy is removed. */
class SessionsResumeCardPolicyTest {
    private fun summary(id: String, title: String?, preview: String? = null) = SessionSummary(
        id = id, title = title, preview = preview, startedAt = null, messageCount = 0, source = null
    )

    @Test
    fun loadedListKeepsEachSessionAndOrderWithoutSearch() {
        val newest = summary("new", "Latest")
        val oldest = summary("old", "Earlier")
        val sessions = listOf(newest, oldest)
        assertFalse(SessionsEmptyStatePolicy.isEmptyStateVisible(sessions.size, false))
        assertSame(sessions, SessionsSearchPolicy.filterSessions(sessions, ""))
        assertEquals(listOf("new", "old"), SessionsSearchPolicy.filterSessions(sessions, "").map { it.id })
    }

    @Test
    fun searchedListHasOnlyMatchingRowsAndNoPinnedNewest() {
        val newest = summary("new", "Latest")
        val match = summary("old", "Earlier", "Preview needle")
        val filtered = SessionsSearchPolicy.filterSessions(listOf(newest, match), "needle")
        assertEquals(listOf(match), filtered)
        assertTrue(SessionsSearchPolicy.filterSessions(listOf(newest, match), "unmatched").isEmpty())
    }

    @Test
    fun emptyAndPreviewOnlyListsRetainExistingPolicies() {
        assertTrue(SessionsEmptyStatePolicy.isEmptyStateVisible(0, false))
        assertFalse(SessionsEmptyStatePolicy.isEmptyStateVisible(0, true))
        val previewOnly = listOf(summary("preview", null, "Visible preview"))
        assertEquals(previewOnly, SessionsSearchPolicy.filterSessions(previewOnly, "visible"))
    }
}
