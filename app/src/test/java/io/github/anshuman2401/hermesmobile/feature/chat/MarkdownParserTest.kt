package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownBounds
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownNode
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S9 (TASK-039/040, M-05): Markdown D-10 budget matrix.
 *
 * Every numeric budget is probed below (limit−1), at (exactly limit), and
 * above (limit+1) on the Unicode-scalar axis; byte-axis budgets for code
 * live in CodeBlockBudgetTest. Enforcement happens during scanning, so the
 * over-budget probes also prove the input never materializes unbounded
 * (bounded memory is asserted by completing within the default test
 * timeout while keeping only budget-sized output).
 */
class MarkdownParserTest {

    // ------------------------------------------------------------------
    // Grammar (existing behavior preserved)
    // ------------------------------------------------------------------

    @Test
    fun parse_headings_extractsCorrectLevelsAndText() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            #### Heading 4
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(4, nodes.size)

        val h1 = nodes[0] as MarkdownNode.Heading
        assertEquals(1, h1.level)
        assertEquals("Heading 1", h1.text)

        val h2 = nodes[1] as MarkdownNode.Heading
        assertEquals(2, h2.level)
        assertEquals("Heading 2", h2.text)

        val h3 = nodes[2] as MarkdownNode.Heading
        assertEquals(3, h3.level)
        assertEquals("Heading 3", h3.text)

        // The upgraded grammar preserves all six heading levels.
        val h4 = nodes[3] as MarkdownNode.Heading
        assertEquals(4, h4.level)
        assertEquals("Heading 4", h4.text)
    }

    @Test
    fun parse_codeBlock_extractsLanguageAndCodeContent() {
        val markdown = """
            ```kotlin
            fun hello(): String = "world"
            println(hello())
            ```
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(1, nodes.size)
        val codeNode = nodes[0] as MarkdownNode.CodeBlock
        assertEquals("kotlin", codeNode.language)
        assertTrue(codeNode.code.contains("fun hello(): String"))
        assertTrue(codeNode.code.contains("println(hello())"))
    }

    @Test
    fun parse_codeBlock_handlesUnclosedFenceGracefully() {
        val markdown = """
            ```python
            x = 42
            print(x)
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(1, nodes.size)
        val codeNode = nodes[0] as MarkdownNode.CodeBlock
        assertEquals("python", codeNode.language)
        assertTrue(codeNode.code.contains("print(x)"))
        assertTrue(
            "unclosed fence must carry the visible malformed label",
            codeNode.notice?.contains(MarkdownParser.MALFORMED_FENCE_LABEL) == true
        )
    }

    @Test
    fun parse_blockquote_extractsIndentedContent() {
        val markdown = """
            > This is a quote.
            > It spans multiple lines.
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(1, nodes.size)
        val quoteNode = nodes[0] as MarkdownNode.Blockquote
        assertTrue(quoteNode.text.contains("This is a quote."))
        assertTrue(quoteNode.text.contains("It spans multiple lines."))
    }

    @Test
    fun parse_lists_unorderedAndOrdered() {
        val markdown = """
            - Apple
            * Banana
            - Cherry

            1. First step
            2. Second step
            3. Third step
        """.trimIndent()

        val groups = MarkdownParser.parse(markdown).filterIsInstance<MarkdownNode.ListGroup>()
        val nodes = groups.flatMap { it.items }
        assertEquals(6, nodes.size)

        assertEquals("Apple", (nodes[0] as MarkdownNode.UnorderedListItem).text)
        assertEquals("Banana", (nodes[1] as MarkdownNode.UnorderedListItem).text)
        assertEquals("Cherry", (nodes[2] as MarkdownNode.UnorderedListItem).text)

        val o1 = nodes[3] as MarkdownNode.OrderedListItem
        assertEquals(1, o1.index)
        assertEquals("First step", o1.text)

        val o2 = nodes[4] as MarkdownNode.OrderedListItem
        assertEquals(2, o2.index)
        assertEquals("Second step", o2.text)
    }

    @Test
    fun parse_table_extractsHeadersAndRows() {
        val markdown = """
            | ID | Name | Status |
            |---|---|---|
            | 1 | Hermes | Active |
            | 2 | Apollo | Inactive |
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(1, nodes.size)
        val table = nodes[0] as MarkdownNode.Table
        assertEquals(listOf("ID", "Name", "Status"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("1", "Hermes", "Active"), table.rows[0])
        assertEquals(listOf("2", "Apollo", "Inactive"), table.rows[1])
    }

    @Test
    fun parse_horizontalRule() {
        val markdown = """
            Above

            ---

            Below
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown)
        assertEquals(3, nodes.size)
        assertTrue(nodes[0] is MarkdownNode.Paragraph)
        assertTrue(nodes[1] is MarkdownNode.HorizontalRule)
        assertTrue(nodes[2] is MarkdownNode.Paragraph)
    }

    // ------------------------------------------------------------------
    // D-10 task-list rows (read-only, noninteractive)
    // ------------------------------------------------------------------

    @Test
    fun parse_taskList_rowsAreReadOnlyData() {
        val markdown = """
            - [x] shipped slice
            - [ ] pending review
            * [X] uppercase also counts
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown).filterIsInstance<MarkdownNode.ListGroup>().flatMap { it.items }
        assertEquals(3, nodes.size)
        val done = nodes[0] as MarkdownNode.TaskListItem
        assertTrue(done.checked)
        assertEquals("shipped slice", done.text)
        val pending = nodes[1] as MarkdownNode.TaskListItem
        assertFalse(pending.checked)
        assertEquals("pending review", pending.text)
        val upper = nodes[2] as MarkdownNode.TaskListItem
        assertTrue(upper.checked)
    }

    @Test
    fun parse_taskList_malformedBracketsRemainOrdinaryLists() {
        val markdown = """
            - [xno] not a task
            - [] too short
            - [ ]  spaced marker stays a task
        """.trimIndent()

        val nodes = MarkdownParser.parse(markdown).filterIsInstance<MarkdownNode.ListGroup>().flatMap { it.items }
        assertEquals(3, nodes.size)
        assertTrue(nodes[0] is MarkdownNode.UnorderedListItem)
        assertTrue(nodes[1] is MarkdownNode.UnorderedListItem)
        assertTrue(nodes[2] is MarkdownNode.TaskListItem)
    }

    // ------------------------------------------------------------------
    // D-10 budget: 10,000 lines
    // ------------------------------------------------------------------

    @Test
    fun parse_lineBudget_below_at_above() {
        // One node per 10 lines (heading + 9 blanks) keeps the AST-node
        // budget below its own cap while probing the 10,000-line budget.
        fun corpus(units: Int, extraHeadingLine: Boolean = false) = buildString {
            for (i in 1..units) {
                append("## h ").append(i).append('\n')
                repeat(9) { append('\n') }
            }
            if (extraHeadingLine) append("## h extra\n")
        }

        val below = MarkdownParser.parse(corpus(999, extraHeadingLine = true)) // 9999 lines
        assertTrue(
            "below budget: every line parses",
            below.count { it is MarkdownNode.Heading } == 1000
        )
        assertFalse(
            "below budget: no truncation notice",
            below.any { (it as? MarkdownNode.Paragraph)?.text == MarkdownParser.LINE_BUDGET_LABEL }
        )

        val at = MarkdownParser.parse(corpus(1000)) // exactly 10,000 lines
        assertTrue(
            "at budget: every line parses",
            at.count { it is MarkdownNode.Heading } == 1000
        )
        assertFalse(
            "at budget: no truncation notice",
            at.any { (it as? MarkdownNode.Paragraph)?.text == MarkdownParser.LINE_BUDGET_LABEL }
        )

        val above = MarkdownParser.parse(corpus(1000, extraHeadingLine = true)) // 10,001 lines
        assertTrue(
            "above budget: lines beyond the budget are dropped",
            above.count { it is MarkdownNode.Heading } == 1000
        )
        assertTrue(
            "above budget: visible truncation notice present",
            above.lastOrNull()?.let { it is MarkdownNode.Paragraph && it.text == MarkdownParser.LINE_BUDGET_LABEL } == true
        )
    }

    @Test
    fun parse_lineBudget_countsCRLFAndCRLineBreaks() {
        // Same node-light pattern as parse_lineBudget_below_at_above but
        // with CRLF/CR line breaks (CON-002): each counts as one break.
        fun corpus(unitCount: Int, eol: String) = buildString {
            for (i in 1..unitCount) {
                append("## c ").append(i).append(eol)
                repeat(9) { append(eol) }
            }
            append("## c over").append(eol)
        }
        val eolCrLf = "\r\n"
        val eolCr = "\r"

        val crlfCorpus = corpus(MarkdownParser.MAX_LINES / 10, eolCrLf) // 10,001 lines
        val crlfNodes = MarkdownParser.parse(crlfCorpus)
        assertEquals(
            "CRLF counts as one line break; budget is not inflated or silent",
            1001,
            crlfNodes.size
        )

        val crCorpus = corpus(MarkdownParser.MAX_LINES / 10, eolCr)
        val crNodes = MarkdownParser.parse(crCorpus)
        assertEquals(
            "CR counts as a line break",
            1001,
            crNodes.size
        )
    }

    // ------------------------------------------------------------------
    // D-10 budget: 8,192 Unicode scalars per line
    // ------------------------------------------------------------------

    @Test
    fun parse_lineScalarBudget_below_at_above() {
        fun line(scalars: Int) = "x".repeat(scalars) + "\n"

        val below = MarkdownParser.parse(line(MarkdownParser.MAX_LINE_SCALARS - 1))
        val belowParagraph = below[0] as MarkdownNode.Paragraph
        assertEquals(MarkdownParser.MAX_LINE_SCALARS - 1, belowParagraphScalars(below))
        assertFalse(below[0].toString().contains(MarkdownParser.LINE_SCALARS_LABEL))

        val at = MarkdownParser.parse(line(MarkdownParser.MAX_LINE_SCALARS))
        assertFalse(
            "at budget: no truncation notice",
            at[0].toString().contains(MarkdownParser.LINE_SCALARS_LABEL)
        )

        val above = MarkdownParser.parse(line(MarkdownParser.MAX_LINE_SCALARS + 1))
        val aboveParagraph = above[0] as MarkdownNode.Paragraph
        assertTrue(
            "above budget: per-entry truncation notice is visible",
            aboveParagraph.text.contains(MarkdownParser.LINE_SCALARS_LABEL)
        )
        // Kept scalars stay within the budget (label is appended text).
        assertTrue(
            "above budget: kept body does not exceed the scalar cap",
            aboveParagraph.text.length <=
                MarkdownParser.MAX_LINE_SCALARS + MarkdownParser.LINE_SCALARS_LABEL.length + 1
        )
        // No surrogate split: a multibyte over-budget line keeps pairs whole.
        val emoji = "\uD83D\uDE00" // 😀, one scalar, surrogate pair in UTF-16
        val emojiLine = emoji.repeat(MarkdownParser.MAX_LINE_SCALARS + 1)
        val emojiNodes = MarkdownParser.parse(emojiLine)
        val emojiParagraph = emojiNodes[0] as MarkdownNode.Paragraph
        val kept = emojiParagraph.text.removeSuffix(" ${MarkdownParser.LINE_SCALARS_LABEL}")
        assertEquals(
            "surrogate pairs are never split by the scalar cap",
            MarkdownParser.MAX_LINE_SCALARS,
            kept.codePointCount(0, kept.length)
        )
        assertTrue(kept.codeUnitsContainNoUnpairedSurrogate())
    }

    private fun belowParagraphScalars(nodes: List<MarkdownNode>): Int =
        (nodes[0] as MarkdownNode.Paragraph).text.codePointCount(0, (nodes[0] as MarkdownNode.Paragraph).text.length)

    private fun String.codeUnitsContainNoUnpairedSurrogate(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i += 1
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    // D-10 budget: 10,000 lines x 2,000 AST nodes combined
    // ------------------------------------------------------------------

    @Test
    fun parse_astCapOverOversizedCorpus_lineBudgetNoticeStillEmitted() {
        // F3 regression probe: the corpus holds exactly 10,000 retained
        // lines (plus one dropped tail line) AND the 2,000-node budget is
        // hit at the first paragraph line (index 9,000), so the parser
        // exits via the AST-cap early return. The 10,000-line truncation
        // notice must still be emitted as a separate visible paragraph
        // (never silently dropped). The AST fallback body here is exactly
        // 1,000 lines — at, not over, the 1,000-line preformatted cap —
        // so the fallback cannot carry the line-budget notice itself and
        // the two labels stay distinguishable.
        fun corpus() = buildString {
            repeat(7000) { append('\n') } // 7,000 blank lines (no nodes)
            for (i in 1..2000) {
                append("## h ").append(i).append('\n')
            }
            for (i in 1..1000) {
                append("p ").append(i).append('\n')
            }
            append("p 1001") // 10,001st line: dropped by the line budget
        } // retained = 7,000 + 2,000 + 1,000 = exactly 10,000 lines

        val nodes = MarkdownParser.parse(corpus())

        assertEquals(
            "node budget hit at the first paragraph line",
            2000,
            nodes.count { it is MarkdownNode.Heading }
        )
        val fallback = nodes.filterIsInstance<MarkdownNode.CodeBlock>().single()
        assertTrue(
            "AST-cap fallback carries its own label",
            fallback.notice?.contains(MarkdownParser.AST_BUDGET_LABEL) == true
        )
        assertFalse(
            "the line-budget notice is not buried inside the fallback body",
            fallback.code.contains(MarkdownParser.LINE_BUDGET_LABEL)
        )
        assertTrue(
            "10,000-line truncation notice survives the AST-cap early return",
            nodes.any { it is MarkdownNode.Paragraph && it.text == MarkdownParser.LINE_BUDGET_LABEL }
        )
    }

    // ------------------------------------------------------------------
    // D-10 budget: 2,000 AST nodes
    // ------------------------------------------------------------------

    @Test
    fun parse_astNodeBudget_below_at_above() {
        // Headings keep one node per line (paragraphs merge adjacent lines).
        fun corpus(nodeCount: Int) = buildString {
            for (i in 1..nodeCount) append("## n ").append(i).append('\n')
        }

        val below = MarkdownParser.parse(corpus(MarkdownParser.MAX_AST_NODES - 1))
        assertEquals(MarkdownParser.MAX_AST_NODES - 1, below.size)

        val at = MarkdownParser.parse(corpus(MarkdownParser.MAX_AST_NODES))
        assertEquals(MarkdownParser.MAX_AST_NODES, at.size)

        val above = MarkdownParser.parse(corpus(MarkdownParser.MAX_AST_NODES + 5))
        assertEquals(
            "above budget: exactly the frozen content-node cap is kept",
            MarkdownParser.MAX_AST_NODES,
            above.size - 1 // the bounded labeled fallback marker is the extra node
        )
        val fallback = above.last() as MarkdownNode.CodeBlock
        assertTrue(
            "remainder becomes the labeled preformatted fallback",
            fallback.notice?.contains(MarkdownParser.AST_BUDGET_LABEL) == true
        )
        assertTrue(
            "fallback carries at least one dropped line",
            fallback.code.contains("n ${MarkdownParser.MAX_AST_NODES + 1}")
        )
    }

    // ------------------------------------------------------------------
    // D-10 table bounds: 50 rows x 12 columns, 512 scalars/cell
    // ------------------------------------------------------------------

    @Test
    fun parse_table_rowBudget_below_at_above() {
        fun table(rows: Int): String = buildString {
            append("| A | B |\n")
            append("|---|---|\n")
            for (i in 1..rows) append("| r").append(i).append("a | r").append(i).append("b |\n")
        }

        val below = (MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_ROWS - 1))[0] as MarkdownNode.Table)
        assertEquals(MarkdownParser.MAX_TABLE_ROWS - 1, below.rows.size)

        val at = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_ROWS))[0] as MarkdownNode.Table
        assertEquals(MarkdownParser.MAX_TABLE_ROWS, at.rows.size)

        val aboveNodes = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_ROWS + 1))
        assertTrue(aboveNodes.size == 1 && aboveNodes[0] is MarkdownNode.CodeBlock)
        assertTrue(
            (aboveNodes[0] as MarkdownNode.CodeBlock).notice?.contains(MarkdownParser.TABLE_BUDGET_LABEL) == true
        )
    }

    @Test
    fun parse_table_columnBudget_below_at_above() {
        fun table(columns: Int): String {
            val header = (1..columns).joinToString("") { "| h$it " } + "|\n"
            val separator = (1..columns).joinToString("") { "|---" } + "|\n"
            val row = (1..columns).joinToString("") { "| c$it " } + "|\n"
            return header + separator + row
        }

        val below = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_COLUMNS - 1))
        assertTrue(below[0] is MarkdownNode.Table)
        assertEquals(
            MarkdownParser.MAX_TABLE_COLUMNS - 1,
            (below[0] as MarkdownNode.Table).headers.size
        )

        val at = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_COLUMNS))
        assertTrue(at[0] is MarkdownNode.Table)

        val above = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_COLUMNS + 1))
        assertTrue(above.size == 1 && above[0] is MarkdownNode.CodeBlock)
        assertTrue(
            (above[0] as MarkdownNode.CodeBlock).notice?.contains(MarkdownParser.TABLE_BUDGET_LABEL) == true
        )
    }

    @Test
    fun parse_table_cellScalarBudget_below_at_above() {
        fun table(cellScalars: Int): String {
            val cell = "y".repeat(cellScalars)
            return "| $cell |\n|---|\n| $cell |\n"
        }

        val below = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_CELL_SCALARS - 1))
        assertTrue(below[0] is MarkdownNode.Table)

        val at = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_CELL_SCALARS))
        assertTrue(at[0] is MarkdownNode.Table)

        val above = MarkdownParser.parse(table(MarkdownParser.MAX_TABLE_CELL_SCALARS + 1))
        assertTrue(above.size == 1 && above[0] is MarkdownNode.CodeBlock)
        assertTrue(
            (above[0] as MarkdownNode.CodeBlock).notice?.contains(MarkdownParser.TABLE_BUDGET_LABEL) == true
        )
    }

    @Test
    fun parse_table_malformedSeparatorBecomesLabeledPreformatted() {
        val malformed = "| A | B |\n|not a separator|\n| 1 | 2 |\n"
        val nodes = MarkdownParser.parse(malformed)
        assertTrue(nodes.size == 1 && nodes[0] is MarkdownNode.CodeBlock)
        assertTrue(
            (nodes[0] as MarkdownNode.CodeBlock).notice?.contains(MarkdownParser.TABLE_BUDGET_LABEL) == true
        )
    }

    // ------------------------------------------------------------------
    // D-10 fallback rules
    // ------------------------------------------------------------------

    @Test
    fun parse_htmlScriptStyleAndImageSyntax_renderInert() {
        val html = "<script>alert(1)</script> and <style>body{}</style>"
        val nodes = MarkdownParser.parse(html)
        // Raw HTML is visible inert literal text (no node claims it as code).
        assertTrue(nodes.isNotEmpty())
        val combined = nodes.joinToString { node ->
            when (node) {
                is MarkdownNode.Paragraph -> node.text
                is MarkdownNode.CodeBlock -> node.code
                else -> ""
            }
        }
        assertTrue(combined.contains("<script>alert(1)</script>"))
        assertTrue(combined.contains("<style>body{}</style>"))

        val inlineImage = MarkdownParser.buildInlineMarkdown("![alt text](https://example.com/x.png)")
        assertTrue(
            "image syntax renders as inert alt/source text",
            inlineImage.text.contains("[image: alt text]")
        )
        assertTrue(inlineImage.text.contains("(https://example.com/x.png)"))
        assertTrue(
            "image must not produce a clickable link",
            inlineImage.getLinkAnnotations(0, inlineImage.length).isEmpty()
        )
    }

    @Test
    fun parse_bidiAndRawControlsSurviveAsInertText() {
        val input = "safe \u202Ereversed\u202C text \u0007 bell \u0000 null"
        val nodes = MarkdownParser.parse(input)
        assertNotNull(nodes)
        assertTrue(nodes.isNotEmpty())
    }

    @Test
    fun parse_unpairedSurrogatesBecomeReplacementCharsBeforeScanning() {
        val input = "before \uD83D unpaired and \uDE00 lone low \uD83D\uDE00 pair"
        val nodes = MarkdownParser.parse(input)
        assertTrue(nodes.isNotEmpty())
        val text = nodes.joinToString { node ->
            when (node) {
                is MarkdownNode.Paragraph -> node.text
                else -> ""
            }
        }
        assertTrue(text.contains("before"))
        assertTrue(
            "no unpaired surrogate survives normalization",
            text.codeUnitsContainNoUnpairedSurrogate()
        )
        assertTrue(
            "valid surrogate pair survives as one scalar",
            text.contains("\uD83D\uDE00")
        )
    }

    @Test
    fun parse_forcedNodeFailure_degradesThatEntryOnly() {
        // The renderer wraps parse() so a parse-level failure degrades the
        // whole entry to inert text; the per-entry isolation guarantee is
        // that the parser never throws on any input.
        val adversarial = "***\n```\n\n> \n| a |\n- \n"
        val nodes = MarkdownParser.parse(adversarial)
        assertNotNull(nodes)
    }

    @Test
    fun parse_maliciousAndHugeCorpus_doesNotCrashOrHang() {
        // Deeply nested / repeated delimiters
        val adversarial = "******************************************" +
            "````````````````````````" +
            "<script>alert('xss');</script>" +
            "[unclosed link(https://test"

        val nodes = MarkdownParser.parse(adversarial)
        assertNotNull(nodes)

        // Huge corpus: 2,000 lines
        val largeMarkdown = buildString {
            for (i in 1..2000) {
                appendLine("Line $i: A paragraph with **bold** text and `inline code`.")
            }
        }
        val largeNodes = MarkdownParser.parse(largeMarkdown)
        assertTrue(largeNodes.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Inline semantics (existing behavior preserved)
    // ------------------------------------------------------------------

    @Test
    fun buildInlineMarkdown_formatsBoldItalicAndCode() {
        val text = "This is **bold**, this is *italic*, and `code snippet`."
        val annotated = MarkdownParser.buildInlineMarkdown(text)

        assertEquals("This is bold, this is italic, and code snippet.", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun buildInlineMarkdown_createsClickableLinkOnlyThroughConfirmationCallback() {
        // BLD-006 S11 (D-11): with a confirmation callback present, the link
        // is a Clickable annotation routed to the callback (never a raw
        // LinkAnnotation.Url). The tap-to-confirm wiring and the annotation
        // tag payload are Compose-tier and out-of-scope-per-owner-directive
        // (2026-09-18): Compose 1.12 in Gradle-run JVM tests returns an
        // empty tag from getLinkAnnotations, so tag content is verified by
        // the parser-level contract (canonicalLink.url is what the callback
        // receives), not by reading the tag back here.
        val text = "Check out [Hermes Docs](https://hermes-agent.nousresearch.com)."
        val annotated = MarkdownParser.buildInlineMarkdown(text) { }

        assertTrue(annotated.text.contains("Hermes Docs"))
        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, linkAnnotations.size)
    }

    @Test
    fun buildInlineMarkdown_withoutCallback_linksAreInert() {
        // BLD-006 S11 (D-11): no confirmation callback means NO clickable
        // surface — a LinkAnnotation.Url fallback would hand the raw target
        // to the system and bypass centralized confirmation.
        val text = "Check out [Hermes Docs](https://hermes-agent.nousresearch.com)."
        val annotated = MarkdownParser.buildInlineMarkdown(text)

        assertTrue(annotated.text.contains("Hermes Docs"))
        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(0, linkAnnotations.size)
    }

    @Test
    fun buildInlineMarkdown_rejectsDangerousSchemesAsInertText() {
        val text = "Dangerous [Click Me](javascript:alert(1)) and [File](file:///etc/passwd)."
        val annotated = MarkdownParser.buildInlineMarkdown(text)

        val linkAnnotations = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(0, linkAnnotations.size) // No clickable link created!
        assertTrue(annotated.text.contains("Click Me"))
        assertTrue(annotated.text.contains("File"))
    }
}

/**
 * No unpaired UTF-16 surrogate survives anywhere in the string.
 */
private fun String.codeUnitsContainNoUnpairedSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c)) {
            if (i + 1 >= length || !Character.isLowSurrogate(this[i + 1])) return false
            i += 2
        } else {
            if (Character.isLowSurrogate(c)) return false
            i += 1
        }
    }
    return true
}
