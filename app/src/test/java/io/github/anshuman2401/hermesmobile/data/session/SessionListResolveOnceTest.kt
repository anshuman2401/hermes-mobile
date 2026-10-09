package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.context.FakeContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
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
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * PR-1 REQ-06 (review BLOCKER-3): the access partition is resolved exactly
 * ONCE at listSessions entry (architecture §4 forbids write-time
 * re-resolution) and threaded through this operation's cache WRITE and
 * FALLBACK READ. A workspace/selection change mid-fetch therefore can
 * neither redirect the write to the later selection's scope nor change
 * what the fallback read serves.
 */
class SessionListResolveOnceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeContextProfiles: FakeContextProfileRepository
    private lateinit var sessionRepository: SessionRepository

    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("resolve_once_test")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        fakeContextProfiles = FakeContextProfileRepository()
        sessionRepository = DefaultSessionRepository(
            gatewayAdapter = FakeGatewayProtocolAdapter(),
            vaultStorage = vault,
            contextProfileRepository = fakeContextProfiles
        )
    }

    private suspend fun seedActiveConnection() {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun requirePartition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun cache(cachedProfileName: String?, vararg ids: String) = SessionSummaryCache(
        sessions = ids.map { SessionSummary(it, "T-$it", null, 1.0, 1, "android") },
        syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
        profileLocalId = "conn-1",
        cachedProfileName = cachedProfileName
    )

    @Test
    fun midFlightSelectionChange_writeTargetsEntryScope_neverClobbersLaterSelection() = runBlocking {
        seedActiveConnection()

        // The LATER selection's (builder) pre-existing valid record.
        vault.saveSelectedContextProfile("builder")
        val builderPartition = requirePartition()
        vault.saveSessionSummaryCache(cache("builder", "b-old"), builderPartition)

        // The ENTRY selection (architect) for the operation under test.
        vault.saveSelectedContextProfile("architect")
        val architectPartition = requirePartition()

        val fetched = listOf(SessionSummary("a-new", "Fetched", null, 2.0, 1, "android"))
        fakeContextProfiles.scopedResult = ScopedSessionsResult.Success(fetched)
        // The selection flips to "builder" AFTER listSessions resolved its
        // partition but before the fetch returns — the forbidden scenario.
        fakeContextProfiles.onScopedFetch = { vault.saveSelectedContextProfile("builder") }

        val result = sessionRepository.listSessions()
        assertTrue("Live scoped fetch must serve, was $result", result.isSuccess)

        // The write landed in the ENTRY (architect) scope...
        val architectRead = vault.readSessionSummaryCache(architectPartition)
        assertTrue("Entry-scoped record must be written: $architectRead", architectRead is CacheReadResult.Available)
        assertEquals(
            listOf("a-new"),
            (architectRead as CacheReadResult.Available).value.sessions.map { it.id }
        )

        // ...and the LATER selection's record was never clobbered by it.
        val builderRead = vault.readSessionSummaryCache(builderPartition)
        assertTrue("Builder record must survive untouched: $builderRead", builderRead is CacheReadResult.Available)
        assertEquals(
            listOf("b-old"),
            (builderRead as CacheReadResult.Available).value.sessions.map { it.id }
        )
    }

    @Test
    fun midFlightSelectionChange_fallbackReadServesEntryScope_notLaterSelection() = runBlocking {
        seedActiveConnection()

        vault.saveSelectedContextProfile("architect")
        val architectPartition = requirePartition()
        vault.saveSessionSummaryCache(cache("architect", "arch-old"), architectPartition)

        vault.saveSelectedContextProfile("builder")
        val builderPartition = requirePartition()
        vault.saveSessionSummaryCache(cache("builder", "b-old"), builderPartition)

        // Operation entry selection: architect.
        vault.saveSelectedContextProfile("architect")

        fakeContextProfiles.scopedResult = ScopedSessionsResult.Error(IOException("offline"))
        fakeContextProfiles.onScopedFetch = { vault.saveSelectedContextProfile("builder") }

        val result = sessionRepository.listSessions()
        assertTrue("Entry-scoped fallback must serve: $result", result.isSuccess)
        assertTrue("Fallback result must be flagged offline cache", result.getOrThrow().isOfflineCache)
        assertEquals(
            listOf("arch-old"),
            result.getOrThrow().sessions.map { it.id }
        )
    }
}
