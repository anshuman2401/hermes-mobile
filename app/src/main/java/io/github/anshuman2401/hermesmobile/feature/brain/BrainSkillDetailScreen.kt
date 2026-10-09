package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.anshuman2401.hermesmobile.feature.brain.components.SkillMarkdownDocument

@Composable
fun BrainSkillDetailScreen(
    viewModel: BrainSkillDetailViewModel,
    onNavigateBack: () -> Unit,
    onReloadSkills: () -> Unit = onNavigateBack,
    onReopenFromSkills: () -> Unit = onNavigateBack,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val identity = uiState.identity
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(identity.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Profile: ${identity.selectedProfile}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when (val state = uiState.state) {
                BrainSkillDetailState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                is BrainSkillDetailState.Empty -> Text(
                    "This skill document is empty.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is BrainSkillDetailState.Document -> SkillMarkdownDocument(
                    document = state.document,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                is BrainSkillDetailState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    if (state.canRetry) TextButton(onClick = viewModel::retry) { Text("Retry") }
                }
                is BrainSkillDetailState.Unavailable -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onNavigateBack) { Text("Back to Skills") }
                    TextButton(onClick = onReloadSkills) { Text("Reload Skills") }
                    TextButton(onClick = onReopenFromSkills) { Text("Reopen from Skills") }
                }
            }
        }
    }
}
