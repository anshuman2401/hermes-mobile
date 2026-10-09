package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.hermes.bot.feature.chat.VoiceUiState
import com.hermes.bot.feature.chat.VoicePhase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.hermes.bot.feature.chat.MessageEditState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.bot.feature.chat.PendingImageAttachment
import com.hermes.bot.feature.chat.PendingFileAttachment

internal fun fileSizeLabel(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> "${(bytes + 1024 * 1024 - 1) / (1024 * 1024)} MB"
}

/**
 * Text composer shell per ARCH-001 (chat-rendering.md / ux-navigation.md § 3. Chat).
 * Send only via the button (owner directive 2026-09-27: Enter inserts a
 * newline), single-flight submission protection, and >=48dp touch targets.
 */
@Composable
fun ChatComposer(
    draftText: String,
    edit: MessageEditState? = null,
    canResendEdit: Boolean = false,
    onCancelEdit: () -> Unit = {},
    voice: VoiceUiState = VoiceUiState(),
    dictationAvailable: Boolean = false,
    canStartDictation: Boolean = false,
    onDictationClick: () -> Unit = {},
    onStopDictation: () -> Unit = {},
    onCancelDictation: () -> Unit = {},
    onDismissDictationMessage: () -> Unit = {},
    onOpenMicrophoneSettings: () -> Unit = {},
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    isSubmitting: Boolean,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    // BLD-006 S2: true when submitBlockedReason == NO_RUNTIME. Gates Send/IME
    // Send only — the text field stays enabled so the draft remains editable.
    isSendBlocked: Boolean = false,
    // BLD-006 S3: single derived readiness flag from ChatUiState.isComposerReady.
    // When null (standalone previews), falls back to the local derivation below.
    isComposerReady: Boolean? = null,
    pendingImages: List<PendingImageAttachment> = emptyList(),
    pendingFiles: List<PendingFileAttachment> = emptyList(),
    attachmentErrorMessage: String? = null,
    onDismissAttachmentMessage: (() -> Unit)? = null,
    attachmentDisabledReason: String? = null,
    onRemoveImage: ((String) -> Unit)? = null,
    onRemoveFile: ((String) -> Unit)? = null,
    onStopStreaming: () -> Unit = {},
    // Presentation-only seam for the future attachment flow. The default keeps
    // the affordance visible but inert until attachment behavior is planned.
    onAttachmentClick: (() -> Unit)? = null,
    attachmentMenu: @Composable () -> Unit = {},
    // CHAT-010 (frozen plan v1): the derived session model + reasoning label
    // from ChatUiState.sessionModelLabel. Null (standalone previews, host
    // never reported a model) omits the model control — never a placeholder.
    // CHAT-016 (TASK-017, OQ-001 owner resolution 2026-09-26): the caption row
    // is unconditionally tappable so the read-only model sheet is reachable
    // without a live runtime. The former enablement parameter is removed; the
    // only RPC path (ChatViewModel.openSessionPicker) keeps its
    // runtime/guard checks (safe no-op without a runtime) — no ViewModel
    // change (CON-002).
    sessionModelLabel: String? = null,
    modelChangeStatus: String? = null,
    modelChangePending: Boolean = false,
    sessionYolo: Boolean? = null,
    yoloPending: Boolean = false,
    canToggleYolo: Boolean = false,
    onToggleYolo: () -> Unit = {},
    onOpenContextUsage: (() -> Unit)? = null,
    onOpenPicker: () -> Unit = {},
    isSlashCommand: Boolean = false,
    slashContent: @Composable () -> Unit = {}
) {
    // S3 single flight: streaming gates Send/IME Send exactly like submitting.
    // The explicit flag (when provided) is authoritative; the fallback keeps
    // standalone previews and legacy callers working unchanged.
    val showStop = isStreaming && !isSlashCommand && edit == null
    val canSend = isComposerReady ?: (
        !voice.isBusy &&
        (draftText.isNotBlank() || pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) &&
            !isSubmitting &&
            !isStreaming &&
            !isSendBlocked
        )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (edit != null) {
            Text("Editing message", style = MaterialTheme.typography.titleSmall)
            Text("Resending replaces this message and removes all later messages from this conversation. Actions already performed are not undone.",
                style = MaterialTheme.typography.bodySmall)
            edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelEdit, enabled = !edit.busy) { Text("Cancel") }
                TextButton(onClick = onSend, enabled = canResendEdit && !edit.busy) { Text("Save & resend") }
            }
            edit.phase?.let { phase -> Text(when (phase) {
                com.hermes.bot.data.turn.EditPhase.CHECKING -> "Checking history…"
                com.hermes.bot.data.turn.EditPhase.STOPPING -> "Stopping response…"
                com.hermes.bot.data.turn.EditPhase.UPDATING -> "Updating conversation…"
            }, style = MaterialTheme.typography.labelSmall) }
        }
        if (!voice.isBusy && edit == null) slashContent()
        if (voice.isBusy || voice.message != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = when (voice.phase) {
                    VoicePhase.PERMISSION -> "Waiting for microphone permission…"
                    VoicePhase.STARTING -> "Starting microphone…"
                    VoicePhase.RECORDING -> "Recording · ${voice.elapsedSeconds / 60}:${(voice.elapsedSeconds % 60).toString().padStart(2, '0')} / 2:00"
                    VoicePhase.TRANSCRIBING -> "Transcribing…"
                    VoicePhase.IDLE -> voice.message.orEmpty()
                }
                if (voice.phase == VoicePhase.TRANSCRIBING || voice.phase == VoicePhase.STARTING) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = if (voice.message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (voice.isBusy) TextButton(onClick = onCancelDictation) { Text("Cancel") }
                else {
                    if (voice.openSettings) TextButton(onClick = onOpenMicrophoneSettings) { Text("Open app settings") }
                    TextButton(onClick = onDismissDictationMessage) { Text("Dismiss") }
                }
            }
        }
        if (attachmentErrorMessage != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = attachmentErrorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f).semantics { contentDescription = attachmentErrorMessage }
                )
                if (onDismissAttachmentMessage != null) {
                    TextButton(onClick = onDismissAttachmentMessage) { Text("Dismiss") }
                }
            }
        }
        if (onAttachmentClick == null && attachmentDisabledReason != null) {
            Text(
                text = attachmentDisabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingImages.forEach { image ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = image.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 12.dp)
                                    .semantics { contentDescription = "Attached image ${image.displayName}" }
                            )
                            IconButton(
                                onClick = { onRemoveImage?.invoke(image.serverPath) },
                                enabled = onRemoveImage != null,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "Remove image ${image.displayName}"
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        if (pendingFiles.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingFiles.forEach { file ->
                    val size = fileSizeLabel(file.sizeBytes)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${file.displayName} · $size",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 12.dp)
                                    .semantics { contentDescription = "Attached file ${file.displayName}, $size" }
                            )
                            IconButton(
                                onClick = { onRemoveFile?.invoke(file.refText) },
                                enabled = onRemoveFile != null,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "Remove file ${file.displayName}"
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box {
                IconButton(
                    onClick = { onAttachmentClick?.invoke() },
                    enabled = onAttachmentClick != null,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (onAttachmentClick == null) {
                                attachmentDisabledReason?.let { "Add attachment. $it" }
                                    ?: "Add attachment (not available yet)"
                            } else {
                                "Add attachment"
                            }
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (onAttachmentClick != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        }
                    )
                }
                attachmentMenu()
            }

            val fieldShape = RoundedCornerShape(24.dp)
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val outlineWidth = if (isFocused) 1.dp else 0.5.dp
            val outlineColor = when {
                isFocused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }

            var fieldValue by remember { mutableStateOf(TextFieldValue(draftText)) }
            var lastTranscriptRevision by remember { mutableStateOf(voice.transcriptRevision) }
            LaunchedEffect(draftText, voice.transcriptRevision) {
                if (voice.transcriptRevision != lastTranscriptRevision) {
                    fieldValue = TextFieldValue(draftText, TextRange(draftText.length))
                    lastTranscriptRevision = voice.transcriptRevision
                } else if (fieldValue.text != draftText) {
                    fieldValue = TextFieldValue(draftText, TextRange(
                        fieldValue.selection.start.coerceAtMost(draftText.length),
                        fieldValue.selection.end.coerceAtMost(draftText.length)))
                }
            }
            val editFocus = remember { FocusRequester() }
            LaunchedEffect(edit?.messageId, edit?.busy) {
                if (edit != null && !edit.busy) editFocus.requestFocus()
            }
            OutlinedTextField(
                enabled = edit?.busy != true,
                value = fieldValue,
                onValueChange = { fieldValue = it; onDraftChange(it.text) },
                trailingIcon = if (dictationAvailable && edit == null) ({
                    IconButton(onClick = if (voice.phase == VoicePhase.RECORDING) onStopDictation else onDictationClick,
                        enabled = voice.phase == VoicePhase.RECORDING || canStartDictation,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = if (voice.phase == VoicePhase.RECORDING) "Stop recording" else "Dictate message"
                        }) {
                        val tint = if (voice.phase == VoicePhase.RECORDING) MaterialTheme.colorScheme.error
                            else if (canStartDictation) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                            if (voice.phase == VoicePhase.RECORDING) {
                                drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.2f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f))
                            } else {
                                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.35f, 0f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.3f, size.height * 0.6f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f))
                                drawArc(tint, 0f, 180f, false,
                                    topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.2f, size.height * 0.3f),
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.5f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                                drawLine(tint, androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.8f),
                                    androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                }) else null,
                shape = fieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    errorBorderColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        text = "Ask Hermes...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                // Owner directive 2026-09-27 (ui-enter-newline-v1): Enter
                // inserts a newline; send only via the button — the IME action
                // is unconditional Default (never Send), and no onSend keyboard
                // callback is wired to the field.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(editFocus)
                    .defaultMinSize(minHeight = 48.dp)
                    .border(
                        width = outlineWidth,
                        color = outlineColor,
                        shape = fieldShape
                    )
                    .semantics {
                        contentDescription = "Message input. Current value: ${draftText.ifEmpty { "empty" }}"
                    }
            )

            val buttonEnabled = if (showStop) true else canSend
            if (edit == null) IconButton(
                onClick = if (showStop) onStopStreaming else onSend,
                enabled = buttonEnabled,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = when {
                            showStop -> "Stop generating"
                            isSubmitting -> "Sending prompt..."
                            isSendBlocked -> "Send prompt. Session needs to be restored."
                            else -> "Send prompt"
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            // The active stop action uses the error role; Send uses primary.
                            if (showStop) MaterialTheme.colorScheme.error
                            else if (buttonEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else if (showStop) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(MaterialTheme.colorScheme.onError)
                        )
                    } else {
                        PaperPlaneIcon(
                            modifier = Modifier.size(20.dp),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onOpenContextUsage?.invoke() }, enabled = onOpenContextUsage != null,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Context & usage" }) {
                ContextUsageIcon(tint = if (onOpenContextUsage != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            YoloToggle(sessionYolo, canToggleYolo, onToggleYolo, yoloPending)
            if (sessionModelLabel != null) {
                Row(
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                        .clickable(enabled = !voice.isBusy, onClick = onOpenPicker)
                        .semantics {
                            contentDescription = "Current session model: $sessionModelLabel" +
                                ". Change model or reasoning for this chat"
                        },
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sessionModelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (modelChangePending) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.dp)
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
        modelChangeStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
    }
}
