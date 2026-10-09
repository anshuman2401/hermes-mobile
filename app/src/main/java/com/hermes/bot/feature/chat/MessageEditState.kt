package com.hermes.bot.feature.chat

import com.hermes.bot.data.gateway.MessageEditPolicy
import com.hermes.bot.data.turn.EditPhase

/** Text remains separate from the persisted normal draft. */
data class MessageEditState(val messageId: String, val rowId: Long, val original: String, val text: String,
    val phase: EditPhase? = null, val error: String? = null, val targetReady: Boolean = true) {
    val busy get() = phase != null
    override fun toString() = "MessageEditState(busy=$busy, [REDACTED])"
}

// Ordinary Send may be blocked by a running resumed turn. Edit verifies activity and stops it before writing.
internal fun ChatUiState.editReady(): Boolean = !runtimeSessionId.isNullOrBlank() &&
    isAttachmentConnectionAvailable && savedSnapshotCapturedAtMillis == null &&
    !isInitialLoading && !isRefreshing && !isLoadingEarlier && !isSubmitting &&
    !isAttachingImage && !isAttachingFile && pendingImages.isEmpty() && pendingFiles.isEmpty() &&
    !voice.isBusy && !slashBusy && !isCompressionBusy && !pickerBusy && !yoloPending &&
    !approvals.blocksSend && pendingUnknownTurnId == null && unknownEditId == null

internal fun ChatUiState.canEdit(message: UserMessage): Boolean = editReady() && edit == null &&
    message.state == UserMessageState.SENT && message.editable != null && MessageEditPolicy.plainText(message.text)

internal fun ChatUiState.canResendEdit(): Boolean = editReady() && edit?.let {
    it.targetReady && !it.busy && MessageEditPolicy.plainText(it.text) && it.text.trim() != it.original.trim()
} == true
