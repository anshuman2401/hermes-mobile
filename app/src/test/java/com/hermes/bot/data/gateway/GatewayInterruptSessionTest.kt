package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T1: `session.interrupt` request shape, correlation, and typed outcomes.
 *
 * Acceptance row: "interruptSession sends session.interrupt with the live
 * session id and reports delivered/failed".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayInterruptSessionTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter

    private val validTicket = GatewayTicket("safe-test-ticket-xyz", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    @Test
    fun interruptSession_sendsCorrelatedSessionInterrupt_withLiveSessionId() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        var capturedSessionId: String? = null
        transport.setRpcHandler("session.interrupt") { id, params ->
            capturedSessionId = params["session_id"]?.asStringOrNull()
            transport.respondSuccess(id, """{"ok":true}""")
        }

        val result = adapter.interruptSession("runtime-live-1")

        // Delivered: correlated ack resolves the shared sendRpc path to Success.
        assertTrue("Expected acked interrupt, got $result", result is GatewayResult.Success)
        assertTrue((result as GatewayResult.Success).value.ok)
        // Request addresses the live session id in params.
        assertEquals("runtime-live-1", capturedSessionId)
        // Wire frame carries the method and the same live session id; the ack
        // only correlates by the frame's own id (respondSuccess echoes it), so
        // a mismatched id could never have produced the Success above.
        val frame = transport.sentFrames.last { it.contains("session.interrupt") }
        assertTrue(frame.contains(""""method":"session.interrupt""""))
        assertTrue(frame.contains(""""session_id":"runtime-live-1""""))
    }

    @Test
    fun interruptSession_jsonRpcError_surfacesTypedRpcFailure() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        transport.setRpcHandler("session.interrupt") { id, _ ->
            transport.respondError(id, 4009, "no active turn")
        }

        val result = adapter.interruptSession("runtime-live-1")

        assertTrue("Expected typed failure, got $result", result is GatewayResult.Failure)
        val failure = (result as GatewayResult.Failure).failure
        assertTrue("Expected GatewayFailure.Rpc, got $failure", failure is GatewayFailure.Rpc)
        assertEquals(4009, (failure as GatewayFailure.Rpc).code)
        assertEquals("no active turn", failure.message)
    }

    @Test
    fun interruptSession_ackOkFalseAndMissingOk_surfacedNotFabricated() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        // Explicit ok:false is preserved verbatim, never overwritten to true.
        transport.setRpcHandler("session.interrupt") { id, _ ->
            transport.respondSuccess(id, """{"ok":false}""")
        }
        val declined = adapter.interruptSession("runtime-live-1")
        assertTrue(declined is GatewayResult.Success)
        assertFalse((declined as GatewayResult.Success).value.ok)

        // A correlated ack without an ok field proves delivery; ok defaults
        // to true (documented default), not fabricated host confirmation.
        transport.setRpcHandler("session.interrupt") { id, _ ->
            transport.respondSuccess(id, """{}""")
        }
        val bareAck = adapter.interruptSession("runtime-live-1")
        assertTrue(bareAck is GatewayResult.Success)
        assertTrue((bareAck as GatewayResult.Success).value.ok)
    }

    @Test
    fun interruptSession_notConnected_failsWithoutWritingFrame() = runTest(testDispatcher) {
        // No connect() — the shared sendRpc connected/capability gate must
        // reject before any frame reaches the transport.
        val result = adapter.interruptSession("runtime-live-1")

        assertTrue(result is GatewayResult.Failure)
        assertTrue(
            "Expected NotConnected, got ${(result as GatewayResult.Failure).failure}",
            result.failure is GatewayFailure.NotConnected
        )
        assertTrue(transport.sentFrames.none { it.contains("session.interrupt") })
    }
}
