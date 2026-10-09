package io.github.anshuman2401.hermesmobile.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.design.AtelierShapes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SessionsFilterSheet(
    pinnedOnly: Boolean,
    selectedSources: Set<String>?,
    options: List<SessionsSourceFilterPolicy.Option>,
    onPinnedChange: (Boolean) -> Unit,
    onAllSourcesChange: () -> Unit,
    onSourceToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val allState = when {
        selectedSources == null -> ToggleableState.On
        selectedSources.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        selectedLabelColor = MaterialTheme.colorScheme.primary,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
            .padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close filters")
                }
            }
            Text("Show", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !pinnedOnly, onClick = { onPinnedChange(false) }, label = { Text("All") },
                    colors = chipColors, shape = AtelierShapes.action, modifier = Modifier.defaultMinSize(minHeight = 48.dp))
                FilterChip(selected = pinnedOnly, onClick = { onPinnedChange(true) }, label = { Text("Pinned") },
                    colors = chipColors, shape = AtelierShapes.action, modifier = Modifier.defaultMinSize(minHeight = 48.dp))
            }
            Text("Sources", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                item(key = "all-sources") {
                    Row(Modifier.fillMaxWidth().triStateToggleable(state = allState, role = Role.Checkbox,
                        onClick = onAllSourcesChange).defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TriStateCheckbox(state = allState, onClick = null)
                        Text("All sources", modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text(options.sumOf { it.count }.toString(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(options, key = { "source:${it.key}" }) { option ->
                    val checked = selectedSources == null || option.key in selectedSources
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                        onValueChange = { onSourceToggle(option.key) }).defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(option.label, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(option.count.toString(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                .defaultMinSize(minHeight = 48.dp), shape = AtelierShapes.action) { Text("Done") }
        }
    }
}
