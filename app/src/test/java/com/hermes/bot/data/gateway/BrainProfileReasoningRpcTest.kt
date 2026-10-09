package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrainProfileReasoningRpcTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun explicitDefaultUsesLiteralProfileAndKeyOnlyParams() = runTest(dispatcher) {
        val transport = FakeGatewayTransport()
        val adapter = adapter(transport)
        connect(adapter)
        var observed: JsonObject? = null
        transport.setRpcHandler("config.get") { id, params ->
            observed = params
            transport.respondSuccess(id, "{\"value\":\"high\",\"display\":\"show\"}")
        }

        val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
        val result = adapter.getProfileReasoningDefault("default", origin, adapter.connectionGeneration)
        advanceUntilIdle()

        assertTrue(result is GatewayResult.Success)
        assertEquals("high", (result as GatewayResult.Success).value.effort)
        assertEquals(setOf("key", "profile"), observed?.map?.keys)
        assertEquals("reasoning", observed?.get("key")?.asStringOrNull())
        assertEquals("default", observed?.get("profile")?.asStringOrNull())
        assertFalse(observed?.map?.containsKey("session_id") == true)
    }

    @Test
    fun wrongOriginAndGenerationRejectBeforeFrame() = runTest(dispatcher) {
        val transport = FakeGatewayTransport()
        val adapter = adapter(transport)
        connect(adapter)
        val originalCount = transport.sentFrames.count { methodOf(it) == "config.get" }
        val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()

        val wrongOrigin = adapter.getProfileReasoningDefault(
            "work",
            CanonicalOrigin.parse("https://other.hermes.local").getOrThrow(),
            adapter.connectionGeneration
        )
        val wrongGeneration = adapter.getProfileReasoningDefault(
            "work",
            origin,
            adapter.connectionGeneration + 1
        )

        assertTrue(wrongOrigin is GatewayResult.Failure)
        assertTrue(wrongGeneration is GatewayResult.Failure)
        assertEquals(originalCount, transport.sentFrames.count { methodOf(it) == "config.get" })
    }

    @Test
    fun cancellationRemovesPendingRequestAndNextReadStillCompletes() = runTest(dispatcher) {
        val transport = FakeGatewayTransport()
        val adapter = adapter(transport, timeoutMillis = 5_000L)
        connect(adapter)
        val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
        transport.setRpcHandler("config.get") { _, _ -> Unit }

        val pending = launch {
            adapter.getProfileReasoningDefault("work", origin, adapter.connectionGeneration)
        }
        runCurrent()
        pending.cancel()
        pending.join()
        assertEquals(0, pendingCount(adapter))

        transport.setRpcHandler("config.get") { id, _ ->
            transport.respondSuccess(id, "{\"value\":\"medium\"}")
        }
        val result = adapter.getProfileReasoningDefault("work", origin, adapter.connectionGeneration)
        advanceUntilIdle()

        assertEquals("medium", (result as GatewayResult.Success).value.effort)
        assertEquals(2, transport.sentFrames.count { methodOf(it) == "config.get" })
    }

    @Test
    fun suspendedScopedSendCannotBeRetargetedByConcurrentConnect() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : GatewayTransport by fake {
            override suspend fun send(text: String): Boolean {
                if (methodOf(text) == "config.get") {
                    entered.complete(Unit)
                    release.await()
                }
                return fake.send(text)
            }
        }
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher, rpcTimeoutMs = 1_000L)
        connect(adapter)
        val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
        fake.setRpcHandler("config.get") { id, _ -> fake.respondSuccess(id, "{\"value\":\"high\"}") }
        val read = launch { adapter.getProfileReasoningDefault("default", origin, adapter.connectionGeneration) }
        entered.await()
        val switch = launch { adapter.connect("https://other.hermes.local", GatewayTicket("synthetic-ticket", 30)) }
        runCurrent()
        assertFalse("connection switch must wait for the scoped send", switch.isCompleted)
        assertEquals("wss://test.hermes.local/api/ws", fake.lastConnectConfig?.url)
        release.complete(Unit)
        read.join()
        switch.join()
        assertEquals(0, pendingCount(adapter))
        adapter.disconnect()
    }

    @Test fun unsupportedTimeoutAndTransportFailurePreserveReasoningResults() = runTest(dispatcher) {
        val fake = FakeGatewayTransport()
        var failSend = false
        val transport = object : GatewayTransport by fake {
            override suspend fun send(text: String): Boolean = if (failSend && methodOf(text) == "config.get") false else fake.send(text)
        }
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher, rpcTimeoutMs = 100)
        connect(adapter)
        val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
        fake.setRpcHandler("config.get") { id, _ -> fake.respondError(id, -32601, "synthetic unsupported") }
        assertTrue(adapter.getProfileReasoningDefault("default", origin, adapter.connectionGeneration) is GatewayResult.Failure)
        fake.setRpcHandler("config.get") { _, _ -> Unit }
        assertTrue(adapter.getProfileReasoningDefault("default", origin, adapter.connectionGeneration) is GatewayResult.Failure)
        failSend = true
        val result = adapter.getProfileReasoningDefault("default", origin, adapter.connectionGeneration) as GatewayResult.Failure
        assertEquals(GatewayFailure.Transport("Profile reasoning transport failure"), result.failure)
        assertEquals(0, pendingCount(adapter))
        adapter.disconnect()
    }

    private fun pendingCount(adapter: DefaultGatewayProtocolAdapter): Int {
        val field = adapter.javaClass.getDeclaredField("pendingRequests").apply { isAccessible = true }
        return (field.get(adapter) as Map<*, *>).size
    }

    private fun adapter(transport: FakeGatewayTransport, timeoutMillis: Long = 1_000L) =
        DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = dispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = timeoutMillis
        )

    private fun kotlinx.coroutines.test.TestScope.connect(adapter: DefaultGatewayProtocolAdapter) {
        launch { adapter.connect("https://test.hermes.local", GatewayTicket("synthetic-ticket", 30)) }
        advanceUntilIdle()
    }

    private fun methodOf(frame: String): String? =
        (GatewayJson.parseOrNull(frame) as? JsonObject)?.get("method")?.asStringOrNull()
}
