package com.hermes.bot.data.local

import com.hermes.bot.data.gateway.json.GatewayJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * PR-2 TEST-06: ContextProfileCacheV2 vault I/O — TTL boundary, entry/byte caps,
 * exact AAD/filename literals, corruption isolation, future-schema quarantine.
 */
class EncryptedVaultRosterCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    private var clockMs: Long = System.currentTimeMillis()
    private var wallMs: Long = System.currentTimeMillis()
    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("roster_cache_io_test")
        engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        clockMs = System.currentTimeMillis()
        wallMs = clockMs
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = engine,
            elapsedRealtimeMillis = { clockMs },
            wallClockMillis = { wallMs }
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun recordId(p: String, o: String, e: String, w: String) =
        "context-profiles:$p:$o:$e:$w"

    private fun rosterFile(p: String, o: String, e: String, w: String): File =
        File(
            File(File(File(storageDir, "cache"), sha256Hex(p)), sha256Hex("$p:$o")),
            "roster_${sha256Hex(recordId(p, o, e, w))}.enc"
        )

    private fun rosterAad(p: String, o: String, e: String, w: String, schema: Int = 2): ByteArray =
        "CONTEXT_PROFILE_CACHE:$schema:${recordId(p, o, e, w)}:$p:$o:$e:$w".toByteArray(Charsets.UTF_8)

    private suspend fun seedPartition(workspace: String? = null): CacheAccessPartition {
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
        workspace?.let { vault.saveSelectedContextProfile(it) }
        return (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
    }

    private fun rosterCache(
        partition: CacheAccessPartition,
        capturedAt: Long,
        entries: List<CachedContextProfile>,
        selectedName: String? = null
    ): ContextProfileCacheV2 {
        val base = ContextProfileCacheV2(
            recordId = "",
            profileLocalId = partition.profileLocalId,
            originSha256 = partition.originSha256,
            cacheEpoch = partition.cacheEpoch,
            workspaceScopeTag = partition.workspaceScopeTag,
            createdAtMillis = capturedAt,
            updatedAtMillis = capturedAt,
            capturedAtEpochMillis = capturedAt,
            selectedWorkspaceName = selectedName,
            entries = entries
        )
        return base.copy(recordId = base.recordId())
    }

    private fun craftPayload(
        partition: CacheAccessPartition,
        schema: Int = 2,
        selectedName: String? = null,
        entries: List<Map<String, Any?>> = listOf(
            mapOf("name" to "alpha-ws", "isRunning" to false, "isActive" to true)
        ),
        capturedAt: Long = wallMs
    ): ByteArray {
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to schema,
            "recordId" to recordId(
                partition.profileLocalId,
                partition.originSha256,
                partition.cacheEpoch,
                partition.workspaceScopeTag
            ),
            "profileLocalId" to partition.profileLocalId,
            "originSha256" to partition.originSha256,
            "cacheEpoch" to partition.cacheEpoch,
            "workspaceScopeTag" to partition.workspaceScopeTag,
            "createdAtMillis" to capturedAt,
            "updatedAtMillis" to capturedAt,
            "capturedAtEpochMillis" to capturedAt,
            "selectedWorkspaceName" to selectedName,
            "entries" to entries
        )
        return GatewayJson.serialize(GatewayJson.fromAny(map)).toByteArray(Charsets.UTF_8)
    }

    private fun writeCrafted(partition: CacheAccessPartition, payload: ByteArray, aad: ByteArray): File {
        val file = rosterFile(
            partition.profileLocalId,
            partition.originSha256,
            partition.cacheEpoch,
            partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        file.writeBytes(engine.encrypt(payload, aad))
        return file
    }

    // ---- TTL boundary (24h from capturedAtEpochMillis) ----

    @Test
    fun rosterTtl_readableUntil24h_thenExpiredAndDeleted() = runBlocking {
        val partition = seedPartition(workspace = "alpha")
        val t0 = wallMs
        vault.saveContextProfileCache(
            rosterCache(
                partition, t0,
                listOf(CachedContextProfile(name = "alpha-ws")),
                selectedName = "alpha"
            ),
            partition
        )

        // Fresh capture reads Available.
        assertTrue(vault.readContextProfileCache(partition) is CacheReadResult.Available)

        // +23h59m still readable (<= 24h).
        wallMs = t0 + (23L * 60 + 59) * 60_000
        assertTrue(
            "Roster must remain readable at +23h59m",
            vault.readContextProfileCache(partition) is CacheReadResult.Available
        )

        // +24h01m expired; a second read proves the record was deleted.
        wallMs = t0 + (24L * 60 + 1) * 60_000
        assertEquals(
            "Roster must expire after 24h",
            CacheReadResult.Expired,
            vault.readContextProfileCache(partition)
        )
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partition))
    }

    // ---- Caps ----

    @Test
    fun rosterEntryCap_101Entries_skipsWriteSilently() = runBlocking {
        val partition = seedPartition()
        val entries = (1..101).map { CachedContextProfile(name = "w$it") }
        vault.saveContextProfileCache(rosterCache(partition, wallMs, entries), partition)

        val file = rosterFile(
            partition.profileLocalId,
            partition.originSha256,
            partition.cacheEpoch,
            partition.workspaceScopeTag
        )
        assertFalse("101 entries must skip the write entirely", file.exists())
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partition))
    }

    @Test
    fun rosterByteCap_oversizeDtoserialize64KiB_skipsWriteSilently() = runBlocking {
        val partition = seedPartition()
        val bigName = "x".repeat(1000)
        val entries = (1..80).map { CachedContextProfile(name = "w${it}_$bigName") }
        assertTrue(
            "Fixture must exceed the 64 KiB DTO cap by name bytes alone",
            entries.sumOf { it.name.length } > ContextProfileCacheV2.MAX_PAYLOAD_BYTES
        )

        vault.saveContextProfileCache(rosterCache(partition, wallMs, entries), partition)

        val file = rosterFile(
            partition.profileLocalId,
            partition.originSha256,
            partition.cacheEpoch,
            partition.workspaceScopeTag
        )
        assertFalse("An oversize DTO must skip the write silently", file.exists())
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(partition))
    }

    // ---- Exact plan literals: a record crafted with the plan's exact AAD
    //      at the plan's exact filename must decrypt (pins both strings). ----

    @Test
    fun craftedRecord_withExactPlanAadAndFilename_readsAvailable() = runBlocking {
        val partition = seedPartition(workspace = "alpha")
        val expectedRecordId = recordId(
            partition.profileLocalId,
            partition.originSha256,
            partition.cacheEpoch,
            partition.workspaceScopeTag
        )
        val expectedAadString =
            "CONTEXT_PROFILE_CACHE:2:$expectedRecordId:${partition.profileLocalId}:" +
                "${partition.originSha256}:${partition.cacheEpoch}:${partition.workspaceScopeTag}"
        assertTrue(expectedAadString.contains("CONTEXT_PROFILE_CACHE:2:context-profiles:"))

        val file = writeCrafted(
            partition,
            craftPayload(partition, selectedName = "alpha"),
            expectedAadString.toByteArray(Charsets.UTF_8)
        )
        assertEquals("roster_${sha256Hex(expectedRecordId)}.enc", file.name)

        val read = vault.readContextProfileCache(partition)
        assertTrue(
            "Vault must use the plan's exact CONTEXT_PROFILE_CACHE:2 AAD (got $read)",
            read is CacheReadResult.Available
        )
    }

    // ---- Corruption / wrong AAD delete only the target; sibling stays Available ----

    @Test
    fun corruptAndWrongAad_deleteTargetOnly_otherWorkspaceStillReadsAvailable() = runBlocking {
        val partA = seedPartition(workspace = "alpha")
        vault.saveContextProfileCache(
            rosterCache(
                partA, wallMs,
                listOf(CachedContextProfile(name = "alpha-ws")),
                selectedName = "alpha"
            ),
            partA
        )
        vault.saveSelectedContextProfile("beta")
        val partB = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        vault.saveContextProfileCache(
            rosterCache(
                partB, wallMs,
                listOf(CachedContextProfile(name = "beta-ws")),
                selectedName = "beta"
            ),
            partB
        )

        val fileA = rosterFile(
            partA.profileLocalId, partA.originSha256, partA.cacheEpoch, partA.workspaceScopeTag
        )
        val fileB = rosterFile(
            partB.profileLocalId, partB.originSha256, partB.cacheEpoch, partB.workspaceScopeTag
        )
        assertTrue(fileA.exists() && fileB.exists())

        // AAD-swapped: workspace A's payload encrypted under workspace B's AAD.
        fileA.writeBytes(
            engine.encrypt(
                craftPayload(partA, selectedName = "alpha"),
                rosterAad(
                    partB.profileLocalId, partB.originSha256, partB.cacheEpoch, partB.workspaceScopeTag
                )
            )
        )
        assertEquals(CacheReadResult.Corrupt, vault.readContextProfileCache(partA))
        assertFalse("Corrupt record must be deleted", fileA.exists())
        assertTrue(
            "Sibling workspace roster must stay readable",
            vault.readContextProfileCache(partB) is CacheReadResult.Available
        )

        // Plain corruption: garbage bytes — same isolation.
        fileA.parentFile?.mkdirs()
        fileA.writeBytes("not-a-ciphertext".toByteArray())
        assertEquals(CacheReadResult.Corrupt, vault.readContextProfileCache(partA))
        assertFalse(fileA.exists())
        assertTrue(
            vault.readContextProfileCache(partB) is CacheReadResult.Available
        )
        // Deleting one subtree must never touch the other origin's directory.
        assertTrue(fileB.exists())
    }

    // ---- Future schema is quarantined, never parsed ----

    @Test
    fun futureSchemaVersion_quarantinedAndNeverServed() = runBlocking {
        val partition = seedPartition(workspace = "alpha")
        val file = writeCrafted(
            partition,
            craftPayload(partition, schema = 3, selectedName = "alpha"),
            rosterAad(
                partition.profileLocalId,
                partition.originSha256,
                partition.cacheEpoch,
                partition.workspaceScopeTag
            )
        )
        val quarantine = File(file.parentFile, file.name + ".quarantine")

        assertEquals(
            "Future schema must surface Unavailable, not a parse failure",
            CacheReadResult.Unavailable,
            vault.readContextProfileCache(partition)
        )
        assertFalse("Original must be moved aside", file.exists())
        assertTrue("Quarantine residue must persist", quarantine.exists())

        // Residue keeps the partition Unavailable (never Absent, never served).
        assertEquals(CacheReadResult.Unavailable, vault.readContextProfileCache(partition))
    }
}
