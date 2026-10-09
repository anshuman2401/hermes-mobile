package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.DefaultAuthRepository
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BrainModelSettingsReaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun hostShapedCatalogAndInfoDecodeOptionalMetadataAndCurrentOnlyMain() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"synthetic-current-provider","model":"synthetic-current-only","providers":[""" +
                    """{"slug":"synthetic-current-provider","authenticated":true,"models":["synthetic-current-model","synthetic-alt"],"capabilities":{"synthetic-current-model":{"reasoning":true,"can_disable_reasoning":false},"synthetic-alt":{"reasoning":false}}},""" +
                    """{"slug":"synthetic-unauthenticated","authenticated":false,"models":["synthetic-hidden"]}],"api_key":"synthetic-secret"}"""
            ))
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"synthetic-current-provider","model":"synthetic-current-only","name":"Synthetic Current","slug":"synthetic-current-only","supports_reasoning":true,"reasoning_effort":"synthetic-secret"}"""
            ))

            val result = fixture.reader().read()
            val settings = (result as BrainModelReadResult.Available).settings
            assertEquals("default", settings.selectedProfile)
            assertEquals("synthetic-current-provider", settings.main?.provider)
            assertEquals("synthetic-current-only", settings.main?.model)
            assertEquals(true, settings.main?.metadata?.supportsReasoning)
            assertEquals(false, settings.providers[0].capabilities["synthetic-current-model"]?.canDisableReasoning)
            assertEquals(null, settings.providers[0].capabilities["synthetic-alt"]?.canDisableReasoning)
            assertEquals(false, settings.providers[1].authenticated)
            assertFalse(result.toString().contains("synthetic-secret"))
        }
    }

    @Test
    fun explicitDefaultAndNamedRoutesAndUnavailableSelectionMakeZeroFactories() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(optionsJson()))
            fixture.server.enqueue(MockResponse().setBody(infoJson()))
            assertTrue(fixture.reader().read() is BrainModelReadResult.Available)
            fixture.select("named_profile")
            fixture.server.enqueue(MockResponse().setBody(optionsJson()))
            fixture.server.enqueue(MockResponse().setBody(infoJson()))
            assertTrue(fixture.reader().read() is BrainModelReadResult.Available)
            val first = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            val second = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            val third = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            val fourth = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("profile=default", first?.requestUrl?.encodedQuery)
            assertEquals("profile=default", second?.requestUrl?.encodedQuery)
            assertEquals("profile=named_profile", third?.requestUrl?.encodedQuery)
            assertEquals("profile=named_profile", fourth?.requestUrl?.encodedQuery)
            assertTrue(listOf(first, second, third, fourth).all { it?.method == "GET" })
            val requestsBeforeUnavailable = fixture.server.requestCount

            val transport = CountingTransport()
            val reader = { BrainModelSettingsReader(fixture.vault, fixture.auth, transport) }
            for (selection in listOf("current", "Current", "root", "name with space", "name\n")) {
                fixture.select(selection)
                val result = reader().read()
                assertTrue("selection $selection must be unavailable", result is BrainModelReadResult.Unavailable)
            }
            fixture.clearSelection()
            assertTrue(reader().read() is BrainModelReadResult.Unavailable)
            assertEquals(0, transport.opens)
            assertEquals(requestsBeforeUnavailable, fixture.server.requestCount)

            val malformedVault = object : VaultStorage by fixture.vault {
                override suspend fun readSelectedContextProfileAuthoritative() = SelectedContextReadResult.Unreadable
            }
            assertTrue(BrainModelSettingsReader(malformedVault, fixture.auth, transport).read() is BrainModelReadResult.Unavailable)
            assertEquals(0, transport.opens)
        }
    }

    @Test
    fun capturedSharedAuthRejectsWrongOwnerAndCarriesAcceptedSuccessor() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            val captured = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gatedVault = object : VaultStorage by fixture.vault {
                var leaseReads = 0
                override suspend fun readAuthCredentialLease(origin: String): AuthCredentialLease? {
                    val lease = fixture.vault.readAuthCredentialLease(origin)
                    if (leaseReads++ == 0) {
                        captured.complete(Unit)
                        release.await()
                    }
                    return lease
                }
            }
            var opens = 0
            val noFactory = object : BrainModelReadTransport {
                override fun open(origin: CanonicalOrigin, path: String, headers: Map<String, String>, timeoutMillis: Long) :
                    com.hermes.bot.data.auth.AuthenticatedHttpCall<BrainModelReadResponse> {
                    opens++
                    error("factory must not run after captured owner changes")
                }
            }
            val staleRead = async(start = CoroutineStart.UNDISPATCHED) {
                BrainModelSettingsReader(gatedVault, fixture.auth, noFactory).read()
            }
            captured.await()
            fixture.vault.saveAuthCredential(fixture.credential("synthetic-new-owner"))
            release.complete(Unit)
            assertEquals(BrainModelReadFailure.STALE, (staleRead.await() as BrainModelReadResult.Unavailable).reason)
            assertEquals(0, opens)
        }

        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().addHeader("Set-Cookie", "session=synthetic-successor; Path=/")
                .setBody(optionsJson()))
            val body = DeferredBodyTransport(OkHttpBrainModelReadTransport())
            val read = async { BrainModelSettingsReader(fixture.vault, fixture.auth, body).read() }
            withTimeout(5_000) { body.entered.await() }
            fixture.server.enqueue(MockResponse().setBody("{\"ticket\":\"synthetic-ticket\",\"ttl\":30}"))
            assertTrue(fixture.auth.mintWebSocketTicket(fixture.origin).isSuccess)
            fixture.server.enqueue(MockResponse().setBody(infoJson()))
            val first = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            val second = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals("session=synthetic-successor", second?.getHeader("Cookie"))
            body.release.complete(Unit)
            assertTrue(read.await() is BrainModelReadResult.Available)
        }
    }

    @Test
    fun fixedFailuresAndActualCancellationCloseSingleOwner() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            for (response in listOf(
                MockResponse().setBody("{synthetic-malformed"),
                MockResponse().setBody("{\"provider\":false,\"model\":\"synthetic-model\",\"providers\":[]}"),
                MockResponse().setResponseCode(302).addHeader("Location", "/synthetic-redirect"),
                MockResponse().setBody("x".repeat(OkHttpBrainModelReadTransport.MAX_BODY_BYTES + 1))
            )) {
                fixture.server.enqueue(response)
                val result = fixture.reader().read()
                assertTrue(result is BrainModelReadResult.Unavailable)
                assertFalse(result.toString().contains("synthetic-malformed"))
            }
            fixture.server.enqueue(MockResponse().setBody(optionsJson()))
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"synthetic-other-provider","model":"synthetic-model","supports_reasoning":true}"""
            ))
            assertEquals(BrainModelReadFailure.MALFORMED,
                (fixture.reader().read() as BrainModelReadResult.Unavailable).reason)
        }

        val callStarted = CompletableDeferred<Call>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { callStarted.complete(call) }
        }).build()
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody("partial").setHeader("Content-Length", "100000")
                .setSocketPolicy(SocketPolicy.KEEP_OPEN))
            val read = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.reader(OkHttpBrainModelReadTransport(client)).read()
            }
            val call = withTimeout(5_000) { callStarted.await() }
            read.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertTrue(read.isCancelled)
        }
    }

    @Test
    fun ordinaryScopeOrConnectionChangeAndCancellationRejectAggregate() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(optionsJson()))
            fixture.server.enqueue(MockResponse().setBody(infoJson()))
            val body = DeferredBodyTransport(OkHttpBrainModelReadTransport())
            val read = async { BrainModelSettingsReader(fixture.vault, fixture.auth, body).read() }
            withTimeout(5_000) { body.entered.await() }
            fixture.vault.saveSelectedContextProfile("other")
            body.release.complete(Unit)
            assertEquals(BrainModelReadFailure.STALE, (read.await() as BrainModelReadResult.Unavailable).reason)
            assertEquals(1, fixture.server.requestCount)
        }

        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(optionsJson()).setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val read = async(start = CoroutineStart.UNDISPATCHED) { fixture.reader().read() }
            val request = withContext(Dispatchers.IO) { fixture.server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(request)
            read.cancelAndJoin()
            assertTrue(read.isCancelled)
        }
    }

    @Test
    fun projectionDropsSyntheticSecretsAndContainsOnlyAllowlistedTypes() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"synthetic-provider","model":"synthetic-model","providers":[{"slug":"synthetic-provider","models":["synthetic-model"],"authenticated":true,"capabilities":{"synthetic-model":{"reasoning":false,"can_disable_reasoning":true,"api_key":"synthetic-secret"}}}],"base_url":"synthetic-secret","key_env":"synthetic-secret","warning":"synthetic-secret","custom_config":{"token":"synthetic-secret"}}"""
            ))
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"synthetic-provider","model":"synthetic-model","name":"Synthetic","supports_reasoning":false,"rawbody":"synthetic-secret"}"""
            ))
            val result = fixture.reader().read()
            assertTrue(result is BrainModelReadResult.Available)
            assertFalse(result.toString().contains("synthetic-secret"))
            assertFalse(result.toString().contains("base_url"))
            assertFalse(result.toString().contains("rawbody"))
        }
    }

    @Test
    fun providerEndpointProjectionIsPresenceOnlyAndDiscardsUrlValues() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("default")
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"","model":"","providers":[{"slug":"synthetic-with-endpoint","models":["synthetic-model"],"authenticated":true,"api_url":"synthetic-private-url"},{"slug":"synthetic-without-endpoint","models":["synthetic-model"],"authenticated":true},{"slug":"synthetic-malformed-endpoint","models":["synthetic-model"],"authenticated":true,"api_url":{"secret":"synthetic-private-url"}}]}"""
            ))
            fixture.server.enqueue(MockResponse().setBody(
                """{"provider":"","model":""}"""
            ))

            val settings = (fixture.reader().read() as BrainModelReadResult.Available).settings
            assertEquals(true, settings.providers[0].requiresEndpointPassthrough)
            assertEquals(false, settings.providers[1].requiresEndpointPassthrough)
            assertEquals(true, settings.providers[2].requiresEndpointPassthrough)
            assertFalse(settings.toString().contains("synthetic-private-url"))
        }
    }

    private fun Fixture.reader(transport: BrainModelReadTransport = OkHttpBrainModelReadTransport()) =
        BrainModelSettingsReader(vault, auth, transport)

    private class Fixture(folder: java.io.File) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth: AuthRepository = DefaultAuthRepository(vault, OkHttpAuthTransport())

        init {
            runBlocking {
                vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
                vault.setActiveProfileId("synthetic-local")
                vault.saveAuthCredential(credential())
            }
        }

        fun credential(cookie: String = "synthetic-cookie") =
            AuthCredential("cookie_session", "basic", mapOf("session" to cookie), origin.originUrl)

        fun select(profile: String) = runBlocking { vault.saveSelectedContextProfile(profile) }
        fun clearSelection() = runBlocking { vault.clearSelectedContextProfile() }
        override fun close() { server.shutdown() }
    }

    private class CountingTransport : BrainModelReadTransport {
        var opens = 0
        override fun open(origin: CanonicalOrigin, path: String, headers: Map<String, String>, timeoutMillis: Long):
            com.hermes.bot.data.auth.AuthenticatedHttpCall<BrainModelReadResponse> {
            opens++
            error("unexpected transport factory")
        }
    }

    private class DeferredBodyTransport(private val delegate: BrainModelReadTransport) : BrainModelReadTransport {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override fun open(origin: CanonicalOrigin, path: String, headers: Map<String, String>, timeoutMillis: Long) =
            object : com.hermes.bot.data.auth.AuthenticatedHttpCall<BrainModelReadResponse> {
                private val pending = delegate.open(origin, path, headers, timeoutMillis)
                override fun cancel() = pending.cancel()
                override suspend fun awaitHeaders(): BrainModelReadResponse {
                    val response = pending.awaitHeaders()
                    return object : BrainModelReadResponse by response {
                        override suspend fun readBoundedBody(): ByteArray {
                            entered.complete(Unit)
                            release.await()
                            return response.readBoundedBody()
                        }
                        override fun close() = response.close()
                    }
                }
            }
    }

    private fun optionsJson() =
        """{"provider":"synthetic-provider","model":"synthetic-model","providers":[{"slug":"synthetic-provider","models":["synthetic-model"],"authenticated":true}]}"""

    private fun infoJson() =
        """{"provider":"synthetic-provider","model":"synthetic-model","name":"Synthetic","supports_reasoning":false}"""
}
