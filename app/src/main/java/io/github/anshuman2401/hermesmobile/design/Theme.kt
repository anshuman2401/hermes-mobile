package io.github.anshuman2401.hermesmobile.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    // Polish PR A: primaryContainer/onPrimaryContainer were UNWIRED — they
    // resolved to the M3 1.4.0 baselines (#36363B/#49454F), which is why the
    // round-2 tripwire measured primaryText at 3.14:1 there. Now a computed
    // violet-family pair: 12.94:1 (onPrimaryContainer/onPrimaryContainer tier).
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    background = DarkSurface,
    surface = DarkSurface,
    // Polish PR A: the subtle elevation step below the card tier.
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceElevated,
    // Polish PR A: was UNWIRED (M3 baseline #36343B, 3.97:1 for
    // onSurfaceVariant — the class behind the 3.97 code-label incident).
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DarkError,
    onError = DarkOnError
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    // Polish PR A: computed violet-family container pair (15.17:1).
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    background = LightSurface,
    surface = LightSurface,
    // Polish PR A: the subtle elevation step below the card tier.
    surfaceVariant = LightSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceElevated,
    // Polish PR A: was UNWIRED (M3 baseline #E6E0E9).
    surfaceContainerHighest = LightSurfaceContainerHighest,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = LightError,
    onError = LightOnError
)

@Composable
fun HermesBotTheme(
    themePreference: AppThemePreference = AppThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themePreference) {
        AppThemePreference.SYSTEM -> isSystemInDarkTheme()
        AppThemePreference.LIGHT -> false
        AppThemePreference.DARK -> true
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (isDark) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalHermesExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HermesTypography,
            content = content
        )
    }
}
