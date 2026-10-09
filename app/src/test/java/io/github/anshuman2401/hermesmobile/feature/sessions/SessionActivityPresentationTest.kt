package io.github.anshuman2401.hermesmobile.feature.sessions

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** S3 witnesses use production grouping/conversion/label APIs, never a test resolver. */
class SessionActivityPresentationTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private val zone = ZoneId.of("UTC")
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun useFixedTimeZone() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun activityAndFallback() {
        val active = summary(
            startedAt = seconds("2026-08-01T12:00:00Z"),
            lastActive = seconds("2026-10-03T11:55:00Z") + 0.5
        )

        assertEquals(
            Instant.parse("2026-10-03T11:55:00.500Z"),
            effectiveSessionTimestamp(active, zone)
        )
        assertEquals(
            "4m ago",
            safeRelativeActivityLabel(now, effectiveSessionTimestamp(active, zone))
        )
        assertHeading(SessionsDateGroupingPolicy.TODAY, active)
        assertHeading(
            SessionsDateGroupingPolicy.YESTERDAY,
            active.copy(lastActive = null, startedAt = seconds("2026-10-02T10:00:00Z"))
        )
    }

    @Test
    fun activityTakesPrecedenceEvenWhenCreationIsNewer() {
        val session = summary(
            startedAt = seconds("2026-10-03T11:00:00Z"),
            lastActive = seconds("2026-10-02T10:00:00Z")
        )

        assertEquals(
            Instant.parse("2026-10-02T10:00:00Z"),
            effectiveSessionTimestamp(session, zone)
        )
        assertEquals(
            "Yesterday",
            safeRelativeActivityLabel(now, effectiveSessionTimestamp(session, zone))
        )
        assertHeading(SessionsDateGroupingPolicy.YESTERDAY, session)
    }

    @Test
    fun usableActivityDoesNotRequireUsableCreation() {
        for (startedAt in unusableTimestamps()) {
            val session = summary(startedAt, seconds("2026-10-03T11:55:00Z"))

            assertHeading(SessionsDateGroupingPolicy.TODAY, session)
            assertEquals(
                "5m ago",
                safeRelativeActivityLabel(now, effectiveSessionTimestamp(session, zone))
            )
        }
    }

    @Test
    fun missingOrUnusableActivityFallsBackToCreation() {
        for (lastActive in unusableTimestamps()) {
            val session = summary(seconds("2026-10-02T10:00:00Z"), lastActive)

            assertHeading(SessionsDateGroupingPolicy.YESTERDAY, session)
            assertEquals(
                Instant.parse("2026-10-02T10:00:00Z"),
                effectiveSessionTimestamp(session, zone)
            )
            assertEquals(
                "Yesterday",
                safeRelativeActivityLabel(now, effectiveSessionTimestamp(session, zone))
            )
        }
    }

    @Test
    fun neitherUsableKeepsExistingEarlierHeadingAndEmptyLabel() {
        for (timestamp in unusableTimestamps()) {
            val session = summary(timestamp, timestamp)

            assertHeading(SessionsDateGroupingPolicy.EARLIER, session)
            assertNull(effectiveSessionTimestamp(session, zone))
            assertEquals(
                "",
                safeRelativeActivityLabel(now, effectiveSessionTimestamp(session, zone))
            )
        }
    }

    @Test
    fun activityGroupsKeepExistingHeadingsAndInputOrderWithinSection() {
        val first = summary(seconds("2026-08-01T12:00:00Z"), seconds("2026-10-03T08:00:00Z"))
        val second = first.copy(id = "synthetic-second", lastActive = seconds("2026-10-03T11:00:00Z"))
        val previousWeek = first.copy(id = "synthetic-week", lastActive = seconds("2026-09-29T12:00:00Z"))
        val earlier = first.copy(id = "synthetic-earlier", lastActive = seconds("2026-09-01T12:00:00Z"))

        val groups = SessionsDateGroupingPolicy.group(listOf(first, earlier, second, previousWeek), now, zone)

        assertEquals(
            listOf(SessionsDateGroupingPolicy.TODAY, SessionsDateGroupingPolicy.PREVIOUS_7_DAYS,
                SessionsDateGroupingPolicy.EARLIER),
            groups.map { it.heading }
        )
        assertEquals(listOf(first, second), groups[0].sessions)
        assertSame(first, groups[0].sessions[0])
        assertSame(second, groups[0].sessions[1])
        assertSame(previousWeek, groups[1].sessions.single())
        assertSame(earlier, groups[2].sessions.single())
    }

    @Test
    fun activityBucketUsesSuppliedLocalCalendarZone() {
        val localZone = ZoneId.of("America/Los_Angeles")
        val session = summary(seconds("2026-08-01T12:00:00Z"), seconds("2026-10-03T06:30:00Z"))

        val groups = SessionsDateGroupingPolicy.group(
            listOf(session), Instant.parse("2026-10-03T07:30:00Z"), localZone
        )

        assertEquals(SessionsDateGroupingPolicy.YESTERDAY, groups.single().heading)
        assertSame(session, groups.single().sessions.single())
    }

    @Test
    fun existingRowLabelAndGroupingUseTheSameSummaryTimestampResolver() {
        // Supplement the grouping witnesses: Compose owns the row call site,
        // so verify it feeds the summary through the shared production policy.
        val screen = source("SessionsScreen.kt")
        val grouping = source("SessionsDateGroupingPolicy.kt")
        assertTrue(
            "the existing row label must consume the shared summary timestamp",
            Regex("""val label = safeRelativeActivityLabel\(\s*now,\s*effectiveSessionTimestamp\(session, zone\)\s*\)""")
                .containsMatchIn(screen)
        )
        assertTrue(
            "grouping must consume the same summary timestamp as the row",
            grouping.contains("headingFor(effectiveSessionTimestamp(session, zone), today, zone)")
        )
        assertTrue(screen.contains("if (label.isNotEmpty() && !largeText)") &&
            screen.contains("if (label.isNotEmpty() && largeText)"))
        assertTrue(screen.contains("text = label"))
    }

    private fun assertHeading(expected: String, session: SessionSummary) {
        val groups = SessionsDateGroupingPolicy.group(listOf(session), now, zone)
        assertEquals(expected, groups.single().heading)
        assertSame(session, groups.single().sessions.single())
    }

    private fun summary(startedAt: Double?, lastActive: Double?) = SessionSummary(
        id = "synthetic-session",
        title = "Synthetic conversation",
        preview = null,
        startedAt = startedAt,
        messageCount = 0,
        source = null,
        lastActive = lastActive
    )

    private fun seconds(value: String): Double = Instant.parse(value).epochSecond.toDouble()

    private fun unusableTimestamps(): List<Double?> = listOf(
        null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.MAX_VALUE, -Double.MAX_VALUE,
        // Valid Instants whose UTC calendar projection is unrepresentable.
        -31557014167219200.0, 31556889864403196.0
    )

    private fun source(name: String): String =
        File("src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/$name").let {
            if (it.exists()) it else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/sessions/$name")
        }.readText()
}
