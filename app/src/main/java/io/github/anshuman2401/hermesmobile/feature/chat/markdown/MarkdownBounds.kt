package io.github.anshuman2401.hermesmobile.feature.chat.markdown

/**
 * BLD-006 S9 — shared measurement/truncation helpers for the frozen D-10
 * Markdown budgets, operating on the surrogate-normalized stream (CON-008):
 * unpaired UTF-16 surrogates become U+FFFD before any count, so no surrogate
 * pair or UTF-8 sequence is ever split and UTF-8 byte counts are measured on
 * the normalized stream.
 *
 * All walks are single-pass, constant-memory, and pair-atomic: a valid
 * surrogate pair is consumed whole; an unpaired surrogate counts as its
 * 3-byte U+FFFD replacement. Truncation always ends on a scalar boundary.
 *
 * The numeric budget constants live in their plan-named enforcing types:
 * parser/table budgets in [MarkdownParser], code budgets in
 * [CodeBlockBudget] (CodeBlock.kt).
 */
internal object MarkdownBounds {

    /**
     * Surrogate-normalizes [text] per CON-008: every unpaired UTF-16
     * surrogate becomes U+FFFD; valid pairs pass through untouched.
     */
    fun normalizeSurrogates(text: String): String {
        var hasSurrogate = false
        for (c in text) {
            if (Character.isSurrogate(c)) {
                hasSurrogate = true
                break
            }
        }
        if (!hasSurrogate) return text
        val sb = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        sb.append(ch).append(text[index + 1])
                        index += 2
                    } else {
                        sb.append('\uFFFD')
                        index += 1
                    }
                }
                Character.isLowSurrogate(ch) -> {
                    sb.append('\uFFFD')
                    index += 1
                }
                else -> {
                    sb.append(ch)
                    index += 1
                }
            }
        }
        return sb.toString()
    }

    /** Unicode scalar count of the (normalized) stream; pair-atomic. */
    fun codePointCount(text: String): Int {
        var index = 0
        var count = 0
        while (index < text.length) {
            index += if (
                Character.isHighSurrogate(text[index]) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) 2 else 1
            count++
        }
        return count
    }

    /** UTF-8 byte count of the surrogate-normalized stream, per scalar. */
    fun utf8ByteCount(text: String): Int {
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        bytes += 4
                        index += 2
                    } else {
                        // Unpaired high surrogate: 3 replacement bytes (U+FFFD).
                        bytes += 3
                        index += 1
                    }
                }
                Character.isLowSurrogate(ch) -> {
                    bytes += 3
                    index += 1
                }
                ch.code < 0x80 -> {
                    bytes += 1
                    index += 1
                }
                ch.code < 0x800 -> {
                    bytes += 2
                    index += 1
                }
                else -> {
                    bytes += 3
                    index += 1
                }
            }
        }
        return bytes
    }

    /**
     * Longest prefix of [text] whose UTF-8 encoding is at most [maxBytes],
     * cut only on scalar boundaries (never inside a surrogate pair or
     * UTF-8 sequence). [text] must already be surrogate-normalized.
     */
    fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val ch = text[index]
            val cpBytes: Int
            val advance: Int
            when {
                Character.isHighSurrogate(ch) -> {
                    if (index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) {
                        cpBytes = 4; advance = 2
                    } else {
                        cpBytes = 3; advance = 1
                    }
                }
                Character.isLowSurrogate(ch) -> {
                    cpBytes = 3; advance = 1
                }
                ch.code < 0x80 -> {
                    cpBytes = 1; advance = 1
                }
                ch.code < 0x800 -> {
                    cpBytes = 2; advance = 1
                }
                else -> {
                    cpBytes = 3; advance = 1
                }
            }
            if (bytes + cpBytes > maxBytes) break
            bytes += cpBytes
            index += advance
        }
        return text.substring(0, index)
    }

    /**
     * Longest prefix of [text] containing at most [maxCodePoints] Unicode
     * scalars, cut only on scalar boundaries (never inside a surrogate pair).
     * [text] must already be surrogate-normalized.
     */
    fun truncateToCodePoints(text: String, maxCodePoints: Int): String {
        if (maxCodePoints <= 0) return ""
        var index = 0
        var count = 0
        while (index < text.length && count < maxCodePoints) {
            index += if (
                Character.isHighSurrogate(text[index]) &&
                index + 1 < text.length &&
                Character.isLowSurrogate(text[index + 1])
            ) 2 else 1
            count++
        }
        return text.substring(0, index)
    }
}
