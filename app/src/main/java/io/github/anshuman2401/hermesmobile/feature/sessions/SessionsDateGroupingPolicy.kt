package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** A non-empty date section for the filtered Sessions list. */
data class SessionsDateGroup(
    val heading: String,
    val sessions: List<SessionSummary>
)

/**
 * Converts a wire epoch-seconds value without allowing malformed session data
 * to escape into the row renderer. Floor preserves the actual instant for
 * negative fractional seconds (for example, -0.5 is 1969-12-31T23:59:59.5Z).
 */
internal fun epochSecondsToInstantOrNull(epochSeconds: Double): Instant? {
    if (!epochSeconds.isFinite()) return null

    return try {
        val wholeSeconds = floor(epochSeconds)
        val nanos = ((epochSeconds - wholeSeconds) * NANOS_PER_SECOND)
            .toLong()
            .coerceIn(0L, NANOS_PER_SECOND - 1L)
        Instant.ofEpochSecond(wholeSeconds.toLong(), nanos)
    } catch (_: RuntimeException) {
        null
    }
}

/** Shared row/group timestamp: usable activity first, then usable creation. */
internal fun effectiveSessionTimestamp(session: SessionSummary, zone: ZoneId): Instant? {
    fun usableTimestamp(epochSeconds: Double?): Instant? = withRepresentableCalendarDate(
        startedAt = epochSeconds?.let(::epochSecondsToInstantOrNull),
        zone = zone,
        fallback = null
    ) { it }

    return usableTimestamp(session.lastActive) ?: usableTimestamp(session.startedAt)
}

/**
 * The screen's single relative-label entry point for wire epoch seconds.
 * The same calendar-representability policy used by grouping protects the
 * unchanged relative label from unrenderable Instant extremes.
 */
internal fun safeRelativeActivityLabel(now: Instant, startedAt: Instant?): String =
    withRepresentableCalendarDate(
        startedAt = startedAt,
        zone = ZoneId.systemDefault(),
        fallback = ""
    ) { representableStartedAt ->
        relativeActivityLabel(now, representableStartedAt)
    }

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * Pure local-calendar grouping for the consumer Sessions list.
 *
 * The input is already filtered by [SessionsSearchPolicy]. This policy keeps
 * host order and object identity within each section, never sorts or
 * deduplicates, and treats malformed timestamps as Earlier.
 */
object SessionsDateGroupingPolicy {
    const val TODAY = "Today"
    const val YESTERDAY = "Yesterday"
    const val PREVIOUS_7_DAYS = "Previous 7 days"
    const val EARLIER = "Earlier conversations"

    fun group(
        filteredSessions: List<SessionSummary>,
        now: Instant,
        zone: ZoneId
    ): List<SessionsDateGroup> {
        val today = now.atZone(zone).toLocalDate()
        val sessionsByHeading = linkedMapOf(
            TODAY to mutableListOf<SessionSummary>(),
            YESTERDAY to mutableListOf(),
            PREVIOUS_7_DAYS to mutableListOf(),
            EARLIER to mutableListOf()
        )

        filteredSessions.forEach { session ->
            val heading = headingFor(effectiveSessionTimestamp(session, zone), today, zone)
            sessionsByHeading.getValue(heading).add(session)
        }

        return sessionsByHeading.mapNotNull { (heading, sessions) ->
            if (sessions.isEmpty()) null else SessionsDateGroup(heading, sessions.toList())
        }
    }

    private fun headingFor(timestamp: Instant?, today: LocalDate, zone: ZoneId): String {
        val sessionDate = timestamp?.atZone(zone)?.toLocalDate()
            ?: return EARLIER

        return when {
            !sessionDate.isBefore(today) -> TODAY
            sessionDate == today.minusDays(1) -> YESTERDAY
            sessionDate >= today.minusDays(7) && sessionDate <= today.minusDays(2) -> {
                PREVIOUS_7_DAYS
            }
            else -> EARLIER
        }
    }
}

/**
 * A finite epoch value may construct a valid Instant whose local calendar
 * projection is outside LocalDate's supported range. Both presentation consumers use
 * this narrow guard, and the caller chooses the safe malformed-data fallback.
 */
private inline fun <T> withRepresentableCalendarDate(
    startedAt: Instant?,
    zone: ZoneId,
    fallback: T,
    render: (Instant) -> T
): T {
    if (startedAt == null) return fallback

    return try {
        startedAt.atZone(zone).toLocalDate()
        render(startedAt)
    } catch (_: DateTimeException) {
        fallback
    }
}
