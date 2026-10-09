package io.github.anshuman2401.hermesmobile.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Local presentation preference that survives ordinary Activity recreation.
 */
enum class AppThemePreference(val displayName: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Extended semantic tokens specified in specs/architecture/design/theme-and-icon-brief.md.
 * Supplements standard Material3 ColorScheme with domain-specific semantic colors.
 *
 * CHG-008 additions: [violetText] (body-text-strength violet, D4), [primaryText]
 * (the role-correct primary FOREGROUND token, repair round 1) and
 * [glassOutline] / [glassFill] (glassmorphism plumbing for the S2 GlassSurface).
 */
data class HermesExtendedColors(
    val warning: Color,
    val surfaceElevated: Color,
    val surfaceContainer: Color,
    val codeSurface: Color,
    val codeOnSurface: Color,
    /** CHG-008 D4: violet for body text. Dark = #8B8AF7 (6.40:1 canvas). */
    val violetText: Color,
    /**
     * CHG-008 repair round 1: the only legitimate primary-hued FOREGROUND role.
     *
     * `colorScheme.primary` is a FILL token — the approved dark value `#5352ED`
     * is 3.44:1 on canvas, 3.07:1 on card and 2.93:1 on the elevated card, so it
     * fails the 4.5:1 text floor and the 3:1 graphical-object floor. Any
     * primary-hued text, link or icon tint must use this token instead.
     *
     * Dark = `DarkVioletText` (#8B8AF7): 6.40 canvas / 5.73 card / 5.46 elevated.
     * Light = `LightPrimary`: today's light value, so the light theme's
     * appearance is unchanged (CON-006).
     */
    val primaryText: Color,
    /** True when the dark palette is active; read by consumers that cannot diff tokens. */
    val isDark: Boolean,
    /** CHG-008 D5/D7: decorative hairline outline for glass surfaces. */
    val glassOutline: Color,
    /** CHG-008 D5: glass fill base color; consumers apply their own alpha. */
    val glassFill: Color,
    /**
     * ui-reply-format-polish-v1 (plan §1): fenced-code token colors, painted
     * as SpanStyle colors on the code-block background (surfaceContainerHighest
     * tier). All eight shade/background pairings are ≥4.5:1 (computed, see
     * Color.kt); the two substituted light shades keep their hue family.
     */
    val codeKeyword: Color,
    val codeString: Color,
    val codeComment: Color,
    val codeNumber: Color
)

val LightExtendedColors = HermesExtendedColors(
    warning = LightWarning,
    surfaceElevated = LightSurfaceElevated,
    surfaceContainer = LightSurfaceContainer,
    codeSurface = LightCodeSurface,
    codeOnSurface = LightCodeOnSurface,
    // CON-006 plumbing: light theme keeps its existing appearance, so the
    // light violet-text role reuses the existing light outline hex.
    violetText = LightOutline,
    // Light primary-hued foreground keeps TODAY'S appearance exactly: the light
    // theme already rendered these sites in LightPrimary.
    primaryText = LightPrimary,
    glassOutline = LightOutline,
    glassFill = LightSurfaceContainer,
    // ui-reply-format-polish-v1: substituted light code shades (Color.kt).
    codeKeyword = LightCodeKeyword,
    codeString = LightCodeString,
    codeComment = LightCodeComment,
    codeNumber = LightCodeNumber,
    isDark = false
)

val DarkExtendedColors = HermesExtendedColors(
    warning = DarkWarning,
    surfaceElevated = DarkSurfaceElevated,
    surfaceContainer = DarkSurfaceContainer,
    codeSurface = DarkCodeSurface,
    codeOnSurface = DarkCodeOnSurface,
    violetText = DarkVioletText,
    // The dark foreground role is the body-text-strength violet. Deliberately
    // NOT DarkPrimary — see the [HermesExtendedColors.primaryText] KDoc.
    primaryText = DarkVioletText,
    glassOutline = DarkGlassOutline,
    glassFill = DarkGlassFill,
    // ui-reply-format-polish-v1: dark code shades are the plan's verbatim.
    codeKeyword = DarkCodeKeyword,
    codeString = DarkCodeString,
    codeComment = DarkCodeComment,
    codeNumber = DarkCodeNumber,
    isDark = true
)

val LocalHermesExtendedColors = staticCompositionLocalOf { LightExtendedColors }

object HermesTheme {
    val extendedColors: HermesExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHermesExtendedColors.current
}

/**
 * CHG-008 route-1 plumbing (S1): approved card corner range 16-20dp
 * (owner visual language). Declared here with ZERO consumers so the S2 (glass)
 * and S3 (shape/outline) slices touch disjoint files instead of both appending
 * to this file — see learning 2026-09-18-chg008-parallel-slices-shared-token-file.
 */
object HermesShapeTokens {
    /** Small cards, banners, strips. */
    val cardCornerSmall = 16.dp
    /** Large cards, composer, hero surfaces. */
    val cardCornerLarge = 20.dp
    /** Chat message bubbles keep their asymmetric 4dp tail corners. */
    val bubbleCorner = 16.dp
}
