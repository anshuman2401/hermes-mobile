package com.hermes.bot.feature.connect

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.hermes.bot.design.AppThemePreference
import com.hermes.bot.design.AtelierTheme

/** Keep connection forms and their transient states within the Atelier palette. */
internal fun connectAtelierColors(base: ColorScheme): ColorScheme = base.copy(
    primaryContainer = base.surfaceContainerHighest, onPrimaryContainer = base.onSurface,
    secondaryContainer = base.surfaceContainerHighest, onSecondaryContainer = base.onSurface,
    tertiary = base.primary, onTertiary = base.onPrimary,
    tertiaryContainer = base.surfaceContainerHighest, onTertiaryContainer = base.onSurface,
    errorContainer = base.surfaceContainer, onErrorContainer = base.onSurface,
    surfaceVariant = base.surfaceContainer, surfaceTint = Color.Transparent
)

@Composable
internal fun ConnectAtelierTheme(preference: AppThemePreference, content: @Composable () -> Unit) {
    AtelierTheme(preference) {
        val colors = connectAtelierColors(MaterialTheme.colorScheme)
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.primary, backgroundColor = colors.primary.copy(alpha = 0.2f)
        )) {
            MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, content = content)
        }
    }
}
