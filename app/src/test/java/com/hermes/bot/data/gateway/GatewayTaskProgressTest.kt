package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GatewayTaskProgressTest {
    private val snapshot = """{"todos":[{"id":"a","content":"Synthetic step","status":"in_progress"}],"revision":4}"""
    private val adapter get() = DefaultGatewayProtocolAdapter(FakeGatewayTransport(), Dispatchers.Default)

    @Test fun completeRequiresTaskToolAndActualResultNotArguments() {
        for (name in listOf("todo_list", "todo")) {
            val event = adapter.parseEventForTest("""{"type":"tool.complete","session_id":"synthetic-runtime","payload":{"name":"$name","tool_id":"synthetic-call","result":$snapshot}}""") as GatewayEvent.ToolComplete
            assertEquals(4L, event.taskSnapshot!!.revision)
            val argsOnly = adapter.parseEventForTest("""{"type":"tool.complete","payload":{"name":"$name","args":$snapshot}}""") as GatewayEvent.ToolComplete
            assertNull(argsOnly.taskSnapshot)
        }
        val other = adapter.parseEventForTest("""{"type":"tool.complete","payload":{"name":"other","result":$snapshot}}""") as GatewayEvent.ToolComplete
        assertNull(other.taskSnapshot)
        val failed = adapter.parseEventForTest("""{"type":"tool.complete","payload":{"name":"todo_list","result":$snapshot,"error":true}}""") as GatewayEvent.ToolComplete
        assertNull(failed.taskSnapshot)
        val start = adapter.parseEventForTest("""{"type":"tool.start","payload":{"name":"todo_list","args":$snapshot}}""")
        assertTrue(start is GatewayEvent.ToolStart)
    }

    @Test fun standaloneUpdateIsSessionScopedAndInvalidDataIsUnavailable() {
        val update = adapter.parseEventForTest("""{"type":"todo.updated","session_id":"synthetic-runtime","payload":$snapshot}""") as GatewayEvent.TasksUpdated
        assertEquals("synthetic-runtime", update.sessionId)
        assertTrue(update.requiresSessionId)
        assertEquals(4L, update.snapshot!!.revision)
        assertNull((adapter.parseEventForTest("""{"type":"todo.updated","payload":{"todos":"invalid"}}""") as GatewayEvent.TasksUpdated).snapshot)
    }

    @Test fun resumeReadsOptionalAuthoritativeTodoStateWithRedaction() = runBlocking {
        val transport = FakeGatewayTransport()
        val protocol = DefaultGatewayProtocolAdapter(transport, Dispatchers.Default)
        try {
            assertTrue(withTimeout(5000) { protocol.connect("http://example.test", GatewayTicket("synthetic-ticket")) })
            transport.setRpcHandler("session.resume") { id, _ ->
                transport.respondSuccess(id, """{"session_id":"synthetic-runtime","stored_session_id":"synthetic-stored","running":true,"todo_state":${snapshot.replace("Synthetic step", "password=synthetic-value")}}""")
            }
            val resumed = (withTimeout(5000) { protocol.resumeSession("synthetic-stored") } as GatewayResult.Success).value
            assertEquals(4L, resumed.taskSnapshot!!.revision)
            assertFalse(resumed.taskSnapshot.items[0].content.contains("synthetic-value"))
            transport.setRpcHandler("session.resume") { id, _ -> transport.respondSuccess(id, """{"session_id":"synthetic-runtime"}""") }
            assertNull((protocol.resumeSession("synthetic-stored") as GatewayResult.Success).value.taskSnapshot)
        } finally { protocol.disconnect() }
    }
}
