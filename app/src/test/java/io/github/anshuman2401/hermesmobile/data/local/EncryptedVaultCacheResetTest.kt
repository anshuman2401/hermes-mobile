package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runBlocking as nestedRunBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Plan CACHE-PR5 TEST-02..TEST-05 — the seven-phase clear on the JVM:
 * TEST-02 scope (five families deleted, every other seeded artifact
 * byte-identical), TEST-03 atomic phase sequence + sync seam + mutex-held
 * run, TEST-04 idempotent re-run with zero additional deletions, TEST-05
 * crash at each phase boundary: residue carried, nothing reads Corrupt,
 * the resolver stays Available, and a re-run completes.
 */
class EncryptedVaultCacheResetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val stages = mutableListOf<CacheWriteStage>()
    private val markers = mutableListOf<Any>()
    // ONE engine across every instance in this test: readEncryptedSingleton
    // fail-closes (deletes) records a fresh key cannot decrypt, so instances
    // over the same storage MUST share the provider (mirrors RecoveryCrashTest).
    private val cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private val origin = "http://127.0.0.1:9701"
    private var probeArmed = false
    private var probeResult: Any? = "not-run"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("cache_reset_pr5")
        stages.clear()
        markers.clear()
        probeArmed = false
        probeResult = "not-run"
        vault = openVault()
    }

    private fun openVault(
        observer: (CacheWriteStage) -> Unit = { stages.add(it); markers.add(it) },
    ): EncryptedVault = EncryptedVault(
        storageDir,
        cipherEngine,
        cacheStageObserver = { stage ->
            observer(stage)
            if (probeArmed) {
                probeArmed = false
                // TEST-03: the clear currently HOLDS the vault mutex (the
                // observer runs inside its run) — an unrelated guarded write
                // must block until withTimeout gives up and returns null.
                probeResult = nestedRunBlocking {
                    withTimeoutOrNull(300L) {
                        vault.saveDraft(Draft("probe-session", "probe"))
                    }
                }
            }
        },
        directorySyncSeam = { markers.add(SYNC_MARK) },
    )

    // ---- fixtures (mirrors ConversationCachePurgeAndResetTest + RosterCacheTest) ----

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun indexFiles(): List<File> =
        File(storageDir, "conversation_indexes").listFiles()?.toList() ?: emptyList()

    private fun catalogFiles(): List<File> =
        storageDir.listFiles()!!.filter { it.name.startsWith("global_catalog_") && it.extension == "enc" }

    private fun contentFiles(p: CacheAccessPartition): List<File> = File(
        File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
        sha256Hex("${p.profileLocalId}:${p.originSha256}")
    ).listFiles()?.filter { !it.name.startsWith("cache_access_") } ?: emptyList()

    private fun rosterFiles(): List<File> = storageDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("roster_") && it.name.endsWith(".enc") }.toList()

    private fun summaryFiles(): List<File> = storageDir.walkTopDown()
        .filter { it.isFile && it.name.startsWith("summary_") && it.name.endsWith(".enc") }.toList()

    /** Family classification: everything the seven-phase clear must delete. */
    private fun familyOf(f: File): String? {
        val n = f.name
        if (f.parentFile?.name == "conversation_indexes") return "index"
        if (n.startsWith("global_catalog_")) return "catalog"
        if (n.startsWith("roster_")) return "roster"
        if (n.startsWith("summary_")) return "summary"
        if (n == "session_summary_cache.enc") return "summary"
        if (n.endsWith(".quarantine")) return "quarantine"
        if (f.parentFile?.name == "pending_interrupts") return "interrupt"
        if (n.startsWith("conversation_")) return "content"
        return null
    }

    private fun familyFiles(): List<File> =
        storageDir.walkTopDown().filter { it.isFile && familyOf(it) != null }.toList()

    private fun snapshotKeepFiles(): Map<String, ByteArray> =
        storageDir.walkTopDown()
            .filter { it.isFile && familyOf(it) == null }
            .associate { it.relativeTo(storageDir).path to it.readBytes() }

    private suspend fun seed(v: EncryptedVault) {
        v.saveConnectionProfile(ConnectionProfile("conn-1", origin, "A"))
        v.setActiveProfileId("conn-1")
        v.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
        v.saveSelectedContextProfile("alpha")
    }

    private suspend fun partition(v: EncryptedVault): CacheAccessPartition {
        v.setActiveProfileId("conn-1")
        val result = v.activeAccessPartition()
        if (result !is CacheAccessPartitionResult.Available) {
            val idx = File(storageDir, "profile_index.enc")
            val idxDesc = if (idx.exists()) idx.readText().take(300) else "ABSENT"
            assertTrue(
                "partition must resolve [${storageDir.name}] root=${storageDir.list()?.joinToString(",")} idx=$idxDesc: $result",
                false
            )
        }
        return (result as CacheAccessPartitionResult.Available).partition
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
        // Identity binding: non-empty workspace tag ⇔ non-null selected name
        // (the Gating seed uses null/null on an unscoped partition).
        selectedWorkspaceName = if (p.workspaceScopeTag.isEmpty()) null else "alpha",
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

    private fun rosterFor(p: CacheAccessPartition, capturedAt: Long): ContextProfileCacheV2 {
        val base = ContextProfileCacheV2(
            recordId = "",
            profileLocalId = p.profileLocalId,
            originSha256 = p.originSha256,
            cacheEpoch = p.cacheEpoch,
            workspaceScopeTag = p.workspaceScopeTag,
            createdAtMillis = capturedAt,
            updatedAtMillis = capturedAt,
            capturedAtEpochMillis = capturedAt,
            selectedWorkspaceName = "alpha",
            entries = listOf(CachedContextProfile(name = "alpha-ws"))
        )
        return base.copy(recordId = base.recordId())
    }

    private fun summaryFor() = SessionSummaryCache(
        sessions = listOf(
            SessionSummary(
                id = "sess-1", title = "T", preview = "p",
                startedAt = 1.0, messageCount = 1, source = null
            )
        )
    )

    private suspend fun seedFiveFamilies(v: EncryptedVault, p: CacheAccessPartition) {
        assertTrue(v.replaceConversationSnapshot(recordFor(p, "sess-A", "A body"), p))
        v.saveContextProfileCache(rosterFor(p, System.currentTimeMillis()), p)
        v.saveSessionSummaryCache(summaryFor(), p)
    }

    // ---- TEST-02: scope + byte-identical keep set ----

    @Test
    fun clearCache_deletesFiveFamilies_andEveryOtherArtifactIsByteIdentical() = runBlocking {
        seed(vault)
        val p = partition(vault)
        seedFiveFamilies(vault, p)
        vault.saveDraft(Draft("sess-A", "half-typed"))
        vault.savePendingTurn(
            PendingTurnRecord(pendingTurnId = "pt-1", storedSessionId = "sess-A", promptContent = "hi")
        )
        vault.savePendingInterrupt(PendingInterruptRecord(storedSessionId = "sess-A"))

        assertTrue("Precondition: content", contentFiles(p).isNotEmpty())
        assertTrue("Precondition: indexes", indexFiles().isNotEmpty())
        assertTrue("Precondition: catalog", catalogFiles().isNotEmpty())
        assertTrue("Precondition: roster", rosterFiles().isNotEmpty())
        assertTrue("Precondition: summaries", summaryFiles().isNotEmpty())

        val before = snapshotKeepFiles()
        assertTrue("Precondition: keep-set non-empty (state/drafts/selection files)", before.isNotEmpty())
        stages.clear()
        markers.clear()

        val result = vault.clearCache()
        assertEquals("fail-closed success", CacheClearResult.Complete, result)

        assertTrue("content gone", contentFiles(p).isEmpty())
        assertTrue("indexes gone", indexFiles().isEmpty())
        assertTrue("catalog gone", catalogFiles().isEmpty())
        assertTrue("roster gone", rosterFiles().isEmpty())
        assertTrue("summaries gone", summaryFiles().isEmpty())
        assertTrue("interrupt tail cleared (phase 7)", vault.getPendingInterrupts().isEmpty())

        val after = snapshotKeepFiles()
        assertEquals("no keep-set file added/removed", before.keys, after.keys)
        for ((path, bytes) in before) {
            assertArrayEquals("keep-set byte drift at $path", bytes, after.getValue(path))
        }

        // Functional keep-list (plan acceptance): drafts, pending turn,
        // credentials + rotation state (resolver still Available).
        assertEquals("half-typed", vault.getDraft("sess-A")?.composerText)
        assertEquals("pt-1", vault.getPendingTurn("pt-1")?.pendingTurnId)
        val resolver = vault.activeAccessPartition()
        assertTrue("credentials + rotation state survive: $resolver", resolver is CacheAccessPartitionResult.Available)
    }

    // ---- TEST-03: seven-phase order + sync seam + one mutex-held run ----

    @Test
    fun clearCache_runsSevenPhasesInOneMutexHeldRun_syncSeamAfterSummaryBeforeInterruptTail() = runBlocking {
        seed(vault)
        val p = partition(vault)
        seedFiveFamilies(vault, p)
        vault.savePendingInterrupt(PendingInterruptRecord(storedSessionId = "sess-A"))

        stages.clear()
        markers.clear()
        probeArmed = true

        val result = vault.clearCache()
        assertEquals(CacheClearResult.Complete, result)

        // Exact phase sequence as a subsequence with single fires of the two
        // new boundaries (PR-3's three conversation stages are pre-existing).
        fun stage(name: CacheWriteStage) = markers.indexOf(name)
        val rec = stage(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        val idx = stage(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        val cat = stage(CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED)
        val roster = stage(CacheWriteStage.STAGE_ROSTER_DELETED)
        val summary = stage(CacheWriteStage.STAGE_SUMMARY_DELETED)
        val sync = markers.indexOf(SYNC_MARK)
        assertTrue("phase1 fires (markers=$markers)", rec >= 0)
        assertTrue("phase2 after 1", idx > rec)
        assertTrue("phase3 after 2", cat > idx)
        assertTrue("phase4 roster after catalog", roster > cat)
        assertTrue("phase5 summary after roster", summary > roster)
        assertTrue("phase6 sync fires exactly once",
            markers.count { it == SYNC_MARK } == 1)
        assertTrue("phase6 sync after phase5", sync > summary)
        assertEquals("each new boundary fires exactly once",
            1, markers.count { it == CacheWriteStage.STAGE_ROSTER_DELETED })
        assertEquals("each new boundary fires exactly once",
            1, markers.count { it == CacheWriteStage.STAGE_SUMMARY_DELETED })
        assertTrue("phase7 tail cleared", vault.getPendingInterrupts().isEmpty())

        // Mutex-held: the probe write attempted inside the run never returned.
        assertEquals(
            "a guarded write must block during the clear (null = timed out waiting for the mutex)",
            null, probeResult
        )
    }

    // ---- TEST-04: idempotent re-run, zero additional deletions ----

    @Test
    fun clearCache_secondRun_isCompleteWithZeroAdditionalDeletions() = runBlocking {
        seed(vault)
        val p = partition(vault)
        seedFiveFamilies(vault, p)
        vault.saveDraft(Draft("sess-A", "keep me"))

        assertEquals(CacheClearResult.Complete, vault.clearCache())
        assertTrue(familyFiles().isEmpty())
        val firedFirstRun = stages.size
        assertTrue("first run fired deletion counters", firedFirstRun > 0)

        stages.clear()
        markers.clear()
        val second = vault.clearCache()
        assertEquals("second run completes", CacheClearResult.Complete, second)
        assertTrue(
            "zero additional deletion counters on the idempotent re-run (stages=$stages)",
            stages.isEmpty()
        )
        assertEquals("drafts still survive the second run", "keep me", vault.getDraft("sess-A")?.composerText)
        assertTrue(
            "resolver still Available after re-run",
            vault.activeAccessPartition() is CacheAccessPartitionResult.Available
        )
    }

    // ---- TEST-05: crash at each phase boundary → recoverable, then re-run completes ----

    private data class CrashCase(
        val stage: CacheWriteStage,
        val phase: CacheClearResiduePhase,
        val rosterGone: Boolean,
        val summaryGone: Boolean,
    )

    @Test
    fun crashAtEachPhaseBoundary_recoversLosesOnlyRemainingFamilies_andRerunCompletes() = runBlocking {
        val cases = listOf(
            CrashCase(
                CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED,
                CacheClearResiduePhase.CONVERSATION_CACHE, rosterGone = false, summaryGone = false
            ),
            CrashCase(
                CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED,
                CacheClearResiduePhase.CONVERSATION_CACHE, rosterGone = false, summaryGone = false
            ),
            CrashCase(
                CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED,
                CacheClearResiduePhase.CONVERSATION_CACHE, rosterGone = false, summaryGone = false
            ),
            CrashCase(
                CacheWriteStage.STAGE_ROSTER_DELETED,
                CacheClearResiduePhase.ROSTER, rosterGone = true, summaryGone = false
            ),
            CrashCase(
                CacheWriteStage.STAGE_SUMMARY_DELETED,
                CacheClearResiduePhase.SUMMARIES, rosterGone = true, summaryGone = true
            ),
        )

        for (c in cases) {
            storageDir = tempFolder.newFolder("crash_${c.stage}")
            stages.clear()
            markers.clear()
            probeArmed = false

            var armed = true
            val crashing = openVault(observer = { stage ->
                stages.add(stage)
                markers.add(stage)
                if (armed && stage == c.stage) {
                    throw IllegalStateException("injected interruption at $stage")
                }
            })
            seed(crashing)
            val p = partition(crashing)
            seedFiveFamilies(crashing, p)
            crashing.saveDraft(Draft("sess-A", "draft-$c"))
            stages.clear()
            markers.clear()

            val result = crashing.clearCache()
            assertTrue("${c.stage}: residue carried, got $result", result is CacheClearResult.Residual)
            assertEquals(
                "${c.stage}: named phase",
                c.phase, (result as CacheClearResult.Residual).phase
            )

            // Re-open on the same storage: recovery runs.
            armed = false
            val reopened = EncryptedVault(
                storageDir,
                cipherEngine,
                cacheStageObserver = { stages.add(it) },
            )
            val p2 = partition(reopened)

            val conv = reopened.readConversationSnapshot(p2, "sess-A")
            assertTrue(
                "${c.stage}: stale content must never read Corrupt/Available — got $conv",
                conv is CacheReadResult.Absent
            )

            if (c.rosterGone) {
                assertTrue(
                    "${c.stage}: roster family deleted before the crash point",
                    rosterFiles().isEmpty()
                )
                assertTrue(
                    "${c.stage}: roster read Absent",
                    reopened.readContextProfileCache(p2) is CacheReadResult.Absent
                )
            } else {
                assertTrue("${c.stage}: roster survives", rosterFiles().isNotEmpty())
                assertTrue(
                    "${c.stage}: context profile cache reads Available, got ${reopened.readContextProfileCache(p2)} " +
                        "[epoch p=${p.cacheEpoch} p2=${p2.cacheEpoch} roster=${rosterFiles()}]",
                    reopened.readContextProfileCache(p2) is CacheReadResult.Available
                )
            }

            if (c.summaryGone) {
                assertTrue("${c.stage}: summary family deleted", summaryFiles().isEmpty())
            } else {
                assertTrue("${c.stage}: summary survives", summaryFiles().isNotEmpty())
            }

            // Credentials/sign-in and drafts survive every boundary.
            assertTrue(
                "${c.stage}: resolver stays Available",
                reopened.activeAccessPartition() is CacheAccessPartitionResult.Available
            )
            assertEquals(
                "${c.stage}: draft survives",
                "draft-$c", reopened.getDraft("sess-A")?.composerText
            )

            // Re-run completes and drains all five families.
            assertEquals(
                "${c.stage}: idempotent re-run completes",
                CacheClearResult.Complete, reopened.clearCache()
            )
            assertTrue(
                "${c.stage}: all five families gone after re-run",
                familyFiles().isEmpty()
            )
        }
    }

    private companion object {
        const val SYNC_MARK = "SYNC_SEAM"
    }
}
