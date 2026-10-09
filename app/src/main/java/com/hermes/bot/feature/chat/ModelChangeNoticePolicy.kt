package com.hermes.bot.feature.chat

import java.util.Locale

/**
 * CHAT-017 (frozen plan v1, TASK-001): the pure policy behind the one-line
 * light-gray model/reasoning change notice rendered above an assistant reply.
 *
 * Pure Kotlin — no Android/Compose imports, no host I/O, no clock. The only
 * inputs are the four parameters (REQ-007: no synthesized names, no cached
 * defaults, no other state). The caller (ChatViewModel) extracts values
 * exclusively through [SessionModelLabelPolicy] extraction of host-reported
 * `SessionInfo.info` data.
 *
 * Copy rules (frozen per requirements REQ-002):
 * - Model change only:  `Model changed to <model>`
 * - Effort change only: `Reasoning changed to <effort>`
 * - Both changed:       joined with the [SessionModelLabelPolicy] separator
 *                       semantics (single ` · `, U+00B7 with single spaces)
 * - Effort cleared (previously non-blank -> blank/null): the effort component
 *   reads `Reasoning changed to provider default` (blank is the host's
 *   documented provider-default encoding — not a fabrication).
 * - Model name verbatim (trimmed); effort lowercased via [Locale.ROOT],
 *   consistent with the caption policy.
 */
object ModelChangeNoticePolicy {

    /** U+00B7 MIDDLE DOT with single spaces (SessionModelLabelPolicy semantics). */
    private const val LABEL_SEPARATOR = " · "

    private const val MODEL_CHANGED_PREFIX = "Model changed to "
    private const val REASONING_CHANGED_PREFIX = "Reasoning changed to "
    private const val REASONING_PROVIDER_DEFAULT_COPY = "Reasoning changed to provider default"

    /**
     * The one-line notice text for this report, or null when nothing changed
     * (or when no previous report exists — the first report is never a
     * change, REQ-004).
     *
     * Normalization: both sides are trimmed; blank and null are equivalent
     * ("no value"). A fired component list is joined with the single
     * ` · ` separator; no fired components -> null.
     */
    fun diff(
        previousModel: String?,
        previousEffort: String?,
        newModel: String?,
        newEffort: String?
    ): String? {
        val prevModel = previousModel?.trim().takeUnless { it.isNullOrBlank() }
        val prevEffort = previousEffort?.trim().takeUnless { it.isNullOrBlank() }

        // Rule 1: no previous report (either field) -> first report, never
        // a change notice (REQ-004).
        if (prevModel == null && prevEffort == null) return null

        val newModelTrimmed = newModel?.trim().takeUnless { it.isNullOrBlank() }
        val newEffort = newEffort?.trim().takeUnless { it.isNullOrBlank() }

        // Rule 2: the model component fires when the new model is non-blank
        // and differs from the trimmed previous model (a null previous model
        // counts as differing — the host reported a model where none was
        // known before).
        val modelComponent = newModelTrimmed
            ?.takeIf { it != prevModel }
            ?.let { MODEL_CHANGED_PREFIX + it }

        // Rule 3: the effort component fires when the trimmed/lowercased new
        // effort differs from the trimmed/lowercased previous effort. A
        // cleared effort (new blank/null after a non-blank previous) is the
        // host's provider-default encoding -> the documented default copy.
        val newEffortComparable = newEffort?.lowercase(Locale.ROOT)
        val prevEffortComparable = prevEffort?.lowercase(Locale.ROOT)
        val effortComponent = when {
            newEffort != null && newEffortComparable != prevEffortComparable ->
                REASONING_CHANGED_PREFIX + newEffortComparable
            newEffort == null && prevEffort != null -> REASONING_PROVIDER_DEFAULT_COPY
            else -> null
        }

        // Rule 4: join fired components with the single ` · ` separator;
        // no components -> null.
        return listOfNotNull(modelComponent, effortComponent)
            .joinToString(LABEL_SEPARATOR)
            .ifEmpty { null }
    }
}