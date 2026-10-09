package io.github.anshuman2401.hermesmobile.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [SessionsRowMetaPolicy].
 * Pure policy tests verifying message count pluralization and source label formatting.
 */
class SessionsRowMetaPolicyTest {

    @Test
    fun messageLabel_zeroCount_returnsPluralMessages() {
        assertEquals("0 messages", SessionsRowMetaPolicy.messageLabel(0))
    }

    @Test
    fun messageLabel_singularCount_returnsSingularMessage() {
        assertEquals("1 message", SessionsRowMetaPolicy.messageLabel(1))
    }

    @Test
    fun messageLabel_pluralCounts_returnsPluralMessages() {
        assertEquals("2 messages", SessionsRowMetaPolicy.messageLabel(2))
        assertEquals("5 messages", SessionsRowMetaPolicy.messageLabel(5))
        assertEquals("12 messages", SessionsRowMetaPolicy.messageLabel(12))
        assertEquals("100 messages", SessionsRowMetaPolicy.messageLabel(100))
    }

    @Test
    fun messageLabel_negativeCount_returnsPluralMessages() {
        assertEquals("-1 messages", SessionsRowMetaPolicy.messageLabel(-1))
    }

    @Test
    fun sourceLabel_nonBlankSource_returnsFormattedSource() {
        assertEquals("Source: human", SessionsRowMetaPolicy.sourceLabel("human"))
        assertEquals("Source: cli", SessionsRowMetaPolicy.sourceLabel("cli"))
        assertEquals("Source: telegram", SessionsRowMetaPolicy.sourceLabel("telegram"))
    }

    @Test
    fun sourceLabel_sourceWithWhitespace_returnsTrimmedFormattedSource() {
        assertEquals("Source: human", SessionsRowMetaPolicy.sourceLabel("  human  "))
        assertEquals("Source: agent", SessionsRowMetaPolicy.sourceLabel("\tagent\n"))
    }

    @Test
    fun sourceLabel_nullOrBlankSource_returnsNull() {
        assertNull(SessionsRowMetaPolicy.sourceLabel(null))
        assertNull(SessionsRowMetaPolicy.sourceLabel(""))
        assertNull(SessionsRowMetaPolicy.sourceLabel("   "))
        assertNull(SessionsRowMetaPolicy.sourceLabel("\t\n"))
    }

    @Test
    fun modelLabel_exactKeyMatch_returnsModel() {
        val models = mapOf("sess-1" to "claude-3-5-sonnet", "sess-2" to "gpt-4o")
        assertEquals("claude-3-5-sonnet", SessionsRowMetaPolicy.modelLabel(5, models, "sess-1"))
        assertEquals("gpt-4o", SessionsRowMetaPolicy.modelLabel(0, models, "sess-2"))
    }

    @Test
    fun modelLabel_blankOrWhitespaceModel_returnsNull() {
        val models = mapOf("sess-1" to "", "sess-2" to "   ", "sess-3" to "\t\n")
        assertNull(SessionsRowMetaPolicy.modelLabel(1, models, "sess-1"))
        assertNull(SessionsRowMetaPolicy.modelLabel(2, models, "sess-2"))
        assertNull(SessionsRowMetaPolicy.modelLabel(3, models, "sess-3"))
    }

    @Test
    fun modelLabel_absentKeyOrEmptyMap_returnsNull() {
        val models = mapOf("sess-1" to "claude-3-5-sonnet")
        assertNull(SessionsRowMetaPolicy.modelLabel(1, models, "sess-unknown"))
        assertNull(SessionsRowMetaPolicy.modelLabel(1, emptyMap(), "sess-1"))
    }

    @Test
    fun policy_isPureAndDeterministic() {
        repeat(3) {
            assertEquals("0 messages", SessionsRowMetaPolicy.messageLabel(0))
            assertEquals("1 message", SessionsRowMetaPolicy.messageLabel(1))
            assertEquals("12 messages", SessionsRowMetaPolicy.messageLabel(12))
            assertEquals("Source: human", SessionsRowMetaPolicy.sourceLabel("human"))
            assertNull(SessionsRowMetaPolicy.sourceLabel(null))
            assertNull(SessionsRowMetaPolicy.sourceLabel(""))
            assertNull(SessionsRowMetaPolicy.sourceLabel("   "))
            val models = mapOf("s-1" to "claude-3-5-sonnet")
            assertEquals("claude-3-5-sonnet", SessionsRowMetaPolicy.modelLabel(1, models, "s-1"))
            assertNull(SessionsRowMetaPolicy.modelLabel(1, models, "s-2"))
        }
    }
}
