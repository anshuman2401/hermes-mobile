package com.hermes.bot.data.context

import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartition
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.CacheEpochRotationReason
import com.hermes.bot.data.local.CacheReadResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * PR-2 TEST-01..05: offline workspace roster cache — repository-facing behavior.
 *
 * Guarantees under test:
 * - TEST-01: exact record IDs / AAD strings / derived filenames; per-workspace isolation.
 * - TEST-02: the local (persisted) selection label survives an offline cached load.
 * - TEST-03: the full offline flow attempts only read-only calls; the cached read
 *   adds zero transport attempts; the result is labelled CACHED.
 * - TEST-04: sign-out gate returns NO_ACTIVE_CONNECTION with no roster bytes; a
 *   re-authenticated credential resolves a rotated epoch that never reads the
 *   pre-sign-out roster slot.
 * - TEST-05: same-origin credential replacement rotates the epoch; the old-epoch
 *   roster is never served through the current resolver; a fresh capture lands
 *   under the new epoch's slot.
 */
class ContextProfileRosterCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeTransport: ContextProfileRepositoryTest.FakeContextProfileTransport
    private lateinit var repository: DefaultContextProfileRepository

    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("context_profile_roster_cache_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeTransport = ContextProfileRepositoryTest.FakeContextProfileTransport()
        repository = DefaultContextProfileRepository(vaultStorage = vault, httpTransport = fakeTransport)

        runBlocking {
            vault.saveConnectionProfile(
                ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "T")
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
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Plan's exact record-ID template: `context-profiles:{P}:{O}:{E}:{W}`. */
    private fun expectedRecordId(p: CacheAccessPartition): String =
        "context-profiles:${p.profileLocalId}:${p.originSha256}:${p.cacheEpoch}:${p.workspaceScopeTag}"

    /** Plan's exact AAD template: `CONTEXT_PROFILE_CACHE:2:{recordId}:{P}:{O}:{E}:{W}`. */
    private fun expectedAad(p: CacheAccessPartition): String =
        "CONTEXT_PROFILE_CACHE:2:${expectedRecordId(p)}:${p.profileLocalId}:${p.originSha256}:" +
            "${p.cacheEpoch}:${p.workspaceScopeTag}"

    /** Plan's derived filename: `roster_{sha256Hex(recordId)}.enc` in the P/O subtree. */
    private fun expectedRosterFile(p: CacheAccessPartition): File = File(
        File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        ),
        "roster_${sha256Hex(expectedRecordId(p))}.enc"
    )

    private suspend fun partitionFor(workspace: String? = null): CacheAccessPartition {
        workspace?.let { vault.saveSelectedContextProfile(it) }
        return (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
    }

    // ---- TEST-01: exact strings + per-workspace isolation ----

    @Test
    fun workspaceIsolation_exactRecordIdsAadsAndFilenames() = runBlocking {
        // Live capture under workspace "alpha" (write happens only after both GETs parse).
        val partitionA = partitionFor(workspace = "alpha")
        val live = repository.getProfiles()
        assertTrue("Live load must succeed", live is ContextProfilesResult.Success)

        val fileA = expectedRosterFile(partitionA)
        assertTrue(
            "Capture must land at the plan-derived filename for workspace A",
            fileA.exists()
        )

        // Switch the local selection; resolve workspace B's partition.
        val selection = repository.selectProfile("beta")
        assertTrue(selection is ContextProfileSelectionResult.Success)
        val partitionB = partitionFor()

        // Exact plan literals (worked example: W = `w:` + sha256(workspace)).
        assertEquals(
            "w:" + sha256Hex("alpha"),
            partitionA.workspaceScopeTag
        )
        assertEquals(
            "context-profiles:conn-1:${partitionA.originSha256}:${partitionA.cacheEpoch}:w:" +
                sha256Hex("alpha"),
            expectedRecordId(partitionA)
        )
        assertEquals(
            "CONTEXT_PROFILE_CACHE:2:context-profiles:conn-1:${partitionA.originSha256}:" +
                "${partitionA.cacheEpoch}:w:${sha256Hex("alpha")}:conn-1:" +
                "${partitionA.originSha256}:${partitionA.cacheEpoch}:w:${sha256Hex("alpha")}",
            expectedAad(partitionA)
        )
        assertEquals(
            "roster_" + sha256Hex(expectedRecordId(partitionA)) + ".enc",
            fileA.name
        )

        // Identities are pairwise distinct across workspaces.
        assertFalse(expectedRecordId(partitionA) == expectedRecordId(partitionB))
        assertFalse(expectedAad(partitionA) == expectedAad(partitionB))
        assertFalse(fileA == expectedRosterFile(partitionB))

        // B's slot reads Absent even though A's file exists on disk.
        assertTrue(fileA.exists())
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partitionB))

        // Repository-level offline load under B never serves A's roster,
        // and loading never mutates the persisted selection.
        val selectionBefore = vault.getSelectedContextProfile()
        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val offline = repository.getProfiles()
        assertTrue(
            "No cross-workspace roster may be served offline",
            offline is ContextProfilesResult.Error
        )
        assertEquals(selectionBefore, vault.getSelectedContextProfile())
    }

    // ---- TEST-02: local selection label survives the cached load ----

    @Test
    fun cachedLoad_preservesLocalSelectionLabel() = runBlocking {
        vault.saveSelectedContextProfile("ca")

        val t0 = System.currentTimeMillis()
        val live = repository.getProfiles()
        assertTrue(live is ContextProfilesResult.Success)
        val liveSuccess = live as ContextProfilesResult.Success
        val t1 = System.currentTimeMillis()
        assertEquals(RosterSource.LIVE, liveSuccess.source)
        assertEquals("ca", liveSuccess.activeProfileName)
        assertTrue(liveSuccess.profiles.first { it.name == "ca" }.isActive)

        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val offline = repository.getProfiles()
        assertTrue(offline is ContextProfilesResult.Success)
        val cached = offline as ContextProfilesResult.Success
        assertEquals(RosterSource.CACHED, cached.source)
        assertEquals("ca", cached.activeProfileName)
        assertTrue(
            "Local selection label must survive the offline load",
            cached.profiles.first { it.name == "ca" }.isActive
        )
        assertFalse(cached.profiles.first { it.name == "default" }.isActive)
        assertNotNull(cached.capturedAtMillis)
        assertTrue(
            "Capture time must be the moment of the successful live fetch",
            cached.capturedAtMillis in t0..t1
        )
        assertEquals("ca", vault.getSelectedContextProfile())
    }

    // ---- TEST-03: read-only flow, zero cached-read transport attempts ----

    @Test
    fun offlineFlow_recordsOnlyGetAttempts_andCarriesCachedLabel() = runBlocking {
        val live = repository.getProfiles()
        assertTrue(live is ContextProfilesResult.Success)
        assertEquals(2, fakeTransport.recordedRequests.size)

        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val selectionBefore = vault.getSelectedContextProfile()

        val firstOffline = repository.getProfiles()
        assertTrue(firstOffline is ContextProfilesResult.Success)
        // The cached read itself adds zero transport attempts — only the
        // failed active-profile attempt (1) is recorded.
        assertEquals(3, fakeTransport.recordedRequests.size)
        assertEquals(
            "Cached result must be labelled, never unlabelled-live",
            RosterSource.CACHED,
            (firstOffline as ContextProfilesResult.Success).source
        )

        val secondOffline = repository.getProfiles()
        assertEquals(4, fakeTransport.recordedRequests.size)
        assertEquals(
            RosterSource.CACHED,
            (secondOffline as ContextProfilesResult.Success).source
        )
        assertEquals(selectionBefore, vault.getSelectedContextProfile())
        assertTrue(
            "Every attempt across the offline flow is read-only",
            fakeTransport.recordedRequests.all { it.method == "GET" }
        )
    }

    // ---- TEST-04: sign-out gate + rotated-epoch isolation ----

    @Test
    fun signOut_gateFailsClosed_rosterBytesNeverSurface() = runBlocking {
        // Capture a roster under workspace "alpha" pre-sign-out.
        val partitionBefore = partitionFor(workspace = "alpha")
        val live = repository.getProfiles()
        assertTrue(live is ContextProfilesResult.Success)
        assertTrue(expectedRosterFile(partitionBefore).exists())
        val requestsAfterLive = fakeTransport.recordedRequests.size

        vault.clearAuthCredential("conn-1", CacheEpochRotationReason.SIGNED_OUT)
        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")

        val gated = repository.getProfiles()
        assertTrue(gated is ContextProfilesResult.Error)
        val error = gated as ContextProfilesResult.Error
        assertEquals(ContextProfileErrorReason.NO_ACTIVE_CONNECTION, error.reason)
        assertFalse(
            "No workspace name may surface through the gated error",
            error.safeMessage.contains("alpha")
        )
        assertFalse(error.safeMessage.contains("default"))
        assertEquals(
            "Credential gate must fire before any transport attempt",
            requestsAfterLive,
            fakeTransport.recordedRequests.size
        )

        // Re-authenticate with a different credential (same origin): epoch rotates.
        val epochBefore = partitionBefore.cacheEpoch
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok2"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
        val partitionAfter = partitionFor()
        assertFalse(epochBefore == partitionAfter.cacheEpoch)

        // The pre-sign-out roster may still sit on disk, but the new partition
        // resolves a different slot — it is never served from there.
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partitionAfter))
        val offline = repository.getProfiles()
        assertTrue(offline is ContextProfilesResult.Error)
        val offlineError = offline as ContextProfilesResult.Error
        assertFalse(offlineError.safeMessage.contains("alpha"))
        assertFalse(offlineError.safeMessage.contains("default"))
    }

    // ---- TEST-05: credential replacement (no sign-out) rotates the epoch ----

    @Test
    fun credentialReplacement_epochRotates_oldRosterNeverServed_newEpochRecaptures() = runBlocking {
        val partitionE1 = partitionFor()
        val live1 = repository.getProfiles()
        assertTrue(live1 is ContextProfilesResult.Success)
        val fileE1 = expectedRosterFile(partitionE1)
        assertTrue(fileE1.exists())

        // Same-origin credential replacement rotates the epoch first.
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok2"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
        val partitionE2 = partitionFor()
        assertFalse(partitionE1.cacheEpoch == partitionE2.cacheEpoch)

        // Old-epoch roster read through the current resolver: never Available.
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partitionE2))

        // Offline right after replacement: the original error, not the old roster.
        fakeTransport.throwExceptionOnExecute = java.net.SocketTimeoutException("Timeout")
        val offline = repository.getProfiles()
        assertTrue(offline is ContextProfilesResult.Error)
        fakeTransport.throwExceptionOnExecute = null

        // A fresh live capture lands under the new epoch's slot only.
        val live2 = repository.getProfiles()
        assertTrue(live2 is ContextProfilesResult.Success)
        val fileE2 = expectedRosterFile(partitionE2)
        assertTrue(fileE2.exists())
        assertFalse(fileE1.name == fileE2.name)
        assertTrue(vault.readContextProfileCache(partitionE2) is CacheReadResult.Available)
    }
}
