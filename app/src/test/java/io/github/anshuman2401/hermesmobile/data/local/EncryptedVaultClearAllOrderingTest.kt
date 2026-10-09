package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Plan CACHE-PR5 TEST-07 — full local wipe: one atomic vault transaction
 * (credentials gone → selection gone → drafts gone → pending turns gone,
 * proven by the clearAll body's source order and by the post-state), idempotent
 * re-run returns Complete, and conversation/roster/summary families drain too.
 */
class EncryptedVaultClearAllOrderingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private val origin = "http://127.0.0.1:9702"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("clear_all_ordering")
        vault = EncryptedVault(
            storageDir,
            AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            cacheStageObserver = { },
        )
    }

    private fun source(relPath: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (candidate in listOf(File(dir, relPath), File(dir, "app/$relPath"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("source not found: $relPath")
    }

    @Test
    fun clearAll_bodyOrdersCredentialsThenSelectionThenDraftsThenPendingTurns() {
        val src = source("src/main/java/io/github/anshuman2401/hermesmobile/data/local/EncryptedVault.kt")
        val start = src.indexOf("override suspend fun clearAll(): CacheClearResult")
        assertTrue("clearAll body present", start > 0)
        val end = src.indexOf("\n    override suspend fun ", start + 10)
        val body = src.substring(start, if (end > 0) end else src.length)

        val auth = body.indexOf("legacyAuthFile.delete()")
        val selection = body.indexOf("selectionFile.delete()")
        val drafts = body.indexOf("draftsDir.listFiles()")
        val pending = body.indexOf("pendingDir.listFiles()")
        assertTrue("auth credential delete in body", auth > 0)
        assertTrue("selection delete in body", selection > 0)
        assertTrue("drafts delete in body", drafts > 0)
        assertTrue("pending turns delete in body", pending > 0)
        assertTrue("credentials before selection", auth < selection)
        assertTrue("selection before drafts", selection < drafts)
        assertTrue("drafts before pending turns", drafts < pending)
    }

    @Test
    fun clearAll_wipesEverySeededEntity_atomically_andReRunCompletes() = runBlocking {
        vault.saveConnectionProfile(ConnectionProfile("conn-1", origin, "A"))
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
        vault.saveSelectedContextProfile("alpha")
        val p = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        vault.saveDraft(Draft("sess-A", "draft-text"))
        vault.savePendingTurn(
            PendingTurnRecord(pendingTurnId = "pt-1", storedSessionId = "sess-A", promptContent = "hi")
        )
        vault.savePendingInterrupt(PendingInterruptRecord(storedSessionId = "sess-A"))
        assertTrue("draft seeded", vault.getDraft("sess-A") != null)

        val result = vault.clearAll()
        assertEquals(CacheClearResult.Complete, result)

        // File-level post-state FIRST (before any API call that would run
        // migration repair and re-materialize empty records): the wipe deleted
        // each family's file in this one transaction.
        assertTrue("profile index gone", !File(storageDir, "profile_index.enc").exists())
        assertTrue("selection gone", !File(storageDir, "session_selection.enc").exists())
        assertTrue("draft store empty", (File(storageDir, "drafts").listFiles() ?: emptyArray<File>()).isEmpty())
        assertTrue("pending store empty", (File(storageDir, "pending_turns").listFiles() ?: emptyArray<File>()).isEmpty())
        assertTrue("conversation indexes wiped", !(File(storageDir, "conversation_indexes").exists()))

        // Atomic post-state: every entity class gone in one run.
        assertEquals("no draft survives", null, vault.getDraft("sess-A"))
        assertEquals("no pending turn survives", null, vault.getPendingTurn("pt-1"))
        assertTrue("no interrupt survives", vault.getPendingInterrupts().isEmpty())
        val resolver = vault.activeAccessPartition()
        assertTrue("credentials gone → resolver unavailable: $resolver", resolver !is CacheAccessPartitionResult.Available)
        assertTrue("no conversation/index/catalog file survives", noConversationFamilies())

        // Idempotent re-run: still Complete, nothing left to delete.
        assertEquals(CacheClearResult.Complete, vault.clearAll())
        assertTrue(noConversationFamilies())
    }

    private fun noConversationFamilies(): Boolean {
        val families = storageDir.walkTopDown().filter {
            it.isFile && (
                it.name.startsWith("conversation_") ||
                    it.name.startsWith("global_catalog_") ||
                    it.name.startsWith("roster_") ||
                    it.name.startsWith("summary_")
                )
        }
        return families.none()
    }
}
