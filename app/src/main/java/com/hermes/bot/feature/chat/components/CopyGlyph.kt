package com.hermes.bot.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icon-only copy affordance (owner directive 2026-09-27): the classic
 * two-stacked-sheets copy glyph, drawn with primitives — no icon-asset or
 * icons-extended dependency (BLD-006 S10 precedent: code-drawn glyphs). After
 * a successful copy the glyph swaps to the standard check.
 *
 * Purely visual; [copied] drives the swap. Callers keep their own click
 * target (48dp) and TalkBack semantics.
 */
@Composable
internal fun CopyGlyph(
    copied: Boolean,
    glyphSize: Dp,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (copied) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(glyphSize)
        )
    } else {
        Box(
            modifier = modifier.size(glyphSize),
            contentAlignment = Alignment.Center
        ) {
            // Back sheet: outline, offset toward the top-right.
            Box(
                modifier = Modifier
                    .size(glyphSize * 0.68f)
                    .align(Alignment.TopEnd)
                    .border(
                        width = 1.5.dp,
                        color = tint,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            // Front sheet: solid, offset toward the bottom-left; its fill
            // occludes the back sheet's overlap so the two sheets read
            // stacked on any background.
            Box(
                modifier = Modifier
                    .size(glyphSize * 0.68f)
                    .align(Alignment.BottomStart)
                    .background(tint, RoundedCornerShape(2.dp))
            )
        }
    }
}