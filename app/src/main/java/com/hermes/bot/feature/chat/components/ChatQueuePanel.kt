package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.queue.*

internal enum class QueueControl { NONE, RESUME, REVIEW }

/** Restored waiting intent stays inline; only failure or uncertain outcomes need review. */
internal fun queueControl(state: ChatQueueState, isStreaming: Boolean): QueueControl {
    if (!state.visible || state.edit != null) return QueueControl.NONE
    if (state.error != null ||
        state.active?.state in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN, QueueItemState.CONFIRMING) ||
        state.pause in setOf(QueuePause.STORAGE, QueuePause.FAILED, QueuePause.UNKNOWN, QueuePause.CONFIRMATION)) {
        return QueueControl.REVIEW
    }
    if (state.pause != null && state.pause != QueuePause.EDITING) return QueueControl.RESUME
    return QueueControl.NONE
}

@Composable
fun ChatQueuePresentation(state: ChatQueueState, isStreaming: Boolean, maxHeight: Dp,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: (String) -> Unit, onEdit: (String) -> Unit,
    onEditText: (String) -> Unit, onFinishEdit: (Boolean) -> Unit, onCheck: () -> Unit,
    onDismiss: () -> Unit, onRetry: () -> Unit) {
    val control = queueControl(state, isStreaming)
    if (control == QueueControl.RESUME) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = onResume) { Text("Resume queue") }
        }
    } else if (control == QueueControl.REVIEW) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(state.error ?: when (state.active?.state) {
                QueueItemState.UNKNOWN -> "Could not confirm your message finished."
                QueueItemState.FAILED -> "Message could not finish."
                QueueItemState.CONFIRMING -> "Waiting for Hermes to confirm the command."
                else -> "Messages are waiting for the connection."
            }, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onCheck) { Text("Refresh") }
                if (state.active?.state in setOf(QueueItemState.UNKNOWN, QueueItemState.FAILED, QueueItemState.CONFIRMING)) {
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                    if (state.active?.state == QueueItemState.FAILED)
                        TextButton(onClick = onRetry) { Text("Retry") }
                } else TextButton(onClick = onResume) { Text("Resume queue") }
            }
        }
    }
    if (state.waiting.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(64.dp))
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("queued-prompt-pills"), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.waiting.forEachIndexed { index, item ->
                Surface(shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 1.dp) {
                    Row(Modifier.widthIn(max = 360.dp).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.List, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(preview(item), modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (item.payload is QueuePayload.Prompt) IconButton(
                            onClick = { onEdit(item.id) }, enabled = state.edit == null, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit queued prompt ${index + 1}")
                        }
                        IconButton(onClick = { onCancel(item.id) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel queued item ${index + 1}")
                        }
                    }
                }
            }
        }
    }
    if (state.edit != null) AlertDialog(
        onDismissRequest = { onFinishEdit(false) },
        title = { Text("Edit queued prompt") },
        text = { Column {
            OutlinedTextField(value = state.edit.text, onValueChange = onEditText,
                modifier = Modifier.fillMaxWidth(), maxLines = 6)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = { onFinishEdit(true) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onFinishEdit(false) }) { Text("Cancel edit") } })

}

private fun preview(item: QueueItem): String = when (val payload = item.payload) {
    is QueuePayload.Prompt -> payload.text
    is QueuePayload.Command -> payload.invocation.text
}
