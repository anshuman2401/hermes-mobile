package com.hermes.bot.data.gateway

import com.hermes.bot.data.gateway.fake.FakeGatewayFixtures
import com.hermes.bot.data.gateway.fake.FakeGatewayTransport
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CHG-005-S1 (TASK-001/002/003): JVM fixture tests for tolerant `session.resume` /
 * `session.create` history message parsing in [DefaultGatewayProtocolAdapter.parseMessages].
 *
 * Fixture variants covered: string content, block-array content, top-level `text`
 * fallback, verbatim (missing) role, missing content, and redaction preservation.
 * All shapes are synthetic; no real host literals, tokens, or credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayParseMessagesTest {

    private lateinit var transport: FakeGatewayTransport
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var adapter: DefaultGatewayProtocolAdapter

    private val validTicket = GatewayTicket("safe-test-ticket-xyz", 30)

    @Before
    fun setUp() {
        transport = FakeGatewayTransport()
        adapter = DefaultGatewayProtocolAdapter(
            transport = transport,
            dispatcher = testDispatcher,
            readyTimeoutMs = 1_000L,
            rpcTimeoutMs = 1_000L
        )
    }

    private fun resumeWithResult(resultJson: String): List<SessionMessage> {
        var out: List<SessionMessage> = emptyList()
        runTest(testDispatcher) {
            launch { adapter.connect("https://test.hermes.local", validTicket) }
            advanceUntilIdle()

            transport.setRpcHandler("session.resume") { id, _ ->
                transport.respondSuccess(id, resultJson)
            }

            val res = adapter.resumeSession("sid-parse-fixtures")
            assertTrue("resume should succeed for fixture: $resultJson", res is GatewayResult.Success)
            out = (res as GatewayResult.Success<SessionResumeResult>).value.messages
        }
        return out
    }

    // --- (a) String content fixture parses to full text ---

    @Test
    fun stringContent_parsesToFullText() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "user", "content": "Echo the single word: PONG" },
                { "role": "assistant", "content": "PONG" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Echo the single word: PONG", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("PONG", messages[1].content)
    }

    // --- (b) Block-array content concatenates `text` fields in order ---

    @Test
    fun blockArrayContent_concatenatesTextBlocksInOrder() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                {
                  "role": "assistant",
                  "content": [
                    { "type": "text", "text": "First block. " },
                    { "type": "text", "text": "Second block." },
                    { "type": "tool_use", "id": "tool-1", "name": "tool" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("First block. Second block.", messages[0].content)
    }

    @Test
    fun blockArrayContent_withNoTextBlocks_yieldsEmptyContent() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                {
                  "role": "assistant",
                  "content": [
                    { "type": "tool_use", "id": "tool-1", "name": "tool" }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("", messages[0].content)
    }

    // --- (c) Top-level `text` string field is the fallback body ---

    @Test
    fun topLevelTextFallback_usedWhenContentAbsent() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "user", "text": "Hello from text field" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello from text field", messages[0].content)
    }

    @Test
    fun topLevelTextFallback_notUsedWhenContentStringPresent() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "user", "content": "content wins", "text": "not this" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("content wins", messages[0].content)
    }

    // --- (d) Missing/blank role stays verbatim (no silent "user" default) ---

    @Test
    fun missingRole_keptEmptyNotDefaultedToUser() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "content": "body without role" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("", messages[0].role)
        assertEquals("body without role", messages[0].content)
    }

    @Test
    fun blankRole_keptBlankNotDefaultedToUser() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "   ", "content": "body with blank role" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("   ", messages[0].role)
        assertEquals("body with blank role", messages[0].content)
    }

    @Test
    fun nonStringRole_keptEmptyAtBoundary() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": 7, "content": "body with numeric role" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("", messages[0].role)
        assertEquals("body with numeric role", messages[0].content)
    }

    // --- (e) Missing/non-string content stays "" ---

    @Test
    fun missingContent_staysEmpty() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "user" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("", messages[0].content)
    }

    @Test
    fun nonObjectMessageEntries_skipped() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                "not-an-object",
                { "role": "user", "content": "kept entry" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("kept entry", messages[0].content)
    }

    // --- (f) Redaction preserved through tolerant extraction ---

    @Test
    fun redactionPreserved_forStringAndBlockAndTextVariants() {
        val ticketPrefix = "hermes-gateway" + "-ticket."
        val ticketLike = ticketPrefix + "safe-test-" + "ticket-xyz"
        val bearerLike = "Bearer safe-test-token-xyz"
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "user", "content": "leak: $ticketLike" },
                {
                  "role": "assistant",
                  "content": [
                    { "type": "text", "text": "auth: $bearerLike" }
                  ],
                  "text": "fallback: $ticketLike"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertTrue(
            "string content must be redacted",
            messages[0].content.contains("[REDACTED]") && !messages[0].content.contains("safe-test-ticket-xyz")
        )
        assertTrue(
            "block-array text must be redacted",
            messages[1].content.contains("[REDACTED]") && !messages[1].content.contains("safe-test-token-xyz")
        )
        // The top-level `text` fallback is never used when a block-array produced text,
        // but the fixture asserts the redaction behavior of extraction itself.
        assertTrue(messages[1].content.isNotBlank())
    }

    @Test
    fun redactionPreserved_textFallbackVariant() {
        val ticketPrefix = "hermes-gateway" + "-ticket."
        val ticketLike = ticketPrefix + "safe-test-" + "ticket-xyz"
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "user", "text": "fallback leak: $ticketLike" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertTrue(
            "text fallback must be redacted",
            messages[0].content.contains("[REDACTED]") && !messages[0].content.contains("safe-test-ticket-xyz")
        )
    }

    // --- (g) Redactor behavior sanity (direct unit) ---

    @Test
    fun redactor_redactsTicketAndBearerLiterals() {
        val redacted = GatewayRedactor.redactString(
            "hermes-gateway-ticket.abc123 and Bearer xyz789"
        )
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(!redacted.contains("abc123"))
        assertTrue(!redacted.contains("xyz789"))
    }

    // --- (h) Sanity: JSON fixture parses via GatewayJson (fixture validity) ---

    @Test
    fun fixtureExtractionHelper_isValidJson() {
        val parsed = GatewayJson.parseOrNull("""{"role":"user","content":"x"}""")
        assertTrue(parsed is JsonObject)
        assertEquals("user", (parsed as JsonObject)["role"]?.asStringOrNull())
    }

    // --- (i) F007-S1: host `session.resume` tool rows carry name/context, no content ---

    @Test
    fun toolRow_parsesNameAndContextWithoutContent() {
        // Host resume rows (session_history.py shape): role "tool" carries
        // name/context and NO content/text field. The boundary must preserve
        // them as raw data; content stays "" (no fabricated body).
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "tool", "name": "web_search", "context": "searching docs for gateway" },
                { "role": "assistant", "text": "Done." }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertEquals("tool", messages[0].role)
        assertEquals("", messages[0].content)
        assertEquals("web_search", messages[0].toolName)
        assertEquals("searching docs for gateway", messages[0].toolContext)

        assertEquals("assistant", messages[1].role)
        assertNull(messages[1].toolName)
        assertNull(messages[1].toolContext)
    }

    @Test
    fun toolRow_nonStringNameAndContext_stayNull() {
        // Raw data at the boundary: a non-string name/context must NOT be
        // fabricated into a string; it stays null for the chat reducer.
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "tool", "name": 7, "context": true }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertEquals("tool", messages[0].role)
        assertEquals("", messages[0].content)
        assertNull(messages[0].toolName)
        assertNull(messages[0].toolContext)
    }

    // --- (j) F-007 polish: tool-role matching is case-insensitive (parity with
    // ChatViewModel.loadSession, which already uses ignoreCase) ---

    @Test
    fun toolRow_uppercaseRole_stillCarriesNameAndContext() {
        // The reducer (ChatViewModel.loadSession) matches role "tool" with
        // ignoreCase = true; the adapter boundary must not be stricter than the
        // consumer or a "Tool" row degrades to a generic unknown-role row.
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "Tool", "name": "web_search", "context": "case probe" },
                { "role": "TOOL", "name": "read_file", "context": "case probe 2" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertEquals("web_search", messages[0].toolName)
        assertEquals("case probe", messages[0].toolContext)
        assertEquals("read_file", messages[1].toolName)
        assertEquals("case probe 2", messages[1].toolContext)
    }

    @Test
    fun assistantRow_reasoningKeys_parseIntoReasoningField() {
        // F-007 deferred behavior: host assistant resume rows may carry
        // `reasoning`/`reasoning_content` (session_history.py forwards
        // _HISTORY_ASSISTANT_DETAIL_KEYS). The boundary preserves them as raw
        // data (redacted); hydration decides the rendering surface.
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "assistant", "text": "answer one", "reasoning": "why one" },
                { "role": "assistant", "text": "answer two", "reasoning_content": "why two" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        assertEquals("why one", messages[0].reasoning)
        assertEquals("why two", messages[1].reasoning)
    }

    @Test
    fun assistantRow_absentReasoningKeys_stayNull() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "assistant", "text": "no reasoning here" }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertNull(messages[0].reasoning)
    }

    @Test
    fun assistantRow_nonStringReasoning_staysNull() {
        // Raw data at the boundary: a non-string reasoning value must NOT be
        // fabricated into a string; it stays null for the chat reducer.
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 1,
              "messages": [
                { "role": "assistant", "text": "x", "reasoning": 7 }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, messages.size)
        assertNull(messages[0].reasoning)
    }

    // --- Regression: pre-existing persisted fixture still parses with new logic ---

    @Test
    fun sessionResume_persistedFixture_messagesStillParsed() = runTest(testDispatcher) {
        launch { adapter.connect("https://test.hermes.local", validTicket) }
        advanceUntilIdle()

        val res = adapter.resumeSession("sid-regression")
        assertTrue(res is GatewayResult.Success)
        val persisted = (res as GatewayResult.Success<SessionResumeResult>).value
        assertEquals("Echo the single word: PONG", persisted.messages[0].content)
        assertEquals("PONG", persisted.messages[1].content)
        assertEquals("user", persisted.messages[0].role)
        assertEquals("assistant", persisted.messages[1].role)
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_1, adapter.currentReplayEpoch)
    }

    // --- CHAT-009 (FZ-9): tolerant history-row timestamp parse ---

    @Test
    fun parseMessages_historyRowTimestamp_fractionalEpochSeconds_parsedToMillis() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 2,
              "messages": [
                { "role": "user", "content": "hi", "timestamp": 1789609565.33 },
                { "role": "assistant", "content": "yo", "timestamp": 1789609570 }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, messages.size)
        // Fractional epoch SECONDS -> epoch MILLIS (rounded toward zero via
        // double->long cast, per FZ-9); integral seconds parse identically.
        assertEquals(1789609565330L, messages[0].createdAtMillis)
        assertEquals(1789609570000L, messages[1].createdAtMillis)
    }

    @Test
    fun parseMessages_historyRowTimestamp_absentOrNonNumber_null() {
        val messages = resumeWithResult(
            """
            {
              "session_id": "TEST-RUNTIME-SID-1",
              "message_count": 3,
              "messages": [
                { "role": "user", "content": "no key" },
                { "role": "assistant", "content": "string ts", "timestamp": "1789609565.33" },
                { "role": "user", "content": "null ts", "timestamp": null }
              ]
            }
            """.trimIndent()
        )

        assertEquals(3, messages.size)
        assertNull(messages[0].createdAtMillis)
        assertNull(messages[1].createdAtMillis)
        assertNull(messages[2].createdAtMillis)
    }
}
