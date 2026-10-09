package com.hermes.bot.data.local

import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.cache.VaultConversationCacheRepository
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartition
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.CacheReadResult
import com.hermes.bot.data.local.CacheWriteStage
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
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
import java.util.UUID

/**
 * PR-4 (plan CACHE-PR4 §9 row T-09/T-11/T-01/T-02/T-05): the phone-captured
 * tool-trace store, all file-backed (REQ-09). Covers append→read round trip,
 * exact-tuple dedupe, trace caps with oldest-whole-event drop and
 * single-oversize Skipped, TTL non-extension (REQ-06), CON-01 carry-forward
 * through replace, schemaVersion-2 both directions, stage observation, and
 * rotation/workspace/AAD fail-closed scope isolation. Synthetic fixtures and
 * synthetic session ids only (REQ-09); no payload/args/result content in
 * test names or failure logs.
 */
class ConversationToolTraceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var repo: VaultConversationCacheRepository
    private val stages = mutableListOf<CacheWriteStage>()
    private var fixedNow = 10_000_000_000_000L
    private val origin = "http://127.0.0.1:9411"

    // Hoisted so fixture surgery (decrypt) runs under the vault's exact key material.
    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("tool_trace_store_test")
        stages.clear()
        fixedNow = 10_000_000_000_000L
        vault = EncryptedVault(
            storageDir,
            engine,
            wallClockMillis = { fixedNow },
            cacheStageObserver = stages::add
        )
        repo = VaultConversationCacheRepository(vault)
    }

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

    private suspend fun partition(workspaceScopeTag: String? = null): CacheAccessPartition {
        val base = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        return if (workspaceScopeTag == null) base else base.copy(workspaceScopeTag = workspaceScopeTag)
    }

    private fun cacheFor(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        capturedAt: Long = fixedNow,
        workspaceName: String? = null
    ) = com.hermes.bot.data.local.ConversationCacheV2(
        recordId = com.hermes.bot.data.local.ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
        ),
        profileLocalId = p.profileLocalId,
        originSha256 = p.originSha256,
        cacheEpoch = p.cacheEpoch,
        workspaceScopeTag = p.workspaceScopeTag,
        durableSessionId = sid,
        selectedWorkspaceName = workspaceName,
        snapshot = com.hermes.bot.data.local.CachedRemoteHistorySnapshot(
            snapshotId = UUID.nameUUIDFromBytes(sid.toByteArray()).toString(),
            capturedAtMillis = capturedAt,
            messagesOmitted = false,
            clientTruncated = false,
            rows = listOf(
                com.hermes.bot.data.local.CachedRemoteHistoryRow(
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

    private suspend fun replace(
        p: CacheAccessPartition,
        sid: String,
        body: String,
        capturedAt: Long = fixedNow,
        workspaceName: String? = null
    ): Boolean =
        vault.replaceConversationSnapshot(cacheFor(p, sid, body, capturedAt, workspaceName), p)

    private suspend fun seedSnapshot(p: CacheAccessPartition, sid: String, messages: List<String>, at: Long): ConversationCacheWriteResult =
        repo.replaceRemoteSnapshot(
            storedSessionId = sid,
            resume = SessionResumeResult(
                runtimeSessionId = "runtime-for-$sid",
                durableSessionId = sid,
                messageCount = messages.size,
                messages = messages.mapIndexed { i, m ->
                    SessionMessage(role = if (i % 2 == 0) "user" else "assistant", content = m)
                }
            ),
            nowMillis = at
        )

    private suspend fun append(
        sid: String,
        runtime: String,
        event: GatewayEvent,
        at: Long = fixedNow
    ): ConversationCacheWriteResult = repo.recordObservedToolEvent(sid, runtime, event, at)

    private fun startEvent(
        runtime: String,
        seq: Long?,
        toolId: String,
        name: String,
        context: String? = null,
        args: Map<String, Any?> = emptyMap()
    ) = GatewayEvent.ToolStart(
        sessionId = runtime, seq = seq, toolId = toolId, name = name, context = context, args = args
    )

    // ---- fixture surgery helpers (mirror ConversationSnapshotStoreTest) ----

    private fun contentDir(p: CacheAccessPartition): File = File(
        File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
        sha256Hex("${p.profileLocalId}:${p.originSha256}")
    )

    private fun recordIdFor(p: CacheAccessPartition, sid: String): String =
        com.hermes.bot.data.local.ConversationCacheV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
        )

    private fun conversationRecordFile(p: CacheAccessPartition, sid: String): File =
        File(contentDir(p), "conversation_${sha256Hex(recordIdFor(p, sid))}.enc")

    private fun conversationAadOf(p: CacheAccessPartition, sid: String): ByteArray =
        (
            "CONVERSATION:2:${recordIdFor(p, sid)}:${p.profileLocalId}:${p.originSha256}:" +
                "${p.cacheEpoch}:${p.workspaceScopeTag}:$sid"
            ).toByteArray(Charsets.UTF_8)

    private fun indexFileOf(p: CacheAccessPartition): File {
        val recordId = com.hermes.bot.data.local.ConversationPartitionIndexV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag
        )
        return File(File(storageDir, "conversation_indexes"), "index_${sha256Hex(recordId)}.enc")
    }

    private fun indexAadOf(p: CacheAccessPartition): ByteArray =
        (
            "CONVERSATION_INDEX:2:${com.hermes.bot.data.local.ConversationPartitionIndexV2.recordIdFor(
                p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag
            )}:${p.profileLocalId}:${p.originSha256}:${p.cacheEpoch}:${p.workspaceScopeTag}"
            ).toByteArray(Charsets.UTF_8)

    /** Decrypt an identified record to its plaintext JSON (fixture surgery read). */
    private fun decryptPlainOf(file: File, recordId: String, aad: ByteArray): String {
        val bytes = file.readBytes()
        val idLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals("envelope record id", recordId, String(bytes, 4, idLen, Charsets.UTF_8))
        return String(engine.decrypt(bytes.copyOfRange(4 + idLen, bytes.size), aad).getOrThrow(), Charsets.UTF_8)
    }

    private fun plainRecordOf(p: CacheAccessPartition, sid: String): String =
        decryptPlainOf(conversationRecordFile(p, sid), recordIdFor(p, sid), conversationAadOf(p, sid))

    private fun plainIndexOf(p: CacheAccessPartition): String =
        decryptPlainOf(indexFileOf(p), com.hermes.bot.data.local.ConversationPartitionIndexV2.recordIdFor(
            p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag
        ), indexAadOf(p))

    /** Fixture surgery write: re-encrypt a modified plaintext back into the envelope. */
    private fun writePlain(p: CacheAccessPartition, sid: String, plain: String) {
        val recordId = recordIdFor(p, sid)
        val ciphertext = engine.encrypt(plain.toByteArray(Charsets.UTF_8), conversationAadOf(p, sid))
        conversationRecordFile(p, sid).writeBytes(
            java.nio.ByteBuffer.allocate(4 + recordId.length + ciphertext.size)
                .putInt(recordId.length)
                .put(recordId.toByteArray(Charsets.UTF_8))
                .put(ciphertext)
                .array()
        )
    }

    /** Fixture surgery: replace the stored localTrace array content (row JSON is bracket-free). */
    private fun rewriteTraceRows(plain: String, rowsJson: String): String {
        val marker = ",\"localTrace\":["
        val at = plain.indexOf(marker)
        assertTrue("localTrace marker present", at >= 0)
        val start = at + marker.length
        val end = plain.indexOf(']', start)
        assertTrue("trace array close present", end >= 0)
        return plain.substring(0, start) + rowsJson + plain.substring(end)
    }

    /** Hand-built trace-row JSON in the exact §5.3 serializer shape (fixture injection). */
    private fun traceRowJson(
        id: String,
        seq: Long?,
        toolId: String?,
        name: String,
        observedAt: Long = 1_000L,
        sourceType: com.hermes.bot.data.local.ToolTraceSourceType =
            com.hermes.bot.data.local.ToolTraceSourceType.TOOL_START,
        status: com.hermes.bot.data.local.ToolStatus = com.hermes.bot.data.local.ToolStatus.RUNNING
    ): String = buildString {
        append("{\"localEventId\":\"").append(id).append('"')
        append(",\"sourceSeq\":").append(seq?.toString() ?: "null")
        append(",\"sourceType\":\"").append(sourceType.name).append('"')
        append(",\"toolId\":").append(toolId?.let { "\"$it\"" } ?: "null")
        append(",\"name\":\"").append(name).append('"')
        append(",\"context\":null")
        append(",\"status\":\"").append(status.name).append('"')
        append(",\"durationSeconds\":null")
        append(",\"observedAtMillis\":").append(observedAt)
        append('}')
    }

    private fun longAfter(json: String, key: String): Long {
        val idx = json.indexOf("\"$key\":")
        assertTrue("key $key present", idx >= 0)
        val start = idx + key.length + 3
        val end = (start until json.length).first { !json[it].isDigit() }
        return json.substring(start, end).toLong()
    }

    // ---------------------------------------------------------------------
    // Round trip: redacted DTO fields preserved, unmapped fields never stored
    // ---------------------------------------------------------------------
    @Test
    fun append_readRoundTrip_preservesObservedFields_dropsUnmappedFields() = runBlocking {
        seed()
        val p = partition()
        assertTrue("seed write", replace(p, "sess-tr-1", "synthetic question"))

        val at = fixedNow + 60_000L
        val complete = append(
            "sess-tr-1", "runtime-1",
            GatewayEvent.ToolComplete(
                sessionId = "runtime-1", seq = 11L, toolId = "tool-11", name = "grep_tool",
                durationSeconds = 1.25,
                result = mapOf("output" to "RESULT_SENTINEL_a91"),
                summary = "SUMMARY_SENTINEL_a91"
            ),
            at
        )
        assertTrue("append written: $complete", complete is ConversationCacheWriteResult.Written)

        val read = repo.load("sess-tr-1")
        assertTrue("load snapshot", read is ConversationCacheReadResult.Snapshot)
        val trace = (read as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("one observed event", 1, trace.size)
        val dto = trace.single()
        assertEquals(11L, dto.sourceSeq)
        assertEquals(com.hermes.bot.data.local.ToolTraceSourceType.TOOL_COMPLETE, dto.sourceType)
        assertEquals("tool-11", dto.toolId)
        assertEquals("grep_tool", dto.name)
        assertEquals(com.hermes.bot.data.local.ToolStatus.COMPLETE, dto.status)
        assertEquals(1.25, dto.durationSeconds!!, 0.0001)
        assertEquals(at, dto.observedAtMillis)
        assertTrue("locally minted id", dto.localEventId.isNotBlank())
        assertEquals("context never mapped from ToolComplete", null, dto.context)

        // args/result/summary sentinels absent from decrypted plaintext AND raw ciphertext.
        val plain = plainRecordOf(p, "sess-tr-1")
        assertFalse("result/summary never serialized", plain.contains("RESULT_SENTINEL_a91"))
        assertFalse("summary never serialized", plain.contains("SUMMARY_SENTINEL_a91"))
        val rawCiphertext = conversationRecordFile(p, "sess-tr-1").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("plaintext sentinels absent from ciphertext", rawCiphertext.contains("RESULT_SENTINEL_a91"))

        // Args sentinel via ToolStart too.
        val s = append(
            "sess-tr-1", "runtime-1",
            startEvent("runtime-1", 12L, "tool-12", "web_fetch", "synthetic context", args = mapOf("query" to "ARG_SENTINEL_a91")),
            fixedNow + 61_000L
        )
        assertTrue("start written: $s", s is ConversationCacheWriteResult.Written)
        val plain2 = plainRecordOf(p, "sess-tr-1")
        assertFalse("args never serialized", plain2.contains("ARG_SENTINEL_a91"))
        val trace2 = (repo.load("sess-tr-1") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("context preserved for ToolStart", "synthetic context", trace2.last().context)

        // Blank stored id never builds a record id (§5.5 guard).
        val blank = append("", "runtime-1", startEvent("runtime-1", 13L, "tool-13", "web_fetch"))
        assertTrue("blank id skipped", blank is ConversationCacheWriteResult.Skipped)
    }

    // ---------------------------------------------------------------------
    // T-09: event cap (128) + trace byte cap (256 KiB), oldest WHOLE events
    // dropped; single-oversize event Skipped; nothing ever partial.
    // ---------------------------------------------------------------------
    @Test
    fun append_caps_dropOldestWholeEvents_singleOversizeSkipped() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-2", "synthetic question"))

        for (i in 1..130) {
            val r = append(
                "sess-tr-2", "runtime-1",
                startEvent("runtime-1", i.toLong(), "tool-$i", "tool_$i", "ctx$i"),
                fixedNow + i
            )
            assertTrue("append #$i written: $r", r is ConversationCacheWriteResult.Written)
        }
        var trace = (repo.load("sess-tr-2") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("event cap 128 (oldest whole dropped)", 128, trace.size)
        assertEquals("oldest surviving is event #3", 3L, trace.first().sourceSeq)
        assertEquals("newest kept", 130L, trace.last().sourceSeq)
        assertEquals(
            "contiguous observed order, no partials",
            trace.size.toLong(),
            trace.last().sourceSeq!! - trace.first().sourceSeq!! + 1
        )
        trace.forEach { dto ->
            val expectedSeq = dto.sourceSeq!!
            assertTrue("context intact for event $expectedSeq", dto.context == "ctx$expectedSeq")
        }

        // Byte cap: seven ~40 KiB events exceed 256 KiB → oldest whole dropped.
        val big = "x".repeat(40_000)
        for (i in 1..7) {
            val r = append(
                "sess-tr-2", "runtime-2",
                startEvent("runtime-2", i.toLong(), "big-$i", "tool_big", big),
                fixedNow + 1_000 + i
            )
            assertTrue("big append #$i: $r", r is ConversationCacheWriteResult.Written)
        }
        trace = (repo.load("sess-tr-2") as ConversationCacheReadResult.Snapshot).value.localTrace
        val bigEvents = trace.filter { it.toolId?.startsWith("big-") == true }
        assertEquals("byte cap drops oldest whole big event (7 → 6)", 6, bigEvents.size)
        assertEquals("dropped big-1", "big-2", bigEvents.first().toolId)
        bigEvents.forEach { dto ->
            assertEquals("surviving events whole, never truncated", 40_000, dto.context!!.length)
        }

        // Single event alone over the trace budget ⇒ Skipped, trace unchanged.
        val before = trace.size
        val oversize = append(
            "sess-tr-2", "runtime-2",
            startEvent("runtime-2", 99L, "big-over", "tool_big", "y".repeat(300_000)),
            fixedNow + 2_000
        )
        assertTrue("single oversize skipped", oversize is ConversationCacheWriteResult.Skipped)
        val after = (repo.load("sess-tr-2") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("no append happened", before, after.size)
        assertFalse("oversize event absent", after.any { it.toolId == "big-over" })
    }

    // ---------------------------------------------------------------------
    // REQ-06: append never refreshes capturedAt/expiresAt; updatedAt,
    // lastAccessedAt, index bytesEstimate/revision move; 7-day TTL enforced.
    // ---------------------------------------------------------------------
    @Test
    fun append_neverExtendsRetention_ttlStillSevenDaysFromSnapshot() = runBlocking {
        seed()
        val p = partition()
        val t0 = fixedNow
        assertTrue(replace(p, "sess-tr-3", "synthetic question"))
        val indexBefore = plainIndexOf(p)
        val revBefore = longAfter(indexBefore, "revision")
        val bytesBefore = longAfter(indexBefore, "bytesEstimate")
        val expiresBefore = longAfter(indexBefore, "expiresAtMillis")

        fixedNow = t0 + 3_600_000L
        val r = append("sess-tr-3", "runtime-1", startEvent("runtime-1", 1L, "tool-1", "tool_a", "ctx"))
        assertTrue("append written: $r", r is ConversationCacheWriteResult.Written)

        val plain = plainRecordOf(p, "sess-tr-3")
        assertEquals("capturedAt never refreshed by append", t0, longAfter(plain, "capturedAtMillis"))
        assertEquals("updatedAt moved to append time", fixedNow, longAfter(plain, "updatedAtMillis"))
        assertEquals("lastAccessedAt moved to append time", fixedNow, longAfter(plain, "lastAccessedAtMillis"))

        val indexAfter = plainIndexOf(p)
        assertEquals("expiresAt still anchored to capture", expiresBefore, longAfter(indexAfter, "expiresAtMillis"))
        assertEquals("expiresAt = capturedAt + 7d", t0 + com.hermes.bot.data.local.ConversationCacheV2.MAX_TTL_MILLIS, expiresBefore)
        assertTrue("index revision moved", longAfter(indexAfter, "revision") > revBefore)
        assertTrue("index bytesEstimate moved", longAfter(indexAfter, "bytesEstimate") > bytesBefore)

        // Stage observation: the append fires the trace record stage before
        // its OWN index step — scope the scan to after the trace stage (the
        // seed chain committed its index long before this append).
        val traceStage = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_TRACE_APPENDED)
        val indexStage = if (traceStage < 0) -1 else traceStage +
            stages.drop(traceStage).indexOfFirst { it.name.startsWith("STAGE_CONVERSATION_INDEX") }
        assertTrue("TRACE_APPENDED fired (stages=$stages)", traceStage >= 0)
        assertTrue("index step after record step (stages=$stages)", indexStage > traceStage)

        // Past the 7-day bound: append cleans up and Skips — trace cannot keep it alive.
        fixedNow = t0 + com.hermes.bot.data.local.ConversationCacheV2.MAX_TTL_MILLIS + 60_000L
        val expired = append("sess-tr-3", "runtime-1", startEvent("runtime-1", 2L, "tool-2", "tool_a", "ctx"))
        assertTrue("expired append skipped: $expired", expired is ConversationCacheWriteResult.Skipped)
        val read = repo.load("sess-tr-3")
        assertTrue("record cleaned up (PR-3 read order): $read", read is ConversationCacheReadResult.Miss)
    }

    // ---------------------------------------------------------------------
    // REQ-04: dedupe on the exact tuple only — no fuzzy matching ever.
    // ---------------------------------------------------------------------
    @Test
    fun dedupe_exactTupleOnly_preservesDistinctToolsAndRuntimes() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-4", "synthetic question"))

        fun traceSize(): Int =
            runBlocking { (repo.load("sess-tr-4") as ConversationCacheReadResult.Snapshot).value.localTrace.size }

        val a = append("sess-tr-4", "runtime-1", startEvent("runtime-1", 5L, "tool-a", "first_name"))
        assertTrue("original append: $a", a is ConversationCacheWriteResult.Written)
        assertEquals(1, traceSize())

        val reDelivery = append("sess-tr-4", "runtime-1", startEvent("runtime-1", 5L, "tool-a", "first_name"))
        assertTrue("host re-delivery suppressed: $reDelivery", reDelivery is ConversationCacheWriteResult.Skipped)
        assertEquals(1, traceSize())

        val renamed = append("sess-tr-4", "runtime-1", startEvent("runtime-1", 5L, "tool-a", "renamed_after_delivery"))
        assertTrue("name is NOT in the tuple (no fuzzy expansion either way): $renamed", renamed is ConversationCacheWriteResult.Skipped)
        assertEquals(1, traceSize())

        val otherToolSameName = append("sess-tr-4", "runtime-1", startEvent("runtime-1", 5L, "tool-b", "first_name"))
        assertTrue("same-name concurrent tool with distinct toolId appends", otherToolSameName is ConversationCacheWriteResult.Written)
        assertEquals(2, traceSize())

        // REQ-04 seed semantics (review F1 re-pin): the seen-set is seeded
        // from the LOADED record's stored TRIPLES — stored rows never carry a
        // runtime (§5.3 NEVER-list) and the tuple's runtime component is the
        // CURRENT admission context — so the same observation re-delivered
        // under a NEW runtime session still matches and stays ONE row.
        val otherRuntime = append("sess-tr-4", "runtime-2", startEvent("runtime-2", 5L, "tool-a", "first_name"))
        assertTrue(
            "stored triple seeds the seen-set: new-runtime re-delivery is suppressed",
            otherRuntime is ConversationCacheWriteResult.Skipped
        )
        assertEquals(2, traceSize())

        val nullSeq = append("sess-tr-4", "runtime-1", startEvent("runtime-1", null, "tool-a", "first_name"))
        assertTrue("null seq ⇒ always unique", nullSeq is ConversationCacheWriteResult.Written)
        assertEquals(3, traceSize())

        val blankToolId = append("sess-tr-4", "runtime-1", startEvent("runtime-1", 9L, "", "first_name"))
        assertTrue("blank toolId ⇒ null ⇒ always unique", blankToolId is ConversationCacheWriteResult.Written)
        assertEquals(4, traceSize())

        val gen = append(
            "sess-tr-4", "runtime-1",
            GatewayEvent.ToolGenerating(sessionId = "runtime-1", seq = 5L, name = "gen_tool")
        )
        assertTrue("first TOOL_GENERATING append", gen is ConversationCacheWriteResult.Written)
        assertEquals(5, traceSize())
        val genAgain = append(
            "sess-tr-4", "runtime-1",
            GatewayEvent.ToolGenerating(sessionId = "runtime-1", seq = 5L, name = "gen_tool")
        )
        // §9 / review F4 re-pin: blank toolId ⇒ ALWAYS unique — TOOL_GENERATING
        // maps toolId=null (REQ-03), so its same-seq re-delivery appends.
        assertTrue(
            "blank toolId ⇒ always unique: TOOL_GENERATING re-delivery appends",
            genAgain is ConversationCacheWriteResult.Written
        )
        assertEquals(6, traceSize())
    }

    // ---------------------------------------------------------------------
    // CON-01: replace-on-resume replaces rows but carries the trace forward.
    // ---------------------------------------------------------------------
    @Test
    fun replace_afterAppend_carriesTraceForward_replacesRows() = runBlocking {
        seed()
        val p = partition()
        val t0 = fixedNow
        assertTrue(replace(p, "sess-tr-5", "synthetic question"))
        val first = append("sess-tr-5", "runtime-1", startEvent("runtime-1", 1L, "tool-1", "tool_a", "ctx"))
        assertTrue("append: $first", first is ConversationCacheWriteResult.Written)

        val carriedId = (repo.load("sess-tr-5") as ConversationCacheReadResult.Snapshot)
            .value.localTrace.single().localEventId

        fixedNow = t0 + 60_000L
        val replaced = seedSnapshot(
            p, "sess-tr-5",
            messages = listOf("fresh server question", "fresh server answer"),
            at = fixedNow
        )
        assertTrue("resume replace written: $replaced", replaced is ConversationCacheWriteResult.Written)

        val snapshot = (repo.load("sess-tr-5") as ConversationCacheReadResult.Snapshot).value
        assertEquals("rows fully replaced by the remote capture", 2, snapshot.rows.size)
        assertEquals("fresh server question", snapshot.rows.first().content)
        assertEquals("remote capturedAt wins", fixedNow, snapshot.capturedAtMillis)
        assertEquals("trace retained (CON-01)", 1, snapshot.localTrace.size)
        assertEquals("same observed event identity", carriedId, snapshot.localTrace.single().localEventId)
    }

    // ---------------------------------------------------------------------
    // schemaVersion 2 both directions: legacy PR-3 record (no localTrace
    // key) parses to an empty trace; trace-bearing record round-trips.
    // ---------------------------------------------------------------------
    @Test
    fun schemaVersion2_bothDirections_legacyWithoutLocalTraceParsesEmpty() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-6", "synthetic question"))

        // (a) PR-3-shaped record: strip the localTrace key → still schema 2, trace empty.
        val recordId = recordIdFor(p, "sess-tr-6")
        val file = conversationRecordFile(p, "sess-tr-6")
        val original = decryptPlainOf(file, recordId, conversationAadOf(p, "sess-tr-6"))
        val stripped = StringBuilder(original)
        val cut = stripped.indexOf(",\"localTrace\":[")
        assertTrue("localTrace key present after an append-based write path: ${original.takeLast(200)}", cut >= 0)
        val end = stripped.lastIndexOf('}')
        val legacy = stripped.substring(0, cut) + stripped.substring(end)
        val ciphertext = engine.encrypt(legacy.toByteArray(Charsets.UTF_8), conversationAadOf(p, "sess-tr-6"))
        file.writeBytes(
            java.nio.ByteBuffer.allocate(4 + recordId.length + ciphertext.size)
                .putInt(recordId.length)
                .put(recordId.toByteArray(Charsets.UTF_8))
                .put(ciphertext)
                .array()
        )
        val legacyPlain = decryptPlainOf(file, recordId, conversationAadOf(p, "sess-tr-6"))
        assertTrue("legacy record stays schemaVersion 2", legacyPlain.contains("\"schemaVersion\":2"))
        assertFalse("localTrace key removed", legacyPlain.contains("\"localTrace\""))
        val legacyRead = repo.load("sess-tr-6")
        assertTrue("legacy record serves: $legacyRead", legacyRead is ConversationCacheReadResult.Snapshot)
        assertEquals(
            "missing localTrace ⇒ empty trace",
            emptyList<com.hermes.bot.data.local.CachedLocalToolTraceEvent>(),
            (legacyRead as ConversationCacheReadResult.Snapshot).value.localTrace
        )
        assertEquals("legacy rows untouched", 1, legacyRead.value.rows.size)

        // (b) Trace-bearing record round-trips through PR-3's read order at schema 2.
        val r = append("sess-tr-6", "runtime-1", startEvent("runtime-1", 1L, "tool-1", "tool_a"))
        assertTrue("re-append: $r", r is ConversationCacheWriteResult.Written)
        val plain = plainRecordOf(p, "sess-tr-6")
        assertTrue("schemaVersion 2", plain.contains("\"schemaVersion\":2"))
        assertTrue("localTrace serialized", plain.contains("\"localTrace\":[{"))
        val roundTripped = (repo.load("sess-tr-6") as ConversationCacheReadResult.Snapshot).value
        assertEquals(1, roundTripped.localTrace.size)
        assertEquals(1, roundTripped.rows.size)
    }

    // ---------------------------------------------------------------------
    // T-01/T-02/T-05/T-11 scope implications: rotation, workspace scope, and
    // AAD-swapped records fail closed while the rightful scope serves.
    // ---------------------------------------------------------------------
    @Test
    fun traceRecord_failsClosed_onRotationWorkspaceMismatchAndAadSwap() = runBlocking {
        seed()
        val p = partition()
        val pRotated = p.copy(cacheEpoch = "${p.cacheEpoch}-rotated")
        // Mirror of EncryptedVault.workspaceTagFor (private): name-tagged
        // scope ⇒ "w:<sha256(name)>", default user scope ⇒ "u".
        val pOtherWorkspace = p.copy(workspaceScopeTag = "w:" + sha256Hex("ws-b"))
        assertTrue(replace(p, "sess-tr-7", "synthetic question"))
        assertTrue(replace(p, "sess-tr-8", "other synthetic question"))
        val r = append("sess-tr-7", "runtime-1", startEvent("runtime-1", 1L, "tool-1", "tool_a", "ctx-a"))
        assertTrue("append: $r", r is ConversationCacheWriteResult.Written)

        // (T-01/T-02) Epoch rotation: the trace-bearing record is unreadable
        // under the rotated partition; the original still serves.
        val rotatedRead = vault.readConversationSnapshot(pRotated, "sess-tr-7", nowMillis = fixedNow)
        assertFalse("rotated epoch must not serve the old record", rotatedRead is CacheReadResult.Available)
        val originalRead = repo.load("sess-tr-7")
        assertTrue("original epoch still serves", originalRead is ConversationCacheReadResult.Snapshot)
        assertEquals(1, (originalRead as ConversationCacheReadResult.Snapshot).value.localTrace.size)

        // (T-05) Same durable id under another workspace scope yields a distinct record/trace.
        val workspaceRead = vault.readConversationSnapshot(pOtherWorkspace, "sess-tr-7", nowMillis = fixedNow)
        assertFalse("other workspace scope must not see this record", workspaceRead is CacheReadResult.Available)
        assertTrue(replace(pOtherWorkspace, "sess-tr-7", "workspace b body", workspaceName = "ws-b"))
        val wsAppend = vault.appendConversationToolTrace(
            pOtherWorkspace, "sess-tr-7",
            com.hermes.bot.data.local.CachedLocalToolTraceEvent(
                localEventId = "evt-ws-b",
                sourceSeq = 42L,
                sourceType = com.hermes.bot.data.local.ToolTraceSourceType.TOOL_START,
                toolId = "tool-ws-b",
                name = "tool_ws",
                context = "ws-b ctx",
                status = com.hermes.bot.data.local.ToolStatus.RUNNING,
                durationSeconds = null,
                observedAtMillis = fixedNow
            )
        )
        assertTrue("workspace-b append", wsAppend)
        val wsTrace = (vault.readConversationSnapshot(pOtherWorkspace, "sess-tr-7", nowMillis = fixedNow) as CacheReadResult.Available)
            .value.localTrace
        assertEquals("workspace B has only its own trace", listOf("evt-ws-b"), wsTrace.map { it.localEventId })
        val aTrace = (repo.load("sess-tr-7") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("workspace A trace untouched", 1, aTrace.size)

        // (T-11) AAD/filename swap: the swapped record fails closed, the other scope serves.
        val swapSource = conversationRecordFile(p, "sess-tr-8")
        val swapTarget = conversationRecordFile(p, "sess-tr-7")
        swapTarget.writeBytes(swapSource.readBytes())
        val swapped = vault.readConversationSnapshot(p, "sess-tr-7", nowMillis = fixedNow)
        assertFalse("swapped identity must fail closed", swapped is CacheReadResult.Available)
        val otherScope = repo.load("sess-tr-8")
        assertTrue("other scope still serves", otherScope is ConversationCacheReadResult.Snapshot)
        assertEquals(0, (otherScope as ConversationCacheReadResult.Snapshot).value.localTrace.size)
    }

    // ---------------------------------------------------------------------
    // Review F1 / REQ-04 seed semantics: dedupe keys are seeded from the
    // stored TRIPLES; the runtime component comes from the admission context
    // at append time — a re-delivery AFTER resume under a NEW runtime
    // session still dedupes to a single row.
    // ---------------------------------------------------------------------
    @Test
    fun redeliveryAfterResume_underNewRuntimeSession_stillOneRow() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-9", "synthetic question"))
        val first = append("sess-tr-9", "runtime-1", startEvent("runtime-1", 7L, "tool-r", "resumed_tool"))
        assertTrue("original append: $first", first is ConversationCacheWriteResult.Written)

        // Resume ⇒ replace-on-resume carries the trace forward (CON-01).
        val resumed = seedSnapshot(
            p, "sess-tr-9", listOf("fresh server question", "fresh server answer"), fixedNow + 60_000L
        )
        assertTrue("resume replace: $resumed", resumed is ConversationCacheWriteResult.Written)

        val redelivery = append(
            "sess-tr-9", "runtime-2", startEvent("runtime-2", 7L, "tool-r", "resumed_tool"), fixedNow + 61_000L
        )
        assertTrue(
            "same toolId+name+seq under a NEW runtime session after resume ⇒ suppressed",
            redelivery is ConversationCacheWriteResult.Skipped
        )
        val trace = (repo.load("sess-tr-9") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("single row after post-resume re-delivery", 1, trace.size)
        assertEquals("tool-r", trace.single().toolId)
    }

    // ---------------------------------------------------------------------
    // Review F1 schema: runtimeSessionId is on the plan §5.3 NEVER-list —
    // never serialized; a record still carrying the key (earlier writer)
    // parses robustly (unknown key ignored, backward-compatible).
    // ---------------------------------------------------------------------
    @Test
    fun schema_neverPersistsRuntimeSessionId_legacyKeyIgnoredOnParse() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-10", "synthetic question"))
        val w = append("sess-tr-10", "runtime-1", startEvent("runtime-1", 4L, "tool-s", "schema_tool"))
        assertTrue("append: $w", w is ConversationCacheWriteResult.Written)

        // (a) NEVER-list: the key never reaches stored plaintext.
        val plain = plainRecordOf(p, "sess-tr-10")
        assertFalse(
            "runtimeSessionId must never persist (plan §5.3 NEVER-list)",
            plain.contains("\"runtimeSessionId\"")
        )

        // (b) Backward-compatible parse: inject the legacy key into the row —
        // the reader ignores unknown keys, so the record still serves.
        val marker = "\"localEventId\":"
        val at = plain.indexOf(marker)
        assertTrue("row present", at >= 0)
        val legacy = plain.substring(0, at) +
            "\"runtimeSessionId\":\"runtime-legacy\"," +
            plain.substring(at)
        writePlain(p, "sess-tr-10", legacy)
        val read = repo.load("sess-tr-10")
        assertTrue("legacy-key record still serves: $read", read is ConversationCacheReadResult.Snapshot)
        val row = (read as ConversationCacheReadResult.Snapshot).value.localTrace.single()
        assertEquals("schema_tool", row.name)
        assertEquals(4L, row.sourceSeq)
    }

    // ---------------------------------------------------------------------
    // Review F2 (write gate): REQ-05 measures the ACTUAL serialized DTO row
    // bytes — name and context both count — so an over-cap name can never
    // be stored (the old 260+context estimate ignored name/toolId).
    // ---------------------------------------------------------------------
    @Test
    fun append_overCapNameOrContext_rejectedByDtoByteGate() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-11", "synthetic question"))
        val huge = "n".repeat(300_000)

        val overName = append(
            "sess-tr-11", "runtime-1",
            startEvent("runtime-1", 1L, "tool-huge", huge)
        )
        assertTrue(
            "over-cap NAME rejected at write (REQ-05: normalized DTO bytes): $overName",
            overName is ConversationCacheWriteResult.Skipped
        )

        val overContext = append(
            "sess-tr-11", "runtime-1",
            startEvent("runtime-1", 2L, "tool-huge-ctx", "ok_name", huge)
        )
        assertTrue(
            "over-cap context rejected at write: $overContext",
            overContext is ConversationCacheWriteResult.Skipped
        )

        val trace = (repo.load("sess-tr-11") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("nothing over-cap ever stored", 0, trace.size)
    }

    // ---------------------------------------------------------------------
    // Review F2 (read re-check): REQ-05 "again after decryption" — a row
    // over the 256 KiB trace budget that is found on disk (fixture surgery)
    // is dropped, never served; the record itself still serves (drop-row
    // remedy, never fail-closed).
    // ---------------------------------------------------------------------
    @Test
    fun read_reEnforcesByteCapAfterDecryption_dropsOverCapRow() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-12", "synthetic question"))

        val plain = plainRecordOf(p, "sess-tr-12")
        val overCapRow = traceRowJson(
            id = "evt-big", seq = 1L, toolId = "tool-big", name = "n".repeat(300_000)
        )
        val keepRow = traceRowJson(
            id = "evt-keep", seq = 2L, toolId = "tool-keep", name = "keep_row", observedAt = 1_001L
        )
        writePlain(p, "sess-tr-12", rewriteTraceRows(plain, "$overCapRow,$keepRow"))

        val read = repo.load("sess-tr-12")
        assertTrue("record still serves (drop-row, not fail-closed): $read", read is ConversationCacheReadResult.Snapshot)
        val snapshot = read as ConversationCacheReadResult.Snapshot
        assertEquals(
            "over-cap row dropped, in-budget row kept",
            listOf("evt-keep"),
            snapshot.value.localTrace.map { it.localEventId }
        )
        assertEquals("server rows untouched", 1, snapshot.value.rows.size)
    }

    // ---------------------------------------------------------------------
    // Review F2 (read re-check): the ≤128 event count cap is re-enforced
    // after decryption — oldest WHOLE events drop first.
    // ---------------------------------------------------------------------
    @Test
    fun read_reEnforcesEventCountCapAfterDecryption_dropsOldest() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-13", "synthetic question"))

        val plain = plainRecordOf(p, "sess-tr-13")
        val rows = (1..130).joinToString(",") { i ->
            traceRowJson(id = "evt-$i", seq = i.toLong(), toolId = "tool-$i", name = "tool_$i", observedAt = i.toLong())
        }
        writePlain(p, "sess-tr-13", rewriteTraceRows(plain, rows))

        val trace = (repo.load("sess-tr-13") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("count cap re-enforced after decryption", 128, trace.size)
        assertEquals("oldest whole events dropped first", 3L, trace.first().sourceSeq)
        assertEquals("newest kept", 130L, trace.last().sourceSeq)
    }

    // ---------------------------------------------------------------------
    // Review F4 / REQ-04 "otherwise appended" / §9: blank toolId ⇒ always
    // unique — a SAME-seq, same-name re-delivery of a blank-toolId event
    // appends a second row (never deduped).
    // ---------------------------------------------------------------------
    @Test
    fun blankToolId_sameSeqSameNameRedelivery_appendsSecondRow() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-tr-14", "synthetic question"))
        val first = append("sess-tr-14", "runtime-1", startEvent("runtime-1", 5L, "", "anon_tool"))
        assertTrue("first blank-toolId event: $first", first is ConversationCacheWriteResult.Written)
        val again = append("sess-tr-14", "runtime-1", startEvent("runtime-1", 5L, "", "anon_tool"))
        assertTrue(
            "blank toolId same-seq re-delivery must NOT dedupe (§9): $again",
            again is ConversationCacheWriteResult.Written
        )
        val trace = (repo.load("sess-tr-14") as ConversationCacheReadResult.Snapshot).value.localTrace
        assertEquals("two rows", 2, trace.size)
    }
}
