package com.hermes.bot.feature.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner directive 2026-09-27 (follow-up to PR #183): the two remaining
 * visible copy-text surfaces become icon-only:
 * - assistant bubble: "Copy"/"Copied" TextButton -> IconButton + CopyGlyph
 * - diagnostics screen: "Copy diagnostics" OutlinedButton -> icon (Build
 *   core-icon stands in; no icons-extended dependency) + check after copy
 *
 * Source contract per repo convention (JVM only, no compose infra).
 * Behavior contracts that must survive: sealed gating + bounded
 * copyRequest (SEC-005), TalkBack strings ("Copy response"/"Response
 * copied"/"Copy diagnostics"), Toast confirmation, enabled gating.
 */
class CopyIconOnlyAssistantAndDiagnosticsSourceContractTest {

    private fun readMain(relative: String): String {
        val direct = File("src/main/java/$relative")
        return (if (direct.exists()) direct else File("app/src/main/java/$relative")).readText()
    }

    @Test
    fun assistantBubble_copyControlIsIconOnly() {
        val src = readMain("com/hermes/bot/feature/chat/components/AssistantMessageBubble.kt")
        val window = src.substringAfter("if (sealedForCopy && message.text.isNotEmpty() && copyRequest != null) {")
        assertTrue("assistant copy control must be an IconButton", window.contains("IconButton("))
        assertTrue("assistant copy control must use CopyGlyph", window.contains("CopyGlyph("))
        assertFalse(
            "assistant copy control must not render visible copy text",
            window.contains("Text(\"Copy\"") || window.contains("Text(\"Copied\"")
        )
        // Bounded sealed-gated copy + TalkBack semantics preserved.
        assertTrue(
            "SEC-005 bounded copy call preserved",
            window.contains("copySensitiveText(") && window.contains("copyRequest.label")
        )
        assertTrue(
            "copy semantics must remain",
            window.contains("\"Copy response\"") && window.contains("\"Response copied\"")
        )
        assertTrue(
            "sealed gate must remain",
            src.contains("sealedForCopy && message.text.isNotEmpty() && copyRequest != null")
        )
        // The glyph file must not be re-erased: check swap present.
        assertTrue(
            "CopyGlyph must swap to Check when copied",
            readMain("com/hermes/bot/feature/chat/components/CopyGlyph.kt").contains("Icons.Default.Check")
        )
    }

    @Test
    fun diagnosticsScreen_copyControlIsIconOnly() {
        val src = readMain("com/hermes/bot/feature/settings/DebugLogsScreen.kt")
        assertFalse(
            "diagnostics copy button must not render visible copy text",
            src.contains("Text(\"Copy diagnostics\")")
        )
        // Icon-only copy button with check swap + toast confirmation.
        assertTrue(
            "diagnostics copy must show the check when copied",
            src.contains("Icons.Filled.Check") && src.contains("isCopied = true")
        )
        assertTrue(
            "toast confirmation must remain",
            src.contains("Diagnostics copied to clipboard")
        )
        // Share/Refresh stay text OutlinedButtons (not copy controls).
        assertTrue(
            "share/refresh remain text",
            src.contains("Text(\"Share\")") && src.contains("Text(\"Refresh\")")
        )
    }

    @Test
    fun existingSemanticsContracts_stillSatisfied() {
        // ChatUiPresentationSourceContractTest:193 pins "Copy response"
        // (TalkBack) — the icon-only rewrite keeps the string.
        val bubble = readMain("com/hermes/bot/feature/chat/components/AssistantMessageBubble.kt")
        assertTrue(bubble.contains("\"Copy response\""))
        // DiagnosticsScreenSourceContractTest:67 pins "Copy diagnostics"
        // (TalkBack) — the icon-only rewrite keeps the string.
        val diag = readMain("com/hermes/bot/feature/settings/DebugLogsScreen.kt")
        assertTrue(diag.contains("\"Copy diagnostics\""))
    }
}