package com.hermes.bot.feature.sessions

import com.hermes.bot.data.gateway.SessionSummary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SessionsDateGroupingPolicyTest {
    private val now = Instant.parse("2026-09-28T00:30:00Z")
    private val utc = ZoneId.of("UTC")

    private fun summary(id: String, startedAt: Double?) = SessionSummary(
        id = id,
        title = "Session $id",
        preview = null,
        startedAt = startedAt,
        messageCount = 0,
        source = null
    )

    private fun epochSeconds(date: String, zone: ZoneId = utc): Double =
        Instant.parse(date).atZone(zone).toEpochSecond().toDouble()

    @Test
    fun group_classifiesCalendarBoundariesAndFutureInHeadingOrder() {
        val today = summary("today", epochSeconds("2026-09-28T00:00:00Z"))
        val future = summary("future", epochSeconds("2026-09-29T12:00:00Z"))
        val yesterday = summary("yesterday", epochSeconds("2026-09-27T23:59:59Z"))
        val dayTwo = summary("day-2", epochSeconds("2026-09-26T12:00:00Z"))
        val daySeven = summary("day-7", epochSeconds("2026-09-21T12:00:00Z"))
        val dayEight = summary("day-8", epochSeconds("2026-09-20T12:00:00Z"))

        val groups = SessionsDateGroupingPolicy.group(
            listOf(daySeven, today, dayEight, yesterday, future, dayTwo),
            now,
            utc
        )

        assertEquals(
            listOf(
                SessionsDateGroupingPolicy.TODAY,
                SessionsDateGroupingPolicy.YESTERDAY,
                SessionsDateGroupingPolicy.PREVIOUS_7_DAYS,
                SessionsDateGroupingPolicy.EARLIER
            ),
            groups.map { it.heading }
        )
        assertEquals(listOf("today", "future"), groups[0].sessions.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].sessions.map { it.id })
        assertEquals(listOf("day-7", "day-2"), groups[2].sessions.map { it.id })
        assertEquals(listOf("day-8"), groups[3].sessions.map { it.id })
    }

    @Test
    fun group_usesTheSuppliedZoneAcrossUtcMidnight() {
        val zone = ZoneId.of("America/Los_Angeles")
        val beforeLocalMidnight = summary("before", epochSeconds("2026-09-28T06:30:00Z"))
        val afterLocalMidnight = summary("after", epochSeconds("2026-09-28T07:30:00Z"))

        val groups = SessionsDateGroupingPolicy.group(
            listOf(beforeLocalMidnight, afterLocalMidnight),
            Instant.parse("2026-09-28T07:30:00Z"),
            zone
        )

        assertEquals(
            listOf(SessionsDateGroupingPolicy.TODAY, SessionsDateGroupingPolicy.YESTERDAY),
            groups.map { it.heading }
        )
        assertEquals(listOf("after"), groups[0].sessions.map { it.id })
        assertEquals(listOf("before"), groups[1].sessions.map { it.id })
    }

    @Test
    fun group_invalidNullAndOutOfRangeTimestampsAreEarlierWithoutThrowing() {
        val sessions = listOf(
            summary("null", null),
            summary("nan", Double.NaN),
            summary("positive-infinity", Double.POSITIVE_INFINITY),
            summary("negative-infinity", Double.NEGATIVE_INFINITY),
            summary("too-large", 1.0e40),
            summary("too-small", -1.0e40),
            summary("max-finite", Double.MAX_VALUE),
            summary("min-finite", -Double.MAX_VALUE)
        )

        val groups = SessionsDateGroupingPolicy.group(sessions, now, utc)

        assertEquals(listOf(SessionsDateGroupingPolicy.EARLIER), groups.map { it.heading })
        assertEquals(sessions.map { it.id }, groups.single().sessions.map { it.id })
    }

    @Test
    fun group_preservesInputIdentityAndOrderWithoutDeduplication() {
        val first = summary("same", epochSeconds("2026-09-28T01:00:00Z"))
        val second = summary("same", epochSeconds("2026-09-28T02:00:00Z"))
        val third = summary("older", epochSeconds("2026-09-27T01:00:00Z"))

        val groups = SessionsDateGroupingPolicy.group(listOf(first, second, third), now, utc)

        assertSame(first, groups[0].sessions[0])
        assertSame(second, groups[0].sessions[1])
        assertEquals(listOf("same", "same"), groups[0].sessions.map { it.id })
        assertSame(third, groups[1].sessions.single())
    }

    @Test
    fun group_consumesOnlyFilteredSubsetAndOmitsEmptyHeadings() {
        val includedToday = summary("included", epochSeconds("2026-09-28T01:00:00Z"))
        val filteredOutYesterday = summary("filtered-out", epochSeconds("2026-09-27T01:00:00Z"))
        val filtered = SessionsSearchPolicy.filterSessions(
            listOf(includedToday, filteredOutYesterday),
            "included"
        )

        val groups = SessionsDateGroupingPolicy.group(filtered, now, utc)

        assertEquals(listOf(SessionsDateGroupingPolicy.TODAY), groups.map { it.heading })
        assertEquals(listOf("included"), groups.flatMap { it.sessions }.map { it.id })
        assertEquals(1, groups.flatMap { it.sessions }.size)
    }

    @Test
    fun group_handlesNegativeFractionalEpochSecondsByCalendarDate() {
        val epochBefore1970 = summary("epoch-before", -0.5)

        val groups = SessionsDateGroupingPolicy.group(listOf(epochBefore1970), now, utc)

        assertEquals(listOf(SessionsDateGroupingPolicy.EARLIER), groups.map { it.heading })
        assertSame(epochBefore1970, groups.single().sessions.single())
    }

    @Test
    fun group_instantMinAtUtc_isEarlierWithoutThrowing() {
        val instantMin = summary("instant-min", -31557014167219200.0)

        val groups = SessionsDateGroupingPolicy.group(listOf(instantMin), now, utc)

        assertEquals(listOf(SessionsDateGroupingPolicy.EARLIER), groups.map { it.heading })
        assertSame(instantMin, groups.single().sessions.single())
    }

    @Test
    fun group_validInstantNearMaxInPositiveOffsetZone_isEarlierWithoutThrowing() {
        val nearInstantMax = summary("near-instant-max", 31556889864403196.0)
        val zone = ZoneId.of("Pacific/Kiritimati")

        assertEquals(
            Instant.ofEpochSecond(31556889864403196L),
            epochSecondsToInstantOrNull(nearInstantMax.startedAt!!)
        )
        val groups = SessionsDateGroupingPolicy.group(listOf(nearInstantMax), now, zone)

        assertEquals(listOf(SessionsDateGroupingPolicy.EARLIER), groups.map { it.heading })
        assertSame(nearInstantMax, groups.single().sessions.single())
    }

    @Test
    fun safeRelativeActivityLabel_instantMinAtUtc_isEmptyWithoutThrowing() {
        withDefaultTimeZone(ZoneId.of("UTC")) {
            assertEquals(
                "",
                safeRelativeActivityLabel(
                    now,
                    epochSecondsToInstantOrNull(-31557014167219200.0)
                )
            )
        }
    }

    @Test
    fun safeRelativeActivityLabel_validInstantNearMaxInPositiveOffsetZone_isEmpty() {
        withDefaultTimeZone(ZoneId.of("Pacific/Kiritimati")) {
            assertEquals(
                "",
                safeRelativeActivityLabel(
                    now,
                    epochSecondsToInstantOrNull(31556889864403196.0)
                )
            )
        }
    }

    @Test
    fun safeRelativeActivityLabel_validOrdinaryTimestampsKeepExistingLabels() {
        withDefaultTimeZone(ZoneId.of("UTC")) {
            val threeHoursAgo = now.minus(Duration.ofHours(3))
            val yearAgo = now.minus(Duration.ofDays(400))

            assertEquals("3h ago", safeRelativeActivityLabel(now, threeHoursAgo))
            assertEquals(
                relativeActivityLabel(now, yearAgo),
                safeRelativeActivityLabel(now, yearAgo)
            )
        }
    }

    @Test
    fun safeRelativeActivityLabel_nullAndMalformedValuesAreEmpty() {
        assertEquals("", safeRelativeActivityLabel(now, null))
        assertEquals("", safeRelativeActivityLabel(now, epochSecondsToInstantOrNull(Double.NaN)))
        assertEquals("", safeRelativeActivityLabel(now, epochSecondsToInstantOrNull(Double.MAX_VALUE)))
    }

    @Test
    fun sharedEpochConversion_preservesNegativeFractionAndRejectsInvalidRanges() {
        assertEquals(
            Instant.ofEpochSecond(-1L, 500_000_000L),
            epochSecondsToInstantOrNull(-0.5)
        )
        assertEquals(null, epochSecondsToInstantOrNull(Double.NaN))
        assertEquals(null, epochSecondsToInstantOrNull(Double.POSITIVE_INFINITY))
        assertEquals(null, epochSecondsToInstantOrNull(Double.MAX_VALUE))
    }

    @Test
    fun group_usesLocalCalendarDateAcrossDstTransition() {
        val newYork = ZoneId.of("America/New_York")
        val beforeFallBack = summary("before-fall-back", epochSeconds("2026-11-01T05:30:00Z"))

        val groups = SessionsDateGroupingPolicy.group(
            listOf(beforeFallBack),
            Instant.parse("2026-11-02T05:30:00Z"),
            newYork
        )

        assertEquals(listOf(SessionsDateGroupingPolicy.YESTERDAY), groups.map { it.heading })
        assertSame(beforeFallBack, groups.single().sessions.single())
    }

    private fun withDefaultTimeZone(zone: ZoneId, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
