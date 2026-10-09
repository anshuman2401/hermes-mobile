package com.hermes.bot.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [ToolDetailPolicy] per PRD v2 § 3 (Step Detail).
 * Verifies cwd extraction, input/output content formatting, word count,
 * 200-char preview truncation, and copy-all text generation.
 */
class ToolDetailPolicyTest {

    @Test
    fun extractCwd_fromArgsSummary_quotedKey() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "bash",
            status = ToolStatus.COMPLETED,
            argsSummary = """{"command": "ls -la", "cwd": "/home/user/project"}"""
        )
        assertEquals("/home/user/project", ToolDetailPolicy.extractCwd(activity))
    }

    @Test
    fun extractCwd_fromArgsSummary_unquotedKey() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "bash",
            status = ToolStatus.COMPLETED,
            argsSummary = "{command: ls -la, cwd: /srv/apps/hermes}"
        )
        assertEquals("/srv/apps/hermes", ToolDetailPolicy.extractCwd(activity))
    }

    @Test
    fun extractCwd_fromContext_whenPath() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "file_search",
            status = ToolStatus.COMPLETED,
            context = "/var/log/nginx",
            argsSummary = "pattern: *.log"
        )
        assertEquals("/var/log/nginx", ToolDetailPolicy.extractCwd(activity))
    }

    @Test
    fun extractCwd_whenAbsent_returnsNull_neverFabricated() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "web_search",
            status = ToolStatus.COMPLETED,
            argsSummary = "query: hermes bot"
        )
        assertNull(ToolDetailPolicy.extractCwd(activity))
    }

    @Test
    fun formatInputContent_returnsFullArgs() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "web_search",
            status = ToolStatus.COMPLETED,
            argsSummary = "query: latest android updates"
        )
        assertEquals("query: latest android updates", ToolDetailPolicy.formatInputContent(activity))
    }

    @Test
    fun formatInputContent_historicalStepWithNoArgs_returnsNull() {
        // Resumed session step with name and context only
        val activity = ToolActivity(
            id = "t1",
            toolId = null,
            name = "read_file",
            status = ToolStatus.COMPLETED,
            context = null,
            argsSummary = null
        )
        assertNull(ToolDetailPolicy.formatInputContent(activity))
    }

    @Test
    fun formatOutputContent_returnsResultSummary() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "terminal",
            status = ToolStatus.COMPLETED,
            resultSummary = "Process finished with exit code 0"
        )
        assertEquals("Process finished with exit code 0", ToolDetailPolicy.formatOutputContent(activity))
    }

    @Test
    fun formatOutputContent_whenEmptyOrRunning_returnsNull() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "terminal",
            status = ToolStatus.RUNNING,
            resultSummary = null
        )
        assertNull(ToolDetailPolicy.formatOutputContent(activity))
    }

    @Test
    fun wordCount_countsAccurately() {
        assertEquals(0, ToolDetailPolicy.wordCount(null))
        assertEquals(0, ToolDetailPolicy.wordCount(""))
        assertEquals(0, ToolDetailPolicy.wordCount("   "))
        assertEquals(1, ToolDetailPolicy.wordCount("Thought"))
        assertEquals(5, ToolDetailPolicy.wordCount("The model is planning actions."))
        assertEquals(4, ToolDetailPolicy.wordCount("Multiple   spaces\nand\tnewlines"))
    }

    @Test
    fun previewText_atOrBelow200Chars_returnsUnchanged() {
        val shortText = "A".repeat(150)
        assertFalse(ToolDetailPolicy.exceedsPreviewCap(shortText))
        assertEquals(shortText, ToolDetailPolicy.previewText(shortText))

        val exact200 = "B".repeat(200)
        assertFalse(ToolDetailPolicy.exceedsPreviewCap(exact200))
        assertEquals(exact200, ToolDetailPolicy.previewText(exact200))
    }

    @Test
    fun previewText_above200Chars_truncatesWithEllipsis() {
        val longText = "C".repeat(250)
        assertTrue(ToolDetailPolicy.exceedsPreviewCap(longText))
        val preview = ToolDetailPolicy.previewText(longText)
        assertEquals(201, preview.length) // 200 chars + "…"
        assertTrue(preview.endsWith("…"))
        assertEquals("C".repeat(200) + "…", preview)
    }

    @Test
    fun formatCopyAll_toolStep_includesAllFieldsUntruncated() {
        val activity = ToolActivity(
            id = "t1",
            toolId = "call-1",
            name = "terminal",
            status = ToolStatus.COMPLETED,
            durationSeconds = 2.5,
            argsSummary = "command: ls -la, cwd: /root",
            resultSummary = "total 16\ndrwxr-xr-x 2 root root 4096"
        )
        val step = ToolBlockStep.Tool(activity)
        val copyAll = ToolDetailPolicy.formatCopyAll(step)
        assertTrue(copyAll.contains("Tool: terminal"))
        assertTrue(copyAll.contains("Status: Completed"))
        assertTrue(copyAll.contains("Duration: 2.5s"))
        assertTrue(copyAll.contains("Working directory: /root"))
        assertTrue(copyAll.contains("Input:\ncommand: ls -la, cwd: /root"))
        assertTrue(copyAll.contains("Output:\ntotal 16\ndrwxr-xr-x 2 root root 4096"))
    }

    @Test
    fun formatCopyAll_reasoningStep_includesWordCountAndFullText() {
        val reasoning = ReasoningEntry(
            id = "r1",
            kind = ReasoningDisplayKind.REASONING,
            text = "Analyzing the codebase structure to identify dependencies."
        )
        val step = ToolBlockStep.Reasoning(reasoning)
        val copyAll = ToolDetailPolicy.formatCopyAll(step)
        assertTrue(copyAll.contains("Reasoning"))
        assertTrue(copyAll.contains("Words: 7"))
        assertTrue(copyAll.contains("Analyzing the codebase structure to identify dependencies."))
    }
}
