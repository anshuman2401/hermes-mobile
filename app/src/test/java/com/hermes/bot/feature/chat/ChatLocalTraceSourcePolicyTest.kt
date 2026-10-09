package com.hermes.bot.feature.chat

import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.session.S4bBaselineFixture
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Finite S7 source/display witnesses; no device viewport claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLocalTraceSourcePolicyTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun P01_currentStoredPublicationHidesOnlyCachedCard() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { f ->
            f.initialize()
            val original = seedTrace(f.cache)
            val vm = f.vm()
            f.awaitLoaded(vm)
            val state = vm.uiState.value
            assertEquals(original, state.cachedToolTrace)
            assertNull(state.savedSnapshotCapturedAtMillis)
            val display = project(state)
            assertTrue("stored Actions remain", display.any { it is TraceDisplayEntry.BlockCard })
            assertEquals(ToolBlockPolicy.buildBlockDisplayEntries(state.timeline, state.trace, 1000L),
                display.filterNot { it is TraceDisplayEntry.CachedTrace })
            assertFalse("accepted current stored history must suppress the redundant phone card",
                display.any { it is TraceDisplayEntry.CachedTrace })
            assertEquals(original, readTrace(f.cache))
        }
    }

    @Test fun P02_initialStoredFailureKeepsCompactAndLocalProvenance() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize()
            val original = seedTrace(f.cache)
            // Successful headers, failed body decoding: the existing compact fallback remains admitted.
            f.storedJson = "Synthetic invalid stored body"
            val state = f.loaded(f.vm())
            assertFalse(state.hasAcceptedStoredHistory)
            assertNull(state.savedSnapshotCapturedAtMillis)
            assertTrue(state.timeline.filterIsInstance<AssistantMessage>().any { it.text == "Synthetic compact answer" })
            assertEquals(original, project(state).filterIsInstance<TraceDisplayEntry.CachedTrace>().single().entries)
            assertNotNull(state.errorMessage)
            assertEquals(original, readTrace(f.cache))
        } finally { f.close() }
    }

    @Test fun P03_actualOfflineSnapshotRetainsCardAndComposerGate() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize()
            val original = seedTrace(f.cache)
            f.gateway.disconnect()
            val state = f.joined(f.vm())
            assertFalse(state.hasAcceptedStoredHistory)
            assertNotNull(state.savedSnapshotCapturedAtMillis)
            assertNull(state.runtimeSessionId)
            assertNull(state.submitBlockedReason)
            assertFalse(state.copy(composerDraft = "Synthetic draft").isComposerReady)
            assertEquals(original, project(state).filterIsInstance<TraceDisplayEntry.CachedTrace>().single().entries)
            assertEquals(0, f.httpCalls.get())
            assertEquals(0, f.resumeCalls.get())
            assertEquals(original, readTrace(f.cache))
        } finally { f.close() }
    }

    @Test fun P04_realLoadReplacementClearsSuppressionBeforeCompactFallback() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize()
            val original = seedTrace(f.cache)
            val vm = f.vm()
            assertTrue(f.loaded(vm).hasAcceptedStoredHistory)
            // Successful headers, failed body decoding: the existing compact fallback remains admitted.
            f.storedJson = "Synthetic invalid stored body"
            vm.loadSession()
            assertFalse("actual load entry resets source provenance", vm.uiState.value.hasAcceptedStoredHistory)
            val replaced = f.loaded(vm)
            assertFalse(replaced.hasAcceptedStoredHistory)
            assertEquals(original, project(replaced).filterIsInstance<TraceDisplayEntry.CachedTrace>().single().entries)
            assertEquals(2, f.httpCalls.get())
        } finally { f.close() }
    }

    @Test fun P05_emptyAcceptedTailPolicyDefaultsAndRetainedRefresh() = runBlocking(main.testDispatcher) {
        val f = S4bBaselineFixture(temp.newFolder(), main.testDispatcher)
        try {
            f.initialize()
            val original = seedTrace(f.cache)
            f.storedJson = """{"session_id":"synthetic-session","profile":"default","messages":[],"pagination":{"limit":120,"offset":0,"order":"latest","returned":0}}"""
            val vm = f.vm()
            val empty = f.loaded(vm)
            assertTrue("valid empty publication is accepted, independent of tool count", empty.hasAcceptedStoredHistory)
            assertFalse(project(empty).any { it is TraceDisplayEntry.CachedTrace })
            assertEquals(original, empty.cachedToolTrace)

            // Load a populated stored source, then retain that source across an actual failed refresh.
            f.storedJson = null
            vm.loadSession()
            val before = f.loaded(vm)
            assertTrue(before.hasAcceptedStoredHistory)
            // Successful headers, failed body decoding: the existing compact fallback remains admitted.
            f.storedJson = "Synthetic invalid stored body"
            vm.refreshSession()
            assertTrue("refresh entry is not a tail replacement", vm.uiState.value.hasAcceptedStoredHistory)
            val after = f.joined(vm)
            assertTrue(after.hasAcceptedStoredHistory)
            assertNotNull(after.refreshErrorMessage)
            assertFalse(after.isRefreshing)
            assertEquals(before.timeline, after.timeline)
            assertFalse(project(after).any { it is TraceDisplayEntry.CachedTrace })
            assertEquals(original, after.cachedToolTrace)
            assertEquals(original, readTrace(f.cache))
            assertEquals(3, f.httpCalls.get())

            val trace = TraceState("synthetic-turn", 1L)
            val timeline = listOf(UserMessage("synthetic-turn", "Synthetic question"),
                AssistantMessage("asst-synthetic-turn", "Synthetic answer"),
                ToolActivity(id = "synthetic-step", toolId = "synthetic-tool", name = "terminal", status = ToolStatus.COMPLETED))
            val blocks = ToolBlockPolicy.buildBlockDisplayEntries(timeline, trace, 1000L)
            val legacy = ChatUiState(timeline = timeline, trace = trace, cachedToolTrace = original)
            assertFalse(legacy.hasAcceptedStoredHistory)
            assertEquals(blocks + TraceDisplayEntry.CachedTrace(original), project(legacy))
            assertEquals(blocks, project(legacy.copy(hasAcceptedStoredHistory = true)))
            assertEquals(blocks + TraceDisplayEntry.CachedTrace(original),
                project(legacy.copy(hasAcceptedStoredHistory = true, savedSnapshotCapturedAtMillis = 1L)))
            assertEquals(blocks, project(legacy.copy(cachedToolTrace = emptyList())))
            assertEquals(blocks, ExecutionTracePolicy.buildBlockDisplayEntries(timeline, trace, nowMillis = 1000L))
            assertEquals(original, ExecutionTracePolicy.cachedToolTraceForDisplay(original))
            val block = blocks.filterIsInstance<TraceDisplayEntry.BlockCard>().single().block
            assertTrue(block.isLive)
            assertEquals(1, block.totalActionCount)
            assertTrue(ExecutionTracePolicy.buildDisplayEntries(timeline, trace).any { it is TraceDisplayEntry.Header })

            val direct = java.io.File("src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt")
            val screen = (if (direct.exists()) direct else java.io.File("app/src/main/java/com/hermes/bot/feature/chat/ChatScreen.kt")).readText()
            val projection = screen.substring(screen.indexOf("val displayEntries = remember"), screen.indexOf("var activeSheetBlock"))
            // Owner decision 2026-10-07 supersedes conditional screen wiring:
            // cached observations are omitted even for offline/compact fallback.
            assertTrue(projection.contains("ExecutionTracePolicy.buildBlockDisplayEntries("))
            assertFalse(projection.contains("cachedToolTrace"))
            assertFalse(projection.contains("hasAcceptedStoredHistory"))
            assertFalse(projection.contains("savedSnapshotCapturedAtMillis"))
            assertFalse(screen.contains("CachedToolTraceCard("))
            assertTrue(screen.contains("is TraceDisplayEntry.CachedTrace -> Unit"))

        } finally { f.close() }
    }

    private fun project(state: ChatUiState): List<TraceDisplayEntry> =
        ExecutionTracePolicy.buildBlockDisplayEntries(state.timeline, state.trace,
            ExecutionTracePolicy.cachedToolTraceForDisplay(state.cachedToolTrace,
                state.hasAcceptedStoredHistory, state.savedSnapshotCapturedAtMillis), 1000L)

    private suspend fun seedTrace(cache: ConversationCacheRepository): List<CachedToolTraceEntry> {
        val now = System.currentTimeMillis()
        assertTrue(cache.replaceRemoteSnapshot("synthetic-session", SessionResumeResult(
            runtimeSessionId = "synthetic-runtime", durableSessionId = "synthetic-session", messageCount = 2,
            messages = listOf(SessionMessage("user", "Synthetic cached question"),
                SessionMessage("assistant", "Synthetic cached answer"))), now) is ConversationCacheWriteResult.Written)
        assertTrue(cache.recordObservedToolEvent("synthetic-session", "synthetic-runtime",
            GatewayEvent.ToolStart(sessionId = "synthetic-runtime", seq = 1, toolId = "synthetic-local-tool",
                name = "terminal", context = "Synthetic context"), now) is ConversationCacheWriteResult.Written)
        return readTrace(cache).also { assertEquals(1, it.size) }
    }

    private suspend fun readTrace(cache: ConversationCacheRepository): List<CachedToolTraceEntry> =
        ExecutionTracePolicy.buildCachedToolTraceEntryList(
            (cache.load("synthetic-session") as ConversationCacheReadResult.Snapshot).value.localTrace)
}
