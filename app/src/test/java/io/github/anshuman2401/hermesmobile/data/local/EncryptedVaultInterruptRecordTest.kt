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

class EncryptedVaultInterruptRecordTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var cipherEngine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    private val sample = PendingInterruptRecord(
        storedSessionId = "sess-round-trip",
        runtimeSessionId = "rt-snapshot",
        enqueuedAtMs = 1_700_000_000_000L
    )

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("interrupt_vault")
        cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        vault = EncryptedVault(vaultDir, cipherEngine)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun pendingInterrupt_roundTrip_survivesVaultRecreation() = runBlocking {
        vault.savePendingInterrupt(sample)
        // Process death: a fresh vault instance over the same directory must
        // deserialize the exact record (serialize -> deserialize round trip).
        val restored = EncryptedVault(vaultDir, cipherEngine).getPendingInterrupts()
        assertEquals(1, restored.size)
        assertEquals(sample, restored[0])
    }

    @Test
    fun pendingInterrupt_ciphertextHidesSessionIdentifiers() = runBlocking {
        vault.savePendingInterrupt(sample)
        val files = File(vaultDir, "pending_interrupts")
            .listFiles { _, name -> name.startsWith("pinterrupt_") && name.endsWith(".enc") }
        assertTrue(files != null && files.isNotEmpty())
        val raw = String(files!![0].readBytes(), Charsets.UTF_8)
        assertFalse("Stored session id must be encrypted at rest", raw.contains(sample.storedSessionId))
        assertFalse("Runtime session id must be encrypted at rest", raw.contains("rt-snapshot"))
    }

    @Test
    fun pendingInterrupt_deleteAndClear() = runBlocking {
        vault.savePendingInterrupt(sample)
        vault.savePendingInterrupt(sample.copy(storedSessionId = "sess-other", runtimeSessionId = null))

        vault.deletePendingInterrupt(sample.storedSessionId)
        val remaining = vault.getPendingInterrupts()
        assertEquals(1, remaining.size)
        assertEquals("sess-other", remaining[0].storedSessionId)

        vault.clearPendingInterrupts()
        assertEquals(0, vault.getPendingInterrupts().size)
    }

    @Test
    fun granularReset_clearCache_dropsPendingInterrupts() = runBlocking {
        vault.savePendingInterrupt(sample)
        vault.clearCache()
        assertEquals("FD-001 cost (b): holds must not outlive a granular reset", 0, vault.getPendingInterrupts().size)
    }

    @Test
    fun granularReset_clearCredentials_dropsPendingInterrupts() = runBlocking {
        vault.savePendingInterrupt(sample)
        vault.clearCredentials()
        assertEquals("FD-001 cost (b): holds must not outlive a granular reset", 0, vault.getPendingInterrupts().size)
    }

    @Test
    fun granularReset_clearAll_dropsPendingInterrupts() = runBlocking {
        vault.savePendingInterrupt(sample)
        vault.clearAll()
        assertEquals("FD-001 cost (b): holds must not outlive a granular reset", 0, vault.getPendingInterrupts().size)
    }

    @Test
    fun swappedRecordType_failsClosedAndNeverSurfacesAsHold() = runBlocking {
        // Plant a DRAFT ciphertext under the pending-interrupt filename: the AAD
        // binds record type, so it must fail closed and never parse as a hold.
        vault.saveDraft(Draft(storedSessionId = "sess-swap", composerText = "draft body"))
        val draftFile = File(vaultDir, "drafts")
            .listFiles { _, name -> name.endsWith(".enc") }!![0]
        val planted = File(vaultDir, "pending_interrupts/pinterrupt_${sha256Hex("sess-swap")}.enc")
        draftFile.copyTo(planted, overwrite = true)

        assertEquals(0, vault.getPendingInterrupts().size)
        assertFalse("AAD mismatch must quarantine the planted file", planted.exists())
    }
}
