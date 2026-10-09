package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.context.FakeContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ScopedSessionsResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.SessionSummaryCache
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * PR-1 REQ-09 (review BLOCKER-2): cache-WRITE failures are contained at the
 * repository boundary on BOTH call sites (:139 scoped / :220 unscoped) —
 * a live fetch always displays. Cache I/O is availability, never
 * authorization; reads remain fail-closed (covered elsewhere).
 */
class SessionListCacheWriteFailureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter

    private val origin = "http://127.0.0.1:9119"

    /** Delegating storage whose summary-cache write always fails (disk-full style). */
    private class FailingSummaryWriteVault(
        delegate: VaultStorage
    ) : VaultStorage by delegate {
        override suspend fun saveSessionSummaryCache(
            cache: SessionSummaryCache,
            partition: CacheAccessPartition
        ) {
            throw IOException("injected cache write failure")
        }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("cache_write_failure_test")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        fakeAdapter = FakeGatewayProtocolAdapter()
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

    @Test
    fun liveFetch_displays_evenWhenCacheWriteFails_unscoped() = runBlocking {
        seedActiveConnection()
        val repository = DefaultSessionRepository(
            gatewayAdapter = fakeAdapter,
            vaultStorage = FailingSummaryWriteVault(vault)
        )

        val result = repository.listSessions()

        assertTrue("Live list must display despite cache-write failure: $result", result.isSuccess)
        assertFalse("Live result is never offline cache", result.getOrThrow().isOfflineCache)
        assertTrue("Fetched rows must be present", result.getOrThrow().sessions.isNotEmpty())
    }

    @Test
    fun liveFetch_displays_evenWhenCacheWriteFails_scoped() = runBlocking {
        seedActiveConnection()
        vault.saveSelectedContextProfile("architect")
        val fetched = listOf(SessionSummary("a-1", "Architect Session", null, 3.0, 1, "android"))
        val repository = DefaultSessionRepository(
            gatewayAdapter = fakeAdapter,
            vaultStorage = FailingSummaryWriteVault(vault),
            contextProfileRepository = FakeContextProfileRepository(ScopedSessionsResult.Success(fetched))
        )

        val result = repository.listSessions()

        assertTrue("Scoped live list must display despite cache-write failure: $result", result.isSuccess)
        assertFalse("Live result is never offline cache", result.getOrThrow().isOfflineCache)
        assertEquals(listOf("a-1"), result.getOrThrow().sessions.map { it.id })
    }
}
