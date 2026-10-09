package io.github.anshuman2401.hermesmobile.data.diagnostics

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticJsonlBehaviorTest {
    private fun record(message: String = "safe"): String =
        "{\"v\":1,\"ts\":\"2026-09-16T12:00:00Z\",\"severity\":\"INFO\",\"code\":\"test_code\",\"component\":\"test\",\"message\":\"$message\"}"

    @Test fun completeJsonlReader_boundsBeforeMaterialization_and_countsHostileRecords() {
        val oversized = "x".repeat(DiagnosticRecordEncoder.MAX_RECORD_BYTES + 1)
        val input = listOf(
            record("Said \\\"hello\\\""),
            "{not-json}",
            "{\"v\":1,\"ts\":\"partial\"",
            oversized
        ).joinToString("\n") + "\n" + record("unterminated")

        val result = DiagnosticJsonl.readComplete(
            ByteArrayInputStream(input.toByteArray()),
            DiagnosticRecordEncoder.MAX_RECORD_BYTES
        )

        assertEquals(1, result.records.size)
        assertEquals(4, result.rejectedCount)
        assertTrue(result.records.single().contains("Said \\\"hello\\\""))
        assertFalse(result.records.any { it.contains("unterminated") })
    }

    @Test fun canonicalRecord_parsesAndRedactsSensitiveEscapedValues_beforeDisplayOrShare() {
        val hostile = record("token=secret-canary and hello")
        val canonical = DiagnosticJsonl.parseComplete(hostile)

        assertTrue(canonical != null)
        assertFalse(canonical!!.canonicalJsonLine().contains("secret-canary"))
        assertEquals(
            "2026-09-16T12:00:00Z  test_code/test  [redacted] and hello",
            canonical.structuredDisplay()
        )
    }
}
