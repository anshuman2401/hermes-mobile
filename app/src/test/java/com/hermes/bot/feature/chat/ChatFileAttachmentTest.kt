package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.AttachFileResult
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.SessionHandle
import com.hermes.bot.feature.chat.components.fileSizeLabel
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatFileAttachmentTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun missingRuntimeKeepsAttachmentsDisabledWithoutRestoreHint() {
        for (reason in listOf(null, SubmitBlockedReason.NO_RUNTIME)) {
            val state = ChatUiState(runtimeSessionId = null, submitBlockedReason = reason)
            assertFalse(state.canAttachImage)
            assertFalse(state.canAttachFile)
            assertEquals(null, state.attachmentDisabledReason)
        }
    }

    private class Repo : SessionRepository {
        override val activeSession: StateFlow<SessionHandle?> = MutableStateFlow(null)
        override val activeMessages: StateFlow<List<SessionMessage>> = MutableStateFlow(emptyList())
        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())
        val attached = mutableListOf<Pair<String, Int>>()
        var attachGate: CompletableDeferred<GatewayResult<AttachFileResult>>? = null
        var attachResult: GatewayResult<AttachFileResult>? = null
        var throwOnAttach = false
        var detachCalls = 0
        var resumeResult: Result<SessionResumeResult> = Result.success(
            SessionResumeResult("<runtime>", "<durable>", 0)
        )
        override suspend fun listSessions(forceRefresh: Boolean) = Result.success(SessionListOutcome(emptyList(), false))
        override suspend fun fetchHistorySnapshot(durableSessionId: String) = resumeSession(durableSessionId)
        override suspend fun createDraftSession(title: String?) = Result.success(SessionHandle("<durable>", "<runtime>", isDraft = true))
        override suspend fun resumeSession(storedSessionId: String) = resumeResult
        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
        override suspend fun attachFile(runtimeSessionId: String, bytes: ByteArray, mimeType: String, name: String): GatewayResult<AttachFileResult> {
            attached += runtimeSessionId to bytes.size
            if (throwOnAttach) throw IllegalStateException("<transport-failure>")
            assertEquals("application/pdf", mimeType)
            assertEquals("report.pdf", name)
            return attachGate?.await() ?: attachResult ?: GatewayResult.Success(AttachFileResult(name, "<path>", "<ref-path>", "<ref-text>", true))
        }
        override suspend fun detachImage(runtimeSessionId: String, serverPath: String): GatewayResult<com.hermes.bot.data.gateway.DetachImageResult> {
            detachCalls++
            throw AssertionError("File removal must stay local")
        }
    }

    private fun viewModel(repo: Repo, bytes: ByteArray) = ChatViewModel(
        sessionId = "<durable>",
        sessionRepository = repo,
        documentAttachmentReader = DocumentAttachmentReader {
            SelectedDocument(bytes, "report.pdf", "application/pdf")
        },
        documentReadContext = mainDispatcherRule.testDispatcher
    )

    @Test fun selectedPdf_addsNamedSizedChip_andRemovalDropsOnlyLocalRef() = runTest {
        val repo = Repo()
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()

        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()

        assertEquals(listOf("<runtime>" to 1), repo.attached)
        assertEquals(listOf(PendingFileAttachment("<ref-text>", "report.pdf", 1)), vm.uiState.value.pendingFiles)
        assertTrue(vm.uiState.value.isComposerReady)
        assertEquals("1 B", fileSizeLabel(vm.uiState.value.pendingFiles.single().sizeBytes))

        vm.removePendingFile("<ref-text>")
        assertTrue(vm.uiState.value.pendingFiles.isEmpty())
        assertEquals(0, repo.detachCalls)
    }

    @Test fun documentAt50Mb_isAllowed_butFirstExcessByteIsRejectedBeforeRpc() = runTest {
        val repo = Repo()
        val allowed = viewModel(repo, ByteArray(MAX_DOCUMENT_BYTES))
        advanceUntilIdle()
        allowed.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertEquals(listOf("<runtime>" to MAX_DOCUMENT_BYTES), repo.attached)
        assertEquals(1, allowed.uiState.value.pendingFiles.size)

        val tooLargeRepo = Repo()
        val tooLarge = viewModel(tooLargeRepo, ByteArray(MAX_DOCUMENT_BYTES + 1))
        advanceUntilIdle()
        tooLarge.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertTrue(tooLargeRepo.attached.isEmpty())
        assertTrue(tooLarge.uiState.value.pendingFiles.isEmpty())
        assertEquals("Documents must be 50 MB or smaller.", tooLarge.uiState.value.attachmentErrorMessage)
    }

    @Test fun historyReloadIgnoresLateDocumentAttachAck() = runTest {
        val repo = Repo().apply { attachGate = CompletableDeferred() }
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()

        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        vm.loadSession()
        repo.attachGate!!.complete(GatewayResult.Success(
            AttachFileResult("report.pdf", "<path>", "<ref-path>", "<ref-text>", true)
        ))
        advanceUntilIdle()

        assertEquals(listOf("<runtime>" to 1), repo.attached)
        assertTrue(vm.uiState.value.pendingFiles.isEmpty())
        assertFalse(vm.uiState.value.isAttachingFile)
    }

    @Test fun noRuntime_blocksDocumentReadAndAttach() = runTest {
        val repo = Repo().apply { resumeResult = Result.failure(IllegalStateException("unavailable")) }
        var reads = 0
        val vm = ChatViewModel(
            sessionId = "<durable>", sessionRepository = repo,
            documentAttachmentReader = DocumentAttachmentReader {
                reads++
                SelectedDocument(byteArrayOf(97), "report.pdf", "application/pdf")
            },
            documentReadContext = mainDispatcherRule.testDispatcher
        )
        advanceUntilIdle()
        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertEquals(0, reads)
        assertTrue(repo.attached.isEmpty())
        assertFalse(vm.uiState.value.isComposerReady)
    }

    @Test fun hostFileFailure_isDistinctAndDismissible() = runTest {
        val repo = Repo().apply {
            attachResult = GatewayResult.Failure(GatewayFailure.Rpc(5028, "<unsafe-error>"))
        }
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertEquals("Hermes could not attach the document. Try again.", vm.uiState.value.attachmentErrorMessage)
        assertTrue(vm.uiState.value.pendingFiles.isEmpty())
        vm.dismissAttachmentMessage()
        assertEquals(null, vm.uiState.value.attachmentErrorMessage)
        repo.attachResult = null
        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingFiles.size)
        vm.loadSession(preserveAttachments = true)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingFiles.size)
    }

    @Test fun savedSnapshotGate_disablesPickerAndPreventsDocumentRpc() = runTest {
        val state = ChatUiState(runtimeSessionId = "<runtime>", savedSnapshotCapturedAtMillis = 1L)
        assertFalse(state.canAttachFile)
        assertEquals("Reconnect to add attachments to this saved snapshot.", state.attachmentDisabledReason)

        val adapter = FakeGatewayProtocolAdapter()
        val repo = Repo()
        val vm = ChatViewModel(
            sessionId = "<durable>", sessionRepository = repo,
            gatewayProtocolAdapter = adapter,
            documentAttachmentReader = DocumentAttachmentReader { SelectedDocument(byteArrayOf(97), "report.pdf", "application/pdf") },
            documentReadContext = mainDispatcherRule.testDispatcher
        )
        advanceUntilIdle()
        val field = ChatViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as MutableStateFlow<ChatUiState>
        flow.value = vm.uiState.value.copy(savedSnapshotCapturedAtMillis = 1L)
        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertTrue(repo.attached.isEmpty())
    }

    @Test fun thrownFileAttachFailure_isReportedAsConnectionFailure() = runTest {
        val repo = Repo().apply { throwOnAttach = true }
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onDocumentSelected("<selection>")
        advanceUntilIdle()
        assertEquals("Connection lost while trying to attach the document. Reconnect and try again.",
            vm.uiState.value.attachmentErrorMessage)
        assertTrue(vm.uiState.value.pendingFiles.isEmpty())
        assertTrue(vm.uiState.value.canAttachFile)
    }
}
