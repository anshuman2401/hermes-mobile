package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.local.CachedLocalToolTraceEvent
import io.github.anshuman2401.hermesmobile.data.local.ToolStatus
import io.github.anshuman2401.hermesmobile.data.local.ToolTraceSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-4 (plan CACHE-PR4 §9): pure projection tests for the cached tool-trace
 * display entry — bounded DTO→[CachedToolTraceEntry] mapping for all four
 * source types, redacted context never reaches a display row, RUNNING never
 * claims a completion, the group is appended after the timeline only when
 * non-empty, and group keys can never collide with reducer row/trace-header
 * keys.
 */
class CachedToolTracePolicyTest {

    private fun dto(
        seq: Long,
        sourceType: ToolTraceSourceType,
        toolId: String?,
        name: String,
        context: String? = null,
        status: ToolStatus = ToolStatus.RUNNING,
        durationSeconds: Double? = null,
        observedAtMillis: Long = 1_000L
    ) = CachedLocalToolTraceEvent(
        localEventId = "evt-$seq",
        sourceSeq = seq,
        sourceType = sourceType,
        toolId = toolId,
        name = name,
        context = context,
        status = status,
        durationSeconds = durationSeconds,
        observedAtMillis = observedAtMillis
    )

    private fun userEntry(id: String) = UserMessage(
        id = id, text = "body-$id", state = UserMessageState.SENT, timestamp = 1_000L
    )

    // ------------------------------------------------------------------
    // Bounded mapping across all four source types; observed fields only.
    // ------------------------------------------------------------------
    @Test
    fun mapsAllFourSourceTypes_observedFieldsOnly() {
        val events = listOf(
            dto(1L, ToolTraceSourceType.TOOL_GENERATING, null, "prep_tool"),
            dto(2L, ToolTraceSourceType.TOOL_START, "tool-2", "web_fetch"),
            dto(3L, ToolTraceSourceType.TOOL_PROGRESS_GENERIC, null, "Tool progress"),
            dto(4L, ToolTraceSourceType.TOOL_COMPLETE, "tool-4", "grep_tool",
                status = ToolStatus.COMPLETE, durationSeconds = 1.5)
        )
        val entries = ExecutionTracePolicy.buildCachedToolTraceEntryList(events)
        assertEquals(4, entries.size)

        assertEquals("prep_tool", entries[0].name)
        assertEquals(CachedToolTraceStatus.RUNNING, entries[0].status)
        assertEquals(null, entries[0].durationSeconds)

        assertEquals("web_fetch", entries[1].name)
        assertEquals(CachedToolTraceStatus.RUNNING, entries[1].status)

        assertEquals("Tool progress", entries[2].name)
        assertEquals(CachedToolTraceStatus.RUNNING, entries[2].status)

        assertEquals("grep_tool", entries[3].name)
        assertEquals(CachedToolTraceStatus.COMPLETE, entries[3].status)
        assertEquals(1.5, entries[3].durationSeconds!!, 0.0001)

        // Identity and observed time pass through; input order preserved
        // (observed order — completion-before-start stays put).
        assertEquals(listOf("evt-1", "evt-2", "evt-3", "evt-4"), entries.map { it.id })
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_000L), entries.map { it.observedAtMillis })

        assertEquals(emptyList<CachedToolTraceEntry>(), ExecutionTracePolicy.buildCachedToolTraceEntryList(emptyList()))
    }

    // ------------------------------------------------------------------
    // The redacted context is bounded input — it must never reach the
    // display entry in any form (field, equality, or text rendering).
    // ------------------------------------------------------------------
    @Test
    fun redactedContext_neverReachesDisplayRow() {
        val withCtx = ExecutionTracePolicy.buildCachedToolTraceEntryList(
            listOf(dto(1L, ToolTraceSourceType.TOOL_START, "tool-1", "web_fetch", context = "CTX_SENTINEL_policy"))
        ).single()
        val withoutCtx = ExecutionTracePolicy.buildCachedToolTraceEntryList(
            listOf(dto(1L, ToolTraceSourceType.TOOL_START, "tool-1", "web_fetch"))
        ).single()
        // Two DTOs differing only in redacted context project identically.
        assertEquals(withoutCtx, withCtx)
        assertFalse("sentinel absent from entry text", withCtx.toString().contains("CTX_SENTINEL_policy"))
        assertEquals("web_fetch", withCtx.name)
    }

    // ------------------------------------------------------------------
    // Status text: RUNNING never claims a completion; duration formats only
    // when actually captured.
    // ------------------------------------------------------------------
    @Test
    fun statusText_runningNeverFabricatesCompletion() {
        assertEquals(
            "Completion not captured",
            ExecutionTracePolicy.cachedTraceStatusText(CachedToolTraceStatus.RUNNING, null)
        )
        assertEquals(
            "Completed in 1.5s",
            ExecutionTracePolicy.cachedTraceStatusText(CachedToolTraceStatus.COMPLETE, 1.5)
        )
        assertEquals(
            "Completed",
            ExecutionTracePolicy.cachedTraceStatusText(CachedToolTraceStatus.COMPLETE, null)
        )
        // RUNNING with a duration still never claims completion wording.
        val running = ExecutionTracePolicy.cachedTraceStatusText(CachedToolTraceStatus.RUNNING, 2.0)
        assertFalse(running.startsWith("Completed"))
    }

    // ------------------------------------------------------------------
    // Group placement and key space: appended last only when non-empty;
    // key can never collide with reducer row ids or trace-header keys;
    // empty input is byte-identical to the default (pre-PR-4) call.
    // ------------------------------------------------------------------
    @Test
    fun groupAppendedLast_onlyWhenNonEmpty_keyNeverCollides() {
        val timeline = listOf(userEntry("u1"), userEntry("u2"))
        val entries = ExecutionTracePolicy.buildCachedToolTraceEntryList(
            listOf(dto(1L, ToolTraceSourceType.TOOL_START, "tool-1", "web_fetch"))
        )

        val withGroup = ExecutionTracePolicy.buildBlockDisplayEntries(timeline, null, entries)
        assertEquals(withGroup.size, ExecutionTracePolicy.buildBlockDisplayEntries(timeline, null).size + 1)
        val group = withGroup.last() as TraceDisplayEntry.CachedTrace
        assertEquals("cached-tool-trace", group.key)
        assertFalse("group key is not a reducer row id", group.key in timeline.map { it.id })
        assertFalse("group key is not a trace-header key", group.key.startsWith("trace-header-"))

        // All display keys unique.
        val keys = withGroup.map { it.key }
        assertEquals(keys, keys.distinct())

        // Empty (or default) ⇒ no group; byte-identical to the baseline list.
        val baseline = ExecutionTracePolicy.buildBlockDisplayEntries(timeline, null)
        assertTrue(baseline.none { it is TraceDisplayEntry.CachedTrace })
        assertEquals(
            baseline,
            ExecutionTracePolicy.buildBlockDisplayEntries(timeline, null, emptyList())
        )
    }

    // ------------------------------------------------------------------
    // With a live trace header present the group still appends after
    // everything and keys stay unique across raw + header rows.
    // ------------------------------------------------------------------
    @Test
    fun groupAppendsAfterHeaderAndRows_keysRemainUnique() {
        // Header insertion needs the traced turn's assistant row
        // (`asst-<turnId>`, spanStartIndex rule) — without it the surface
        // fail-closes to no header.
        val timeline = listOf(
            userEntry("u1"),
            AssistantMessage(
                id = "asst-turn-1",
                text = "body-asst-turn-1",
                state = AssistantMessageState.COMPLETE,
                timestamp = 1_500L
            )
        )
        val trace = TraceState(turnId = "turn-1", startedAtMillis = 1_000L)
        val entries = ExecutionTracePolicy.buildCachedToolTraceEntryList(
            listOf(dto(1L, ToolTraceSourceType.TOOL_COMPLETE, "tool-1", "grep_tool",
                status = ToolStatus.COMPLETE, durationSeconds = 0.5))
        )
        val out = ExecutionTracePolicy.buildBlockDisplayEntries(timeline, trace, entries)
        assertTrue(out.last() is TraceDisplayEntry.CachedTrace)
        // The flat projection for the same turn DOES mint the trace header
        // (spanStartIndex rule); its `trace-header-` key must never collide
        // with the group key across either projection model.
        val flatKeys = ExecutionTracePolicy.buildDisplayEntries(timeline, trace).map { it.key }
        val blockKeys = out.map { it.key }
        assertTrue("header row present in the flat projection", flatKeys.any { it.startsWith("trace-header-") })
        // Unique within each projection model, and the header key never
        // equals the group key across models.
        assertEquals(flatKeys, flatKeys.distinct())
        assertEquals(blockKeys, blockKeys.distinct())
        val groupKey = out.last().key
        assertEquals(1, blockKeys.count { it == groupKey })
        assertTrue("header key never equals the group key", flatKeys.none { it == groupKey })
    }
}
