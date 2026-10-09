package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.feature.chat.markdown.*
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test

class MarkdownRenderingRegressionTest {
    private fun inline(s: String) = MarkdownParser.buildInlineMarkdown(s, onUrlClick = {})
    private fun targets(s: String) = inline(s).let { a ->
        a.getLinkAnnotations(0,a.length).map { (it.item as LinkAnnotation.Clickable).tag }
    }
    @Test fun plainUrlsExcludePunctuationAndRetainPortsQueriesFragments() {
        assertEquals(listOf("https://example.com:8443/a?q=1#part", "http://example.org/a(b)"),
            targets("See https://example.com:8443/a?q=1#part, and (http://example.org/a(b))."))
    }
    @Test fun balancedExplicitDestinationsAndNestedLabels() {
        assertEquals(listOf("https://example.com/a(b)"), targets("[**x**](https://example.com/a(b))"))
        assertEquals("x", inline("[**x**](https://example.com/a(b))").text)
        assertTrue(inline("[**x**](https://example.com)").spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }
    @Test fun nestedEmphasisAndStrike() {
        val a=inline("**b *i*** ~~gone~~")
        assertEquals("b i gone",a.text)
        assertTrue(a.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(a.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }
    @Test fun literalEscapesAndIdentifiers() {
        assertEquals("*x* foo_bar_baz",inline("\\*x\\* foo_bar_baz").text)
    }
    @Test fun codeSpansPreserveLiteralContentWithoutAddedSpaces() {
        assertEquals("a`b",inline("``a`b``").text)
        assertEquals("x=c.",inline("x=`c`.").text)
        assertTrue(targets("`https://example.com`").isEmpty())
        assertEquals(listOf("https://example.com"), targets("[`label`](https://example.com)"))
    }
    @Test fun hashPrefixedTextNeverDisappears() {
        assertEquals("####### x",(MarkdownParser.parse("####### x").single() as MarkdownNode.Paragraph).text)
        assertEquals("#tag",(MarkdownParser.parse("#tag").single() as MarkdownNode.Paragraph).text)
    }
    @Test fun hardLineBreaksSurviveBlockAndInlineParsing() {
        val p=MarkdownParser.parse("a  \nb").single() as MarkdownNode.Paragraph
        assertEquals("a\nb",p.text)
        assertEquals("a\nb",p.inline.joinToString("") { it.text })
    }
    @Test fun fourBacktickAndTildeFences() {
        val code=MarkdownParser.parse("````\n```\nx\n````").single() as MarkdownNode.CodeBlock
        assertEquals("```\nx\n",code.code)
        assertEquals("x\n",(MarkdownParser.parse("~~~kotlin\nx\n~~~").single() as MarkdownNode.CodeBlock).code)
    }
    @Test fun invalidCloserRemainsCode() {
        assertEquals("a\n```x\nb\n",(MarkdownParser.parse("```\na\n```x\nb\n```").single() as MarkdownNode.CodeBlock).code)
    }
    @Test fun generatedNoticesNeverEnterCodeCopy() {
        val c=MarkdownParser.parse("```\nx").single() as MarkdownNode.CodeBlock
        assertEquals("x\n",TranscriptCopyPolicy.codeCopyRequestIfSealed(true,c.code)!!.text)
    }
    @Test fun trailingWhitespaceAndBlankLineRemainInCopy() {
        val c=MarkdownParser.parse("```\n  a\t\n\n```").single() as MarkdownNode.CodeBlock
        assertEquals("  a\t\n\n",TranscriptCopyPolicy.codeCopyRequestIfSealed(true,c.code)!!.text)
    }
    @Test fun escapedPipesRemainOneCellAndEmptyCellsSurvive() {
        val t=MarkdownParser.parse("| A | B |\n|---|---|\n| x\\|y | z |\n| | x |").single() as MarkdownNode.Table
        assertEquals(listOf("x|y","z"),t.rows[0].map { inline(it).text })
        assertEquals(listOf("","x"),t.rows[1])
    }
    @Test fun idnPortsKeepTheSameValidationBoundary() {
        val url="https://bücher.example:8443/a"
        val c=ExternalLinkPolicy.validate(url)!!
        assertEquals("xn--bcher-kva.example:8443",ExternalLinkPolicy.canonicalDisclosure(c))
        assertEquals(listOf(url),targets("[x]($url)"))
        for(s in listOf("https://bücher.example:0/a","https://bücher.example:65536/a","https://bücher.example:no/a","https://bücher.example:+443/a","https://bücher.example:4294967295/a","https://bücher.example:/a","https://user@bücher.example:8443/a")) assertNull(ExternalLinkPolicy.validate(s))
    }
    @Test fun imagesHtmlAndUnsafeLinksStayInert() {
        val a=inline("![alt](https://example.com/a.png) <script>x</script> [bad](javascript:alert(1))")
        assertTrue(a.text.contains("[image: alt]")); assertTrue(a.text.contains("<script>x</script>"))
        assertTrue(a.getLinkAnnotations(0,a.length).isEmpty())
    }
    @Test(timeout=10000) fun longMalformedParagraphHasBoundedWork() {
        val s=("[".repeat(4096)+"\n").repeat(128)
        val p=MarkdownParser.parse(s).single() as MarkdownNode.Paragraph
        assertEquals(s.replace("\n"," ").trimEnd(),p.text)
        assertEquals(p.text,p.inline.joinToString("") { it.text })
    }
}
