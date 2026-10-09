package io.github.anshuman2401.hermesmobile.data.auth

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayRedactor
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.origin.OriginCookie
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

/**
 * RPR-003 Security Hardening & Behavioral Tests.
 *
 * Verifies:
 * - Hostile URLs cause ZERO credential transport.
 * - Non-secret preflight check occurs before credential exposure.
 * - 2xx response without session cookie fails.
 * - Cookies never cross origins.
 * - Single-use ticket minting: distinct tickets per attempt, zero caching.
 * - Poison secrets never leak into public outputs, exceptions, or toString.
 */
class AuthOriginVaultSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vault: EncryptedVault
    private lateinit var fakeTransport: RecordingAuthHttpTransport
    private lateinit var authRepo: DefaultAuthRepository

    class RecordingAuthHttpTransport : AuthHttpTransport {
        val recordedRequests = mutableListOf<RecordedRequest>()
        var responseProvider: (method: String, url: String) -> HttpResponse = { _, _ -> HttpResponse(200) }

        data class RecordedRequest(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?
        )

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse {
            recordedRequests.add(RecordedRequest(method, url, headers, body))
            return responseProvider(method, url)
        }
    }

    @Before
    fun setUp() {
        val storageDir = tempFolder.newFolder("auth_origin_sec_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeTransport = RecordingAuthHttpTransport()
        authRepo = DefaultAuthRepository(vaultStorage = vault, httpTransport = fakeTransport)
    }

    @Test
    fun hostileUrls_causeStrictlyZeroCredentialTransport() = runBlocking {
        val hostileOrigins = listOf(
            "http://cleartext.hermes.local/../invalid",
            "ws://hermes.local:8443",
            "https://user:password@hermes.local:8443",
            "https://hermes.local:8443/?token=leak",
            "https://hermes.local:8443/#leak",
            "https://hermes.local:8443\r\ninjection",
            "https://hermes.local\u202Einjection.com",
            "https://hermes.local:0",
            "https://hermes.local:70000",
            "https://hermes.local:8443/../evil",
            "https://hermes%2Elocal:8443",
            "https://hermes.local\\@attacker.com"
        )

        val poisonPassword = "poison-password-must-not-transmit".toCharArray()

        for (hostile in hostileOrigins) {
            val countBefore = fakeTransport.recordedRequests.size

            // Attempt login with hostile origin
            val loginResult = authRepo.login(
                origin = hostile,
                username = "admin",
                password = poisonPassword.clone()
            )

            assertTrue("Expected failure for hostile origin: $hostile", loginResult.isFailure)
            assertEquals("Hostile origin must produce ZERO network requests", countBefore, fakeTransport.recordedRequests.size)

            // Attempt ticket mint with hostile origin
            val ticketResult = authRepo.mintWebSocketTicket(hostile)
            assertTrue("Expected failure for ticket mint on hostile origin: $hostile", ticketResult.isFailure)
            assertEquals("Hostile origin must produce ZERO network requests", countBefore, fakeTransport.recordedRequests.size)
        }
    }

    @Test
    fun preflightFailure_strictlyBlocksCredentialTransmission() = runBlocking {
        // Preflight returns 500 server error
        fakeTransport.responseProvider = { _, url ->
            if (url.endsWith("/api/status")) {
                HttpResponse(500, body = "Internal Server Error")
            } else {
                HttpResponse(200, body = """{"ok":true}""")
            }
        }

        val secretPassword = "ConfidentialPassword123!".toCharArray()
        val result = authRepo.login(
            origin = "https://hermes.local:8443",
            username = "admin",
            password = secretPassword
        )

        assertTrue("Login must fail when preflight fails", result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)

        // Verify that NO request was sent to /auth/password-login
        val loginRequests = fakeTransport.recordedRequests.filter { it.url.contains("password-login") }
        assertEquals("Zero credential transmission when preflight fails", 0, loginRequests.size)

        // Verify password array was wiped
        for (c in secretPassword) {
            assertEquals('0', c)
        }
    }

    @Test
    fun response2xxWithoutRequiredCookie_failsAuthentication() = runBlocking {
        // Status preflight succeeds, login responds 200 OK but NO Set-Cookie header
        fakeTransport.responseProvider = { _, url ->
            if (url.endsWith("/api/status")) {
                HttpResponse(200, body = """{"version":"v0.21.1","auth_required":true,"gateway_running":true,"auth_providers":["basic"]}""")
            } else {
                HttpResponse(200, headers = emptyMap(), body = """{"success":true}""")
            }
        }

        val result = authRepo.login(
            origin = "https://hermes.local:8443",
            username = "admin",
            password = "test-password".toCharArray()
        )

        assertTrue("2xx without Set-Cookie must fail", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is SecurityException)
        assertTrue(ex!!.message!!.contains("missing required session cookie") || ex.message!!.contains("no valid session cookie"))

        // Vault must not have any stored credential
        assertNull(vault.getAuthCredential())
    }

    @Test
    fun cookiesNeverCrossOrigins_strictDomainIsolation() {
        val originA = CanonicalOrigin.parse("https://gateway-a.local:8443").getOrThrow()
        val originB = CanonicalOrigin.parse("https://gateway-b.local:8443").getOrThrow()

        val cookieHeader = "hermes_session=secret_token_123; Domain=gateway-a.local; Path=/; Secure; HttpOnly"
        val parsedCookie = OriginCookie.parseSetCookie(cookieHeader, originA)
        assertNotNull(parsedCookie)

        // Applicable to origin A
        assertTrue(parsedCookie!!.isApplicableTo(originA))

        // Strictly NOT applicable to origin B
        assertFalse("Cookie from origin A must never be applicable to origin B", parsedCookie.isApplicableTo(originB))

        // Attempting to set cookie with wrong domain from origin A is rejected entirely
        val forgedCookie = OriginCookie.parseSetCookie(
            "hermes_session=evil; Domain=gateway-b.local; Path=/",
            originA
        )
        assertNull("Cookie forged with cross-domain attribute must be rejected", forgedCookie)
    }

    @Test
    fun everyConnectionAttempt_receivesDistinctSingleUseTicket_neverCached() = runBlocking {
        var ticketCounter = 0
        fakeTransport.responseProvider = { _, url ->
            when {
                url.endsWith("/api/status") -> {
                    HttpResponse(200, body = """{"version":"v0.21.1","auth_required":true,"gateway_running":true,"auth_providers":["basic"]}""")
                }
                url.endsWith("/auth/password-login") -> {
                    HttpResponse(200, headers = mapOf("Set-Cookie" to listOf("hermes_session=valid_session_cookie; Path=/")), body = """{"ok":true}""")
                }
                url.endsWith("/api/auth/ws-ticket") -> {
                    ticketCounter++
                    HttpResponse(200, body = """{"ticket":"single-use-ticket-$ticketCounter","ttl":30}""")
                }
                else -> HttpResponse(404)
            }
        }

        // Login first
        authRepo.login("https://hermes.local:8443", "admin", "pass".toCharArray())

        // First ticket mint
        val ticket1 = authRepo.mintWebSocketTicket("https://hermes.local:8443").getOrThrow()
        assertEquals("single-use-ticket-1", ticket1.value)

        // Second ticket mint MUST fetch a fresh distinct ticket from server (never cached)
        val ticket2 = authRepo.mintWebSocketTicket("https://hermes.local:8443").getOrThrow()
        assertEquals("single-use-ticket-2", ticket2.value)

        // Third ticket mint
        val ticket3 = authRepo.mintWebSocketTicket("https://hermes.local:8443").getOrThrow()
        assertEquals("single-use-ticket-3", ticket3.value)

        assertTrue(ticket1.value != ticket2.value)
        assertTrue(ticket2.value != ticket3.value)
        assertEquals(3, ticketCounter)
    }

    @Test
    fun poisonSecrets_neverEnterPublicOutputsOrToString() {
        val poisonCookie = "super_secret_cookie_token_99999"
        val poisonTicket = "raw_single_use_ticket_secret_88888"

        val credential = io.github.anshuman2401.hermesmobile.data.local.AuthCredential(
            driverKind = "cookie_session",
            providerId = "password",
            cookies = mapOf("hermes_session" to poisonCookie),
            canonicalOrigin = "https://hermes.local:8443"
        )
        assertFalse("AuthCredential.toString must not leak cookie", credential.toString().contains(poisonCookie))
        assertTrue("AuthCredential.toString must show REDACTED", credential.toString().contains("[REDACTED]"))

        val ticket = GatewayTicket(poisonTicket, 30)
        assertFalse("GatewayTicket.toString must not leak ticket", ticket.toString().contains(poisonTicket))
        assertTrue("GatewayTicket.toString must show REDACTED", ticket.toString().contains("[REDACTED]"))

        val cookieObj = OriginCookie(
            name = "hermes_session",
            value = poisonCookie,
            domain = "hermes.local",
            sourceOrigin = "https://hermes.local:8443"
        )
        assertFalse("OriginCookie.toString must not leak value", cookieObj.toString().contains(poisonCookie))
        assertTrue("OriginCookie.toString must show REDACTED", cookieObj.toString().contains("[REDACTED]"))

        val redactedLog = GatewayRedactor.redactString("Error contacting ws-ticket with ticket=$poisonTicket and cookie hermes_session=$poisonCookie")
        assertFalse(redactedLog.contains(poisonTicket))
        assertFalse(redactedLog.contains(poisonCookie))
    }
}
