package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.commands.SlashSuggestion
import com.hermes.bot.data.commands.SlashResult

@Composable
fun SlashCommandPanel(
    visible: Boolean,
    loading: Boolean,
    suggestions: List<SlashSuggestion>,
    error: String?,
    busy: Boolean,
    result: SlashResult?,
    onChoose: (SlashSuggestion) -> Unit,
    onDismiss: () -> Unit,
    onDismissResult: () -> Unit = {}
) {
    if (visible) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Slash command suggestions" }
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Slash commands", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss slash suggestions")
                    }
                }
                when {
                    loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading suggestions…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    error != null -> Text(error, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    suggestions.isEmpty() -> Text("No matching commands", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
                        val groups = suggestions.groupBy { it.group }
                        groups.forEach { (group, rows) ->
                            item { Text(group, Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(rows) { row ->
                                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onChoose(row) }
                                    .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(30.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(if (group == "Skills") "✦" else "/",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(row.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        if (row.description.isNotBlank()) Text(row.description,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    Text("↗", Modifier.padding(start = 8.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (busy) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Running command…", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    } else if (!visible && result != null) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(result.command, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissResult, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss command result")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 160.dp).fillMaxWidth()) {
                    item { Text(result.text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
