package io.github.anshuman2401.hermesmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModelTest {
    @Test fun fourSeveritiesMapToFixedPrivateNames() {
        assertEquals(
            setOf("hermes-bot-debug.log", "hermes-bot-info.log", "hermes-bot-warn.log", "hermes-bot-error.log"),
            DiagnosticSeverity.entries.map { it.fileName }.toSet()
        )
    }

    @Test fun encoderProducesOneBoundedJsonlLineWithoutSensitiveCanaries() {
        val passwordCanary = "password-canary-CHG002"
        val tokenCanary = "Bearer token-canary-CHG002"
        val event = DiagnosticRecordEncoder.encode(
            CatalogEvent.LOGIN_FAILED,
            DiagnosticMetadata.forEvent(
                CatalogEvent.LOGIN_FAILED,
                DiagnosticField.ExceptionClass.from(IllegalStateException("$passwordCanary $tokenCanary host.example Cookie: secret"))
            )
        )

        assertTrue(event.endsWith("\n"))
        assertTrue(event.toByteArray(Charsets.UTF_8).size <= DiagnosticRecordEncoder.MAX_RECORD_BYTES)
        assertFalse(event.contains(passwordCanary))
        assertFalse(event.contains(tokenCanary))
        assertFalse(event.contains("host.example"))
        assertFalse(event.contains("Cookie: secret"))
        assertTrue(event.contains("login_failed"))
    }

    @Test fun hostileInputIsResanitizedBeforeDisplay() {
        val canary = "ticket-canary-CHG002"
        val hostile = "{\"v\":1,\"message\":\"ok\",\"ticket\":\"$canary\",\"host\":\"192.0.2.1\"}"

        val rendered = DiagnosticRedactor.sanitizeLine(hostile)

        assertFalse(rendered.contains(canary))
        assertFalse(rendered.contains("192.0.2.1"))
        assertTrue(rendered.contains("[redacted]"))
    }

    @Test fun lifecycleRecordsReadyAndDegradedStartupCompositionAndActivityTransitions() {
        val events = mutableListOf<CatalogEvent>()
        val lifecycle = DiagnosticsLifecycle(object : Diagnostics {
            override fun emit(event: CatalogEvent, metadata: DiagnosticMetadata) { events += event }
        })

        lifecycle.applicationStarted(prepared = true)
        assertEquals(
            listOf(
                CatalogEvent.DIAGNOSTICS_READY,
                CatalogEvent.APP_START,
                CatalogEvent.COMPOSITION_START
            ),
            events
        )
        events.clear()
        lifecycle.applicationStarted(prepared = false)
        lifecycle.compositionReady()
        lifecycle.activityCreated()
        lifecycle.foregrounded()
        lifecycle.backgrounded()

        assertEquals(
            listOf(
                CatalogEvent.DIAGNOSTICS_DEGRADED,
                CatalogEvent.APP_START,
                CatalogEvent.COMPOSITION_START,
                CatalogEvent.COMPOSITION_READY,
                CatalogEvent.ACTIVITY_CREATE,
                CatalogEvent.APP_FOREGROUND,
                CatalogEvent.APP_BACKGROUND
            ),
            events
        )
    }
}
