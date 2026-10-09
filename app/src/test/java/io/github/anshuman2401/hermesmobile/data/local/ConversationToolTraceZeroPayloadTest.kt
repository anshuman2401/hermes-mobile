package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheWriteResult
import io.github.anshuman2401.hermesmobile.data.cache.VaultConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartition
import io.github.anshuman2401.hermesmobile.data.local.CacheAccessPartitionResult
import io.github.anshuman2401.hermesmobile.data.local.CacheReadResult
import io.github.anshuman2401.hermesmobile.data.local.CachedLocalToolTraceEvent
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.ConversationCacheV2
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.ToolStatus
import io.github.anshuman2401.hermesmobile.data.local.ToolTraceSourceType
import kotlinx.coroutines.runBlocking
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
import java.util.UUID

/**
 * PR-4 (plan CACHE-PR4 §9 T-12, blocking): the zero-payload sentinel for
 * tool-progress capture. ToolProgress carries a host payload map whose
 * entries may hold terminal-like content (SEC-01 REQ-02): it must never be
 * inspected, serialized, logged, or projected. The sentinel strings are
 * unique constants that must appear in NO decrypted record byte and NO
 * ciphertext, and the mapping must be byte-identical for two different
 * payloads — behavioral proof that projection never depends on payload.
 */
class ConversationToolTraceZeroPayloadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var repo: VaultConversationCacheRepository
    private var fixedNow = 10_000_000_000_000L
    private val origin = "http://127.0.0.1:9413"
    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    /** Sentinel values never allowed to reach any serialized byte. */
    private val sentinelText = "SENTINEL_TEXT_9f3a_never_persist"
    private val sentinelOutput = "SENTINEL_OUTPUT_9f3a_never_persist"
    private val sentinelArg = "SENTINEL_ARG_9f3a_never_persist"

    /** Throws on any inspection — projection must never touch payload values. */
    private class ExplodingValue : Any() {
        override fun toString(): String =
            throw AssertionError("payload value was inspected by the mapping")
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("zero_payload_test")
        fixedNow = 10_000_000_000_000L
        vault = EncryptedVault(
            storageDir,
            engine,
            wallClockMillis = { fixedNow }
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

    private suspend fun partition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun cacheFor(p: CacheAccessPartition, sid: String, body: String, capturedAt: Long) =
        ConversationCacheV2(
            recordId = ConversationCacheV2.recordIdFor(
                p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid
            ),
            profileLocalId = p.profileLocalId,
            originSha256 = p.originSha256,
            cacheEpoch = p.cacheEpoch,
            workspaceScopeTag = p.workspaceScopeTag,
            durableSessionId = sid,
            selectedWorkspaceName = null,
            snapshot = io.github.anshuman2401.hermesmobile.data.local.CachedRemoteHistorySnapshot(
                snapshotId = UUID.nameUUIDFromBytes(sid.toByteArray()).toString(),
                capturedAtMillis = capturedAt,
                messagesOmitted = false,
                clientTruncated = false,
                rows = listOf(
                    io.github.anshuman2401.hermesmobile.data.local.CachedRemoteHistoryRow(
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

    private suspend fun seedSnapshot(p: CacheAccessPartition, sid: String, messages: List<String>): ConversationCacheWriteResult =
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
            nowMillis = fixedNow
        )

    private fun recordIdFor(p: CacheAccessPartition, sid: String): String =
        ConversationCacheV2.recordIdFor(p.profileLocalId, p.originSha256, p.cacheEpoch, p.workspaceScopeTag, sid)

    private fun contentDir(p: CacheAccessPartition): File = File(
        File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
        sha256Hex("${p.profileLocalId}:${p.originSha256}")
    )

    private fun conversationRecordFile(p: CacheAccessPartition, sid: String): File =
        File(contentDir(p), "conversation_${sha256Hex(recordIdFor(p, sid))}.enc")

    private fun conversationAadOf(p: CacheAccessPartition, sid: String): ByteArray =
        (
            "CONVERSATION:2:${recordIdFor(p, sid)}:${p.profileLocalId}:${p.originSha256}:" +
                "${p.cacheEpoch}:${p.workspaceScopeTag}:$sid"
            ).toByteArray(Charsets.UTF_8)

    private fun decryptPlainOf(p: CacheAccessPartition, sid: String): String {
        val file = conversationRecordFile(p, sid)
        val bytes = file.readBytes()
        val idLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals(
            "envelope record id",
            recordIdFor(p, sid),
            String(bytes, 4, idLen, Charsets.UTF_8)
        )
        return String(
            engine.decrypt(bytes.copyOfRange(4 + idLen, bytes.size), conversationAadOf(p, sid)).getOrThrow(),
            Charsets.UTF_8
        )
    }

    /** payload shaped like host terminal output: text, nested, arg-like, output-like. */
    private fun explodingProgress(runtime: String, seq: Long) = GatewayEvent.ToolProgress(
        sessionId = runtime,
        seq = seq,
        type = "edit",
        payload = mapOf(
            "text" to sentinelText,
            "output" to sentinelOutput,
            "args" to mapOf("query" to sentinelArg),
            "nested" to listOf(sentinelOutput, sentinelArg),
            "t" to ExplodingValue()
        )
    )

    @Test
    fun toolProgressSentinel_zeroPayloadProjection_neverSerialized() = runBlocking {
        seed()
        val p = partition()
        assertTrue(replace(p, "sess-zp-1", "synthetic question"))

        val written = repo.recordObservedToolEvent(
            "sess-zp-1", "runtime-1", explodingProgress("runtime-1", 3L), fixedNow + 5_000L
        )
        // If mapping ever inspected the payload, ExplodingValue.toString would
        // throw an Error that the repository's exception guard does not swallow
        // and this line would never be reached.
        assertTrue("progress append must be Written: $written", written is ConversationCacheWriteResult.Written)

        val plain = decryptPlainOf(p, "sess-zp-1")
        val localTraceStart = plain.indexOf("\"localTrace\":[")
        assertTrue("localTrace present", localTraceStart >= 0)
        val traceJson = plain.substring(localTraceStart, plain.lastIndexOf('}') + 1)
        assertFalse("sentinel text absent from trace bytes", traceJson.contains(sentinelText))
        assertFalse("sentinel output absent from trace bytes", traceJson.contains(sentinelOutput))
        assertFalse("sentinel arg absent from trace bytes", traceJson.contains(sentinelArg))
        assertFalse("no payload key ever serialized", traceJson.contains("\"payload\""))
        assertFalse("no arg-like key ever serialized", traceJson.contains("\"args\""))
        assertFalse("no nested output key ever serialized", traceJson.contains("\"output\""))
        // Whole-record (and ciphertext) levels too.
        assertFalse("sentinel text absent from record", plain.contains(sentinelText))
        val ciphertext = conversationRecordFile(p, "sess-zp-1").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("sentinel text absent from ciphertext", ciphertext.contains(sentinelText))

        val dto = (repo.load("sess-zp-1") as ConversationCacheReadResult.Snapshot).value.localTrace.single()
        assertEquals("Tool progress", dto.name)
        assertEquals(ToolTraceSourceType.TOOL_PROGRESS_GENERIC, dto.sourceType)
        assertEquals(ToolStatus.RUNNING, dto.status)
        assertNull("context never derived from payload", dto.context)
        assertNull("toolId never derived from payload", dto.toolId)
        assertNull("duration never derived from payload", dto.durationSeconds)
        assertEquals(3L, dto.sourceSeq)
        assertEquals(fixedNow + 5_000L, dto.observedAtMillis)
    }

    @Test
    fun differentPayloads_produceByteIdenticalProjection() = runBlocking {
        seed()
        val pA = partition()
        assertTrue(replace(pA, "sess-zp-a", "synthetic question"))
        val at = fixedNow + 7_000L
        val a = repo.recordObservedToolEvent(
            "sess-zp-a", "runtime-x", explodingProgress("runtime-x", 3L), at
        )
        assertTrue("append A: $a", a is ConversationCacheWriteResult.Written)

        assertTrue(replace(pA, "sess-zp-b", "other synthetic question"))
        val b = repo.recordObservedToolEvent(
            "sess-zp-b", "runtime-x",
            GatewayEvent.ToolProgress(
                sessionId = "runtime-x",
                seq = 3L,
                type = "status",
                payload = mapOf("completely" to "different", "state" to 7)
            ),
            at
        )
        assertTrue("append B: $b", b is ConversationCacheWriteResult.Written)

        val dtoA = (repo.load("sess-zp-a") as ConversationCacheReadResult.Snapshot).value.localTrace.single()
        val dtoB = (repo.load("sess-zp-b") as ConversationCacheReadResult.Snapshot).value.localTrace.single()
        assertEquals(
            "projection must be byte-identical for two different payloads",
            dtoA.copy(localEventId = "<mint>"),
            dtoB.copy(localEventId = "<mint>")
        )
        // And identical serialized bytes apart from the locally minted id.
        val bytesA = plainTraceOf(pA, "sess-zp-a").replace(Regex("\"localEventId\":\"[^\"]*\""), "")
        val bytesB = plainTraceOf(pA, "sess-zp-b").replace(Regex("\"localEventId\":\"[^\"]*\""), "")
        assertEquals("serialized localTrace identical", bytesA, bytesB)
    }

    private fun plainTraceOf(p: CacheAccessPartition, sid: String): String {
        val plain = decryptPlainOf(p, sid)
        return plain.substring(plain.indexOf("\"localTrace\":["), plain.lastIndexOf('}') + 1)
    }
}
