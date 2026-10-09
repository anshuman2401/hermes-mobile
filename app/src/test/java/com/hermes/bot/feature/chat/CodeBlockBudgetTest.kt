package com.hermes.bot.feature.chat

import com.hermes.bot.feature.chat.markdown.BoundedCode
import com.hermes.bot.feature.chat.markdown.CodeBlockBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S9 (TASK-041, M-05): D-10 code budgets — 60-line initial preview,
 * 2,000-line / 256 KiB UTF-8 expanded maximum — probed below/at/above on
 * both the line axis and the UTF-8 byte axis, on the surrogate-normalized
 * stream (CON-008): no surrogate pair or UTF-8 sequence is ever split.
 */
class CodeBlockBudgetTest {

    // ------------------------------------------------------------------
    // Line-axis probes (2,000-line maximum)
    // ------------------------------------------------------------------

    @Test
    fun boundedCode_lineAxis_below_at_above() {
        fun code(lines: Int) = buildString {
            for (i in 1..lines) {
                append("line ").append(i)
                if (i < lines) append('\n')
            }
        }

        val below = BoundedCode.boundedCodeScope(code(CodeBlockBudget.MAX_LINES - 1))
        assertFalse(below.truncated)
        assertEquals(
            CodeBlockBudget.MAX_LINES - 1,
            below.text.split("\n").size
        )

        val at = BoundedCode.boundedCodeScope(code(CodeBlockBudget.MAX_LINES))
        assertFalse(at.truncated)
        assertEquals(CodeBlockBudget.MAX_LINES, at.text.split("\n").size)

        val above = BoundedCode.boundedCodeScope(code(CodeBlockBudget.MAX_LINES + 7))
        assertTrue(above.truncated)
        assertEquals(
            "line axis: exactly the frozen cap is kept",
            CodeBlockBudget.MAX_LINES,
            above.text.split("\n").size
        )
    }

    // ------------------------------------------------------------------
    // Byte-axis probes (256 KiB UTF-8 maximum)
    // ------------------------------------------------------------------

    @Test
    fun boundedCode_byteAxis_below_at_above() {
        // Single-line probes make the byte accounting exact: no separator
        // bytes are involved.
        val below = BoundedCode.boundedCodeScope("a".repeat(CodeBlockBudget.MAX_UTF8_BYTES - 1))
        assertFalse("below the byte budget nothing is truncated", below.truncated)
        assertEquals(CodeBlockBudget.MAX_UTF8_BYTES - 1, utf8Bytes(below.text))

        val at = BoundedCode.boundedCodeScope("a".repeat(CodeBlockBudget.MAX_UTF8_BYTES))
        assertFalse("exactly at the byte budget nothing is truncated", at.truncated)
        assertEquals(
            "at-budget content is retained whole",
            CodeBlockBudget.MAX_UTF8_BYTES,
            utf8Bytes(at.text)
        )

        val above = BoundedCode.boundedCodeScope("a".repeat(CodeBlockBudget.MAX_UTF8_BYTES + 3))
        assertTrue("above the byte budget the scope is truncated", above.truncated)
        assertEquals(
            "the scalar-aligned prefix is kept",
            CodeBlockBudget.MAX_UTF8_BYTES,
            utf8Bytes(above.text)
        )
    }

    @Test
    fun boundedCode_byteBudget_cutEndsOnScalarBoundary() {
        // Lines of 4-byte emoji (U+1F600, surrogate pair in UTF-16): a
        // byte-budget cut must land on a scalar boundary, never inside a
        // surrogate pair or UTF-8 sequence.
        val line = "\uD83D\uDE00".repeat(300) // 1200 UTF-8 bytes per line
        val code = buildString {
            for (i in 1..300) {
                append(line)
                if (i < 300) append('\n')
            }
        }
        val scope = BoundedCode.boundedCodeScope(code)
        assertTrue(scope.truncated)
        assertTrue(utf8Bytes(scope.text) <= CodeBlockBudget.MAX_UTF8_BYTES)
        // The cut lands on a scalar boundary: within the final (partial)
        // line, kept emoji bytes are a multiple of 4 — separators from
        // earlier lines make the total non-multiple, so check the tail.
        val tail = scope.text.substringAfterLast('\n', scope.text)
        assertTrue(tail.isNotEmpty())
        assertEquals(0, utf8Bytes(tail) % 4)
        assertTrue(scope.text.codeUnitsContainNoUnpairedSurrogate())
    }

    // ------------------------------------------------------------------
    // CON-008 surrogate safety
    // ------------------------------------------------------------------

    @Test
    fun boundedCode_unpairedSurrogatesNormalizeAndNeverSplit() {
        val code = "plain\n\uD83D unpaired\n\uDE00 lone low\n\uD83D\uDE00 pair\nlast"
        val scope = BoundedCode.boundedCodeScope(code)
        assertFalse(scope.truncated)
        assertTrue(scope.text.contains("plain"))
        assertTrue(scope.text.contains("last"))
        assertTrue(
            "no unpaired surrogate survives",
            scope.text.codeUnitsContainNoUnpairedSurrogate()
        )
        assertTrue(scope.text.contains("\uD83D\uDE00"))
    }

    // ------------------------------------------------------------------
    // Whichever limit occurs first
    // ------------------------------------------------------------------

    @Test
    fun boundedCode_whicheverLimitFirst_stopsTheScan() {
        // 100 KiB of ASCII: under the 256 KiB byte budget but over 2,000 lines.
        val lineCountOver = CodeBlockBudget.MAX_LINES + 1
        val linesOver = buildString {
            for (i in 1..lineCountOver) {
                append("x")
                if (i < lineCountOver) append('\n')
            }
        }
        val overLines = BoundedCode.boundedCodeScope(linesOver)
        assertTrue("2,000 lines hit before the byte budget", overLines.truncated)

        // 256 KiB + 1 bytes in ONE line: over the byte budget, under 2,000 lines.
        val oneHugeLine = "y".repeat(CodeBlockBudget.MAX_UTF8_BYTES + 1)
        val overBytes = BoundedCode.boundedCodeScope(oneHugeLine)
        assertTrue("256 KiB hit before the line budget", overBytes.truncated)
        assertEquals(
            "the scalar-aligned prefix is kept",
            CodeBlockBudget.MAX_UTF8_BYTES,
            utf8Bytes(overBytes.text)
        )
    }

    @Test
    fun boundedCode_emptyInput() {
        val scope = BoundedCode.boundedCodeScope("")
        assertFalse(scope.truncated)
        assertEquals("", scope.text)
    }

    // ------------------------------------------------------------------
    // Multi-line at-boundary probes (separator-inclusive accounting)
    // ------------------------------------------------------------------

    @Test
    fun boundedCode_multiLine_byteBudgetAtBoundary_isSeparatorInclusive() {
        // F1/F2 regression probe: the 256 KiB budget counts the '\n'
        // separator bytes that join kept lines. A prior line exactly fills
        // the budget, so the NEXT whole line can no longer be kept whole:
        // the cut must happen before any of it (or its separator) is
        // emitted, leaving kept bytes <= MAX_UTF8_BYTES.
        val fillerLine = "a".repeat(CodeBlockBudget.MAX_UTF8_BYTES - 1)
        val code = fillerLine + "\nb\nc"
        val scope = BoundedCode.boundedCodeScope(code)
        assertTrue("over-budget source is truncated", scope.truncated)
        assertTrue(
            "kept UTF-8 bytes never exceed the frozen budget",
            utf8Bytes(scope.text) <= CodeBlockBudget.MAX_UTF8_BYTES
        )
    }

    @Test
    fun boundedCode_multiLine_linesAtBoundaryWithExactByteTotal_keepsFittingLine() {
        // F2 regression probe: MAX_LINES lines whose separator-inclusive
        // UTF-8 total is EXACTLY MAX_UTF8_BYTES must all be kept whole.
        // The scope holds MAX_LINES lines (not MAX_LINES - 1) and nothing
        // is truncated.
        val lineBytes = 130
        val lastLineBytes = CodeBlockBudget.MAX_UTF8_BYTES -
            (CodeBlockBudget.MAX_LINES - 1) * (lineBytes + 1)
        assertTrue(lastLineBytes > 0)
        val code = buildString {
            repeat(CodeBlockBudget.MAX_LINES - 1) {
                append("a".repeat(lineBytes))
                append('\n')
            }
            append("b".repeat(lastLineBytes))
        }
        assertEquals(CodeBlockBudget.MAX_UTF8_BYTES, utf8Bytes(code))

        val scope = BoundedCode.boundedCodeScope(code)
        assertFalse("at-boundary content is retained whole", scope.truncated)
        assertEquals(
            "2000 fitting lines are all kept (D-10 permits 2,000)",
            CodeBlockBudget.MAX_LINES,
            scope.text.split("\n").size
        )
        assertEquals(CodeBlockBudget.MAX_UTF8_BYTES, utf8Bytes(scope.text))
    }

    private fun utf8Bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size

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
}
