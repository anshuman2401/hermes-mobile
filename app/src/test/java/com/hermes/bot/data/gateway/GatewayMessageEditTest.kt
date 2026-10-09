package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayMessageEditTest {
    @Test fun scopedEditCarriesCutAndNormalSendDoesNot() = runTest {
        val transport = FakeGatewayTransport()
        val adapter = DefaultGatewayProtocolAdapter(transport, dispatcher = StandardTestDispatcher(testScheduler))
        val origin = CanonicalOrigin.parse("http://example.test:8000").getOrThrow()
        launch { adapter.connect(origin.originUrl, GatewayTicket("synthetic", 30)) }; advanceUntilIdle()
        val requests = mutableListOf<JsonValue>()
        transport.setRpcHandler("prompt.submit") { id, params ->
            requests += params
            transport.respondSuccess(id, """{"status":"streaming","user_row_id":21}""")
        }
        val edit = adapter.submitEditScoped("runtime", "Changed text", 7, origin, adapter.connectionGeneration)
        assertEquals(21L, (edit as GatewayResult.Success).value.userRowId)
        assertEquals(7L, requests.single()["truncate_before_row_id"]?.asLongOrNull())
        assertEquals(true, requests.single()["confirm_truncate"]?.asBooleanOrNull())
        assertEquals(true, requests.single()["confirm_empty_truncate"]?.asBooleanOrNull())
        assertNull(requests.single()["truncate_before_user_ordinal"])
        adapter.submitPrompt("runtime", "Ordinary follow-up")
        assertNull(requests.last()["confirm_truncate"])
        assertNull(requests.last()["truncate_before_row_id"])
        assertTrue(adapter.submitEditScoped("runtime", "Changed", 7, origin, -1) is GatewayResult.Failure)
        assertEquals(2, requests.size)
        adapter.disconnect()
    }
    @Test fun onlyCompleteRawPlainTextGrantsAnEditTarget() {
        fun target(json: String, text: String = "Hello", truncated: Boolean = false) =
            MessageEditPolicy.target((GatewayJson.parseOrNull(json) as JsonObject).map, 5, text, truncated)
        assertEquals(5L, target("""{"content":"Hello"}""")?.rowId)
        assertNull(target("""{"text":"Hello"}""")) // compact projection cannot prove absence of image parts
        assertNull(target("""{"content":[{"text":"Hello"}],"text":"Hello"}"""))
        assertNull(target("""{"content":"Hello"}""", truncated = true))
        assertNull(target("""{"content":"Hello","images":["synthetic"]}"""))
        assertNull(target("""{"content":"Hello","display_kind":"skill_invocation"}"""))
        assertNull(target("""{"content":"/skill Hello"}""", "/skill Hello"))
        assertNull(target("""{"content":"password=synthetic"}""", "password=[REDACTED]"))
        assertFalse(MessageEditPolicy.plainText("x".repeat(MessageEditPolicy.MAX_TEXT_CHARS + 1)))
    }
}
