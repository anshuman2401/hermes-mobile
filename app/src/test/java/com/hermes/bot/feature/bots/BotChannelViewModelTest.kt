package com.hermes.bot.feature.bots

import com.hermes.bot.data.context.*
import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.data.session.*
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.feature.chat.ChatComposerSingleFlightTest
import com.hermes.bot.feature.sessions.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotChannelViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private class Previews : BotThreadPreviewRepository {
        var gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val ids = mutableListOf<String>()
        var nonCooperative = false
        override suspend fun getReply(profile: String, durableId: String): BotThreadReply {
            ids += durableId; active++; maxActive = maxOf(active, maxActive)
            try {
                if (nonCooperative) withContext(NonCancellable) { gate.await() } else gate.await()
                return BotThreadReply("synthetic reply")
            } finally { active-- }
        }
    }
    @Test fun visibleRowsAreBoundedDeduplicatedAndOfflineClearsContent() = runTest {
        val previews = Previews(); val vm = BotChannelViewModel(previews)
        vm.enter("Alpha", "Alpha", null)
        val ids = (1..8).map { "synthetic-$it" }
        vm.showThreadPreviews(ids, true); runCurrent()
        assertEquals(4, previews.ids.size)
        vm.showThreadPreviews(ids, true); previews.gate.complete(Unit); advanceUntilIdle()
        assertEquals(8, previews.ids.size); assertEquals(4, previews.maxActive)
        assertEquals(ids.toSet(), vm.uiState.value.threadReplies.keys)
        vm.showThreadPreviews(emptyList(), true); vm.showThreadPreviews(ids, true); advanceUntilIdle()
        assertEquals(8, previews.ids.size)
        vm.showThreadPreviews(ids, false); advanceUntilIdle()
        assertTrue(vm.uiState.value.threadReplies.isEmpty()); assertEquals(8, previews.ids.size)
    }
    @Test fun routeExitProfileChangeAndDisconnectRejectLateNonCooperativeResults() = runTest {
        for (action in listOf("exit", "profile", "disconnect")) {
            val previews = Previews().apply { nonCooperative = true }
            val vm = BotChannelViewModel(previews); vm.enter("Alpha", "Alpha", null)
            vm.showThreadPreviews(listOf("synthetic-one"), true); runCurrent()
            when (action) {
                "exit" -> vm.leaveChannel()
                "profile" -> vm.enter("Beta", "Beta", null)
                else -> vm.clearConnection()
            }
            previews.gate.complete(Unit); advanceUntilIdle()
            assertTrue(vm.uiState.value.threadReplies.isEmpty())
        }
    }
    private class Sessions : SessionRepository by ChatComposerSingleFlightTest.SingleFlightSessionRepository() {
        var creates = 0
        var gate = CompletableDeferred<Unit>()
        var failure = false
        override suspend fun listSessions(forceRefresh: Boolean) = Result.success(SessionListOutcome(emptyList(), false, selectedProfileName = "Alpha"))
        override suspend fun createDraftSession(title: String?): Result<SessionHandle> {
            creates++; gate.await()
            return if (failure) Result.failure(IllegalStateException("synthetic failure"))
            else Result.success(SessionHandle("synthetic-created", "synthetic-runtime", isDraft = true))
        }
    }
    @Test fun doubleSendCreatesOnceAndFailedOpenRetainsSameId() = runTest {
        val vm = BotChannelViewModel(); vm.enter("Alpha", "Alpha", null)
        val repo = Sessions(); val sessions = SessionsViewModel(repo); advanceUntilIdle()
        vm.updateDraft("synthetic prompt")
        vm.send(sessions, true) { _, _ -> false }; vm.send(sessions, true) { _, _ -> false }
        runCurrent(); assertEquals(1, repo.creates)
        repo.gate.complete(Unit); advanceUntilIdle()
        assertEquals("synthetic-created", vm.uiState.value.createdSessionId)
        assertEquals("synthetic prompt", vm.uiState.value.draft)
        vm.send(sessions, true) { _, _ -> fail("must not create again"); false }
        vm.openCreated { id, entry -> assertEquals("synthetic-created", id); assertEquals("synthetic prompt", entry.text); true }
        assertEquals(1, repo.creates); assertEquals("", vm.uiState.value.draft)
    }
    @Test fun failureRetainsDraftAndConnectionClearRejectsLateNavigation() = runTest {
        for (clear in listOf(false, true)) {
            val vm = BotChannelViewModel(); vm.enter("Alpha", "Alpha", null)
            val repo = Sessions().apply { failure = !clear }; val sessions = SessionsViewModel(repo); advanceUntilIdle()
            vm.updateDraft("synthetic prompt"); vm.send(sessions, true) { _, _ -> fail("must not open"); false }; runCurrent()
            if (clear) vm.clearConnection()
            repo.gate.complete(Unit); advanceUntilIdle()
            assertFalse(vm.uiState.value.transferring)
            if (clear) assertNull(vm.uiState.value.channel) else {
                assertEquals("synthetic prompt", vm.uiState.value.draft); assertNotNull(vm.uiState.value.notice)
            }
        }
    }
    @Test fun switchingBotsRestoresDraftAndScrollWithoutCreatingOrTransferring() {
        val vm = BotChannelViewModel()
        vm.enter("Alpha", "Alpha", null, "synthetic-profile", "http://example.invalid")
        vm.updateDraft("synthetic alpha draft")
        vm.saveScrollPosition("Alpha", 3, 18, "synthetic-profile", "http://example.invalid")
        vm.enter("Beta", "Beta", null, "synthetic-profile", "http://example.invalid")
        assertEquals("", vm.uiState.value.draft)
        vm.updateDraft("synthetic beta draft")
        vm.enter("Alpha", "Alpha", null, "synthetic-profile", "http://example.invalid")
        assertEquals("synthetic alpha draft", vm.uiState.value.draft)
        assertEquals(3, vm.uiState.value.scrollIndex); assertEquals(18, vm.uiState.value.scrollOffset)
        assertFalse(vm.uiState.value.transferring); assertNull(vm.uiState.value.createdSessionId)
        vm.clearConnection()
        vm.saveScrollPosition("Alpha", 9, 20, "synthetic-profile", "http://example.invalid")
        vm.enter("Alpha", "Alpha", null, "other-profile", "http://example.invalid")
        assertEquals("", vm.uiState.value.draft); assertEquals(0, vm.uiState.value.scrollIndex)
    }

    @Test fun reselectingSameBotAfterAnotherWorkspaceRequiresSelection() {
        assertFalse(needsBotSelection("Alpha", "Alpha", "Alpha"))
        assertTrue(needsBotSelection("Alpha", "Alpha", "Beta"))
        assertTrue(needsBotSelection("Alpha", null, "Alpha"))
        assertTrue(needsBotSelection("Alpha", "Alpha", null))
    }

    @Test fun scopedListAdmissionAndMissingFieldsRemainHonest() {
        val state = SessionsUiState(selectedProfileName = "Alpha")
        assertTrue(isChannelListReady("Alpha", state))
        assertFalse(isChannelListReady("Alpha", state.copy(selectedProfileName = "Beta")))
        assertFalse(isChannelListReady("Alpha", state.copy(isLoading = true)))
        assertFalse(isChannelListReady("Alpha", state.copy(staleProfileNotice = "synthetic notice")))
        val row = SessionSummary("synthetic", " ", " ", Double.NaN, -1, null).threadPresentation()
        assertEquals("Untitled conversation", row.title); assertNull(row.opening); assertNull(row.startedAt); assertNull(row.messageCount)
        assertFalse(row.toString().contains("Untitled"))
    }
    @Test fun matchingConversationRowsStayVisibleDuringRefreshWithoutEnablingActions() {
        val state = SessionsUiState(selectedProfileName = "Alpha", isLoading = true)
        assertTrue(isChannelListVisible("Alpha", state))
        assertFalse(isChannelListReady("Alpha", state))
        assertFalse(isChannelListVisible("Beta", state))
        assertFalse(isChannelListVisible(null, state))
        assertFalse(isChannelListVisible("Alpha", state.copy(staleProfileNotice = "Synthetic notice")))
        assertTrue(isChannelListReady("Alpha", state.copy(isLoading = false)))
    }

}
