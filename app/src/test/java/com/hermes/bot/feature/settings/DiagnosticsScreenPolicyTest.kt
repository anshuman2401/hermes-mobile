package com.hermes.bot.feature.settings

import com.hermes.bot.data.diagnostics.DiagnosticEvent
import com.hermes.bot.data.diagnostics.DiagnosticRing
import com.hermes.bot.data.diagnostics.DiagnosticJsonl
import com.hermes.bot.data.diagnostics.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DiagnosticsScreenPolicyTest {

    @Test fun savedSeverityFilterUsesParsedRecordsAndPreservesOrder() {
        val records = listOf(
            DiagnosticJsonl.Record("2026-10-08T10:00:00Z", "INFO", "app_start", "startup", "Application started"),
            DiagnosticJsonl.Record("2026-10-08T09:00:00Z", "WARN", "login_failed", "auth", "Login failed")
        )
        assertEquals(records, DiagnosticsScreenPolicy.savedVisible(records, null))
        assertEquals(listOf(records.last()), DiagnosticsScreenPolicy.savedVisible(records, DiagnosticSeverity.WARN))
        assertTrue(DiagnosticsScreenPolicy.savedVisible(records, DiagnosticSeverity.ERROR).isEmpty())
    }

    @Test
    fun policy_emptyState_producesEmptyStateContract() {
        val ring = DiagnosticRing()
        val presentation = DiagnosticsScreenPolicy.reduce(ring)

        assertTrue(presentation.isEmpty)
        assertTrue(presentation.orderedEvents.isEmpty())
        assertTrue(presentation.groupedEvents.isEmpty())
        assertEquals(presentation.reportPreview, presentation.copyPayload)
        assertTrue(presentation.reportPreview.contains("None recorded."))
        assertTrue(DiagnosticsScreenPolicy.isCopyPayloadIdenticalToReport(presentation.copyPayload, presentation.reportPreview))
    }

    @Test
    fun policy_nullRing_producesSafeDefaults() {
        val presentation = DiagnosticsScreenPolicy.reduce(null)

        assertTrue(presentation.isEmpty)
        assertTrue(presentation.orderedEvents.isEmpty())
        assertTrue(presentation.groupedEvents.isEmpty())
        assertEquals("", presentation.reportPreview)
        assertEquals("", presentation.copyPayload)
    }

    @Test
    fun policy_groupedOrdering_mostRecentFirst() {
        val ring = DiagnosticRing()
        val t1 = Instant.parse("2026-09-21T10:00:00Z")
        val t2 = Instant.parse("2026-09-21T10:01:00Z")
        val t3 = Instant.parse("2026-09-21T10:02:00Z")
        val t4 = Instant.parse("2026-09-21T10:03:00Z")

        val e1 = DiagnosticEvent.Auth.LoggingIn("basic", timestamp = t1)
        val e2 = DiagnosticEvent.Http.RequestCompleted("GET", "/api/v1", 200, 10, timestamp = t2)
        val e3 = DiagnosticEvent.Auth.Authenticated("basic", timestamp = t3)
        val e4 = DiagnosticEvent.WebSocket.Connected(timestamp = t4)

        ring.record(e1)
        ring.record(e2)
        ring.record(e3)
        ring.record(e4)

        val presentation = DiagnosticsScreenPolicy.reduce(ring)

        assertFalse(presentation.isEmpty)
        assertEquals(4, presentation.orderedEvents.size)

        // Chronological descending (most recent first)
        assertEquals(e4, presentation.orderedEvents[0])
        assertEquals("[redacted]", (presentation.orderedEvents[1] as DiagnosticEvent.Auth.Authenticated).provider)
        assertEquals("[redacted]", (presentation.orderedEvents[2] as DiagnosticEvent.Http.RequestCompleted).urlPathShape)
        assertEquals("[redacted]", (presentation.orderedEvents[3] as DiagnosticEvent.Auth.LoggingIn).provider)

        // Grouped by category
        val groups = presentation.groupedEvents
        assertTrue(groups.containsKey(DiagnosticsScreenPolicy.EventCategory.AUTH))
        assertTrue(groups.containsKey(DiagnosticsScreenPolicy.EventCategory.HTTP))
        assertTrue(groups.containsKey(DiagnosticsScreenPolicy.EventCategory.WEBSOCKET))

        // Within AUTH group: e3 (10:02) before e1 (10:00)
        val authEvents = groups[DiagnosticsScreenPolicy.EventCategory.AUTH]!!
        assertEquals(2, authEvents.size)
        assertEquals("[redacted]", (authEvents[0] as DiagnosticEvent.Auth.Authenticated).provider)
        assertEquals("[redacted]", (authEvents[1] as DiagnosticEvent.Auth.LoggingIn).provider)
    }

    @Test
    fun policy_copyPayload_strictlyEqualsSnapshotReport() {
        val ring = DiagnosticRing()
        ring.record(DiagnosticEvent.Auth.LoggingIn("user"))
        ring.record(DiagnosticEvent.Http.RequestCompleted("POST", "/auth/login", 200, 42))
        ring.record(DiagnosticEvent.Switch.SheetOpened())
        ring.record(DiagnosticEvent.Switch.ConfirmTapped("default"))
        ring.record(DiagnosticEvent.WebSocket.Connecting())
        ring.record(DiagnosticEvent.WebSocket.Connected())
        ring.record(DiagnosticEvent.App.ForegroundEntered)

        val presentation = DiagnosticsScreenPolicy.reduce(ring)

        assertEquals(presentation.reportPreview, presentation.copyPayload)
        assertTrue(DiagnosticsScreenPolicy.isCopyPayloadIdenticalToReport(presentation.copyPayload, presentation.reportPreview))
        assertTrue(presentation.reportPreview.contains("=== Hermes Mobile Diagnostics Report ==="))
        assertTrue(presentation.reportPreview.contains("AUTH: logging in (provider=[redacted])"))
        assertTrue(presentation.reportPreview.contains("HTTP: POST [redacted] -> 200 (42ms)"))
        assertTrue(presentation.reportPreview.contains("SWITCH: sheet opened"))
        assertTrue(presentation.reportPreview.contains("SWITCH: confirm tapped (target=[redacted])"))
        assertTrue(presentation.reportPreview.contains("WS: connecting"))
        assertTrue(presentation.reportPreview.contains("WS: connected"))
        assertTrue(presentation.reportPreview.contains("APP: foreground entered"))
    }
}
