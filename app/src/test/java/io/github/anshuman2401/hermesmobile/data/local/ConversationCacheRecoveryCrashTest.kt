package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Charter T-07, plan §9 row (b): fault injection at every crash-critical
 * `CacheWriteStage` via the observer seam — crash, re-open, recovery behaves
 * deterministically; cross-partition isolation asserted in every scenario.
 */
class ConversationCacheRecoveryCrashTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private val originA = "http://127.0.0.1:9301"
    private val originB = "http://127.0.0.1:9302"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("recovery_crash_test")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Re-opened vaults must share the SAME key material — a fresh
    // InMemoryMasterKeyProvider per instance generates a new random key and
    // every persisted file becomes undecryptable (NO_ACTIVE_PROFILE).
    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    private fun vault(observer: (CacheWriteStage) -> Unit = {}): EncryptedVault = EncryptedVault(
        storageDir,
        engine,
        cacheStageObserver = observer
    )

    private suspend fun seedProfiles(v: EncryptedVault) {
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

    private suspend fun partitionOf(v: EncryptedVault, profileLocalId: String): CacheAccessPartition {
        v.setActiveProfileId(profileLocalId)
        val result = v.activeAccessPartition()
        assertTrue("partition must resolve: $result", result is CacheAccessPartitionResult.Available)
        return (result as CacheAccessPartitionResult.Available).partition
    }

    private fun recordFor(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        capturedAt: Long = System.currentTimeMillis()
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
            capturedAtMillis = capturedAt,
            messagesOmitted = false,
            clientTruncated = false,
            rows = listOf(
                CachedRemoteHistoryRow(0, "assistant", body, null, null, null, capturedAt)
            )
        ),
        lastAccessedAtMillis = capturedAt,
        createdAtMillis = capturedAt,
        updatedAtMillis = capturedAt
    )

    /** Partition B's snapshot for the every-scenario isolation assertion. */
    private suspend fun seedIsolationAnchor(v: EncryptedVault): String {
        val pB = partitionOf(v, "conn-2")
        assertTrue(v.replaceConversationSnapshot(recordFor(pB, "sess-B", "B body anchor"), pB))
        v.setActiveProfileId("conn-1")
        return "B body anchor"
    }

    private suspend fun assertIsolationIntact(v: EncryptedVault, expectedB: String) {
        val pB = partitionOf(v, "conn-2")
        val readB = v.readConversationSnapshot(pB, "sess-B")
        assertTrue("Partition B must stay readable: $readB", readB is CacheReadResult.Available)
        val bodyB = (readB as CacheReadResult.Available).value.snapshot.rows.single().content
        assertTrue("B must never see A's content: '$bodyB'", bodyB == expectedB)
        v.setActiveProfileId("conn-1")
    }

    /** Decrypted index JSON — proves exactly which entries the §5.3 repair kept or dropped. */
    private fun indexJsonOf(p: CacheAccessPartition): String {
        val recordId = ConversationPartitionIndexV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag
        )
        val file = File(File(storageDir, "conversation_indexes"), "index_${sha256Hex(recordId)}.enc")
        val bytes = file.readBytes()
        val idLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        assertTrue("index envelope must carry its record id (idLen=$idLen, len=${bytes.size})", idLen in 1 until bytes.size)
        val aad = (
            "CONVERSATION_INDEX:2:$recordId:${p.profileLocalId}:${p.originSha256}:" +
                "${p.cacheEpoch}:${p.workspaceScopeTag}"
            ).toByteArray(Charsets.UTF_8)
        val plain = engine.decrypt(bytes.copyOfRange(4 + idLen, bytes.size), aad).getOrThrow()
        return String(plain, Charsets.UTF_8)
    }

    private fun contentFileOf(p: CacheAccessPartition, sid: String): File {
        val recordId = ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
        )
        return File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        ).resolve("conversation_${sha256Hex(recordId)}.enc")
    }

    @Test
    fun crashAfterRecordWriteBeforeIndex_leavesNoReadablePartialState() = runBlocking {
        // The observer only arms for the 'sess-A' write — seeding must not trip it.
        var armed = false
        val crashing = vault { stage ->
            // RECORD_WRITTEN fires POST content-durable, BEFORE the index write —
            // this is the exact "content written, no index entry" crash window.
            if (armed && stage == CacheWriteStage.STAGE_CONVERSATION_RECORD_WRITTEN
            ) throw IllegalStateException("crash: between content and index")
        }
        seedProfiles(crashing)
        val expectedB = seedIsolationAnchor(crashing)
        val pA = partitionOf(crashing, "conn-1")
        armed = true

        try {
            crashing.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA)
            throw AssertionError("Stage observer must abort the write")
        } catch (expected: IllegalStateException) {
            // simulated crash between durable steps
        }

        // Re-open: recovery runs (index validation → orphan scan → catalog rebuild).
        val reopened = vault()
        val pA2 = partitionOf(reopened, "conn-1")
        val readA = reopened.readConversationSnapshot(pA2, "sess-A")
        assertTrue("Record without index entry must NOT read: $readA", readA is CacheReadResult.Absent)
        assertIsolationIntact(reopened, expectedB)
    }

    @Test
    fun crashAfterIndexBeforeCatalog_recoveryRebuildsCatalogOnReopen() = runBlocking {
        var armed = false
        val crashing = vault { stage ->
            // INDEX_WRITTEN fires POST index-durable, BEFORE the catalog write
            // is attempted — exact "index committed, catalog missing" window.
            if (armed && stage == CacheWriteStage.STAGE_CONVERSATION_INDEX_WRITTEN)
                throw IllegalStateException("crash: before catalog commit")
        }
        seedProfiles(crashing)
        val expectedB = seedIsolationAnchor(crashing)
        val pA = partitionOf(crashing, "conn-1")
        // Drop the catalog seeded by the B write so the A-crash's "catalog not
        // yet committed" state is observable (REQ-01: catalog is written LAST).
        storageDir.listFiles()!!
            .filter { it.name.startsWith("global_catalog_") && it.name.endsWith(".enc") }
            .forEach { assertTrue("seeded catalog removable: ${it.name}", it.delete()) }
        armed = true

        try {
            crashing.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA)
            throw AssertionError("Stage observer must abort the write")
        } catch (expected: IllegalStateException) {
            // record + index durable, catalog missing
        }

        val catalog = storageDir.listFiles()!!.filter { it.name.startsWith("global_catalog_") }
        assertTrue("Catalog must be absent after the crash", catalog.none { it.extension == "enc" })

        val reopened = vault()
        val pA2 = partitionOf(reopened, "conn-1")
        val readA = reopened.readConversationSnapshot(pA2, "sess-A")
        assertTrue("Direct partition read stays authoritative: $readA", readA is CacheReadResult.Available)
        val rebuilt = storageDir.listFiles()!!.filter { it.name.startsWith("global_catalog_") && it.extension == "enc" }
        assertTrue("Recovery rebuilds the catalog deterministically", rebuilt.isNotEmpty())
        assertIsolationIntact(reopened, expectedB)
    }

    @Test
    fun crashDuringPurgeDeleteSteps_reopenRecoveryConvergesToCleanState() = runBlocking {
        var armed = false
        val crashing = vault { stage ->
            if (armed && stage == CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
                throw IllegalStateException("crash: during purge")
        }
        seedProfiles(crashing)
        seedIsolationAnchor(crashing)
        val pA = partitionOf(crashing, "conn-1")
        assertTrue(crashing.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))
        armed = true

        val result = crashing.clearCache()
        // PR-5 REQ-02/OQ-02: the injected abort is carried as residue instead
        // of being thrown (the pre-PR-5 contract propagated the observer
        // exception); the phases after the abort never ran.
        assertTrue(
            "Stage observer must abort the purge as residue, got $result",
            result is CacheClearResult.Residual
        )
        assertTrue(
            "Abort named at the conversation-cache phase, got $result",
            result is CacheClearResult.Residual &&
                result.phase == CacheClearResiduePhase.CONVERSATION_CACHE
        )

        // Global reset (REQ-08) is authoritative for EVERY partition — recovery
        // must converge to a clean state with nothing stale readable anywhere.
        val reopened = vault()
        val pA2 = partitionOf(reopened, "conn-1")
        val readA = reopened.readConversationSnapshot(pA2, "sess-A")
        assertTrue("After aborted purge nothing stale may read: $readA", readA !is CacheReadResult.Available)
        val pB2 = partitionOf(reopened, "conn-2")
        val readB = reopened.readConversationSnapshot(pB2, "sess-B")
        assertTrue("Reset is authoritative across partitions too: $readB", readB !is CacheReadResult.Available)
        val staleContent = storageDir.walkTopDown().filter {
            it.isFile && it.name.startsWith("conversation_") && it.name.endsWith(".enc")
        }.toList()
        assertTrue("No conversation content may survive the aborted reset: $staleContent", staleContent.isEmpty())
    }

    @Test
    fun missingContentFile_recoveryDropsStaleIndexEntry() = runBlocking {
        val v = vault()
        seedProfiles(v)
        val expectedB = seedIsolationAnchor(v)
        val pA = partitionOf(v, "conn-1")
        assertTrue(v.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))

        // Simulate a torn purge: content gone, index entry left behind.
        val contentDir = File(
            File(File(storageDir, "cache"), sha256Hex(pA.profileLocalId)),
            sha256Hex("${pA.profileLocalId}:${pA.originSha256}")
        )
        contentDir.listFiles()!!.filter { !it.name.startsWith("cache_access_") }
            .forEach { assertTrue(it.delete()) }

        // The torn state: the index STILL references an entry with no content.
        val hashA = sha256Hex(
            ConversationCacheV2.recordIdFor(
                pA.profileLocalId, pA.originSha256, pA.cacheEpoch, pA.workspaceScopeTag, "sess-A"
            )
        )
        assertTrue("fixture: pre-recovery index references the stale entry", indexJsonOf(pA).contains(hashA))
        assertFalse(
            "fixture: no content survives the torn purge",
            contentFileOf(pA, "sess-A").exists()
        )

        // Re-open triggers §5.3 entry-level repair — the index must be
        // REWRITTEN without the stale entry (asserting only `read !is Available`
        // is trivially true even if repair removed nothing).
        val reopened = vault()
        val pA2 = partitionOf(reopened, "conn-1")
        val readA = reopened.readConversationSnapshot(pA2, "sess-A")
        assertTrue("Stale entry must not resolve: $readA", readA !is CacheReadResult.Available)
        assertFalse(
            "Recovery must drop the stale index entry — still referenced: ${indexJsonOf(pA2)}",
            indexJsonOf(pA2).contains(hashA)
        )
        assertFalse(
            "Entry file must not exist after recovery",
            contentFileOf(pA2, "sess-A").exists()
        )
        assertIsolationIntact(reopened, expectedB)
    }

    // §5.3 entry-level repair (plan row (b)): a VALID index referencing an
    // EXPIRED entry is rewritten WITHOUT it and the expired content file is
    // DELETED — the 7-day retention limit must hold across recovery, and stale
    // bytes must not keep inflating bytesEstimate/count-driven caps.
    @Test
    fun expiredEntry_recoveryRemovesEntryAndDeletesContent() = runBlocking {
        val v = vault()
        seedProfiles(v)
        seedIsolationAnchor(v)
        val pA = partitionOf(v, "conn-1")
        val expiredAt = System.currentTimeMillis() - ConversationCacheV2.MAX_TTL_MILLIS - 60_000L
        assertTrue(v.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body", capturedAt = expiredAt), pA))

        val hashA = sha256Hex(
            ConversationCacheV2.recordIdFor(
                pA.profileLocalId, pA.originSha256, pA.cacheEpoch, pA.workspaceScopeTag, "sess-A"
            )
        )
        assertTrue("fixture: entry is indexed", indexJsonOf(pA).contains(hashA))
        assertTrue("fixture: expired content is on disk", contentFileOf(pA, "sess-A").exists())

        // Re-open runs recovery: entry-level repair must rewrite the index and
        // delete the expired content (retention breach otherwise).
        val reopened = vault()
        val pA2 = partitionOf(reopened, "conn-1")
        assertFalse(
            "Expired entry must be dropped from the index (§5.3): ${indexJsonOf(pA2)}",
            indexJsonOf(pA2).contains(hashA)
        )
        assertFalse(
            "Expired content must be deleted by recovery (7-day retention)",
            contentFileOf(pA2, "sess-A").exists()
        )
        val readA = reopened.readConversationSnapshot(pA2, "sess-A")
        assertTrue("Nothing expired may serve: $readA", readA !is CacheReadResult.Available)
        assertIsolationIntact(reopened, "B body anchor")
    }
}
