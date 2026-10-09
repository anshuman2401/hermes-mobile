package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CHAT-017 (frozen plan v1, TASK-005 / TASK-001): the pure policy table for
 * the model/reasoning change notice — each rule independently unit-tested,
 * including blank-vs-null equivalence, cleared-effort copy, the both-changed
 * join, first-report null, and identical-repeat null.
 */
class ModelChangeNoticePolicyTest {

    // Rule 1: no previous report (both null) -> null (first report, REQ-004).
    @Test
    fun firstReport_bothPreviousNull_returnsNull() {
        assertNull(ModelChangeNoticePolicy.diff(null, null, "new-model", "high"))
        assertNull(ModelChangeNoticePolicy.diff(null, null, null, null))
        assertNull(ModelChangeNoticePolicy.diff(null, null, "new-model", null))
    }

    // Rule 1 (blank-vs-null equivalence): blank previous fields are "no
    // previous report" too — never a change.
    @Test
    fun firstReport_blankPreviousEqualsNull_returnsNull() {
        assertNull(ModelChangeNoticePolicy.diff("", "", "new-model", "high"))
        assertNull(ModelChangeNoticePolicy.diff("   ", null, "new-model", "high"))
        assertNull(ModelChangeNoticePolicy.diff(null, "  ", "new-model", "high"))
    }

    // Rule 2: model-only change -> the frozen model copy.
    @Test
    fun modelOnlyChange_modelCopy() {
        assertEquals(
            "Model changed to claude-opus-4-6",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "claude-opus-4-6", "high")
        )
    }

    // Rule 3: effort-only change -> the frozen reasoning copy.
    @Test
    fun effortOnlyChange_reasoningCopy() {
        assertEquals(
            "Reasoning changed to low",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "gpt-5.6-luna", "low")
        )
    }

    // Rule 4: both changed -> single combined line joined with ` · `.
    @Test
    fun bothChanged_joinedWithMiddleDot() {
        assertEquals(
            "Model changed to claude-opus-4-6 · Reasoning changed to medium",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "claude-opus-4-6", "medium")
        )
    }

    // Rule 3: cleared effort (previously non-blank -> blank/null) -> the
    // provider-default copy (blank is the host's provider-default encoding).
    @Test
    fun clearedEffort_providerDefaultCopy() {
        assertEquals(
            "Reasoning changed to provider default",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "gpt-5.6-luna", null)
        )
        assertEquals(
            "Reasoning changed to provider default",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "gpt-5.6-luna", "  ")
        )
    }

    // Cleared effort + model change -> combined line with the default copy.
    @Test
    fun clearedEffort_withModelChange_combinedLine() {
        assertEquals(
            "Model changed to claude-opus-4-6 · Reasoning changed to provider default",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "claude-opus-4-6", null)
        )
    }

    // Identical repeat report -> null (REQ-006); values compared after
    // trim/lowercase normalization, never by raw identity.
    @Test
    fun identicalRepeatReport_returnsNull() {
        assertNull(ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "gpt-5.6-luna", "high"))
        // Blank-vs-null equivalence on the NEW side: a blank new value reads
        // as cleared only when the previous was non-blank; equal "no value"
        // states never fire.
        assertNull(ModelChangeNoticePolicy.diff("gpt-5.6-luna", null, "gpt-5.6-luna", ""))
        assertNull(ModelChangeNoticePolicy.diff("gpt-5.6-luna", "", "gpt-5.6-luna", null))
    }

    // Whitespace trim + Locale.ROOT lowercasing are applied on BOTH sides.
    @Test
    fun trimAndRootLocaleCaseFold_beforeComparison() {
        // Same values modulo trim/case -> no change.
        assertNull(ModelChangeNoticePolicy.diff("  gpt-5.6-luna  ", " High ", "gpt-5.6-luna", "high"))
        // Different only by case -> no change (effort vocabulary is
        // case-folded like the caption).
        assertNull(ModelChangeNoticePolicy.diff("gpt-5.6-luna", "HIGH", "gpt-5.6-luna", "high"))
        // Trimmed model compare: padded new model equals previous -> no model
        // component.
        assertNull(ModelChangeNoticePolicy.diff("m1", "low", " m1 ", "low"))
    }

    // A null previous model counts as differing (rule 2) when a previous
    // effort existed (so rule 1 does not short-circuit).
    @Test
    fun nullPreviousModel_countsAsDiffering() {
        assertEquals(
            "Model changed to gpt-5.6-luna",
            ModelChangeNoticePolicy.diff(null, "high", "gpt-5.6-luna", "high")
        )
    }

    // A null previous effort counts as differing when a previous model
    // existed (rule 3) -> effort component fires.
    @Test
    fun nullPreviousEffort_effortComponentFires() {
        assertEquals(
            "Reasoning changed to high",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", null, "gpt-5.6-luna", "high")
        )
    }

    // A blank new model never fabricates a model component; a same-model
    // report with only the effort changed stays effort-only.
    @Test
    fun blankNewModel_neverFabricatesModelComponent() {
        assertEquals(
            "Reasoning changed to provider default",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", null, null)
        )
        assertEquals(
            "Reasoning changed to provider default",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "", null)
        )
    }

    // The effort value is rendered lowercased (Locale.ROOT), verbatim
    // otherwise — `none` is a real host value and is never reworded.
    @Test
    fun effortRenderedLowercased_noneVerbatim() {
        assertEquals(
            "Reasoning changed to xhigh",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "low", "gpt-5.6-luna", "XHIGH")
        )
        assertEquals(
            "Reasoning changed to none",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "gpt-5.6-luna", "None")
        )
    }

    // The model name is rendered verbatim (trimmed only) — never lowercased.
    @Test
    fun modelRenderedVerbatimTrimmed() {
        assertEquals(
            "Model changed to GPT-5.6-Luna",
            ModelChangeNoticePolicy.diff("gpt-5.6-luna", "high", "  GPT-5.6-Luna  ", "high")
        )
    }
}