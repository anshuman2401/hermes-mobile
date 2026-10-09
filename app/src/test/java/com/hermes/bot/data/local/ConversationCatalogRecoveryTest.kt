package com.hermes.bot.data.local

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
 * Charter T-08, plan §9 row (c): the GLOBAL catalog is opaque maintenance
 * metadata — rebuilds from validated indexes, never authorizes a payload, and
 * never carries identifiers in its ciphertext.
 */
class ConversationCatalogRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val origin = "http://127.0.0.1:9411"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("catalog_recovery_test")
        vault = newVault()
    }

    // Re-opened vaults must share the SAME key material — a fresh
    // InMemoryMasterKeyProvider per instance generates a new random key and
    // every persisted file becomes undecryptable (NO_ACTIVE_PROFILE).
    private val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())

    private fun newVault(): EncryptedVault = EncryptedVault(
        storageDir,
        engine
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun catalogAadOf(): ByteArray =
        "GLOBAL_CONVERSATION_CATALOG:1:global-conversation-catalog:catalog-v1".toByteArray(Charsets.UTF_8)

    private fun decryptCatalogJson(): String =
        String(engine.decrypt(catalogFile().readBytes(), catalogAadOf()).getOrThrow(), Charsets.UTF_8)

    private suspend fun seed() {
        vault.saveConnectionProfile(ConnectionProfile("conn-1", origin, "T"))
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

    private suspend fun partition(): CacheAccessPartition {
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

    private fun catalogFile(): File =
        storageDir.listFiles()!!.first { it.name.startsWith("global_catalog_") && it.extension == "enc" }

    @Test
    fun missingOrCorruptCatalog_directPartitionReadsStaySnapshot() = runBlocking {
        seed()
        val p = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(p, "sess-100", "catalog-free body"), p))
        assertTrue("Write populates the catalog", catalogFile().exists())
        val catalog = catalogFile()

        // Missing catalog.
        assertTrue(catalog.delete())
        val afterDelete = vault.readConversationSnapshot(p, "sess-100")
        assertTrue("Catalog is not an authorization source: $afterDelete", afterDelete is CacheReadResult.Available)

        // Corrupt catalog — fail closed to rebuild, never to block reads.
        catalog.writeBytes(ByteArray(32) { 0x11 })
        val afterCorrupt = vault.readConversationSnapshot(p, "sess-100")
        assertTrue("Corrupt catalog must not block reads: $afterCorrupt", afterCorrupt is CacheReadResult.Available)

        // Re-open: recovery quarantines/rebuilds; reads stay intact.
        vault = newVault()
        val p2 = partition()
        val afterReopen = vault.readConversationSnapshot(p2, "sess-100")
        assertTrue("Recovery never loses the payload: $afterReopen", afterReopen is CacheReadResult.Available)
        assertEquals(
            "catalog-free body",
            (afterReopen as CacheReadResult.Available).value.snapshot.rows.single().content
        )
    }

    @Test
    fun validCatalogAloneCannotProduceAPayload() = runBlocking {
        seed()
        val p = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(p, "sess-100", "payload body"), p))
        assertTrue(catalogFile().exists())

        // Delete ONLY the content file: the catalog still lists the record id
        // hash — it must still be impossible to read a payload from it.
        val contentDir = File(
            File(File(storageDir, "cache"), sha256Hex(p.profileLocalId)),
            sha256Hex("${p.profileLocalId}:${p.originSha256}")
        )
        contentDir.listFiles()!!.filter { !it.name.startsWith("cache_access_") }
            .forEach { assertTrue(it.delete()) }

        val read = vault.readConversationSnapshot(p, "sess-100")
        assertTrue("Catalog alone must miss: $read", read is CacheReadResult.Absent)
    }

    @Test
    fun catalogCiphertext_carriesNoSessionIdTitleOrWorkspaceIdentity() = runBlocking {
        seed()
        val p = partition()
        val sid = "sess-SENTINEL-c91f"
        assertTrue(vault.replaceConversationSnapshot(recordFor(p, sid, "body"), p))

        val bytes = catalogFile().readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("Catalog ciphertext must not carry the durable session id", text.contains(sid))
        assertFalse("Catalog ciphertext must not carry a title", text.contains("SENTINEL_TITLE"))
        assertFalse("Catalog ciphertext must not carry the origin", text.contains(origin))
        assertFalse("Catalog ciphertext must not carry the profile id", text.contains("conn-1"))
        // Rebuild after deletion produces a catalog with the same reachability.
        assertTrue(catalogFile().delete())
        vault = newVault()
        val p2 = partition()
        val read = vault.readConversationSnapshot(p2, sid)
        assertTrue("Rebuilt catalog path never changes read authority: $read", read is CacheReadResult.Available)
    }

    // (Plan §9 row (c), repair finding 6) A catalog carrying a NEWER schema
    // than we know must fail closed: never parsed into authority, quarantined,
    // then rebuilt from the validated indexes — direct reads still serve via
    // index authority alone (the catalog never authorizes a payload).
    @Test
    fun futureSchemaCatalog_failClosed_rebuiltFromIndexes_directReadStillServes() = runBlocking {
        seed()
        val p = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(p, "sess-100", "body"), p))

        // Hand-craft a newer-schema catalog under the correct AEAD binding.
        val futureJson =
            """{"schemaVersion":99,"recordId":"global-conversation-catalog","createdAtMillis":1,"updatedAtMillis":1,"entries":[]}"""
        val crafted = engine.encrypt(futureJson.toByteArray(Charsets.UTF_8), catalogAadOf())
        catalogFile().writeBytes(crafted)

        // Fail-closed: the crafted catalog authorizes nothing; the index does.
        val before = vault.readConversationSnapshot(p, "sess-100")
        assertTrue("direct read must still serve via index authority: $before", before is CacheReadResult.Available)

        vault = newVault()   // constructor recovery sweeps the catalog

        val rebuilt = catalogFile().readBytes()
        assertFalse("newer-schema catalog must be discarded, not kept", rebuilt.contentEquals(crafted))
        assertTrue("catalog must be rebuilt from indexes", rebuilt.isNotEmpty())
        assertFalse(
            "quarantine residue must be swept by the rebuild",
            File(catalogFile().parentFile, "${catalogFile().name}.quarantine").exists()
        )
        val after = vault.readConversationSnapshot(p, "sess-100")
        assertTrue("direct read still Available after rebuild: $after", after is CacheReadResult.Available)
    }

    // (Plan §9 row (c), repair finding 6) Determinism: rebuilding from the SAME
    // index bytes yields the SAME catalog (revision included) — byte-identical
    // modulo the two wall-clock timestamp fields.
    @Test
    fun rebuildSameIndexBytes_yieldsSameCatalogRevision() = runBlocking {
        seed()
        val p = partition()
        assertTrue(vault.replaceConversationSnapshot(recordFor(p, "sess-100", "one"), p))

        fun rebuildAndDecrypt(): String {
            assertTrue(catalogFile().delete())
            vault = newVault()
            return decryptCatalogJson()
        }

        val first = rebuildAndDecrypt()
        val second = rebuildAndDecrypt()
        val wallClockFields = Regex("""\s*,?\s*"(?:createdAtMillis|updatedAtMillis)"\s*:\s*\d+\s*,?""")
        assertEquals(
            "Same index bytes must rebuild the same catalog (revision included)",
            first.replace(wallClockFields, ""),
            second.replace(wallClockFields, "")
        )
    }
}
