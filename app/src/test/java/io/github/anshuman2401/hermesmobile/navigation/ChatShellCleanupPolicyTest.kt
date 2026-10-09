package io.github.anshuman2401.hermesmobile.navigation

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.feature.chat.ChatStreamingStatus
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * BLD-006 S1 Candidate V2 — SHELL-level chat collector cleanup policy tests.
 *
 * These tests drive [ChatShellCleanupPolicy] — the exact decision logic executed
 * by HermesNavShell's cleanup LaunchedEffect — against a REAL
 * [ChatRouteOwnerViewModel] creating REAL child [ChatViewModel]s (real gateway
 * collectors), with disposal observed through per-child closeables. They
 * reproduce the review's HIGH trace (same-key route re-entry disposing the
 * displayed session's collector) and the MEDIUM-1 rows (session switch, route
 * exit, rotation-recomposition) at the shell-policy seam rather than by driving
 * the owner directly.
 */
class ChatShellCleanupPolicyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var adapter: FakeGatewayProtocolAdapter
    private lateinit var repo: FakeShellSessionRepository

    /** Probe: sessionId -> number of times that session's child VM was disposed. */
    private val clearedCounts = mutableMapOf<String, Int>()

    /** Probe: sessionId -> most recently observed live child (duplicate detection). */
    private val liveInstances = mutableMapOf<String, ChatViewModel>()

    /** Probe callback wired into each child's disposal closeable. */
    private val probe: (String) -> Unit = { key ->
        clearedCounts[key] = (clearedCounts[key] ?: 0) + 1
        liveInstances.remove(key)
    }

    /** Minimal resume-able repository: hands out a per-stored-key runtime ID. */
    class FakeShellSessionRepository : SessionRepository {
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
                    runtimeSessionId = "runtime-for-$storedSessionId",
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
        repo = FakeShellSessionRepository()
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
     * production shape (`viewModel()` inside a ViewModelStoreOwner) — with the
     * host store retained for the Activity-teardown path.
     */
    private var hostStore: ViewModelStore? = null

    private fun newShell(): ChatRouteOwnerViewModel {
        hostStore = ViewModelStore()
        val hostOwner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = hostStore!!
        }
        val factory = viewModelFactory {
            initializer {
                ChatRouteOwnerViewModel { key -> newProbeChild(key).also { liveInstances[key] = it } }
            }
        }
        return ViewModelProvider(hostOwner, factory).get(ChatRouteOwnerViewModel::class.java)
    }

    /**
     * Drives one shell cleanup-effect run exactly as HermesNavShell executes it:
     * policy decision -> owner.clear(tracked) -> tracking update.
     */
    private fun runShellEffect(
        owner: ChatRouteOwnerViewModel,
        currentScreen: ShellScreen,
        isExpanded: Boolean,
        activeChatSessionId: String,
        previousChatKeyState: TrackedKey
    ) {
        val decision = ChatShellCleanupPolicy.decide(
            currentScreen = currentScreen,
            isExpanded = isExpanded,
            activeKey = activeChatSessionId,
            trackedKey = previousChatKeyState.value
        )
        owner.clear(decision.clearKey)
        previousChatKeyState.value = decision.nextTrackedKey
    }

    /** rememberSaveable-equivalent for the shell's tracked previous key. */
    private class TrackedKey(var value: String? = null)

    /**
     * Compact-mode trace of the review's HIGH finding:
     * open CHAT -> back to SESSIONS -> re-open the SAME session. After re-entry
     * the displayed session must have exactly one live collector; the VM shown
     * on screen must never be disposed mid-display.
     */
    @Test
    fun compact_sameSessionAwayAndBack_displayedSessionKeepsOneLiveCollector() = kotlinx.coroutines.test.runTest {
        val owner = newShell()
        val tracked = TrackedKey()

        // t1: open CHAT for sess-42 (initial effect run; nothing tracked yet)
        val vmA = owner.get("sess-42")!!
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
        assertSame(vmA, liveInstances["sess-42"])

        // t2: back to SESSIONS — route exit cleanup: collector disposed, tracked key nulled
        runShellEffect(owner, ShellScreen.SESSIONS, isExpanded = false, "sess-42", tracked)
        assertEquals(1, clearedCounts["sess-42"])
        assertFalse(liveInstances.containsKey("sess-42"))
        assertNull(tracked.value)

        // t3: re-open the same session — fresh VM created for display...
        val vmB = owner.get("sess-42")!!
        assertNotSame(vmA, vmB)
        // ...and the cleanup effect must NOT dispose the just-displayed VM.
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
        assertSame(vmB, liveInstances["sess-42"])
        assertEquals(1, clearedCounts["sess-42"])

        // The displayed VM's collector is live: an exact-runtime SessionTitle
        // event applies (liveness probe: the collector ran). (An exact-runtime
        // DELTA would be owner-locked-dropped since plan
        // ui-remove-unattributed-activity-notices-v1: unattributable
        // interim/delta activity renders nothing, BLD-006 S4 D-01 — no active
        // admitted local turn.)
        adapter.eventFlow.emit(
            GatewayEvent.SessionTitle(
                sessionId = "runtime-for-sess-42",
                seq = 1L,
                title = "probe title"
            )
        )
        assertEquals("probe title", vmB.uiState.value.sessionTitle)
        assertTrue(vmB.uiState.value.timeline.isEmpty())
    }

    /**
     * Compact-mode SETTINGS round-trip: CHAT -> SETTINGS -> back to CHAT for the
     * same session — the returned-to VM stays live and streams.
     */
    @Test
    fun compact_settingsRoundTrip_displayedSessionKeepsOneLiveCollector() {
        val owner = newShell()
        val tracked = TrackedKey()

        val vmA = owner.get("sess-42")!!
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)

        // CHAT -> SETTINGS (away from chat routes)
        runShellEffect(owner, ShellScreen.SETTINGS, isExpanded = false, "sess-42", tracked)
        assertEquals(1, clearedCounts["sess-42"])
        assertNull(tracked.value)

        // SETTINGS -> back to CHAT, same session
        val vmB = owner.get("sess-42")!!
        assertNotSame(vmA, vmB)
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
        assertSame(vmB, liveInstances["sess-42"])
        assertEquals(1, clearedCounts["sess-42"])
    }

    /**
     * Rotation/activity recreation pattern: the shell recomposes with the same
     * restored values for both currentScreen and tracked key (the activity-owned
     * owner VM and its children survive). The first post-rotation effect run
     * must not clear the still-displayed key.
     */
    @Test
    fun rotation_recompositionWithRestoredState_displayedKeyNeverCleared() {
        val owner = newShell()
        val tracked = TrackedKey()

        val vmA = owner.get("sess-42")!!
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)

        // "Rotation": the owner VM (activity-retained) and its child survive,
        // while the shell's rememberSaveable state restores identical values.
        // The recomposition re-runs the effect with tracked == active.
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
        assertSame(vmA, liveInstances["sess-42"])
        assertNull(clearedCounts["sess-42"])

        // Expanded-width rotation variant (600dp+ dual pane): same invariant.
        runShellEffect(owner, ShellScreen.SESSIONS, isExpanded = true, "sess-42", tracked)
        assertSame(vmA, liveInstances["sess-42"])
        assertNull(clearedCounts["sess-42"])
    }

    /**
     * Expanded dual-pane: leave via SETTINGS (from the SESSIONS rail) and
     * return — the same invariant as the compact trace, with the shell
     * remaining on a chat-displaying screen throughout (SESSIONS is
     * chat-displaying when expanded).
     */
    @Test
    fun expanded_settingsRoundTrip_displayedSessionKeepsOneLiveCollector() {
        val owner = newShell()
        val tracked = TrackedKey()

        val vmA = owner.get("sess-42")!!
        runShellEffect(owner, ShellScreen.SESSIONS, isExpanded = true, "sess-42", tracked)
        assertSame(vmA, liveInstances["sess-42"])
        assertNull(clearedCounts["sess-42"])

        // Away to SETTINGS: leaving the expanded chat display disposes the
        // collector (route-exit acceptance row) and nulls the tracked key.
        runShellEffect(owner, ShellScreen.SETTINGS, isExpanded = true, "sess-42", tracked)
        assertEquals(1, clearedCounts["sess-42"])
        assertNull(tracked.value)

        // Back to the expanded dual pane for the same session: fresh VM, alive.
        val vmB = owner.get("sess-42")!!
        assertNotSame(vmA, vmB)
        runShellEffect(owner, ShellScreen.SESSIONS, isExpanded = true, "sess-42", tracked)
        assertSame(vmB, liveInstances["sess-42"])
        assertEquals(1, clearedCounts["sess-42"])
    }

    /**
     * Session switch (CHAT -> CHAT with a different session): only the OLD key
     * is disposed; the newly selected session's collector stays alive.
     */
    @Test
    fun compact_sessionSwitch_oldKeyDisposed_newKeyAlive() {
        val owner = newShell()
        val tracked = TrackedKey()

        val vmA = owner.get("sess-a")!!
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-a", tracked)
        assertSame(vmA, liveInstances["sess-a"])

        // Switch to sess-b while staying on the CHAT route
        val vmB = owner.get("sess-b")!!
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-b", tracked)
        assertEquals(1, clearedCounts["sess-a"])
        assertFalse(liveInstances.containsKey("sess-a"))
        assertSame(vmB, liveInstances["sess-b"])
        assertNull(clearedCounts["sess-b"])
    }

    /**
     * Repeated away/back cycles still converge to exactly one live collector
     * for the displayed key and zero tracked leaks.
     */
    @Test
    fun compact_repeatedAwayBackCycles_exactlyOneLiveCollectorAtRest() {
        val owner = newShell()
        val tracked = TrackedKey()

        // Initial CHAT display: composition materializes the VM, then the
        // first effect run tracks it (nothing cleared — nothing tracked yet).
        owner.get("sess-42")
        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
        repeat(3) { round ->
            // Route exit: away to SESSIONS (compact = no chat pane).
            runShellEffect(owner, ShellScreen.SESSIONS, isExpanded = false, "sess-42", tracked)
            // Composition re-entry: the CHAT branch materializes the key's VM
            // before the cleanup effect runs (keyed child creation is
            // synchronous), exactly as the shell does.
            owner.get("sess-42")
            runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-42", tracked)
            // After each full cycle, exactly one live collector for the displayed key.
            assertEquals("round $round", 1, liveInstances.count())
            assertEquals(setOf("sess-42"), liveInstances.keys)
        }
        // 3 exit cleanups: one per away/back round (the initial CHAT run
        // clears nothing — nothing tracked yet).
        assertEquals(3, clearedCounts["sess-42"])
        assertNull(clearedCounts.entries.firstOrNull { it.key != "sess-42" }?.value)
    }

    /**
     * Route exit with pending navigation: leaving Chat routes always ends with
     * the tracked key nulled — so re-entry with any session creates a fresh VM
     * instead of the effect resurrecting or wrongly clearing one.
     */
    @Test
    fun routeExit_tracksNothing_afterLeavingChatRoutes() {
        val owner = newShell()
        val tracked = TrackedKey()

        runShellEffect(owner, ShellScreen.CHAT, isExpanded = false, "sess-a", tracked)
        runShellEffect(owner, ShellScreen.DEBUG_LOGS, isExpanded = false, "sess-a", tracked)
        assertNull(tracked.value)
        assertTrue(liveInstances.isEmpty())

        // And every non-chat screen decision with nothing tracked is a no-op.
        runShellEffect(owner, ShellScreen.SETTINGS, isExpanded = false, "sess-a", tracked)
        assertTrue(owner.activeKeys().isEmpty() || liveInstances.isEmpty())
    }

    // ------------------------------------------------------------------
    // Policy decision table (pure): exhaustive chat/non-chat x tracked matrix
    // ------------------------------------------------------------------

    @Test
    fun policy_onChatRoute_sameKey_keepDisplayed() {
        ChatShellCleanupPolicyTestRows.onChatRouteRows().forEach { row ->
            val decision = ChatShellCleanupPolicy.decide(
                currentScreen = row.screen,
                isExpanded = row.isExpanded,
                activeKey = row.activeKey,
                trackedKey = row.trackedKey
            )
            assertEquals("screen=${row.screen} expanded=${row.isExpanded} tracked=${row.trackedKey}",
                row.expectedClearKey, decision.clearKey)
            assertEquals("screen=${row.screen} expanded=${row.isExpanded} tracked=${row.trackedKey}",
                row.expectedTracked, decision.nextTrackedKey)
        }
    }

    @Test
    fun policy_offChatRoute_clearTracked_thenTrackNothing() {
        ChatShellCleanupPolicyTestRows.offChatRouteRows().forEach { row ->
            val decision = ChatShellCleanupPolicy.decide(
                currentScreen = row.screen,
                isExpanded = row.isExpanded,
                activeKey = row.activeKey,
                trackedKey = row.trackedKey
            )
            assertEquals("screen=${row.screen} expanded=${row.isExpanded} tracked=${row.trackedKey}",
                row.expectedClearKey, decision.clearKey)
            assertEquals("screen=${row.screen} expanded=${row.isExpanded} tracked=${row.trackedKey}",
                row.expectedTracked, decision.nextTrackedKey)
        }
    }
}

/** Shared policy-row fixture data for the decision-table tests. */
internal object ChatShellCleanupPolicyTestRows {

    data class Row(
        val screen: ShellScreen,
        val isExpanded: Boolean,
        val activeKey: String,
        val trackedKey: String?,
        val expectedClearKey: String?,
        val expectedTracked: String?
    )

    fun onChatRouteRows(): List<Row> = listOf(
        // Tracked == active (rotation restore): nothing cleared, tracking kept.
        Row(ShellScreen.CHAT, false, "k", "k", null, "k"),
        Row(ShellScreen.SESSIONS, true, "k", "k", null, "k"),
        // Switch CHAT -> CHAT: old key cleared, new key tracked.
        Row(ShellScreen.CHAT, false, "b", "a", "a", "b"),
        Row(ShellScreen.SESSIONS, true, "b", "a", "a", "b"),
        // First effect run (nothing tracked): no clear, start tracking.
        Row(ShellScreen.CHAT, false, "k", null, null, "k"),
        Row(ShellScreen.SESSIONS, true, "k", null, null, "k")
    )

    fun offChatRouteRows(): List<Row> = listOf(
        // Tracked non-null: route exit disposes it and tracks nothing.
        Row(ShellScreen.SETTINGS, false, "k", "k", "k", null),
        Row(ShellScreen.CONNECT, false, "k", "k", "k", null),
        Row(ShellScreen.DEBUG_LOGS, false, "k", "k", "k", null),
        Row(ShellScreen.SESSIONS, false, "k", "k", "k", null),
        // Tracked already null: no-op.
        Row(ShellScreen.SETTINGS, false, "k", null, null, null),
        Row(ShellScreen.CONNECT, false, "k", null, null, null),
        Row(ShellScreen.DEBUG_LOGS, false, "k", null, null, null),
        Row(ShellScreen.SESSIONS, false, "k", null, null, null)
    )
}
