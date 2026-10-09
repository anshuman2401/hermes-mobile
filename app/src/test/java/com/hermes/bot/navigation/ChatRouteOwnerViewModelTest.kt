package com.hermes.bot.navigation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.feature.chat.AssistantMessage
import com.hermes.bot.feature.chat.AssistantMessageState
import com.hermes.bot.feature.chat.ChatStreamingStatus
import com.hermes.bot.feature.chat.ChatViewModel
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * BLD-006 S1 — collector-count probe for the lifecycle-owned Chat collector.
 *
 * Verifies that repeated session switching / (composition-level) re-entry never
 * leaves more than one active keyed collector at a time, and that zero survive
 * after exit. Cleanup is observable through the child ViewModel's disposal:
 * each key's disposal count is probed via a closeable registered on the child
 * (ViewModel.clear() runs closeables exactly once, at the same point where
 * onCleared() fires during store disposal).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRouteOwnerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var adapter: FakeGatewayProtocolAdapter
    private lateinit var repo: FakeOwnerSessionRepository

    /** Probe: sessionId -> number of times that session's child VM was disposed. */
    private val clearedCounts = mutableMapOf<String, Int>()

    /** Probe: sessionId -> most recently observed live child (duplicate detection). */
    private val liveInstances = mutableMapOf<String, ChatViewModel>()

    /** Host ViewModelStores created by ownerWithProbes for deterministic teardown. */
    private val hostStores = mutableListOf<ViewModelStore>()

    /** Probe callback wired into each child's disposal closeable. */
    private val probe: (String) -> Unit = { key ->
        clearedCounts[key] = (clearedCounts[key] ?: 0) + 1
        liveInstances.remove(key)
    }

    /** Minimal resume-able repository: hands out runtime-100 for any stored key. */
    class FakeOwnerSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-100",
                    durableSessionId = storedSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Before
    fun setUp() {
        adapter = FakeGatewayProtocolAdapter()
        repo = FakeOwnerSessionRepository()
        clearedCounts.clear()
        liveInstances.clear()
        hostStores.clear()
    }

    @After
    fun tearDown() {
        hostStores.forEach { it.clear() }
        hostStores.clear()
        clearedCounts.clear()
        liveInstances.clear()
    }

    /**
     * Creates a real child ChatViewModel wired to the fake repo/adapter, with a
     * probe closeable recording disposal under the child's own key.
     */
    private fun newProbeChild(key: String): ChatViewModel {
        val vm = ChatViewModel(
            sessionId = key,
            sessionRepository = repo,
            gatewayProtocolAdapter = adapter
        )
        vm.addCloseable { probe(key) }
        return vm
    }

    /**
     * Builds the owner through a real host [ViewModelStore] — the exact
     * production shape (`viewModel()` inside a ViewModelStoreOwner) — and
     * returns the owner plus the host store so tests can drive the genuine
     * Activity teardown path (`hostStore.clear()` -> owner disposal ->
     * children cleared).
     */
    private fun ownerWithProbes(): Pair<ChatRouteOwnerViewModel, ViewModelStore> {
        val hostStore = ViewModelStore()
        hostStores.add(hostStore)
        val hostOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = hostStore
        }
        val factory = viewModelFactory {
            initializer {
                ChatRouteOwnerViewModel { key -> newProbeChild(key).also { liveInstances[key] = it } }
            }
        }
        val owner = ViewModelProvider(hostOwner, factory).get(ChatRouteOwnerViewModel::class.java)
        return Pair(owner, hostStore)
    }

    // ------------------------------------------------------------------
    // Collector-count probe: exactly one active collector per key at a time,
    // zero after exit
    // ------------------------------------------------------------------

    @Test
    fun repeatedSessionSwitch_neverLeavesDuplicateCollector_zeroAfterExit() = runTest {
        val (owner, _) = ownerWithProbes()

        // Composition-level re-entry: repeatedly get the same key — the same
        // instance is returned, never a second live collector.
        repeat(4) {
            val vm = owner.get("sess-a")!!
            assertSame(vm, liveInstances["sess-a"])
        }
        advanceUntilIdle()
        assertEquals(setOf("sess-a"), owner.activeKeys().toSet())

        // Session switch away: previous key disposed, zero collectors active.
        owner.clear("sess-a")
        advanceUntilIdle()
        assertEquals(1, clearedCounts["sess-a"])
        assertFalse(liveInstances.containsKey("sess-a"))
        assertTrue(owner.activeKeys().isEmpty())

        // Switch to another session, then back to the first: fresh child per key.
        val vmB = owner.get("sess-b")!!
        advanceUntilIdle()
        assertEquals("sess-b", vmB.uiState.value.sessionId)
        val vmA2 = owner.get("sess-a")!!
        advanceUntilIdle()
        assertNotEquals(vmB, vmA2)
        assertEquals(setOf("sess-a", "sess-b"), owner.activeKeys().toSet())

        // Rapid switch cycle: away/back — still exactly one collector per key.
        owner.clear("sess-a")
        owner.get("sess-a")
        owner.clear("sess-a")
        owner.get("sess-a")
        advanceUntilIdle()
        assertEquals(setOf("sess-a", "sess-b"), owner.activeKeys().toSet())

        // Route exit: clear every visited key — zero collectors remain.
        owner.clear("sess-a")
        owner.clear("sess-b")
        advanceUntilIdle()
        assertTrue(owner.activeKeys().isEmpty())
        assertEquals(4, clearedCounts["sess-a"])
        assertEquals(1, clearedCounts["sess-b"])
    }

    @Test
    fun ownerStoreClear_clearsWholeStore_allChildrenDisposed() = runTest {
        val (owner, hostStore) = ownerWithProbes()
        owner.get("sess-a")
        owner.get("sess-b")
        owner.get("sess-c")
        advanceUntilIdle()
        assertEquals(3, owner.activeKeys().size)

        // Activity teardown: the host store's clear() disposes the owner VM,
        // whose onCleared() clears every surviving child store.
        hostStore.clear()
        advanceUntilIdle()

        assertTrue(owner.activeKeys().isEmpty())
        assertEquals(1, clearedCounts["sess-a"])
        assertEquals(1, clearedCounts["sess-b"])
        assertEquals(1, clearedCounts["sess-c"])
    }

    @Test
    fun onCleared_cancelsGatewayCollector_noEventMutatesTimelineAfterwards() = runTest {
        val (owner, hostStore) = ownerWithProbes()
        val vm = owner.get("sess-a")!!
        advanceUntilIdle()
        assertEquals(setOf("sess-a"), owner.activeKeys().toSet())
        assertEquals("runtime-100", vm.uiState.value.runtimeSessionId)
        assertTrue(vm.uiState.value.timeline.isEmpty())

        // While alive, the collector delivers a matching runtime event — the
        // session-title update applies (liveness probe: the collector ran).
        // (A matching runtime DELTA would be owner-locked-dropped since plan
        // ui-remove-unattributed-activity-notices-v1: unattributable
        // interim/delta activity renders nothing, BLD-006 S4 D-01.)
        adapter.eventFlow.emit(
            GatewayEvent.SessionTitle(
                sessionId = "runtime-100",
                seq = 1L,
                title = "probe title"
            )
        )
        advanceUntilIdle()
        assertEquals("probe title", vm.uiState.value.sessionTitle)
        assertTrue(vm.uiState.value.timeline.isEmpty())

        // Activity teardown: collector cancelled before any further events.
        hostStore.clear()
        advanceUntilIdle()
        assertEquals(1, clearedCounts["sess-a"])

        // A late delta for the still-matching runtime must not reach the
        // disposed collector's timeline — and since plan
        // ui-remove-unattributed-activity-notices-v1 an unattributable delta
        // renders nothing anyway; the timeline stays empty throughout.
        adapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 2L,
                text = "late delta for disposed collector"
            )
        )
        advanceUntilIdle()
        assertEquals(
            0,
            vm.uiState.value.timeline.size
        )
    }

    // ------------------------------------------------------------------
    // clear() semantics
    // ------------------------------------------------------------------

    @Test
    fun clear_isIdempotent_secondCallIsNoOp() = runTest {
        val (owner, _) = ownerWithProbes()
        owner.get("sess-a")
        advanceUntilIdle()
        owner.clear("sess-a")
        advanceUntilIdle()
        assertEquals(1, owner.clearedKeyCount)
        owner.clear("sess-a")
        advanceUntilIdle()
        assertEquals(1, owner.clearedKeyCount)
        assertTrue(owner.activeKeys().isEmpty())
    }

    @Test
    fun clear_disposesOnlyTheTargetedKey() = runTest {
        val (owner, _) = ownerWithProbes()
        val vmA = owner.get("sess-a")!!
        val vmB = owner.get("sess-b")!!
        advanceUntilIdle()

        owner.clear("sess-a")
        advanceUntilIdle()

        assertEquals(1, clearedCounts["sess-a"])
        assertNull(clearedCounts["sess-b"])
        assertEquals(listOf("sess-b"), owner.activeKeys())
        // The surviving child remains the same live instance.
        assertSame(vmB, owner.get("sess-b"))
        // Disposed child's own row was frozen at disposal — no resurrection.
        assertNotEquals(vmA, owner.get("sess-a"))
        advanceUntilIdle()
    }

    // ------------------------------------------------------------------
    // Missing provider: shell renders fallback safely, no collector created
    // ------------------------------------------------------------------

    @Test
    fun get_withoutProvider_returnsNull_noChildStoreCreated() {
        val owner = ChatRouteOwnerViewModel(chatViewModelProvider = null)
        assertNull(owner.get("sess-a"))
        assertTrue(owner.activeKeys().isEmpty())
        assertEquals(0, owner.clearedKeyCount)
    }

    @Test
    fun clear_withoutProvider_isSafeNoOp() {
        val owner = ChatRouteOwnerViewModel(chatViewModelProvider = null)
        owner.clear("sess-a")
        assertEquals(0, owner.clearedKeyCount)
    }

    // ------------------------------------------------------------------
    // Keyed child creation semantics: exactly-once per key, standard keyed
    // provider path
    // ------------------------------------------------------------------

    @Test
    fun keyedChildCreation_runsExactlyOnce_perKey_insideChildStore() = runTest {
        val (owner, _) = ownerWithProbes()
        val first = owner.get("sess-a")!!
        val second = owner.get("sess-a")!!
        advanceUntilIdle()
        assertSame(first, second)
        // Exactly one child exists for the key; no duplicate store was created.
        assertEquals(listOf("sess-a"), owner.activeKeys())
        owner.clear("sess-a")
        advanceUntilIdle()
    }
}
