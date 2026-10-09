package com.hermes.bot.feature.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Visible "Copy"/"Copy all" text controls become
 * icon-only (copy icon, check after copy). Source contract for the three
 * surfaces — tool-detail section boxes, "Copy all" footer, code-block header —
 * JVM source check. Behavior (copySensitiveText calls, clip labels,
 * sealed-gating) must remain byte-identical.
 */
class CopyIconOnlySourceContractTest {

    private fun src(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.resolve("com/hermes/bot/feature/chat/components/ActivityBottomSheet.kt").exists() }
        return root.resolve(relative).readText()
    }

    private fun sheet(): String =
        src("com/hermes/bot/feature/chat/components/ActivityBottomSheet.kt")

    private fun codeBlock(): String =
        src("com/hermes/bot/feature/chat/markdown/CodeBlock.kt")

    @Test
    fun detailSectionBox_copyControlIsIconOnly() {
        val window = sheet().substringAfter("if (onCopy != null) {")
            .substringBefore("}\n            }\n        }\n    }")
        assertTrue(
            "Detail-section copy control must be an IconButton",
            window.contains("IconButton(")
        )
        assertTrue(
            "Copy icon must swap to a check after copy",
            window.contains("CopyGlyph(")
        )
        // No visible copy TEXT remains on this control.
        assertFalse(
            "Detail copy control must not render copy text",
            window.contains("\"Copy\"") || window.contains("\"Copied\"")
        )
        // Touch target + TalkBack description preserved.
        assertTrue(
            "Detail copy control must keep a 48dp target and semantics description",
            window.contains("size(48.dp)") && window.contains("contentDescription =")
        )
    }

    @Test
    fun detailFooter_copyAllControlIsIconOnly() {
        val window = sheet().substringAfter("Copy all: icon-only per owner directive")
        assertTrue(
            "Copy-all footer must be an IconButton",
            window.contains("IconButton(")
        )
        assertTrue(
            "Copy-all icon must swap to a check after copy",
            window.contains("CopyGlyph(")
        )
        assertFalse(
            "Copy-all footer must not render copy text",
            window.contains("\"Copy all\"") || window.contains("\"Copied all\"")
        )
        // The full untruncated copy-all behavior is unchanged.
        assertTrue(
            "Copy-all behavior must keep ToolDetailPolicy.formatCopyAll",
            window.contains("ToolDetailPolicy.formatCopyAll(step)")
        )
    }

    @Test
    fun codeBlock_copyControlIsIconOnlyAndStillSealed() {
        val src = codeBlock()
        // Sealed gating unchanged (no copy control while streaming).
        assertTrue(
            "Code copy must remain sealed-gated",
            src.contains("if (sealed) {") &&
                src.contains("TranscriptCopyPolicy.codeCopyRequestIfSealed(")
        )
        val iconWindow = src.substringAfter("Owner directive 2026-09-27: icon-only copy control.")
        assertTrue(
            "Code copy icon render must exist with no visible copy text",
            iconWindow.isNotEmpty()
        )
        assertFalse(
            "Code copy control must not render the 'Copy'/'Copied' text labels",
            Regex(""""(Copy|Copied)"""").containsMatchIn(
                src.substringAfter("IconButton(").substringBefore("// Monospace code body")
            )
        )
        // TalkBack description preserved.
        assertTrue(
            "Code copy semantics description must stay attached",
            src.contains("\"Copy code block\"")
        )
    }

    @Test
    fun sensitiveClipWritePaths_unchanged() {
        // All copy writes still flow through copySensitiveText (SEC-005).
        assertTrue(
            "Sheet copy must still use copySensitiveText",
            sheet().contains("copySensitiveText(context, \"Hermes tool input\"") &&
                sheet().contains("copySensitiveText(context, \"Hermes tool output\"") &&
                sheet().contains("copySensitiveText(context, \"Hermes activity detail\"")
        )
        assertTrue(
            "Code copy must still use the policy request label",
            codeBlock().contains("copySensitiveText(context, request.label, request.text)")
        )
    }
}
