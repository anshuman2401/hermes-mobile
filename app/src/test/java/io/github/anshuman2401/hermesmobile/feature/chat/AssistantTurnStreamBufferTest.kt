package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S4 (TASK-016/TASK-019 / D-04): below/at/above probes at every frozen
 * numeric boundary of [AssistantTurnStreamBuffer], on the surrogate-normalized
 * stream (CON-002/CON-008). Each row records admitted canonical bytes/scalars,
 * presentation bytes/scalars, SOURCE_OVERFLOW, and the exact labels.
 */
class AssistantTurnStreamBufferTest {

    // ------------------------------------------------------------------
    // Admission: every admitted scalar exactly once, in order
    // ------------------------------------------------------------------

    @Test
    fun appendDelta_zeroDelta_retainsNothing() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("")
        assertEquals(0L, buffer.canonicalByteCount)
        assertEquals(0L, buffer.canonicalScalarCount)
        assertFalse(buffer.sourceOverflow)
        assertEquals("", buffer.presentationWindow())
    }

    @Test
    fun appendDelta_oneDelta_admittedExactlyOnce() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("hello")
        assertEquals("hello", buffer.presentationWindow())
        assertEquals(5L, buffer.canonicalByteCount)
        assertEquals(5L, buffer.canonicalScalarCount)
        assertFalse(buffer.sourceOverflow)
    }

    @Test
    fun appendDelta_manyFragments_preservesOrderWithoutDuplication() {
        val buffer = AssistantTurnStreamBuffer()
        val fragments = (1..1000).map { "frag$it|" }
        fragments.forEach { buffer.appendDelta(it) }
        val expected = fragments.joinToString("")
        assertEquals(expected, buffer.presentationWindow())
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), buffer.canonicalByteCount)
        assertFalse(buffer.sourceOverflow)
    }

    @Test
    fun appendDelta_emptyFragment_isIgnored() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("abc")
        buffer.appendDelta("")
        assertEquals("abc", buffer.presentationWindow())
    }

    // ------------------------------------------------------------------
    // Surrogate normalization (CON-002 / CON-008)
    // ------------------------------------------------------------------

    @Test
    fun appendDelta_pairedSurrogates_areAdmittedWhole() {
        val buffer = AssistantTurnStreamBuffer()
        // U+1F600 (4 UTF-8 bytes) as a surrogate pair
        buffer.appendDelta("ok \uD83D\uDE00")
        assertEquals("ok \uD83D\uDE00", buffer.presentationWindow())
        // o=1, k=1, space=1, emoji=4 => 7 bytes, 4 scalars
        assertEquals(7L, buffer.canonicalByteCount)
        assertEquals(4L, buffer.canonicalScalarCount)
    }

    @Test
    fun appendDelta_unpairedSurrogates_areNormalizedToReplacementChar() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("a\uD800b") // lone high surrogate
        buffer.appendDelta("\uDC00c")   // lone low surrogate
        assertEquals("a\uFFFDb\uFFFDc", buffer.presentationWindow())
        // a=1, U+FFFD=3, b=1, U+FFFD=3, c=1 => 9 bytes
        assertEquals(9L, buffer.canonicalByteCount)
        assertEquals(5L, buffer.canonicalScalarCount)
    }

    @Test
    fun appendDelta_splitSurrogatePairAcrossDeltas_isNotAdmittedBroken() {
        // A surrogate pair split across delta boundaries: the high surrogate
        // is normalized to U+FFFD in its own delta; the low surrogate follows
        // in the next delta (also U+FFFD). No broken UTF-8 is ever stored.
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("\uD83D")
        buffer.appendDelta("\uDE00")
        assertEquals("\uFFFD\uFFFD", buffer.presentationWindow())
        assertEquals(6L, buffer.canonicalByteCount)
    }

    // ------------------------------------------------------------------
    // 1 MiB presentation window: below / at / above
    // ------------------------------------------------------------------

    private val asciiWindowBelow = "a".repeat((1L * 1024 * 1024 - 1).toInt())
    private val asciiWindowAt = "a".repeat(1 * 1024 * 1024)
    private val asciiWindowAbove = "a".repeat(1 * 1024 * 1024 + 1)

    @Test
    fun presentation_belowWindow_noLabel() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta(asciiWindowBelow)
        assertFalse(buffer.presentationClipped)
        assertEquals(asciiWindowBelow, buffer.presentationWindow())
        assertEquals((1L * 1024 * 1024 - 1), buffer.canonicalByteCount)
        assertFalse(buffer.sourceOverflow)
    }

    @Test
    fun presentation_atWindow_noLabel() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta(asciiWindowAt)
        assertFalse(buffer.presentationClipped)
        assertEquals(asciiWindowAt, buffer.presentationWindow())
        assertEquals((1L * 1024 * 1024), buffer.canonicalByteCount)
    }

    @Test
    fun presentation_aboveWindow_clipAtScalarBoundary_withExactLabel() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta(asciiWindowAbove)
        assertTrue(buffer.presentationClipped)
        // 1 MiB admitted canonical (hard ceiling not reached)
        assertEquals((1L * 1024 * 1024 + 1), buffer.canonicalByteCount)
        assertFalse(buffer.sourceOverflow)
        // Window shows exactly 1 MiB of scalars + the exact frozen label
        assertEquals(
            asciiWindowAt + AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL,
            buffer.presentationWindow()
        )
    }

    @Test
    fun presentation_windowClip_isScalarAligned_onMultibyteBoundary() {
        // Fill with 3-byte scalars (U+2603 = ☃ SNOWMAN) so the 1 MiB boundary
        // cannot fall inside a UTF-8 sequence. 1 MiB / 3 = 349525 scalars with
        // a 1-byte remainder; the clip must land after 349525 whole scalars.
        val threeByteScalar = "\u2603"
        val count = (AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES / 3).toInt()
        val payload = threeByteScalar.repeat(count + 1) // above the window
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta(payload)

        val window = buffer.presentationWindow()
        // Window = exactly `count` whole scalars + label (label not counted in
        // the byte budget)
        val expectedWindow = threeByteScalar.repeat(count)
        assertTrue(buffer.presentationClipped)
        assertTrue(window.startsWith(expectedWindow))
        assertTrue(window.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL))
        // No surrogate pair or UTF-8 sequence is split: the window is exactly
        // the count whole scalars plus the label.
        assertEquals(expectedWindow.length + AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL.length, window.length)
    }

    @Test
    fun presentation_windowClip_neverSplitsSurrogatePair() {
        // Fill the window with ASCII then place a supplementary scalar (emoji,
        // 4 UTF-8 bytes as a surrogate pair) so it straddles the 1 MiB edge.
        val leading = "a".repeat(1 * 1024 * 1024 - 2) // 2 bytes below the limit
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta(leading)
        buffer.appendDelta("\uD83D\uDE00") // U+1F600, 4 bytes
        val window = buffer.presentationWindow()
        assertTrue(buffer.presentationClipped)
        // The pair straddles the boundary (1 MiB - 2 + 4 > 1 MiB): it must NOT
        // appear partially — the window ends after whole scalars only, i.e.
        // the emoji is not present and the label follows the ASCII prefix.
        assertTrue(window.startsWith(leading))
        assertFalse(window.substringAfter(leading).startsWith("\uD83D"))
        assertTrue(window.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL))
    }

    // ------------------------------------------------------------------
    // 8 MiB canonical hard ceiling: below / at / above (deltas)
    // ------------------------------------------------------------------

    @Test
    fun canonical_belowCeiling_allAdmitted_noOverflow() {
        val buffer = AssistantTurnStreamBuffer()
        // 4 MiB + 1 is below the 8 MiB ceiling: fully admitted across deltas.
        val half = "b".repeat(4 * 1024 * 1024)
        buffer.appendDelta(half)
        buffer.appendDelta("x")
        assertFalse(buffer.sourceOverflow)
        assertEquals(4L * 1024 * 1024 + 1, buffer.canonicalByteCount)
    }

    @Test
    fun canonical_atCeiling_lastByteAdmitted_thenNextDeltaRejected() {
        val buffer = AssistantTurnStreamBuffer()
        val atCeiling = "c".repeat(8 * 1024 * 1024)
        buffer.appendDelta(atCeiling)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        // Exactly at the ceiling: fully admitted, no overflow yet.
        assertFalse(buffer.sourceOverflow)

        // One more scalar cannot fit: whole delta rejected.
        buffer.appendDelta("x")
        assertTrue(buffer.sourceOverflow)
        assertEquals(1L, buffer.rejectedByteCount)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
    }

    @Test
    fun canonical_aboveCeiling_prefixAdmitted_scalarAligned_overflowFlagged() {
        val buffer = AssistantTurnStreamBuffer()
        val over = "d".repeat(8 * 1024 * 1024 + 7)
        buffer.appendDelta(over)
        assertTrue(buffer.sourceOverflow)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        assertEquals(7L, buffer.rejectedByteCount)
        // The presentation window additionally shows the exact overflow label.
        assertTrue(
            buffer.presentationWindow()
                .endsWith(AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL)
        )
        assertTrue(
            buffer.presentationWindow()
                .endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL +
                    AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL)
        )
    }

    @Test
    fun canonical_ceilingBoundary_isScalarAligned_onMultibyteScalars() {
        // Deltas of 3-byte scalars (U+2603 = ☃) crossing the ceiling: the cut
        // must land on a whole scalar, never inside a UTF-8 sequence. First
        // delta = 6 MiB admitted fully; second delta admits the largest
        // whole-scalar prefix of the remaining 2 MiB: 699050 scalars.
        val buffer = AssistantTurnStreamBuffer()
        val chunk = "\u2603".repeat(2 * 1024 * 1024) // 6 MiB of 3-byte scalars
        buffer.appendDelta(chunk)
        buffer.appendDelta(chunk)
        assertTrue(buffer.sourceOverflow)
        // scalars: 2097152 + 699050 = 2796202; bytes: 6 MiB + 2097150 = 8388606
        assertEquals(2796202L, buffer.canonicalScalarCount)
        assertEquals(8388606L, buffer.canonicalByteCount)
        // rejected = emitted (12 MiB) - admitted (8388606 bytes)
        assertEquals(12L * 1024 * 1024 - 8388606L, buffer.rejectedByteCount)
    }

    @Test
    fun canonical_ceilingBoundary_neverSplitsSurrogatePair() {
        // 4-byte emoji scalars: the ceiling cut must not split the pair.
        val buffer = AssistantTurnStreamBuffer()
        val emoji = "\uD83D\uDE00" // 4 UTF-8 bytes, 1 scalar
        // 8 MiB / 4 = exactly 2097152 whole emojis fill the ceiling
        val count = (AssistantTurnStreamBuffer.HARD_CEILING_BYTES / 4).toInt()
        buffer.appendDelta(emoji.repeat(count))      // at ceiling
        buffer.appendDelta(emoji.repeat(count + 1))  // whole-scalar admission
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        assertEquals(2097152L, buffer.canonicalScalarCount)
        assertTrue(buffer.sourceOverflow)
        // At the exact ceiling, remaining = 0: the second delta is rejected
        // whole ((count+1) emojis = 8388612 bytes).
        assertEquals((count.toLong() + 1L) * 4L, buffer.rejectedByteCount)
    }

    // ------------------------------------------------------------------
    // Authoritative terminal text under the same ceiling
    // ------------------------------------------------------------------

    @Test
    fun terminalText_equalLength_replacesCanonical_exactlyOnce() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("streamed ")
        buffer.appendDelta("text")
        buffer.replaceWithTerminalText("streamed text")
        assertEquals("streamed text", buffer.presentationWindow())
        assertEquals("streamed text".length.toLong(), buffer.canonicalScalarCount)
        assertFalse(buffer.sourceOverflow)
        assertFalse(buffer.presentationClipped)
    }

    @Test
    fun terminalText_differentText_replacesAggregate() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("delta A")
        buffer.appendDelta("delta B")
        buffer.replaceWithTerminalText("authoritative terminal text")
        assertEquals("authoritative terminal text", buffer.presentationWindow())
    }

    @Test
    fun terminalText_null_keepsAdmittedCanonical_deltas() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("delta A")
        buffer.appendDelta("delta B")
        buffer.replaceWithTerminalText(null)
        assertEquals("delta Adelta B", buffer.presentationWindow())
        assertFalse(buffer.sourceOverflow)
    }

    @Test
    fun terminalText_null_keepsOverflowState_fromDeltas() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("x".repeat(8 * 1024 * 1024))
        buffer.appendDelta("y")
        buffer.replaceWithTerminalText(null)
        assertTrue(buffer.sourceOverflow)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        assertTrue(
            buffer.presentationWindow()
                .endsWith(AssistantTurnStreamBuffer.SOURCE_OVERFLOW_LABEL)
        )
    }

    @Test
    fun terminalText_belowCeiling_replacesAndClearsOverflow() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("x".repeat(8 * 1024 * 1024))
        buffer.appendDelta("y")
        assertTrue(buffer.sourceOverflow)
        buffer.replaceWithTerminalText("final text")
        assertFalse(buffer.sourceOverflow)
        assertEquals("final text", buffer.presentationWindow())
        assertEquals("final text".length.toLong(), buffer.canonicalScalarCount)
    }

    @Test
    fun terminalText_aboveCeiling_sealsAdmittedPrefix_withOverflow() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("small")
        val over = "e".repeat(8 * 1024 * 1024 + 3)
        buffer.replaceWithTerminalText(over)
        assertTrue(buffer.sourceOverflow)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        assertEquals(3L, buffer.rejectedByteCount)
        // The visible window shows the first 1 MiB of the sealed prefix.
        assertTrue(
            buffer.presentationWindow()
                .startsWith(over.substring(0, AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES.toInt()))
        )
    }

    @Test
    fun terminalText_aboveCeiling_cutIsScalarAligned() {
        val buffer = AssistantTurnStreamBuffer()
        // Terminal text of 3-byte scalars (U+2603 = ☃) above the ceiling:
        // the cut must drop the trailing partial scalar. Largest whole-scalar
        // prefix at or below 8 MiB = 2796202 scalars = 8388606 bytes.
        val scalar = "\u2603"
        val count = (AssistantTurnStreamBuffer.HARD_CEILING_BYTES / 3).toInt() + 2
        buffer.replaceWithTerminalText(scalar.repeat(count))
        assertTrue(buffer.sourceOverflow)
        val whole = AssistantTurnStreamBuffer.HARD_CEILING_BYTES / 3 // 2796202
        assertEquals(whole, buffer.canonicalScalarCount)
        assertEquals(whole * 3L, buffer.canonicalByteCount)
        assertEquals(count.toLong() * 3L - whole * 3L, buffer.rejectedByteCount)
    }

    @Test
    fun terminalText_overCeiling_neverSplitsSurrogatePair() {
        val buffer = AssistantTurnStreamBuffer()
        val emoji = "\uD83D\uDE00"
        // 8 MiB / 4 = exactly whole emojis; add 2 more (8 bytes) so the last
        // whole pair boundary is exactly at the ceiling.
        val count = (AssistantTurnStreamBuffer.HARD_CEILING_BYTES / 4).toInt() + 2
        buffer.replaceWithTerminalText(emoji.repeat(count))
        assertTrue(buffer.sourceOverflow)
        // 2097152 whole emojis admitted (8 MiB), the last 2 rejected whole.
        assertEquals(2097152L, buffer.canonicalScalarCount)
        assertEquals(8L * 1024 * 1024, buffer.canonicalByteCount)
        assertEquals(8L, buffer.rejectedByteCount)
    }

    @Test
    fun terminalText_atWindow_exactlyNoClipLabel() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.replaceWithTerminalText("a".repeat(1 * 1024 * 1024))
        assertFalse(buffer.presentationClipped)
        assertFalse(
            buffer.presentationWindow()
                .contains(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL)
        )
    }

    @Test
    fun terminalText_aboveWindow_showsClipLabel() {
        val buffer = AssistantTurnStreamBuffer()
        buffer.replaceWithTerminalText("a".repeat(1 * 1024 * 1024 + 5))
        assertTrue(buffer.presentationClipped)
        assertTrue(
            buffer.presentationWindow()
                .endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL)
        )
        assertFalse(buffer.sourceOverflow)
    }

    // ------------------------------------------------------------------
    // Presentation window derived only from the first 1 MiB
    // ------------------------------------------------------------------

    @Test
    fun presentationWindow_derivedFromFirstMib_only() {
        val buffer = AssistantTurnStreamBuffer()
        val firstMiB = "f".repeat(1 * 1024 * 1024)
        val tail = "T"
        buffer.appendDelta(firstMiB)
        buffer.appendDelta(tail)
        val window = buffer.presentationWindow()
        // The window holds exactly the first 1 MiB; the tail byte is retained
        // in canonical source only — never silently implied as visible.
        assertTrue(window.startsWith(firstMiB))
        assertTrue(window.endsWith(AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL))
        assertFalse(window.contains(tail))
        assertEquals(1L * 1024 * 1024 + 1, buffer.canonicalByteCount)
        assertFalse(buffer.sourceOverflow)
    }

    @Test
    fun copyScope_isVisibleWindow_only() {
        // The app copies only the visible presentation window (D-04): the
        // window text is what the bubble renders and clipboard receives; the
        // canonical source never leaves the buffer.
        val buffer = AssistantTurnStreamBuffer()
        buffer.appendDelta("a".repeat(1 * 1024 * 1024 + 10))
        val window = buffer.presentationWindow()
        assertTrue(window.length <= 1 * 1024 * 1024 +
            AssistantTurnStreamBuffer.PRESENTATION_TRUNCATION_LABEL.length)
    }
}
