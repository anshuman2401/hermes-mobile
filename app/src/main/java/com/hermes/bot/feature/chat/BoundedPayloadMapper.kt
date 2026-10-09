package com.hermes.bot.feature.chat

/**
 * BLD-006 S8 (TASK-034/035) — bounded display mapper enforcing the frozen D-09
 * budgets DURING traversal (PAT-003), never after materialization.
 *
 * D-09 budgets (all frozen; below/at/above probes live in BoundedPayloadMapperTest):
 * - tool name/type:      128 code points, visible ellipsis
 * - context:             512 code points, visible "context truncated" label
 * - collapsed summary:   256 code points, visible ellipsis
 * - expanded args:       4 KiB UTF-8, visible truncation label
 * - expanded result:    16 KiB UTF-8, visible truncation label
 * - generic payload preview: 1 KiB UTF-8, visible truncation label
 * - structured traversal: depth 8 / 256 aggregate values, bounded marker
 *
 * CON-008 surrogate safety: code-point budgets are applied on the
 * surrogate-normalized stream (unpaired UTF-16 surrogates become U+FFFD before
 * measuring), UTF-8 byte budgets are measured on that normalized stream, and no
 * surrogate pair or UTF-8 sequence is ever split.
 *
 * SEC-002 / PAT-003 / CON-002: raw maps are never passed to Compose and never
 * converted with unbounded toString()/joinToString(); every budget is checked
 * incrementally while walking the structure, and every string scalar is capped
 * against the remaining byte budget as it is appended. Truncation helpers are
 * window-based: the input is walked structurally (O(n), zero allocation) and
 * only the kept window is normalized/redacted — a single 6 MiB string leaf is
 * never fully materialized, normalized, or redacted as a whole. Regex
 * redaction is defense in depth applied to the kept window only; a
 * small look-ahead window past the cut lets boundary-spanning secret tokens
 * still match the redaction patterns.
 */
object BoundedPayloadMapper {

    // ------------------------------------------------------------------
    // Frozen D-09 budgets
    // ------------------------------------------------------------------

    const val MAX_NAME_TYPE_CODE_POINTS = 128
    const val MAX_CONTEXT_CODE_POINTS = 512
    const val MAX_COLLAPSED_SUMMARY_CODE_POINTS = 256

    const val MAX_EXPANDED_ARGS_UTF8_BYTES = 4 * 1024
    const val MAX_EXPANDED_RESULT_UTF8_BYTES = 16 * 1024
    const val MAX_GENERIC_PREVIEW_UTF8_BYTES = 1 * 1024

    const val MAX_TRAVERSAL_DEPTH = 8
    const val MAX_TRAVERSAL_VALUES = 256

    // ------------------------------------------------------------------
    // Frozen truncation copy (D-09 / TASK-035)
    // ------------------------------------------------------------------

    const val ELLIPSIS = "…"
    const val CONTEXT_TRUNCATED_LABEL = "context truncated"
    const val DETAILS_TRUNCATED_LABEL = "Details truncated."

    const val ARGS_TRUNCATED_SUFFIX = "\nDetails truncated. (expanded arguments exceeded the 4 KiB display budget)"
    const val RESULT_TRUNCATED_SUFFIX = "\nDetails truncated. (expanded result exceeded the 16 KiB display budget)"
    const val GENERIC_TRUNCATED_SUFFIX = "\nDetails truncated. (payload preview exceeded the 1 KiB display budget)"

    // Structured-traversal bounded markers (TASK-034).
    const val DEPTH_BOUND_MARKER = "[depth limit reached — details truncated]"
    const val VALUE_COUNT_BOUND_MARKER = "[value limit reached — details truncated]"

    /** Marker appended to a single string scalar capped mid-walk (PAT-003). */
    const val TRAVERSAL_CUT_MARKER = "…[truncated]"

    /** Extra chars past a cut kept only as regex context for boundary-spanning secrets. */
    private const val REDACTION_LOOKAHEAD_CHARS = 128

    /** Per-key cap for map keys during traversal (keys are never payload-sized). */
    private const val SCALAR_KEY_CAP_BYTES = 128

    // ------------------------------------------------------------------
    // CON-008 surrogate normalization
    // ------------------------------------------------------------------

    /**
     * Surrogate-normalizes a string per CON-008: unpaired UTF-16 surrogates
     * become U+FFFD so budgets never split a pair or sequence. Applied to kept
     * windows before any measurement or redaction.
     */
    fun normalizeSurrogates(text: String): String {
        if (!containsSurrogates(text)) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    sbAppendPair(sb, text, i)
                    i += 2
                    continue
                }
                sb.append('\uFFFD')
                i += 1
                continue
            }
            if (Character.isLowSurrogate(c)) {
                sb.append('\uFFFD')
                i += 1
                continue
            }
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }

    private fun sbAppendPair(sb: StringBuilder, text: String, i: Int) {
        sb.append(text[i]).append(text[i + 1])
    }

    private fun containsSurrogates(text: String): Boolean {
        for (c in text) {
            if (Character.isSurrogate(c)) return true
        }
        return false
    }

    /**
     * Code points of the surrogate-normalized stream. Structural walk on the
     * original chars (a valid pair and an unpaired surrogate each normalize to
     * exactly one code point), so counting the original stream equals counting
     * the normalized one — no full normalized copy is materialized.
     */
    fun codePointCount(text: String): Int {
        var i = 0
        var count = 0
        while (i < text.length) {
            i += if (Character.isHighSurrogate(text[i]) &&
                i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            count++
        }
        return count
    }

    /**
     * UTF-8 byte length of the surrogate-normalized stream, computed with the
     * same structural walk (unpaired surrogate → 3 replacement bytes) — the
     * whole input is never encoded to a byte array first.
     */
    fun utf8ByteCount(text: String): Int = utf8Walk(text, Int.MAX_VALUE).first

    // ------------------------------------------------------------------
    // Windowed truncation (PAT-003 / CON-002 review repair)
    // ------------------------------------------------------------------

    /**
     * Structural walk over [text]'s original chars accumulating UTF-8 bytes of
     * the (would-be) normalized stream, stopping before exceeding [byteLimit].
     * Returns (bytes kept, end index exclusive). Pair-atomic: a valid surrogate
     * pair is consumed whole; an unpaired surrogate counts as its 3-byte
     * U+FFFD replacement. Zero allocation.
     */
    private fun utf8Walk(text: String, byteLimit: Int): Pair<Int, Int> {
        var i = 0
        var bytes = 0
        while (i < text.length) {
            val c = text[i]
            val cpBytes: Int
            val advance: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    cpBytes = 4; advance = 2
                } else {
                    cpBytes = 3; advance = 1
                }
            } else if (Character.isLowSurrogate(c)) {
                cpBytes = 3; advance = 1
            } else {
                cpBytes = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                advance = 1
            }
            if (bytes + cpBytes > byteLimit) break
            bytes += cpBytes
            i += advance
        }
        return bytes to i
    }

    /**
     * Structural walk counting code points up to [maxCodePoints]; returns
     * (end index exclusive, truncated flag). Pair-atomic; zero allocation.
     */
    private fun cpWindowEnd(text: String, maxCodePoints: Int): Pair<Int, Boolean> {
        var i = 0
        var count = 0
        while (i < text.length && count < maxCodePoints) {
            i += if (Character.isHighSurrogate(text[i]) &&
                i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            count++
        }
        return i to (i < text.length)
    }

    /**
     * Truncates to at most [maxCodePoints] code points of the surrogate-
     * normalized stream, appending [ellipsis] when truncation occurred. Never
     * splits a surrogate pair.
     *
     * Window-based (review repair): the input is walked structurally and only
     * the kept window (plus a small regex look-ahead when [redact] is set) is
     * normalized/redacted — the full input is never materialized as a
     * normalized or redacted copy first.
     */
    fun truncateCodePoints(
        text: String,
        maxCodePoints: Int,
        ellipsis: String = ELLIPSIS,
        redact: Boolean = false
    ): String {
        if (maxCodePoints <= 0) return ""
        val (end, truncated) = cpWindowEnd(text, maxCodePoints)
        if (!truncated) {
            // Input fits the budget: it is small by definition (≤ budget code
            // points), so normalize/redact of the whole is bounded.
            val whole = normalizeSurrogates(text)
            return if (redact) ChatSecurityPolicy.redactSensitiveData(whole) else whole
        }
        val srcEnd = if (redact) minOf(text.length, end + REDACTION_LOOKAHEAD_CHARS) else end
        val processed = normalizeSurrogates(text.substring(0, srcEnd))
        val redacted = if (redact) ChatSecurityPolicy.redactSensitiveData(processed) else processed
        // Final exact cut on the small processed window (redaction may have
        // grown it slightly); ellipsis marks the truncation of the original.
        val (cutEnd, _) = cpWindowEnd(redacted, maxCodePoints)
        return redacted.substring(0, cutEnd) + ellipsis
    }

    /**
     * Truncates the surrogate-normalized stream to at most [maxUtf8Bytes] UTF-8
     * bytes without splitting a UTF-8 sequence or surrogate pair, appending
     * [suffix] when truncation occurred.
     *
     * Window-based (review repair): pass 1 walks the original chars to check
     * whether the whole input fits (zero allocation); only when over budget is
     * a kept window (bytes reserved for [suffix]) normalized/redacted. The kept
     * body plus suffix stays within the budget, so the total output is
     * deterministically ≤ maxUtf8Bytes whenever truncation occurs.
     */
    fun truncateUtf8Bytes(
        text: String,
        maxUtf8Bytes: Int,
        suffix: String,
        redact: Boolean = false
    ): String {
        if (maxUtf8Bytes <= 0) return ""
        val (totalBytes, _) = utf8Walk(text, Int.MAX_VALUE)
        if (totalBytes <= maxUtf8Bytes) {
            // Whole input fits: small by definition — normalize/redact whole.
            val whole = normalizeSurrogates(text)
            return if (redact) ChatSecurityPolicy.redactSensitiveData(whole) else whole
        }
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val bodyBudget = (maxUtf8Bytes - suffixBytes).coerceAtLeast(0)
        val (_, end) = utf8Walk(text, bodyBudget)
        val srcEnd = if (redact) minOf(text.length, end + REDACTION_LOOKAHEAD_CHARS) else end
        val processed = normalizeSurrogates(text.substring(0, srcEnd))
        val redacted = if (redact) ChatSecurityPolicy.redactSensitiveData(processed) else processed
        // Re-cap the processed window (redaction may have grown it); cp-safe.
        val (_, capEnd) = utf8Walk(redacted, bodyBudget)
        return redacted.substring(0, capEnd) + suffix
    }

    // ------------------------------------------------------------------
    // D-09 budget display helpers (one per budgeted field)
    // ------------------------------------------------------------------

    /** 128 cp budget with visible ellipsis (tool name / event type). */
    fun boundedNameType(value: String?): String {
        if (value.isNullOrBlank()) return "unknown"
        return truncateCodePoints(value.trim(), MAX_NAME_TYPE_CODE_POINTS, redact = true)
    }

    /** 512 cp budget with visible "context truncated" label. */
    fun boundedContext(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        val out = truncateCodePoints(trimmed, MAX_CONTEXT_CODE_POINTS, ellipsis = "", redact = true)
        return if (cpWindowEnd(trimmed, MAX_CONTEXT_CODE_POINTS).second) {
            out + " ($CONTEXT_TRUNCATED_LABEL)"
        } else {
            out
        }
    }

    /** 256 cp budget with visible ellipsis (collapsed summary row). */
    fun boundedCollapsedSummary(value: String?): String {
        if (value.isNullOrBlank()) return "unknown"
        return truncateCodePoints(value.trim(), MAX_COLLAPSED_SUMMARY_CODE_POINTS, redact = true)
    }

    /** 4 KiB UTF-8 budget with visible truncation label (expanded arguments). */
    fun boundedExpandedArgs(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return truncateUtf8Bytes(value, MAX_EXPANDED_ARGS_UTF8_BYTES, ARGS_TRUNCATED_SUFFIX, redact = true)
    }

    /** 16 KiB UTF-8 budget with visible truncation label (expanded result). */
    fun boundedExpandedResult(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return truncateUtf8Bytes(value, MAX_EXPANDED_RESULT_UTF8_BYTES, RESULT_TRUNCATED_SUFFIX, redact = true)
    }

    /** 1 KiB UTF-8 budget with visible truncation label (generic payload preview). */
    fun boundedGenericPreview(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return truncateUtf8Bytes(value, MAX_GENERIC_PREVIEW_UTF8_BYTES, GENERIC_TRUNCATED_SUFFIX, redact = true)
    }

    // ------------------------------------------------------------------
    // Structured traversal (TASK-034) — replaces the unbounded
    // joinToString materialization paths in formatToolArgs/formatToolResult.
    // Budgets are checked incrementally while walking; an over-budget input
    // never materializes as a full string first (PAT-003 / SEC-002).
    // ------------------------------------------------------------------

    /**
     * Walks [value] with bounded depth and aggregate-value budgets. Output is
     * built incrementally and cut with a bounded marker the moment a budget is
     * exceeded; every string scalar is capped against the remaining byte budget
     * as it is appended (a single 6 MiB string leaf never materializes beyond
     * the capped window). Regex redaction is applied defense-in-depth to the
     * bounded result only.
     */
    fun traverseStructured(
        value: Any?,
        depthBudget: Int = MAX_TRAVERSAL_DEPTH,
        valueBudget: Int = MAX_TRAVERSAL_VALUES
    ): String {
        // Top-level scalar strings render raw (no quotes) through the SAME
        // capped path as every other string scalar (review repair: the old
        // short-circuit normalized+redacted the full input first). Top-level
        // scalar strings are tool results in the certified tool.complete
        // shape, so the cut marker is the expanded-result truncation label.
        if (value is String) {
            return boundedScalarString(value, MAX_EXPANDED_RESULT_UTF8_BYTES, RESULT_TRUNCATED_SUFFIX)
        }
        val sb = StringBuilder()
        val counter = intArrayOf(0)
        val overflow = walk(value, 1, depthBudget, valueBudget, counter, sb)
        return when (overflow) {
            Overflow.DEPTH -> {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(DEPTH_BOUND_MARKER)
                ChatSecurityPolicy.redactSensitiveData(sb.toString())
            }
            Overflow.VALUES -> {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(VALUE_COUNT_BOUND_MARKER)
                ChatSecurityPolicy.redactSensitiveData(sb.toString())
            }
            Overflow.NONE -> ChatSecurityPolicy.redactSensitiveData(sb.toString())
            null -> ChatSecurityPolicy.redactSensitiveData(sb.toString())
        }
    }

    private enum class Overflow { NONE, DEPTH, VALUES }

    /**
     * Caps a single string scalar during traversal: at most [remainingBytes]
     * UTF-8 bytes are kept (reserving room for [cutSuffix]), the suffix is
     * appended when the scalar was cut. The input is only sampled window-wise
     * — never fully normalized or redacted first (PAT-003 / CON-002).
     */
    private fun boundedScalarString(value: String, remainingBytes: Int, cutSuffix: String): String {
        if (remainingBytes <= 0) return ""
        val cutBytes = cutSuffix.toByteArray(Charsets.UTF_8).size
        val keepBudget = remainingBytes - cutBytes
        if (keepBudget <= 0) return ""
        // keepBudget chars ≥ keepBudget bytes, so the sample can fill the
        // window for any content; the byte-cap below trims multibyte tails.
        val sampleEnd = minOf(value.length, keepBudget)
        val sample = value.substring(0, sampleEnd)
        val redacted = ChatSecurityPolicy.redactSensitiveData(normalizeSurrogates(sample))
        val (_, capEnd) = utf8Walk(redacted, keepBudget)
        val kept = redacted.substring(0, capEnd)
        return if (capEnd < redacted.length || sampleEnd < value.length) kept + cutSuffix else kept
    }

    /**
     * Returns the first overflow kind hit, or null when the walk stayed within
     * all budgets. Every budget check happens before recursion and before
     * appending child content.
     */
    private fun walk(
        value: Any?,
        depth: Int,
        depthBudget: Int,
        valueBudget: Int,
        counter: IntArray,
        sb: StringBuilder
    ): Overflow? {
        // Traversal-depth budget applies to every value, scalar or container:
        // content at depth > depthBudget is cut with the bounded marker and
        // never rendered (depth 8 is a stop boundary).
        if (depth > depthBudget) return Overflow.DEPTH

        // Aggregate-value budget: counts leaf values about to be rendered.
        // Containers are walked but not counted; the check happens before each
        // child is visited, so the 257th leaf stops with the bounded marker.
        val isLeaf = value == null || value is String || value is Number || value is Boolean
        if (isLeaf) {
            if (counter[0] >= valueBudget) return Overflow.VALUES
            counter[0]++
        }

        return when (value) {
            null -> {
                sb.append("null")
                null
            }
            is String -> {
                // Cap the scalar against the bytes still available for the
                // output field; the bounded marker is appended when cut, and a
                // spent budget contributes nothing more. Redaction runs on the
                // capped window only (PAT-003: never the full input).
                val remaining = (MAX_EXPANDED_RESULT_UTF8_BYTES - sb.length * 3).coerceAtLeast(0)
                sb.append('"')
                sb.append(boundedScalarString(value, remaining, TRAVERSAL_CUT_MARKER))
                sb.append('"')
                null
            }
            is Number, is Boolean -> {
                sb.append(value.toString())
                null
            }
            is Map<*, *> -> {
                if (depth > depthBudget) return Overflow.DEPTH
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (counter[0] >= valueBudget) return Overflow.VALUES
                    if (!first) sb.append(", ")
                    first = false
                    // Keys are capped too: a crafted oversized key never
                    // materializes a whole normalized/redacted copy.
                    sb.append(
                        boundedScalarString(
                            k?.toString() ?: "null",
                            SCALAR_KEY_CAP_BYTES,
                            TRAVERSAL_CUT_MARKER
                        )
                    ).append(": ")
                    val child = walk(v, depth + 1, depthBudget, valueBudget, counter, sb)
                    if (child != null) return child
                }
                sb.append('}')
                null
            }
            is List<*> -> {
                if (depth > depthBudget) return Overflow.DEPTH
                sb.append('[')
                var first = true
                for (v in value) {
                    if (counter[0] >= valueBudget) return Overflow.VALUES
                    if (!first) sb.append(", ")
                    first = false
                    val child = walk(v, depth + 1, depthBudget, valueBudget, counter, sb)
                    if (child != null) return child
                }
                sb.append(']')
                null
            }
            else -> {
                // Unknown scalar kinds render as a bounded typed placeholder, never
                // an unbounded toString() (SEC-002).
                sb.append(value.javaClass.simpleName.ifBlank { "unknown" })
                null
            }
        }
    }
}
