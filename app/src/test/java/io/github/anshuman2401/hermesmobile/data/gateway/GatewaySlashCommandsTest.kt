package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.commands.*
import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySlashCommandsTest {
    @Test fun catalogAndCompletionUseExactSessionAndHonorReplacementColumn() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("commands.catalog") { id, params ->
            assertEquals("<runtime>", params["session_id"]?.asStringOrNull())
            transport.respondSuccess(id, """{"pairs":[["/goal","Goal"],["/review","Review"]],"commands":{"/goal":{"argument_mode":"mixed"}},"canon":{"/compact":"/compress"}}""")
        }
        val catalog = (adapter.slashCatalog("<runtime>", origin, adapter.connectionGeneration) as GatewayResult.Success).value
        assertEquals(listOf("Commands", "Skills"), catalog.suggestions.map { it.group })
        transport.setRpcHandler("complete.slash") { id, params ->
            assertEquals("/reasoning hi", params["text"]?.asStringOrNull())
            transport.respondSuccess(id, """{"items":[{"text":"high","meta":"More effort"}],"replace_from":11}""")
        }
        val complete = (adapter.slashComplete("<runtime>", "/reasoning hi", origin, adapter.connectionGeneration) as GatewayResult.Success).value
        assertEquals(11, complete.replaceFrom)
        assertEquals("high", complete.items.single().text)
    }
    @Test fun mutationTimeoutNeverFallsBackButMissingHandlerCan() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler), rpcTimeoutMs = 100)
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        var dispatches = 0
        transport.setRpcHandler("command.dispatch") { id, _ ->
            dispatches++; transport.respondSuccess(id, """{"type":"prefill","message":"Synthetic editable message"}""")
        }
        transport.setRpcHandler("slash.exec") { _, _ -> }
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("undo", ""), origin, adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(0, dispatches)
        transport.setRpcHandler("slash.exec") { id, params ->
            assertEquals("undo", params["command"]?.asStringOrNull())
            transport.respondError(id, -32601, "Method not found")
        }
        val response = adapter.slashExecute("<runtime>", SlashInvocation("undo", ""), origin, adapter.connectionGeneration)
        assertTrue((response as GatewayResult.Success).value is SlashDirective.Prefill)
        assertEquals(1, dispatches)
    }
    @Test fun compressionUsesDedicatedLongBudgetAndStatusUsesStructuredOutput() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler), rpcTimeoutMs = 100)
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        transport.setRpcHandler("session.compress") { id, params ->
            assertEquals("retain decisions", params["focus_topic"]?.asStringOrNull())
            backgroundScope.launch { kotlinx.coroutines.delay(500); transport.respondSuccess(id, """{"compressed":true,"messages":[]}""") }
        }
        val response = adapter.slashExecute("<runtime>", SlashInvocation("compress", "retain decisions"), origin, adapter.connectionGeneration)
        assertTrue((response as GatewayResult.Success).value is SlashDirective.Compressed)
        assertFalse(transport.sentFrames.any { it.contains("slash.exec") })
        transport.setRpcHandler("session.status") { id, _ -> transport.respondSuccess(id, """{"output":"Synthetic ready status"}""") }
        assertEquals("Synthetic ready status", ((adapter.slashExecute("<runtime>", SlashInvocation("status",""), origin,
            adapter.connectionGeneration) as GatewayResult.Success).value as SlashDirective.Output).text)
    }
    @Test fun wrongOriginOrGenerationCannotWrite() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        val before = transport.sentFrames.size
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("goal","synthetic"), origin, -1) is GatewayResult.Failure)
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("goal","synthetic"),
            CanonicalOrigin.parse("http://other.test:8000").getOrThrow(), adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(before, transport.sentFrames.size)
    }
    @Test fun decoderRejectsOversizedAndMalformedDirectives() {
        fun json(text: String) = io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson.parse(text)
        assertNull(SlashWireDecoder.directive(json("""{"type":"send","message":7}""")))
        assertNull(SlashWireDecoder.directive(json("""{"type":"alias"}""")))
        val root = io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject("type" to io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString("send"),
            "message" to io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString("x".repeat(SlashCommandPolicy.MAX_PAYLOAD+1)))
        assertNull(SlashWireDecoder.directive(root))
    }
    @Test fun yoloWritesOnlyExplicitSessionFlagAndRequiresStrictAcknowledgement() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        for ((arg, value) in listOf("on" to "1", "off" to "0")) {
            transport.setRpcHandler("config.set") { id, params ->
                assertEquals("<runtime>", params["session_id"]?.asStringOrNull())
                assertEquals("session", params["scope"]?.asStringOrNull())
                assertEquals("yolo", params["key"]?.asStringOrNull())
                assertEquals(value, params["value"]?.asStringOrNull())
                transport.respondSuccess(id, """{"key":"yolo","scope":"session","value":"$value"}""")
            }
            assertTrue((adapter.slashExecute("<runtime>", SlashInvocation("yolo", arg), origin,
                adapter.connectionGeneration) as GatewayResult.Success).value is SlashDirective.YoloChanged)
        }
        for (response in listOf(
            """{"key":"yolo","scope":"global","value":"1"}""",
            """{"key":"yolo","value":"1"}""",
            """{"key":"yolo","scope":"session","value":true}""",
            """{"key":"yolo","scope":"session","value":"0"}""")) {
            transport.setRpcHandler("config.set") { id, _ -> transport.respondSuccess(id, response) }
            assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("yolo", "on"), origin,
                adapter.connectionGeneration) is GatewayResult.Failure)
        }
        assertFalse(transport.sentFrames.any { it.contains("slash.exec") || it.contains("command.dispatch") })
        val before = transport.sentFrames.size
        for ((runtime, arg, generation) in listOf(Triple("", "on", adapter.connectionGeneration),
            Triple("<runtime>", "global", adapter.connectionGeneration), Triple("<runtime>", "", adapter.connectionGeneration),
            Triple("<runtime>", "on", -1L))) {
            assertTrue(adapter.slashExecute(runtime, SlashInvocation("yolo", arg), origin, generation) is GatewayResult.Failure)
        }
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("yolo", "on"),
            CanonicalOrigin.parse("http://other.test:8000").getOrThrow(), adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(before, transport.sentFrames.size)
    }
    @Test fun yoloTimeoutAndMissingMethodNeverRetryOrFallback() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler), rpcTimeoutMs = 100)
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket", 30)) }; advanceUntilIdle()
        var writes = 0
        transport.setRpcHandler("config.set") { _, _ -> writes++ }
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("yolo", "on"), origin,
            adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(1, writes)
        transport.setRpcHandler("config.set") { id, _ -> writes++; transport.respondError(id, -32601, "Method not found") }
        assertTrue(adapter.slashExecute("<runtime>", SlashInvocation("yolo", "off"), origin,
            adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(2, writes)
        assertFalse(transport.sentFrames.any { it.contains("slash.exec") || it.contains("command.dispatch") })
    }

}
