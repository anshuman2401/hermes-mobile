package io.github.anshuman2401.hermesmobile.data.insights

import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.ContextProfile
import io.github.anshuman2401.hermesmobile.data.context.ContextProfilesResult
import io.github.anshuman2401.hermesmobile.data.context.FakeContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.context.RosterSource
import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class HostInsightsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private suspend fun fixture(): Fixture = Fixture(temp.newFolder()).also { it.prepare() }
    @Test fun exactDefaultProfileAndLogFilterWithUnchangedContent() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("""{"file":"gateway","lines":["  synthetic line\t  ","", "synthetic\ncontinuation"]}"""))
            val result = f.repo.logs(f.scope(), HostLogFile.GATEWAY, HostLogLevel.WARNING) as InsightsRead.Ready
            assertEquals(listOf("  synthetic line\t  ", "", "synthetic\ncontinuation"), result.value.lines)
            val request = f.request()
            assertEquals("GET", request.method)
            assertEquals("/api/logs?profile=default&file=gateway&lines=200&level=WARNING", request.path)
            assertEquals("session=synthetic-cookie", request.getHeader("Cookie"))
            assertFalse(result.toString().contains("synthetic"))
            assertFalse(result.value.toString().contains("synthetic"))
        }
    }
    @Test fun healthSectionsAreIndependentAndSystemRequestIsMachineWide() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(404))
            f.server.enqueue(MockResponse().setBody("""{"hermes_version":"synthetic","uptime_seconds":90000,"memory":{"used":1024}}"""))
            val result = (f.repo.health(f.scope()) as InsightsRead.Ready).value
            assertEquals(InsightsFailure.UNSUPPORTED, (result.status as InsightsRead.Failed).reason)
            val metrics = (result.system as InsightsRead.Ready).value.metrics
            assertEquals(90000.0, metrics.first().value)
            assertNull(metrics.first { it.label == "CPU" }.value)
            assertEquals("/api/status?profile=default", f.request().path)
            assertEquals("/api/system/stats", f.request().path)
        }
    }
    @Test fun statusDiscardsPrivateExtraFieldsAndDoesNotGuessGatewayState() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("""{"version":"1.0","gateway_running":false,"gateway_state":"unrecognized","hostname":"synthetic-private","config_path":"synthetic-path","active_sessions":3}"""))
            f.server.enqueue(MockResponse().setResponseCode(404))
            val health = (f.repo.health(f.scope()) as InsightsRead.Ready).value
            val status = (health.status as InsightsRead.Ready).value
            assertEquals("Stopped", status.gateway)
            assertEquals(3.0, status.sessions)
            assertFalse(status.toString().contains("synthetic-private"))
        }
    }
    @Test fun analyticsMissingOptionalSectionsAndNullCountersStayUnavailable() = runBlocking {
        fixture().use { f ->
            f.vault.saveSelectedContextProfile("work")
            f.server.enqueue(MockResponse().setBody(usage(7)))
            val result = (f.repo.usage(f.scope(), 7) as InsightsRead.Ready).value
            assertEquals("/api/analytics/usage?profile=work&days=7", f.request().path)
            assertNull(result.totals.first { it.label == "Input tokens" }.value)
            assertEquals(0.0, result.totals.first { it.label == "Sessions" }.value)
            assertTrue(result.tasks.isEmpty()); assertTrue(result.skills.isEmpty()); assertTrue(result.tools.isEmpty())
        }
    }
    @Test fun analyticsIncludesTaskSkillToolBreakdownsAndSeparateCosts() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("""{"period_days":30,"totals":{"total_estimated_cost":2.5,"total_actual_cost":1.25},"daily":[{"day":"2026-10-01","input_tokens":10,"output_tokens":5}],"by_model":[{"model":"synthetic-model","input_tokens":10,"output_tokens":5}],"by_task":[{"task":"vision","input_tokens":2,"output_tokens":3,"estimated_cost":0.01}],"skills":{"top_skills":[{"skill":"synthetic-skill","total_count":3}]},"tools":[{"tool":"synthetic-tool","count":4}]}"""))
            val result = (f.repo.usage(f.scope(), 30) as InsightsRead.Ready).value
            assertEquals(15.0, result.models.single().count)
            assertEquals(5.0, result.tasks.single().count)
            assertEquals(3.0, result.skills.single().count); assertEquals(4.0, result.tools.single().count)
            assertEquals(2.5, result.totals.first { it.label == "Estimated cost" }.value)
            assertEquals(1.25, result.totals.first { it.label == "Actual cost" }.value)
        }
    }
    @Test fun analyticsRosterAndUsageDoNotChangeSelectedContextProfile() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("""{"active":"default"}"""))
            f.server.enqueue(MockResponse().setBody("""{"profiles":["work","current","default","work"]}"""))
            val roster = (f.repo.analyticsRoster() as InsightsRead.Ready).value
            assertEquals(listOf("work", "default"), roster.profiles)
            f.server.enqueue(MockResponse().setBody(usage(30)))
            assertTrue(f.repo.analyticsUsage(roster, "work", 30) is InsightsRead.Ready)
            assertEquals("/api/profiles/active", f.request().path)
            assertEquals("/api/profiles", f.request().path)
            assertEquals("/api/analytics/usage?profile=work&days=30", f.request().path)
            assertEquals("default", f.vault.getSelectedContextProfile())
            assertEquals(InsightsFailure.STALE, (f.repo.analyticsUsage(roster, "unknown", 30) as InsightsRead.Failed).reason)
        }
    }
    @Test fun analyticsRosterCannotBeUsedAfterConnectionChanges() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("""{"active":"default"}"""))
            f.server.enqueue(MockResponse().setBody("""{"profiles":["default","work"]}"""))
            val roster = (f.repo.analyticsRoster() as InsightsRead.Ready).value
            f.vault.saveConnectionProfile(ConnectionProfile("other-local", f.origin.originUrl, "Synthetic other"))
            f.vault.setActiveProfileId("other-local")
            assertEquals(InsightsFailure.STALE, (f.repo.analyticsUsage(roster, "work", 30) as InsightsRead.Failed).reason)
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun cachedRosterIsNotOfferedForAnalyticsRequests() = runBlocking {
        fixture().use { f ->
            val profiles = FakeContextProfileRepository().apply {
                profilesResult = ContextProfilesResult.Success(listOf(ContextProfile("default")), "default", RosterSource.CACHED)
            }
            val repo = DefaultHostInsightsRepository(f.vault, f.auth, f.gateway, profiles = profiles)
            assertEquals(InsightsFailure.UNAVAILABLE, (repo.analyticsRoster() as InsightsRead.Failed).reason)
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun changedProfileAndGatewayGenerationPreventDispatch() = runBlocking {
        fixture().use { f ->
            val scope = f.scope()
            f.vault.saveSelectedContextProfile("other")
            assertEquals(InsightsFailure.STALE, (f.repo.logs(scope, HostLogFile.AGENT, HostLogLevel.ALL) as InsightsRead.Failed).reason)
            f.vault.saveSelectedContextProfile("default")
            f.gateway.disconnect()
            assertFalse(f.repo.isCurrent(scope))
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun changedConnectionAndCredentialInvalidateScope() = runBlocking {
        fixture().use { f ->
            val scope = f.scope()
            f.vault.saveConnectionProfile(ConnectionProfile("other-local", f.origin.originUrl, "Synthetic other"))
            f.vault.setActiveProfileId("other-local")
            assertFalse(f.repo.isCurrent(scope))
            f.vault.setActiveProfileId("synthetic-local")
            f.vault.clearAuthCredential()
            assertFalse(f.repo.isCurrent(scope))
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun lateResponseCannotPublishAfterProfileSwitch() = runBlocking {
        fixture().use { f ->
            val transport = HostInsightsTransport { origin, path, headers ->
                val call = OkHttpHostInsightsTransport().open(origin, path, headers)
                object : io.github.anshuman2401.hermesmobile.data.auth.AuthenticatedHttpCall<InsightsResponse> {
                    override fun cancel() = call.cancel()
                    override suspend fun awaitHeaders(): InsightsResponse {
                        val response = call.awaitHeaders()
                        f.vault.saveSelectedContextProfile("other")
                        return response
                    }
                }
            }
            val repo = DefaultHostInsightsRepository(f.vault, f.auth, f.gateway, HostInsightsAdapter(f.auth, transport))
            f.server.enqueue(MockResponse().setBody("""{"file":"agent","lines":["synthetic"]}"""))
            val result = repo.logs(f.scope(), HostLogFile.AGENT, HostLogLevel.ALL)
            assertEquals(InsightsFailure.STALE, (result as InsightsRead.Failed).reason)
        }
    }
    @Test fun redirectIsTerminalAndNeverContactsOtherOrigin() = runBlocking {
        fixture().use { f ->
            MockWebServer().use { other ->
                other.start()
                f.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/redirect")))
                val result = f.repo.logs(f.scope(), HostLogFile.AGENT, HostLogLevel.ALL)
                assertTrue(result is InsightsRead.Failed)
                assertEquals(1, f.server.requestCount); assertEquals(0, other.requestCount)
            }
        }
    }
    @Test fun errorsAndMalformedBodiesNeverSurfaceResponseText() = runBlocking {
        fixture().use { f ->
            val scope = f.scope()
            f.server.enqueue(MockResponse().setResponseCode(500).setBody("synthetic private error"))
            val result = f.repo.logs(scope, HostLogFile.AGENT, HostLogLevel.ALL)
            assertEquals(InsightsFailure.UNAVAILABLE, (result as InsightsRead.Failed).reason)
            assertFalse(result.toString().contains("synthetic"))
            f.server.enqueue(MockResponse().setBody("""{"file":"agent","lines":[12]}"""))
            assertEquals(InsightsFailure.MALFORMED, (f.repo.logs(scope, HostLogFile.AGENT, HostLogLevel.ALL) as InsightsRead.Failed).reason)
        }
    }
    @Test fun expiredAuthAndUnsupportedEndpointsAreDistinct() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(InsightsFailure.AUTH, (f.repo.logs(f.scope(), HostLogFile.AGENT, HostLogLevel.ALL) as InsightsRead.Failed).reason)
        }
        fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(InsightsFailure.UNSUPPORTED, (f.repo.logs(f.scope(), HostLogFile.AGENT, HostLogLevel.ALL) as InsightsRead.Failed).reason)
        }
    }
    @Test fun overlargeLogResponseFailsWithoutPartialContent() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("x".repeat(OkHttpHostInsightsTransport.MAX_BYTES + 1)))
            assertTrue(f.repo.logs(f.scope(), HostLogFile.AGENT, HostLogLevel.ALL) is InsightsRead.Failed)
        }
    }
    @Test fun malformedAnalyticsPeriodDoesNotMasqueradeAsSelectedPeriod() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody(usage(7)))
            assertEquals(InsightsFailure.MALFORMED, (f.repo.usage(f.scope(), 90) as InsightsRead.Failed).reason)
        }
    }
    @Test fun refreshedCookieReceiptIsUsedForTheSecondHealthRead() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setHeader("Set-Cookie", "session=synthetic-rotated; Path=/; HttpOnly")
                .setBody("""{"version":"1.0","gateway_running":true}"""))
            f.server.enqueue(MockResponse().setBody("""{"hermes_version":"1.0","cpu_count":4}"""))
            val scope = f.scope()
            assertTrue(f.repo.health(scope) is InsightsRead.Ready)
            assertEquals("session=synthetic-cookie", f.request().getHeader("Cookie"))
            assertEquals("session=synthetic-rotated", f.request().getHeader("Cookie"))
            assertTrue(f.repo.isCurrent(scope))
        }
    }
    @Test fun reservedCurrentProfileNeverFallsBackToProcessScope() = runBlocking {
        fixture().use { f ->
            f.vault.saveSelectedContextProfile("current")
            assertTrue(f.repo.capture() is InsightsRead.Failed)
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun noCredentialCaptureDoesNotDispatchRequests() = runBlocking {
        fixture().use { f ->
            f.vault.clearAuthCredential()
            assertEquals(InsightsFailure.AUTH, (f.repo.capture() as InsightsRead.Failed).reason)
            assertEquals(0, f.server.requestCount)
        }
    }
    @Test fun cancelledBodyReadClosesTheTransportWithoutDeliveringContent() = runBlocking {
        fixture().use { f ->
            f.server.enqueue(MockResponse().setBody("synthetic").setBodyDelay(500, TimeUnit.MILLISECONDS))
            val call = OkHttpHostInsightsTransport().open(f.origin, "/api/logs?profile=default&file=agent&lines=200", emptyMap())
            val response = kotlinx.coroutines.withTimeout(5000) { call.awaitHeaders() }
            val read = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { response.body() }
            read.cancel()
            kotlinx.coroutines.withTimeout(5000) { read.join() }
            assertTrue(read.isCancelled)
            response.close()
        }
    }
    private fun usage(days: Int) = """{"period_days":$days,"totals":{"total_sessions":0,"total_input":null},"daily":[],"by_model":[]}"""
    private class Fixture(folder: java.io.File) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gateway = DefaultGatewayProtocolAdapter(FakeGatewayTransport(), dispatcher = Dispatchers.Unconfined, readyTimeoutMs = 1000, rpcTimeoutMs = 1000)
        val repo = DefaultHostInsightsRepository(vault, auth, gateway,
            profiles = DefaultContextProfileRepository(vault, OkHttpAuthTransport()))
        suspend fun prepare() {
            vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
            vault.setActiveProfileId("synthetic-local")
            vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic-cookie"), origin.originUrl))
            vault.saveSelectedContextProfile("default")
            gateway.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30))
        }
        suspend fun scope() = (repo.capture() as InsightsRead.Ready).value
        fun request() = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Synthetic request missing")
        override fun close() { gateway.disconnect(); server.shutdown() }
    }
}
