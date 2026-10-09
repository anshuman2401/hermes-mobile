package com.hermes.bot.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.bot.data.diagnostics.DiagnosticJsonl
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.DiagnosticSeverity
import com.hermes.bot.data.diagnostics.PrivateDiagnosticLogStore
import com.hermes.bot.data.diagnostics.PrivateLogResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DebugLogsUiState(
    val selectedSeverity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    val loading: Boolean = false,
    val lines: List<String> = emptyList(),
    val message: String? = null,
    val bounded: Boolean = false,
    val canShare: Boolean = false,
    val canClear: Boolean = false,
    val diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    val savedEvents: List<DiagnosticJsonl.Record> = emptyList(),
    val savedEventsBounded: Boolean = false,
    val unavailableFiles: Int = 0,
    val malformedRecords: Int = 0,
    val groupedEvents: Map<DiagnosticsScreenPolicy.EventCategory, List<DiagnosticEvent>> = emptyMap(),
    val reportPreview: String = "",
    val copyPayload: String = "",
    val isReportEmpty: Boolean = true
)

/** Combines persisted diagnostics and current-process context into one shareable report. */
class DebugLogsViewModel(
    private val repository: PrivateDiagnosticLogStore,
    private val diagnosticRing: DiagnosticRing? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _uiState = MutableStateFlow(DebugLogsUiState())
    val uiState: StateFlow<DebugLogsUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    @Volatile private var refreshGeneration = 0L

    init {
        refresh()
    }

    fun select(severity: DiagnosticSeverity) {
        _uiState.value = _uiState.value.copy(selectedSeverity = severity, message = null)
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, message = null)
        refreshJob?.cancel()
        val selected = _uiState.value.selectedSeverity
        val generation = ++refreshGeneration
        refreshJob = viewModelScope.launch(ioDispatcher) {
            if (generation != refreshGeneration) return@launch
            val results = DiagnosticSeverity.entries.associateWith { repository.read(it) }
            if (generation != refreshGeneration) return@launch
            val records = results.values.filterIsInstance<PrivateLogResult.Success<com.hermes.bot.data.diagnostics.PrivateLogRead>>()
                .flatMap { it.value.linesNewestFirst }.mapNotNull(DiagnosticJsonl::parseComplete)
                .sortedByDescending { it.timestamp }
            val skipped = results.values.filterIsInstance<PrivateLogResult.Success<com.hermes.bot.data.diagnostics.PrivateLogRead>>()
                .sumOf { it.value.malformedCount }
            val unavailable = results.values.count { it !is PrivateLogResult.Success }
            val presentation = DiagnosticsScreenPolicy.reduce(diagnosticRing)
            val report = buildString {
                appendLine("Hermes Mobile · Error report")
                appendLine("Messages, credentials, user content and source paths are excluded.")
                appendLine("Crash capture is best effort; native crashes, ANRs and OS kills are not captured.")
                appendLine()
                appendLine("ERRORS AND WARNINGS · newest first")
                val failures = records.filter { it.severity == "ERROR" || it.severity == "WARN" }
                if (failures.isEmpty()) appendLine("No stored errors or warnings.")
                var retainedFailures = 0
                var remaining = 60_000
                for (record in failures.take(40)) {
                    val detail = record.structuredDisplay()
                    if (detail.length > remaining) break
                    appendLine("[${record.severity}] $detail"); appendLine()
                    remaining -= detail.length + 2
                    retainedFailures++
                }
                if (failures.size > retainedFailures) appendLine("Older errors/warnings omitted (${failures.size - retainedFailures}).")
                appendLine()
                appendLine("RECENT APP EVENTS · newest first")
                var contextBudget = 20_000
                for (record in records.filter { it.severity != "ERROR" && it.severity != "WARN" }.take(40)) {
                    val detail = record.structuredDisplay()
                    if (detail.length > contextBudget) { appendLine("[older app events omitted]"); break }
                    appendLine(detail)
                    contextBudget -= detail.length + 1
                }
                appendLine()
                appendLine("Storage: $skipped invalid records skipped; $unavailable severity files unavailable.")
                appendLine("Report includes bounded recent records; older entries may be omitted.")
                appendLine()
                appendLine("CURRENT PROCESS · resets when the app restarts")
                append(presentation.reportPreview.take(24_000))
                if (presentation.reportPreview.length > 24_000) appendLine("\n[additional process context omitted]")
            }.trimEnd()
            val selectedResult = results.getValue(selected)
            val read = (selectedResult as? PrivateLogResult.Success)?.value
            withContext(Dispatchers.Main.immediate) {
                if (generation != refreshGeneration) return@withContext
                _uiState.value = _uiState.value.copy(
                diagnosticEvents = presentation.orderedEvents,
                savedEvents = records,
                savedEventsBounded = results.values.filterIsInstance<PrivateLogResult.Success<com.hermes.bot.data.diagnostics.PrivateLogRead>>().any { it.value.bounded },
                unavailableFiles = unavailable,
                malformedRecords = skipped,
                groupedEvents = presentation.groupedEvents,
                loading = false,
                lines = read?.linesNewestFirst.orEmpty(),
                bounded = read?.bounded ?: false,
                message = if (selectedResult !is PrivateLogResult.Success) selectedResult.userMessage()
                    else skipped.takeIf { it > 0 }?.let { "$it malformed record(s) were skipped." },
                reportPreview = report,
                copyPayload = report,
                canShare = report.isNotBlank(),
                isReportEmpty = records.isEmpty() && presentation.isEmpty
                )
            }
        }
    }

    private fun PrivateLogResult<*>.userMessage(): String = when (this) {
        PrivateLogResult.Unavailable -> "Private diagnostic storage is unavailable."
        is PrivateLogResult.IoFailure -> "Private diagnostic storage could not be read."
        is PrivateLogResult.Success -> ""
    }
}
