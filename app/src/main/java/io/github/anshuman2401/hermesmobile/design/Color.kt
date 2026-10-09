package io.github.anshuman2401.hermesmobile.design

import androidx.compose.ui.graphics.Color

// Light Theme Tokens (Theme and Icon Brief - ARCH-001)
val LightSurface = Color(0xFFF7F9FC)
val LightSurfaceContainer = Color(0xFFEEF2F7)
val LightSurfaceElevated = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF17202A)
val LightOnSurfaceVariant = Color(0xFF485563)
val LightPrimary = Color(0xFF155EEF)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFF006E6D)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightWarning = Color(0xFF9A6700)
val LightError = Color(0xFFB42318)
val LightOnError = Color(0xFFFFFFFF)
val LightOutline = Color(0xFF667484)
val LightCodeSurface = Color(0xFFE7EDF6)
val LightCodeOnSurface = Color(0xFF162235)

// Dark Theme Tokens (Theme and Icon Brief - ARCH-001)
// CHG-008 S1: deep-dark refresh — canvas #0F1015, cards #1A1C24/#1E202B,
// violet primary #5352ED with #FFFFFF onPrimary, emerald #2ED573,
// amber #FFAA2C, muted gray #8E92A4, decorative outline #454B60.
// Every pairing is gated in ThemeAndContrastTest; values are skeptic-verified
// (specs/test-evidence/chg-008/skeptic-round1.md + skeptic-round2.md).
val DarkSurface = Color(0xFF0F1015)
val DarkSurfaceContainer = Color(0xFF1A1C24)
val DarkSurfaceElevated = Color(0xFF1E202B)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkOnSurfaceVariant = Color(0xFF8E92A4)
val DarkPrimary = Color(0xFF5352ED)
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkSecondary = Color(0xFF2ED573)
val DarkOnSecondary = Color(0xFF003735)
val DarkWarning = Color(0xFFFFAA2C)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkOutline = Color(0xFF454B60)
val DarkCodeSurface = Color(0xFF14151C)
val DarkCodeOnSurface = Color(0xFFD7E7FF)

// Polish PR A (theme-wire-unwired-roles): product tokens for the three M3
// roles Theme.kt previously left unwired. Every pairing below is computed
// (WCAG 2.x relative luminance, the same math as ContrastMath.kt) and gated in
// ThemeAndContrastTest against the RESOLVED schemes, both themes.
//  - DarkSurfaceContainerHighest #232532: sits between elevated #1E202B and
//    the code-header tier; onSurface 15.19:1, onSurfaceVariant 4.92:1,
//    primaryText 5.12:1.
//  - DarkSurfaceVariant #171922: subtle elevation step between canvas
//    #0F1015 and card #1A1C24; onSurface 17.52:1, onSurfaceVariant 5.67:1,
//    primaryText 5.90:1, error 10.32:1.
//  - DarkPrimaryContainer #202040: deep violet family fill between canvas and
//    card. Its on* partner DarkOnPrimaryContainer #EAE7FF is 12.94:1 at full
//    opacity, 7.02:1 at the 0.7-alpha status-label tier and 7.86:1 at 0.75 —
//    so every alpha-diluted child of the bubble passes in dark.
//  - LightSurfaceVariant #E7EBF2: step between container #EEF2F7 and canvas;
//    onSurface 13.76:1, onSurfaceVariant 6.37:1.
//  - LightSurfaceContainerHighest #F2F5FA: step above container #EEF2F7,
//    below white elevated; onSurface 15.06:1, onSurfaceVariant 6.98:1.
//  - LightPrimaryContainer #EAEAFF: pale violet family fill; its on*
//    partner LightOnPrimaryContainer #10103F is 15.17:1 at full opacity,
//    6.32:1 at the 0.7-alpha tier and 7.49:1 at 0.75.
val DarkSurfaceContainerHighest = Color(0xFF232532)
val DarkPrimaryContainer = Color(0xFF202040)
val DarkOnPrimaryContainer = Color(0xFFEAE7FF)
val DarkSurfaceVariant = Color(0xFF171922)

val LightSurfaceContainerHighest = Color(0xFFF2F5FA)
val LightPrimaryContainer = Color(0xFFEAEAFF)
val LightOnPrimaryContainer = Color(0xFF10103F)
val LightSurfaceVariant = Color(0xFFE7EBF2)

// CHG-008: violet text role — body-text-strength violet (D4).
// #5352ED is 3.44:1 on canvas and may only carry large text/icons/UI roles;
// this value is 6.40:1 on canvas and 5.73:1 on card, so it is the token that
// body-text violet must use.
val DarkVioletText = Color(0xFF8B8AF7)

// CHG-008 route-1 plumbing (S1): glass tokens consumed by S2's GlassSurface.
// Zero consumers in S1 by design — landing them here keeps S2/S3 on disjoint
// files instead of both appending to HermesTokens.kt (see learning
// 2026-09-18-chg008-parallel-slices-shared-token-file).
val DarkGlassOutline = Color(0xFF454B60)
val DarkGlassFill = Color(0xFF1A1C24)

// ui-reply-format-polish-v1 (frozen plan §1): fenced-code token palette.
// Every pairing is computed (WCAG 2.x relative luminance, ContrastMath math)
// against the actual code-block backgrounds: body text sits on
// surfaceContainerHighest (#F2F5FA light / #232532 dark) and the header row on
// surfaceContainerHigh (#FFFFFF light / #1E202B dark); the worst case per
// shade is recorded in specs/learnings/2026-09/. Plan §1's two light shades
// failed the 4.5:1 AA floor and were substituted with the nearest passing
// shades: codeComment #607D8B (4.00:1 light body) → blueGrey 700 #455A64
// (6.63:1); codeNumber #E65100 (3.47:1 light body) → deepOrange 900 #BF360C
// (5.13:1). All dark plan shades passed and are kept verbatim.
val LightCodeKeyword = Color(0xFF6A1B9A)
val LightCodeString = Color(0xFF1B5E20)
val LightCodeComment = Color(0xFF455A64)
val LightCodeNumber = Color(0xFFBF360C)
val DarkCodeKeyword = Color(0xFFCE93D8)
val DarkCodeString = Color(0xFFA5D6A7)
val DarkCodeComment = Color(0xFF90A4AE)
val DarkCodeNumber = Color(0xFFFFB74D)
