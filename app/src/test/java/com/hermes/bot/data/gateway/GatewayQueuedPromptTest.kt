package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.JsonValue
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayQueuedPromptTest {
    @Test fun queuedPromptChecksExactOriginAndSocketGenerationAndCarriesNoEditFields() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        val requests = mutableListOf<JsonValue>()
        transport.setRpcHandler("prompt.submit") { id, params ->
            requests += params; transport.respondSuccess(id, """{"status":"streaming","user_row_id":21}""")
        }
        val generation = adapter.connectionGeneration
        assertTrue(adapter.submitPromptScoped("synthetic-runtime", "Synthetic prompt", origin, generation - 1) is GatewayResult.Failure)
        assertTrue(adapter.submitPromptScoped("synthetic-runtime", "Synthetic prompt", CanonicalOrigin.parse("http://other.example.test:8000").getOrThrow(), generation) is GatewayResult.Failure)
        assertTrue(requests.isEmpty())
        val admitted = adapter.submitPromptScoped("synthetic-runtime", "Synthetic prompt", origin, generation) as GatewayResult.Success
        assertEquals(21L, admitted.value.userRowId)
        assertEquals("synthetic-runtime", requests.single()["session_id"]?.asStringOrNull())
        assertEquals("Synthetic prompt", requests.single()["text"]?.asStringOrNull())
        assertNull(requests.single()["truncate_before_row_id"])
        assertNull(requests.single()["confirm_truncate"])
        adapter.disconnect()
    }

    @Test fun unconfirmedScopedAcknowledgementStaysUnknown() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("prompt.submit") { id, _ -> transport.respondSuccess(id, "{}") }
        val result = adapter.submitPromptScoped("synthetic-runtime", "Synthetic prompt", origin, adapter.connectionGeneration) as GatewayResult.Failure
        assertTrue(result.failure is GatewayFailure.SubmissionUnknown)
        assertEquals(1, transport.sentFrames.count { it.contains("prompt.submit") })
        adapter.disconnect()
    }
}
