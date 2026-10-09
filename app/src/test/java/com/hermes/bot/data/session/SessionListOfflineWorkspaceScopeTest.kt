package com.hermes.bot.data.session

import com.hermes.bot.data.auth.HttpResponse
import com.hermes.bot.data.context.ContextProfileRepository
import com.hermes.bot.data.context.DefaultContextProfileRepository
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartition
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.domain.LocalCacheException
import com.hermes.bot.data.local.SessionSummaryCache
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * REQ-06/REQ-08 (TEST-09): switching the workspace selection never reuses or
 * clears another workspace's offline record — the mismatch fails closed and
 * the original record still serves after re-selection.
 */
class SessionListOfflineWorkspaceScopeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var contextProfileRepository: ContextProfileRepository
    private lateinit var sessionRepository: SessionRepository

    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("workspace_scope_test")
        val storage: VaultStorage =
            EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault = storage as EncryptedVault
        fakeAdapter = FakeGatewayProtocolAdapter()
        val fakeTransport = SessionListPartitionResolverTest.Scoped500Transport()
        contextProfileRepository = DefaultContextProfileRepository(
            vaultStorage = storage,
            httpTransport = fakeTransport,
        )
        sessionRepository = DefaultSessionRepository(
            gatewayAdapter = fakeAdapter,
            vaultStorage = storage,
            contextProfileRepository = contextProfileRepository,
            httpTransport = fakeTransport
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

    @Test
    fun workspaceSwitch_neverServesOtherScope_andPreservesOriginalRecord() = runBlocking {
        seedActiveConnection()

        vault.saveSelectedContextProfile("architect")
        val architectPartition = requirePartition()
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("arch-9", "Archived", "cached", 500.0, 3, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1",
                cachedProfileName = "architect"
            ),
            architectPartition
        )

        // 1) Matching selection serves the record with the offline flag.
        val first = sessionRepository.listSessions()
        assertTrue("Architect record must serve: $first", first.isSuccess)
        assertTrue("Must be offline cache", first.getOrThrow().isOfflineCache)

        // 2) Switch to a workspace with no record: fail closed, never cross-serve.
        vault.saveSelectedContextProfile("builder")
        val second = sessionRepository.listSessions()
        assertTrue("Switched workspace must fail closed offline", second.isFailure)
        assertTrue(
            "Must fail closed with LocalCacheException, was ${second.exceptionOrNull()}",
            second.exceptionOrNull() is LocalCacheException
        )

        // 3) The mismatch read must not have cleared or corrupted the original record.
        vault.saveSelectedContextProfile("architect")
        val third = sessionRepository.listSessions()
        assertTrue("Original workspace record must still serve after re-selection: $third", third.isSuccess)
        assertTrue("Must still be offline cache", third.getOrThrow().isOfflineCache)
    }
}
