package io.github.anshuman2401.hermesmobile.feature.chat

import java.util.Locale

/**
 * A chronological step inside a [ToolActivityBlock] per PRD v2 § 1.
 */
sealed interface ToolBlockStep {
    val id: String
    val name: String
    val isRunning: Boolean
    val durationText: String?
    val entry: TimelineEntry

    data class Tool(
        val activity: ToolActivity
    ) : ToolBlockStep {
        override val id: String get() = activity.id
        override val name: String get() = activity.name
        override val isRunning: Boolean get() = activity.status == ToolStatus.RUNNING || activity.status == ToolStatus.PREPARING
        override val durationText: String? get() = activity.durationSeconds?.let { String.format(Locale.ROOT, "%.1fs", it) }
        override val entry: TimelineEntry get() = activity
    }

    data class Reasoning(
        val reasoning: ReasoningEntry,
        val running: Boolean = false
    ) : ToolBlockStep {
        override val id: String get() = reasoning.id
        override val name: String get() = when (reasoning.kind) {
            ReasoningDisplayKind.REASONING -> "Reasoning"
            ReasoningDisplayKind.THINKING -> "Thinking"
        }
        override val isRunning: Boolean get() = running
        override val durationText: String? get() = null
        override val entry: TimelineEntry get() = reasoning
    }

    data class Generic(
        val generic: GenericGatewayActivity
    ) : ToolBlockStep {
        override val id: String get() = generic.id
        override val name: String get() = generic.summary
        override val isRunning: Boolean get() = false
        override val durationText: String? get() = null
        override val entry: TimelineEntry get() = generic
    }

    /**
     * CHAT-018 PR-A (REQ-002): one agent commentary segment inside the card.
     * Never running, never timed; excluded from [ToolActivityBlock.totalActionCount]
     * (AC-5) and from header action copy (OQ-001 — the header reads
     * "Agent notes" when the card holds only notes).
     */
    data class Note(
        val note: AgentNoteEntry
    ) : ToolBlockStep {
        override val id: String get() = note.id
        override val name: String get() = note.text
        override val isRunning: Boolean get() = false
        override val durationText: String? get() = null
        override val entry: TimelineEntry get() = note
    }
}

/**
 * Collapsed block representation grouping an assistant reply's tool steps and reasoning
 * per PRD v2 § 1 (Block Card) and § 2 (Bottom Sheet).
 */
data class ToolActivityBlock(
    val id: String,
    val turnId: String? = null,
    val steps: List<ToolBlockStep>,
    val totalActionCount: Int,
    val aggregateTimeMillis: Long?,
    val aggregateTimeText: String?,
    val isLive: Boolean = false,
    val startedAtMillis: Long? = null
)

/**
 * Pure stateless decision policy for the Tool Activity Block Card + Bottom Sheet per PRD v2.
 * Groups tool steps and reasoning per assistant reply into one collapsible block card.
 * All methods are pure and JVM-testable.
 */
object ToolBlockPolicy {

    /**
     * Formats an aggregate client-measured wall-clock run time per PRD v2 § 1 (e.g. "12m 32s").
     * Fail-closed: returns null when startedAtMillis <= 0 (REQ-005 precedent).
     */
    fun formatAggregateTime(startedAtMillis: Long, endedAtMillis: Long?, nowMillis: Long): String? {
        if (startedAtMillis <= 0L) return null
        val end = endedAtMillis ?: nowMillis
        val elapsedMillis = (end - startedAtMillis).coerceAtLeast(0L)
        return formatDuration(elapsedMillis)
    }

    /**
     * Formats elapsed duration millis into readable "12m 32s", "45s", or "1h 12m 32s".
     */
    fun formatDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, seconds)
            minutes > 0L -> String.format(Locale.ROOT, "%dm %02ds", minutes, seconds)
            else -> String.format(Locale.ROOT, "%ds", seconds)
        }
    }

    /**
     * Formats the header action count string (e.g. "15 actions" or "1 action").
     */
    fun formatActionCount(count: Int): String {
        return "$count ${if (count == 1) "action" else "actions"}"
    }

    /**
     * CHAT-018 PR-A (OQ-001): the collapsed card's header title.
     * A notes-only card (zero non-note steps) reads "Agent notes"; otherwise
     * the existing action-count copy is byte-identical to [formatActionCount].
     */
    fun headerTitle(block: ToolActivityBlock): String {
        val nonNoteCount = block.steps.count { it !is ToolBlockStep.Note }
        return if (nonNoteCount == 0) "Agent notes" else formatActionCount(nonNoteCount)
    }

    /**
     * Selects one preview row without changing the full chronological list used by the sheet.
     * A tool call remains visible even when later reasoning or generic activity follows it.
     */
    fun visibleCollapsedSteps(steps: List<ToolBlockStep>): List<ToolBlockStep> {
        val preview = steps.lastOrNull { it is ToolBlockStep.Tool } ?: steps.lastOrNull()
        return preview?.let(::listOf) ?: emptyList()
    }

    /**
     * Returns true if any step in [block] is currently active ([ToolStatus.RUNNING] or [ToolStatus.PREPARING]).
     * When false (all steps terminal), no ticking timer should run (battery-safe).
     */
    fun hasLiveStep(block: ToolActivityBlock): Boolean {
        return block.steps.any { it.isRunning }
    }

    /**
     * Resolves the elapsed text for a [ToolActivityBlock] collapsed header per PRD v2 § 1.
     *
     * If the block has a live step ([hasLiveStep]), computes client-measured wall-clock
     * duration from start to [nowMillis] using [formatAggregateTime].
     * If all steps are terminal, returns the frozen [ToolActivityBlock.aggregateTimeText].
     * Fail-closed: returns null if the start timestamp is absent or invalid (<= 0).
     */
    fun headerElapsedText(
        block: ToolActivityBlock,
        nowMillis: Long
    ): String? {
        if (!hasLiveStep(block)) {
            return block.aggregateTimeText
        }
        val startMillis = block.startedAtMillis
            ?: block.steps.map { it.entry.timestamp }.filter { it > 0L }.minOrNull()
            ?: return null

        return formatAggregateTime(startMillis, null, nowMillis)
    }

    /**
     * Converts a domain TimelineEntry into a ToolBlockStep if it represents an activity row,
     * or null if it is a message/notice.
     */
    fun toBlockStep(entry: TimelineEntry, isTurnLive: Boolean = false): ToolBlockStep? {
        return when (entry) {
            is ToolActivity -> ToolBlockStep.Tool(entry)
            is ReasoningEntry -> ToolBlockStep.Reasoning(entry, running = isTurnLive)
            is GenericGatewayActivity -> if (entry.eventType == "session.usage") null else ToolBlockStep.Generic(entry)
            is AgentNoteEntry -> ToolBlockStep.Note(entry)
            else -> null
        }
    }

    /**
     * Pure grouping policy: partitions the timeline and groups each assistant reply's
     * tool steps and reasoning into ONE collapsed block card between the user message
     * and the assistant answer per PRD v2 § 1.
     */
    fun buildBlockDisplayEntries(
        timeline: List<TimelineEntry>,
        trace: TraceState? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): List<TraceDisplayEntry> {
        val visibleTimeline = timeline.filterNot { it is GenericGatewayActivity && it.eventType == "session.usage" }
        return buildVisibleBlockDisplayEntries(visibleTimeline, trace, nowMillis)
    }

    private fun buildVisibleBlockDisplayEntries(
        timeline: List<TimelineEntry>, trace: TraceState?, nowMillis: Long
    ): List<TraceDisplayEntry> {
        if (timeline.isEmpty()) return emptyList()

        val out = ArrayList<TraceDisplayEntry>(timeline.size)
        var i = 0
        while (i < timeline.size) {
            val entry = timeline[i]

            // 1. Pass through UserMessage
            if (entry is UserMessage) {
                out.add(TraceDisplayEntry.Raw(entry))
                i++

                // Collect everything belonging to this turn up to the next UserMessage
                val turnEntries = ArrayList<TimelineEntry>()
                while (i < timeline.size && timeline[i] !is UserMessage) {
                    turnEntries.add(timeline[i])
                    i++
                }

                // Partition turnEntries into activity rows, assistant message(s), and system notices
                val activityEntries = ArrayList<TimelineEntry>()
                var assistantMessage: AssistantMessage? = null
                val notices = ArrayList<TimelineEntry>()

                for (item in turnEntries) {
                    when (item) {
                        is ToolActivity, is ReasoningEntry, is GenericGatewayActivity, is AgentNoteEntry -> {
                            activityEntries.add(item)
                        }
                        is AssistantMessage -> {
                            if (assistantMessage == null) {
                                assistantMessage = item
                            } else {
                                // If multiple assistant messages, preserve extra
                                notices.add(item)
                            }
                        }
                        else -> notices.add(item)
                    }
                }

                // If activity rows exist, create a block card between UserMessage and AssistantMessage
                if (activityEntries.isNotEmpty()) {
                    val isLiveTurn = trace != null &&
                        (trace.turnId == entry.id ||
                            assistantMessage?.id == ExecutionTracePolicy.traceAssistantId(trace.turnId)) &&
                        trace.overallState == TraceOverallState.RUNNING

                    val steps = activityEntries.mapNotNull { toBlockStep(it, isLiveTurn) }
                    val blockId = assistantMessage?.id?.let { "block-$it" } ?: "block-${entry.id}"

                    // CHAT-018 PR-A (AC-5/OQ-001): agent notes are card steps but
                    // never count toward the action header, and their timestamps
                    // are excluded from the historical aggregate-elapsed window
                    // (a notes-only card computes no aggregate elapsed).
                    val nonNoteTimestamps = activityEntries
                        .filter { it !is AgentNoteEntry }
                        .map { it.timestamp }
                    val validTimestamps = nonNoteTimestamps.filter { it > 0L }
                    val startMillis = if (trace != null && trace.startedAtMillis > 0L) {
                        trace.startedAtMillis
                    } else {
                        validTimestamps.minOrNull()
                    }

                    // Client-measured aggregate time
                    val aggregateTimeText = if (isLiveTurn) {
                        formatAggregateTime(trace!!.startedAtMillis, trace.endedAtMillis, nowMillis)
                    } else {
                        // Historical or sealed: calculate from step timestamps
                        if (validTimestamps.isNotEmpty()) {
                            val start = validTimestamps.minOrNull() ?: 0L
                            val end = maxOf(validTimestamps.maxOrNull() ?: 0L, assistantMessage?.timestamp ?: 0L)
                            if (start > 0L && end >= start) {
                                formatDuration(end - start)
                            } else null
                        } else null
                    }

                    val block = ToolActivityBlock(
                        id = blockId,
                        turnId = trace?.turnId ?: entry.id,
                        steps = steps,
                        totalActionCount = steps.count { it !is ToolBlockStep.Note },
                        aggregateTimeMillis = if (startMillis != null && startMillis > 0L) {
                            ((trace?.endedAtMillis ?: nowMillis) - startMillis).coerceAtLeast(0L)
                        } else null,
                        aggregateTimeText = aggregateTimeText,
                        isLive = isLiveTurn,
                        startedAtMillis = startMillis
                    )

                    out.add(TraceDisplayEntry.BlockCard(block))
                }

                // Assistant message renders after the block card
                if (assistantMessage != null) {
                    out.add(TraceDisplayEntry.Raw(assistantMessage))
                }

                // System notices render
                for (notice in notices) {
                    out.add(TraceDisplayEntry.Raw(notice))
                }
            } else if (entry is AssistantMessage) {
                // Assistant message without preceding user message (e.g. initial greeting)
                out.add(TraceDisplayEntry.Raw(entry))
                i++
            } else {
                // System notices or orphan activity rows outside a user turn
                out.add(TraceDisplayEntry.Raw(entry))
                i++
            }
        }

        return out
    }
}
