package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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

/** Hidden Models page; navigation assembly is intentionally deferred. */
@Composable
fun BrainMainSettingsScreen(
    viewModel: BrainMainSettingsViewModel,
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Models", style = MaterialTheme.typography.headlineSmall)
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
                BrainMainSettingsLoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is BrainMainSettingsLoadState.Error -> {
                    Text(loadState.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) {
                        Text(if (uiState.reloadRequired) "Reload settings" else "Retry")
                    }
                }
                is BrainMainSettingsLoadState.Ready -> {
                    val snapshot = loadState.snapshot
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Main model", style = MaterialTheme.typography.titleMedium)
                            Text(
                                snapshot.main?.let { "${it.provider}/${it.model}" } ?: "Not configured",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Reasoning: ${brainReasoningLabel(snapshot.reasoningEffort)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (snapshot.providers.isEmpty()) {
                                Text(
                                    "No configured models are available.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = viewModel::openModelDialog,
                                enabled = !uiState.reloadRequired,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("Choose model")
                            }
                        }
                    }
                    if (uiState.reloadRequired) {
                        Text(
                            "Settings may have saved; they were not confirmed. Reload before retrying.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::load) { Text("Reload settings") }
                    }
                }
            }
        }
    }

    uiState.dialog?.let { dialog ->
        BrainMainModelDialog(
            state = dialog,
            onModelSelected = viewModel::selectModel,
            onReasoningSelected = viewModel::selectReasoning,
            onApply = viewModel::apply,
            onCancel = viewModel::dismissModelDialog,
            onConfirmWarning = viewModel::confirmWarning,
            onDeclineWarning = viewModel::declineWarning,
            reloadRequired = uiState.reloadRequired,
            onReload = viewModel::load
        )
    }
}
