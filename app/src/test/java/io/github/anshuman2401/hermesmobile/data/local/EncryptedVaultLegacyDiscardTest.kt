package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * REQ-05 / SEC-01 (TEST-03): the pre-partition legacy summary cache is
 * discarded at every vault initialization — moved aside transiently, followed
 * by a durable disposition record, then the quarantine residue is deleted.
 * The legacy payload is never parsed, migrated, or presented as live data.
 */
class EncryptedVaultLegacyDiscardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: VaultCipherEngine
    private lateinit var legacyFile: File
    private lateinit var quarantineFile: File
    private lateinit var dispositionFile: File

    private val legacyAad =
        "SESSION_SUMMARY_CACHE:1:singleton_session_summary_cache:".toByteArray(Charsets.UTF_8)
    private val dispositionAad =
        "CACHE_MIGRATION_STATE:1:cache-migration-state:".toByteArray(Charsets.UTF_8)

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("legacy_discard_test")
        engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        legacyFile = File(storageDir, "session_summary_cache.enc")
        quarantineFile = File(storageDir, "session_summary_cache.quarantine.enc")
        dispositionFile =
            File(storageDir, "cache_migration_state_${sha256Hex("cache-migration-state")}.enc")

        // Craft a legacy-format encrypted summary cache exactly as the pre-PR-1 writer did.
        val legacyJson = """{"sessions":[{"id":"legacy-1","title":"Legacy Session","preview":"P","started_at":1.0,"message_count":1,"source":"tui"}],"profileLocalId":null,"cachedProfileName":null,"syncTimeEpochSeconds":${System.currentTimeMillis() / 1000},"schemaVersion":1}"""
        legacyFile.writeBytes(engine.encrypt(legacyJson.toByteArray(Charsets.UTF_8), legacyAad))
    }

    @Test
    fun initialization_discardsLegacySummary_writesDisposition_leavesNoResidue() = runBlocking {
        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
        // Any vault operation runs the initialization discard state machine.
        vault.activeAccessPartition()

        assertFalse("Legacy live summary file must be discarded", legacyFile.exists())
        assertFalse("Transient quarantine residue must be deleted", quarantineFile.exists())
        assertTrue("Disposition record must be persisted durably", dispositionFile.exists())

        val dispositionPlaintext =
            engine.decrypt(dispositionFile.readBytes(), dispositionAad).getOrThrow().toString(Charsets.UTF_8)
        assertTrue(
            "Disposition must record the historical discard",
            dispositionPlaintext.contains("DISCARDED_NO_HISTORICAL_ORIGIN")
        )

        // Re-check on every initialization: fresh residue is discarded again and
        // the equal disposition is not rewritten unnecessarily.
        legacyFile.writeBytes(engine.encrypt("{}".toByteArray(Charsets.UTF_8), legacyAad))
        val secondVault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
        secondVault.getConnectionProfile()

        assertFalse("Second initialization must discard the residue again", legacyFile.exists())
        assertFalse(quarantineFile.exists())
        assertTrue(dispositionFile.exists())
    }

    @Test
    fun initialization_writesDispositionWhenAbsent_evenWithoutLegacyFile() = runBlocking {
        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
        vault.getConnectionProfile()

        assertFalse(legacyFile.exists())
        assertTrue("Disposition must be recorded even on a clean vault", dispositionFile.exists())
    }

    @Test
    fun dispositionRecord_carriesNoTimestampField_perPlan() = runBlocking {
        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
        vault.activeAccessPartition()

        assertTrue(dispositionFile.exists())
        val plaintext = engine
            .decrypt(dispositionFile.readBytes(), dispositionAad)
            .getOrThrow()
            .toString(Charsets.UTF_8)
        assertFalse(
            "Plan forbids timestamp fields in CacheMigrationState: $plaintext",
            plaintext.contains("updatedAtMs")
        )
        assertTrue(plaintext.contains("DISCARDED_NO_HISTORICAL_ORIGIN"))
    }

    @Test
    fun futureSchemaDisposition_failsClosed_neverDowngradedNorOverwritten() = runBlocking {
        // P3-2: an unknown FUTURE schema's record must never be parsed by
        // this build — and must never be overwritten by this build's record
        // (that would destroy data the newer schema still owns).
        val futureJson = """{"disposition":"FUTURE_SCHEMA_DISCARDED","schemaVersion":2}"""
        dispositionFile.writeBytes(engine.encrypt(futureJson.toByteArray(Charsets.UTF_8), dispositionAad))
        // setUp left a legacy residue file behind.
        assertTrue(legacyFile.exists())

        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
        vault.getConnectionProfile()

        // The residue is still swept — legacy ciphertext must not survive.
        assertFalse("Legacy residue must be swept even under fail-closed state", legacyFile.exists())
        assertFalse(quarantineFile.exists())

        // ...but the future-schema record itself is untouched: fail closed.
        val stillFuture = engine
            .decrypt(dispositionFile.readBytes(), dispositionAad)
            .getOrThrow()
            .toString(Charsets.UTF_8)
        assertTrue(
            "Future-schema record must remain intact, was $stillFuture",
            stillFuture.contains("FUTURE_SCHEMA_DISCARDED")
        )
        assertFalse(
            "No-downgrade: this schema must not overwrite a future record",
            stillFuture.contains("DISCARDED_NO_HISTORICAL_ORIGIN")
        )
    }
}
