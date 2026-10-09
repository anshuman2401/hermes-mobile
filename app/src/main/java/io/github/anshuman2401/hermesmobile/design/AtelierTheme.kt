package io.github.anshuman2401.hermesmobile.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.anshuman2401.hermesmobile.R

internal data class AtelierPalette(
    val canvas: Color, val panel: Color, val ink: Color, val muted: Color,
    val border: Color, val accent: Color, val connected: Color, val destructive: Color,
    val selection: Color
)

internal val AtelierLight = AtelierPalette(
    Color(0xFFF7F6F2), Color(0xFFFFFEFA), Color(0xFF252D29), Color(0xFF626B65),
    Color(0xFFE6E7DF), Color(0xFFA84021), Color(0xFF326345), Color(0xFF9B3724), Color(0xFFEDECE5)
)
internal val AtelierDark = AtelierPalette(
    Color(0xFF141716), Color(0xFF1D211F), Color(0xFFF1F3EB), Color(0xFFA3ADA5),
    Color(0xFF343C36), Color(0xFFEFAD8E), Color(0xFFACD4A0), Color(0xFFFFB19C), Color(0xFF2B322D)
)

internal val AtelierSans = FontFamily(
    Font(R.font.settings_dm_sans, FontWeight.Normal),
    Font(R.font.settings_dm_sans, FontWeight.Medium),
    Font(R.font.settings_dm_sans, FontWeight.SemiBold)
)
internal val AtelierEditorial = FontFamily(Font(R.font.settings_instrument_serif))

@Composable
internal fun AtelierTheme(preference: AppThemePreference, updateSystemBars: Boolean = true, content: @Composable () -> Unit) {
    val dark = when (preference) {
        AppThemePreference.SYSTEM -> isSystemInDarkTheme()
        AppThemePreference.LIGHT -> false
        AppThemePreference.DARK -> true
    }
    val p = if (dark) AtelierDark else AtelierLight
    val view = LocalView.current
    if (updateSystemBars) DisposableEffect(view, dark) {
        val activity = view.context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !dark
        controller?.isAppearanceLightNavigationBars = !dark
        onDispose {
            if (previousStatus != null) controller?.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null) controller?.isAppearanceLightNavigationBars = previousNavigation
        }
    }
    val colors = MaterialTheme.colorScheme.copy(
        background = p.canvas, surface = p.canvas, surfaceContainer = p.panel,
        surfaceContainerHigh = p.panel, surfaceContainerHighest = p.panel,
        onBackground = p.ink, onSurface = p.ink, onSurfaceVariant = p.muted,
        primary = p.accent, onPrimary = p.canvas, secondary = p.connected,
        outline = p.muted, outlineVariant = p.border, error = p.destructive
    )
    val typography = atelierTypography(MaterialTheme.typography)
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides p.ink, content = content)
    }
}

internal fun atelierTypography(base: androidx.compose.material3.Typography): androidx.compose.material3.Typography = base.copy(
        headlineSmall = TextStyle(fontFamily = AtelierEditorial, fontWeight = FontWeight.Normal,
            fontSize = 28.sp, lineHeight = 34.sp),
        titleMedium = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontFamily = AtelierSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
    )

@Composable
internal fun atelierSelectionColor(): Color =
    if (MaterialTheme.colorScheme.background == AtelierLight.canvas) AtelierLight.selection else AtelierDark.selection
