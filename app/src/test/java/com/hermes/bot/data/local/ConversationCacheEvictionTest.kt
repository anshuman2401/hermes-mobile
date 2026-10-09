package com.hermes.bot.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Charter T-09, plan §9 row (d): conversation-cache caps — 24 MiB per
 * partition, 32 MiB global, 768 KiB per snapshot, 256 records per partition —
 * with deterministic whole-record eviction and no partial rows.
 */
class ConversationCacheEvictionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val originA = "http://127.0.0.1:9501"
    private val originB = "http://127.0.0.1:9502"
    private var fixedNow = 10_000_000_000_000L

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("eviction_test")
        fixedNow = 10_000_000_000_000L
        vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            wallClockMillis = { fixedNow }
        )
    }

    private suspend fun seed(v: EncryptedVault = vault) {
        v.saveConnectionProfile(ConnectionProfile("conn-1", originA, "A"))
        v.setActiveProfileId("conn-1")
        v.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-a"),
                canonicalOrigin = originA
            ),
            profileLocalId = "conn-1"
        )
        v.saveConnectionProfile(ConnectionProfile("conn-2", originB, "B"))
        v.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-b"),
                canonicalOrigin = originB
            ),
            profileLocalId = "conn-2"
        )
    }

    private suspend fun partitionOf(v: EncryptedVault = vault, profileLocalId: String = "conn-1"): CacheAccessPartition {
        v.setActiveProfileId(profileLocalId)
        return (v.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
    }

    private fun recordFor(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        lastAccessed: Long
    ) = ConversationCacheV2(
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
            capturedAtMillis = lastAccessed,
            messagesOmitted = false,
            clientTruncated = false,
            rows = listOf(
                CachedRemoteHistoryRow(0, "assistant", body, null, null, null, lastAccessed)
            )
        ),
        lastAccessedAtMillis = lastAccessed,
        createdAtMillis = lastAccessed,
        updatedAtMillis = lastAccessed
    )

    private suspend fun write(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        lastAccessed: Long
    ): Boolean = vault.replaceConversationSnapshot(recordFor(p, sid, body, lastAccessed), p)

    private suspend fun available(
        p: CacheAccessPartition,
        sid: String,
        v: EncryptedVault = vault
    ): Boolean = v.readConversationSnapshot(p, sid, nowMillis = fixedNow) is CacheReadResult.Available

    @Test
    fun countCap_256Records_evictsLeastRecentlyAccessedDeterministically() = runBlocking {
        seed()
        val p = partitionOf()

        for (i in 1..257) {
            // Distinct lastAccessed keeps the LRU order unambiguous: sid-001 is
            // always the least recently accessed.
            val at = fixedNow - (10_000 - i).toLong()
            val ok = write(p, "sid-%03d".format(i), "body $i", lastAccessed = at)
            assertTrue("Write $i must succeed", ok)
        }

        assertFalse("Least-recently-accessed record evicted at the 256 cap", available(p, "sid-001"))
        assertTrue(available(p, "sid-002"))
        assertTrue(available(p, "sid-256"))
        assertTrue(available(p, "sid-257"))
    }

    @Test
    fun countCapEviction_isDeterministicForIdenticalLastAccessedTies() = runBlocking {
        seed()
        val p = partitionOf()

        // Identical lastAccessed ⇒ tie broken by (indexLocatorSha256,
        // recordIdHash): compute the expected victim exactly as the store
        // orders it and pin it.
        val sids = (1..257).map { "tie-%03d".format(it) }
        val hashes = sids.associateWith { sid ->
            val recordId = ConversationCacheV2.recordIdFor(
                p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
            )
            sha256Hex(recordId)
        }
        val expectedVictim = sids.minByOrNull { hashes.getValue(it) }!!

        sids.forEach { sid -> assertTrue(write(p, sid, "body", lastAccessed = fixedNow)) }

        assertFalse(
            "Tie-break must evict the recordIdHash-minimum sid deterministically",
            available(p, expectedVictim)
        )
        sids.filter { it != expectedVictim }.forEach { sid -> assertTrue("Keep $sid", available(p, sid)) }
    }

    @Test
    fun partitionByteCap_24MiB_evictsOldestWholeRecords() = runBlocking {
        seed()
        val p = partitionOf()

        // 33 × ~780 KiB ⇒ ~25.4 MiB > 24 MiB per-partition budget.
        val big = "b".repeat(ConversationCacheV2.MAX_SNAPSHOT_BYTES - 1024)
        for (i in 1..33) {
            assertTrue("Write $i must fit (skip guard is per record)", write(p, "big-%03d".format(i), big, lastAccessed = fixedNow - (100 - i)))
        }

        assertFalse("Oldest record evicted for bytes", available(p, "big-001"))
        assertTrue("Newest record retained", available(p, "big-033"))
        assertTrue(available(p, "big-032"))
        // No partial rows: a survivor reads a complete payload.
        val survivor = vault.readConversationSnapshot(p, "big-033", nowMillis = fixedNow)
        assertTrue(survivor is CacheReadResult.Available)
        val row = (survivor as CacheReadResult.Available).value.snapshot.rows.single()
        assertTrue(row.content.length == big.length)
    }

    @Test
    fun globalByteCap_32MiB_evictsAcrossPartitions() = runBlocking {
        seed()
        val pA = partitionOf(profileLocalId = "conn-1")
        val big = "g".repeat(ConversationCacheV2.MAX_SNAPSHOT_BYTES - 1024)

        // Partition A: 30 × ~780 KiB ≈ 23.4 MiB — under its OWN 24 MiB cap.
        for (i in 1..30) {
            assertTrue(write(pA, "ga-%03d".format(i), big, lastAccessed = fixedNow - (100 - i)))
        }

        val pB = partitionOf(profileLocalId = "conn-2")
        // Partition B pushes the cross-partition total past 32 MiB
        // (30 × ~780 KiB = 23.4 MiB + 15 × ~780 KiB = 11.7 MiB ⇒ ~35.1 MiB).
        for (i in 1..15) {
            assertTrue(vault.replaceConversationSnapshot(recordFor(pB, "gb-%03d".format(i), big, fixedNow - (50 - i)), pB))
        }

        vault.setActiveProfileId("conn-1")
        assertFalse(
            "Global budget evicts A's oldest even though A is under its own cap",
            available(pA, "ga-001", v = vault)
        )
        vault.setActiveProfileId("conn-2")
        assertTrue(available(pB, "gb-015", v = vault))
    }

    @Test
    fun oversizeWrite_skipsEntireRecord_leavingExistingRowsIntact() = runBlocking {
        seed()
        val p = partitionOf()
        assertTrue(write(p, "keep-1", "one", fixedNow))
        assertTrue(write(p, "keep-2", "two", fixedNow))

        val oversize = "z".repeat(ConversationCacheV2.MAX_SNAPSHOT_BYTES + 1)
        assertFalse("Over the 768 KiB remote cap ⇒ Skipped", write(p, "skip-1", oversize, fixedNow))
        assertTrue(available(p, "keep-1"))
        assertTrue(available(p, "keep-2"))
        assertFalse("No partial rows for the skipped record", available(p, "skip-1"))
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
