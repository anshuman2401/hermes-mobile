package com.hermes.bot.data.update

import com.hermes.bot.data.auth.DefaultAuthRepository
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.gateway.DefaultGatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.insights.DefaultHostInsightsRepository
import com.hermes.bot.data.insights.InsightsRead
import com.hermes.bot.data.insights.InsightsScope
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit

class HostUpdateRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(transport: HostUpdateTransport? = null) = Fixture(temp.newFolder(), transport)
    private val available = """{"install_method":"git","current_version":"1.0","behind":2,"update_available":true,"can_apply":true,"commits":[{"summary":"Synthetic change"},{"summary":"Second synthetic change"}]}"""

    @Test fun checkUsesExactAuthenticatedOriginAndBoundedHostFields() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody(available))
            val checked = (f.repo.check(f.scope()) as HostUpdateResult.Ready).value
            assertEquals("1.0", checked.installedVersion)
            assertEquals(2, checked.behind)
            assertTrue(checked.appUpdateEligible)
            assertEquals(listOf("Synthetic change", "Second synthetic change"), checked.changes)
            val request = f.request()
            assertEquals("GET", request.method)
            assertEquals("/api/hermes/update/check?force=true&profile=default", request.path)
            assertEquals("session=synthetic-cookie", request.getHeader("Cookie"))
            assertFalse(checked.toString().contains("Synthetic change"))
        }
    }

    @Test fun explicitStartRechecksEligibilityAndWritesExactlyOnce() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody(available))
            f.server.enqueue(MockResponse().setBody("""{"ok":true,"action_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""))
            val started = (f.repo.start(f.scope()) as HostUpdateResult.Ready).value
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", started.actionId)
            assertEquals("GET", f.request().method)
            val post = f.request()
            assertEquals("POST", post.method)
            assertEquals("/api/hermes/update", post.path)
            assertEquals("session=synthetic-cookie", post.getHeader("Cookie"))
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun externallyManagedOrUncertainCheckCannotWrite() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody(available.replace("\"git\"", "\"apt\"").replace("\"can_apply\":true", "\"can_apply\":false")))
            assertEquals(HostUpdateFailure.NOT_ELIGIBLE, (f.repo.start(f.scope()) as HostUpdateResult.Failed).reason)
            assertEquals("GET", f.request().method)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun profileChangePreventsBothReadAndWrite() = runBlocking {
        fixture().use { f ->
            f.prepare()
            val scope = f.scope()
            f.vault.saveSelectedContextProfile("other")
            assertEquals(HostUpdateFailure.STALE, (f.repo.check(scope) as HostUpdateResult.Failed).reason)
            assertEquals(HostUpdateFailure.STALE, (f.repo.start(scope) as HostUpdateResult.Failed).reason)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun ambiguousPostDoesNotRetryAndReturnsUnknown() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody(available))
            f.server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val result = f.repo.start(f.scope())
            assertEquals(HostUpdateFailure.UNKNOWN, (result as HostUpdateResult.Failed).reason)
            assertEquals("GET", f.request().method)
            assertEquals("POST", f.request().method)
            assertEquals(2, f.server.requestCount)
        }
    }

    @Test fun redirectIsRejectedWithoutContactingTarget() = runBlocking {
        fixture().use { f ->
            f.prepare()
            MockWebServer().use { target ->
                target.start()
                f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/other")))
                val result = f.repo.check(f.scope())
                assertEquals(HostUpdateFailure.ORIGIN, (result as HostUpdateResult.Failed).reason)
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test fun statusUsesOnlyStructuredOutcomeAndRejectsLargeBody() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody("""{"name":"hermes-update","running":false,"action_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","receipt":{"outcome":"success"},"lines":["synthetic private log"]}"""))
            val status = (f.repo.status(f.scope()) as HostUpdateResult.Ready).value
            assertEquals("success", status.outcome)
            assertFalse(status.toString().contains("synthetic private log"))
            assertEquals("/api/actions/hermes-update/status?lines=1", f.request().path)
            f.server.enqueue(MockResponse().setBody("x".repeat(OkHttpHostUpdateTransport.MAX_BYTES + 1)))
            assertEquals(HostUpdateFailure.UNAVAILABLE, (f.repo.status(f.scope()) as HostUpdateResult.Failed).reason)
        }
    }

    @Test fun interruptedReceiptIsAValidHostStatus() = runBlocking {
        fixture().use { f ->
            f.prepare()
            f.server.enqueue(MockResponse().setBody("""{"name":"hermes-update","running":false,"receipt":{"outcome":"interrupted"}}"""))
            val status = (f.repo.status(f.scope()) as HostUpdateResult.Ready).value
            assertFalse(status.running)
            assertEquals("interrupted", status.outcome)
            assertNull(status.actionId)
        }
    }

    @Test fun invalidStructuredResponsesAreNotConnectionFailures() = runBlocking {
        fixture().use { f ->
            f.prepare()
            for (body in listOf("{}", """{"name":"hermes-update","running":false,"receipt":{"outcome":"unrecognized"}}""", "not-json")) {
                f.server.enqueue(MockResponse().setBody(body))
                assertEquals(HostUpdateFailure.MALFORMED, (f.repo.status(f.scope()) as HostUpdateResult.Failed).reason)
            }
            f.server.enqueue(MockResponse().setBody(available.replace("\"can_apply\":true", "\"can_apply\":\"invalid\"")))
            assertEquals(HostUpdateFailure.MALFORMED, (f.repo.check(f.scope()) as HostUpdateResult.Failed).reason)
            f.server.enqueue(MockResponse().setBody(available))
            f.server.enqueue(MockResponse().setBody("{}"))
            assertEquals(HostUpdateFailure.UNKNOWN, (f.repo.start(f.scope()) as HostUpdateResult.Failed).reason)
            f.server.enqueue(MockResponse().setBody(available))
            f.server.enqueue(MockResponse().setBody("""{"ok":false}"""))
            assertEquals(HostUpdateFailure.REFUSED, (f.repo.start(f.scope()) as HostUpdateResult.Failed).reason)
        }
    }

    @Test fun statusHeaderAndBodyTimeoutsAreReportedWithoutConnectionFailure() = runBlocking {
        for (failure in listOf(SocketTimeoutException(), InterruptedIOException())) {
            for (beforeHeaders in listOf(true, false)) {
                assertStatusFailure(failure, beforeHeaders, HostUpdateFailure.TIMEOUT)
            }
        }
    }

    @Test fun statusHeaderAndBodyConnectionFailuresAreNotTimeouts() = runBlocking {
        for (beforeHeaders in listOf(true, false)) {
            assertStatusFailure(IOException(), beforeHeaders, HostUpdateFailure.UNAVAILABLE)
        }
    }

    @Test fun timedOutStartRemainsUnknownAndNeverRetries() = runBlocking {
        for (beforeHeaders in listOf(true, false)) {
            val requests = Collections.synchronizedList(mutableListOf<Request>())
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                if (request.method == "GET") {
                    syntheticResponse(request, available.toResponseBody())
                } else {
                    failingResponse(request, SocketTimeoutException(), beforeHeaders)
                }
            }.build()
            fixture(OkHttpHostUpdateTransport(client)).use { f ->
                f.prepare()
                assertEquals(HostUpdateFailure.UNKNOWN, (f.repo.start(f.scope()) as HostUpdateResult.Failed).reason)
                assertEquals(listOf("GET", "POST"), requests.map { it.method })
                assertEquals(listOf("/api/hermes/update/check", "/api/hermes/update"), requests.map { it.url.encodedPath })
                assertTrue(requests.all { it.header("Cookie") == "session=synthetic-cookie" })
            }
        }
    }

    private suspend fun assertStatusFailure(failure: IOException, beforeHeaders: Boolean, expected: HostUpdateFailure) {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            failingResponse(request, failure, beforeHeaders)
        }.build()
        fixture(OkHttpHostUpdateTransport(client)).use { f ->
            f.prepare()
            assertEquals("${failure.javaClass.simpleName}, beforeHeaders=$beforeHeaders",
                expected, (f.repo.status(f.scope()) as HostUpdateResult.Failed).reason)
            assertEquals(1, requests.size)
            assertEquals("GET", requests.single().method)
            assertEquals("/api/actions/hermes-update/status", requests.single().url.encodedPath)
            assertEquals("lines=1", requests.single().url.encodedQuery)
            assertEquals("session=synthetic-cookie", requests.single().header("Cookie"))
        }
    }

    // Exercise the real OkHttp callback and body-read failure paths without racing
    // worker scheduling against short wall-clock socket/server delays.
    private fun failingResponse(request: Request, failure: IOException, beforeHeaders: Boolean): Response {
        if (beforeHeaders) throw failure
        val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure
        }.buffer()
        return syntheticResponse(request, object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        })
    }

    private fun syntheticResponse(request: Request, body: ResponseBody): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic").body(body).build()

    private class Fixture(folder: java.io.File, transport: HostUpdateTransport?) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gateway = DefaultGatewayProtocolAdapter(FakeGatewayTransport(), dispatcher = Dispatchers.Unconfined,
            readyTimeoutMs = 1000, rpcTimeoutMs = 1000)
        private val owners = DefaultHostInsightsRepository(vault, auth, gateway)
        val repo = DefaultHostUpdateRepository(owners, HostUpdateAdapter(auth, transport ?: OkHttpHostUpdateTransport()))
        suspend fun prepare() {
            vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
            vault.setActiveProfileId("synthetic-local")
            vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
            vault.saveSelectedContextProfile("default")
            gateway.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30))
        }
        suspend fun scope(): InsightsScope = (repo.capture() as HostUpdateResult.Ready).value
        fun request() = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Synthetic request missing")
        override fun close() { gateway.disconnect(); server.shutdown() }
    }
}
