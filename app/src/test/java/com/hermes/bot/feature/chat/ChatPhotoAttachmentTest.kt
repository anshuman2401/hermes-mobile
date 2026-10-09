package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.AttachImageResult
import com.hermes.bot.data.gateway.DetachImageResult
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import com.hermes.bot.domain.ConnectionStatus
import com.hermes.bot.data.gateway.SessionMessage
import com.hermes.bot.data.gateway.SessionResumeResult
import com.hermes.bot.data.session.SessionListOutcome
import com.hermes.bot.data.session.SessionRepository
import com.hermes.bot.domain.SessionHandle
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
class ChatPhotoAttachmentTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private class Repo : SessionRepository {
        override val activeSession: StateFlow<SessionHandle?> = MutableStateFlow(null)
        override val activeMessages: StateFlow<List<SessionMessage>> = MutableStateFlow(emptyList())
        override val activeTurnStreaming: StateFlow<ChatStreamingStatus> = MutableStateFlow(ChatStreamingStatus())
        val attached = mutableListOf<Pair<String, Int>>()
        val detached = mutableListOf<Pair<String, String>>()
        var attachGate: CompletableDeferred<GatewayResult<AttachImageResult>>? = null
        var detachGate: CompletableDeferred<GatewayResult<DetachImageResult>>? = null
        var attachResult: GatewayResult<AttachImageResult>? = null
        var throwOnAttach = false
        var resumeResult: Result<SessionResumeResult> = Result.success(SessionResumeResult("<runtime>", "<durable>", 0))
        override suspend fun listSessions(forceRefresh: Boolean) = Result.success(SessionListOutcome(emptyList(), false))
        override suspend fun fetchHistorySnapshot(durableSessionId: String) = resumeSession(durableSessionId)
        override suspend fun createDraftSession(title: String?) = Result.success(SessionHandle("<durable>", "<runtime>", isDraft = true))
        override suspend fun resumeSession(storedSessionId: String) = resumeResult
        override suspend fun restoreSelectedSession(): SessionHandle? = null
        override suspend fun clearActiveSession() {}
        override suspend fun getActiveSession(): SessionHandle? = null
        override suspend fun attachImage(runtimeSessionId: String, bytes: ByteArray, name: String, ext: String?): GatewayResult<AttachImageResult> {
            attached += runtimeSessionId to bytes.size
            if (throwOnAttach) throw IllegalStateException("<transport-failure>")
            assertEquals("photo.png", name)
            assertEquals("png", ext)
            return attachGate?.await() ?: attachResult ?: GatewayResult.Success(AttachImageResult("<image-path>", 1, "<image-text>", bytes.size))
        }
        override suspend fun detachImage(runtimeSessionId: String, serverPath: String): GatewayResult<DetachImageResult> {
            detached += runtimeSessionId to serverPath
            return detachGate?.await() ?: GatewayResult.Success(DetachImageResult(true, 0))
        }
    }

    private fun viewModel(repo: Repo, bytes: ByteArray, sessionId: String = "<durable>", adapter: FakeGatewayProtocolAdapter? = null) = ChatViewModel(
        sessionId = sessionId,
        sessionRepository = repo,
        gatewayProtocolAdapter = adapter,
        photoAttachmentReader = PhotoAttachmentReader { SelectedPhoto(bytes, "photo.png", "png") },
        photoReadContext = mainDispatcherRule.testDispatcher
    )

    @Test fun selectedPhoto_attachesOnLiveRuntime_thenRemovalDetachesServerPath() = runTest {
        val repo = Repo()
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals(listOf("<runtime>" to 1), repo.attached)
        assertEquals(listOf(PendingImageAttachment("<image-path>", "photo.png", "<image-text>")), vm.uiState.value.pendingImages)
        assertTrue(vm.uiState.value.isComposerReady)

        val otherSession = viewModel(Repo(), byteArrayOf(97), "<other-session>")
        advanceUntilIdle()
        assertTrue(otherSession.uiState.value.pendingImages.isEmpty())

        vm.removePendingImage("<image-path>")
        advanceUntilIdle()
        assertEquals(listOf("<runtime>" to "<image-path>"), repo.detached)
        assertTrue(vm.uiState.value.pendingImages.isEmpty())

    }

    @Test fun overCapPhoto_isRejectedBeforeAnyRpc() = runTest {
        val repo = Repo()
        val vm = viewModel(repo, ByteArray(MAX_PHOTO_BYTES + 1))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertTrue(repo.attached.isEmpty())
        assertTrue(vm.uiState.value.pendingImages.isEmpty())
        assertEquals("Photos must be 25 MB or smaller.", vm.uiState.value.attachmentErrorMessage)
    }

    @Test fun historyReloadKeepsQueuedPhotoWhenDetachAckIsLate() = runTest {
        val repo = Repo()
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        val queued = vm.uiState.value.pendingImages

        repo.detachGate = CompletableDeferred()
        vm.removePendingImage("<image-path>")
        advanceUntilIdle()
        vm.loadSession(preserveAttachments = true)
        advanceUntilIdle()
        repo.detachGate!!.complete(GatewayResult.Success(DetachImageResult(true, 0)))
        advanceUntilIdle()

        assertEquals(listOf("<runtime>" to "<image-path>"), repo.detached)
        assertEquals(queued, vm.uiState.value.pendingImages)
        assertFalse(vm.uiState.value.isAttachingImage)
    }

    @Test fun historyReloadClearsChipsAndIgnoresStaleAttachAck() = runTest {
        val repo = Repo()
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingImages.size)
        vm.loadSession()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingImages.isEmpty())

        repo.attachGate = CompletableDeferred()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        vm.loadSession()
        repo.attachGate!!.complete(GatewayResult.Success(AttachImageResult("<late-path>", 1, "<image-text>", 1)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.pendingImages.isEmpty())
        assertEquals(1, repo.attached.last().second)
    }

    @Test fun missingRuntimeAndSnapshotGatesPreventAttachmentAndSend() = runTest {
        val chips = listOf(PendingImageAttachment("<image-path>", "photo.png", "<image-text>"))
        val ready = ChatUiState(runtimeSessionId = "<runtime>", pendingImages = chips)
        assertTrue(ready.isComposerReady)
        assertTrue(ready.canAttachImage)
        assertFalse(ready.copy(runtimeSessionId = null).canAttachImage)
        assertFalse(ready.copy(runtimeSessionId = null).isComposerReady)
        assertFalse(ready.copy(savedSnapshotCapturedAtMillis = 1).canAttachImage)
        assertFalse(ready.copy(savedSnapshotCapturedAtMillis = 1).isComposerReady)
        assertFalse(ready.copy(isInitialLoading = true).canAttachImage)
        assertFalse(ready.copy(isRefreshing = true).canAttachImage)
        assertFalse(ready.copy(isAttachingImage = true).isComposerReady)
        assertFalse(ready.copy(isSubmitting = true).canAttachImage)
        assertFalse(ready.copy(isStreaming = true).canAttachImage)
        assertFalse(ready.copy(submitBlockedReason = SubmitBlockedReason.NO_RUNTIME).canAttachImage)

        val repo = Repo().apply { resumeResult = Result.failure(IllegalStateException("unavailable")) }
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertTrue(repo.attached.isEmpty())
    }

    @Test fun attachmentFailures_areCategorizedDismissibleAndDoNotBlockRetry() = runTest {
        val cases = listOf(
            4015 to "Hermes rejected the photo attachment request. Check its type or size.",
            4016 to "Hermes rejected the photo attachment request. Check its type or size.",
            4017 to "Hermes rejected the photo attachment request. Check its type or size.",
            4018 to "Hermes rejected the photo attachment request. Check its type or size.",
            5027 to "Hermes could not attach the photo. Try again.",
            -32601 to "This Hermes host does not support attachments."
        )
        for ((code, expected) in cases) {
            val repo = Repo().apply {
                attachResult = GatewayResult.Failure(GatewayFailure.Rpc(code, "<unsafe-error>"))
            }
            val vm = viewModel(repo, byteArrayOf(97))
            advanceUntilIdle()
            vm.onPhotoSelected("<selection>")
            advanceUntilIdle()
            assertEquals(expected, vm.uiState.value.attachmentErrorMessage)
            assertTrue(vm.uiState.value.pendingImages.isEmpty())
            assertTrue(vm.uiState.value.canAttachImage)
            vm.dismissAttachmentMessage()
            assertEquals(null, vm.uiState.value.attachmentErrorMessage)
            repo.attachResult = null
            vm.onPhotoSelected("<selection>")
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.pendingImages.size)
        }
    }

    @Test fun transportFailureAndReconnectCountMismatch_remainVisibleUntilDismissed() = runTest {
        val adapter = FakeGatewayProtocolAdapter()
        val repo = Repo()
        val vm = viewModel(repo, byteArrayOf(97), adapter = adapter)
        advanceUntilIdle()
        repo.attachResult = GatewayResult.Failure(GatewayFailure.Transport("<unsafe-error>"))
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals("Connection lost while trying to attach the photo. Reconnect and try again.", vm.uiState.value.attachmentErrorMessage)
        assertTrue(vm.uiState.value.pendingImages.isEmpty())

        repo.attachResult = null
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingImages.size)
        adapter.connectionStatus.value = ConnectionStatus.OFFLINE
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canAttachImage)
        assertEquals("Reconnect to add attachments.", vm.uiState.value.attachmentDisabledReason)
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals(2, repo.attached.size)

        adapter.connectionStatus.value = ConnectionStatus.CONNECTED
        advanceUntilIdle()
        vm.loadSession(preserveAttachments = true)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingImages.size)
        repo.attachResult = GatewayResult.Success(AttachImageResult("<second-path>", 1, "<image-text>", 1))
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.pendingImages.size)
        assertEquals("Some queued photos may no longer be on the server. Review and reattach them before sending.",
            vm.uiState.value.attachmentErrorMessage)
        vm.dismissAttachmentMessage()
        assertEquals(null, vm.uiState.value.attachmentErrorMessage)
        assertEquals(2, vm.uiState.value.pendingImages.size)
    }

    @Test fun thrownAttachFailure_isReportedAsConnectionFailure() = runTest {
        val repo = Repo().apply { throwOnAttach = true }
        val vm = viewModel(repo, byteArrayOf(97))
        advanceUntilIdle()
        vm.onPhotoSelected("<selection>")
        advanceUntilIdle()
        assertEquals("Connection lost while trying to attach the photo. Reconnect and try again.",
            vm.uiState.value.attachmentErrorMessage)
        assertTrue(vm.uiState.value.pendingImages.isEmpty())
        assertTrue(vm.uiState.value.canAttachImage)
    }
}
