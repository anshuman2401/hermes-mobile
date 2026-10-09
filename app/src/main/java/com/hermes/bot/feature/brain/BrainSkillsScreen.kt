package com.hermes.bot.feature.brain

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.hermes.bot.data.brain.BrainInstalledSkill

/** Hidden Phase A Skills page; public Brain navigation is a later scope. */
@Composable
fun BrainSkillsScreen(
    viewModel: BrainSkillsViewModel,
    onNavigateBack: () -> Unit,
    onOpenSkill: (name: String, snapshotId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val detailStore = remember { ViewModelStore() }
    var detailIdentity by remember { mutableStateOf<BrainSkillDetailIdentity?>(null) }
    DisposableEffect(Unit) {
        onDispose { detailStore.clear() }
    }

    val openIdentity = detailIdentity
    if (openIdentity != null && viewModel.hasDetailViewModelFactory()) {
        val detailViewModel = remember(openIdentity) {
            ViewModelProvider(
                detailStore,
                BrainSkillDetailFactory {
                    viewModel.createDetailViewModel(
                        openIdentity.snapshotId,
                        openIdentity.name,
                        openIdentity.selectedProfile
                    ) ?: error("Skill detail ViewModel unavailable")
                }
            ).get(
                "skill-detail-${openIdentity.snapshotId}-${openIdentity.name}",
                BrainSkillDetailViewModel::class.java
            )
        }
        val returnToSkills = brainDetailReturn(detailStore) { detailIdentity = null }
        BackHandler(onBack = returnToSkills)
        BrainSkillDetailScreen(
            viewModel = detailViewModel,
            onNavigateBack = returnToSkills,
            onReloadSkills = {
                detailStore.clear()
                detailIdentity = null
                viewModel.load()
            },
            onReopenFromSkills = returnToSkills,
            modifier = modifier
        )
        return
    }
    val titleProfile = uiState.selectedProfile ?: "Not selected"

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
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
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("Skills", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Profile: $titleProfile",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when (val loadState = uiState.loadState) {
                BrainSkillsLoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is BrainSkillsLoadState.Error -> {
                    Text(loadState.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::load) { Text("Retry") }
                }
                is BrainSkillsLoadState.Ready -> {
                    if (uiState.feedback != null) {
                        Text(
                            text = uiState.feedback!!,
                            color = if (uiState.reloadRequired) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (uiState.reloadRequired) {
                        TextButton(onClick = viewModel::load) { Text("Retry") }
                    }
                    if (loadState.snapshot.skills.isEmpty()) {
                        Text("No installed skills")
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(loadState.snapshot.skills, key = { it.name }) { skill ->
                                BrainSkillRow(
                                    skill = skill,
                                    busy = uiState.busySkill == skill.name,
                                    controlsEnabled = !uiState.reloadRequired && uiState.busySkill == null,
                                    onOpen = {
                                        onOpenSkill(skill.name, loadState.snapshot.snapshotId)
                                        if (viewModel.hasDetailViewModelFactory()) {
                                            detailStore.clear()
                                            detailIdentity = BrainSkillDetailIdentity(
                                                snapshotId = loadState.snapshot.snapshotId,
                                                name = skill.name,
                                                selectedProfile = loadState.snapshot.selectedProfile
                                            )
                                        }
                                    },
                                    onToggle = { enabled -> viewModel.toggle(skill.name, enabled) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrainSkillRow(
    skill: BrainInstalledSkill,
    busy: Boolean,
    controlsEnabled: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = "Open skill",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = controlsEnabled
            )
        }
    }
}

private class BrainSkillDetailFactory(
    private val creator: () -> BrainSkillDetailViewModel
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}
