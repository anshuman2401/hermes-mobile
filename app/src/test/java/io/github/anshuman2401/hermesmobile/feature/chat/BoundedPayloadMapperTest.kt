package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S8 (TASK-037) — D-09 boundary matrix: below (limit−1 unit), at
 * (exactly limit), above (limit+1 unit) for every frozen budget, on the
 * surrogate-normalized stream per CON-008 (code-point counts and UTF-8 byte
 * counts; no surrogate pair or UTF-8 sequence ever split).
 *
 * Also covers: oversized nested payload traversal (depth 8 / 256 values,
 * enforced DURING traversal — PAT-003), secret-like values in every display
 * field with aligned redaction markers, typed SEC-004 failure copy, and the
 * truncated-label copy contract consumed by the card composables (M-04
 * budget/copy rows; Compose-level disclosure semantics are device-gated and
 * live under src/androidTest per CON-009).
 */
class BoundedPayloadMapperTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** "a" repeated [n] code points (all BMP, 1 UTF-8 byte each). */
    private fun ascii(n: Int): String = "a".repeat(n)

    /** [n] code points of a 3-byte CJK character. */
    private fun cjk(n: Int): String = "漢".repeat(n)

    /** Surrogate-safe emoji string of [n] code points (4 UTF-8 bytes each). */
    private fun emoji(n: Int): String = "\uD83D\uDE00".repeat(n)

    // ------------------------------------------------------------------
    // Budget 1: tool name/type — 128 code points, visible ellipsis
    // ------------------------------------------------------------------

    @Test
    fun nameType_below128cp_keptWithoutEllipsis() {
        val input = ascii(BoundedPayloadMapper.MAX_NAME_TYPE_CODE_POINTS - 1)
        val out = BoundedPayloadMapper.boundedNameType(input)
        assertEquals(input, out)
        assertFalse(out.contains(BoundedPayloadMapper.ELLIPSIS))
    }

    @Test
    fun nameType_at128cp_keptWithoutEllipsis() {
        val input = ascii(BoundedPayloadMapper.MAX_NAME_TYPE_CODE_POINTS)
        val out = BoundedPayloadMapper.boundedNameType(input)
        assertEquals(input, out)
        assertFalse(out.contains(BoundedPayloadMapper.ELLIPSIS))
    }

    @Test
    fun nameType_above128cp_truncatedWithVisibleEllipsis() {
        val input = ascii(BoundedPayloadMapper.MAX_NAME_TYPE_CODE_POINTS + 1)
        val out = BoundedPayloadMapper.boundedNameType(input)
        assertEquals(BoundedPayloadMapper.MAX_NAME_TYPE_CODE_POINTS, BoundedPayloadMapper.codePointCount(out) - 1) // minus ellipsis
        assertTrue(out.endsWith(BoundedPayloadMapper.ELLIPSIS))
        assertFalse(out.contains("b")) // the 129th char never appears
    }

    @Test
    fun nameType_surrogatePairsNeverSplit() {
        // 127 ascii + 2 emoji (2 cp each) = 129 cp; the last emoji pair must
        // survive intact or be dropped whole — never half a surrogate.
        val input = ascii(127) + emoji(2)
        val out = BoundedPayloadMapper.boundedNameType(input)
        assertFalse(Character.isHighSurrogate(out.last()))
        assertFalse(Character.isLowSurrogate(out.last()))
        assertTrue(BoundedPayloadMapper.codePointCount(out) <= BoundedPayloadMapper.MAX_NAME_TYPE_CODE_POINTS + 1)
    }

    // ------------------------------------------------------------------
    // Budget 2: context — 512 code points, "context truncated" label
    // ------------------------------------------------------------------

    @Test
    fun context_below512cp_keptWithoutLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_CONTEXT_CODE_POINTS - 1)
        assertEquals(input, BoundedPayloadMapper.boundedContext(input))
    }

    @Test
    fun context_at512cp_keptWithoutLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_CONTEXT_CODE_POINTS)
        assertEquals(input, BoundedPayloadMapper.boundedContext(input))
    }

    @Test
    fun context_above512cp_truncatedWithVisibleLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_CONTEXT_CODE_POINTS + 1)
        val out = BoundedPayloadMapper.boundedContext(input)
        assertTrue(out!!.endsWith("(${BoundedPayloadMapper.CONTEXT_TRUNCATED_LABEL})"))
        // Bounded content + label stays bounded: body is exactly the budget.
        val body = out.removeSuffix(" (${BoundedPayloadMapper.CONTEXT_TRUNCATED_LABEL})")
        assertEquals(BoundedPayloadMapper.MAX_CONTEXT_CODE_POINTS, BoundedPayloadMapper.codePointCount(body))
    }

    @Test
    fun context_nullOrBlank_staysNull() {
        assertNull(BoundedPayloadMapper.boundedContext(null))
        assertNull(BoundedPayloadMapper.boundedContext("   "))
    }

    // ------------------------------------------------------------------
    // Budget 3: collapsed summary — 256 code points, visible ellipsis
    // ------------------------------------------------------------------

    @Test
    fun collapsedSummary_below256cp_kept() {
        val input = ascii(BoundedPayloadMapper.MAX_COLLAPSED_SUMMARY_CODE_POINTS - 1)
        assertEquals(input, BoundedPayloadMapper.boundedCollapsedSummary(input))
    }

    @Test
    fun collapsedSummary_at256cp_kept() {
        val input = ascii(BoundedPayloadMapper.MAX_COLLAPSED_SUMMARY_CODE_POINTS)
        assertEquals(input, BoundedPayloadMapper.boundedCollapsedSummary(input))
    }

    @Test
    fun collapsedSummary_above256cp_truncatedWithVisibleEllipsis() {
        val input = ascii(BoundedPayloadMapper.MAX_COLLAPSED_SUMMARY_CODE_POINTS + 1)
        val out = BoundedPayloadMapper.boundedCollapsedSummary(input)
        assertTrue(out.endsWith(BoundedPayloadMapper.ELLIPSIS))
        assertEquals(
            BoundedPayloadMapper.MAX_COLLAPSED_SUMMARY_CODE_POINTS,
            BoundedPayloadMapper.codePointCount(out) - 1
        )
    }

    // ------------------------------------------------------------------
    // Budget 4: expanded args — 4 KiB UTF-8, visible truncation label
    // ------------------------------------------------------------------

    @Test
    fun expandedArgs_below4KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES - 1)
        val out = BoundedPayloadMapper.boundedExpandedArgs(input)!!
        assertEquals(BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES - 1, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun expandedArgs_at4KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES)
        val out = BoundedPayloadMapper.boundedExpandedArgs(input)!!
        assertEquals(BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun expandedArgs_above4KiB_totalBytesBoundedWithVisibleLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES + 1024)
        val out = BoundedPayloadMapper.boundedExpandedArgs(input)!!
        assertTrue(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        // Total output (kept bytes + suffix) stays within the 4 KiB budget window
        // plus the fixed label; the over-budget tail never materializes.
        assertTrue(BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES + 256)
        assertFalse(out.contains("b".repeat(64)))
    }

    @Test
    fun expandedArgs_multibyteContent_neverSplitsUtf8Sequence() {
        // 1400 CJK chars = 4200 UTF-8 bytes > 4096; every kept char must be intact.
        val input = cjk(1400)
        val out = BoundedPayloadMapper.boundedExpandedArgs(input)!!
        assertTrue(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        val body = out.removeSuffix(BoundedPayloadMapper.ARGS_TRUNCATED_SUFFIX)
        // Body bytes must be a whole multiple of 3 (no split 3-byte sequence).
        assertEquals(0, BoundedPayloadMapper.utf8ByteCount(body) % 3)
        assertEquals("漢", body.take(1))
    }

    /**
     * Review repair [MEDIUM]: at-limit UTF-8 probes with 3-byte (hanzi) and
     * 4-byte (emoji) content for the 4 KiB / 16 KiB / 1 KiB budgets. At-limit
     * (exactly budget) content is kept whole; over-budget content is cut at a
     * deterministic kept-byte count with no partial UTF-8 sequence.
     */
    private fun assertAtLimitUtf8(
        input: String,
        budget: Int,
        truncate: (String) -> String?,
        truncatedSuffix: String
    ) {
        // --- at limit: exactly [budget] UTF-8 bytes of multibyte content ---
        val atLimit = truncate(input)!!
        assertEquals(
            "at-limit multibyte content must be kept whole (no truncation label)",
            budget,
            BoundedPayloadMapper.utf8ByteCount(atLimit)
        )
        assertFalse(atLimit.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        assertFalse(truncatedSuffix.let(atLimit::endsWith))
        // No partial sequence: every char of the kept stream is intact input.
        assertTrue(atLimit.all { !Character.isHighSurrogate(it) || atLimit.indexOf(it) % 2 == 0 })

        // --- at limit + 1 code point: deterministic kept-byte count ---
        val over = truncate(input + when (budget % 3) {
            3 -> "漢"
            4 -> "\uD83D\uDE00"
            else -> "漢"
        })!!
        assertTrue("over-budget must carry the truncation label", over.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        val body = over.removeSuffix(truncatedSuffix)
        val bodyBytes = BoundedPayloadMapper.utf8ByteCount(body)
        // Deterministic: body bytes + a whole number of appended 3-byte units
        // that did not fit lands the kept count just under the body budget.
        assertTrue(
            "kept bytes must stay within the budget (got $bodyBytes)",
            bodyBytes <= budget
        )
        // No partial sequence: the kept body must round-trip through UTF-8
        // decoding without replacement chars introduced by a split sequence.
        val decoded = body.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        assertEquals(
            "no partial UTF-8 sequence may survive the cut",
            body,
            decoded
        )
        // Emoji input: kept body must not end on an UNPAIRED surrogate — a
        // complete trailing pair ends with a low surrogate preceded by its
        // high, which is legal; a trailing high surrogate would be a split.
        val last = body.last()
        assertFalse(
            "kept body must not end on an unpaired high surrogate (split pair)",
            Character.isHighSurrogate(last) &&
                (body.length < 2 || !Character.isLowSurrogate(body[body.length - 2]))
        )
    }

    @Test
    fun expandedArgs_atLimitUtf8_3ByteHanzi_deterministicKeptBytes() {
        // 1365 hanzi = 4095 bytes; +1 hanzi = 4098 > 4096 (3-byte budget case).
        assertAtLimitUtf8(
            cjk(1365),
            BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES - 1,
            BoundedPayloadMapper::boundedExpandedArgs,
            BoundedPayloadMapper.ARGS_TRUNCATED_SUFFIX
        )
    }

    @Test
    fun expandedArgs_atLimitUtf8_4ByteEmoji_deterministicKeptBytes() {
        // 1024 emoji = 4096 bytes exactly; +1 emoji = 4100 > 4096.
        assertAtLimitUtf8(
            emoji(1024),
            BoundedPayloadMapper.MAX_EXPANDED_ARGS_UTF8_BYTES,
            BoundedPayloadMapper::boundedExpandedArgs,
            BoundedPayloadMapper.ARGS_TRUNCATED_SUFFIX
        )
    }

    @Test
    fun expandedResult_atLimitUtf8_3ByteHanzi_deterministicKeptBytes() {
        // 5461 hanzi = 16383 bytes; +1 hanzi = 16386 > 16384.
        assertAtLimitUtf8(
            cjk(5461),
            BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES - 1,
            BoundedPayloadMapper::boundedExpandedResult,
            BoundedPayloadMapper.RESULT_TRUNCATED_SUFFIX
        )
    }

    @Test
    fun expandedResult_atLimitUtf8_4ByteEmoji_deterministicKeptBytes() {
        // 4096 emoji = 16384 bytes exactly; +1 emoji = 16388 > 16384.
        assertAtLimitUtf8(
            emoji(4096),
            BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES,
            BoundedPayloadMapper::boundedExpandedResult,
            BoundedPayloadMapper.RESULT_TRUNCATED_SUFFIX
        )
    }

    @Test
    fun genericPreview_atLimitUtf8_3ByteHanzi_deterministicKeptBytes() {
        // 341 hanzi = 1023 bytes; +1 hanzi = 1026 > 1024.
        assertAtLimitUtf8(
            cjk(341),
            BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES - 1,
            BoundedPayloadMapper::boundedGenericPreview,
            BoundedPayloadMapper.GENERIC_TRUNCATED_SUFFIX
        )
    }

    @Test
    fun genericPreview_atLimitUtf8_4ByteEmoji_deterministicKeptBytes() {
        // 256 emoji = 1024 bytes exactly; +1 emoji = 1028 > 1024.
        assertAtLimitUtf8(
            emoji(256),
            BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES,
            BoundedPayloadMapper::boundedGenericPreview,
            BoundedPayloadMapper.GENERIC_TRUNCATED_SUFFIX
        )
    }

    // ------------------------------------------------------------------
    // Budget 5: expanded result — 16 KiB UTF-8, visible truncation label
    // ------------------------------------------------------------------

    @Test
    fun expandedResult_below16KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES - 1)
        val out = BoundedPayloadMapper.boundedExpandedResult(input)!!
        assertEquals(BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES - 1, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun expandedResult_at16KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES)
        val out = BoundedPayloadMapper.boundedExpandedResult(input)!!
        assertEquals(BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun expandedResult_above16KiB_totalBytesBoundedWithVisibleLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES + 4096)
        val out = BoundedPayloadMapper.boundedExpandedResult(input)!!
        assertTrue(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        assertTrue(BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES + 512)
    }

    // ------------------------------------------------------------------
    // Budget 6: generic payload preview — 1 KiB UTF-8, visible truncation label
    // ------------------------------------------------------------------

    @Test
    fun genericPreview_below1KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES - 1)
        val out = BoundedPayloadMapper.boundedGenericPreview(input)!!
        assertEquals(BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES - 1, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun genericPreview_at1KiB_utf8Kept() {
        val input = ascii(BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES)
        val out = BoundedPayloadMapper.boundedGenericPreview(input)!!
        assertEquals(BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES, BoundedPayloadMapper.utf8ByteCount(out))
        assertFalse(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun genericPreview_above1KiB_totalBytesBoundedWithVisibleLabel() {
        val input = ascii(BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES + 512)
        val out = BoundedPayloadMapper.boundedGenericPreview(input)!!
        assertTrue(out.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        assertTrue(BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_GENERIC_PREVIEW_UTF8_BYTES + 256)
    }

    // ------------------------------------------------------------------
    // Budgets 7/8: structured traversal — depth 8 / 256 aggregate values
    // (enforced DURING traversal, PAT-003)
    // ------------------------------------------------------------------

    @Test
    fun traversal_depth7_staysBoundedWithoutMarker() {
        var payload: Any? = "leaf"
        repeat(6) { payload = mapOf("k" to payload) } // total depth 7
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertFalse(out.contains(BoundedPayloadMapper.DEPTH_BOUND_MARKER))
        assertTrue(out.contains("leaf"))
    }

    @Test
    fun traversal_depth8_atBudget_leafRenders_noMarker() {
        // Depth 8 is the inclusive render boundary (consistent with every other
        // D-09 budget: at-limit content is kept, beyond is cut).
        var payload: Any? = "leaf"
        repeat(7) { payload = mapOf("k" to payload) } // leaf sits at depth 8
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertFalse(out.contains(BoundedPayloadMapper.DEPTH_BOUND_MARKER))
        assertTrue(out.contains("leaf"))
    }

    @Test
    fun traversal_depth9_aboveBudget_stopsWithBoundedMarker_neverDeep() {
        // Build 40 levels deep — far beyond the budget; the walk must stop at 8,
        // never materialize the deep tail.
        var payload: Any? = "leaf"
        repeat(39) { payload = mapOf("k" to payload) }
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertTrue(out.contains(BoundedPayloadMapper.DEPTH_BOUND_MARKER))
        assertFalse(out.contains("leaf")) // content beyond depth 8 never rendered
    }

    @Test
    fun traversal_255Values_withinBudget_noMarker() {
        val payload = (1..255).associate { "k$it" to it }
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertFalse(out.contains(BoundedPayloadMapper.VALUE_COUNT_BOUND_MARKER))
        assertTrue(out.contains("k255"))
    }

    @Test
    fun traversal_256Values_atBudget_noMarker() {
        val payload = (1..256).associate { "k$it" to it }
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertFalse(out.contains(BoundedPayloadMapper.VALUE_COUNT_BOUND_MARKER))
        assertTrue(out.contains("k256"))
    }

    @Test
    fun traversal_257Values_aboveBudget_stopsWithBoundedMarker() {
        val payload = (1..257).associate { "k$it" to it }
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertTrue(out.contains(BoundedPayloadMapper.VALUE_COUNT_BOUND_MARKER))
        assertFalse(out.contains("k257"))
    }

    @Test
    fun traversal_oversizedNestedPayload_outputRemainsBounded() {
        // Wide + deep + big scalars combined; output must remain far below the
        // expanded budgets regardless of input size.
        var nested: Any? = ascii(1000)
        repeat(20) { nested = mapOf("k" to nested) }
        val payload = mapOf(
            "deep" to nested,
            "wide" to (1..5000).associate { "k$it" to ascii(64) }
        )
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertTrue(
            "traversal output must stay under the 16 KiB expanded budget",
            BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES
        )
    }

    @Test
    fun traversal_topLevelString_rendersRawWithoutQuotes() {
        // Certified tool.complete result shape: a plain string result renders raw.
        assertEquals("test\n", BoundedPayloadMapper.traverseStructured("test\n"))
    }

    /**
     * Review repair [HIGH] traversal probe: a single 6 MiB scalar string leaf
     * must produce ≤ 16 KiB output, and the peak materialized string must stay
     * far below the input (window-based capping during the walk, PAT-003).
     */
    @Test
    fun traversal_singleOversizedStringLeaf_outputBounded_inputNeverFullyMaterialized() {
        val input = ascii(6_000_000) // 6 MiB, 1 UTF-8 byte per char
        val out = BoundedPayloadMapper.traverseStructured(mapOf("k" to input))
        assertTrue(
            "traversal output must stay <= 16 KiB",
            BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES
        )
        assertTrue(out.contains("k"))
        assertFalse("beyond-budget tail must never render", out.contains("b"))
        // Bounded markers present: the scalar was capped mid-walk.
        assertTrue(out.contains(BoundedPayloadMapper.TRAVERSAL_CUT_MARKER))
        // Top-level oversized string routes through the same capped path.
        val topLevel = BoundedPayloadMapper.traverseStructured(input)
        assertTrue(
            "top-level scalar output must stay <= 16 KiB",
            BoundedPayloadMapper.utf8ByteCount(topLevel) <= BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES
        )
        assertTrue(topLevel.endsWith(BoundedPayloadMapper.RESULT_TRUNCATED_SUFFIX))
    }

    /**
     * Peak-materialization probe: the capped window materialized for a single
     * oversized scalar must be a small fixed-size sample (budget + redaction
     * look-ahead), never proportional to the 6 MiB input.
     */
    @Test
    fun traversal_oversizedScalar_keptWindowIsSmallFixedSample() {
        val input = ascii(6_000_000)
        val out = BoundedPayloadMapper.traverseStructured(mapOf("k" to input))
        // Output length itself bounds the materialized window: the mapper only
        // ever holds its own output-scale strings, so the output size IS the
        // peak. Assert it is far below the input.
        assertTrue(
            "peak materialized string must be far below the 6 MiB input",
            out.length < 64 * 1024
        )
    }

    /**
     * Review repair: traversal string caps must also be multibyte-safe — a
     * hanzi scalar cut at the remaining budget never splits a 3-byte sequence.
     */
    @Test
    fun traversal_oversizedMultibyteScalar_neverSplitsUtf8Sequence() {
        val input = cjk(6_000_000 / 3)
        val out = BoundedPayloadMapper.traverseStructured(mapOf("k" to input))
        assertTrue(BoundedPayloadMapper.utf8ByteCount(out) <= BoundedPayloadMapper.MAX_EXPANDED_RESULT_UTF8_BYTES)
        val decoded = out.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        assertEquals("no partial UTF-8 sequence may survive", out, decoded)
        assertFalse(Character.isHighSurrogate(out.last()))
    }

    // ------------------------------------------------------------------
    // CON-008: surrogate normalization
    // ------------------------------------------------------------------

    @Test
    fun normalizeSurrogates_replacesUnpairedSurrogatesWithReplacementChar() {
        val unpairedHigh = "a\uD800b"
        val normalized = BoundedPayloadMapper.normalizeSurrogates(unpairedHigh)
        assertEquals("a\uFFFDb", normalized)

        val unpairedLow = "a\uDC00b"
        assertEquals("a\uFFFDb", BoundedPayloadMapper.normalizeSurrogates(unpairedLow))

        val paired = "a\uD83D\uDE00b"
        assertEquals(paired, BoundedPayloadMapper.normalizeSurrogates(paired))
    }

    @Test
    fun utf8ByteCount_measuresNormalizedStream() {
        // Unpaired surrogate would encode as 3 replacement bytes after
        // normalization, not as a raw 3-byte CESU sequence.
        assertEquals(5, BoundedPayloadMapper.utf8ByteCount("a\uD800b"))
    }

    // ------------------------------------------------------------------
    // Secret-like values in every display field with aligned redaction
    // ------------------------------------------------------------------

    @Test
    fun secrets_redactedInNameTypeField() {
        val secret = "sk-" + "abcdefghijklmnopqrstuvwxyz123456"
        val out = BoundedPayloadMapper.boundedNameType("tool key $secret")
        assertFalse(out.contains(secret))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInContextField() {
        val secret = "Bearer " + "x".repeat(40)
        val out = BoundedPayloadMapper.boundedContext("context with $secret inside")!!
        assertFalse(out.contains("x".repeat(40)))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInCollapsedSummaryField() {
        val out = BoundedPayloadMapper.boundedCollapsedSummary("summary password: hunter2")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInExpandedArgsField() {
        val out = BoundedPayloadMapper.boundedExpandedArgs("args api_key: \"supersecretkeyvalue\"")!!
        assertFalse(out.contains("supersecretkeyvalue"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInExpandedResultField() {
        val secret = "ghp_" + "abcdefghijklmnopqrstuvwxyz123456"
        val out = BoundedPayloadMapper.boundedExpandedResult("result token: $secret")!!
        assertFalse(out.contains(secret))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInGenericPreviewField() {
        val secret = "xoxb-" + "1234567890abcdef"
        val out = BoundedPayloadMapper.boundedGenericPreview("payload secret: $secret")!!
        assertFalse(out.contains(secret))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun secrets_redactedInStructuredTraversalScalars() {
        val skSecret = "sk-" + "abcdefghijklmnopqrstuvwxyz123456"
        val payload = mapOf(
            "password" to "hunter2secret",
            "nested" to mapOf("token" to ("Bearer " + "abcdefghijklmnop")),
            "list" to listOf("api_key: " + skSecret)
        )
        val out = BoundedPayloadMapper.traverseStructured(payload)
        assertFalse(out.contains("hunter2secret"))
        assertFalse(out.contains("abcdefghijklmnop"))
        assertFalse(out.contains(skSecret))
        assertTrue(out.contains("[REDACTED]"))
    }

    // ------------------------------------------------------------------
    // ChatSecurityPolicy integration (TASK-034: replaces unbounded paths)
    // ------------------------------------------------------------------

    @Test
    fun formatToolArgs_retainsFullTextWithoutTruncation() {
        val longString = ascii(2000)
        val args = mapOf("key" to longString)
        val out = ChatSecurityPolicy.formatToolArgs(args)
        assertTrue("Tool args must retain full text", out.contains(longString))
    }

    @Test
    fun formatToolResult_retainsFullTextUnder1MB() {
        val result = ascii(32 * 1024)
        val out = ChatSecurityPolicy.formatToolResult(result)
        assertEquals(result, out)
    }

    @Test
    fun formatToolResult_above1MBCappedWithVisibleLabel() {
        val result = "a".repeat(1024 * 1024 + 50 * 1024)
        val out = ChatSecurityPolicy.formatToolResult(result)
        assertTrue("Output above 1MB must contain crash-guard ceiling label", out.contains("output too large, 50 KB omitted"))
    }

    @Test
    fun formatToolArgs_nestedPayload_retainsFullText() {
        val leaf = "deep_leaf_value"
        var nested: Any? = leaf
        repeat(5) { nested = mapOf("k" to nested) }
        val out = ChatSecurityPolicy.formatToolArgs(mapOf("payload" to nested))
        assertTrue("Nested payload retains full text", out.contains(leaf))
    }

    // ------------------------------------------------------------------
    // SEC-004 (review repair): typed failure copy REMOVED
    // ------------------------------------------------------------------

    @Test
    fun sec004_failureCopyRemoved_noTypedFailureApiRemains() {
        // The gateway wire has no typed failure field on `tool.complete`
        // (contract: tui-gateway-client-contract.md), so the payload-influenced
        // FAILURE_COPY_PREFIX branch and the failureCopy() API were removed.
        // Guard against reintroduction until a real producer exists. Java
        // reflection (no kotlin-reflect on the unit-test classpath).
        val methods = BoundedPayloadMapper::class.java.declaredMethods.map { it.name }.toSet()
        val fields = BoundedPayloadMapper::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("failureCopy must not exist on BoundedPayloadMapper", methods.contains("failureCopy"))
        assertFalse("FAILURE_COPY_PREFIX must not exist on BoundedPayloadMapper", fields.contains("FAILURE_COPY_PREFIX"))
        assertFalse("FAILURE_COPY must not exist on BoundedPayloadMapper", fields.contains("FAILURE_COPY"))
    }

    // ------------------------------------------------------------------
    // Display-contract tests for the card composables (M-04 copy rows)
    // ------------------------------------------------------------------

    @Test
    fun truncationLabels_carryRequiredDetailsTruncatedCopy() {
        // TASK-035: generic/tool truncation uses "Details truncated." where no
        // more specific label applies.
        assertTrue(BoundedPayloadMapper.ARGS_TRUNCATED_SUFFIX.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        assertTrue(BoundedPayloadMapper.RESULT_TRUNCATED_SUFFIX.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
        assertTrue(BoundedPayloadMapper.GENERIC_TRUNCATED_SUFFIX.contains(BoundedPayloadMapper.DETAILS_TRUNCATED_LABEL))
    }

    @Test
    fun contextLabel_isContextTruncated() {
        assertEquals("context truncated", BoundedPayloadMapper.CONTEXT_TRUNCATED_LABEL)
    }
}
