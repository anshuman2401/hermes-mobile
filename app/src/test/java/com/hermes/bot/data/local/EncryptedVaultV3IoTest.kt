package com.hermes.bot.data.local

import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.session.invalidActivityResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * REQ-04/REQ-10 (TEST-04, TEST-07): V3 per-partition record I/O.
 *
 * Read order is enforced fail-closed: absent, future schema (quarantine),
 * corrupt/downgrade (delete), identity/workspace agreement, retention
 * (wall age / monotonic age / skew), payload caps. Writes are capped so a
 * successful save always round-trips.
 */
class EncryptedVaultV3IoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    private var clockMs: Long = System.currentTimeMillis()
    private val origin = "http://127.0.0.1:9119"

    private val eightDaysMs = 8L * 24 * 60 * 60 * 1000

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("v3_io_test")
        engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        clockMs = System.currentTimeMillis()
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = engine,
            elapsedRealtimeMillis = { clockMs }
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun recordId(p: String, o: String, e: String, w: String) =
        "session-summaries:$p:$o:$e:$w"

    /** Mirrors the on-disk caps the vault enforces for schema-3 records. */
    object SessionSummaryCacheV3 {
        const val MAX_SUMMARIES = 100
        const val MAX_PAYLOAD_BYTES = 262_144 // 256 KiB
    }

    private fun summaryFile(p: String, o: String, e: String, w: String): File =
        File(
            File(File(File(storageDir, "cache"), sha256Hex(p)), sha256Hex("$p:$o")),
            "summary_${sha256Hex(recordId(p, o, e, w))}.enc"
        )

    private fun summaryAad(p: String, o: String, e: String, w: String, schema: Int = 3): ByteArray =
        "SESSION_SUMMARY_CACHE:$schema:${recordId(p, o, e, w)}:$p:$o:$e:$w".toByteArray(Charsets.UTF_8)

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

    private fun sessionMaps(count: Int): List<Map<String, Any?>> = (1..count).map { i ->
        mapOf(
            "id" to "s$i",
            "title" to "Title $i",
            "preview" to "Preview $i",
            "started_at" to i.toDouble(),
            "message_count" to i,
            "source" to "android"
        )
    }

    private fun payload(
        schemaVersion: Int = 3,
        sessions: List<Map<String, Any?>> = sessionMaps(1),
        selectedWorkspaceName: String? = null,
        p: String = "conn-1",
        o: String = sha256Hex(origin),
        e: String = "epoch-test",
        w: String = "u",
        wall: Long = System.currentTimeMillis(),
        elapsed: Long = 1_000L,
        sync: Long = System.currentTimeMillis() / 1000
    ): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            linkedMapOf(
                "schemaVersion" to schemaVersion,
                "sessions" to sessions,
                "selectedWorkspaceName" to selectedWorkspaceName,
                "profileLocalId" to p,
                "originSha256" to o,
                "cacheEpoch" to e,
                "workspaceScopeTag" to w,
                "syncTimeEpochSeconds" to sync,
                "capturedAtWallMillis" to wall,
                "capturedAtElapsedMillis" to elapsed
            )
        )
    )

    @Test
    fun read_retainsFractionalActivityFromEncryptedV3() = runBlocking {
        val partition = seedPartition()
        seedEncryptedSummary(partition, activitySessionMaps())

        val read = vault.readSessionSummaryCache(partition)
        assertTrue(read is CacheReadResult.Available)
        val cache = (read as CacheReadResult.Available).value
        assertEquals(listOf("s1", "s2"), cache.sessions.map { it.id })
        assertEquals(listOf(1.0, 2.0), cache.sessions.map { it.startedAt })
        assertEquals(listOf(1000.125, 2000.875), cache.sessions.map { it.lastActive })
    }

    @Test
    fun saveThenRestart_retainsFractionalActivityAndOrderInEncryptedV3() = runBlocking {
        val partition = seedPartition()
        val cache = SessionSummaryCache(
            sessions = listOf(
                SessionSummary("s1", "Title 1", "Preview 1", 1.0, 1, "android", lastActive = 1000.125),
                SessionSummary("s2", "Title 2", "Preview 2", 2.0, 2, "android", lastActive = 2000.875)
            ),
            syncTimeEpochSeconds = 1234L,
            profileLocalId = partition.profileLocalId
        )

        vault.saveSessionSummaryCache(cache, partition)
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("Title 1"))
        val restarted = EncryptedVault(storageDir, engine, elapsedRealtimeMillis = { clockMs })
        val read = restarted.readSessionSummaryCache(partition)
        assertTrue(read is CacheReadResult.Available)
        val restored = (read as CacheReadResult.Available).value
        assertEquals(3, restored.schemaVersion)
        assertEquals(cache.syncTimeEpochSeconds, restored.syncTimeEpochSeconds)
        assertEquals(listOf("s1", "s2"), restored.sessions.map { it.id })
        assertEquals(listOf(1000.125, 2000.875), restored.sessions.map { it.lastActive })
    }

    @Test
    fun read_legacyV3WithoutActivity_remainsAvailableWithoutRewriting() = runBlocking {
        val partition = seedPartition()
        val file = seedEncryptedSummary(partition, sessionMaps(1))
        val bytes = file.readBytes()

        val read = vault.readSessionSummaryCache(partition)
        assertTrue(read is CacheReadResult.Available)
        val cache = (read as CacheReadResult.Available).value
        assertEquals(3, cache.schemaVersion)
        assertEquals(1234L, cache.syncTimeEpochSeconds)
        assertEquals("s1", cache.sessions.single().id)
        assertNull(cache.sessions.single().lastActive)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun read_missingNullMalformedAndNonfiniteActivityRemainUnknown() = runBlocking {
        val partition = seedPartition()
        // Preserve overflow-number lexemes in the synthetic fixture, rather than
        // serializing Double infinity as an invalid bare JSON token.
        val sessions = GatewayJson.parse(invalidActivityResponse)["sessions"]!!
            .asArrayOrNull()!!.list.map { it.asObjectOrNull()!!.map }
        seedEncryptedSummary(partition, sessions)

        val read = vault.readSessionSummaryCache(partition)
        assertTrue(read is CacheReadResult.Available)
        val rows = (read as CacheReadResult.Available).value.sessions
        assertEquals(11, rows.size)
        rows.forEach {
            assertEquals(42.5, it.startedAt!!, 0.0)
            assertNull(it.lastActive)
        }
    }

    @Test
    fun saveThenRead_nonfiniteActivityRemainsUnknown() = runBlocking {
        val partition = seedPartition()
        val values = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = values.mapIndexed { index, value ->
                    SessionSummary("s$index", "Synthetic", null, 42.5, 0, "android", lastActive = value)
                },
                profileLocalId = partition.profileLocalId
            ),
            partition
        )

        val read = vault.readSessionSummaryCache(partition)
        assertTrue(read is CacheReadResult.Available)
        val rows = (read as CacheReadResult.Available).value.sessions
        assertEquals(listOf("s0", "s1", "s2", "s3"), rows.map { it.id })
        rows.forEach { assertNull(it.lastActive) }
    }

    private fun activitySessionMaps(): List<Map<String, Any?>> =
        sessionMaps(2).mapIndexed { index, row ->
            row + ("last_active" to if (index == 0) 1000.125 else 2000.875)
        }

    private fun seedEncryptedSummary(
        partition: CacheAccessPartition,
        sessions: List<Map<String, Any?>>
    ): File {
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        val json = payload(
            sessions = sessions,
            p = partition.profileLocalId,
            o = partition.originSha256,
            e = partition.cacheEpoch,
            w = partition.workspaceScopeTag,
            elapsed = clockMs,
            sync = 1234L
        )
        file.writeBytes(engine.encrypt(json.toByteArray(Charsets.UTF_8), summaryAad(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )))
        return file
    }

    @Test
    fun saveThenRead_roundTripsUnderExactStorageLayout_withoutPlaintext() = runBlocking {
        val partition = seedPartition()
        val dto = SessionSummaryCache(
            sessions = listOf(SessionSummary("s1", "Secret Title", "Preview", 42.0, 3, "android")),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
            profileLocalId = "conn-1"
        )
        vault.saveSessionSummaryCache(dto, partition)

        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        assertTrue("V3 record must live at the partitioned layout path", file.exists())
        assertFalse(
            "Record must never be plaintext",
            String(file.readBytes(), Charsets.UTF_8).contains("Secret Title")
        )

        val read = vault.readSessionSummaryCache(partition)
        assertTrue("Round trip must be Available, was $read", read is CacheReadResult.Available)
        val value = (read as CacheReadResult.Available).value
        assertEquals(listOf("s1"), value.sessions.map { it.id })
        assertEquals("conn-1", value.profileLocalId)
        assertEquals(3, value.schemaVersion)
        assertEquals(null, value.cachedProfileName)
    }

    @Test
    fun saveThenRead_roundTripsWorkspaceScopedRecord() = runBlocking {
        val partition = seedPartition(workspace = "architect")
        assertEquals("w:${sha256Hex("architect")}", partition.workspaceScopeTag)
        val dto = SessionSummaryCache(
            sessions = listOf(SessionSummary("w1", "Scoped", null, 1.0, 1, "tui")),
            syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
            profileLocalId = "conn-1",
            cachedProfileName = "architect"
        )
        vault.saveSessionSummaryCache(dto, partition)

        val read = vault.readSessionSummaryCache(partition)
        assertTrue("Scoped round trip must be Available, was $read", read is CacheReadResult.Available)
        val value = (read as CacheReadResult.Available).value
        assertEquals("architect", value.cachedProfileName)
        assertEquals("w:${sha256Hex("architect")}", partition.workspaceScopeTag)
    }

    @Test
    fun read_unknownFutureSchema_quarantinesOnlyThatFile_andStaysUnavailable() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        file.writeBytes(
            engine.encrypt(payload(schemaVersion = 4).toByteArray(Charsets.UTF_8), summaryAad(
                partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
            ))
        )

        val read = vault.readSessionSummaryCache(partition)
        assertEquals("Future schema must return Unavailable", CacheReadResult.Unavailable, read)
        assertFalse("Original file must be renamed aside", file.exists())
        val residue = File(file.parentFile, file.name + ".quarantine")
        assertTrue("Quarantine residue must be preserved for audit", residue.exists())

        val second = vault.readSessionSummaryCache(partition)
        assertEquals("Quarantined partition must stay Unavailable, never silently Absent", CacheReadResult.Unavailable, second)
    }

    @Test
    fun read_downgradedSchema_deletesAndReturnsCorrupt() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        file.writeBytes(
            engine.encrypt(payload(schemaVersion = 2).toByteArray(Charsets.UTF_8), summaryAad(
                partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
            ))
        )

        assertEquals(CacheReadResult.Corrupt, vault.readSessionSummaryCache(partition))
        assertFalse("Corrupt record must be deleted", file.exists())
    }

    @Test
    fun read_profileIdentityMismatch_deletesAndReturnsPartitionMismatch() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        // Payload claims a different profile while the AAD/file identity is the caller's partition.
        file.writeBytes(
            engine.encrypt(payload(p = "other-conn").toByteArray(Charsets.UTF_8), summaryAad(
                partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
            ))
        )

        assertEquals(CacheReadResult.PartitionMismatch, vault.readSessionSummaryCache(partition))
        assertFalse(file.exists())
    }

    @Test
    fun read_workspaceBindingMismatch_returnsPartitionMismatch() = runBlocking {
        val partition = seedPartition() // unscoped: workspaceScopeTag == "u"
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        val otherWorkspace = "w:${sha256Hex("builder")}"
        // AAD follows the caller partition, payload claims another workspace — must fail closed.
        file.writeBytes(
            engine.encrypt(
                payload(w = otherWorkspace, selectedWorkspaceName = "builder").toByteArray(Charsets.UTF_8),
                summaryAad(
                    partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
                )
            )
        )

        assertEquals(CacheReadResult.PartitionMismatch, vault.readSessionSummaryCache(partition))
        assertFalse(file.exists())
    }

    @Test
    fun read_expiresOnMonotonicAge_andDeletesRecord() = runBlocking {
        val partition = seedPartition()
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("s1", "T", null, 1.0, 1, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1"
            ),
            partition
        )
        assertTrue(vault.readSessionSummaryCache(partition) is CacheReadResult.Available)

        clockMs += eightDaysMs
        assertEquals(CacheReadResult.Expired, vault.readSessionSummaryCache(partition))
        assertEquals(
            "Expired record must be gone afterwards",
            CacheReadResult.Absent,
            vault.readSessionSummaryCache(partition)
        )
    }

    @Test
    fun read_expiresOnClockSkew_withoutWallAging() = runBlocking {
        val partition = seedPartition()
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("s1", "T", null, 1.0, 1, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1"
            ),
            partition
        )
        // Monotonic clock races 6 minutes ahead of the wall clock: beyond the 5-minute
        // skew tolerance — one clock went backwards — record must not be served.
        clockMs += 6L * 60 * 1000
        assertEquals(CacheReadResult.Expired, vault.readSessionSummaryCache(partition))
    }

    @Test
    fun read_corruptBytes_deletesAndReturnsCorrupt() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9))

        assertEquals(CacheReadResult.Corrupt, vault.readSessionSummaryCache(partition))
        assertFalse(file.exists())
    }

    @Test
    fun read_countOverCap_deletesAndReturnsCorrupt() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        file.writeBytes(
            engine.encrypt(
                payload(
                    sessions = sessionMaps(SessionSummaryCacheV3.MAX_SUMMARIES + 1),
                    e = partition.cacheEpoch
                )
                    .toByteArray(Charsets.UTF_8),
                summaryAad(
                    partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
                )
            )
        )

        assertEquals(CacheReadResult.Corrupt, vault.readSessionSummaryCache(partition))
        assertFalse(file.exists())
    }

    @Test
    fun read_payloadOverSizeCap_deletesAndReturnsCorrupt() = runBlocking {
        val partition = seedPartition()
        val file = summaryFile(
            partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
        )
        file.parentFile?.mkdirs()
        val oversized = sessionMaps(1).map { it + ("preview" to "x".repeat(SessionSummaryCacheV3.MAX_PAYLOAD_BYTES)) }
        file.writeBytes(
            engine.encrypt(
                payload(sessions = oversized).toByteArray(Charsets.UTF_8),
                summaryAad(
                    partition.profileLocalId, partition.originSha256, partition.cacheEpoch, partition.workspaceScopeTag
                )
            )
        )

        assertEquals(CacheReadResult.Corrupt, vault.readSessionSummaryCache(partition))
        assertFalse(file.exists())
    }

    @Test
    fun save_capsSummaryCountAtMaximum_soRoundTripAlwaysSucceeds() = runBlocking {
        val partition = seedPartition()
        val many = (1..SessionSummaryCacheV3.MAX_SUMMARIES + 50).map { i ->
            SessionSummary("s$i", "T$i", null, i.toDouble(), 1, "android")
        }
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = many,
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1"
            ),
            partition
        )

        val read = vault.readSessionSummaryCache(partition)
        assertTrue("Capped write must remain readable, was $read", read is CacheReadResult.Available)
        val value = (read as CacheReadResult.Available).value
        assertEquals(SessionSummaryCacheV3.MAX_SUMMARIES, value.sessions.size)
        // Head (newest-first input order) is preserved; the tail is dropped.
        assertEquals("s1", value.sessions.first().id)
    }

    @Test
    fun oversizedSingleEntry_isSkippedSilently_perOQ04() = runBlocking {
        val partition = seedPartition()
        // A single entry over the 256 KiB budget: the cap loop cannot trim it.
        val oversized = SessionSummary(
            "huge-1",
            "Huge",
            "x".repeat(SessionSummaryCacheV3.MAX_PAYLOAD_BYTES),
            1.0,
            1,
            "tui"
        )

        // OQ-04 (review BLOCKER-2): an oversized write is SKIPPED silently —
        // never thrown. A cache write is availability, not a gate.
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(oversized),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1"
            ),
            partition
        )

        assertFalse(
            "Skipped write must not create a record",
            summaryFile(
                partition.profileLocalId,
                partition.originSha256,
                partition.cacheEpoch,
                partition.workspaceScopeTag
            ).exists()
        )
        assertTrue(
            "Skipped write leaves the partition empty (fail-closed on read)",
            vault.readSessionSummaryCache(partition) is CacheReadResult.Absent
        )
    }
}
