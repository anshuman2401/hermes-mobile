package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.anshuman2401.hermesmobile.data.gateway.PickerProviderGroup
import io.github.anshuman2401.hermesmobile.data.gateway.SESSION_REASONING_LEVELS
import io.github.anshuman2401.hermesmobile.data.gateway.SessionModelOption
import io.github.anshuman2401.hermesmobile.data.gateway.SessionModelPickerGroupingPolicy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

internal const val PICKER_UNAVAILABLE_TITLE = "Can't switch models"
internal const val PICKER_UNAVAILABLE_BODY = "Connect to Hermes to change the model or reasoning."

/** Match the Settings model dialog's 60% display height, clamped for the keyboard and system bars. */
internal fun modelPickerDialogHeightPx(screenHeightPx: Int, topInsetPx: Int, bottomInsetPx: Int, imeBottomPx: Int): Int {
    val desired = (screenHeightPx * 0.60f).roundToInt()
    return min(desired, max(0, screenHeightPx - topInsetPx - max(bottomInsetPx, imeBottomPx)))
}

/** Settings-style presentation for the current session; requests remain owned by ChatViewModel. */
@Composable
fun ModelPickerDialog(
    options: List<SessionModelOption>,
    currentModelName: String?,
    currentReasoningEffort: String?,
    sessionModelLabel: String?,
    pickerBusy: Boolean,
    pickerNotice: String?,
    pickerPendingNotice: String?,
    canOpenPicker: Boolean,
    onRefresh: () -> Unit,
    onPickModel: (SessionModelOption) -> Unit,
    onPickReasoning: (String) -> Unit,
    onDismiss: () -> Unit,
    catalogLoading: Boolean = false,
    canChangePicker: Boolean = canOpenPicker && !pickerBusy,
    requestedProvider: String? = null,
    onCheckStatus: (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = (configuration.screenHeightDp * density.density).roundToInt()
    val dialogHeightDp = modelPickerDialogHeightPx(
        screenHeightPx,
        WindowInsets.systemBars.getTop(density),
        WindowInsets.systemBars.getBottom(density),
        WindowInsets.ime.getBottom(density)
    ).toFloat().div(density.density).dp
    var searchQuery by remember { mutableStateOf("") }
    var expandedProvider by remember { mutableStateOf<String?>(null) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    val searching = searchQuery.isNotBlank()
    val groups = remember(options) { SessionModelPickerGroupingPolicy.group(options) }
    val displayedGroups = remember(groups, searchQuery) { SessionModelPickerGroupingPolicy.filter(groups, searchQuery) }
    val selectedOption = remember(options, currentModelName) {
        SessionModelPickerGroupingPolicy.uniqueCurrentOption(options, currentModelName)
    }
    val readOnly = !canOpenPicker

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(min = 280.dp, max = 560.dp).height(dialogHeightDp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose chat model", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "This conversation · ${sessionModelLabel ?: "Not reported"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (catalogLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    if (!readOnly) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !catalogLoading && !pickerBusy,
                            modifier = Modifier.semantics { contentDescription = "Refresh model catalog" }
                        ) { Icon(Icons.Filled.Refresh, contentDescription = null) }
                    }
                }

                if (readOnly) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(PICKER_UNAVAILABLE_TITLE, style = MaterialTheme.typography.titleMedium)
                        Text(PICKER_UNAVAILABLE_BODY, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        enabled = !pickerBusy,
                        singleLine = true,
                        placeholder = { Text("Search models or providers") },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                            .semantics { contentDescription = "Search models or providers" }
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        displayedGroups.forEach { group ->
                            val expanded = searching || expandedProvider == group.providerSlug
                            item(key = "provider:${group.providerSlug}") {
                                ProviderRow(
                                    group = group,
                                    expanded = expanded,
                                    enabled = !pickerBusy && !searching,
                                    onClick = { expandedProvider = if (expanded) null else group.providerSlug }
                                )
                            }
                            if (expanded) {
                                items(group.models.size, key = { index -> "model:${group.providerSlug}:$index" }) { index ->
                                    val option = group.models[index]
                                    ModelRow(
                                        option = option,
                                        selected = selectedOption === option,
                                        enabled = canChangePicker,
                                        onClick = { onPickModel(option); onDismiss() }
                                    )
                                }
                            }
                        }
                        if (displayedGroups.isEmpty()) {
                            item(key = "empty") {
                                if (catalogLoading && options.isEmpty()) {
                                    CircularProgressIndicator(Modifier.padding(vertical = 18.dp))
                                } else {
                                    Text(
                                        if (searching) "No models or providers match your search." else "No configured models available.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                requestedProvider?.let {
                    Text("Requested provider: $it", style = MaterialTheme.typography.labelSmall)
                }
                pickerNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                pickerPendingNotice?.takeIf { it != pickerNotice }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (!readOnly && onCheckStatus != null) {
                    TextButton(onClick = onCheckStatus, enabled = !pickerBusy) { Text("Check status") }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                if (!readOnly) {
                    Row(
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Reasoning", style = MaterialTheme.typography.labelLarge)
                        Box {
                            TextButton(
                                onClick = { effortMenuExpanded = true },
                                enabled = canChangePicker,
                                modifier = Modifier.semantics {
                                    contentDescription = "Reasoning effort ${reasoningLabel(currentReasoningEffort)}"
                                }
                            ) {
                                Text(reasoningLabel(currentReasoningEffort), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                                    modifier = Modifier.size(20.dp).rotate(if (effortMenuExpanded) 90f else 0f))
                            }
                            DropdownMenu(expanded = effortMenuExpanded, onDismissRequest = { effortMenuExpanded = false }) {
                                SESSION_REASONING_LEVELS.forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(reasoningLabel(level)) },
                                        onClick = { effortMenuExpanded = false; onPickReasoning(level); onDismiss() },
                                        enabled = canChangePicker
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private fun reasoningLabel(value: String?): String = when (value?.trim()?.lowercase(Locale.ROOT)) {
    null, "" -> "Not reported"
    "none" -> "Off"
    else -> value.orEmpty().trim().lowercase(Locale.ROOT)
}

@Composable
private fun ProviderRow(group: PickerProviderGroup, expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(group.providerSlug, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${group.models.size}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse ${group.providerSlug}" else "Expand ${group.providerSlug}",
            modifier = Modifier.size(24.dp).rotate(if (expanded) 90f else 0f))
    }
}

@Composable
private fun ModelRow(option: SessionModelOption, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(option.model, modifier = Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
