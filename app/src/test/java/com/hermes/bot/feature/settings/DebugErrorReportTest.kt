package com.hermes.bot.feature.settings

import com.hermes.bot.data.diagnostics.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DebugErrorReportTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun persistedCrashAndWarningsAppearInExactlyTheCopiedReportWithoutRing() {
        val metadata = DiagnosticMetadata.forEvent(CatalogEvent.APP_CRASH, DiagnosticField.ExceptionClass.from(IllegalStateException("synthetic-message-canary")))
        val store = object : PrivateDiagnosticLogStore {
            override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> = PrivateLogResult.Success(
                PrivateLogRead(when (severity) {
                    DiagnosticSeverity.ERROR -> listOf(DiagnosticRecordEncoder.encode(CatalogEvent.APP_CRASH, metadata))
                    DiagnosticSeverity.WARN -> listOf(DiagnosticRecordEncoder.encode(CatalogEvent.GATEWAY_DISCONNECTED))
                    else -> emptyList()
                }, 0, false)
            )
        }
        val vm = DebugLogsViewModel(store, ioDispatcher = main.testDispatcher)
        val state = vm.uiState.value
        assertFalse(state.loading)
        assertEquals(state.reportPreview, state.copyPayload)
        assertTrue(state.copyPayload.contains("app_crash"))
        assertTrue(state.copyPayload.contains("java.lang.IllegalStateException"))
        assertTrue(state.copyPayload.contains("Gateway disconnected"))
        assertFalse(state.copyPayload.contains("synthetic-message-canary"))
    }

    @Test fun largePersistedTracesFitWithinAndroidTextSharingBudget() {
        val failure = IllegalStateException().apply {
            stackTrace = Array(24) { StackTraceElement("com.hermes.bot." + "A".repeat(220), "m".repeat(128), "synthetic-source-canary", 1) }
        }
        val error = DiagnosticRecordEncoder.encode(CatalogEvent.APP_CRASH,
            DiagnosticMetadata.forEvent(CatalogEvent.APP_CRASH, DiagnosticField.ExceptionClass.from(failure)))
        val store = object : PrivateDiagnosticLogStore {
            override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> = PrivateLogResult.Success(
                PrivateLogRead(when (severity) {
                    DiagnosticSeverity.ERROR -> List(40) { error }
                    DiagnosticSeverity.INFO -> List(40) { error.replace("\"severity\":\"ERROR\"", "\"severity\":\"INFO\"") }
                    else -> emptyList()
                }, 0, false)
            )
        }
        val state = DebugLogsViewModel(store, ioDispatcher = main.testDispatcher).uiState.value
        assertTrue(state.copyPayload.length < 110_000)
        assertTrue(state.copyPayload.contains("Older errors/warnings omitted"))
        assertTrue(state.copyPayload.contains("older app events omitted"))
        assertEquals(state.reportPreview, state.copyPayload)
    }

    @Test fun partialStorageFailureIsVisibleAndRefreshDoesNotPublishStaleResults() {
        val release = CompletableDeferred<Unit>()
        var first = true
        val store = object : PrivateDiagnosticLogStore {
            override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> {
                if (first) {
                    first = false
                    withContext(NonCancellable) { release.await() }
                    return PrivateLogResult.Success(PrivateLogRead(listOf(DiagnosticRecordEncoder.encode(CatalogEvent.APP_CRASH)), 0, false))
                }
                return PrivateLogResult.Unavailable
            }
        }
        val vm = DebugLogsViewModel(store, ioDispatcher = main.testDispatcher)
        vm.refresh()
        val report = vm.uiState.value.copyPayload
        assertTrue(report.contains("4 severity files unavailable"))
        release.complete(Unit)
        assertEquals(report, vm.uiState.value.copyPayload)
        assertFalse(vm.uiState.value.loading)
    }
}
