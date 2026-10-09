package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * HOME-S7 policy tests (frozen plan v1, §7 File A).
 *
 * The search filter (FZ-1/FZ-2) is a pure function over the already-loaded,
 * S1-ordered (newest-first) session list: blank-after-trim query is an
 * identity passthrough; otherwise a session matches iff its displayed title
 * (with the frozen "Session <id>" fallback) or its non-blank preview contains
 * the trimmed query case-insensitively. No re-sorting, no new gateway calls.
 */
class SessionsSearchPolicyTest {

    private fun summary(
        id: String,
        startedAt: Double? = null,
        title: String? = null,
        preview: String? = null
    ) = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        startedAt = startedAt,
        messageCount = 0,
        source = null
    )

    @Test
    fun filterSessions_blankQuery_returnsSameListInstance() {
        // TEST-001: blank-after-trim query ("", "   ") => identity passthrough,
        // the SAME list instance (no copy), per FZ-1.
        val sessions = listOf(
            summary(id = "a", title = "Alpha"),
            summary(id = "b", title = "Beta")
        )

        assertSame(sessions, SessionsSearchPolicy.filterSessions(sessions, ""))
        assertSame(sessions, SessionsSearchPolicy.filterSessions(sessions, "   "))
    }

    @Test
    fun filterSessions_matchesTitleCaseInsensitive() {
        // TEST-002: query "meet" matches titles case-insensitively — a
        // lowercase title hit ("Weekly meet recap") and an uppercase title hit
        // ("MEETING PLAN", query lowercase vs title uppercase); a non-matching
        // title is excluded. (The plan's example title "Weekly sync notes"
        // does not contain "meet" as a substring; the frozen FZ-1 rule is
        // case-insensitive substring, so the fixture uses real hits.)
        val hitLower = summary(id = "1", title = "Weekly meet recap", preview = null)
        val hitUpper = summary(id = "2", title = "MEETING PLAN", preview = null)
        val miss = summary(id = "3", title = "Grocery list", preview = null)
        val sessions = listOf(hitLower, hitUpper, miss)

        val result = SessionsSearchPolicy.filterSessions(sessions, "meet")

        assertEquals(listOf(hitLower, hitUpper), result)
    }

    @Test
    fun filterSessions_matchesPreviewWhenTitleMisses() {
        // TEST-003: query matches only the preview text of a session whose
        // title does not contain it.
        val hit = summary(id = "1", title = "Project kickoff", preview = "Discussed the deploy pipeline")
        val miss = summary(id = "2", title = "Unrelated", preview = "Something else entirely")
        val sessions = listOf(hit, miss)

        val result = SessionsSearchPolicy.filterSessions(sessions, "deploy")

        assertEquals(listOf(hit), result)
    }

    @Test
    fun filterSessions_matchesRenderedTitleFallback() {
        // TEST-004: a session with title = null is filtered against the frozen
        // rendered fallback "Session <id>" — proving the user can search for
        // what is actually displayed (FZ-1).
        val hit = summary(id = "4a2", title = null, preview = null)
        val miss = summary(id = "9", title = null, preview = null)
        val sessions = listOf(hit, miss)

        val result = SessionsSearchPolicy.filterSessions(sessions, "session 4")

        assertEquals(listOf(hit), result)
    }

    @Test
    fun filterSessions_nullOrBlankPreviewNeverMatches() {
        // TEST-005: sessions with preview = null and preview = "" are excluded
        // by a non-blank query when their titles do not contain it — a
        // null/blank preview can never be the matching field. The title-only
        // path still works for null/blank-preview sessions, and a non-blank
        // preview containing the query matches.
        val nullPreview = summary(id = "1", title = "Storm log", preview = null)
        val emptyPreview = summary(id = "2", title = "Winter run", preview = "")
        val titleHit = summary(id = "3", title = "Aardvark notes", preview = null)
        val previewHit = summary(id = "4", title = "Orbit", preview = "plains habitat")
        val sessions = listOf(nullPreview, emptyPreview, titleHit, previewHit)

        val result = SessionsSearchPolicy.filterSessions(sessions, "a")

        assertEquals(listOf(titleHit, previewHit), result)
    }

    @Test
    fun filterSessions_preservesOrderAndIsPure() {
        // TEST-006: input ordered newest-first with interleaved matches and
        // non-matches => result is the matching sessions in identical order
        // (no re-sort); invoking twice returns equal lists (determinism);
        // MIN_QUERY_LENGTH is the frozen constant 1.
        val s1 = summary(id = "1", startedAt = 400.0, title = "Meet plan")
        val s2 = summary(id = "2", startedAt = 300.0, title = "Unrelated")
        val s3 = summary(id = "3", startedAt = 200.0, title = "Post-meet recap", preview = null)
        val s4 = summary(id = "4", startedAt = 100.0, title = "Diary")
        val sessions = listOf(s1, s2, s3, s4)

        val first = SessionsSearchPolicy.filterSessions(sessions, "meet")
        val second = SessionsSearchPolicy.filterSessions(sessions, "meet")

        assertEquals(listOf(s1, s3), first)
        assertEquals(first, second)
        assertEquals(1, SessionsSearchPolicy.MIN_QUERY_LENGTH)
    }

    @Test
    fun filterSessions_singleCharQuery_matches() {
        // TEST-006a: a single-character query (length == MIN_QUERY_LENGTH) is
        // a legal query: it matches "Banana plan" and excludes non-matches.
        val hit = summary(id = "1", title = "Banana plan")
        val miss = summary(id = "2", title = "Zesty run")
        val sessions = listOf(hit, miss)

        val result = SessionsSearchPolicy.filterSessions(sessions, "a")

        assertEquals(listOf(hit), result)
    }
}
