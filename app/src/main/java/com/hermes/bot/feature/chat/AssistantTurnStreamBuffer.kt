package com.hermes.bot.feature.chat

/**
 * BLD-006 S4 (TASK-016 / D-04): enforcing type for the two explicit text
 * layers of an active assistant turn.
 *
 * 1. A canonical retained source, lossless up to the frozen 8 MiB UTF-8 hard
 *    ceiling. Deltas are admitted incrementally — bounds are enforced during
 *    append (PAT-003, bounds-before-materialization): each admission keeps the
 *    normalized source at or below the ceiling and ends on a Unicode-scalar
 *    boundary (CON-008). Every admitted scalar appears exactly once and in
 *    order. Once the ceiling is reached, later text is rejected from local
 *    retention and [sourceOverflow] is raised; the omitted suffix is never
 *    claimed as stored.
 *
 * 2. A rich-presentation window derived from the first 1 MiB of canonical
 *    source. The window never silently implies completeness: 1 MiB clipping
 *    shows [PRESENTATION_TRUNCATION_LABEL] and hard-ceiling overflow
 *    additionally shows [SOURCE_OVERFLOW_LABEL] (D-04).
 *
 * Authoritative terminal text replaces canonical source only after applying
 * the same 8 MiB ceiling; an over-cap terminal text seals the admitted prefix
 * with the overflow flag. A null terminal text retains the lossless admitted
 * canonical delta text, including any explicit overflow state.
 *
 * All admission happens on the surrogate-normalized stream: unpaired UTF-16
 * surrogates are normalized to U+FFFD first, so no surrogate pair or UTF-8
 * sequence is ever split and byte counts are measured on the normalized text
 * (CON-002 / CON-008).
 *
 * Internal representation uses incremental appends so per-delta work is
 * bounded by the delta size (plus one bounded 1 MiB clip scan when the
 * presentation window fills) — never a full re-scan per delta.
 *
 * Publication cadence (32 ms coalescing) is owned by S5 and intentionally out
 * of scope here.
 */
class AssistantTurnStreamBuffer {

    /** Terminal/display flag: the canonical source hit the 8 MiB ceiling. */
    var sourceOverflow: Boolean = false
        private set

    /**
     * True when the canonical source exceeded the 1 MiB presentation window
     * and the visible window is clipped (D-04: the window never silently
     * implies completeness).
     */
    var presentationClipped: Boolean = false
        private set

    /** UTF-8 byte count of the canonical retained source (normalized stream). */
    var canonicalByteCount: Long = 0L
        private set

    /** Unicode scalar count of the canonical retained source (normalized stream). */
    var canonicalScalarCount: Long = 0L
        private set

    /**
     * Number of canonical bytes rejected from local retention after the hard
     * ceiling was reached (diagnostic only; never rendered as stored text).
     */
    var rejectedByteCount: Long = 0L
        private set

    private val canonical = StringBuilder()
    private val presentation = StringBuilder()
    private var presentationBytes = 0L
    private var presentationFrozen = false

    /**
     * Appends a streamed delta to the canonical source.
     *
     * Admission is incremental: the delta is surrogate-normalized first, then
     * only the largest scalar-aligned prefix that fits under the 8 MiB ceiling
     * is retained; the rejected suffix (if any) is counted, never materialized
     * into the source, and [sourceOverflow] is raised.
     */
    fun appendDelta(delta: String) {
        if (delta.isEmpty()) return
        val normalized = normalizeSurrogates(delta)
        if (normalized.isEmpty()) return
        val normalizedBytes = utf8ByteCount(normalized)
        val remaining = HARD_CEILING_BYTES - canonicalByteCount

        if (remaining <= 0L) {
            // Ceiling already reached: reject the whole delta.
            rejectedByteCount += normalizedBytes
            sourceOverflow = true
            return
        }

        val admitted: String
        if (normalizedBytes <= remaining) {
            admitted = normalized
        } else {
            // Over-cap delta: admit the largest scalar-aligned prefix that
            // fits, so no surrogate pair or UTF-8 sequence is split.
            val cut = scalarAlignedCutUtf8(normalized, remaining)
            admitted = normalized.substring(0, cut)
            val admittedBytes = utf8ByteCount(admitted)
            rejectedByteCount += normalizedBytes - admittedBytes
            sourceOverflow = true
        }

        canonical.append(admitted)
        canonicalScalarCount += admitted.codePointCount(0, admitted.length)
        canonicalByteCount += utf8ByteCount(admitted)
        admitToPresentation(admitted)
    }

    /**
     * Replaces canonical source with the authoritative terminal text under
     * the same 8 MiB ceiling. An over-cap terminal text seals the admitted
     * scalar-aligned prefix and raises [sourceOverflow]; under-cap text
     * clears the flag (the replacement is fully retained). Null keeps the
     * lossless admitted canonical delta text as-is.
     */
    fun replaceWithTerminalText(terminalText: String?) {
        if (terminalText == null) return
        val normalized = normalizeSurrogates(terminalText)
        val normalizedBytes = utf8ByteCount(normalized)

        canonical.setLength(0)
        presentation.setLength(0)
        presentationBytes = 0L
        presentationFrozen = false
        presentationClipped = false
        canonicalByteCount = 0L
        canonicalScalarCount = 0L

        if (normalizedBytes <= HARD_CEILING_BYTES) {
            canonical.append(normalized)
            canonicalByteCount = normalizedBytes
            canonicalScalarCount = normalized.codePointCount(0, normalized.length).toLong()
            admitToPresentation(normalized)
            sourceOverflow = false
            rejectedByteCount = 0L
            return
        }

        // Over-cap terminal text: admit the largest scalar-aligned prefix and
        // seal it with the explicit overflow flag.
        val cut = scalarAlignedCutUtf8(normalized, HARD_CEILING_BYTES)
        val admitted = normalized.substring(0, cut)
        canonical.append(admitted)
        canonicalByteCount = utf8ByteCount(admitted)
        canonicalScalarCount = admitted.codePointCount(0, admitted.length).toLong()
        rejectedByteCount = normalizedBytes - canonicalByteCount
        sourceOverflow = true
        admitToPresentation(admitted)
    }

    /**
     * The rich-presentation window: the first 1 MiB of canonical source plus
     * the frozen truncation labels when clipping or overflow applies. Never
     * silently implies completeness.
     */
    fun presentationWindow(): String = buildString {
        append(presentation)
        if (presentationClipped) append(PRESENTATION_TRUNCATION_LABEL)
        if (sourceOverflow) append(SOURCE_OVERFLOW_LABEL)
    }

    /**
     * Admits [text] (already ceiling-admitted) into the 1 MiB presentation
     * window, clipping on a scalar boundary exactly at the window limit.
     */
    private fun admitToPresentation(text: String) {
        if (presentationFrozen) return
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val scalarBytes = utf8ByteCountOfCodePoint(codePoint).toLong()
            if (presentationBytes + scalarBytes > PRESENTATION_WINDOW_BYTES) {
                presentationFrozen = true
                presentationClipped = true
                return
            }
            presentationBytes += scalarBytes
            presentation.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
    }

    companion object {
        /** Frozen D-04 canonical UTF-8 hard ceiling: 8 MiB. */
        const val HARD_CEILING_BYTES: Long = 8L * 1024L * 1024L

        /** Frozen D-04 rich-presentation window: first 1 MiB of canonical source. */
        const val PRESENTATION_WINDOW_BYTES: Long = 1L * 1024L * 1024L

        /** Frozen D-04 label for 1 MiB presentation clipping. */
        const val PRESENTATION_TRUNCATION_LABEL: String = "Content truncated for display."

        /** Frozen D-04 label additionally shown on hard-ceiling overflow. */
        const val SOURCE_OVERFLOW_LABEL: String =
            "Response exceeded the 8 MiB safety limit; remaining text was not retained."

        /** Terminal/display flag name recorded in tests and evidence. */
        const val SOURCE_OVERFLOW: String = "SOURCE_OVERFLOW"

        /**
         * Normalizes unpaired UTF-16 surrogates to U+FFFD (CON-002/CON-008) so
         * all counts and boundaries operate on the normalized stream.
         */
        fun normalizeSurrogates(text: String): String {
            var hasSurrogate = false
            for (ch in text) {
                if (Character.isSurrogate(ch)) {
                    hasSurrogate = true
                    break
                }
            }
            if (!hasSurrogate) return text
            val sb = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                if (Character.isHighSurrogate(ch)) {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        sb.append(ch).append(text[index + 1])
                        index += 2
                    } else {
                        sb.append('\uFFFD')
                        index += 1
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    sb.append('\uFFFD')
                    index += 1
                } else {
                    sb.append(ch)
                    index += 1
                }
            }
            return sb.toString()
        }

        /** UTF-8 byte count of a (normalized) string, measured per scalar. */
        fun utf8ByteCount(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                bytes += utf8ByteCountOfCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
            return bytes
        }

        /** UTF-8 byte length of a single code point. */
        fun utf8ByteCountOfCodePoint(codePoint: Int): Int = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

        /**
         * Returns the char index cut so that the prefix [0, cut) of [text]
         * has a UTF-8 encoding of at most [maxBytes], cut only on scalar
         * boundaries (never inside a surrogate pair or UTF-8 sequence).
         */
        fun scalarAlignedCutUtf8(text: String, maxBytes: Long): Int {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val scalarBytes = utf8ByteCountOfCodePoint(codePoint).toLong()
                if (bytes + scalarBytes > maxBytes) break
                bytes += scalarBytes
                index += Character.charCount(codePoint)
            }
            return index
        }

        /**
         * Returns the longest prefix of [text] whose UTF-8 encoding is at most
         * [maxBytes], cut only on scalar boundaries.
         */
        fun scalarAlignedPrefixUtf8(text: String, maxBytes: Long): String =
            text.substring(0, scalarAlignedCutUtf8(text, maxBytes))
    }
}
