package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayBotScreenTest {
    private val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
    private val status = """{"supported":true,"installed":true,"running":true,"geometry":"1440x900","lease":{"holder":"agent","epoch":1},"profile_key":"synthetic"}"""

    @Test fun profileScopedStatusObserveAndLeaseUseCapturedSocket() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("display.status") { id, params ->
            assertEquals("research", params["profile"]?.asStringOrNull()); transport.respondSuccess(id, status)
        }
        assertTrue(adapter.screenStatus("research", origin, adapter.connectionGeneration) is GatewayResult.Success)
        transport.setRpcHandler("display.observe") { id, params ->
            assertEquals("research", params["profile"]?.asStringOrNull())
            transport.respondSuccess(id, status.dropLast(1) + """, "ticket":"synthetic-display-ticket","path":"/api/display/ws","viewer_id":"synthetic-viewer-id"}""")
        }
        val observation = adapter.screenObserve("research", origin, adapter.connectionGeneration)
        assertEquals("synthetic-viewer-id", (observation as GatewayResult.Success).value.viewerId)
        transport.setRpcHandler("display.lease.acquire") { id, params ->
            assertEquals("synthetic-viewer-id", params["viewer_id"]?.asStringOrNull())
            transport.respondSuccess(id, """{"lease":{"holder":"human","viewer_hash":"012345abcdef","epoch":2}}""")
        }
        assertTrue(adapter.screenAcquire("research", "synthetic-viewer-id", origin, adapter.connectionGeneration) is GatewayResult.Success)
        val before = transport.sentFrames.size
        assertTrue(adapter.screenAcquire("research", "synthetic-viewer-id", origin, -1) is GatewayResult.Failure)
        assertEquals(before, transport.sentFrames.size)
    }

    @Test fun malformedOrForeignDisplayContractFailsClosed() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("display.observe") { id, _ ->
            transport.respondSuccess(id, status.dropLast(1) + """, "ticket":"synthetic-display-ticket","path":"https://foreign.test/","viewer_id":"synthetic-viewer-id"}""")
        }
        assertTrue(adapter.screenObserve("research", origin, adapter.connectionGeneration) is GatewayResult.Failure)
        assertTrue(adapter.screenStatus(" bad ", origin, adapter.connectionGeneration) is GatewayResult.Failure)
    }

    @Test fun stopIsScopedAndNeverSendsForce() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("display.stop") { id, params ->
            assertEquals("research", params["profile"]?.asStringOrNull())
            assertNull(params["force"])
            transport.respondSuccess(id, """{"supported":true,"installed":true,"running":false,"geometry":"1440x900","lease":{"holder":"agent","epoch":2},"stopped":true}""")
        }
        val stopped = adapter.screenStop("research", origin, adapter.connectionGeneration)
        assertFalse((stopped as GatewayResult.Success).value.running)
        val sent = transport.sentFrames.size
        assertTrue(adapter.screenStop("research", origin, -1) is GatewayResult.Failure)
        assertEquals(sent, transport.sentFrames.size)
    }
}
