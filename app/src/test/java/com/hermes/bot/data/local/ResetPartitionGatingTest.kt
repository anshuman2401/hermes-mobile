package com.hermes.bot.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Plan CACHE-PR5 TEST-06 — partition gating: one profile's clear-cache run
 * leaves BOTH partitions' access state (credentials + rotation) Available,
 * cached content reads Absent, and the global draft store survives; the full
 * local reset (clearAll) is what flips availability to unavailable.
 *
 * Plan wording adaptation (disclosed in the PR): clearCache is a whole-vault
 * clear across every partition by design (REQ-06 covers all of them), so the
 * "other partition stays Available" evidence is asserted for the pair rather
 * than one-clear-one-gated.
 */
class ResetPartitionGatingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("reset_partition_gating")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun seedProfile(localId: String, origin: String) {
        vault.saveConnectionProfile(ConnectionProfile(localId, origin, localId))
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-$localId"),
                canonicalOrigin = origin
            ),
            profileLocalId = localId
        )
    }

    private suspend fun partitionOf(localId: String): CacheAccessPartition {
        vault.setActiveProfileId(localId)
        val r = vault.activeAccessPartition()
        assertTrue("$localId must resolve: $r", r is CacheAccessPartitionResult.Available)
        return (r as CacheAccessPartitionResult.Available).partition
    }

    private fun recordFor(p: CacheAccessPartition, sid: String, body: String) = ConversationCacheV2(
        schemaVersion = ConversationCacheV2.CURRENT_SCHEMA_VERSION,
        recordId = ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
        ),
        profileLocalId = p.profileLocalId,
        originSha256 = p.originSha256,
        cacheEpoch = p.cacheEpoch,
        workspaceScopeTag = p.workspaceScopeTag,
        durableSessionId = sid,
        selectedWorkspaceName = null,
        snapshot = CachedRemoteHistorySnapshot(
            snapshotId = sid,
            capturedAtMillis = System.currentTimeMillis(),
            messagesOmitted = false,
            clientTruncated = false,
            rows = listOf(
                CachedRemoteHistoryRow(0, "user", body, null, null, null, System.currentTimeMillis())
            )
        ),
        lastAccessedAtMillis = System.currentTimeMillis(),
        createdAtMillis = System.currentTimeMillis(),
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun contentFiles(p: CacheAccessPartition): List<File> = File(
        File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
        sha256Hex("${p.profileLocalId}:${p.originSha256}")
    ).listFiles()?.filter { !it.name.startsWith("cache_access_") } ?: emptyList()

    @Test
    fun clearCache_keepsBothPartitionsAvailable_contentGated_draftsSurvive_fullResetFlipsAvailability() = runBlocking {
        seedProfile("conn-1", "http://127.0.0.1:9711")
        seedProfile("conn-2", "http://127.0.0.1:9712")
        val p1 = partitionOf("conn-1")
        val p2 = partitionOf("conn-2")
        assertTrue(vault.replaceConversationSnapshot(recordFor(p1, "sess-1", "one"), p1))
        assertTrue(vault.replaceConversationSnapshot(recordFor(p2, "sess-2", "two"), p2))
        vault.saveDraft(Draft("sess-1", "kept-draft"))

        val result = vault.clearCache()
        assertEquals(CacheClearResult.Complete, result)

        // Data-driven over both partitions (plan's acceptance loop).
        for (localId in listOf("conn-1", "conn-2")) {
            val p = partitionOf(localId)
            val resolver = vault.activeAccessPartition()
            assertTrue(
                "$localId: credentials + rotation state survive clearCache: $resolver",
                resolver is CacheAccessPartitionResult.Available
            )
            val conv = vault.readConversationSnapshot(
                p, if (localId == "conn-1") "sess-1" else "sess-2"
            )
            assertTrue(
                "$localId: cached content gated after clear (got $conv)",
                conv is CacheReadResult.Absent
            )
            assertEquals(
                "$localId: draft store survives clearCache",
                "kept-draft", vault.getDraft("sess-1")?.composerText
            )
            assertTrue("$localId: content files drained", contentFiles(p).isEmpty())
        }

        // The FULL local reset is the action that flips availability.
        assertEquals(CacheClearResult.Complete, vault.clearAll())
        for (localId in listOf("conn-1", "conn-2")) {
            vault.setActiveProfileId(localId)
            val after = vault.activeAccessPartition()
            assertTrue(
                "$localId: full local reset leaves no credential-resolved partition: $after",
                after !is CacheAccessPartitionResult.Available
            )
        }
        assertNull("drafts are removed by the full local reset too", vault.getDraft("sess-1"))
    }
}
