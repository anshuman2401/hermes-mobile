package com.hermes.bot.data.history

import com.hermes.bot.data.gateway.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class StoredHistoryProtocolAdapterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun editEligibilityUsesRawMetadataBeforeProjectionDropsFields() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope("synthetic")
            val rows = listOf(
                """{"id":1,"session_id":"synthetic","role":"user","content":"Plain message"}""",
                """{"id":2,"session_id":"synthetic","role":"user","content":"Image prompt","images":["synthetic"]}""",
                """{"id":3,"session_id":"synthetic","role":"user","content":"File prompt @file:synthetic.txt"}"""
            ).joinToString(",")
            f.enqueue(HistoryFixture.page(session = "synthetic", rows = rows, count = 3))
            val page = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow()
            assertEquals(1L, page.rows[0].editable?.rowId)
            assertNull(page.rows[1].editable)
            assertNull(page.rows[2].editable)
            val users = com.hermes.bot.feature.chat.StoredHistoryHydrator.hydrate(page.rows)
                .filterIsInstance<com.hermes.bot.feature.chat.UserMessage>()
            assertEquals(1L, users[0].editable?.rowId)
            assertNull(users[1].editable)
        }
    }

    @Test fun exactEncodedGetExplicitDefaultAndSourceOrder() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize()
            val id = "synthetic+?:%"
            val scope = f.scope(id)
            f.enqueue(HistoryFixture.page(session = id, rows = HistoryFixture.row(9, session = id) + "," +
                HistoryFixture.row(2, session = id), count = 2))
            val result = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow()
            val request = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/sessions/synthetic+%3F:%25/messages", request.requestUrl!!.encodedPath)
            assertEquals("limit=120&offset=0&order=latest&include_compacted=true&profile=default", request.requestUrl!!.encodedQuery)
            assertEquals(listOf(9L, 2L), result.rows.map { it.id })
            assertEquals(2, result.nextOffset); assertFalse(result.possiblyEarlier)
            assertEquals(id, result.requestedSessionId); assertEquals(id, result.resolvedSessionId)
            assertEquals(11L, result.scope.requestGeneration); assertEquals(17L, result.scope.tailGeneration)
            f.assertReadOnly()
        }
    }

    @Test fun propertyPresenceToolsReasoningHiddenRowsAndCanonicalBounds() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope()
            val extras = listOf("null", "false", "0", "\"\"").map {
                """"content":$it,"result":$it,"args":{},"tool_call_id":"synthetic-call","tool_name":"synthetic-tool","tool_calls":[{"id":"synthetic-call","function":{"name":"synthetic-tool","arguments":"{}"}}],"reasoning":"synthetic-reasoning","display_kind":"hidden","display_metadata":{"synthetic":true}"""
            }
            val rows = extras.mapIndexed { i, extra -> HistoryFixture.row((i + 1).toLong(), extra) } + HistoryFixture.row(5, "\"reasoning_content\":\"synthetic-reasoning\"")
            f.enqueue(HistoryFixture.page(rows = rows.joinToString(","), count = 5))
            val page = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow()
            assertEquals(5, page.returned); assertEquals(5, page.nextOffset)
            page.rows.take(4).forEachIndexed { index, row ->
                assertTrue(row.hasProperty("content")); assertTrue(row.hasProperty("result"))
                assertEquals(GatewayJson.parse(listOf("null", "false", "0", "\"\"")[index]), row.fields["result"])
                assertTrue(row.hasProperty("tool_calls")); assertTrue(row.hasProperty("reasoning"))
                assertEquals(JsonString("hidden"), row.fields["display_kind"])
                assertTrue(row.canonicalBytes > 0); assertFalse(row.truncated)
                assertFalse(row.toString().contains("synthetic"))
            }
            assertFalse(page.rows.last().hasProperty("content"))
            assertTrue(page.rows.last().hasProperty("reasoning_content")); f.assertReadOnly()
        }
    }

    @Test fun wrongOrMissingWireIdentityAndPaginationNeverRetryOrCommit() = runBlocking {
        val valid = HistoryFixture.page()
        val bodies = listOf(
            valid.replace("\"profile\":\"default\",", ""),
            valid.replace("\"profile\":\"default\"", "\"profile\":\"synthetic-other\""),
            valid.replace("\"session_id\":\"synthetic-session\",", ""),
            valid.replace("\"pagination\":", "\"unrecognized\":"),
            HistoryFixture.page(limit = 30), HistoryFixture.page(offset = 1), HistoryFixture.page(order = "oldest"),
            HistoryFixture.page(count = 0), HistoryFixture.page(rows = HistoryFixture.row(0)),
            HistoryFixture.page(rows = HistoryFixture.row(-1)),
            HistoryFixture.page(rows = HistoryFixture.row().replace("\"id\":1", "\"id\":\"1\"")),
            HistoryFixture.page(rows = HistoryFixture.row().replace("\"id\":1", "\"id\":1.5")),
            HistoryFixture.page(rows = HistoryFixture.row() + "," + HistoryFixture.row(), count = 2),
            HistoryFixture.page(rows = HistoryFixture.row(session = "synthetic-foreign")),
            "{", valid.replace("\"returned\":1", "\"returned\":1,\"returned\":1"),
            valid.replace("\"returned\":1", "\"returned\":01"), valid.replace("synthetic-text", "synthetic\ntext")
        )
        for (body in bodies) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope(); val budget = StoredHistoryBudget(scope); f.enqueue(body)
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount); assertEquals(0, budget.successfulPages)
            assertTrue(f.repository.fetchStoredHistoryPage(scope, budget).isFailure)
            assertEquals(1, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun descendantRemainsUnavailableWithoutResumeBinding() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope()
            f.enqueue(HistoryFixture.page(session = "synthetic-descendant", rows = HistoryFixture.row(session = "synthetic-descendant")))
            val outcome = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope))
            assertEquals(StoredHistoryFailure.UNBOUND_DESCENDANT, (outcome.exceptionOrNull() as StoredHistoryException).failure)
            val rejected = outcome.exceptionOrNull() as StoredHistoryException
            assertEquals("synthetic-session", rejected.requestedSessionId)
            assertEquals("synthetic-descendant", rejected.resolvedSessionId)
            assertFalse(rejected.toString().contains("synthetic")); f.assertReadOnly()
        }
    }

    @Test fun malformedScopeAndForeignOriginNeverReachHttp() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize()
            for (id in listOf("", " synthetic", "a/b", "a\\b", "..", "a\nb", "a\u202eb"))
                assertTrue(f.repository.captureStoredHistoryScope("synthetic-a", f.origin, "default", id, 1, 1).isFailure)
            assertTrue(f.repository.captureStoredHistoryScope("synthetic-other", f.origin, "default", "synthetic", 1, 1).isFailure)
            val other = com.hermes.bot.data.origin.CanonicalOrigin.parse("http://example.test:4321").getOrThrow()
            assertTrue(f.repository.captureStoredHistoryScope("synthetic-a", other, "default", "synthetic", 1, 1).isFailure)
            assertTrue(f.repository.captureStoredHistoryScope("synthetic-a", f.origin, "", "synthetic", 1, 1).isFailure)
            assertEquals(0, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun opaqueCallIdentitySurvivesDisplayBoundsWithoutPrefixCollision() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope()
            val a = "synthetic-" + "x".repeat(18000) + "-a"
            val b = "synthetic-" + "x".repeat(18000) + "-b"
            val fields = JsonObject("tool_calls" to JsonArray(listOf(
                JsonObject("id" to JsonString(a)), JsonObject("id" to JsonString(b)))), "tool_call_id" to JsonString(a))
            val extra = GatewayJson.serialize(fields).removePrefix("{").removeSuffix("}")
            f.enqueue(HistoryFixture.page(rows = HistoryFixture.row(extra = extra)))
            val row = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow().rows.single()
            val ids = row.fields.getValue("tool_calls").asArrayOrNull()!!.list.map { it["id"]!!.asStringOrNull() }
            assertTrue("Opaque call identifiers must remain exact", ids == listOf(a, b))
            assertTrue("Result correlation identity must remain exact", row.fields["tool_call_id"]!!.asStringOrNull() == a)
            assertFalse(row.truncated); f.assertReadOnly()
        }
    }

    @Test fun nestedCanonicalValueBudgetIsFiniteAcrossContainers() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope()
            val nested = JsonArray(List(20) { JsonArray(List(20) { JsonNumber(0L) }) })
            val extra = "\"content\":" + GatewayJson.serialize(nested)
            f.enqueue(HistoryFixture.page(rows = HistoryFixture.row(extra = extra)))
            val row = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow().rows.single()
            fun count(value: JsonValue): Int = 1 + when (value) {
                is JsonArray -> value.list.sumOf { count(it) }
                is JsonObject -> value.map.values.sumOf { count(it) }
                else -> 0
            }
            assertTrue("Canonical nested content exceeds its 256-value budget", count(row.fields.getValue("content")) <= 256)
            assertTrue(row.truncated); f.assertReadOnly()
        }
    }

    @Test fun unicodeBoundedCanonicalRowDropsUnknownWireFields() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); val scope = f.scope()
            val extra = """"content":"${"😀".repeat(10000)}","args":"${"x".repeat(10000)}","unrecognized":"synthetic-private""""
            f.enqueue(HistoryFixture.page(rows = HistoryFixture.row(extra = extra)))
            val row = f.repository.fetchStoredHistoryPage(scope, StoredHistoryBudget(scope)).getOrThrow().rows.single()
            assertTrue(row.truncated); assertEquals(16384, row.fields["content"]!!.asStringOrNull()!!.toByteArray().size)
            assertEquals(4096, row.fields["args"]!!.asStringOrNull()!!.length)
            assertFalse(row.hasProperty("unrecognized")); assertTrue(row.canonicalBytes < 22000); f.assertReadOnly()
        }
    }
}
