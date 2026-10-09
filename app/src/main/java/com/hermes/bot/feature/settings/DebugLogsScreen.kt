package com.hermes.bot.feature.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.bot.data.diagnostics.DiagnosticJsonl
import com.hermes.bot.data.diagnostics.DiagnosticSeverity
import com.hermes.bot.design.AppThemePreference
import kotlinx.coroutines.delay

@Composable
fun DebugLogsScreen(
    viewModel: DebugLogsViewModel,
    onNavigateBack: () -> Unit,
    profileSwitcher: @Composable (() -> Unit)? = null,
    currentTheme: AppThemePreference = AppThemePreference.SYSTEM
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showCurrent by remember { mutableStateOf(false) }
    var severity by remember { mutableStateOf<DiagnosticSeverity?>(null) }
    var expandedRecord by remember { mutableIntStateOf(-1) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(isCopied) { if (isCopied) { delay(2000); isCopied = false } }
    BackHandler(enabled = showReport) { showReport = false }
    val visible = remember(state.savedEvents, severity) {
        DiagnosticsScreenPolicy.savedVisible(state.savedEvents, severity)
    }

    SettingsAtelierTheme(currentTheme) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (showReport) showReport = false else onNavigateBack() }, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                }
                Text(if (showReport) "App report" else "App logs", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (profileSwitcher != null) profileSwitcher()
                if (!showReport) TextButton(onClick = viewModel::refresh, enabled = !state.loading) { Text("Refresh") }
            }
            if (showReport) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(state.copyPayload))
                            Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                            isCopied = true
                        },
                        enabled = !state.loading && state.canShare,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Icon(if (isCopied) Icons.Filled.Check else Icons.Filled.Build,
                            contentDescription = if (isCopied) "Diagnostics copied to clipboard" else "Copy diagnostics",
                            modifier = Modifier.size(18.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, state.copyPayload)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share diagnostics"))
                        },
                        enabled = !state.loading && state.canShare,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                    ) { Text("Share") }
                }
                Text("Copy and Share send exactly the report shown below.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SelectionContainer {
                        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(state.reportPreview.ifBlank { DiagnosticsScreenPolicy.EMPTY_REPORT_MESSAGE },
                                Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                Text("Saved app events stay on this device. Current session events reset after restart.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !showCurrent, onClick = { showCurrent = false }, label = { Text("Saved events") })
                    FilterChip(selected = showCurrent, onClick = { showCurrent = true }, label = { Text("Current session") })
                    TextButton(onClick = { showReport = true }) { Text("View report") }
                }
                if (!showCurrent) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = severity == null, onClick = { severity = null; expandedRecord = -1 }, label = { Text("All") })
                        listOf(DiagnosticSeverity.ERROR, DiagnosticSeverity.WARN, DiagnosticSeverity.INFO, DiagnosticSeverity.DEBUG).forEach { choice ->
                            FilterChip(selected = severity == choice, onClick = { severity = choice; expandedRecord = -1 },
                                label = { Text(choice.name.lowercase().replaceFirstChar(Char::titlecase)) })
                        }
                    }
                    Text("${visible.size} shown · newest first" + if (state.savedEventsBounded) " · older records omitted" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (state.malformedRecords > 0 && state.message?.contains("malformed") != true)
                    Text("${state.malformedRecords} invalid app log record(s) skipped.", style = MaterialTheme.typography.bodySmall)
                if (state.unavailableFiles > 0) Text("${state.unavailableFiles} app log file(s) unavailable.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (showCurrent) {
                        if (state.diagnosticEvents.isEmpty() && !state.loading) item { Text("No current session events recorded yet.") }
                        itemsIndexed(state.diagnosticEvents) { _, event ->
                            Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp)) {
                                Text(event.timestamp.toString(), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(event.formatSummary(), style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider()
                        }
                    } else {
                        if (visible.isEmpty() && !state.loading) item {
                            Text(if (severity == null) "No saved app events recorded yet." else "No saved ${severity?.name?.lowercase()} events.")
                        }
                        itemsIndexed(visible) { index, record ->
                            SavedEventRow(record, expanded = expandedRecord == index,
                                onClick = { expandedRecord = if (expandedRecord == index) -1 else index })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedEventRow(record: DiagnosticJsonl.Record, expanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.severity, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(record.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${record.component} · ${record.message}", style = MaterialTheme.typography.bodyMedium)
        if (expanded) {
            Text(record.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            record.details.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall) }
            record.trace?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
    HorizontalDivider()
}
