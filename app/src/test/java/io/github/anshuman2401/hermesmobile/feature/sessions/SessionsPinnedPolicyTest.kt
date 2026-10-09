package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SessionsPinnedPolicyTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val zone = ZoneId.of("UTC")
    private fun row(id: String, ago: Long) = SessionSummary(id, id, null, now.minusSeconds(ago).epochSecond.toDouble(), 1, "android", lastActive = now.minusSeconds(ago).epochSecond.toDouble())

    @Test fun pinnedRowsComeFirstWithNoDuplicates_thenExistingDateGroups() {
        val input = listOf(row("today", 10), row("pin-old", 180000), row("yesterday", 86400), row("pin-new", 20))
        val groups = SessionsPinnedPolicy.groups(input, setOf("pin-old", "pin-new", "absent"), false, now, zone)
        assertEquals(listOf("Pinned", "Today", "Yesterday"), groups.map { it.heading })
        assertEquals(listOf("pin-new", "pin-old"), groups.first().sessions.map { it.id })
        val ids = groups.flatMap { it.sessions }.map { it.id }
        assertEquals(input.size, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun pinnedOnlyAndSearchNeverShowUnpinnedOrFabricatedRows() {
        val input = listOf(row("alpha", 10), row("beta", 86400))
        val searched = SessionsSearchPolicy.filterSessions(input, "beta")
        assertTrue(SessionsPinnedPolicy.groups(searched, setOf("alpha", "absent"), true, now, zone).isEmpty())
        assertEquals(listOf("alpha"), SessionsPinnedPolicy.groups(input, setOf("alpha"), true, now, zone).single().sessions.map { it.id })
        assertEquals(SessionsDateGroupingPolicy.group(input, now, zone), SessionsPinnedPolicy.groups(input, emptySet(), false, now, zone))
    }
}
