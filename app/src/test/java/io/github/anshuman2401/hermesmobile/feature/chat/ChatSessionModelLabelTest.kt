package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.cache.CachedConversationSnapshot
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheReadResult
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheRepository
import io.github.anshuman2401.hermesmobile.data.cache.ConversationCacheWriteResult
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayEvent
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.ReasoningDefaultResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.CachedRemoteHistoryRow
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CHAT-010 (frozen plan v1): the session model + reasoning caption state —
 * resume-success population, re-entry clearing, session.info event updates,
 * runtime-ID guard, offline-snapshot silence, and loadEarlier non-touching.
 *
 * CHAT-011 S2 (frozen plan TASK-006/007, TEST-05..TEST-12): extends the
 * caption with the per-field cached-default fallback and the seed-on-miss
 * path — the caption falls back per field to the cached workspace default
 * (live session values first), and ChatViewModel is the single writer that
 * seeds the vault record from the info echo + the key-restricted
 * config.get reasoning RPC.
 *
 * Fake-adapter conventions per ChatComposerSingleFlightTest: the VM attaches
 * a fresh runtime on loadSession() success, and GatewayEvents flow through
 * the fake adapter's eventFlow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionModelLabelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: LabelSessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    private val runtimeId = "runtime-100"

    /** Minimal in-memory [ConversationCacheRepository] double serving a fixed snapshot. */
    private class SnapshotOnlyCacheRepository(
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

    private class LabelSessionRepository : SessionRepository {
        override suspend fun capturePickerAccess(runtimeSessionId: String) = object : io.github.anshuman2401.hermesmobile.data.session.SessionPickerAccess {
            override suspend fun isCurrent() = true
            override suspend fun options() = listSessionModelOptions(runtimeSessionId)
            override suspend fun setModel(option: io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption) = setSessionModel(runtimeSessionId, option)
            override suspend fun setReasoning(effort: String) = setSessionReasoning(runtimeSessionId, effort)
        }
        val pickerCalls = mutableListOf<String>()
        override suspend fun listSessionModelOptions(runtimeSessionId: String) =
            GatewayResult.Success(listOf(io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption("openai", "gpt-5")))
        override suspend fun setSessionReasoning(runtimeSessionId: String, effort: String): GatewayResult<io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult> {
            pickerCalls += "$runtimeSessionId:$effort"
            return GatewayResult.Success(io.github.anshuman2401.hermesmobile.data.gateway.SessionPickerSetResult(false, false))
        }
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

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
                    messages = emptyList(),
                    info = mapOf<String, Any?>(
                        "model" to "snapshot-should-not-appear",
                        "reasoning_effort" to "snapshot-should-not-appear"
                    )
                )
            )

        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
    }

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_model_label_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = LabelSessionRepository()
    }

    private fun vm(): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = fakeAdapter,
        pendingTurnLedger = ledger,
        vaultStorage = vault
    )

    // T1: resume success with info.model + reasoning_effort -> derived label.
    @Test
    fun resumeSuccess_withModelAndEffort_setsDerivedLabel() = runTest {
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("gpt-5.6-luna", state.sessionModelName)
        assertEquals("high", state.sessionReasoningEffort)
        assertEquals("gpt-5.6-luna · high", state.sessionModelLabel)
    }

    // T2: resume success, no reasoning key -> model only.
    @Test
    fun resumeSuccess_withoutReasoningKey_labelIsModelOnly() = runTest {
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = mapOf<String, Any?>("model" to "gpt-5.6-luna")
            )
        )
        val viewModel = vm()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("gpt-5.6-luna", state.sessionModelName)
        assertNull(state.sessionReasoningEffort)
        assertEquals("gpt-5.6-luna", state.sessionModelLabel)
    }

    // T3: re-entry after a known label, then failed resume -> cleared, no
    // stale carry-over.
    @Test
    fun loadSessionReEntry_afterKnownLabel_thenFailedResume_labelCleared() = runTest {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals("gpt-5.6-luna · high", viewModel.uiState.value.sessionModelLabel)

        // Re-entry with a failing resume: the label must NOT survive.
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("host down"))
        viewModel.loadSession()
        advanceUntilIdle()

        assertNull("label must clear on loadSession entry", viewModel.uiState.value.sessionModelName)
        assertNull(viewModel.uiState.value.sessionReasoningEffort)
        assertNull("no stale label after failed resume", viewModel.uiState.value.sessionModelLabel)
    }

    // T4: session.info event for the attached runtime session updates the
    // label from the event payload (host-side model/reasoning switch).
    @Test
    fun sessionInfoEvent_forAttachedRuntime_updatesLabelFromPayload() = runTest {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals("gpt-5.6-luna · high", viewModel.uiState.value.sessionModelLabel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionInfo(
                sessionId = runtimeId,
                seq = 10L,
                info = mapOf<String, Any?>(
                    "model" to "claude-opus-4-6",
                    "reasoning_effort" to "medium"
                )
            )
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("claude-opus-4-6", state.sessionModelName)
        assertEquals("medium", state.sessionReasoningEffort)
        assertEquals("claude-opus-4-6 · medium", state.sessionModelLabel)
    }

    // T5: session.info for a different/blank session id -> no state change
    // (the existing exact runtime-ID guard).
    @Test
    fun sessionInfoEvent_forOtherOrBlankSessionId_noStateChange() = runTest {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals("gpt-5.6-luna · high", viewModel.uiState.value.sessionModelLabel)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionInfo(
                sessionId = "runtime-OTHER",
                seq = 11L,
                info = mapOf<String, Any?>("model" to "intruder-model")
            )
        )
        advanceUntilIdle()
        assertEquals(
            "foreign runtime id must not mutate the label",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionInfo(
                sessionId = null,
                seq = 12L,
                info = mapOf<String, Any?>("model" to "intruder-model")
            )
        )
        advanceUntilIdle()
        assertEquals(
            "blank/null session id must not mutate the label",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // T6: offline snapshot path -> label stays null (the cache stores no
    // model identity; showing nothing is the honest state).
    @Test
    fun offlineSnapshotPath_labelStaysNull() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val cacheRepo = SnapshotOnlyCacheRepository(
            CachedConversationSnapshot(
                durableSessionId = "sess-100",
                capturedAtMillis = System.currentTimeMillis(),
                messagesOmitted = false,
                rows = listOf(
                    CachedRemoteHistoryRow(
                        ordinal = 0,
                        role = "user",
                        content = "Cached question"
                    )
                )
            )
        )
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
        assertEquals("timeline serves from cache", 1, state.timeline.size)
        assertNotNull(state.savedSnapshotCapturedAtMillis)
        assertNull("offline snapshot serves no model identity", state.sessionModelName)
        assertNull(state.sessionReasoningEffort)
        assertNull(state.sessionModelLabel)
    }

    // loadEarlier()/fetchHistorySnapshot must NOT touch the label (history-
    // only fetch must never downgrade a known label mid-scroll).
    @Test
    fun loadEarlier_doesNotTouchLabel() = runTest {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals("gpt-5.6-luna · high", viewModel.uiState.value.sessionModelLabel)

        viewModel.loadEarlier()
        advanceUntilIdle()

        assertEquals(
            "history fetch must not downgrade the label",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // ---------------------------------------------------------------------
    // CHAT-011 S2 (frozen plan TASK-006/007, TEST-05..TEST-12): per-field
    // cached-default fallback + seed-on-miss.
    // ---------------------------------------------------------------------

    /**
     * Pure-state pin of the per-field fallback derivation (TASK-006): live
     * session values win each field independently; the cached workspace
     * default fills each blank gap per field; nothing resolves -> absent.
     */
    @Test
    fun chatUiState_derivedLabel_perFieldFallbackToCachedDefaults() {
        val cachedOnly = ChatUiState(
            cachedDefaultModelName = "cached-pro",
            cachedDefaultReasoningEffort = "xhigh"
        )
        assertEquals(
            "no live fields: caption is the cached workspace default",
            "cached-pro · xhigh",
            cachedOnly.sessionModelLabel
        )

        val liveModelBlankEffort = cachedOnly.copy(sessionModelName = "gpt-5.6-luna")
        assertEquals(
            "live model wins the model field; the cached effort fills the blank effort gap (the owner's report)",
            "gpt-5.6-luna · xhigh",
            liveModelBlankEffort.sessionModelLabel
        )

        val liveBoth = cachedOnly.copy(
            sessionModelName = "gpt-5.6-luna",
            sessionReasoningEffort = "low"
        )
        assertEquals(
            "live values win both fields when the host reported them",
            "gpt-5.6-luna · low",
            liveBoth.sessionModelLabel
        )

        assertEquals(
            "cached model with no effort resolution anywhere: model alone",
            "cached-pro",
            ChatUiState(cachedDefaultModelName = "cached-pro").sessionModelLabel
        )

        assertNull(
            "no live fields and no cached default: caption absent, never a placeholder",
            ChatUiState().sessionModelLabel
        )
    }

    /**
     * CHAT-011 S2 test double: interface delegation over the standard fake
     * (FakeGatewayProtocolAdapter is final, so delegation — not inheritance)
     * recording [GatewayProtocolAdapter.getReasoningDefault] calls with a
     * scriptable result. Every other behavior — eventFlow,
     * connectionStatus, resume plumbing — is the base fake's, shared by
     * reference through the delegate.
     */
    private class RecordingReasoningAdapter(
        base: GatewayProtocolAdapter
    ) : GatewayProtocolAdapter by base {
        var reasoningResult: GatewayResult<ReasoningDefaultResult> =
            GatewayResult.Success(ReasoningDefaultResult(effort = "xhigh"))
        val reasoningCalls = mutableListOf<String?>()
        var reasoningGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun getReasoningDefault(
            profile: String?
        ): GatewayResult<ReasoningDefaultResult> {
            reasoningCalls.add(profile)
            reasoningGate?.await()
            return reasoningResult
        }
    }

    /**
     * CHAT-011 S2 test double capturing [VaultStorage.saveProfileModelDefault]
     * calls so tests can assert the seeded record and script write failures.
     * `VaultStorage by delegate` keeps every other vault behavior (context
     * profile reads included) on the real encrypted vault.
     */
    private class RecordingVault(private val delegate: EncryptedVault) :
        VaultStorage by delegate {
        val savedRecords = mutableListOf<ProfileModelDefault>()
        var failOnSave = false

        override suspend fun saveProfileModelDefault(record: ProfileModelDefault) {
            if (failOnSave) throw java.io.IOException("vault write failed")
            savedRecords.add(record)
            delegate.saveProfileModelDefault(record)
        }
    }

    private suspend fun saveDefaultRecord(model: String, effort: String) {
        vault.saveProfileModelDefault(
            ProfileModelDefault(
                workspaceName = "default",
                modelName = model,
                reasoningEffort = effort,
                updatedAtMillis = 1_760_000_000_000L
            )
        )
    }

    private fun vm(
        recordingVault: VaultStorage,
        adapter: GatewayProtocolAdapter
    ): ChatViewModel = ChatViewModel(
        sessionId = "sess-100",
        sessionRepository = fakeRepo,
        gatewayProtocolAdapter = adapter,
        pendingTurnLedger = ledger,
        vaultStorage = recordingVault
    )

    // TEST-05: seed-on-empty — empty cache + resume success with info echo
    @Test
    fun chat014_seedProbeBlockedByPickerEvenWhenOldProbeCompletes() = runTest {
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        adapter.reasoningGate = kotlinx.coroutines.CompletableDeferred()
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        runCurrent()
        assertEquals(1, adapter.reasoningCalls.size)
        viewModel.openSessionPicker()
        runCurrent()
        viewModel.pickSessionReasoning("low")
        runCurrent()
        assertEquals(listOf("runtime-100:low"), fakeRepo.pickerCalls)
        adapter.reasoningGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, recordingVault.savedRecords.size)
    }

    @Test
    fun chat014_switchedBlankHostEffortNeverMasqueradesAsCachedOldEffort() {
        val state = ChatUiState(sessionId = "sess-100", sessionModelName = "new-model",
            sessionReasoningEffort = "", cachedDefaultReasoningEffort = "high", pickerSwitchStarted = true)
        assertEquals(io.github.anshuman2401.hermesmobile.feature.chat.SessionModelLabelPolicy.displayLabel("new-model", null), state.sessionModelLabel)
    }

    // + connected -> one getReasoningDefault call; record saved with the
    // ECHO model + the RPC effort; no second seed on the next load.
    @Test
    fun seedOnEmpty_resumeSuccess_seedsOnceWithEchoModelAndRpcEffort() = runTest {
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals(
            "cache miss + echo + connected must issue exactly one reasoning RPC",
            1,
            adapter.reasoningCalls.size
        )
        assertNull(
            "the probe is scoped to the selected workspace profile (none selected here)",
            adapter.reasoningCalls.first()
        )
        assertEquals("atomic seed: exactly one record saved", 1, recordingVault.savedRecords.size)
        val record = recordingVault.savedRecords.first()
        assertEquals(
            "seed model must come from the session info echo",
            "gpt-5.6-luna",
            record.modelName
        )
        assertEquals(
            "seed effort must come from the config.get RPC, not the session info",
            "xhigh",
            record.reasoningEffort
        )
        assertEquals(
            "seed record keys the selected workspace",
            "default",
            ProfileModelDefault.workspaceKey(record.workspaceName)
        )

        // Next load is a cache hit: no second RPC, no second write.
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals("cache hit must not re-RPC", 1, adapter.reasoningCalls.size)
        assertEquals("cache hit must not re-save", 1, recordingVault.savedRecords.size)
    }

    // TEST-06: cache-hit — record exists at VM init -> zero config.get RPCs,
    // zero vault writes; the cached default is in presentation state.
    @Test
    fun cacheHit_recordExists_zeroRpcZeroWrites() = runTest {
        saveDefaultRecord(model = "cached-pro", effort = "xhigh")
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals("cache hit must issue zero config.get RPCs", 0, adapter.reasoningCalls.size)
        assertEquals("cache hit must issue zero vault writes", 0, recordingVault.savedRecords.size)
        assertEquals(
            "init read surfaces the cached default model",
            "cached-pro",
            viewModel.uiState.value.cachedDefaultModelName
        )
        assertEquals(
            "init read surfaces the cached default effort",
            "xhigh",
            viewModel.uiState.value.cachedDefaultReasoningEffort
        )
        assertEquals(
            "live resume values still win the caption",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // TEST-07: display fallback — no live session fields, cache present ->
    // caption = cached model + cached effort; no echo also means no seed.
    @Test
    fun displayFallback_noSessionFields_cachePresent_showsCachedDefault() = runTest {
        saveDefaultRecord(model = "cached-pro", effort = "xhigh")
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = emptyMap<String, Any?>()
            )
        )
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val viewModel = vm(RecordingVault(vault), adapter)
        advanceUntilIdle()

        assertNull("resume reported no model identity", viewModel.uiState.value.sessionModelName)
        assertEquals(
            "caption falls back to the cached workspace default",
            "cached-pro · xhigh",
            viewModel.uiState.value.sessionModelLabel
        )
        assertEquals("no model echo means no seed RPC", 0, adapter.reasoningCalls.size)
    }

    // TEST-08: effort fallback — session model present, session effort blank
    // (the owner's reported bug), cached effort present -> live model with
    // the cached effort.
    @Test
    fun effortFallback_sessionModelBlankEffort_usesCachedEffort() = runTest {
        saveDefaultRecord(model = "cached-pro", effort = "xhigh")
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = mapOf<String, Any?>("model" to "gpt-5.6-luna")
            )
        )
        val viewModel = vm(RecordingVault(vault), RecordingReasoningAdapter(fakeAdapter))
        advanceUntilIdle()

        assertEquals("gpt-5.6-luna", viewModel.uiState.value.sessionModelName)
        assertNull("blank host effort reads as null", viewModel.uiState.value.sessionReasoningEffort)
        assertEquals(
            "per-field fallback: the cached effort fills the blank live gap (owner's bug fixed)",
            "gpt-5.6-luna · xhigh",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // TEST-09: `none` effort cached -> displayed verbatim, never hidden,
    // never remapped.
    @Test
    fun noneEffortCached_displayedVerbatim() = runTest {
        saveDefaultRecord(model = "cached-pro", effort = "none")
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = mapOf<String, Any?>("model" to "gpt-5.6-luna")
            )
        )
        val viewModel = vm(RecordingVault(vault), RecordingReasoningAdapter(fakeAdapter))
        advanceUntilIdle()

        assertEquals(
            "cached `none` effort displays verbatim (reasoning disabled is a real host value)",
            "gpt-5.6-luna · none",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // TEST-05 companion: the session.info event path also feeds the single
    // seed — and the just-seeded default immediately fills the caption's
    // blank effort gap.
    @Test
    fun seedOnEmpty_sessionInfoEvent_alsoTriggersSeedAndFeedsFallback() = runTest {
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = emptyMap<String, Any?>()
            )
        )
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals("resume without a model echo must not seed", 0, adapter.reasoningCalls.size)

        fakeAdapter.eventFlow.emit(
            GatewayEvent.SessionInfo(
                sessionId = runtimeId,
                seq = 10L,
                info = mapOf<String, Any?>("model" to "echo-model")
            )
        )
        advanceUntilIdle()

        assertEquals("session.info with an echo triggers the seed RPC", 1, adapter.reasoningCalls.size)
        assertEquals("seed saved once from the event echo", 1, recordingVault.savedRecords.size)
        assertEquals("echo-model", recordingVault.savedRecords.first().modelName)
        assertEquals(
            "the just-seeded default feeds the blank effort gap",
            "echo-model · xhigh",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // Seed atomicity (open decision 2): the RPC answered but with no usable
    // effort -> a partial fetch never half-seeds.
    @Test
    fun seedOnEmpty_rpcSuccessBlankEffort_savesNothing() = runTest {
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        adapter.reasoningResult = GatewayResult.Success(ReasoningDefaultResult(effort = null))
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals("the seed RPC was issued", 1, adapter.reasoningCalls.size)
        assertEquals(
            "a partial fetch (blank RPC effort) must never half-seed",
            0,
            recordingVault.savedRecords.size
        )
        assertEquals(
            "caption shows the live session truth only",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // TEST-10: offline snapshot path with an empty cache -> caption absent,
    // zero RPCs, no crash.
    @Test
    fun offlineSnapshot_emptyCache_captionAbsentNoRpcNoCrash() = runTest {
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val cacheRepo = SnapshotOnlyCacheRepository(
            CachedConversationSnapshot(
                durableSessionId = "sess-100",
                capturedAtMillis = System.currentTimeMillis(),
                messagesOmitted = false,
                rows = listOf(
                    CachedRemoteHistoryRow(
                        ordinal = 0,
                        role = "user",
                        content = "Cached question"
                    )
                )
            )
        )
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))

        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = RecordingVault(vault),
            conversationCacheRepository = cacheRepo
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("timeline serves from cache", 1, state.timeline.size)
        assertNotNull(state.savedSnapshotCapturedAtMillis)
        assertNull("offline with an empty cache: caption absent", state.sessionModelLabel)
        assertEquals("offline path must issue zero reasoning RPCs", 0, adapter.reasoningCalls.size)
    }

    // TEST-10 companion: offline snapshot path with a cached default PRESENT
    // -> the caption shows the cached workspace default (vault read needs no
    // RPC, works offline); zero RPCs; no crash.
    @Test
    fun offlineSnapshot_cachePresent_showsCachedDefaultWithoutRpc() = runTest {
        saveDefaultRecord(model = "cached-pro", effort = "xhigh")
        fakeAdapter.connectionStatus.value = ConnectionStatus.OFFLINE
        val cacheRepo = SnapshotOnlyCacheRepository(
            CachedConversationSnapshot(
                durableSessionId = "sess-100",
                capturedAtMillis = System.currentTimeMillis(),
                messagesOmitted = false,
                rows = listOf(
                    CachedRemoteHistoryRow(
                        ordinal = 0,
                        role = "user",
                        content = "Cached question"
                    )
                )
            )
        )
        fakeRepo.resumeOutcome = Result.failure(IllegalStateException("must not resume offline"))

        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val viewModel = ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = RecordingVault(vault),
            conversationCacheRepository = cacheRepo
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("timeline serves from cache", 1, state.timeline.size)
        assertNotNull(state.savedSnapshotCapturedAtMillis)
        assertEquals(
            "offline with a cached default: the caption shows the cached workspace default (no RPC)",
            "cached-pro · xhigh",
            state.sessionModelLabel
        )
        assertEquals("offline path must issue zero reasoning RPCs", 0, adapter.reasoningCalls.size)
    }

    // TEST-11: config.get failure during seed -> no vault write; caption
    // shows what is known (the echo model only); no crash.
    @Test
    fun rpcFailureDuringSeed_noWrite_captionShowsWhatIsKnown() = runTest {
        fakeRepo.resumeOutcome = Result.success(
            SessionResumeResult(
                runtimeSessionId = runtimeId,
                durableSessionId = "sess-100",
                messageCount = 0,
                messages = emptyList(),
                info = mapOf<String, Any?>("model" to "gpt-5.6-luna")
            )
        )
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        adapter.reasoningResult = GatewayResult.Failure(
            GatewayFailure.Rpc(code = -32601, message = "method not found")
        )
        val recordingVault = RecordingVault(vault)
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals("the seed attempted the RPC", 1, adapter.reasoningCalls.size)
        assertEquals("RPC failure must save nothing", 0, recordingVault.savedRecords.size)
        assertNull("no cached default was learned", viewModel.uiState.value.cachedDefaultModelName)
        assertEquals(
            "caption shows what is known: the echo model only",
            "gpt-5.6-luna",
            viewModel.uiState.value.sessionModelLabel
        )
    }

    // TEST-12: vault write failure during seed -> caption unaffected this
    // session; no crash; no retry storm (one bounded retry on the next load).
    @Test
    fun vaultWriteFailureDuringSeed_captionUnaffectedNoRetryStorm() = runTest {
        val adapter = RecordingReasoningAdapter(fakeAdapter)
        val recordingVault = RecordingVault(vault)
        recordingVault.failOnSave = true
        val viewModel = vm(recordingVault, adapter)
        advanceUntilIdle()

        assertEquals("one seed attempt per load", 1, adapter.reasoningCalls.size)
        assertEquals(
            "the failed write is swallowed; nothing captured or persisted",
            0,
            recordingVault.savedRecords.size
        )
        assertEquals(
            "vault write failure must not disturb the caption",
            "gpt-5.6-luna · high",
            viewModel.uiState.value.sessionModelLabel
        )

        // The cache is still empty, so the next load retries the seed exactly
        // once more (plan §Fail paths) — one bounded retry, never a loop.
        viewModel.loadSession()
        advanceUntilIdle()
        assertEquals("one bounded retry on the next load, never a storm", 2, adapter.reasoningCalls.size)
        assertEquals(
            "still nothing persisted while writes keep failing",
            0,
            recordingVault.savedRecords.size
        )
    }
}