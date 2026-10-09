package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.sortedByActivity
import java.time.Instant
import java.time.ZoneId

/** Local projection only: pin IDs never fabricate session rows. */
object SessionsPinnedPolicy {
    const val PINNED = "Pinned"

    fun groups(sessions: List<SessionSummary>, pins: Set<String>, pinnedOnly: Boolean,
               now: Instant, zone: ZoneId): List<SessionsDateGroup> {
        val pinned = sessions.filter { it.id in pins }.sortedByActivity()
        val first = if (pinned.isEmpty()) emptyList() else listOf(SessionsDateGroup(PINNED, pinned))
        return if (pinnedOnly) first else first + SessionsDateGroupingPolicy.group(sessions.filter { it.id !in pins }, now, zone)
    }
}
