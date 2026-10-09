package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayTransport
import io.github.anshuman2401.hermesmobile.data.gateway.json.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GatewayActiveProjectionContractTest {
    private fun resume(body: String): SessionResumeResult = runBlocking {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, Dispatchers.Default)
        try {
            assertTrue(withTimeout(5000) { adapter.connect("http://example.test", GatewayTicket("synthetic-ticket", 30)) })
            transport.setRpcHandler("session.resume") { id, _ -> transport.respondSuccess(id, body) }
            (withTimeout(5000) { adapter.resumeSession("synthetic-requested") } as GatewayResult.Success).value
        } finally { adapter.disconnect() }
    }

    @Test fun certifiedUnpersistedDraftIsIdleWithoutDefaultingUnknownActivity() {
        val fixture = io.github.anshuman2401.hermesmobile.data.gateway.fake.FakeGatewayFixtures.SESSION_RESUME_UNPERSISTED
        val draft = resume(fixture)
        assertEquals(ResumeActivityEvidence.NON_RUNNING, draft.activityEvidence)
        assertTrue(draft.isUnpersistedDraft)
        // Preserve wire absence independently of the recognized response variant.
        assertEquals(WirePresence.ABSENT, draft.activeProjection!!.running.presence)
        assertEquals(WirePresence.ABSENT, draft.activeProjection.status.presence)
        val root = GatewayJson.parse(fixture).asObjectOrNull()!!.map
        val mutations = listOf(
            root - "info", root - "session_id", root - "stored_session_id", root - "message_count", root - "messages",
            root + ("session_id" to JsonString(" ")),
            root + ("session_key" to JsonString("synthetic-conflicting")),
            root + ("info" to JsonObject("lazy" to JsonBoolean(false))),
            root + ("info" to JsonObject("lazy" to JsonString("true"))),
            root + ("message_count" to JsonNumber(1L)),
            root + ("message_count" to JsonString("0")),
            root + ("message_count" to JsonNumber(4294967296L)),
            root + ("messages" to JsonArray(JsonObject("role" to JsonString("user"), "content" to JsonString("Synthetic")))),
            root + ("messages" to JsonNull)
        ) + listOf("running", "status", "inflight", "queued", "turn_started_at", "started_at", "messages_omitted")
            .map { root + (it to JsonNull) }
        for (map in mutations) {
            val result = resume(GatewayJson.serialize(JsonObject(map)))
            assertFalse(result.isUnpersistedDraft)
            assertEquals(ResumeActivityEvidence.UNKNOWN, result.activityEvidence)
        }
        val active = resume(GatewayJson.serialize(JsonObject(root + ("running" to JsonBoolean(true)))))
        assertFalse(active.isUnpersistedDraft)
        assertEquals(ResumeActivityEvidence.RUNNING, active.activityEvidence)
    }

    @Test fun actualWireRetainsExactSourceSecondsWithoutChangingMillis() {
        val message = resume("""{"messages":[{"role":"assistant","content":"Synthetic compact text","timestamp":1.2345}]}""").messages.single()
        assertEquals(1234L, message.createdAtMillis)
        // Old-entrypoint witness: absence is an assertion failure, never a missing-symbol compile RED.
        val observed = message.javaClass.methods.firstOrNull { it.name == "getSourceTimestampSeconds" }?.invoke(message)
        assertEquals("Actual source seconds must remain available independently of rounded display time", 1.2345, observed)
    }

    @Test fun actualTextOnlyProjectionPreservesEmptyFalseZeroAndDropsInventedParts() {
        val result = resume("""{"running":false,"status":"idle","turn_started_at":0,"started_at":999,
            "session_id":"synthetic-runtime","session_key":"synthetic-durable",
            "inflight":{"user":"","assistant":"","streaming":false,"corrections":[],"correction_offsets":[],
            "display_kind":"hidden","display_metadata":{"title_preview":"Synthetic display","unknown":{"parts":[1]}},
            "error":"","status":"error","recoverable":false,"error_surface":{"layer":"provider","code":"synthetic","retryable":false},
            "tools":[{"result":"SYNTHETIC_UNSUPPORTED"}],"reasoning":"SYNTHETIC_UNSUPPORTED"},"queued":{"user":""}}""")
        assertEquals(ResumeActivityEvidence.NON_RUNNING, result.activityEvidence)
        assertEquals("synthetic-runtime", result.observedRuntimeSessionId)
        assertEquals("synthetic-durable", result.observedDurableSessionId)
        val active = result.activeProjection!!
        assertEquals(false, active.running.value); assertEquals(WirePresence.VALUE, active.running.presence)
        assertEquals(0.0, active.turnStartedAt.value!!, 0.0)
        val inflight = active.inflight.value!!
        for (property in listOf(inflight.user, inflight.assistant, inflight.streaming, inflight.corrections,
            inflight.correctionOffsets, inflight.error, inflight.recoverable, active.queued)) assertEquals(WirePresence.VALUE, property.presence)
        assertEquals("", inflight.user.value); assertEquals(false, inflight.streaming.value)
        assertEquals(emptyList<String>(), inflight.corrections.value)
        assertEquals(false, inflight.errorSurface.value!!.retryable.value)
        assertEquals(setOf("title_preview"), inflight.displayMetadata.value!!.fields.keys)
        assertEquals("", active.queued.value!!.user.value)
    }

    @Test fun absentNullInvalidFieldsRetainValidNeighboursAndStrictActivity() {
        for ((raw, state) in listOf("" to WirePresence.ABSENT, ",\"inflight\":null" to WirePresence.NULL,
            ",\"inflight\":false" to WirePresence.INVALID, ",\"inflight\":{}" to WirePresence.VALUE)) {
            val active = resume("""{"started_at":123$raw}""").activeProjection!!
            assertEquals(state, active.inflight.presence)
            assertEquals(WirePresence.ABSENT, active.turnStartedAt.presence)
            assertEquals(WirePresence.ABSENT, active.queued.presence)
        }
        val result = resume("""{"running":"false","status":"idle","turn_started_at":"0","queued":[],
            "inflight":{"user":null,"assistant":"Synthetic retained","streaming":0,"error":7,"recoverable":"false",
            "corrections":["Synthetic one",7,""],"correction_offsets":[0,-1,2.5,"3",false],
            "display_metadata":{"task_count":false,"failed_count":0},"error_surface":{"layer":0,"code":"Synthetic code","retryable":null}}}""")
        assertEquals(ResumeActivityEvidence.UNKNOWN, result.activityEvidence)
        val active = result.activeProjection!!; val inflight = active.inflight.value!!
        assertEquals(WirePresence.INVALID, active.running.presence)
        assertEquals(WirePresence.INVALID, active.turnStartedAt.presence)
        assertEquals(WirePresence.INVALID, active.queued.presence)
        assertEquals(WirePresence.NULL, inflight.user.presence)
        assertEquals("Synthetic retained", inflight.assistant.value)
        assertEquals(WirePresence.INVALID, inflight.streaming.presence)
        assertEquals(WirePresence.INVALID, inflight.corrections.presence)
        assertEquals(listOf("Synthetic one", ""), inflight.corrections.value)
        assertEquals(listOf(0L), inflight.correctionOffsets.value)
        assertEquals(WirePresence.INVALID, inflight.errorSurface.value!!.layer.presence)
        assertEquals(WirePresence.NULL, inflight.errorSurface.value!!.retryable.presence)
        assertEquals(WirePresence.INVALID, inflight.displayMetadata.value!!.fields.getValue("task_count").presence)
    }

    @Test fun observedBindingNeverUsesRequestFallbackAndContradictionDoesNotChangeLegacyRouting() {
        val missing = resume("""{}""")
        assertEquals("synthetic-requested", missing.runtimeSessionId)
        assertEquals("synthetic-requested", missing.durableSessionId)
        assertNull(missing.observedRuntimeSessionId); assertNull(missing.observedDurableSessionId)
        val result = resume("""{"session_id":" ","stored_session_id":"synthetic-a","session_key":"synthetic-b","resumed":"synthetic-a","running":true}""")
        assertNull(result.observedRuntimeSessionId); assertNull(result.observedDurableSessionId)
        assertEquals("synthetic-a", result.durableSessionId)
        assertEquals(ResumeActivityEvidence.RUNNING, result.activityEvidence)
        assertEquals(ResumeActivityEvidence.UNKNOWN, resume("""{"running":false,"status":"busy"}""").activityEvidence)
        assertEquals(ResumeActivityEvidence.UNKNOWN, SessionResumeResult("synthetic", "synthetic", 0).activityEvidence)
        assertEquals(WirePresence.ABSENT, missing.activeProjection!!.running.presence)
        assertEquals(WirePresence.NULL, resume("""{"running":null,"status":null}""").activeProjection!!.running.presence)
    }

    @Test fun epochTypesAreStrictFiniteNonnegativeAndLegacyMillisAreUnchanged() {
        for (raw in listOf("null", "-1", "\"1\"", "true", "1e309")) {
            val result = resume("""{"turn_started_at":$raw,"messages":[{"timestamp":$raw}]}""")
            assertNull(result.activeProjection!!.turnStartedAt.value)
            assertNull(result.messages.single().sourceTimestampSeconds)
        }
        assertEquals(0.0, resume("""{"turn_started_at":0}""").activeProjection!!.turnStartedAt.value!!, 0.0)
    }

    @Test fun unicodeTextNamesCorrectionsAndSupportedMetadataAreBoundedAndRedacted() {
        val large = "😀".repeat(10000)
        val raw = JsonObject("running" to JsonBoolean(true), "inflight" to JsonObject(
            "assistant" to JsonString(large), "display_kind" to JsonString("😀".repeat(200)),
            "user" to JsonString("Bearer synthetic-token-value"),
            "corrections" to JsonArray(List(300) { JsonString("Synthetic correction") }),
            "correction_offsets" to JsonArray(List(300) { JsonNumber(0L) }),
            "display_metadata" to JsonObject("display_text" to JsonString(large), "title_preview" to JsonString(large),
                "unknown" to JsonArray(List(300) { JsonString(large.take(8)) }))))
        val result = resume(GatewayJson.serialize(raw)); val active = result.activeProjection!!
        val inflight = active.inflight.value!!
        assertEquals(16384, inflight.assistant.value!!.toByteArray().size)
        assertTrue(inflight.assistant.truncated)
        assertEquals(128, inflight.displayKind.value!!.codePointCount(0, inflight.displayKind.value!!.length))
        assertEquals(256, inflight.corrections.value!!.size); assertTrue(inflight.corrections.truncated)
        assertEquals(256, inflight.correctionOffsets.value!!.size)
        assertTrue(active.retainedBytes <= 8 * 1024 * 1024)
        assertEquals("Bearer [REDACTED]", inflight.user.value)
        assertEquals(setOf("display_text", "title_preview"), inflight.displayMetadata.value!!.fields.keys)
    }

    @Test fun rawActiveQueueErrorsDisplayAndObservedIdentityNeverAppearInModelStrings() {
        val markers = listOf("SYNTHETIC_RAW_USER", "SYNTHETIC_RAW_ASSISTANT", "SYNTHETIC_RAW_ERROR", "SYNTHETIC_RAW_DISPLAY",
            "SYNTHETIC_RAW_CORRECTION", "SYNTHETIC_RAW_QUEUE", "synthetic-observed-runtime", "synthetic-observed-durable")
        val result = resume("""{"session_id":"${markers[6]}","session_key":"${markers[7]}",
            "inflight":{"user":"${markers[0]}","assistant":"${markers[1]}","error":"${markers[2]}",
            "display_metadata":{"title_preview":"${markers[3]}"},"corrections":["${markers[4]}"]},"queued":{"user":"${markers[5]}"}}""")
        val active = result.activeProjection!!; val inflight = active.inflight.value!!
        val strings = result.toString() + active + active.inflight + inflight + inflight.displayMetadata.value +
            inflight.corrections + active.queued.value + inflight.user
        markers.forEach { assertFalse("Raw ephemeral model payload leaked", strings.contains(it)) }
        assertFalse(result.copy(activeProjection = null).toString().contains(markers[0])) // legacy Any? inflight also suppressed
    }
}
