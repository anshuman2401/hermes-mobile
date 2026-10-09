package io.github.anshuman2401.hermesmobile.feature.brain

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import io.github.anshuman2401.hermesmobile.R
import io.github.anshuman2401.hermesmobile.design.AppThemePreference
import io.github.anshuman2401.hermesmobile.design.RelayOrbitIcon
import io.github.anshuman2401.hermesmobile.feature.settings.SettingsAtelierTheme
import io.github.anshuman2401.hermesmobile.feature.settings.SettingsEditorial
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult

@Composable
fun BrainScreen(
    onNavigateBack: () -> Unit,
    readSelectedProfile: suspend () -> SelectedContextReadResult,
    modelsViewModelProvider: (() -> BrainMainSettingsViewModel)? = null,
    auxiliaryViewModelProvider: (() -> BrainAuxiliaryModelsViewModel)? = null,
    skillsViewModelProvider: (() -> BrainSkillsViewModel)? = null,
    modifier: Modifier = Modifier,
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM
) {
    val store = remember(readSelectedProfile) { ViewModelStore() }
    val owner = remember(store) { brainNavigationOwner(store, readSelectedProfile) }
    DisposableEffect(store) { onDispose { store.clear() } }
    owner.updateProviders(modelsViewModelProvider, auxiliaryViewModelProvider, skillsViewModelProvider)
    val destination by owner.destination.collectAsStateWithLifecycle()
    val caption by owner.profileCaption.collectAsStateWithLifecycle()
    val back: () -> Unit = { if (owner.back() == BrainBackAction.SETTINGS) onNavigateBack() }
    // Composed after the shell handler, before the Skills detail handler.
    BackHandler(onBack = back)
    SettingsAtelierTheme(currentTheme) {
        when (destination) {
            BrainDestination.OVERVIEW -> BrainOverview(caption, owner::open, back, modifier)
            BrainDestination.MODELS -> owner.models()?.let {
                key(it) { BrainMainSettingsScreen(it, back, modifier) }
            } ?: BrainUnavailable("Models", back, modifier)
            BrainDestination.AUXILIARY_MODELS -> owner.auxiliary()?.let {
                key(it) { BrainAuxiliaryModelsScreen(it, back, modifier) }
            } ?: BrainUnavailable("Auxiliary Models", back, modifier)
            BrainDestination.SKILLS -> owner.skills()?.let {
                key(it) {
                    BrainSkillsScreen(it, back, onOpenSkill = { _, _ -> }, modifier = modifier)
                }
            } ?: BrainUnavailable("Skills", back, modifier)
        }
    }
}

@Composable
private fun BrainOverview(caption: String, open: (BrainDestination) -> Unit, back: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        BrainHeading("Brain", back)
        Text(caption, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Text("Models, supporting tasks, and skills for this profile.",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Profile settings", Modifier.padding(start = 4.dp, bottom = 10.dp).semantics { heading() },
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth()) {
                BrainEntry("Models", "Main model & reasoning defaults", R.drawable.ic_settings_brain) { open(BrainDestination.MODELS) }
                BrainEntryDivider()
                BrainEntry("Auxiliary Models", "Models for supporting tasks", R.drawable.ic_settings_connections) { open(BrainDestination.AUXILIARY_MODELS) }
                BrainEntryDivider()
                BrainEntry("Skills", "Installed skills & their content", R.drawable.ic_settings_logs) { open(BrainDestination.SKILLS) }
            }
        }
    }
}

@Composable
private fun BrainEntry(title: String, description: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrainEntryDivider() {
    HorizontalDivider(Modifier.padding(start = 51.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun BrainUnavailable(title: String, back: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrainHeading(title, back)
        Text("Brain page unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrainHeading(title: String, back: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
            }
            Spacer(Modifier.weight(1f))
            RelayOrbitIcon(size = 18.dp)
            Spacer(Modifier.width(7.dp))
            Text("HERMES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
        }
        Spacer(Modifier.height(20.dp))
        Text(title, Modifier.semantics { heading() }, fontFamily = SettingsEditorial, fontSize = 48.sp,
            lineHeight = 52.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
