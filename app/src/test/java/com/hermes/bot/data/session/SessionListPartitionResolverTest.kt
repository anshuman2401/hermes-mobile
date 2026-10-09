package com.hermes.bot.data.session

import com.hermes.bot.data.auth.AuthHttpTransport
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * REQ-06 (TEST-05, TEST-06): the offline fallback is served only through the
 * resolved partition that matches the active workspace selection; a record
 * scoped to another workspace is never served and the call fails closed.
 */
class SessionListPartitionResolverTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeTransport: Scoped500Transport
    private lateinit var contextProfileRepository: ContextProfileRepository
    private lateinit var sessionRepository: SessionRepository

    private val origin = "http://127.0.0.1:9119"

    /** Transport whose scoped fetch always fails — the offline situation under test. */
    class Scoped500Transport : AuthHttpTransport {
        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse = HttpResponse(500, body = "Server Error")
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("partition_resolver_test")
        val storage: VaultStorage =
            EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault = storage as EncryptedVault
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeTransport = Scoped500Transport()
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

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
    fun scopedOffline_servesRecordScopedToSelectedWorkspace_withOfflineFlag() = runBlocking {
        seedActiveConnection()
        vault.saveSelectedContextProfile("architect")
        val partition = requirePartition()

        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("arch-9", "Archived Thread", "cached body", 500.0, 3, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1",
                cachedProfileName = "architect"
            ),
            partition
        )

        val result = sessionRepository.listSessions()
        assertTrue("Offline partitioned fallback must succeed: $result", result.isSuccess)
        val outcome = result.getOrThrow()
        assertTrue("Outcome must be flagged offline cache", outcome.isOfflineCache)
        assertEquals("architect", outcome.selectedProfileName)
        assertEquals(listOf("arch-9"), outcome.sessions.map { it.id })
    }

    @Test
    fun scopedOffline_recordBelongingToAnotherWorkspace_failsClosedWithLocalCacheException() = runBlocking {
        seedActiveConnection()
        vault.saveSelectedContextProfile("architect")
        val livePartition = requirePartition()

        // Only a record scoped to workspace "builder" exists — written under its own identity.
        val builderPartition = livePartition.copy(
            workspaceScopeTag = "w:${sha256Hex("builder")}"
        )
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("bld-1", "Builder Thread", "cached body", 500.0, 3, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1",
                cachedProfileName = "builder"
            ),
            builderPartition
        )

        val result = sessionRepository.listSessions()
        assertTrue("Cross-workspace record must not be served", result.isFailure)
        assertTrue(
            "Must fail closed with LocalCacheException, was ${result.exceptionOrNull()}",
            result.exceptionOrNull() is LocalCacheException
        )
    }
}
