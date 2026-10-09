package com.hermes.bot.data.auth

import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.CacheEpochRotationReason
import com.hermes.bot.data.local.Draft
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.SignOutCacheChoice
import com.hermes.bot.data.local.VaultStorage
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

class DefaultAuthRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vault: EncryptedVault
    private lateinit var fakeTransport: FakeAuthHttpTransport
    private lateinit var authRepo: DefaultAuthRepository

    class FakeAuthHttpTransport : AuthHttpTransport {
        var recordedRequests = mutableListOf<RecordedRequest>()
        var statusResponse = HttpResponse(
            statusCode = 200,
            body = """{"version":"0.21.1","gateway_running":true,"auth_required":true,"auth_providers":["basic"]}"""
        )
        var nextResponse = HttpResponse(200)

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
            return if (url.endsWith("/api/status")) statusResponse else nextResponse
        }
    }

    @Before
    fun setUp() {
        val storageDir = tempFolder.newFolder("auth_test")
        vault = EncryptedVault(storageDir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        fakeTransport = FakeAuthHttpTransport()
        authRepo = DefaultAuthRepository(vaultStorage = vault, httpTransport = fakeTransport)
    }

    @Test fun ticketUnauthorizedAndForbiddenHaveTypedAuthenticationFailure() = runBlocking {
        for (code in listOf(401, 403)) {
            vault.saveAuthCredential(com.hermes.bot.data.local.AuthCredential(
                "cookie_session", "basic", mapOf("session" to "synthetic"), "https://example.test"))
            fakeTransport.nextResponse = HttpResponse(code)
            val result = authRepo.mintWebSocketTicket("https://example.test")
            assertTrue(result.exceptionOrNull() is AuthSessionRejectedException)
            assertNull(vault.getAuthCredential())
        }
    }

    @Test fun ticketProviderUnavailablePreservesLoginAndDoesNotRequestSignIn() = runBlocking {
        vault.saveAuthCredential(com.hermes.bot.data.local.AuthCredential(
            "cookie_session", "basic", mapOf("session" to "synthetic"), "https://example.test"))
        fakeTransport.nextResponse = HttpResponse(503)
        val result = authRepo.mintWebSocketTicket("https://example.test")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is AuthSessionRejectedException)
        assertNotNull(vault.getAuthCredential())
    }

    @Test
    fun ticketRotationIsCommittedBeforeNextTicket() = runBlocking {
        vault.saveAuthCredential(com.hermes.bot.data.local.AuthCredential(
            "cookie_session", "basic", mapOf("session" to "synthetic-old", "refresh" to "synthetic-retained"),
            "https://example.test"
        ))
        fakeTransport.nextResponse = HttpResponse(200,
            mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")),
            """{"ticket":"synthetic-ticket","ttl":30}""")
        assertTrue(authRepo.mintWebSocketTicket("https://example.test").isSuccess)
        assertTrue(authRepo.mintWebSocketTicket("https://example.test").isSuccess)
        assertEquals("session=synthetic-new; refresh=synthetic-retained",
            fakeTransport.recordedRequests.last().headers["Cookie"])
    }

    @Test
    fun malformedTicketBodyDoesNotUndoAcceptedCookieRotation() = runBlocking {
        vault.saveAuthCredential(com.hermes.bot.data.local.AuthCredential(
            "cookie_session", "basic", mapOf("session" to "synthetic-old"), "https://example.test"))
        fakeTransport.nextResponse = HttpResponse(200,
            mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")), "synthetic-malformed")
        assertTrue(authRepo.mintWebSocketTicket("https://example.test").isFailure)
        assertEquals("synthetic-new", vault.getAuthCredential()!!.cookies["session"])
    }

    @Test
    fun login_success_storesCredentialAndParsesSetCookie() = runBlocking {
        fakeTransport.nextResponse = HttpResponse(
            statusCode = 200,
            headers = mapOf(
                "Set-Cookie" to listOf("hermes_session=sess_cookie_abc123; Path=/; HttpOnly")
            ),
            body = """{"ok":true}"""
        )

        val password = "my-secret-password".toCharArray()
        val result = authRepo.login(
            origin = "https://hermes.local:8443",
            username = "admin",
            password = password,
            provider = "password"
        )

        assertTrue(result.isSuccess)
        val cred = result.getOrThrow()
        assertEquals("basic", cred.providerId)
        assertEquals("sess_cookie_abc123", cred.cookies["hermes_session"])
        assertEquals(2, fakeTransport.recordedRequests.size)
        assertTrue(fakeTransport.recordedRequests[0].url.endsWith("/api/status"))
        val loginRequest = fakeTransport.recordedRequests[1]
        assertTrue(loginRequest.url.endsWith("/auth/password-login"))
        assertTrue(loginRequest.body!!.contains("\"provider\":\"basic\""))
        assertFalse(loginRequest.body.contains("\"provider\":\"password\""))

        val stored = vault.getAuthCredential()
        assertNotNull(stored)
        assertEquals("sess_cookie_abc123", stored!!.cookies["hermes_session"])
    }

    @Test
    fun login_clearsPasswordMemoryImmediately() = runBlocking {
        fakeTransport.nextResponse = HttpResponse(statusCode = 200, body = """{"ok":true}""")
        val password = "sensitive-pass".toCharArray()

        authRepo.login(
            origin = "https://hermes.local:8443",
            username = "admin",
            password = password
        )

        // Verify array was wiped with '0'
        for (c in password) {
            assertEquals('0', c)
        }
    }

    @Test
    fun login_httpOrigin_runsPreflightBeforeCredentialPostAndFailsWithoutCookie() = runBlocking {
        val result = authRepo.login(
            origin = "http://cleartext.hermes.local:9119",
            username = "admin",
            password = "pass".toCharArray()
        )

        assertTrue(result.isFailure)
        assertEquals(2, fakeTransport.recordedRequests.size)
        assertTrue(fakeTransport.recordedRequests[0].url.endsWith("/api/status"))
        assertNull(fakeTransport.recordedRequests[0].body)
        assertTrue(fakeTransport.recordedRequests[1].url.endsWith("/auth/password-login"))
        assertNotNull(fakeTransport.recordedRequests[1].body)
    }

    @Test
    fun checkStatus_retainsCertifiedAdvertisedProviderId() = runBlocking {
        val result = authRepo.checkStatus("https://hermes.local:8443")

        assertTrue(result.isSuccess)
        assertEquals(listOf("basic"), result.getOrThrow().authProviders)
    }

    @Test
    fun login_withoutAdvertisedProvider_failsBeforeCredentialPost() = runBlocking {
        fakeTransport.statusResponse = HttpResponse(
            statusCode = 200,
            body = """{"version":"0.21.1","gateway_running":true,"auth_required":true}"""
        )
        val password = "sensitive-pass".toCharArray()

        val result = authRepo.login(
            origin = "https://hermes.local:8443",
            username = "admin",
            password = password
        )

        assertTrue(result.isFailure)
        assertEquals(1, fakeTransport.recordedRequests.size)
        assertTrue(fakeTransport.recordedRequests.single().url.endsWith("/api/status"))
        assertTrue(password.all { it == '0' })
    }

    @Test
    fun mintWebSocketTicket_usesCookieAndReturnsMemoryOnlyTicket() = runBlocking {
        // Prepare stored session credential
        fakeTransport.nextResponse = HttpResponse(
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("hermes_session=valid_session_cookie; Path=/")),
            body = """{"ok":true}"""
        )
        authRepo.login("https://hermes.local:8443", "admin", "p".toCharArray())

        // Set up ticket response
        fakeTransport.nextResponse = HttpResponse(
            statusCode = 200,
            body = """{"ticket":"memory-ticket-xyz-987","ttl":30}"""
        )

        val ticketResult = authRepo.mintWebSocketTicket("https://hermes.local:8443")
        assertTrue(ticketResult.isSuccess)
        val ticket = ticketResult.getOrThrow()
        assertEquals("memory-ticket-xyz-987", ticket.value)
        assertEquals(30, ticket.ttlSeconds)

        // Verify request had Cookie header
        val req = fakeTransport.recordedRequests.last()
        assertEquals("POST", req.method)
        assertEquals("https://hermes.local:8443/api/auth/ws-ticket", req.url)
        assertEquals("hermes_session=valid_session_cookie", req.headers["Cookie"])

        // CRITICAL CONTRACT CHECK: Ticket must NEVER be stored in vault
        val storedCred = vault.getAuthCredential()
        assertNotNull(storedCred)
        assertFalse(storedCred!!.toString().contains("memory-ticket-xyz-987"))
    }

    @Test
    fun mintWebSocketTicket_authExpired401_clearsCredentialAndPreservesDraft() = runBlocking {
        // Setup initial credential and draft
        fakeTransport.nextResponse = HttpResponse(
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("hermes_session=expired_session; Path=/")),
            body = """{"ok":true}"""
        )
        authRepo.login("https://hermes.local:8443", "admin", "p".toCharArray())

        vault.saveDraft(Draft(storedSessionId = "sess-1", composerText = "Saved draft message"))

        // Next call returns 401
        fakeTransport.nextResponse = HttpResponse(statusCode = 401, body = "Unauthorized")

        val ticketResult = authRepo.mintWebSocketTicket("https://hermes.local:8443")
        assertTrue(ticketResult.isFailure)

        // Auth credential must be cleared
        assertNull("Expired auth credential must be cleared", vault.getAuthCredential())
        assertFalse("Session is no longer valid", authRepo.hasValidSession())

        // Draft must be preserved!
        val draft = vault.getDraft("sess-1")
        assertNotNull("Draft must be preserved when auth expires", draft)
        assertEquals("Saved draft message", draft!!.composerText)
    }

    @Test
    fun signOut_clearsAuthAndDoesNotDeleteRemoteServerSession() = runBlocking {
        fakeTransport.nextResponse = HttpResponse(
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("hermes_session=active_session; Path=/")),
            body = """{"ok":true}"""
        )
        authRepo.login("https://hermes.local:8443", "admin", "p".toCharArray())

        val requestCountBeforeSignOut = fakeTransport.recordedRequests.size

        // Sign out
        authRepo.signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.KEEP)

        assertNull("Auth credential must be cleared after sign-out", vault.getAuthCredential())

        // Invariant: Local reset/sign-out must never remotely delete the server session.
        assertEquals(
            "Sign-out must not perform any remote HTTP request",
            requestCountBeforeSignOut,
            fakeTransport.recordedRequests.size
        )
    }

    /**
     * PR-6 TEST-05 (REQ-02/REQ-07): DefaultAuthRepository forwards the SAME
     * explicit choice into clearAuthCredential — no call site relies on the
     * storage-layer default.
     */
    private class RecordingVaultStorage(
        private val delegate: VaultStorage
    ) : VaultStorage by delegate {
        val clearChoices = mutableListOf<SignOutCacheChoice>()
        val clearReasons = mutableListOf<CacheEpochRotationReason>()

        override suspend fun clearAuthCredential(
            profileLocalId: String?,
            reason: CacheEpochRotationReason,
            cacheChoice: SignOutCacheChoice
        ) {
            clearChoices += cacheChoice
            clearReasons += reason
            delegate.clearAuthCredential(profileLocalId, reason, cacheChoice)
        }
    }

    @Test
    fun signOut_passesChoiceToVault() = runBlocking {
        val recording = RecordingVaultStorage(vault)
        val repo = DefaultAuthRepository(vaultStorage = recording, httpTransport = fakeTransport)
        repo.signOut(clearDrafts = false, cacheChoice = SignOutCacheChoice.CLEAR)
        assertEquals(listOf(SignOutCacheChoice.CLEAR), recording.clearChoices)
        // REQ-01: sign-out rotates with the signed-out reason for either choice.
        assertEquals(listOf(CacheEpochRotationReason.SIGNED_OUT), recording.clearReasons)
        assertNull("choice sign-out still clears the credential", vault.getAuthCredential())
    }

    @Test
    fun notifyAuthExpired_usesExplicitKeep() = runBlocking {
        val recording = RecordingVaultStorage(vault)
        val repo = DefaultAuthRepository(vaultStorage = recording, httpTransport = fakeTransport)
        repo.notifyAuthExpired()
        assertEquals(
            "expiry has no user present — explicit KEEP (REQ-07)",
            listOf(SignOutCacheChoice.KEEP),
            recording.clearChoices
        )
        assertEquals(listOf(CacheEpochRotationReason.AUTH_EXPIRED), recording.clearReasons)
    }
}
