package com.hermes.bot.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CHAT-010 (frozen plan v1, T7): the policy table for the session model +
 * reasoning caption. Pure mapping only — the UI never formats.
 */
class SessionModelLabelPolicyTest {

    // Baseline: model + non-blank effort -> the frozen copy format.
    @Test
    fun modelAndEffort_joinedByMiddleDot() {
        assertEquals(
            "gpt-5.6-luna · high",
            SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", "high")
        )
    }

    // Blank/absent effort = provider default (unknown to the client) ->
    // model only.
    @Test
    fun blankEffort_showsModelOnly() {
        assertEquals(
            "gpt-5.6-luna",
            SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", null)
        )
        assertEquals(
            "gpt-5.6-luna",
            SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", "")
        )
        assertEquals(
            "gpt-5.6-luna",
            SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", "   ")
        )
    }

    // Host never reported a model -> the row is absent. Never "Unknown",
    // never a placeholder.
    @Test
    fun blankOrNullModel_returnsNull() {
        assertNull(SessionModelLabelPolicy.displayLabel(null, "high"))
        assertNull(SessionModelLabelPolicy.displayLabel("", "high"))
        assertNull(SessionModelLabelPolicy.displayLabel("   ", null))
    }

    // Effort vocabulary is shown verbatim; `none` is a real host value
    // (reasoning disabled) and must NOT be dropped or reworded.
    @Test
    fun effortNone_shownVerbatim() {
        assertEquals(
            "gpt-5.6-luna · none",
            SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", "none")
        )
    }

    // Whitespace trimmed on both fields; effort lowercased via
    // Locale.ROOT (host vocabulary is lowercase; never locale-uppercased).
    @Test
    fun whitespaceTrimmed_effortLowercasedViaRootLocale() {
        assertEquals(
            "gpt-5.6-luna · high",
            SessionModelLabelPolicy.displayLabel("  gpt-5.6-luna  ", " High ")
        )
        assertEquals(
            "gpt-5.6-luna",
            SessionModelLabelPolicy.displayLabel("  gpt-5.6-luna  ", null)
        )
    }

    // The certified effort vocabulary all render through the same join —
    // no value is special-cased or filtered (host hermes_constants.
    // VALID_REASONING_EFFORTS plus `none`).
    @Test
    fun fullEffortVocabulary_rendersVerbatim() {
        val efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra", "minimal", "none")
        efforts.forEach { effort ->
            assertEquals(
                "gpt-5.6-luna · $effort",
                SessionModelLabelPolicy.displayLabel("gpt-5.6-luna", effort)
            )
        }
    }

    // Raw extraction from a host info map: model / reasoning_effort keys,
    // non-string/absent/blank read as null — never fabricated.
    @Test
    fun infoExtraction_modelAndEffort_nonStringOrBlankReadAsNull() {
        val info = mapOf<String, Any?>(
            "model" to "gpt-5.6-luna",
            "reasoning_effort" to "high"
        )
        assertEquals("gpt-5.6-luna", SessionModelLabelPolicy.modelFromInfo(info))
        assertEquals("high", SessionModelLabelPolicy.reasoningEffortFromInfo(info))

        // Blank effort (the host's provider-default encoding) -> null.
        val blankEffort = mapOf<String, Any?>("model" to "m", "reasoning_effort" to "  ")
        assertEquals("m", SessionModelLabelPolicy.modelFromInfo(blankEffort))
        assertNull(SessionModelLabelPolicy.reasoningEffortFromInfo(blankEffort))

        // Absent keys / non-string values -> null, never fabricated.
        assertNull(SessionModelLabelPolicy.modelFromInfo(emptyMap()))
        assertNull(SessionModelLabelPolicy.reasoningEffortFromInfo(emptyMap()))
        val nonString = mapOf<String, Any?>("model" to 42, "reasoning_effort" to mapOf("a" to 1))
        assertNull(SessionModelLabelPolicy.modelFromInfo(nonString))
        assertNull(SessionModelLabelPolicy.reasoningEffortFromInfo(nonString))
    }
}