package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior tests for [UserMessageCollapsePolicy] (owner directive 2026-09-27):
 * user bubbles >300 chars collapse to exactly 300 chars with a bottom-right
 * "Show more" expand control; expansion restores the full text verbatim.
 */
class UserMessageCollapsePolicyTest {

    private fun longText(n: Int): String = "x".repeat(n)

    @Test
    fun shouldCollapse_isFalseAtExactlyThreshold() {
        assertFalse(UserMessageCollapsePolicy.shouldCollapse(longText(300)))
    }

    @Test
    fun shouldCollapse_isTrueAboveThreshold() {
        assertTrue(UserMessageCollapsePolicy.shouldCollapse(longText(301)))
        assertTrue(UserMessageCollapsePolicy.shouldCollapse(longText(5_000)))
    }

    @Test
    fun shouldCollapse_isFalseForShortAndEmpty() {
        assertFalse(UserMessageCollapsePolicy.shouldCollapse(""))
        assertFalse(UserMessageCollapsePolicy.shouldCollapse("hello"))
    }

    @Test
    fun collapsedText_truncatesToExactly300Chars_whenCollapsed() {
        val text = longText(1_000)
        val shown = UserMessageCollapsePolicy.collapsedText(text, expanded = false)
        assertEquals(300, shown.length)
        assertEquals(text.take(300), shown)
    }

    @Test
    fun collapsedText_returnsFullTextVerbatim_whenExpanded() {
        val text = longText(1_000)
        assertEquals(
            text,
            UserMessageCollapsePolicy.collapsedText(text, expanded = true)
        )
    }

    @Test
    fun collapsedText_returnsFullText_forShortMessages() {
        assertEquals("short", UserMessageCollapsePolicy.collapsedText("short", expanded = false))
        assertEquals("", UserMessageCollapsePolicy.collapsedText("", expanded = false))
    }

    @Test
    fun threshold_isOwnerSpecified300_andLabelFrozen() {
        assertEquals(300, UserMessageCollapsePolicy.COLLAPSE_THRESHOLD_CHARS)
        assertEquals("Show more", UserMessageCollapsePolicy.SHOW_MORE_LABEL)
    }

    @Test
    fun collapsedPrefix_isExactlyTheFirst300Chars_inOrder() {
        // Deterministic layout: 299 'A's, then a unique 'B' at position 300
        // (0-indexed 299), then trailing 'C's. The collapsed preview must end
        // exactly at the 'B' — proving it takes the FIRST 300 chars in order.
        val text = "A".repeat(299) + "B" + "C".repeat(100)
        val shown = UserMessageCollapsePolicy.collapsedText(text, expanded = false)
        assertEquals(300, shown.length)
        assertEquals("A".repeat(299) + "B", shown)
    }
}