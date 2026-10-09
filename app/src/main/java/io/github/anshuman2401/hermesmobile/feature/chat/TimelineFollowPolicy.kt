package io.github.anshuman2401.hermesmobile.feature.chat

/**
 * BLD-006 S6 (D-05 / REQ-006): pure decision policy for user-controlled
 * timeline following.
 *
 * Follow state is derived ONLY from the scroll viewport and the admitted
 * timeline entries — never from connection, replay, or transport state
 * (TASK-029 stop condition). The composable wires [LazyListViewport] from a
 * real `LazyListState`; everything else is deterministic and JVM-testable.
 */
enum class FollowDecision {
    /** Instant, non-animated jump to the latest row (hydration replacement). */
    JUMP_TO_LATEST,

    /** Pinned near the end: follow growth of the same row or appended rows. */
    FOLLOW_LATEST,

    /** Unpinned: preserve the reading position; never force the viewport. */
    PRESERVE_VIEWPORT
}

/**
 * Minimal viewport snapshot extracted from `LazyListState.layoutInfo` so the
 * pinning rule can be exercised without a device (M-07 scroll rows, JVM tier).
 */
data class LazyListViewport(
    val firstVisibleItemIndex: Int,
    val lastVisibleItemIndex: Int,
    val totalItemsCount: Int,
    val canScrollForward: Boolean = lastVisibleItemIndex < totalItemsCount - 1
)

data class OlderHistoryAnchor(val tailRevision: Long, val olderRevision: Long,
    val visible: List<Pair<String, Int>>)

internal data class TimelineLayoutSignal(
    val following: Boolean, val explicitJump: Boolean, val hydrationJump: Boolean,
    val preservePrepend: Boolean, val anchor: OlderHistoryAnchor?,
    val olderRevision: Long, val tailRevision: Long, val hasTimeline: Boolean, val loadingEarlier: Boolean,
    val canScrollForward: Boolean, val totalItems: Int, val viewportStart: Int,
    val viewportEnd: Int, val visible: List<Triple<Any, Int, Int>>, val animating: Boolean
)

object TimelineFollowPolicy {
    fun anchorPending(anchor: OlderHistoryAnchor, loadingEarlier: Boolean, olderRevision: Long): Boolean =
        loadingEarlier || olderRevision != anchor.olderRevision

    /** Geometry changes request direct correction, never change user intent. */
    fun shouldCorrectLayout(following: Boolean, canScrollForward: Boolean,
        hydrationJump: Boolean, preservePrepend: Boolean, animating: Boolean): Boolean =
        following && !preservePrepend && !animating && (hydrationJump || canScrollForward)

    fun olderAnchorTarget(anchor: OlderHistoryAnchor, displayKeys: List<String>, tailRevision: Long,
        userMoved: Boolean): Pair<Int, Int>? {
        if (userMoved || anchor.tailRevision != tailRevision) return null
        return anchor.visible.firstNotNullOfOrNull { (key, offset) ->
            displayKeys.indexOf(key).takeIf { it >= 0 }?.let { it to offset }
        }
    }


    /** End sentinel follows all display rows, including the optional history header. */
    fun bottomTargetIndex(displayItemCount: Int): Int = displayItemCount.coerceAtLeast(0)

    /** Only user movement away disables following; new layout/content never does. */
    fun followingAfterScroll(following: Boolean, movingToEarlier: Boolean, atBottom: Boolean): Boolean =
        when {
            movingToEarlier -> false
            atBottom -> true
            else -> following
        }

    /** The end must be reached, not merely the start of a tall final row. */
    fun isPinnedNearEnd(viewport: LazyListViewport): Boolean =
        viewport.totalItemsCount <= 0 || !viewport.canScrollForward

    /**
     * The new-activity affordance appears only while the user has scrolled up
     * away from the latest row, the timeline has content to return to, and the
     * initial load has finished. PR #40 review F1: the screen previously
     * derived `!isInitialLoading && timeline.isNotEmpty()` inside an unkeyed
     * `remember { derivedStateOf { ... } }` that captured plain `val`s by
     * value at first composition — the affordance froze in both flows
     * (new-session captured `emptyList()`, hydrated-session captured
     * `isInitialLoading = true`). The full decision now lives here so the
     * wiring passes every non-viewport axis as keyed arguments.
     */
    fun shouldShowNewActivityAffordance(
        viewport: LazyListViewport,
        timelineNonEmpty: Boolean,
        isInitialLoading: Boolean
    ): Boolean =
        !isInitialLoading &&
            timelineNonEmpty &&
            !isPinnedNearEnd(viewport)

    /**
     * Cheap change signature for the follow effect. It tracks the timeline
     * size plus the last row's identity-and-content digest, so same-row text
     * growth, appended activity rows, and terminal events all change it while
     * pure presentation toggles (card expansion) do not. A middle-row in-place
     * update deliberately does not re-trigger following — the viewport follows
     * the latest row, per the frozen S6 acceptance row.
     */
    fun contentSignature(timeline: List<TimelineEntry>): String {
        if (timeline.isEmpty()) return "empty"
        val lastDigest = when (val last = timeline.last()) {
            is UserMessage -> "u:${last.text.length}"
            is AssistantMessage -> "a:${last.text.length}:${last.state}"
            is ReasoningEntry -> "r:${last.text.length}"
            is ToolActivity -> "t:${last.status}:${last.resultSummary?.length ?: -1}"
            is GenericGatewayActivity -> "g:${last.summary.length}"
            is TurnNotice -> "n:${last.message.length}"
            is ConnectionNotice -> "c:${last.message.length}"
            is LocalDraftNotice -> "d:${last.message.length}"
            is ModelChangeNoticeRow -> "mcr:${last.text.length}"
            is AgentNoteEntry -> "an:${last.text.length}"
        }
        return "${timeline.size}:$lastDigest"
    }
}

/**
 * Stateful follow decisions across timeline changes. Hydration (history
 * replacement) is marked when the initial load starts; the next non-empty
 * timeline change then consumes the pending jump so hydration lands on the
 * latest row instantly and never animates row-by-row (TASK-027).
 *
 * PR #40 review minor (a): the shell reuses the `ChatScreen` composition slot
 * across session switches (compact and expanded), so the screen keys this
 * machine per session (`remember(sessionId)`) — an unkeyed remember would
 * carry an unconsumed hydration jump from session A into session B.
 */
class TimelineFollowStateMachine {

    private var hydrationJumpPending = false
    private var observedOlderRevision = 0L

    /** Call when hydration/history replacement starts (initial loading). */
    fun markHydrationStarted() {
        hydrationJumpPending = true
    }

    /**
     * Decision for the current timeline change. The hydration jump is consumed
     * by the first non-empty timeline change after it was requested; later
     * changes fall back to the pinned/unpinned rule.
     */
    fun onTimelineChanged(pinned: Boolean, timelineNonEmpty: Boolean, olderRevision: Long = 0): FollowDecision {
        if (olderRevision != observedOlderRevision) {
            observedOlderRevision = olderRevision
            hydrationJumpPending = false
            return FollowDecision.PRESERVE_VIEWPORT
        }
        if (!timelineNonEmpty) return FollowDecision.PRESERVE_VIEWPORT
        if (hydrationJumpPending) {
            hydrationJumpPending = false
            return FollowDecision.JUMP_TO_LATEST
        }
        return if (pinned) FollowDecision.FOLLOW_LATEST else FollowDecision.PRESERVE_VIEWPORT
    }
}
