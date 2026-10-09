package io.github.anshuman2401.hermesmobile.data.gateway

import io.github.anshuman2401.hermesmobile.data.gateway.json.*
import io.github.anshuman2401.hermesmobile.domain.TaskStatus
import org.junit.Assert.*
import org.junit.Test

/** Synthetic schema examples; no live-host certification or private values. */
class TaskSnapshotDecoderTest {
    private fun snapshot(items: String, revision: String = "3") =
        TaskSnapshotDecoder.decode(GatewayJson.parse("""{"todos":[$items],"revision":$revision}"""))
    private fun row(id: String = "a", status: String = "pending", content: String = "Synthetic step") =
        """{"id":"$id","content":"$content","status":"$status"}"""

    @Test fun parsesFourStatusesAndCountsOnlyNonCancelledTasks() {
        val decoded = snapshot(listOf("completed", "in_progress", "pending", "cancelled")
            .mapIndexed { i, status -> row("$i", status) }.joinToString(","))!!
        assertEquals(4, decoded.items.size)
        assertEquals(TaskStatus.IN_PROGRESS, decoded.items[1].status)
        assertEquals(1, decoded.completedCount)
        assertEquals(3, decoded.countedCount)
        assertTrue(decoded.hasOpenTasks)
        assertEquals(3L, decoded.revision)
    }

    @Test fun fullStringResultsAndUnversionedLegacyResultsAreSupported() {
        val text = """{"todos":[${row()}]}"""
        assertNull(TaskSnapshotDecoder.decode(JsonString(text))!!.revision)
        assertEquals(1, TaskSnapshotDecoder.decode(JsonString(text))!!.items.size)
        assertEquals(emptyList<Any>(), snapshot("")!!.items)
    }

    @Test fun malformedSnapshotsAreRejectedAtomicallyRatherThanShowingPartialProgress() {
        listOf(row(status = "done"), row(id = ""), row(content = ""), "null", "{}", "17",
            row() + "," + row()).forEach { assertNull(snapshot(it)) }
        listOf("null", "-1", "1.2", "true", "\"2\"", "99999999999999999999999").forEach {
            assertNull(snapshot(row(), it))
        }
        assertNull(TaskSnapshotDecoder.decode(GatewayJson.parse("""{"todos":null}""")))
        assertNull(TaskSnapshotDecoder.decode(JsonString("[not json]")))
    }

    @Test fun errorResultsCannotFabricateAConfirmedChecklist() {
        assertNull(TaskSnapshotDecoder.decode(GatewayJson.parse("""{"todos":[${row()}],"error":"Synthetic failure"}""")))
        assertNull(TaskSnapshotDecoder.decode(GatewayJson.parse("""{"todos":[${row()}],"success":false}""")))
    }

    @Test fun itemAndJsonBudgetsFailClosed() {
        assertEquals(256, snapshot((0..255).joinToString(",") { row("$it") })!!.items.size)
        assertNull(snapshot((0..256).joinToString(",") { row("$it") }))
        assertNull(TaskSnapshotDecoder.decode(JsonString(" ".repeat(TaskSnapshotDecoder.MAX_RESULT_CHARS + 1))))
        assertNull(TaskSnapshotDecoder.decode(JsonString("[".repeat(1000) + "]".repeat(1000))))
        assertNull(snapshot(row(id = "x".repeat(129))))
        assertNull(snapshot(row(content = "x".repeat(16_001))))
    }

    @Test fun descriptionsAreScalarBoundedAndSecretPatternsRedacted() {
        val emoji = "\uD83D\uDE00"
        val decoded = snapshot(row(content = emoji.repeat(600)))!!
        assertTrue(decoded.descriptionsTruncated)
        assertEquals(512, decoded.items[0].content.codePointCount(0, decoded.items[0].content.length))
        assertTrue(decoded.items[0].content.endsWith("…"))
        val redacted = snapshot(row(content = "password=synthetic-value"))!!
        assertFalse(redacted.items[0].content.contains("synthetic-value"))
        assertFalse(redacted.toString().contains("password"))
        assertFalse(redacted.items[0].toString().contains("password"))
    }

    @Test fun parentIdsAreBoundedAndUnknownFieldsAreNotRetained() {
        val value = GatewayJson.parse("""{"todos":[{"id":"child","content":"Synthetic","status":"pending","parent":"root","extra":"discard"}]}""")
        assertEquals("root", TaskSnapshotDecoder.decode(value)!!.items[0].parentId)
    }
}
