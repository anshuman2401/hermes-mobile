package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.DefaultAuthRepository
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.gateway.DefaultGatewayProtocolAdapter
import com.hermes.bot.data.gateway.GatewayTicket
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.AuthCredential as VaultAuthCredential
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BrainMainSettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun validSelectedProfileWithoutConnectedGatewayIsUnavailableNotInvalidSelection() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")

            val result = fixture.repository.load()

            assertTrue(result is BrainMainSettingsLoadResult.Unavailable)
            val failure = (result as BrainMainSettingsLoadResult.Unavailable).failure
            assertTrue(
                failure.reason == BrainMainSettingsFailureReason.UNAVAILABLE ||
                    failure.reason == BrainMainSettingsFailureReason.STALE
            )
            assertEquals(0, fixture.server.requestCount)

            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.gateway.disconnect()
            val stale = fixture.repository.load()
            assertTrue(stale is BrainMainSettingsLoadResult.Unavailable)
            val staleFailure = (stale as BrainMainSettingsLoadResult.Unavailable).failure
            assertTrue(
                staleFailure.reason == BrainMainSettingsFailureReason.UNAVAILABLE ||
                    staleFailure.reason == BrainMainSettingsFailureReason.STALE
            )
            assertEquals(0, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun namedProfileApplyUsesExactSparseBodiesAndReadback() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available
            fixture.nextRequest()
            fixture.nextRequest()

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            fixture.installConfigHandler("high")
            val result = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "high")
            )

            assertTrue(result is BrainMainSettingsApplyResult.Saved)
            val main = fixture.nextRequest()
            assertEquals("POST", main.method)
            assertEquals("/api/model/set", main.requestUrl?.encodedPath)
            assertEquals("work", main.requestUrl?.queryParameter("profile"))
            val mainBody = jsonBody(main)
            assertEquals(setOf("scope", "provider", "model"), mainBody.map.keys)
            assertEquals("main", mainBody["scope"]?.asStringOrNull())
            assertEquals("provider-b", mainBody["provider"]?.asStringOrNull())
            assertEquals("model-b", mainBody["model"]?.asStringOrNull())

            val reasoning = fixture.nextRequest()
            assertEquals("PUT", reasoning.method)
            assertEquals("/api/config", reasoning.requestUrl?.encodedPath)
            assertEquals("work", reasoning.requestUrl?.queryParameter("profile"))
            val reasoningBody = jsonBody(reasoning)
            assertEquals(setOf("config"), reasoningBody.map.keys)
            val config = reasoningBody["config"] as JsonObject
            assertEquals(setOf("agent"), config.map.keys)
            val agent = config["agent"] as JsonObject
            assertEquals(setOf("reasoning_effort"), agent.map.keys)
            assertEquals("high", agent["reasoning_effort"]?.asStringOrNull())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun defaultProfileWarningRequiresOneUseConfirmationAndResendsFrozenBody() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("default")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available
            fixture.nextRequest()
            fixture.nextRequest()

            fixture.enqueue(MockResponse().setBody("{\"ok\":false,\"confirm_required\":true,\"message\":\"Synthetic warning\"}"))
            val warning = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "medium")
            ) as BrainMainSettingsApplyResult.ConfirmationRequired
            assertEquals(loaded.snapshot.snapshotId, warning.snapshotId)
            assertEquals("Synthetic warning", warning.warning)
            assertEquals(3, fixture.server.requestCount)
            val initial = fixture.nextRequest()
            assertEquals("POST", initial.method)
            assertEquals("default", initial.requestUrl?.queryParameter("profile"))
            val initialBody = jsonBody(initial)
            assertEquals(setOf("scope", "provider", "model"), initialBody.map.keys)
            assertEquals("main", initialBody["scope"]?.asStringOrNull())
            assertEquals("provider-b", initialBody["provider"]?.asStringOrNull())
            assertEquals("model-b", initialBody["model"]?.asStringOrNull())

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            val result = fixture.repository.confirmMainWarning(warning.snapshotId)
            assertTrue(result is BrainMainSettingsApplyResult.Saved)
            val acknowledged = fixture.nextRequest()
            assertEquals("POST", acknowledged.method)
            val confirmed = jsonBody(acknowledged)
            assertEquals(
                setOf("scope", "provider", "model", "confirm_expensive_model"),
                confirmed.map.keys
            )
            assertEquals("provider-b", confirmed["provider"]?.asStringOrNull())
            assertEquals("model-b", confirmed["model"]?.asStringOrNull())
            assertEquals(true, confirmed["confirm_expensive_model"]?.asBooleanOrNull())
            assertEquals("default", acknowledged.requestUrl?.queryParameter("profile"))
            assertEquals(6, fixture.server.requestCount)
            val repeated = fixture.repository.confirmMainWarning(warning.snapshotId)
            assertTrue(repeated !is BrainMainSettingsApplyResult.Saved)
            assertEquals(6, fixture.server.requestCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun readbackReplyAfterProfileAndAuthOwnerChangeIsNotPublished() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available
            fixture.nextRequest()
            fixture.nextRequest()

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            fixture.gatewayTransport.setRpcHandler("config.get") { id, _ ->
                runBlocking {
                    fixture.vault.saveSelectedContextProfile("other")
                    fixture.vault.saveAuthCredential(
                        VaultAuthCredential(
                            "cookie_session",
                            "basic",
                            mapOf("session" to "synthetic-owner-b"),
                            fixture.origin.originUrl
                        )
                    )
                }
                fixture.gatewayTransport.respondSuccess(id, "{\"value\":\"high\",\"display\":\"show\"}")
            }
            val result = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "high")
            )

            assertTrue(result is BrainMainSettingsApplyResult.Failed)
            assertTrue(result !is BrainMainSettingsApplyResult.Saved)
            assertTrue(result !is BrainMainSettingsApplyResult.Partial)
            assertEquals(
                BrainMainSettingsFailureReason.READBACK_UNCONFIRMED,
                (result as BrainMainSettingsApplyResult.Failed).failure.reason
            )
            assertEquals(6, fixture.server.requestCount)
            repeat(4) {
                assertEquals("work", fixture.nextRequest().requestUrl?.queryParameter("profile"))
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unknownHostEffortIsRetainedUntilExplicitCanonicalChoice() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("vendor-custom")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available
            fixture.nextRequest()
            fixture.nextRequest()

            val noOp = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-a", "model-a", "vendor-custom")
            )
            assertTrue(noOp is BrainMainSettingsApplyResult.Saved)
            assertEquals(2, fixture.server.requestCount)

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            val mainOnly = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "vendor-custom")
            )
            assertTrue(mainOnly is BrainMainSettingsApplyResult.Saved)
            assertEquals(5, fixture.server.requestCount)
            val mainOnlyRequests = listOf(fixture.nextRequest(), fixture.nextRequest(), fixture.nextRequest())
            assertEquals("POST", mainOnlyRequests.first().method)
            assertTrue(mainOnlyRequests.drop(1).all { it.method == "GET" })
            assertTrue(mainOnlyRequests.none { it.method == "PUT" })

            val changedUnknown = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "other-custom")
            )
            assertTrue(changedUnknown is BrainMainSettingsApplyResult.Failed)
            assertEquals(5, fixture.server.requestCount)

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            fixture.installConfigHandler("high")
            val canonical = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "high")
            )
            assertTrue(canonical is BrainMainSettingsApplyResult.Saved)
            assertEquals(8, fixture.server.requestCount)
            val canonicalRequests = listOf(fixture.nextRequest(), fixture.nextRequest(), fixture.nextRequest())
            assertEquals("PUT", canonicalRequests.first().method)
            assertTrue(canonicalRequests.drop(1).all { it.method == "GET" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refusedMainUsesRotatedReceiptAndStopsDependentReasoningWithoutLeakOrReplay() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available
            fixture.nextRequest()
            fixture.nextRequest()

            fixture.enqueue(
                MockResponse()
                    .addHeader("Set-Cookie", "session=synthetic-successor; Path=/")
                    .setBody("{\"ok\":false,\"message\":\"synthetic-private-warning\",\"api_key\":\"synthetic-secret\"}")
            )
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val result = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "medium")
            )

            assertTrue(result is BrainMainSettingsApplyResult.Failed)
            assertEquals(BrainMainSettingsFailureReason.READBACK_UNCONFIRMED,
                (result as BrainMainSettingsApplyResult.Failed).failure.reason)
            assertTrue(!result.toString().contains("synthetic-secret"))
            assertEquals(5, fixture.server.requestCount)
            val requests = listOf(fixture.nextRequest(), fixture.nextRequest(), fixture.nextRequest())
            assertEquals(1, requests.count { it.method == "POST" })
            assertTrue(requests.none { it.method == "PUT" })
            assertTrue(requests.drop(1).all { it.method == "GET" })
            assertEquals("session=synthetic-cookie", requests.first().getHeader("Cookie"))
            assertTrue(requests.drop(1).all {
                it.getHeader("Cookie") == "session=synthetic-successor" &&
                    it.requestUrl?.queryParameter("profile") == "work"
            })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reasoningFailureReportsPartialAfterExactTargetReadback() = runTest(testDispatcher) {
        val fixture = Fixture(temp.newFolder(), testDispatcher)
        try {
            fixture.prepare("work")
            launch { fixture.gateway.connect(fixture.origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }
            advanceUntilIdle()
            fixture.enqueueOptionsAndInfo("provider-a", "model-a")
            fixture.installConfigHandler("medium")
            val loaded = fixture.repository.load() as BrainMainSettingsLoadResult.Available

            fixture.enqueue(MockResponse().setBody("{\"ok\":true}"))
            fixture.enqueue(MockResponse().setResponseCode(500).setBody("{\"ok\":false}"))
            fixture.enqueueOptionsAndInfo("provider-b", "model-b")
            fixture.installConfigHandler("medium")
            val result = fixture.repository.apply(
                loaded.snapshot.snapshotId,
                BrainMainSettingsDraft("provider-b", "model-b", "high")
            )

            val partial = result as BrainMainSettingsApplyResult.Partial
            assertTrue(partial.mainSaved)
            assertTrue(!partial.reasoningSaved)
            assertEquals("provider-b", partial.snapshot.main?.provider)
            assertEquals("model-b", partial.snapshot.main?.model)
            assertEquals("medium", partial.snapshot.reasoningEffort)
        } finally {
            fixture.close()
        }
    }

    private fun jsonBody(request: RecordedRequest): JsonObject =
        GatewayJson.parseOrNull(request.body.readUtf8()) as JsonObject

    private class Fixture(
        folder: java.io.File,
        private val dispatcher: CoroutineDispatcher
    ) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gatewayTransport = FakeGatewayTransport()
        val gateway = DefaultGatewayProtocolAdapter(
            transport = gatewayTransport,
            dispatcher = dispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
        val repository = BrainMainSettingsRepository(vault, auth, gateway)

        suspend fun prepare(profile: String) {
            vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
            vault.setActiveProfileId("synthetic-local")
            vault.saveAuthCredential(
                VaultAuthCredential(
                    "cookie_session",
                    "basic",
                    mapOf("session" to "synthetic-cookie"),
                    origin.originUrl
                )
            )
            vault.saveSelectedContextProfile(profile)
        }

        fun installConfigHandler(effort: String) {
            gatewayTransport.setRpcHandler("config.get") { id, _ ->
                gatewayTransport.respondSuccess(id, "{\"value\":\"$effort\",\"display\":\"show\"}")
            }
        }

        fun enqueueOptionsAndInfo(provider: String, model: String) {
            enqueue(MockResponse().setBody(options(provider, model)))
            enqueue(MockResponse().setBody(info(provider, model)))
        }

        fun enqueue(response: MockResponse) = server.enqueue(response)

        suspend fun nextRequest(): RecordedRequest = withContext(Dispatchers.IO) {
            server.takeRequest(2, TimeUnit.SECONDS) ?: error("synthetic request missing")
        }

        override fun close() {
            gateway.disconnect()
            server.shutdown()
        }

        private fun options(provider: String, model: String) =
            """{"provider":"$provider","model":"$model","providers":[{"slug":"provider-a","authenticated":true,"models":["model-a"],"capabilities":{"model-a":{"reasoning":true}}},{"slug":"provider-b","authenticated":true,"models":["model-b"]}]}"""

        private fun info(provider: String, model: String) =
            """{"provider":"$provider","model":"$model","name":"Synthetic","supports_reasoning":true}"""
    }
}
