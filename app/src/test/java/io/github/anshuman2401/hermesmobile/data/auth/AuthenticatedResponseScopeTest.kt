package io.github.anshuman2401.hermesmobile.data.auth

import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthenticatedResponseScopeTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = CanonicalOrigin.parse("http://example.test").getOrThrow()
    private fun credential(value: String = "synthetic-old") = AuthCredential(
        "cookie_session", "basic", mapOf("session" to value, "refresh" to "synthetic-retained"), origin.originUrl)
    private fun vault() = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    private class Headers(override val statusCode: Int = 200,
        override val headers: Map<String, List<String>> = emptyMap()) : AuthenticatedHttpHeaders {
        var closed = false
        override fun close() { closed = true }
    }
    private fun call(response: Headers) = object : AuthenticatedHttpCall<Headers> {
        override suspend fun awaitHeaders() = response
        override fun cancel() = response.close()
    }
    private val ticketTransport = object : AuthHttpTransport {
        var selected: String? = null
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            selected = headers["Cookie"]
            return HttpResponse(200, body = """{"ticket":"synthetic-ticket","ttl":30}""")
        }
    }

    private val loginTransport = object : AuthHttpTransport {
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
            if (url.endsWith("/api/status")) HttpResponse(200,
                body = """{"version":"synthetic","auth_providers":["basic"]}""")
            else HttpResponse(200, mapOf("Set-Cookie" to listOf("session=synthetic-login; Path=/")))
    }

    @Test fun deferredRotationOwnReceiptAndSubsequentTicket() = runBlocking {
        val vault = vault(); vault.saveAuthCredential(credential())
        val repo = DefaultAuthRepository(vault, ticketTransport)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exchange = async(start = CoroutineStart.UNDISPATCHED) {
            repo.exchangeAuthenticated(origin, "/api/synthetic") {
                object : AuthenticatedHttpCall<Headers> {
                    override suspend fun awaitHeaders(): Headers {
                        entered.complete(Unit); release.await()
                        return Headers(headers = mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")))
                    }
                    override fun cancel() = Unit
                }
            }
        }
        entered.await()
        val ticket = async(start = CoroutineStart.UNDISPATCHED) { repo.mintWebSocketTicket(origin) }
        assertNull(ticketTransport.selected)
        release.complete(Unit)
        val accepted = exchange.await().getOrThrow()
        assertTrue(repo.isResponseCurrent(accepted.receipt))
        assertTrue(ticket.await().isSuccess)
        assertEquals("session=synthetic-new; refresh=synthetic-retained", ticketTransport.selected)
        // Body ownership is independent: another exchange can finish while this handle is open.
        assertFalse(accepted.response.closed)
        vault.saveAuthCredential(credential("synthetic-login"))
        assertFalse(repo.isResponseCurrent(accepted.receipt))
        accepted.response.close()
    }

    @Test fun lateTerminalOrDeletionCannotAffectLoginSignoutOrProfileSwitch() = runBlocking {
        for (status in listOf(200, 401, 403)) for (change in 0..2) {
            val vault = vault()
            vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
            vault.saveAuthCredential(credential())
            val repo = DefaultAuthRepository(vault, loginTransport)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val response = Headers(status, mapOf("Set-Cookie" to listOf("session=; Path=/; Max-Age=0")))
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                repo.exchangeAuthenticated(origin, "/api/synthetic") {
                    object : AuthenticatedHttpCall<Headers> {
                        override suspend fun awaitHeaders(): Headers { entered.complete(Unit); release.await(); return response }
                        override fun cancel() = response.close()
                    }
                }
            }
            entered.await()
            when (change) {
                0 -> assertTrue(repo.login(origin, "synthetic-user", "synthetic-password".toCharArray(), "basic").isSuccess)
                1 -> repo.signOut(cacheChoice = SignOutCacheChoice.KEEP)
                2 -> {
                    vault.saveConnectionProfile(ConnectionProfile("synthetic-b", origin.originUrl, "Synthetic"))
                    vault.setActiveProfileId("synthetic-b")
                    vault.saveAuthCredential(credential("synthetic-login"))
                }
            }
            val before = vault.getAuthCredential(); release.complete(Unit)
            assertTrue(old.await().exceptionOrNull() is StaleAuthenticatedResponseException)
            assertEquals(before, vault.getAuthCredential())
            assertTrue(response.closed)
        }
    }

    @Test fun validatedTombstonesAndProviderUncertainty() = runBlocking {
        for (cookie in listOf("session=; Path=/; Max-Age=0",
            "session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")) {
            val vault = vault(); vault.saveAuthCredential(credential())
            val repo = DefaultAuthRepository(vault)
            assertTrue(repo.exchangeAuthenticated(origin, "/api/synthetic") {
                call(Headers(headers = mapOf("Set-Cookie" to listOf(cookie))))
            }.exceptionOrNull() is SecurityException)
            assertNull(vault.getAuthCredential())
        }
        for (cookie in listOf("session=; Domain=other.test; Max-Age=0",
            "session=; Path=/other; Max-Age=0", "session=; Secure; Max-Age=0")) {
            val vault = vault(); vault.saveAuthCredential(credential())
            val repo = DefaultAuthRepository(vault)
            assertTrue(repo.exchangeAuthenticated(origin, "/api/synthetic") {
                call(Headers(headers = mapOf("Set-Cookie" to listOf(cookie))))
            }.isSuccess)
            assertEquals(credential().cookies, vault.getAuthCredential()!!.cookies)
        }
        val vault = vault(); vault.saveAuthCredential(credential())
        val repo = DefaultAuthRepository(vault)
        assertTrue(repo.exchangeAuthenticated(origin, "/api/synthetic") {
            call(Headers(503, mapOf("Set-Cookie" to listOf("session=; Max-Age=0"))))
        }.isSuccess)
        assertEquals(credential().cookies, vault.getAuthCredential()!!.cookies)
    }

    @Test fun cancellationCancelsTransportAndReleasesHeaderOwnership() = runBlocking {
        val vault = vault(); vault.saveAuthCredential(credential())
        val repo = DefaultAuthRepository(vault, ticketTransport)
        val entered = CompletableDeferred<Unit>(); var cancelled = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            repo.exchangeAuthenticated(origin, "/api/synthetic") {
                object : AuthenticatedHttpCall<Headers> {
                    override suspend fun awaitHeaders(): Headers { entered.complete(Unit); awaitCancellation() }
                    override fun cancel() { cancelled = true }
                }
            }
        }
        entered.await(); request.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(repo.mintWebSocketTicket(origin).isSuccess)
    }

    @Test fun failedPersistenceRejectsExchangeAndLaterTicket() = runBlocking {
        var fail = false
        val directory = temp.newFolder()
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        val failingEngine = object : VaultCipherEngine by engine {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (fail && associatedData?.toString(Charsets.UTF_8)?.startsWith("AUTH_CREDENTIAL:") == true)
                    error("Synthetic persistence failure")
                return engine.encrypt(plaintext, associatedData)
            }
        }
        val vault = EncryptedVault(directory, failingEngine)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential()); fail = true
        val repo = DefaultAuthRepository(vault, ticketTransport)
        val response = Headers(headers = mapOf("Set-Cookie" to listOf("session=synthetic-new")))
        assertTrue(repo.exchangeAuthenticated(origin, "/api/synthetic") { call(response) }.isFailure)
        assertTrue(response.closed)
        assertTrue(repo.mintWebSocketTicket(origin).isFailure)
        assertNull(ticketTransport.selected)
        // Durable denial also survives a new repository/vault instance.
        val reopened = EncryptedVault(directory, engine)
        assertNull(reopened.readAuthCredentialLease(origin.originUrl))
        assertTrue(reopened.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        assertTrue(DefaultAuthRepository(reopened, ticketTransport).mintWebSocketTicket(origin).isFailure)
    }

    @Test fun initialDenialEncryptionFailureRemainsDeniedAfterRestart() = runBlocking {
        val directory = temp.newFolder()
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        var armed = false
        var injected = false
        val failingEngine = object : VaultCipherEngine by engine {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (armed && associatedData?.toString(Charsets.UTF_8)?.startsWith("CACHE_ACCESS_STATE:") == true) {
                    injected = true
                    throw IllegalStateException("Synthetic initial denial encryption failure")
                }
                return engine.encrypt(plaintext, associatedData)
            }
        }
        val vault = EncryptedVault(directory, failingEngine)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential())
        val issuing = vault.readAuthCredentialLease(origin.originUrl)!!
        val repo = DefaultAuthRepository(vault, ticketTransport)
        armed = true
        val response = Headers(headers = mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")))
        val rejected = repo.exchangeAuthenticated(origin, "/api/synthetic") { call(response) }
        assertTrue(injected)
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertTrue(response.closed)
        assertTrue(repo.mintWebSocketTicket(origin).isFailure)
        assertNull(ticketTransport.selected)
        armed = false
        assertNull(vault.readAuthCredentialLease(origin.originUrl))
        assertNull(vault.getAuthCredential())
        assertNull(vault.getAuthCredential("synthetic-a"))
        val shared = DefaultAuthRepository(vault, ticketTransport)
        assertFalse(shared.isResponseCurrent(issuing))
        assertTrue(shared.mintWebSocketTicket(origin).isFailure)
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        // D0: prior healthy bytes may reopen; this is fresh host validation, not recovery.
        // Restore working encryption, but do not authenticate before reopening.
        val reopened = EncryptedVault(directory, engine)
        val reopenedLease = reopened.readAuthCredentialLease(origin.originUrl)
        val restarted = DefaultAuthRepository(reopened, ticketTransport)
        val restartedTicket = restarted.mintWebSocketTicket(origin)
        assertLegitimateLoginAndOtherProfileRemainIndependent(reopened, repo, issuing)
        assertNotNull("D0 leaves intact prior bytes readable on reconstruction", reopenedLease)
        assertTrue("Synthetic host may accept the reconstructed lease", restartedTicket.isSuccess)
    }

    @Test fun finalReauthorizationSyncFailureCannotAuthorizePartialSuccessor() = runBlocking {
        val directory = temp.newFolder()
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        var armed = false
        var syncs = 0
        var injected = false
        val vault = EncryptedVault(directory, engine, directorySync = {
            // First state sync follows SIGNED_OUT; second follows installed successor.
            if (armed && ++syncs == 2) {
                injected = true
                throw java.io.IOException("Synthetic final reauthorization sync failure")
            }
        })
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential())
        val issuing = vault.readAuthCredentialLease(origin.originUrl)!!
        val repo = DefaultAuthRepository(vault, ticketTransport)
        armed = true
        val response = Headers(headers = mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")))
        val rejected = repo.exchangeAuthenticated(origin, "/api/synthetic") { call(response) }
        assertTrue(injected)
        assertEquals("One bounded restoration follows the failed successor publication", 3, syncs)
        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
        assertTrue(response.closed)
        armed = false
        assertNull(vault.getAuthCredential())
        assertTrue(DefaultAuthRepository(vault, ticketTransport).mintWebSocketTicket(origin).isFailure)
        assertFalse(repo.isResponseCurrent(issuing))
        val laterTicket = repo.mintWebSocketTicket(origin)
        val reopened = EncryptedVault(directory, engine)
        val reopenedLease = reopened.readAuthCredentialLease(origin.originUrl)
        val restartedTicket = DefaultAuthRepository(reopened, ticketTransport).mintWebSocketTicket(origin)
        assertLegitimateLoginAndOtherProfileRemainIndependent(vault, repo, issuing)
        assertTrue("Failed final sync must deny the partial successor in the same repository", laterTicket.isFailure)
        assertNull("Failed final sync must remain denied after restart", reopenedLease)
        assertTrue(restartedTicket.isFailure)
    }

    @Test fun cancellationDuringFailedCommitClosesAndRetainsSharedDenial() = runBlocking {
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider()); var armed = false
        val cipher = object : VaultCipherEngine by engine {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (armed && associatedData?.toString(Charsets.UTF_8)?.startsWith("CACHE_ACCESS_STATE:") == true)
                    throw CancellationException("Synthetic commit cancellation")
                return engine.encrypt(plaintext, associatedData)
            }
        }
        val vault = EncryptedVault(temp.newFolder(), cipher)
        vault.saveAuthCredential(credential()); val lease = vault.readAuthCredentialLease(origin.originUrl)!!
        val response = Headers(headers = mapOf("Set-Cookie" to listOf("session=synthetic-new; Path=/")))
        armed = true
        val outcome = runCatching { DefaultAuthRepository(vault, ticketTransport)
            .exchangeAuthenticated(origin, "/api/synthetic") { call(response) } }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertTrue(response.closed)
        armed = false
        assertNull(vault.getAuthCredential())
        val shared = DefaultAuthRepository(vault, ticketTransport)
        assertFalse(shared.isResponseCurrent(lease))
        assertTrue(shared.mintWebSocketTicket(origin).isFailure)
        assertNull(ticketTransport.selected)
    }

    private suspend fun assertLegitimateLoginAndOtherProfileRemainIndependent(
        vault: EncryptedVault, failedRepo: DefaultAuthRepository, issuing: AuthCredentialLease
    ) {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-b", origin.originUrl, "Synthetic"))
        assertTrue(vault.setActiveProfileId("synthetic-b"))
        vault.saveAuthCredential(credential("synthetic-independent"))
        assertTrue(failedRepo.mintWebSocketTicket(origin).isSuccess)
        assertTrue(vault.setActiveProfileId("synthetic-a"))
        val loginRepo = DefaultAuthRepository(vault, loginTransport)
        assertTrue(loginRepo.login(origin, "synthetic-user", "synthetic-password".toCharArray(), "basic").isSuccess)
        val newer = vault.readAuthCredentialLease(origin.originUrl)!!
        assertNotEquals(issuing.epoch, newer.epoch)
        assertTrue(vault.mutateAuthCredentialIfCurrent(issuing, null, clear = true) is AuthCredentialMutationResult.Stale)
        assertTrue(failedRepo.mintWebSocketTicket(origin).isSuccess)
        assertTrue(loginRepo.isResponseCurrent(newer))
    }
}
