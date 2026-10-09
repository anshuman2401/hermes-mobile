package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.feature.chat.markdown.BoundedCode

/**
 * BLD-006 S10 (TASK-045/TASK-046, D-04/D-10/D-12/SEC-005): sealed transcript
 * copy policy and clipboard adapter semantics.
 *
 * - Copy/selection are gated to sealed terminal assistant states only; while
 *   a message is STREAMING no copy or selection control is active (TASK-045,
 *   D-12). A null request means "no control, no write".
 * - Response copy exposes ONLY the visible 1 MiB D-04 presentation window,
 *   re-clipped on the surrogate-normalized stream (CON-008) so the clipboard
 *   can never exceed the visibly disclosed bounded source — never a partial
 *   stream, never the hidden over-cap suffix, never the whole 8 MiB
 *   canonical source (TASK-048 stop condition).
 * - Code copy exposes ONLY the bounded D-10 code scope (≤2,000 lines /
 *   256 KiB UTF-8, whichever occurs first, separator-inclusive accounting).
 * - Truncation labels are disclosure text appended after the bounded window;
 *   they are never part of the copied content scope.
 *
 * This type is pure JVM (no android.*): the Compose layer performs the actual
 * user-initiated write through the shared SEC-005 sensitive-clip helper
 * ([io.github.anshuman2401.hermesmobile.feature.chat.components.copySensitiveText]), which marks
 * the clip sensitive where the Android API supports it (API 33+,
 * ASSUMPTION-007).
 */
object TranscriptCopyPolicy {

    /** Frozen D-04 copy ceiling: the 1 MiB presentation window. */
    val RESPONSE_COPY_WINDOW_BYTES: Long =
        AssistantTurnStreamBuffer.PRESENTATION_WINDOW_BYTES

    /** Clipboard label for sealed assistant-response copy. */
    const val RESPONSE_COPY_LABEL: String = "Hermes response"

    /** Clipboard label for bounded code-block copy. */
    const val CODE_COPY_LABEL: String = "Hermes code block"

    /**
     * A user-initiated clipboard write request: the bounded scope text plus
     * the clip label. Built only for sealed messages; the Compose layer
     * writes it verbatim with sensitive content metadata (SEC-005).
     */
    data class TranscriptCopyRequest(val label: String, val text: String)

    /**
     * TASK-045/D-12: copy is allowed for sealed terminal states only.
     * STREAMING is never sealed — no copy/selection control is active
     * during streaming.
     */
    fun sealedForCopy(state: AssistantMessageState): Boolean =
        state != AssistantMessageState.STREAMING

    /**
     * Builds the response copy request for a sealed message, or null while
     * streaming (no control, no write). The scope is the largest
     * scalar-aligned UTF-8 prefix of at most [RESPONSE_COPY_WINDOW_BYTES]
     * bytes of the surrogate-normalized presentation text — exactly the
     * visibly disclosed bounded source, never an over-cap suffix.
     */
    fun responseCopyRequestIfSealed(
        state: AssistantMessageState,
        presentationText: String
    ): TranscriptCopyRequest? {
        if (!sealedForCopy(state)) return null
        val normalized = AssistantTurnStreamBuffer.normalizeSurrogates(presentationText)
        val scope =
            AssistantTurnStreamBuffer.scalarAlignedPrefixUtf8(
                normalized,
                RESPONSE_COPY_WINDOW_BYTES
            )
        return TranscriptCopyRequest(RESPONSE_COPY_LABEL, scope)
    }

    /**
     * Builds the code copy request for a sealed code block, or null while
     * streaming. The scope is the D-10 bounded code scope (TASK-041/
     * [BoundedCode]): at most 2,000 lines and 256 KiB UTF-8 of the
     * surrogate-normalized stream, whichever limit is hit first with
     * separator-inclusive byte accounting — never an over-cap suffix.
     */
    fun codeCopyRequestIfSealed(sealed: Boolean, code: String): TranscriptCopyRequest? {
        if (!sealed) return null
        return TranscriptCopyRequest(CODE_COPY_LABEL, BoundedCode.boundedCodeScope(code).text)
    }
}
