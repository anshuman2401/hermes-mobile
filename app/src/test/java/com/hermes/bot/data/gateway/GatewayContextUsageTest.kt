package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayContextUsageTest {
    @Test fun liveUsageIsTypedPartialBoundedAndNeverGeneric() {
        val adapter = DefaultGatewayProtocolAdapter(FakeGatewayTransport())
        fun event(body: String) = adapter.parseEventForTest(
            """{"type":"session.usage","session_id":"synthetic-runtime","payload":$body}""") as GatewayEvent.SessionUsageUpdated
        val complete = event("""{"usage":{"context_used":100,"context_max":1000,"context_percent":10,"context_estimated":false,"model":" Synthetic model ","total":150,"reasoning":0}}""")
        assertEquals(100L, complete.usage.context!!.used)
        assertEquals(150L, complete.usage.total)
        assertEquals("Synthetic model", complete.usage.model)
        assertTrue(complete.usage.context!!.categories.isEmpty())
        assertFalse(complete.toString().contains("Synthetic model"))
        for (value in listOf("-1", "100.5", "\"100\"", "9223372036854775808")) {
            val partial = event("""{"usage":{"context_used":$value,"context_max":1000,"context_percent":10,"context_estimated":false,"model":"Synthetic","total":0}}""")
            assertNull(partial.usage.context)
            assertEquals("Synthetic", partial.usage.model)
            assertEquals(0L, partial.usage.total)
        }
        for (body in listOf("{}", "{\"usage\":null}", "{\"usage\":7}")) {
            assertNull(event(body).usage.context)
            assertNull(event(body).usage.model)
        }
        assertNull(event("""{"usage":{"model":"${"x".repeat(257)}","total":-1}}""").usage.model)
        assertNull(event("""{"usage":{"model":" ","total":-1}}""").usage.total)
        val invalidModel = event("""{"usage":{"context_used":0,"context_max":0,"context_percent":0,"context_estimated":true,"model":7}}""")
        assertNotNull(invalidModel.usage.context)
        assertNull(invalidModel.usage.model)
    }
    private val context = """{"context_used":100,"context_max":1000,"context_percent":10,"context_estimated":true,"context_source":"local_estimate","model":"Synthetic model","categories":[{"id":"conversation","label":"Conversation","tokens":200,"color":"var(--unsafe)"}],"estimated_total":200,"context_files":[{"path":"synthetic-unused-path"}]}"""
    @Test fun occupancyAndCategoryEstimatesStaySeparateAndManifestIsDiscarded() {
        val result = ContextUsageWireDecoder.breakdown(GatewayJson.parse(context))!!
        assertEquals(900L, result.remaining)
        assertEquals(200.0, result.categoryTotal, 0.0)
        assertTrue(result.estimated)
        assertFalse(result.toString().contains("synthetic-unused-path"))
        assertEquals("Conversation", result.categories.single().label)
    }
    @Test fun malformedNumbersDuplicateIdsAndOversizedRowsFailClosed() {
        for ((old, new) in listOf("\"context_used\":100" to "\"context_used\":-1",
            "\"context_used\":100" to "\"context_used\":100.5",
            "\"context_used\":100" to "\"context_used\":\"100\"",
            "\"context_max\":1000" to "\"context_max\":99",
            "\"context_percent\":10" to "\"context_percent\":101",
            "\"context_estimated\":true" to "\"context_estimated\":\"true\"")) {
            assertNull(ContextUsageWireDecoder.breakdown(GatewayJson.parse(context.replace(old,new))))
        }
        val base = GatewayJson.parse(context) as JsonObject
        val row = (base["categories"] as JsonArray).list.single()
        assertNull(ContextUsageWireDecoder.breakdown(JsonObject(base.map + ("categories" to JsonArray(List(33) { row })))))
        assertNull(ContextUsageWireDecoder.breakdown(JsonObject(base.map + ("categories" to JsonArray(listOf(row,row))))))
    }
    @Test fun nullableTotalAndNoAccountExtensionAreNotZero() {
        val usage = ContextUsageWireDecoder.usage(GatewayJson.parse("""{"total":null}"""))!!
        assertNull(usage.total)
        assertNull(usage.account.bankedResets)
        val zero = ContextUsageWireDecoder.usage(GatewayJson.parse("""{"total":0,"account_lines":[]}"""))!!
        assertEquals(0L, zero.total)
        assertNull(ContextUsageWireDecoder.usage(GatewayJson.parse("""{"total":-1}""")))
        assertNull(ContextUsageWireDecoder.usage(GatewayJson.parse("""{"account_lines":[7]}""")))
        assertNull(ContextUsageWireDecoder.usage(JsonObject("account_lines" to JsonArray(List(33) { JsonString("Synthetic") }))))
    }
    @Test fun emptyUnbuiltSessionIsDistinctFromUnavailableRpc() {
        val root = GatewayJson.parse(context) as JsonObject
        val result = ContextUsageWireDecoder.breakdown(JsonObject(root.map + mapOf("context_used" to JsonNumber(0L),
            "context_max" to JsonNumber(0L), "context_percent" to JsonNumber(0L), "categories" to JsonArray(emptyList()))))!!
        assertEquals(0L, result.capacity)
        assertTrue(result.categories.isEmpty())
    }
    @Test fun callsCarryExactOriginProfileGenerationAndNeverSendOnStaleScope() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket",30)) }; advanceUntilIdle()
        var reads = 0
        for (method in listOf("session.context_breakdown", "session.usage")) transport.setRpcHandler(method) { id, params ->
            assertEquals("<runtime>", params["session_id"]?.asStringOrNull())
            assertEquals("synthetic-workspace", params["profile"]?.asStringOrNull())
            reads++
            transport.respondSuccess(id, if (method == "session.usage") """{"total":500}""" else context)
        }
        assertTrue(adapter.sessionContextBreakdown("<runtime>","synthetic-workspace",origin,adapter.connectionGeneration) is GatewayResult.Success)
        assertTrue(adapter.sessionUsage("<runtime>","synthetic-workspace",origin,adapter.connectionGeneration) is GatewayResult.Success)
        assertTrue(adapter.sessionUsage("<runtime>","synthetic-workspace",origin,-1) is GatewayResult.Failure)
        assertTrue(adapter.sessionContextBreakdown("<runtime>","synthetic-workspace",
            CanonicalOrigin.parse("http://other.test:8000").getOrThrow(),adapter.connectionGeneration) is GatewayResult.Failure)
        assertTrue(adapter.sessionUsage("","synthetic-workspace",origin,adapter.connectionGeneration) is GatewayResult.Failure)
        assertEquals(2, reads)
    }
    @Test fun compressionOutcomesDoNotConfuseAbortedMessagesWithSuccess() {
        fun decode(json: String) = SlashWireDecoder.compressed(GatewayJson.parse(json))!!
        assertNull(SlashWireDecoder.compressed(GatewayJson.parse("""{"status":"future-status","messages":[]}""")))
        assertTrue(decode("""{"status":"pending"}""").pending)
        assertTrue(decode("""{"compressed":false,"lock_held":true}""").pending)
        val aborted = decode("""{"status":"aborted","messages":[]}""")
        assertTrue(aborted.failed); assertFalse(aborted.changed)
        assertTrue(decode("""{"status":"compressed"}""").changed)
        assertFalse(decode("""{"compressed":false}""").changed)
    }
    @Test fun desktopStatusKindSurvivesAdapterBoundary() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { adapter.events.collect { events.add(it) } }
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic-ticket",30)) }; advanceUntilIdle()
        transport.emitFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"status.update","session_id":"<runtime>","payload":{"kind":"compacted","text":"Synthetic completion"}}}""")
        runCurrent()
        assertEquals("compacted", events.filterIsInstance<GatewayEvent.StatusUpdate>().last().kind)
    }
}
