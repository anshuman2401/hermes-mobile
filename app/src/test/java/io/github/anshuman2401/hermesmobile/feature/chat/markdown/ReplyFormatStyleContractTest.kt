package io.github.anshuman2401.hermesmobile.feature.chat.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract pins for the assistant-reply formatting polish (frozen
 * plan ui-reply-format-polish-v1 §2/§3). Written RED against the pre-PR
 * styles, flipped GREEN by the product edits in the same PR. Compose-tier
 * rendering is out of JVM scope (owner device check is the acceptance
 * gate); these pins hold the source-level style contract instead.
 *
 * Style pins only — copy/semantics/security assertions live in
 * TranscriptCopyPolicyTest, ThemeAndContrastTest and the icon-only
 * contract tests and are intentionally NOT touched here.
 */
class ReplyFormatStyleContractTest {

    private fun mainSource(relPath: String): String {
        val direct = File("app/src/main/java/$relPath")
        return (if (direct.exists()) direct else File("../app/src/main/java/$relPath")).readText()
    }

    private fun renderer() = mainSource("io/github/anshuman2401/hermesmobile/feature/chat/markdown/MarkdownRenderer.kt")

    private fun bubble() = mainSource("io/github/anshuman2401/hermesmobile/feature/chat/components/AssistantMessageBubble.kt")

    private fun parser() = mainSource("io/github/anshuman2401/hermesmobile/feature/chat/markdown/MarkdownParser.kt")

    private fun codeBlock() = mainSource("io/github/anshuman2401/hermesmobile/feature/chat/markdown/CodeBlock.kt")

    private fun themeTokens() = mainSource("io/github/anshuman2401/hermesmobile/design/HermesTokens.kt")

    private fun colorTokens() = mainSource("io/github/anshuman2401/hermesmobile/design/Color.kt")

    // --- upgrade 1: syntax highlighting -------------------------------------

    @Test
    fun codeBlock_rendersWithSyntaxHighlighter() {
        val cb = codeBlock()
        assertTrue(
            "CodeBlock body must render via SyntaxHighlighter",
            cb.contains("SyntaxHighlighter.highlight(")
        )
        assertTrue(
            "CodeBlock must resolve the extended code token colors",
            cb.contains("codeKeyword") && cb.contains("codeString") &&
                cb.contains("codeComment") && cb.contains("codeNumber")
        )
    }

    @Test
    fun theme_declaresFourCodeTokensForBothThemes() {
        val tokens = themeTokens()
        for (field in listOf("codeKeyword", "codeString", "codeComment", "codeNumber")) {
            assertTrue(
                "HermesExtendedColors must declare $field",
                tokens.contains("val $field: Color")
            )
        }
        val colors = colorTokens()
        assertTrue(
            "Color.kt must declare the substituted light code shades",
            colors.contains("LightCodeKeyword = Color(0xFF6A1B9A)") &&
                colors.contains("LightCodeString = Color(0xFF1B5E20)") &&
                colors.contains("LightCodeComment = Color(0xFF455A64)") &&
                colors.contains("LightCodeNumber = Color(0xFFBF360C)")
        )
        assertTrue(
            "Color.kt must declare the dark code shades",
            colors.contains("DarkCodeKeyword = Color(0xFFCE93D8)") &&
                colors.contains("DarkCodeString = Color(0xFFA5D6A7)") &&
                colors.contains("DarkCodeComment = Color(0xFF90A4AE)") &&
                colors.contains("DarkCodeNumber = Color(0xFFFFB74D)")
        )
    }

    // --- upgrade 2: accent colors -------------------------------------------

    @Test
    fun headings_usePrimaryColor() {
        val renderer = renderer()
        val headingBlock = renderer.substringAfter("is MarkdownNode.Heading").substringBefore("is MarkdownNode.Paragraph")
        assertTrue(
            "Heading text must be colorScheme.primary (sizes/weights unchanged)",
            headingBlock.contains("color = MaterialTheme.colorScheme.primary")
        )
    }

    @Test
    fun inlineCode_hasNoChipBackground() {
        val result = MarkdownParser.buildInlineMarkdown("x=`c`.")
        org.junit.Assert.assertEquals("x=c.",result.text)
        val code = result.spanStyles.single { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace }
        org.junit.Assert.assertEquals(androidx.compose.ui.graphics.Color.Unspecified,code.item.background)
    }

    @Test
    fun inlineCode_fontColorIsAccentTertiaryText() {
        val color = androidx.compose.ui.graphics.Color(0xFFEFB8C8)
        val result = MarkdownParser.buildInlineMarkdown("`c`",codeFontColor = color)
        org.junit.Assert.assertEquals(color,result.spanStyles.single().item.color)
        assertTrue(renderer().contains("val codeFontColor = MaterialTheme.colorScheme.tertiary"))
        assertTrue(!renderer().contains("colorScheme.tertiaryContainer"))
    }

    @Test
    fun quoteBar_alphaIsOne() {
        val renderer = renderer()
        val quoteBlock = renderer.substringAfter("is MarkdownNode.Blockquote").substringBefore("is MarkdownNode.UnorderedListItem")
        assertTrue(
            "Quote bar must paint primary at alpha 1.0",
            quoteBlock.contains("primary.copy(alpha = 1.0f)")
        )
    }

    // --- upgrade 3: text size & spacing ---------------------------------------

    @Test
    fun assistantParagraphs_useBodyLarge() {
        val renderer = renderer()
        val paragraphBlock = renderer.substringAfter("is MarkdownNode.Paragraph").substringBefore("is MarkdownNode.CodeBlock")
        assertTrue(
            "Renderer paragraph branch must use bodyLarge",
            paragraphBlock.contains("typography.bodyLarge")
        )
        val bubble = bubble()
        assertTrue(
            "Assistant bubble body must use bodyLarge (renderer is the markdown path; the bubble's plain-text fallback row is the bodyLarge consumer here)",
            bubble.contains("bodyLarge")
        )
    }

    @Test
    fun headings_getTwelveDpTopPadding_firstNodeExempt() {
        val renderer = renderer()
        val headingBlock = renderer.substringAfter("is MarkdownNode.Heading").substringBefore("is MarkdownNode.Paragraph")
        assertTrue(
            "Heading must carry 12.dp top padding",
            headingBlock.contains("padding(top = 12.dp)")
        )
        assertTrue(
            "The very first node must not get the heading top padding (index 0 exemption)",
            renderer.contains("if (index != 0)")
        )
    }

    @Test
    fun listItems_getFourDpVerticalPadding() {
        val renderer = renderer()
        for (marker in listOf("is MarkdownNode.UnorderedListItem", "is MarkdownNode.OrderedListItem")) {
            val block = renderer.substringAfter(marker)
                .substringBefore("is MarkdownNode.TaskListItem").let {
                    if (marker == "is MarkdownNode.OrderedListItem") it.substringBefore("is MarkdownNode.TaskListItem")
                    else it.substringBefore("is MarkdownNode.OrderedListItem")
                }
            assertTrue(
                "$marker must carry 4.dp vertical padding",
                block.contains("padding(vertical = 4.dp)")
            )
        }
    }
}