package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionModelPickerTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeGatewayTransport()
    private val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher, readyTimeoutMs = 1_000, rpcTimeoutMs = 1_000)
    private suspend fun kotlinx.coroutines.test.TestScope.connect() {
        launch { adapter.connect("https://test.hermes.local", GatewayTicket("test-ticket", 30)) }
        advanceUntilIdle()
    }
    private fun frame(method: String) = transport.sentFrames.filter { it.contains("\"method\":\"$method\"") }

    @Test fun scopedPickerRejectsStaleGenerationAndMalformedAckWithoutRetry() = runTest(dispatcher) {
        connect()
        val origin = com.hermes.bot.data.origin.CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
        assertTrue(adapter.setSessionPickerScoped("runtime-1", null, "high", origin, adapter.connectionGeneration - 1) is GatewayResult.Failure)
        assertTrue(frame("config.set").isEmpty())
        transport.setRpcHandler("config.set") { id, _ -> transport.respondSuccess(id, """{"deferred":"yes"}""") }
        var result: GatewayResult<SessionPickerSetResult>? = null
        launch { result = adapter.setSessionPickerScoped("runtime-1", null, "high", origin, adapter.connectionGeneration) }
        advanceUntilIdle()
        assertTrue((result as GatewayResult.Failure).failure is GatewayFailure.SubmissionUnknown)
        assertEquals(1, frame("config.set").size)
        val sent = frame("config.set").single()
        assertTrue(sent.contains("\"scope\":\"session\""))
        assertTrue(sent.contains("runtime-1"))
    }

    @Test fun `A catalog excludes unauthenticated and unsafe provider and model tokens`() = runTest(dispatcher) {
        connect()
        transport.setRpcHandler("model.options") { id, _ ->
            transport.respondSuccess(id, """{"providers":[{"slug":"openai","authenticated":true,"models":["gpt-5","a --session","gpt/5"]},{"slug":"bad slug","authenticated":true,"models":["good"]},{"slug":"private","authenticated":false,"models":["good"]}]}""")
        }
        var result: GatewayResult<List<SessionModelOption>>? = null
        launch { result = adapter.listSessionModelOptions("runtime-1") }
        advanceUntilIdle()
        assertEquals(listOf(SessionModelOption("openai", "gpt-5"), SessionModelOption("openai", "gpt/5")), (result as GatewayResult.Success).value)
        assertTrue(frame("model.options").single().contains("include_unconfigured"))
    }

    @Test fun `B model request scoped to selected runtime and never global`() = runTest(dispatcher) {
        connect()
        transport.setRpcHandler("config.set") { id, _ -> transport.respondSuccess(id, """{"ok":true}""") }
        var result: GatewayResult<SessionPickerSetResult>? = null
        launch { result = adapter.setSessionModel("runtime-1", SessionModelOption("openai", "gpt-5")) }
        advanceUntilIdle()
        assertTrue(result is GatewayResult.Success)
        val sent = frame("config.set").single()
        assertTrue(sent.contains("runtime-1"))
        assertTrue(sent.contains("--session"))
        assertTrue(sent.contains("--provider openai"))
        assertFalse(sent.contains("prompt.submit"))
        assertEquals(1, frame("config.set").size)
    }

    @Test fun `C reasoning only accepts allowlisted levels and session scope`() = runTest(dispatcher) {
        connect()
        transport.setRpcHandler("config.set") { id, _ -> transport.respondSuccess(id, """{"ok":true}""") }
        assertTrue(adapter.setSessionReasoning("runtime-1", "maximum") is GatewayResult.Failure)
        assertTrue(frame("config.set").isEmpty())
        launch { adapter.setSessionReasoning("runtime-1", "high") }
        advanceUntilIdle()
        val sent = frame("config.set").single()
        assertTrue(sent.contains("\"scope\":\"session\""))
        assertTrue(sent.contains("runtime-1"))
    }

    @Test fun `D deferred echo and confirmation are not applied assertions`() = runTest(dispatcher) {
        connect()
        transport.setRpcHandler("config.set") { id, _ -> transport.respondSuccess(id, """{"ok":true,"deferred":true,"confirm_required":true}""") }
        var result: GatewayResult<SessionPickerSetResult>? = null
        launch { result = adapter.setSessionReasoning("runtime-1", "low") }
        advanceUntilIdle()
        assertEquals(SessionPickerSetResult(deferred = true, confirmRequired = true), (result as GatewayResult.Success).value)
    }

    @Test fun `E unsafe model tokens never dispatch config or prompt`() = runTest(dispatcher) {
        connect()
        for (model in listOf("--other", "a b", "a\nboom", "")) {
            assertTrue(adapter.setSessionModel("runtime-1", SessionModelOption("openai", model)) is GatewayResult.Failure)
        }
        assertTrue(adapter.setSessionModel("runtime-1", SessionModelOption("bad slug", "good")) is GatewayResult.Failure)
        assertTrue(frame("config.set").isEmpty())
        assertTrue(frame("prompt.submit").isEmpty())
    }

    @Test fun `I failed application preserves failure rather than applied state`() = runTest(dispatcher) {
        connect()
        transport.setRpcHandler("config.set") { id, _ -> transport.respondError(id, 4001, "switch rejected") }
        var result: GatewayResult<SessionPickerSetResult>? = null
        launch { result = adapter.setSessionReasoning("runtime-1", "low") }
        advanceUntilIdle()
        assertTrue(result is GatewayResult.Failure)
        assertTrue(frame("prompt.submit").isEmpty())
    }
}
