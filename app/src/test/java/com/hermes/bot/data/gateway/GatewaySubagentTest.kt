package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.subagent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySubagentTest {
    @Test fun nativeEventsAreTypedAndRedactedWithoutGenericProgressDuplication() {
        val adapter=DefaultGatewayProtocolAdapter(FakeGatewayTransport())
        for (type in SubagentWireDecoder.eventTypes) {
            val event=adapter.parseEventForTest("""{"type":"$type","session_id":"runtime","payload":{"subagent_id":"child","goal":"Synthetic task","text":"password=synthetic-value"}}""") as GatewayEvent.SubagentUpdate
            assertTrue(event.requiresSessionId)
            assertEquals("child",event.child!!.id)
            assertFalse(event.child.activity.joinToString().contains("synthetic-value"))
            assertFalse(event.toString().contains("runtime"))
        }
    }
    @Test fun scopedRpcResponsesAreStrictAndControlTimeoutIsSingleSendUnknown() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler),rpcTimeoutMs=100)
        val origin=CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch {adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30))};advanceUntilIdle()
        transport.setRpcHandler("subagent.list") {id,params ->
            assertEquals("runtime",params["session_id"]?.asStringOrNull())
            transport.respondSuccess(id,"""{"subagents":[{"subagent_id":"child","goal":"Synthetic task","status":"running"}],"delegations":[]}""")
        }
        assertEquals("child",adapter.listSubagents("runtime",origin,adapter.connectionGeneration).getOrNull()!!.children.single().id)
        transport.setRpcHandler("subagent.steer") {id,_ ->transport.respondSuccess(id,"""{"subagent_id":"child","status":"queued","text":"Synthetic"}""")}
        assertEquals(SubagentControl.QUEUED,adapter.controlSubagent("runtime","child","Synthetic",origin,adapter.connectionGeneration))
        transport.setRpcHandler("subagent.interrupt") {_,_ ->}
        assertEquals(SubagentControl.UNKNOWN,adapter.controlSubagent("runtime","child",null,origin,adapter.connectionGeneration))
        assertEquals(1,transport.sentFrames.count {it.contains("subagent.interrupt")})
        val sent=transport.sentFrames.size
        assertTrue(adapter.listSubagents("runtime",origin,-1) is GatewayResult.Failure)
        assertEquals(SubagentControl.UNAVAILABLE,adapter.controlSubagent("runtime","child","Synthetic",origin,-1))
        assertEquals(sent,transport.sentFrames.size)
    }
}
