package io.github.anshuman2401.hermesmobile.feature.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.anshuman2401.hermesmobile.feature.chat.ExternalLinkPolicy
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType
import java.util.EnumSet

/** Immutable, inert inline runs. URLs are validated only when creating a clickable annotation. */
data class MarkdownInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null
)

enum class TableAlignment { LEFT, CENTER, RIGHT }

sealed interface MarkdownNode {
    data class Heading(val level: Int, val text: String, val inline: List<MarkdownInline> = emptyList()) : MarkdownNode
    data class Paragraph(val text: String, val inline: List<MarkdownInline> = emptyList()) : MarkdownNode
    data class CodeBlock(val language: String?, val code: String, val notice: String? = null) : MarkdownNode
    data class Blockquote(val text: String, val children: List<MarkdownNode> = emptyList()) : MarkdownNode
    data class ListGroup(val items: List<MarkdownNode>) : MarkdownNode
    data class UnorderedListItem(val text: String, val inline: List<MarkdownInline> = emptyList(), val children: List<MarkdownNode> = emptyList()) : MarkdownNode
    data class OrderedListItem(val index: Int, val text: String, val inline: List<MarkdownInline> = emptyList(), val children: List<MarkdownNode> = emptyList()) : MarkdownNode
    data class TaskListItem(val text: String, val checked: Boolean, val inline: List<MarkdownInline> = emptyList(), val children: List<MarkdownNode> = emptyList()) : MarkdownNode
    data class Table(
        val headers: List<String>, val rows: List<List<String>>,
        val alignments: List<TableAlignment> = List(headers.size) { TableAlignment.LEFT },
        val inlineRows: List<List<List<MarkdownInline>>> = emptyList()
    ) : MarkdownNode
    data object HorizontalRule : MarkdownNode
}

/** Bounded CommonMark grammar -> native presentation. Never renders HTML or fetches media. */
object MarkdownParser {
    const val MAX_LINES = 10_000
    const val MAX_LINE_SCALARS = 8_192
    const val MAX_AST_NODES = 2_000
    const val MAX_TABLE_ROWS = 50
    const val MAX_TABLE_COLUMNS = 12
    const val MAX_TABLE_CELL_SCALARS = 512
    const val MAX_NESTING = 24
    const val MAX_INPUT_BYTES = 1024 * 1024
    const val LINE_BUDGET_LABEL = "Content truncated at the 10,000-line display limit."
    const val LINE_SCALARS_LABEL = "Long line truncated."
    const val AST_BUDGET_LABEL = "Content truncated; the remainder is shown as plain text."
    const val TABLE_BUDGET_LABEL = "Table too large; showing a text preview."
    const val MALFORMED_FENCE_LABEL = "Malformed code fence; showing as plain text."
    const val INPUT_BUDGET_LABEL = "Content truncated at the 1 MiB display limit."
    const val FALLBACK_BUDGET_LABEL = "Text preview truncated at the 1,000-line fallback limit."
    const val NESTING_LABEL = "Formatting too deeply nested; showing as plain text."
    private const val PREFORMATTED_MAX_LINES = 1_000
    // Post-processors which recursively walk arbitrary trees are deliberately avoided.
    // Plain URL detection and read-only task markers are projected by this adapter.
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.builder().maxCells((MAX_TABLE_ROWS + 1) * MAX_TABLE_COLUMNS).build(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
    private val urls by lazy { LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build() }

    private val taskMarker by lazy { Regex("^\\[([ xX])]\\s+") }

    private data class Input(val text: String, val clippedLines: Set<Int>, val notices: List<String>)

    private fun bound(raw: String): Input {
        // Byte truncation already measures unpaired surrogates as their replacement bytes.
        // Normalize only the kept prefix; never scan/copy an unlimited discarded suffix.
        val prefix = MarkdownBounds.truncateToUtf8Bytes(raw, MAX_INPUT_BYTES)
        val bytesClipped = prefix.length < raw.length
        val limited = MarkdownBounds.normalizeSurrogates(prefix)
        val text = StringBuilder()
        val clipped = mutableSetOf<Int>()
        var index = 0
        var line = 0
        while (index < limited.length && line < MAX_LINES) {
            val start = index
            while (index < limited.length && limited[index] != '\n' && limited[index] != '\r') index++
            val value = limited.substring(start, index)
            val kept = MarkdownBounds.truncateToCodePoints(value, MAX_LINE_SCALARS)
            if (kept.length < value.length) clipped.add(line)
            text.append(kept)
            if (index < limited.length) {
                val cr = limited[index] == '\r'
                index++
                if (cr && index < limited.length && limited[index] == '\n') index++
                text.append('\n')
            }
            line++
        }
        val notices = buildList {
            if (index < limited.length && limited.substring(index).any { it != '\n' && it != '\r' }) add(LINE_BUDGET_LABEL)
            if (bytesClipped) add(INPUT_BUDGET_LABEL)
        }
        return Input(text.toString(), clipped.toSet(), notices)
    }

    fun parse(rawText: String, sealed: Boolean = true): List<MarkdownNode> {
        if (rawText.isBlank()) return emptyList()
        val input = bound(rawText)
        val projection = Projection(input, sealed)
        val document = try {
            parser.parse(input.text)
        } catch (_: IllegalArgumentException) {
            // The table grammar aborts at the cell cap before constructing an oversized tree.
            // Keep the bounded source as inert preformatted text rather than dropping the entry.
            return listOf(projection.fallback(input.text, TABLE_BUDGET_LABEL)) + input.notices.map { literal(it) }
        }
        return projection.blocks(document) + input.notices.map { literal(it) }
    }

    private fun literal(text: String) = MarkdownNode.Paragraph(text, listOf(MarkdownInline(text)))

    private class Projection(val input: Input, val sealed: Boolean) {
        var count = 0
        var exhausted = false

        fun source(node: Node): String = node.sourceSpans.joinToString("\n") {
            input.text.substring(it.inputIndex, it.inputIndex + it.length)
        }

        fun clipped(node: Node) = node.sourceSpans.any { it.lineIndex in input.clippedLines }

        fun fallback(body: String, notice: String): MarkdownNode.CodeBlock {
            val lines = body.lineSequence().take(PREFORMATTED_MAX_LINES + 1).toList()
            val extra = if (lines.size > PREFORMATTED_MAX_LINES) "\n$FALLBACK_BUDGET_LABEL" else ""
            return MarkdownNode.CodeBlock(null, lines.take(PREFORMATTED_MAX_LINES).joinToString("\n"), notice + extra)
        }

        fun blocks(parent: Node, depth: Int = 0): List<MarkdownNode> {
            val result = mutableListOf<MarkdownNode>()
            for (node in children(parent)) {
                if (exhausted) break
                if (count >= MAX_AST_NODES) {
                    exhausted = true
                    val start = node.sourceSpans.firstOrNull()?.inputIndex ?: input.text.length
                    result.add(fallback(input.text.substring(start), AST_BUDGET_LABEL))
                    break
                }
                if (depth >= MAX_NESTING) {
                    count++
                    result.add(fallback(source(node), NESTING_LABEL))
                    continue
                }
                when (node) {
                    is Heading -> {
                        count++
                        val suffix = if (clipped(node)) " $LINE_SCALARS_LABEL" else ""
                        val inline = inlineRuns(node)
                        result.add(MarkdownNode.Heading(node.level, inline.joinToString("") { it.text } + suffix,
                            inline + if (suffix.isEmpty()) emptyList() else listOf(MarkdownInline(suffix))))
                    }
                    is Paragraph -> {
                        count++
                        val raw = if (node.sourceSpans.size >= 2) source(node) else ""
                        if (raw.isNotEmpty() && raw.lineSequence().all { it.trim().startsWith("|") && it.trim().endsWith("|") }) {
                            result.add(fallback(raw, TABLE_BUDGET_LABEL))
                        } else {
                            val suffix = if (clipped(node)) " $LINE_SCALARS_LABEL" else ""
                            val inline = inlineRuns(node)
                            result.add(MarkdownNode.Paragraph(inline.joinToString("") { it.text } + suffix, inline + if (suffix.isEmpty()) emptyList() else listOf(MarkdownInline(suffix))))
                        }
                    }
                    is FencedCodeBlock -> {
                        count++
                        val notices = buildList {
                            if (clipped(node)) add(LINE_SCALARS_LABEL)
                            if (sealed && node.closingFenceLength == null) add(MALFORMED_FENCE_LABEL)
                        }
                        val language = node.info?.trim()?.takeWhile { !it.isWhitespace() }
                            ?.takeIf { it.isNotEmpty() && it.length <= 40 && it.all { c -> c.isLetterOrDigit() || c in "_+-#." } }
                        result.add(MarkdownNode.CodeBlock(language, node.literal, notices.takeIf { it.isNotEmpty() }?.joinToString("\n")))
                    }
                    is IndentedCodeBlock -> { count++; result.add(MarkdownNode.CodeBlock(null, node.literal, if (clipped(node)) LINE_SCALARS_LABEL else null)) }
                    is BlockQuote -> { count++; result.add(MarkdownNode.Blockquote(source(node), blocks(node, depth + 1))) }
                    is BulletList, is OrderedList -> {
                        val items = mutableListOf<MarkdownNode>()
                        var ordinal = (node as? OrderedList)?.markerStartNumber ?: 1
                        for (item in children(node)) {
                            if (exhausted) break
                            if (count >= MAX_AST_NODES) {
                                exhausted = true
                                val start = item.sourceSpans.firstOrNull()?.inputIndex ?: input.text.length
                                items.add(fallback(input.text.substring(start), AST_BUDGET_LABEL)); break
                            }
                            count++
                            val itemOrdinal = ordinal++
                            val paragraph = item.firstChild as? Paragraph
                            val firstText = paragraph?.firstChild as? Text
                            val mark = firstText?.literal?.takeIf { it.startsWith("[") }?.let { taskMarker.find(it) }
                            if (mark != null) firstText.literal = firstText.literal.substring(mark.value.length)
                            val runs = paragraph?.let { inlineRuns(it) } ?: emptyList()
                            val text = runs.joinToString("") { it.text }
                            val suffix = if (paragraph != null && clipped(paragraph)) " $LINE_SCALARS_LABEL" else ""
                            val inline = runs +
                                if (suffix.isEmpty()) emptyList() else listOf(MarkdownInline(suffix))
                            // The first paragraph is represented by the row; the remaining blocks preserve nesting and continuation paragraphs.
                            if (paragraph != null) paragraph.unlink()
                            val rest = blocks(item, depth + 1)
                            items.add(when {
                                mark != null -> MarkdownNode.TaskListItem(text + suffix, mark.groupValues[1].equals("x", true), inline, rest)
                                node is OrderedList -> MarkdownNode.OrderedListItem(itemOrdinal, text + suffix, inline, rest)
                                else -> MarkdownNode.UnorderedListItem(text + suffix, inline, rest)
                            })
                        }
                        result.add(MarkdownNode.ListGroup(items.toList()))
                    }
                    is TableBlock -> { count++; result.add(table(node)) }
                    is ThematicBreak -> { count++; result.add(MarkdownNode.HorizontalRule) }
                    is HtmlBlock -> { count++; result.add(literal(node.literal.trimEnd('\n'))) }
                    is LinkReferenceDefinition -> Unit
                    else -> result.addAll(blocks(node, depth + 1))
                }
            }
            return result.toList()
        }

        fun table(node: TableBlock): MarkdownNode {
            val raw = source(node)
            val rows = children(node).flatMap { children(it) }
            val cells = rows.map { children(it).filterIsInstance<TableCell>() }
            val columns = cells.firstOrNull()?.size ?: 0
            val inlineRows = cells.map { row -> row.map { inlineRuns(it) } }
            val textRows = inlineRows.map { row -> row.map { runs -> runs.joinToString("") { it.text } } }
            // Check source widths too: a grammar parser may discard excess row cells.
            val sourceWidths = raw.lineSequence().map { tableCellCount(it) }.toList()
            if (columns !in 1..MAX_TABLE_COLUMNS || cells.size - 1 > MAX_TABLE_ROWS ||
                sourceWidths.any { it > columns } ||
                textRows.flatten().any { MarkdownBounds.codePointCount(it) > MAX_TABLE_CELL_SCALARS } || clipped(node)) {
                return fallback(raw, TABLE_BUDGET_LABEL)
            }
            return MarkdownNode.Table(textRows.first(), textRows.drop(1), cells.first().map {
                when (it.alignment) { TableCell.Alignment.CENTER -> TableAlignment.CENTER; TableCell.Alignment.RIGHT -> TableAlignment.RIGHT; else -> TableAlignment.LEFT }
            }, inlineRows)
        }
    }

    private fun tableCellCount(line: String): Int {
        val s = line.trim().removePrefix("|").removeSuffix("|")
        var cells = 1
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && s[i + 1] == '|') i += 2
            else { if (s[i] == '|') cells++; i++ }
        }
        return cells
    }

    private fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }

    private data class Frame(val node: Node, val style: MarkdownInline, val depth: Int)

    /** Iterative inline walk: malformed/deep input never recurses on the Java stack. */
    private fun inlineRuns(parent: Node): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        val stack = ArrayDeque<Frame>()
        children(parent).asReversed().forEach { stack.addLast(Frame(it, MarkdownInline(""), 0)) }
        while (stack.isNotEmpty()) {
            val (node, inherited, depth) = stack.removeLast()
            val style = when (node) {
                is StrongEmphasis -> inherited.copy(bold = true)
                is Emphasis -> inherited.copy(italic = true)
                is Strikethrough -> inherited.copy(strike = true)
                is Link -> inherited.copy(url = node.destination)
                else -> inherited
            }
            when (node) {
                is Text -> {
                    if (node.literal.isEmpty()) continue
                    if (style.url != null || style.code || !node.literal.contains("http", ignoreCase = true)) result.add(style.copy(text = node.literal))
                    else {
                        var start = 0
                        for (link in urls.extractLinks(node.literal)) {
                            if (link.beginIndex > start) result.add(style.copy(text = node.literal.substring(start, link.beginIndex)))
                            val target = node.literal.substring(link.beginIndex, link.endIndex)
                            result.add(style.copy(text = target, url = target)); start = link.endIndex
                        }
                        if (start < node.literal.length) result.add(style.copy(text = node.literal.substring(start)))
                    }
                }
                is Code -> result.add(style.copy(text = node.literal, code = true))
                is SoftLineBreak -> result.add(style.copy(text = " "))
                is HardLineBreak -> result.add(style.copy(text = "\n"))
                is HtmlInline -> result.add(style.copy(text = node.literal, url = null))
                is HtmlBlock -> result.add(style.copy(text = node.literal, url = null))
                is Image -> {
                    val alt = plainText(node)
                    result.add(style.copy(text = "[image: $alt]" + if (node.destination.isNullOrBlank()) "" else " (${node.destination})", url = null))
                }
                else -> {
                    if (depth >= MAX_NESTING) {
                        result.add(style.copy(text = plainText(node), url = null))
                        result.add(MarkdownInline(" $NESTING_LABEL"))
                    } else children(node).asReversed().forEach { stack.addLast(Frame(it, style, depth + 1)) }
                }
            }
        }
        return result.toList()
    }

    private fun plainText(parent: Node): String = buildString {
        val stack = ArrayDeque<Node>(); children(parent).asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is Text -> append(n.literal)
                is Code -> append(n.literal)
                is SoftLineBreak, is HardLineBreak -> append(' ')
                is HtmlInline -> append(n.literal)
                else -> children(n).asReversed().forEach { stack.addLast(it) }
            }
        }
    }

    fun buildInlineMarkdown(
        text: String,
        linkColor: Color = Color(0xFF64B5F6),
        codeFontColor: Color = Color(0xFFEFB8C8),
        onUrlClick: ((String) -> Unit)? = null
    ): AnnotatedString = annotate(inlineRuns(parser.parse(bound(text).text)), linkColor, codeFontColor, onUrlClick)

    fun annotate(
        runs: List<MarkdownInline>, linkColor: Color, codeFontColor: Color,
        onUrlClick: ((String) -> Unit)?
    ): AnnotatedString = buildAnnotatedString {
        var activeUrl: String? = null
        for (run in runs) {
            val target = if (onUrlClick != null) ExternalLinkPolicy.validate(run.url)?.url else null
            if (target != activeUrl) {
                if (activeUrl != null) pop()
                activeUrl = target
                if (target != null) pushLink(LinkAnnotation.Clickable(
                    tag = target,
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onUrlClick?.invoke(target) }
                ))
            }
            pushStyle(SpanStyle(
                fontWeight = if (run.bold) FontWeight.Bold else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
                color = if (run.code) codeFontColor else Color.Unspecified
            ))
            append(run.text)
            pop()
        }
        if (activeUrl != null) pop()
    }
}
