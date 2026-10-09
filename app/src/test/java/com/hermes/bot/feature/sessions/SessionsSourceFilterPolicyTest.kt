package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SessionsSourceFilterPolicyTest {
    private fun row(id: String, source: String?, activity: Double = 100.0) =
        SessionSummary(id, id, null, activity, 1, source, lastActive = activity)

    @Test fun defaultIncludesEverySource_andExplicitNoneIncludesNothing() {
        val rows = listOf(row("a", "android"), row("b", "new-source"), row("c", null))
        assertSame(rows, SessionsSourceFilterPolicy.filter(rows, null))
        assertTrue(SessionsSourceFilterPolicy.filter(rows, emptySet()).isEmpty())
    }

    @Test fun optionsUseCompleteListWithCounts_sortedValuesAndUnspecifiedLast() {
        val rows = listOf(row("a", " tui "), row("b", "android"), row("c", null),
            row("d", " \t"), row("e", "android"), row("f", "Unspecified"))
        val options = SessionsSourceFilterPolicy.options(rows)
        assertEquals(listOf("Unspecified", "android", "tui", ""), options.map { it.key })
        assertEquals(listOf(1, 2, 1, 2), options.map { it.count })
        assertEquals(listOf("c", "d"), SessionsSourceFilterPolicy.filter(rows, setOf("")).map { it.id })
        assertEquals(listOf("f"), SessionsSourceFilterPolicy.filter(rows, setOf("Unspecified")).map { it.id })
    }

    @Test fun multiselectIsUnion_sourceSearchAndPinnedAreIntersection_withoutNewRows() {
        val now = Instant.ofEpochSecond(1000)
        val rows = listOf(row("alpha-new", "android", 300.0), row("beta", "tui", 400.0),
            row("alpha-old", "tui", 200.0), row("alpha-other", "other", 500.0))
        val selected = SessionsSourceFilterPolicy.filter(rows, setOf("android", "tui"))
        assertEquals(rows.take(3), selected)
        val searched = SessionsSearchPolicy.filterSessions(selected, "alpha")
        val groups = SessionsPinnedPolicy.groups(searched, setOf("alpha-old", "alpha-new", "absent"), true,
            now, ZoneId.of("UTC"))
        assertEquals(listOf("alpha-new", "alpha-old"), groups.single().sessions.map { it.id })
        assertEquals(rows.take(3), selected) // Input and order remain unchanged.
        assertEquals(3, selected.map { it.id }.distinct().size)
    }

    @Test fun togglingAllIndividualChoicesAndUnknownOption() {
        val options = SessionsSourceFilterPolicy.options(listOf(row("a", "android"), row("b", "tui")))
        val androidOnly = SessionsSourceFilterPolicy.toggle(null, "tui", options)
        assertEquals(setOf("android"), androidOnly)
        assertEquals(emptySet<String>(), SessionsSourceFilterPolicy.toggle(androidOnly, "android", options))
        assertNull(SessionsSourceFilterPolicy.toggle(androidOnly, "tui", options))
        assertEquals(androidOnly, SessionsSourceFilterPolicy.toggle(androidOnly, "unknown", options))
    }

    @Test fun refreshAddsNewSourcesOnlyUnderAll_andDoesNotEraseExplicitSelections() {
        val rows = listOf(row("a", "android"), row("b", "tui"), row("c", "future"))
        assertEquals(3, SessionsSourceFilterPolicy.filter(rows, null).size)
        assertEquals(listOf("a"), SessionsSourceFilterPolicy.filter(rows, setOf("android")).map { it.id })
        assertTrue(SessionsSourceFilterPolicy.filter(rows, setOf("no-longer-present")).isEmpty())
    }

    @Test fun largeCataloguePreservesIdentityOrderAndExactCase() {
        val rows = (0 until 10000).map { row("synthetic-$it", if (it % 2 == 0) "android" else "Android") }
        assertEquals(2, SessionsSourceFilterPolicy.options(rows).size)
        val selected = SessionsSourceFilterPolicy.filter(rows, setOf("android"))
        assertEquals(5000, selected.size)
        selected.forEachIndexed { index, item -> assertSame(rows[index * 2], item) }
    }
}
