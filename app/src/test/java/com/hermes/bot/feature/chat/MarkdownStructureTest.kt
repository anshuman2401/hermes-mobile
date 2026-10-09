package com.hermes.bot.feature.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.hermes.bot.feature.chat.markdown.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarkdownStructureTest {
    @Test fun fallbackDisclosureNamesItsActualLineBudgetAndStaysOutOfCopy() {
        val raw = "| A |\n|---|\n" + "| x |\n".repeat(1500)
        val node = MarkdownParser.parse(raw).single() as MarkdownNode.CodeBlock
        assertTrue(node.notice!!.contains(MarkdownParser.FALLBACK_BUDGET_LABEL))
        assertFalse(node.code.contains(MarkdownParser.FALLBACK_BUDGET_LABEL))
        assertEquals(1000, node.code.lines().size)
    }

    @Test fun orderedTasksDoNotResetFollowingNumbers() {
        val list = MarkdownParser.parse("5. [x] done\n6. next").single() as MarkdownNode.ListGroup
        assertTrue(list.items[0] is MarkdownNode.TaskListItem)
        assertEquals(6, (list.items[1] as MarkdownNode.OrderedListItem).index)
    }

    @Test fun nestedListsAndContinuationBlocksRetainTheirParent() {
        val group=MarkdownParser.parse("3. parent\n   - child\n     - grandchild\n\n   continuation").single() as MarkdownNode.ListGroup
        val parent=group.items.single() as MarkdownNode.OrderedListItem
        assertEquals(3,parent.index)
        assertEquals("parent",parent.text)
        val child=(parent.children.first() as MarkdownNode.ListGroup).items.single() as MarkdownNode.UnorderedListItem
        assertEquals("child",child.text)
        assertEquals("grandchild",((child.children.single() as MarkdownNode.ListGroup).items.single() as MarkdownNode.UnorderedListItem).text)
        assertEquals("continuation",(parent.children.last() as MarkdownNode.Paragraph).text)
    }
    @Test fun quotesPreserveNestedBlocksRatherThanFlatteningMarkers() {
        val q=MarkdownParser.parse("> # Title\n>\n> - item\n>\n> ```\n> x\n> ```").single() as MarkdownNode.Blockquote
        assertTrue(q.children[0] is MarkdownNode.Heading)
        assertTrue(q.children[1] is MarkdownNode.ListGroup)
        assertEquals("x\n",(q.children[2] as MarkdownNode.CodeBlock).code)
    }
    @Test fun allHeadingLevelsAndSetextHeadingsAreSupported() {
        val nodes=MarkdownParser.parse((1..6).joinToString("\n") { "#".repeat(it)+" h$it" })
        assertEquals((1..6).toList(),nodes.map { (it as MarkdownNode.Heading).level })
        assertEquals(2,(MarkdownParser.parse("Title\n---").single() as MarkdownNode.Heading).level)
    }
    @Test fun tablesHaveAlignmentAndIdenticalColumnWidthsAcrossRows() {
        val t=MarkdownParser.parse("A | B | C\n:---|:---:|---:\nshort||longer text\nx|y|z").single() as MarkdownNode.Table
        assertEquals(listOf(TableAlignment.LEFT,TableAlignment.CENTER,TableAlignment.RIGHT),t.alignments)
        assertEquals(listOf("short","","longer text"),t.rows.first())
        val widths=tableColumnWidths(t)
        assertEquals(3,widths.size)
        assertTrue(widths.all { it in 112..280 })
    }
    @Test fun malformedOrExcessCellsUseFaithfulFallbackWithSeparateNotice() {
        for(s in listOf("| A |\n||\n| x |","| A |\n|---|\n| x | y |")) {
            val n=MarkdownParser.parse(s).single() as MarkdownNode.CodeBlock
            assertEquals(s,n.code)
            assertEquals(MarkdownParser.TABLE_BUDGET_LABEL,n.notice)
            assertEquals(s,TranscriptCopyPolicy.codeCopyRequestIfSealed(true,n.code)!!.text)
        }
    }
    @Test fun streamingFenceHasNoFalseErrorAndSameSealedCopyScope() {
        val partial="```python\nx"
        val stream=MarkdownParser.parse(partial,sealed=false).single() as MarkdownNode.CodeBlock
        val complete=MarkdownParser.parse(partial,sealed=true).single() as MarkdownNode.CodeBlock
        assertNull(stream.notice)
        assertEquals(MarkdownParser.MALFORMED_FENCE_LABEL,complete.notice)
        assertEquals(stream.code,complete.code)
        assertNull(TranscriptCopyPolicy.codeCopyRequestIfSealed(false,stream.code))
        assertEquals("x\n",TranscriptCopyPolicy.codeCopyRequestIfSealed(true,complete.code)!!.text)
    }
    @Test fun longCodeLineNoticeIsVisibleAndNeverCopied() {
        val n=MarkdownParser.parse("```\n"+"x".repeat(8193)+"\n```").single() as MarkdownNode.CodeBlock
        assertEquals(MarkdownParser.LINE_SCALARS_LABEL,n.notice)
        assertEquals("x".repeat(8192)+"\n",TranscriptCopyPolicy.codeCopyRequestIfSealed(true,n.code)!!.text)
    }
    @Test fun referenceLinksAreResolvedBeforePreparingNativeSpans() = runBlocking {
        val source="[**docs**][ref]\n\n[ref]: https://example.com/a(b)"
        val document=prepareMarkdown(source,true,Color.Blue,Color.Red,{})
        assertEquals(source,document.source)
        val text=document.nodes.single().text!!
        assertEquals("docs",text.text)
        assertEquals("https://example.com/a(b)",(text.getLinkAnnotations(0,text.length).single().item as LinkAnnotation.Clickable).tag)
    }
    @Test fun taskRowsAreReadOnlyNestedDataWithFormatting() {
        val list=MarkdownParser.parse("- [x] **done**\n  - [ ] next").single() as MarkdownNode.ListGroup
        val first=list.items.single() as MarkdownNode.TaskListItem
        assertTrue(first.checked); assertTrue(first.inline.single().bold)
        val second=(first.children.single() as MarkdownNode.ListGroup).items.single() as MarkdownNode.TaskListItem
        assertFalse(second.checked); assertEquals("next",second.text)
    }
    @Test fun inputByteLimitNormalizesAndKeepsWholeUnicodeScalars() {
        val nodes=MarkdownParser.parse("😀".repeat(300000))
        assertTrue(nodes.filterIsInstance<MarkdownNode.Paragraph>().any { it.text == MarkdownParser.INPUT_BUDGET_LABEL })
        val text=(nodes.first() as MarkdownNode.Paragraph).text.removeSuffix(" "+MarkdownParser.LINE_SCALARS_LABEL)
        assertEquals("😀".repeat(8192),text)
    }
    @Test fun structuralDepthFallsBackWithoutDiscardingContent() {
        val raw="> ".repeat(1000)+"sentinel"
        fun flatten(nodes: List<MarkdownNode>): List<MarkdownNode> = nodes.flatMap {
            listOf(it)+ if(it is MarkdownNode.Blockquote) flatten(it.children) else emptyList()
        }
        val nodes=flatten(MarkdownParser.parse(raw))
        val fallback=nodes.filterIsInstance<MarkdownNode.CodeBlock>().single()
        assertEquals(MarkdownParser.NESTING_LABEL,fallback.notice)
        assertTrue(fallback.code.contains("sentinel"))
        assertTrue(nodes.size <= MarkdownParser.MAX_NESTING+1)
    }
    @Test fun terminalNewlineWithinCodeBudgetIsPreserved() {
        val text=("x\n").repeat(CodeBlockBudget.MAX_LINES)
        assertEquals(text,TranscriptCopyPolicy.codeCopyRequestIfSealed(true,text)!!.text)
        assertFalse(BoundedCode.boundedCodeScope(text).truncated)
        val over=BoundedCode.boundedCodeScope(text+"hidden")
        assertTrue(over.truncated)
        assertFalse(over.text.contains("hidden"))
    }
}
