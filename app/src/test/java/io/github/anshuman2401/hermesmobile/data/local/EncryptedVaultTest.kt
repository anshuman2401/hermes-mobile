package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.CountDownLatch

class EncryptedVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var cipherEngine: VaultCipherEngine
    private lateinit var vault: EncryptedVault

    // PR-1: injectable monotonic clock (P1/T1a-T1b) — defaults to SystemClock on device.
    private var clockMs: Long = System.currentTimeMillis()

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("vault_test")
        cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        clockMs = System.currentTimeMillis()
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = cipherEngine,
            elapsedRealtimeMillis = { clockMs }
        )
    }

    @Test
    fun connectionProfile_roundTrip_encryptsAndDecryptsSuccessfully() = runBlocking {
        val profile = ConnectionProfile(
            localId = "local-prof-1",
            normalizedOrigin = "https://gateway.hermes.internal:8443",
            displayLabel = "Production Hermes",
            lastSuccessfulProtocol = "hermes-gateway-v1"
        )

        vault.saveConnectionProfile(profile)
        val loaded = vault.getConnectionProfile()

        assertNotNull(loaded)
        assertEquals(profile.localId, loaded!!.localId)
        assertEquals(profile.normalizedOrigin, loaded.normalizedOrigin)
        assertEquals(profile.displayLabel, loaded.displayLabel)
        assertEquals(profile.lastSuccessfulProtocol, loaded.lastSuccessfulProtocol)

        // Verify stored file is encrypted and does not contain cleartext origin
        val profileFiles = storageDir.listFiles { _, name -> name.startsWith("connection_profile_") && name.endsWith(".enc") }
        assertNotNull(profileFiles)
        assertTrue(profileFiles!!.isNotEmpty())
        val rawFileBytes = profileFiles[0].readBytes()
        val rawContent = String(rawFileBytes, Charsets.UTF_8)
        assertFalse("Ciphertext must not contain cleartext origin", rawContent.contains("https://gateway.hermes.internal:8443"))
    }

    @Test
    fun authCredential_roundTrip_encryptsAndClearsCredentials() = runBlocking {
        val credential = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("hermes_session" to "secret-cookie-value-xyz", "session_id" to "sid-123"),
            canonicalOrigin = "https://hermes.local:8443"
        )

        vault.saveAuthCredential(credential)
        val loaded = vault.getAuthCredential()

        assertNotNull(loaded)
        assertEquals("password", loaded!!.providerId)
        assertEquals("secret-cookie-value-xyz", loaded.cookies["hermes_session"])
        assertEquals("https://hermes.local:8443", loaded.canonicalOrigin)

        // Verify ciphertext does not contain the cleartext cookie
        val authFiles = storageDir.listFiles { _, name -> name.startsWith("auth_credential_") && name.endsWith(".enc") }
        assertNotNull(authFiles)
        assertTrue(authFiles!!.isNotEmpty())
        val rawFileBytes = authFiles[0].readBytes()
        val rawContent = String(rawFileBytes, Charsets.UTF_8)
        assertFalse("Ciphertext must not contain secret cookie value", rawContent.contains("secret-cookie-value-xyz"))

        // Clear credentials
        vault.clearCredentials()
        assertNull(vault.getAuthCredential())
    }

    @Test
    fun sessionSummaryCache_expiresAfterSevenDays() = runBlocking {
        val oldTimestamp = (System.currentTimeMillis() / 1000) - (8 * 24 * 3600L) // 8 days ago
        val cache = SessionSummaryCache(
            sessions = listOf(
                SessionSummary(
                    id = "sess-1",
                    title = "Test Session",
                    preview = "Snippet",
                    startedAt = 1000.0,
                    messageCount = 5,
                    source = "android"
                )
            ),
            syncTimeEpochSeconds = oldTimestamp
        )

        // PR-1: writes are partition-gated; the record is stamped from the
        // injectable monotonic clock, which then ages past the 7-day retention.
        val partition = CacheAccessPartition("prof-x", "origin-x", "epoch-x", "u")
        vault.saveSessionSummaryCache(cache, partition)
        clockMs += 8L * 24 * 60 * 60 * 1000
        assertEquals(
            "Cache older than 7 days must be expired and deleted",
            CacheReadResult.Expired,
            vault.readSessionSummaryCache(partition)
        )
        assertEquals(
            "Expired record must stay deleted",
            CacheReadResult.Absent,
            vault.readSessionSummaryCache(partition)
        )
    }

    @Test
    fun draft_expiresAfterThirtyDaysInactivity() = runBlocking {
        val activeDraft = Draft(
            storedSessionId = "session-active",
            composerText = "Fresh draft message",
            editTimeEpochSeconds = System.currentTimeMillis() / 1000
        )
        val staleDraft = Draft(
            storedSessionId = "session-stale",
            composerText = "Old stale draft",
            editTimeEpochSeconds = (System.currentTimeMillis() / 1000) - (35 * 24 * 3600L) // 35 days ago
        )

        vault.saveDraft(activeDraft)
        vault.saveDraft(staleDraft)

        assertNotNull("Active draft must be present", vault.getDraft("session-active"))
        assertNull("Stale draft older than 30 days must be expired and return null", vault.getDraft("session-stale"))

        val all = vault.getAllDrafts()
        assertEquals(1, all.size)
        assertEquals("session-active", all[0].storedSessionId)
    }

    @Test
    fun corruptedCiphertext_recoversGracefullyWithoutCrashing() = runBlocking {
        val profile = ConnectionProfile(
            localId = "local-prof-corrupt",
            normalizedOrigin = "https://gateway.hermes.internal:8443",
            displayLabel = "Test"
        )
        vault.saveConnectionProfile(profile)

        // Corrupt the ciphertext file
        val files = storageDir.listFiles { _, name -> name.startsWith("connection_profile_") && name.endsWith(".enc") }
        assertNotNull(files)
        assertTrue(files!!.isNotEmpty())
        val file = files[0]
        file.writeBytes("corrupted_non_encrypted_garbage_data".toByteArray())

        val result = vault.getConnectionProfile()
        assertNull("Corrupted ciphertext must result in safe null recovery without crash", result)
        assertFalse("Corrupted file must be quarantined/deleted", file.exists())
    }

    @Test
    fun conditionalProfileCommit_afterInvalidation_preservesExistingProfileAndLeavesNoTempFile() = runBlocking {
        val oldProfile = ConnectionProfile("old", "http://old.example:9119", "Old")
        val replacement = ConnectionProfile("new", "http://new.example:9119", "New")
        vault.saveConnectionProfile(oldProfile)
        val gate = ConnectionProfileGenerationGate()
        val staleGeneration = gate.beginAttempt()
        gate.invalidate()

        val result = vault.saveConnectionProfileIfCurrent(replacement, staleGeneration, gate)

        assertEquals(ProfileCommitResult.STALE, result)
        assertEquals(oldProfile.normalizedOrigin, vault.getConnectionProfile()!!.normalizedOrigin)
        assertTrue(storageDir.listFiles()!!.none { it.name.contains("connection_profile_") && it.name.endsWith(".tmp") })
    }

    @Test
    fun conditionalProfileCommit_atomicMoveFailure_preservesPriorBytesAndCleansStaging() = runBlocking {
        vault.saveConnectionProfile(ConnectionProfile("old", "http://old.example:9119", "Old"))
        val profileFiles = storageDir.listFiles { _, name -> name.startsWith("connection_profile_") && name.endsWith(".enc") }
        assertNotNull(profileFiles)
        assertTrue(profileFiles!!.isNotEmpty())
        val targetFile = profileFiles[0]
        val before = targetFile.readBytes()
        val failingVault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = cipherEngine,
            atomicMove = { source, target -> throw AtomicMoveNotSupportedException(source.path, target.path, "test") }
        )
        val gate = ConnectionProfileGenerationGate()
        val generation = gate.beginAttempt()

        val result = failingVault.saveConnectionProfileIfCurrent(
            ConnectionProfile("new", "http://new.example:9119", "New"),
            generation,
            gate
        )

        assertTrue(result is ProfileCommitResult.FAILED)
        assertTrue(before.contentEquals(targetFile.readBytes()))
        assertTrue(storageDir.listFiles()!!.none { it.name.contains("connection_profile_") && it.name.endsWith(".tmp") })
    }

    @Test
    fun generationGate_serializesInvalidationBehindCommit() = runBlocking {
        val gate = ConnectionProfileGenerationGate()
        val generation = gate.beginAttempt()
        val commitStarted = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commit = async(Dispatchers.Default) {
            gate.commitIfCurrent(generation) {
                commitStarted.countDown()
                releaseCommit.await()
                ProfileCommitResult.SAVED
            }
        }
        commitStarted.await()
        val invalidate = async(Dispatchers.Default) { gate.invalidate() }
        delay(50)

        assertFalse("Invalidation must wait until the linearized commit finishes", invalidate.isCompleted)
        releaseCommit.countDown()
        assertEquals(ProfileCommitResult.SAVED, commit.await())
        assertTrue(invalidate.await() > generation)
    }

    @Test
    fun futureSchemaVersion_returnsNullGracefully() = runBlocking {
        // Construct and encrypt a record with schemaVersion = 999 with connection profile AAD
        val futureJson = """{"localId":"fut-1","normalizedOrigin":"https://hermes.future","displayLabel":"F","schemaVersion":999}"""
        val aad = "CONNECTION_PROFILE:${ConnectionProfile.CURRENT_SCHEMA_VERSION}:singleton_connection_profile".toByteArray(Charsets.UTF_8)
        val encrypted = cipherEngine.encrypt(futureJson.toByteArray(Charsets.UTF_8), aad)
        File(storageDir, "connection_profile.enc").writeBytes(encrypted)

        val result = vault.getConnectionProfile()
        assertNull("Unknown future schema version must be safely ignored", result)
    }

    @Test
    fun vaultSwapAttack_swappingRecordTypes_failsClosedDueToAadMismatch() = runBlocking {
        // 1. Create a valid draft
        val draft = Draft(
            storedSessionId = "session-swap-test",
            composerText = "Draft text that must not become an auth credential"
        )
        vault.saveDraft(draft)

        // Locate the draft file
        val draftsDir = File(storageDir, "drafts")
        val draftFiles = draftsDir.listFiles { _, name -> name.endsWith(".enc") }
        assertNotNull(draftFiles)
        assertTrue(draftFiles!!.isNotEmpty())
        val draftFile = draftFiles[0]

        // 2. Perform swap attack: copy draft ciphertext into auth_credential file
        val cred0 = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "test",
            cookies = mapOf("c" to "v"),
            canonicalOrigin = "https://hermes.local:8443"
        )
        vault.saveAuthCredential(cred0)
        val authFiles = storageDir.listFiles { _, name -> name.startsWith("auth_credential_") && name.endsWith(".enc") }
        assertNotNull(authFiles)
        assertTrue(authFiles!!.isNotEmpty())
        val authFile = authFiles[0]
        draftFile.copyTo(authFile, overwrite = true)

        // 3. Attempt to read auth credential.
        // Because of AEAD AAD binding (DRAFT vs AUTH_CREDENTIAL), tag verification must fail!
        val cred = vault.getAuthCredential()
        assertNull("Swap attack across record types must fail closed", cred)
        assertFalse("Swapped/corrupted auth file must be quarantined/deleted", authFile.exists())
    }

    @Test
    fun vaultSwapAttack_swappingDraftBetweenSessions_failsClosedDueToAadMismatch() = runBlocking {
        // 1. Save draft for session-alpha
        val draftAlpha = Draft(
            storedSessionId = "session-alpha",
            composerText = "Alpha confidential draft"
        )
        vault.saveDraft(draftAlpha)

        val draftsDir = File(storageDir, "drafts")
        val alphaFiles = draftsDir.listFiles { _, name -> name.endsWith(".enc") }
        assertNotNull(alphaFiles)
        val alphaFile = alphaFiles!![0]

        // 2. Copy alpha file to the expected file name of session-beta
        // (Even if an attacker renames the file to match session-beta's hash)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val betaHash = md.digest("session-beta".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val betaFile = File(draftsDir, "draft_$betaHash.enc")
        alphaFile.copyTo(betaFile, overwrite = true)

        // 3. Attempt to read draft for session-beta.
        // Because AAD binds storedSessionId ("session-alpha" vs "session-beta"), decryption or identity check fails!
        val loadedBeta = vault.getDraft("session-beta")
        assertNull("Swapped draft between sessions must fail closed", loadedBeta)
        assertFalse("Swapped draft file must be quarantined/deleted", betaFile.exists())
    }

    @Test
    fun collisionResistance_distinctFilesForCollisionProneIds() = runBlocking {
        val id1 = "session/with/slashes"
        val id2 = "session_with_slashes"
        val id3 = "session.with.slashes"

        vault.saveDraft(Draft(id1, "Text 1"))
        vault.saveDraft(Draft(id2, "Text 2"))
        vault.saveDraft(Draft(id3, "Text 3"))

        val draftsDir = File(storageDir, "drafts")
        val files = draftsDir.listFiles { _, name -> name.endsWith(".enc") }
        assertNotNull(files)
        assertEquals("Three collision-prone IDs must produce three distinct files", 3, files!!.size)

        assertEquals("Text 1", vault.getDraft(id1)?.composerText)
        assertEquals("Text 2", vault.getDraft(id2)?.composerText)
        assertEquals("Text 3", vault.getDraft(id3)?.composerText)
    }

    @Test
    fun granularReset_clearCacheDoesNotTouchDraftsOrAuth() = runBlocking {
        val cred = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "test",
            cookies = mapOf("c" to "v"),
            canonicalOrigin = "https://hermes.local:8443"
        )
        val draft = Draft(
            storedSessionId = "sess-draft",
            composerText = "Unsent text"
        )
        val cache = SessionSummaryCache(
            sessions = listOf(
                SessionSummary("s1", "T", "P", 0.0, 1, "android")
            )
        )

        vault.saveAuthCredential(cred)
        vault.saveDraft(draft)
        val partition = CacheAccessPartition("prof-x", "origin-x", "epoch-x", "u")
        vault.saveSessionSummaryCache(cache, partition)

        // Clear ONLY cache
        vault.clearCache()

        assertEquals(
            "Cache must be cleared",
            CacheReadResult.Absent,
            vault.readSessionSummaryCache(partition)
        )
        assertNotNull("Auth credential must remain intact", vault.getAuthCredential())
        assertNotNull("Draft must remain intact", vault.getDraft("sess-draft"))
    }
}
