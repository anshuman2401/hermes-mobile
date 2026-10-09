package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionSummary

/**
 * HOME-S7 (specs/plans/HOME-001-S7-search-filter-bar-plan-v1.md, FZ-1/FZ-2).
 *
 * Pure client-side session search filter over the already-loaded list.
 * No gateway/repository calls, no re-sorting, no deduplication — the S1
 * newest-first ordering (HOME-S1) is authoritative and must be preserved
 * exactly. Locale-independent matching: `String.contains(other, ignoreCase = true)`,
 * never `toLowerCase()` (HOME-S7 INV-8, JVM-deterministic).
 */
object SessionsSearchPolicy {

    /** Frozen minimum trimmed query length (HOME-S7 FZ-1). */
    const val MIN_QUERY_LENGTH = 1

    /**
     * Returns the sessions whose displayed title (with the frozen
     * "Session <id>" fallback used by the S5/S6 row rendering) or non-blank
     * preview contains [query].trim() case-insensitively, preserving the
     * input order. A blank-after-trim query returns the input list unchanged
     * (the same instance).
     */
    fun filterSessions(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            return sessions
        }
        return sessions.filter { session ->
            val displayedTitle = session.title.orEmpty().ifEmpty { "Session ${session.id}" }
            val preview = session.preview.orEmpty()
            displayedTitle.contains(other = trimmed, ignoreCase = true) ||
                preview.contains(other = trimmed, ignoreCase = true)
        }
    }
}
