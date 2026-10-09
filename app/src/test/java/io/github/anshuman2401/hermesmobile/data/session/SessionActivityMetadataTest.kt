package io.github.anshuman2401.hermesmobile.data.session

import io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTicket
import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal val fractionalActivityResponse = """{"sessions":[
    {"id":"first","started_at":200.0,"last_active":1000.125},
    {"id":"second","started_at":100.0,"last_active":2000.875}
]}"""

// Synthetic wire rows, shared by the actual gateway, scoped and encrypted-cache readers.
internal val invalidActivityResponse = """{"sessions":[
    {"id":"missing","started_at":42.5},
    {"id":"null","started_at":42.5,"last_active":null},
    {"id":"malformed","started_at":42.5,"last_active":"invalid"},
    {"id":"boolean","started_at":42.5,"last_active":true},
    {"id":"object","started_at":42.5,"last_active":{}},
    {"id":"array","started_at":42.5,"last_active":[]},
    {"id":"nan","started_at":42.5,"last_active":"NaN"},
    {"id":"infinity","started_at":42.5,"last_active":"Infinity"},
    {"id":"negative-infinity","started_at":42.5,"last_active":"-Infinity"},
    {"id":"overflow","started_at":42.5,"last_active":1e309},
    {"id":"negative-overflow","started_at":42.5,"last_active":-1e309}
]}"""

@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivityMetadataTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun gatewayList_retainsFractionalActivityWithoutReordering() = runTest(dispatcher) {
        val rows = fetch(fractionalActivityResponse)

        assertEquals(listOf("first", "second"), rows.map { it.id })
        assertEquals(listOf(200.0, 100.0), rows.map { it.startedAt })
        assertEquals(listOf(1000.125, 2000.875), rows.map { it.lastActive })
    }

    @Test
    fun gatewayList_missingNullMalformedAndNonfiniteActivityRemainUnknown() = runTest(dispatcher) {
        val rows = fetch(invalidActivityResponse)
        assertEquals(11, rows.size)
        rows.forEach {
            assertEquals(42.5, it.startedAt!!, 0.0)
            assertNull(it.lastActive)
        }
        // Existing six-positional callers continue to represent unknown activity.
        assertNull(SessionSummary("legacy", null, null, 42.5, 0, null).lastActive)
    }

    private suspend fun fetch(response: String): List<SessionSummary> {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher)
        try {
            assertTrue(adapter.connect("http://activity.invalid:9119", GatewayTicket("synthetic-ticket", 30)))
            transport.setRpcHandler("session.list") { id, _ -> transport.respondSuccess(id, response) }
            val result = adapter.listSessions()
            assertTrue(result is GatewayResult.Success)
            return (result as GatewayResult.Success).value.sessions
        } finally {
            adapter.disconnect()
        }
    }
}
