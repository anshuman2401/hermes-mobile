package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SessionSummaryCache freshness predicate (CHG-003-S5).
 *
 * Contract (local-persistence-contract.md + slice plan CHG-003-S5):
 * the 7-day freshness window is enforced at the repository boundary — expired cache is
 * NOT returned as list data and behaves as a failure/offline state instead.
 */
class SessionSummaryCacheFreshnessTest {

    private fun summary(id: String) = SessionSummary(id, "T", "P", 0.0, 1, "android")

    @Test
    fun isFresh_cacheJustSynced_isTrue() {
        val now = System.currentTimeMillis() / 1000
        val cache = SessionSummaryCache(sessions = listOf(summary("a")), syncTimeEpochSeconds = now)
        assertTrue(cache.isFresh(currentEpochSeconds = now))
        assertFalse(cache.isStale(currentEpochSeconds = now))
    }

    @Test
    fun isFresh_exactlyAtSevenDayBoundary_isTrue() {
        // Inclusive boundary: exactly MAX_RETENTION_SECONDS after sync is still fresh.
        val syncTime = 1_000_000L
        val cache = SessionSummaryCache(sessions = listOf(summary("a")), syncTimeEpochSeconds = syncTime)
        val atBoundary = syncTime + SessionSummaryCache.MAX_RETENTION_SECONDS
        assertTrue("Exactly 7 days after sync must still be fresh", cache.isFresh(atBoundary))
        assertFalse("Exactly 7 days after sync must not be stale", cache.isStale(atBoundary))
    }

    @Test
    fun isFresh_oneSecondPastSevenDayBoundary_isFalse() {
        // Strictly past expiry: 7 days + 1 second is stale — no silent stale list.
        val syncTime = 1_000_000L
        val cache = SessionSummaryCache(sessions = listOf(summary("a")), syncTimeEpochSeconds = syncTime)
        val pastBoundary = syncTime + SessionSummaryCache.MAX_RETENTION_SECONDS + 1
        assertFalse("1 second past expiry must be stale", cache.isFresh(pastBoundary))
        assertTrue(cache.isStale(pastBoundary))
    }

    @Test
    fun isFresh_eightDayOldCache_isFalse() {
        val now = System.currentTimeMillis() / 1000
        val cache = SessionSummaryCache(
            sessions = listOf(summary("a")),
            syncTimeEpochSeconds = now - (8 * 24 * 3600L)
        )
        assertFalse(cache.isFresh(now))
        assertTrue(cache.isStale(now))
    }

    @Test
    fun isStale_isExactNegationOfIsFresh() {
        val syncTime = 1_000_000L
        val cache = SessionSummaryCache(sessions = listOf(summary("a")), syncTimeEpochSeconds = syncTime)
        for (offset in listOf(0L, 1L, 3_600L, SessionSummaryCache.MAX_RETENTION_SECONDS, SessionSummaryCache.MAX_RETENTION_SECONDS + 1, 30L * 24 * 3600L)) {
            val at = syncTime + offset
            assertEquals(
                "isStale(t) must be exact negation of isFresh(t) at offset=$offset",
                cache.isFresh(at),
                !cache.isStale(at)
            )
        }
    }

    @Test
    fun maxRetention_isExactlySevenDays() {
        assertEquals(7 * 24 * 3600L, SessionSummaryCache.MAX_RETENTION_SECONDS)
    }
}
