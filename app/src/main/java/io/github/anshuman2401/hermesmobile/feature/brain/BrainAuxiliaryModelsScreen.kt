package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.anshuman2401.hermesmobile.data.brain.BrainAuxiliaryTaskRow

/** Hidden Auxiliary Models page; navigation assembly is intentionally deferred. */
@Composable
fun BrainAuxiliaryModelsScreen(
    viewModel: BrainAuxiliaryModelsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.feedback) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(feedback)
        viewModel.clearFeedback()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Column {
                    Text("Auxiliary Models", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Profile: ${uiState.selectedProfile ?: "Not selected"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (val loadState = uiState.loadState) {
                BrainAuxiliaryModelsLoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is BrainAuxiliaryModelsLoadState.Error -> {
                    Text(loadState.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("Reload settings") }
                }
                is BrainAuxiliaryModelsLoadState.Ready -> {
                    if (loadState.partial) {
                        Text(
                            "Some auxiliary assignments are unavailable. Existing values are shown as returned by Hermes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!loadState.snapshot.catalogAvailable) {
                        Text(
                            "The model catalog is unavailable. Existing assignments remain visible; new concrete choices are disabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.reloadRequired) {
                        Text(
                            uiState.feedback
                                ?: "Settings may have saved; they were not confirmed. Reload before retrying.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = viewModel::load) { Text("Reload settings") }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(loadState.snapshot.tasks, key = { it.task.wireKey }) { row ->
                            AuxiliaryTaskCard(
                                row = row,
                                enabled = !uiState.reloadRequired && row.assignment != null,
                                onClick = { viewModel.openTaskDialog(row.task) }
                            )
                        }
                    }
                }
            }
        }
    }

    uiState.dialog?.let { dialog ->
        BrainAuxiliaryModelDialog(
            state = dialog,
            reloadRequired = uiState.reloadRequired,
            onModelSelected = viewModel::selectModel,
            onReturnToAuto = viewModel::returnToAuto,
            onApply = viewModel::apply,
            onCancel = viewModel::dismissModelDialog,
            onConfirmWarning = viewModel::confirmWarning,
            onDeclineWarning = viewModel::declineWarning,
            onReload = viewModel::load
        )
    }
}

@Composable
private fun AuxiliaryTaskCard(
    row: BrainAuxiliaryTaskRow,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    row.task.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    row.currentDisplay() ?: "Unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.assignment == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                row.task.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.assignment == null) {
                Text(
                    "This task is missing from the host response.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
