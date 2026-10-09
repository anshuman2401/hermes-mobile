package com.hermes.bot.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** The approved chat mockup uses Atelier's canvas/type with an olive action accent. */
internal fun chatAtelierColors(base: ColorScheme, dark: Boolean): ColorScheme {
    val p = if (dark) AtelierDark else AtelierLight
    val olive = if (dark) Color(0xFFB9CAA5) else Color(0xFF52683D)
    val soft = if (dark) Color(0xFF2B3427) else Color(0xFFEEF1E6)
    val errorPanel = if (dark) Color(0xFF42251F) else Color(0xFFF8E6DF)
    return base.copy(
        primary = olive, onPrimary = p.canvas, primaryContainer = soft, onPrimaryContainer = p.ink,
        secondary = olive, onSecondary = p.canvas, secondaryContainer = soft, onSecondaryContainer = p.ink,
        tertiary = olive, onTertiary = p.canvas, tertiaryContainer = soft, onTertiaryContainer = p.ink,
        background = p.canvas, onBackground = p.ink, surface = p.canvas, onSurface = p.ink,
        surfaceVariant = soft, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.panel, surfaceContainerLow = p.panel,
        surfaceContainer = p.panel, surfaceContainerHigh = p.panel, surfaceContainerHighest = p.selection,
        surfaceBright = p.panel, surfaceDim = p.canvas, surfaceTint = Color.Transparent,
        outline = p.muted, outlineVariant = p.border,
        error = p.destructive, onError = p.canvas, errorContainer = errorPanel, onErrorContainer = p.ink,
        inverseSurface = p.ink, inverseOnSurface = p.canvas, inversePrimary = olive
    )
}

@Composable
internal fun ChatAtelierTheme(preference: AppThemePreference, content: @Composable () -> Unit) {
    val dark = when (preference) {
        AppThemePreference.SYSTEM -> isSystemInDarkTheme()
        AppThemePreference.LIGHT -> false
        AppThemePreference.DARK -> true
    }
    AtelierTheme(preference) {
        val colors = chatAtelierColors(MaterialTheme.colorScheme, dark)
        val base = if (dark) DarkExtendedColors else LightExtendedColors
        val extended = base.copy(
            warning = colors.error, surfaceElevated = colors.surfaceContainerHigh,
            surfaceContainer = colors.surfaceContainer, codeSurface = colors.surfaceContainerHighest,
            codeOnSurface = colors.onSurface, violetText = colors.primary, primaryText = colors.primary,
            glassOutline = colors.outlineVariant, glassFill = colors.surfaceContainer, isDark = dark,
            codeKeyword = colors.primary, codeString = colors.primary,
            codeComment = colors.onSurfaceVariant, codeNumber = colors.error
        )
        CompositionLocalProvider(LocalHermesExtendedColors provides extended) {
            MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography.copy(
                labelMedium = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            ), content = content)
        }
    }
}
