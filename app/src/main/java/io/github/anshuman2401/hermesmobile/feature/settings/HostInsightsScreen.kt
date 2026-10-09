package io.github.anshuman2401.hermesmobile.feature.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.anshuman2401.hermesmobile.data.insights.*
import io.github.anshuman2401.hermesmobile.design.AppThemePreference
import kotlinx.coroutines.awaitCancellation
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A page-local store clears memory and cancels requests on route/scope disposal. */
@Composable
fun HostInsightsRoute(page: HostInsightsPage, provider: (() -> HostInsightsViewModel)?, currentTheme: AppThemePreference = AppThemePreference.SYSTEM, onBack: () -> Unit) {
    val store = remember(page, provider) { ViewModelStore() }
    val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
    DisposableEffect(store) { onDispose { store.clear() } }
    BackHandler(onBack = onBack)
    if (provider == null) {
        if (page != HostInsightsPage.LOGS) SettingsAtelierTheme(currentTheme) {
            HealthAnalyticsScreen(page, HostInsightsUiState(), onBack, {}, {}, preview = true)
        } else SettingsAtelierTheme(currentTheme) { Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            InsightsHeading(page.title, onBack)
            Text("Host information unavailable in preview mode.")
        } }
        return
    }
    val vm: HostInsightsViewModel = viewModel(viewModelStoreOwner = owner, factory = viewModelFactory { initializer { provider() } })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, page, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.open(page)
            try { awaitCancellation() } finally { vm.leave() }
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    if (page != HostInsightsPage.LOGS) SettingsAtelierTheme(currentTheme) {
        HealthAnalyticsScreen(page, state, onBack, vm::refresh, vm::period, vm::analyticsProfile)
    } else SettingsAtelierTheme(currentTheme) {
        HostInsightsScreen(state, onBack, vm::refresh, vm::file, vm::level, vm::search)
    }
}

@Composable
private fun HostInsightsScreen(state: HostInsightsUiState, back: () -> Unit, refresh: () -> Unit,
    file: (HostLogFile) -> Unit, level: (HostLogLevel) -> Unit, search: (String) -> Unit) {
    val context = LocalContext.current
    val visible = remember(state.logs, state.query) { HostLogProjection.visible(state.logs?.lines.orEmpty(), state.query) }
    var selectedLine by remember { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = selectedLine != null) { selectedLine = null }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate back") }
            Text("${state.file.label} logs", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = refresh, enabled = !state.loading) { Text("Refresh") }
        }
        Text("Host logs may contain private information. Copy only what you intend to share.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Source: ${state.file.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    HostLogFile.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { sourceMenu = false; file(choice) }) }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { levelMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("Level: ${state.level.label}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = levelMenu, onDismissRequest = { levelMenu = false }) {
                    HostLogLevel.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) }, onClick = { levelMenu = false; level(choice) }) }
                }
            }
        }
        OutlinedTextField(value = state.query, onValueChange = search, label = { Text("Search fetched lines") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.fetchedAt?.let { "Fetched ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))} · up to 200 lines · ${visible.size} shown" }
                ?: "No snapshot fetched", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { copyHostLogs(context, HostLogProjection.copy(visible)) }, enabled = visible.isNotEmpty()) { Text("Copy shown") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) { Text(failure.message); TextButton(onClick = refresh, enabled = !state.loading) { Text("Retry") } }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.logs != null && visible.isEmpty()) item { EmptyMessage(if (state.query.isBlank()) "No lines returned for this source and level." else "No matches in this fetched snapshot.") }
            items(visible, key = { it.index }) { line ->
                Column(Modifier.fillMaxWidth().clickable { selectedLine = line.text }.defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp)) {
                    Text(line.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
    selectedLine?.let { raw ->
        AlertDialog(onDismissRequest = { selectedLine = null }, title = { Text("Full log line") },
            text = { Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { SensitiveLogText(raw) } },
            confirmButton = { TextButton(onClick = { copyHostLogs(context, raw) }) { Text("Copy line") } },
            dismissButton = { TextButton(onClick = { selectedLine = null }) { Text("Close") } })
    }
}

@Composable
private fun InsightsHeading(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Navigate back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
@Composable private fun EmptyMessage(text: String) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
internal fun formatMetric(metric: Metric): String {
    val value = metric.value ?: return "Unavailable"
    return when (metric.unit) {
        "USD" -> String.format(Locale.US, "$%.4f", value)
        "%" -> String.format(Locale.US, "%.1f%%", value)
        "bytes" -> String.format(Locale.US, "%.1f GiB", value / (1024 * 1024 * 1024))
        "seconds" -> "${(value / 86400).toLong()}d ${(value / 3600).toLong() % 24}h ${(value / 60).toLong() % 60}m"
        else -> java.text.NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 0 }.format(value)
    }
}
private fun copyHostLogs(context: Context, text: String) {
    val clip = ClipData.newPlainText("Hermes host logs", text)
    markSensitive(clip)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun markSensitive(clip: ClipData) {
    clip.description.extras = (clip.description.extras ?: PersistableBundle()).apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
}

@Suppress("DEPRECATION")
@Composable
private fun SensitiveLogText(text: String) {
    val clipboard = LocalClipboard.current
    val legacy = LocalClipboardManager.current
    val sensitive = remember(clipboard) {
        object : androidx.compose.ui.platform.Clipboard by clipboard {
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                clipEntry?.clipData?.let(::markSensitive)
                clipboard.setClipEntry(clipEntry)
            }
        }
    }
    val sensitiveLegacy = remember(legacy) {
        object : androidx.compose.ui.platform.ClipboardManager by legacy {
            override fun setText(annotatedString: AnnotatedString) {
                val clip = ClipData.newPlainText("Hermes host logs", annotatedString.text)
                markSensitive(clip)
                legacy.setClip(ClipEntry(clip))
            }
            override fun setClip(clipEntry: ClipEntry?) {
                clipEntry?.clipData?.let(::markSensitive)
                legacy.setClip(clipEntry)
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides sensitive, LocalClipboardManager provides sensitiveLegacy) {
        SelectionContainer {
            Text(text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
        }
    }
}
