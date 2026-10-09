package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PR-1 (review P2-1): [fsyncDirectoryBestEffort] is the default
 * [EncryptedVault] directorySync seam — a best-effort android.system.Os
 * fsync of the parent directory (REQ-02 durability across power loss for
 * rotation-state renames), never a no-op, and every failure (including the
 * JVM unit-test android stub) is swallowed (PAT-01/RISK-03: never blocks
 * the mutation it guards).
 */
class BestEffortDirectorySyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("best_effort_sync_test")
    }

    @Test
    fun fsyncDirectoryBestEffort_neverThrows_onRealOrMissingDirectory() {
        val dir = tempFolder.newFolder("fsync_target")
        // JVM: android.system.Os is a unit-test stub — the failure must be
        // swallowed; on a real device this fsyncs the directory fd.
        fsyncDirectoryBestEffort(dir)
        fsyncDirectoryBestEffort(File(dir, "missing_child"))
    }

    @Test
    fun defaultVaultSyncSeam_mintsEpochAndServesOfflineWithoutThrowing() = runBlocking {
        val vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        val origin = "http://127.0.0.1:9121"
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "T")
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
        assertNotNull(
            "Rotation through the default best-effort sync seam must stay available",
            vault.activeAccessPartition()
        )
    }
}
