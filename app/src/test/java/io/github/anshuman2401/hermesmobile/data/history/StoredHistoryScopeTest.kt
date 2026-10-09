package io.github.anshuman2401.hermesmobile.data.history

import io.github.anshuman2401.hermesmobile.data.auth.*
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoredHistoryScopeTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = CanonicalOrigin.parse("http://example.test").getOrThrow()
    private fun credential() = AuthCredential("cookie_session", "basic",
        mapOf("session" to "synthetic-same"), origin.originUrl)
    private class Headers : AuthenticatedHttpHeaders {
        override val statusCode = 200
        override val headers = emptyMap<String, List<String>>()
        override fun close() = Unit
    }

    @Test fun queuedCapturedOwnerNeverDispatchesAfterSameOriginSwitch() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential())
        val captured = vault.readAuthCredentialLease(origin.originUrl)!!
        val auth = DefaultAuthRepository(vault)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            auth.exchangeAuthenticated(origin, "/api/synthetic") {
                object : AuthenticatedHttpCall<Headers> {
                    override suspend fun awaitHeaders(): Headers { entered.complete(Unit); release.await(); return Headers() }
                    override fun cancel() = Unit
                }
            }
        }
        entered.await()
        var factories = 0
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            // Captured lease is compared after the existing header lock is acquired.
            auth.exchangeAuthenticated(origin, "/api/synthetic", captured) {
                factories++
                object : AuthenticatedHttpCall<Headers> {
                    override suspend fun awaitHeaders() = Headers()
                    override fun cancel() = Unit
                }
            }
        }
        vault.saveConnectionProfile(ConnectionProfile("synthetic-b", origin.originUrl, "Synthetic"))
        assertTrue(vault.setActiveProfileId("synthetic-b"))
        vault.saveAuthCredential(credential())
        val before = vault.readAuthCredentialLease(origin.originUrl)!!
        release.complete(Unit); first.await()
        val result = queued.await()
        assertEquals("Captured owner must be checked before the queued factory", 0, factories)
        assertTrue(result.exceptionOrNull() is StaleAuthenticatedResponseException)
        assertEquals(before.epoch, vault.readAuthCredentialLease(origin.originUrl)!!.epoch)
        assertFalse(auth.isResponseCurrent(captured))
    }
    @Test fun queuedRealGraphRejectsNewLoginAndABAReturn() = runBlocking {
        for (change in 0..2) {
            var factories = 0
            HistoryFixture(temp.newFolder()) { actual ->
                object : StoredHistoryHttpTransport by actual {
                    override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
                        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
                        factories++; return actual.open(origin, path, profile, limit, offset, headers, timeoutMillis)
                    }
                }
            }.use { fixture ->
                fixture.initialize(); val scope = fixture.scope(); val budget = StoredHistoryBudget(scope)
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.auth.exchangeAuthenticated(fixture.origin, "/api/synthetic") {
                        object : AuthenticatedHttpCall<Headers> {
                            override suspend fun awaitHeaders(): Headers { entered.complete(Unit); release.await(); return Headers() }
                            override fun cancel() = Unit
                        }
                    }
                }
                entered.await()
                val queued = async(start = CoroutineStart.UNDISPATCHED) { fixture.repository.fetchStoredHistoryPage(scope, budget) }
                if (change != 0) {
                    fixture.vault.saveConnectionProfile(ConnectionProfile("synthetic-b", fixture.origin.originUrl, "Synthetic"))
                    fixture.vault.setActiveProfileId("synthetic-b")
                    fixture.vault.saveAuthCredential(scope.lease.credential)
                }
                if (change != 1) {
                    fixture.vault.setActiveProfileId("synthetic-a")
                    fixture.enqueue("""{"version":"synthetic","auth_providers":["basic"]}""")
                    fixture.server.enqueue(okhttp3.mockwebserver.MockResponse().addHeader("Set-Cookie", "session=synthetic-old; Path=/"))
                    assertTrue(fixture.auth.login(fixture.origin, "synthetic-user", "synthetic-password".toCharArray(), "basic").isSuccess)
                }
                val before = fixture.vault.readAuthCredentialLease(fixture.origin.originUrl)!!
                val diagnostics = fixture.diagnostics.snapshot()
                val requests = fixture.server.requestCount
                release.complete(Unit); first.await()
                assertEquals(StoredHistoryFailure.STALE, (queued.await().exceptionOrNull() as StoredHistoryException).failure)
                assertEquals(0, factories); assertEquals(requests, fixture.server.requestCount)
                assertEquals(before.epoch, fixture.vault.readAuthCredentialLease(fixture.origin.originUrl)!!.epoch)
                assertEquals(before.credential, fixture.vault.getAuthCredential())
                assertEquals(diagnostics, fixture.diagnostics.snapshot())
                assertTrue(fixture.gateway.resumeCalls.isEmpty()); assertNull(fixture.repository.activeSession.value)
            }
        }
    }

    @Test fun matchingRotationReleasesHeaderOwnerBeforeBodyAndUsesLatestTicket() = runBlocking {
        lateinit var body: DeferredHistoryBody
        HistoryFixture(temp.newFolder()) { DeferredHistoryBody(it).also { body = it } }.use { fixture ->
            fixture.initialize(); val scope = fixture.scope(); val budget = StoredHistoryBudget(scope)
            fixture.enqueue(cookie = true)
            val read = async { fixture.repository.fetchStoredHistoryPage(scope, budget) }
            withTimeout(5000) { body.entered.await() }
            fixture.enqueue("""{"ticket":"synthetic-ticket","ttl":30}""")
            assertTrue(withTimeout(5000) { fixture.auth.mintWebSocketTicket(fixture.origin) }.isSuccess)
            fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
            val ticketRequest = fixture.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("session=synthetic-new", ticketRequest.getHeader("Cookie"))
            assertTrue(fixture.auth.isResponseCurrent(budget.receipt))
            body.release.complete(Unit); assertTrue(read.await().isSuccess); assertTrue(body.closed)
            fixture.assertReadOnly()
        }
    }

    @Test fun staleDeferredBodiesNeverPublishForLoginABARemoteSelectionOrInvalidation() = runBlocking {
        for (change in 0..4) {
            lateinit var body: DeferredHistoryBody
            HistoryFixture(temp.newFolder()) { DeferredHistoryBody(it).also { body = it } }.use { fixture ->
                fixture.initialize(); val scope = fixture.scope(); val budget = StoredHistoryBudget(scope)
                fixture.enqueue(cookie = true)
                val read = async { fixture.repository.fetchStoredHistoryPage(scope, budget) }
                withTimeout(5000) { body.entered.await() }
                when (change) {
                    0 -> fixture.vault.saveAuthCredential(fixture.credential("synthetic-login"))
                    1 -> {
                        fixture.vault.saveConnectionProfile(ConnectionProfile("synthetic-b", fixture.origin.originUrl, "Synthetic"))
                        fixture.vault.setActiveProfileId("synthetic-b"); fixture.vault.saveAuthCredential(fixture.credential())
                        fixture.vault.setActiveProfileId("synthetic-a"); fixture.vault.saveAuthCredential(fixture.credential())
                    }
                    2 -> fixture.vault.saveSelectedContextProfile("synthetic-other")
                    3 -> scope.invalidate()
                    4 -> fixture.auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
                }
                val before = fixture.vault.getAuthCredential()
                val diagnostic = fixture.diagnostics.snapshot()
                body.release.complete(Unit)
                assertEquals(StoredHistoryFailure.STALE, (read.await().exceptionOrNull() as StoredHistoryException).failure)
                assertTrue(body.closed); assertEquals(before, fixture.vault.getAuthCredential())
                assertEquals(diagnostic, fixture.diagnostics.snapshot())
                assertNull(fixture.repository.activeSession.value); assertTrue(fixture.repository.activeMessages.value.isEmpty())
                assertNull(fixture.vault.getSessionSelection()); assertTrue(fixture.gateway.replayCalls.isEmpty())
            }
        }
    }

    @Test fun storedReadPreservesExistingSessionStreamingSelectionReplayAndVaultBytes() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize()
            f.gateway.resumeSessionResult = io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(
                io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult("synthetic-runtime", "synthetic-selected", 1,
                    listOf(io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage("assistant", "synthetic-existing"))))
            assertTrue(f.repository.resumeSession("synthetic-selected").isSuccess)
            f.repository.publishTurnStreaming("synthetic-selected", true)
            val scope = f.scope(); val session = f.repository.activeSession.value
            val messages = f.repository.activeMessages.value; val streaming = f.repository.activeTurnStreaming.value
            val selection = f.vault.getSessionSelection(); val replay = f.gateway.replayCalls.toList()
            val watermarks = f.gateway.watermarkBySession.toMap(); val diagnostics = f.diagnostics.snapshot()
            fun files() = f.folder.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(f.folder).path to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
            val bytes = files(); f.enqueue()
            assertTrue(f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).isSuccess)
            assertEquals(session, f.repository.activeSession.value); assertEquals(messages, f.repository.activeMessages.value)
            assertEquals(streaming, f.repository.activeTurnStreaming.value); assertEquals(selection, f.vault.getSessionSelection())
            assertEquals(replay, f.gateway.replayCalls); assertEquals(watermarks, f.gateway.watermarkBySession)
            assertEquals(1, f.gateway.resumeCalls.size); assertEquals(diagnostics, f.diagnostics.snapshot())
            assertEquals(bytes, files())
        }
    }

    @Test fun capturedOriginCredentialAndUnsupportedAuthFailBeforeFactory() = runBlocking {
        HistoryFixture(temp.newFolder()).use { fixture ->
            fixture.initialize(); val lease = fixture.scope().lease
            val variants = listOf(
                AuthCredentialLease(lease.profileLocalId, lease.origin, lease.epoch, lease.credential.copy(cookies = emptyMap())),
                AuthCredentialLease(lease.profileLocalId, origin.originUrl, lease.epoch, lease.credential))
            var calls = 0
            for (expected in variants) assertTrue(fixture.auth.exchangeAuthenticated<Headers>(fixture.origin, "/api/synthetic", expected) {
                calls++; error("Synthetic forbidden factory")
            }.exceptionOrNull() is StaleAuthenticatedResponseException)
            val unsupported = object : AuthRepository {
                override suspend fun signOut(clearDrafts: Boolean, cacheChoice: SignOutCacheChoice) = Unit
                override suspend fun notifyAuthExpired() = Unit
            }
            assertTrue(unsupported.exchangeAuthenticated<Headers>(fixture.origin, "/api/synthetic", lease) {
                calls++; error("Synthetic forbidden factory")
            }.isFailure)
            assertEquals(0, calls); fixture.assertReadOnly()
        }
    }

}

/** All three classes share the actual repository -> adapter -> HTTP -> real auth/vault composition. */
internal class HistoryFixture(val folder: java.io.File,
    decorate: (StoredHistoryHttpTransport) -> StoredHistoryHttpTransport = { it }) : AutoCloseable {
    val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
    val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
    val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
    val diagnostics = io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing()
    val gateway = io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter()
    val auth = DefaultAuthRepository(vault, io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport(), diagnostics)
    private val guardedGateway = object : io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter by gateway {
        override suspend fun submitPrompt(sessionId: String, text: String): io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult<io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult> =
            error("Stored read must never submit a prompt")
        override suspend fun connect(origin: String, ticket: io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket, useQueryFallback: Boolean): Boolean =
            error("Stored read must never open a socket")
    }
    val repository = io.github.anshuman2401.hermesmobile.data.session.DefaultSessionRepository(guardedGateway, vault,
        diagnosticRing = diagnostics, storedHistoryAdapter = DefaultStoredHistoryProtocolAdapter(auth,
            decorate(OkHttpStoredHistoryTransport())))
    suspend fun initialize() {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin.originUrl, "Synthetic"))
        vault.saveAuthCredential(credential())
    }
    fun credential(value: String = "synthetic-old") = AuthCredential("cookie_session", "basic", mapOf("session" to value), origin.originUrl)
    suspend fun scope(id: String = "synthetic-session", profile: String = "default") = repository.captureStoredHistoryScope(
        "synthetic-a", origin, profile, id, 11, 17).getOrThrow()
    fun enqueue(body: String = page(), cookie: Boolean = false, status: Int = 200) {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(status).setBody(body).apply {
            if (cookie) addHeader("Set-Cookie", "session=synthetic-new; Path=/")
        })
    }
    fun assertReadOnly() = runBlocking {
        assertNull(repository.activeSession.value)
        assertTrue(repository.activeMessages.value.isEmpty())
        assertFalse(repository.activeTurnStreaming.value.isStreaming)
        assertNull(vault.getSessionSelection())
        assertNull(repository.getSessionSummaryCache())
        assertTrue(gateway.resumeCalls.isEmpty()); assertTrue(gateway.createCalls.isEmpty())
        assertTrue(gateway.replayCalls.isEmpty()); assertTrue(gateway.watermarkBySession.isEmpty())
        assertEquals(0, diagnostics.snapshot().size)
    }
    override fun close() { server.shutdown() }
    companion object {
        fun row(id: Long = 1, extra: String = "\"content\":\"synthetic-text\"", session: String = "synthetic-session") =
            """{"id":$id,"session_id":"$session","role":"assistant",$extra}"""
        fun page(limit: Int = 120, offset: Int = 0, rows: String = row(), count: Int = 1,
            session: String = "synthetic-session", profile: String = "default", order: String = "latest") =
            """{"session_id":"$session","profile":"$profile","messages":[$rows],"pagination":{"limit":$limit,"offset":$offset,"order":"$order","returned":$count}}"""
    }
}

internal class DeferredHistoryBody(private val delegate: StoredHistoryHttpTransport) : StoredHistoryHttpTransport {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var closed = false
    override fun open(origin: CanonicalOrigin, path: String, profile: String, limit: Int, offset: Int,
        headers: Map<String, String>, timeoutMillis: Long): AuthenticatedHttpCall<StoredHistoryResponse> {
        val pending = delegate.open(origin, path, profile, limit, offset, headers, timeoutMillis)
        return object : AuthenticatedHttpCall<StoredHistoryResponse> {
            override fun cancel() = pending.cancel()
            override suspend fun awaitHeaders(): StoredHistoryResponse {
                val response = pending.awaitHeaders()
                return object : StoredHistoryResponse by response {
                    override suspend fun readBoundedBody(): ByteArray {
                        entered.complete(Unit); release.await(); return response.readBoundedBody()
                    }
                    override fun close() { closed = true; response.close() }
                }
            }
        }
    }
}
