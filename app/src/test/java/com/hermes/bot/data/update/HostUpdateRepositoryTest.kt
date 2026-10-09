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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class HostUpdateRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = Fixture(temp.newFolder())
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

    private class Fixture(folder: java.io.File) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gateway = DefaultGatewayProtocolAdapter(FakeGatewayTransport(), dispatcher = Dispatchers.Unconfined,
            readyTimeoutMs = 1000, rpcTimeoutMs = 1000)
        private val owners = DefaultHostInsightsRepository(vault, auth, gateway)
        val repo = DefaultHostUpdateRepository(owners, HostUpdateAdapter(auth))
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
