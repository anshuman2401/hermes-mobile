package io.github.anshuman2401.hermesmobile.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.anshuman2401.hermesmobile.design.HermesShapeTokens
import io.github.anshuman2401.hermesmobile.feature.chat.ChatDaySeparatorPolicy
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessage
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessageCollapsePolicy
import io.github.anshuman2401.hermesmobile.feature.chat.UserMessageState

/**
 * User message bubble per ARCH-001 (chat-rendering.md / session-and-turn-state-machine.md).
 * Displays optimistic sending state, confirmed sent state, or explicit UNKNOWN warning.
 */
@Composable
fun UserMessageBubble(
    message: UserMessage,
    onRetryAsNewPrompt: (() -> Unit)? = null,
    onCheckHistory: (() -> Unit)? = null,
    retryEnabled: Boolean = true,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(topStart = HermesShapeTokens.bubbleCorner, topEnd = 4.dp, bottomStart = HermesShapeTokens.bubbleCorner, bottomEnd = HermesShapeTokens.bubbleCorner)),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = HermesShapeTokens.bubbleCorner, topEnd = 4.dp, bottomStart = HermesShapeTokens.bubbleCorner, bottomEnd = HermesShapeTokens.bubbleCorner)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Owner directive 2026-09-27: long prompts collapse to their
                // first 300 characters with a "Show more" control at the
                // bubble's bottom-right, so one long message doesn't push the
                // rest of the conversation out of view. Expansion is
                // per-message state (keyed by message id) and survives
                // scrolling the item out of composition.
                var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
                val invocationText = io.github.anshuman2401.hermesmobile.feature.chat.SkillInvocationDisplay.text(message.text)
                val collapsed = UserMessageCollapsePolicy.shouldCollapse(invocationText)
                val displayText = UserMessageCollapsePolicy.collapsedText(
                    text = invocationText,
                    expanded = expanded
                )
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .fillMaxWidth(),
                    textAlign = TextAlign.Start
                )

                if (collapsed) {
                    // Bottom-right expand control, inside the bubble under the
                    // (truncated) text. Only this message expands; the full
                    // text is restored verbatim — no re-truncation of content.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { expanded = true },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Show the full message. The preview shows the first " +
                                        UserMessageCollapsePolicy.COLLAPSE_THRESHOLD_CHARS +
                                        " characters."
                            }
                        ) {
                            Text(
                                text = UserMessageCollapsePolicy.SHOW_MORE_LABEL,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Status indication
                val statusAlignment = when (message.state) {
                    UserMessageState.SENT -> Alignment.End
                    UserMessageState.SENDING, UserMessageState.UNKNOWN -> Alignment.Start
                }
                Row(
                    modifier = Modifier.align(statusAlignment),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    when (message.state) {
                        UserMessageState.SENDING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "Sending...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }

                        UserMessageState.SENT -> {
                            Text(
                                text = "Sent",
                                style = MaterialTheme.typography.labelSmall,
                                // Polish PR A: raised 0.6 -> 0.7. On the now-wired
                                // primaryContainer the 0.6 tier computes to 4.55:1 in
                                // light — under AA; 0.7 is 6.32:1 light / 7.02:1 dark.
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }

                        UserMessageState.UNKNOWN -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Sent status unknown",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Sent status unknown",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // CHAT-009 (FZ-1/FZ-7): per-bubble time caption, outside the bubble
        // Surface (does not disturb the bubble's own status row). Absent
        // timestamp (0L sentinel) renders no time or divider; Edit stays available.
        ChatTimestampCaption(
            timestampMillis = message.timestamp,
            leadingContent = onEdit?.let { edit -> { EditMessageButton(edit) } }
        )
        // Keep Edit available for local messages without a source timestamp.
        if (message.timestamp <= 0L && onEdit != null) EditMessageButton(onEdit)

        // Explicit user decision banner when message state is UNKNOWN
        if (message.state == UserMessageState.UNKNOWN) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(HermesShapeTokens.cardCornerSmall)),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                shape = RoundedCornerShape(HermesShapeTokens.cardCornerSmall)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Prompt status unknown — original prompt may have reached the host.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (onCheckHistory != null) {
                            OutlinedButton(
                                onClick = onCheckHistory,
                                // CHG-008 repair round 2 (P1) — background CORRECTED.
                                // Round 1's comment claimed this button sat on the
                                // bubble's `primaryContainer`. It does not: the bubble's
                                // Surface closes above, and this button is a child of the
                                // UNKNOWN decision banner, whose background is
                                // `errorContainer` at 20% alpha composited over the
                                // canvas. That composite role is unwired in BOTH themes
                                // and, read off the resolved schemes, is
                                // dark 0.20*#8C1D18 + 0.80*#0F1015 = #281316 and
                                // light 0.20*#F9DEDC + 0.80*#F7F9FC = #F7F4F6.
                                //
                                // The principled pairing on that container is its own M3
                                // `on*` role, `onErrorContainer`: 13.82:1 dark / 14.88:1
                                // light on the composite. Round 1's `primaryText` was
                                // chosen for a background this button never paints on —
                                // on THAT claimed background it measured 3.14:1 dark /
                                // 4.20:1 light (both fail). `onSurface` would also pass
                                // here (17.57 / 15.06) but is not the matching role for an
                                // error container. Pinned in both themes, off the resolved
                                // schemes, by
                                // ThemeAndContrastTest.userMessageBubbleCheckHistoryButton_meetsAaInBothThemesOnItsRealBackground.
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics { contentDescription = "Check history to resolve prompt status" }
                            ) {
                                Text("Check history", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (onRetryAsNewPrompt != null) {
                            OutlinedButton(
                                onClick = onRetryAsNewPrompt,
                                // CHAT-019 repair round 2 (PROD-003): the retry
                                // affordance is refresh-gated exactly like the
                                // refresh notice's Retry control and the composer
                                // readiness flag; ChatScreen supplies the flag.
                                // The M3 disabled rendering is the standard
                                // enabled=false affordance — no bespoke styling.
                                // 'Check history' is deliberately untouched.
                                enabled = retryEnabled,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .semantics { contentDescription = "Send prompt again as a new attempt" }
                            ) {
                                Text("Send as new prompt", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditMessageButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Edit message",
            modifier = Modifier.size(18.dp)
        )
    }
}
