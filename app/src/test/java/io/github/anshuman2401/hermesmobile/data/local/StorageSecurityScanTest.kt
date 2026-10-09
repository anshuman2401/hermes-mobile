package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayConnectConfig
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayConnectResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransportFrame
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.origin.OriginCookie
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageSecurityScanTest {

    @Test
    fun modelStringRepresentations_doNotExposeSyntheticPrivateValues() {
        val marker = "synthetic-private-value"
        val representations = listOf(
            AuthCredential("cookie_session", marker, mapOf("session" to marker), "http://$marker"),
            Draft(marker, marker, profileLocalId = marker),
            PendingTurnRecord(marker, marker, promptContent = marker, state = PendingTurnState.UNKNOWN),
            SessionMessage("user", marker, toolContext = marker, reasoning = marker),
            SessionResumeResult(marker, marker, 1, messages = listOf(SessionMessage("user", marker))),
            OriginCookie(marker, marker, marker, sourceOrigin = "http://$marker"),
            GatewayConnectConfig("ws://$marker/api/ws?ticket=$marker", listOf("hermes-gateway-ticket.$marker")),
            GatewayConnectResult.Failure(IllegalStateException(marker)),
            GatewayTransportFrame.Text(marker),
            GatewayTransportFrame.Close(1000, marker),
            GatewayTransportFrame.Error(IllegalStateException(marker))
        ).map(Any::toString)
        assertTrue(representations.all { "[REDACTED]" in it })
        assertTrue(representations.none { marker in it })
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun models_toString_neverExposeSecretsOrPromptContent() {
        val cred = AuthCredential(
            driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password",
            cookies = mapOf("hermes_session" to "super-secret-session-cookie-12345")
        )
        assertFalse("AuthCredential.toString must not leak cookie value", cred.toString().contains("super-secret-session-cookie-12345"))
        assertTrue("AuthCredential.toString must show [REDACTED]", cred.toString().contains("[REDACTED]"))

        val draft = Draft(
            storedSessionId = "sess-1",
            composerText = "Confidential user prompt message"
        )
        assertFalse("Draft.toString must not leak prompt content", draft.toString().contains("Confidential user prompt message"))

        val turn = PendingTurnRecord(
            pendingTurnId = "turn-1",
            storedSessionId = "sess-1",
            promptContent = "Another confidential user prompt"
        )
        assertFalse("PendingTurnRecord.toString must not leak prompt content", turn.toString().contains("Another confidential user prompt"))

        val ticket = GatewayTicket(value = "secret-ticket-raw", ttlSeconds = 30)
        assertFalse("GatewayTicket.toString must not leak ticket value", ticket.toString().contains("secret-ticket-raw"))
        assertTrue("GatewayTicket.toString must show [REDACTED]", ticket.toString().contains("[REDACTED]"))
    }

    @Test
    fun redactor_redactsAllSensitiveCredentialPatterns() {
        val cookieLog = "Received cookie: hermes_session=raw_secret_cookie_val_123 in response"
        val redactedCookie = GatewayRedactor.redactString(cookieLog)
        assertFalse(redactedCookie.contains("raw_secret_cookie_val_123"))
        assertTrue(redactedCookie.contains("hermes_session=[REDACTED]"))

        val bearerLog = "Sending header Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test"
        val redactedBearer = GatewayRedactor.redactString(bearerLog)
        assertFalse(redactedBearer.contains("eyJhbGciOiJIUzI1NiJ9.test"))
        assertTrue(redactedBearer.contains("Bearer [REDACTED]"))

        val ticketLog = "Connecting to subprotocol hermes-gateway-ticket.ticket_val_999"
        val redactedTicket = GatewayRedactor.redactString(ticketLog)
        assertFalse(redactedTicket.contains("ticket_val_999"))
        assertTrue(redactedTicket.contains("hermes-gateway-ticket.[REDACTED]"))

        val kvPasswordLog = "User login attempt with password=PlainTextPassword123"
        val redactedPassword = GatewayRedactor.redactString(kvPasswordLog)
        assertFalse(redactedPassword.contains("PlainTextPassword123"))
        assertTrue(redactedPassword.contains("password=[REDACTED]"))
    }

    @Test
    fun encryptedVaultFiles_containZeroPlaintextData() = runBlocking {
        val dir = tempFolder.newFolder("secret_scan_vault")
        val vault = EncryptedVault(dir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))

        val secretCookie = "very_secret_cookie_token_abc"
        val secretDraft = "super_confidential_prompt_text"

        vault.saveAuthCredential(
            AuthCredential(
                driverKind = "cookie_session",
                providerId = "pwd",
                cookies = mapOf("session" to secretCookie)
            )
        )
        vault.saveDraft(Draft(storedSessionId = "s-1", composerText = secretDraft))

        // Scan every file in the storage directory
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("Vault files must exist", files.isNotEmpty())

        for (file in files) {
            val content = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("Vault file ${file.name} must not contain plaintext cookie", content.contains(secretCookie))
            assertFalse("Vault file ${file.name} must not contain plaintext draft", content.contains(secretDraft))
        }
    }
}
