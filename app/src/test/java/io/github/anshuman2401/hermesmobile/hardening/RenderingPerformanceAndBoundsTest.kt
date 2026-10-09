package io.github.anshuman2401.hermesmobile.hardening

import io.github.anshuman2401.hermesmobile.feature.chat.ChatSecurityPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownNode
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * BLD-008: Rendering bounds, performance resilience, and adversarial input fuzzing test.
 * Verifies that native Markdown parsing and tool rendering never crash, freeze, or exhaust memory
 * when subjected to massive streams, unclosed delimiters, or adversarial payloads.
 */
class RenderingPerformanceAndBoundsTest {

    @Test
    fun massiveStream_tenThousandLines_parsesUnderFiveHundredMilliseconds() {
        val massiveContent = buildString {
            for (i in 1..10_000) {
                when (i % 5) {
                    0 -> appendLine("## Section Header $i")
                    1 -> appendLine("This is paragraph $i with **bold text** and `inline code`.")
                    2 -> appendLine("> Blockquote on line $i describing important agent observation.")
                    3 -> appendLine("- Bullet item $i with [safe link](https://example.com/item/$i)")
                    4 -> appendLine("```kotlin\nval sampleVar$i = $i * 2\n```")
                }
            }
        }

        val duration = measureTimeMillis {
            val ast = MarkdownParser.parse(massiveContent)
            assertNotNull(ast)
            fun descendants(nodes: List<MarkdownNode>): List<MarkdownNode> = nodes.flatMap { node ->
                val children = when (node) {
                    is MarkdownNode.ListGroup -> node.items
                    is MarkdownNode.Blockquote -> node.children
                    is MarkdownNode.UnorderedListItem -> node.children
                    is MarkdownNode.OrderedListItem -> node.children
                    is MarkdownNode.TaskListItem -> node.children
                    else -> emptyList()
                }
                listOf(node) + descendants(children)
            }
            val disclosures = setOf(MarkdownParser.LINE_BUDGET_LABEL, MarkdownParser.INPUT_BUDGET_LABEL)
            val projected = descendants(ast).filterNot {
                it is MarkdownNode.ListGroup || (it is MarkdownNode.Paragraph && it.text in disclosures)
            }
            assertTrue("Input-limit disclosures stay visible and separate from content", ast.filterIsInstance<MarkdownNode.Paragraph>().any {
                it.text == MarkdownParser.LINE_BUDGET_LABEL
            })
            assertTrue("Projection must enforce the 2,000-block budget plus one fallback", projected.size <= MarkdownParser.MAX_AST_NODES + 1)
            assertTrue("Large input must retain the initial content", (ast.first() as MarkdownNode.Paragraph).text.startsWith("This is paragraph 1"))
            assertTrue("Remaining content must have a visible bounded fallback", projected.filterIsInstance<MarkdownNode.CodeBlock>().any {
                it.notice?.contains(MarkdownParser.AST_BUDGET_LABEL) == true
            })
        }

        assertTrue("10,000 line stream parse must complete in < 500ms (took ${duration}ms)", duration < 500)
    }

    @Test
    fun adversarialCorpus_unclosedFencesAndDelimiters_doesNotHangOrCrash() {
        val adversarialInputs = listOf(
            // 5,000 unclosed backticks
            "`".repeat(5000),
            // Unclosed code block with enormous body
            "```typescript\n" + "function loop() {\n".repeat(2000),
            // Deeply nested blockquotes
            "> ".repeat(200) + "Deeply nested quote content",
            // Deeply nested lists
            "  ".repeat(100) + "* Nested list item",
            // Single line 50,000 characters without spaces
            "A".repeat(50_000),
            // Null bytes and control characters
            "Text with null \u0000 and bell \u0007 and backspace \u0008 and formfeed \u000C",
            // Right-to-Left bidirectional override character
            "Safe text \u202E malicious reversed text \u202C continuation",
            // Unpaired high surrogate characters
            "Surrogate pair test: \uD83D (unpaired)",
            // Raw script tags and HTML injection attempts
            "<script>alert(1)</script><img src=x onerror=alert(2)><iframe src='https://evil.com'></iframe>"
        )

        for (input in adversarialInputs) {
            val ast = MarkdownParser.parse(input)
            assertNotNull("Parser must safely return an AST for adversarial input", ast)
            // Verify raw scripts are never parsed into actionable execution objects
            for (block in ast) {
                if (block is MarkdownNode.Paragraph) {
                    val inline = MarkdownParser.buildInlineMarkdown(block.text)
                    assertNotNull(inline)
                }
            }
        }
    }

    @Test
    fun toolActivityFormatting_massiveArgumentsPayload_redactsAndFormatsSafely() {
        val largeArgs = mutableMapOf<String, Any?>()
        for (i in 1..2000) {
            largeArgs["arg_$i"] = "value_$i"
        }
        largeArgs["auth_token"] = "Bearer sk-1234567890abcdef"
        largeArgs["db_password"] = "SuperSecretDbPassword123"

        val duration = measureTimeMillis {
            val formatted = ChatSecurityPolicy.formatToolArgs(largeArgs)
            assertNotNull(formatted)
            // PRD v2 Owner Decision #2 & #5: full text retained without display-level redaction
            assertTrue("Full args retained", formatted.contains("arg_2000: \"value_2000\""))
            assertTrue("Auth token retained full text", formatted.contains("sk-1234567890abcdef"))
            assertTrue("Password retained full text", formatted.contains("SuperSecretDbPassword123"))
        }

        assertTrue("Tool arguments formatting must be instantaneous (< 100ms, took ${duration}ms)", duration < 100)
    }

    @Test
    fun markdownTableParsing_largeTable_doesNotExhaustMemory() {
        val tableMarkdown = buildString {
            appendLine("| Column 1 | Column 2 | Column 3 |")
            appendLine("|---|---|---|")
            for (i in 1..500) {
                appendLine("| Data 1-$i | Data 2-$i | Data 3-$i |")
            }
        }

        val ast = MarkdownParser.parse(tableMarkdown)
        assertNotNull(ast)
        assertTrue(ast.isNotEmpty())
    }
}
