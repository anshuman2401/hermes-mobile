package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class ProfileMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var cipherEngine: VaultCipherEngine

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("profile_migration_test")
        cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    }

    @Test
    fun migration_fromOldSingleton_createsKeyedRecordsAndSetsActiveProfile() = runBlocking {
        val legacyConnectionFile = File(storageDir, "connection_profile.enc")
        val legacyAuthFile = File(storageDir, "auth_credential.enc")

        // 1. Write legacy singleton connection profile with old AAD
        val oldProfileJson = """{
            "localId": "legacy-prof-123",
            "normalizedOrigin": "https://legacy.hermes.internal:8443",
            "displayLabel": "Migrated Hermes",
            "lastSuccessfulProtocol": "hermes-gateway-v1",
            "createdAt": 1700000000000,
            "updatedAt": 1700000005000,
            "schemaVersion": 1
        }""".trimIndent()
        val oldProfileAad = "CONNECTION_PROFILE:1:singleton_connection_profile:".toByteArray(Charsets.UTF_8)
        legacyConnectionFile.writeBytes(cipherEngine.encrypt(oldProfileJson.toByteArray(Charsets.UTF_8), oldProfileAad))

        // 2. Write legacy singleton auth credential with old AAD
        val oldAuthJson = """{
            "driverKind": "cookie_session",
            "providerId": "password",
            "cookies": {"hermes_session": "secret-migrated-token"},
            "canonicalOrigin": "https://legacy.hermes.internal:8443",
            "expiryEpochSeconds": null,
            "createdAt": 1700000000000,
            "updatedAt": 1700000005000,
            "schemaVersion": 1
        }""".trimIndent()
        val oldAuthAad = "AUTH_CREDENTIAL:1:singleton_auth_credential:".toByteArray(Charsets.UTF_8)
        legacyAuthFile.writeBytes(cipherEngine.encrypt(oldAuthJson.toByteArray(Charsets.UTF_8), oldAuthAad))

        // 3. Initialize vault and perform read to trigger migration
        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = cipherEngine)
        val profiles = vault.listConnectionProfiles()

        assertEquals(1, profiles.size)
        val profile = profiles[0]
        assertEquals("legacy-prof-123", profile.localId)
        assertEquals("https://legacy.hermes.internal:8443", profile.normalizedOrigin)
        assertEquals("Migrated Hermes", profile.displayLabel)
        assertEquals("hermes-gateway-v1", profile.lastSuccessfulProtocol)
        assertEquals(1700000000000L, profile.createdAt)
        assertEquals(1700000005000L, profile.updatedAt)

        // Active profile matches migrated profile
        assertEquals("legacy-prof-123", vault.getActiveProfileId())
        assertEquals("legacy-prof-123", vault.getMigratedProfileId())

        // Migration preserves the actual keyed encrypted content, independently
        // of the public auth gates. The envelope and AAD use the existing schema.
        val migratedFiles = storageDir.listFiles { _, name ->
            name.startsWith("auth_credential_") && name.endsWith(".enc")
        }!!
        assertEquals(1, migratedFiles.size)
        val envelope = ByteBuffer.wrap(migratedFiles.single().readBytes())
        val idLength = envelope.int
        assertTrue(idLength > 0 && idLength < envelope.remaining())
        val idBytes = ByteArray(idLength).also { envelope.get(it) }
        val recordId = idBytes.toString(Charsets.UTF_8)
        assertEquals("auth_credential_legacy-prof-123", recordId)
        val ciphertext = ByteArray(envelope.remaining()).also { envelope.get(it) }
        val keyedAad = "AUTH_CREDENTIAL:1:$recordId:".toByteArray(Charsets.UTF_8)
        val plaintext = cipherEngine.decrypt(ciphertext, keyedAad).getOrThrow()
        val migrated = GatewayJson.parseOrNull(plaintext.toString(Charsets.UTF_8))!!.asObjectOrNull()!!
        assertEquals(1, migrated["schemaVersion"]!!.asIntOrNull())
        assertEquals("cookie_session", migrated["driverKind"]!!.asStringOrNull())
        assertEquals("password", migrated["providerId"]!!.asStringOrNull())
        assertEquals("secret-migrated-token", migrated["cookies"]!!.asObjectOrNull()!!["hermes_session"]!!.asStringOrNull())
        assertEquals(profile.normalizedOrigin, migrated["canonicalOrigin"]!!.asStringOrNull())

        // Legacy bytes do not mint an access epoch or authorize session reuse.
        assertFalse(File(storageDir, "cache").walkTopDown().any { it.name.startsWith("cache_access_") })
        assertNull(vault.getAuthCredential("legacy-prof-123"))
        assertNull(vault.getAuthCredential())
        assertNull(vault.readAuthCredentialLease(profile.normalizedOrigin))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)

        // Old legacy files deleted
        assertFalse(legacyConnectionFile.exists())
        assertFalse(legacyAuthFile.exists())

        // Profile index file and keyed files exist
        val indexFile = File(storageDir, "profile_index.enc")
        assertTrue(indexFile.exists())
        val profileFiles = storageDir.listFiles { _, name -> name.startsWith("connection_profile_") && name.endsWith(".enc") }
        assertEquals(1, profileFiles!!.size)
        val authFiles = storageDir.listFiles { _, name -> name.startsWith("auth_credential_") && name.endsWith(".enc") }
        assertEquals(1, authFiles!!.size)

        // Complete explicit synthetic authentication through the real repository.
        val calls = mutableListOf<String>()
        val transport = object : AuthHttpTransport {
            override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                calls += method
                return when {
                    url.endsWith("/api/status") -> HttpResponse(200, body = """{"version":"synthetic","auth_providers":["basic"]}""")
                    url.endsWith("/auth/password-login") -> HttpResponse(200,
                        mapOf("Set-Cookie" to listOf("hermes_session=synthetic-new-login; Path=/")))
                    else -> error("Unexpected synthetic request")
                }
            }
        }
        val result = DefaultAuthRepository(vault, transport).login(
            origin = profile.normalizedOrigin, username = "synthetic-user",
            password = "synthetic-password".toCharArray(), provider = "basic"
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("GET", "POST"), calls)
        val credential = vault.getAuthCredential("legacy-prof-123")!!
        assertEquals("synthetic-new-login", credential.cookies["hermes_session"])
        assertEquals(profile.normalizedOrigin, credential.canonicalOrigin)
        assertEquals(credential, vault.getAuthCredential())
        val lease = vault.readAuthCredentialLease(profile.normalizedOrigin)!!
        assertEquals("legacy-prof-123", lease.profileLocalId)
        assertEquals(credential, lease.credential)
        assertTrue(lease.epoch.isNotBlank())
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Available)
    }

    @Test
    fun migration_isIdempotent_rerunPreservesData() = runBlocking {
        val legacyConnectionFile = File(storageDir, "connection_profile.enc")
        val oldProfileJson = """{
            "localId": "prof-idempotent",
            "normalizedOrigin": "https://idempotent.hermes:8443",
            "displayLabel": "Idempotent Host",
            "schemaVersion": 1
        }""".trimIndent()
        val oldProfileAad = "CONNECTION_PROFILE:1:singleton_connection_profile:".toByteArray(Charsets.UTF_8)
        legacyConnectionFile.writeBytes(cipherEngine.encrypt(oldProfileJson.toByteArray(Charsets.UTF_8), oldProfileAad))

        // First vault instance runs migration
        val vault1 = EncryptedVault(storageDir = storageDir, cipherEngine = cipherEngine)
        val loaded1 = vault1.getConnectionProfile()
        assertNotNull(loaded1)
        assertEquals("prof-idempotent", loaded1!!.localId)
        assertFalse(legacyConnectionFile.exists())

        // Second vault instance accessing the directory
        val vault2 = EncryptedVault(storageDir = storageDir, cipherEngine = cipherEngine)
        val loaded2 = vault2.getConnectionProfile()
        assertNotNull(loaded2)
        assertEquals("prof-idempotent", loaded2!!.localId)
        assertEquals(listOf("prof-idempotent"), vault2.listConnectionProfiles().map { it.localId })
        assertEquals("prof-idempotent", vault2.getActiveProfileId())
    }

    @Test
    fun migration_corruptedOldSingleton_startsEmptyAndRecordsDiagnostic() = runBlocking {
        val legacyConnectionFile = File(storageDir, "connection_profile.enc")
        legacyConnectionFile.writeBytes("garbage_unencrypted_corrupted_data".toByteArray())

        val vault = EncryptedVault(storageDir = storageDir, cipherEngine = cipherEngine)
        val profiles = vault.listConnectionProfiles()
        assertTrue("Corrupted legacy profile must result in empty profiles list", profiles.isEmpty())
        assertNull("Active profile must be null", vault.getActiveProfileId())

        // Legacy file quarantined/deleted
        assertFalse(legacyConnectionFile.exists())

        // Diagnostic recorded
        val diagnostics = vault.getDiagnostics()
        assertTrue(diagnostics.any { it.code == "PROFILE_MIGRATION_FAILED" && it.errorCategory == "CORRUPTION" })
    }
}
