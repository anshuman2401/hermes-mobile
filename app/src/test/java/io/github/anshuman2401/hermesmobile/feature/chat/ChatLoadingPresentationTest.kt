package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.*
import org.junit.Test

class ChatLoadingPresentationTest {
    @Test fun emptyInitialLoadUsesBlockingProgress() {
        val state = ChatUiState(isInitialLoading = true)
        assertTrue(state.showInitialLoading)
        assertFalse(state.showHistoryRefresh)
        assertFalse(state.isComposerReady)
        assertFalse(state.copy(isInitialLoading = false).showInitialLoading)
    }

    @Test fun populatedRecoveryKeepsContentAndDoesNotArmHydrationJump() {
        val rows = listOf(AssistantMessage("synthetic-row", "Synthetic message"))
        val ready = ChatUiState(timeline = rows, runtimeSessionId = "synthetic-runtime", composerDraft = "Synthetic draft")
        val following = TimelineFollowStateMachine()
        following.markHydrationStarted()
        following.onTimelineChanged(true, true, 0)
        val loading = ready.copy(isInitialLoading = true)
        if (loading.showInitialLoading) following.markHydrationStarted()
        assertFalse(loading.showInitialLoading)
        assertTrue(loading.showHistoryRefresh)
        assertFalse(loading.isComposerReady)
        assertEquals(FollowDecision.PRESERVE_VIEWPORT, following.onTimelineChanged(false, true, 0))
        assertFalse(ready.showHistoryRefresh)
        assertTrue(ready.isComposerReady)
        assertEquals(rows, loading.timeline)
    }

    @Test fun manualRefreshAndLabelledSnapshotKeepInlineProgress() {
        val state = ChatUiState(timeline = listOf(AssistantMessage("synthetic-row", "Synthetic message")))
        assertTrue(state.copy(isRefreshing = true).showHistoryRefresh)
        val snapshot = state.copy(isInitialLoading = true, savedSnapshotCapturedAtMillis = 123L)
        assertFalse(snapshot.showInitialLoading)
        assertEquals(123L, snapshot.savedSnapshotCapturedAtMillis)
        assertFalse(snapshot.isComposerReady)
    }
}
