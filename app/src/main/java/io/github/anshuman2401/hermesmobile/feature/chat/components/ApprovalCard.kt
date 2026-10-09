package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.data.approval.*

/** Approval content is a live, memory-only surface, never a transcript row. */
@Composable
fun ApprovalCard(state: ApprovalState, maxHeight: Dp, onDecide: (String, ApprovalChoice) -> Unit, onCheck: () -> Unit) {
    val request = state.requests.firstOrNull()
    if (request == null && state.notice == null) return
    var confirmAlways by remember(request?.requestId) { mutableStateOf(false) }
    val enabled = state.canDecide
    val cardHeight = maxHeight.coerceAtLeast(120.dp)
    val cardScroll = remember(request?.requestId) { androidx.compose.foundation.ScrollState(0) }
    LaunchedEffect(enabled, request?.choices) {
        if (!enabled || ApprovalChoice.ALWAYS !in request?.choices.orEmpty()) confirmAlways = false
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.heightIn(max = cardHeight).verticalScroll(cardScroll).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (request != null) "Approval needed" else "Approval update", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (state.requests.size > 1) Text("1 of ${state.requests.size}", style = MaterialTheme.typography.labelSmall)
            }
            if (request != null) {
                Text(request.details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(10.dp))
                Text("Choose how long to allow this request.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ApprovalChoice.DENY in request.choices) OutlinedButton(
                        onClick = { onDecide(request.requestId, ApprovalChoice.DENY) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reject") }
                    if (ApprovalChoice.ONCE in request.choices) Button(
                        onClick = { onDecide(request.requestId, ApprovalChoice.ONCE) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Run once") }
                }
                if (ApprovalChoice.SESSION in request.choices || ApprovalChoice.ALWAYS in request.choices) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ApprovalChoice.SESSION in request.choices) FilledTonalButton(
                            onClick = { onDecide(request.requestId, ApprovalChoice.SESSION) }, enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("This session") }
                        if (ApprovalChoice.ALWAYS in request.choices) FilledTonalButton(
                            onClick = { confirmAlways = true }, enabled = enabled,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Always allow") }
                    }
                }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.connected && state.phase in setOf(ApprovalPhase.UNKNOWN, ApprovalPhase.UNAVAILABLE))
                TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check status") }
        }
    }
    if (confirmAlways && request != null && enabled && ApprovalChoice.ALWAYS in request.choices) {
        AlertDialog(onDismissRequest = { confirmAlways = false }, title = { Text("Always allow?") },
            text = { Text("Hermes will remember this flagged pattern for future sessions in this profile. Other approval rules still apply.") },
            confirmButton = { TextButton(onClick = { confirmAlways = false; onDecide(request.requestId, ApprovalChoice.ALWAYS) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Always allow") } },
            dismissButton = { TextButton(onClick = { confirmAlways = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } })
    }
}
