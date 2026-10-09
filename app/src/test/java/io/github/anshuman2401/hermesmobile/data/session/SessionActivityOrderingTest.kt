package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.context.FakeContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionListResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionActivityOrderingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vault: EncryptedVault
    private lateinit var adapter: FakeGatewayProtocolAdapter
    private lateinit var context: FakeContextProfileRepository

    @Before
    fun setUp() = runBlocking {
        val origin = "http://activity.invalid:9119"
        vault = EncryptedVault(
            tempFolder.newFolder("activity-ordering"),
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        vault.saveConnectionProfile(ConnectionProfile("connection-test", origin, "Synthetic server"))
        vault.setActiveProfileId("connection-test")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "synthetic-cookie"),
                canonicalOrigin = origin
            ),
            profileLocalId = "connection-test"
        )
        adapter = FakeGatewayProtocolAdapter()
        context = FakeContextProfileRepository()
    }

    @Test
    fun olderActiveWins() = runBlocking {
        val older = row("older", startedAt = 10.0, lastActive = 400.875)
        val newer = row("newer", startedAt = 300.0, lastActive = 300.125)
        assertOnlineOrder(listOf(newer, older), listOf(older, newer))
    }

    @Test
    fun missingActivityPreservesHostOrder() = runBlocking {
        val rows = listOf(
            row("z-host-first", startedAt = 10.0),
            row("b-host-second", startedAt = null),
            row("a-host-third", startedAt = 900.0)
        )
        assertOnlineOrder(rows, rows)
    }

    @Test
    fun mixedActivityPromotesKnownAndPreservesUnknownSubsequence() = runBlocking {
        val unknownFirst = row("z-unknown", startedAt = 900.0)
        val low = row("known-low", startedAt = 800.0, lastActive = 100.25)
        val unknownSecond = row("b-unknown", startedAt = null)
        val high = row("known-high", startedAt = 10.0, lastActive = 200.75)
        val unknownThird = row("a-unknown", startedAt = 950.0)
        assertOnlineOrder(
            listOf(unknownFirst, low, unknownSecond, high, unknownThird),
            listOf(high, low, unknownFirst, unknownSecond, unknownThird)
        )
    }

    @Test
    fun equalActivityPreservesInputDespiteCreationAndId() = runBlocking {
        val first = row("z-tie", startedAt = 10.0, lastActive = 200.5)
        val second = row("a-tie", startedAt = 900.0, lastActive = 200.5)
        val latest = row("latest", startedAt = 5.0, lastActive = 300.5)
        assertOnlineOrder(listOf(first, second, latest), listOf(latest, first, second))
    }

    @Test
    fun nonfiniteActivityDoesNotMakeAnOtherwiseUnknownPageSortable() = runBlocking {
        val rows = listOf(
            row("nan-first", 10.0, Double.NaN),
            row("missing-second", 900.0),
            row("infinity-third", null, Double.POSITIVE_INFINITY),
            row("negative-infinity-fourth", 800.0, Double.NEGATIVE_INFINITY)
        )
        assertOnlineOrder(rows, rows)
    }

    @Test
    fun mixedNonfiniteActivityRemainsInUnknownSubsequence() = runBlocking {
        val invalidFirst = row("invalid-first", 900.0, Double.POSITIVE_INFINITY)
        val known = row("known", 10.0, 200.5)
        val missing = row("missing", 800.0)
        val invalidLast = row("invalid-last", null, Double.NaN)
        assertOnlineOrder(
            listOf(invalidFirst, known, missing, invalidLast),
            listOf(known, invalidFirst, missing, invalidLast)
        )
    }

    @Test
    fun onlineAndOfflineUseSamePolicyIncludingUnorderedCachedPages() = runBlocking {
        val oldActive = row("old-active", 10.0, 400.5)
        val newer = row("newer", 300.0, 300.5)
        val tied = row("a-tied", 900.0, 300.5)
        val unknownFirst = row("z-unknown", 800.0)
        // Use cache-roundtrip-stable creation metadata here; online witnesses
        // above independently retain null creation values.
        val unknownSecond = row("a-unknown", 0.0)
        val cases = listOf(
            listOf(newer, oldActive, tied) to listOf(oldActive, newer, tied),
            listOf(unknownSecond, unknownFirst) to listOf(unknownSecond, unknownFirst),
            listOf(unknownFirst, newer, unknownSecond, oldActive, tied) to
                listOf(oldActive, newer, tied, unknownFirst, unknownSecond)
        )
        for (scope in listOf(null, "workspace-test")) {
            selectScope(scope)
            for ((input, expected) in cases) {
                // Seed the original page, so offline ordering cannot pass merely
                // by replaying a list that the online path already sorted.
                vault.saveSessionSummaryCache(
                    SessionSummaryCache(input, profileLocalId = "connection-test", cachedProfileName = scope),
                    partition()
                )
                adapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Synthetic offline"))
                context.scopedResult = ScopedSessionsResult.Error(IllegalStateException("Synthetic offline"))
                val offline = repository().listSessions().getOrThrow()
                assertTrue(offline.isOfflineCache)
                assertEquals(scope, offline.selectedProfileName)
                assertEquals(expected, offline.sessions)

                configureOnline(input)
                val online = repository().listSessions(forceRefresh = true).getOrThrow()
                assertFalse(online.isOfflineCache)
                assertEquals(offline.sessions, online.sessions)
                val cached = vault.readSessionSummaryCache(partition()) as CacheReadResult.Available
                assertEquals(expected, cached.value.sessions)
            }
        }
    }

    private suspend fun assertOnlineOrder(input: List<SessionSummary>, expected: List<SessionSummary>) {
        for (scope in listOf(null, "workspace-test")) {
            selectScope(scope)
            configureOnline(input)
            val outcome = repository().listSessions(forceRefresh = true).getOrThrow()
            assertFalse(outcome.isOfflineCache)
            assertEquals(scope, outcome.selectedProfileName)
            // Compare whole rows: ordering must not fabricate or replace metadata.
            assertEquals(expected, outcome.sessions)
        }
    }

    private suspend fun selectScope(scope: String?) {
        if (scope == null) vault.clearSelectedContextProfile() else vault.saveSelectedContextProfile(scope)
    }

    private fun configureOnline(rows: List<SessionSummary>) {
        adapter.listSessionsResult = GatewayResult.Success(SessionListResult(rows))
        context.scopedResult = ScopedSessionsResult.Success(rows)
    }

    private fun repository() = DefaultSessionRepository(adapter, vault, contextProfileRepository = context)

    private suspend fun partition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun row(id: String, startedAt: Double?, lastActive: Double? = null) =
        SessionSummary(id, "Synthetic title", "Synthetic preview", startedAt, 1, "android", lastActive)
}
