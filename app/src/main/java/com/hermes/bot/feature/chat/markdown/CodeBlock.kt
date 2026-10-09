package com.hermes.bot.feature.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.bot.design.HermesShapeTokens
import com.hermes.bot.design.HermesTheme
import com.hermes.bot.feature.chat.TranscriptCopyPolicy
import com.hermes.bot.feature.chat.components.CopyGlyph
import com.hermes.bot.feature.chat.components.copySensitiveText
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Frozen D-10 code budgets (BLD-006 S9, TASK-041), enforced on the
 * surrogate-normalized stream (CON-008) so no surrogate pair or UTF-8
 * sequence is ever split and byte counts measure the normalized stream:
 * - initial preview: 60 lines with an explicit "show more" action
 * - expanded view/copy maximum: 2,000 lines or 256 KiB UTF-8, whichever
 *   occurs first, labeled [CODE_TRUNCATED_LABEL]; no unbounded "show all"
 */
object CodeBlockBudget {
    const val PREVIEW_LINES = 60
    const val MAX_LINES = 2_000
    const val MAX_UTF8_BYTES = 256 * 1024
    const val CODE_TRUNCATED_LABEL = "Code preview truncated."
    const val SHOW_MORE_LINES = 60
}

/**
 * Bounded code scope produced by [BoundedCode.boundedCodeScope]: the kept
 * text plus whether source content beyond the D-10 budget was dropped
 * (always visibly labeled, never silently clipped).
 */
internal data class BoundedCodeScope(val text: String, val truncated: Boolean)

/**
 * D-10 bounded code scope (TASK-041): collects at most
 * [CodeBlockBudget.MAX_LINES] lines AND [CodeBlockBudget.MAX_UTF8_BYTES]
 * UTF-8 bytes of the surrogate-normalized stream, whichever limit is hit
 * first stopping the scan (PAT-003: enforced during the walk, so an
 * oversized block never materializes beyond the cap). The kept text always
 * ends on a whole scalar; a byte-budget cut lands mid-line only as that
 * line's scalar-aligned UTF-8 prefix (never inside a sequence/pair).
 */
internal object BoundedCode {
    fun boundedCodeScope(code: String): BoundedCodeScope {
        val normalized = MarkdownBounds.normalizeSurrogates(code)
        var index = 0
        var bytes = 0
        var completedLines = 0
        while (index < normalized.length) {
            if (completedLines >= CodeBlockBudget.MAX_LINES) break
            val ch = normalized[index]
            // A separator into an over-budget next line belongs to the omitted suffix.
            // A terminal newline within the budget is retained byte-for-byte.
            if (ch == '\n' && completedLines + 1 == CodeBlockBudget.MAX_LINES && index + 1 < normalized.length) break
            val advance = if (Character.isHighSurrogate(ch) && index + 1 < normalized.length &&
                Character.isLowSurrogate(normalized[index + 1])) 2 else 1
            val scalarBytes = when {
                advance == 2 -> 4
                ch.code < 0x80 -> 1
                ch.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + scalarBytes > CodeBlockBudget.MAX_UTF8_BYTES) break
            bytes += scalarBytes
            index += advance
            if (ch == '\n') completedLines++
        }
        return BoundedCodeScope(normalized.substring(0,index),index < normalized.length)
    }
}

/**
 * Monospace code block component per ARCH-001 (chat-rendering.md), with the
 * frozen D-10 code budgets (BLD-006 S9, TASK-041). Features:
 * - Language header tag
 * - Horizontal scrolling with accessible contentDescription
 * - One-tap user-gesture Copy button (>=48dp touch target)
 * - 60-line initial preview with an explicit "show more" action
 * - Expanded view/copy hard maximum: 2,000 lines or 256 KiB UTF-8,
 *   whichever occurs first, labeled "Code preview truncated."; there is
 *   deliberately no unbounded "show all" (D-10)
 * - Copy exposes only the bounded visible code scope (never an over-cap
 *   suffix; full copy policy is owned by S10)
 * - No run or executable buttons
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    sealed: Boolean = true,
    modifier: Modifier = Modifier,
    notice: String? = null
) {
    val context = LocalContext.current
    var isCopied by remember(code) { mutableStateOf(false) }
    var visibleLines by remember { mutableStateOf(CodeBlockBudget.PREVIEW_LINES) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    // D-10: the code body is bounded during the walk (PAT-003) — the
    // 2,000-line AND 256 KiB UTF-8 budgets stop the scan, so an oversized
    // block never materializes beyond the cap and over-budget source is
    // always visibly labeled.
    val bounded = remember(code) { BoundedCode.boundedCodeScope(code) }
    val lines = remember(bounded) {
        bounded.text.split("\n").let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
    }
    val showMoreAvailable = lines.size > CodeBlockBudget.PREVIEW_LINES

    val displayedCode = remember(bounded, visibleLines) {
        if (visibleLines < lines.size) {
            lines.take(visibleLines).joinToString("\n") +
                "\n... [${lines.size - visibleLines} more lines]"
        } else {
            bounded.text
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
    ) {
        Column {
            // Selection covers source code, not the language label or action chrome.
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = language?.uppercase(Locale.ROOT) ?: "CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )

                    // BLD-006 S10 (TASK-045/D-12): the code copy control exists
                    // only for sealed content; while streaming there is no copy
                    // control at all.
                    if (sealed) {
                        IconButton(
                            onClick = {
                                // Copy exposes only the bounded D-10 code scope —
                                // never an over-cap suffix. BLD-006 S10
                                // (TASK-046/SEC-005): user-initiated write marked
                                // sensitive where the Android API supports it.
                                val request = TranscriptCopyPolicy.codeCopyRequestIfSealed(
                                    sealed = true,
                                    code = code
                                )
                                if (request != null) {
                                    copySensitiveText(context, request.label, request.text)
                                    isCopied = true
                                }
                            },
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics {
                                    contentDescription = if (isCopied) "Code copied to clipboard" else "Copy code block"
                                }
                        ) {
                            // Owner directive 2026-09-27: icon-only copy control.
                            // Copy icon swaps to a check after a successful copy.
                            CopyGlyph(
                                copied = isCopied,
                                glyphSize = 16.dp,
                                tint = HermesTheme.extendedColors.primaryText
                            )
                        }
                    }
                }
            }

            // Monospace code body with horizontal scroll. ui-reply-format-
            // polish-v1 §1: fenced code renders through SyntaxHighlighter when
            // the fence label maps to a family; unknown/absent labels keep the
            // plain monospace look. Colors are theme-resolved here so the
            // highlighter stays a pure function.
            val codeTokens = HermesTheme.extendedColors.let {
                CodeTokenColors(
                    keyword = it.codeKeyword,
                    string = it.codeString,
                    comment = it.codeComment,
                    number = it.codeNumber
                )
            }
            val annotatedCode = remember(displayedCode, language, codeTokens) {
                SyntaxHighlighter.highlight(displayedCode, language, codeTokens)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState())
                    .semantics {
                        contentDescription = "Scroll code horizontally"
                    }
            ) {
                Text(
                    text = annotatedCode,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Visible truncation label (D-10: truncation is always labeled)
            if (bounded.truncated || notice != null) {
                DisableSelection {
                    Text(
                        text = listOfNotNull(notice, if (bounded.truncated) CodeBlockBudget.CODE_TRUNCATED_LABEL else null).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        // CHG-008 repair round 2 (P2): this label sits on the code
                        // body's `surfaceContainerHighest`. The refreshed dark
                        // onSurfaceVariant #8E92A4 is only 3.97:1 there — a
                        // regression the dark refresh introduced (pre-PR #B7C2D0
                        // was 6.80:1). colorScheme.onSurface is the role that pairs
                        // with a surface role: 12.27:1 dark / 12.69:1 light.
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            // User-initiated bounded expansion ("show more"), never an
            // unbounded "show all": each activation reveals at most
            // [CodeBlockBudget.SHOW_MORE_LINES] more lines up to the cap.
            if (showMoreAvailable && visibleLines < lines.size) {
                DisableSelection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                visibleLines = (visibleLines + CodeBlockBudget.SHOW_MORE_LINES)
                                    .coerceAtMost(CodeBlockBudget.MAX_LINES)
                            },
                            // CHG-008 repair round 2 (P2): this TextButton sits on the
                            // code body's `surfaceContainerHighest`. Round 1's
                            // primaryText was 4.14:1 dark / 4.18:1 light there —
                            // both below AA. The `surface*` role's matching partner
                            // is `onSurface`: 12.27:1 dark / 12.69:1 light.
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(
                                text = "Show more (${lines.size - visibleLines} lines remain)",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}
