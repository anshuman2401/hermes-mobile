package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.queue.*

@Composable
fun ChatQueuePresentation(state: ChatQueueState, isStreaming: Boolean, maxHeight: Dp,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: (String) -> Unit, onEdit: (String) -> Unit,
    onEditText: (String) -> Unit, onFinishEdit: (Boolean) -> Unit, onCheck: () -> Unit,
    onDismiss: () -> Unit, onRetry: () -> Unit) {
    var reviewing by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible, isStreaming) { if (!state.visible || isStreaming) reviewing = false }
    if (isStreaming) {
        ChatQueuePanel(state, maxHeight, onPause, onResume, onCancel, onEdit, onEditText,
            onFinishEdit, onCheck, onDismiss, onRetry)
    } else if (state.visible) {
        TextButton(onClick = { reviewing = true }) {
            Text(if (state.waiting.isEmpty() && state.active == null) "Review queue status"
                else "Review queued messages (${state.waiting.size + if (state.active != null) 1 else 0})")
        }
        if (reviewing) AlertDialog(onDismissRequest = { reviewing = false },
            confirmButton = { TextButton(onClick = { reviewing = false }) { Text("Close") } },
            text = { ChatQueuePanel(state, maxHeight, onPause, onResume, onCancel, onEdit, onEditText,
                onFinishEdit, onCheck, onDismiss, onRetry) })
    }
}

@Composable
fun ChatQueuePanel(state: ChatQueueState, maxHeight: Dp, onPause: () -> Unit, onResume: () -> Unit,
    onCancel: (String) -> Unit, onEdit: (String) -> Unit, onEditText: (String) -> Unit,
    onFinishEdit: (Boolean) -> Unit, onCheck: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit) {
    if (!state.visible) return
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = maxHeight.coerceAtLeast(1.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Queue · ${state.waiting.size}/10", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = if (state.pause == null) onPause else onResume,
                    enabled = state.edit == null && state.active?.state !in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN, QueueItemState.CONFIRMING)) {
                    Text(if (state.pause == null) "Pause" else "Resume queue")
                }
            }
            Text(when (state.pause) {
                null -> "Runs in order"
                QueuePause.RESTORED -> "Paused · restored queue"
                QueuePause.STOPPED -> "Paused · response stopped"
                QueuePause.DISCONNECTED -> "Paused · reconnect before resuming"
                QueuePause.BACKGROUND -> "Paused · chat was left"
                QueuePause.FAILED -> "Paused · item failed"
                QueuePause.UNKNOWN -> "Paused · outcome unknown; check history"
                QueuePause.CONFIRMATION -> "Paused · confirmation required"
                QueuePause.EDITING -> "Paused · editing queued prompt"
                QueuePause.STORAGE -> "Paused · queue storage unavailable"
                QueuePause.USER -> "Paused"
            }, style = MaterialTheme.typography.bodySmall)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.active?.let { active ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("${statusLabel(active.state)} · ${preview(active)}", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                if (active.state in setOf(QueueItemState.FAILED, QueueItemState.UNKNOWN, QueueItemState.CONFIRMING)) {
                    Row {
                        TextButton(onClick = onCheck) { Text("Check status") }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                        if (active.state == QueueItemState.FAILED) TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
            state.waiting.forEachIndexed { index, item ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (state.edit?.id == item.id) {
                    OutlinedTextField(value = state.edit.text, onValueChange = onEditText, label = { Text("Edit queued prompt") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row {
                        TextButton(onClick = { onFinishEdit(true) }) { Text("Save") }
                        TextButton(onClick = { onFinishEdit(false) }) { Text("Cancel edit") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${preview(item)}", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (item.payload is QueuePayload.Prompt) IconButton(onClick = { onEdit(item.id) }, enabled = state.edit == null) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit queued prompt ${index + 1}")
                        }
                        IconButton(onClick = { onCancel(item.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel queued item ${index + 1}")
                        }
                    }
                }
            }
        }
    }
}
private fun preview(item: QueueItem): String = when (val payload = item.payload) {
    is QueuePayload.Prompt -> payload.text
    is QueuePayload.Command -> payload.invocation.text
}
private fun statusLabel(state: QueueItemState): String = when (state) {
    QueueItemState.WAITING -> "Waiting"
    QueueItemState.SENDING -> "Sending"
    QueueItemState.STREAMING -> "Streaming"
    QueueItemState.RUNNING_COMMAND -> "Running command"
    QueueItemState.CONFIRMING -> "Waiting for confirmation"
    QueueItemState.FAILED -> "Failed"
    QueueItemState.UNKNOWN -> "Outcome unknown"
}
