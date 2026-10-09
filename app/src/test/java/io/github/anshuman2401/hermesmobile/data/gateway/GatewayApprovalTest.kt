package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.approval.*
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.*
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayApprovalTest {
    private val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
    private fun params(queue: String = "queue-a", runtime: String = "runtime-a", choices: String = "[\"once\",\"session\",\"always\",\"deny\"]") =
        """{"session_id":"$runtime","request_id":"$queue","command":"synthetic command","choices":$choices}"""
    private fun frame(id: String = "server-a", queue: String = "queue-a", runtime: String = "runtime-a") =
        """{"jsonrpc":"2.0","id":"$id","method":"approval","params":${params(queue,runtime)}}"""
    @Test fun choicesRestrictionsBoundsAndRedactedRepresentations() {
        fun decode(body: String) = ApprovalWireDecoder.request(GatewayJson.parse(body) as JsonObject)
        val request = decode(params())!!
        assertEquals(4,request.choices.size)
        assertFalse(request.toString().contains("synthetic command"))
        assertEquals(setOf(ApprovalChoice.ONCE,ApprovalChoice.DENY), decode(params().dropLast(1)+",\"smart_denied\":true}")!!.choices)
        assertFalse(ApprovalChoice.ALWAYS in decode(params().dropLast(1)+",\"allow_permanent\":false}")!!.choices)
        assertNull(decode(params(choices="[\"future\"]")))
        assertNull(decode(params().replace("synthetic command","x".repeat(16385))))
        assertNull(decode(params().replace("queue-a","")))
        assertNull(ApprovalWireDecoder.pending(GatewayJson.parse("""{"approvals":[${params()},${params()}]}"""),"runtime-a"))
        assertNull(ApprovalWireDecoder.pending(GatewayJson.parse("""{"approvals":[${params(runtime="other")}]}"""),"runtime-a"))
    }
    @Test fun handshakeLiveReplyQueueIdentityAndDuplicateDelivery() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30)) };advanceUntilIdle()
        assertTrue(adapter.approvalInbox.value.available)
        assertEquals(1,transport.sentFrames.count { it.contains("client.capabilities") })
        transport.emitFrame(frame());transport.emitFrame(frame());runCurrent()
        assertEquals(1,adapter.approvalInbox.value.requests.size)
        val request=adapter.approvalInbox.value.requests.single()
        assertEquals("queue-a",request.requestId);assertEquals("server-a",request.serverRequestId)
        assertEquals(ApprovalOutcome.SENT,adapter.answerApproval(request,ApprovalChoice.ONCE,origin,adapter.connectionGeneration))
        val response=GatewayJson.parse(transport.sentFrames.last()) as JsonObject
        assertEquals("server-a",response["id"]?.asStringOrNull());assertNull(response["method"])
        assertEquals("once",response["result"]?.get("choice")?.asStringOrNull())
        assertTrue(transport.sentFrames.none { it.contains("approval.respond") })
        adapter.disconnect();assertTrue(adapter.approvalInbox.value.requests.isEmpty())
    }
    @Test fun serverRequestNeverConsumesClientRpcAndUnsupportedKindsFailExplicitly() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30)) };advanceUntilIdle()
        transport.setRpcHandler("session.list") { id,_ -> transport.emitFrame(frame(id));transport.respondSuccess(id,"""{"sessions":[]}""") }
        assertTrue(adapter.listSessions() is GatewayResult.Success);runCurrent()
        transport.emitFrame("""{"jsonrpc":"2.0","id":"unsupported","method":"secret","params":{}}""");runCurrent()
        val result=GatewayJson.parse(transport.sentFrames.last()) as JsonObject
        assertEquals(-32601,result["error"]?.get("code")?.asIntOrNull())
        assertTrue(adapter.approvalInbox.value.requests.isNotEmpty())
    }
    @Test fun cancellationIsExactAndSnapshotsCannotReviveCancelledRequests() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30)) };advanceUntilIdle()
        transport.emitFrame(frame());runCurrent()
        transport.setRpcHandler("approval.pending") { id,_ ->
            transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"runtime-a","payload":{"id":"server-a","reason":"timeout"}}}""")
            transport.respondSuccess(id,"""{"approvals":[${params()}]}""")
        }
        assertEquals(false,(adapter.refreshApprovals("runtime-a",origin,adapter.connectionGeneration) as GatewayResult.Success).value)
        assertTrue(adapter.approvalInbox.value.requests.isEmpty())
        assertEquals("timeout",adapter.approvalInbox.value.cancelled["runtime-a"])
    }
    @Test fun fallbackCarriesExactIdentityAndForeignOriginOrGenerationCannotWrite() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler))
        launch { adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30)) };advanceUntilIdle()
        transport.setRpcHandler("approval.pending") { id,_ -> transport.respondSuccess(id,"""{"approvals":[${params()}]}""") }
        adapter.refreshApprovals("runtime-a",origin,adapter.connectionGeneration)
        val request=adapter.approvalInbox.value.requests.single()
        val before=transport.sentFrames.size
        assertEquals(ApprovalOutcome.UNAVAILABLE,adapter.answerApproval(request,ApprovalChoice.ONCE,origin,-1))
        assertEquals(ApprovalOutcome.UNAVAILABLE,adapter.answerApproval(request,ApprovalChoice.ONCE,
            CanonicalOrigin.parse("http://other.test:8000").getOrThrow(),adapter.connectionGeneration))
        assertEquals(before,transport.sentFrames.size)
        transport.setRpcHandler("approval.respond") { id,p ->
            assertEquals("runtime-a",p["session_id"]?.asStringOrNull());assertEquals("queue-a",p["request_id"]?.asStringOrNull())
            assertEquals("session",p["choice"]?.asStringOrNull());assertEquals(false,p["all"]?.asBooleanOrNull())
            transport.respondSuccess(id,"""{"resolved":1}""")
        }
        assertEquals(ApprovalOutcome.SENT,adapter.answerApproval(request,ApprovalChoice.SESSION,origin,adapter.connectionGeneration))
    }
    @Test fun resumeRestoresLiveResponseHandleAndCapabilityFailureKeepsOrdinaryChat() = runTest {
        val transport=FakeGatewayTransport();val adapter=DefaultGatewayProtocolAdapter(transport,dispatcher=StandardTestDispatcher(testScheduler))
        transport.setRpcHandler("client.capabilities") { id,_ -> transport.respondError(id,-32601,"Synthetic unsupported") }
        launch { assertTrue(adapter.connect(origin.originUrl,GatewayTicket("synthetic-ticket",30))) };advanceUntilIdle()
        assertFalse(adapter.approvalInbox.value.available)
        transport.setRpcHandler("session.resume") { id,_ -> transport.respondSuccess(id,
            """{"session_id":"runtime-a","messages":[],"open_requests":[{"id":"server-a","method":"approval","params":${params()}}]}""") }
        adapter.resumeSession("stored-a")
        assertEquals("server-a",adapter.approvalInbox.value.requests.single().serverRequestId)
    }
}
