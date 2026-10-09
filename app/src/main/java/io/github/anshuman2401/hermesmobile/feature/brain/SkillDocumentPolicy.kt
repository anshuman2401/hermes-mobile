package io.github.anshuman2401.hermesmobile.feature.brain

/** Formatting classes are deliberately small; source text remains authoritative. */
enum class SkillDocumentBlockKind {
    FRONTMATTER,
    HEADING,
    PARAGRAPH,
    CODE,
    LIST,
    QUOTE,
    RULE,
    TABLE,
    RAW
}

data class SkillDocumentSpan(
    val start: Int,
    val endExclusive: Int,
    val kind: SkillDocumentBlockKind
)

data class SkillDocumentBlock(
    val start: Int,
    val endExclusive: Int,
    val kind: SkillDocumentBlockKind,
    val source: String
)

data class SkillDocumentChunk(val blocks: List<SkillDocumentBlock>)

data class SkillDocument(
    val source: String,
    val spans: List<SkillDocumentSpan>,
    val blocks: List<SkillDocumentBlock>,
    val chunks: List<SkillDocumentChunk>,
    val formattingFallback: Boolean
) {
    fun reconstruct(): String = spans.joinToString(separator = "") {
        source.substring(it.start, it.endExclusive)
    }
}

enum class SkillInlineStyle {
    BOLD,
    ITALIC,
    CODE
}

data class SkillInlineSpan(
    val start: Int,
    val endExclusive: Int,
    val style: SkillInlineStyle
)

data class SkillInlinePresentation(
    val text: String,
    val spans: List<SkillInlineSpan>
)

data class SkillListItemPresentation(
    val marker: String,
    val content: SkillInlinePresentation
)

data class SkillTablePresentation(
    val header: List<SkillInlinePresentation>,
    val rows: List<List<SkillInlinePresentation>>
)

/** Small, pure normal-presentation mappings; the source document remains authoritative. */
object SkillDocumentPresentation {
    private val unordered = Regex("^ {0,3}([-+*])\\s+")
    private val ordered = Regex("^ {0,3}(\\d+[.)])\\s+")

    fun literal(source: String): SkillInlinePresentation =
        SkillInlinePresentation(source, emptyList())

    fun heading(source: String): SkillInlinePresentation {
        val first = presentationLines(source).firstOrNull() ?: return inlineText(source)
        val content = first.content.replaceFirst(Regex("^ {0,3}#{1,6}[ \\t]*"), "")
        return inlineText(content)
    }

    fun paragraph(source: String): SkillInlinePresentation = inlineText(source)

    fun quote(source: String): SkillInlinePresentation =
        inlineText(rewriteLines(source) { line ->
            val trimmed = line.trimStart()
            if (!trimmed.startsWith(">")) line
            else trimmed.removePrefix(">").removePrefix(" ")
        })

    fun list(source: String): List<SkillListItemPresentation> = presentationLines(source).map { line ->
        val unorderedMatch = unordered.find(line.content)
        val orderedMatch = ordered.find(line.content)
        when {
            unorderedMatch != null -> SkillListItemPresentation(
                marker = "•",
                content = inlineText(line.content.removeRange(unorderedMatch.range))
            )
            orderedMatch != null -> SkillListItemPresentation(
                marker = orderedMatch.groupValues[1],
                content = inlineText(line.content.removeRange(orderedMatch.range))
            )
            else -> SkillListItemPresentation("•", inlineText(line.content))
        }
    }

    fun table(source: String): SkillTablePresentation {
        val rows = presentationLines(source).map { parseTableRow(it.content) }
        if (rows.size < 2) return SkillTablePresentation(emptyList(), emptyList())
        return SkillTablePresentation(
            header = rows.first().map { cell -> inlineText(cell) },
            rows = rows.drop(2).map { row -> row.map { cell -> inlineText(cell) } }
        )
    }

    fun fencedCode(source: String): String {
        val lines = presentationLines(source)
        if (lines.size < 2) return source
        return lines.drop(1).dropLast(1).joinToString(separator = "") { it.content + it.separator }
    }

    fun inlineText(source: String): SkillInlinePresentation {
        val text = StringBuilder()
        val spans = mutableListOf<SkillInlineSpan>()
        var index = 0
        while (index < source.length) {
            val marker = when {
                source.startsWith("**", index) -> "**" to SkillInlineStyle.BOLD
                source[index] == '`' -> "`" to SkillInlineStyle.CODE
                source[index] == '*' && (index + 1 >= source.length || source[index + 1] != '*') ->
                    "*" to SkillInlineStyle.ITALIC
                else -> null
            }
            if (marker != null) {
                val (token, style) = marker
                val close = source.indexOf(token, index + token.length)
                if (close > index + token.length &&
                    source.substring(index + token.length, close).none { it == '\r' || it == '\n' }
                ) {
                    val start = text.length
                    text.append(source, index + token.length, close)
                    spans += SkillInlineSpan(start, text.length, style)
                    index = close + token.length
                    continue
                }
            }
            text.append(source[index])
            index++
        }
        return SkillInlinePresentation(text.toString(), spans)
    }

    private fun parseTableRow(line: String): List<String> {
        val cells = line.split('|').map { it.trim() }.toMutableList()
        if (cells.firstOrNull().orEmpty().isEmpty()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.lastOrNull().orEmpty().isEmpty()) cells.removeAt(cells.lastIndex)
        return cells
    }

    private fun rewriteLines(source: String, transform: (String) -> String): String = buildString {
        presentationLines(source).forEach { line ->
            append(transform(line.content))
            append(line.separator)
        }
    }

    private data class PresentationLine(
        val content: String,
        val separator: String
    )

    private fun presentationLines(source: String): List<PresentationLine> {
        if (source.isEmpty()) return emptyList()
        val result = mutableListOf<PresentationLine>()
        var start = 0
        while (start < source.length) {
            val newline = source.indexOf('\n', start)
            if (newline < 0) {
                result += PresentationLine(source.substring(start), "")
                break
            }
            val contentEnd = if (newline > start && source[newline - 1] == '\r') newline - 1 else newline
            result += PresentationLine(
                content = source.substring(start, contentEnd),
                separator = source.substring(contentEnd, newline + 1)
            )
            start = newline + 1
        }
        return result
    }
}

/** Lossless, native-friendly Markdown classification; it is not a CommonMark engine. */
object SkillDocumentPolicy {
    const val MAX_FORMATTED_BLOCKS = 2_000
    const val MAX_LINE_SCALARS = 8_192
    const val MAX_FENCE_LINES = 2_000
    const val MAX_FENCE_BYTES = 256 * 1024
    const val MAX_TABLE_ROWS = 2_000
    const val MAX_CHUNK_UTF16 = 32 * 1024

    private val heading = Regex("^ {0,3}#{1,6}(?:\\s|$)")
    private val unordered = Regex("^ {0,3}(?:[-+*])\\s+")
    private val ordered = Regex("^ {0,3}\\d+[.)]\\s+")
    private val rule = Regex("^ {0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(?:\\|\\s*:?-{1,}:?\\s*)+\\|?\\s*$")

    fun parse(source: String): SkillDocument {
        if (source.isEmpty()) return SkillDocument(source, emptyList(), emptyList(), emptyList(), false)
        val lines = linesOf(source)
        val blocks = mutableListOf<SkillDocumentBlock>()
        var index = 0
        var fallback = false

        fun add(first: Int, lastInclusive: Int, kind: SkillDocumentBlockKind) {
            if (first > lastInclusive) return
            val start = lines[first].start
            val end = lines[lastInclusive].endExclusive
            blocks += SkillDocumentBlock(start, end, kind, source.substring(start, end))
        }

        // YAML/TOML-style metadata stays readable and inert, including its delimiters.
        if (lines.first().content.trim() == "---") {
            var close = lines.indexOfFirstAfter(1) { it.content.trim() == "---" || it.content.trim() == "..." }
            if (close < 0) close = lines.lastIndex
            add(0, close, SkillDocumentBlockKind.FRONTMATTER)
            index = close + 1
        }

        while (index < lines.size) {
            if (blocks.size >= MAX_FORMATTED_BLOCKS) {
                add(index, lines.lastIndex, SkillDocumentBlockKind.RAW)
                fallback = true
                break
            }
            val line = lines[index]
            val text = line.content
            if (text.scalarCount() > MAX_LINE_SCALARS) {
                add(index, index, SkillDocumentBlockKind.RAW)
                fallback = true
                index++
                continue
            }
            if (text.isBlank()) {
                add(index, index, SkillDocumentBlockKind.RAW)
                index++
                continue
            }

            val fence = fenceMarker(text)
            if (fence != null) {
                var end = index + 1
                var closed = false
                while (end < lines.size) {
                    if (fenceMarker(lines[end].content)?.first == fence.first) {
                        closed = true
                        end++
                        break
                    }
                    end++
                }
                val rawEnd = lines[end - 1].endExclusive
                val raw = source.substring(line.start, rawEnd)
                val tooLarge = end - index > MAX_FENCE_LINES || raw.toByteArray(Charsets.UTF_8).size > MAX_FENCE_BYTES
                add(index, end - 1, if (closed && !tooLarge) SkillDocumentBlockKind.CODE else SkillDocumentBlockKind.RAW)
                if (!closed || tooLarge) fallback = true
                index = end
                continue
            }
            when {
                heading.containsMatchIn(text) -> { add(index, index, SkillDocumentBlockKind.HEADING); index++ }
                rule.matches(text.trim()) -> { add(index, index, SkillDocumentBlockKind.RULE); index++ }
                text.trimStart().startsWith(">") -> {
                    val start = index
                    while (index < lines.size && lines[index].content.trimStart().startsWith(">")) index++
                    add(start, index - 1, SkillDocumentBlockKind.QUOTE)
                }
                unordered.containsMatchIn(text) || ordered.containsMatchIn(text) -> {
                    val start = index
                    while (index < lines.size &&
                        (unordered.containsMatchIn(lines[index].content) || ordered.containsMatchIn(lines[index].content))) index++
                    add(start, index - 1, SkillDocumentBlockKind.LIST)
                }
                isTableStart(lines, index) -> {
                    val start = index
                    index += 2
                    while (index < lines.size && lines[index].content.contains('|')) index++
                    val rows = index - start
                    val raw = source.substring(lines[start].start, lines[index - 1].endExclusive)
                    val tableFits = rows <= MAX_TABLE_ROWS && raw.length <= MAX_FENCE_BYTES
                    add(start, index - 1, if (tableFits) {
                        SkillDocumentBlockKind.TABLE
                    } else {
                        SkillDocumentBlockKind.RAW
                    })
                    if (!tableFits) fallback = true
                }
                else -> {
                    val start = index
                    index++
                    while (index < lines.size && lines[index].content.isNotBlank() && !isBlockStart(lines, index)) index++
                    val paragraph = source.substring(lines[start].start, lines[index - 1].endExclusive)
                    val inertLiteral = paragraph.contains("![") || paragraph.contains("](") ||
                        paragraph.contains("<script", ignoreCase = true) || paragraph.contains("<html", ignoreCase = true) ||
                        paragraph.contains("{{") || paragraph.contains("#!/")
                    add(start, index - 1, if (inertLiteral) SkillDocumentBlockKind.RAW else SkillDocumentBlockKind.PARAGRAPH)
                }
            }
        }

        val chunkBlocks = blocks.flatMap { block ->
            val splitBlocks = splitForChunks(source, block)
            if (splitBlocks.size > 1 &&
                (block.kind == SkillDocumentBlockKind.CODE || block.kind == SkillDocumentBlockKind.TABLE)
            ) {
                fallback = true
                splitBlocks.map { it.copy(kind = SkillDocumentBlockKind.RAW) }
            } else {
                splitBlocks
            }
        }
        val spans = chunkBlocks.map { SkillDocumentSpan(it.start, it.endExclusive, it.kind) }
        val chunks = chunkBlocks.chunkedBySize(MAX_CHUNK_UTF16)
        return SkillDocument(source, spans, blocks, chunks, fallback)
    }

    private fun isBlockStart(lines: List<SourceLine>, index: Int): Boolean {
        val text = lines[index].content
        return fenceMarker(text) != null || heading.containsMatchIn(text) || rule.matches(text.trim()) ||
            text.trimStart().startsWith(">") || unordered.containsMatchIn(text) || ordered.containsMatchIn(text) ||
            isTableStart(lines, index)
    }

    private fun isTableStart(lines: List<SourceLine>, index: Int): Boolean =
        index + 1 < lines.size && lines[index].content.contains('|') &&
            tableSeparator.matches(lines[index + 1].content)

    private fun fenceMarker(text: String): Pair<Char, Int>? {
        val trimmed = text.trimStart()
        if (trimmed.length < 3 || (trimmed.first() != '`' && trimmed.first() != '~')) return null
        val marker = trimmed.first()
        val count = trimmed.takeWhile { it == marker }.length
        return if (count >= 3) marker to count else null
    }

    private fun splitForChunks(source: String, block: SkillDocumentBlock): List<SkillDocumentBlock> {
        if (block.source.length <= MAX_CHUNK_UTF16) return listOf(block)
        val result = mutableListOf<SkillDocumentBlock>()
        var start = block.start
        while (start < block.endExclusive) {
            var end = minOf(start + MAX_CHUNK_UTF16, block.endExclusive)
            if (end < block.endExclusive && end > start && source[end - 1] == '\r') end--
            if (end < block.endExclusive && end > start && source[end - 1].isHighSurrogate()) end--
            if (end <= start) end = nextScalarEnd(source, start, block.endExclusive)
            result += SkillDocumentBlock(start, end, block.kind, source.substring(start, end))
            start = end
        }
        return result
    }

    private fun nextScalarEnd(source: String, start: Int, limit: Int): Int =
        if (start + 1 < limit && source[start].isHighSurrogate() && source[start + 1].isLowSurrogate()) {
            start + 2
        } else start + 1

    private fun List<SkillDocumentBlock>.chunkedBySize(maxSize: Int): List<SkillDocumentChunk> {
        val result = mutableListOf<SkillDocumentChunk>()
        var current = mutableListOf<SkillDocumentBlock>()
        var size = 0
        for (block in this) {
            if (current.isNotEmpty() && size + block.source.length > maxSize) {
                result += SkillDocumentChunk(current)
                current = mutableListOf()
                size = 0
            }
            current += block
            size += block.source.length
        }
        if (current.isNotEmpty()) result += SkillDocumentChunk(current)
        return result
    }

    private data class SourceLine(val start: Int, val contentEnd: Int, val endExclusive: Int, val content: String)

    private fun linesOf(source: String): List<SourceLine> {
        val result = mutableListOf<SourceLine>()
        var start = 0
        while (start < source.length) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline + 1
            val contentEnd = if (newline > start && source[newline - 1] == '\r') newline - 1 else if (newline < 0) source.length else newline
            result += SourceLine(start, contentEnd, end, source.substring(start, contentEnd))
            start = end
        }
        return result
    }

    private fun <T> List<T>.indexOfFirstAfter(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }

    private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
    private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'

    private fun String.scalarCount(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            count++
            index += if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
        }
        return count
    }
}
