package com.hermes.bot.design

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Shared WCAG 2.1 relative-luminance / contrast-ratio math, extracted from
 * `ThemeAndContrastTest` in CHG-008 S3 so the D7 invariant test
 * (`ShapeAndOutlineInvariantTest`) and the pairing-matrix gate
 * (`ThemeAndContrastTest`) import ONE implementation instead of duplicating it.
 * Mechanical move, zero production impact (plan §4.1 FILE C).
 *
 * Values returned here are RAW WCAG ratios — skeptic-verified numbers
 * (specs/test-evidence/chg-008/) are asserted as-is in the gate tests and must
 * never be re-derived by hand (learning
 * 2026-09-18-chg008-contrast-claims-must-be-computed).
 */
internal fun sRgbToLinear(c: Float): Double {
    return if (c <= 0.04045f) {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).pow(2.4)
    }
}

internal fun calculateRelativeLuminance(color: Color): Double {
    val r = sRgbToLinear(color.red)
    val g = sRgbToLinear(color.green)
    val b = sRgbToLinear(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

internal fun calculateContrastRatio(foreground: Color, background: Color): Double {
    val lum1 = calculateRelativeLuminance(foreground)
    val lum2 = calculateRelativeLuminance(background)
    val brightest = max(lum1, lum2)
    val darkest = min(lum1, lum2)
    return (brightest + 0.05) / (darkest + 0.05)
}
