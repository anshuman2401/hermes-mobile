package com.hermes.bot.data.cron

import com.hermes.bot.data.auth.AuthHttpTransport
import com.hermes.bot.data.auth.HttpResponse
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.local.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CronHttpAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var vault: EncryptedVault
    private val origin = "http://example.test:9119"
    private val jobJson = """{"id":"synthetic-job","profile":"alpha","name":"Synthetic job","prompt":"Synthetic instructions","schedule":{"kind":"cron","expr":"0 9 * * *"},"schedule_display":"Daily","enabled":true,"last_status":"ok","hermes_home":"REDACTED_PATH"}"""
    private fun job() = CronJob("synthetic-job", "alpha", "Synthetic job", "Synthetic instructions", "0 9 * * *", "Daily", true, null, "ok")
    private class Transport : AuthHttpTransport {
        data class Request(val method: String, val url: String, val body: String?)
        val requests = mutableListOf<Request>()
        var response = HttpResponse(200, body = "{}")
        var hook: (suspend () -> Unit)? = null
        var writeHook: (suspend () -> Unit)? = null
        var runResponse: HttpResponse? = null
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            requests += Request(method, url, body)
            hook?.invoke()
            if (method != "GET") writeHook?.invoke()
            return if (url.contains("/runs?")) runResponse ?: response else response
        }
    }

    @Before fun prepare() = runBlocking {
        // Explicit software test key seam; does not emulate Android Keystore.
        vault = EncryptedVault(temporary.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        seed(origin)
    }
    private suspend fun seed(endpoint: String) {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection", endpoint, "Synthetic server"))
        vault.setActiveProfileId("synthetic-connection")
        vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password", mapOf("session" to "SYNTHETIC_COOKIE"), endpoint))
    }

    private suspend fun adapter(transport: Transport): CronHttpAdapter {
        val response = transport.response
        val hook = transport.hook
        transport.response = HttpResponse(200, body = "[$jobJson]")
        transport.hook = null
        val adapter = CronHttpAdapter(vault, transport)
        adapter.list()
        transport.requests.clear()
        transport.response = response
        transport.hook = hook
        return adapter
    }

    @Test fun listIsHostWideAndReturnsOnlyTypedFields() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = "[$jobJson]") }
        val result = CronHttpAdapter(vault, transport).list() as CronResult.Success
        assertEquals(1, result.value.size)
        assertEquals("alpha", result.value.single().profile)
        assertEquals("0 9 * * *", result.value.single().schedule)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("$origin/api/cron/jobs?profile=all", transport.requests.single().url)
    }

    @Test fun createUsesExplicitOwnerAndLocalDeliveryAndUpdatePreservesOtherFields() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = jobJson) }
        val adapter = adapter(transport)
        assertTrue(adapter.save(null, "alpha", "Synthetic", "Synthetic instructions", "0 9 * * *") is CronResult.Success)
        val create = transport.requests.single()
        assertEquals("POST", create.method)
        assertTrue(create.url.endsWith("?profile=alpha"))
        assertEquals("local", GatewayJson.parse(create.body!!)["deliver"]?.asStringOrNull())
        assertTrue(adapter.save(job(), "alpha", "Renamed synthetic", job().prompt, job().schedule) is CronResult.Success)
        val update = transport.requests.last()
        assertEquals("PUT", update.method)
        val fields = GatewayJson.parse(update.body!!)["updates"]!!.asObjectOrNull()!!.map
        assertEquals(setOf("name"), fields.keys)
        val before = transport.requests.size
        adapter.save(job(), "beta", "Synthetic", "Synthetic instructions", "0 9 * * *")
        assertEquals(before, transport.requests.size)
    }

    @Test fun actionsAndHistoryCarryJobOwnerAndDoNotUseActiveWorkspace() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = jobJson) }
        val adapter = adapter(transport)
        adapter.act(job(), CronAction.PAUSE)
        adapter.act(job(), CronAction.RESUME)
        adapter.act(job(), CronAction.DELETE)
        transport.runResponse = HttpResponse(200, body = """{"runs":[{"started_at":123.0,"is_active":false,"preview":"REDACTED_CONTENT"}]}""")
        val history = adapter.runs(job()) as CronResult.Success
        assertEquals(123.0, history.value.single().startedAt!!, 0.0)
        assertEquals(listOf("GET", "POST", "GET", "POST", "GET", "DELETE", "GET", "GET"), transport.requests.map { it.method })
        assertTrue(transport.requests.all { it.url.contains("profile=alpha") })
    }

    @Test fun unauthorizedUnsupportedMalformedAndRedirectResponsesFailClosed() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = jobJson) }
        val adapter = adapter(transport)
        for ((code, reason) in listOf(401 to CronFailure.AUTH_REQUIRED, 404 to CronFailure.UNSUPPORTED, 302 to CronFailure.UNAVAILABLE)) {
            transport.response = HttpResponse(code, body = "REDACTED_ERROR")
            assertEquals(reason, (adapter.list() as CronResult.Failure).reason)
        }
        transport.response = HttpResponse(200, body = """[{"id":"unknown"}]""")
        assertEquals(CronFailure.INVALID, (adapter.list() as CronResult.Failure).reason)
    }

    @Test fun ambiguousMutationMakesOneWriteAndCannotReturnRawError() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = jobJson); writeHook = { throw java.io.IOException("REDACTED_ERROR") } }
        val result = adapter(transport).act(job(), CronAction.PAUSE)
        assertEquals(CronFailure.UNKNOWN, (result as CronResult.Failure).reason)
        assertEquals(1, transport.requests.count { it.method != "GET" })
    }

    @Test fun malformedMutationAcknowledgementAndDuplicateRowsFailClosed() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = "{}") }
        val adapter = adapter(transport)
        assertEquals(CronFailure.UNKNOWN, (adapter.save(null, "alpha", "Synthetic", "Synthetic instructions", "0 9 * * *") as CronResult.Failure).reason)
        transport.response = HttpResponse(200, body = "[$jobJson,$jobJson]")
        assertEquals(CronFailure.INVALID, (adapter.list() as CronResult.Failure).reason)
    }

    @Test fun ownerFallbackAndChangedCredentialPreventAnyMutation() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = jobJson.replace("alpha", "beta")) }
        val adapter = adapter(transport)
        assertEquals(CronFailure.STALE, (adapter.act(job(), CronAction.DELETE) as CronResult.Failure).reason)
        assertEquals(CronFailure.STALE, (adapter.runs(job()) as CronResult.Failure).reason)
        assertTrue(transport.requests.all { it.method == "GET" })
        transport.requests.clear()
        vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password", mapOf("session" to "OTHER_SYNTHETIC_COOKIE"), origin))
        assertEquals(CronFailure.STALE, (adapter.act(job(), CronAction.PAUSE) as CronResult.Failure).reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun changedConnectionOriginRequiresAuthAndNeverSendsCredentials() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = "[$jobJson]") }
        vault.saveConnectionProfile(ConnectionProfile("synthetic-connection", "http://other.example.test:9119", "Synthetic server"))
        assertNull(vault.getAuthCredential("synthetic-connection"))
        assertEquals(CronFailure.AUTH_REQUIRED, (CronHttpAdapter(vault, transport).list() as CronResult.Failure).reason)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun credentialReplacementInvalidatesSuccessfulRead() = runBlocking {
        val transport = Transport().apply {
            response = HttpResponse(200, body = "[$jobJson]")
            hook = { vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password", mapOf("session" to "OTHER_SYNTHETIC_COOKIE"), origin)) }
        }
        assertEquals(CronFailure.STALE, (CronHttpAdapter(vault, transport).list() as CronResult.Failure).reason)
    }

    @Test fun productionTransportRejectsRedirectAndRetryAfterWithoutSecondRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            seed(server.url("/").toString().trimEnd('/'))
            val adapter = CronHttpAdapter(vault, cronHttpTransport())
            server.enqueue(MockResponse().setResponseCode(200).setBody("[$jobJson]"))
            adapter.list()
            server.enqueue(MockResponse().setResponseCode(200).setBody(jobJson))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/different"))
            assertEquals(CronFailure.UNKNOWN, (adapter.act(job(), CronAction.PAUSE) as CronResult.Failure).reason)
            assertEquals(3, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(200).setBody(jobJson))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            assertEquals(CronFailure.UNKNOWN, (adapter.act(job(), CronAction.PAUSE) as CronResult.Failure).reason)
            assertEquals(5, server.requestCount)
        }
    }
}
