package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.json.*
import com.hermes.bot.data.history.*
import org.junit.Assert.*
import org.junit.Test

class StoredHistoryHydratorTest {
    @Test fun ordinaryOlderAnchorsKeepExistingRowsAndRewindIsNotCompleteness() {
        val previous = listOf(3L, 4L, 5L).map { row(it, "assistant", "\"content\":\"same\"") }
        val older = listOf(1L, 3L, 6L).map { row(it, "assistant", "\"content\":\"different\"") }
        val merged = StoredHistoryHydrator.mergeOlder(previous, older)
        assertEquals(listOf(1L, 3L, 6L, 4L, 5L), merged.map { it.id })
        assertSame(previous.first(), merged[1])
        assertEquals(previous, StoredHistoryHydrator.mergeOlder(previous, previous))
        assertEquals(listOf(1L, 3L, 4L, 5L), StoredHistoryHydrator.mergeOlder(previous, listOf(older.first())).map { it.id })
        assertEquals(previous, StoredHistoryHydrator.mergeOlder(previous, emptyList()))
        assertEquals(emptyList<StoredHistoryRow>(), StoredHistoryHydrator.mergeOlder(emptyList(), older))
    }
    @Test fun olderCanonicalRowCeilingIs4096Not4097() {
        val rows = (1L..4096L).map { row(it, "assistant") }
        StoredHistoryHydrator.admit(rows, StoredHistoryHydrator.hydrateForActive(rows))
        try { StoredHistoryHydrator.hydrateForActive(rows + row(4097, "assistant")); fail("Must reject whole proposed window") }
        catch (failure: StoredHistoryException) { assertEquals(StoredHistoryFailure.BUDGET, failure.failure) }
    }

    private fun row(id: Long, role: String, fields: String = "", bytes: Int = 64, truncated: Boolean = false) =
        StoredHistoryRow(id, role, (GatewayJson.parse("{$fields}") as JsonObject).map, bytes, truncated)
    private fun call(id: String = "synthetic-call", args: String = "{}", name: String = "terminal") =
        """{"id":"$id","function":{"name":"$name","arguments":$args}}"""
    private fun assistant(id: Long, calls: String) = row(id, "assistant", "\"tool_calls\":[$calls]")
    private fun result(id: Long, call: String = "synthetic-call", value: String = "\"synthetic-output\"") =
        row(id, "tool", "\"tool_call_id\":\"$call\",\"content\":$value")
    private fun tools(rows: List<StoredHistoryRow>) = StoredHistoryHydrator.hydrate(rows).filterIsInstance<ToolActivity>()

    @Test fun unpairedHistoricalDelegationKeepsUnknownTaskCards() {
        val out=tools(listOf(assistant(1,call(name="delegate_task",args="{\"goal\":\"Synthetic\"}"))))
        assertEquals(1,out.size)
        assertEquals(com.hermes.bot.data.subagent.SubagentStatus.UNKNOWN,out.single().delegatedTasks.single().status)
        assertTrue(out.single().delegatedTasks.single().historical)
        assertFalse(out.single().delegatedTasks.single().observed)
    }
    @Test fun parallelSameNameAndReusedIdsStayOccurrenceQualified() {
        val rows = listOf(row(1, "user", "\"content\":\"Synthetic first\""),
            assistant(9, call(args = "{\"x\":1}") + "," + call(args = "{\"x\":2}")),
            result(2, value = "\"first\""), result(3, value = "\"second\""),
            row(4, "user", "\"content\":\"Synthetic second\""), assistant(5, call()), result(6))
        val out = tools(rows)
        assertEquals(listOf("first", "second", "synthetic-output"), out.map { it.resultSummary })
        assertEquals(listOf(9L, 9L, 5L), out.map { it.storedSource!!.callRowId })
        assertEquals(listOf(0, 1, 0), out.map { it.storedSource!!.callIndex })
        assertEquals(3, out.map { it.id }.distinct().size)
    }

    @Test fun duplicateResultRowCannotConsumeLaterReusedOccurrence() {
        val first = result(3)
        val out = StoredHistoryHydrator.hydrate(listOf(assistant(1, call()), first,
            assistant(4, call()), first))
        assertEquals(1, out.filterIsInstance<ToolActivity>().size)
        assertTrue(out.filterIsInstance<GenericGatewayActivity>().single().summary.contains("outcome unknown"))
    }

    @Test fun resultBeforeCallOnlyReassociatesUnambiguouslyWithinUserContext() {
        assertEquals(1, tools(listOf(result(1), assistant(2, call()))).size)
        val ambiguous = StoredHistoryHydrator.hydrate(listOf(result(1), assistant(2, call() + "," + call())))
        assertTrue(ambiguous.none { it is ToolActivity })
        assertEquals(3, ambiguous.filterIsInstance<GenericGatewayActivity>().size)
        assertTrue(ambiguous.filterIsInstance<GenericGatewayActivity>().first().payloadPreview!!.contains("Input: Unknown"))
        assertTrue(tools(listOf(result(1), row(2, "user"), assistant(3, call()))).isEmpty())
    }

    @Test fun presenceNullFalseZeroAndEmptyIsNotTruthiness() {
        for (value in listOf("null", "false", "0", "\"\"")) {
            val tool = tools(listOf(assistant(1, call(args = value)), result(2, value = value))).single()
            assertEquals(ToolStatus.COMPLETED, tool.status)
            assertTrue(tool.storedSource!!.argumentsPresent)
            assertEquals(value, ToolDetailPolicy.formatInputContent(tool))
            assertEquals(value, ToolDetailPolicy.formatOutputContent(tool))
            assertTrue(ToolDetailPolicy.formatCopyAll(ToolBlockStep.Tool(tool)).contains("Output:"))
        }
    }

    @Test fun presentBlankAndLegacyPlaceholderStringsRemainActualStoredValues() {
        val space = GatewayJson.serialize(JsonString(" "))
        val blank = tools(listOf(assistant(1, call(args = space)), result(2, value = space))).single()
        assertEquals(space, ToolDetailPolicy.formatInputContent(blank))
        assertEquals(space, ToolDetailPolicy.formatOutputContent(blank))
        val labels = tools(listOf(assistant(1, call(args = GatewayJson.serialize(JsonString("(no arguments)")))),
            result(2, value = GatewayJson.serialize(JsonString("(empty result)"))))).single()
        assertEquals("(no arguments)", ToolDetailPolicy.formatInputContent(labels))
        assertEquals("(empty result)", ToolDetailPolicy.formatOutputContent(labels))
    }

    @Test fun noIdentityOrResultNeverInventsArgumentsOrCompletion() {
        val out = StoredHistoryHydrator.hydrate(listOf(assistant(1, "{\"function\":{\"name\":\"terminal\"}}"),
            row(2, "tool", "\"content\":\"orphan output\""), row(3, "tool", "\"tool_call_id\":\"synthetic-call\"")))
        assertTrue(out.none { it is ToolActivity })
        assertEquals(3, out.size)
        assertTrue((out[1] as GenericGatewayActivity).payloadPreview!!.contains("Input: Unknown"))
    }

    @Test fun explicitResultRowArgumentsAreRetainedWithoutInventingMissingCallInput() {
        val noArguments = "{\"id\":\"synthetic-call\",\"function\":{\"name\":\"terminal\"}}"
        val out = tools(listOf(assistant(1, noArguments),
            row(2, "tool", "\"tool_call_id\":\"synthetic-call\",\"args\":false,\"content\":0"))).single()
        assertEquals("false", out.argsSummary)
        assertTrue(out.storedSource!!.argumentsPresent)
        assertEquals("0", out.resultSummary)
    }

    @Test fun sourceOrderAndRowsWithEqualContentRemainDistinct() {
        val out = StoredHistoryHydrator.hydrate(listOf(row(9, "user", "\"content\":\"same\""),
            row(2, "assistant", "\"content\":\"same\"")))
        assertEquals(listOf("stored-9", "stored-2"), out.map { it.id })
    }

    @Test fun blankAndHiddenAssistantToolsAndReasoningStayInExistingTurnActions() {
        val rows = listOf(row(1, "user", "\"content\":\"Synthetic question\""),
            row(2, "assistant", "\"content\":\"\",\"display_kind\":\"hidden\",\"reasoning\":\"synthetic reasoning\",\"tool_calls\":[${call()}]"), result(3),
            row(4, "assistant", "\"content\":\"Synthetic answer\""))
        val display = ToolBlockPolicy.buildBlockDisplayEntries(StoredHistoryHydrator.hydrate(rows))
        val block = display.filterIsInstance<TraceDisplayEntry.BlockCard>().single().block
        assertEquals("stored-1", block.turnId)
        assertEquals(2, block.steps.size)
        assertNull(block.aggregateTimeText)
    }

    @Test fun opaqueLongIdsNeverUseTruncatedPrefixCorrelation() {
        val prefix = "x".repeat(18000)
        val out = tools(listOf(assistant(1, call(prefix + "a") + "," + call(prefix + "b")),
            result(2, prefix + "b"), result(3, prefix + "a")))
        assertEquals(listOf(3L, 2L), out.map { it.storedSource!!.resultRowId })
    }

    @Test fun boundedPlainHtmlAndPathsAreOnlyDetailAndCopyStrings() {
        val value = GatewayJson.serialize(JsonString("<script>synthetic()</script> /synthetic/file " + "x".repeat(30000)))
        val tool = tools(listOf(assistant(1, call(args = value)), result(2, value = value))).single()
        assertTrue(tool.argsSummary!!.contains("truncated"))
        assertTrue(tool.resultSummary!!.contains("truncated"))
        assertTrue(ToolDetailPolicy.formatCopyAll(ToolBlockStep.Tool(tool)).contains("<script>"))
        assertNull(tool.durationSeconds)
    }

    @Test fun selectedWindowAccountsRowsIndexesAndProjectionBeforePublishing() {
        for (rows in listOf(List(4097) { row((it + 1).toLong(), "user") },
            listOf(row(1, "user", bytes = 8 * 1024 * 1024)))) {
            try { StoredHistoryHydrator.hydrate(rows); fail("must reject whole window") }
            catch (failure: StoredHistoryException) { assertEquals(StoredHistoryFailure.BUDGET, failure.failure) }
        }
        assertEquals(4096, StoredHistoryHydrator.hydrate(List(4096) {
            row((it + 1).toLong(), "user", "\"content\":\"synthetic\"")
        }).size)
    }
}
