package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TRD1 slice 1 (TASK-004): JVM tests for the pure [ExecutionTracePolicy] —
 * span discovery, activity-row collection, header insertion, action counts,
 * elapsed formatting, and display-key uniqueness. Synthetic timeline fixtures
 * only; no real payload text (SEC-001).
 */
class ExecutionTracePolicyTest {

    private fun userMessage(id: String = "u-1") = UserMessage(id = id, text = "synthetic prompt")

    private fun assistantMessage(
        id: String = "asst-turn-1",
        state: AssistantMessageState = AssistantMessageState.COMPLETE
    ) = AssistantMessage(id = id, text = "synthetic answer", state = state)

    private fun toolRow(id: String) = ToolActivity(
        id = id,
        toolId = "synthetic-tool",
        name = "synthetic_tool",
        status = ToolStatus.COMPLETED
    )

    private fun genericRow(id: String) = GenericGatewayActivity(
        id = id,
        eventType = "synthetic.event",
        summary = "synthetic activity"
    )

    private fun reasoningRow(id: String) = ReasoningEntry(
        id = id,
        kind = ReasoningDisplayKind.REASONING,
        text = "synthetic reasoning"
    )

    // ------------------------------------------------------------------
    // Span discovery (TASK-002 b)
    // ------------------------------------------------------------------

    @Test
    fun traceAssistantId_mintsStablePrefix() {
        assertEquals("asst-turn-1", ExecutionTracePolicy.traceAssistantId("turn-1"))
        assertEquals("asst-x", ExecutionTracePolicy.traceAssistantId("x"))
    }

    @Test
    fun spanStartIndex_findsAssistantRowById() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1")
        )
        assertEquals(1, ExecutionTracePolicy.spanStartIndex(timeline, "turn-1"))
    }

    @Test
    fun spanStartIndex_absentAssistantRow_returnsNull() {
        val timeline = listOf(userMessage("u-1"), toolRow("t-1"))
        assertNull(ExecutionTracePolicy.spanStartIndex(timeline, "turn-1"))
    }

    @Test
    fun spanStartIndex_emptyTimeline_returnsNull() {
        assertNull(ExecutionTracePolicy.spanStartIndex(emptyList(), "turn-1"))
    }

    @Test
    fun spanStartIndex_matchesExactId_neverPrefixOrOtherTurn() {
        val timeline = listOf(
            assistantMessage("asst-turn-11"), // different turn: turn-11 vs turn-1
            assistantMessage("asst-turn-1")
        )
        assertEquals(1, ExecutionTracePolicy.spanStartIndex(timeline, "turn-1"))
    }

    // ------------------------------------------------------------------
    // Activity-row collection (TASK-002 c)
    // ------------------------------------------------------------------

    @Test
    fun activityRowIds_collectsToolGenericReasoning_afterAssistantRow() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            genericRow("g-1"),
            reasoningRow("r-1")
        )
        assertEquals(
            setOf("t-1", "g-1", "r-1"),
            ExecutionTracePolicy.activityRowIds(timeline, "turn-1")
        )
    }

    @Test
    fun activityRowIds_stopsAtNextUserMessageBoundary() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            userMessage("u-2"), // next turn boundary
            toolRow("t-2"),     // belongs to the next turn, never collected
            assistantMessage("asst-turn-2")
        )
        assertEquals(
            setOf("t-1"),
            ExecutionTracePolicy.activityRowIds(timeline, "turn-1")
        )
    }

    @Test
    fun activityRowIds_stopsAtNextAssistantMessageBoundary() {
        val timeline = listOf(
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            assistantMessage("asst-other-turn"),
            toolRow("t-2")
        )
        assertEquals(
            setOf("t-1"),
            ExecutionTracePolicy.activityRowIds(timeline, "turn-1")
        )
    }

    @Test
    fun activityRowIds_absentAssistantRow_returnsEmpty() {
        val timeline = listOf(userMessage("u-1"), toolRow("t-1"))
        assertTrue(ExecutionTracePolicy.activityRowIds(timeline, "turn-1").isEmpty())
    }

    // ------------------------------------------------------------------
    // Action count (TASK-002 d)
    // ------------------------------------------------------------------

    @Test
    fun actionCount_countsMixedRows() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            genericRow("g-1"),
            reasoningRow("r-1"),
            toolRow("t-2")
        )
        assertEquals(4, ExecutionTracePolicy.actionCount(timeline, "turn-1"))
    }

    @Test
    fun actionCount_noActivityRows_isZero() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1")
        )
        assertEquals(0, ExecutionTracePolicy.actionCount(timeline, "turn-1"))
    }

    @Test
    fun actionCount_absentSpan_isZero() {
        val timeline = listOf(userMessage("u-1"))
        assertEquals(0, ExecutionTracePolicy.actionCount(timeline, "turn-1"))
    }

    // ------------------------------------------------------------------
    // Elapsed text (TASK-002 e)
    // ------------------------------------------------------------------

    @Test
    fun elapsedText_underOneHour_isMmSs() {
        assertEquals("00:00", ExecutionTracePolicy.elapsedText(1000L, 1000L, 999_999L))
        assertEquals("01:05", ExecutionTracePolicy.elapsedText(1000L, 66_000L, 999_999L))
        assertEquals(
            "59:59",
            ExecutionTracePolicy.elapsedText(1L, (59 * 60 + 59) * 1000L + 1L, 999_999L)
        )
    }

    @Test
    fun elapsedText_oneHourOrMore_isHMmSs() {
        assertEquals("1:00:00", ExecutionTracePolicy.elapsedText(1L, 3_600_001L, 999_999L))
        assertEquals(
            "2:03:04",
            ExecutionTracePolicy.elapsedText(1L, (2 * 3600 + 3 * 60 + 4) * 1000L + 1L, 999_999L)
        )
    }

    @Test
    fun elapsedText_liveTurn_usesNowMillis() {
        // endedAt == null: elapsed is measured against nowMillis (live read).
        assertEquals("00:30", ExecutionTracePolicy.elapsedText(1L, null, 30_001L))
    }

    @Test
    fun elapsedText_invalidStart_returnsNull() {
        assertNull(ExecutionTracePolicy.elapsedText(0L, 30_000L, 999_999L))
        assertNull(ExecutionTracePolicy.elapsedText(-5L, 30_000L, 999_999L))
        // Live turn with an invalid start also hides (fail-closed).
        assertNull(ExecutionTracePolicy.elapsedText(-5L, null, 30_000L))
    }

    @Test
    fun elapsedText_terminalBeforeStart_clampsToZero() {
        // Defensive: a clock skew where endedAt < startedAt never yields a
        // negative duration.
        assertEquals("00:00", ExecutionTracePolicy.elapsedText(30_000L, 20_000L, 999_999L))
    }

    // ------------------------------------------------------------------
    // Display list builder (TASK-002 f)
    // ------------------------------------------------------------------

    @Test
    fun buildDisplayEntries_nullTrace_isFullPassthrough() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1")
        )
        val display = ExecutionTracePolicy.buildDisplayEntries(timeline, null)
        assertEquals(timeline.size, display.size)
        display.forEachIndexed { index, displayEntry ->
            val raw = displayEntry as TraceDisplayEntry.Raw
            assertEquals(timeline[index], raw.entry)
            assertEquals(timeline[index].id, displayEntry.key)
        }
    }

    @Test
    fun buildDisplayEntries_absentAssistantRow_isFullPassthrough() {
        val timeline = listOf(userMessage("u-1"), toolRow("t-1"))
        val trace = TraceState(turnId = "turn-1", startedAtMillis = 1_000L)
        val display = ExecutionTracePolicy.buildDisplayEntries(timeline, trace)
        assertEquals(timeline.size, display.size)
        assertTrue(display.all { it is TraceDisplayEntry.Raw })
    }

    @Test
    fun buildDisplayEntries_headerInsertedAfterAssistantRow_beforeFirstActivityRow() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            genericRow("g-1")
        )
        val trace = TraceState(
            turnId = "turn-1",
            startedAtMillis = 1_000L,
            endedAtMillis = 5_000L,
            overallState = TraceOverallState.COMPLETE
        )
        val display = ExecutionTracePolicy.buildDisplayEntries(timeline, trace)

        // One header + every timeline entry, in order.
        assertEquals(timeline.size + 1, display.size)
        val headerIndex = display.indexOfFirst {
            it is TraceDisplayEntry.Header
        }
        // Before the first activity row (index 2 raw → header lands at raw index 2).
        assertEquals(2, headerIndex)
        // ...and after the assistant row (raw index 1).
        assertEquals(
            "asst-turn-1",
            (display[headerIndex - 1] as TraceDisplayEntry.Raw).entry.id
        )
        // The turn's user message and assistant row stay OUTSIDE-before, activity rows follow.
        assertEquals("u-1", (display[0] as TraceDisplayEntry.Raw).entry.id)
        assertEquals("t-1", (display[headerIndex + 1] as TraceDisplayEntry.Raw).entry.id)
    }

    @Test
    fun buildDisplayEntries_headerKeyUsesDocumentedPrefix_andRawKeysStayEntryIds() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1")
        )
        val trace = TraceState(turnId = "turn-1", startedAtMillis = 1_000L)
        val display = ExecutionTracePolicy.buildDisplayEntries(timeline, trace)
        val header = display.first { it is TraceDisplayEntry.Header }
        assertEquals("trace-header-turn-1", header.key)
        // Raw entry keys stay the entry IDs (LazyColumn keys remain stable).
        val rawKeys = display
            .filterIsInstance<TraceDisplayEntry.Raw>()
            .map { it.key }
        assertEquals(timeline.map { it.id }, rawKeys)
        // The header key is distinct from every raw entry key in this list.
        assertFalse(rawKeys.contains(header.key))
    }

    @Test
    fun buildDisplayEntries_headerFollowsTurnSpan_notJustFirstAssistant() {
        // Two turns in one timeline: the header must attach to the traced
        // turn's assistant row, not the first assistant row in the list.
        val timeline = listOf(
            assistantMessage("asst-turn-0"),
            toolRow("t-0"),
            assistantMessage("asst-turn-1"),
            toolRow("t-1"),
            toolRow("t-2")
        )
        val trace = TraceState(turnId = "turn-1", startedAtMillis = 1_000L)
        val display = ExecutionTracePolicy.buildDisplayEntries(timeline, trace)
        val headerIndex = display.indexOfFirst {
            it is TraceDisplayEntry.Header
        }
        // Before timeline[3] ("t-1"), after timeline[2] ("asst-turn-1").
        assertEquals(3, headerIndex)
        assertFalse(display[headerIndex] is TraceDisplayEntry.Raw)
    }
}
