package com.hermes.bot.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.bot.BuildConfig
import com.hermes.bot.R
import com.hermes.bot.data.local.ConnectionProfile
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.RelayOrbitIcon

/** Atelier presentation; all domain actions stay behind the existing callbacks. */
@Composable
fun SettingsScreen(
    currentTheme: AppThemePreference,
    onThemeChanged: (AppThemePreference) -> Unit,
    connectedOrigin: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onClearOfflineCache: () -> Unit = {},
    onClearLocalData: () -> Unit = {},
    onOpenDebugLogs: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = onOpenDebugLogs,
    profiles: List<ConnectionProfile> = emptyList(),
    activeProfileId: String? = null,
    onSwitchProfile: (String) -> Unit = {},
    onRemoveProfile: (String) -> Unit = {},
    onAddConnection: () -> Unit = {},
    onOpenBrain: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenHostLogs: () -> Unit = {},
    profileSwitcher: @Composable (() -> Unit)? = null,
    isHostConnected: Boolean = !connectedOrigin.isNullOrBlank(),
    onForgetSavedLogin: (String) -> Unit = {},
    savedLoginMessage: String? = null
) {
    SettingsAtelierTheme(currentTheme) {
        var presentation by remember(activeProfileId) { mutableStateOf(SettingsPresentationState()) }
        val activeProfile = profiles.firstOrNull { it.localId == activeProfileId }
        Column(
            modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                RelayOrbitIcon(size = 18.dp)
                Spacer(Modifier.width(7.dp))
                Text("HERMES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
                if (profileSwitcher != null) {
                    Spacer(Modifier.width(4.dp))
                    profileSwitcher()
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Make it yours.", modifier = Modifier.semantics { heading() }, fontFamily = SettingsEditorial, fontSize = 48.sp,
                lineHeight = 52.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("A little tuning. A better everyday.",
                Modifier.padding(top = 8.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                onClick = { presentation = presentation.openSheet(SettingsSheet.CONNECTIONS) },
                modifier = Modifier.semantics { role = Role.Button },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsLineIcon(R.drawable.ic_settings_server)
                    val stackStatus = LocalDensity.current.fontScale > 1.2f
                    Column(Modifier.weight(1f)) {
                        Text(activeProfile?.let(ProfileRemoveConfirmationPolicy::displayLabel) ?: "No saved connection",
                            style = MaterialTheme.typography.titleSmall)
                        if (stackStatus) SettingsConnectionStatus(isHostConnected)
                    }
                    if (!stackStatus) SettingsConnectionStatus(isHostConnected)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            SettingsGroupLabel("Your host")
            SettingsGroup {
                SettingsRow("Brain", "Models, auxiliary models & skills", R.drawable.ic_settings_brain, onClick = onOpenBrain)
                SettingsRowDivider()
                SettingsRow("Health", "Host, gateway & system", R.drawable.ic_settings_health, onClick = onOpenHealth)
                SettingsRowDivider()
                SettingsRow("Updates", "Hermes software", R.drawable.ic_settings_updates, onClick = onOpenUpdates)
                SettingsRowDivider()
                SettingsRow("Analytics", "Usage, tokens & costs", R.drawable.ic_settings_analytics, onClick = onOpenAnalytics)
                SettingsRowDivider()
                SettingsRow("Gateway logs", "Gateway first · other host files", R.drawable.ic_settings_logs, onClick = onOpenHostLogs)
            }
            Spacer(Modifier.height(24.dp))
            SettingsGroupLabel("On this device")
            SettingsGroup {
                SettingsRow("Appearance", null, R.drawable.ic_settings_appearance,
                    value = currentTheme.displayName,
                    onClick = { presentation = presentation.openSheet(SettingsSheet.APPEARANCE) })
                SettingsRowDivider()
                SettingsRow("Connections", null, R.drawable.ic_settings_connections,
                    value = "${profiles.size} saved",
                    onClick = { presentation = presentation.openSheet(SettingsSheet.CONNECTIONS) })
                SettingsRowDivider()
                SettingsRow("Data & privacy", null, R.drawable.ic_settings_privacy,
                    onClick = { presentation = presentation.openSheet(SettingsSheet.PRIVACY) })
                SettingsRowDivider()
                SettingsRow("App logs", "Events recorded on this device", R.drawable.ic_settings_diagnostics, onClick = onOpenDiagnostics)
            }
            Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FavoriteBorder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Built for your own space.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Hermes Mobile · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val sheet = presentation.sheet
        if (sheet != null) {
            SettingsDetailSheet(sheet, onDismiss = { presentation = presentation.dismiss() }) {
                when (sheet) {
                    SettingsSheet.APPEARANCE -> {
                        Text("Choose the atmosphere that feels right.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.selectableGroup()) {
                            AppThemePreference.entries.forEach { pref ->
                                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                                    .selectable(selected = pref == currentTheme, role = Role.RadioButton,
                                        onClick = { onThemeChanged(pref) }),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = pref == currentTheme, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(pref.displayName, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    SettingsSheet.CONNECTIONS -> {
                        Text("Manage saved Hermes connections. Switching connects to the selected host.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profiles.isEmpty()) {
                            Text("No saved connections. Add a connection to get started.")
                        }
                        savedLoginMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        profiles.forEach { profile ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(ProfileRemoveConfirmationPolicy.displayLabel(profile), style = MaterialTheme.typography.titleMedium)
                                if (profile.localId == activeProfileId) Text("Active", color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelSmall)
                                Text(profile.normalizedOrigin, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!profile.lastSuccessfulProtocol.isNullOrBlank()) Text("Protocol: ${profile.lastSuccessfulProtocol}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // Stack actions to keep long connection labels and large fonts usable.
                                if (profile.localId != activeProfileId) OutlinedButton(
                                    onClick = { onSwitchProfile(profile.localId) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) { Text("Switch") }
                                OutlinedButton(onClick = { onForgetSavedLogin(profile.localId) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Forget saved login") }
                                if (ProfileRemoveConfirmationPolicy.canRemove(profiles.size)) OutlinedButton(
                                    onClick = { presentation = presentation.requestRemoval(profile.localId, profiles.size) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) { Text("Remove") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        OutlinedButton(onClick = onAddConnection,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add connection")
                        }
                    }
                    SettingsSheet.PRIVACY -> {
                        Text("Choose what to remove from this device. Neither action deletes your Hermes sessions on the server.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { presentation = presentation.requestReset(SettingsResetChoice.CLEAR_OFFLINE_CACHE) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Clear offline cache") }
                        Text("Removes saved lists, rosters, and conversations stored on this device. Drafts, pending turns, sign-in, and your Hermes sessions stay.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { presentation = presentation.requestReset(SettingsResetChoice.CLEAR_ALL_LOCAL_DATA) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                        ) { Text("Clear all local app data") }
                        Text("Erases this device's copy: offline cache, drafts, pending turns, saved connections, and sign-in. Your Hermes sessions on the server are not deleted.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val pendingReset = presentation.pendingResetChoice
        if (pendingReset != null) {
            val copy = SettingsResetPolicy.copyFor(pendingReset)
            AlertDialog(
                onDismissRequest = { presentation = presentation.dismiss() },
                title = { Text(copy.title) }, text = { Text(copy.message, Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = {
                    TextButton(onClick = {
                        val (next, choice) = presentation.confirmReset()
                        presentation = next
                        when (choice) {
                            SettingsResetChoice.CLEAR_OFFLINE_CACHE -> onClearOfflineCache()
                            SettingsResetChoice.CLEAR_ALL_LOCAL_DATA -> onClearLocalData()
                            null -> Unit
                        }
                    }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(copy.confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { presentation = presentation.dismiss() },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(copy.cancelLabel) }
                }
            )
        }
        val pendingRemoveProfile = profiles.firstOrNull { it.localId == presentation.pendingRemoveProfileId }
        if (pendingRemoveProfile != null) {
            AlertDialog(
                onDismissRequest = { presentation = presentation.dismiss() },
                title = { Text(ProfileRemoveConfirmationPolicy.confirmationTitle()) },
                text = { Text(ProfileRemoveConfirmationPolicy.confirmationMessage(
                    ProfileRemoveConfirmationPolicy.displayLabel(pendingRemoveProfile)),
                    Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = {
                    TextButton(onClick = {
                        val (next, id) = presentation.confirmRemoval(profiles.map { it.localId })
                        presentation = next
                        id?.let(onRemoveProfile)
                    }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { presentation = presentation.dismiss() },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun SettingsGroupLabel(title: String) {
    Text(title.uppercase(), Modifier.padding(start = 4.dp, bottom = 10.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingsRow(
    title: String, description: String?, @DrawableRes icon: Int,
    value: String? = null, onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = Modifier.semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            SettingsLineIcon(icon)
            val stackValue = LocalDensity.current.fontScale > 1.2f
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                (description ?: value?.takeIf { stackValue })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (value != null && !stackValue) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsLineIcon(@DrawableRes resource: Int) {
    Icon(painterResource(resource), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(Modifier.padding(start = 51.dp, end = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetailSheet(sheet: SettingsSheet, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(when (sheet) {
                    SettingsSheet.APPEARANCE -> "Appearance"
                    SettingsSheet.CONNECTIONS -> "Connections"
                    SettingsSheet.PRIVACY -> "Data & privacy"
                }, Modifier.weight(1f), fontFamily = SettingsEditorial, fontSize = 30.sp, lineHeight = 36.sp)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Close settings detail")
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsConnectionStatus(connected: Boolean) {
    val color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(if (connected) "Connected" else "Not connected", color = color,
            style = MaterialTheme.typography.labelSmall)
    }
}
