package io.github.anshuman2401.hermesmobile.feature.settings

import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticEvent
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticRing
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticJsonl
import io.github.anshuman2401.hermesmobile.data.diagnostics.DiagnosticSeverity

/**
 * Pure Kotlin presentation policy for the Diagnostics viewer screen.
 * Handles chronological sorting (most recent first), category grouping,
 * and guarantees that copy/share payloads strictly match the on-screen report preview.
 */
object DiagnosticsScreenPolicy {

    fun savedVisible(records: List<DiagnosticJsonl.Record>, severity: DiagnosticSeverity?): List<DiagnosticJsonl.Record> =
        records.filter { severity == null || it.severity == severity.name }

    const val EMPTY_EVENTS_MESSAGE = "No diagnostic events recorded yet."
    const val EMPTY_REPORT_MESSAGE = "No report available."

    enum class EventCategory(val displayName: String) {
        AUTH("Authentication"),
        HTTP("HTTP Requests"),
        SWITCH("Workspace Switch"),
        WEBSOCKET("WebSocket Gateway"),
        SESSION_LIST("Session List"),
        APP("App Lifecycle"),
        OTHER("General")
    }

    data class DiagnosticsPresentation(
        val orderedEvents: List<DiagnosticEvent>,
        val groupedEvents: Map<EventCategory, List<DiagnosticEvent>>,
        val reportPreview: String,
        val copyPayload: String,
        val isEmpty: Boolean
    )

    fun categoryFor(event: DiagnosticEvent): EventCategory = when (event) {
        is DiagnosticEvent.Auth -> EventCategory.AUTH
        is DiagnosticEvent.Http -> EventCategory.HTTP
        is DiagnosticEvent.Switch -> EventCategory.SWITCH
        is DiagnosticEvent.WebSocket -> EventCategory.WEBSOCKET
        is DiagnosticEvent.SessionList -> EventCategory.SESSION_LIST
        is DiagnosticEvent.App -> EventCategory.APP
    }

    /**
     * Orders events newest-first (chronological descending by timestamp).
     */
    fun orderNewestFirst(events: List<DiagnosticEvent>): List<DiagnosticEvent> =
        events.sortedByDescending { it.timestamp }

    /**
     * Groups events by category while preserving newest-first order within each group.
     */
    fun groupByCategory(events: List<DiagnosticEvent>): Map<EventCategory, List<DiagnosticEvent>> {
        val newestFirst = orderNewestFirst(events)
        val map = linkedMapOf<EventCategory, MutableList<DiagnosticEvent>>()
        for (event in newestFirst) {
            val cat = categoryFor(event)
            map.getOrPut(cat) { mutableListOf() }.add(event)
        }
        return map
    }

    /**
     * Reduces the current DiagnosticRing state into presentation state.
     * Guarantees reportPreview == copyPayload.
     */
    fun reduce(ring: DiagnosticRing?): DiagnosticsPresentation {
        if (ring == null) {
            return DiagnosticsPresentation(
                orderedEvents = emptyList(),
                groupedEvents = emptyMap(),
                reportPreview = "",
                copyPayload = "",
                isEmpty = true
            )
        }

        val rawEvents = ring.snapshot()
        val ordered = orderNewestFirst(rawEvents)
        val grouped = groupByCategory(ordered)
        val report = ring.snapshotReport(maxEvents = 100)

        return DiagnosticsPresentation(
            orderedEvents = ordered,
            groupedEvents = grouped,
            reportPreview = report,
            copyPayload = report,
            isEmpty = rawEvents.isEmpty()
        )
    }

    fun isCopyPayloadIdenticalToReport(copyPayload: String, reportPreview: String): Boolean =
        copyPayload == reportPreview
}
