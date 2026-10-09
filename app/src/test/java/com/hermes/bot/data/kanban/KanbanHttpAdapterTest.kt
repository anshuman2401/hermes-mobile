package com.hermes.bot.data.kanban

import com.hermes.bot.data.auth.AuthHttpTransport
import com.hermes.bot.data.auth.HttpResponse
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class KanbanHttpAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val origin = "http://example.test:9119"
    private lateinit var vault: EncryptedVault
    private val card = """{"id":"card-a","title":"Synthetic card","body":"Description","status":"todo","priority":3,"assignee":null,"comment_count":2,"warnings":{"count":1},"latest_summary":"Progress"}"""
    private class Transport : AuthHttpTransport {
        data class Call(val method: String, val url: String, val body: String?)
        val calls = mutableListOf<Call>()
        var response = HttpResponse(200, body = "{}")
        var throwOnWrite = false
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
            calls += Call(method, url, body)
            if (throwOnWrite && method != "GET") throw IOException("Synthetic transport failure")
            return response
        }
    }
    @Before fun prepare() = runBlocking {
        vault = EncryptedVault(temporary.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault.saveConnectionProfile(ConnectionProfile("connection-a", origin, "Synthetic"))
        vault.setActiveProfileId("connection-a")
        vault.saveAuthCredential(AuthCredential(AuthCredential.DRIVER_COOKIE_SESSION, "password",
            mapOf("session" to "SYNTHETIC_COOKIE"), origin))
    }

    @Test fun boardReadPinsSlugAndPreservesHostLaneOrderAndWarningCount() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body =
            """{"columns":[{"name":"triage","tasks":[]},{"name":"todo","tasks":[$card]},{"name":"done","tasks":[]}],"latest_event_id":9}""") }
        val result = KanbanHttpAdapter(vault, transport).board("board a") as KanbanResult.Success
        assertEquals(listOf("triage", "todo", "done"), result.value.lanes.map { it.name })
        assertEquals("card-a", result.value.lanes[1].cards.single().id)
        assertEquals(1, result.value.lanes[1].cards.single().warningCount)
        assertEquals(9L, result.value.cursor)
        assertEquals("$origin/api/plugins/kanban/board?board=board%20a", transport.calls.single().url)
    }

    @Test fun mutationIsSingleSendAndNeverReportsAmbiguousFailureAsSuccess() = runBlocking {
        val transport = Transport().apply { throwOnWrite = true }
        val result = KanbanHttpAdapter(vault, transport).patch("alpha", "card-a", mapOf("status" to "ready"))
        assertEquals(KanbanFailure.UNKNOWN, (result as KanbanResult.Failure).reason)
        assertEquals(1, transport.calls.size)
        assertEquals("PATCH", transport.calls.single().method)
        assertTrue(transport.calls.single().url.endsWith("/tasks/card-a?board=alpha"))
    }

    @Test fun createStartsInTriageAndOmittedAssigneeUsesHostDefault() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(200, body = """{"task":$card}""") }
        val draft = KanbanCreate("New card", "Description", 2, null, "scratch", null,
            emptyList(), null, null, null, null, false, "synthetic-key")
        assertTrue(KanbanHttpAdapter(vault, transport).create("alpha", draft) is KanbanResult.Success)
        val payload = GatewayJson.parse(transport.calls.single().body!!).asObjectOrNull()!!.map
        assertEquals(true, payload["triage"]?.asBooleanOrNull())
        assertFalse(payload.containsKey("assignee"))
        assertEquals("synthetic-key", payload["idempotency_key"]?.asStringOrNull())
    }

    @Test fun unsupportedAndMalformedBoardFailClosed() = runBlocking {
        val transport = Transport().apply { response = HttpResponse(404, body = "{}") }
        val adapter = KanbanHttpAdapter(vault, transport)
        assertEquals(KanbanFailure.UNSUPPORTED, (adapter.boards() as KanbanResult.Failure).reason)
        transport.response = HttpResponse(200, body = """{"columns":[{"name":"todo","tasks":[{"id":"x"}]}],"latest_event_id":1}""")
        assertEquals(KanbanFailure.INVALID, (adapter.board("alpha") as KanbanResult.Failure).reason)
    }
}
