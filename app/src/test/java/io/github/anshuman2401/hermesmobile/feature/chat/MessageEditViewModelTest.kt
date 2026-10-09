package io.github.anshuman2401.hermesmobile.feature.chat

import androidx.lifecycle.ViewModelStore
import io.github.anshuman2401.hermesmobile.data.gateway.*
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.data.session.*
import io.github.anshuman2401.hermesmobile.data.turn.*
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MessageEditViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()
    private class Access : MessageEditAccess {
        override val ownerKey = "b".repeat(64)
        var writes = 0
        var read: SessionResumeResult = history()
        var onWrite: () -> Unit = {}
        override suspend fun isCurrent() = true
        override suspend fun snapshot() = GatewayResult.Success(read)
        override suspend fun interrupt() = GatewayResult.Success(InterruptSessionResult(true))
        override suspend fun submit(text: String, rowId: Long): GatewayResult<PromptSubmitResult> {
            writes++; onWrite(); return GatewayResult.Success(PromptSubmitResult("streaming", 41))
        }
    }
    companion object {
        private fun history() = SessionResumeResult("runtime-100", "sess-100", 4,
            listOf(SessionMessage("user", "Original", editable = EditableMessage(10), rowId = 10),
                SessionMessage("assistant", "Old answer"), SessionMessage("user", "Later", editable = EditableMessage(20), rowId = 20),
                SessionMessage("assistant", "Later answer")), activityEvidence = ResumeActivityEvidence.NON_RUNNING)
    }
    @Test fun cancelPreservesNormalDraftAndSuccessReplacesSuffix() = runTest(main.testDispatcher) {
        val fake = ChatViewModelTest.FakeChatSessionRepository().apply { resumeOutcome = Result.success(history()) }
        val access = Access()
        val repo = object : SessionRepository by fake {
            override suspend fun captureMessageEditAccess(storedId: String, runtimeId: String) = access
        }
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        val vm = ChatViewModel("sess-100", repo, FakeGatewayProtocolAdapter(), PendingTurnLedger(vault), vaultStorage = vault)
        val store = ViewModelStore().also { it.put("chat", vm) }
        try {
            vm.loadSession(); advanceUntilIdle()
            vm.updateDraft("Unsent normal draft"); advanceUntilIdle()
            vm.beginMessageEdit(vm.uiState.value.timeline.filterIsInstance<UserMessage>().first().id); advanceUntilIdle()
            vm.updateDraft("Edited text")
            assertEquals("Unsent normal draft", vm.uiState.value.composerDraft)
            assertEquals("Edited text", vm.uiState.value.edit?.text)
            vm.cancelMessageEdit()
            assertNull(vm.uiState.value.edit)
            assertEquals("Unsent normal draft", vm.uiState.value.composerDraft)
            vm.beginMessageEdit(vm.uiState.value.timeline.filterIsInstance<UserMessage>().first().id); advanceUntilIdle()
            vm.updateDraft("Edited text")
            access.onWrite = {
                fake.resumeOutcome = Result.success(history().copy(messageCount = 2, messages = listOf(
                    SessionMessage("user", "Edited text", editable = EditableMessage(41), rowId = 41),
                    SessionMessage("assistant", "New answer"))))
            }
            vm.resendEditedMessage(); vm.resendEditedMessage(); advanceUntilIdle()
            assertEquals(1, access.writes)
            assertNull(vm.uiState.value.edit)
            assertEquals("Unsent normal draft", vm.uiState.value.composerDraft)
            assertEquals(listOf("Edited text"), vm.uiState.value.timeline.filterIsInstance<UserMessage>().map { it.text })
            assertEquals("Unsent normal draft", vault.getDraft("sess-100")?.composerText)
        } finally { store.clear() }
    }
    @Test fun restartRestoresUnknownEditWithoutTextMatchingOrAutomaticRetry() = runTest(main.testDispatcher) {
        val fake = ChatViewModelTest.FakeChatSessionRepository().apply { resumeOutcome = Result.success(history()) }
        val access = Access()
        val repo = object : SessionRepository by fake {
            override suspend fun captureMessageEditAccess(storedId: String, runtimeId: String) = access
        }
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        val ledger = PendingTurnLedger(vault)
        val pending = ledger.prepareTurn("sess-100", "Original", "runtime-100", PendingEditMetadata(20, access.ownerKey))
        ledger.markWriteAttempted(pending.pendingTurnId)
        val vm = ChatViewModel("sess-100", repo, FakeGatewayProtocolAdapter(), ledger, vaultStorage = vault)
        val store = ViewModelStore().also { it.put("chat", vm) }
        try {
            vm.loadSession(); advanceUntilIdle()
            assertEquals(pending.pendingTurnId, vm.uiState.value.unknownEditId)
            assertFalse(vm.uiState.value.isComposerReady)
            vm.retryAmbiguousPrompt(); advanceUntilIdle()
            assertEquals(0, access.writes)
            vm.reviewUnknownEdit(); advanceUntilIdle()
            assertNull(vm.uiState.value.unknownEditId)
            assertEquals("Original", vm.uiState.value.reviewEditText)
            assertNull(vm.uiState.value.edit)
            assertEquals(PendingTurnState.RESOLVED, ledger.getPendingTurn(pending.pendingTurnId)?.state)
            assertEquals(0, access.writes)
        } finally { store.clear() }
    }

    @Test fun readinessAllowsStreamingButRejectsConflictsAndUnchangedText() {
        val message = UserMessage("m", "Hello", editable = EditableMessage(7))
        val ready = ChatUiState(runtimeSessionId = "runtime", isStreaming = true)
        assertTrue(ready.canEdit(message))
        assertTrue(ready.copy(submitBlockedReason = SubmitBlockedReason.NO_RUNTIME).canEdit(message))
        assertFalse(ready.copy(runtimeSessionId = null).canEdit(message))
        assertFalse(ready.canEdit(message.copy(editable = null)))
        assertFalse(ready.copy(savedSnapshotCapturedAtMillis = 1).canEdit(message))
        assertFalse(ready.copy(pendingImages = listOf(PendingImageAttachment("synthetic", "Image", "Image"))).canEdit(message))
        val edit = ready.copy(edit = MessageEditState("m", 7, "Hello", "Hello"))
        assertFalse(edit.canResendEdit())
        assertTrue(edit.copy(edit = edit.edit!!.copy(text = "Changed")).canResendEdit())
        assertFalse(edit.canAttachImage)
        assertFalse(edit.isComposerReady)
    }
}
