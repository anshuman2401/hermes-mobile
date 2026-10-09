package com.hermes.bot.feature.chat

import com.hermes.bot.data.cache.CachedConversationSnapshot
import com.hermes.bot.data.cache.ConversationCacheRepository
import com.hermes.bot.data.cache.ConversationCacheReadResult
import com.hermes.bot.data.cache.ConversationCacheWriteResult
import com.hermes.bot.data.gateway.GatewayEvent
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.PromptSubmitResult
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.ledger.PendingTurnLedger
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.CachedRemoteHistoryRow
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-017 v2 (plan v2 TASK-005): notice-at-ack flow tests. A qualifying
 * SessionInfo ack appends a standalone [ModelChangeNoticeRow] to the live
 * timeline IMMEDIATELY (idle or mid-stream); the v1 attachment machinery
 * (pending field + mint-site consumption + bubble render) is fully removed —
 * the deleted `AssistantMessage.modelChangeNotice` field is compiler-enforced.
 * Rows are live-only: never persisted to the offline snapshot, never produced
 * by history hydration. The diff policy itself is covered by
 * ModelChangeNoticePolicyTest (unchanged).
 *
 * Fake-adapter conventions per ChatComposerSingleFlightTest /
 * ChatSessionModelLabelTest: the VM attaches a fresh runtime on loadSession()
 * success and GatewayEvents flow through the fake adapter's eventFlow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatModelChangeNoticeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: NoticeSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    private val runtimeId = "runtime-100"

    /** Minimal [SessionRepository] double with a scriptable resume result. */
    private class NoticeSessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> =
            MutableStateFlow(ChatStreamingStatus())

        var resumeOutcome: Result<SessionResumeResult> = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = mapOf<String, Any?>(
                    "model" to "gpt-5.6-luna",
                    "reasoning_effort" to "high"
                )
            )
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> =
            resumeOutcome

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
        val dir = tempFolder.newFolder("chat_model_change_notice_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = NoticeSessionRepository()
    }

    private fun vm(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = ledger,
        vaultStorage = vault
    )

    private fun sessionInfoEvent(
        model: String?,
        effort: String?,
        seq: Long
    ): GatewayEvent.SessionInfo {
        val info = mutableMapOf<String, Any?>()
        if (model != null) info["model"] = model
        if (effort != null) info["reasoning_effort"] = effort
        return GatewayEvent.SessionInfo(
            sessionId = runtimeId,
            seq = seq,
            info = info
        )
    }

    private fun assistantRows(state: ChatUiState): List<AssistantMessage> =
        state.timeline.filterIsInstance<AssistantMessage>()

    private fun noticeRows(state: ChatUiState): List<ModelChangeNoticeRow> =
        state.timeline.filterIsInstance<ModelChangeNoticeRow>()

    // AC-6/REQ-006: the resume echo is the FIRST report — it seeds the
    // baseline and fires no row.
    @Test
    fun firstReport_resumeEcho_noNotice() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        assertEquals("gpt-5.6-luna", viewModel.uiState.value.sessionModelName)
        assertEquals("high", viewModel.uiState.value.sessionReasoningEffort)
        assertTrue(noticeRows(viewModel.uiState.value).isEmpty())
        assertTrue(assistantRows(viewModel.uiState.value).isEmpty())
    }

    // AC-3/REQ-005: a SessionInfo event that repeats the last reported pair
    // is an identical repeat — silent (no row).
    @Test
    fun identicalRepeatReport_noRow() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(sessionInfoEvent("gpt-5.6-luna", "high", seq = 10L))
        advanceUntilIdle()

        assertTrue(noticeRows(viewModel.uiState.value).isEmpty())
        assertFalse(viewModel.uiState.value.timeline.any { it is AssistantMessage })
    }

    // AC-1/REQ-001: ack while idle appends the standalone row IMMEDIATELY,
    // at the end of the timeline; assistant rows are untouched (no attach).
    @Test
    fun ackWhileIdle_appendsRowImmediately_assistantRowsUntouched() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 11L))
        advanceUntilIdle()

        val rows = noticeRows(viewModel.uiState.value)
        assertEquals(1, rows.size)
        assertEquals(
            "Model changed to claude-opus-4-6 · Reasoning changed to medium",
            rows.single().text
        )
        assertTrue(rows.single().id.startsWith("mcr-"))
        // Appended at the timeline end (nothing existed, so it is the last row).
        assertEquals(
            rows.single().id,
            viewModel.uiState.value.timeline.last().id
        )
        // No attachment anywhere: zero assistant rows, and the deleted
        // field is compiler-enforced absent.
        assertTrue(assistantRows(viewModel.uiState.value).isEmpty())

        // Exactly-once: an identical repeat stays silent.
        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 12L))
        advanceUntilIdle()
        assertEquals(1, noticeRows(viewModel.uiState.value).size)
    }

    // AC-2/REQ-001: ack while a turn is streaming appends the row
    // immediately; the streaming row is untouched and no attach happens. The
    // terminal seal afterwards keeps the reply clean.
    @Test
    fun ackMidStream_appendsRow_streamingRowUntouched_noAttach() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.updateDraft("stream please")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        val streamingBefore = assistantRows(viewModel.uiState.value).single()

        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 12L))
        advanceUntilIdle()

        val rows = noticeRows(viewModel.uiState.value)
        assertEquals(1, rows.size)
        assertEquals(
            "Model changed to claude-opus-4-6 · Reasoning changed to medium",
            rows.single().text
        )
        // The row lands BELOW (after) the streaming reply, not on it.
        assertEquals(
            rows.single().id,
            viewModel.uiState.value.timeline.last().id
        )
        val streamingAfter = assistantRows(viewModel.uiState.value)
            .filter { it.state == AssistantMessageState.STREAMING }
        assertEquals(1, streamingAfter.size)
        assertEquals(
            "streaming row untouched: same id and state, no attach",
            streamingBefore.id,
            streamingAfter.single().id
        )
        assertEquals("", streamingAfter.single().text)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = runtimeId,
                seq = 13L,
                text = "The authoritative terminal text.",
                status = "complete"
            )
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isStreaming)
        val sealedRow = assistantRows(viewModel.uiState.value)
            .firstOrNull { it.state == AssistantMessageState.COMPLETE }
        assertNotNull(sealedRow)
        // Still exactly one notice row and one reply — the seal did not
        // duplicate or attach anything.
        assertEquals(1, noticeRows(viewModel.uiState.value).size)
        assertEquals(1, assistantRows(viewModel.uiState.value).size)
    }

    // AC-4/REQ-005: change-back to a previously reported value fires a NEW
    // row (pure comparison, no memory of shown values), while identical
    // repeats in between stay silent.
    @Test
    fun changeBack_firesNewRow() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 20L))
        advanceUntilIdle()
        assertEquals(1, noticeRows(viewModel.uiState.value).size)

        // Identical repeat: silent.
        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 21L))
        advanceUntilIdle()
        assertEquals(1, noticeRows(viewModel.uiState.value).size)

        // Change back to the original pair: a new row with the new values.
        fakeAdapter.eventFlow.emit(sessionInfoEvent("gpt-5.6-luna", "high", seq = 22L))
        advanceUntilIdle()
        val rows = noticeRows(viewModel.uiState.value)
        assertEquals(2, rows.size)
        assertEquals(
            "Model changed to gpt-5.6-luna · Reasoning changed to high",
            rows.last().text
        )
    }

    // AC-6/REQ-006: loadSession resets the baseline — the resume echo after
    // re-entry repeats the ORIGINAL pair and fires no false row, and
    // hydration replaced any previously fired live row.
    @Test
    fun loadSessionResetsBaseline_resumeEchoFiresNothing() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        // A change fires a row first.
        fakeAdapter.eventFlow.emit(sessionInfoEvent("claude-opus-4-6", "medium", seq = 30L))
        advanceUntilIdle()
        assertEquals(1, noticeRows(viewModel.uiState.value).size)

        // Re-entry (the owner taps the session again): the baseline resets
        // and hydration replaces the timeline — the fired row must be gone.
        viewModel.loadSession()
        advanceUntilIdle()

        assertTrue(
            "hydration replaced the live notice row",
            noticeRows(viewModel.uiState.value).isEmpty()
        )

        // The first report after the reset (the resume echo) repeats the
        // ORIGINAL pair: fresh first-report semantics, no false row.
        fakeAdapter.eventFlow.emit(sessionInfoEvent("gpt-5.6-luna", "high", seq = 31L))
        advanceUntilIdle()
        assertTrue(noticeRows(viewModel.uiState.value).isEmpty())
    }

    // AC-5/REQ-004: the offline snapshot path reuses the history mapper,
    // which knows NO notice row types — cached assistant rows carry no
    // notice (the field is deleted; compiler-enforced) and no
    // ModelChangeNoticeRow can be hydrated from a snapshot.
    @Test
    fun offlineSnapshot_noNoticeRows_hydrated() = runTest {
        val cacheRepo = SnapshotOnlyNoticeCacheRepository(
            CachedConversationSnapshot(
                durableSessionId = "sess-100",
                capturedAtMillis = System.currentTimeMillis(),
                messagesOmitted = false,
                rows = listOf(
                    CachedRemoteHistoryRow(
                        ordinal = 0,
                        role = "assistant",
                        content = "Cached assistant answer"
                    )
                )
            )
        )
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))

        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault,
            conversationCacheRepository = cacheRepo
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.timeline.isNotEmpty())
        val cachedAssistant = state.timeline.filterIsInstance<AssistantMessage>()
        assertEquals(1, cachedAssistant.size)
        assertTrue(
            "no notice row can be hydrated from a snapshot (REQ-004)",
            noticeRows(state).isEmpty()
        )
    }

    // Foreign-runtime SessionInfo events never touch the notice pipeline
    // (the exact runtime-ID admission guard upstream of the handler).
    @Test
    fun foreignRuntimeSessionInfo_noRowNoStateChange() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionInfo(
                sessionId = "runtime-OTHER",
                seq = 40L,
                info = mapOf<String, Any?>("model" to "intruder-model")
            )
        )
        advanceUntilIdle()

        assertEquals("gpt-5.6-luna", viewModel.uiState.value.sessionModelName)
        assertTrue(viewModel.uiState.value.timeline.isEmpty())
    }

    // TASK-002 (g): the TimelineFollowPolicy fingerprint is total over
    // TimelineEntry subtypes — the new row has its own branch and the
    // signature is deterministic.
    @Test
    fun timelineFollowPolicy_fingerprintBranchPresent() {
        val row = ModelChangeNoticeRow(id = "mcr-x", text = "Model changed to m")
        val signature = TimelineFollowPolicy.contentSignature(listOf(row))
        assertEquals("1:mcr:18", signature)
        // Two rows of the same text (different ids) share the fingerprint —
        // the digest tracks content length, not identity.
        assertEquals(
            TimelineFollowPolicy.contentSignature(listOf(row)),
            TimelineFollowPolicy.contentSignature(listOf(row.copy(id = "mcr-y")))
        )
    }
}

/**
 * Minimal in-memory [ConversationCacheRepository] double serving a fixed
 * snapshot (same convention as ChatSessionModelLabelTest).
 */
private class SnapshotOnlyNoticeCacheRepository(
    private val snapshot: CachedConversationSnapshot
) : ConversationCacheRepository {
    override suspend fun load(storedSessionId: String): ConversationCacheReadResult =
        ConversationCacheReadResult.Snapshot(snapshot)

    override suspend fun replaceRemoteSnapshot(
        storedSessionId: String,
        resume: SessionResumeResult,
        nowMillis: Long
    ): ConversationCacheWriteResult = ConversationCacheWriteResult.Skipped

    override suspend fun recordObservedToolEvent(
        storedSessionId: String,
        runtimeSessionId: String,
        event: GatewayEvent,
        observedAtMillis: Long
    ): ConversationCacheWriteResult = ConversationCacheWriteResult.Skipped
}