package com.hermes.bot.feature.brain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermes.bot.data.brain.BrainAuxiliaryDraft
import com.hermes.bot.data.brain.BrainAuxiliaryTask
import com.hermes.bot.data.brain.BrainAuxiliaryTaskRow
import com.hermes.bot.data.brain.BrainModelProvider
import kotlin.math.roundToInt

@Composable
fun BrainAuxiliaryModelDialog(
    state: BrainAuxiliaryModelDialogState,
    reloadRequired: Boolean,
    onModelSelected: (String, String) -> Unit,
    onReturnToAuto: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onConfirmWarning: () -> Unit,
    onDeclineWarning: () -> Unit,
    onReload: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenHeightPx = (configuration.screenHeightDp * density.density).roundToInt()
    val dialogHeightDp = brainDialogHeightPx(
        screenHeightPx = screenHeightPx,
        topInsetPx = WindowInsets.systemBars.getTop(density),
        bottomInsetPx = WindowInsets.systemBars.getBottom(density),
        imeBottomPx = WindowInsets.ime.getBottom(density)
    ).toFloat().div(density.density).dp

    var searchQuery by remember(state.snapshot.snapshotId, state.task) { mutableStateOf("") }
    var expansion by remember(state.snapshot.snapshotId, state.task) { mutableStateOf(BrainExpansionState()) }
    val searching = searchQuery.isNotBlank()
    val displayedGroups = filterBrainModelProviders(state.snapshot.providers, searchQuery)
    val current = state.snapshot.tasks.firstOrNull { it.task == state.task }?.assignment
    val currentPairOffered = current != null && state.snapshot.providers.any { provider ->
        provider.slug == current.provider && current.model in provider.models
    }
    val showCurrentOnly = current != null &&
        !(current.provider.equals("auto", ignoreCase = true) && current.model.isEmpty()) &&
        (current.model.isNotEmpty() || current.provider.isNotBlank()) &&
        (!currentPairOffered || current.provider.equals("auto", ignoreCase = true)) &&
        (searchQuery.isBlank() || current.provider.contains(searchQuery, true) || current.model.contains(searchQuery, true))
    val controlsEnabled = !state.saving && state.confirmationWarning == null && !reloadRequired

    Dialog(
        onDismissRequest = { if (!state.saving) onCancel() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !state.saving,
            dismissOnClickOutside = !state.saving
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(min = 280.dp, max = 560.dp)
                .height(dialogHeightDp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    "Choose ${state.task.displayName} model",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    state.task.purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { next ->
                        if (!searching && next.isNotBlank()) {
                            expansion = BrainDialogExpansionPolicy.beginSearch(expansion)
                        } else if (searching && next.isBlank()) {
                            expansion = BrainDialogExpansionPolicy.clearSearch(expansion)
                        }
                        searchQuery = next
                    },
                    enabled = controlsEnabled,
                    singleLine = true,
                    placeholder = { Text("Search providers or models") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (showCurrentOnly && current != null) {
                        item(key = "current-only") {
                            CurrentOnlyRow(current.provider, current.model)
                        }
                    }
                    displayedGroups.forEach { group ->
                        val expanded = searching || expansion.expandedProvider == group.provider.slug
                        item(key = "provider:${group.provider.slug}") {
                            AuxiliaryProviderHeader(
                                group = group,
                                expanded = expanded,
                                enabled = controlsEnabled && !searching,
                                onClick = {
                                    expansion = BrainDialogExpansionPolicy.toggle(
                                        expansion,
                                        group.provider.slug
                                    )
                                }
                            )
                        }
                        if (expanded) {
                            items(
                                items = group.models,
                                key = { model -> "model:${group.provider.slug}:$model" }
                            ) { model ->
                                val canSelect = controlsEnabled &&
                                    state.snapshot.catalogAvailable &&
                                    group.provider.authenticated &&
                                    !group.provider.requiresEndpointPassthrough
                                AuxiliaryModelChoiceRow(
                                    provider = group.provider,
                                    model = model,
                                    selected = state.draft is BrainAuxiliaryDraft.Concrete &&
                                        state.draft.provider == group.provider.slug &&
                                        state.draft.model == model,
                                    saved = current?.provider == group.provider.slug && current?.model == model,
                                    enabled = canSelect,
                                    disabledReason = when {
                                        !group.provider.authenticated -> "Sign in to this provider"
                                        group.provider.requiresEndpointPassthrough -> "This endpoint-backed model is not supported here"
                                        !state.snapshot.catalogAvailable -> "Model catalog unavailable"
                                        else -> null
                                    },
                                    onClick = { onModelSelected(group.provider.slug, model) }
                                )
                            }
                        }
                    }
                    if (displayedGroups.isEmpty() && !showCurrentOnly) {
                        item(key = "empty-search") {
                            Text(
                                if (searching) "No models or providers match your search." else "No configured models.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 18.dp)
                            )
                        }
                    }
                }

                if (!state.snapshot.catalogAvailable) {
                    Text(
                        "The model catalog is unavailable. Existing assignments remain visible; choose Auto or reload settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (reloadRequired) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Settings may have saved; they were not confirmed. Reload before retrying.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onReload) { Text("Reload settings") }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onReturnToAuto,
                        enabled = controlsEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "Return ${state.task.displayName} to Auto"
                        }
                    ) {
                        Text("Return to Auto")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
                        Button(onClick = onApply, enabled = controlsEnabled) {
                            if (state.saving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Apply")
                            }
                        }
                    }
                }
            }
        }
    }

    state.confirmationWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = onDeclineWarning,
            title = { Text("Confirm model change") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = onConfirmWarning, enabled = !state.saving && !reloadRequired) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = onDeclineWarning, enabled = !state.saving) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AuxiliaryProviderHeader(
    group: BrainModelProviderGroup,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            group.provider.slug,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            when {
                !group.provider.authenticated -> "Unavailable"
                group.provider.requiresEndpointPassthrough -> "Endpoint unsupported"
                else -> group.models.size.toString()
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .rotate(if (expanded) 90f else 0f)
        )
    }
}

@Composable
private fun AuxiliaryModelChoiceRow(
    provider: BrainModelProvider,
    model: String,
    selected: Boolean,
    saved: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.RadioButton
            )
            .padding(start = 12.dp, end = 4.dp),
    ) {
        Row(
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(
                model,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (saved) {
                Text(
                    "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 60.dp, bottom = 2.dp)
                )
            }
        }
        disabledReason?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 60.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun CurrentOnlyRow(provider: String, model: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Current saved value", style = MaterialTheme.typography.labelLarge)
            Text(
                when {
                    provider.isBlank() -> model
                    model.isBlank() -> "$provider (provider default)"
                    else -> "$provider/$model"
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "Current only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
