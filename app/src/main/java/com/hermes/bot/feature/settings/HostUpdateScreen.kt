package com.hermes.bot.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.RelayOrbitIcon
import kotlinx.coroutines.awaitCancellation
import java.text.DateFormat
import java.util.Date

@Composable
fun HostUpdateRoute(
    provider: (() -> HostUpdateViewModel)?,
    currentTheme: AppThemePreference,
    connectionLabel: String,
    onBack: () -> Unit
) {
    val store = remember(provider) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    DisposableEffect(store) { onDispose { store.clear() } }
    BackHandler(onBack = onBack)
    if (provider == null) {
        SettingsAtelierTheme(currentTheme) { HostUpdateScreen(HostUpdateUiState(), connectionLabel, onBack, {}, {}, {}, {}) }
        return
    }
    val vm: HostUpdateViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory { initializer { provider() } })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.open()
            try { awaitCancellation() } finally { vm.leave() }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsAtelierTheme(currentTheme) {
        HostUpdateScreen(state, connectionLabel, onBack, vm::refresh, vm::requestUpdate, vm::dismissConfirmation, vm::confirmUpdate)
    }
}

@Composable
private fun HostUpdateScreen(
    state: HostUpdateUiState,
    connectionLabel: String,
    back: () -> Unit,
    refresh: () -> Unit,
    requestUpdate: () -> Unit,
    dismissConfirmation: () -> Unit,
    confirmUpdate: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 20.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings")
                    }
                    Spacer(Modifier.weight(1f))
                    RelayOrbitIcon(size = 18.dp)
                    Text(" HERMES", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
                }
                Spacer(Modifier.height(20.dp))
                Text("Host updates.", modifier = Modifier.semantics { heading() }, fontFamily = SettingsEditorial,
                    fontSize = 46.sp, lineHeight = 50.sp)
                Text("Keep this Hermes host current.", modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(connectionLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = refresh, enabled = !state.loading && state.phase != HostUpdatePhase.STARTING) {
                        Text("Check again")
                    }
                }
                if (state.loading) {
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Checking Hermes…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(statusTitle(state), style = MaterialTheme.typography.titleMedium)
                        state.check?.let { check ->
                            Text("Installed ${check.installedVersion}", style = MaterialTheme.typography.bodyMedium)
                            if (check.available && check.behind != null && check.behind > 0)
                                Text("${check.behind} changes available", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.checkedAt?.let { time ->
                            Text("Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.failure?.let { failure ->
                    Text(failure.message, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                val check = state.check
                if (check != null && check.available && check.changes.isNotEmpty()) {
                    Text("WHAT’S CHANGED", Modifier.padding(top = 24.dp, bottom = 10.dp).semantics { heading() },
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(horizontal = 17.dp, vertical = 8.dp)) {
                            check.changes.forEachIndexed { index, change ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text(change, Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (check != null && !check.canApply) {
                    Text("This installation is managed outside the app. Update Hermes on the host.",
                        Modifier.padding(top = 18.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (check != null && check.behind == null && !check.available) {
                    Text("The update source could not be checked. Try again later.",
                        Modifier.padding(top = 18.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.phase in setOf(HostUpdatePhase.STARTING, HostUpdatePhase.WAITING, HostUpdatePhase.UNKNOWN)) {
                    Text("The host may restart and briefly disconnect. This screen will check its status while open.",
                        Modifier.padding(top = 18.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.canStart || state.phase in setOf(HostUpdatePhase.STARTING, HostUpdatePhase.WAITING)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                Button(onClick = requestUpdate, enabled = state.canStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.phase == HostUpdatePhase.STARTING) "Starting update…" else if (state.phase == HostUpdatePhase.WAITING) "Waiting for host" else "Update Hermes")
                }
                if (state.canStart) Text("Updating may interrupt active work.", Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.confirmationOpen) AlertDialog(
        onDismissRequest = dismissConfirmation,
        title = { Text("Update Hermes?") },
        text = { Text("The host may restart. Active work could be interrupted. You can return after it reconnects.") },
        confirmButton = { TextButton(onClick = confirmUpdate) { Text("Update host") } },
        dismissButton = { TextButton(onClick = dismissConfirmation) { Text("Cancel") } }
    )
}

internal fun statusTitle(state: HostUpdateUiState): String = when (state.phase) {
    HostUpdatePhase.STARTING -> "Starting update"
    HostUpdatePhase.WAITING -> "Waiting for host"
    HostUpdatePhase.COMPLETE -> "Update completed"
    HostUpdatePhase.FAILED -> "Update failed"
    HostUpdatePhase.UNKNOWN -> "Update status unknown"
    HostUpdatePhase.IDLE -> when {
        state.loading -> "Checking for updates"
        state.check == null -> "Update information unavailable"
        state.check.installMethod != "git" && !state.check.canApply -> "Managed on host"
        state.check.behind == null && !state.check.available -> "Update check unavailable"
        state.check.available -> "Update available"
        else -> "You’re up to date"
    }
}
