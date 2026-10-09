package io.github.anshuman2401.hermesmobile.data.local

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
 * Plan §9 row (e) — grounds REQ-08: origin/profile purge and granular resets
 * delete conversation content → index envelopes → catalog, in that stage
 * order, while identity/state/drafts/pending/credentials survive `clearCache`.
 */
class ConversationCachePurgeAndResetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val stages = mutableListOf<CacheWriteStage>()
    private val originA = "http://127.0.0.1:9601"
    private val originB = "http://127.0.0.1:9602"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("purge_reset_test")
        stages.clear()
        vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            cacheStageObserver = { stage -> stages.add(stage) }
        )
    }

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

    private suspend fun seed() {
        vault.saveConnectionProfile(ConnectionProfile("conn-1", originA, "A"))
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = originA
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun partition(): CacheAccessPartition {
        vault.setActiveProfileId("conn-1")
        val result = vault.activeAccessPartition()
        assertTrue("partition must resolve: $result", result is CacheAccessPartitionResult.Available)
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

    private fun assertDeleteStageOrder(context: String) {
        val rec = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        val idx = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        val cat = stages.indexOf(CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED)
        assertTrue("$context: content delete fired (stages=$stages)", rec >= 0)
        assertTrue("$context: index delete after content (stages=$stages)", idx > rec)
        assertTrue("$context: catalog delete last (stages=$stages)", cat > idx)
    }

    @Test
    fun originRotationPurgesConversationContentThenIndexThenCatalog() = runBlocking {
        seed()
        val pA = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))
        assertTrue("Precondition: content on disk", contentFiles(pA).isNotEmpty())
        assertTrue("Precondition: index on disk", indexFiles().isNotEmpty())
        assertTrue("Precondition: catalog on disk", catalogFiles().isNotEmpty())

        stages.clear()
        // Origin edit on the SAME profile ⇒ PR-1 rotate+purge of the old subtree.
        vault.saveConnectionProfile(ConnectionProfile("conn-1", originB, "A"))
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok2"),
                canonicalOrigin = originB
            ),
            profileLocalId = "conn-1"
        )

        assertTrue("Old-origin content purged by layout", contentFiles(pA).isEmpty())
        assertDeleteStageOrder("origin rotation")
    }

    @Test
    fun profileDeleteRemovesConversationCacheArtifacts() = runBlocking {
        seed()
        // deleteConnectionProfile refuses the LAST remaining profile — keep a second.
        vault.saveConnectionProfile(ConnectionProfile("conn-2", "http://127.0.0.1:9602", "B"))
        val pA = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))
        assertTrue(indexFiles().isNotEmpty())
        assertTrue(catalogFiles().isNotEmpty())

        stages.clear()
        assertTrue(vault.deleteConnectionProfile("conn-1"))

        assertTrue("Profile delete removes that partition's content", contentFiles(pA).isEmpty())
        assertDeleteStageOrder("profile delete")
    }

    @Test
    fun clearCache_removesConversationArtifacts_butKeepsAccessStateAndCredentials() = runBlocking {
        seed()
        val pA = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))
        assertTrue(catalogFiles().isNotEmpty())

        stages.clear()
        vault.clearCache()

        assertTrue("clearCache removes conversation content", contentFiles(pA).isEmpty())
        assertTrue("clearCache removes partition indexes", indexFiles().isEmpty())
        assertTrue("clearCache removes the global catalog", catalogFiles().isEmpty())
        assertDeleteStageOrder("clearCache")

        // CacheAccessState + credentials survive ⇒ partition still resolves.
        val after = vault.activeAccessPartition()
        assertTrue(
            "clearCache must keep rotation state and credentials: $after",
            after is CacheAccessPartitionResult.Available
        )
    }

    @Test
    fun clearAll_wipesConversationIndexesAndCatalog() = runBlocking {
        seed()
        val pA = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(pA, "sess-A", "A body"), pA))
        assertTrue(indexFiles().isNotEmpty())
        assertTrue(catalogFiles().isNotEmpty())

        stages.clear()
        vault.clearAll()

        assertTrue("clearAll wipes conversation_indexes/", indexFiles().isEmpty())
        assertTrue("clearAll wipes the catalog", catalogFiles().isEmpty())
        assertTrue(contentFiles(pA).isEmpty())
        assertFalse("Stage ordering observed end-to-end", stages.isEmpty())
        assertDeleteStageOrder("clearAll")
    }
}
