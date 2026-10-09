package com.hermes.bot.feature.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDocumentPolicyTest {
    @Test
    fun spansReconstructFrontmatterUnicodeCrLfAndTailExactly() {
        val source = "---\r\nname: demo\r\n---\r\n# Heading\r\n\r\n**bold** and `code` 😀\r\nfinal-no-newline"
        val document = SkillDocumentPolicy.parse(source)

        assertEquals(source, document.reconstruct())
        assertEquals(0, document.spans.first().start)
        assertEquals(source.length, document.spans.last().endExclusive)
        assertTrue(document.blocks.any { it.kind == SkillDocumentBlockKind.FRONTMATTER })
        assertTrue(document.blocks.any { it.kind == SkillDocumentBlockKind.HEADING })
        assertTrue(document.blocks.any { it.kind == SkillDocumentBlockKind.PARAGRAPH })
    }

    @Test
    fun malformedAndOverBudgetFormattingFallsBackWithoutDroppingText() {
        val longLine = "x".repeat(SkillDocumentPolicy.MAX_LINE_SCALARS + 1)
        val unclosedFence = "```\n" + "code\n".repeat(SkillDocumentPolicy.MAX_FENCE_LINES + 1)
        val source = "$longLine\n$unclosedFence\n<literal>{{shell}} [image](https://invalid.example)"
        val document = SkillDocumentPolicy.parse(source)

        assertEquals(source, document.reconstruct())
        assertTrue(document.blocks.any { it.kind == SkillDocumentBlockKind.RAW })
        assertTrue(document.chunks.sumOf { chunk -> chunk.blocks.sumOf { it.source.length } } == source.length)
    }

    @Test
    fun ordinaryTablesListsQuotesRulesAndFencesReceiveSeparateKinds() {
        val source = "- one\n> quote\n---\n| a | b |\n| --- | --- |\n| 1 | 2 |\n```sh\necho safe\n```"
        val kinds = SkillDocumentPolicy.parse(source).blocks.map { it.kind }

        assertTrue(SkillDocumentBlockKind.LIST in kinds)
        assertTrue(SkillDocumentBlockKind.QUOTE in kinds)
        assertTrue(SkillDocumentBlockKind.RULE in kinds)
        assertTrue(SkillDocumentBlockKind.TABLE in kinds)
        assertTrue(SkillDocumentBlockKind.CODE in kinds)
    }

    @Test
    fun ordinaryPresentationRemovesDelimitersAndKeepsReadableBlocksAndInlineStyles() {
        val source = "# Heading **bold** *italic* `code`\r\n" +
            "- first **bold**\r\n" +
            "2. second *italic*\r\n" +
            "> quoted\r\n" +
            "| Name | Value |\r\n| --- | --- |\r\n| one | two |\r\n" +
            "```sh\r\nprintf 'safe'\r\n```"
        val document = SkillDocumentPolicy.parse(source)
        val heading = document.blocks.first { it.kind == SkillDocumentBlockKind.HEADING }
        val list = document.blocks.first { it.kind == SkillDocumentBlockKind.LIST }
        val quote = document.blocks.first { it.kind == SkillDocumentBlockKind.QUOTE }
        val table = document.blocks.first { it.kind == SkillDocumentBlockKind.TABLE }
        val code = document.blocks.first { it.kind == SkillDocumentBlockKind.CODE }

        val headingPresentation = SkillDocumentPresentation.heading(heading.source)
        assertEquals("Heading bold italic code", headingPresentation.text)
        assertEquals(
            listOf(SkillInlineStyle.BOLD, SkillInlineStyle.ITALIC, SkillInlineStyle.CODE),
            headingPresentation.spans.map { it.style }
        )
        assertFalse(headingPresentation.text.contains("#"))
        assertFalse(headingPresentation.text.contains("**"))
        assertFalse(headingPresentation.text.contains("`"))

        val listPresentation = SkillDocumentPresentation.list(list.source)
        assertEquals(listOf("•", "2."), listPresentation.map { it.marker })
        assertEquals(listOf("first bold", "second italic"), listPresentation.map { it.content.text })
        assertEquals(SkillInlineStyle.BOLD, listPresentation.first().content.spans.single().style)
        assertEquals(SkillInlineStyle.ITALIC, listPresentation[1].content.spans.single().style)

        assertEquals("quoted\r\n", SkillDocumentPresentation.quote(quote.source).text)
        val tablePresentation = SkillDocumentPresentation.table(table.source)
        assertEquals(listOf("Name", "Value"), tablePresentation.header.map { it.text })
        assertEquals(listOf(listOf("one", "two")), tablePresentation.rows.map { row -> row.map { it.text } })
        assertEquals("printf 'safe'\r\n", SkillDocumentPresentation.fencedCode(code.source))
    }

    @Test
    fun normalBlockSemanticsAndLongPreviewPreserveEveryUtf16CodeUnit() {
        val structured = "---\r\ntitle: inert\r\n---\r\n" +
            "# Heading *italic* **bold**\r\n" +
            "\r\n- first item\r\n- second item\r\n" +
            "> quoted text\r\n" +
            "---\r\n" +
            "| left | right |\r\n| --- | --- |\r\n| one | two |\r\n" +
            "```sh\r\nprintf '😀'\r\n```\r\n" +
            "full-tail-without-newline"
        val longSource = structured + "\r\n" +
            ("ordinary scalar 😀\r\n").repeat(SkillDocumentPolicy.MAX_CHUNK_UTF16 / 8 + 32) +
            "tail-after-preview-budget"
        val document = SkillDocumentPolicy.parse(longSource)
        val kinds = document.blocks.map { it.kind }

        assertEquals(longSource, document.reconstruct())
        assertTrue(SkillDocumentBlockKind.FRONTMATTER in kinds)
        assertTrue(SkillDocumentBlockKind.HEADING in kinds)
        assertTrue(SkillDocumentBlockKind.LIST in kinds)
        assertTrue(SkillDocumentBlockKind.QUOTE in kinds)
        assertTrue(SkillDocumentBlockKind.RULE in kinds)
        assertTrue(SkillDocumentBlockKind.TABLE in kinds)
        assertTrue(SkillDocumentBlockKind.CODE in kinds)
        assertTrue(document.chunks.size > 1)
        assertEquals(longSource.length, document.chunks.sumOf { chunk -> chunk.blocks.sumOf { it.source.length } })

        document.spans.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endExclusive, right.start)
        }
        document.spans.forEach { span ->
            if (span.endExclusive < longSource.length) {
                assertTrue(!longSource[span.endExclusive - 1].isHighSurrogate())
                assertTrue(longSource[span.endExclusive - 1] != '\r')
            }
        }
    }

    @Test
    fun chunkedCodePresentationPreservesCompleteBodyBoundaryAndTail() {
        val body = buildString {
            repeat(1_200) { index ->
                append("line-${index.toString().padStart(4, '0')} payload-abcdefghijklmnopqrstuv\n")
            }
        }
        val source = "```text\n$body```\n"
        val document = SkillDocumentPolicy.parse(source)
        val chunkBlocks = document.chunks.flatMap { it.blocks }

        assertTrue(document.chunks.size > 1)
        val displayed = buildString {
            chunkBlocks.forEach { block ->
                append(
                    when (block.kind) {
                        SkillDocumentBlockKind.CODE -> SkillDocumentPresentation.fencedCode(block.source)
                        SkillDocumentBlockKind.RAW -> SkillDocumentPresentation.literal(block.source).text
                        else -> error("Unexpected block kind in code fixture: ${block.kind}")
                    }
                )
            }
        }
        val boundaryLine = "line-0799 payload-abcdefghijklmnopqrstuv\n"
        val tailLine = "line-1199 payload-abcdefghijklmnopqrstuv\n"

        assertTrue(body.contains(boundaryLine))
        assertTrue(body.contains(tailLine))
        assertTrue(displayed.contains(boundaryLine))
        assertTrue(displayed.contains(tailLine))
        if (displayed == source) {
            assertEquals(source, displayed)
        } else {
            assertEquals(body, displayed)
        }
    }

    @Test
    fun chunkedTablePresentationPreservesEveryRowAndCell() {
        val expectedRows = buildList {
            add(listOf("header", "value"))
            repeat(1_200) { index ->
                add(
                    listOf(
                        "row-${index.toString().padStart(4, '0')}",
                        "abcdefghijklmnopqrstuvwxyz"
                    )
                )
            }
        }
        val source = buildString {
            append("| header | value |\n")
            append("| --- | --- |\n")
            repeat(1_200) { index ->
                append("| row-${index.toString().padStart(4, '0')} | abcdefghijklmnopqrstuvwxyz |\n")
            }
        }
        val document = SkillDocumentPolicy.parse(source)
        val chunkBlocks = document.chunks.flatMap { it.blocks }
        val displayedRows = mutableListOf<List<String>>()
        val literalText = buildString {
            chunkBlocks.forEach { block ->
                when (block.kind) {
                    SkillDocumentBlockKind.TABLE -> {
                        val table = SkillDocumentPresentation.table(block.source)
                        displayedRows += table.header.map { it.text }
                        displayedRows += table.rows.map { row -> row.map { it.text } }
                    }
                    SkillDocumentBlockKind.RAW -> append(SkillDocumentPresentation.literal(block.source).text)
                    else -> error("Unexpected block kind in table fixture: ${block.kind}")
                }
            }
        }
        val boundaryRow = listOf("row-0780", "abcdefghijklmnopqrstuvwxyz")
        val tailRow = listOf("row-1199", "abcdefghijklmnopqrstuvwxyz")

        assertTrue(document.chunks.size > 1)
        assertTrue(expectedRows.contains(boundaryRow))
        assertTrue(expectedRows.contains(tailRow))
        if (literalText == source) {
            assertEquals(source, literalText)
            assertTrue(literalText.contains("| row-0780 | abcdefghijklmnopqrstuvwxyz |\n"))
            assertTrue(literalText.contains("| row-1199 | abcdefghijklmnopqrstuvwxyz |\n"))
        } else {
            assertEquals(expectedRows, displayedRows)
            assertTrue(displayedRows.contains(boundaryRow))
            assertTrue(displayedRows.contains(tailRow))
        }
    }
}
