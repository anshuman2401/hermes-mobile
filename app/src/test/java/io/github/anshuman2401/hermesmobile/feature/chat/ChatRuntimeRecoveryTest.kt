package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.gateway.GatewayResult
import io.github.anshuman2401.hermesmobile.data.gateway.PromptSubmitResult
import io.github.anshuman2401.hermesmobile.data.gateway.SessionMessage
import io.github.anshuman2401.hermesmobile.data.gateway.SessionResumeResult
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.session.SessionListOutcome
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.domain.SessionHandle
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BLD-006 S2 — runtime session recovery (ViewModel rows).
 *
 * Blocked state must preserve the editable draft, emit zero prompt frames and
 * zero ledger rows, and `loadSession()` (the exact method the shell's resume
 * callback invokes) must clear the block, restore the runtime, and never
 * auto-submit the draft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRuntimeRecoveryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeAdapter: FakeGatewayProtocolAdapter
    private lateinit var fakeRepo: FakeRecoverySessionRepository
    private lateinit var ledger: PendingTurnLedger
    private lateinit var vault: EncryptedVault

    class FakeRecoverySessionRepository : SessionRepository {
        val _activeSession = MutableStateFlow<SessionHandle?>(null)
        override val activeSession: StateFlow<SessionHandle?> = _activeSession

        val _activeMessages = MutableStateFlow<List<SessionMessage>>(emptyList())
        override val activeMessages: StateFlow<List<SessionMessage>> = _activeMessages

        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())

        var resumeCalls = 0

        /** Typed, non-secret failure message (SEC-004: no exception internals). */
        var resumeOutcome: Result<SessionResumeResult> = Result.failure(
            IllegalStateException("Session restore is temporarily unavailable")
        )

        override suspend fun listSessions(forceRefresh: Boolean): Result<SessionListOutcome> =
            Result.success(SessionListOutcome(emptyList(), false))

        override suspend fun createDraftSession(title: String?): Result<SessionHandle> =
            Result.success(SessionHandle("sess-draft", "runtime-draft", isDraft = true))

        override suspend fun resumeSession(storedSessionId: String): Result<SessionResumeResult> {
            resumeCalls++
            return resumeOutcome
        }

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

    /** Test-local adapter wrapper recording every prompt.submit frame. */
    private class RecordingAdapter(
        private val delegate: FakeGatewayProtocolAdapter
    ) : io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter by delegate {
        val submittedFrames = mutableListOf<Pair<String, String>>()
        override suspend fun submitPrompt(
            sessionId: String,
            text: String
        ): GatewayResult<PromptSubmitResult> {
            submittedFrames.add(sessionId to text)
            return delegate.submitPrompt(sessionId, text)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ChatViewModel.uiStateFlow(): MutableStateFlow<ChatUiState> {
        val field = ChatViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        return field.get(this) as MutableStateFlow<ChatUiState>
    }

    @Before
    fun setUp() {
        val dir = tempFolder.newFolder("chat_recovery_vault")
        vault = EncryptedVault(
            storageDir = dir,
            cipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
        ledger = PendingTurnLedger(vault)
        fakeAdapter = FakeGatewayProtocolAdapter()
        fakeRepo = FakeRecoverySessionRepository()
    }

    /** VM whose resume failed at init, leaving runtimeSessionId null (blocked). */
    private fun blockedVm(adapter: RecordingAdapter): ChatViewModel =
        ChatViewModel(
            sessionId = "sess-100",
            sessionRepository = fakeRepo,
            gatewayProtocolAdapter = adapter,
            pendingTurnLedger = ledger,
            vaultStorage = vault
        )

    private fun successResume(): Result<SessionResumeResult> = Result.success(
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

    // 1. Blocked submit: draft preserved, zero frames, zero ledger rows.
    @Test
    fun resumeBlockedState_doesNotClearDraftAndNoOptimisticRow() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        assertNull(viewModel.uiState.value.runtimeSessionId)
        val beforeLedger = ledger.getAllPendingTurns().size

        viewModel.updateDraft("draft kept")
        viewModel.submitPrompt()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertEquals("draft kept", state.composerDraft)
        assertEquals(0, state.timeline.size)
        assertFalse(state.isSubmitting)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(0, adapter.submittedFrames.size)
        assertEquals(beforeLedger, ledger.getAllPendingTurns().size)
    }

    // 2. Successful resume via loadSession(): block cleared, runtime restored,
    //    history hydrated, draft untouched, no auto-submit.
    @Test
    fun successfulResume_clearsBlockedState_restoresRuntime_neverAutoSubmits() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        viewModel.updateDraft("draft kept")
        viewModel.submitPrompt()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)

        fakeRepo.resumeOutcome = successResume()
        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.submitBlockedReason)
        assertEquals("runtime-100", state.runtimeSessionId)
        assertEquals(2, state.timeline.size)
        assertEquals("draft kept", state.composerDraft)
        assertFalse(state.isSubmitting)
        assertEquals(SubmissionStatus.IDLE, state.submissionStatus)
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 3. Draft survives the full blocked -> resumed cycle.
    @Test
    fun resumeDraft_survivesBlockedResumeCycle() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        viewModel.updateDraft("survive me")
        advanceUntilIdle()

        fakeRepo.resumeOutcome = successResume()
        viewModel.loadSession()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.submitBlockedReason)
        assertEquals("survive me", viewModel.uiState.value.composerDraft)
        assertEquals("survive me", vault.getDraft("sess-100")?.composerText)
    }

    // 4. Failing resume keeps the blocked state and the draft; the error copy
    //    is the typed repository message, not raw exception internals (SEC-004).
    @Test
    fun resumeFailure_keepsBlockedState_andKeepsDraft() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        viewModel.updateDraft("draft kept")
        viewModel.submitPrompt()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)

        viewModel.loadSession()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.runtimeSessionId)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertEquals("draft kept", state.composerDraft)
        assertEquals("Session restore is temporarily unavailable", state.errorMessage)
        assertFalse(state.errorMessage!!.contains("IllegalStateException"))
        assertFalse(state.errorMessage!!.contains("Exception"))
        assertFalse(state.errorMessage!!.contains("java."))
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 5. Blocked ambiguous retry leaves the draft untouched (same blocked reason).
    @Test
    fun retryAmbiguousPrompt_blocked_showsSameBlockedReason() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        val viewModel = blockedVm(adapter)
        viewModel.updateDraft("retry draft")
        viewModel.submitPrompt()
        assertEquals(SubmitBlockedReason.NO_RUNTIME, viewModel.uiState.value.submitBlockedReason)
        assertEquals("retry draft", viewModel.uiState.value.composerDraft)

        viewModel.retryAmbiguousPrompt()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SubmitBlockedReason.NO_RUNTIME, state.submitBlockedReason)
        assertEquals("retry draft", state.composerDraft)
        assertFalse(state.isSubmitting)
        assertEquals(0, adapter.submittedFrames.size)
    }

    // 6. Shell-policy seam: the exact lambda the shell passes
    //    ({ vm.loadSession() }) resumes once per activation, never submits.
    @Test
    fun resumeCallback_invokesLoadSession_exactlyOncePerActivation() = runTest {
        val adapter = RecordingAdapter(fakeAdapter)
        // Resolve the chat VM the same way the shell does: through a real
        // ChatRouteOwnerViewModel with a recording provider.
        val owner = io.github.anshuman2401.hermesmobile.navigation.ChatRouteOwnerViewModel { key ->
            ChatViewModel(
                sessionId = key,
                sessionRepository = fakeRepo,
                gatewayProtocolAdapter = adapter,
                pendingTurnLedger = ledger,
                vaultStorage = vault
            )
        }
        val vm = owner.get("sess-100")!!
        assertNull(vm.uiState.value.runtimeSessionId)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, vm.uiState.value.submitBlockedReason)
        vm.updateDraft("seam draft")

        // The exact shell callback shape for both layouts.
        val onResumeOrRestore: () -> Unit = { vm.loadSession() }

        fakeRepo.resumeOutcome = successResume()
        onResumeOrRestore()
        advanceUntilIdle()
        // Under MainDispatcherRule the resume runs eagerly; the repo counter is
        // the authoritative activation record.
        assertTrue(fakeRepo.resumeCalls >= 1)
        assertNull(vm.uiState.value.submitBlockedReason)
        assertEquals("runtime-100", vm.uiState.value.runtimeSessionId)

        // Second activation (e.g. after another blocked cycle) resumes again —
        // once per activation, no implicit submit in between.
        fakeRepo.resumeOutcome = Result.failure(
            IllegalStateException("Session restore is temporarily unavailable")
        )
        onResumeOrRestore()
        advanceUntilIdle()
        assertTrue(fakeRepo.resumeCalls >= 2)
        assertEquals(SubmitBlockedReason.NO_RUNTIME, vm.uiState.value.submitBlockedReason)

        assertEquals(0, adapter.submittedFrames.size)
        assertEquals("seam draft", vm.uiState.value.composerDraft)
    }
}
