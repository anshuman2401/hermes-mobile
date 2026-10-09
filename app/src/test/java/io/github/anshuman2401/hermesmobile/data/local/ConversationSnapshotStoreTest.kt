package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Charter T-11 (conversation part) + T-09 (TTL/byte caps), plan §9 row (a):
 * REQ-01/REQ-02 write→read conformance of the offline conversation snapshot
 * store, all file-backed (REQ-11).
 */
class ConversationSnapshotStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val stages = mutableListOf<CacheWriteStage>()
    private var fixedNow = 10_000_000_000_000L
    private val origin = "http://127.0.0.1:9211"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("snapshot_store_test")
        stages.clear()
        fixedNow = 10_000_000_000_000L
        vault = newVault(stages::add)
    }

    // Hoisted so fixture surgery (decrypt -> transform -> re-encrypt) runs under
    // the vault's exact key material — the same instance the vault uses.
    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    private fun newVault(observer: (CacheWriteStage) -> Unit): EncryptedVault = EncryptedVault(
        storageDir,
        engine,
        wallClockMillis = { fixedNow },
        cacheStageObserver = observer
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun seed() {
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

    private suspend fun partition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun cacheFor(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        capturedAt: Long = fixedNow,
        schemaVersion: Int = ConversationCacheV2.CURRENT_SCHEMA_VERSION,
        workspaceName: String? = null
    ) = ConversationCacheV2(
        schemaVersion = schemaVersion,
        recordId = ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
        ),
        profileLocalId = p.profileLocalId,
        originSha256 = p.originSha256,
        cacheEpoch = p.cacheEpoch,
        workspaceScopeTag = p.workspaceScopeTag,
        durableSessionId = sid,
        selectedWorkspaceName = workspaceName,
        snapshot = CachedRemoteHistorySnapshot(
            snapshotId = UUID.nameUUIDFromBytes(sid.toByteArray()).toString(),
            capturedAtMillis = capturedAt,
            messagesOmitted = false,
            clientTruncated = false,
            rows = listOf(
                CachedRemoteHistoryRow(
                    ordinal = 0, role = "user", content = body,
                    toolName = null, toolContext = null, reasoning = null,
                    createdAtMillis = capturedAt
                )
            )
        ),
        lastAccessedAtMillis = capturedAt,
        createdAtMillis = capturedAt,
        updatedAtMillis = capturedAt
    )

    private suspend fun replace(p: CacheAccessPartition, sid: String, body: String, capturedAt: Long = fixedNow): Boolean =
        vault.replaceConversationSnapshot(cacheFor(p, sid, body, capturedAt), p)

    private fun contentDir(p: CacheAccessPartition): File = File(
        File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
        sha256Hex("${p.profileLocalId}:${p.originSha256}")
    )

    private fun contentFile(p: CacheAccessPartition): File =
        contentDir(p).listFiles()!!.first { !it.name.startsWith("cache_access_") }

    private fun allEncryptedFiles(): List<File> = storageDir.walkTopDown().filter { it.isFile }.toList()

    private fun recordIdFor(p: CacheAccessPartition, sid: String): String =
        ConversationCacheV2.recordIdFor(p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid)

    private fun conversationRecordFile(p: CacheAccessPartition, sid: String): File =
        File(contentDir(p), "conversation_${sha256Hex(recordIdFor(p, sid))}.enc")

    private fun conversationAadOf(p: CacheAccessPartition, sid: String): ByteArray =
        (
            "CONVERSATION:2:${recordIdFor(p, sid)}:${p.profileLocalId}:${p.originSha256}:" +
                "${p.cacheEpoch}:${p.workspaceScopeTag}:$sid"
            ).toByteArray(Charsets.UTF_8)

    private fun indexRecordIdFor(p: CacheAccessPartition): String =
        ConversationPartitionIndexV2.recordIdFor(p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag)

    private fun indexFileOf(p: CacheAccessPartition): File =
        File(File(storageDir, "conversation_indexes"), "index_${sha256Hex(indexRecordIdFor(p))}.enc")

    private fun indexAadOf(p: CacheAccessPartition): ByteArray =
        (
            "CONVERSATION_INDEX:2:${indexRecordIdFor(p)}:${p.profileLocalId}:${p.originSha256}:" +
                "${p.cacheEpoch}:${p.workspaceScopeTag}"
            ).toByteArray(Charsets.UTF_8)

    /** Plaintext JSON size of a record: file - 4 (len) - recordId - 32 (IV+tag). */
    private fun jsonSizeOf(file: File, recordId: String): Long =
        file.length() - 4L - recordId.length - 32L

    /**
     * Fixture surgery: decrypt an identified record, transform its JSON, and
     * re-encrypt it in the exact on-disk framing. Needed where the WRITE gate
     * legitimately refuses to mint a fixture (over-cap plaintext, future
     * schema) but the READ contract still defines an outcome for it.
     */
    private fun rewriteIdentified(file: File, recordId: String, aad: ByteArray, transform: (String) -> String) {
        val bytes = file.readBytes()
        val idLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals("envelope record id", recordId, String(bytes, 4, idLen, Charsets.UTF_8))
        val plain = String(engine.decrypt(bytes.copyOfRange(4 + idLen, bytes.size), aad).getOrThrow(), Charsets.UTF_8)
        val transformed = transform(plain)
        val ciphertext = engine.encrypt(transformed.toByteArray(Charsets.UTF_8), aad)
        file.writeBytes(
            java.nio.ByteBuffer.allocate(4 + recordId.length + ciphertext.size)
                .putInt(recordId.length)
                .put(recordId.toByteArray(Charsets.UTF_8))
                .put(ciphertext)
                .array()
        )
    }

    @Test
    fun writeReadRoundTrip_usesContentIndexCatalogOrder() = runBlocking {
        seed()
        val p = partition()
        stages.clear()

        assertTrue("Fresh write must succeed", replace(p, "sess-100", "Cached question"))

        val read = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue("Round trip must be readable: $read", read is CacheReadResult.Available)
        val cache = (read as CacheReadResult.Available).value
        assertEquals("sess-100", cache.durableSessionId)
        assertEquals(fixedNow, cache.snapshot.capturedAtMillis)
        assertEquals("Cached question", cache.snapshot.rows.single().content)
        assertFalse(cache.snapshot.messagesOmitted)

        // REQ-01 write order: content → index → catalog, exactly.
        val rec = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_RECORD_WRITTEN)
        val idx = stages.indexOfFirst { it.name.startsWith("STAGE_CONVERSATION_INDEX") }
        val cat = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_CATALOG_WRITTEN)
        assertTrue("Record stage fired (stages=$stages)", rec >= 0)
        assertTrue("Index stage fired (stages=$stages)", idx > rec)
        assertTrue("Catalog stage last (stages=$stages)", cat > idx)
    }

    @Test
    fun indexEntryMissing_isMissEvenWithContentFilePresent() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-100", "body"))

        // Delete every partition index envelope: the per-partition index is the
        // SOLE authority for existence — the content file alone proves nothing.
        File(storageDir, "conversation_indexes").listFiles()!!.forEach { assertTrue(it.delete()) }

        val read = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue("Index-absent must miss despite the file: $read", read is CacheReadResult.Absent)
        // §5.3 read-conformance (repair finding 4): with the INDEX itself gone
        // there is no validated authority that could authorize deletion — the
        // content stays INACCESSIBLE (missed on every read, retried by
        // recovery), never destroyed. Deletion applies only when the index is
        // VALID and merely lacks the entry.
        val partitionDir = File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        )
        assertTrue(
            "Content must remain (inaccessible), not deleted: ${partitionDir.listFiles()?.toList()}",
            partitionDir.listFiles()!!.any { !it.name.startsWith("cache_access_") }
        )
    }

    @Test
    fun filenameAadSwap_identityMismatch_deletesSwappedFile() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-100", "original body"))
        val original = contentFile(p)

        // Copy the record under ANOTHER identity's filename: header/AAD swap.
        val swappedId = ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, "sess-200"
        )
        val swapped = File(original.parentFile, original.name.replace(sha256Hex(
            ConversationCacheV2.recordIdFor(
                p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, "sess-100"
            )
        ), sha256Hex(swappedId)))
        original.copyTo(swapped, overwrite = true)

        val read = vault.readConversationSnapshot(p, "sess-200", nowMillis = fixedNow)
        assertTrue(
            "Swapped identity must fail closed (distinct reason), got: $read",
            read !is CacheReadResult.Available
        )
        assertFalse("Identity mismatch deletes the swapped file", swapped.exists())
        // The genuine record is untouched.
        val genuine = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue(genuine is CacheReadResult.Available)
    }

    @Test
    fun futureSchemaWrite_failsClosed_writesNoRecord() = runBlocking {
        seed()
        val p = partition()
        val cache = cacheFor(
            p, "sess-100", "body",
            schemaVersion = ConversationCacheV2.CURRENT_SCHEMA_VERSION + 1
        )
        val written = vault.replaceConversationSnapshot(cache, p)
        assertFalse("Unknown future schema must fail closed", written)
        val read = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue("Nothing may be readable after a rejected write", read is CacheReadResult.Absent)
    }

    @Test
    fun corruptCiphertext_neverFallsBackToPlaintextOrSoftwareKey() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-100", "SENTINEL_BODY_f3a9"))
        val file = contentFile(p)
        file.writeBytes(ByteArray(64) { 0x5A })

        val read = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertFalse(
            "AEAD failure must never surface a payload",
            read is CacheReadResult.Available
        )
    }

    @Test
    fun ttlBoundary_expiresExactlyOneMillisecondPastSevenDays() = runBlocking {
        seed()
        val p = partition()
        val ttl = ConversationCacheV2.MAX_TTL_MILLIS
        assertTrue(ttl == 7L * 24 * 60 * 60 * 1000)

        // Exactly at the boundary: readable.
        assertTrue(replace(p, "sess-100", "body", capturedAt = fixedNow - ttl))
        val atBoundary = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue("Exact TTL boundary must stay readable: $atBoundary", atBoundary is CacheReadResult.Available)

        // +1 ms past: expired (T-09 TTL cap).
        fixedNow += 1
        val past = vault.readConversationSnapshot(p, "sess-100", nowMillis = fixedNow)
        assertTrue("1 ms past TTL must expire: $past", past is CacheReadResult.Expired)
        assertTrue(
            "Expired record is deleted",
            contentDir(p).listFiles()!!.none { !it.name.startsWith("cache_access_") }
        )
    }

    @Test
    fun oversizeSnapshot_skipsWrite_keepsExistingRecordsIntact() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-001", "small body one"))
        assertTrue(replace(p, "sess-002", "small body two"))

        val oversizeBody = "x".repeat(ConversationCacheV2.MAX_SNAPSHOT_BYTES + 1)
        val written = replace(p, "sess-900", oversizeBody)
        assertFalse("Oversize remote payload must be skipped", written)

        val none = vault.readConversationSnapshot(p, "sess-900", nowMillis = fixedNow)
        assertTrue("No partial record for the skipped write", none is CacheReadResult.Absent)
        val first = vault.readConversationSnapshot(p, "sess-001", nowMillis = fixedNow)
        assertTrue(first is CacheReadResult.Available)
        assertEquals("small body one", (first as CacheReadResult.Available).value.snapshot.rows.single().content)
        val second = vault.readConversationSnapshot(p, "sess-002", nowMillis = fixedNow)
        assertTrue(second is CacheReadResult.Available)
    }

    @Test
    fun ciphertextAndHeaders_neverContainTitlesBodiesOrWorkspaceNames() = runBlocking {
        seed()
        val body = "SENTINEL_BODY_e7c1 never plaintext"
        val workspace = "SENTINEL_WS_9d2b"
        // The write guard (REQ-06 W-consistency) binds selectedWorkspaceName to
        // the active selection — select it before resolving the partition.
        vault.saveSelectedContextProfile(workspace)
        val p = partition()
        assertTrue(
            "seed write must succeed",
            vault.replaceConversationSnapshot(cacheFor(p, "sess-100", body, workspaceName = workspace), p)
        )

        val files = allEncryptedFiles()
        files.forEach { file ->
            val bytes = file.readBytes()
            val asText = String(bytes, Charsets.ISO_8859_1)
            assertFalse("${file.name} leaks body", asText.contains("SENTINEL_BODY_e7c1"))
            assertFalse("${file.name} leaks workspace name", asText.contains("SENTINEL_WS_9d2b"))
            assertFalse("${file.name} leaks title", asText.contains("SENTINEL_TITLE"))
        }
        // Sanity: the scan actually visited the conversation files.
        assertTrue("expected conversation files on disk, saw ${files.size}", files.size >= 3)
        assertNotEquals("", origin)
    }

    // ---------- Repair review findings (plan §5.2/§5.3, REQ-02 fixed order, §9) ----------

    // (Finding 2 / plan §5.2) A record whose plaintext sits just BELOW the cap
    // must read Available: the pre-decrypt guard's overhead allowance must cover
    // the FULL envelope — 4-byte length + the record-id header (computed here,
    // never assumed) + IV 12 + tag 16 — not a flat +128 B that under-counts it.
    @Test
    fun nearCapSnapshot_readsAvailable_contentPreserved() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-nc", "NEAR_CAP"))
        val recordId = recordIdFor(p, "sess-nc")
        val file = conversationRecordFile(p, "sess-nc")
        val delta = ConversationCacheV2.MAX_SNAPSHOT_BYTES - 10L - jsonSizeOf(file, recordId)
        assertTrue("fixture must grow toward the cap (delta=$delta)", delta > 0)
        assertTrue(replace(p, "sess-nc", "NEAR_CAP" + "x".repeat(delta.toInt())))
        assertEquals(
            "payload must sit exactly 10 B below the cap",
            ConversationCacheV2.MAX_SNAPSHOT_BYTES - 10L,
            jsonSizeOf(file, recordId)
        )

        val read = vault.readConversationSnapshot(p, "sess-nc", nowMillis = fixedNow)
        assertTrue("valid near-cap snapshot must read Available, got $read", read is CacheReadResult.Available)
        assertTrue("near-cap content must not be deleted", file.exists())
    }

    // (Finding 4 / REQ-02 fixed order + §5.3) Multi-defect record: the INDEX is
    // corrupt AND the content is expired. Plan order runs step 10 (index) before
    // step 11 (TTL) ⇒ Unavailable — and a corrupt INDEX may never authorize
    // deleting content; it stays inaccessible for repair, not destroyed.
    @Test
    fun corruptIndex_withExpiredRecord_unavailableAndContentLeftInaccessible() = runBlocking {
        seed()
        val p = partition()
        assertTrue(
            replace(p, "sess-800", "BODY_800", capturedAt = fixedNow - ConversationCacheV2.MAX_TTL_MILLIS - 1)
        )
        val file = conversationRecordFile(p, "sess-800")
        assertTrue("fixture: content on disk", file.exists())
        indexFileOf(p).writeBytes(ByteArray(48) { 0x5A })   // index unreadable

        val read = vault.readConversationSnapshot(p, "sess-800", nowMillis = fixedNow)
        assertTrue("plan step 10 before step 11 ⇒ Unavailable, got $read", read is CacheReadResult.Unavailable)
        assertTrue("no validated index ⇒ content left inaccessible, not deleted", file.exists())
    }

    // (Finding 4) Multi-defect record: expired AND over the plaintext cap — the
    // plan's step 11 (TTL) must fire before step 12 (cap): reason is Expired,
    // not Corrupt. The write path rightly refuses to mint an over-cap payload,
    // so the fixture is grown by envelope surgery.
    @Test
    fun expiredOversizedRecord_reportsExpired_step11BeforeStep12() = runBlocking {
        seed()
        val p = partition()
        assertTrue(
            replace(p, "sess-900", "BODY_900", capturedAt = fixedNow - ConversationCacheV2.MAX_TTL_MILLIS - 1)
        )
        val recordId = recordIdFor(p, "sess-900")
        val file = conversationRecordFile(p, "sess-900")
        val delta = ConversationCacheV2.MAX_SNAPSHOT_BYTES + 10L - jsonSizeOf(file, recordId)
        assertTrue("fixture must exceed the cap (delta=$delta)", delta > 0)
        rewriteIdentified(file, recordId, conversationAadOf(p, "sess-900")) { json ->
            assertTrue("payload carries the body", json.contains("BODY_900"))
            json.replace("BODY_900", "BODY_900" + "x".repeat(delta.toInt()))
        }

        val read = vault.readConversationSnapshot(p, "sess-900", nowMillis = fixedNow)
        assertTrue("plan order (11) before (12): Expired, got $read", read is CacheReadResult.Expired)
        assertFalse("expired content must be deleted", file.exists())
    }

    // (Finding 4 / §5.3 boundary) The ONLY authorized read-path deletion:
    // envelope validated, index VALID, entry orphaned ⇒ delete + Miss. The index
    // file itself survives.
    @Test
    fun validIndexMissingEntry_orphanContentDeletedOnRead() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-700", "BODY_700"))
        val recordId = recordIdFor(p, "sess-700")
        val file = conversationRecordFile(p, "sess-700")
        val hash = sha256Hex(recordId)
        rewriteIdentified(indexFileOf(p), indexRecordIdFor(p), indexAadOf(p)) { json ->
            assertTrue("fixture index references the entry", json.contains(hash))
            json.replace(hash, "f".repeat(64))   // unlink ONLY this entry — index stays VALID
        }

        val read = vault.readConversationSnapshot(p, "sess-700", nowMillis = fixedNow)
        assertTrue("validated orphan ⇒ Absent, got $read", read is CacheReadResult.Absent)
        assertFalse("validated-index orphan must be deleted", file.exists())
        assertTrue("the index file itself survives", indexFileOf(p).exists())
    }

    // (Finding 6 / plan §9 row 4) Unknown-future CONTENT schema ⇒ quarantined:
    // never served, never parsed into rows (only the schema header is read).
    // The write path refuses such records (futureSchemaWrite above), so the
    // fixture is surgery on a conformant one.
    @Test
    fun futureSchemaContent_quarantined_neverServed() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-fut", "SENTINEL_FUTURE_BODY"))
        val recordId = recordIdFor(p, "sess-fut")
        val file = conversationRecordFile(p, "sess-fut")
        rewriteIdentified(file, recordId, conversationAadOf(p, "sess-fut")) { json ->
            val upgraded = json.replaceFirst(
                Regex("\"schemaVersion\"\\s*:\\s*${ConversationCacheV2.CURRENT_SCHEMA_VERSION}\\b"),
                "\"schemaVersion\":${ConversationCacheV2.CURRENT_SCHEMA_VERSION + 97}"
            )
            assertTrue("fixture must carry the future schema", upgraded != json)
            upgraded
        }

        val read = vault.readConversationSnapshot(p, "sess-fut", nowMillis = fixedNow)
        assertTrue("schema-future content ⇒ Unavailable, got $read", read is CacheReadResult.Unavailable)
        assertFalse("content must be quarantined away", file.exists())
        val residue = File(file.parentFile, "${file.name}.quarantine")
        assertTrue("quarantine residue must remain while unreadable", residue.exists())
        val again = vault.readConversationSnapshot(p, "sess-fut", nowMillis = fixedNow)
        assertFalse("quarantined content must never serve", again is CacheReadResult.Available)
    }

    // (Finding 5 / plan §5.2) toString redaction: payload content, durable
    // session ids, and workspace names must never leak through the default
    // data-class toString (the logging surface).
    @Test
    fun toStringRedaction_noPayloadSessionOrWorkspaceLeak() {
        val row = CachedRemoteHistoryRow(0, "user", "SENTINEL_BODY", null, "SENTINEL_TOOL", "SENTINEL_REASON", 1L)
        val snapshot = CachedRemoteHistorySnapshot("snap-1", fixedNow, false, false, listOf(row))
        val cache = ConversationCacheV2(
            recordId = "conversation:conn-1:origin:epoch:u:SENTINEL_SID",
            profileLocalId = "conn-1",
            originSha256 = "ab".repeat(32),
            cacheEpoch = "epoch",
            workspaceScopeTag = "u",
            durableSessionId = "SENTINEL_SID",
            selectedWorkspaceName = "SENTINEL_WORKSPACE",
            snapshot = snapshot,
            lastAccessedAtMillis = 1L,
            createdAtMillis = 1L,
            updatedAtMillis = 1L
        )
        val index = ConversationPartitionIndexV2(
            recordId = "SENTINEL_INDEX_RECORD",
            profileLocalId = "conn-1",
            originSha256 = "ab".repeat(32),
            cacheEpoch = "epoch",
            workspaceScopeTag = "u",
            revision = 1L,
            entries = listOf(
                ConversationIndexEntry(
                    recordIdHash = "SENTINEL_INDEX_ENTRY",
                    bytesEstimate = 2L,
                    expiresAtMillis = 3L,
                    lastAccessedAtMillis = 4L
                )
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 1L
        )
        val catalog = GlobalConversationCatalogV1(
            recordId = "SENTINEL_CATALOG_RECORD",
            entries = listOf(
                GlobalCatalogEntry(
                    indexLocatorSha256 = "SENTINEL_CATALOG_ENTRY",
                    observedIndexRevision = 1L,
                    bytesEstimate = 2L,
                    oldestLastAccessedAtMillis = 3L,
                    newestExpiryAtMillis = 4L
                )
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 1L
        )
        listOf<Any>(cache, snapshot, row, index, catalog).forEach { model ->
            val text = model.toString()
            assertFalse(
                "${model.javaClass.simpleName} leaks sensitive payload: $text",
                text.contains("SENTINEL")
            )
        }
        assertFalse(
            "ConversationCacheV2 must not print snapshot rows",
            cache.snapshot.toString().contains("SENTINEL_BODY")
        )
    }
}
