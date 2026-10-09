package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * PR-6 TEST-01/TEST-02/TEST-03: sign-out keep/clear cache choice at the
 * vault boundary.
 *
 * - KEEP (REQ-02): ciphertext retained; rotated SIGNED_OUT epoch observed
 *   durable BEFORE the credential file disappears (injected ordering probe);
 *   next sign-in yields a FRESH epoch — every A-era read addressed through
 *   the resolver-gated partition misses (Unavailable/Absent), old bytes
 *   never reach a presentation-shaped outcome, fake REST transport sees
 *   zero calls.
 * - CLEAR (REQ-05/REQ-08): profile subtree + every P-owned conversation
 *   index gone, catalog holds no locator of a deleted index, zero cache
 *   files remain for P; drafts/pending/other-profile credentials and
 *   profile records survive byte-identical; delete order content → index →
 *   catalog (PR-3 PAT-01 stages), then directory-sync; re-run is idempotent.
 * - Crash injections (REQ-08): rotation fault before credential delete and
 *   stage-observer faults mid-purge (subtree/index/catalog) all preserve
 *   fail-closed reads; purge faults never fail the sign-out itself.
 */
class EncryptedVaultSignOutCacheChoiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    private var syncCount = 0
    private var failSyncAtCall = -1
    private val stages = mutableListOf<CacheWriteStage>()
    private var crashAtStage: CacheWriteStage? = null
    private var transportCalls = 0

    private val originA = "http://127.0.0.1:9611"
    private val originB = "http://127.0.0.1:9722"

    private val catalogAad =
        "GLOBAL_CONVERSATION_CATALOG:1:global-conversation-catalog:catalog-v1".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("signout_cache_choice_test")
        engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        syncCount = 0
        failSyncAtCall = -1
        stages.clear()
        crashAtStage = null
        transportCalls = 0
        vault = newVault()
    }

    private fun newVault(): EncryptedVault = EncryptedVault(
        storageDir = storageDir,
        cipherEngine = engine,
        directorySync = { _ ->
            syncCount++
            if (syncCount == failSyncAtCall) {
                throw IOException("injected directory sync failure")
            }
        },
        cacheStageObserver = { stage ->
            stages += stage
            if (stage == crashAtStage) {
                throw IOException("injected stage crash at $stage")
            }
        }
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun profileDir(profileLocalId: String): File =
        File(File(storageDir, "cache"), sha256Hex(profileLocalId))

    private fun originDir(profileLocalId: String, originSha256: String): File =
        File(profileDir(profileLocalId), sha256Hex("$profileLocalId:$originSha256"))

    private fun stateFile(profileLocalId: String, originSha256: String): File =
        originDir(profileLocalId, originSha256)
            .resolve("cache_access_${sha256Hex("cache-access:$profileLocalId:$originSha256")}.enc")

    private fun credentialFile(profileLocalId: String): File =
        storageDir.resolve("auth_credential_${sha256Hex("auth_credential_$profileLocalId")}.enc")

    private fun profileRecordFile(profileLocalId: String): File =
        storageDir.resolve("connection_profile_${sha256Hex("connection_profile_$profileLocalId")}.enc")

    private fun indexFiles(): List<File> =
        File(storageDir, "conversation_indexes").listFiles()?.toList() ?: emptyList()

    private fun catalogFiles(): List<File> =
        storageDir.listFiles()!!.filter { it.name.startsWith("global_catalog_") && it.extension == "enc" }

    private fun contentFiles(p: CacheAccessPartition): List<File> =
        originDir(p.profileLocalId, p.originSha256).walkTopDown()
            .filter {
                it.isFile && it.name.startsWith("conversation_") &&
                    it.name.endsWith(".enc") && !it.name.endsWith(".quarantine")
            }
            .toList()

    private fun stateRotationReason(profileLocalId: String, originSha256: String): String? {
        val file = stateFile(profileLocalId, originSha256)
        if (!file.exists()) return null
        val aad = "CACHE_ACCESS_STATE:1:cache-access:$profileLocalId:$originSha256:$profileLocalId:$originSha256"
            .toByteArray(Charsets.UTF_8)
        val json = engine.decrypt(file.readBytes(), aad).getOrThrow().toString(Charsets.UTF_8)
        return when {
            json.contains("INITIAL_AUTH") -> "INITIAL_AUTH"
            json.contains("CREDENTIAL_REPLACED") -> "CREDENTIAL_REPLACED"
            json.contains("SIGNED_OUT") -> "SIGNED_OUT"
            json.contains("ORIGIN_REPLACED") -> "ORIGIN_REPLACED"
            else -> null
        }
    }

    /** Test-side copy of the 4-byte-BE length-prefixed envelope header (write path format). */
    private fun envelopeHeaderRecordId(file: File): String? {
        val bytes = file.readBytes()
        if (bytes.size < 4) return null
        val len = ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
        if (len <= 0 || bytes.size < 4 + len) return null
        return String(bytes, 4, len, Charsets.UTF_8)
    }

    private fun indexRecordIdFor(p: CacheAccessPartition): String =
        "conversation-index:${p.profileLocalId}:${p.originSha256}:${p.cacheEpoch}:${p.workspaceScopeTag}"

    private suspend fun seed(origin: String = originA, profileId: String = "conn-1") {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = profileId, normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId(profileId)
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-v1"),
                canonicalOrigin = origin
            ),
            profileLocalId = profileId
        )
    }

    private suspend fun requirePartition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun repo(): DefaultAuthRepository = DefaultAuthRepository(
        vaultStorage = vault,
        httpTransport = object : AuthHttpTransport {
            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?
            ): HttpResponse {
                transportCalls++
                return HttpResponse(500, body = "unused")
            }
        }
    )

    private suspend fun saveRecord(p: CacheAccessPartition) {
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("s1", "T", null, 1.0, 1, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = p.profileLocalId
            ),
            p
        )
    }

    private suspend fun saveRoster(p: CacheAccessPartition) {
        val capturedAt = System.currentTimeMillis()
        val base = ContextProfileCacheV2(
            recordId = "",
            profileLocalId = p.profileLocalId,
            originSha256 = p.originSha256,
            cacheEpoch = p.cacheEpoch,
            workspaceScopeTag = p.workspaceScopeTag,
            createdAtMillis = capturedAt,
            updatedAtMillis = capturedAt,
            capturedAtEpochMillis = capturedAt,
            // null name => expected workspace tag "u" — matches the fresh
            // activation partition, otherwise writeRosterRecordInternal
            // silently skips the write (identity/tag guard).
            selectedWorkspaceName = null,
            entries = listOf(CachedContextProfile("alpha-ws", isRunning = false, isActive = true))
        )
        vault.saveContextProfileCache(base.copy(recordId = base.recordId()), p)
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

    private fun freshVaultDir(name: String) {
        storageDir = tempFolder.newFolder(name)
        syncCount = 0
        failSyncAtCall = -1
        stages.clear()
        crashAtStage = null
        vault = newVault()
    }

    // ------------------------------------------------------------------ TEST-01
    @Test
    fun signOutKeep_retainsCiphertext_nextSignInCannotReadOldBytes() = runBlocking {
        seed()
        val originSha = sha256Hex(originA)
        val pA = requirePartition()
        saveRecord(pA)
        saveRoster(pA)
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-100", "secret payload"), pA))
        assertTrue(vault.readSessionSummaryCache(pA) is CacheReadResult.Available)
        assertTrue(vault.readContextProfileCache(pA) is CacheReadResult.Available)
        assertTrue(vault.readConversationSnapshot(pA, "sess-100") is CacheReadResult.Available)

        val credentialBefore = credentialFile("conn-1").readBytes()
        val retainedBefore = (contentFiles(pA) + indexFiles()).associateWith { it.readBytes() }

        // Injected ordering probe: fault the next directory sync so the flow
        // dies between the rotation write and the credential delete — only
        // work committed BEFORE that point may be observable.
        failSyncAtCall = syncCount + 1
        var threw = false
        try {
            repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.KEEP)
        } catch (expected: IOException) {
            threw = true
        }
        assertTrue("injected rotation fault must surface", threw)
        assertEquals(
            "SIGNED_OUT epoch must be durable BEFORE the credential file disappears",
            "SIGNED_OUT",
            stateRotationReason("conn-1", originSha)
        )
        assertTrue("credential file still present at abort", credentialFile("conn-1").exists())
        assertArrayEquals(credentialBefore, credentialFile("conn-1").readBytes())
        assertNull("SIGNED_OUT denies retained credential", vault.getAuthCredential("conn-1"))
        assertNull(newVault().getAuthCredential("conn-1"))
        retainedBefore.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }

        // Complete the sign-out (fault healed).
        failSyncAtCall = -1
        repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.KEEP)
        assertNull(vault.getAuthCredential("conn-1"))
        assertFalse("completed sign-out physically removes credential", credentialFile("conn-1").exists())
        retainedBefore.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        assertEquals(
            "rotated state remains durable after sign-out",
            "SIGNED_OUT",
            stateRotationReason("conn-1", originSha)
        )

        // KEEP: ciphertext retained on disk; resolver has no holder (no read path opens the files).
        assertTrue("KEEP retains content ciphertext", contentFiles(pA).isNotEmpty())
        assertTrue("KEEP retains index envelopes", indexFiles().isNotEmpty())
        val unresolved = vault.activeAccessPartition()
        assertTrue("resolver must be Unavailable with no holder: $unresolved", unresolved is CacheAccessPartitionResult.Unavailable)

        // Credential B signs in on the same profile → FRESH epoch.
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-v2"),
                canonicalOrigin = originA
            ),
            profileLocalId = "conn-1"
        )
        val pB = requirePartition()
        assertNotEquals("B signs in on a fresh epoch", pA.cacheEpoch, pB.cacheEpoch)
        assertEquals(pA.profileLocalId, pB.profileLocalId)
        assertEquals(pA.workspaceScopeTag, pB.workspaceScopeTag)

        // Every A-era record ID/AAD/filename addressed through B's fresh
        // partition misses — Unavailable/Absent, never the old bytes.
        assertEquals(CacheReadResult.Absent, vault.readSessionSummaryCache(pB))
        assertEquals(CacheReadResult.Absent, vault.readContextProfileCache(pB))
        assertEquals(CacheReadResult.Absent, vault.readConversationSnapshot(pB, "sess-100"))
        assertEquals(CacheReadResult.Absent, vault.readConversationSnapshot(pB, "sess-999"))

        // ...while the retained bytes are still physically present (retention
        // held), just unreachable through any resolver-gated path.
        assertTrue(contentFiles(pA).isNotEmpty())
        assertTrue(indexFiles().isNotEmpty())

        // Fake REST transport observes ZERO calls across sign-out + offline reads.
        assertEquals("sign-out and offline reads are strictly local", 0, transportCalls)
    }

    // ------------------------------------------------------------------ TEST-02
    @Test
    fun signOutClear_purgesAllPrimaryRecords_indexes_catalog() = runBlocking {
        seed()
        // Second profile (own credential) — must survive byte-identically.
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-2", normalizedOrigin = originB, displayLabel = "Other")
        )
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok-other"),
                canonicalOrigin = originB
            ),
            profileLocalId = "conn-2"
        )
        // saveAuthCredential activates its profile (Ensures ACTIVE) — pA below
        // must be conn-1's partition, so explicitly re-activate conn-1.
        vault.setActiveProfileId("conn-1")
        val otherCredBefore = credentialFile("conn-2").readBytes()
        val otherProfileBefore = profileRecordFile("conn-2").readBytes()

        // PR-3 slots — survive both choices.
        vault.saveDraft(Draft(storedSessionId = "sess-100", composerText = "keep me"))
        vault.savePendingTurn(
            PendingTurnRecord(pendingTurnId = "pt-1", storedSessionId = "sess-100", promptContent = "hello")
        )

        val pA = requirePartition()
        saveRecord(pA)
        saveRoster(pA)
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-100", "secret payload"), pA))
        val catalogBeforeBytes = catalogFiles().single().readBytes()

        // conn-2 conversation — its index + catalog locator must survive.
        vault.setActiveProfileId("conn-2")
        val pC = requirePartition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(pC, "sess-200", "other payload"), pC))
        val otherContentBefore = contentFiles(pC).map { it.readBytes().toList() }

        vault.setActiveProfileId("conn-1")
        val draftsBefore = vault.getAllDrafts()
        val stagesBefore = stages.size

        repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.CLEAR)

        // Credential cleared.
        assertNull(vault.getAuthCredential("conn-1"))

        // (a) profile subtree gone; (b) zero cache files for P.
        assertFalse("profile subtree cache/<sha256(P)>/ must be gone", profileDir("conn-1").exists())
        assertTrue("zero cache files for P", contentFiles(pA).isEmpty())

        // (c) every P-owned conversation-index envelope is gone; conn-2's remains.
        val remainingHeaders = indexFiles().mapNotNull { envelopeHeaderRecordId(it) }
        assertTrue(
            "no conn-1 index may survive: $remainingHeaders",
            remainingHeaders.none { it.contains(":conn-1:") }
        )
        assertTrue(
            "conn-2 index must survive",
            remainingHeaders.contains(indexRecordIdFor(pC))
        )

        // (d) global catalog holds no locator of the deleted index, keeps conn-2's.
        val catalogFile = catalogFiles().single()
        val catalogJson = String(
            engine.decrypt(catalogFile.readBytes(), catalogAad).getOrThrow(),
            Charsets.UTF_8
        )
        assertFalse(
            "catalog must not hold a locator of the deleted index; " +
                "conn1Locator=" + sha256Hex(indexRecordIdFor(pA)) +
                " catalogs=" + catalogFiles().map { it.name } +
                " jsonHead=" + catalogJson.take(600),
            catalogJson.contains(sha256Hex(indexRecordIdFor(pA)))
        )
        assertTrue(catalogJson.contains(sha256Hex(indexRecordIdFor(pC))))
        assertTrue(
            "catalog must have been rewritten without the deleted locator",
            !catalogBeforeBytes.contentEquals(catalogFile.readBytes())
        )

        // Survivors byte-identical: other-profile credential + profile record.
        assertTrue(
            "other-profile credential must survive byte-identical",
            otherCredBefore.contentEquals(credentialFile("conn-2").readBytes())
        )
        assertTrue(
            "other-profile record must survive byte-identical",
            otherProfileBefore.contentEquals(profileRecordFile("conn-2").readBytes())
        )
        assertEquals(draftsBefore, vault.getAllDrafts())
        assertNotNull("pending turn survives", vault.getPendingTurn("pt-1"))
        assertNotNull("profile record of P survives sign-out", vault.getConnectionProfile("conn-1"))
        assertEquals("conn-2", vault.getConnectionProfile("conn-2")?.localId)

        // Delete order: content → index → catalog (PR-3 PAT-01 stages).
        val seen = stages.drop(stagesBefore)
        val recordIdx = seen.indexOf(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        val indexIdx = seen.indexOf(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        val catalogIdx = seen.indexOf(CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED)
        assertTrue("RECORD_DELETED observed: $seen", recordIdx >= 0)
        assertTrue("INDEX_DELETED observed: $seen", indexIdx >= 0)
        assertTrue("CATALOG_DELETED observed: $seen", catalogIdx >= 0)
        assertTrue("content -> index -> catalog", recordIdx < indexIdx && indexIdx < catalogIdx)

        // Other-profile reads stay intact (its partition never involved).
        vault.setActiveProfileId("conn-2")
        assertTrue(vault.readConversationSnapshot(pC, "sess-200") is CacheReadResult.Available)
        vault.setActiveProfileId("conn-1")

        // Idempotent: re-running deletes nothing extra, throws nothing.
        val otherContentAgain = contentFiles(pC).map { it.readBytes().toList() }
        repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.CLEAR)
        assertTrue(otherCredBefore.contentEquals(credentialFile("conn-2").readBytes()))
        assertTrue(otherContentBefore == otherContentAgain && otherContentAgain == contentFiles(pC).map { it.readBytes().toList() })
        assertTrue(
            indexFiles().mapNotNull { envelopeHeaderRecordId(it) }.contains(indexRecordIdFor(pC))
        )
    }

    // ------------------------------------------------------------------ TEST-03
    @Test
    fun bothChoices_rotateEpochBeforeCredentialRemoval() = runBlocking {
        for (choice in SignOutCacheChoice.entries) {
            freshVaultDir("rotate_${choice}")
            seed()
            val originSha = sha256Hex(originA)
            val credentialBefore = credentialFile("conn-1").readBytes()

            // Injected ordering probe: rotation committed BEFORE credential removal.
            failSyncAtCall = syncCount + 1
            var threw = false
            try {
                repo().signOut(clearDrafts = false, cacheChoice = choice)
            } catch (expected: IOException) {
                threw = true
            }
            assertTrue("$choice: injected rotation fault must surface", threw)
            assertEquals(
                "$choice: rotated SIGNED_OUT durable while credential still present",
                "SIGNED_OUT",
                stateRotationReason("conn-1", originSha)
            )
            assertTrue("$choice: credential file intact at abort", credentialFile("conn-1").exists())
            assertArrayEquals(credentialBefore, credentialFile("conn-1").readBytes())
            assertNull("$choice: retained credential is denied", vault.getAuthCredential("conn-1"))

            // Process-restart realism (fresh vault over the same dir): the
            // rotated epoch and the credential both survive the "crash".
            vault = newVault()
            assertTrue(credentialFile("conn-1").exists())
            assertArrayEquals(credentialBefore, credentialFile("conn-1").readBytes())
            assertNull("$choice: persisted SIGNED_OUT denies after reconstruction", vault.getAuthCredential("conn-1"))
            assertEquals("SIGNED_OUT", stateRotationReason("conn-1", originSha))

            failSyncAtCall = -1
            repo().signOut(clearDrafts = false, cacheChoice = choice)
            assertNull("$choice: credential removed on completion", vault.getAuthCredential("conn-1"))
            assertFalse("$choice: credential physically removed", credentialFile("conn-1").exists())
            if (choice == SignOutCacheChoice.KEEP) {
                assertEquals(
                    "KEEP: rotated state file survives sign-out",
                    "SIGNED_OUT",
                    stateRotationReason("conn-1", originSha)
                )
            } else {
                assertFalse(
                    "CLEAR: rotation state lives in the purged subtree",
                    stateFile("conn-1", originSha).exists()
                )
            }
        }
    }

    // ---------------------------------------------------- TEST-03 crash injection
    @Test
    fun crashInjection_eachPurgeStage_preservesFailClosedReads_isResumable() = runBlocking {
        for (crashStage in listOf(
            CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED,
            CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED,
            CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED
        )) {
            freshVaultDir("crash_$crashStage")
            seed()
            val originSha = sha256Hex(originA)
            val p = requirePartition()
            saveRecord(p)
            assertTrue(vault.replaceConversationSnapshot(recordFor(p, "sess-100", "secret payload"), p))

            crashAtStage = crashStage
            // REQ-08: sign-out itself never fails because of cache cleanup.
            repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.CLEAR)

            // Fail-closed at every observation point of the interrupted state.
            assertNull("$crashStage: credential removed", vault.getAuthCredential("conn-1"))
            val unresolved = vault.activeAccessPartition()
            assertTrue(
                "$crashStage: reads fail closed: $unresolved",
                unresolved is CacheAccessPartitionResult.Unavailable
            )
            // The rotation record lives INSIDE the purged subtree, so once any
            // purge stage has run CLEAR legitimately removes it with the rest —
            // fail-closed holds because the credential is gone first (SEC-01),
            // not because the epoch file survives.

            // Heal and resume: a re-run finishes the purge with no residue and
            // deletes nothing extra.
            crashAtStage = null
            repo().signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.CLEAR)
            assertFalse("$crashStage: subtree gone after resume", profileDir("conn-1").exists())
            assertTrue(
                "$crashStage: no conn-1 index after resume",
                indexFiles().mapNotNull { envelopeHeaderRecordId(it) }.none { it.contains(":conn-1:") }
            )
            val catalogs = catalogFiles()
            if (catalogs.isNotEmpty()) {
                val json = String(engine.decrypt(catalogs.single().readBytes(), catalogAad).getOrThrow(), Charsets.UTF_8)
                assertFalse(
                    "$crashStage: catalog locator of deleted index removed",
                    json.contains(sha256Hex("conversation-index:conn-1:${p.originSha256}:${p.cacheEpoch}:${p.workspaceScopeTag}"))
                )
            }
            assertTrue("$crashStage: fail-closed after resume", vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        }
    }
}
