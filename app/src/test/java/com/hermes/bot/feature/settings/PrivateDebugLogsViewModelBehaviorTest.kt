package com.hermes.bot.feature.settings

import com.hermes.bot.data.diagnostics.DiagnosticSeverity
import com.hermes.bot.data.diagnostics.DiagnosticJsonl
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.PrivateDiagnosticLogStore
import com.hermes.bot.data.diagnostics.PrivateLogRead
import com.hermes.bot.data.diagnostics.PrivateLogResult
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivateDebugLogsViewModelBehaviorTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun defaultsToErrorAndRendersOnlyPrivateReadResultsNewestFirst() = runBlocking {
        val store = object : PrivateDiagnosticLogStore {
            override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> =
                PrivateLogResult.Success(PrivateLogRead(listOf("newest", "older"), 2, true))
        }
        val viewModel = DebugLogsViewModel(store)
        kotlinx.coroutines.withTimeout(5_000) {
            viewModel.uiState.first { !it.loading && it.copyPayload.isNotBlank() }
        }

        assertEquals(DiagnosticSeverity.ERROR, viewModel.uiState.value.selectedSeverity)
        assertEquals(listOf("newest", "older"), viewModel.uiState.value.lines)
        assertTrue(viewModel.uiState.value.bounded)
        assertTrue(viewModel.uiState.value.message!!.contains("malformed", ignoreCase = true))
        assertFalse(viewModel.uiState.value.canClear)
        assertTrue(viewModel.uiState.value.canShare)
    }

    @Test fun savedEventsMergeAllSeveritiesNewestFirstAndReportRemainsExact() = runBlocking {
        val old = DiagnosticJsonl.Record("2026-10-08T09:00:00Z", "WARN", "login_failed", "auth", "Login failed").canonicalJsonLine()
        val recent = DiagnosticJsonl.Record("2026-10-08T10:00:00Z", "INFO", "app_start", "startup", "Application started").canonicalJsonLine()
        val store = object : PrivateDiagnosticLogStore {
            override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> =
                PrivateLogResult.Success(PrivateLogRead(when (severity) {
                    DiagnosticSeverity.WARN -> listOf(old)
                    DiagnosticSeverity.INFO -> listOf(recent)
                    else -> emptyList()
                }, 0, severity == DiagnosticSeverity.WARN))
        }
        val ring = DiagnosticRing().apply { record(DiagnosticEvent.Auth.Expired()) }
        val viewModel = DebugLogsViewModel(store, ring)
        kotlinx.coroutines.withTimeout(5_000) { viewModel.uiState.first { !it.loading && it.savedEvents.size == 2 } }
        val state = viewModel.uiState.value
        assertEquals(listOf("INFO", "WARN"), state.savedEvents.map { it.severity })
        assertTrue(state.savedEventsBounded)
        assertEquals(1, state.diagnosticEvents.size)
        assertEquals(state.reportPreview, state.copyPayload)
    }
}
