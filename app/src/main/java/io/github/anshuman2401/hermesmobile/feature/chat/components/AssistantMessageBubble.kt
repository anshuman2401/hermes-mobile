package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.design.HermesTheme
import io.github.anshuman2401.hermesmobile.feature.chat.AssistantMessage
import io.github.anshuman2401.hermesmobile.feature.chat.AssistantMessageState
import io.github.anshuman2401.hermesmobile.feature.chat.TranscriptCopyPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.markdown.MarkdownRenderer
import kotlinx.coroutines.delay

/**
 * Assistant message bubble per ARCH-001 (chat-rendering.md).
 * Features:
 * - Progressive streaming text display
 * - Native Markdown & code block rendering
 * - Explicit terminal state chips: Complete, Interrupted, Failed, Unknown
 * - One-tap copy action on user gesture (BLD-006 S10/D-12: sealed messages
 *   only, bounded 1 MiB window scope, sensitive-marked clip via SEC-005)
 * - Text selection (BLD-006 S10/TASK-045): enabled only for sealed terminal
 *   messages — no selection control is active during streaming, and the
 *   selectable source is the same bounded visible presentation window
 */
@Composable
fun AssistantMessageBubble(
    message: AssistantMessage,
    onUrlClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    // BLD-006 S10 (TASK-045/D-12): one derived sealed-for-copy flag. While
    // STREAMING the copy control is absent and the body is not selectable.
    val sealedForCopy = TranscriptCopyPolicy.sealedForCopy(message.state)
    // BLD-006 S10 (TASK-046/D-04): the copy scope is the visible 1 MiB
    // presentation window — never the whole 8 MiB canonical source, never a
    // partial stream, never a hidden over-cap suffix. Labels are disclosure
    // text, not payload. Built once per (state, text) pair.
    val copyRequest = remember(message.state, message.text) {
        TranscriptCopyPolicy.responseCopyRequestIfSealed(
            message.state,
            message.text
        )
    }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // BLD-006 S10 (TASK-045/D-12): the selection container wraps
            // the body only for sealed terminal messages — while
            // STREAMING there is no selection control at all. The
            // selectable source is the same bounded visible presentation
            // window rendered above.
            if (sealedForCopy) {
                SelectionContainer {
                    AssistantMessageBody(
                        message = message,
                        onUrlClick = onUrlClick,
                        sealedForCopy = sealedForCopy
                    )
                }
            } else {
                AssistantMessageBody(
                    message = message,
                    onUrlClick = onUrlClick,
                    sealedForCopy = sealedForCopy
                )
            }

            // Terminal status bar / actions remain available on the chat canvas.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Status tag. CHAT-020 (D-01): STREAMING renders no bottom
                // status row — the body's "Thinking..." row
                // (AssistantMessageBody) is the single streaming indicator,
                // so the bubble never shows two competing labels at once.
                when (message.state) {
                    AssistantMessageState.STREAMING -> Unit

                    AssistantMessageState.INTERRUPTED -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Interrupted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    AssistantMessageState.FAILED -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            // BLD-006 S4 (SEC-004): typed non-secret copy —
                            // raw exception/transport/payload text is never
                            // rendered on the chip. Failure detail stays in
                            // ViewModel state for diagnostics only.
                            Text(
                                text = "Turn failed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        // PR-36 review polish: AssistantMessage.errorMessage is
                        // intentionally UNRENDERED in the transcript. The raw
                        // gateway error line is redacted at the adapter
                        // boundary (GatewayRedactor), not classified here, and
                        // this transcript is the durable, copyable surface —
                        // a raw exception/transport line pasted into a ticket
                        // is exactly the leak vector SEC-004 guards against.
                        // The FAILED chip above already carries the typed
                        // non-secret status ("Turn failed"), and the VM-level
                        // ChatUiState.errorMessage is the user-facing channel
                        // for bounded, pre-reviewed copy. This row's field
                        // stays diagnostics-only; this test-backed contract
                        // pins the row contract.
                    }

                    AssistantMessageState.UNKNOWN -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Unknown status",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    AssistantMessageState.COMPLETE -> {
                        // Subtle completed mark or empty spacer
                        Spacer(modifier = Modifier.width(1.dp))
                    }
                }

                // BLD-006 S10 (TASK-045/D-12): the copy control exists
                // only for sealed messages with visible content; while
                // streaming there is no copy control at all.
                if (sealedForCopy && message.text.isNotEmpty() && copyRequest != null) {
                    // Owner directive 2026-09-27 (follow-up to PR #183): the
                    // copy control is icon-only. Same bounded copy call
                    // (SEC-005) and TalkBack description; the copy glyph
                    // swaps to a check for 2s after a successful copy.
                    IconButton(
                        onClick = {
                            // BLD-006 S10 (TASK-046/SEC-005): user-initiated
                            // write of exactly the bounded visible scope,
                            // marked sensitive where the Android API
                            // supports it (API 33+, ASSUMPTION-007).
                            copySensitiveText(
                                context,
                                copyRequest.label,
                                copyRequest.text
                            )
                            isCopied = true
                        },
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics {
                                contentDescription = if (isCopied) "Response copied" else "Copy response"
                            }
                    ) {
                        CopyGlyph(
                            copied = isCopied,
                            glyphSize = 16.dp,
                            tint = HermesTheme.extendedColors.primaryText
                        )
                    }
                }
            }
        }

        // CHAT-009 (FZ-1/FZ-7): per-bubble time caption, outside the content
        // column (does not disturb the terminal status row). Absent
        // timestamp (0L sentinel) renders nothing — never a fabricated time.
        ChatTimestampCaption(timestampMillis = message.timestamp)
    }
}

/**
 * BLD-006 S10: the assistant bubble body — bounded Markdown/code rendering or
 * the streaming "Thinking..." row. Extracted so [AssistantMessageBubble] can
 * wrap it in a [SelectionContainer] only when the message is sealed
 * (TASK-045/D-12). The [sealedForCopy] flag also gates the code-block copy
 * controls rendered by [MarkdownRenderer].
 */
@Composable
private fun AssistantMessageBody(
    message: AssistantMessage,
    onUrlClick: ((String) -> Unit)?,
    sealedForCopy: Boolean
) {
    if (message.text.isNotEmpty()) {
        MarkdownRenderer(
            content = message.text,
            messageKey = message.id,
            onUrlClick = onUrlClick,
            sealed = sealedForCopy
        )
    } else if (message.state == AssistantMessageState.STREAMING) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                // CHG-008 repair round 1 (P1-A): foreground indicator on the
                // chat canvas.
                color = HermesTheme.extendedColors.primaryText
            )
            Text(
                text = "Thinking...",
                // ui-reply-format-polish-v1 §3: the bubble's plain-text row
                // reads at bodyLarge like the renderer body (14sp → 16sp).
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
