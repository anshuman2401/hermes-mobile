package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
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
 * REQ-04 (FILE-15): exact on-disk layout of the partitioned cache plus the
 * granular-reset scopes — clearCache keeps rotation state, clearAll wipes the
 * whole tree, and records are never stored as cleartext.
 */
class StorageLayoutTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault

    private val origin = "http://127.0.0.1:9119"

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("storage_layout_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun seed(): CacheAccessPartition {
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
        return (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
    }

    private suspend fun saveRecord(partition: CacheAccessPartition, name: String? = null) {
        vault.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("s1", "Layout Secret", null, 1.0, 1, "android")),
                syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
                profileLocalId = "conn-1",
                cachedProfileName = name
            ),
            partition
        )
    }

    @Test
    fun layout_matchesDocumentedPartitionedPaths() = runBlocking {
        val partition = seed()
        saveRecord(partition)

        val profileDir = File(File(storageDir, "cache"), sha256Hex("conn-1"))
        val originSha = sha256Hex(origin)
        val originDirectory = File(profileDir, sha256Hex("conn-1:$originSha"))
        assertTrue("Profile directory must be cache/<sha256(P)>", profileDir.isDirectory)
        assertTrue("Origin directory must be cache/<sha256(P)>/<sha256(P:O)>", originDirectory.isDirectory)

        val recordId = "session-summaries:conn-1:$originSha:${partition.cacheEpoch}:${partition.workspaceScopeTag}"
        val summaryFile = File(originDirectory, "summary_${sha256Hex(recordId)}.enc")
        assertTrue("Summary file name must be summary_<sha256(recordId)>.enc", summaryFile.isFile)

        val stateFile = File(originDirectory, "cache_access_${sha256Hex("cache-access:conn-1:$originSha")}.enc")
        assertTrue("Rotation state file must use the documented name", stateFile.isFile)

        assertFalse(
            "Record must never be cleartext",
            String(summaryFile.readBytes(), Charsets.UTF_8).contains("Layout Secret")
        )

        // A second workspace scope records independently inside the same origin directory.
        val scoped = partition.copy(workspaceScopeTag = "w:${sha256Hex("architect")}")
        saveRecord(scoped, name = "architect")
        val scopedId =
            "session-summaries:conn-1:$originSha:${partition.cacheEpoch}:w:${sha256Hex("architect")}"
        assertTrue(
            "Each workspace scope gets its own record file",
            File(originDirectory, "summary_${sha256Hex(scopedId)}.enc").isFile
        )
        assertEquals("Two workspace records can coexist", 3, originDirectory.listFiles()!!.size)
    }

    @Test
    fun clearCache_keepsStateFile_clearAllWipesTreeQuarantineAndDisposition() = runBlocking {
        val partition = seed()
        saveRecord(partition)
        val originDirectory = File(
            File(File(storageDir, "cache"), sha256Hex("conn-1")),
            sha256Hex("conn-1:${sha256Hex(origin)}")
        )
        val stateFile = File(
            originDirectory,
            "cache_access_${sha256Hex("cache-access:conn-1:${sha256Hex(origin)}")}.enc"
        )
        val dispositionFile =
            File(storageDir, "cache_migration_state_${sha256Hex("cache-migration-state")}.enc")

        vault.clearCache()
        assertEquals(
            "Records gone after clearCache",
            CacheReadResult.Absent,
            vault.readSessionSummaryCache(partition)
        )
        assertTrue("Rotation state survives clearCache", stateFile.isFile)
        assertEquals(
            "Exactly the state file remains in the origin directory",
            1,
            originDirectory.listFiles()!!.size
        )

        saveRecord(partition)
        // Residue and disposition exist before clearAll in a full lifecycle; create
        // a quarantine residue to prove clearAll sweeps it too.
        val quarantine = File(storageDir, "session_summary_cache.quarantine.enc")
        quarantine.writeBytes(byteArrayOf(7, 7, 7))

        vault.clearAll()
        assertFalse("cache/ tree wiped", File(storageDir, "cache").exists())
        assertFalse("Quarantine residue wiped", quarantine.exists())
        assertFalse("Disposition record wiped", dispositionFile.exists())
    }
}
