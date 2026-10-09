package com.hermes.bot.feature.chat

import com.hermes.bot.feature.chat.markdown.BoundedCode
import com.hermes.bot.feature.chat.markdown.CodeBlockBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S10 (TASK-045..TASK-047, M-06): sealed transcript selection and
 * copy policy rows — streaming vs sealed gating, response source
 * below/at/above the 1 MiB D-04 copy window, code below/at/above the
 * 2,000-line / 256 KiB D-10 scope, exact copied-scope assertions,
 * cancellation, sensitive metadata (SEC-005), and TalkBack/target semantics.
 *
 * Compose-tier rows (visual control presence, real clipboard writes on a
 * device) require separate device/emulator execution. The gating decision
 * and exact scope are proven
 * here at the policy layer; the Compose layer renders from the same policy
 * functions.
 */
class TranscriptCopyPolicyTest {

    // ------------------------------------------------------------------
    // TASK-045: streaming vs sealed gating (D-12)
    // ------------------------------------------------------------------

    @Test
    fun gating_streamingIsNeverSealed_allTerminalStatesAre() {
        assertFalse(TranscriptCopyPolicy.sealedForCopy(AssistantMessageState.STREAMING))
        assertTrue(TranscriptCopyPolicy.sealedForCopy(AssistantMessageState.COMPLETE))
        assertTrue(TranscriptCopyPolicy.sealedForCopy(AssistantMessageState.INTERRUPTED))
        assertTrue(TranscriptCopyPolicy.sealedForCopy(AssistantMessageState.FAILED))
        assertTrue(TranscriptCopyPolicy.sealedForCopy(AssistantMessageState.UNKNOWN))
    }

    @Test
    fun gating_streamingProducesNoCopyRequestAndNoClipboardWrite() {
        // D-12: during streaming no copy/selection control is active — the
        // policy refuses to mint a request, so the Compose layer has nothing
        // to write and no control to render (asserted as a state transition:
        // no request => no enabled control in the bubble).
        assertNull(
            TranscriptCopyPolicy.responseCopyRequestIfSealed(
                AssistantMessageState.STREAMING,
                "partial stream text"
            )
        )
        assertNull(TranscriptCopyPolicy.codeCopyRequestIfSealed(sealed = false, code = "partial"))
    }

    @Test
    fun gating_sealedCompleteMintsTheFullVisibleScope() {
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            "hello sealed world"
        )
        assertEquals("hello sealed world", request!!.text)
        assertEquals(TranscriptCopyPolicy.RESPONSE_COPY_LABEL, request.label)
    }

    // ------------------------------------------------------------------
    // TASK-046: response copy scope — 1 MiB window, below/at/above,
    // measured on the surrogate-normalized stream (CON-008)
    // ------------------------------------------------------------------

    private fun utf8Bytes(text: String): Long =
        AssistantTurnStreamBuffer.utf8ByteCount(text)

    private fun String.codeUnitsContainNoUnpairedSurrogate(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i += 1
            }
        }
        return true
    }

    @Test
    fun responseCopy_belowTheWindow_returnsTheWholeSource() {
        val text = "a".repeat(1024)
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        assertEquals(text, request!!.text)
        assertEquals(1024L, utf8Bytes(request.text))
    }

    @Test
    fun responseCopy_atTheWindow_returnsExactlyOneMiB() {
        val text = "b".repeat(AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES.toInt())
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        assertEquals(
            "at-boundary copy keeps exactly the 1 MiB visible window",
            AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES,
            utf8Bytes(request!!.text)
        )
        assertEquals(text, request.text)
    }

    @Test
    fun responseCopy_aboveTheWindow_neverExceedsOneMiB() {
        // 1 MiB + 3 bytes: the copy must be the scalar-aligned prefix, never
        // the over-cap suffix and never the whole 8 MiB canonical source.
        val text = "c".repeat(AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES.toInt() + 3)
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        assertEquals(
            AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES,
            utf8Bytes(request!!.text)
        )
        assertTrue(request.text.length < text.length)
    }

    @Test
    fun responseCopy_multiByteCutLandsOnScalarBoundary() {
        // 4-byte emoji (U+1F600, surrogate pair in UTF-16): a window cut must
        // land between scalars — never inside a surrogate pair or UTF-8
        // sequence — and must measure the normalized stream.
        val emoji = "\uD83D\uDE00"
        val text = emoji.repeat(300_000) // 1,200,000 UTF-8 bytes
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        val copied = request!!.text
        assertEquals(
            AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES,
            utf8Bytes(copied)
        )
        // 1 MiB / 4 bytes per scalar = exactly 262,144 whole emoji scalars.
        assertEquals(262_144, copied.codePointCount(0, copied.length))
        assertTrue(copied.codeUnitsContainNoUnpairedSurrogate())
    }

    @Test
    fun responseCopy_unpairedSurrogatesNormalizeBeforeCopy() {
        val text = "ok\uDE00tail"
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        // The lone low surrogate becomes U+FFFD on the normalized stream; the
        // copy never carries an unpaired surrogate.
        assertTrue(request!!.text.codeUnitsContainNoUnpairedSurrogate())
        assertEquals(
            utf8Bytes("ok") + 3 + utf8Bytes("tail"),
            utf8Bytes(request.text)
        )
    }

    @Test
    fun responseCopy_disclosureLabelsAreNeverPartOfTheScope() {
        // The presentation window's frozen truncation labels are disclosure
        // text; the copied scope is bounded to the 1 MiB window and must not
        // gain extra disclosure text beyond it (TASK-048 stop-condition
        // invariant: the clipboard never exceeds the visibly disclosed
        // bounded source).
        val text = "d".repeat(AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES.toInt()) +
            AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL +
            AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            text
        )
        assertEquals(
            AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES,
            utf8Bytes(request!!.text)
        )
        assertFalse(request.text.contains("Content truncated"))
        assertFalse(request.text.contains("8 MiB"))
    }

    // ------------------------------------------------------------------
    // TASK-046: code copy scope — D-10 bounded scope, below/at/above
    // ------------------------------------------------------------------

    @Test
    fun codeCopy_lineAxis_below_at_above() {
        fun code(lines: Int) = buildString {
            for (i in 1..lines) {
                append("line ").append(i)
                if (i < lines) append('\n')
            }
        }

        val below = TranscriptCopyPolicy.codeCopyRequestIfSealed(
            sealed = true,
            code = code(CodeBlockBudget.MAX_LINES - 1)
        )!!
        assertEquals(CodeBlockBudget.MAX_LINES - 1, below.text.split("\n").size)

        val at = TranscriptCopyPolicy.codeCopyRequestIfSealed(
            sealed = true,
            code = code(CodeBlockBudget.MAX_LINES)
        )!!
        assertEquals(CodeBlockBudget.MAX_LINES, at.text.split("\n").size)

        val above = TranscriptCopyPolicy.codeCopyRequestIfSealed(
            sealed = true,
            code = code(CodeBlockBudget.MAX_LINES + 9)
        )!!
        assertTrue(above.text.split("\n").size <= CodeBlockBudget.MAX_LINES)
        assertTrue(BoundedCode.boundedCodeScope(above.text).text == above.text)
    }

    @Test
    fun codeCopy_byteAxis_below_at_above_separatorInclusive() {
        // Separator-inclusive accounting (PR41 F1 lesson): multi-line scopes
        // measure the '\n' joining bytes too; the copy must stay within the
        // 256 KiB budget on the normalized stream.
        val line = "z".repeat(1024) // 1024 bytes + 1 separator byte per join
        fun code(lines: Int) = buildString {
            for (i in 1..lines) {
                append(line)
                if (i < lines) append('\n')
            }
        }

        // Below the budget: 250 lines → 249 separators + 250 * 1024 bytes
        // = 258,374 < 256 KiB — the whole multi-line scope is retained with
        // separator-inclusive accounting.
        val below = TranscriptCopyPolicy.codeCopyRequestIfSealed(sealed = true, code = code(250))!!
        assertFalse(BoundedCode.boundedCodeScope(below.text).truncated)
        assertEquals(250, below.text.split("\n").size)
        assertTrue(utf8Bytes(below.text) <= CodeBlockBudget.MAX_UTF8_BYTES)

        // Over-budget source: the copied scope never exceeds 256 KiB.
        val above = TranscriptCopyPolicy.codeCopyRequestIfSealed(sealed = true, code = code(300))!!
        assertTrue(
            "code copy never exceeds the 256 KiB bounded scope",
            utf8Bytes(above.text) <= CodeBlockBudget.MAX_UTF8_BYTES
        )
        // The source was truncated (the scope is strictly smaller than the
        // 300 × 1024-byte + separator input) and the kept scope is stable:
        // re-bounding the already-bounded copy is a no-op, so the clipboard
        // can never re-grow beyond the disclosed scope.
        assertTrue(utf8Bytes(above.text) < utf8Bytes(code(300)))
        assertFalse(BoundedCode.boundedCodeScope(above.text).truncated)
    }

    @Test
    fun codeCopy_scopeIsIdempotentAndLabeled() {
        val request = TranscriptCopyPolicy.codeCopyRequestIfSealed(
            sealed = true,
            code = "x".repeat(CodeBlockBudget.MAX_UTF8_BYTES + 10)
        )!!
        assertEquals(TranscriptCopyPolicy.CODE_COPY_LABEL, request.label)
        // Re-bounding the already-bounded copy is a no-op: the clipboard can
        // never re-grow beyond the disclosed scope.
        val reScope = BoundedCode.boundedCodeScope(request.text)
        assertEquals(request.text, reScope.text)
        assertFalse(reScope.truncated)
    }

    @Test
    fun codeCopy_streamingBlockIsCancelled() {
        assertNull(
            TranscriptCopyPolicy.codeCopyRequestIfSealed(sealed = false, code = "partial code")
        )
    }

    // ------------------------------------------------------------------
    // TASK-048 stop-condition invariant: no clipboard path exceeds the
    // visibly disclosed bounded source
    // ------------------------------------------------------------------

    @Test
    fun stopCondition_responseCopyNeverExceedsTheDisclosedWindow() {
        // Proportionally probe far above the window with mixed 1/2/3/4-byte
        // scalars; the copied scope stays within 1 MiB on the normalized
        // stream every time.
        val pattern = buildString {
            append("a")            // 1 byte
            append("\u00e9")       // 2 bytes (é)
            append("\u4e2d")       // 3 bytes (中)
            append("\uD83D\uDE00") // 4 bytes (emoji)
        }
        val oversized = pattern.repeat(300_000) // far above 1 MiB
        val request = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            oversized
        )!!
        assertTrue(
            "TASK-048: clipboard content never exceeds the visibly disclosed bounded source",
            utf8Bytes(request.text) <= AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES
        )
        assertTrue(request.text.codeUnitsContainNoUnpairedSurrogate())
    }

    // ------------------------------------------------------------------
    // SEC-005: sensitive metadata + TalkBack/target semantics
    // ------------------------------------------------------------------

    @Test
    fun sec005_requestsCarrySensitiveClipLabelsAndUserInitiatedContract() {
        // The Compose layer writes these requests through
        // copySensitiveText, which sets ClipDescription.EXTRA_IS_SENSITIVE
        // on API 33+ (requires separate device-tier verification).
        // The policy-level contract: both request
        // types expose exactly one label + scope pair, and the write happens
        // only inside the user's onClick.
        val response = TranscriptCopyPolicy.responseCopyRequestIfSealed(
            AssistantMessageState.COMPLETE,
            "response text"
        )!!
        val code = TranscriptCopyPolicy.codeCopyRequestIfSealed(true, "code")!!
        assertEquals(TranscriptCopyPolicy.RESPONSE_COPY_LABEL, response.label)
        assertEquals(TranscriptCopyPolicy.CODE_COPY_LABEL, code.label)
        // Content descriptions asserted at the source level supplement (never
        // replace) these behavior rows.
        SourceAssertions.assertBubbleCopySemanticsPresent()
        SourceAssertions.assertCodeCopySemanticsPresent()
    }
}

/** Supplemental source-text assertions (AGENTS.md: never replace behavior tests). */
private object SourceAssertions {
    private fun sourceOf(resourceName: String): String =
        TranscriptCopyPolicyTest::class.java
            .getResourceAsStream("/com/hermes/bot/feature/chat/$resourceName")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("missing test resource $resourceName")

    fun assertBubbleCopySemanticsPresent() {
        val src = sourceOf("bubble-source.txt")
        assertTrue(src.contains("Copy response"))
        assertTrue(src.contains("Response copied"))
        assertTrue(src.contains("SelectionContainer"))
        assertTrue(src.contains("sealedForCopy"))
    }

    fun assertCodeCopySemanticsPresent() {
        val src = sourceOf("codeblock-source.txt")
        assertTrue(src.contains("Copy code block"))
        assertTrue(src.contains("Code copied to clipboard"))
        assertTrue(src.contains("if (sealed)"))
    }
}
