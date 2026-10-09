package io.github.anshuman2401.hermesmobile.feature.settings

import androidx.compose.runtime.Composable
import io.github.anshuman2401.hermesmobile.design.AppThemePreference
import io.github.anshuman2401.hermesmobile.design.AtelierTheme

// Compatibility names keep existing Settings/Brain presentation unchanged.
internal val AtelierLight = io.github.anshuman2401.hermesmobile.design.AtelierLight
internal val AtelierDark = io.github.anshuman2401.hermesmobile.design.AtelierDark
internal val SettingsEditorial = io.github.anshuman2401.hermesmobile.design.AtelierEditorial

@Composable
internal fun SettingsAtelierTheme(preference: AppThemePreference, content: @Composable () -> Unit) {
    AtelierTheme(preference = preference, content = content)
}
