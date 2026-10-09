package com.hermes.bot.data.session

import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.CacheAccessPartitionResult
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.Draft
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.SessionSelection
import com.hermes.bot.data.local.SessionSummaryCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileScopedSessionStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var repository: DefaultSessionRepository

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("profile_scoped_session_test")
        vault = EncryptedVault(storageDir = storageDir, cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeAdapter = FakeGatewayProtocolAdapter()
        repository = DefaultSessionRepository(gatewayAdapter = fakeAdapter, vaultStorage = vault)
    }

    @Test
    fun sessionSelection_resolvesOnlyForActiveProfile() = runBlocking {
        val profA = ConnectionProfile("prof-a", "https://a.hermes:8443", "Profile A")
        val profB = ConnectionProfile("prof-b", "https://b.hermes:8443", "Profile B")
        vault.saveConnectionProfile(profA)
        vault.saveConnectionProfile(profB)

        // Set active to prof-a and save session selection
        vault.setActiveProfileId("prof-a")
        val created = repository.createDraftSession("Session Alpha").getOrThrow()
        assertEquals("durable-key-created-1", created.storedSessionId)

        // prof-a resolves the selection
        val selA = repository.getSessionSelection()
        assertNotNull(selA)
        assertEquals("durable-key-created-1", selA!!.storedSessionId)
        val restoredA = repository.restoreSelectedSession()
        assertNotNull(restoredA)
        assertEquals("durable-key-created-1", restoredA!!.storedSessionId)

        // Switch to prof-b
        vault.setActiveProfileId("prof-b")
        val selB = repository.getSessionSelection()
        assertNull("Selection for Profile A must not be visible to Profile B", selB)
        val restoredB = repository.restoreSelectedSession()
        assertNull("Restoring selection on Profile B must return null", restoredB)

        // Switch back to prof-a
        vault.setActiveProfileId("prof-a")
        val selA2 = repository.getSessionSelection()
        assertNotNull("Selection for Profile A must be restored when switching back", selA2)
        assertEquals("durable-key-created-1", selA2!!.storedSessionId)
    }

    @Test
    fun drafts_resolveOnlyForActiveProfile() = runBlocking {
        val profA = ConnectionProfile("prof-a", "https://a.hermes:8443", "Profile A")
        val profB = ConnectionProfile("prof-b", "https://b.hermes:8443", "Profile B")
        vault.saveConnectionProfile(profA)
        vault.saveConnectionProfile(profB)

        // Active = prof-a
        vault.setActiveProfileId("prof-a")
        repository.saveDraft(Draft(storedSessionId = "sess-1", composerText = "Draft on A"))

        assertEquals("Draft on A", repository.getDraft("sess-1")?.composerText)
        assertEquals(1, repository.getAllDrafts().size)

        // Switch to prof-b
        vault.setActiveProfileId("prof-b")
        assertNull("Draft on A must not resolve on B", repository.getDraft("sess-1"))
        assertTrue("Drafts list on B must be empty", repository.getAllDrafts().isEmpty())

        // Save draft on B
        repository.saveDraft(Draft(storedSessionId = "sess-2", composerText = "Draft on B"))
        assertEquals("Draft on B", repository.getDraft("sess-2")?.composerText)
        assertEquals(1, repository.getAllDrafts().size)

        // Switch back to prof-a
        vault.setActiveProfileId("prof-a")
        assertEquals("Draft on A", repository.getDraft("sess-1")?.composerText)
        assertNull("Draft on B must not resolve on A", repository.getDraft("sess-2"))
        val draftsA = repository.getAllDrafts()
        assertEquals(1, draftsA.size)
        assertEquals("Draft on A", draftsA[0].composerText)
    }

    @Test
    fun draft_crossProfileSessionCollision_yieldsNull() = runBlocking {
        val profA = ConnectionProfile("prof-a", "https://a.hermes:8443", "Profile A")
        val profB = ConnectionProfile("prof-b", "https://b.hermes:8443", "Profile B")
        vault.saveConnectionProfile(profA)
        vault.saveConnectionProfile(profB)

        // Active = prof-a, save draft for "colliding-sess"
        vault.setActiveProfileId("prof-a")
        repository.saveDraft(Draft(storedSessionId = "colliding-sess", composerText = "Secret draft for A"))

        // Active = prof-b (same session ID on different profile)
        vault.setActiveProfileId("prof-b")
        val draftOnB = repository.getDraft("colliding-sess")
        assertNull("Draft on A must not resolve on B even with identical session ID (MEDIUM-1)", draftOnB)

        // Delete draft on B must NOT delete A's draft
        repository.deleteDraft("colliding-sess")
        vault.setActiveProfileId("prof-a")
        val draftOnA = repository.getDraft("colliding-sess")
        assertNotNull("Draft on A must survive deleteDraft from profile B", draftOnA)
        assertEquals("Secret draft for A", draftOnA?.composerText)
    }

    @Test
    fun sessionSummaryCache_resolvesOnlyForActiveProfile() = runBlocking {
        val profA = ConnectionProfile("prof-a", "https://a.hermes:8443", "Profile A")
        val profB = ConnectionProfile("prof-b", "https://b.hermes:8443", "Profile B")
        vault.saveConnectionProfile(profA)
        vault.saveConnectionProfile(profB)

        // Active = prof-a. PR-1: offline partition gating needs a credential
        // bound to this profile's origin before any cache write is permitted.
        vault.setActiveProfileId("prof-a")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = "https://a.hermes:8443"
            ),
            profileLocalId = "prof-a"
        )
        val outcomeA = repository.listSessions(forceRefresh = true).getOrThrow()
        assertEquals(2, outcomeA.sessions.size)

        val cacheA = repository.getSessionSummaryCache()
        assertNotNull(cacheA)
        assertEquals(2, cacheA!!.sessions.size)

        // Switch to prof-b
        vault.setActiveProfileId("prof-b")
        assertNull("Cache for Profile A must not be visible on Profile B", repository.getSessionSummaryCache())

        // Offline fallback on Profile B fails instead of leaking Profile A cache
        fakeAdapter.listSessionsResult = GatewayResult.Failure(GatewayFailure.Transport("Network down"))
        val outcomeB = repository.listSessions(forceRefresh = false)
        assertTrue("Offline fallback must fail when no profile-scoped cache exists", outcomeB.isFailure)

        // Switch back to prof-a: offline fallback works from Profile A cache
        vault.setActiveProfileId("prof-a")
        val cachedOutcomeA = repository.listSessions(forceRefresh = false).getOrThrow()
        assertTrue(cachedOutcomeA.isOfflineCache)
        assertEquals(2, cachedOutcomeA.sessions.size)
    }

    @Test
    fun legacyRecords_withoutProfileLocalId_attachToMigratedProfile() = runBlocking {
        val legacyDir = tempFolder.newFolder("legacy_scoped_test")
        val keyProvider = InMemoryMasterKeyProvider()
        val engine = AesGcmCipherEngine(keyProvider)
        val legacyConnectionFile = File(legacyDir, "connection_profile.enc")
        val oldProfileJson = """{
            "localId": "prof-migrated-1",
            "normalizedOrigin": "https://migrated.hermes:8443",
            "displayLabel": "Migrated Host",
            "schemaVersion": 1
        }""".trimIndent()
        val oldProfileAad = "CONNECTION_PROFILE:1:singleton_connection_profile:".toByteArray(Charsets.UTF_8)
        legacyConnectionFile.writeBytes(engine.encrypt(oldProfileJson.toByteArray(Charsets.UTF_8), oldProfileAad))

        // Create vault and trigger migration
        val vaultMigrated = EncryptedVault(storageDir = legacyDir, cipherEngine = engine)
        val loadedProfile = vaultMigrated.getConnectionProfile()
        assertNotNull(loadedProfile)
        assertEquals("prof-migrated-1", loadedProfile!!.localId)
        assertEquals("prof-migrated-1", vaultMigrated.getMigratedProfileId())

        // Save legacy records directly (with profileLocalId = null)
        vaultMigrated.saveSessionSelection(
            SessionSelection(storedSessionId = "sess-legacy-1", lastKnownTitle = "Legacy Title", profileLocalId = null)
        )
        vaultMigrated.saveDraft(
            Draft(storedSessionId = "sess-legacy-1", composerText = "Legacy composer draft", profileLocalId = null)
        )
        // Add a second profile
        val prof2 = ConnectionProfile("prof-second", "https://second.hermes:8443", "Second Host")
        vaultMigrated.saveConnectionProfile(prof2)

        val repoMigrated = DefaultSessionRepository(gatewayAdapter = fakeAdapter, vaultStorage = vaultMigrated)

        // Active profile is prof-migrated-1: legacy records must resolve.
        // PR-1: seed the origin-bound credential/rotation state the partition
        // gate requires, then write the record under the resolved partition.
        vaultMigrated.setActiveProfileId("prof-migrated-1")
        vaultMigrated.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = "https://migrated.hermes:8443"
            ),
            profileLocalId = "prof-migrated-1"
        )
        vaultMigrated.saveSessionSummaryCache(
            SessionSummaryCache(
                sessions = listOf(SessionSummary("sess-legacy-1", "Legacy Title", "Preview", 1000.0, 1, "android")),
                profileLocalId = null
            ),
            (vaultMigrated.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        )
        val selMigrated = repoMigrated.getSessionSelection()
        assertNotNull("Legacy selection must attach to migrated profile", selMigrated)
        assertEquals("sess-legacy-1", selMigrated!!.storedSessionId)

        val draftMigrated = repoMigrated.getDraft("sess-legacy-1")
        assertNotNull("Legacy draft must attach to migrated profile", draftMigrated)
        assertEquals("Legacy composer draft", draftMigrated!!.composerText)

        val cacheMigrated = repoMigrated.getSessionSummaryCache()
        assertNotNull("Legacy cache must attach to migrated profile", cacheMigrated)
        assertEquals(1, cacheMigrated!!.sessions.size)

        // Switch to second profile: legacy records must NOT resolve
        vaultMigrated.setActiveProfileId("prof-second")
        assertNull("Legacy selection must NOT resolve on non-migrated profile", repoMigrated.getSessionSelection())
        assertNull("Legacy draft must NOT resolve on non-migrated profile", repoMigrated.getDraft("sess-legacy-1"))
        assertNull("Legacy cache must NOT resolve on non-migrated profile", repoMigrated.getSessionSummaryCache())
    }
}
