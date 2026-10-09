package com.hermes.bot.feature.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S6 (TASK-028): deterministic JVM behavior tests for user-controlled
 * timeline following (D-05 / REQ-006).
 *
 * These tests cover timeline decisions at the JVM tier over the extracted
 * [TimelineFollowPolicy]/[TimelineFollowStateMachine] policy — the same pure
 * decision surface `ChatScreen` wires into `LazyListState` — with the
 * rendered-viewport rows requiring separate device/emulator execution.
 *
 * Learnings applied: assert state transitions, not call counts; every frozen
 * axis gets boundary probes (M-07 scroll rows).
 */
class TimelineFollowPolicyTest {
    @org.junit.Test fun olderHistoryAnchorPreservesDisplayKeyAndOffset() {
        val anchor = OlderHistoryAnchor(7, 3, listOf("stored-b" to 23, "stored-c" to -40))
        val keys = listOf("history-begin-indicator", "day-older", "stored-a", "day-current", "stored-b", "stored-c")
        org.junit.Assert.assertEquals(4 to 23, TimelineFollowPolicy.olderAnchorTarget(anchor, keys, 7, false))
        org.junit.Assert.assertEquals(4 to -40, TimelineFollowPolicy.olderAnchorTarget(anchor, keys.filterNot { it == "stored-b" }, 7, false))
        org.junit.Assert.assertNull(TimelineFollowPolicy.olderAnchorTarget(anchor, keys, 8, false))
        org.junit.Assert.assertNull(TimelineFollowPolicy.olderAnchorTarget(anchor, keys, 7, true))
        org.junit.Assert.assertNull(TimelineFollowPolicy.olderAnchorTarget(anchor, listOf("absent"), 7, false))
        val follow = TimelineFollowStateMachine()
        follow.markHydrationStarted()
        org.junit.Assert.assertEquals(FollowDecision.PRESERVE_VIEWPORT, follow.onTimelineChanged(true, true, 4))
        org.junit.Assert.assertEquals(FollowDecision.FOLLOW_LATEST, follow.onTimelineChanged(true, true, 4))
        org.junit.Assert.assertEquals(FollowDecision.PRESERVE_VIEWPORT, follow.onTimelineChanged(false, true, 4))
    }


    @Test
    fun tallLatestRowVisibleButEndOffscreen_isNotPinnedAndShowsArrow() {
        val viewport = LazyListViewport(9, 9, 10, canScrollForward = true)
        assertFalse(TimelineFollowPolicy.isPinnedNearEnd(viewport))
        assertTrue(TimelineFollowPolicy.shouldShowNewActivityAffordance(viewport, true, false))
        assertTrue(TimelineFollowPolicy.isPinnedNearEnd(viewport.copy(canScrollForward = false)))
        assertFalse(TimelineFollowPolicy.shouldShowNewActivityAffordance(
            viewport.copy(canScrollForward = false), true, false
        ))
    }

    @Test
    fun bottomTargetIsAfterEveryDisplayRow_includingHeadersAndSeparators() {
        val displayKeys = listOf("history-begin-indicator", "day", "message", "tall-message")
        val target = TimelineFollowPolicy.bottomTargetIndex(displayKeys.size)
        assertEquals(4, target)
        assertTrue(target > displayKeys.lastIndex)
        assertEquals(0, TimelineFollowPolicy.bottomTargetIndex(0))
    }

    @Test
    fun streamingGrowthDoesNotUnpinFollowIntent_whenLayoutCanStillScroll() {
        val machine = TimelineFollowStateMachine()
        machine.markHydrationStarted()
        var following = true
        assertEquals(FollowDecision.JUMP_TO_LATEST, machine.onTimelineChanged(following, true))
        repeat(4) {
            following = TimelineFollowPolicy.followingAfterScroll(following, false, false)
            assertEquals(FollowDecision.FOLLOW_LATEST, machine.onTimelineChanged(following, true))
        }
    }

    @Test
    fun upwardReadingWithinTallFinalMessage_pausesGrowthAndAppendUntilBottom() {
        val machine = TimelineFollowStateMachine()
        var following = TimelineFollowPolicy.followingAfterScroll(true, true, false)
        repeat(4) {
            following = TimelineFollowPolicy.followingAfterScroll(following, false, false)
            assertEquals(FollowDecision.PRESERVE_VIEWPORT, machine.onTimelineChanged(following, true))
        }
        following = TimelineFollowPolicy.followingAfterScroll(following, false, true)
        assertEquals(FollowDecision.FOLLOW_LATEST, machine.onTimelineChanged(following, true))
    }

    @Test
    fun earlierHistoryPreservesAnchorAndDoesNotReenablePausedFollowing() {
        val machine = TimelineFollowStateMachine()
        val anchor = OlderHistoryAnchor(3, 0, listOf("tall-message" to 760))
        val prepended = listOf("history-begin-indicator", "older-day", "older-message", "day", "tall-message")
        assertEquals(4 to 760, TimelineFollowPolicy.olderAnchorTarget(anchor, prepended, 3, false))
        assertEquals(FollowDecision.PRESERVE_VIEWPORT, machine.onTimelineChanged(false, true, 1))
        assertEquals(FollowDecision.PRESERVE_VIEWPORT, machine.onTimelineChanged(false, true, 1))
    }

    @Test
    fun screenUsesLayoutCollectorAndEndTarget_andUserIntentIsIndependentOfLayout() {
        val candidates = listOf(
            java.io.File("src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt"),
            java.io.File("app/src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt")
        )
        val screen = candidates.first { it.exists() }.readText()
        assertTrue(screen.contains("TimelineFollowPolicy.bottomTargetIndex(currentDisplayKeys.size)"))
        assertTrue(screen.contains("TimelineLayoutSignal("))
        assertTrue(screen.contains("withFrameNanos { }"))
        assertTrue(screen.contains("item(key = \"timeline-bottom\")"))
        assertTrue(screen.contains("Spacer(Modifier.height(0.dp))"))
        assertTrue(screen.contains("onTimelineChanged(followingLatest, timeline.isNotEmpty(), olderRevision)"))
        assertTrue(screen.contains("NestedScrollSource.UserInput && available.y > 0f"))
        val arrow = screen.substring(screen.indexOf("if (showNewActivityAffordance)"))
        assertTrue(arrow.contains("followingLatest = true"))
    }

    @Test
    fun failedOrNoOpEarlierRequestCannotBlockFollowing() {
        val anchor = OlderHistoryAnchor(0, 2, emptyList())
        assertFalse(TimelineFollowPolicy.anchorPending(anchor, false, 2))
        assertTrue(TimelineFollowPolicy.anchorPending(anchor, true, 2))
        assertTrue(TimelineFollowPolicy.anchorPending(anchor, false, 3))
    }

    @Test
    fun layoutGrowthCorrectsOnlyWhileFollowingAndNotAnchoringOrAnimating() {
        assertTrue(TimelineFollowPolicy.shouldCorrectLayout(true, true, false, false, false))
        assertFalse(TimelineFollowPolicy.shouldCorrectLayout(false, true, false, false, false))
        assertFalse(TimelineFollowPolicy.shouldCorrectLayout(true, false, false, false, false))
        assertFalse(TimelineFollowPolicy.shouldCorrectLayout(true, true, false, true, false))
        assertFalse(TimelineFollowPolicy.shouldCorrectLayout(true, true, false, false, true))
        assertTrue(TimelineFollowPolicy.shouldCorrectLayout(true, false, true, false, false))
    }

    // ------------------------------------------------------------------
    // Pinning rule (M-07: pinned stream / user scrolled up)
    // ------------------------------------------------------------------

    @Test
    fun pinned_whenLastItemIsLastVisible_followsLatestRow() {
        val pinned = TimelineFollowPolicy.isPinnedNearEnd(
            LazyListViewport(
                firstVisibleItemIndex = 8,
                lastVisibleItemIndex = 9,
                totalItemsCount = 10
            )
        )
        assertTrue(pinned)
    }

    @Test
    fun pinned_whenOnlyLastRowVisible() {
        val pinned = TimelineFollowPolicy.isPinnedNearEnd(
            LazyListViewport(
                firstVisibleItemIndex = 9,
                lastVisibleItemIndex = 9,
                totalItemsCount = 10
            )
        )
        assertTrue(pinned)
    }

    @Test
    fun unpinned_whenLastRowNotVisible_readingPositionPreserved() {
        val pinned = TimelineFollowPolicy.isPinnedNearEnd(
            LazyListViewport(
                firstVisibleItemIndex = 0,
                lastVisibleItemIndex = 4,
                totalItemsCount = 10
            )
        )
        assertFalse(pinned)
    }

    @Test
    fun emptyList_countsAsPinned_firstAppearingRowIsFollowed() {
        val pinned = TimelineFollowPolicy.isPinnedNearEnd(
            LazyListViewport(
                firstVisibleItemIndex = 0,
                lastVisibleItemIndex = -1,
                totalItemsCount = 0
            )
        )
        assertTrue(pinned)
    }

    // ------------------------------------------------------------------
    // Follow decisions (M-07: same-row growth, appended activity, terminal)
    // ------------------------------------------------------------------

    @Test
    fun pinnedFollows_sameRowTextGrowth() = runTest {
        val machine = TimelineFollowStateMachine()
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    @Test
    fun pinnedFollows_appendedActivityRow() = runTest {
        val machine = TimelineFollowStateMachine()
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    @Test
    fun pinnedFollows_terminalEvent() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    @Test
    fun unpinnedNeverForcesViewport_forGrowthAppendOrTerminal() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)

        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
    }

    @Test
    fun unpinningThenRepinning_restoresFollow() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    @Test
    fun emptyTimelineChange_neverForcesViewport() = runTest {
        val machine = TimelineFollowStateMachine()
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = false)
        )
    }

    // ------------------------------------------------------------------
    // Initial hydration (M-07: initial history — no row-by-row animation)
    // ------------------------------------------------------------------

    @Test
    fun hydration_jumpsToLatestInstantly_notAnimated() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.markHydrationStarted()
        assertEquals(
            FollowDecision.JUMP_TO_LATEST,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
    }

    @Test
    fun hydrationJump_consumedExactlyOnce_laterChangesUsePinnedRule() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.markHydrationStarted()
        machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        // After the jump is consumed, an unpinned viewport stays preserved.
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
    }

    @Test
    fun hydration_withEmptyHistory_consumesOnFirstNonEmptyChange_only() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.markHydrationStarted()
        // Hydration resolved to an empty timeline: no jump, but the pending
        // request stays until a non-empty change arrives.
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = false)
        )
        // The next appended row (e.g. first admitted message) still lands on
        // the latest row instantly.
        assertEquals(
            FollowDecision.JUMP_TO_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    // ------------------------------------------------------------------
    // Interleaved activity (M-07: interleaved activity rows while streaming)
    // ------------------------------------------------------------------

    @Test
    fun interleavedActivity_betweenDeltas_pinnedFollows_unpinnedPreserves() = runTest {
        val machine = TimelineFollowStateMachine()

        // delta
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
        // interleaved tool activity appended while pinned
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
        // user scrolls up; further growth (deltas + tool rows) never forces
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
        // terminal event while unpinned: still no forced viewport
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
    }

    // ------------------------------------------------------------------
    // Affordance visibility (M-07: new-activity affordance semantics)
    // ------------------------------------------------------------------

    @Test
    fun affordance_hiddenWhenPinned_shownWhenScrolledUp() {
        val viewportAtEnd = LazyListViewport(4, 5, 6)
        assertFalse(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = viewportAtEnd,
                timelineNonEmpty = true,
                isInitialLoading = false
            )
        )

        val viewportScrolledUp = LazyListViewport(0, 2, 6)
        assertTrue(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = viewportScrolledUp,
                timelineNonEmpty = true,
                isInitialLoading = false
            )
        )
    }

    @Test
    fun affordance_hiddenForEmptyTimeline_evenWhenViewportEmpty() {
        assertFalse(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = LazyListViewport(0, -1, 0),
                timelineNonEmpty = false,
                isInitialLoading = false
            )
        )
    }

    // Repair 1 (PR #40 review F1 + minors): the wiring's affordance derivation
    // captured plain `timeline` / `isInitialLoading` vals by value at first
    // composition, so the affordance froze on the first frame in both the
    // new-session (empty list captured) and hydrated-session (isInitialLoading
    // = true captured) flows. The full affordance decision — including the
    // hydration guard and the content axis — is consolidated here so the
    // wiring feeds it as arguments (keyed per recomposition) instead of
    // capturing plain vals inside an unkeyed `derivedStateOf`.

    @Test
    fun affordance_hydrationGuard_hiddenWhileInitialLoading_evenWhenScrolledUpWithContent() {
        val scrolledUp = LazyListViewport(0, 2, 6)
        assertFalse(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = scrolledUp,
                timelineNonEmpty = true,
                isInitialLoading = true
            )
        )
    }

    @Test
    fun affordance_contentAxis_hiddenForEmptyTimeline_evenWhenScrolledUpAndNotLoading() {
        // New-session flow: an empty admitted timeline must never show the
        // affordance, regardless of the (empty) viewport snapshot.
        val emptyViewport = LazyListViewport(0, -1, 0)
        assertFalse(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = emptyViewport,
                timelineNonEmpty = false,
                isInitialLoading = false
            )
        )
    }

    @Test
    fun affordance_fullDecision_shownOnlyWhenScrolledUpWithContentAndNotLoading() {
        assertTrue(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = LazyListViewport(0, 2, 6),
                timelineNonEmpty = true,
                isInitialLoading = false
            )
        )
        assertFalse(
            TimelineFollowPolicy.shouldShowNewActivityAffordance(
                viewport = LazyListViewport(4, 5, 6),
                timelineNonEmpty = true,
                isInitialLoading = false
            )
        )
    }

    // ------------------------------------------------------------------
    // Per-session follow state (PR #40 review minor a)
    // ------------------------------------------------------------------

    @Test
    fun hydrationPendingFlag_livesOnTheMachineInstance_freshMachinePerSessionStartsClean() = runTest {
        // Documents why ChatScreen keys followStateMachine per session: the
        // shell reuses the ChatScreen composition slot across session
        // switches, so an unkeyed remember would hand session B a machine
        // still carrying session A's unconsumed hydration jump (pending-flag
        // leakage across sessions).
        val leakedFromPreviousSession = TimelineFollowStateMachine()
        leakedFromPreviousSession.markHydrationStarted()
        // Session A never admitted a row before the switch; session B's first
        // non-empty change would wrongly consume A's hydration jump.
        assertEquals(
            FollowDecision.JUMP_TO_LATEST,
            leakedFromPreviousSession.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
        // The keyed (per-session) machine the repair wires starts clean.
        val freshForNextSession = TimelineFollowStateMachine()
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            freshForNextSession.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    // ------------------------------------------------------------------
    // Content signature (what counts as a follow-triggering timeline change)
    // ------------------------------------------------------------------

    @Test
    fun contentSignature_sameRowAssistantGrowth_changes() {
        val before = listOf(
            userRow("u1"),
            AssistantMessage(id = "a1", text = "Hello")
        )
        val after = listOf(
            userRow("u1"),
            AssistantMessage(id = "a1", text = "Hello, streamed world")
        )
        assertTrue(
            TimelineFollowPolicy.contentSignature(before) !=
                TimelineFollowPolicy.contentSignature(after)
        )
    }

    @Test
    fun contentSignature_appendedActivityRow_changes() {
        val before = listOf<TimelineEntry>(userRow("u1"))
        val after = before + toolRow("t1")
        assertTrue(
            TimelineFollowPolicy.contentSignature(before) !=
                TimelineFollowPolicy.contentSignature(after)
        )
    }

    @Test
    fun contentSignature_terminalEventOnLastRow_changes() {
        val before = listOf<TimelineEntry>(
            userRow("u1"),
            AssistantMessage(id = "a1", text = "done", state = AssistantMessageState.STREAMING)
        )
        val after = listOf<TimelineEntry>(
            userRow("u1"),
            AssistantMessage(id = "a1", text = "done", state = AssistantMessageState.COMPLETE)
        )
        assertTrue(
            TimelineFollowPolicy.contentSignature(before) !=
                TimelineFollowPolicy.contentSignature(after)
        )
    }

    @Test
    fun contentSignature_presentationOnlyToggle_onMiddleRow_doesNotChange() {
        val before = listOf(
            userRow("u1"),
            toolRow("t1"),
            AssistantMessage(id = "a1", text = "done")
        )
        // Collapse/expand toggle on a middle tool row is presentation-only.
        val after = listOf(
            userRow("u1"),
            toolRow("t1").copy(isExpanded = true),
            AssistantMessage(id = "a1", text = "done")
        )
        assertEquals(
            TimelineFollowPolicy.contentSignature(before),
            TimelineFollowPolicy.contentSignature(after)
        )
    }

    @Test
    fun contentSignature_noteAppend_changesFingerprint() {
        // CHAT-018 PR-A: an appended AgentNoteEntry must change the fingerprint
        // so the pinned timeline keeps following the live narration.
        val before = listOf<TimelineEntry>(userRow("u1"), AssistantMessage(id = "a1", text = ""))
        val after = before + AgentNoteEntry(id = "note-1", text = "working on it")
        assertTrue(
            TimelineFollowPolicy.contentSignature(before) !=
                TimelineFollowPolicy.contentSignature(after)
        )
    }

    @Test
    fun contentSignature_noteEntry_hashesTextLength() {
        // Signature = "<timeline-size>:<last-row digest>"; digest = "an:<len>".
        assertEquals(
            "1:an:13",
            TimelineFollowPolicy.contentSignature(
                listOf(AgentNoteEntry(id = "note-1", text = "working on it"))
            )
        )
        assertEquals(
            "1:an:3",
            TimelineFollowPolicy.contentSignature(listOf(AgentNoteEntry(id = "note-1", text = "abc")))
        )
        // Identity does not participate; length does (append-in-place growth).
        assertEquals(
            "1:an:3",
            TimelineFollowPolicy.contentSignature(listOf(AgentNoteEntry(id = "note-2", text = "abc")))
        )
    }

    @Test
    fun contentSignature_emptyTimeline_hasStableSignature() {
        assertEquals(
            TimelineFollowPolicy.contentSignature(emptyList()),
            TimelineFollowPolicy.contentSignature(emptyList())
        )
    }

    @Test
    fun refreshReplacement_unpinnedViewportPreservesReadingPosition() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        assertEquals(
            FollowDecision.PRESERVE_VIEWPORT,
            machine.onTimelineChanged(pinned = false, timelineNonEmpty = true)
        )
    }

    @Test
    fun refreshReplacement_pinnedViewportFollowsLatest() = runTest {
        val machine = TimelineFollowStateMachine()
        machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        assertEquals(
            FollowDecision.FOLLOW_LATEST,
            machine.onTimelineChanged(pinned = true, timelineNonEmpty = true)
        )
    }

    private fun userRow(id: String) = UserMessage(id = id, text = "prompt $id")

    private fun toolRow(id: String) = ToolActivity(
        id = id,
        toolId = id,
        name = "tool-$id",
        status = ToolStatus.RUNNING
    )
}
