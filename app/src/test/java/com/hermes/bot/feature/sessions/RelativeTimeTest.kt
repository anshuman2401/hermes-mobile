package com.hermes.bot.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * JVM unit tests for [relativeActivityLabel].
 * HOME-S2 frozen plan v1.
 */
class RelativeTimeTest {

    private val now = Instant.parse("2026-09-20T12:00:00Z")

    @Test
    fun relativeActivityLabel_nullStartedAt_returnsEmptyString() {
        assertEquals("", relativeActivityLabel(now, null))
    }

    @Test
    fun relativeActivityLabel_futureTimestamp_clampsToJustNow() {
        val startedAt = now.plus(Duration.ofHours(3))
        assertEquals("just now", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_59s_returnsJustNow() {
        val startedAt = now.minus(Duration.ofSeconds(59))
        assertEquals("just now", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_61s_returnsJustNow() {
        val startedAt = now.minus(Duration.ofSeconds(61))
        assertEquals("just now", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_59m_returnsMinutesAgo() {
        val startedAt = now.minus(Duration.ofMinutes(59))
        assertEquals("59m ago", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_61m_returnsOneHourAgo() {
        val startedAt = now.minus(Duration.ofMinutes(61))
        assertEquals("1h ago", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_23h_returnsHoursAgo() {
        val startedAt = now.minus(Duration.ofHours(23))
        assertEquals("23h ago", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_25h_returnsYesterday() {
        val startedAt = now.minus(Duration.ofHours(25))
        assertEquals("Yesterday", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_47h_returnsYesterday() {
        val startedAt = now.minus(Duration.ofHours(47))
        assertEquals("Yesterday", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_49h_returnsTwoDaysAgo() {
        val startedAt = now.minus(Duration.ofHours(49))
        assertEquals("2d ago", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_6d_returnsDaysAgo() {
        val startedAt = now.minus(Duration.ofDays(6))
        assertEquals("6d ago", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_8d_returnsMonthDayDate() {
        val startedAt = now.minus(Duration.ofDays(8))
        assertEquals("Sep 12", relativeActivityLabel(now, startedAt))
    }

    @Test
    fun relativeActivityLabel_400d_returnsMonthDayYearDate() {
        val startedAt = now.minus(Duration.ofDays(400))
        assertEquals("Aug 16, 2025", relativeActivityLabel(now, startedAt))
    }
}
