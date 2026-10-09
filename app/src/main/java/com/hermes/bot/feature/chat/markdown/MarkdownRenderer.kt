package com.hermes.bot.feature.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.bot.design.HermesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext


@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null,
    sealed: Boolean = true,
    messageKey: Any = Unit
) {
    val linkColor = HermesTheme.extendedColors.primaryText
    val codeFontColor = MaterialTheme.colorScheme.tertiary
    val latestCallback by rememberUpdatedState(onUrlClick)
    val click: (String) -> Unit = remember { { latestCallback?.invoke(it) } }
    val linksEnabled = onUrlClick != null
    var prepared by remember(messageKey, linkColor, codeFontColor, linksEnabled) {
        mutableStateOf<PreparedMarkdown?>(null)
    }
    val coordinator = remember(messageKey, linkColor, codeFontColor, linksEnabled) {
        MarkdownPreparationCoordinator<PreparedMarkdown>(prepare = { source, terminal, previous ->
            try {
                prepareMarkdown(source, terminal, linkColor, codeFontColor,
                    if (linksEnabled) click else null, previous)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val bounded = MarkdownBounds.normalizeSurrogates(
                    MarkdownBounds.truncateToUtf8Bytes(source, MarkdownParser.MAX_INPUT_BYTES))
                PreparedMarkdown(source, listOf(PreparedMarkdownNode(
                    MarkdownNode.Paragraph(bounded), AnnotatedString(bounded))))
            }
        })
    }
    SideEffect { coordinator.submit(content, sealed) }
    LaunchedEffect(coordinator) { coordinator.run { prepared = it } }
    val current = prepared
    // Retain a same-message parsed prefix while its next streaming revision is prepared.
    // A replacement/history revision never displays an unrelated previous result.
    if (current != null && (current.source == content || content.startsWith(current.source))) {
        MarkdownBlocks(current.nodes,sealed,modifier)
    } else {
        Text("Formatting message…",modifier = modifier,style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MarkdownBlocks(nodes: List<PreparedMarkdownNode>, sealed: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier,verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEachIndexed { index, prepared ->
            when (val node = prepared.node) {
                is MarkdownNode.Heading -> {
                    val style = when (node.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        4 -> MaterialTheme.typography.bodyLarge
                        5 -> MaterialTheme.typography.bodyMedium
                        else -> MaterialTheme.typography.labelLarge
                    }
                    Text(prepared.text ?: AnnotatedString(node.text),style = style,fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = (if (index != 0) Modifier.padding(top = 12.dp) else Modifier).semantics { heading() })
                }
                is MarkdownNode.Paragraph -> Text(prepared.text ?: AnnotatedString(node.text),
                    style = MaterialTheme.typography.bodyLarge,color = MaterialTheme.colorScheme.onSurface)
                is MarkdownNode.CodeBlock -> CodeBlock(node.code,node.language,sealed,
                    modifier = Modifier.padding(vertical = 4.dp),notice = node.notice)
                is MarkdownNode.Blockquote -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 1.0f)))
                    MarkdownBlocks(prepared.children,sealed,Modifier.weight(1f).padding(start = 12.dp))
                }
                is MarkdownNode.ListGroup -> Column(Modifier.fillMaxWidth().semantics {
                    collectionInfo = CollectionInfo(rowCount = prepared.children.size,columnCount = 1)
                }) {
                    prepared.children.forEachIndexed { row, child ->
                        Box(Modifier.semantics { collectionItemInfo = CollectionItemInfo(rowIndex = row,rowSpan = 1,columnIndex = 0,columnSpan = 1) }) {
                            MarkdownBlocks(listOf(child),sealed)
                        }
                    }
                }
                is MarkdownNode.UnorderedListItem -> MarkdownListItem("•",prepared,sealed,Modifier.padding(vertical = 4.dp))
                is MarkdownNode.OrderedListItem -> MarkdownListItem("${node.index}.",prepared,sealed,Modifier.padding(vertical = 4.dp))
                is MarkdownNode.TaskListItem -> MarkdownListItem(if (node.checked) "☑" else "☐",prepared,sealed,
                    Modifier.padding(vertical = 4.dp).semantics { stateDescription = if (node.checked) "Completed task" else "Incomplete task" })
                is MarkdownNode.Table -> MarkdownTable(node,prepared.cells)
                is MarkdownNode.HorizontalRule -> HorizontalDivider(Modifier.padding(vertical = 8.dp),color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun MarkdownListItem(marker: String, item: PreparedMarkdownNode, sealed: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment = Alignment.Top) {
        Text(marker,style = MaterialTheme.typography.bodyLarge,fontWeight = FontWeight.Bold,color = HermesTheme.extendedColors.primaryText,
            modifier = Modifier.widthIn(min = 24.dp))
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!item.text.isNullOrEmpty()) Text(item.text!!,style = MaterialTheme.typography.bodyLarge,color = MaterialTheme.colorScheme.onSurface)
            if (item.children.isNotEmpty()) MarkdownBlocks(item.children,sealed)
        }
    }
}

/** Shared, bounded column widths keep headers, empty cells and all data rows aligned. */
internal fun tableColumnWidths(table: MarkdownNode.Table): List<Int> = table.headers.indices.map { column ->
    val length = (listOf(table.headers)+table.rows).maxOf { row ->
        MarkdownBounds.codePointCount(row.getOrElse(column) { "" })
    }
    (length * 8 + 24).coerceIn(112,280)
}

@Composable
private fun MarkdownTable(table: MarkdownNode.Table, cells: List<List<AnnotatedString>>) {
    val widths = remember(table) { tableColumnWidths(table) }
    Surface(Modifier.fillMaxWidth(),shape = MaterialTheme.shapes.small,color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp).semantics {
            contentDescription = "Table. Scroll horizontally for more columns."
            collectionInfo = CollectionInfo(rowCount = cells.size,columnCount = table.headers.size)
        }) {
            cells.forEachIndexed { rowIndex, row ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    table.headers.indices.forEach { column ->
                        val align = when (table.alignments.getOrNull(column)) {
                            TableAlignment.CENTER -> TextAlign.Center
                            TableAlignment.RIGHT -> TextAlign.Right
                            else -> TextAlign.Left
                        }
                        Text(row.getOrElse(column) { AnnotatedString("") },
                            modifier = Modifier.width(widths[column].dp).padding(horizontal = 8.dp).semantics {
                                collectionItemInfo = CollectionItemInfo(rowIndex,rowSpan = 1,columnIndex = column,columnSpan = 1)
                                if (rowIndex == 0) heading()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (rowIndex == 0) FontWeight.Bold else null,
                            textAlign = align,color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (rowIndex == 0) HorizontalDivider(Modifier.width(widths.sum().dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

internal data class PreparedMarkdownNode(
    val node: MarkdownNode, val text: AnnotatedString? = null,
    val children: List<PreparedMarkdownNode> = emptyList(),
    val cells: List<List<AnnotatedString>> = emptyList()
)
internal data class PreparedMarkdown(val source: String, val nodes: List<PreparedMarkdownNode>)

/** Grammar and annotation work runs together off the UI thread, never per Text composable. */
internal suspend fun prepareMarkdown(
    content: String, sealed: Boolean, linkColor: Color, codeFontColor: Color,
    onUrlClick: ((String) -> Unit)?, previous: PreparedMarkdown? = null
): PreparedMarkdown = withContext(Dispatchers.Default) {
    suspend fun prepare(nodes: List<MarkdownNode>, old: List<PreparedMarkdownNode>): List<PreparedMarkdownNode> = nodes.mapIndexed { index, n ->
        coroutineContext.ensureActive()
        val cached = old.getOrNull(index)
        if (cached?.node == n) return@mapIndexed cached
        fun annotate(text: String, inline: List<MarkdownInline>) =
            if (inline.isEmpty()) MarkdownParser.buildInlineMarkdown(text, linkColor, codeFontColor, onUrlClick)
            else MarkdownParser.annotate(inline, linkColor, codeFontColor, onUrlClick)
        when (n) {
            is MarkdownNode.Heading -> PreparedMarkdownNode(n, annotate(n.text,n.inline))
            is MarkdownNode.Paragraph -> PreparedMarkdownNode(n, annotate(n.text,n.inline))
            is MarkdownNode.Blockquote -> PreparedMarkdownNode(n, children = prepare(n.children, cached?.children.orEmpty()))
            is MarkdownNode.ListGroup -> PreparedMarkdownNode(n, children = prepare(n.items, cached?.children.orEmpty()))
            is MarkdownNode.UnorderedListItem -> PreparedMarkdownNode(n, annotate(n.text,n.inline), prepare(n.children, cached?.children.orEmpty()))
            is MarkdownNode.OrderedListItem -> PreparedMarkdownNode(n, annotate(n.text,n.inline), prepare(n.children, cached?.children.orEmpty()))
            is MarkdownNode.TaskListItem -> PreparedMarkdownNode(n, annotate(n.text,n.inline), prepare(n.children, cached?.children.orEmpty()))
            is MarkdownNode.Table -> PreparedMarkdownNode(n, cells = (listOf(n.headers)+n.rows).mapIndexed { row, cells ->
                cells.mapIndexed { column, cell -> annotate(cell,n.inlineRows.getOrNull(row)?.getOrNull(column) ?: emptyList()) }
            })
            else -> PreparedMarkdownNode(n)
        }
    }
    com.hermes.bot.feature.chat.ChatRenderProbe.beforePreparation?.invoke()
    val started = System.nanoTime()
    var completed = false
    try {
        val parsed = MarkdownParser.parse(content, sealed)
        val nodes = prepare(parsed, previous?.nodes.orEmpty())
        coroutineContext.ensureActive()
        completed = true
        PreparedMarkdown(content,nodes)
    } finally {
        com.hermes.bot.feature.chat.ChatRenderProbe.preparation?.invoke(System.nanoTime() - started, completed)
    }
}
