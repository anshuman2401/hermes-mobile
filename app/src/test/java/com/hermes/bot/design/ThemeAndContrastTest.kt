package com.hermes.bot.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Automated text and action contrast verification for all semantic tokens.
 * Required by BLD-004 acceptance criteria and specs/architecture/design/theme-and-icon-brief.md.
 * Tests WCAG 2.1 AA compliance (>= 4.5:1 for normal text).
 *
 * CHG-008 S1: every dark pairing was recomputed against the refreshed dark
 * palette. Skeptic-verified expected values (specs/test-evidence/chg-008/
 * skeptic-round1.md + skeptic-round2.md) are asserted both as `ratio >= 4.5`
 * gates and as tolerance checks, so a token typo that still clears 4.5:1 is
 * caught. Per learning 2026-09-18-chg008-contrast-claims-must-be-computed the
 * skeptic numbers are used as given, never re-derived by hand.
 */
class ThemeAndContrastTest {

    // CHG-008 S3: the contrast math below now lives in ContrastMath.kt
    // (shared with ShapeAndOutlineInvariantTest) — this class imports
    // sRgbToLinear / calculateRelativeLuminance / calculateContrastRatio
    // from the same package, so no local copies remain.

    @Test
    fun connectFilledFields_textAndBoundaryPairsMeetFloorsInBothResolvedThemes() {
        for ((name, scheme, extended) in listOf(
            Triple("light", lightScheme(), LightExtendedColors),
            Triple("dark", darkScheme(), DarkExtendedColors)
        )) {
            for ((role, foreground) in listOf(
                "value" to scheme.onSurface,
                "placeholder and neutral boundary" to scheme.onSurfaceVariant,
                "focus boundary and cursor" to extended.primaryText,
                "error boundary" to scheme.error
            )) {
                val ratio = calculateContrastRatio(foreground, scheme.surfaceContainer)
                assertTrue("$name Connect $role on filled field $ratio < 4.5", ratio >= 4.5)
                // The same boundary touches the surrounding canvas.
                val canvasRatio = calculateContrastRatio(foreground, scheme.surface)
                assertTrue("$name Connect $role against canvas $canvasRatio < 3", canvasRatio >= 3)
            }
        }
    }

    @Test
    fun lightTheme_textAndActionPairings_meetWcagAaContrast() {
        val minRatio = 4.5 // WCAG AA normal text threshold

        // Primary text on canvas
        val onSurfaceRatio = calculateContrastRatio(LightOnSurface, LightSurface)
        assertTrue("Light OnSurface contrast $onSurfaceRatio < $minRatio", onSurfaceRatio >= minRatio)

        // Primary text on container
        val onSurfaceContainerRatio = calculateContrastRatio(LightOnSurface, LightSurfaceContainer)
        assertTrue("Light OnSurface on Container contrast $onSurfaceContainerRatio < $minRatio", onSurfaceContainerRatio >= minRatio)

        // Secondary text on canvas
        val onSurfaceVariantRatio = calculateContrastRatio(LightOnSurfaceVariant, LightSurface)
        assertTrue("Light OnSurfaceVariant contrast $onSurfaceVariantRatio < $minRatio", onSurfaceVariantRatio >= minRatio)

        // Secondary text on container
        val onSurfaceVariantContainerRatio = calculateContrastRatio(LightOnSurfaceVariant, LightSurfaceContainer)
        assertTrue("Light OnSurfaceVariant on Container contrast $onSurfaceVariantContainerRatio < $minRatio", onSurfaceVariantContainerRatio >= minRatio)

        // OnPrimary on Primary
        val onPrimaryRatio = calculateContrastRatio(LightOnPrimary, LightPrimary)
        assertTrue("Light OnPrimary on Primary contrast $onPrimaryRatio < $minRatio", onPrimaryRatio >= minRatio)

        // OnSecondary on Secondary
        val onSecondaryRatio = calculateContrastRatio(LightOnSecondary, LightSecondary)
        assertTrue("Light OnSecondary on Secondary contrast $onSecondaryRatio < $minRatio", onSecondaryRatio >= minRatio)

        // OnError on Error
        val onErrorRatio = calculateContrastRatio(LightOnError, LightError)
        assertTrue("Light OnError on Error contrast $onErrorRatio < $minRatio", onErrorRatio >= minRatio)

        // Warning on Surface
        val warningRatio = calculateContrastRatio(LightWarning, LightSurface)
        assertTrue("Light Warning on Surface contrast $warningRatio < $minRatio", warningRatio >= minRatio)

        // Code text on Code surface
        val codeRatio = calculateContrastRatio(LightCodeOnSurface, LightCodeSurface)
        assertTrue("Light Code contrast $codeRatio < $minRatio", codeRatio >= minRatio)

        // CHG-008 repair round 1: the new primaryText FOREGROUND role must keep
        // today's light appearance exactly (CON-006) — its light value is the
        // existing LightPrimary hex, so the light ratios below are the light
        // ratios this text already had. Pinned so a dark-only edit cannot drift
        // the light theme.
        val primaryText = LightExtendedColors.primaryText
        assertEquals(
            "Light primaryText must stay on the existing LightPrimary hex (CON-006)",
            LightPrimary,
            primaryText
        )
        val primaryTextCanvasRatio = calculateContrastRatio(primaryText, LightSurface)
        assertTrue(
            "Light primaryText on canvas contrast $primaryTextCanvasRatio < $minRatio",
            primaryTextCanvasRatio >= minRatio
        )
        assertEquals("Light primaryText on canvas moved off the recomputed 5.13", 5.13, primaryTextCanvasRatio, 0.05)

        val primaryTextContainerRatio = calculateContrastRatio(primaryText, LightSurfaceContainer)
        assertTrue(
            "Light primaryText on card contrast $primaryTextContainerRatio < $minRatio",
            primaryTextContainerRatio >= minRatio
        )
        assertEquals(
            "Light primaryText on card moved off the recomputed 4.82",
            4.82,
            primaryTextContainerRatio,
            0.05
        )
    }

    @Test
    fun darkTheme_textAndActionPairings_meetWcagAaContrast() {
        val minRatio = 4.5 // WCAG AA normal text threshold

        // Primary text on canvas (skeptic: 19.00)
        val onSurfaceRatio = calculateContrastRatio(DarkOnSurface, DarkSurface)
        assertTrue("Dark OnSurface contrast $onSurfaceRatio < $minRatio", onSurfaceRatio >= minRatio)

        // Primary text on container (skeptic: 17.00)
        val onSurfaceContainerRatio = calculateContrastRatio(DarkOnSurface, DarkSurfaceContainer)
        assertTrue("Dark OnSurface on Container contrast $onSurfaceContainerRatio < $minRatio", onSurfaceContainerRatio >= minRatio)

        // Secondary text on canvas (skeptic: 6.15)
        val onSurfaceVariantRatio = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurface)
        assertTrue("Dark OnSurfaceVariant contrast $onSurfaceVariantRatio < $minRatio", onSurfaceVariantRatio >= minRatio)
        assertEquals("Dark OnSurfaceVariant on canvas moved off the skeptic 6.15", 6.15, onSurfaceVariantRatio, 0.05)

        // Secondary text on card (skeptic: 5.50)
        val onSurfaceVariantContainerRatio = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurfaceContainer)
        assertTrue("Dark OnSurfaceVariant on Container contrast $onSurfaceVariantContainerRatio < $minRatio", onSurfaceVariantContainerRatio >= minRatio)
        assertEquals("Dark OnSurfaceVariant on card moved off the skeptic 5.50", 5.50, onSurfaceVariantContainerRatio, 0.05)

        // CHG-008 skeptic round-2 hardening: gray on the elevated/card upper
        // bound #1E202B (5.24:1) — the tightest gray-on-card pairing, adopted
        // as a controller default because S1 recomputes every dark pairing.
        val onSurfaceVariantElevatedRatio = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurfaceElevated)
        assertTrue("Dark OnSurfaceVariant on elevated contrast $onSurfaceVariantElevatedRatio < $minRatio", onSurfaceVariantElevatedRatio >= minRatio)
        assertEquals("Dark OnSurfaceVariant on #1E202B moved off the skeptic 5.24", 5.24, onSurfaceVariantElevatedRatio, 0.05)

        // OnPrimary on Primary — D4 mandatory swap: #FFFFFF on #5352ED
        // (skeptic: 5.53). The previous #002E69 on #5352ED was 2.38:1 (blocker B3).
        val onPrimaryRatio = calculateContrastRatio(DarkOnPrimary, DarkPrimary)
        assertTrue("Dark OnPrimary on Primary contrast $onPrimaryRatio < $minRatio", onPrimaryRatio >= minRatio)
        assertEquals("Dark OnPrimary on violet moved off the skeptic 5.53", 5.53, onPrimaryRatio, 0.05)

        // OnSecondary on Secondary (skeptic: 6.81, onSecondary hex unchanged)
        val onSecondaryRatio = calculateContrastRatio(DarkOnSecondary, DarkSecondary)
        assertTrue("Dark OnSecondary on Secondary contrast $onSecondaryRatio < $minRatio", onSecondaryRatio >= minRatio)
        assertEquals("Dark OnSecondary on emerald moved off the skeptic 6.81", 6.81, onSecondaryRatio, 0.05)

        // OnError on Error (unchanged tokens; skeptic: 7.72)
        val onErrorRatio = calculateContrastRatio(DarkOnError, DarkError)
        assertTrue("Dark OnError on Error contrast $onErrorRatio < $minRatio", onErrorRatio >= minRatio)

        // Warning on canvas (skeptic: 9.99)
        val warningRatio = calculateContrastRatio(DarkWarning, DarkSurface)
        assertTrue("Dark Warning on Surface contrast $warningRatio < $minRatio", warningRatio >= minRatio)
        assertEquals("Dark Warning on canvas moved off the skeptic 9.99", 9.99, warningRatio, 0.05)

        // Warning on card (skeptic: 8.93) — the previous gate only checked canvas.
        val warningContainerRatio = calculateContrastRatio(DarkWarning, DarkSurfaceContainer)
        assertTrue("Dark Warning on Container contrast $warningContainerRatio < $minRatio", warningContainerRatio >= minRatio)
        assertEquals("Dark Warning on card moved off the skeptic 8.93", 8.93, warningContainerRatio, 0.05)

        // Emerald on canvas (skeptic: 9.85)
        val secondaryOnCanvasRatio = calculateContrastRatio(DarkSecondary, DarkSurface)
        assertTrue("Dark Secondary on Surface contrast $secondaryOnCanvasRatio < $minRatio", secondaryOnCanvasRatio >= minRatio)
        assertEquals("Dark Secondary on canvas moved off the skeptic 9.85", 9.85, secondaryOnCanvasRatio, 0.05)

        // Emerald on card (skeptic: 8.81)
        val secondaryOnCardRatio = calculateContrastRatio(DarkSecondary, DarkSurfaceContainer)
        assertTrue("Dark Secondary on Container contrast $secondaryOnCardRatio < $minRatio", secondaryOnCardRatio >= minRatio)
        assertEquals("Dark Secondary on card moved off the skeptic 8.81", 8.81, secondaryOnCardRatio, 0.05)

        // Violet text role — body-text-strength violet #8B8AF7 on canvas (skeptic: 6.40)
        val violetTextOnCanvasRatio = calculateContrastRatio(DarkVioletText, DarkSurface)
        assertTrue("Dark VioletText on Surface contrast $violetTextOnCanvasRatio < $minRatio", violetTextOnCanvasRatio >= minRatio)
        assertEquals("DarkVioletText on canvas moved off the skeptic 6.40", 6.40, violetTextOnCanvasRatio, 0.05)

        // Violet text role on card (skeptic: 5.73)
        val violetTextOnCardRatio = calculateContrastRatio(DarkVioletText, DarkSurfaceContainer)
        assertTrue("Dark VioletText on Container contrast $violetTextOnCardRatio < $minRatio", violetTextOnCardRatio >= minRatio)
        assertEquals("DarkVioletText on card moved off the skeptic 5.73", 5.73, violetTextOnCardRatio, 0.05)

        // Code text on code surface (CHG-008 code surface #14151C; skeptic family 5.6-14.9)
        val codeRatio = calculateContrastRatio(DarkCodeOnSurface, DarkCodeSurface)
        assertTrue("Dark Code contrast $codeRatio < $minRatio", codeRatio >= minRatio)
        // CHG-008 repair round 1: plan §2.3 requires the tolerance check on every
        // recomputed pairing. The code surface moved to #14151C, so the pairing
        // recomputes to 14.53 and is pinned here like the other dark pairings.
        assertEquals("Dark code text on the code surface moved off the recomputed 14.53", 14.53, codeRatio, 0.05)

        // CHG-008 repair round 1 (P1-A): primaryText is the FOREGROUND/content
        // role for the primary hue. DarkPrimary (#5352ED) is an approved FILL
        // token (3.44 canvas / 3.07 card / 2.93 elevated) and is forbidden as a
        // small-text or icon foreground; every `colorScheme.primary` foreground
        // consumer in feature code now reads primaryText instead. The dark value
        // resolves to DarkVioletText (#8B8AF7) at 6.40 canvas / 5.73 card /
        // 5.46 elevated.
        val primaryText = DarkExtendedColors.primaryText
        val primaryTextCanvasRatio = calculateContrastRatio(primaryText, DarkSurface)
        assertTrue(
            "Dark primaryText on canvas contrast $primaryTextCanvasRatio < $minRatio",
            primaryTextCanvasRatio >= minRatio
        )
        assertEquals("Dark primaryText on canvas moved off the recomputed 6.40", 6.40, primaryTextCanvasRatio, 0.05)

        val primaryTextCardRatio = calculateContrastRatio(primaryText, DarkSurfaceContainer)
        assertTrue(
            "Dark primaryText on card contrast $primaryTextCardRatio < $minRatio",
            primaryTextCardRatio >= minRatio
        )
        assertEquals("Dark primaryText on card moved off the recomputed 5.73", 5.73, primaryTextCardRatio, 0.05)

        val primaryTextElevatedRatio = calculateContrastRatio(primaryText, DarkSurfaceElevated)
        assertTrue(
            "Dark primaryText on elevated contrast $primaryTextElevatedRatio < $minRatio",
            primaryTextElevatedRatio >= minRatio
        )
        assertEquals(
            "Dark primaryText on #1E202B moved off the recomputed 5.46",
            5.46,
            primaryTextElevatedRatio,
            0.05
        )

        // D5 glass-blend effective background. V1 glass is an 85% alpha
        // surfaceContainer fill composited over the canvas. The skeptic-verified
        // composite is exactly #181A22 and every dark text token sits in the
        // 5.62-14.89:1 band on it. The blend is computed HERE from the REAL
        // tokens (not a literal), so a token swap that breaks the glass tier
        // fails this gate.
        val fillAlpha = 0.85f
        val glassBlend = Color(
            red = DarkSurfaceContainer.red * fillAlpha + DarkSurface.red * (1f - fillAlpha),
            green = DarkSurfaceContainer.green * fillAlpha + DarkSurface.green * (1f - fillAlpha),
            blue = DarkSurfaceContainer.blue * fillAlpha + DarkSurface.blue * (1f - fillAlpha),
            alpha = 1f
        )

        // Composite identity: the computed blend must land on the skeptic's
        // #181A22 within one 8-bit channel step (1/255).
        val expectedBlend = Color(0xFF181A22)
        val channelTolerance = 1f / 255f
        assertEquals("glass blend red channel drifted off #181A22", expectedBlend.red, glassBlend.red, channelTolerance)
        assertEquals("glass blend green channel drifted off #181A22", expectedBlend.green, glassBlend.green, channelTolerance)
        assertEquals("glass blend blue channel drifted off #181A22", expectedBlend.blue, glassBlend.blue, channelTolerance)

        // Header white on the blend (skeptic band top: 17.36)
        val onSurfaceBlendRatio = calculateContrastRatio(DarkOnSurface, glassBlend)
        assertTrue("Dark OnSurface on glass blend $onSurfaceBlendRatio < $minRatio", onSurfaceBlendRatio >= minRatio)

        // Muted gray on the blend (skeptic: 5.62)
        val onSurfaceVariantBlendRatio = calculateContrastRatio(DarkOnSurfaceVariant, glassBlend)
        assertTrue("Dark OnSurfaceVariant on glass blend $onSurfaceVariantBlendRatio < $minRatio", onSurfaceVariantBlendRatio >= minRatio)
        assertEquals("Dark OnSurfaceVariant on glass blend moved off the skeptic 5.62", 5.62, onSurfaceVariantBlendRatio, 0.05)

        // Code text on the blend (skeptic band: 13.86)
        val codeBlendRatio = calculateContrastRatio(DarkCodeOnSurface, glassBlend)
        assertTrue("Dark CodeOnSurface on glass blend $codeBlendRatio < $minRatio", codeBlendRatio >= minRatio)

        // Violet text on the blend (skeptic: 5.85)
        val violetTextBlendRatio = calculateContrastRatio(DarkVioletText, glassBlend)
        assertTrue("Dark VioletText on glass blend $violetTextBlendRatio < $minRatio", violetTextBlendRatio >= minRatio)
        assertEquals("DarkVioletText on glass blend moved off the skeptic 5.85", 5.85, violetTextBlendRatio, 0.05)

        // CHG-008 repair round 1: the primaryText foreground role resolves to the
        // same dark value, so pin it on the composite too — the glass composer is
        // the S2 consumer and must not inherit a sub-4.5 foreground.
        val primaryTextBlendRatio = calculateContrastRatio(DarkExtendedColors.primaryText, glassBlend)
        assertTrue("Dark primaryText on glass blend $primaryTextBlendRatio < $minRatio", primaryTextBlendRatio >= minRatio)
        assertEquals("Dark primaryText on glass blend moved off the recomputed 5.85", 5.85, primaryTextBlendRatio, 0.05)

        // D3 regression tripwire: white on amber #FFAA2C is 1.90:1 — it fails AA
        // and MUST NOT be used as a fill/foreground pair. This is deliberately a
        // failing-pairing tripwire, not a passing gate: if this assertion ever
        // stops holding, the amber token moved and D3 must be revalidated.
        val whiteOnAmber = calculateContrastRatio(Color.White, DarkWarning)
        assertTrue("white on amber must stay below 3.0 (skeptic 1.90); got $whiteOnAmber", whiteOnAmber < 3.0)
        assertEquals("white on amber moved off the skeptic 1.90", 1.90, whiteOnAmber, 0.05)
    }

    @Test
    fun darkTheme_violetRoles_respectLargeTextBoundary() {
        // #5352ED on canvas is 3.44:1 — passes the >=3:1 UI-component threshold
        // (WCAG 1.4.11) and fails the 4.5:1 body-text threshold. This test pins
        // BOTH facts so a future token change cannot silently cross either line.
        val violetOnCanvas = calculateContrastRatio(DarkPrimary, DarkSurface)
        assertTrue("violet UI role $violetOnCanvas < 3.0", violetOnCanvas >= 3.0)
        assertTrue(
            "violet must not be promoted to body text; $violetOnCanvas >= 4.5 means the D4 boundary moved — revalidate",
            violetOnCanvas < 4.5
        )

        // CHG-008 repair round 1 (P1-A): the fill token is ALSO below the 4.5:1
        // text floor on the two card surfaces, so a foreground consumer of
        // `colorScheme.primary` on a card is a real regression, not a
        // theoretical one. Pin both card surfaces so the reason the
        // primaryText role exists cannot be optimised away.
        val violetOnCard = calculateContrastRatio(DarkPrimary, DarkSurfaceContainer)
        assertTrue("violet fill on card $violetOnCard < 3.0", violetOnCard >= 3.0)
        assertTrue(
            "violet fill on card is below the text floor as designed; $violetOnCard >= 4.5 means revalidate primaryText's need",
            violetOnCard < 4.5
        )

        val violetOnElevated = calculateContrastRatio(DarkPrimary, DarkSurfaceElevated)
        assertTrue(
            "violet fill on elevated $violetOnElevated must stay below the 4.5 text floor — it is a fill token",
            violetOnElevated < 4.5
        )
    }

    @Test
    fun composerSessionModelCaption_usesMutedAtelierForegroundNotPrimaryFill() {
        // CHAT-010 (frozen plan display rules): the session model caption row
        // in ChatComposer is a text foreground on the chat background (composer glass shell removed by owner decision 2026-09-26).
        // It must use HermesTheme.extendedColors.primaryText (the proven
        // >=4.5:1 foreground role; 5.85:1 on the dark canvas, already
        // pinned above), NOT colorScheme.primary (the #5352ED fill token,
        // 3.07:1 on this card — the CHG-008 P1-A defect class). The caption's
        // ratio floor is already covered by the primaryText blend gate in
        // darkTheme_darkModeColors...; this source pin holds the call site.
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")

        val composer = File(
            mainSourceDir,
            "com/hermes/bot/feature/chat/components/ChatComposer.kt"
        ).readText()

        // The caption row must exist and read the primaryText role.
        assertTrue(
            "ChatComposer must render the session model caption row",
            composer.contains("sessionModelLabel")
        )
        assertTrue(
            "session model caption must use the Atelier muted foreground checked in both modes",
            composer.contains("sessionModelLabel -> MaterialTheme.colorScheme.onSurfaceVariant") ||
                composer.contains("sessionModelLabel != null") &&
                composer.substring(composer.indexOf("sessionModelLabel != null"))
                    .contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )
        // The caption must NOT read the primary fill token as its color.
        // Scope from the caption rendering block itself (not the parameter
        // declaration) so the send button's legitimate primary container
        // fill earlier in the file is out of the checked region.
        val captionBlockStart = composer.indexOf("if (sessionModelLabel != null)")
        assertTrue(
            "caption rendering block must exist",
            captionBlockStart >= 0
        )
        val captionRegion = composer.substring(captionBlockStart)
        assertFalse(
            "session model caption must not use colorScheme.primary as its foreground (3.07:1)",
            captionRegion.contains("colorScheme.primary")
        )
    }

    @Test
    fun composerSessionModelCaption_isRightAlignedWithPinnedTypography() {
        // CHAT-011 S2 (frozen plan §Display rules + TEST-13): the caption row
        // REPLACES the CHAT-010 left-aligned block — right alignment via
        // `textAlign = TextAlign.End` on the existing fillMaxWidth() Text
        // (trailing-edge alignment, no layout restructuring; the owner asked
        // for the default on the right side). Typography, color, and
        // semantics stay exactly the CHAT-010 pins, asserted here together:
        // labelSmall, primaryText (the only >=4.5:1 primary foreground role
        // on the chat background — see the test above), single line, ellipsized,
        // "Current session model: <label>" description.
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")

        val composer = File(
            mainSourceDir,
            "com/hermes/bot/feature/chat/components/ChatComposer.kt"
        ).readText()

        val captionBlockStart = composer.indexOf("if (sessionModelLabel != null)")
        assertTrue("caption rendering block must exist", captionBlockStart >= 0)
        val captionRegion = composer.substring(captionBlockStart)

        assertTrue(
            "approved mockup right-aligns the session model caption",
            captionRegion.contains("textAlign = TextAlign.End")
        )
        assertTrue(
            "caption typography must stay pinned to labelSmall (CHAT-010 convention; caption shows the bare reasoning value after the separator per owner decision 2026-09-26)",
            captionRegion.contains("MaterialTheme.typography.labelSmall")
        )
        assertTrue(
            "caption uses the Atelier muted foreground (>=4.5:1 on canvas)",
            captionRegion.contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertTrue(
            "caption must stay single-line ellipsized",
            captionRegion.contains("maxLines = 1") && captionRegion.contains("TextOverflow.Ellipsis")
        )
        assertTrue(
            "caption semantics description must stay the CHAT-010 wording",
            captionRegion.contains("Current session model: ")
        )
    }

    @Test
    fun darkTheme_primaryText_isTheOnlyPrimaryForegroundRole() {
        // CHG-008 repair round 1 (P1-A): every `colorScheme.primary` FOREGROUND
        // consumer in feature code (streaming labels, markdown bullets and
        // ordered markers, task-list glyphs, code-block copy label, tool-status
        // spinner, state-banner badge) now reads the primaryText role instead.
        // This test pins that the role is >= 4.5:1 on each REAL background those
        // sites paint on, so no future palette edit can silently re-break them.
        val minRatio = 4.5
        val primaryText = DarkExtendedColors.primaryText

        // Canvas-backed sites and card-backed sites.
        val backgrounds = mapOf(
            "canvas #0F1015" to DarkSurface,
            "card #1A1C24" to DarkSurfaceContainer,
            "elevated #1E202B" to DarkSurfaceElevated
        )
        backgrounds.forEach { (label, background) ->
            val ratio = calculateContrastRatio(primaryText, background)
            assertTrue("primaryText on $label contrast $ratio < $minRatio", ratio >= minRatio)
        }

        // The light role must be a different value, and must keep the light
        // theme's current appearance (CON-006): the light primaryText is the
        // existing LightPrimary hex.
        assertEquals("Light primaryText must equal LightPrimary (CON-006)", LightPrimary, LightExtendedColors.primaryText)
        assertTrue(
            "Dark primaryText must NOT be the dark fill token; the fill is 3.07:1 on card",
            primaryText != DarkPrimary
        )
    }

    @Test
    fun darkTheme_iconAndIndicatorRoles_meetThreeToOne() {
        // CHG-008 repair round 1 (P1-B): GenericGatewayCard's info icon was
        // tinted `colorScheme.outline`, which the dark refresh turned into the
        // DECORATIVE hairline #454B60 (1.97:1 on card). Icons are graphical
        // objects and need >= 3:1 (WCAG 1.4.11). The icon now shares the
        // onSurfaceVariant tint of the text it sits beside.
        val minIconRatio = 3.0
        val darkIconRatio = calculateContrastRatio(DarkOnSurfaceVariant, DarkSurfaceContainer)
        assertTrue("Dark info-icon tint on card $darkIconRatio < $minIconRatio", darkIconRatio >= minIconRatio)
        assertEquals("Dark icon tint on card moved off the recomputed 5.50", 5.50, darkIconRatio, 0.05)

        val lightIconRatio = calculateContrastRatio(LightOnSurfaceVariant, LightSurfaceContainer)
        assertTrue("Light info-icon tint on card $lightIconRatio < $minIconRatio", lightIconRatio >= minIconRatio)
        assertEquals("Light icon tint on card moved off the recomputed 6.78", 6.78, lightIconRatio, 0.05)

        // The decorative outline must stay below the graphical-object floor —
        // that is exactly why it is not a valid icon tint (D7 ruling).
        val outlineOnCard = calculateContrastRatio(DarkOutline, DarkSurfaceContainer)
        assertTrue(
            "decorative outline on card $outlineOnCard must stay below $minIconRatio — it must never carry meaning (D7)",
            outlineOnCard < minIconRatio
        )

        // ToolActivityCard's RUNNING spinner is a graphical object too; it now
        // uses primaryText, so re-pin the elevated/card surface it paints on.
        val runningIndicatorRatio = calculateContrastRatio(DarkExtendedColors.primaryText, DarkSurfaceElevated)
        assertTrue(
            "Dark RUNNING indicator on elevated $runningIndicatorRatio < $minIconRatio",
            runningIndicatorRatio >= minIconRatio
        )
    }

    /**
     * CHG-008 repair round 2: the resolved-scheme gate for the sites that sit on
     * UNWIRED M3 roles.
     *
     * Round 1 declared these sites an unfixable "residual" and guarded them with
     * `assertTrue(ratio < 4.5)` — guards that could never fire, because the
     * companion `assertEquals(..., 0.05)` pin already fixed the value. That is
     * exactly backwards for a fixable site, and all four turned out to be
     * fixable in-scope (see the two methods below). What replaces it is a real
     * gate that reads the ACTUAL schemes out of `Theme.kt` by reflection, so the
     * assertion is made against the colours the app really resolves — not
     * against a hand-copied hex that can drift from `Theme.kt` unnoticed.
     */
    private fun resolvedScheme(field: String): ColorScheme {
        val f = Class.forName("com.hermes.bot.design.ThemeKt").getDeclaredField(field)
        f.isAccessible = true
        return f.get(null) as ColorScheme
    }

    private fun darkScheme() = resolvedScheme("DarkColorScheme")

    private fun lightScheme() = resolvedScheme("LightColorScheme")

    /** Opaque composite of [fg] at [alpha] over [bg] (both assumed opaque). */
    private fun compositeOver(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1f - alpha),
        green = fg.green * alpha + bg.green * (1f - alpha),
        blue = fg.blue * alpha + bg.blue * (1f - alpha),
        alpha = 1f
    )

    @Test
    fun resolvedSchemes_wireTheFormerUnwiredRoles_withComputedProductTokens() {
        // Polish PR A (theme-wire-unwired-roles): surfaceContainerHighest,
        // primaryContainer/onPrimaryContainer and surfaceVariant are now WIRED
        // product tokens in Color.kt, both themes. The old M3-baseline tripwire
        // is INVERTED into a pin on the computed product values: if any of
        // these roles drifts back to an M3 baseline (or to any other value),
        // this fails and the consumer sites must be re-measured. This is an
        // update of a tripwire to reflect a now-wired token — not a weakening:
        // each role still has a pin, and the ratio gates below keep the floors.
        val dark = darkScheme()
        val light = lightScheme()

        assertEquals(
            "dark surfaceContainerHighest drifted off the product token #232532",
            Color(0xFF232532),
            dark.surfaceContainerHighest
        )
        assertEquals(
            "light surfaceContainerHighest drifted off the product token #F2F5FA",
            Color(0xFFF2F5FA),
            light.surfaceContainerHighest
        )
        assertEquals(
            "dark surfaceVariant drifted off the product token #171922",
            Color(0xFF171922),
            dark.surfaceVariant
        )
        assertEquals(
            "light surfaceVariant drifted off the product token #E7EBF2",
            Color(0xFFE7EBF2),
            light.surfaceVariant
        )
        assertEquals(
            "dark primaryContainer drifted off the product token #202040",
            Color(0xFF202040),
            dark.primaryContainer
        )
        assertEquals(
            "dark onPrimaryContainer drifted off the product token #EAE7FF",
            Color(0xFFEAE7FF),
            dark.onPrimaryContainer
        )
        assertEquals(
            "light primaryContainer drifted off the product token #EAEAFF",
            Color(0xFFEAEAFF),
            light.primaryContainer
        )
        assertEquals(
            "light onPrimaryContainer drifted off the product token #10103F",
            Color(0xFF10103F),
            light.onPrimaryContainer
        )
        // errorContainer remains unwired by design (no product token exists for
        // it; the banner composite role is gated separately). Keep the old
        // baseline tripwire so a surprise wiring here still fails loudly and
        // forces re-measurement.
        assertEquals(
            "dark errorContainer is no longer the M3 baseline #8C1D18 — the role was wired; re-measure the banner composite",
            Color(0xFF8C1D18),
            dark.errorContainer
        )
        assertEquals(
            "light errorContainer is no longer the M3 baseline #F9DEDC — the role was wired; re-measure the banner composite",
            Color(0xFFF9DEDC),
            light.errorContainer
        )
    }

    @Test
    fun surfaceContainerHighest_meetsFloorsInBothThemesOnTheResolvedRole() {
        // Polish PR A: the role is now the wired product token
        // (dark #232532 / light #F2F5FA). Gate EVERY foreground role the
        // consumers actually use on it, in BOTH themes:
        //   - CodeBlock code body, truncation label, "Show more", the
        //     ToolActivityCard duration chip and the ChatScreen connection
        //     notice all read onSurface -> text floor;
        //   - onSurfaceVariant is M3's OutlinedButton/label default and any
        //     icon tint on this tier -> both the 4.5 text floor and the 3.0
        //     graphical floor;
        //   - primaryText (the violet foreground role) must also clear both
        //     floors in case a primary-hued foreground lands here.
        val minTextRatio = 4.5
        val minIconRatio = 3.0
        val dark = darkScheme()
        val light = lightScheme()

        val darkOnSurface = calculateContrastRatio(dark.onSurface, dark.surfaceContainerHighest)
        assertTrue("dark onSurface on surfaceContainerHighest $darkOnSurface < $minTextRatio", darkOnSurface >= minTextRatio)
        assertEquals("dark onSurface on #232532 moved off the computed 15.19", 15.19, darkOnSurface, 0.05)

        val lightOnSurface = calculateContrastRatio(light.onSurface, light.surfaceContainerHighest)
        assertTrue("light onSurface on surfaceContainerHighest $lightOnSurface < $minTextRatio", lightOnSurface >= minTextRatio)
        assertEquals("light onSurface on #F2F5FA moved off the computed 15.06", 15.06, lightOnSurface, 0.05)

        val darkOnSurfaceVariant = calculateContrastRatio(dark.onSurfaceVariant, dark.surfaceContainerHighest)
        assertTrue("dark onSurfaceVariant on surfaceContainerHighest $darkOnSurfaceVariant < $minTextRatio", darkOnSurfaceVariant >= minTextRatio)
        assertEquals("dark onSurfaceVariant on #232532 moved off the computed 4.92", 4.92, darkOnSurfaceVariant, 0.05)

        val lightOnSurfaceVariant = calculateContrastRatio(light.onSurfaceVariant, light.surfaceContainerHighest)
        assertTrue("light onSurfaceVariant on surfaceContainerHighest $lightOnSurfaceVariant < $minTextRatio", lightOnSurfaceVariant >= minTextRatio)
        assertEquals("light onSurfaceVariant on #F2F5FA moved off the computed 6.98", 6.98, lightOnSurfaceVariant, 0.05)

        val darkPrimaryText = calculateContrastRatio(DarkExtendedColors.primaryText, dark.surfaceContainerHighest)
        assertTrue("dark primaryText on surfaceContainerHighest $darkPrimaryText < $minTextRatio", darkPrimaryText >= minTextRatio)
        assertEquals("dark primaryText on #232532 moved off the computed 5.12", 5.12, darkPrimaryText, 0.05)

        val lightPrimaryText = calculateContrastRatio(LightExtendedColors.primaryText, light.surfaceContainerHighest)
        assertTrue("light primaryText on surfaceContainerHighest $lightPrimaryText < $minTextRatio", lightPrimaryText >= minTextRatio)
        assertEquals("light primaryText on #F2F5FA moved off the computed 4.95", 4.95, lightPrimaryText, 0.05)

        // Graphical floors (icons/spinners tinted on this container).
        assertTrue("dark onSurfaceVariant on #232532 below the 3.0 graphical floor", darkOnSurfaceVariant >= minIconRatio)
        assertTrue("light onSurfaceVariant on #F2F5FA below the 3.0 graphical floor", lightOnSurfaceVariant >= minIconRatio)
    }

    @Test
    fun surfaceVariant_meetsFloorsInBothThemesOnTheResolvedRole() {
        // Polish PR A: the SessionsScreen LocalCacheFailure banner's
        // `surfaceVariant` is now the wired product token
        // (dark #171922 / light #E7EBF2). The repair-round-2 pairing
        // (onSurface content) still clears AA against the NEW values, and the
        // old failing pair (onSurfaceVariant) now PASSES in both themes —
        // the round-2 "must stay below 4.5" defect tripwire is superseded by
        // this positive gate (the token moved, so the old baseline math no
        // longer describes reality).
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        val darkAfter = calculateContrastRatio(dark.onSurface, dark.surfaceVariant)
        assertTrue("dark onSurface on surfaceVariant $darkAfter < $minRatio", darkAfter >= minRatio)
        assertEquals("dark onSurface on #171922 moved off the computed 17.52", 17.52, darkAfter, 0.05)

        val lightAfter = calculateContrastRatio(light.onSurface, light.surfaceVariant)
        assertTrue("light onSurface on surfaceVariant $lightAfter < $minRatio", lightAfter >= minRatio)
        assertEquals("light onSurface on #E7EBF2 moved off the computed 13.76", 13.76, lightAfter, 0.05)

        val darkVariant = calculateContrastRatio(dark.onSurfaceVariant, dark.surfaceVariant)
        assertTrue("dark onSurfaceVariant on surfaceVariant $darkVariant < $minRatio", darkVariant >= minRatio)
        assertEquals("dark onSurfaceVariant on #171922 moved off the computed 5.67", 5.67, darkVariant, 0.05)

        val lightVariant = calculateContrastRatio(light.onSurfaceVariant, light.surfaceVariant)
        assertTrue("light onSurfaceVariant on surfaceVariant $lightVariant < $minRatio", lightVariant >= minRatio)
        assertEquals("light onSurfaceVariant on #E7EBF2 moved off the computed 6.37", 6.37, lightVariant, 0.05)
    }

    @Test
    fun primaryContainer_meetsFloorsInBothThemesOnTheResolvedRole() {
        // Polish PR A: the UserMessageBubble fill `primaryContainer` is now
        // the wired product token (dark #202040 + #EAE7FF on*; light #EAEAFF
        // + #10103F on*). Gates every tier the bubble actually renders:
        //   - full-opacity onPrimaryContainer (body text, 4.5 floor);
        //   - the 0.7-alpha status-label tier ("Sending...", "Sent",
        //     "Sending" spinner — text + graphical objects);
        //   - the error icon/label the UNKNOWN state paints on the bubble.
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        val darkFull = calculateContrastRatio(dark.onPrimaryContainer, dark.primaryContainer)
        assertTrue("dark onPrimaryContainer on primaryContainer $darkFull < $minRatio", darkFull >= minRatio)
        assertEquals("dark onPrimaryContainer on #202040 moved off the computed 12.94", 12.94, darkFull, 0.05)

        val lightFull = calculateContrastRatio(light.onPrimaryContainer, light.primaryContainer)
        assertTrue("light onPrimaryContainer on primaryContainer $lightFull < $minRatio", lightFull >= minRatio)
        assertEquals("light onPrimaryContainer on #EAEAFF moved off the computed 15.17", 15.17, lightFull, 0.05)

        // The 0.7-alpha status tier (text AND the spinner graphical object).
        val darkStatus = calculateContrastRatio(compositeOver(dark.onPrimaryContainer, 0.7f, dark.primaryContainer), dark.primaryContainer)
        assertTrue("dark 0.7-alpha status label on primaryContainer $darkStatus < $minRatio", darkStatus >= minRatio)
        assertEquals("dark 0.7-alpha on #202040 moved off the computed 7.02", 7.02, darkStatus, 0.05)

        val lightStatus = calculateContrastRatio(compositeOver(light.onPrimaryContainer, 0.7f, light.primaryContainer), light.primaryContainer)
        assertTrue("light 0.7-alpha status label on primaryContainer $lightStatus < $minRatio", lightStatus >= minRatio)
        assertEquals("light 0.7-alpha on #EAEAFF moved off the computed 6.32", 6.32, lightStatus, 0.05)

        // UNKNOWN-state error content on the bubble fill.
        val darkError = calculateContrastRatio(dark.error, dark.primaryContainer)
        assertTrue("dark error on primaryContainer $darkError < $minRatio", darkError >= minRatio)
        assertEquals("dark error on #202040 moved off the computed 9.22", 9.22, darkError, 0.05)

        val lightError = calculateContrastRatio(light.error, light.primaryContainer)
        assertTrue("light error on primaryContainer $lightError < $minRatio", lightError >= minRatio)
        assertEquals("light error on #EAEAFF moved off the computed 5.54", 5.54, lightError, 0.05)
    }

    @Test
    fun userMessageBubbleStatusLabels_meetAaInBothThemesOnResolvedPrimaryContainer() {
        // Polish PR A: the bubble's status labels sit under alpha. "Sending..."
        // was 0.7 (passes on the new token) but the 'Sent' label was 0.6,
        // which computes to 4.55:1 in LIGHT on the wired #EAEAFF — below AA.
        // Fix: alpha raised to 0.7 (6.32:1 light / 7.02:1 dark). This source
        // scan pins the file so the 0.6 tier cannot silently return.
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")
        val bubble = File(mainSourceDir, "com/hermes/bot/feature/chat/components/UserMessageBubble.kt").readText()

        assertFalse(
            "UserMessageBubble 'Sent' label must not use the 0.6-alpha tier (4.55:1 light on wired #EAEAFF — below AA)",
            bubble.contains("onPrimaryContainer.copy(alpha = 0.6f)")
        )
        assertTrue(
            "UserMessageBubble 'Sent' label must use the 0.7-alpha tier (6.32:1 light / 7.02:1 dark on the wired primaryContainer)",
            bubble.contains("color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)")
        )
    }

    @Test
    fun surfaceContainerHighestSites_meetAaInBothThemesOnTheResolvedRole() {
        // Polish PR A (theme-wire-unwired-roles): the three sites now sit on
        // the WIRED product token (dark #232532 / light #F2F5FA) instead of the
        // old unwired M3 baselines (#36343B / #E6E0E9). The onSurface pairing
        // each site uses recomputes to 15.19 dark / 15.06 light; the old
        // 12.27 / 12.69 pins described the now-retired baselines.
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        val darkRatio = calculateContrastRatio(dark.onSurface, dark.surfaceContainerHighest)
        assertTrue(
            "dark onSurface on surfaceContainerHighest ${dark.surfaceContainerHighest} is $darkRatio < $minRatio",
            darkRatio >= minRatio
        )
        assertEquals("dark onSurface on #232532 moved off the computed 15.19", 15.19, darkRatio, 0.05)

        val lightRatio = calculateContrastRatio(light.onSurface, light.surfaceContainerHighest)
        assertTrue(
            "light onSurface on surfaceContainerHighest ${light.surfaceContainerHighest} is $lightRatio < $minRatio",
            lightRatio >= minRatio
        )
        assertEquals("light onSurface on #F2F5FA moved off the computed 15.06", 15.06, lightRatio, 0.05)

        // Both themes must clear the floor — an assertion that only holds in one
        // theme is precisely the round-1 failure mode this replaces.
        listOf("dark" to darkRatio, "light" to lightRatio).forEach { (theme, ratio) ->
            assertTrue("$theme onSurface on surfaceContainerHighest $ratio < $minRatio", ratio >= minRatio)
        }
    }

    @Test
    fun userMessageBubbleCheckHistoryButton_meetsAaInBothThemesOnItsRealBackground() {
        // CHG-008 repair round 2 (P1). Round 1 set the "Check history"
        // OutlinedButton's contentColor from the premise that it sits on the
        // bubble's `primaryContainer`. It does not: the bubble's Surface closes
        // before the UNKNOWN decision banner opens, and the button is a child of
        // that banner, whose colour is `errorContainer` at 20% alpha over the
        // canvas. The background is therefore the composite below.
        //
        // The principled pairing on an error container is its own `on*` role,
        // `onErrorContainer` — the same role the app already uses for text on
        // error containers elsewhere (SessionsScreen, AssistantMessageBubble,
        // ConnectScreen). This asserts BOTH the real background identity and the
        // two ratios, so neither the premise nor the ratio can drift again.
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        // Background identity: the banner composite, derived from the RESOLVED
        // errorContainer over the RESOLVED canvas.
        val darkBanner = compositeOver(dark.errorContainer, 0.20f, dark.background)
        val lightBanner = compositeOver(light.errorContainer, 0.20f, light.background)
        assertEquals("dark banner composite drifted off #281316", 0x28 / 255f, darkBanner.red, 1f / 255f)
        assertEquals("light banner composite drifted off #F7F4F6", 0xF7 / 255f, lightBanner.red, 1f / 255f)

        val darkRatio = calculateContrastRatio(dark.onErrorContainer, darkBanner)
        assertTrue(
            "dark onErrorContainer on the banner composite is $darkRatio < $minRatio",
            darkRatio >= minRatio
        )
        assertEquals("dark onErrorContainer on the banner moved off the measured 13.82", 13.82, darkRatio, 0.05)

        val lightRatio = calculateContrastRatio(light.onErrorContainer, lightBanner)
        assertTrue(
            "light onErrorContainer on the banner composite is $lightRatio < $minRatio",
            lightRatio >= minRatio
        )
        assertEquals("light onErrorContainer on the banner moved off the measured 14.88", 14.88, lightRatio, 0.05)

        // The round-1 values are now on record as measured, NOT assumed: they
        // were chosen for the wrong background. On the CLAIMED unwired M3
        // baseline they measured 3.14 dark / 4.20 light — which is why the
        // premise, not just the colour, had to be corrected. Polish PR A has
        // since WIRED primaryContainer to product tokens (#202040 / #EAEAFF),
        // so the scheme-based tripwire pins are updated to the new computed
        // values: the primaryText foreground role now measures 5.27 dark /
        // 4.57 light on the wired container. (The button still uses
        // onErrorContainer against its real banner composite, asserted above.)
        val wiredDarkPrimaryContainerRatio = calculateContrastRatio(DarkExtendedColors.primaryText, dark.primaryContainer)
        assertEquals(
            "primaryText on the WIRED dark primaryContainer #202040 moved off the computed 5.27",
            5.27,
            wiredDarkPrimaryContainerRatio,
            0.05
        )
        val wiredLightPrimaryContainerRatio = calculateContrastRatio(LightExtendedColors.primaryText, light.primaryContainer)
        assertEquals(
            "primaryText on the WIRED light primaryContainer #EAEAFF moved off the computed 4.57",
            4.57,
            wiredLightPrimaryContainerRatio,
            0.05
        )
    }

    @Test
    fun unwiredRoleSites_useTheMatchingOnRoleNotAnAssumedToken() {
        // CHG-008 repair round 2: guard the CLASS of defect that produced both
        // round-1 findings, not just the two instances.
        //
        // A token-pairing assertion is NOT sufficient here: it stays green while
        // a call site paints the wrong colour (exactly how round 1 shipped a
        // regression past a green gate). So the four fixed SITES are pinned by
        // source scan as well as by ratio. A small-text/label colour on an
        // unwired M3 container must be that container's own `on*` role (or
        // onSurface), never a token picked against some other background.
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")

        fun source(relPath: String) = File(mainSourceDir, relPath).readText()

        val bubble = source("com/hermes/bot/feature/chat/components/UserMessageBubble.kt")
        assertTrue(
            "UserMessageBubble 'Check history' must set contentColor from colorScheme.onErrorContainer (its real background's matching on* role)",
            bubble.contains("contentColor = MaterialTheme.colorScheme.onErrorContainer")
        )
        assertFalse(
            "UserMessageBubble must not tint that button from an assumed-background token (the round-1 primaryText premise was wrong)",
            bubble.contains("contentColor = HermesTheme.extendedColors.primaryText")
        )

        val codeBlock = source("com/hermes/bot/feature/chat/markdown/CodeBlock.kt")
        assertTrue(
            "CodeBlock 'Show more' must set contentColor from colorScheme.onSurface (its surfaceContainerHighest background's matching on* role)",
            codeBlock.contains("contentColor = MaterialTheme.colorScheme.onSurface")
        )
        assertFalse(
            "CodeBlock 'Show more' must not use primaryText on surfaceContainerHighest (4.14:1 dark / 4.18:1 light — both fail)",
            codeBlock.contains("contentColor = HermesTheme.extendedColors.primaryText")
        )
        // The truncation label sits on the same unwired role. Scope the check to
        // the CODE_TRUNCATED_LABEL block so the header's language tag (which
        // paints on the WIRED surfaceContainerHigh) is not falsely implicated.
        val truncationBlock = codeBlock.substringAfter("if (bounded.truncated)")
            .substringBefore("// User-initiated bounded expansion")
        assertTrue(
            "CodeBlock truncation label must read colorScheme.onSurface (the matching surface-role pairing this PR wired)",
            truncationBlock.contains("color = MaterialTheme.colorScheme.onSurface")
        )
        assertFalse(
            "CodeBlock truncation label must keep the onSurface pairing pin (the site pairing policy stays)",
            truncationBlock.contains("color = MaterialTheme.colorScheme.onSurfaceVariant")
        )

        val toolCard = source("com/hermes/bot/feature/chat/components/ToolActivityCard.kt")
        val durationChipBlock = toolCard.substringAfter("activity.durationSeconds != null")
            .substringBefore("IconButton(")
        assertTrue(
            "ToolActivityCard duration chip must read colorScheme.onSurface (the matching surface-role pairing this PR wired)",
            durationChipBlock.contains("color = MaterialTheme.colorScheme.onSurface")
        )
        assertFalse(
            "ToolActivityCard duration chip must keep the onSurface pairing pin (the site pairing policy stays)",
            durationChipBlock.contains("color = MaterialTheme.colorScheme.onSurfaceVariant")
        )
    }

    @Test
    fun themeRefreshDarkRegressions_reasoningCardIconAndText_meetFloorsOnResolvedScheme() {
        // CHG-008 follow-up (theme-refresh dark regression, coordinator-verified):
        // ReasoningCard paints on `surfaceContainer` and used to tint its info
        // icon `outline` (dark #454B60 = 1.97:1 on the card — the decorative
        // hairline, below the 3:1 graphical-object floor) under a blanket
        // `Modifier.alpha(0.7f)` that ALSO diluted the label, the expand chevron
        // and the expanded body text to 3.40:1 dark / 3.38:1 light — below the
        // 4.5:1 text floor. The fix removes the blanket alpha (every child
        // carries meaning; nothing in this card is decoration) and shares the
        // onSurfaceVariant role across icon + text, like GenericGatewayCard.
        //
        // GATED so neither site can regress again:
        //  1. ratio gates on the RESOLVED schemes for both themes;
        //  2. a source scan pins the exact tint/alpha state of the file.
        val minTextRatio = 4.5
        val minIconRatio = 3.0
        val dark = darkScheme()
        val light = lightScheme()

        // Icon tint = onSurfaceVariant on the card — both themes, both floors.
        val darkIcon = calculateContrastRatio(dark.onSurfaceVariant, dark.surfaceContainer)
        assertTrue("dark ReasoningCard icon on card $darkIcon < $minIconRatio", darkIcon >= minIconRatio)
        assertEquals("dark ReasoningCard icon moved off the measured 5.50", 5.50, darkIcon, 0.05)

        val lightIcon = calculateContrastRatio(light.onSurfaceVariant, light.surfaceContainer)
        assertTrue("light ReasoningCard icon on card $lightIcon < $minIconRatio", lightIcon >= minIconRatio)
        assertEquals("light ReasoningCard icon moved off the measured 6.78", 6.78, lightIcon, 0.05)

        // Label + expanded body text = onSurfaceVariant on the card: text floor.
        // (Same role/pair as the icon, asserted at the stricter 4.5 floor.)
        listOf("dark" to darkIcon, "light" to lightIcon).forEach { (theme, ratio) ->
            assertTrue("$theme ReasoningCard text on card $ratio < $minTextRatio", ratio >= minTextRatio)
        }

        // Alpha-dilution tripwire: the composite that made this site fail is
        // pinned. Under the blanket 0.7 alpha the pairing composites to 3.40
        // dark / 3.38 light — below the 4.5:1 TEXT floor in both themes (the
        // label and the expanded body text sit under the alpha). Any
        // reintroduction of the blanket alpha re-fails this gate.
        val darkDiluted = compositeOver(dark.onSurfaceVariant, 0.7f, dark.surfaceContainer)
        val darkDilutedRatio = calculateContrastRatio(darkDiluted, dark.surfaceContainer)
        assertEquals("dark 0.7-alpha composite moved off the measured 3.40", 3.40, darkDilutedRatio, 0.05)
        assertTrue(
            "the 0.7-alpha composite must stay below the 4.5 text floor (3.40 measured) — it is the exact defect this fix removes",
            darkDilutedRatio < minTextRatio
        )
        val lightDiluted = compositeOver(light.onSurfaceVariant, 0.7f, light.surfaceContainer)
        val lightDilutedRatio = calculateContrastRatio(lightDiluted, light.surfaceContainer)
        assertEquals("light 0.7-alpha composite moved off the measured 3.38", 3.38, lightDilutedRatio, 0.05)
        assertTrue(
            "light 0.7-alpha composite must stay below the text floor (3.38 measured)",
            lightDilutedRatio < minTextRatio
        )

        // The decorative outline must never return as a meaningful-content tint:
        // on card it is below the 3:1 graphical-object floor in dark.
        val outlineOnCard = calculateContrastRatio(dark.outline, dark.surfaceContainer)
        assertTrue(
            "dark outline on card $outlineOnCard must stay below $minIconRatio — never a meaningful-content tint (D7)",
            outlineOnCard < minIconRatio
        )

        // Source scan: pins the actual site state (a token-pair gate alone
        // stays green while a call site keeps painting the wrong colour).
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")
        val reasoningCard = File(
            mainSourceDir,
            "com/hermes/bot/feature/chat/components/ReasoningCard.kt"
        ).readText()
        assertFalse(
            "ReasoningCard must not tint the info icon from colorScheme.outline (1.97:1 dark on card — decorative role, D7)",
            reasoningCard.contains("tint = MaterialTheme.colorScheme.outline")
        )
        assertTrue(
            "ReasoningCard info icon must share the onSurfaceVariant role of the text beside it",
            reasoningCard.contains("tint = MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertFalse(
            "ReasoningCard must not carry a blanket Modifier.alpha over its content (0.7 alpha composites to 3.40 dark / 3.38 light — below both floors)",
            Regex("""Column\s*\(\s*modifier\s*=\s*Modifier\.alpha""").containsMatchIn(reasoningCard)
        )
    }

    @Test
    fun themeRefreshDarkRegressions_localCacheErrorBanner_meetsAaOnResolvedSurfaceVariant() {
        // Polish PR A (theme-wire-unwired-roles): `surfaceVariant` is now the
        // WIRED product token (dark #171922 / light #E7EBF2) instead of the
        // unwired M3 1.4.0 baselines (#49454F / #E7E0EC). The repair-round-2
        // pairing (onSurface content, from the class defect where dark
        // onSurfaceVariant was 3.02:1 on the old baseline) recomputes to
        // 17.52 dark / 13.76 light against the new values — still gated. The
        // onSurfaceVariant pair itself now also passes (5.67 / 6.37), so the
        // old "must stay below 4.5" defect tripwire (3.02 on the retired
        // baseline) is superseded by the positive gate in
        // surfaceVariant_meetsFloorsInBothThemesOnTheResolvedRole; the
        // pairing-pin policy the site still follows (onSurface on a surface
        // role) is kept as a source scan.
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        // Container identity: the wired product tokens.
        assertEquals("dark surfaceVariant drifted off the product token #171922", Color(0xFF171922), dark.surfaceVariant)
        assertEquals("light surfaceVariant drifted off the product token #E7EBF2", Color(0xFFE7EBF2), light.surfaceVariant)

        // AFTER (the fix): onSurface on surfaceVariant, both themes, new tokens.
        val darkAfter = calculateContrastRatio(dark.onSurface, dark.surfaceVariant)
        assertTrue("dark onSurface on surfaceVariant $darkAfter < $minRatio", darkAfter >= minRatio)
        assertEquals("dark onSurface on #171922 moved off the computed 17.52", 17.52, darkAfter, 0.05)

        val lightAfter = calculateContrastRatio(light.onSurface, light.surfaceVariant)
        assertTrue("light onSurface on surfaceVariant $lightAfter < $minRatio", lightAfter >= minRatio)
        assertEquals("light onSurface on #E7EBF2 moved off the computed 13.76", 13.76, lightAfter, 0.05)

        // Source scan: the LocalCacheFailure branch must not resolve its
        // contentColor from onSurfaceVariant against the surfaceVariant
        // container again.
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")
        val sessions = File(
            mainSourceDir,
            "com/hermes/bot/feature/sessions/SessionsScreen.kt"
        ).readText()
        val localCacheBlock = sessions
            .substringAfter("is SessionError.LocalCacheFailure -> Quadruple(")
            .substringBefore("is SessionError.NetworkFailure -> return")
        assertFalse(
            "LocalCacheFailure banner must keep its onSurface contentColor pairing pin (site policy stays; the pair itself now passes)",
            localCacheBlock.contains("MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertTrue(
            "LocalCacheFailure banner contentColor must be colorScheme.onSurface (17.52:1 dark / 13.76:1 light on the wired surfaceVariant)",
            localCacheBlock.contains("MaterialTheme.colorScheme.onSurface")
        )

        // CHG-008 repair round 1: the banner's Dismiss OutlinedButton sits on
        // the SAME surfaceVariant container, but it paints OUTSIDE the
        // Quadruple block — M3 1.4.0's OutlinedButton defaults its label to
        // onSurfaceVariant, which is the identical failing 3.02:1 dark pair.
        // The fix pins `contentColor = contentColor` (the banner's onSurface
        // Quadruple field) on the button; this scan keeps that pin from
        // silently regressing.
        val bannerBlock = sessions
            .substringAfter("fun SessionErrorBanner(")
            .substringBefore("private data class Quadruple")
        val dismissButton = bannerBlock
            .substringAfter("OutlinedButton(")
            .substringBefore("Text(\"Dismiss\")")
        assertTrue(
            "SessionErrorBanner Dismiss OutlinedButton must set explicit colors (the M3 default label would resolve onSurfaceVariant, not the pinned onSurface pairing)",
            dismissButton.contains("colors = ButtonDefaults.outlinedButtonColors(")
        )
        assertTrue(
            "SessionErrorBanner Dismiss OutlinedButton label must use the banner's onSurface contentColor (17.52:1 dark / 13.76:1 light on the wired surfaceVariant)",
            dismissButton.contains("contentColor = contentColor")
        )
    }

    @Test
    fun themePreferences_containExpectedSystemAndOverrideOptions() {
        val options = AppThemePreference.entries
        assertEquals(3, options.size)
        assertTrue(options.contains(AppThemePreference.SYSTEM))
        assertTrue(options.contains(AppThemePreference.LIGHT))
        assertTrue(options.contains(AppThemePreference.DARK))
        assertEquals("System default", AppThemePreference.SYSTEM.displayName)
        assertEquals("Light", AppThemePreference.LIGHT.displayName)
        assertEquals("Dark", AppThemePreference.DARK.displayName)
    }

    @Test
    fun bannerFamilyOutlinedButtons_useTheMatchingOnRoleNotTheM3DefaultLabel() {
        // Polish PR D+E (P-8): M3 1.4.0's OutlinedButton defaults its label to
        // `onSurfaceVariant`. On every coloured container the banner family
        // paints on, that default is a pairing the surface does not own — the
        // exact class of defect PR #62 found on the SessionErrorBanner Dismiss
        // (2.95:1 dark on the then-unwired errorContainer). The fixed SITES
        // below now set an explicit on* role; this source scan pins each one
        // (a ratio gate alone stays green while a call site reverts).
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val mainSourceDir = File(projectDir, "src/main/java")
            .takeIf { it.exists() } ?: File("app/src/main/java")

        fun source(relPath: String) = File(mainSourceDir, relPath).readText()

        val connect = source("com/hermes/bot/feature/connect/ConnectScreen.kt")
        assertTrue(
            "ConnectScreen 'Edit address' OutlinedButton must set contentColor from colorScheme.onErrorContainer (its errorContainer background's matching on* role; 2.95:1 dark on the default)",
            connect.contains("contentColor = MaterialTheme.colorScheme.onErrorContainer")
        )

        val sessions = source("com/hermes/bot/feature/sessions/SessionsScreen.kt")
        assertFalse("Sessions no longer renders an offline Reconnect card",
            sessions.contains("fun ConnectionHealthStrip("))

        val settings = source("com/hermes/bot/feature/settings/SettingsScreen.kt")
        // The redesign uses a native clickable row for app diagnostics.
        assertTrue(settings.contains("onClick = onOpenDiagnostics"))
        for (label in listOf("Clear offline cache", "Clear all local app data")) {
            // each fixed button must carry the explicit colors ABOVE its label —
            // scan backwards: the substring between the button call and the label.
            val beforeLabel = settings
                .substringBefore("Text(\"$label\")")
                .substringAfter("OutlinedButton(")
            assertTrue(
                "SettingsScreen '$label' OutlinedButton must set explicit colors (the M3 default label resolves onSurfaceVariant at 5.50:1 dark on surfaceContainer)",
                beforeLabel.contains("colors = ButtonDefaults.outlinedButtonColors(") &&
                    beforeLabel.contains("contentColor = MaterialTheme.colorScheme.onSurface")
            )
        }

        val debugLogs = source("com/hermes/bot/feature/settings/DebugLogsScreen.kt")
        val reportCopyButton = debugLogs
            .substringAfter("OutlinedButton(")
            .substringBefore("Icons.Filled.Check")
        assertTrue(
            "DebugLogsScreen report Copy OutlinedButton must use the canvas-owned onSurface role",
            reportCopyButton.contains("colors = ButtonDefaults.outlinedButtonColors(") &&
                reportCopyButton.contains("contentColor = MaterialTheme.colorScheme.onSurface") &&
                debugLogs.contains("SettingsAtelierTheme(currentTheme)")
        )
    }

    @Test
    fun bannerFamilyOutlinedButtonPairs_meetAaInBothThemesOnResolvedScheme() {
        // Polish PR D+E (P-8): the RESOLVED-scheme ratio gates for every fixed
        // site class. The before values are pinned as failing/weak tripwires so
        // a token edit that re-opens the pairing fails loudly.
        val minRatio = 4.5
        val dark = darkScheme()
        val light = lightScheme()

        // Class 1: default label on surfaceContainer cards (ConnectScreen
        // Disconnect + Diagnostics, the three
        // SettingsScreen buttons). BEFORE: onSurfaceVariant 5.50 dark / 6.78
        // light — passes but pairs a role the surface does not own. AFTER:
        // onSurface.
        val darkAfter = calculateContrastRatio(dark.onSurface, dark.surfaceContainer)
        val lightAfter = calculateContrastRatio(light.onSurface, light.surfaceContainer)
        assertTrue("dark onSurface on surfaceContainer $darkAfter < $minRatio", darkAfter >= minRatio)
        assertTrue("light onSurface on surfaceContainer $lightAfter < $minRatio", lightAfter >= minRatio)
        assertEquals("dark onSurface on surfaceContainer moved off the computed 17.00", 17.00, darkAfter, 0.05)
        assertEquals("light onSurface on surfaceContainer moved off the computed 14.63", 14.63, lightAfter, 0.05)

        // Class 2: default label on the (unwired, M3-baseline) errorContainer —
        // the ConnectScreen ORIGIN_POLICY_REJECTED 'Edit address' card. BEFORE:
        // onSurfaceVariant 2.95:1 dark / 5.99:1 light (the PR-#62 defect class
        // in dark). AFTER: the container's own on* role — 7.17:1 dark /
        // 12.77:1 light.
        assertEquals(
            "dark errorContainer is no longer the M3 baseline #8C1D18 — the role was wired; re-measure the 'Edit address' button pairing",
            Color(0xFF8C1D18),
            dark.errorContainer
        )
        assertEquals(
            "light errorContainer is no longer the M3 baseline #F9DEDC — the role was wired; re-measure the 'Edit address' button pairing",
            Color(0xFFF9DEDC),
            light.errorContainer
        )
        val darkErrorAfter = calculateContrastRatio(dark.onErrorContainer, dark.errorContainer)
        val lightErrorAfter = calculateContrastRatio(light.onErrorContainer, light.errorContainer)
        assertTrue("dark onErrorContainer on errorContainer $darkErrorAfter < $minRatio", darkErrorAfter >= minRatio)
        assertTrue("light onErrorContainer on errorContainer $lightErrorAfter < $minRatio", lightErrorAfter >= minRatio)
        assertEquals("dark onErrorContainer on #8C1D18 moved off the computed 7.17", 7.17, darkErrorAfter, 0.05)
        assertEquals("light onErrorContainer on #F9DEDC moved off the computed 12.77", 12.77, lightErrorAfter, 0.05)
        val darkBefore = calculateContrastRatio(dark.onSurfaceVariant, dark.errorContainer)
        assertEquals("dark onSurfaceVariant on #8C1D18 moved off the computed 2.95", 2.95, darkBefore, 0.05)
        assertTrue(
            "dark onSurfaceVariant on errorContainer must stay below $minRatio (2.95) — it is the defect this fix removes",
            darkBefore < minRatio
        )
    }
}
