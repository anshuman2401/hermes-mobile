package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.InterruptSessionResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReasoningDefaultResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReasoningKind
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingInterruptQueue
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.PendingTurnState
import io.github.anshuman2401.hermesmobile.data.local.Draft
import io.github.anshuman2401.hermesmobile.data.local.ProfileModelDefault
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeChatSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault
    private lateinit var queue: PendingInterruptQueue

    class FakeChatSessionRepository : SessionRepository {
        var pickerAuthority = 0
        var pickerCaptureGate: CompletableDeferred<Unit>? = null
        var pickerContextGate: CompletableDeferred<Unit>? = null
        var pickerCaptureFails = false
        var catalogGate: CompletableDeferred<Unit>? = null
        var catalogFails = false
        override suspend fun capturePickerAccess(runtimeSessionId: String): io.github.anshuman2401.hermesmobile.data.session.SessionPickerAccess? {
            pickerCaptureGate?.await()
            if (pickerCaptureFails) return null
            val authority = pickerAuthority
            return object : io.github.anshuman2401.hermesmobile.data.session.SessionPickerAccess {
                override suspend fun isCurrent() = pickerAuthority == authority
                override suspend fun isSameContext(): Boolean {
                    pickerContextGate?.await()
                    return pickerAuthority == authority
                }
                override suspend fun options(): GatewayResult<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption>> {
                    catalogGate?.await()
                    return if (catalogFails) GatewayResult.Failure(GatewayFailure.Transport("Unavailable")) else listSessionModelOptions(runtimeSessionId)
                }
                override suspend fun setModel(option: io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption) = setSessionModel(runtimeSessionId, option)
                override suspend fun setReasoning(effort: String) = setSessionReasoning(runtimeSessionId, effort)
            }
        }
        var pickerModels = listOf(io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption("openai", "gpt-5"))
        var pickerSetResult: io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult<io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult> =
            io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(false, false))
        val pickerModelCalls = mutableListOf<Pair<String, io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption>>()
        val pickerReasoningCalls = mutableListOf<Pair<String, String>>()
        var pickerGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun listSessionModelOptions(runtimeSessionId: String) =
            io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(pickerModels)
        override suspend fun setSessionModel(runtimeSessionId: String, option: io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption): io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult<io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult> {
            pickerModelCalls += runtimeSessionId to option
            pickerGate?.await()
            return pickerSetResult
        }
        override suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult<io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult> {
            pickerReasoningCalls += runtimeSessionId to effort
            pickerGate?.await()
            return pickerSetResult
        }
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var createDraftSessionCalls = 0
        var resumeSessionCalls = 0
        var resumeGate: CompletableDeferred<Unit>? = null
        var fetchHistorySnapshotCalls = 0

        var resumeOutcome: Result<SessionResumeResult> = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "Initial question"),
                    SessionMessage(role = "assistant", content = "Initial answer")
                )
            )
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> {
            createDraftSessionCalls++
            return Result.success(SessionHandle("sess-draft-1", "runtime-draft-1", isDraft = true))
        }

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            resumeSessionCalls++
            resumeGate?.await()
            return resumeOutcome
        }

        override suspend fun fetchHistorySnapshot(durableSessionId: String): Result<SessionResumeResult> {
            fetchHistorySnapshotCalls++
            return Result.success(
                SessionResumeResult(
                    runtimeSessionId = "runtime-snap",
                    durableSessionId = durableSessionId,
                    messageCount = 0,
                    messages = emptyList()
                )
            )
        }

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Test
    fun loadSession_rendersNonBlankBodies_skipsBlanks_stableIds() = runTest {
        // CHG-005-S2 (TASK-006) + CHG-005-S3 (TASK-010): mixed history fixture —
        // user text, assistant markdown, blank-body entries (whitespace only
        // and truly empty), an unknown non-blank role (tool/step event → ONE
        // collapsed bounded row, not an assistant bubble), and a blank-role
        // non-blank entry (also one collapsed bounded row).
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 6,
                messages = listOf(
                    SessionMessage(role = "user", content = "First question"),
                    SessionMessage(
                        role = "assistant",
                        content = "## Answer\n\n- point one\n- point two"
                    ),
                    SessionMessage(role = "assistant", content = "   "),
                    SessionMessage(role = "", content = ""),
                    SessionMessage(role = "tool", content = "step output"),
                    SessionMessage(role = "  ", content = "orphan step body")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val timeline = state.timeline

        // Only non-blank rows render, in original order: user text, assistant
        // markdown, the unknown-role bounded row, and the blank-role bounded
        // row (skeptic P3: blank-role + non-blank content renders exactly ONE
        // collapsed row — ownership resolved to the S3 mapping).
        assertEquals(4, timeline.size)

        val userRow = timeline[0] as UserMessage
        assertEquals("First question", userRow.text)
        assertEquals(UserMessageState.SENT, userRow.state)
        assertEquals("hist-user-0", userRow.id)

        val assistantRow = timeline[1] as AssistantMessage
        assertEquals("## Answer\n\n- point one\n- point two", assistantRow.text)
        assertEquals(AssistantMessageState.COMPLETE, assistantRow.state)
        assertEquals("hist-asst-1", assistantRow.id)

        // Unknown non-blank role: ONE collapsed bounded row, stable source-index ID.
        val unknownRoleRow = timeline[2] as GenericGatewayActivity
        assertEquals("hist-event-4", unknownRoleRow.id)
        assertFalse(unknownRoleRow.isExpanded)
        assertEquals(BoundedPayloadMapper.boundedNameType("history.tool"), unknownRoleRow.eventType)
        assertEquals(
            BoundedPayloadMapper.boundedCollapsedSummary("Historical Hermes activity: tool"),
            unknownRoleRow.summary
        )

        // Blank-role + non-blank content: ONE collapsed bounded row too.
        val blankRoleRow = timeline[3] as GenericGatewayActivity
        assertEquals("hist-event-5", blankRoleRow.id)
        assertFalse(blankRoleRow.isExpanded)
        assertEquals(
            BoundedPayloadMapper.boundedNameType("history.unknown"),
            blankRoleRow.eventType
        )

        // No raw payload text anywhere in the timeline: neither entry body is
        // rendered as message text, summary, or preview (SEC-001 / TASK-010).
        timeline.forEach { entry ->
            when (entry) {
                is UserMessage -> assertFalse(entry.text.contains("step output"))
                is AssistantMessage -> assertFalse(entry.text.contains("step output"))
                is GenericGatewayActivity -> {
                    assertFalse(entry.summary.contains("step output"))
                    assertFalse(entry.payloadPreview?.contains("step output") == true)
                }
                else -> {}
            }
        }
        // RISK-003: skipped blank entries counted for debug diagnostics.
        assertEquals(2, viewModel.skippedHistoryCount)
    }

    @Test
    fun loadSession_assistantHistoryReasoning_hydratesCollapsedReasoningEntry() = runTest {
        // F-007 polish (deferred from PR #59): host assistant resume rows may
        // carry reasoning/reasoning_content (_HISTORY_ASSISTANT_DETAIL_KEYS,
        // session_history.py). When the parser preserves that raw data, the
        // historical assistant row must expose it under the SAME collapsed
        // reasoning surface used by live reasoning (S2) and final reasoning
        // (S4): one ReasoningEntry appended after the assistant row.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "question"),
                    SessionMessage(
                        role = "assistant",
                        content = "answer",
                        reasoning = "why I answered this"
                    ),
                    SessionMessage(role = "assistant", content = "no reasoning row")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val reasoningEntries = timeline.filterIsInstance<ReasoningEntry>()
        assertEquals(1, reasoningEntries.size)
        assertEquals("why I answered this", reasoningEntries.single().text)
        assertEquals(ReasoningDisplayKind.REASONING, reasoningEntries.single().kind)
        assertFalse(reasoningEntries.single().isExpanded)

        // Order: question, answer, reasoning (after its assistant row), then
        // the assistant row without reasoning.
        assertEquals(
            listOf("hist-user-0", "hist-asst-1", "reasoning-hist-asst-1", "hist-asst-2"),
            timeline.map { it.id }
        )
    }

    @Test
    fun loadSession_blankAssistantRowWithReasoning_reasoningRidesNothing() = runTest {
        // Self-review guard: a blank assistant row is skipped entirely; its
        // raw reasoning (if the host shipped one) must not leak into the
        // timeline as an orphan reasoning entry with no assistant row.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(
                    SessionMessage(role = "assistant", content = "   ", reasoning = "orphan")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertTrue(timeline.isEmpty())
        assertTrue(timeline.filterIsInstance<ReasoningEntry>().isEmpty())
        assertEquals(1, viewModel.skippedHistoryCount)
    }

    @Test
    fun loadSession_blankOnlyHistory_rendersNothing() = runTest {
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "assistant", content = ""),
                    SessionMessage(role = "user", content = "  \n\t ")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.timeline.isEmpty())
        assertEquals(2, viewModel.skippedHistoryCount)
    }

    @Test
    fun loadSession_historicalToolRows_renderAsCollapsedCompletedToolEntries() = runTest {
        // F007-S1: a host session.resume snapshot contains role:"tool" rows with
        // name/context and NO content. Hydration must render them as completed,
        // collapsed tool entries instead of silently dropping blank bodies.
        // A blank-content TEXT row (assistant) still follows the old blank-skip.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "user", content = "Question"),
                    SessionMessage(
                        role = "tool",
                        content = "",
                        toolName = "web_search",
                        toolContext = "searching docs for gateway"
                    ),
                    SessionMessage(role = "assistant", content = "")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // user text + the tool row; the blank assistant TEXT row is still skipped.
        assertEquals(2, timeline.size)

        val userRow = timeline[0] as UserMessage
        assertEquals("hist-user-0", userRow.id)

        // Historical tool row: completed, collapsed, stable unique ID.
        val toolRow = timeline[1] as ToolActivity
        assertEquals("hist-tool-1", toolRow.id)
        assertEquals(ToolStatus.COMPLETED, toolRow.status)
        assertFalse(toolRow.isExpanded)
        assertEquals(BoundedPayloadMapper.boundedNameType("web_search"), toolRow.name)
        assertEquals(
            BoundedPayloadMapper.boundedContext("searching docs for gateway"),
            toolRow.context
        )

        // The blank text body is never rendered anywhere in the tool row.
        assertFalse(toolRow.name.contains("Question"))
        assertEquals(1, viewModel.skippedHistoryCount)
    }

    @Test
    fun loadSession_successfulWriteKeepsFileReferenceWithoutRawResult() = runTest {
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult(
            runtimeSessionId = "runtime-100", durableSessionId = "sess-100", messageCount = 1,
            messages = listOf(SessionMessage(role = "tool", toolName = "write_file",
                content = """{"bytes_written":12,"files_modified":["report.txt"]}"""))
        ))
        val viewModel = ChatViewModel(sessionId = "sess-100", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter, pendingTurnLedger = ledger)
        viewModel.loadSession(); advanceUntilIdle()
        val row = viewModel.uiState.value.timeline.single() as ToolActivity
        assertEquals("report.txt", row.files.single().name)
        assertNull(row.resultSummary)
        assertNull(row.argsSummary)
    }

    @Test
    fun loadSession_toolRowWithoutNameOrContext_rendersBoundedGenericRow() = runTest {
        // F007-S1 edge: a role:"tool" row with blank name/context and no content
        // still must not vanish — it falls back to the collapsed generic
        // historical-activity row (bounded, no raw payload).
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(
                    SessionMessage(role = "tool", content = "", toolName = null, toolContext = null)
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(1, timeline.size)
        val fallbackRow = timeline[0] as GenericGatewayActivity
        assertEquals("hist-event-0", fallbackRow.id)
        assertFalse(fallbackRow.isExpanded)
        assertEquals(
            BoundedPayloadMapper.boundedNameType("history.tool"),
            fallbackRow.eventType
        )
    }

    @Test
    fun loadSession_emptyMessagesArray_rendersEmptyTimeline() = runTest {
        // CHG-005-S2 P3: an empty messages array (count 0) hydrates to an
        // empty timeline with no skipped entries and no error surfaced.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList()
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.timeline.isEmpty())
        assertEquals(0, viewModel.skippedHistoryCount)
        assertNull(state.errorMessage)
        assertFalse(state.isInitialLoading)
    }

    @Test
    fun loadSession_unknownRoleEntry_rendersOneCollapsedBoundedRow_noRawPayload() = runTest {
        // CHG-005-S3 (TASK-010/011): a tool/step event role in history renders
        // exactly ONE collapsed bounded row — never an assistant bubble, never
        // a raw payload copy, no invented cards (RISK-002: this row exists
        // only because the host reported the entry).
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "user", content = "run the tool"),
                    SessionMessage(role = "tool", content = "raw tool body that must not render")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(2, timeline.size)

        val eventRow = timeline[1] as GenericGatewayActivity
        assertEquals("hist-event-1", eventRow.id)
        // Collapsed by default, expandable like live cards (S8 toggling path).
        assertFalse(eventRow.isExpanded)
        assertEquals(BoundedPayloadMapper.boundedNameType("history.tool"), eventRow.eventType)
        assertEquals(
            BoundedPayloadMapper.boundedCollapsedSummary("Historical Hermes activity: tool"),
            eventRow.summary
        )
        // No raw payload rendering: the raw body appears nowhere in the row.
        assertFalse(eventRow.summary.contains("raw tool body"))
        assertFalse(eventRow.payloadPreview?.contains("raw tool body") == true)

        // Toggle path stays consistent with live generic cards.
        viewModel.toggleGenericActivityExpanded("hist-event-1")
        assertTrue(
            (viewModel.uiState.value.timeline[1] as GenericGatewayActivity).isExpanded
        )
    }

    @Test
    fun loadSession_blankRoleNonBlankContent_rendersOneCollapsedBoundedRow() = runTest {
        // CHG-005-S3 (skeptic P3 ownership): blank-role + non-blank body is a
        // collapsed bounded row, not a skipped entry and not an assistant bubble.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 2,
                messages = listOf(
                    SessionMessage(role = "", content = "unattributed step body"),
                    SessionMessage(role = "assistant", content = "final reply")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(2, timeline.size)

        val blankRoleRow = timeline[0] as GenericGatewayActivity
        assertEquals("hist-event-0", blankRoleRow.id)
        assertFalse(blankRoleRow.isExpanded)
        assertEquals(
            BoundedPayloadMapper.boundedNameType("history.unknown"),
            blankRoleRow.eventType
        )
        assertEquals(
            BoundedPayloadMapper.boundedCollapsedSummary("Historical Hermes activity: unknown"),
            blankRoleRow.summary
        )
        // No raw payload text in the row or anywhere in the timeline.
        assertFalse(blankRoleRow.summary.contains("unattributed step body"))
        timeline.forEach { entry ->
            when (entry) {
                is UserMessage -> assertFalse(entry.text.contains("unattributed step body"))
                is AssistantMessage -> assertFalse(entry.text.contains("unattributed step body"))
                else -> {}
            }
        }

        // The assistant entry that follows still renders normally.
        val assistantRow = timeline[1] as AssistantMessage
        assertEquals("final reply", assistantRow.text)
        assertEquals(AssistantMessageState.COMPLETE, assistantRow.state)
        assertEquals("hist-asst-1", assistantRow.id)
        assertEquals(0, viewModel.skippedHistoryCount)
    }

    @Test
    fun loadSession_mixedFixture_unknownRowsRenderInOrder_withStableHistIds() = runTest {
        // CHG-005-S3 (fixture ordering): interleaved known/unknown roles keep
        // original order and stable source-index IDs across entry kinds.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 5,
                messages = listOf(
                    SessionMessage(role = "tool", content = "step one"),
                    SessionMessage(role = "user", content = "mid prompt"),
                    SessionMessage(role = "", content = "blank role body"),
                    SessionMessage(role = "assistant", content = "reply"),
                    SessionMessage(role = "step", content = "step two")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(5, timeline.size)

        val kinds = timeline.map { entry ->
            when (entry) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                is GenericGatewayActivity -> "event"
                else -> "other"
            }
        }
        assertEquals(listOf("event", "user", "event", "assistant", "event"), kinds)

        val eventIds = timeline
            .filterIsInstance<GenericGatewayActivity>()
            .map { it.id }
        assertEquals(listOf("hist-event-0", "hist-event-2", "hist-event-4"), eventIds)

        // Every unknown-role row is collapsed and bounded — no raw payloads.
        timeline.filterIsInstance<GenericGatewayActivity>().forEach { row ->
            assertFalse(row.isExpanded)
            assertFalse(row.summary.contains("step one"))
            assertFalse(row.summary.contains("step two"))
            assertFalse(row.summary.contains("blank role body"))
        }
    }

    @Test
    fun loadSession_toolRowWithBodyAndName_staysOnToolPath() = runTest {
        // F007-S1 decision: tool identity is the host's nonblank name/context
        // evidence, never the body. A legacy/body-bearing tool row must stay on
        // the tool path (ToolActivity, name preserved) instead of degrading to a
        // generic unknown-role row that loses the tool name. The body is still
        // never rendered anywhere.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(
                    SessionMessage(
                        role = "tool",
                        content = "legacy tool body that must not render",
                        toolName = "read_file",
                        toolContext = "reading the plan"
                    )
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertEquals(1, timeline.size)
        val toolRow = timeline[0] as ToolActivity
        assertEquals("hist-tool-0", toolRow.id)
        assertEquals(ToolStatus.COMPLETED, toolRow.status)
        assertFalse(toolRow.isExpanded)
        assertEquals(BoundedPayloadMapper.boundedNameType("read_file"), toolRow.name)
        assertEquals(0, viewModel.skippedHistoryCount)

        // The raw legacy body is never rendered as row text.
        assertFalse(toolRow.name.contains("legacy tool body"))
        assertFalse(toolRow.context?.contains("legacy tool body") == true)
    }

    @Test
    fun loadSession_blankBodiesStillSkipped_unknownRoleOrNot() = runTest {
        // CHG-005-S3 preserves S2 semantics for TEXT rows: blank bodies for
        // user/assistant/unknown roles remain skipped and counted in
        // skippedHistoryCount (RISK-002). F007-S1 narrows this to TEXT rows
        // only: a bare role:"tool" blank-body row (no name/context) now renders
        // ONE bounded generic historical-activity row instead of vanishing.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-100",
                durableSessionId = "sess-100",
                messageCount = 3,
                messages = listOf(
                    SessionMessage(role = "tool", content = ""),
                    SessionMessage(role = "", content = "   \n"),
                    SessionMessage(role = "assistant", content = "kept")
                )
            )
        )
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.loadSession()
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        // The blank blank-role row is skipped; the bare tool row renders ONE
        // collapsed bounded generic row (F007-S1: it can never silently vanish).
        assertEquals(2, timeline.size)
        assertEquals("kept", (timeline[1] as AssistantMessage).text)
        val bareToolRow = timeline[0] as GenericGatewayActivity
        assertEquals("hist-event-0", bareToolRow.id)
        assertFalse(bareToolRow.isExpanded)
        assertEquals(1, viewModel.skippedHistoryCount)
    }

    @Test
    fun refreshSession_successReplacesHistory_preservesDraft_andDoesNotSubmit() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = ChatViewModel("sess-100", fakeRepo, adapter, ledger)
        advanceUntilIdle()
        val beforeTimeline = viewModel.uiState.value.timeline
        viewModel.updateDraft("keep this draft")
        advanceUntilIdle()
        val beforeSubmissions = adapter.submittedFrames.size
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-refresh",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "assistant", content = "authoritative refresh"))
            )
        )

        viewModel.refreshSession()
        advanceUntilIdle()

        assertEquals(2, fakeRepo.resumeSessionCalls)
        assertTrue(beforeTimeline.isNotEmpty())
        assertEquals("authoritative refresh", (viewModel.uiState.value.timeline.single() as AssistantMessage).text)
        assertEquals("keep this draft", viewModel.uiState.value.composerDraft)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.refreshErrorMessage)
        assertFalse(viewModel.uiState.value.isInitialLoading)
        assertEquals(beforeSubmissions, adapter.submittedFrames.size)
    }

    @Test
    fun refreshSession_failure_preservesTimelineAndDraft_withoutSnapshotFallback() = runTest {
        val viewModel = ChatViewModel("sess-100", fakeRepo, fakeAdapter, ledger)
        advanceUntilIdle()
        val beforeTimeline = viewModel.uiState.value.timeline
        viewModel.updateDraft("draft survives failure")
        advanceUntilIdle()
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("offline"))

        viewModel.refreshSession()
        advanceUntilIdle()

        assertEquals(2, fakeRepo.resumeSessionCalls)
        assertEquals(beforeTimeline, viewModel.uiState.value.timeline)
        assertEquals("draft survives failure", viewModel.uiState.value.composerDraft)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals("Unable to refresh chat. Try again.", viewModel.uiState.value.refreshErrorMessage)
        assertEquals(0, fakeRepo.fetchHistorySnapshotCalls)
    }

    @Test
    fun refreshSession_guardsNewSessionSubmitStream_andInFlightActivation() = runTest {
        val viewModel = ChatViewModel("sess-100", fakeRepo, fakeAdapter, ledger)
        advanceUntilIdle()
        fakeRepo.resumeGate = CompletableDeferred()
        viewModel.refreshSession()
        runCurrent()
        assertEquals(2, fakeRepo.resumeSessionCalls)
        viewModel.refreshSession()
        assertEquals(2, fakeRepo.resumeSessionCalls)
        fakeRepo.resumeGate!!.complete(Unit)
        advanceUntilIdle()

        val newSession = ChatViewModel("new-session", fakeRepo, fakeAdapter, ledger)
        advanceUntilIdle()
        newSession.refreshSession()
        assertEquals(2, fakeRepo.resumeSessionCalls)

        viewModel.uiStateFlow().value = viewModel.uiState.value.copy(isSubmitting = true)
        viewModel.refreshSession()
        assertEquals(2, fakeRepo.resumeSessionCalls)
        viewModel.uiStateFlow().value = viewModel.uiState.value.copy(isSubmitting = false, isStreaming = true)
        viewModel.refreshSession()
        assertEquals(2, fakeRepo.resumeSessionCalls)
    }

    @Test
    fun refreshSession_success_preservesRuntimeErrorAndSubmitBlockState() = runTest {
        val viewModel = ChatViewModel("sess-100", fakeRepo, fakeAdapter, ledger)
        advanceUntilIdle()
        viewModel.updateDraft("draft survives refresh")
        viewModel.uiStateFlow().value = viewModel.uiState.value.copy(
            errorMessage = "runtime error remains visible",
            submitBlockedReason = SubmitBlockedReason.NO_RUNTIME
        )
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-refresh-preserves-errors",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "assistant", content = "new history"))
            )
        )

        viewModel.refreshSession()
        advanceUntilIdle()

        assertEquals("runtime error remains visible", viewModel.uiState.value.errorMessage)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals("draft survives refresh", viewModel.uiState.value.composerDraft)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.refreshErrorMessage)
    }

    @Test
    fun refreshSession_success_doesNotLookupOrPersistProfileModelDefault() = runTest {
        val adapter = RecordingReasoningDefaultAdapter(fakeAdapter)
        val profileVault = CountingProfileDefaultVault(vault)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = profileVault
        )
        advanceUntilIdle()
        adapter.reasoningDefaultCalls = 0
        profileVault.profileDefaultReadCalls = 0
        profileVault.profileDefaultWriteCalls = 0
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-refresh-no-default-side-effect",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "assistant", content = "new history")),
                info = mapOf("model" to "gpt-refresh")
            )
        )

        viewModel.refreshSession()
        advanceUntilIdle()

        assertEquals(0, adapter.reasoningDefaultCalls)
        assertEquals(0, profileVault.profileDefaultReadCalls)
        assertEquals(0, profileVault.profileDefaultWriteCalls)
    }

    @Test
    fun refreshSession_inFlight_fencesComposerAndSubmit_preservesDraft() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()
        viewModel.updateDraft("prompt held during refresh")
        val beforeTimeline = viewModel.uiState.value.timeline
        fakeRepo.resumeGate = CompletableDeferred()
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-refresh-interleaving",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "assistant", content = "authoritative history"))
            )
        )

        viewModel.refreshSession()
        runCurrent()
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertFalse(viewModel.uiState.value.isComposerReady)

        viewModel.submitPrompt()
        runCurrent()

        assertEquals(0, adapter.submittedFrames.size)
        assertEquals("prompt held during refresh", viewModel.uiState.value.composerDraft)
        assertEquals(beforeTimeline, viewModel.uiState.value.timeline)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertFalse(viewModel.uiState.value.isStreaming)

        fakeRepo.resumeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("authoritative history", (viewModel.uiState.value.timeline.single() as AssistantMessage).text)
        assertEquals("prompt held during refresh", viewModel.uiState.value.composerDraft)
    }

    @Test
    fun refreshSession_inFlight_retryAmbiguousPrompt_isFenced_andRetryWorksAfterRefreshCompletes() = runTest {
        // CHAT-019-PROD-003 (closure blocker, repair round 2): with a pending
        // UNKNOWN turn and a manual refresh suspended on the gated fake
        // repository, an explicit "Send as new prompt" retry must NOT launch a
        // turn inside the refresh window. The retry that lands mid-window would
        // be displaced by applyManualRefreshSuccess() replacing the timeline.
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()
        val seededRuntime = viewModel.uiState.value.runtimeSessionId
        assertFalse(seededRuntime.isNullOrBlank())

        // Prior prompt write ended ambiguous: UNKNOWN turn awaiting explicit retry.
        val seeded = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "ambiguous prompt",
            runtimeSessionId = seededRuntime
        )
        ledger.markUnknown(seeded.pendingTurnId)
        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(pendingUnknownTurnId = seeded.pendingTurnId)

        // Start the manual refresh against the gated resume; it hangs in flight.
        fakeRepo.resumeGate = CompletableDeferred()
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-refresh-retry-fence",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "assistant", content = "authoritative refresh"))
            )
        )
        viewModel.refreshSession()
        runCurrent()
        assertTrue(viewModel.uiState.value.isRefreshing)

        val beforeFrames = adapter.submittedFrames.size
        val beforeLedger = ledger.getAllPendingTurns().size
        val beforeTimeline = viewModel.uiState.value.timeline

        // Activate the retry affordance mid-refresh (the UI button is enabled
        // here only pre-repair; the ViewModel must fence it regardless).
        viewModel.retryAmbiguousPrompt()
        runCurrent()

        // No turn launch: no coordinator retry frame, no ledger mutation, no
        // submission state, no timeline mutation, UNKNOWN affordance retained.
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(beforeFrames, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertEquals(
            PendingTurnState.UNKNOWN,
            ledger.getAllPendingTurns().single().state
        )
        assertEquals(seeded.pendingTurnId, viewModel.uiState.value.pendingUnknownTurnId)
        assertEquals(beforeTimeline, viewModel.uiState.value.timeline)

        // The refresh lane is untouched by the fenced retry.
        assertTrue(viewModel.uiState.value.isRefreshing)
        assertNull(viewModel.uiState.value.refreshErrorMessage)

        // Refresh completes; the authoritative replacement must not clobber an
        // active retry turn because none was launched.
        fakeRepo.resumeGate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals("authoritative refresh", (viewModel.uiState.value.timeline.single() as AssistantMessage).text)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(2, fakeRepo.resumeSessionCalls)

        // The retry affordance works again after the refresh completes.
        viewModel.retryAmbiguousPrompt()
        advanceUntilIdle()
        assertEquals(beforeFrames + 1, adapter.submittedFrames.size)
        assertEquals("ambiguous prompt", adapter.submittedFrames.single().second)
        assertNull(viewModel.uiState.value.pendingUnknownTurnId)
        assertTrue(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun retryAmbiguousPrompt_fencedWhileSubmittingOrStreaming_noTurnLaunch() = runTest {
        // Same consolidated fence mirrors the V2 submitPrompt() guard family:
        // no retry may launch while a local turn is submitting or streaming.
        val adapter = recordingAdapter()
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()
        val seededRuntime = viewModel.uiState.value.runtimeSessionId
        assertFalse(seededRuntime.isNullOrBlank())
        val seeded = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "ambiguous prompt",
            runtimeSessionId = seededRuntime
        )
        ledger.markUnknown(seeded.pendingTurnId)
        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(pendingUnknownTurnId = seeded.pendingTurnId)

        val beforeFrames = adapter.submittedFrames.size
        val beforeLedger = ledger.getAllPendingTurns().size

        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(isSubmitting = true)
        viewModel.retryAmbiguousPrompt()
        assertEquals(beforeFrames, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertEquals(seeded.pendingTurnId, viewModel.uiState.value.pendingUnknownTurnId)

        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(isSubmitting = false, isStreaming = true)
        viewModel.retryAmbiguousPrompt()
        assertEquals(beforeFrames, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertEquals(seeded.pendingTurnId, viewModel.uiState.value.pendingUnknownTurnId)
    }

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_test_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        queue = PendingInterruptQueue(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeChatSessionRepository()
    }

    private fun kotlinx.coroutines.test.TestScope.loadedPickerVm(): ChatViewModel {
        val vm = ChatViewModel(sessionId = "sess-100", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter, pendingTurnLedger = ledger)
        vm.loadSession()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canOpenPicker)
        vm.openSessionPicker()
        advanceUntilIdle()
        return vm
    }

    @Test fun pickerOldCatalogCannotClearNewerSetting() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerAuthority++
        fakeRepo.pickerContextGate = CompletableDeferred()
        vm.openSessionPicker()
        runCurrent()
        fakeRepo.pickerGate = CompletableDeferred()
        vm.pickSessionReasoning("high")
        runCurrent()
        fakeRepo.pickerContextGate!!.complete(Unit)
        runCurrent()
        assertEquals("high", vm.uiState.value.activePickerChange?.effort)
        assertEquals(PickerChange.Phase.WRITING, vm.uiState.value.activePickerChange?.phase)
        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fakeRepo.pickerReasoningCalls.size)
    }

    @Test fun pickerLateCaptureFailureCannotChangeAnotherRuntime() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerCaptureGate = CompletableDeferred()
        vm.openSessionPicker()
        runCurrent()
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult("runtime-next", "sess-100", 0, emptyList()))
        vm.loadSession()
        runCurrent()
        fakeRepo.pickerCaptureFails = true
        fakeRepo.pickerCaptureGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("runtime-next", vm.uiState.value.runtimeSessionId)
        assertFalse(vm.uiState.value.pickerCatalogLoading)
        assertNull(vm.uiState.value.pickerNotice)
    }

    @Test fun pickerQueuedEchoDoesNotReplaceConfirmedStateAndNewRuntimeIsIndependent() = runTest {
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, emptyList(), info = mapOf("model" to "original-model")))
        val vm = loadedPickerVm()
        fakeRepo.pickerSetResult = GatewayResult.Success(io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(true, false))
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        advanceUntilIdle()
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, emptyList(), info = mapOf("model" to "gpt-5")))
        vm.checkPickerStatus()
        advanceUntilIdle()
        assertEquals("original-model", vm.uiState.value.sessionModelName)
        assertEquals(PickerChange.Phase.DEFERRED, vm.uiState.value.activePickerChange?.phase)
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult("runtime-next", "sess-100", 0, emptyList(), info = mapOf("model" to "next-model")))
        vm.loadSession()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activePickerChange)
        assertEquals("next-model", vm.uiState.value.sessionModelName)
        assertTrue(vm.uiState.value.pickerOptions.isEmpty())
    }

    @Test fun pickerFreshRefreshSettlesUnknownWithoutAnotherWrite() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerSetResult = GatewayResult.Failure(GatewayFailure.Transport("Timeout"))
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        advanceUntilIdle()
        fakeRepo.resumeOutcome = Result.success(SessionResumeResult("runtime-100", "sess-100", 0, emptyList(), info = mapOf("model" to "actual-model")))
        vm.checkPickerStatus()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activePickerChange)
        assertEquals("actual-model", vm.uiState.value.sessionModelName)
        assertEquals(1, fakeRepo.pickerModelCalls.size)
    }

    @Test fun pickerBeforeAckEchoSettlesOnlyAfterAcceptanceAndDeferredEchoDoesNot() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerGate = CompletableDeferred()
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        runCurrent()
        fakeAdapter.eventFlow.emit(GatewayEvent.SessionInfo("runtime-100", null, mapOf("model" to "gpt-5")))
        runCurrent()
        assertEquals(PickerChange.Phase.WRITING, vm.uiState.value.activePickerChange?.phase)
        assertNull(vm.uiState.value.sessionModelName)
        fakeRepo.pickerGate!!.complete(Unit)
        runCurrent()
        assertNull(vm.uiState.value.activePickerChange)
        fakeRepo.pickerSetResult = GatewayResult.Success(io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(true, false))
        vm.pickSessionReasoning("high")
        runCurrent()
        fakeAdapter.eventFlow.emit(GatewayEvent.SessionInfo("runtime-100", null, mapOf("reasoning_effort" to "high")))
        advanceUntilIdle()
        assertEquals(PickerChange.Phase.DEFERRED, vm.uiState.value.activePickerChange?.phase)
        assertNull(vm.uiState.value.sessionReasoningEffort)
    }

    @Test fun pickerSlashUpdatesImmediatelyAndPreservesNewDraftDuringResponse() = runTest {
        val commands = object : io.github.anshuman2401.hermesmobile.data.commands.SlashCommandRepository {
            override suspend fun capture(storedId: String, runtimeId: String) = io.github.anshuman2401.hermesmobile.data.commands.CommandSession(storedId, runtimeId)
            override suspend fun isCurrent(session: io.github.anshuman2401.hermesmobile.data.commands.CommandSession) = true
            override suspend fun suggestions(session: io.github.anshuman2401.hermesmobile.data.commands.CommandSession, query: String) = emptyList<io.github.anshuman2401.hermesmobile.data.commands.SlashSuggestion>()
            override suspend fun execute(session: io.github.anshuman2401.hermesmobile.data.commands.CommandSession, invocation: io.github.anshuman2401.hermesmobile.data.commands.SlashInvocation): io.github.anshuman2401.hermesmobile.data.commands.CommandOutcome = error("Picker writes must share picker lifecycle")
        }
        val vm = ChatViewModel(sessionId = "sess-100", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter, pendingTurnLedger = ledger, slashCommands = commands)
        vm.loadSession()
        advanceUntilIdle()
        fakeRepo.pickerGate = CompletableDeferred()
        vm.updateDraft("/reasoning high")
        vm.submitPrompt()
        assertEquals("high", vm.uiState.value.activePickerChange?.effort)
        runCurrent()
        vm.updateDraft("Next draft")
        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("Next draft", vm.uiState.value.composerDraft)
        assertEquals(1, fakeRepo.pickerReasoningCalls.size)
    }

    @Test fun pickerLateCatalogCannotPublishAcrossWorkspaceChange() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.catalogGate = CompletableDeferred()
        vm.openSessionPicker()
        runCurrent()
        fakeRepo.pickerAuthority++
        fakeRepo.pickerModels = emptyList()
        fakeRepo.catalogGate!!.complete(Unit)
        advanceUntilIdle()
        // Replaced authority invalidates the previous catalog too.
        assertTrue(vm.uiState.value.pickerOptions.isEmpty())
        vm.openSessionPicker()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pickerOptions.isEmpty())
    }

    @Test fun pickerImmediateCaptionDraftAndSendBeforeHostReply() = runTest {
        val vm = loadedPickerVm()
        vm.updateDraft("Keep this draft")
        fakeRepo.pickerGate = CompletableDeferred()
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        assertTrue(vm.uiState.value.sessionModelLabel!!.contains("gpt-5"))
        assertNull(vm.uiState.value.sessionModelName)
        assertEquals("Keep this draft", vm.uiState.value.composerDraft)
        assertTrue(vm.uiState.value.isComposerReady)
        assertFalse(vm.uiState.value.canChangePicker)
        runCurrent()
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        assertEquals(1, fakeRepo.pickerModelCalls.size)
        fakeRepo.pickerGate!!.complete(Unit)
        runCurrent()
        assertEquals(PickerChange.Phase.CONFIRMING, vm.uiState.value.activePickerChange?.phase)
        fakeAdapter.eventFlow.emit(GatewayEvent.SessionInfo("runtime-100", null, mapOf("model" to "gpt-5")))
        runCurrent()
        assertNull(vm.uiState.value.activePickerChange)
        assertEquals("gpt-5", vm.uiState.value.sessionModelName)
        assertEquals("Keep this draft", vm.uiState.value.composerDraft)
    }

    @Test fun pickerRejectionRollsBackAndTimeoutNeverResends() = runTest {
        val vm = loadedPickerVm()
        val before = vm.uiState.value.sessionModelLabel
        fakeRepo.pickerSetResult = GatewayResult.Failure(GatewayFailure.Rpc(-32602, "Rejected"))
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        advanceUntilIdle()
        assertNull(vm.uiState.value.activePickerChange)
        assertEquals(before, vm.uiState.value.sessionModelLabel)
        assertEquals("Couldn’t change the setting.", vm.uiState.value.pickerStatus)
        fakeRepo.pickerSetResult = GatewayResult.Failure(GatewayFailure.Transport("Timeout"))
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        advanceUntilIdle()
        assertEquals(PickerChange.Phase.UNCONFIRMED, vm.uiState.value.activePickerChange?.phase)
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("Unavailable"))
        vm.checkPickerStatus()
        advanceUntilIdle()
        vm.pickSessionReasoning("low")
        assertEquals(2, fakeRepo.pickerModelCalls.size)
        assertTrue(fakeRepo.pickerReasoningCalls.isEmpty())
        assertEquals(PickerChange.Phase.UNCONFIRMED, vm.uiState.value.activePickerChange?.phase)
    }

    @Test fun pickerCatalogKeepsRowsDuringRefreshAndFailure() = runTest {
        val vm = loadedPickerVm()
        val options = vm.uiState.value.pickerOptions
        fakeRepo.catalogGate = CompletableDeferred()
        vm.openSessionPicker()
        runCurrent()
        assertEquals(options, vm.uiState.value.pickerOptions)
        assertTrue(vm.uiState.value.pickerCatalogLoading)
        fakeRepo.catalogFails = true
        fakeRepo.catalogGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(options, vm.uiState.value.pickerOptions)
        assertFalse(vm.uiState.value.pickerCatalogLoading)
    }

    @Test fun pickerWorkspaceChangeDiscardsLateWrite() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerGate = CompletableDeferred()
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        runCurrent()
        fakeRepo.pickerAuthority++
        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.activePickerChange)
        assertTrue(vm.uiState.value.pickerOptions.isEmpty())
        assertEquals(1, fakeRepo.pickerModelCalls.size)
    }

    @Test fun pickerF_deferredKeepsRequestedPresentationAndPreventsAnotherWrite() = runTest {
        val vm = loadedPickerVm()
        val before = vm.uiState.value.sessionModelLabel
        fakeRepo.pickerSetResult = io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(deferred = true, confirmRequired = false))
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        advanceUntilIdle()
        assertEquals(listOf("runtime-100" to fakeRepo.pickerModels.single()), fakeRepo.pickerModelCalls)
        assertEquals("gpt-5", vm.uiState.value.activePickerChange?.model)
        assertTrue(vm.uiState.value.pickerStatus!!.contains("not yet confirmed"))
        fakeRepo.pickerSetResult = io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Failure(
            io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure.Transport("apply failed"))
        vm.pickSessionReasoning("low")
        advanceUntilIdle()
        assertEquals("gpt-5", vm.uiState.value.activePickerChange?.model)
        assertTrue(vm.uiState.value.pickerStatus!!.contains("not yet confirmed"))
    }

    @Test fun pickerG_noSessionOrConfirmationNeverGlobalizes() = runTest {
        val absent = ChatViewModel(sessionId = "new-session", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter, pendingTurnLedger = ledger)
        advanceUntilIdle()
        absent.openSessionPicker()
        absent.pickSessionReasoning("low")
        assertTrue(fakeRepo.pickerReasoningCalls.isEmpty())
        val vm = loadedPickerVm()
        fakeRepo.pickerSetResult = io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(deferred = false, confirmRequired = true))
        vm.pickSessionReasoning("high")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pickerNotice!!.contains("requires confirmation"))
        assertEquals(listOf("runtime-100" to "high"), fakeRepo.pickerReasoningCalls)
    }

    @Test fun pickerH_singleFlightAndStaleRuntimeIgnoreLateResponse() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerGate = kotlinx.coroutines.CompletableDeferred()
        vm.pickSessionModel(fakeRepo.pickerModels.single())
        runCurrent()
        vm.pickSessionReasoning("low")
        assertTrue(fakeRepo.pickerReasoningCalls.isEmpty())
        fakeRepo.resumeOutcome = Result.success(io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult(
            runtimeSessionId = "runtime-other", durableSessionId = "sess-100", messageCount = 0, messages = emptyList()))
        vm.loadSession()
        runCurrent()
        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("runtime-other", vm.uiState.value.runtimeSessionId)
        assertNull(vm.uiState.value.pickerNotice)
        assertEquals(1, fakeRepo.pickerModelCalls.size)
    }

    @Test fun pickerRepair_sameRuntimeHydrationKeepsFirstRequestOwner() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerGate = kotlinx.coroutines.CompletableDeferred()
        vm.pickSessionReasoning("high")
        runCurrent()
        assertEquals(listOf("runtime-100" to "high"), fakeRepo.pickerReasoningCalls)

        vm.loadSession() // The host returns the SAME runtime while the first RPC is suspended.
        runCurrent()
        assertTrue("first request still owns the picker after same-runtime resume", vm.uiState.value.pickerBusy)
        vm.pickSessionReasoning("low")
        runCurrent()
        assertEquals("second tap must not dispatch", listOf("runtime-100" to "high"), fakeRepo.pickerReasoningCalls)

        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(PickerChange.Phase.UNCONFIRMED, vm.uiState.value.activePickerChange?.phase)
        assertTrue(!vm.uiState.value.pickerBusy)
    }

    @Test fun pickerRepair_deferredNoticeSurvivesDismissAndCatalogReopen() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerSetResult = io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult.Success(
            io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(deferred = true, confirmRequired = false))
        vm.pickSessionReasoning("high")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pickerStatus!!.contains("not yet confirmed"))

        // Dismissal changes only the composable's sheet flag; a reopen fetches a fresh catalog.
        vm.openSessionPicker()
        runCurrent()
        assertTrue("pending status remains visible after successful catalog reload",
            vm.uiState.value.pickerStatus?.contains("not yet confirmed") == true)
        assertEquals(listOf("runtime-100" to "high"), fakeRepo.pickerReasoningCalls)
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        assertEquals(PickerChange.Phase.DEFERRED, vm.uiState.value.activePickerChange?.phase)
        fakeAdapter.connectionStatus.value = ConnectionStatus.CONNECTED
        runCurrent()
        vm.openSessionPicker()
        advanceUntilIdle()
        assertEquals(PickerChange.Phase.DEFERRED, vm.uiState.value.activePickerChange?.phase)
    }

    @Test fun pickerD_seedGuardStartsBeforeAnyRepositoryWrite() = runTest {
        val vm = loadedPickerVm()
        fakeRepo.pickerGate = kotlinx.coroutines.CompletableDeferred()
        vm.pickSessionReasoning("high")
        assertTrue(vm.uiState.value.pickerSwitchStarted)
        assertTrue(vm.uiState.value.pickerBusy)
        fakeRepo.pickerGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("runtime-100" to "high"), fakeRepo.pickerReasoningCalls)
    }

    // ------------------------------------------------------------------
    // T3 PR-2 (plan TASK-005 / TEST-001): capture a failed best-effort stop
    // as a durable PendingInterruptQueue hold keyed by the STORED session id.
    // Success, terminal rejections, and a missing stored id never enqueue;
    // the local optimistic finalize stays byte-identical in every case
    // (FD-005 — capture only observes T1's GatewayResult, it never gates it).
    // ------------------------------------------------------------------

    private fun newStopVm(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = ledger,
        pendingInterruptQueue = queue
    )

    private fun TestScope.streamingVm(): ChatViewModel {
        val viewModel = newStopVm()
        advanceUntilIdle()
        viewModel.updateDraft("stop capture probe")
        viewModel.submitPrompt()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isStreaming)
        return viewModel
    }

    private fun assertLocalStopFinalized(viewModel: ChatViewModel) {
        val state = viewModel.uiState.value
        assertFalse("Optimistic local stop must stay unconditional (FD-005)", state.isStreaming)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        // .last(): this fixture's timeline also carries the hydrated history
        // assistant row; the streamed row under test is always the newest.
        assertEquals(
            AssistantMessageState.INTERRUPTED,
            state.timeline.filterIsInstance<AssistantMessage>().last().state
        )
    }

    // REQ-001: stop tapped while the gateway is not connected enqueues exactly
    // one hold for the session (T1's pre-flight NotConnected is the capture
    // signal), and the local stop still finalizes untouched.
    @Test
    fun stopStreaming_notConnectedWhileOffline_enqueuesExactlyOneDurableHold() = runTest {
        fakeAdapter.interruptResult = GatewayResult.Failure(GatewayFailure.NotConnected())
        val viewModel = streamingVm()
        // Start the turn online; the connection drops before the Stop intent.
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals("T1 attempt still fired first", listOf("runtime-100"), fakeAdapter.interruptedSessions)
        val hold = queue.getPendingHolds().single()
        assertEquals("Hold keys on the stored session id", "sess-100", hold.storedSessionId)
        assertEquals("Enqueue-time runtime id snapshot", "runtime-100", hold.runtimeSessionId)
        assertLocalStopFinalized(viewModel)
    }

    // Success path: the host acked the interrupt, so nothing is held.
    @Test
    fun stopStreaming_interruptSuccess_enqueuesNothing() = runTest {
        fakeAdapter.interruptResult = GatewayResult.Success(InterruptSessionResult(ok = true))
        val viewModel = streamingVm()

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals("A delivered stop must never enqueue", 0, queue.getPendingHolds().size)
        assertLocalStopFinalized(viewModel)
    }

    // Connection-class failure where delivery threw before a typed result:
    // captured as a hold, local finalize untouched.
    @Test
    fun stopStreaming_interruptThrows_enqueuesHold_andStillFinalizes() = runTest {
        fakeAdapter.interruptException = IllegalStateException("synthetic transport blow-up")
        val viewModel = streamingVm()

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals(1, queue.getPendingHolds().size)
        assertLocalStopFinalized(viewModel)
    }

    // Non-terminal transport/RPC failures are host-unreachable class (a
    // reconnect can genuinely deliver them later) -> captured per TASK-005.
    @Test
    fun stopStreaming_connectionClassFailures_enqueueHold() = runTest {
        val capturable = listOf(
            GatewayResult.Failure(GatewayFailure.Transport("socket closed before interrupt ack")),
            GatewayResult.Failure(GatewayFailure.Rpc(code = -32603, message = "internal error"))
        )
        for (result in capturable) {
            queue.clear()
            fakeAdapter.interruptResult = result
            val viewModel = streamingVm()

            viewModel.stopStreaming()
            advanceUntilIdle()

            assertEquals("Must capture connection-class failure: $result", 1, queue.getPendingHolds().size)
            assertLocalStopFinalized(viewModel)
        }
    }

    // Auth rejection, capability-missing (-32601), invalid-request (-32600),
    // and protocol-incompatible failures are terminal: reconnect cannot make
    // them succeed, so a hold would only re-send a doomed interrupt (dispatch
    // failure-class rule; plan FD-005 captures the NotConnected/Transport/Rpc
    // CONNECTION class, not terminal rejections).
    @Test
    fun stopStreaming_terminalRejections_areNeverCaptured() = runTest {
        val rejections = listOf(
            GatewayResult.Failure(GatewayFailure.AuthenticationRequired()),
            GatewayResult.Failure(GatewayFailure.Rpc(code = 4401, message = "auth required")),
            GatewayResult.Failure(GatewayFailure.Rpc(code = 4403, message = "policy rejection")),
            GatewayResult.Failure(GatewayFailure.Transport("handshake rejected", statusCode = 403)),
            GatewayResult.Failure(GatewayFailure.Rpc(code = -32600, message = "invalid request")),
            GatewayResult.Failure(GatewayFailure.Rpc(code = -32601, message = "method not found")),
            GatewayResult.Failure(GatewayFailure.IncompatibleProtocol("wire version mismatch"))
        )
        for (result in rejections) {
            queue.clear()
            fakeAdapter.interruptResult = result
            val viewModel = streamingVm()

            viewModel.stopStreaming()
            advanceUntilIdle()

            assertTrue("Must not capture terminal rejection: $result", queue.getPendingHolds().isEmpty())
            assertLocalStopFinalized(viewModel)
        }
    }

    // No stored session id -> a hold would be undeliverable, so capture never
    // fires (plan FD-001 keys holds by the durable/stored id only).
    @Test
    fun stopStreaming_withoutStoredSessionId_neverEnqueues() = runTest {
        fakeAdapter.interruptResult = GatewayResult.Failure(GatewayFailure.NotConnected())
        val viewModel = streamingVm()
        viewModel.uiStateFlow().value = viewModel.uiState.value.copy(durableSessionId = null)

        viewModel.stopStreaming()
        advanceUntilIdle()

        assertEquals("No stored id -> no hold", 0, queue.getPendingHolds().size)
        assertLocalStopFinalized(viewModel)
    }

    @Test
    fun loadSession_hydratesServerHistoryIntoTimeline() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("sess-100", state.durableSessionId)
        assertEquals("runtime-100", state.runtimeSessionId)
        assertEquals(2, state.timeline.size)

        val userEntry = state.timeline[0] as UserMessage
        assertEquals("Initial question", userEntry.text)
        assertEquals(UserMessageState.SENT, userEntry.state)

        val asstEntry = state.timeline[1] as AssistantMessage
        assertEquals("Initial answer", asstEntry.text)
        assertEquals(AssistantMessageState.COMPLETE, asstEntry.state)
    }

    @Test
    fun submitPrompt_happyPath_recordsLedger_andStreamsAssistantDeltas() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        // Type prompt and submit
        viewModel.updateDraft("What is Hermes?")
        assertEquals("What is Hermes?", viewModel.uiState.value.composerDraft)

        viewModel.submitPrompt()

        // Draft cleared, user bubble added, provisional assistant created
        val state = viewModel.uiState.value
        assertEquals("", state.composerDraft)
        assertTrue(state.isStreaming)
        assertEquals(4, state.timeline.size) // 2 historical + 1 user + 1 assistant

        val userMessage = state.timeline[2] as UserMessage
        assertEquals("What is Hermes?", userMessage.text)
        assertEquals(UserMessageState.SENT, userMessage.state)

        val provisionalAssistant = state.timeline[3] as AssistantMessage
        assertEquals("", provisionalAssistant.text)
        assertEquals(AssistantMessageState.STREAMING, provisionalAssistant.state)

        // Stream deltas — BLD-006 S5: text is admitted and retained
        // synchronously; the timeline publication is coalesced onto the
        // virtual-time scheduler, so the window must elapse before the row
        // text updates. Publication count stays decoupled from delta count.
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 1L,
                text = "Hermes is "
            )
        )
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 2L,
                text = "an AI agent."
            )
        )
        advanceUntilIdle()

        val updatedAsst = viewModel.uiState.value.timeline[3] as AssistantMessage
        assertEquals("Hermes is an AI agent.", updatedAsst.text)
        assertEquals(AssistantMessageState.STREAMING, updatedAsst.state)

        // Terminal complete
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 3L,
                text = "Hermes is an AI agent.",
                status = "complete"
            )
        )
        advanceUntilIdle()

        val sealedAsst = viewModel.uiState.value.timeline[3] as AssistantMessage
        assertEquals(AssistantMessageState.COMPLETE, sealedAsst.state)
        assertFalse(viewModel.uiState.value.isStreaming)

        // Ledger turn marked resolved
        val ledgerTurn = ledger.getPendingTurn(userMessage.id)
        assertNotNull(ledgerTurn)
        assertEquals(PendingTurnState.RESOLVED, ledgerTurn!!.state)
    }

    @Test
    fun streamingTurn_interruptedState_rendersInterruptedChip() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.updateDraft("Generate a long story")
        viewModel.submitPrompt()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(
                sessionId = "runtime-100",
                seq = 1L,
                text = "Once upon a time..."
            )
        )
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = "Once upon a time...",
                status = "interrupted"
            )
        )
        advanceUntilIdle()

        val asstEntry = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals("Once upon a time...", asstEntry.text)
        assertEquals(AssistantMessageState.INTERRUPTED, asstEntry.state)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun streamingTurn_errorEvent_rendersFailedState() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        viewModel.updateDraft("Trigger an error")
        viewModel.submitPrompt()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.Error(
                sessionId = "runtime-100",
                seq = 1L,
                message = "Model context length exceeded"
            )
        )
        advanceUntilIdle()

        val asstEntry = viewModel.uiState.value.timeline.last() as AssistantMessage
        assertEquals(AssistantMessageState.FAILED, asstEntry.state)
        assertEquals("Model context length exceeded", asstEntry.errorMessage)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun toolActivity_fullLifecycle_andToggleExpand() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()

        // 1. Tool generating (preparing)
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolGenerating(
                sessionId = "runtime-100",
                seq = 1L,
                name = "code_interpreter"
            )
        )
        advanceUntilIdle()
        val prepTool = viewModel.uiState.value.timeline.last() as ToolActivity
        assertEquals("code_interpreter", prepTool.name)
        assertEquals(ToolStatus.PREPARING, prepTool.status)

        // 2. Tool start
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolStart(
                sessionId = "runtime-100",
                seq = 2L,
                toolId = "t-101",
                name = "code_interpreter",
                context = "Evaluating fibonacci(10)",
                args = mapOf("code" to "def fib(n): return n", "secret_key" to "sk-1234567890abcdef1234567890")
            )
        )
        advanceUntilIdle()
        val runningTool = viewModel.uiState.value.timeline.last() as ToolActivity
        assertEquals("t-101", runningTool.toolId)
        assertEquals(ToolStatus.RUNNING, runningTool.status)
        assertEquals("Evaluating fibonacci(10)", runningTool.context)
        // Full text retention (no display-level secret redaction)
        assertTrue(runningTool.argsSummary!!.contains("sk-1234567890"))
        assertFalse(runningTool.argsSummary!!.contains("[REDACTED]"))

        // 3. Tool complete
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolComplete(
                sessionId = "runtime-100",
                seq = 3L,
                toolId = "t-101",
                name = "code_interpreter",
                durationSeconds = 1.25,
                result = "Result: 55"
            )
        )
        advanceUntilIdle()
        val completedTool = viewModel.uiState.value.timeline.last() as ToolActivity
        assertEquals(ToolStatus.COMPLETED, completedTool.status)
        assertEquals(1.25, completedTool.durationSeconds!!, 0.01)
        assertEquals("Result: 55", completedTool.resultSummary)
        assertFalse(completedTool.isExpanded)

        // Toggle expand
        viewModel.toggleToolExpanded(completedTool.id)
        val expandedTool = viewModel.uiState.value.timeline.last() as ToolActivity
        assertTrue(expandedTool.isExpanded)
    }

    @Test
    fun submissionFailure_marksTurnUnknown_andEnforcesExplicitRetry() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )

        // Configure adapter failure
        fakeAdapter.submitPromptResult = GatewayResult.Failure(
            GatewayFailure.SubmissionUnknown("Network connection lost during write")
        )

        viewModel.updateDraft("Will fail submission")
        viewModel.submitPrompt()

        val state = viewModel.uiState.value
        assertEquals(SubmissionStatus.UNKNOWN, state.submissionStatus)
        assertNotNull(state.pendingUnknownTurnId)

        val userMessage = state.timeline.last() as UserMessage
        assertEquals(UserMessageState.UNKNOWN, userMessage.state)

        // Auto-send is prohibited
        assertFalse(ledger.canAutoSend(state.pendingUnknownTurnId!!))

        // Reset adapter to success for retry
        fakeAdapter.submitPromptResult = GatewayResult.Success(io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult("streaming"))

        // Explicit user retry
        viewModel.retryAmbiguousPrompt()

        val retriedState = viewModel.uiState.value
        assertEquals(SubmissionStatus.STREAMING, retriedState.submissionStatus)
        assertNull(retriedState.pendingUnknownTurnId)
    }

    @Test
    fun unknownGatewayEvent_fallsBackToGenericActivityRow() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()

        fakeAdapter.eventFlow.emit(
            GatewayEvent.Unknown(
                type = "agent.custom_future_notice",
                sessionId = "runtime-100",
                seq = 99L,
                payload = mapOf("details" to "non-critical server alert", "password" to "superSecret")
            )
        )
        advanceUntilIdle()

        val entry = viewModel.uiState.value.timeline.last() as GenericGatewayActivity
        assertEquals("agent.custom_future_notice", entry.eventType)
        assertTrue(entry.summary.contains("agent.custom_future_notice"))
        assertFalse(entry.payloadPreview!!.contains("superSecret"))
        assertTrue(entry.payloadPreview!!.contains("[REDACTED]"))
        assertFalse(entry.isExpanded)

        // Toggle expand
        viewModel.toggleGenericActivityExpanded(entry.id)
        val expandedEntry = viewModel.uiState.value.timeline.last() as GenericGatewayActivity
        assertTrue(expandedEntry.isExpanded)
    }

    // ------------------------------------------------------------------
    // CHG-003-S3 simplified submit guard (AC-01..AC-04)
    // ------------------------------------------------------------------

    /** Test-local adapter wrapper recording every prompt.submit frame. */
    private class RecordingAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : GatewayProtocolAdapter by delegate {
        val submittedFrames = mutableListOf<Pair<String, String>>()
        override suspend fun submitPrompt(sessionId: String, text: String): GatewayResult<PromptSubmitResult> {
            submittedFrames.add(sessionId to text)
            return delegate.submitPrompt(sessionId, text)
        }
    }

    private class RecordingReasoningDefaultAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : GatewayProtocolAdapter by delegate {
        var reasoningDefaultCalls = 0

        override suspend fun getReasoningDefault(profile: String?): GatewayResult<ReasoningDefaultResult> {
            reasoningDefaultCalls++
            return GatewayResult.Success(ReasoningDefaultResult(effort = "high"))
        }
    }

    private class CountingProfileDefaultVault(
        private val delegate: VaultStorage
    ) : VaultStorage by delegate {
        var profileDefaultReadCalls = 0
        var profileDefaultWriteCalls = 0

        override suspend fun getProfileModelDefault(workspace: String): ProfileModelDefault? {
            profileDefaultReadCalls++
            return delegate.getProfileModelDefault(workspace)
        }

        override suspend fun saveProfileModelDefault(record: ProfileModelDefault) {
            profileDefaultWriteCalls++
            delegate.saveProfileModelDefault(record)
        }
    }

    private fun recordingAdapter(): RecordingAdapter = RecordingAdapter(fakeAdapter)

    @Test
    fun imageOnlySend_usesServerTextsAndClearsChipsAtAdmission() = runTest {
        val adapter = recordingAdapter()
        val vm = ChatViewModel(sessionId = "<durable>", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter, pendingTurnLedger = ledger)
        advanceUntilIdle()
        val images = listOf(
            PendingImageAttachment("<path-a>", "a.png", "<image-text-a>"),
            PendingImageAttachment("<path-b>", "b.png", "<image-text-b>")
        )
        vm.uiStateFlow().value = vm.uiState.value.copy(pendingImages = images)

        vm.submitPrompt()
        advanceUntilIdle()

        assertEquals(listOf("<image-text-a>\n<image-text-b>"), adapter.submittedFrames.map { it.second })
        assertTrue(vm.uiState.value.pendingImages.isEmpty())
        assertEquals(SubmissionStatus.STREAMING, vm.uiState.value.submissionStatus)
    }

    @Test
    fun fileRefsAppendToDraftAndClearAtAdmission() = runTest {
        val adapter = recordingAdapter()
        val vm = ChatViewModel(sessionId = "<durable>", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter, pendingTurnLedger = ledger)
        advanceUntilIdle()
        vm.updateDraft("<draft>")
        vm.uiStateFlow().value = vm.uiState.value.copy(pendingFiles = listOf(
            PendingFileAttachment("<ref-a>"), PendingFileAttachment("<ref-b>")
        ))

        vm.submitPrompt()
        advanceUntilIdle()

        assertEquals(listOf("<draft>\n<ref-a>\n<ref-b>"), adapter.submittedFrames.map { it.second })
        assertTrue(vm.uiState.value.pendingFiles.isEmpty())
    }

    @Test
    fun attachmentSend_runtimeAndSavedSnapshotGatesPreserveChips() = runTest {
        val adapter = recordingAdapter()
        val vm = ChatViewModel(sessionId = "<durable>", sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter, pendingTurnLedger = ledger)
        advanceUntilIdle()
        val images = listOf(PendingImageAttachment("<path>", "photo.png", "<image-text>"))
        val initial = vm.uiState.value.copy(pendingImages = images)
        vm.uiStateFlow().value = initial.copy(runtimeSessionId = null)
        vm.submitPrompt()
        advanceUntilIdle()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, vm.uiState.value.submitBlockedReason)
        assertEquals(images, vm.uiState.value.pendingImages)
        assertTrue(adapter.submittedFrames.isEmpty())

        vm.uiStateFlow().value = initial.copy(savedSnapshotCapturedAtMillis = 1L)
        vm.submitPrompt()
        advanceUntilIdle()
        assertEquals(images, vm.uiState.value.pendingImages)
        assertTrue(adapter.submittedFrames.isEmpty())
    }

    /** Access the backing state flow to seed presentation-only state in tests. */
    @Suppress("UNCHECKED_CAST")
    private fun ChatViewModel.uiStateFlow(): MutableStateFlow<ChatUiState> {
        val field = ChatViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        return field.get(this) as MutableStateFlow<ChatUiState>
    }

    /** VM whose loadSession() failed, leaving runtimeSessionId null (blocked state). */
    private fun blockedVm(adapter: RecordingAdapter): ChatViewModel {
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("resume failed"))
        return ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger
        )
    }

    private suspend fun assertBlockedNoSideEffects(
        viewModel: ChatViewModel,
        adapter: RecordingAdapter,
        draftText: String
    ) {
        val beforeFrames = adapter.submittedFrames.size
        val beforeLedger = ledger.getAllPendingTurns().size
        fakeRepo.createDraftSessionCalls = 0

        viewModel.updateDraft(draftText)
        viewModel.submitPrompt()

        val state = viewModel.uiState.value
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertEquals(draftText, state.composerDraft)
        assertFalse(state.isSubmitting)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(0, state.timeline.size)
        assertEquals(beforeFrames, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertEquals(0, fakeRepo.createDraftSessionCalls)
    }

    @Test
    fun submitPrompt_freshRuntime_submitsExactlyOneFrame_oneLedgerPath() = runTest {
        val adapter = recordingAdapter()
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger
        )
        assertEquals("runtime-100", viewModel.uiState.value.runtimeSessionId)
        assertNull(viewModel.uiState.value.submitBlockedReason)

        viewModel.updateDraft("Fresh runtime prompt")
        viewModel.submitPrompt()

        assertEquals(1, adapter.submittedFrames.size)
        assertEquals("runtime-100", adapter.submittedFrames.first().first)
        assertEquals("Fresh runtime prompt", adapter.submittedFrames.first().second)
        assertEquals(1, ledger.getAllPendingTurns().size)
        assertEquals(0, fakeRepo.createDraftSessionCalls)
    }

    @Test
    fun submitPrompt_blocked_nullRuntime_noSideEffects() = runTest {
        val adapter = recordingAdapter()
        val viewModel = blockedVm(adapter)
        assertNull(viewModel.uiState.value.runtimeSessionId)
        assertBlockedNoSideEffects(viewModel, adapter, "Null runtime prompt")
    }

    @Test
    fun submitPrompt_blocked_emptyAndWhitespaceRuntime_noSideEffects() = runTest {
        for (runtimeId in listOf("", "   ")) {
            fakeRepo.resumeOutcome = Result.success(
                SessionResumeResult(
                    runtimeSessionId = runtimeId,
                    durableSessionId = "sess-100",
                    messageCount = 0,
                    messages = emptyList()
                )
            )
            val adapter = recordingAdapter()
            val viewModel = ChatViewModel(
                sessionId = "sess-100",
                sessionRepository = fakeRepo,
                gatewayProtocolAdapter = adapter,
                pendingTurnLedger = ledger
            )
            // loadSession() wrote a blank/whitespace runtime: the guard must still block.
            assertEquals(runtimeId, viewModel.uiState.value.runtimeSessionId)
            assertBlockedNoSideEffects(viewModel, adapter, "Blocked prompt [$runtimeId]")
        }
    }

    @Test
    fun retryAmbiguousPrompt_blockedRuntime_noCoordinatorCall_noLedgerAttempt() = runTest {
        val adapter = recordingAdapter()
        val viewModel = blockedVm(adapter)
        // Seed the UNKNOWN turn the blocked retry targets.
        val seeded = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "old prompt",
            runtimeSessionId = null
        )
        ledger.markUnknown(seeded.pendingTurnId)
        assertEquals(1, ledger.getAllPendingTurns().size)
        assertEquals(PendingTurnState.UNKNOWN, ledger.getAllPendingTurns().single().state)

        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(pendingUnknownTurnId = seeded.pendingTurnId)

        fakeRepo.createDraftSessionCalls = 0
        viewModel.retryAmbiguousPrompt()

        val state = viewModel.uiState.value
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertFalse(state.isSubmitting)
        // Old UNKNOWN record unchanged; no new ledger attempt.
        val afterLedger = ledger.getAllPendingTurns().single()
        assertEquals(seeded.pendingTurnId, afterLedger.pendingTurnId)
        assertEquals(PendingTurnState.UNKNOWN, afterLedger.state)
        assertEquals(0, fakeRepo.createDraftSessionCalls)
        assertEquals(0, adapter.submittedFrames.size)
    }

    @Test
    fun retryAmbiguousPrompt_coordinatorAbsentBranch_blocked_noLedgerMutation() = runTest {
        // No gateway adapter passed: coordinator is null, so retryAmbiguousPrompt
        // would run the ledger fallback path — but the guard must block it first.
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("resume failed"))
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            pendingTurnLedger = ledger
        )
        assertNull(viewModel.uiState.value.runtimeSessionId)

        val seeded = ledger.prepareTurn(
            storedSessionId = "sess-100",
            promptContent = "old prompt",
            runtimeSessionId = null
        )
        ledger.markUnknown(seeded.pendingTurnId)
        val beforeLedger = ledger.getAllPendingTurns().size

        viewModel.uiStateFlow().value =
            viewModel.uiStateFlow().value.copy(pendingUnknownTurnId = seeded.pendingTurnId)
        viewModel.retryAmbiguousPrompt()

        val state = viewModel.uiState.value
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun blockedState_clearedOnSuccessfulLoadSession() = runTest {
        val adapter = recordingAdapter()
        val viewModel = blockedVm(adapter)
        viewModel.updateDraft("Surviving draft")
        viewModel.submitPrompt()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals("Surviving draft", viewModel.uiState.value.composerDraft)
        assertEquals(0, adapter.submittedFrames.size)

        // Resume now succeeds; loadSession() writes a fresh runtime and clears the block.
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = "runtime-fresh",
                durableSessionId = "sess-100",
                messageCount = 1,
                messages = listOf(SessionMessage(role = "user", content = "hello"))
            )
        )
        viewModel.loadSession()

        val state = viewModel.uiState.value
        assertEquals("runtime-fresh", state.runtimeSessionId)
        assertNull(state.submitBlockedReason)
        // Draft untouched by resume.
        assertEquals("Surviving draft", state.composerDraft)
    }

    @Test
    fun updateDraft_nonblankPersistsDraftForDurableSession() = runTest {
        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = vault)

        viewModel.updateDraft("persist this")
        advanceUntilIdle()

        assertEquals("persist this", vault.getDraft("sess-100")?.composerText)
    }

    @Test
    fun updateDraft_blankDeletesDraftForDurableSession() = runTest {
        vault.saveDraft(Draft("sess-100", "remove this"))
        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = vault)

        viewModel.updateDraft("")
        advanceUntilIdle()

        assertNull(vault.getDraft("sess-100"))
    }

    @Test
    fun draftWrites_areSerializedAndLatestGenerationWins() = runTest {
        val controlledVault = OrderedDraftVault(vault)
        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = controlledVault)

        viewModel.updateDraft("older")
        controlledVault.firstSaveStarted.await()
        viewModel.updateDraft("newer")
        controlledVault.releaseFirstSave.complete(Unit)
        advanceUntilIdle()

        assertEquals("newer", vault.getDraft("sess-100")?.composerText)
        assertEquals(listOf("older", "newer"), controlledVault.savedTexts)
    }

    @Test
    fun recreatedViewModel_restoresDraftForExplicitDurableSession() = runTest {
        vault.saveDraft(Draft("sess-100", "restored text"))

        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = vault)
        advanceUntilIdle()

        assertEquals("restored text", viewModel.uiState.value.composerDraft)
    }

    @Test
    fun restoreDraft_scopesToActiveProfile_ignoresDraftFromDifferentProfile() = runTest {
        vault.setActiveProfileId("prof-a")
        vault.saveDraft(Draft(storedSessionId = "sess-100", composerText = "other profile draft", profileLocalId = "prof-other"))

        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = vault)
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.composerDraft)
    }

    @Test
    fun blankDraftUpdate_doesNotDeleteDraftBelongingToDifferentProfile() = runTest {
        vault.setActiveProfileId("prof-a")
        vault.saveDraft(Draft(storedSessionId = "sess-100", composerText = "other profile draft", profileLocalId = "prof-other"))

        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = vault)
        advanceUntilIdle()

        viewModel.updateDraft("")
        advanceUntilIdle()

        assertEquals("other profile draft", vault.getDraft("sess-100")?.composerText)
    }

    @Test
    fun newerInMemoryEdit_winsOverInFlightRestore() = runTest {
        val controlledVault = BlockingReadDraftVault(vault)
        vault.saveDraft(Draft("sess-100", "stale stored text"))
        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = controlledVault)

        controlledVault.readStarted.await()
        viewModel.updateDraft("new in memory")
        controlledVault.releaseRead.complete(Unit)
        advanceUntilIdle()

        assertEquals("new in memory", viewModel.uiState.value.composerDraft)
    }

    @Test
    fun vaultReadAndWriteFailures_doNotCancelViewModelOrEraseText() = runTest {
        val failingVault = FailingDraftVault(vault)
        failingVault.failReads = true
        val viewModel = ChatViewModel(sessionId = "sess-100", vaultStorage = failingVault)
        advanceUntilIdle()

        viewModel.updateDraft("kept after read failure")
        failingVault.failReads = false
        failingVault.failWrites = true
        advanceUntilIdle()

        assertEquals("kept after read failure", viewModel.uiState.value.composerDraft)
        viewModel.updateDraft("still alive")
        assertEquals("still alive", viewModel.uiState.value.composerDraft)
    }

    @Test
    fun acceptedSend_clearsStoredDraftAtExistingInMemoryClearPoint() = runTest {
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )
        viewModel.updateDraft("send this")
        advanceUntilIdle()

        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.composerDraft)
        assertNull(vault.getDraft("sess-100"))
    }

    @Test
    fun rejectedBeforeRuntimeAcceptance_retainsInMemoryAndStoredDraft() = runTest {
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("resume unavailable"))
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )
        viewModel.updateDraft("retain this")
        advanceUntilIdle()

        viewModel.submitPrompt()
        advanceUntilIdle()

        assertEquals("retain this", viewModel.uiState.value.composerDraft)
        assertEquals("retain this", vault.getDraft("sess-100")?.composerText)
    }

    // =========================================================================
    // F007-S2: live reasoning timeline entries (reasoning.delta/thinking.delta)
    // =========================================================================

    /** Helper: an admitted live turn with a provisional assistant entry. */
    private suspend fun TestScope.startStreamingTurn(viewModel: ChatViewModel) {
        viewModel.updateDraft("streaming prompt")
        viewModel.submitPrompt()
        advanceUntilIdle()
    }

    private suspend fun TestScope.emitReasoning(
        viewModel: ChatViewModel,
        kind: ReasoningKind,
        text: String,
        seq: Long
    ) {
        advanceUntilIdle()
        fakeAdapter.eventFlow.emit(
            GatewayEvent.ReasoningDelta(
                sessionId = "runtime-100",
                seq = seq,
                kind = kind,
                text = text
            )
        )
        advanceUntilIdle()
    }

    @Test
    fun streamingTurn_reasoningDelta_appearsInTimelineOrder_andAccumulates() = runTest {
        // F007-S2: during a live admitted turn, reasoning.delta renders as a
        // reasoning entry positioned between the assistant row and the latest
        // activity, and its text accumulates across deltas.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        emitReasoning(viewModel, ReasoningKind.REASONING, "step one: ", seq = 1L)
        emitReasoning(viewModel, ReasoningKind.REASONING, "step two", seq = 2L)

        val timeline = viewModel.uiState.value.timeline
        val reasoningEntries = timeline.filterIsInstance<ReasoningEntry>()
        assertEquals(1, reasoningEntries.size)
        val reasoning = reasoningEntries.single()
        assertEquals("step one: step two", reasoning.text)
        assertEquals(ReasoningDisplayKind.REASONING, reasoning.kind)
        assertFalse(reasoning.isExpanded)

        // The assistant row still exists and is unaffected by reasoning text.
        val assistant = timeline.filterIsInstance<AssistantMessage>()
            .last { it.id.startsWith("asst-") }
        assertEquals(AssistantMessageState.STREAMING, assistant.state)
        assertTrue(timeline.last() is ReasoningEntry)
    }

    @Test
    fun streamingTurn_thinkingDelta_rendersThinkingLabel() = runTest {
        // F007-S2: thinking.delta carries kind=thinking so the collapsed block
        // can be labeled distinctly.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        emitReasoning(viewModel, ReasoningKind.THINKING, "waiting on model", seq = 1L)

        val reasoning = viewModel.uiState.value.timeline
            .filterIsInstance<ReasoningEntry>()
            .single()
        assertEquals(ReasoningDisplayKind.THINKING, reasoning.kind)
        assertEquals("waiting on model", reasoning.text)
    }

    @Test
    fun streamingTurn_reasoningThenMessageDelta_orderPreserved_betweenSegments() = runTest {
        // F007-S2: reasoning and assistant text deltas preserve event order in
        // the timeline: assistant row appended at turn start, reasoning entry
        // appended when the reasoning delta arrives.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        emitReasoning(viewModel, ReasoningKind.REASONING, "reasoning first", seq = 1L)
        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageDelta(sessionId = "runtime-100", seq = 2L, text = "answer text")
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val kinds = timeline.map { entry ->
            when (entry) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                is ReasoningEntry -> "reasoning"
                else -> "other"
            }
        }
        // 2 history rows + user + assistant + reasoning: order preserved.
        assertEquals(
            listOf("user", "assistant", "user", "assistant", "reasoning"),
            kinds
        )
    }

    @Test
    fun streamingTurn_oversizedReasoning_truncatesWithLabel_noHang() = runTest {
        // F007-S2: the accumulated reasoning text is bounded (32 KiB); an
        // oversized stream truncates with a visible label — no OOM/hang.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        val chunk = "r".repeat(4096)
        repeat(20) { index ->
            emitReasoning(viewModel, ReasoningKind.REASONING, chunk, seq = index.toLong() + 1)
        }

        val reasoning = viewModel.uiState.value.timeline
            .filterIsInstance<ReasoningEntry>()
            .single()
        // 20 * 4096 = 81920 chars > 32 KiB chars budget: truncated with label.
        assertTrue(reasoning.text.length <= ReasoningPolicy.MAX_ACCUMULATED_UTF8_BYTES + ReasoningPolicy.TRUNCATION_LABEL.length)
        assertTrue(reasoning.text.endsWith(ReasoningPolicy.TRUNCATION_LABEL))
        // Truncation is disclosed, never silent.
        assertFalse(reasoning.text.length == 20 * 4096)
    }

    @Test
    fun streamingTurn_reasoningToggleExpand_expandsAndCollapses() = runTest {
        // F007-S2: the expand affordance toggles the collapsed-by-default block.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)
        emitReasoning(viewModel, ReasoningKind.REASONING, "hidden reasoning", seq = 1L)

        val entryId = viewModel.uiState.value.timeline
            .filterIsInstance<ReasoningEntry>()
            .single()
            .id
        viewModel.toggleReasoningExpanded(entryId)
        assertTrue(
            (viewModel.uiState.value.timeline.filterIsInstance<ReasoningEntry>().single()).isExpanded
        )
        viewModel.toggleReasoningExpanded(entryId)
        assertFalse(
            (viewModel.uiState.value.timeline.filterIsInstance<ReasoningEntry>().single()).isExpanded
        )
    }

    @Test
    fun unattributedReasoningOutsideActiveTurn_isSilentlyDropped() = runTest {
        // F007-S2 + plan ui-drop-unattributed-notices-v2-plan.md (owner-locked,
        // PR #178 precedent): reasoning deltas without an attributable active
        // admitted local turn (D-01) are silently dropped — no fabricated
        // local-turn proof, no standalone reasoning block, and no
        // GenericGatewayActivity row ("Unattributed reasoning activity" is
        // gone). D-01 holds in its stronger form: renders nothing, timeline
        // unchanged.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        advanceUntilIdle()

        emitReasoning(viewModel, ReasoningKind.REASONING, "unsolicited", seq = 1L)
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertTrue(timeline.filterIsInstance<ReasoningEntry>().isEmpty())
        assertTrue(
            "unattributed reasoning delta must render nothing",
            timeline.filterIsInstance<GenericGatewayActivity>().isEmpty()
        )
        // The init hydration row count is unchanged — the delta added no row.
        assertEquals(2, timeline.size)
    }

    @Test
    fun reasoningAvailable_stillRendersToolProgressRow_mappingUnchanged() = runTest {
        // F007-S2 guard: reasoning.available keeps its ToolProgress mapping;
        // its timeline row remains the existing bounded tool-progress row.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.ToolProgress(
                sessionId = "runtime-100",
                seq = 5L,
                type = "reasoning.available",
                payload = mapOf("text" to "preview text")
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertTrue(timeline.filterIsInstance<ReasoningEntry>().isEmpty())
        val progressRow = timeline.filterIsInstance<GenericGatewayActivity>()
            .single { it.eventType == BoundedPayloadMapper.boundedNameType("tool.progress.reasoning.available") }
        assertTrue(progressRow.payloadPreview!!.contains("preview text"))
    }

    @Test
    fun streamingTurn_messageComplete_finalizesTurnAndReasoningStaysCollapsed() = runTest {
        // F007-S2: a terminal complete event seals the assistant entry while
        // the reasoning block keeps its accumulated text (history rendering of
        // reasoning is a separate slice; the live entry survives the seal).
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)
        emitReasoning(viewModel, ReasoningKind.REASONING, "reasoning before complete", seq = 1L)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = "final answer",
                status = "complete"
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertFalse(viewModel.uiState.value.isStreaming)
        val assistant = timeline.filterIsInstance<AssistantMessage>()
            .last { it.id.startsWith("asst-") }
        assertEquals(AssistantMessageState.COMPLETE, assistant.state)
        assertEquals("final answer", assistant.text)
        val reasoning = timeline.filterIsInstance<ReasoningEntry>().single()
        assertEquals("reasoning before complete", reasoning.text)
        assertFalse(reasoning.isExpanded)
    }

    // =========================================================================
    // F007-S4: seal-time final reasoning (message.complete.reasoning)
    // =========================================================================

    @Test
    fun messageComplete_finalReasoning_withoutLiveReasoning_sealedTurnShowsCollapsedReasoning() = runTest {
        // F007-S4: when a turn completes without any accumulated live
        // reasoning, the host-provided final reasoning from
        // message.complete.payload.reasoning attaches to the sealed turn as
        // the same collapsed reasoning surface used by live reasoning (S2).
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "final answer",
                status = "complete",
                reasoning = "host final reasoning"
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        assertFalse(viewModel.uiState.value.isStreaming)
        val reasoning = timeline.filterIsInstance<ReasoningEntry>().single()
        assertEquals("host final reasoning", reasoning.text)
        assertFalse(reasoning.isExpanded)
        // Same collapsed reasoning surface: kind is the reasoning display kind.
        assertEquals(ReasoningDisplayKind.REASONING, reasoning.kind)
    }

    @Test
    fun messageComplete_finalReasoning_withLiveReasoning_prefersLive_noDuplication() = runTest {
        // F007-S4: when live reasoning already accumulated under S2, the
        // host's final reasoning must NOT be appended again — one collapsed
        // reasoning entry with the live accumulated text.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)
        emitReasoning(viewModel, ReasoningKind.REASONING, "live stream text", seq = 1L)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 2L,
                text = "final answer",
                status = "complete",
                reasoning = "host final reasoning (would duplicate)"
            )
        )
        advanceUntilIdle()

        val timeline = viewModel.uiState.value.timeline
        val reasoningEntries = timeline.filterIsInstance<ReasoningEntry>()
        assertEquals(1, reasoningEntries.size)
        assertEquals("live stream text", reasoningEntries.single().text)
    }

    @Test
    fun messageComplete_withoutFinalReasoning_noReasoningEntry_created() = runTest {
        // F007-S4 guard: no fabricated reasoning — an absent final reasoning
        // on a turn without live reasoning leaves no reasoning entry behind.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "final answer",
                status = "complete"
            )
        )
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.timeline.filterIsInstance<ReasoningEntry>().isEmpty()
        )
    }

    @Test
    fun messageComplete_blankFinalReasoning_noReasoningEntry_created() = runTest {
        // F-007 polish (reviewer note on S4): a blank/whitespace final
        // reasoning payload must be treated as absent — same precedent as
        // blank terminal status mapping to UNKNOWN instead of fabricating a
        // state. No empty collapsed reasoning card is left behind.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.MessageComplete(
                sessionId = "runtime-100",
                seq = 1L,
                text = "final answer",
                status = "complete",
                reasoning = "   "
            )
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isStreaming)
        assertTrue(
            viewModel.uiState.value.timeline.filterIsInstance<ReasoningEntry>().isEmpty()
        )
    }

    // =========================================================================
    // Polish PR C (chat logic tidy-ups)
    // =========================================================================

    @Test
    fun streamingTurn_reasoningThenThinkingDeltas_labelLatchesOnFirstKind() = runTest {
        // Polish P-3: within one turn, the reasoning entry's label is latched on
        // the FIRST delta's kind. Hosts may emit both `reasoning.delta` and
        // `thinking.delta` in a single turn; text from the other kind still
        // appends, but the label must never flip mid-turn (it alternated before
        // because `kind = displayKind` overwrote the entry on every delta).
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        emitReasoning(viewModel, ReasoningKind.REASONING, "first kind wins: ", seq = 1L)
        emitReasoning(viewModel, ReasoningKind.THINKING, "thinking still appends", seq = 2L)
        emitReasoning(viewModel, ReasoningKind.REASONING, " and so does reasoning", seq = 3L)

        val timeline = viewModel.uiState.value.timeline
        val reasoning = timeline.filterIsInstance<ReasoningEntry>().single()
        // Latched label + all text from both streams.
        assertEquals(ReasoningDisplayKind.REASONING, reasoning.kind)
        assertEquals(
            "first kind wins: thinking still appends and so does reasoning",
            reasoning.text
        )
    }

    @Test
    fun streamingTurn_thinkingThenReasoningDeltas_labelLatchesOnThinking() = runTest {
        // Polish P-3 mirror: the same latch rule with the kinds swapped — the
        // first delta decides the label regardless of which kind leads.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        emitReasoning(viewModel, ReasoningKind.THINKING, "thinking first: ", seq = 1L)
        emitReasoning(viewModel, ReasoningKind.REASONING, "reasoning appends", seq = 2L)

        val reasoning = viewModel.uiState.value.timeline
            .filterIsInstance<ReasoningEntry>()
            .single()
        assertEquals(ReasoningDisplayKind.THINKING, reasoning.kind)
        assertEquals("thinking first: reasoning appends", reasoning.text)
    }

    @Test
    fun reasoningAccumulation_multibyteContent_truncatesAtUtf8ByteBudgetNotCharCount() = runTest {
        // Polish P-4: the 32 KiB budget is UTF-8 BYTES, not UTF-16 chars. Emoji
        // are 4 UTF-8 bytes each but 1 char (`length`) in Kotlin, so a
        // bytes->chars regression would let the oversized-reasoning guard pass
        // while storing 4x the budget. This test asserts the byte count
        // directly: 20_000 emoji (80_000 UTF-8 bytes) must truncate well
        // before the byte budget + label, and the kept window must stay within
        // MAX_ACCUMULATED_UTF8_BYTES measured in bytes.
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = fakeAdapter,
            pendingTurnLedger = ledger
        )
        startStreamingTurn(viewModel)

        val emoji = "\uD83E\uDD16" // 🤖 robot face: 1 char, 4 UTF-8 bytes
        emitReasoning(
            viewModel,
            ReasoningKind.REASONING,
            emoji.repeat(20_000),
            seq = 1L
        )

        val reasoning = viewModel.uiState.value.timeline
            .filterIsInstance<ReasoningEntry>()
            .single()
        assertTrue(reasoning.text.endsWith(ReasoningPolicy.TRUNCATION_LABEL))
        // Bytes, not chars: the kept text must fit the byte budget.
        assertTrue(
            "UTF-8 byte count must stay within the 32 KiB budget",
            BoundedPayloadMapper.utf8ByteCount(reasoning.text) <=
                ReasoningPolicy.MAX_ACCUMULATED_UTF8_BYTES
        )
        // A chars-based (buggy) bound would admit all 20_000 emoji = 80_000
        // bytes; the correct bytes-based bound keeps far fewer.
        assertTrue(
            BoundedPayloadMapper.utf8ByteCount(reasoning.text) <
                20_000 * 4
        )
    }

    private class OrderedDraftVault(private val delegate: VaultStorage) : VaultStorage by delegate {
        val firstSaveStarted = CompletableDeferred<Unit>()
        val releaseFirstSave = CompletableDeferred<Unit>()
        val savedTexts = mutableListOf<String>()

        override suspend fun saveDraft(draft: Draft) {
            savedTexts += draft.composerText
            if (draft.composerText == "older") {
                firstSaveStarted.complete(Unit)
                releaseFirstSave.await()
            }
            delegate.saveDraft(draft)
        }
    }

    private class BlockingReadDraftVault(private val delegate: VaultStorage) : VaultStorage by delegate {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        override suspend fun getDraft(storedSessionId: String): Draft? {
            readStarted.complete(Unit)
            releaseRead.await()
            return delegate.getDraft(storedSessionId)
        }
    }

    private class FailingDraftVault(private val delegate: VaultStorage) : VaultStorage by delegate {
        var failReads = false
        var failWrites = false

        override suspend fun getDraft(storedSessionId: String): Draft? {
            if (failReads) throw IllegalStateException("synthetic read failure")
            return delegate.getDraft(storedSessionId)
        }

        override suspend fun saveDraft(draft: Draft) {
            if (failWrites) throw IllegalStateException("synthetic write failure")
            delegate.saveDraft(draft)
        }
    }
}
