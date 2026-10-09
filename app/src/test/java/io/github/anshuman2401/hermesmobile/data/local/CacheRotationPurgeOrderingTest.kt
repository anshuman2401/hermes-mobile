package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.session.DefaultSessionRepository
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * REQ-07/REQ-08 (TEST-08, TEST-10): rotation and purge ordering.
 *
 * Epoch rotation is committed BEFORE credential/profile mutation (proven with
 * an injected directory-sync fault); origin-change purges the old (P,O)
 * subtree BEFORE the new profile is written; validation refusals purge-free;
 * granular resets preserve their documented scope; sign-out vs credential
 * expiry record distinct rotation reasons.
 */
class CacheRotationPurgeOrderingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    private var syncCount = 0
    private var failSyncAtCall = -1

    private val originA = "http://127.0.0.1:9119"
    private val originB = "http://127.0.0.1:9220"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("rotation_ordering_test")
        engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        syncCount = 0
        failSyncAtCall = -1
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = engine,
            directorySync = { _ ->
                syncCount++
                if (syncCount == failSyncAtCall) {
                    throw IOException("injected directory sync failure")
                }
            }
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun originDir(profileLocalId: String, originSha256: String): File =
        File(File(File(storageDir, "cache"), sha256Hex(profileLocalId)), sha256Hex("$profileLocalId:$originSha256"))

    private fun stateFile(profileLocalId: String, originSha256: String): File =
        originDir(profileLocalId, originSha256)
            .resolve("cache_access_${sha256Hex("cache-access:$profileLocalId:$originSha256")}.enc")

    private suspend fun seed(origin: String = originA) {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-v1"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun requirePartition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private suspend fun saveRecord(partition: CacheAccessPartition) {
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("s1", "T", null, 1.0, 1, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1"
            ),
            partition
        )
    }

    private fun stateRotationReason(profileLocalId: String, originSha256: String): String? {
        val file = stateFile(profileLocalId, originSha256)
        if (!file.exists()) return null
        val aad = "CACHE_ACCESS_STATE:1:cache-access:$profileLocalId:$originSha256:$profileLocalId:$originSha256"
            .toByteArray(Charsets.UTF_8)
        val json = engine.decrypt(file.readBytes(), aad).getOrThrow().toString(Charsets.UTF_8)
        return when {
            json.contains("AUTH_EXPIRED") -> "AUTH_EXPIRED"
            json.contains("INITIAL_AUTH") -> "INITIAL_AUTH"
            json.contains("CREDENTIAL_REPLACED") -> "CREDENTIAL_REPLACED"
            json.contains("SIGNED_OUT") -> "SIGNED_OUT"
            json.contains("ORIGIN_REPLACED") -> "ORIGIN_REPLACED"
            else -> null
        }
    }

    @Test
    fun credentialSave_rotatesEpochBeforeCredentialMutation_whenSyncFaultsBetween() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        val before = requirePartition()
        saveRecord(before)
        assertTrue(vault.readSessionSummaryCache(before) is CacheReadResult.Available)

        val credentialFile = storageDir.resolve("auth_credential_${sha256Hex("auth_credential_conn-1")}.enc")
        val credentialBefore = credentialFile.readBytes()
        val summaryId = "session-summaries:conn-1:$originSha:${before.cacheEpoch}:${before.workspaceScopeTag}"
        val summaryFile = originDir("conn-1", originSha).resolve("summary_${sha256Hex(summaryId)}.enc")
        val summaryBefore = summaryFile.readBytes()

        // The rotation-state directory sync fires before the credential write;
        // fault it so only work committed before that point may be observable.
        failSyncAtCall = syncCount + 1
        var threw = false
        try {
            vault.saveAuthCredential(
                AuthCredential(
                    driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                    providerId = "password",
                    cookies = mapOf("session_id" to "tok-v2"),
                    canonicalOrigin = originA
                ),
                profileLocalId = "conn-1"
            )
        } catch (expected: IOException) {
            threw = true
        }
        failSyncAtCall = -1
        assertTrue("Injected sync fault must propagate", threw)

        // Inspect the installed encrypted state without granting an auth partition.
        val aad = "CACHE_ACCESS_STATE:1:cache-access:conn-1:$originSha:conn-1:$originSha"
            .toByteArray(Charsets.UTF_8)
        val stateJson = engine.decrypt(stateFile("conn-1", originSha).readBytes(), aad).getOrThrow()
        val state = GatewayJson.parseOrNull(stateJson.toString(Charsets.UTF_8))!!.asObjectOrNull()!!
        assertEquals(1, state["schemaVersion"]!!.asIntOrNull())
        assertEquals(originSha, state["originSha256"]!!.asStringOrNull())
        val installedEpoch = state["cacheEpoch"]!!.asStringOrNull()!!
        assertTrue(installedEpoch.isNotBlank())
        assertNotEquals("Epoch must rotate before credential mutation", before.cacheEpoch, installedEpoch)
        assertTrue("Credential file survives before mutation", credentialFile.exists())
        assertArrayEquals("Credential ciphertext must remain unchanged", credentialBefore, credentialFile.readBytes())
        assertNull(vault.getAuthCredential())
        assertNull(vault.getAuthCredential("conn-1"))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)

        // The retained old-partition summary cannot be served through the public
        // repository gate, even though its physical bytes survive the failed save.
        assertArrayEquals(summaryBefore, summaryFile.readBytes())
        val noOpTransport = object : AuthHttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?) =
                HttpResponse(500, body = "unused")
        }
        val adapter = FakeGatewayProtocolAdapter().apply {
            listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Synthetic offline"))
        }
        val repository = DefaultSessionRepository(
            gatewayAdapter = adapter, vaultStorage = vault, httpTransport = noOpTransport
        )
        val result = repository.listSessions()
        assertTrue("Old-partition cache must fail closed after failed save", result.isFailure)
        assertTrue(result.getOrNull()?.isOfflineCache != true)

        // And the rotated state carries the credential-replaced reason.
        assertEquals("CREDENTIAL_REPLACED", stateRotationReason("conn-1", originSha))
    }

    @Test
    fun originChange_purgesOldOriginSubtreeBeforeProfileMutation_whenSyncFaultsDuringPurge() = runBlocking {
        seed(originA)
        val partitionA = requirePartition()
        saveRecord(partitionA)
        assertTrue(originDir("conn-1", sha256Hex(originA)).exists())

        // Rotation-state sync (call N+1) may proceed; the purge directory sync (call N+2) faults.
        failSyncAtCall = syncCount + 2
        var threw = false
        try {
            vault.saveConnectionProfile(
                ConnectionProfile(localId = "conn-1", normalizedOrigin = originB, displayLabel = "Test Server")
            )
        } catch (expected: IOException) {
            threw = true
        }
        failSyncAtCall = -1
        assertTrue("Injected purge sync fault must propagate", threw)

        // Profile mutation strictly after the purge: profile must still be the old origin.
        val profile = vault.getConnectionProfile("conn-1")
        assertEquals("Profile write must happen after purge", originA, profile?.normalizedOrigin)

        // Old (P,O_A) subtree purged — including its rotation state — so offline is
        // unavailable until the next authentication re-establishes state for the new origin.
        assertFalse(
            "Old origin subtree must be purged before the profile mutation",
            originDir("conn-1", sha256Hex(originA)).exists()
        )
        val resolve = vault.activeAccessPartition()
        assertTrue(
            "Resolver must be unavailable until next authentication, was $resolve",
            resolve is CacheAccessPartitionResult.Unavailable
        )
        assertEquals(
            CacheUnavailableReason.CACHE_ACCESS_STATE_ABSENT,
            (resolve as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun staleConditionalProfileCommit_isRefusedWithoutPurge() = runBlocking {
        seed()
        val partition = requirePartition()
        saveRecord(partition)

        val gate = ConnectionProfileGenerationGate()
        val generation = gate.beginAttempt()
        gate.invalidate() // a newer attempt supersedes this one

        val result = vault.saveConnectionProfileIfCurrent(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = originB, displayLabel = "Never"),
            generation,
            gate
        )
        assertEquals(ProfileCommitResult.STALE, result)

        // Validation refusal must be purge-free: record and resolver state intact.
        assertTrue(
            "Stale refusal must not purge the cache",
            vault.readSessionSummaryCache(partition) is CacheReadResult.Available
        )
        assertEquals(originA, vault.getConnectionProfile("conn-1")?.normalizedOrigin)
    }

    @Test
    fun lastProfileDeletionRefusal_isPurgeFree() = runBlocking {
        seed()
        val partition = requirePartition()
        saveRecord(partition)

        val deleted = vault.deleteConnectionProfile("conn-1")
        assertFalse("Deleting the last profile must be refused", deleted)

        assertTrue(
            "Refused deletion must not purge the cache",
            vault.readSessionSummaryCache(partition) is CacheReadResult.Available
        )
        assertTrue("Profile must remain", vault.getConnectionProfile("conn-1") != null)
        assertTrue(
            "Resolver must stay available",
            vault.activeAccessPartition() is CacheAccessPartitionResult.Available
        )
    }

    @Test
    fun clearCache_keepsRotationState_clearAllWipesEverything() = runBlocking {
        seed()
        val partition = requirePartition()
        saveRecord(partition)
        val state = stateFile("conn-1", sha256Hex(originA))
        assertTrue(state.exists())

        vault.clearCache()
        assertEquals(
            "Clear-cache must drop records but keep rotation state",
            CacheReadResult.Absent,
            vault.readSessionSummaryCache(partition)
        )
        assertTrue("Rotation state must survive clearCache", state.exists())
        assertTrue(
            "Resolver must stay available after clearCache",
            vault.activeAccessPartition() is CacheAccessPartitionResult.Available
        )

        saveRecord(partition)
        vault.clearAll()
        assertFalse("Cache tree must be wiped by clearAll", File(storageDir, "cache").exists())
        assertFalse("Rotation state must be wiped by clearAll", state.exists())
        val resolve = vault.activeAccessPartition()
        assertTrue(resolve is CacheAccessPartitionResult.Unavailable)
        // The full wipe removes the active profile first — the resolver reports
        // the profile-level absence before it can ever reach the state stage.
        assertEquals(
            CacheUnavailableReason.NO_ACTIVE_PROFILE,
            (resolve as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun authRotationReasons_credentialExpiryVsSignOut_areDistinct() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        assertEquals("INITIAL_AUTH", stateRotationReason("conn-1", originSha))

        val noOpTransport = object : AuthHttpTransport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?
            ): HttpResponse = HttpResponse(500, body = "unused")
        }
        val authRepository = DefaultAuthRepository(
            vaultStorage = vault,
            httpTransport = noOpTransport
        )

        authRepository.notifyAuthExpired()
        assertEquals("AUTH_EXPIRED", stateRotationReason("conn-1", originSha))

        authRepository.signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.KEEP)
        assertEquals("SIGNED_OUT", stateRotationReason("conn-1", originSha))
        assertNull("Sign-out must clear the credential", vault.getAuthCredential("conn-1"))
    }

    @Test
    fun guardedSave_productionOriginEdit_rotatesAndPurgesOldOriginSubtree_onSyncFaultDuringPurge() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        val before = requirePartition()
        saveRecord(before)
        assertTrue(originDir("conn-1", originSha).exists())

        // Same fault arithmetic as TEST-08: rotation-state sync may proceed;
        // the purge directory sync (next call) faults → purge aborts → the
        // guarded save must fail BEFORE writing the new origin.
        failSyncAtCall = syncCount + 2
        val gate = ConnectionProfileGenerationGate()
        val result = vault.saveConnectionProfileIfCurrent(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = originB, displayLabel = "Test Server"),
            gate.beginAttempt(),
            gate
        )
        failSyncAtCall = -1

        assertTrue(
            "Purge fault must abort the PRODUCTION guarded save, was $result",
            result is ProfileCommitResult.FAILED
        )
        // Mutation strictly after the purge: profile must still be the OLD origin.
        assertEquals(
            "Profile write must happen after the purge, was " +
                vault.getConnectionProfile("conn-1")?.normalizedOrigin,
            originA,
            vault.getConnectionProfile("conn-1")?.normalizedOrigin
        )
        assertFalse(
            "Old (P,O) subtree must be purged by the PRODUCTION path too",
            originDir("conn-1", originSha).exists()
        )
        val resolve = vault.activeAccessPartition()
        assertTrue(
            "Resolver must be unavailable until next authentication, was $resolve",
            resolve is CacheAccessPartitionResult.Unavailable
        )
        assertEquals(
            CacheUnavailableReason.CACHE_ACCESS_STATE_ABSENT,
            (resolve as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun guardedSave_productionOriginEdit_success_purgesOldOriginSubtree() = runBlocking {
        seed()
        val oldSha = sha256Hex(originA)
        val partition = requirePartition()
        saveRecord(partition)

        val gate = ConnectionProfileGenerationGate()
        val result = vault.saveConnectionProfileIfCurrent(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = originB, displayLabel = "Test Server"),
            gate.beginAttempt(),
            gate
        )
        assertEquals(ProfileCommitResult.SAVED, result)
        assertEquals(originB, vault.getConnectionProfile("conn-1")?.normalizedOrigin)
        assertFalse(
            "Production origin edit must purge the old (P,O) subtree",
            originDir("conn-1", oldSha).exists()
        )
        assertTrue(
            "Offline must fail closed after the origin edit until next authentication",
            vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable
        )
    }

    @Test
    fun accessStateFromFutureSchema_failsClosed_neverDowngraded() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        val file = stateFile("conn-1", originSha)
        val aad = "CACHE_ACCESS_STATE:1:cache-access:conn-1:$originSha:conn-1:$originSha"
            .toByteArray(Charsets.UTF_8)
        val json =
            """{"cacheEpoch":"future-epoch-value","originSha256":"$originSha","rotationReason":"INITIAL_AUTH","updatedAtMs":1,"schemaVersion":2}"""
        file.writeBytes(engine.encrypt(json.toByteArray(Charsets.UTF_8), aad))

        val resolve = vault.activeAccessPartition()
        assertTrue(
            "A future-schema access-state must fail closed, was $resolve",
            resolve is CacheAccessPartitionResult.Unavailable
        )
        assertEquals(
            CacheUnavailableReason.CACHE_ACCESS_STATE_INVALID,
            (resolve as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun cacheEpoch_mintedAsBase64url128BitSecureRandom() = runBlocking {
        seed()
        val epoch = requirePartition().cacheEpoch
        assertTrue(
            "Epoch must be unpadded base64url (22 chars for 128 bits), was $epoch",
            Regex("^[A-Za-z0-9_-]{22}$").matches(epoch)
        )
        assertEquals(16, java.util.Base64.getUrlDecoder().decode(epoch).size)
    }

    @Test
    fun signOut_summaryCiphertextRetained_readsFailClosed_neverOfflineCache() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        val partition = requirePartition()
        saveRecord(partition)
        val recordId = "session-summaries:conn-1:$originSha:${partition.cacheEpoch}:${partition.workspaceScopeTag}"
        val summaryFile = originDir("conn-1", originSha).resolve("summary_${sha256Hex(recordId)}.enc")
        assertTrue("Seed record must exist on disk", summaryFile.exists())

        // Production sign-out lane (charter T-01 direction A).
        val noOpTransport = object : AuthHttpTransport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?
            ): HttpResponse = HttpResponse(500, body = "unused")
        }
        val authRepository = DefaultAuthRepository(vaultStorage = vault, httpTransport = noOpTransport)
        authRepository.signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.KEEP)

        // 1) Ciphertext is RETAINED on disk...
        assertTrue("Summary ciphertext must be retained after sign-out", summaryFile.exists())

        // 2) ...but the production partition resolution fails closed...
        val resolve = vault.activeAccessPartition()
        assertTrue(
            "Resolver must fail closed after sign-out, was $resolve",
            resolve is CacheAccessPartitionResult.Unavailable
        )
        assertEquals(
            CacheUnavailableReason.CREDENTIAL_ABSENT,
            (resolve as CacheAccessPartitionResult.Unavailable).reason
        )

        // 3) ...and the production repository can NEVER report isOfflineCache=true.
        vault.saveSelectedContextProfile("architect")
        val repository = DefaultSessionRepository(
            gatewayAdapter = FakeGatewayProtocolAdapter(),
            vaultStorage = vault,
            httpTransport = noOpTransport
        )
        val result = repository.listSessions()
        assertTrue("Sign-out must fail closed offline, was $result", result.isFailure)
        assertTrue(
            "isOfflineCache can never become true after sign-out",
            result.getOrNull()?.isOfflineCache != true
        )
    }
}
