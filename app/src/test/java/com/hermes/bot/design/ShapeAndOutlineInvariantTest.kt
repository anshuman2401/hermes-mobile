package com.hermes.bot.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHG-008 S3 D7 invariant gate.
 *
 * The D7 ruling: the dark outline `#454B60` ([DarkOutline], also wired as the
 * dark `glassOutline`) is DECORATIVE ONLY. It measures ~2.20:1 against the
 * canvas and ~1.97:1 against the card — below the 3:1 WCAG
 * graphical-object floor, which is CORRECT for a decorative hairline but
 * means it must NEVER carry meaningful state. If any boundary ever has to
 * carry state, D7 mandates migration to the accessible fallback `#6B7290`
 * (~4.01:1 canvas / ~3.59:1 card).
 *
 * Both directions are pinned here:
 *  1. decorative (<3:1 against canvas AND card) — the token is not
 *     accidentally promoted into a state-carrying role;
 *  2. fallback (>=3:1 against canvas AND card) — the migration target is a
 *     real, measured value, not a memory.
 *
 * The fallback hex appears ONLY in this test file (checklist §4.2); production
 * code must keep consuming tokens.
 */
class ShapeAndOutlineInvariantTest {

    @Test
    fun d7_darkOutline_isDecorativeStrength() {
        // D7 ruling: #454B60 is below the 3:1 meaningful-boundary threshold in
        // both directions, which is CORRECT for a decorative hairline. State
        // must never be carried by this outline.
        val ratioVsCanvas = calculateContrastRatio(DarkOutline, DarkSurface)
        val ratioVsCard = calculateContrastRatio(DarkOutline, DarkSurfaceContainer)
        assertTrue(
            "DarkOutline vs canvas = $ratioVsCanvas — must stay decorative (<3:1). If this fires, the token was changed into a state-carrying boundary and D7 review is required.",
            ratioVsCanvas < 3.0
        )
        assertTrue(
            "DarkOutline vs card = $ratioVsCard — must stay decorative (<3:1). If this fires, the token was changed into a state-carrying boundary and D7 review is required.",
            ratioVsCard < 3.0
        )
    }

    @Test
    fun d7_meaningfulBoundaryFallback_meetsThreeToOne() {
        // If any boundary ever carries meaning it MUST move to #6B7290
        // (skeptic round 1: ~4.01 vs canvas, ~3.59 vs card). Pin the fallback
        // value so the migration target is a token, not a memory. This hex is
        // allowed ONLY here, in test code — production consumes tokens.
        val fallback = Color(0xFF6B7290)
        val r1 = calculateContrastRatio(fallback, DarkSurface)
        val r2 = calculateContrastRatio(fallback, DarkSurfaceContainer)
        assertTrue(
            "D7 fallback vs canvas = $r1 — must be >= 3:1 (skeptic: 4.01).",
            r1 >= 3.0
        )
        assertTrue(
            "D7 fallback vs card = $r2 — must be >= 3:1 (skeptic: 3.59).",
            r2 >= 3.0
        )
    }

    @Test
    fun shapeTokens_withinApprovedSixteenToTwentyRange() {
        // Approved card corner range 16–20dp (owner visual language §2).
        assertTrue(
            "cardCornerSmall ${HermesShapeTokens.cardCornerSmall.value} below approved 16dp floor",
            HermesShapeTokens.cardCornerSmall.value >= 16f
        )
        assertTrue(
            "cardCornerLarge ${HermesShapeTokens.cardCornerLarge.value} above approved 20dp ceiling",
            HermesShapeTokens.cardCornerLarge.value <= 20f
        )
        assertTrue(
            "cardCornerSmall must not exceed cardCornerLarge",
            HermesShapeTokens.cardCornerSmall.value <= HermesShapeTokens.cardCornerLarge.value
        )
        // bubbleCorner maps the 16dp sides of the chat bubble tail geometry;
        // the 4dp tail side is identity, not a card corner, and stays literal.
        assertTrue(
            "bubbleCorner must equal the 16dp approved floor",
            HermesShapeTokens.bubbleCorner.value == 16f
        )
    }
}
