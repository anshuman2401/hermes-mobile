package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.AttachmentFailureKind
import com.hermes.bot.data.gateway.GatewayFailure
import com.hermes.bot.data.gateway.GatewayResult
import com.hermes.bot.data.gateway.attachmentFailureKind
import com.hermes.bot.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Owns attachment UI operations for one ChatViewModel and its coroutine lifetime. */
internal class ChatAttachmentController(
    private val uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val repository: SessionRepository?,
    private val photoAttachmentReader: PhotoAttachmentReader?,
    private val photoReadContext: CoroutineContext,
    private val documentAttachmentReader: DocumentAttachmentReader?,
    private val documentReadContext: CoroutineContext,
    private val gatewayAvailable: () -> Boolean
) {
    private var attachmentEpoch = 0L
    private var verifyServerImageCountAfterReconnect = false

    /** A late attachment result belongs only to the still displayed runtime and attachment epoch. */
    private fun ownsAttachmentResult(epoch: Long, runtimeId: String): Boolean =
        attachmentEpoch == epoch && uiState.value.runtimeSessionId == runtimeId

    private fun attachmentFailureMessage(failure: GatewayFailure, subject: String, action: String = "attach"): String =
        when (failure.attachmentFailureKind()) {
            AttachmentFailureKind.UNSUPPORTED -> "This Hermes host does not support attachments."
            AttachmentFailureKind.VALIDATION -> "Hermes rejected the $subject attachment request. Check its type or size."
            AttachmentFailureKind.HOST -> "Hermes could not $action the $subject. Try again."
            AttachmentFailureKind.TRANSPORT -> "Connection lost while trying to $action the $subject. Reconnect and try again."
            AttachmentFailureKind.OTHER -> "Could not $action the $subject. Try again."
        }

    fun dismissMessage() {
        uiState.update { it.copy(attachmentErrorMessage = null) }
    }

    fun invalidateForHistoryLoad(verifyQueuedImages: Boolean) {
        attachmentEpoch++
        verifyServerImageCountAfterReconnect = verifyQueuedImages
    }

    fun invalidateForRefresh() {
        attachmentEpoch++
        verifyServerImageCountAfterReconnect = false
    }

    fun verifyImageCountAfterReconnect() {
        verifyServerImageCountAfterReconnect = true
    }

    fun onPhotoSelected(uri: String): Job? {
        val state = uiState.value
        val runtimeId = state.runtimeSessionId ?: return null
        val repo = repository ?: return null
        val reader = photoAttachmentReader ?: return null
        if (!state.canAttachImage || !gatewayAvailable()) return null
        val epoch = attachmentEpoch
        uiState.update { it.copy(isAttachingImage = true, attachmentErrorMessage = null) }
        return scope.launch {
            try {
                val photo = withContext(photoReadContext) { reader.read(uri) }
                if (!ownsAttachmentResult(epoch, runtimeId)) return@launch
                if (photo.bytes.size > MAX_PHOTO_BYTES) {
                    uiState.update { it.copy(attachmentErrorMessage = "Photos must be 25 MB or smaller.") }
                    return@launch
                }
                if (!gatewayAvailable()) {
                    uiState.update { it.copy(attachmentErrorMessage = "Reconnect to add attachments.") }
                    return@launch
                }
                val result = try {
                    withContext(photoReadContext) {
                        repo.attachImage(runtimeId, photo.bytes, photo.displayName, photo.ext)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (ownsAttachmentResult(epoch, runtimeId)) {
                        uiState.update { it.copy(attachmentErrorMessage = "Connection lost while trying to attach the photo. Reconnect and try again.") }
                    }
                    return@launch
                }
                if (!ownsAttachmentResult(epoch, runtimeId)) return@launch
                val countNotice = if (result is GatewayResult.Success && verifyServerImageCountAfterReconnect) {
                    verifyServerImageCountAfterReconnect = false
                    val expected = uiState.value.pendingImages.size + 1
                    when {
                        result.value.count < expected -> "Some queued photos may no longer be on the server. Review and reattach them before sending."
                        result.value.count > expected -> "The server has more queued photos than shown here. Review attachments before sending."
                        else -> null
                    }
                } else null
                uiState.update { current ->
                    when (result) {
                        is GatewayResult.Success -> current.copy(
                            pendingImages = current.pendingImages + PendingImageAttachment(
                                result.value.path, photo.displayName, result.value.text
                            ),
                            attachmentErrorMessage = countNotice
                        )
                        is GatewayResult.Failure -> current.copy(attachmentErrorMessage = attachmentFailureMessage(result.failure, "photo"))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(attachmentErrorMessage = "Could not read photo. Try again.") }
                }
            } finally {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(isAttachingImage = false) }
                }
            }
        }
    }

    fun onDocumentSelected(uri: String) {
        val state = uiState.value
        val runtimeId = state.runtimeSessionId ?: return
        val repo = repository ?: return
        val reader = documentAttachmentReader ?: return
        if (!state.canAttachFile || !gatewayAvailable()) return
        val epoch = attachmentEpoch
        uiState.update { it.copy(isAttachingFile = true, attachmentErrorMessage = null) }
        scope.launch {
            try {
                val document = withContext(documentReadContext) { reader.read(uri) }
                if (!ownsAttachmentResult(epoch, runtimeId)) return@launch
                if (document.bytes.size > MAX_DOCUMENT_BYTES) {
                    uiState.update { it.copy(attachmentErrorMessage = "Documents must be 50 MB or smaller.") }
                    return@launch
                }
                if (!gatewayAvailable()) {
                    uiState.update { it.copy(attachmentErrorMessage = "Reconnect to add attachments.") }
                    return@launch
                }
                val result = try {
                    withContext(documentReadContext) {
                        repo.attachFile(runtimeId, document.bytes, document.mimeType, document.displayName)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    if (ownsAttachmentResult(epoch, runtimeId)) {
                        uiState.update { it.copy(attachmentErrorMessage = "Connection lost while trying to attach the document. Reconnect and try again.") }
                    }
                    return@launch
                }
                if (!ownsAttachmentResult(epoch, runtimeId)) return@launch
                uiState.update { current ->
                    when (result) {
                        is GatewayResult.Success -> current.copy(
                            pendingFiles = current.pendingFiles + PendingFileAttachment(
                                result.value.refText, document.displayName, document.bytes.size
                            )
                        )
                        is GatewayResult.Failure -> current.copy(attachmentErrorMessage = attachmentFailureMessage(result.failure, "document"))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(attachmentErrorMessage = "Could not read document. Try again.") }
                }
            } finally {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(isAttachingFile = false) }
                }
            }
        }
    }

    /** File refs are prompt-local; the host has no file detach RPC. */
    fun removePendingFile(refText: String) {
        uiState.update { current ->
            val index = current.pendingFiles.indexOfFirst { it.refText == refText }
            if (index < 0) current else current.copy(
                pendingFiles = current.pendingFiles.toMutableList().apply { removeAt(index) }
            )
        }
    }

    fun removePendingImage(serverPath: String) {
        val state = uiState.value
        val runtimeId = state.runtimeSessionId ?: return
        val repo = repository ?: return
        if (!state.canAttachImage || !gatewayAvailable() || state.pendingImages.none { it.serverPath == serverPath }) return
        val epoch = attachmentEpoch
        uiState.update { it.copy(isAttachingImage = true, attachmentErrorMessage = null) }
        scope.launch {
            try {
                val result = repo.detachImage(runtimeId, serverPath)
                if (!ownsAttachmentResult(epoch, runtimeId)) return@launch
                uiState.update { current ->
                    if (result is GatewayResult.Success && result.value.detached) {
                        current.copy(pendingImages = current.pendingImages.filterNot { it.serverPath == serverPath })
                    } else {
                        current.copy(attachmentErrorMessage = if (result is GatewayResult.Failure)
                            attachmentFailureMessage(result.failure, "photo", "remove")
                        else "Could not remove photo. Try again.")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(attachmentErrorMessage = "Connection lost while trying to remove the photo. Reconnect and try again.") }
                }
            } finally {
                if (ownsAttachmentResult(epoch, runtimeId)) {
                    uiState.update { it.copy(isAttachingImage = false) }
                }
            }
        }
    }

}
