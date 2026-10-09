package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.SessionListResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CHAT-004 behavior tests (frozen plan TEST-005, TEST-006).
 * Deterministic JVM coverage; device checks are separate.
 */
class ChatViewModelLoadEarlierTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vault: EncryptedVault
    private lateinit var ledger: PendingTurnLedger
    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var snapshotRepo: SnapshotRepository

    private var snapshotResult: Result<SessionResumeResult>? = null
    private var fetchCalls = 0

    /**
     * Repair R1 (positive-path prepend coverage): hydration rows served by
     * [SnapshotRepository.resumeSession] so oldestRenderedHistoryRow is
     * non-null and real prepends happen (the frozen plan's earlier harness
     * hydrated an EMPTY list, so only the null-base path was exercised).
     */
    private var resumeMessages: List<SessionMessage> = emptyList()

    /**
     * Purpose-built fake with recording: asserts exactly-once fetch, prepend
     * merge, indicator states, and no timeline mutation on failure.
     * fetchHistorySnapshot is read-only by contract (never touches
     * activeMessages/watermarks).
     */
    private inner class SnapshotRepository : SessionRepository {
        override val activeTurnStreaming: StateFlow<com.hermes.bot.feature.chat.ChatStreamingStatus> =
            MutableStateFlow(com.hermes.bot.feature.chat.ChatStreamingStatus())
        override val activeSession: StateFlow<SessionHandle?> =
            MutableStateFlow(null)
        override val activeMessages: StateFlow<List<SessionMessage>> =
            MutableStateFlow(emptyList())

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(sessions = emptyList(), isOfflineCache = false))

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> {
            fetchCalls++
            return snapshotResult ?: Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )
        }

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-1", "runtime-1", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-1",
                    durableSessionId = "sess-1",
                    messageCount = resumeMessages.size,
                    messages = resumeMessages
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Before
    fun setUp() {
        val dir: File = tempFolder.newFolder("chat_load_earlier_vault")
        vault = EncryptedVault(dir, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        snapshotResult = null
        fetchCalls = 0
        resumeMessages = emptyList()
        snapshotRepo = SnapshotRepository()
    }

    private fun buildViewModel(): ChatViewModel =
        ChatViewModel(
            sessionId = "sess-1",
            sessionRepository = snapshotRepo,
            pendingTurnLedger = ledger,
            gatewayProtocolAdapter = fakeAdapter,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        )

    @Test
    fun loadEarlier_fetches_once_merges_and_sets_indicator() = runTest {
        val viewModel = buildViewModel()
        // Hydration in this harness returns an EMPTY messages list, so
        // oldestRenderedHistoryRow is null and the first merge prepends
        // nothing (REQ-004: null base => caller must hydrate, not prepend).
        advanceUntilIdle()

        snapshotResult = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-snap",
                durableSessionId = "sess-1",
                messageCount = 0,
                messages = listOf(
                    SessionMessage(role = "user", content = "older-1"),
                    SessionMessage(role = "assistant", content = "older-2")
                )
            )
        )
        viewModel.loadEarlier()
        advanceUntilIdle()

        // Exactly one fetch per gesture; no fabricated rows with a null base;
        // in-flight flag always clears in the terminal update.
        assertEquals(1, fetchCalls)
        assertFalse(viewModel.uiState.value.isLoadingEarlier)
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
        assertEquals(
            ChatUiState.HistoryBeginState.NONE,
            viewModel.uiState.value.historyBeginState
        )
    }

    @Test
    fun loadEarlier_never_arms_hydration_jump() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()

        snapshotResult = Result.failure(RuntimeException("transport down"))
        viewModel.loadEarlier()
        advanceUntilIdle()

        // CON-003: isInitialLoading stays false throughout the whole cycle,
        // so TimelineFollowStateMachine can never emit JUMP_TO_LATEST from a
        // load-earlier fetch. Failure never mutates the timeline and clears
        // the in-flight flag.
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertFalse(viewModel.uiState.value.isLoadingEarlier)
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
        assertEquals(
            ChatUiState.HistoryBeginState.NONE,
            viewModel.uiState.value.historyBeginState
        )
    }

    @Test
    fun loadEarlier_two_prepends_have_distinct_ids_and_preserve_timeline() = runTest {
        // Repair R1: hydrate a NON-EMPTY history so oldestRenderedHistoryRow
        // is non-null and real prepends happen (positive-path coverage).
        // Two consecutive prepends with DIFFERENT older rows must produce a
        // combined timeline with NO duplicate entry IDs (LazyColumn keys) and
        // must preserve hydration order without reordering or dropping the
        // existing timeline.
        val hydration = listOf(
            SessionMessage(role = "user", content = "seed-1"),
            SessionMessage(role = "assistant", content = "seed-2")
        )
        resumeMessages = hydration
        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.timeline.size)

        // Round 0: rows older than "seed-1".
        snapshotResult = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-snap",
                durableSessionId = "sess-1",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "older-a"),
                    SessionMessage(role = "assistant", content = "older-b"),
                    SessionMessage(role = "user", content = "seed-1"),
                    SessionMessage(role = "assistant", content = "seed-2")
                )
            )
        )
        viewModel.loadEarlier()
        advanceUntilIdle()

        // Round 1: DIFFERENT older rows than round 0.
        snapshotResult = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-snap",
                durableSessionId = "sess-1",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "older-c"),
                    SessionMessage(role = "assistant", content = "older-d"),
                    SessionMessage(role = "user", content = "older-a"),
                    SessionMessage(role = "assistant", content = "older-b"),
                    SessionMessage(role = "user", content = "seed-1"),
                    SessionMessage(role = "assistant", content = "seed-2")
                )
            )
        )
        viewModel.loadEarlier()
        advanceUntilIdle()

        assertEquals(2, fetchCalls)
        assertFalse(viewModel.uiState.value.isLoadingEarlier)

        val timeline = viewModel.uiState.value.timeline
        // 2 hydration + 2 + 2 prepended text rows.
        assertEquals(6, timeline.size)

        // No LazyColumn key collisions anywhere in the combined timeline.
        val ids = timeline.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // Round 1's prepended IDs carry the advanced epoch (hist-prev-1-*).
        assertTrue(
            "second prepend must use epoch 1 IDs",
            timeline.take(2).map { it.id } ==
                listOf("hist-prev-1-user-0", "hist-prev-1-asst-1")
        )
        // Round 0's prepended IDs carry epoch 0, hydration IDs stay hist-*.
        assertEquals(
            listOf(
                "hist-prev-1-user-0", "hist-prev-1-asst-1",
                "hist-prev-0-user-0", "hist-prev-0-asst-1",
                "hist-user-0", "hist-asst-1"
            ),
            ids
        )
        // No reorder/drop of the hydration rows: they are intact in the tail.
        val tail = timeline.drop(4)
        assertTrue(
            "hydration rows must remain intact at the timeline tail",
            tail[0] is UserMessage && (tail[0] as UserMessage).text == "seed-1" &&
                tail[1] is AssistantMessage && (tail[1] as AssistantMessage).text == "seed-2"
        )
    }
}
