package com.hermes.bot.data.local

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

class ProfileIndexRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var cipherEngine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("profile_index_test")
        cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        vault = EncryptedVault(storageDir = storageDir, cipherEngine = cipherEngine)
    }

    @Test
    fun list_and_save_profiles_maintainsIndexIntegrity() = runBlocking {
        assertTrue("Initially profile list should be empty", vault.listConnectionProfiles().isEmpty())
        assertNull(vault.getActiveProfileId())

        val profA = ConnectionProfile(
            localId = "prof-a",
            normalizedOrigin = "https://a.hermes.internal:8443",
            displayLabel = "Profile A",
            lastSuccessfulProtocol = "hermes-gateway-v1"
        )
        vault.saveConnectionProfile(profA)

        assertEquals(1, vault.listConnectionProfiles().size)
        assertEquals("prof-a", vault.getActiveProfileId())
        assertEquals(profA.normalizedOrigin, vault.getConnectionProfile("prof-a")?.normalizedOrigin)

        val profB = ConnectionProfile(
            localId = "prof-b",
            normalizedOrigin = "https://b.hermes.internal:8443",
            displayLabel = "Profile B",
            lastSuccessfulProtocol = "hermes-gateway-v1"
        )
        vault.saveConnectionProfile(profB)

        val list = vault.listConnectionProfiles()
        assertEquals(2, list.size)
        assertEquals(listOf("prof-a", "prof-b"), list.map { it.localId })
        // Saving a profile sets it as active
        assertEquals("prof-b", vault.getActiveProfileId())

        // Explicitly switch active profile back to prof-a
        val switched = vault.setActiveProfileId("prof-a")
        assertTrue(switched)
        assertEquals("prof-a", vault.getActiveProfileId())
        assertEquals("prof-a", vault.getConnectionProfile()?.localId)
    }

    @Test
    fun delete_refusesLastProfile() = runBlocking {
        val single = ConnectionProfile(
            localId = "prof-only",
            normalizedOrigin = "https://only.hermes.internal:8443",
            displayLabel = "Only Profile"
        )
        vault.saveConnectionProfile(single)

        val deleted = vault.deleteConnectionProfile("prof-only")
        assertFalse("Deleting the last remaining profile must be refused", deleted)
        assertEquals(1, vault.listConnectionProfiles().size)
        assertEquals("prof-only", vault.getConnectionProfile()?.localId)
    }

    @Test
    fun delete_removesProfileAndItsCredential() = runBlocking {
        val prof1 = ConnectionProfile("prof-1", "https://h1.internal:8443", "Host 1")
        val prof2 = ConnectionProfile("prof-2", "https://h2.internal:8443", "Host 2")
        vault.saveConnectionProfile(prof1)
        vault.saveConnectionProfile(prof2)

        val cred1 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p1",
            cookies = mapOf("auth" to "token-1"),
            canonicalOrigin = "https://h1.internal:8443"
        )
        val cred2 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "p2",
            cookies = mapOf("auth" to "token-2"),
            canonicalOrigin = "https://h2.internal:8443"
        )
        vault.saveAuthCredential(cred1, "prof-1")
        vault.saveAuthCredential(cred2, "prof-2")

        assertEquals("token-1", vault.getAuthCredential("prof-1")?.cookies?.get("auth"))
        assertEquals("token-2", vault.getAuthCredential("prof-2")?.cookies?.get("auth"))

        // Delete profile 2
        val deleted = vault.deleteConnectionProfile("prof-2")
        assertTrue(deleted)

        val profiles = vault.listConnectionProfiles()
        assertEquals(1, profiles.size)
        assertEquals("prof-1", profiles[0].localId)
        assertNull(vault.getConnectionProfile("prof-2"))
        assertNull("Credential for deleted profile must be removed", vault.getAuthCredential("prof-2"))
        assertNotNull("Credential for remaining profile must be intact", vault.getAuthCredential("prof-1"))
    }

    @Test
    fun delete_activeProfile_clearsActiveProfileId() = runBlocking {
        val prof1 = ConnectionProfile("prof-1", "https://h1.internal:8443", "Host 1")
        val prof2 = ConnectionProfile("prof-2", "https://h2.internal:8443", "Host 2")
        vault.saveConnectionProfile(prof1)
        vault.saveConnectionProfile(prof2)
        vault.setActiveProfileId("prof-1")

        val deleted = vault.deleteConnectionProfile("prof-1")
        assertTrue(deleted)
        assertNull("Active profile id must be cleared when active profile is deleted", vault.getActiveProfileId())
        assertEquals(listOf("prof-2"), vault.listConnectionProfiles().map { it.localId })
    }

    @Test
    fun saveConnectionProfile_updateExisting_doesNotDuplicateIndexEntry() = runBlocking {
        val prof = ConnectionProfile("prof-dup", "https://h.internal:8443", "Label Original")
        vault.saveConnectionProfile(prof)
        assertEquals(1, vault.listConnectionProfiles().size)

        val updated = prof.copy(displayLabel = "Label Renamed")
        vault.saveConnectionProfile(updated)

        val list = vault.listConnectionProfiles()
        assertEquals(1, list.size)
        assertEquals("Label Renamed", list[0].displayLabel)
    }

    @Test
    fun clearAuthCredential_withNoActiveProfile_clearsNothingAndPreservesRemainingCredentials() = runBlocking {
        val prof1 = ConnectionProfile("prof-1", "https://h1.internal:8443", "Host 1")
        val cred1 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "user-1",
            cookies = mapOf("auth" to "token-1"),
            canonicalOrigin = prof1.normalizedOrigin
        )
        vault.saveConnectionProfile(prof1)
        vault.saveAuthCredential(cred1, "prof-1")

        // Set activeProfileId to null (no active profile)
        vault.setActiveProfileId("")
        assertNull(vault.getActiveProfileId())

        // Clear credential with null target id — must fail closed and NOT delete prof-1
        vault.clearAuthCredential(null)

        val loaded = vault.getAuthCredential("prof-1")
        assertNotNull("Credential must not be deleted when target profile id cannot be resolved", loaded)
        assertEquals("token-1", loaded?.cookies?.get("auth"))
    }
}
