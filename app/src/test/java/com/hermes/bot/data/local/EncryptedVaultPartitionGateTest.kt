package com.hermes.bot.data.local

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
 * REQ-01/REQ-02 (TEST-01, TEST-02): partition resolution.
 *
 * Offline reads are gated on a resolved access partition binding the signed-in
 * profile, its credential origin, the opaque epoch, and the workspace scope.
 * Every unavailable condition fails closed with a distinct reason.
 */
class EncryptedVaultPartitionGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault

    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("partition_gate_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun stateFile(profileLocalId: String, originSha256: String): File =
        File(File(File(storageDir, "cache"), sha256Hex(profileLocalId)), sha256Hex("$profileLocalId:$originSha256"))
            .resolve("cache_access_${sha256Hex("cache-access:$profileLocalId:$originSha256")}.enc")

    private suspend fun seedProfileAndCredential(credentialOrigin: String = origin) {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = credentialOrigin
            ),
            profileLocalId = "conn-1"
        )
    }

    @Test
    fun resolver_returnsAvailablePartition_boundToActiveProfileOriginAndWorkspace() = runBlocking {
        seedProfileAndCredential()

        val result = vault.activeAccessPartition()
        assertTrue(
            "Resolver must resolve for a fully seeded signed-in profile",
            result is CacheAccessPartitionResult.Available
        )
        val partition = (result as CacheAccessPartitionResult.Available).partition
        assertEquals("conn-1", partition.profileLocalId)
        assertEquals(sha256Hex(origin), partition.originSha256)
        assertEquals("u", partition.workspaceScopeTag)
        assertTrue("Epoch must be present", partition.cacheEpoch.isNotBlank())

        vault.saveSelectedContextProfile("architect")
        val scoped = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        assertEquals("w:${sha256Hex("architect")}", scoped.workspaceScopeTag)
        assertEquals("Workspace switch must not rotate the epoch", partition.cacheEpoch, scoped.cacheEpoch)
    }

    @Test
    fun resolver_reportsNoActiveProfileOnFreshVault() = runBlocking {
        val result = vault.activeAccessPartition()
        assertTrue(result is CacheAccessPartitionResult.Unavailable)
        assertEquals(
            CacheUnavailableReason.NO_ACTIVE_PROFILE,
            (result as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun resolver_reportsCredentialAbsentWhenCredentialNeverSaved() = runBlocking {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "T")
        )
        vault.setActiveProfileId("conn-1")

        val result = vault.activeAccessPartition()
        assertEquals(
            CacheUnavailableReason.CREDENTIAL_ABSENT,
            (result as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun resolver_rejectsCredentialOriginMismatch() = runBlocking {
        seedProfileAndCredential(credentialOrigin = "http://127.0.0.1:9999")

        val result = vault.activeAccessPartition()
        assertEquals(
            CacheUnavailableReason.CREDENTIAL_ORIGIN_MISMATCH,
            (result as CacheAccessPartitionResult.Unavailable).reason
        )
    }

    @Test
    fun resolver_reportsStateAbsentThenStateInvalidFailClosed() = runBlocking {
        seedProfileAndCredential()
        val file = stateFile("conn-1", sha256Hex(origin))
        assertTrue("Rotation state must exist after initial auth", file.exists())

        // Absent: rotation state removed without touching profile/credential.
        assertTrue(file.delete())
        assertEquals(
            CacheUnavailableReason.CACHE_ACCESS_STATE_ABSENT,
            (vault.activeAccessPartition() as CacheAccessPartitionResult.Unavailable).reason
        )

        // Invalid: state present but unreadable — fail closed, never trust bytes.
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertEquals(
            CacheUnavailableReason.CACHE_ACCESS_STATE_INVALID,
            (vault.activeAccessPartition() as CacheAccessPartitionResult.Unavailable).reason
        )
    }
}
