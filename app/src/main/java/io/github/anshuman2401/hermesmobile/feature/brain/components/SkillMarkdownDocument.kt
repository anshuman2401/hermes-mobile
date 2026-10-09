package io.github.anshuman2401.hermesmobile.feature.brain.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import io.github.anshuman2401.hermesmobile.feature.brain.SkillDocument
import io.github.anshuman2401.hermesmobile.feature.brain.SkillDocumentBlock
import io.github.anshuman2401.hermesmobile.feature.brain.SkillDocumentBlockKind
import io.github.anshuman2401.hermesmobile.feature.brain.SkillDocumentPresentation
import io.github.anshuman2401.hermesmobile.feature.brain.SkillInlinePresentation

/** Native, inert rendering of the full policy output. No URL annotations or loaders are added. */
@Composable
fun SkillMarkdownDocument(
    document: SkillDocument,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(document.chunks) { _, chunk ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.blocks.forEach { block -> SkillMarkdownBlock(block) }
            }
        }
    }
}

@Composable
private fun SkillMarkdownBlock(block: SkillDocumentBlock) {
    when (block.kind) {
        SkillDocumentBlockKind.HEADING -> Text(
            text = SkillDocumentPresentation.heading(block.source).asAnnotatedString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        SkillDocumentBlockKind.CODE -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(
                text = SkillDocumentPresentation.fencedCode(block.source),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false
            )
        }
        SkillDocumentBlockKind.QUOTE -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 2.dp, vertical = 10.dp)
            )
            Text(
                SkillDocumentPresentation.quote(block.source).asAnnotatedString(),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SkillDocumentBlockKind.LIST -> Column(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SkillDocumentPresentation.list(block.source).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(item.marker, fontWeight = FontWeight.Bold)
                    Text(
                        item.content.asAnnotatedString(),
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        SkillDocumentBlockKind.RULE -> HorizontalDivider(modifier = Modifier.fillMaxWidth())
        SkillDocumentBlockKind.TABLE -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            val table = SkillDocumentPresentation.table(block.source)
            Column(modifier = Modifier.fillMaxWidth()) {
                SkillTableRow(table.header, header = true)
                table.rows.forEach { row -> SkillTableRow(row, header = false) }
            }
        }
        SkillDocumentBlockKind.FRONTMATTER -> Text(
            text = block.source,
            modifier = Modifier.fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SkillDocumentBlockKind.RAW -> Text(
            text = SkillDocumentPresentation.literal(block.source).asAnnotatedString(),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface
        )
        SkillDocumentBlockKind.PARAGRAPH -> Text(
            text = SkillDocumentPresentation.paragraph(block.source).asAnnotatedString(),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SkillTableRow(cells: List<SkillInlinePresentation>, header: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        cells.forEach { cell ->
            Text(
                text = cell.asAnnotatedString(),
                modifier = Modifier.weight(1f).padding(10.dp),
                fontWeight = if (header) FontWeight.Bold else null,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun SkillInlinePresentation.asAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        val style = when (span.style) {
            io.github.anshuman2401.hermesmobile.feature.brain.SkillInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            io.github.anshuman2401.hermesmobile.feature.brain.SkillInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            io.github.anshuman2401.hermesmobile.feature.brain.SkillInlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
        }
        addStyle(style, span.start, span.endExclusive)
    }
}
