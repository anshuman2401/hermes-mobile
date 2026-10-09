package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [ToolBlockPolicy] per PRD v2 § 1 (Block Card) and § 2 (Bottom Sheet).
 * Verifies per-reply grouping into one collapsible block card, chronological step ordering,
 * single-row preview selection, aggregate duration formatting, and edge cases.
 */
class ToolBlockPolicyTest {

    private fun userMessage(id: String = "u-1", text: String = "query") =
        UserMessage(id = id, text = text)

    private fun assistantMessage(
        id: String = "asst-turn-1",
        text: String = "answer",
        state: AssistantMessageState = AssistantMessageState.COMPLETE,
        timestamp: Long = 20_000L
    ) = AssistantMessage(id = id, text = text, state = state, timestamp = timestamp)

    private fun toolActivity(
        id: String,
        name: String = "search",
        toolId: String? = "call-$id",
        status: ToolStatus = ToolStatus.COMPLETED,
        durationSeconds: Double? = 1.2,
        timestamp: Long = 10_000L
    ) = ToolActivity(
        id = id,
        toolId = toolId,
        name = name,
        status = status,
        durationSeconds = durationSeconds,
        timestamp = timestamp
    )

    private fun reasoningEntry(
        id: String = "r-1",
        text: String = "thinking",
        kind: ReasoningDisplayKind = ReasoningDisplayKind.REASONING,
        timestamp: Long = 15_000L
    ) = ReasoningEntry(id = id, kind = kind, text = text, timestamp = timestamp)

    // ------------------------------------------------------------------
    // CHAT-018 PR-A: agent note steps (ToolBlockStep.Note / headerTitle)
    // ------------------------------------------------------------------

    private fun agentNote(
        id: String = "note-1",
        text: String = "narration",
        timestamp: Long = 12_000L
    ) = AgentNoteEntry(id = id, text = text, timestamp = timestamp)

    @Test fun legacyUsageIsHiddenWithoutMutatingTimeline() {
        val usage=GenericGatewayActivity(id="synthetic-usage",eventType="session.usage",summary="Synthetic",payloadPreview="Synthetic")
        val other=GenericGatewayActivity(id="synthetic-other",eventType="synthetic.event",summary="Synthetic",payloadPreview="Synthetic")
        assertNull(ToolBlockPolicy.toBlockStep(usage))
        assertTrue(ToolBlockPolicy.buildBlockDisplayEntries(listOf(usage)).isEmpty())
        assertEquals(1,ToolBlockPolicy.buildBlockDisplayEntries(listOf(other,usage)).size)
        assertNotNull(ToolBlockPolicy.toBlockStep(other))
        val block=ToolBlockPolicy.buildBlockDisplayEntries(listOf(UserMessage("synthetic-user","Synthetic"),usage,other))
            .filterIsInstance<TraceDisplayEntry.BlockCard>().single().block
        assertEquals(1,block.totalActionCount)
        assertEquals("synthetic-other",block.steps.single().id)
    }

    @Test
    fun toBlockStep_agentNoteEntry_mapsToNoteStep() {
        val entry = agentNote("note-x", text = "checking things")
        val step = ToolBlockPolicy.toBlockStep(entry)
        assertTrue(step is ToolBlockStep.Note)
        assertEquals("note-x", step!!.id)
        assertEquals("checking things", step.name)
        assertFalse(step.isRunning)
        assertNull(step.durationText)
        assertEquals(entry, step.entry)
    }

    @Test
    fun toBlockStep_agentNoteEntry_ignoresTurnLive() {
        val step = ToolBlockPolicy.toBlockStep(agentNote("note-live"), isTurnLive = true)
        assertTrue(step is ToolBlockStep.Note)
        assertFalse("a note is never a running step", step!!.isRunning)
    }

    @Test
    fun headerTitle_notesOnlyCard_readsAgentNotes() {
        val block = ToolActivityBlock(
            id = "b-note",
            steps = listOf(
                ToolBlockStep.Note(agentNote("n-1")),
                ToolBlockStep.Note(agentNote("n-2"))
            ),
            totalActionCount = 0,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertEquals("Agent notes", ToolBlockPolicy.headerTitle(block))
    }

    @Test
    fun headerTitle_mixedCard_keepsActionCountCopyByteIdentical() {
        val block = ToolActivityBlock(
            id = "b-mixed",
            steps = listOf(
                ToolBlockStep.Tool(toolActivity("t-1")),
                ToolBlockStep.Tool(toolActivity("t-2")),
                ToolBlockStep.Note(agentNote("n-1")),
                ToolBlockStep.Reasoning(reasoningEntry("r-1"))
            ),
            totalActionCount = 3,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertEquals("3 actions", ToolBlockPolicy.headerTitle(block))
        assertEquals(
            ToolBlockPolicy.formatActionCount(3),
            ToolBlockPolicy.headerTitle(block)
        )
        // 2 tools + note = 2 non-note steps.
        assertEquals(
            "2 actions",
            ToolBlockPolicy.headerTitle(block.copy(steps = block.steps.take(3)))
        )
    }

    @Test
    fun headerTitle_zeroNonNoteSteps_ruleIsUnconditional() {
        // Plan §A3(e): zero non-note steps -> "Agent notes", unconditionally
        // (the builder never emits an empty card — guard :236 — but the pure
        // function follows the frozen rule for any zero-non-note block).
        val block = ToolActivityBlock(
            id = "b-empty",
            steps = emptyList(),
            totalActionCount = 0,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertEquals("Agent notes", ToolBlockPolicy.headerTitle(block))
    }

    // ------------------------------------------------------------------
    // Header formatters
    // ------------------------------------------------------------------

    @Test
    fun formatDuration_underOneMinute() {
        assertEquals("0s", ToolBlockPolicy.formatDuration(0L))
        assertEquals("45s", ToolBlockPolicy.formatDuration(45_000L))
        assertEquals("59s", ToolBlockPolicy.formatDuration(59_999L))
    }

    @Test
    fun formatDuration_underOneHour() {
        assertEquals("1m 00s", ToolBlockPolicy.formatDuration(60_000L))
        assertEquals("12m 32s", ToolBlockPolicy.formatDuration((12 * 60 + 32) * 1000L))
        assertEquals("59m 59s", ToolBlockPolicy.formatDuration((59 * 60 + 59) * 1000L))
    }

    @Test
    fun formatDuration_oneHourOrMore() {
        assertEquals("1h 00m 00s", ToolBlockPolicy.formatDuration(3600_000L))
        assertEquals("1h 12m 32s", ToolBlockPolicy.formatDuration((3600 + 12 * 60 + 32) * 1000L))
    }

    @Test
    fun formatAggregateTime_invalidOrAbsent_returnsNull_failClosed() {
        assertNull(ToolBlockPolicy.formatAggregateTime(0L, 10_000L, 20_000L))
        assertNull(ToolBlockPolicy.formatAggregateTime(-1L, 10_000L, 20_000L))
    }

    @Test
    fun formatActionCount_formatsSingularAndPlural() {
        assertEquals("1 action", ToolBlockPolicy.formatActionCount(1))
        assertEquals("15 actions", ToolBlockPolicy.formatActionCount(15))
        assertEquals("0 actions", ToolBlockPolicy.formatActionCount(0))
    }

    @Test
    fun visibleCollapsedSteps_emptyAndOneStep() {
        assertTrue(ToolBlockPolicy.visibleCollapsedSteps(emptyList()).isEmpty())
        val one = ToolBlockStep.Reasoning(reasoningEntry("r-1"))
        assertEquals(listOf(one), ToolBlockPolicy.visibleCollapsedSteps(listOf(one)))
    }

    @Test
    fun visibleCollapsedSteps_manyPreferLastToolDespiteTrailingReasoningAndGeneric() {
        val steps = (1..12).map { ToolBlockStep.Tool(toolActivity("t-$it")) } +
            ToolBlockStep.Reasoning(reasoningEntry("r-last")) +
            ToolBlockStep.Generic(GenericGatewayActivity(id = "g-last", eventType = "test", summary = "test", isExpanded = false))
        assertEquals(listOf("t-12"), ToolBlockPolicy.visibleCollapsedSteps(steps).map { it.id })
        assertEquals(14, steps.size) // Selection never mutates the full list.
    }

    @Test
    fun visibleCollapsedSteps_withoutToolUsesLastActivity() {
        val steps = listOf(
            ToolBlockStep.Reasoning(reasoningEntry("r-1")),
            ToolBlockStep.Generic(GenericGatewayActivity(id = "g-2", eventType = "test", summary = "test", isExpanded = false))
        )
        assertEquals(listOf("g-2"), ToolBlockPolicy.visibleCollapsedSteps(steps).map { it.id })
    }

    @Test
    fun buildBlockDisplayEntries_manyStepsRetainsFullChronologyAndTrueCount() {
        val activity = (1..12).map { toolActivity("t-$it", timestamp = 10_000L + it) }
        val timeline = listOf(userMessage("u-1")) + activity +
            listOf(reasoningEntry("r-last", timestamp = 15_000L), assistantMessage("asst-1"))
        val block = (ToolBlockPolicy.buildBlockDisplayEntries(timeline)[1] as TraceDisplayEntry.BlockCard).block
        assertEquals(13, block.totalActionCount)
        assertEquals(activity.map { it.id } + "r-last", block.steps.map { it.id })
        assertEquals(listOf("t-12"), ToolBlockPolicy.visibleCollapsedSteps(block.steps).map { it.id })
        assertEquals("13 actions", ToolBlockPolicy.formatActionCount(block.totalActionCount))
        assertEquals("9s", block.aggregateTimeText)
    }

    // ------------------------------------------------------------------
    // Grouping policy: buildBlockDisplayEntries
    // ------------------------------------------------------------------

    @Test
    fun buildBlockDisplayEntries_emptyTimeline_returnsEmpty() {
        assertTrue(ToolBlockPolicy.buildBlockDisplayEntries(emptyList()).isEmpty())
    }

    @Test
    fun buildBlockDisplayEntries_noActivityRows_passesThroughRaw() {
        val timeline = listOf(
            userMessage("u-1"),
            assistantMessage("asst-1")
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        assertEquals(2, display.size)
        assertTrue(display[0] is TraceDisplayEntry.Raw)
        assertTrue(display[1] is TraceDisplayEntry.Raw)
        assertEquals("u-1", (display[0] as TraceDisplayEntry.Raw).entry.id)
        assertEquals("asst-1", (display[1] as TraceDisplayEntry.Raw).entry.id)
    }

    @Test
    fun buildBlockDisplayEntries_groupsActivitiesIntoOneBlockBetweenUserAndAssistant() {
        val timeline = listOf(
            userMessage("u-1"),
            toolActivity("t-1", name = "web_search", timestamp = 10_000L),
            toolActivity("t-2", name = "read_file", timestamp = 12_000L),
            reasoningEntry("r-1", timestamp = 14_000L),
            assistantMessage("asst-1", timestamp = 20_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)

        // 3 entries: UserMessage -> BlockCard -> AssistantMessage
        assertEquals(3, display.size)
        assertTrue(display[0] is TraceDisplayEntry.Raw)
        assertEquals("u-1", (display[0] as TraceDisplayEntry.Raw).entry.id)

        assertTrue(display[1] is TraceDisplayEntry.BlockCard)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertEquals(3, blockCard.block.totalActionCount)
        assertEquals(3, blockCard.block.steps.size)
        assertEquals(listOf("t-1", "t-2", "r-1"), blockCard.block.steps.map { it.id })

        assertTrue(display[2] is TraceDisplayEntry.Raw)
        assertEquals("asst-1", (display[2] as TraceDisplayEntry.Raw).entry.id)
    }

    @Test
    fun buildBlockDisplayEntries_historicalTurn_toolsBeforeAndReasoningAfterAssistant_allFolded() {
        // Historical pattern from loadSession: tools precede assistant, reasoning follows assistant
        val timeline = listOf(
            userMessage("u-1"),
            toolActivity("t-1", timestamp = 10_000L),
            toolActivity("t-2", timestamp = 11_000L),
            assistantMessage("asst-1", timestamp = 15_000L),
            reasoningEntry("r-1", timestamp = 16_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)

        assertEquals(3, display.size)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertEquals(3, blockCard.block.totalActionCount)
        assertEquals(listOf("t-1", "t-2", "r-1"), blockCard.block.steps.map { it.id })
        assertEquals("asst-1", (display[2] as TraceDisplayEntry.Raw).entry.id)
    }

    @Test
    fun buildBlockDisplayEntries_liveTurn_provisionalAssistantPrecedesTools_blockPlacedBetween() {
        // Live streaming pattern: provisional assistant created at submit point, tools arrive after
        val timeline = listOf(
            userMessage("turn-1"),
            assistantMessage("asst-turn-1", state = AssistantMessageState.STREAMING),
            toolActivity("t-1", status = ToolStatus.RUNNING),
            reasoningEntry("r-1")
        )
        val trace = TraceState(turnId = "turn-1", startedAtMillis = 5_000L)
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline, trace, nowMillis = 15_000L)

        assertEquals(3, display.size)
        assertTrue(display[0] is TraceDisplayEntry.Raw)
        assertTrue(display[1] is TraceDisplayEntry.BlockCard)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertTrue("Live block card must have isLive=true", blockCard.block.isLive)
        assertEquals(2, blockCard.block.steps.size)
        assertTrue(blockCard.block.steps[0].isRunning)

        assertTrue(display[2] is TraceDisplayEntry.Raw)
        assertEquals("asst-turn-1", (display[2] as TraceDisplayEntry.Raw).entry.id)
    }

    @Test
    fun buildBlockDisplayEntries_unkeyedToolGenerating_staysIndependentStep() {
        // PRD v2 edge case: blank tool_id / tool.generating rows stay unmergeable independent rows
        val timeline = listOf(
            userMessage("u-1"),
            toolActivity("gen-1", name = "terminal", toolId = null, status = ToolStatus.PREPARING),
            toolActivity("t-1", name = "terminal", toolId = "call-1", status = ToolStatus.COMPLETED),
            assistantMessage("asst-1")
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        // Both rows are kept as separate steps; gen-1 never merged into t-1
        assertEquals(2, blockCard.block.steps.size)
        assertEquals("gen-1", blockCard.block.steps[0].id)
        assertNull((blockCard.block.steps[0] as ToolBlockStep.Tool).activity.toolId)
        assertEquals("t-1", blockCard.block.steps[1].id)
        assertEquals("call-1", (blockCard.block.steps[1] as ToolBlockStep.Tool).activity.toolId)
    }

    @Test
    fun buildBlockDisplayEntries_concurrentSameNameTools_staySeparateSteps() {
        // PRD v2 edge case: concurrent same-name tools stay separate (tool_id identity)
        val timeline = listOf(
            userMessage("u-1"),
            toolActivity("t-1", name = "web_search", toolId = "id-1"),
            toolActivity("t-2", name = "web_search", toolId = "id-2"),
            assistantMessage("asst-1")
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertEquals(2, blockCard.block.steps.size)
        assertEquals("t-1", blockCard.block.steps[0].id)
        assertEquals("t-2", blockCard.block.steps[1].id)
    }

    // ------------------------------------------------------------------
    // CHAT-018 PR-A: notes join the card, excluded from count and elapsed window
    // ------------------------------------------------------------------

    @Test
    fun buildBlockDisplayEntries_notesFoldIntoCard_betweenUserAndAssistant() {
        val timeline = listOf(
            userMessage("u-1"),
            agentNote("note-1", timestamp = 11_000L),
            toolActivity("t-1", timestamp = 12_000L),
            agentNote("note-2", timestamp = 13_000L),
            assistantMessage("asst-1", timestamp = 20_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        assertEquals(3, display.size)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        // Chronological full list includes the notes (REQ-005 emit order).
        assertEquals(
            listOf("note-1", "t-1", "note-2"),
            blockCard.block.steps.map { it.id }
        )
        // AC-5: totalActionCount excludes notes.
        assertEquals(1, blockCard.block.totalActionCount)
        assertTrue(blockCard.block.steps[0] is ToolBlockStep.Note)
        assertTrue(blockCard.block.steps[2] is ToolBlockStep.Note)
    }

    @Test
    fun buildBlockDisplayEntries_notesOnlyTurn_createsCard() {
        // REQ-003: a notes-only turn still gets a card (activityEntries gains notes).
        val timeline = listOf(
            userMessage("u-1"),
            agentNote("note-1", timestamp = 11_000L),
            agentNote("note-2", timestamp = 12_000L),
            assistantMessage("asst-1", timestamp = 20_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        assertEquals(3, display.size)
        assertTrue(display[1] is TraceDisplayEntry.BlockCard)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertEquals(listOf("note-1", "note-2"), blockCard.block.steps.map { it.id })
        assertEquals(0, blockCard.block.totalActionCount)
    }

    @Test
    fun buildBlockDisplayEntries_noteTimestamps_excludedFromAggregateElapsed() {
        // Notes span a huge fake window (5s..2.7h); the aggregate must be
        // computed from the tool rows only (10_000..12_000) plus the reply
        // timestamp (20_000) -> 20_000-10_000 = 10s.
        val timeline = listOf(
            userMessage("u-1"),
            agentNote("note-early", timestamp = 5_000L),
            toolActivity("t-1", timestamp = 10_000L),
            toolActivity("t-2", timestamp = 12_000L),
            agentNote("note-late", timestamp = 10_000_000L),
            assistantMessage("asst-1", timestamp = 20_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertEquals("10s", blockCard.block.aggregateTimeText)
    }

    @Test
    fun buildBlockDisplayEntries_notesOnlyCard_computesNoAggregateElapsed() {
        val timeline = listOf(
            userMessage("u-1"),
            agentNote("note-1", timestamp = 5_000L),
            agentNote("note-2", timestamp = 9_000_000L),
            assistantMessage("asst-1", timestamp = 20_000L)
        )
        val display = ToolBlockPolicy.buildBlockDisplayEntries(timeline)
        val blockCard = display[1] as TraceDisplayEntry.BlockCard
        assertNull("a notes-only card has no aggregate elapsed", blockCard.block.aggregateTimeText)
        assertNull(blockCard.block.aggregateTimeMillis)
        assertNull(blockCard.block.startedAtMillis)
    }

    @Test
    fun visibleCollapsedSteps_notesOnlyCard_previewsLastNote() {
        val steps = listOf(
            ToolBlockStep.Note(agentNote("note-1")),
            ToolBlockStep.Note(agentNote("note-2"))
        )
        assertEquals(listOf("note-2"), ToolBlockPolicy.visibleCollapsedSteps(steps).map { it.id })
    }

    @Test
    fun visibleCollapsedSteps_mixedCard_preferLastTool_despiteTrailingNote() {
        val steps = listOf(
            ToolBlockStep.Tool(toolActivity("t-1")),
            ToolBlockStep.Note(agentNote("note-1"))
        )
        assertEquals(listOf("t-1"), ToolBlockPolicy.visibleCollapsedSteps(steps).map { it.id })
    }

    // ------------------------------------------------------------------
    // Live ticking timer & battery-safe terminal policy
    // ------------------------------------------------------------------

    @Test
    fun hasLiveStep_detectsRunningAndPreparingSteps() {
        // Tool RUNNING
        val runningToolBlock = ToolActivityBlock(
            id = "b-1",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.RUNNING))),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertTrue("RUNNING tool must indicate live step", ToolBlockPolicy.hasLiveStep(runningToolBlock))

        // Tool PREPARING
        val preparingToolBlock = ToolActivityBlock(
            id = "b-2",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-2", status = ToolStatus.PREPARING))),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertTrue("PREPARING tool must indicate live step", ToolBlockPolicy.hasLiveStep(preparingToolBlock))

        // Reasoning running
        val runningReasoningBlock = ToolActivityBlock(
            id = "b-3",
            steps = listOf(ToolBlockStep.Reasoning(reasoningEntry("r-1"), running = true)),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertTrue("Running reasoning must indicate live step", ToolBlockPolicy.hasLiveStep(runningReasoningBlock))

        // All terminal (COMPLETED)
        val completedBlock = ToolActivityBlock(
            id = "b-4",
            steps = listOf(
                ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.COMPLETED)),
                ToolBlockStep.Reasoning(reasoningEntry("r-1"), running = false),
                ToolBlockStep.Generic(GenericGatewayActivity(id = "g-1", eventType = "test", summary = "test", isExpanded = false))
            ),
            totalActionCount = 3,
            aggregateTimeMillis = 10_000L,
            aggregateTimeText = "10s"
        )
        assertFalse("All-terminal block must NOT indicate live step", ToolBlockPolicy.hasLiveStep(completedBlock))

        // Empty steps
        val emptyBlock = ToolActivityBlock(
            id = "b-5",
            steps = emptyList(),
            totalActionCount = 0,
            aggregateTimeMillis = null,
            aggregateTimeText = null
        )
        assertFalse("Empty block must NOT indicate live step", ToolBlockPolicy.hasLiveStep(emptyBlock))
    }

    @Test
    fun headerElapsedText_liveBlock_ticksWithNowMillis() {
        val start = 10_000L
        val liveBlock = ToolActivityBlock(
            id = "b-live",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.RUNNING, timestamp = start))),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null,
            isLive = true,
            startedAtMillis = start
        )

        // As nowMillis advances, the header text ticks per formatAggregateTime
        assertEquals("0s", ToolBlockPolicy.headerElapsedText(liveBlock, nowMillis = start))
        assertEquals("5s", ToolBlockPolicy.headerElapsedText(liveBlock, nowMillis = start + 5_000L))
        assertEquals("1m 02s", ToolBlockPolicy.headerElapsedText(liveBlock, nowMillis = start + 62_000L))
        assertEquals("12m 32s", ToolBlockPolicy.headerElapsedText(liveBlock, nowMillis = start + (12 * 60 + 32) * 1000L))
        assertEquals("1h 00m 05s", ToolBlockPolicy.headerElapsedText(liveBlock, nowMillis = start + 3605_000L))
    }

    @Test
    fun headerElapsedText_completedBlock_ignoresNowMillis_returnsStaticAggregateTime() {
        val completedBlock = ToolActivityBlock(
            id = "b-done",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.COMPLETED, timestamp = 10_000L))),
            totalActionCount = 1,
            aggregateTimeMillis = 15_000L,
            aggregateTimeText = "15s",
            isLive = false,
            startedAtMillis = 10_000L
        )

        // Completed history stays frozen regardless of nowMillis (battery-safe)
        assertEquals("15s", ToolBlockPolicy.headerElapsedText(completedBlock, nowMillis = 10_000L))
        assertEquals("15s", ToolBlockPolicy.headerElapsedText(completedBlock, nowMillis = 50_000L))
        assertEquals("15s", ToolBlockPolicy.headerElapsedText(completedBlock, nowMillis = 999_999L))
    }

    @Test
    fun headerElapsedText_liveBlock_absentOrInvalidStart_returnsNull_failClosed() {
        // startedAtMillis <= 0 and step timestamp <= 0
        val invalidStartBlock = ToolActivityBlock(
            id = "b-invalid",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.RUNNING, timestamp = 0L))),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null,
            isLive = true,
            startedAtMillis = 0L
        )
        assertNull("Invalid start timestamp must return null (fail-closed)", ToolBlockPolicy.headerElapsedText(invalidStartBlock, nowMillis = 20_000L))

        val negativeStartBlock = ToolActivityBlock(
            id = "b-neg",
            steps = listOf(ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.RUNNING, timestamp = -1L))),
            totalActionCount = 1,
            aggregateTimeMillis = null,
            aggregateTimeText = null,
            isLive = true,
            startedAtMillis = -1L
        )
        assertNull("Negative start timestamp must return null (fail-closed)", ToolBlockPolicy.headerElapsedText(negativeStartBlock, nowMillis = 20_000L))
    }

    @Test
    fun headerElapsedText_liveBlock_fallbackToEarliestStepTimestamp() {
        // startedAtMillis is null on block, but step has valid timestamp
        val fallbackBlock = ToolActivityBlock(
            id = "b-fallback",
            steps = listOf(
                ToolBlockStep.Tool(toolActivity("t-1", status = ToolStatus.COMPLETED, timestamp = 20_000L)),
                ToolBlockStep.Tool(toolActivity("t-2", status = ToolStatus.RUNNING, timestamp = 25_000L))
            ),
            totalActionCount = 2,
            aggregateTimeMillis = null,
            aggregateTimeText = null,
            isLive = true,
            startedAtMillis = null
        )

        // Uses earliest step timestamp (20_000L)
        assertEquals("10s", ToolBlockPolicy.headerElapsedText(fallbackBlock, nowMillis = 30_000L))
    }
}
