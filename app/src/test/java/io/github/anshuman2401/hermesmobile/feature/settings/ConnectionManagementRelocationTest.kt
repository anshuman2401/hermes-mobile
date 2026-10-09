package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.Draft
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.feature.chat.ChatScreenTopBarPolicy
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

/**
 * TEST-002: JVM ConnectionManagementRelocationTest
 *
 * Verifies:
 * - Connection data survives Settings relocation.
 * - Global named/caret chip is absent from default top bar policy.
 * - Settings retains full connection management semantics (active marker, remove policy).
 * - Connection switch/remove preserves unrelated connection credentials and scoped records.
 */
class ConnectionManagementRelocationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var vault: EncryptedVault

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("conn_relocation_test")
        vault = EncryptedVault(
            storageDir = storageDir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
    }

    @Test
    fun globalAffordance_hasNoConnectionChipOrCaret() {
        val defaultActions = ChatScreenTopBarPolicy.actionsOrder()
        assertEquals(emptyList<String>(), defaultActions)
        assertFalse(
            "Global profile chip must not be present in default top bar actions",
            defaultActions.contains("Profile chip")
        )
    }

    @Test
    fun settingsConfirmationPolicy_usesConnectionTerminology() {
        assertEquals("Remove connection", ProfileRemoveConfirmationPolicy.confirmationTitle())
        assertEquals(
            "Remove connection Work Server? This deletes its saved sign-in.",
            ProfileRemoveConfirmationPolicy.confirmationMessage("Work Server")
        )
        assertFalse("Cannot remove when 0 profiles", ProfileRemoveConfirmationPolicy.canRemove(0))
        assertFalse("Cannot remove when single profile", ProfileRemoveConfirmationPolicy.canRemove(1))
        assertTrue("Can remove when multiple profiles", ProfileRemoveConfirmationPolicy.canRemove(2))
    }

    @Test
    fun connectionSwitching_preservesUnrelatedCredentialsAndScopedState() = runBlocking {
        val connA = ConnectionProfile(
            localId = "conn-a",
            normalizedOrigin = "http://server-a.local:9119",
            displayLabel = "Server A",
            lastSuccessfulProtocol = "hermes-gateway-v1"
        )
        val connB = ConnectionProfile(
            localId = "conn-b",
            normalizedOrigin = "http://server-b.local:9119",
            displayLabel = "Server B",
            lastSuccessfulProtocol = "hermes-gateway-v1"
        )

        vault.saveConnectionProfile(connA)
        vault.saveConnectionProfile(connB)
        vault.setActiveProfileId("conn-a")

        val credA = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("session" to "cookie-a"),
            canonicalOrigin = "http://server-a.local:9119"
        )
        val credB = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("session" to "cookie-b"),
            canonicalOrigin = "http://server-b.local:9119"
        )
        vault.saveAuthCredential(credA, "conn-a")
        vault.saveAuthCredential(credB, "conn-b")

        val draftA = Draft(
            storedSessionId = "sess-a",
            composerText = "draft text for A",
            profileLocalId = "conn-a"
        )
        val draftB = Draft(
            storedSessionId = "sess-b",
            composerText = "draft text for B",
            profileLocalId = "conn-b"
        )
        vault.saveDraft(draftA)
        vault.saveDraft(draftB)

        // Switch to connection B
        vault.setActiveProfileId("conn-b")

        // Assert both profiles remain in vault
        val profiles = vault.listConnectionProfiles()
        assertEquals(2, profiles.size)
        assertEquals("conn-b", vault.getActiveProfileId())

        // Credentials for both connections preserved
        val readCredA = vault.getAuthCredential("conn-a")
        assertNotNull(readCredA)
        assertEquals("cookie-a", readCredA!!.cookies["session"])

        val readCredB = vault.getAuthCredential("conn-b")
        assertNotNull(readCredB)
        assertEquals("cookie-b", readCredB!!.cookies["session"])

        // Drafts for both connections are preserved with scoped profileLocalId
        val readDraftA = vault.getDraft("sess-a")
        assertEquals("draft text for A", readDraftA?.composerText)
        assertEquals("conn-a", readDraftA?.profileLocalId)

        val readDraftB = vault.getDraft("sess-b")
        assertEquals("draft text for B", readDraftB?.composerText)
        assertEquals("conn-b", readDraftB?.profileLocalId)
    }

    @Test
    fun connectionRemoval_preservesUnrelatedConnectionAndCredentials() = runBlocking {
        val connA = ConnectionProfile(
            localId = "conn-a",
            normalizedOrigin = "http://server-a.local:9119",
            displayLabel = "Server A"
        )
        val connB = ConnectionProfile(
            localId = "conn-b",
            normalizedOrigin = "http://server-b.local:9119",
            displayLabel = "Server B"
        )

        vault.saveConnectionProfile(connA)
        vault.saveConnectionProfile(connB)
        vault.setActiveProfileId("conn-a")

        val credA = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("session" to "cookie-a"),
            canonicalOrigin = "http://server-a.local:9119"
        )
        val credB = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("session" to "cookie-b"),
            canonicalOrigin = "http://server-b.local:9119"
        )
        vault.saveAuthCredential(credA, "conn-a")
        vault.saveAuthCredential(credB, "conn-b")

        // Remove connection A
        val deleted = vault.deleteConnectionProfile("conn-a")
        assertTrue("Connection A deletion should succeed", deleted)

        // Connection B remains intact
        val remainingProfiles = vault.listConnectionProfiles()
        assertEquals(1, remainingProfiles.size)
        assertEquals("conn-b", remainingProfiles[0].localId)
        assertEquals("Server B", remainingProfiles[0].displayLabel)

        // Connection B credentials remain intact
        val readCredB = vault.getAuthCredential("conn-b")
        assertNotNull("Connection B credentials must survive removal of Connection A", readCredB)
        assertEquals("cookie-b", readCredB!!.cookies["session"])

        // Connection A credentials cleaned up
        assertNull("Connection A credentials must be removed", vault.getAuthCredential("conn-a"))
    }
}
