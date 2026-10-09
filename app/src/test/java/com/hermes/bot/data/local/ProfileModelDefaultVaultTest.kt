package com.hermes.bot.data.local

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-011 S1 (TEST-01/TEST-02): per-workspace default model+effort vault record.
 *
 * Roundtrip persistence through the encrypted singleton seam
 * (`profile_model_defaults.enc`, PAT-001 — same mutex + ioDispatcher + AAD +
 * `writeEncryptedSingleton` pattern as `saveSelectedContextProfile`), and
 * workspace partition isolation: distinct records for distinct workspace keys,
 * no cross-leak between them.
 *
 * RED first: these tests fail before TASK-001/TASK-002 product code exists.
 */
class ProfileModelDefaultVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("profile_model_default_vault")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
    }

    // -------------------------------------------------------------------------
    // TEST-01: roundtrip — save for workspace `coding`, read back verbatim;
    // unknown workspace reads null.
    // -------------------------------------------------------------------------

    @Test
    fun roundtrip_saveAndReadBack_returnsValuesVerbatim() = runBlocking {
        val saved = ProfileModelDefault(
            workspaceName = "coding",
            modelName = "gpt-5.6-luna",
            reasoningEffort = "high",
            updatedAtMillis = 1_760_000_000_000L
        )
        vault.saveProfileModelDefault(saved)

        val loaded = vault.getProfileModelDefault("coding")
        assertNotNull("saved record must read back", loaded)
        assertEquals("coding", loaded!!.workspaceName)
        assertEquals("gpt-5.6-luna", loaded.modelName)
        assertEquals("high", loaded.reasoningEffort)
        assertEquals(1_760_000_000_000L, loaded.updatedAtMillis)
        assertEquals(1, loaded.schemaVersion)
    }

    @Test
    fun roundtrip_unknownWorkspace_returnsNull() = runBlocking {
        val saved = ProfileModelDefault(
            workspaceName = "coding",
            modelName = "gpt-5.6-luna",
            reasoningEffort = "high",
            updatedAtMillis = 1_760_000_000_000L
        )
        vault.saveProfileModelDefault(saved)

        assertNull("unknown workspace must read null", vault.getProfileModelDefault("fitness"))
    }

    @Test
    fun roundtrip_noRecordAtAll_returnsNull() = runBlocking {
        assertNull("empty vault must read null", vault.getProfileModelDefault("coding"))
    }

    // -------------------------------------------------------------------------
    // TEST-01 extension: default-key mapping — blank/null workspace name maps
    // to literal key "default" (plan §state-ownership workspace-key rule).
    // -------------------------------------------------------------------------

    @Test
    fun workspaceKey_blankOrNull_mapsToDefaultKey() {
        assertEquals("default", ProfileModelDefault.workspaceKey(null))
        assertEquals("default", ProfileModelDefault.workspaceKey(""))
        assertEquals("default", ProfileModelDefault.workspaceKey("   "))
        assertEquals("default", ProfileModelDefault.workspaceKey("default"))
        assertEquals("Coding", ProfileModelDefault.workspaceKey(" Coding "))
        assertEquals("Coding", ProfileModelDefault.workspaceKey("Coding"))
    }

    // -------------------------------------------------------------------------
    // TEST-02: workspace partition isolation — distinct records for `coding`
    // and `fitness`; each reads only its own; overwrite updates one.
    // -------------------------------------------------------------------------

    @Test
    fun partition_isolation_eachWorkspaceReadsOnlyItsOwnRecord() = runBlocking {
        val coding = ProfileModelDefault(
            workspaceName = "coding",
            modelName = "gpt-5.6-luna",
            reasoningEffort = "high",
            updatedAtMillis = 1_760_000_000_000L
        )
        val fitness = ProfileModelDefault(
            workspaceName = "fitness",
            modelName = "gpt-5.6-pro",
            reasoningEffort = "low",
            updatedAtMillis = 1_760_000_000_500L
        )
        vault.saveProfileModelDefault(coding)
        vault.saveProfileModelDefault(fitness)

        val codingBack = vault.getProfileModelDefault("coding")
        val fitnessBack = vault.getProfileModelDefault("fitness")
        assertNotNull(codingBack)
        assertNotNull(fitnessBack)
        assertEquals("gpt-5.6-luna", codingBack!!.modelName)
        assertEquals("high", codingBack.reasoningEffort)
        assertEquals("gpt-5.6-pro", fitnessBack!!.modelName)
        assertEquals("low", fitnessBack.reasoningEffort)

        // No cross-leak: neither workspace observes the other's values.
        assertTrue(codingBack.modelName != fitnessBack.modelName)
        assertTrue(codingBack.reasoningEffort != fitnessBack.reasoningEffort)
    }

    @Test
    fun partition_isolation_overwriteUpdatesOnlyThatWorkspace() = runBlocking {
        vault.saveProfileModelDefault(
            ProfileModelDefault("coding", "gpt-5.6-luna", "high", 1_760_000_000_000L)
        )
        vault.saveProfileModelDefault(
            ProfileModelDefault("fitness", "gpt-5.6-pro", "low", 1_760_000_000_500L)
        )
        // Overwrite `coding` only.
        vault.saveProfileModelDefault(
            ProfileModelDefault("coding", "gpt-5.7-titan", "max", 1_760_000_001_000L)
        )

        val codingBack = vault.getProfileModelDefault("coding")
        assertEquals("gpt-5.7-titan", codingBack!!.modelName)
        assertEquals("max", codingBack.reasoningEffort)
        assertEquals(1_760_000_001_000L, codingBack.updatedAtMillis)

        val fitnessBack = vault.getProfileModelDefault("fitness")
        assertEquals("fitness record must survive the coding overwrite",
            "gpt-5.6-pro", fitnessBack!!.modelName)
        assertEquals("low", fitnessBack.reasoningEffort)
        assertEquals(1_760_000_000_500L, fitnessBack.updatedAtMillis)
    }

    // -------------------------------------------------------------------------
    // Persistence across vault re-instantiation (survives restart, REQ-002).
    // -------------------------------------------------------------------------

    @Test
    fun persistence_survivesVaultReinstantiation() = runBlocking {
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        val dir = tempFolder.newFolder("profile_model_default_reopen")
        val first = EncryptedVault(storageDir = dir, cipherEngine = engine)
        first.saveProfileModelDefault(
            ProfileModelDefault("coding", "gpt-5.6-luna", "high", 1_760_000_000_000L)
        )

        val second = EncryptedVault(storageDir = dir, cipherEngine = engine)
        val loaded = second.getProfileModelDefault("coding")
        assertNotNull("record must survive vault reopen", loaded)
        assertEquals("gpt-5.6-luna", loaded!!.modelName)
        assertEquals("high", loaded.reasoningEffort)
    }

    // -------------------------------------------------------------------------
    // SEC-003: record holds only the four allowed fields — the singleton file
    // must not gain extra persisted fields, and ciphertext must not contain
    // the plaintext model/effort strings.
    // -------------------------------------------------------------------------

    @Test
    fun security_singletonFileCiphertext_noPlaintextModelOrEffort() = runBlocking {
        vault.saveProfileModelDefault(
            ProfileModelDefault("coding", "gpt-5.6-luna", "high", 1_760_000_000_000L)
        )

        val singletonFile = java.io.File(storageDir, "profile_model_defaults.enc")
        assertTrue("profile_model_defaults.enc must exist", singletonFile.exists())
        val raw = singletonFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(
            "singleton ciphertext must not contain the plaintext model name",
            !raw.contains("gpt-5.6-luna")
        )
        assertTrue(
            "singleton ciphertext must not contain the workspace name",
            !raw.contains("coding")
        )
    }
}