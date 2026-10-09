package io.github.anshuman2401.hermesmobile.feature.chat

import java.util.Locale

/**
 * CHAT-010 (frozen plan v1): the pure mapping policy behind the session
 * model + reasoning caption shown below the composer input row.
 *
 * Single formatting authority: the UI (ChatComposer caption row) renders
 * [ChatUiState.sessionModelLabel] verbatim and never formats strings
 * itself. Host data only — the policy never fabricates a model, never
 * substitutes a placeholder, and never derives a label from anything but
 * the two host-reported fields.
 */
object SessionModelLabelPolicy {

    /** U+00B7 MIDDLE DOT with single spaces, per the frozen copy format. */
    private const val LABEL_SEPARATOR = " · "

    /**
     * The caption text, or null when the row must be absent.
     *
     * - null/blank model -> null (host never reported one — show nothing,
     *   never "Unknown").
     * - blank/absent effort -> model only (blank host effort means provider
     *   default, which is unknown to the client).
     * - non-blank effort -> "<model> · <effort>" — effort shown
     *   verbatim (`none` is a real host vocabulary value = reasoning
     *   disabled), lowercased via [Locale.ROOT], whitespace trimmed.
     */
    fun displayLabel(model: String?, reasoningEffort: String?): String? {
        val trimmedModel = model?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val trimmedEffort = reasoningEffort?.trim().takeUnless { it.isNullOrBlank() }
            ?.lowercase(Locale.ROOT)
        return if (trimmedEffort == null) {
            trimmedModel
        } else {
            "$trimmedModel$LABEL_SEPARATOR$trimmedEffort"
        }
    }

    /**
     * Raw `model` extraction from a host info map (session.resume result /
     * session.info event). Non-string, absent, or blank values read as
     * null — never fabricated.
     */
    fun modelFromInfo(info: Map<String, Any?>): String? =
        (info["model"] as? String)?.trim().takeUnless { it.isNullOrBlank() }

    /**
     * Raw `reasoning_effort` extraction from a host info map. Blank (the
     * host's provider-default encoding) reads as null — shown as model only.
     */
    fun reasoningEffortFromInfo(info: Map<String, Any?>): String? =
        (info["reasoning_effort"] as? String)?.trim().takeUnless { it.isNullOrBlank() }
}
