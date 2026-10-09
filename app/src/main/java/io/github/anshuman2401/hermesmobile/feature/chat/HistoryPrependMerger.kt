package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage

/**
 * Result of HistoryPrependMerger.merge.
 * TASK-007: olderRows (original order, adjacent duplicates collapsed),
 * beginningReached (zero new older rows and the stop row was matched),
 * stopRow (the matched base row), nonExhaustive (messagesOmitted passthrough).
 */
data class PrependMergeResult(
    val olderRows: List<SessionMessage>,
    val beginningReached: Boolean,
    val stopRow: SessionMessage?,
    val nonExhaustive: Boolean = false
)

/**
 * Pure merge function for history prepend (TASK-007, REQ-003/REQ-004, frozen
 * plan CHAT-004): walks the fresh snapshot from index 0, collects rows OLDER
 * than the existing oldest history row, and stops at the first row whose raw
 * SessionMessage tuple (role, content, toolName, toolContext, reasoning)
 * equals the existing oldest row. Adjacent duplicate older rows collapse
 * (order-preserving). Zero new older rows with the base row matched =>
 * beginningReached (the rendered window already contains the full available
 * history). Base row absent (shrunken window, RISK-002) => zero older rows,
 * beginningReached=false, no state change. Null existingOldestHistoryRow =>
 * zero older rows (the caller must hydrate via loadSession, not prepend).
 */
object HistoryPrependMerger {
    fun merge(
        existingTimeline: List<TimelineEntry> = emptyList(),
        existingOldestHistoryRow: SessionMessage?,
        freshMessages: List<SessionMessage>,
        messagesOmitted: Boolean = false
    ): PrependMergeResult {
        if (existingOldestHistoryRow == null || freshMessages.isEmpty()) {
            return PrependMergeResult(
                olderRows = emptyList(),
                beginningReached = false,
                stopRow = null,
                nonExhaustive = messagesOmitted
            )
        }

        var stopIndex = -1
        for (index in freshMessages.indices) {
            // CHAT-009 (FZ-9 §2.3): the stop-row match stays pinned to the
            // LEGACY five-field tuple (role, content, toolName, toolContext,
            // reasoning). SessionMessage data-class equality now also covers
            // createdAtMillis, which is NOT part of the frozen stop-row
            // contract — a prepend must still stop at the base row even if
            // one side's timestamp failed to parse.
            val fresh = freshMessages[index]
            val base = existingOldestHistoryRow
            val matchesLegacyTuple =
                fresh.role == base.role &&
                    fresh.content == base.content &&
                    fresh.toolName == base.toolName &&
                    fresh.toolContext == base.toolContext &&
                    fresh.reasoning == base.reasoning
            if (matchesLegacyTuple) {
                stopIndex = index
                break
            }
        }

        // Base row not found in the fresh window: no reliable older region is
        // identifiable; never fabricate. Not "beginning reached" either.
        if (stopIndex == -1) {
            return PrependMergeResult(
                olderRows = emptyList(),
                beginningReached = false,
                stopRow = null,
                nonExhaustive = messagesOmitted
            )
        }

        // Rows before the stop row are genuinely older. Collapse ADJACENT
        // duplicates (same raw tuple) in the older region, preserving order.
        val olderRows = ArrayList<SessionMessage>(stopIndex)
        for (index in 0 until stopIndex) {
            val row = freshMessages[index]
            if (olderRows.isEmpty() || olderRows.last() != row) {
                olderRows.add(row)
            }
        }

        return PrependMergeResult(
            olderRows = olderRows,
            beginningReached = olderRows.isEmpty(),
            stopRow = freshMessages[stopIndex],
            nonExhaustive = messagesOmitted
        )
    }
}
