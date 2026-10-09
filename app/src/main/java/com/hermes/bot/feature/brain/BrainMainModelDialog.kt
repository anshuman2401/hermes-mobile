package com.hermes.bot.feature.brain

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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import com.hermes.bot.data.brain.BrainModelProvider
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

internal val BRAIN_REASONING_EFFORTS = listOf(
    "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"
)

data class BrainReasoningPolicy(
    val enabled: Boolean,
    val options: List<String>,
    val explanation: String? = null
)

/** Desktop-compatible reasoning control policy for the currently drafted model. */
internal fun brainReasoningPolicy(
    providers: List<BrainModelProvider>,
    provider: String?,
    model: String?,
    savedEffort: String
): BrainReasoningPolicy {
    val capability = providers
        .firstOrNull { it.slug == provider }
        ?.capabilities
        ?.get(model)
    if (capability?.reasoning == false) {
        return BrainReasoningPolicy(
            enabled = false,
            options = emptyList(),
            explanation = "Reasoning is not available for this model."
        )
    }
    val options = if (capability?.canDisableReasoning == false) {
        BRAIN_REASONING_EFFORTS.filterNot { it == "none" }
    } else {
        BRAIN_REASONING_EFFORTS
    }
    // An unknown saved effort remains displayable; it is not silently rewritten.
    return BrainReasoningPolicy(enabled = true, options = options)
}

internal fun brainReasoningLabel(value: String): String =
    if (value.trim().equals("none", ignoreCase = true)) "Off" else value

data class BrainModelProviderGroup(
    val provider: BrainModelProvider,
    val models: List<String>
)

/** Provider-slug/model-ID search, preserving host provider and model order. */
internal fun filterBrainModelProviders(
    providers: List<BrainModelProvider>,
    query: String
): List<BrainModelProviderGroup> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return providers.map { BrainModelProviderGroup(it, it.models) }
    return providers.mapNotNull { provider ->
        val providerMatches = provider.slug.lowercase(Locale.ROOT).contains(normalized)
        val models = if (providerMatches) provider.models else provider.models.filter {
            it.lowercase(Locale.ROOT).contains(normalized)
        }
        if (models.isEmpty()) null else BrainModelProviderGroup(provider, models)
    }
}

data class BrainExpansionState(
    val expandedProvider: String? = null,
    val beforeSearch: String? = null,
    val searching: Boolean = false
)

internal object BrainDialogExpansionPolicy {
    fun beginSearch(state: BrainExpansionState): BrainExpansionState =
        if (state.searching) state else state.copy(beforeSearch = state.expandedProvider, searching = true)

    fun clearSearch(state: BrainExpansionState): BrainExpansionState =
        if (!state.searching) state else BrainExpansionState(
            expandedProvider = state.beforeSearch,
            beforeSearch = null,
            searching = false
        )

    fun toggle(state: BrainExpansionState, provider: String): BrainExpansionState =
        if (state.searching) state else state.copy(
            expandedProvider = if (state.expandedProvider == provider) null else provider
        )
}

/** Normal height is 60% of the full display; only insets/IME can clamp it. */
internal fun brainDialogHeightPx(
    screenHeightPx: Int,
    topInsetPx: Int,
    bottomInsetPx: Int,
    imeBottomPx: Int
): Int {
    val desired = (screenHeightPx * 0.60f).roundToInt()
    val available = max(0, screenHeightPx - topInsetPx - max(bottomInsetPx, imeBottomPx))
    return min(desired, available)
}

@Composable
fun BrainMainModelDialog(
    state: BrainMainModelDialogState,
    onModelSelected: (String, String) -> Unit,
    onReasoningSelected: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onConfirmWarning: () -> Unit,
    onDeclineWarning: () -> Unit,
    reloadRequired: Boolean,
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
    var searchQuery by remember(state.snapshot.snapshotId) { mutableStateOf("") }
    var expansion by remember(state.snapshot.snapshotId) { mutableStateOf(BrainExpansionState()) }
    var effortMenuExpanded by remember(state.snapshot.snapshotId) { mutableStateOf(false) }
    val searching = searchQuery.isNotBlank()
    val displayedGroups = filterBrainModelProviders(state.snapshot.providers, searchQuery)
    val reasoning = brainReasoningPolicy(
        providers = state.snapshot.providers,
        provider = state.draft.provider,
        model = state.draft.model,
        savedEffort = state.snapshot.reasoningEffort
    )
    val savedMain = state.snapshot.main
    val savedMainInCatalog = savedMain != null && state.snapshot.providers.any { provider ->
        provider.slug == savedMain.provider && savedMain.model in provider.models
    }
    val showSavedOnly = savedMain != null && !savedMainInCatalog &&
        (searchQuery.isBlank() || savedMain.provider.contains(searchQuery, true) || savedMain.model.contains(searchQuery, true))

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
                Text("Choose main model", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { next ->
                        if (!searching && next.isNotBlank()) {
                            expansion = BrainDialogExpansionPolicy.beginSearch(expansion)
                        } else if (searching && next.isBlank()) {
                            expansion = BrainDialogExpansionPolicy.clearSearch(expansion)
                        }
                        searchQuery = next
                    },
                    enabled = !state.saving && !reloadRequired,
                    singleLine = true,
                    placeholder = { Text("Search models or providers") },
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
                    if (showSavedOnly && savedMain != null) {
                        item(key = "saved-current") {
                            SavedOnlyModelRow(savedMain.provider, savedMain.model)
                        }
                    }
                    displayedGroups.forEach { group ->
                        val expanded = searching || expansion.expandedProvider == group.provider.slug
                        item(key = "provider:${group.provider.slug}") {
                            ProviderHeader(
                                group = group,
                                expanded = expanded,
                                enabled = !state.saving && !reloadRequired && !searching,
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
                                ModelChoiceRow(
                                    provider = group.provider,
                                    model = model,
                                    selected = state.draft.provider == group.provider.slug && state.draft.model == model,
                                    enabled = !state.saving && !reloadRequired && group.provider.authenticated,
                                    onClick = { onModelSelected(group.provider.slug, model) }
                                )
                            }
                        }
                    }
                    if (displayedGroups.isEmpty() && !showSavedOnly) {
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
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reasoning", style = MaterialTheme.typography.labelLarge)
                        reasoning.explanation?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { effortMenuExpanded = true },
                            enabled = !state.saving && !reloadRequired && reasoning.enabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Reasoning effort ${brainReasoningLabel(state.draft.reasoningEffort)}"
                            }
                        ) {
                            Text(
                                brainReasoningLabel(state.draft.reasoningEffort),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (effortMenuExpanded) 90f else 0f)
                            )
                        }
                        DropdownMenu(
                            expanded = effortMenuExpanded,
                            onDismissRequest = { effortMenuExpanded = false }
                        ) {
                            reasoning.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(brainReasoningLabel(option)) },
                                    onClick = {
                                        effortMenuExpanded = false
                                        onReasoningSelected(option)
                                    },
                                    enabled = !state.saving && !reloadRequired
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel") }
                    Button(onClick = onApply, enabled = !state.saving && !reloadRequired) {
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

    state.confirmationWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = onDeclineWarning,
            title = { Text("Confirm model change") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = onConfirmWarning, enabled = !state.saving) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = onDeclineWarning, enabled = !state.saving) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProviderHeader(
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
            if (group.provider.authenticated) "${group.models.size}" else "Unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse ${group.provider.slug}" else "Expand ${group.provider.slug}",
            modifier = Modifier
                .size(24.dp)
                .rotate(if (expanded) 90f else 0f)
        )
    }
}

@Composable
private fun ModelChoiceRow(
    provider: BrainModelProvider,
    model: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .padding(start = 12.dp, end = 4.dp),
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SavedOnlyModelRow(provider: String, model: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = true, onClick = null, enabled = false)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                "$provider/$model",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Saved choice; unavailable in the current catalogue",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
