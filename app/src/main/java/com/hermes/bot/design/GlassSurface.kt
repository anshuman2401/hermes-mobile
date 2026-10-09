package com.hermes.bot.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CHG-008 D5: V1 glass = translucent fill + decorative hairline outline.
 * Blur is a stretch goal in a SEPARATE change (captured-background-layer
 * strategy); this modifier never applies blur. Below API 31 — and on every
 * API level in V1 — the translucent tier is the only tier, so [GlassTier]
 * exists purely as the seam for the future blur change (D6 testability).
 */
enum class GlassTier(val apiLevel: Int) {
    TRANSLUCENT_FILL(26),
    BLUR(31);

    companion object {
        fun fromApiLevel(apiLevel: Int): GlassTier =
            if (apiLevel >= BLUR.apiLevel) BLUR else TRANSLUCENT_FILL
    }
}

class GlassSurfaceStyle(
    val tier: GlassTier,
    val fill: Color,
    val outline: Color,
    val cornerRadiusDp: Dp
)

object GlassDefaults {
    const val FILL_ALPHA = 0.85f

    /** V1 rendering contract: the modifier only ever consumes this tier. */
    val v1Tier: GlassTier = GlassTier.TRANSLUCENT_FILL

    /**
     * CHG-008 S2 boundary: the light theme must render EXACTLY as before
     * (brief CON-006 "light untouched except shared plumbing"), so the light
     * tier uses the opaque fill (alpha = 1f) and skips the decorative
     * outline; only the dark palette is translucent frosted glass.
     */
    fun fillAlphaFor(isDark: Boolean): Float = if (isDark) FILL_ALPHA else 1f

    @Composable
    fun style(): GlassSurfaceStyle {
        val isDark = HermesTheme.extendedColors.isDark
        return GlassSurfaceStyle(
            tier = v1Tier,
            fill = MaterialTheme.colorScheme.background.copy(alpha = fillAlphaFor(isDark)),
            outline = if (isDark) HermesTheme.extendedColors.glassOutline else Color.Transparent,
            cornerRadiusDp = HermesShapeTokens.cardCornerLarge
        )
    }
}

/**
 * Glass modifier: clip to corner radius, paint translucent fill, stroke the
 * decorative outline. Consumes tokens only; no raw hex (CON-003). Never
 * applies blur (D5).
 */
fun Modifier.glassSurface(style: GlassSurfaceStyle): Modifier = composed {
    this
        .clip(RoundedCornerShape(style.cornerRadiusDp))
        .background(style.fill)
        .border(1.dp, style.outline, RoundedCornerShape(style.cornerRadiusDp))
}
